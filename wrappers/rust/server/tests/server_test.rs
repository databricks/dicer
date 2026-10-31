use std::convert::Infallible;
use std::error::Error;
use std::fs::write;
use std::io::{Error as IoError, ErrorKind};
use std::mem::replace;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};

use http::{Request, Response};
use raf_server::DatabricksServer;
use raf_tls::{TlsError, TlsOptions};
use raf_tls_test_identity::TestIdentity;
use raf_tls_test_tls_options::TestTlsOptions;
use tempfile::{tempdir, TempDir};
use tokio::net::TcpListener;
use tokio::sync::Notify;
use tokio::task::JoinHandle;
use tonic::body::Body;
use tonic::codegen::{BoxFuture, Context, Poll, Service};
use tonic::server::NamedService;
use tonic::transport::{Channel, ClientTlsConfig, Endpoint};
use tonic::{Code, Status};
use tonic_health::pb::health_check_response::ServingStatus;
use tonic_health::pb::health_client::HealthClient;
use tonic_health::pb::health_server::{Health, HealthServer};
use tonic_health::pb::{HealthCheckRequest, HealthCheckResponse};
use tonic_health::server::HealthReporter;

// ================================================
// Test helpers.
// ================================================

/// Wraps a gRPC service so that each request reaches it only after `delay`, standing in for a slow
/// handler without requiring a generated test service.
#[derive(Clone)]
struct DelayedService<S> {
    /// The service handling requests once the delay elapses.
    inner: S,
    /// How long each request waits before reaching `inner`.
    delay: Duration,
    /// Notified each time a request arrives, before its delay starts.
    request_arrived: Arc<Notify>,
}

impl<S> DelayedService<S> {
    /// Wraps `inner` so that each request reaches it only after `delay`.
    fn new(inner: S, delay: Duration) -> Self {
        Self {
            inner,
            delay,
            request_arrived: Arc::new(Notify::new()),
        }
    }
}

impl<S: NamedService> NamedService for DelayedService<S> {
    const NAME: &'static str = S::NAME;
}

impl<S> Service<Request<Body>> for DelayedService<S>
where
    S: Service<Request<Body>, Response = Response<Body>, Error = Infallible>
        + Clone
        + Send
        + 'static,
    S::Future: Send + 'static,
{
    type Response = Response<Body>;
    type Error = Infallible;
    type Future = BoxFuture<Self::Response, Self::Error>;

    fn poll_ready(&mut self, cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        self.inner.poll_ready(cx)
    }

    fn call(&mut self, request: Request<Body>) -> Self::Future {
        // Call the service that `poll_ready` readied, leaving a fresh clone for the next request. See
        // https://docs.rs/tower/latest/tower/trait.Service.html#be-careful-when-cloning-inner-services.
        let fresh_inner: S = self.inner.clone();
        let mut inner: S = replace(&mut self.inner, fresh_inner);
        let delay: Duration = self.delay;
        self.request_arrived.notify_one();
        Box::pin(async move {
            tokio::time::sleep(delay).await;
            inner.call(request).await
        })
    }
}

/// Returns a health service that reports the server as serving.
fn health_service() -> HealthServer<impl Health> {
    let (_, service): (HealthReporter, HealthServer<_>) = tonic_health::server::health_reporter();
    service
}

/// Connects a plaintext channel to the local server listening on `port`.
async fn connect_plaintext(port: u16) -> Channel {
    Endpoint::from_shared(format!("http://127.0.0.1:{port}"))
        .unwrap()
        .connect()
        .await
        .unwrap()
}

/// Sends a health check over `channel`, returning the reported status.
async fn check_health(channel: Channel) -> Result<ServingStatus, Status> {
    let request = HealthCheckRequest {
        service: String::new(),
    };
    let response: tonic::Response<HealthCheckResponse> =
        HealthClient::new(channel).check(request).await?;
    Ok(response.into_inner().status())
}

/// Connects to the local server on `port` over TLS and sends a health check, returning the reported
/// status or the first connection or RPC error.
async fn check_health_over_tls(
    port: u16,
    client_tls: TlsOptions,
) -> Result<ServingStatus, Box<dyn Error + Send + Sync>> {
    let endpoint: Endpoint = Endpoint::from_shared(format!("https://localhost:{port}"))?
        .tls_config(ClientTlsConfig::try_from(client_tls)?)?;
    let channel: Channel = endpoint.connect().await?;
    Ok(check_health(channel).await?)
}

// ================================================
// Server tests.
// ================================================

#[tokio::test]
async fn test_basic_plaintext() {
    // Test plan: verify that a plaintext server serves RPCs for its registered services and stops
    // cleanly.
    let server: DatabricksServer = DatabricksServer::builder()
        .set_port(0)
        .add_service(health_service())
        .start()
        .await
        .unwrap();
    let port: u16 = server.active_port().unwrap();

    let status: ServingStatus = check_health(connect_plaintext(port).await).await.unwrap();
    assert_eq!(status, ServingStatus::Serving);

    server.stop().await.unwrap();
}

#[tokio::test]
async fn test_basic_https() {
    // Test plan: verify that a server configured with TLS options serves RPCs over mTLS.
    let server: DatabricksServer = DatabricksServer::builder()
        .set_port(0)
        .set_tls_options(TestTlsOptions::server_tls_options())
        .add_service(health_service())
        .start()
        .await
        .unwrap();
    let port: u16 = server.active_port().unwrap();

    let status: ServingStatus = check_health_over_tls(port, TestTlsOptions::client_tls_options())
        .await
        .unwrap();
    assert_eq!(status, ServingStatus::Serving);

    server.stop().await.unwrap();
}

#[tokio::test]
async fn test_tls_fail_with_cert_mismatch() {
    // Test plan: verify that a TLS server rejects clients that do not trust its certificate. Do
    // this by starting a server with the test server TLS options and checking health from a client
    // that trusts only an unrelated CA.
    let dir: TempDir = tempdir().unwrap();
    let unrelated_id: TestIdentity = TestIdentity::generate("Unrelated CA");
    let unrelated_ca_path: PathBuf = dir.path().join("unrelated-ca.pem");
    write(&unrelated_ca_path, unrelated_id.ca_pem).unwrap();
    let client_tls: TlsOptions = TlsOptions::builder()
        .add_root_cert_path(unrelated_ca_path)
        .build()
        .unwrap();
    let server: DatabricksServer = DatabricksServer::builder()
        .set_port(0)
        .set_tls_options(TestTlsOptions::server_tls_options())
        .add_service(health_service())
        .start()
        .await
        .unwrap();
    let port: u16 = server.active_port().unwrap();

    let error: Box<dyn Error + Send + Sync> =
        check_health_over_tls(port, client_tls).await.unwrap_err();
    assert!(
        format!("{error:?}").contains("UnknownIssuer"),
        "Expected TLS error due to mismatched CA, got: {error:?}",
    );

    server.stop().await.unwrap();
}

#[tokio::test]
async fn test_tls_fail_wrong_scheme() {
    // Test plan: verify that a TLS server does not serve clients that connect without TLS. Do this
    // by starting a server with the test server TLS options and checking health from a client that
    // has the test client TLS options but uses an `http://` URI, so it connects in plaintext.
    let server: DatabricksServer = DatabricksServer::builder()
        .set_port(0)
        .set_tls_options(TestTlsOptions::server_tls_options())
        .add_service(health_service())
        .start()
        .await
        .unwrap();
    let port: u16 = server.active_port().unwrap();

    let client_tls_config: ClientTlsConfig =
        ClientTlsConfig::try_from(TestTlsOptions::client_tls_options()).unwrap();
    let result: Result<ServingStatus, Box<dyn Error + Send + Sync>> = async {
        let endpoint: Endpoint = Endpoint::from_shared(format!("http://localhost:{port}"))?
            .tls_config(client_tls_config)?;
        let channel: Channel = endpoint.connect().await?;
        Ok(check_health(channel).await?)
    }
    .await;
    assert!(result.is_err(), "A plaintext client should not be served by a TLS server: {result:?}");

    server.stop().await.unwrap();
}

#[tokio::test]
async fn test_server_timeout_custom_short() {
    // Test plan: verify that the server cancels requests exceeding its timeout. Do this by setting
    // a timeout shorter than the handler's delay and checking that the RPC fails with CANCELLED.
    let server: DatabricksServer = DatabricksServer::builder()
        .set_port(0)
        .set_timeout(Duration::from_millis(200))
        .add_service(DelayedService::new(health_service(), Duration::from_secs(2)))
        .start()
        .await
        .unwrap();
    let port: u16 = server.active_port().unwrap();

    let status: Status = check_health(connect_plaintext(port).await)
        .await
        .unwrap_err();
    // Tonic reports server-side request timeouts as Cancelled.
    assert_eq!(
        status.code(),
        Code::Cancelled,
        "Expected Cancelled status code for server timeout, got: {:?}",
        status.code()
    );

    server.stop().await.unwrap();
}

#[tokio::test]
async fn test_server_timeout_custom_long() {
    // Test plan: verify that the server completes requests within its timeout. Do this by setting
    // a timeout longer than the handler's delay and checking that the RPC succeeds.
    let server: DatabricksServer = DatabricksServer::builder()
        .set_port(0)
        .set_timeout(Duration::from_secs(5))
        .add_service(DelayedService::new(health_service(), Duration::from_millis(100)))
        .start()
        .await
        .unwrap();
    let port: u16 = server.active_port().unwrap();

    let status: ServingStatus = check_health(connect_plaintext(port).await).await.unwrap();
    assert_eq!(status, ServingStatus::Serving);

    server.stop().await.unwrap();
}

#[tokio::test]
async fn test_databricks_server_manual_stop() {
    // Test plan: verify that a stopped server no longer serves requests, and that stopping does not
    // wait for idle clients to disconnect. Do this by checking health successfully over a channel,
    // stopping the server within a bounded time while the channel is still open, and checking that
    // the next RPC over the same channel fails.
    let server: DatabricksServer = DatabricksServer::builder()
        .set_port(0)
        .add_service(health_service())
        .start()
        .await
        .unwrap();

    let channel: Channel = connect_plaintext(server.active_port().unwrap()).await;
    assert_eq!(check_health(channel.clone()).await.unwrap(), ServingStatus::Serving);

    tokio::time::timeout(Duration::from_secs(5), server.stop())
        .await
        .expect("stop() should not wait for idle clients to disconnect")
        .unwrap();

    let result: Result<ServingStatus, Status> = check_health(channel).await;
    assert!(result.is_err(), "Server should be shut down and not accept requests: {result:?}");
}

#[tokio::test]
async fn test_set_port_binds_requested_port() {
    // Test plan: verify that the server listens on the port passed to `set_port`. Do this by
    // finding a free port, starting a server on it, and checking that `active_port` reports it and
    // that RPCs to it succeed.
    let free_port: u16 = {
        let listener = TcpListener::bind("[::]:0").await.unwrap();
        listener.local_addr().unwrap().port()
    };
    let server: DatabricksServer = DatabricksServer::builder()
        .set_port(free_port)
        .add_service(health_service())
        .start()
        .await
        .unwrap();
    assert_eq!(server.active_port(), Some(free_port));

    let status: ServingStatus = check_health(connect_plaintext(free_port).await)
        .await
        .unwrap();
    assert_eq!(status, ServingStatus::Serving);

    server.stop().await.unwrap();
}

#[tokio::test]
async fn test_start_fails_when_port_in_use() {
    // Test plan: verify that `start` reports a failure to bind instead of returning a server that
    // never listens. Do this by holding a listener on a port and starting a server on that port.
    let listener = TcpListener::bind("[::]:0").await.unwrap();
    let port: u16 = listener.local_addr().unwrap().port();

    let result: Result<DatabricksServer, _> = DatabricksServer::builder()
        .set_port(port)
        .add_service(health_service())
        .start()
        .await;
    let error = result.unwrap_err();
    let io_error: &IoError = error.downcast_ref().expect("Expected a port-binding error");
    assert_eq!(io_error.kind(), ErrorKind::AddrInUse);
}

#[tokio::test]
async fn test_start_fails_without_tls_identity() {
    // Test plan: verify that `start` rejects TLS options that cannot configure a server. Do this by
    // starting a server with TLS options that only contain a CA and no identity.
    let tls_without_identity: TlsOptions = TlsOptions::builder()
        .add_root_cert_path(TestTlsOptions::SERVER_TRUSTSTORE_PATH)
        .build()
        .unwrap();

    let result: Result<DatabricksServer, _> = DatabricksServer::builder()
        .set_port(0)
        .set_tls_options(tls_without_identity)
        .add_service(health_service())
        .start()
        .await;
    let error = result.unwrap_err();
    let tls_error: &TlsError = error
        .downcast_ref()
        .expect("Expected a TLS configuration error");
    assert!(matches!(tls_error, TlsError::InvalidIdentityCert(_)));
    assert!(
        tls_error
            .to_string()
            .contains("server requires at least one identity"),
        "Expected a missing server identity error, got: {tls_error}"
    );
}

#[tokio::test]
async fn test_start_without_services_returns_unimplemented() {
    // Test plan: verify that a server with no registered services starts and rejects RPCs as
    // unimplemented. Do this by starting a server without calling `add_service` and checking that
    // a health check fails with UNIMPLEMENTED.
    let server: DatabricksServer = DatabricksServer::builder()
        .set_port(0)
        .start()
        .await
        .unwrap();
    let port: u16 = server.active_port().unwrap();

    let status: Status = check_health(connect_plaintext(port).await)
        .await
        .unwrap_err();
    assert_eq!(status.code(), Code::Unimplemented, "Unexpected status: {status:?}");

    server.stop().await.unwrap();
}

#[tokio::test]
async fn test_mtls_requires_client_identity() {
    // Test plan: verify that a server whose TLS options include a CA requires clients to present an
    // identity issued by that CA. Do this by starting a server with the test server TLS options,
    // then checking health from a client without an identity (fails) and from a client with the
    // test client TLS options (succeeds).
    let server: DatabricksServer = DatabricksServer::builder()
        .set_port(0)
        .set_tls_options(TestTlsOptions::server_tls_options())
        .add_service(health_service())
        .start()
        .await
        .unwrap();
    let port: u16 = server.active_port().unwrap();

    // A client without an identity is rejected.
    let anonymous_client_tls: TlsOptions = TlsOptions::builder()
        .add_root_cert_path(TestTlsOptions::CLIENT_TRUSTSTORE_PATH)
        .build()
        .unwrap();
    let result: Result<ServingStatus, Box<dyn Error + Send + Sync>> =
        check_health_over_tls(port, anonymous_client_tls).await;
    assert!(result.is_err(), "Client without an identity should be rejected: {result:?}");

    // A client with an identity from the trusted CA is accepted.
    let status: ServingStatus = check_health_over_tls(port, TestTlsOptions::client_tls_options())
        .await
        .unwrap();
    assert_eq!(status, ServingStatus::Serving);

    server.stop().await.unwrap();
}

#[tokio::test]
async fn test_stop_waits_for_in_flight_request() {
    // Test plan: verify that `stop` shuts down gracefully, letting in-flight requests complete. Do
    // this by sending a request to a handler that delays its response, stopping the server once the
    // request has reached the handler, and checking that `stop` does not return until the in-flight
    // request has been served and that the request still succeeds.
    const handler_delay: Duration = Duration::from_millis(500);
    let service = DelayedService::new(health_service(), handler_delay);
    let request_arrived: Arc<Notify> = Arc::clone(&service.request_arrived);
    let server: DatabricksServer = DatabricksServer::builder()
        .set_port(0)
        .add_service(service)
        .start()
        .await
        .unwrap();
    let channel: Channel = connect_plaintext(server.active_port().unwrap()).await;
    let request_sent: Instant = Instant::now();
    let in_flight: JoinHandle<Result<ServingStatus, Status>> = tokio::spawn(check_health(channel));

    request_arrived.notified().await;
    server.stop().await.unwrap();
    assert!(
        request_sent.elapsed() >= handler_delay,
        "stop() should wait for the in-flight request to complete"
    );

    let status: ServingStatus = in_flight.await.unwrap().unwrap();
    assert_eq!(status, ServingStatus::Serving);
}

#[tokio::test]
async fn test_drop_without_stop_keeps_serving() {
    // Test plan: verify that dropping a server without calling `stop` leaves it serving. Do this
    // by starting a server, dropping it, and checking that RPCs to its port still succeed.
    let server: DatabricksServer = DatabricksServer::builder()
        .set_port(0)
        .add_service(health_service())
        .start()
        .await
        .unwrap();
    let port: u16 = server.active_port().unwrap();
    drop(server);

    let status: ServingStatus = check_health(connect_plaintext(port).await).await.unwrap();
    assert_eq!(status, ServingStatus::Serving);
}

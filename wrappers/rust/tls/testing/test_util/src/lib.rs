//! TLS handshake helper shared by the TLS test binaries.

use std::error::Error;
use std::time::Duration;

use raf_tls::TlsOptions;
use tokio::net::TcpListener;
use tokio::sync::oneshot;
use tokio::task::JoinHandle;
use tokio::time::error::Elapsed;
use tokio::time::timeout;
use tonic::transport::server::{Router, TcpIncoming};
use tonic::transport::{Channel, ClientTlsConfig, Endpoint, Server, ServerTlsConfig};
use tonic_health::pb::health_client::HealthClient;
use tonic_health::pb::health_server::HealthServer;
use tonic_health::pb::HealthCheckRequest;
use tonic_health::server::HealthReporter;

/// Drives a TLS handshake and one health-check RPC through upstream Tonic.
///
/// # Errors
/// Returns the client's connect or RPC error, e.g. when the handshake is rejected.
///
/// # Panics
/// Panics if setup fails, the RPC or server shutdown times out, or the server task fails.
pub async fn do_handshake(
    server_tls: TlsOptions,
    client_tls: TlsOptions,
    host: &str,
) -> Result<(), Box<dyn Error + Send + Sync>> {
    // Setup failures must fail the test, including cases expecting a TLS rejection.
    // Bind an ephemeral loopback port and construct the server.
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port: u16 = listener.local_addr().unwrap().port();
    let (_, health_service): (HealthReporter, HealthServer<_>) =
        tonic_health::server::health_reporter();
    let server: Router = Server::builder()
        .tls_config(ServerTlsConfig::try_from(server_tls).unwrap())
        .unwrap()
        .add_service(health_service);
    let (shutdown_tx, shutdown_rx): (oneshot::Sender<()>, oneshot::Receiver<()>) =
        oneshot::channel();
    // Serve until the shutdown signal fires after the RPC completes.
    let server_task: JoinHandle<Result<(), tonic::transport::Error>> =
        tokio::spawn(server.serve_with_incoming_shutdown(TcpIncoming::from(listener), async {
            let _ = shutdown_rx.await;
        }));

    // The `host` determines SNI and hostname verification.
    let endpoint = Endpoint::from_shared(format!("https://{host}:{port}"))
        .unwrap()
        .tls_config(ClientTlsConfig::try_from(client_tls).unwrap())
        .unwrap();
    // Connect and send an RPC with timeout, so a hung handshake fails the test.
    let result: Result<Result<(), Box<dyn Error + Send + Sync>>, Elapsed> =
        timeout(Duration::from_secs(10), async {
            let channel: Channel = endpoint.connect().await?;
            HealthClient::new(channel)
                .check(HealthCheckRequest {
                    service: String::new(),
                })
                .await?;
            Ok(())
        })
        .await;

    // Stop the server and surface any server-side failure before returning the client result.
    let _ = shutdown_tx.send(());
    timeout(Duration::from_secs(10), server_task)
        .await
        .expect("TLS server shutdown timed out")
        .expect("TLS server task panicked")
        .expect("TLS server failed");
    result.expect("TLS RPC timed out")
}

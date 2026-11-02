//! Wrapper around a Tonic gRPC server that provides compatible functionality with internal code.

use std::convert::Infallible;
use std::net::SocketAddr;
use std::time::Duration;

use anyhow::{Context, Result};
use http::{Request, Response};
use raf_tls::TlsOptions;
use tokio::net::TcpListener;
use tokio::task::JoinHandle;
use tokio_util::sync::CancellationToken;
use tonic::body::Body;
use tonic::codegen::Service;
use tonic::server::NamedService;
use tonic::service::{Routes, RoutesBuilder};
use tonic::transport::server::{Router, TcpIncoming};
use tonic::transport::{Server, ServerTlsConfig};

/// Idle time before TCP keepalive probes start on accepted connections.
const TCP_KEEPALIVE_TIME: Duration = Duration::from_secs(25);

/// A standard Rust gRPC server in Databricks. Dropping the server without calling
/// [`stop`](Self::stop) leaves it serving until the Tokio runtime shuts down.
///
/// # Examples
/// ```
/// use raf_server::DatabricksServer;
/// let server = DatabricksServer::builder()
///     .add_service(GreeterServer::new(MyGreeter::default()))
///     .set_port(0)
///     .start()
///     .await?;
/// // ...
/// server.stop().await?;
/// ```
#[derive(Debug)]
pub struct DatabricksServer {
    /// The TCP port the server is listening on.
    port: u16,
    /// `stop()` cancels this token to request graceful shutdown.
    shutdown_token: CancellationToken,
    /// The task serving requests, which completes once the server has shut down.
    server_task: JoinHandle<Result<()>>,
}

impl DatabricksServer {
    /// Create a new server builder that can configure a [`DatabricksServer`].
    pub fn builder() -> DatabricksServerBuilder {
        DatabricksServerBuilder::default()
    }

    /// Returns the bound TCP port. The result is always defined.
    pub fn active_port(&self) -> Option<u16> {
        Some(self.port)
    }

    /// Gracefully stops the server. It stops accepting connections and returns once in-flight
    /// requests have completed, without waiting for idle clients to disconnect.
    ///
    /// # Errors
    /// Returns an error if the server fails while serving or its task panics.
    pub async fn stop(self) -> Result<()> {
        self.shutdown_token.cancel();
        self.server_task
            .await
            .context("Server task failed to complete")?
    }
}

/// A builder for configuring a [`DatabricksServer`]. By default the server listens on port 443,
/// serves plaintext, and has no RPC timeout.
pub struct DatabricksServerBuilder {
    /// The gRPC services the server will serve.
    routes_builder: RoutesBuilder,
    /// The TCP port the server will listen on.
    port: u16,
    /// The TLS configuration, or `None` to serve plaintext.
    tls_options: Option<TlsOptions>,
    /// The RPC server timeout, or `None` for no timeout.
    rpc_server_timeout: Option<Duration>,
}

impl Default for DatabricksServerBuilder {
    fn default() -> Self {
        Self {
            routes_builder: RoutesBuilder::default(),
            port: 443,
            tls_options: None,
            rpc_server_timeout: None,
        }
    }
}

impl DatabricksServerBuilder {
    /// Add a gRPC service.
    ///
    /// # Example
    ///
    /// ```rust,ignore
    /// let server = DatabricksServer::builder()
    ///     .add_service(MyServiceServer::new(MyService::default()))
    ///     .set_port(50051)
    ///     .start()
    ///     .await?;
    /// ```
    pub fn add_service<S>(mut self, service: S) -> Self
    where
        // Tonic-generated servers respond with `Response<Body>`, which stands in for Tonic's
        // `axum::response::IntoResponse` bound without a direct axum dependency.
        S: Service<Request<Body>, Response = Response<Body>, Error = Infallible>
            + NamedService
            + Clone
            + Send
            + Sync
            + 'static,
        S::Future: Send + 'static,
    {
        self.routes_builder.add_service(service);
        self
    }

    /// Set the port the server will listen on. Port 0 picks a free port, which
    /// [`DatabricksServer::active_port`] reports once the server has started. Defaults to 443.
    pub fn set_port(mut self, port: u16) -> Self {
        self.port = port;
        self
    }

    /// Set the TLS configuration for the server. If not called, the server serves plaintext.
    /// # Examples
    /// ```
    /// use raf_tls::TlsOptions;
    /// let tls_options = TlsOptions::builder()
    ///   .add_identity("/path/to/cert.pem", "/path/to/key.pem")
    ///   .add_root_cert_path("/path/to/ca.pem")
    ///   .build()?;
    ///
    /// let server = DatabricksServer::builder()
    ///   .add_service(GreeterServer::new(MyGreeter::default()))
    ///   .set_port(443)
    ///   .set_tls_options(tls_options)
    ///   .start()
    ///   .await?;
    /// ```
    pub fn set_tls_options(mut self, tls_options: TlsOptions) -> Self {
        self.tls_options = Some(tls_options);
        self
    }

    /// Set the RPC server timeout. Without a timeout, requests may run indefinitely.
    /// # Examples
    /// ```
    /// use std::time::Duration;
    /// let server = DatabricksServer::builder()
    ///   .add_service(GreeterServer::new(MyGreeter::default()))
    ///   .set_port(443)
    ///   .set_timeout(Duration::from_secs(30))
    ///   .start()
    ///   .await?;
    /// ```
    pub fn set_timeout(mut self, timeout: Duration) -> Self {
        self.rpc_server_timeout = Some(timeout);
        self
    }

    /// Starts serving in a background task. When this returns, the port is bound and
    /// [`DatabricksServer::active_port`] reports it.
    ///
    /// # Errors
    /// Returns an error if the port cannot be bound, the TLS options have no identity, or the TLS
    /// configuration is rejected.
    pub async fn start(self) -> Result<DatabricksServer> {
        // Create cancellation token for shutdown signaling.
        let shutdown_token = CancellationToken::new();

        let mut server: Server = Server::builder();
        if let Some(timeout) = self.rpc_server_timeout {
            server = server.timeout(timeout);
        }
        if let Some(tls_options) = self.tls_options {
            let tls_config: ServerTlsConfig = tls_options.try_into()?;
            server = server.tls_config(tls_config)?;
        }
        let routes: Routes = self.routes_builder.routes();
        let router: Router = server.add_routes(routes);

        // Bind before spawning the server task so that bind failures are returned from `start()`.
        let addr: SocketAddr = format!("[::]:{}", self.port).parse()?;
        let listener = TcpListener::bind(addr).await?;
        let active_port: u16 = listener.local_addr()?.port();
        // Keepalive probes let the server eventually close connections whose client has gone away.
        let incoming = TcpIncoming::from(listener)
            .with_nodelay(Some(true))
            .with_keepalive(Some(TCP_KEEPALIVE_TIME));

        let shutdown_token_clone: CancellationToken = shutdown_token.clone();
        // Spawn the server in a background task to optimize performance. If we were to run
        // `serve_with_incoming_shutdown` directly inside `block_on`, the accept loop would execute
        // on the same thread that is calling `block_on`. This is inefficient because `tokio::spawn`
        // ensures that tasks will run on Tokio's async runtime, but a task inside `block_on` is
        // restricted to a synchronous thread, which means that every new connection would have to
        // be transferred to another Tokio worker thread before being processed.
        //
        // By spawning the server inside `tokio::spawn`, the accept loop runs within Tokio's
        // runtime.
        let server_task: JoinHandle<Result<()>> = tokio::spawn(async move {
            router
                .serve_with_incoming_shutdown(incoming, shutdown_token_clone.cancelled_owned())
                .await
                .with_context(|| "Server error on serve_with_incoming_shutdown")
        });

        Ok(DatabricksServer {
            port: active_port,
            shutdown_token,
            server_task,
        })
    }
}

use std::time::Duration;

use caching_rust_util::protocol_duration::ProtocolDuration;
use dicer_common_proto_common_proto::dicer::assignment_service_server::{
    AssignmentService,
    AssignmentServiceServer,
};
use raf_server::DatabricksServerBuilder;
use raf_tls::TlsOptions;

/// The maximum byte size that can be contained in the watch request's content. This is set to a
/// higher value than the default mainly to accommodate the possible large assignments sync-ed in
/// the watch request.
///
/// The value should match the MAX_WATCH_MESSAGE_CONTENT_LENGTH in WatchServerHelper.scala.
pub const MAX_WATCH_REQUEST_CONTENT_LENGTH_BYTES: usize = 4 * 1024 * 1024; // 4 MiB

/// The default client-side timeout to apply to watch requests.
///
/// The timeouts suggested by the server's responses take precedence over this default.
pub const DEFAULT_WATCH_RPC_TIMEOUT: Duration = Duration::from_secs(5);

/// The minimum suggested watch RPC timeout that we will accept.
pub const MIN_WATCH_RPC_TIMEOUT: Duration = Duration::from_millis(500);

/// Configuration for creating a watch server.
#[derive(Clone)]
pub struct WatchServerConfig {
    /// The port to bind the server to.
    pub port: u16,
    /// Optional TLS configuration for the watch server that clients connect to. If `None`, plain
    /// text communication will be used.
    pub tls_options: Option<TlsOptions>,
    /// Server timeout for watch requests.
    pub timeout: Duration,
}

/// Configures `builder` for the watch RPC server.
pub fn configure_watch_server_builder(
    server_builder: DatabricksServerBuilder,
    config: WatchServerConfig,
) -> DatabricksServerBuilder {
    let server_builder = server_builder
        .set_port(config.port)
        .set_timeout(config.timeout);

    // Add TLS configuration if provided.
    match config.tls_options {
        Some(tls_options) => server_builder.set_tls_options(tls_options),
        None => server_builder,
    }
}

/// Registers an AssignmentService with the provided server builder.
pub fn register_assignment_service(
    server_builder: DatabricksServerBuilder,
    assignment_service: impl AssignmentService,
) -> DatabricksServerBuilder {
    let service = AssignmentServiceServer::new(assignment_service)
        .max_decoding_message_size(MAX_WATCH_REQUEST_CONTENT_LENGTH_BYTES);
    server_builder.add_service(service)
}

/// Gets the default watch RPC timeout of type [ProtocolDuration].
pub fn get_default_watch_rpc_timeout() -> ProtocolDuration {
    // Safe to unwrap because the default timeout is a valid duration.
    ProtocolDuration::try_from(DEFAULT_WATCH_RPC_TIMEOUT).unwrap()
}

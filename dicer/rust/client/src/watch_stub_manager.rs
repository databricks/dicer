//! Manages the creation of watch stubs for watching assignments.

use anyhow::{anyhow, Error};
use caching_rust_util::prefix_error;
use caching_rust_util::prefix_logger::PrefixLogger;
use dicer_common_proto_common_proto::dicer::assignment_service_client::AssignmentServiceClient;
use dicer_rust_common::MAX_WATCH_REQUEST_CONTENT_LENGTH_BYTES;
use http::uri::{Parts, PathAndQuery, Scheme};
use moka::sync::Cache;
use raf_client::GrpcChannel;
use raf_tls::TlsOptions;
use tonic::transport::{ClientTlsConfig, Endpoint, Uri};
use tonic::Status;

use crate::client_metrics::{WatchChannelMetricLabels, DICER_CLIENT_WATCH_CHANNELS_CREATED};
use crate::watch_stub_manager_config::WatchStubManagerConfig;
use crate::WatchAddress;

/// Manages the creation of watch stubs for watching assignments.
///
/// The config's `watch_from_data_plane` does not change which stubs this manager returns, but
/// matches the internal implementation signature for compatibility.
///
/// Cloning a `WatchStubManager` clones the underlying stubs (which share their gRPC channels) and
/// the redirect-stub cache (which shares its backing store), so handing the same manager to several
/// lookups lets them share one set of connections.
#[derive(Clone)]
pub struct WatchStubManager {
    /// The default stub to use for watching assignments. It is bound to the config's watch address
    /// provided when the manager is created.
    default_stub: AssignmentServiceClient<GrpcChannel>,

    /// The config containing fields for establishing a watch connection through this manager.
    config: WatchStubManagerConfig,

    /// Caches a watch stub per redirect address so repeated redirects to the same address reuse
    /// one connection. Entries expire once idle for the config's watch stub cache time.
    watch_stubs_cache: Cache<Uri, AssignmentServiceClient<GrpcChannel>>,
}

impl WatchStubManager {
    /// Creates a manager whose default watch stub connects to the config's watch address.
    ///
    /// # Arguments
    ///
    /// * `config` - The config containing fields for establishing a watch connection through
    ///   this manager.
    ///
    /// # Errors
    ///
    /// Returns [`Status::invalid_argument`] if the default stub cannot be created (e.g. due to
    /// invalid TLS configuration).
    pub fn new(config: WatchStubManagerConfig) -> Result<Self, Status> {
        let default_watch_address: &WatchAddress = config.watch_address();
        let default_stub: AssignmentServiceClient<GrpcChannel> =
            create_watch_stub(default_watch_address.as_str(), &config).map_err(|e| {
                Status::invalid_argument(format!(
                    "Failed to build GrpcChannel for {default_watch_address}: {e}"
                ))
            })?;
        let watch_stubs_cache: Cache<Uri, AssignmentServiceClient<GrpcChannel>> = Cache::builder()
            .time_to_idle(config.watch_stub_cache_time())
            .build();
        Ok(Self {
            default_stub,
            config,
            watch_stubs_cache,
        })
    }

    /// Returns a watch stub for watching assignments.
    ///
    /// # Arguments
    ///
    /// * `address_opt` - The address of the redirect target to create a watch stub for. If not
    ///   provided, returns the stub bound to the config's watch address. If the stub cannot be
    ///   created, falls back to the default stub.
    /// * `logger` - Logger for reporting redirect-stub creation failures.
    pub fn get_stub_for_address(
        &self,
        address_opt: Option<&Uri>,
        logger: &PrefixLogger,
    ) -> AssignmentServiceClient<GrpcChannel> {
        match address_opt {
            Some(address) => self.watch_stubs_cache.get_with(address.clone(), || {
                match create_watch_stub(&address.to_string(), &self.config) {
                    Ok(stub) => stub,
                    Err(e) => {
                        let status = Status::invalid_argument(format!(
                            "Failed to build GrpcChannel for {address}: {e}"
                        ));
                        prefix_error!(
                            logger,
                            "Failed to create watch stub for redirect address \
                                {}: {}. Falling back to default watch address.",
                            address,
                            status
                        );
                        self.default_stub.clone()
                    }
                }
            }),
            None => self.default_stub.clone(),
        }
    }

    /// Returns the config's `watch_from_data_plane`. Unlike the internal implementation, this
    /// manager returns the same stubs either way.
    pub(crate) fn watch_from_data_plane(&self) -> bool {
        self.config.watch_from_data_plane()
    }

    /// Returns the address this manager's default stub connects to.
    pub(crate) fn watch_address(&self) -> &WatchAddress {
        self.config.watch_address()
    }
}

/// Returns `address` with its scheme set to `https` when `tls_options` is set and `http`
/// otherwise, keeping the rest of the URI. This is needed as tonic picks TLS from the scheme, and
/// an address may carry a scheme that disagrees with the configured TLS (redirect addresses are
/// always `https://`) or none at all.
///
/// For example, with no TLS options, "localhost:8080" is interpreted as "http://localhost:8080/".
///
/// # Errors
///
/// Returns an error if `address` does not parse as a URI with a host.
fn normalize_address_with_tls(
    address: &str,
    tls_options: &Option<TlsOptions>,
) -> Result<String, Error> {
    let uri: Uri = address.parse()?;
    let mut parts: Parts = uri.into_parts();
    if parts.authority.is_none() {
        return Err(anyhow!("Watch address {address} has no host."));
    }
    parts.scheme = Some(match tls_options {
        Some(_) => Scheme::HTTPS,
        None => Scheme::HTTP,
    });
    // A URI with a scheme must have a path, so an address without one, such as "localhost:8080",
    // gets "/".
    if parts.path_and_query.is_none() {
        parts.path_and_query = Some(PathAndQuery::from_static("/"));
    }
    let normalized_uri: Uri = Uri::from_parts(parts)?;
    Ok(normalized_uri.to_string())
}

/// Creates a watch stub bound to the provided `address`, using the config's TLS options, client
/// name and source IP.
///
/// # Errors
///
/// Returns an error if `address` has no host, or the target or TLS configuration is rejected.
fn create_watch_stub(
    address: &str,
    config: &WatchStubManagerConfig,
) -> Result<AssignmentServiceClient<GrpcChannel>, Error> {
    let corrected_address: String = normalize_address_with_tls(address, config.tls_options())?;
    let mut endpoint: Endpoint = Endpoint::from_shared(corrected_address)?;

    // Use the config's TLS options when present, otherwise the channel uses insecure (plaintext)
    // credentials.
    if let Some(tls_options) = config.tls_options() {
        let tls_config: ClientTlsConfig = ClientTlsConfig::try_from(tls_options.clone())?;
        endpoint = endpoint.tls_config(tls_config)?;
    }
    // No connect timeout or HTTP/2 keep-alive is configured, so connection establishment uses
    // OS-level TCP defaults.
    let channel: GrpcChannel = endpoint.local_address(config.source_ip()).connect_lazy();

    DICER_CLIENT_WATCH_CHANNELS_CREATED
        .get_or_create_owned(&WatchChannelMetricLabels::new(config.client_name().to_string()))
        .inc();

    Ok(AssignmentServiceClient::new(channel)
        .max_decoding_message_size(MAX_WATCH_REQUEST_CONTENT_LENGTH_BYTES))
}

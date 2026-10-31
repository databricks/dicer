//! The tests in this file only verify construction and conversion of [`TestTlsOptions`]. For tests
//! that verify RPCs with it, see `tests/tls_test.rs`.

use raf_tls::{TlsError, TlsOptions};
use raf_tls_test_tls_options::TestTlsOptions;
use tonic::transport::{ClientTlsConfig, ServerTlsConfig};

#[test]
fn test_server_tls_options() {
    // Test plan: verify that the server-side options build and can configure a server.
    let tls_options: TlsOptions = TestTlsOptions::server_tls_options();

    let result: Result<ServerTlsConfig, TlsError> = ServerTlsConfig::try_from(tls_options);
    assert!(result.is_ok(), "Failed to create server TLS config from test certificates");
}

#[test]
fn test_client_tls_options() {
    // Test plan: verify that the client-side options build and can configure a client.
    let tls_options: TlsOptions = TestTlsOptions::client_tls_options();

    let result: Result<ClientTlsConfig, TlsError> = ClientTlsConfig::try_from(tls_options);
    assert!(result.is_ok(), "Failed to create client TLS config from test certificates");
}

//! Test clients using system trust roots when no CA is configured. Kept in its own test binary
//! because it sets process-wide environment variables.

use std::error::Error;
use std::path::Path;

use raf_tls::TlsOptions;
use raf_tls_test_tls_options::{TestPemPath, TestTlsOptions};
use raf_tls_test_util::do_handshake;

#[tokio::test]
async fn test_client_without_ca_files_uses_platform_roots() {
    // Test plan: verify that a client without CA files trusts the platform roots and still verifies
    // the server certificate. Do this by pointing `SSL_CERT_FILE`, which replaces the platform
    // trust store, at the CA that signed the test server identity. Verify connecting to a server
    // using that identity succeeds. Then verify that connecting to a server using a different
    // identity with a different CA, fails with an unknown issuer error.

    let truststore: TestPemPath = TestTlsOptions::CLIENT_TRUSTSTORE_PATH;
    let truststore_path: &Path = truststore.as_ref();
    // SAFETY: This is the only test in the binary, so no other thread accesses the environment.
    unsafe {
        std::env::set_var("SSL_CERT_FILE", truststore_path);
        std::env::remove_var("SSL_CERT_DIR");
    }

    let client_tls: TlsOptions = TlsOptions::builder().build().unwrap();
    let server_keystore: TestPemPath = TestTlsOptions::SERVER_KEYSTORE_PATH;
    let trusted_server: TlsOptions = TlsOptions::builder()
        .add_identity(server_keystore, server_keystore)
        .build()
        .unwrap();
    let result: Result<(), Box<dyn Error + Send + Sync>> =
        do_handshake(trusted_server, client_tls.clone(), "localhost").await;
    assert!(result.is_ok(), "A server signed by a platform root must be trusted: {result:?}");

    // We use `TestTlsOptions::CLIENT_KEYSTORE_PATH` on the server side, not actually on the client.
    // It's just an arbitrary identity that is different from the one the client trusts.
    let client_keystore: TestPemPath = TestTlsOptions::CLIENT_KEYSTORE_PATH;
    let untrusted_server: TlsOptions = TlsOptions::builder()
        .add_identity(client_keystore, client_keystore)
        .build()
        .unwrap();
    let result: Result<(), Box<dyn Error + Send + Sync>> =
        do_handshake(untrusted_server, client_tls, "localhost").await;
    let error: Box<dyn Error + Send + Sync> = result.unwrap_err();
    assert!(
        format!("{error:?}").contains("UnknownIssuer"),
        "Expected an untrusted server certificate error, got: {error:?}",
    );
}

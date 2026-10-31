use std::error::Error;
use std::fs::{read_to_string, write};
use std::path::{Path, PathBuf};

use raf_tls::{TlsError, TlsOptions, TlsOptionsBuilder};
use raf_tls_test_identity::TestIdentity;
use raf_tls_test_tls_options::{TestPemPath, TestTlsOptions};
use raf_tls_test_util::do_handshake;
use rustls::{CertificateError, Error as RustlsError, InconsistentKeys};
use rustls_pki_types::pem::Error as PemError;
use tempfile::{tempdir, TempDir};
use tonic::transport::{ClientTlsConfig, ServerTlsConfig};

// ================================================
// TLS options builder tests.
// ================================================

/// PEM files that [`TestTlsOptions`] does not provide, written to a temporary directory that is
/// deleted when this struct is dropped.
struct TestPemFiles {
    /// Temp directory for the PEM files.
    dir: TempDir,
}

impl TestPemFiles {
    /// Creates an empty temporary directory for PEM files.
    fn new() -> Self {
        Self {
            dir: tempdir().unwrap(),
        }
    }

    /// Writes `contents` to the file `name` and returns its path.
    fn write_file<C: AsRef<[u8]>>(&self, name: &str, contents: C) -> PathBuf {
        let path: PathBuf = self.dir.path().join(name);
        write(&path, contents).unwrap();
        path
    }
}

/// Builds a `TlsOptionsBuilder` with every field set.
fn baseline_builder() -> TlsOptionsBuilder {
    let keystore: TestPemPath = TestTlsOptions::SERVER_KEYSTORE_PATH;
    TlsOptions::builder()
        .add_identity(keystore, keystore)
        .add_root_cert_path(TestTlsOptions::SERVER_TRUSTSTORE_PATH)
}

#[test]
fn test_missing_ca_file() {
    // Test plan: verify that a nonexistent CA path fails with an I/O error.
    let files = TestPemFiles::new();
    let keystore: TestPemPath = TestTlsOptions::SERVER_KEYSTORE_PATH;
    // Use a valid identity, only CA is missing.
    let result: Result<TlsOptions, TlsError> = TlsOptions::builder()
        .add_identity(keystore, keystore)
        .add_root_cert_path(files.dir.path().join("nonexistent_ca.pem"))
        .build();
    assert!(matches!(result, Err(TlsError::Io(_))));
}

#[test]
fn test_missing_identity_files() {
    // Test plan: verify that a nonexistent identity certificate or key path fails with an I/O
    // error.
    let files = TestPemFiles::new();
    let keystore_pem: TestPemPath = TestTlsOptions::SERVER_KEYSTORE_PATH;
    let keystore: &Path = keystore_pem.as_ref();
    let missing_cert: PathBuf = files.dir.path().join("nonexistent_cert.pem");
    let missing_key: PathBuf = files.dir.path().join("nonexistent_key.pem");
    let identity_paths: [(&Path, &Path); 2] =
        [(missing_cert.as_path(), keystore), (keystore, missing_key.as_path())];
    for (cert_path, key_path) in identity_paths {
        // Only one of the identity files is missing.
        let result: Result<TlsOptions, TlsError> = TlsOptions::builder()
            .add_identity(cert_path, key_path)
            .add_root_cert_path(TestTlsOptions::SERVER_TRUSTSTORE_PATH)
            .build();
        assert!(
            matches!(result, Err(TlsError::Io(_))),
            "Expected an I/O error for certificate {cert_path:?} and key {key_path:?}: {result:?}",
        );
    }
}

#[test]
fn test_root_first_identity_chain_fails() {
    // Test plan: verify that an identity chain in reversed (root-first) order is rejected at
    // build time. rustls treats the first certificate as the leaf, so a root-first chain fails the
    // key/leaf consistency check even though the key is the leaf's.
    let files = TestPemFiles::new();
    let identity: TestIdentity = TestIdentity::generate("Test Root CA");
    let combined_reversed: PathBuf = files.write_file(
        "combined-reversed.pem",
        [identity.ca_pem, identity.cert_pem, identity.key_pem].concat(),
    );
    let result: Result<TlsOptions, TlsError> = TlsOptions::builder()
        .add_identity(&combined_reversed, &combined_reversed)
        .build();
    assert!(
        matches!(
            result,
            Err(TlsError::RustlsError(RustlsError::InconsistentKeys(
                InconsistentKeys::KeyMismatch
            )))
        ),
        "A root-first identity chain must fail the key/leaf consistency check: {result:?}",
    );
}

#[test]
fn test_builder_no_identity_builds_successfully() {
    // Test plan: verify that options without an identity build successfully.
    let result: Result<TlsOptions, TlsError> = TlsOptionsBuilder::new()
        .add_root_cert_path(TestTlsOptions::CLIENT_TRUSTSTORE_PATH)
        .build();

    assert!(result.is_ok(), "No-identity build should succeed");
}

#[test]
fn test_builder_rejects_multiple_identities() {
    // Test plan: verify that adding a second identity produces UnsupportedConfiguration.
    let keystore: TestPemPath = TestTlsOptions::SERVER_KEYSTORE_PATH;
    let result: Result<TlsOptions, TlsError> = TlsOptions::builder()
        .add_identity(keystore, keystore)
        .add_identity(keystore, keystore)
        .add_root_cert_path(TestTlsOptions::SERVER_TRUSTSTORE_PATH)
        .build();
    assert!(matches!(result, Err(TlsError::UnsupportedConfiguration(_))));
}

#[test]
fn test_all_fields_set_identical_and_clone_equal() {
    // Test plan: verify that independently built options with identical inputs, and a clone,
    // compare equal.
    let a: TlsOptions = baseline_builder().build().unwrap();
    let b: TlsOptions = baseline_builder().build().unwrap();
    assert_eq!(a, b);
    assert_eq!(a, a.clone());
}

#[test]
fn test_differing_any_field_not_equal() {
    // Test plan: verify that changing the identity or CA paths makes options compare unequal.
    let base_builder: TlsOptionsBuilder = baseline_builder();
    let base: TlsOptions = base_builder.clone().build().unwrap();
    let alt_keystore: TestPemPath = TestTlsOptions::CLIENT_KEYSTORE_PATH;

    let variant_builders: [TlsOptionsBuilder; 2] = [
        TlsOptions::builder()
            .add_identity(alt_keystore, alt_keystore)
            .add_root_cert_path(TestTlsOptions::SERVER_TRUSTSTORE_PATH),
        base_builder.add_root_cert_path(TestTlsOptions::CLIENT_TRUSTSTORE_PATH),
    ];
    for variant_builder in variant_builders {
        assert_ne!(base, variant_builder.build().unwrap());
    }
}

#[test]
fn test_load_certified_key_invalid_cert_pem() {
    // Test plan: verify that an identity certificate file without PEM certificates is rejected.
    // Do this by creating a certificate file that is not valid PEM, paired with a valid key.
    let files = TestPemFiles::new();
    let cert_path: PathBuf = files.write_file("cert.pem", "not a valid PEM");
    let keystore: TestPemPath = TestTlsOptions::SERVER_KEYSTORE_PATH;

    let result: Result<TlsOptions, TlsError> = TlsOptions::builder()
        .add_identity(cert_path.as_path(), keystore.as_ref())
        .add_root_cert_path(TestTlsOptions::SERVER_TRUSTSTORE_PATH)
        .build();
    match result {
        Err(TlsError::Pem(PemError::NoItemsFound)) => {}
        Err(other) => panic!("Expected Pem error, got: {:?}", other),
        Ok(_) => panic!("Expected an error, but got Ok"),
    }
}

#[test]
fn test_load_certified_key_invalid_key_pem() {
    // Test plan: verify that an identity key file without a PEM private key is rejected. Do this
    // by pairing a valid certificate with a key file that is not valid PEM.
    let files = TestPemFiles::new();
    let key_path: PathBuf = files.write_file("key.pem", "not a valid PEM");
    let keystore: TestPemPath = TestTlsOptions::SERVER_KEYSTORE_PATH;

    let result: Result<TlsOptions, TlsError> = TlsOptions::builder()
        .add_identity(keystore.as_ref(), key_path.as_path())
        .add_root_cert_path(TestTlsOptions::SERVER_TRUSTSTORE_PATH)
        .build();
    match result {
        Err(TlsError::Pem(PemError::NoItemsFound)) => {}
        Err(other) => panic!("Expected Pem error, got: {:?}", other),
        Ok(_) => panic!("Expected an error, but got Ok"),
    }
}

#[test]
fn test_load_certified_key_mismatched_key() {
    // Test plan: verify that an identity whose key does not match its leaf certificate is
    // rejected. Do this by pairing the server certificate with the client's private key.
    let result: Result<TlsOptions, TlsError> = TlsOptions::builder()
        .add_identity(TestTlsOptions::SERVER_KEYSTORE_PATH, TestTlsOptions::CLIENT_KEYSTORE_PATH)
        .add_root_cert_path(TestTlsOptions::SERVER_TRUSTSTORE_PATH)
        .build();
    match result {
        Err(TlsError::RustlsError(RustlsError::InconsistentKeys(
            InconsistentKeys::KeyMismatch,
        ))) => {}
        Err(other) => panic!("Expected KeyMismatch for mismatched key, got: {:?}", other),
        Ok(_) => panic!("Expected an error for mismatched key, but got Ok"),
    }
}

#[test]
fn test_add_certs_to_store_invalid_pem() {
    // Test plan: verify that a CA file containing non-PEM bytes is rejected. Do this by building
    // options from a CA file of random bytes.
    let files = TestPemFiles::new();
    let random_bytes: Vec<u8> = vec![0, 1, 2, 3, 4, 5, 6, 7, 8, 9];
    let ca_path: PathBuf = files.write_file("ca.pem", random_bytes);

    let result: Result<TlsOptions, TlsError> =
        TlsOptions::builder().add_root_cert_path(&ca_path).build();

    match result {
        Err(TlsError::Pem(PemError::NoItemsFound)) => {}
        Err(other) => panic!("Expected Pem error, got: {:?}", other),
        Ok(_) => panic!("Expected an error, but got Ok"),
    }
}

#[test]
fn test_add_certs_to_store_invalid_der() {
    // Test plan: verify that a CA file whose PEM block is not a valid certificate is rejected when
    // adding it to the root store. Do this by building options from a CA file containing a
    // `CERTIFICATE` block with valid base64 that does not decode to certificate DER.
    let files = TestPemFiles::new();
    // The base64 encoding of "not a certificate".
    let ca_path: PathBuf = files.write_file(
        "ca.pem",
        "-----BEGIN CERTIFICATE-----\nbm90IGEgY2VydGlmaWNhdGU=\n-----END CERTIFICATE-----\n",
    );

    let result: Result<TlsOptions, TlsError> =
        TlsOptions::builder().add_root_cert_path(&ca_path).build();

    match result {
        Err(TlsError::RustlsError(RustlsError::InvalidCertificate(
            CertificateError::BadEncoding,
        ))) => {}
        Err(other) => panic!("Expected BadEncoding error, got: {:?}", other),
        Ok(_) => panic!("Expected an error, but got Ok"),
    }
}

#[tokio::test]
async fn test_handshake_trusts_multiple_ca_files() {
    // Test plan: verify that both peers trust certificates from either of two CA files, even when
    // the files lack trailing newlines. Do this by copying the server and client CAs without
    // trailing newlines, creating two `TlsOptions` which use the server and client identities but
    // trust both CAs, and verifying that any combination of the `TlsOptions` across the client and
    // server can connect successfully.
    let files = TestPemFiles::new();
    let server_ca_pem: String = read_to_string(TestTlsOptions::CLIENT_TRUSTSTORE_PATH).unwrap();
    let client_ca_pem: String = read_to_string(TestTlsOptions::SERVER_TRUSTSTORE_PATH).unwrap();
    let server_ca: PathBuf = files.write_file("server-ca.pem", server_ca_pem.trim_end());
    let client_ca: PathBuf = files.write_file("client-ca.pem", client_ca_pem.trim_end());

    let server_keystore: TestPemPath = TestTlsOptions::SERVER_KEYSTORE_PATH;
    let client_keystore: TestPemPath = TestTlsOptions::CLIENT_KEYSTORE_PATH;
    let server_identity_tls = TlsOptions::builder()
        .add_identity(server_keystore, server_keystore)
        .add_root_cert_path(&server_ca)
        .add_root_cert_path(&client_ca)
        .build()
        .unwrap();
    let client_identity_tls = TlsOptions::builder()
        .add_identity(client_keystore, client_keystore)
        .add_root_cert_path(&server_ca)
        .add_root_cert_path(&client_ca)
        .build()
        .unwrap();

    for server_tls in [&server_identity_tls, &client_identity_tls] {
        for client_tls in [&server_identity_tls, &client_identity_tls] {
            let result: Result<(), Box<dyn Error + Send + Sync>> =
                do_handshake(server_tls.clone(), client_tls.clone(), "localhost").await;
            assert!(result.is_ok(), "Both CA files must be trusted: {result:?}");
        }
    }
}

#[test]
fn test_no_identity_server_config_fails() {
    // Test plan: verify that conversion to a server config fails without an identity.
    let tls: TlsOptions = TlsOptionsBuilder::new()
        .add_root_cert_path(TestTlsOptions::SERVER_TRUSTSTORE_PATH)
        .build()
        .unwrap();

    let result: Result<ServerTlsConfig, TlsError> = ServerTlsConfig::try_from(tls);
    assert!(matches!(result, Err(TlsError::InvalidIdentityCert(_))));
}

#[test]
fn test_no_identity_client_config_succeeds() {
    // Test plan: verify that conversion to a client config succeeds without an identity.
    let tls: TlsOptions = TlsOptionsBuilder::new()
        .add_root_cert_path(TestTlsOptions::CLIENT_TRUSTSTORE_PATH)
        .build()
        .unwrap();

    let result: Result<ClientTlsConfig, TlsError> = ClientTlsConfig::try_from(tls);
    assert!(result.is_ok(), "No-identity client config should succeed");
}

// ================================================
// TLS handshake tests.
// ================================================

// Client authentication handshake tests.

#[tokio::test]
async fn test_server_with_client_ca_rejects_no_client_cert() {
    // Test plan: verify that a server with a client CA rejects clients without an identity.
    let client_tls = TlsOptions::builder()
        .add_root_cert_path(TestTlsOptions::CLIENT_TRUSTSTORE_PATH)
        .build()
        .unwrap();

    let result: Result<(), Box<dyn Error + Send + Sync>> =
        do_handshake(TestTlsOptions::server_tls_options(), client_tls, "localhost").await;
    assert!(
        result.is_err(),
        "Handshake should fail when server requires client cert but client has none"
    );
}

#[tokio::test]
async fn test_server_with_client_ca_accepts_valid_client_cert() {
    // Test plan: verify that a server with a client CA accepts a trusted client identity.
    let result: Result<(), Box<dyn Error + Send + Sync>> = do_handshake(
        // `server_tls_options` sets the client CA internally.
        TestTlsOptions::server_tls_options(),
        TestTlsOptions::client_tls_options(),
        "localhost",
    )
    .await;
    assert!(
        result.is_ok(),
        "Handshake should succeed when server requires client cert and client provides one: {:?}",
        result.err()
    );
}

#[tokio::test]
async fn test_server_without_client_ca_accepts_no_client_cert() {
    // Test plan: verify that a server without a client CA accepts clients without an identity.
    let server_keystore: TestPemPath = TestTlsOptions::SERVER_KEYSTORE_PATH;
    let server_tls = TlsOptions::builder()
        .add_identity(server_keystore, server_keystore)
        .build()
        .unwrap();

    let client_tls = TlsOptions::builder()
        .add_root_cert_path(TestTlsOptions::CLIENT_TRUSTSTORE_PATH)
        .build()
        .unwrap();

    let result: Result<(), Box<dyn Error + Send + Sync>> =
        do_handshake(server_tls, client_tls, "localhost").await;
    assert!(
        result.is_ok(),
        "Handshake should succeed when server does not require client cert: {:?}",
        result.err()
    );
}

#[tokio::test]
async fn test_tls_fail_with_server_name_mismatch() {
    // Test plan: verify that a server whose certificate does not cover the requested host is
    // rejected.
    let ret: Result<(), Box<dyn Error + Send + Sync>> = do_handshake(
        TestTlsOptions::server_tls_options(),
        TestTlsOptions::client_tls_options(),
        "127.0.0.1",
    )
    .await;
    let error: Box<dyn Error + Send + Sync> = ret.unwrap_err();
    assert!(
        format!("{error:?}").contains("NotValidForName"),
        "Expected server name mismatch error, got: {error:?}",
    );
}

#[tokio::test]
async fn test_tls_fail_with_cert_mismatch() {
    // Test plan: verify that when one side of the connection uses a certificate that isn't trusted
    // by the other side, the connection fails. Do this by verifying that the mutually trusting
    // server and client options connect successfully. Then, when the server trust store is
    // replaced with an unrelated CA that doesn't trust the client, the connection fails, and vice
    // versa.
    let files = TestPemFiles::new();
    let unrelated_ca: PathBuf =
        files.write_file("unrelated-ca.pem", TestIdentity::generate("Unrelated Test CA").ca_pem);
    let result: Result<(), Box<dyn Error + Send + Sync>> = do_handshake(
        TestTlsOptions::server_tls_options(),
        TestTlsOptions::client_tls_options(),
        "localhost",
    )
    .await;
    assert!(result.is_ok(), "Mutually trusted peers must connect: {result:?}");

    // Only the server's trust store changes; the client still trusts the server.
    let server_keystore: TestPemPath = TestTlsOptions::SERVER_KEYSTORE_PATH;
    let untrusting_server: TlsOptions = TlsOptions::builder()
        .add_identity(server_keystore, server_keystore)
        .add_root_cert_path(&unrelated_ca)
        .build()
        .unwrap();
    let result: Result<(), Box<dyn Error + Send + Sync>> =
        do_handshake(untrusting_server, TestTlsOptions::client_tls_options(), "localhost").await;
    assert!(result.is_err(), "Server must reject an untrusted client certificate");

    // Only the client's trust store changes; the server still trusts the client.
    let client_keystore: TestPemPath = TestTlsOptions::CLIENT_KEYSTORE_PATH;
    let untrusting_client: TlsOptions = TlsOptions::builder()
        .add_identity(client_keystore, client_keystore)
        .add_root_cert_path(&unrelated_ca)
        .build()
        .unwrap();
    let result: Result<(), Box<dyn Error + Send + Sync>> =
        do_handshake(TestTlsOptions::server_tls_options(), untrusting_client, "localhost").await;
    let error: Box<dyn Error + Send + Sync> = result.unwrap_err();
    assert!(
        format!("{error:?}").contains("UnknownIssuer"),
        "Expected an untrusted server certificate error, got: {error:?}",
    );
}

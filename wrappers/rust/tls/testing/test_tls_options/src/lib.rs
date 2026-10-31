//! Test utilities for TLS configuration. Generates ephemeral test certificates at runtime for mTLS
//! between a local server and client.

use std::env::var_os;
use std::fs::write;
use std::path::{Path, PathBuf};
use std::sync::LazyLock;

use raf_tls::TlsOptions;
use raf_tls_test_identity::TestIdentity;
use tempfile::{tempdir, tempdir_in, TempDir};

/// The test PEM files, generated on first use and shared by all tests in the process.
static TEST_PEM_FILES: LazyLock<TestPemFiles> = LazyLock::new(TestPemFiles::generate);

/// Provides TlsOptions for testing mTLS locally. The key stores referred to in this code
/// are test certificates generated on first use. The certificates are valid only for `localhost`,
/// so clients must connect to `localhost` rather than an address such as `127.0.0.1`.
pub struct TestTlsOptions;

impl TestTlsOptions {
    // Note: we have to declare these paths as constants to match internal variables. The
    // `impl AsRef<Path> for TestPemPath` ensures this can be passed to
    // `TlsOptionsBuilder.add_identity` and `TlsOptionsBuilder.add_root_cert_path`.

    /// Path to the keystore file of the client for TlsOptions.
    pub const CLIENT_KEYSTORE_PATH: TestPemPath = TestPemPath::ClientKeystore;

    /// Path to the keystore file of the server for TlsOptions.
    pub const SERVER_KEYSTORE_PATH: TestPemPath = TestPemPath::ServerKeystore;

    /// Path to the truststore file of the client for TlsOptions.
    pub const CLIENT_TRUSTSTORE_PATH: TestPemPath = TestPemPath::ClientTruststore;

    /// Path to the truststore file of the server for TlsOptions.
    pub const SERVER_TRUSTSTORE_PATH: TestPemPath = TestPemPath::ServerTruststore;

    /// Server-side TlsOptions that contains a server-side key pair and the CA that signed the
    /// client-side certificate. The server certificate is valid only for `localhost`.
    pub fn server_tls_options() -> TlsOptions {
        TlsOptions::builder()
            .add_identity(Self::SERVER_KEYSTORE_PATH, Self::SERVER_KEYSTORE_PATH)
            .add_root_cert_path(Self::SERVER_TRUSTSTORE_PATH)
            .build()
            .expect("Failed to create server TLS options")
    }

    /// Client-side TlsOptions that contains a client-side key pair and the CA that signed the
    /// server-side certificate. The client must connect to the server using address `localhost`.
    pub fn client_tls_options() -> TlsOptions {
        TlsOptions::builder()
            .add_identity(Self::CLIENT_KEYSTORE_PATH, Self::CLIENT_KEYSTORE_PATH)
            .add_root_cert_path(Self::CLIENT_TRUSTSTORE_PATH)
            .build()
            .expect("Failed to create client TLS options")
    }
}

/// The path of one of the test PEM files, usable wherever an `AsRef<Path>` is accepted.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TestPemPath {
    /// Combined PEM file with the client certificate followed by its private key.
    ClientKeystore,
    /// Combined PEM file with the server certificate followed by its private key.
    ServerKeystore,
    /// PEM file with the CA that signed the server certificate.
    ClientTruststore,
    /// PEM file with the CA that signed the client certificate.
    ServerTruststore,
}

impl AsRef<Path> for TestPemPath {
    fn as_ref(&self) -> &Path {
        let files: &TestPemFiles = &TEST_PEM_FILES;
        match self {
            TestPemPath::ClientKeystore => &files.client_keystore_path,
            TestPemPath::ServerKeystore => &files.server_keystore_path,
            TestPemPath::ClientTruststore => &files.client_truststore_path,
            TestPemPath::ServerTruststore => &files.server_truststore_path,
        }
    }
}

/// Keystore and truststore PEM files for a test server and client. Each side has its own CA, and
/// each truststore holds the other side's CA.
struct TestPemFiles {
    /// Combined PEM file with the client certificate followed by its private key.
    client_keystore_path: PathBuf,
    /// Combined PEM file with the server certificate followed by its private key.
    server_keystore_path: PathBuf,
    /// PEM file with the CA that signed the server certificate.
    client_truststore_path: PathBuf,
    /// PEM file with the CA that signed the client certificate.
    server_truststore_path: PathBuf,
}

impl TestPemFiles {
    /// Generates the server and client identities and writes them to a new temporary directory.
    fn generate() -> Self {
        // Statics are never dropped, so the directory is kept rather than deleted. Place it under
        // Bazel's per-test temp directory, which Bazel removes after the test. Outside Bazel, fall
        // back to the system temp directory.
        let dir: PathBuf = match var_os("TEST_TMPDIR") {
            Some(test_tmpdir) => tempdir_in(test_tmpdir),
            None => tempdir(),
        }
        .map(TempDir::keep)
        .expect("Failed to create directory for test PEM files");
        let server: TestIdentity = TestIdentity::generate("Test Server CA");
        let client: TestIdentity = TestIdentity::generate("Test Client CA");

        let write_file = |name: &str, contents: &str| -> PathBuf {
            let path: PathBuf = dir.join(name);
            write(&path, contents).expect("Failed to write test PEM file");
            path
        };
        // Use combined key and cert.
        let client_keystore_path: PathBuf = write_file(
            "test_client_keystore.pem",
            &format!("{}{}", client.cert_pem, client.key_pem),
        );
        let server_keystore_path: PathBuf = write_file(
            "test_server_keystore.pem",
            &format!("{}{}", server.cert_pem, server.key_pem),
        );
        let client_truststore_path: PathBuf =
            write_file("test_client_truststore.pem", &server.ca_pem);
        let server_truststore_path: PathBuf =
            write_file("test_server_truststore.pem", &client.ca_pem);

        Self {
            client_keystore_path,
            server_keystore_path,
            client_truststore_path,
            server_truststore_path,
        }
    }
}

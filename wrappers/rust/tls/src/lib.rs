//! TLS configuration for Tonic. Clients use configured CA bundles or platform trust roots and
//! always verify the server's certificate chain and hostname. Servers require a trusted client
//! certificate when CA bundles are configured. Tonic client endpoints must use `https://` URIs to
//! enable TLS.

use std::fmt;
use std::io::Error as StdIOError;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use rustls::crypto::{aws_lc_rs, CryptoProvider};
use rustls::sign::CertifiedKey;
use rustls::{Error as RustlsError, RootCertStore};
use rustls_pki_types::pem::{Error as PemError, PemObject};
use rustls_pki_types::{CertificateDer, PrivateKeyDer};
use thiserror::Error;
use tonic::transport::{Certificate, ClientTlsConfig, Identity as TonicIdentity, ServerTlsConfig};

/// TLS configuration with eagerly loaded CA certificates and identity. All TLS material is
/// loaded and validated when this struct is constructed via [`TlsOptionsBuilder::build()`].
#[derive(Clone)]
pub struct TlsOptions {
    /// Configuration file paths used for equality checks.
    raw: Arc<RawOptions>,
    /// PEM bytes of each CA file, loaded and validated at build time in configured order.
    root_cert_pems: Arc<Vec<Vec<u8>>>,
    /// The identity's PEM bytes, if configured. Its key matches its leaf certificate.
    identity: Option<Arc<IdentityPem>>,
}

/// Equality is based solely on the declarative `raw` inputs, mirroring the Scala counterpart.
impl PartialEq for TlsOptions {
    fn eq(&self, other: &Self) -> bool {
        self.raw == other.raw
    }
}

impl Eq for TlsOptions {}

impl fmt::Debug for TlsOptions {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("TlsOptions")
            .field("raw", &self.raw)
            .finish_non_exhaustive()
    }
}

impl TlsOptions {
    /// Creates a new builder for constructing [`TlsOptions`].
    pub fn builder() -> TlsOptionsBuilder {
        TlsOptionsBuilder::new()
    }
}

/// Converts [`TlsOptions`] into [`tonic::transport::ServerTlsConfig`]. With CA files configured,
/// the server requires clients to present a certificate signed by one of them. Without CA files,
/// clients are not authenticated.
///
/// # Errors
/// Returns [`TlsError::InvalidIdentityCert`] if no identity is configured.
impl TryFrom<TlsOptions> for ServerTlsConfig {
    type Error = TlsError;

    fn try_from(tls: TlsOptions) -> Result<Self, Self::Error> {
        let identity: &Arc<IdentityPem> =
            tls.identity.as_ref().ok_or(TlsError::InvalidIdentityCert(
                "server requires at least one identity. Call add_identity() on TlsOptionsBuilder.",
            ))?;
        let mut config = ServerTlsConfig::new()
            .identity(TonicIdentity::from_pem(&identity.cert_pem, &identity.key_pem));
        if !tls.root_cert_pems.is_empty() {
            // Tonic accepts a single CA bundle. Combine CA files into one PEM bundle, ensuring
            // certificate boundaries stay on separate lines.
            let mut ca_pem: Vec<u8> = Vec::new();
            for pem in tls.root_cert_pems.iter() {
                ca_pem.extend_from_slice(pem);
                ca_pem.push(b'\n');
            }
            config = config.client_ca_root(Certificate::from_pem(ca_pem));
        }
        Ok(config)
    }
}

/// Converts [`TlsOptions`] into [`tonic::transport::ClientTlsConfig`]. With CA files configured,
/// the client trusts only those CAs. Without CA files, it trusts the platform roots, which also
/// honor `SSL_CERT_FILE` and `SSL_CERT_DIR`. If the identity is omitted, the client presents no
/// certificate. This method never fails.
impl TryFrom<TlsOptions> for ClientTlsConfig {
    type Error = TlsError;

    fn try_from(tls: TlsOptions) -> Result<Self, Self::Error> {
        let mut config: ClientTlsConfig = if tls.root_cert_pems.is_empty() {
            // Matches Scala TLSOptions by using system trust roots when no CA files are supplied.
            ClientTlsConfig::new().with_native_roots()
        } else {
            ClientTlsConfig::new()
                .ca_certificates(tls.root_cert_pems.iter().map(Certificate::from_pem))
        };
        if let Some(identity) = &tls.identity {
            config =
                config.identity(TonicIdentity::from_pem(&identity.cert_pem, &identity.key_pem));
        }
        Ok(config)
    }
}

/// A builder for creating `TlsOptions`.
#[derive(Clone, Debug, Default)]
pub struct TlsOptionsBuilder {
    raw: RawOptions,
}

impl TlsOptionsBuilder {
    /// Creates a new builder for constructing [`TlsOptions`].
    pub fn new() -> Self {
        Self::default()
    }

    /// Adds an identity certificate and private key from their respective file paths.
    /// At most one identity is supported.
    #[inline]
    pub fn add_identity<P: AsRef<Path>>(mut self, cert_path: P, key_path: P) -> Self {
        self.raw.identities.push(Identity {
            cert: cert_path.as_ref().to_path_buf(),
            key: key_path.as_ref().to_path_buf(),
        });
        self
    }

    /// Adds a CA certificate file path to the root certificate store. Clients use platform roots
    /// if no CA files are configured. Servers require client authentication iff this is configured.
    #[inline]
    pub fn add_root_cert_path<P: AsRef<Path>>(mut self, path: P) -> Self {
        self.raw.root_certs.push(path.as_ref().to_path_buf());
        self
    }

    /// Builds the TlsOptions, eagerly loading and validating configured PEM files.
    /// Identity is optional for clients, required for servers.
    ///
    /// # Errors
    /// - [`TlsError::UnsupportedConfiguration`] if more than one identity is configured.
    /// - [`TlsError::Io`] if a CA, certificate, or key file cannot be read.
    /// - [`TlsError::Pem`] if a file is malformed or contains no certificate/key.
    /// - [`TlsError::RustlsError`] if a CA certificate is rejected, or the key is unsupported or
    ///   doesn't match the leaf certificate.
    pub fn build(self) -> Result<TlsOptions, TlsError> {
        let raw: RawOptions = self.raw;

        if raw.identities.len() > 1 {
            return Err(TlsError::UnsupportedConfiguration(
                "at most one identity is supported. Call add_identity() once on TlsOptionsBuilder.",
            ));
        }

        // Every CA file must contribute at least one valid certificate. The store is used only
        // for validation, so conversions hand the validated PEM bytes to Tonic.
        let mut store = RootCertStore::empty();
        let mut root_cert_pems: Vec<Vec<u8>> = Vec::new();
        for path in &raw.root_certs {
            let pem: Vec<u8> = std::fs::read(path).map_err(TlsError::Io)?;
            let certs: Vec<CertificateDer<'static>> = parse_cert_chain(&pem)?;
            for cert in certs {
                store.add(cert).map_err(TlsError::RustlsError)?;
            }
            root_cert_pems.push(pem);
        }

        // Validate the identity with the process-default provider from rustls. An application may
        // have installed a provider before `build()`, otherwise fall back to aws-lc-rs.
        let provider: Arc<CryptoProvider> = match CryptoProvider::get_default() {
            Some(provider) => Arc::clone(provider),
            None => Arc::new(aws_lc_rs::default_provider()),
        };
        let identity: Option<Arc<IdentityPem>> = match raw.identities.first() {
            None => None,
            Some(id) => {
                let cert_pem: Vec<u8> = std::fs::read(&id.cert).map_err(TlsError::Io)?;
                let key_pem: Vec<u8> = std::fs::read(&id.key).map_err(TlsError::Io)?;
                validate_identity(&cert_pem, &key_pem, &provider)?;
                Some(Arc::new(IdentityPem { cert_pem, key_pem }))
            }
        };

        Ok(TlsOptions {
            raw: Arc::new(raw),
            root_cert_pems: Arc::new(root_cert_pems),
            identity,
        })
    }
}

/// Errors from loading, validating, or converting TLS configuration.
#[derive(Debug, Error)]
pub enum TlsError {
    #[error("IO error: {0}")]
    Io(#[from] StdIOError),
    #[error("PEM error: {0}")]
    Pem(#[from] PemError),
    #[error("Rustls error: {0}")]
    RustlsError(#[from] RustlsError),
    #[error("Invalid or missing identity certificate: {0}")]
    InvalidIdentityCert(&'static str),
    #[error("Unsupported configuration: {0}")]
    UnsupportedConfiguration(&'static str),
}

/// Raw TLS configuration (before any certificate parsing takes place).
#[derive(Clone, Debug, Default, PartialEq, Eq)]
struct RawOptions {
    /// TLS identities (cert + key). Clients may omit an identity; servers require one.
    identities: Vec<Identity>,
    /// Root certificates for the trust store.
    root_certs: Vec<PathBuf>,
}

/// A pair of file paths for a TLS identity (certificate chain + private key).
#[derive(Clone, Debug, PartialEq, Eq)]
struct Identity {
    /// Path to the PEM certificate chain, with the leaf certificate first.
    cert: PathBuf,
    /// Path to the PEM private key for the leaf certificate.
    key: PathBuf,
}

/// An identity's certificate-chain and private-key PEM bytes.
struct IdentityPem {
    /// Validated PEM certificate chain, with the leaf certificate first.
    cert_pem: Vec<u8>,
    /// PEM private key, validated to match the leaf certificate.
    key_pem: Vec<u8>,
}

/// Validates that the PEM private key matches the first certificate in the chain (the end-entity
/// certificate).
///
/// # Errors
/// - [`TlsError::Pem`] if the certificate chain or key PEM is malformed or empty.
/// - [`TlsError::RustlsError`] if the provider can't load the key or it doesn't match the leaf
///   certificate.
fn validate_identity(
    cert_pem: &[u8],
    key_pem: &[u8],
    provider: &CryptoProvider,
) -> Result<(), TlsError> {
    let cert_chain: Vec<CertificateDer<'static>> = parse_cert_chain(cert_pem)?;
    let key_der: PrivateKeyDer<'static> =
        PrivateKeyDer::from_pem_slice(key_pem).map_err(TlsError::Pem)?;
    CertifiedKey::from_der(cert_chain, key_der, provider).map_err(TlsError::RustlsError)?;
    Ok(())
}

/// Parse certificate chain from PEM bytes.
///
/// # Errors
/// Returns [`TlsError::Pem`] if the PEM is malformed or contains no certificates.
fn parse_cert_chain(cert: &[u8]) -> Result<Vec<CertificateDer<'static>>, TlsError> {
    let certs: Vec<CertificateDer<'static>> = CertificateDer::pem_slice_iter(cert)
        .collect::<Result<Vec<_>, _>>()
        .map_err(TlsError::Pem)?;
    if certs.is_empty() {
        return Err(TlsError::Pem(PemError::NoItemsFound));
    }
    Ok(certs)
}

//! Internal helper to generate ephemeral test certificates for TLS tests.

use rcgen::{
    BasicConstraints,
    Certificate,
    CertificateParams,
    DnType,
    ExtendedKeyUsagePurpose,
    IsCa,
    KeyPair,
    KeyUsagePurpose,
};

/// A `localhost` identity and the CA that signed it, as PEM text.
pub struct TestIdentity {
    /// The leaf certificate.
    pub cert_pem: String,
    /// The private key of the leaf certificate.
    pub key_pem: String,
    /// The certificate of the CA that signed the leaf.
    pub ca_pem: String,
}

impl TestIdentity {
    /// Generates a new CA named `ca_name` and a `localhost` leaf certificate signed by it.
    pub fn generate(ca_name: &str) -> Self {
        let mut ca_params = CertificateParams::default();
        ca_params.is_ca = IsCa::Ca(BasicConstraints::Unconstrained);
        ca_params
            .distinguished_name
            .push(DnType::CommonName, ca_name);
        ca_params.key_usages.push(KeyUsagePurpose::KeyCertSign);
        let ca_key = KeyPair::generate().expect("Failed to generate CA key");
        let ca_cert: Certificate = ca_params
            .self_signed(&ca_key)
            .expect("Failed to generate CA certificate");

        let mut leaf_params = CertificateParams::new(vec!["localhost".to_string()])
            .expect("Failed to create leaf certificate parameters");
        // The same identity can sign TLS handshakes as either a client or a server.
        leaf_params
            .key_usages
            .push(KeyUsagePurpose::DigitalSignature);
        leaf_params.extended_key_usages =
            vec![ExtendedKeyUsagePurpose::ServerAuth, ExtendedKeyUsagePurpose::ClientAuth];
        // The leaf gets its own key; the CA's key signs the certificate for that public key.
        let leaf_key = KeyPair::generate().expect("Failed to generate leaf key");
        let leaf_cert: Certificate = leaf_params
            .signed_by(&leaf_key, &ca_cert, &ca_key)
            .expect("Failed to generate leaf certificate");

        Self {
            cert_pem: leaf_cert.pem(),
            key_pem: leaf_key.serialize_pem(),
            ca_pem: ca_cert.pem(),
        }
    }
}

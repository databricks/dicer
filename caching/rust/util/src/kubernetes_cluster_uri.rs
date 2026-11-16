use anyhow::anyhow;
use derive_more::{Debug, Display};

/// The scheme for Kubernetes cluster URIs in <internal link>.
const KUBERNETES_CLUSTER_SCHEME: &str = "kubernetes-cluster";

/// A URI identifying a Kubernetes cluster. (See <internal link>.)
///
/// Note that the `http::uri::Uri` type doesn't support opaque APIs, so we have to use `String` as
/// the internal representation instead.
#[derive(Clone, Debug, Display, Eq, PartialEq, Hash)]
#[debug("{_0:?}")]
#[display("{_0}")]
pub struct KubernetesClusterUri(String);

impl KubernetesClusterUri {
    /// Validates that the given Kubernetes cluster URI matches the spec at <internal link> (e.g.
    /// "kubernetes-cluster:prod/cloud1/public/region1/clustertype2/01").
    ///
    /// # Errors
    ///
    /// Returns an error if `uri` is not a well-formed Kubernetes cluster URI.
    pub fn from_uri(uri: String) -> anyhow::Result<KubernetesClusterUri> {
        // Verify that the URI has the expected scheme.
        let (scheme, scheme_specific_part) = uri
            .split_once(':')
            .ok_or_else(|| anyhow!("Missing scheme in URI: {uri}"))?;
        if scheme != KUBERNETES_CLUSTER_SCHEME {
            return Err(anyhow!("Expected a {KUBERNETES_CLUSTER_SCHEME} URI, got: {uri}"));
        }

        // Verify that the URI is opaque, i.e., does not represent a hierarchical URI where the
        // scheme-specific part begins with / or where there is no scheme (as in, for example,
        // "https://www.databricks.com" or "relative_uri").
        if scheme_specific_part.starts_with("/") {
            return Err(anyhow!("Expected an opaque URI, got: {uri}"));
        }

        // The path specification (which in the java URI API is called the scheme-specific part for
        // an opaque/non-hierarchical URI) is expected to consist of 6 parts:
        //   - environment (e.g., "dev")
        //   - cloud provider (e.g., "aws")
        //   - regulatory domain (e.g., "public")
        //   - region (e.g., "region8")
        //   - cluster type (e.g., "gc" for GENERAL_CLASSIC)
        //   - cluster code (e.g., "01" for the first GENERAL_CLASSIC cluster in the region)
        // Per the advice at <internal link>, we do not attempt to parse or validate the contents of
        // the parts.
        let num_parts: usize = scheme_specific_part.split("/").count();
        if num_parts != 6 {
            return Err(anyhow!(
                "Cluster URI path must have 6 parts: parts=[{}]",
                scheme_specific_part
                    .split("/")
                    .collect::<Vec<_>>()
                    .join(", ")
            ));
        }

        Ok(KubernetesClusterUri(uri))
    }
}

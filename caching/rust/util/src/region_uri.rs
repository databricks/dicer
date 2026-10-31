use anyhow::anyhow;
use derive_more::{Debug, Display};
use infra_lib_rust::FROM_EMBEDDED;

/// A URI identifying a region. (See <internal link>.)
///
/// Note that the `http::uri::Uri` type doesn't support opaque APIs, so we have to use `String` as
/// the internal representation instead.
#[derive(Clone, Debug, Display, Eq, PartialEq, Hash)]
#[debug("{_0:?}")]
#[display("{_0}")]
pub struct RegionUri(String);

impl RegionUri {
    /// Validates that the given URI names a region in the embedded InfraDataModel owned by the
    /// Infra team (e.g. "region:prod/cloud1/public/region1"; see <internal link>).
    ///
    /// The embedded model is co-versioned with the binary, so a region turned up after the binary
    /// was built is rejected.
    pub fn from_uri(uri: String) -> anyhow::Result<RegionUri> {
        if FROM_EMBEDDED.get_region_by_uri(&uri).is_none() {
            return Err(anyhow!("Expected a region IDM URI, got: {uri}"));
        }
        Ok(RegionUri(uri))
    }
}

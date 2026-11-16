use std::cmp::Ordering;
use std::fmt;
use std::hash::{Hash, Hasher};
use std::str::FromStr;

use http::uri::{PathAndQuery, Scheme};
use tonic::transport::Uri;
use tonic::Status;

/// A routable address for a resource within a target that is sharded by Dicer (a target may be
/// a k8s StatefulSet for instance). The resource is always a k8s pod at present. In the future,
/// support might be extended to targets that (say) span multiple clusters in which case the
/// addressed resource might still be a pod, or something else.
///
/// Addresses compare as URIs, not as the strings they were created from.
#[derive(Clone, Debug)]
pub struct ResourceAddress {
    /// The string the address was created from, which is how it is displayed and serialized.
    uri_str: String,
    uri: Uri,
}

impl ResourceAddress {
    pub fn new(uri: &str) -> Result<Self, Status> {
        if uri.is_empty() {
            return Err(Status::invalid_argument("URI must not be empty"));
        }
        let parsed_uri = Uri::from_str(uri).map_err(|e| {
            Status::invalid_argument(format!("URI not parseable as RFC 2396 URI: {e}"))
        })?;
        Ok(Self {
            uri_str: uri.to_string(),
            uri: parsed_uri,
        })
    }

    /// Returns a reference to the underlying `Uri`.
    ///
    /// Don't use this to serialize the address: `Uri` can render differently from the string the
    /// address was created from (e.g. `https://host:443` renders as `https://host:443/`), and
    /// Java's `URI`, which other Dicer components use, treats those as different addresses. Use
    /// [`Self::as_str`] instead.
    pub fn uri(&self) -> &Uri {
        &self.uri
    }

    /// Returns the string the address was created from. Use this, not [`Self::uri`], whenever the
    /// address is serialized, so that other components see the same address.
    pub fn as_str(&self) -> &str {
        &self.uri_str
    }
}

impl fmt::Display for ResourceAddress {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.uri_str)
    }
}

impl PartialEq for ResourceAddress {
    fn eq(&self, other: &Self) -> bool {
        self.uri == other.uri
    }
}

impl Eq for ResourceAddress {}

impl Hash for ResourceAddress {
    fn hash<H: Hasher>(&self, state: &mut H) {
        self.uri.hash(state)
    }
}

impl Ord for ResourceAddress {
    fn cmp(&self, other: &Self) -> Ordering {
        let left: &Uri = self.uri();
        let right: &Uri = other.uri();
        // In URIs, paths are case sensitive, while schemes and hosts are not.
        left.scheme()
            .map(|scheme: &Scheme| AsciiCaseInsensitiveStr(scheme.as_str()))
            .cmp(
                &right
                    .scheme()
                    .map(|scheme: &Scheme| AsciiCaseInsensitiveStr(scheme.as_str())),
            )
            // We compare the host and port individually instead of just comparing `left.authority()` to
            // `right.authority`, because we need to be consistent with Java's behavior of comparing
            // ports numerically instead of lexicographically.
            .then_with(|| {
                left.host()
                    .map(AsciiCaseInsensitiveStr)
                    .cmp(&right.host().map(AsciiCaseInsensitiveStr))
            })
            .then_with(|| left.port_u16().cmp(&right.port_u16()))
            .then_with(|| {
                left.path_and_query()
                    .map(PathAndQuery::as_str)
                    .cmp(&right.path_and_query().map(PathAndQuery::as_str))
            })
    }
}

impl PartialOrd for ResourceAddress {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

/// A wrapper around a string that compares case-insensitively.
struct AsciiCaseInsensitiveStr<'a>(&'a str);

impl Ord for AsciiCaseInsensitiveStr<'_> {
    fn cmp(&self, other: &Self) -> Ordering {
        self.0
            .bytes()
            .map(|b: u8| b.to_ascii_lowercase())
            .cmp(other.0.bytes().map(|b: u8| b.to_ascii_lowercase()))
    }
}

impl PartialOrd for AsciiCaseInsensitiveStr<'_> {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

impl PartialEq for AsciiCaseInsensitiveStr<'_> {
    fn eq(&self, other: &Self) -> bool {
        self.cmp(other) == Ordering::Equal
    }
}

impl Eq for AsciiCaseInsensitiveStr<'_> {}

//! Parsed and validated representation of [`RedirectP`].

use std::str::FromStr;

use http::Uri;
use tonic::Status;
use caching_rust_util::unwrap_ref_or_default::UnwrapRefOrDefault;
use dicer_common_proto_common_proto::dicer::RedirectP;

/// Routing hint sent by the server that tells the client where to send the next request.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Redirect {
    address_opt: Option<Uri>,
}

impl Redirect {
    /// An empty redirect: no address. Instructs the client to fall back to its default
    /// server-selection behavior.
    pub const EMPTY: Self = Self { address_opt: None };

    /// Constructs a new [`Redirect`]. Returns an error if `address_opt` is a non-empty `Uri` whose
    /// string form is empty (which should not happen for valid `Uri` values, but is asserted for
    /// symmetry with the Scala `Redirect` wrapper).
    pub fn new(address_opt: Option<Uri>) -> Result<Self, Status> {
        if address_opt
            .as_ref()
            .is_some_and(|uri: &Uri| uri.to_string().is_empty())
        {
            return Err(Status::invalid_argument("Redirect address must not be empty"));
        }
        Ok(Self { address_opt })
    }

    /// The redirected URI if one was supplied; otherwise the client should fall back to its
    /// default server-selection behavior.
    pub fn address_opt(&self) -> Option<&Uri> {
        self.address_opt.as_ref()
    }

    /// Converts to the corresponding [`RedirectP`] proto.
    pub fn to_proto(&self) -> RedirectP {
        RedirectP {
            address: self.address_opt.as_ref().map(|uri| uri.to_string()),
            redirect_token: None,
        }
    }

    /// Parses and validates the given proto.
    pub fn from_proto(proto: &RedirectP) -> Result<Self, Status> {
        let raw: &str = proto.address.unwrap_ref_or_default();
        let address_opt: Option<Uri> = if raw.is_empty() {
            None
        } else {
            Some(
                Uri::from_str(raw)
                    .map_err(|e| Status::invalid_argument(format!("Invalid URI {raw}: {e}")))?,
            )
        };
        Self::new(address_opt)
    }
}

// Note: this code is tested using dicer/common/test/data/client_response_test_data.textproto
// with various round-tripping and invalid cases.

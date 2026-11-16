use std::fmt::{self, Write};

use derive_more::Display;
use prometheus_client::encoding::{EncodeLabelValue, LabelValueEncoder};

/// Identifies the type of Dicer client.
#[derive(Clone, Copy, Debug, Display, Eq, PartialEq, Hash)]
pub enum ClientType {
    #[display("clerk")]
    Clerk,

    #[display("slicelet")]
    Slicelet,
}

impl EncodeLabelValue for ClientType {
    fn encode(&self, encoder: &mut LabelValueEncoder) -> Result<(), fmt::Error> {
        let value: &str = match self {
            ClientType::Clerk => "Clerk",
            ClientType::Slicelet => "Slicelet",
        };
        encoder.write_str(value)
    }
}

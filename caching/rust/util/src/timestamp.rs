use std::fmt;

use chrono::{DateTime, SecondsFormat};

/// A timestamp in microseconds since the Unix epoch loosely associated with a MySQL TIMESTAMP.
///
/// We provide this custom type for timestamps instead of [`SystemTime`] or [`Instant`] because
/// these have variable precision, and MySQL `TIMESTAMP`s have microsecond precision; thus, we want
/// to make it impossible to represent invalid timestamps. Additionally, we may sometimes need to
/// convert between inclusive and exclusive timestamp bounds, so it's important to avoid ambiguity
/// about the fact that one microsecond is the minimal unit of time passage.
#[derive(Copy, Clone, PartialEq, Eq)]
pub struct Timestamp {
    epoch_micros: i64,
}

impl Timestamp {
    /// Create a new timestamp from the number of microseconds since the Unix epoch.
    pub fn from_epoch_micros(epoch_micros: i64) -> Self {
        Self { epoch_micros }
    }

    /// Get the number of microseconds since the Unix epoch.
    pub fn epoch_micros(&self) -> i64 {
        self.epoch_micros
    }
}

impl fmt::Debug for Timestamp {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match DateTime::from_timestamp_micros(self.epoch_micros) {
            Some(dt) => write!(f, "{}", dt.to_rfc3339_opts(SecondsFormat::AutoSi, true)),
            None => {
                // `Timestamp` supports values from i64::MIN to i64::MAX, but chrono does not.
                // Thus, we need to print the timestamp in microseconds since epoch for values
                // outside the range of chrono::DateTime.
                write!(f, "Timestamp(Microseconds since epoch: {}μs)", self.epoch_micros)
            }
        }
    }
}

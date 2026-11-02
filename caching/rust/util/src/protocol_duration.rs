use std::ops::Deref;
use std::time::Duration;

use tonic::Status;

/// A duration type constrained to an inclusive range from 0 to [`i64::MAX]` nanoseconds.
///
/// This range represents the common intersection compatible with Java (java.time.Duration), Rust
/// [`std::time::Duration`], and Scala (scala.concurrent.duration.FiniteDuration). This makes it
/// safe to serialize as a Protobuf message for use across these languages. See the notes below for
/// details on the maximum values supported by each language.
///
/// ## Notes
///
/// - [`std::time::Duration`] does not support negative durations, the supported range is from
///   [`Duration::ZERO`] to [`Duration::MAX`] (i.e., [`u64::MAX`] seconds and 999_999_999 ns),
///   inclusive.
/// - java.time.Duration supports negative durations, and its maximum value is `Long.MAX_VALUE`
///   (same as [`i64::MAX`]) seconds and `999_999_999` ns.
/// - scala.concurrent.duration.FiniteDuration also supports negative durations, but its maximum
///   is only [`i64::MAX`] nanoseconds.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub struct ProtocolDuration {
    /// The inner duration. Non-public to ensure valid by construction through `try_from`.
    duration: Duration,
}

impl ProtocolDuration {
    /// The maximum duration supported, see the doc of [`ProtocolDuration`] for details.
    pub const MAX_DURATION: Duration = Duration::from_nanos(i64::MAX as u64);

    /// Returns the maximum supported duration in millis.
    pub const fn max_duration_millis() -> i64 {
        Self::MAX_DURATION.as_millis() as i64
    }

    /// Creates a protocol duration that can be safely serialized across Java, Rust, and Scala.
    ///
    /// # Errors
    /// Returns an error if the duration exceeds the maximum supported range.
    pub fn try_from(duration: Duration) -> Result<Self, Status> {
        if duration > Self::MAX_DURATION {
            Err(Status::invalid_argument("Exceeds maximum supported duration"))
        } else {
            Ok(Self { duration })
        }
    }

    /// Returns the inner `Duration`.
    pub fn into_duration(self) -> Duration {
        self.duration
    }
}

/// Allows treating a `&ProtocolDuration` as a `&Duration`.
impl Deref for ProtocolDuration {
    type Target = Duration;

    fn deref(&self) -> &Self::Target {
        &self.duration
    }
}

//! Macro to enable per-call-site throttled logging.

/// Emits a tracing event at most once per call site in the given number of seconds.
/// The first invocation is admitted immediately. Suppressed arguments are not evaluated, and
/// the next admitted event reports how many invocations were skipped. Zero disables throttling.
///
/// Example usage:
///
/// ```
/// // Per call-site throttling:
/// event_every_n_sec!(10, tracing::Level::ERROR, "Operation failed");
/// ```
#[macro_export]
macro_rules! event_every_n_sec {
    ($num_secs:literal, $level:expr, $($arg:tt)*) => {{
        use $crate::internal_do_not_use::{tracing, Throttler, ThrottlingMetadata};

        static THROTTLER: Throttler = Throttler::new(::std::time::Duration::from_secs($num_secs));
        THROTTLER.run_unless_throttled(|metadata: ThrottlingMetadata| {
            tracing::event!($level, "[{}] {}", metadata, format_args!($($arg)*));
        });
    }};
}

/// Implementation details needed by the macro.
#[doc(hidden)]
pub mod internal_do_not_use {
    use std::fmt::{self, Display, Formatter};
    use std::sync::{Mutex, MutexGuard};
    use std::time::Duration;

    use tokio::time::Instant;
    pub use tracing;

    /// The internal state for one call site.
    struct ThrottlerState {
        /// The last time that a log statement was retained, or `None` if no calls have occurred
        /// yet.
        last_run: Option<Instant>,

        /// The number of log statements that have been discarded due to throttling since the last
        /// retained one.
        num_skipped: i64,
    }

    /// An internal utility for ensuring that a piece of code only runs at most once every N
    /// seconds.
    pub struct Throttler {
        /// Calls will only go through at most once during any given window of time of this
        /// duration.
        period: Duration,

        /// State for this instance of the throttler.
        state: Mutex<ThrottlerState>,
    }

    impl Throttler {
        /// Creates a new `Throttler` that will only allow calls to go through at most once during
        /// any given window of time of the given duration.
        pub const fn new(period: Duration) -> Self {
            Self {
                period,
                state: Mutex::new(ThrottlerState {
                    last_run: None,
                    num_skipped: 0,
                }),
            }
        }

        /// Synchronously invokes `func` unless the time since it was last invoked was less than the
        /// throttling period.
        pub fn run_unless_throttled(&self, func: impl FnOnce(ThrottlingMetadata)) {
            let now = Instant::now();
            let num_skipped: i64 = {
                let mut state: MutexGuard<ThrottlerState> =
                    self.state.lock().expect("throttler state is poisoned");
                if let Some(last_run) = state.last_run {
                    if now < last_run + self.period {
                        state.num_skipped += 1;
                        return;
                    }
                }
                // Run and return the number of previously skipped calls.
                let skipped: i64 = state.num_skipped;
                state.last_run = Some(now);
                state.num_skipped = 0;
                skipped
            };
            // The lock must be released for the upcall.
            func(ThrottlingMetadata {
                period: self.period,
                num_skipped,
            });
        }
    }

    /// Throttling information included in each admitted event's message prefix.
    pub struct ThrottlingMetadata {
        /// The configured throttling period.
        period: Duration,

        /// The number of log statements that have been discarded due to throttling since the last
        /// retained one.
        num_skipped: i64,
    }

    impl Display for ThrottlingMetadata {
        fn fmt(&self, f: &mut Formatter) -> fmt::Result {
            write!(f, "every={}s", self.period.as_secs())?;
            if self.num_skipped > 0 {
                write!(f, ", skipped={}", self.num_skipped)?;
            }
            Ok(())
        }
    }
}

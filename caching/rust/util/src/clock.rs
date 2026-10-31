use std::fmt::{self, Debug};
use std::future::Future;
use std::sync::{Arc, LazyLock};
use std::time::{Duration, Instant, SystemTime};

use chrono::{DateTime, Utc};
use futures::future::BoxFuture;

/// Provides current "ticker" times and wall-clock times, as well as the sleep function that
/// allows injecting a fake clock for testing. See [`FakeClock`] for how to manually advance the
/// clock in tests.
///
/// **Important:** Use `clock.sleep()` instead of `tokio::time::sleep()` to enable deterministic
/// testing. Direct `tokio::time` calls bypass [`FakeClock`] and cannot be controlled via its
/// `advance()` method.
#[derive(Clone)]
pub struct Clock {
    inner: Arc<dyn ClockImpl>,
}

/// Returns a system clock that delegates to [`SystemTime`] and Tokio's time functions.
pub fn system_clock() -> Clock {
    SYSTEM_CLOCK.clone()
}

impl Clock {
    /// Returns the current wall-clock time as a [`std::time::SystemTime`]. Suitable for logging
    /// and when timestamps between different servers must be compared. Callers should assume that
    /// clocks are not perfectly (or even well-) synchronized between different servers.
    pub fn now(&self) -> SystemTime {
        self.inner.now()
    }

    /// Returns the current monotonic time as a [`std::time::Instant`]. Suitable for measuring
    /// durations and scheduling.
    pub fn instant(&self) -> Instant {
        self.inner.instant()
    }

    /// Awaits until the specified duration has elapsed. Same as [`tokio::time::sleep`] if using
    /// [`system_clock()`].
    pub fn sleep(&self, duration: Duration) -> BoxFuture<'static, ()> {
        self.inner.sleep(duration, None)
    }

    /// Similar to [`Clock::sleep`], but allows providing a reason to identify this sleep for test
    /// observability, which is only used by [`FakeClock`] and ignored by [`system_clock()`].
    pub fn sleep_with_reason(&self, duration: Duration, reason: String) -> BoxFuture<'static, ()> {
        self.inner.sleep(duration, Some(reason))
    }

    /// Applies a timeout to a future using the provided clock.
    ///
    /// If the future completes before the duration, [`Ok`] is returned with the future's
    /// output. If the duration elapses first, [`Err`] is returned with [`Elapsed`]. Internally,
    /// this uses [`tokio::select!`] to race the clock's sleep(`duration`) against the future.
    ///
    /// # Example
    ///
    /// ```ignore
    /// use std::time::Duration;
    ///
    /// async fn perform_work_with_timeout(clock: Clock) -> Result<(), Elapsed> {
    ///     clock.timeout(Duration::from_secs(5), async {
    ///         // perform work...
    ///     }).await
    /// }
    /// ```
    pub fn timeout<F, T>(
        &self,
        duration: Duration,
        future: F,
    ) -> impl Future<Output = Result<T, Elapsed>> + Send
    where
        F: Future<Output = T> + Send,
    {
        let sleep_future: BoxFuture<'static, ()> = self.sleep(duration);

        async move {
            tokio::select! {
                biased; // Prefer the completed future over the sleep.
                result = future => Ok(result),
                _ = sleep_future => Err(Elapsed),
            }
        }
    }

    /// Creates a clock from an inner implementation, only exposed via `internal_for_test`.
    pub(crate) fn from_impl<T: ClockImpl>(inner: Arc<T>) -> Self {
        Clock { inner }
    }
}

/// Internal trait for clock implementations. Production code should use [`Clock`] directly
/// created from [`system_clock`]. This trait is only exposed via `internal_for_test` for the
/// [`FakeClock`] to implement.
pub trait ClockImpl: Send + Sync + 'static {
    /// See [`Clock::now`].
    fn now(&self) -> SystemTime;

    /// See [`Clock::instant`].
    fn instant(&self) -> Instant;

    /// See [`Clock::sleep`].
    fn sleep(&self, duration: Duration, reason: Option<String>) -> BoxFuture<'static, ()>;
}

/// Error returned by [`Clock::timeout`] when the timeout elapses.
#[derive(Debug, PartialEq, Eq)]
pub struct Elapsed;

impl fmt::Display for Elapsed {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "deadline has elapsed")
    }
}

impl std::error::Error for Elapsed {}

/// Singleton instance of the system clock.
static SYSTEM_CLOCK: LazyLock<Clock> = LazyLock::new(|| {
    #[derive(Clone)]
    struct SystemClock;

    impl ClockImpl for SystemClock {
        fn now(&self) -> SystemTime {
            SystemTime::now()
        }

        fn instant(&self) -> Instant {
            tokio::time::Instant::now().into_std()
        }

        fn sleep(&self, duration: Duration, _reason: Option<String>) -> BoxFuture<'static, ()> {
            // Reason is ignored by the system clock.
            Box::pin(tokio::time::sleep(duration))
        }
    }

    Clock {
        inner: Arc::new(SystemClock),
    }
});

/// A wrapper that formats [`SystemTime`] values using the ISO 8601 standard,
/// e.g., "2024-12-03T23:46:14Z". Extreme values that are not supported by the
/// `chrono` API get a less friendly default debug format, e.g.,
/// "SystemTime { tv_sec: -8334601228801, tv_nsec: 999999999 }".
///
/// # FAQ
///
/// _Why not use the `chrono::DateTime` as the native system time representation
/// instead of `std::time::System`?_
///
/// While the ergonomics of the `chrono` API are a bit better in some ways, its
/// tendency to panic when it encounters extreme timestamp values makes it a
/// poor choice.
///
/// # Examples
///
/// ```
/// use std::time::{Duration, SystemTime};
/// use caching_rust_util::PrettySystemTime;
///
/// assert_eq!(
///     format!("{:?}", PrettySystemTime(SystemTime::UNIX_EPOCH)),
///     "1970-01-01T00:00:00Z".to_string(),
/// );
/// let nowish = SystemTime::UNIX_EPOCH + Duration::from_secs(1733269574);
/// assert_eq!(
///     format!("{:?}", PrettySystemTime(nowish)),
///     "2024-12-03T23:46:14Z".to_string(),
/// );
/// let distant_future = SystemTime::UNIX_EPOCH + Duration::from_secs(10_000_000_000_000);
/// assert_eq!(
///     format!("{:?}", PrettySystemTime(distant_future)),
///     "SystemTime { tv_sec: 10000000000000, tv_nsec: 0 }".to_string(),
/// );
/// ```
pub struct PrettySystemTime(pub SystemTime);

/// The minimum `SystemTime` that can be prettily formatted using `chrono`.
static MIN_PRETTY_SYSTEM_TIME: LazyLock<SystemTime> =
    LazyLock::new(|| DateTime::<Utc>::MIN_UTC.into());

/// The maximum `SystemTime` that can be prettily formatted using `chrono`.
static MAX_PRETTY_SYSTEM_TIME: LazyLock<SystemTime> =
    LazyLock::new(|| DateTime::<Utc>::MAX_UTC.into());

impl Debug for PrettySystemTime {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let time: SystemTime = self.0;
        if time < *MIN_PRETTY_SYSTEM_TIME || time > *MAX_PRETTY_SYSTEM_TIME {
            // Use the default Debug impl for `SystemTime`, since calling
            // `DateTime::<Utc>::from` will panic.
            time.fmt(f)
        } else {
            // The timestamp is in the range supported by `chrono`, so use its
            // user-friendly formatting.
            DateTime::<Utc>::from(self.0).fmt(f)
        }
    }
}

/// Converts the given i64 number of milliseconds since the Unix epoch into a
/// `SystemTime`. Returns `None` if it is not representable.
///
/// On Linux (and maybe other platforms too), this never returns `None`, since
/// the range of representable `SystemTime`s is wider than the range of
/// representable millisecond i64 timestamps.
pub fn system_time_from_unix_millis(unix_millis: i64) -> Option<SystemTime> {
    let abs_delta_from_epoch = Duration::from_millis(unix_millis.unsigned_abs());
    if unix_millis >= 0 {
        SystemTime::UNIX_EPOCH.checked_add(abs_delta_from_epoch)
    } else {
        SystemTime::UNIX_EPOCH.checked_sub(abs_delta_from_epoch)
    }
}

/// Converts the given `SystemTime` into an i64 number of milliseconds since the
/// Unix epoch, saturating at `i64::MAX`/`i64::MIN` for times too distant to
/// represent.
///
/// Truncates from `SystemTime`'s precision (nanoseconds on Linux) to
/// milliseconds.
pub fn saturated_system_time_to_unix_millis(time: SystemTime) -> i64 {
    let (abs_delta, at_or_after_epoch): (Duration, bool) = abs_duration_from_epoch(time);
    saturated_signed_i64(abs_delta.as_millis(), at_or_after_epoch)
}

/// Converts the given `SystemTime` into an i64 number of microseconds since the
/// Unix epoch, saturating at `i64::MAX`/`i64::MIN` for times too distant to
/// represent.
///
/// Truncates from `SystemTime`'s precision (nanoseconds on Linux) to
/// microseconds.
pub fn saturated_system_time_to_unix_micros(time: SystemTime) -> i64 {
    let (abs_delta, at_or_after_epoch): (Duration, bool) = abs_duration_from_epoch(time);
    saturated_signed_i64(abs_delta.as_micros(), at_or_after_epoch)
}

/// The absolute duration between `time` and the Unix epoch, paired with whether
/// `time` is at or after the epoch (the normal case). E.g. one second after the
/// epoch yields `(1s, true)`; one second before yields `(1s, false)`. Shared by
/// the `saturated_system_time_to_unix_*` conversions, which differ only in the
/// unit they read off the returned duration.
fn abs_duration_from_epoch(time: SystemTime) -> (Duration, bool) {
    let at_or_after_epoch: bool = time >= SystemTime::UNIX_EPOCH;
    // The default is unreachable: `duration_since` only errors when the instant
    // is earlier than its argument, which the branch above rules out.
    let abs_delta: Duration = if at_or_after_epoch {
        time.duration_since(SystemTime::UNIX_EPOCH)
    } else {
        SystemTime::UNIX_EPOCH.duration_since(time)
    }
    .unwrap_or_default();
    (abs_delta, at_or_after_epoch)
}

/// Applies the epoch-relative sign from [`abs_duration_from_epoch`] to
/// `abs_units`, saturating at `i64::MAX`/`i64::MIN` when the magnitude does not
/// fit in an i64. Negating an in-range magnitude never overflows, since the i64
/// range is asymmetric in favor of negative values.
fn saturated_signed_i64(abs_units: u128, at_or_after_epoch: bool) -> i64 {
    match (i64::try_from(abs_units), at_or_after_epoch) {
        (Ok(units), true) => units,
        (Ok(units), false) => -units,
        (Err(_), true) => i64::MAX,
        (Err(_), false) => i64::MIN,
    }
}

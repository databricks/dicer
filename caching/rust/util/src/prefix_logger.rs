use std::sync::Arc;

/// A logger that prefixes every log message with a type name and instance
/// prefix, mirroring the Scala `PrefixLogger` abstraction.
///
/// Each log message is formatted as `{type_name}[{prefix}]: {message}`.
/// The `prefix` string may be empty, or may identify a specific instance of
/// the type (e.g., an account or an endpoint). It is reused as the `prefix`
/// label on the `caching_errors` Prometheus counter.
///
/// # Logging
///
/// Five log-level macros are provided: [`prefix_error!`], [`prefix_warn!`],
/// [`prefix_info!`], [`prefix_debug!`], and [`prefix_trace!`]. Each accepts
/// a format string with optional arguments (same syntax as `format!`).
/// Messages are lazily formatted via `format_args!`, so the cost of
/// building the string is only paid when the tracing subscriber actually
/// records the event.
///
/// **Note:** Rust's `format_args!` disables inline variable capture when
/// the format string is forwarded through a macro. Use explicit positional
/// arguments instead:
///
/// ```ignore
/// prefix_warn!(logger, "retry {}", count);     // OK
/// prefix_warn!(logger, "retry {count}");        // compile error: unused variable
/// ```
///
/// # Throttled logging
///
/// Every log macro accepts an optional `every_n_sec =` parameter that
/// throttles output per call site. The first invocation always fires;
/// subsequent invocations are suppressed until the given number of
/// seconds has elapsed.
///
/// # Alerts
///
/// [`prefix_degraded_alert!`] logs at WARN and [`prefix_critical_alert!`]
/// logs at ERROR. Both also increment the `caching_errors` metric.
///
/// The optional `log_every_n_sec =` parameter throttles the log output
/// while still incrementing the metric on every invocation.
///
/// # Examples
///
/// ```ignore
/// use caching_rust_util::prefix_logger::PrefixLogger;
/// use caching_rust_util::{prefix_info, prefix_warn};
/// use caching_rust_util::{prefix_degraded_alert, prefix_critical_alert};
/// use caching_rust_util::caching_error_code::*;
///
/// let logger = PrefixLogger::new("HttpClient", "api.example.com");
///
/// // Simple logging
/// prefix_info!(logger, "starting watch");
/// prefix_warn!(logger, "retrying after {} failures", retry_count);
///
/// // Throttled logging (at most once per 30s per call site)
/// prefix_warn!(logger, every_n_sec = 30, "slow RPC to {}: {:?}", address, latency);
///
/// // Alert — log + increment metric
/// prefix_degraded_alert!(logger, MY_ERROR_CODE,
///     "cache gen {} > source gen {}", cache_gen, source_gen);
///
/// // Throttled alert — metric increments every time, log throttled
/// prefix_degraded_alert!(logger, MY_ERROR_CODE,
///     log_every_n_sec = 30, "error: {:?}", err);
/// ```
///
/// `Clone` is a cheap `Arc` pointer bump — no allocation.
#[derive(Clone)]
pub struct PrefixLogger {
    type_name: &'static str,
    prefix: Arc<str>,
}

impl PrefixLogger {
    /// Creates a logger for `type_name` with `prefix` added to every log
    /// message. `prefix` is also used as the `prefix` label on the
    /// `caching_errors` metric. Log output is formatted as
    /// `{type_name}[{prefix}]: {message}`.
    ///
    /// ## Multi-language Note
    ///
    /// Unlike Scala's `PrefixLogger.create(clazz, prefix)` which derives
    /// the type name from a `Class[_]` object, Rust has no runtime type
    /// metadata from which we can extract the simple type name, so the caller
    /// passes the name explicitly.
    pub fn new(type_name: &'static str, prefix: impl Into<Arc<str>>) -> Self {
        Self {
            type_name,
            prefix: prefix.into(),
        }
    }
}

/// Logs the message at ERROR level, prefixed with the logger's class name
/// and instance prefix.
///
/// ```ignore
/// prefix_error!(logger, "failed after {} retries", n);
/// prefix_error!(logger, every_n_sec = 30, "retrying after {} failures", n);
/// ```
#[macro_export]
macro_rules! prefix_error {
    ($l:expr, $($a:tt)+) => { $crate::__prefix_event_impl!(ERROR, $l, $($a)+) };
}
/// Logs the message at WARN level. See [`prefix_error!`] for usage.
#[macro_export]
macro_rules! prefix_warn {
    ($l:expr, $($a:tt)+) => { $crate::__prefix_event_impl!(WARN, $l, $($a)+) };
}
/// Logs the message at INFO level. See [`prefix_error!`] for usage.
#[macro_export]
macro_rules! prefix_info {
    ($l:expr, $($a:tt)+) => { $crate::__prefix_event_impl!(INFO, $l, $($a)+) };
}
/// Logs the message at DEBUG level. See [`prefix_error!`] for usage.
#[macro_export]
macro_rules! prefix_debug {
    ($l:expr, $($a:tt)+) => { $crate::__prefix_event_impl!(DEBUG, $l, $($a)+) };
}
/// Logs the message at TRACE level. See [`prefix_error!`] for usage.
#[macro_export]
macro_rules! prefix_trace {
    ($l:expr, $($a:tt)+) => { $crate::__prefix_event_impl!(TRACE, $l, $($a)+) };
}

/// Logs a message at WARN and increments the `caching_errors` metric with
/// [`Severity::Degraded`]. With `log_every_n_sec =`, the log is throttled
/// but the metric still increments on every call.
///
/// The optional `log_every_n_sec =` parameter throttles the log output while
/// still incrementing the metric on every invocation.
///
/// ```ignore
/// prefix_degraded_alert!(logger, MY_ERROR_CODE,
///     "cache gen {} > source gen {}", cache_gen, source_gen);
///
/// prefix_degraded_alert!(logger, MY_ERROR_CODE,
///     log_every_n_sec = 30, "error: {:?}", err);
/// ```
#[macro_export]
macro_rules! prefix_degraded_alert {
    ($l:expr, $code:expr, $($a:tt)+) => {
        $crate::__prefix_alert_impl!(Degraded, WARN, $l, $code, $($a)+)
    };
}

/// Logs a message at ERROR and increments the `caching_errors` metric with
/// [`Severity::Critical`]. With `log_every_n_sec =`, the log is throttled
/// but the metric still increments on every call.
///
/// The optional `log_every_n_sec =` parameter throttles the log output while
/// still incrementing the metric on every invocation.
///
/// ```ignore
/// prefix_critical_alert!(logger, MY_ERROR_CODE,
///     "invariant violated: {}", detail);
/// ```
#[macro_export]
macro_rules! prefix_critical_alert {
    ($l:expr, $code:expr, $($a:tt)+) => {
        $crate::__prefix_alert_impl!(Critical, ERROR, $l, $code, $($a)+)
    };
}

/// Implementation details for [`PrefixLogger`] macros. **Do not use
/// directly.**
///
/// # Lazy formatting contract
///
/// All prefix macros are implemented as `macro_rules!` so that the format
/// arguments are placed **inside** the tracing macro call (or throttler
/// closure). This ensures:
///
/// 1. When the tracing level is disabled, `tracing::event!` short-circuits
///    before evaluating `format_args!`, so no formatting work occurs.
/// 2. When a throttled variant suppresses a call, `event_every_n_sec!`
///    skips the closure entirely, so no formatting work occurs.
///
/// **Maintaining this property**: if you refactor these macros, ensure that
/// format arguments remain inside the branch/closure that is only entered
/// when the message will actually be emitted. Moving arguments outside (for
/// example into a helper function's parameter list) would cause eager
/// evaluation and defeat the optimization. The `test_*_skips_formatting_*`
/// tests in `prefix_logger_test.rs` guard against such regressions.
#[doc(hidden)]
pub mod internal_do_not_use {
    // Re-exported so that `#[macro_export]` macros (which expand at the
    // caller's crate) can reach these crates without the caller adding
    // them as direct dependencies.
    #[doc(hidden)]
    pub use ::tracing;
    #[doc(hidden)]
    pub use ::common_logging_throttled_logger_rust as throttled_logger;

    use super::PrefixLogger;
    use crate::caching_error_code::{CachingErrorCode, Severity};
    use crate::caching_errors;

    #[doc(hidden)]
    pub fn type_name(logger: &PrefixLogger) -> &'static str {
        logger.type_name
    }

    #[doc(hidden)]
    pub fn prefix(logger: &PrefixLogger) -> &str {
        &logger.prefix
    }

    /// Generic rule for the prefix_LEVEL! macros. The following sequence
    /// of transformations is applied, starting from the original macro call
    /// in the caller's code:
    ///
    /// ```ignore
    /// let logger = PrefixLogger::new("HttpClient", "api.example.com");
    ///
    /// // Non-throttled call:
    /// prefix_info!(logger, "attempt {}", n);
    /// // => __prefix_event_impl!(INFO, logger, "attempt {}", n);
    /// // => event!(Level::INFO, "HttpClient[api.example.com]: attempt {}", n);
    ///
    /// // Throttled call:
    /// prefix_info!(logger, every_n_sec = 30, "attempt {}", n);
    /// // => __prefix_event_impl!(INFO, logger, every_n_sec = 30, "attempt {}", n);
    /// // => event_every_n_sec!(30, Level::INFO, "HttpClient[api.example.com]: attempt {}", n);
    /// ```
    #[doc(hidden)]
    #[macro_export]
    macro_rules! __prefix_event_impl {
        ($LEVEL:ident, $l:expr, every_n_sec = $s:literal, $($a:tt)+) => {{
            use $crate::prefix_logger::internal_do_not_use as __pl;
            __pl::throttled_logger::event_every_n_sec!(
                $s, __pl::tracing::Level::$LEVEL, "{}[{}]: {}",
                __pl::type_name(&$l), __pl::prefix(&$l), format_args!($($a)+))
        }};
        ($LEVEL:ident, $l:expr, $($a:tt)+) => {{
            use $crate::prefix_logger::internal_do_not_use as __pl;
            __pl::tracing::event!(__pl::tracing::Level::$LEVEL, "{}[{}]: {}",
                __pl::type_name(&$l), __pl::prefix(&$l), format_args!($($a)+))
        }};
    }

    /// Increments the `caching_errors` metric. Exposed here so that the
    /// alert impl macro can call it without widening the visibility of
    /// `caching_errors::increment_alert_counter`.
    #[doc(hidden)]
    pub fn increment_alert(
        logger: &PrefixLogger,
        severity: Severity,
        error_code: CachingErrorCode,
    ) {
        caching_errors::increment_alert_counter(severity, error_code, logger.prefix.as_ref());
    }

    /// Generic rule for the prefix_{degraded,critical}_alert! macros. The
    /// following sequence of transformations is applied, starting from the
    /// original macro call in the caller's code:
    ///
    /// ```ignore
    /// let logger = PrefixLogger::new("HttpClient", "api.example.com");
    ///
    /// // Non-throttled alert:
    /// prefix_degraded_alert!(logger, MY_CODE, "msg {}", n);
    /// // => __prefix_alert_impl!(Degraded, WARN, logger, MY_CODE, "msg {}", n);
    /// // => increment_alert(&logger, Severity::Degraded, MY_CODE);
    /// //    event!(Level::WARN, "HttpClient[api.example.com]: [Degraded] MY_CODE: msg 42");
    ///
    /// // Throttled alert (metric always increments, log is throttled):
    /// prefix_degraded_alert!(logger, MY_CODE, log_every_n_sec = 30, "msg {}", n);
    /// // => __prefix_alert_impl!(Degraded, WARN, logger, MY_CODE, log_every_n_sec = 30, "msg {}", n);
    /// // => increment_alert(&logger, Severity::Degraded, MY_CODE);
    /// //    event_every_n_sec!(30, Level::WARN, "HttpClient[api.example.com]: [Degraded] MY_CODE: msg 42");
    /// ```
    #[doc(hidden)]
    #[macro_export]
    macro_rules! __prefix_alert_impl {
        ($Severity:ident, $LEVEL:ident, $l:expr, $code:expr, log_every_n_sec = $s:literal, $($a:tt)+) => {{
            use $crate::prefix_logger::internal_do_not_use as __pl;
            __pl::increment_alert(
                &$l, $crate::caching_error_code::Severity::$Severity, $code);
            __pl::throttled_logger::event_every_n_sec!(
                $s, __pl::tracing::Level::$LEVEL, "{}[{}]: [{:?}] {}: {}",
                __pl::type_name(&$l), __pl::prefix(&$l),
                $crate::caching_error_code::Severity::$Severity,
                $code.error_code(), format_args!($($a)+));
        }};
        ($Severity:ident, $LEVEL:ident, $l:expr, $code:expr, $($a:tt)+) => {{
            use $crate::prefix_logger::internal_do_not_use as __pl;
            __pl::increment_alert(
                &$l, $crate::caching_error_code::Severity::$Severity, $code);
            __pl::tracing::event!(__pl::tracing::Level::$LEVEL, "{}[{}]: [{:?}] {}: {}",
                __pl::type_name(&$l), __pl::prefix(&$l),
                $crate::caching_error_code::Severity::$Severity,
                $code.error_code(), format_args!($($a)+));
        }};
    }
}

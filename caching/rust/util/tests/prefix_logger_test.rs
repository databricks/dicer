use std::io::Write;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

use tracing_subscriber::prelude::*;
use caching_rust_util::caching_error_code::{AlertOwnerTeam, CachingErrorCode, Severity};
use caching_rust_util::prefix_logger::PrefixLogger;
use caching_rust_util::{
    caching_errors,
    prefix_critical_alert,
    prefix_debug,
    prefix_degraded_alert,
    prefix_error,
    prefix_info,
    prefix_trace,
    prefix_warn,
};

/// A [`Display`] value that counts how many times formatting is invoked.
/// Used to verify that arguments are only formatted when a message is
/// actually emitted.
#[derive(Clone)]
struct FormatProbe {
    count: Arc<AtomicUsize>,
}

impl FormatProbe {
    fn new() -> Self {
        Self {
            count: Arc::new(AtomicUsize::new(0)),
        }
    }

    /// Returns the number of times formatting has been invoked.
    fn count(&self) -> usize {
        self.count.load(Ordering::Relaxed)
    }
}

impl std::fmt::Display for FormatProbe {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        self.count.fetch_add(1, Ordering::Relaxed);
        f.write_str("probe")
    }
}

const TEST_CODE: CachingErrorCode =
    CachingErrorCode::new("PREFIX_LOGGER_TEST_ERROR", AlertOwnerTeam::CachingTeam);

/// Runs `f` and captures all log output produced at the given `level` as a
/// side-effect of the call.
///
/// Sample usage:
///
/// ```ignore
/// let result: String = capture_at_level(tracing::Level::INFO, || {
///     prefix_info!(logger, "hello, capture_at_level!");
/// });
/// assert!(result.contains("hello, capture_at_level!"));
/// ```
fn capture_at_level(level: tracing::Level, f: impl FnOnce()) -> String {
    let buf = CaptureBuf::new();
    let capture = buf.clone();

    let subscriber = tracing_subscriber::registry().with(
        tracing_subscriber::fmt::layer()
            .with_writer(move || capture.clone())
            .with_ansi(false)
            .with_filter(tracing_subscriber::filter::filter_fn(move |meta| *meta.level() == level)),
    );

    tracing::subscriber::with_default(subscriber, f);

    buf.contents()
}

/// Thread-safe buffer to capture log output.
#[derive(Clone)]
struct CaptureBuf {
    /// Byte buffer to which log outputs are appended.
    buf: Arc<Mutex<Vec<u8>>>,
}

impl CaptureBuf {
    fn new() -> Self {
        Self {
            buf: Arc::new(Mutex::new(Vec::new())),
        }
    }

    /// Returns the log contents appended to the byte buffer so far as a
    /// UTF-8 string.
    fn contents(&self) -> String {
        // Copy current buffer contents.
        let buf: Vec<u8> = self.buf.lock().unwrap().clone();

        // Convert to string and validate UTF-8.
        String::from_utf8(buf).expect("log contents should be valid UTF-8")
    }
}

/// Allows [`CaptureBuf`] to be used as a [`tracing`] log writer. It captures
/// log output to the byte buffer (`CaptureBuf::buf`). Flushing is a no-op
/// since the buffer is just an in-memory vector of bytes.
impl Write for CaptureBuf {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        self.buf.lock().unwrap().extend_from_slice(buf);
        Ok(buf.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

// Test plan: Verify that each log macro emits at the correct tracing level and
// formats output as "TypeName[prefix]: message" by installing a level-filtered
// subscriber and checking the captured output contains the expected string.
#[test]
fn test_all_log_levels_and_prefix_format() {
    let logger = PrefixLogger::new("HttpClient", "api.example.com");

    assert!(capture_at_level(tracing::Level::ERROR, || {
        prefix_error!(logger, "disk full on node {}", 42);
    })
    .contains("HttpClient[api.example.com]: disk full on node 42"));

    assert!(capture_at_level(tracing::Level::WARN, || {
        prefix_warn!(logger, "slow query");
    })
    .contains("HttpClient[api.example.com]: slow query"));

    assert!(capture_at_level(tracing::Level::INFO, || {
        prefix_info!(logger, "starting up");
    })
    .contains("HttpClient[api.example.com]: starting up"));

    assert!(capture_at_level(tracing::Level::DEBUG, || {
        prefix_debug!(logger, "cache hit ratio {}", 0.95);
    })
    .contains("HttpClient[api.example.com]: cache hit ratio 0.95"));

    assert!(capture_at_level(tracing::Level::TRACE, || {
        prefix_trace!(logger, "entering loop iteration");
    })
    .contains("HttpClient[api.example.com]: entering loop iteration"));
}

// Test plan: Verify that prefix_info! formats its arguments when the tracing
// subscriber's max level is at or above INFO by installing an INFO-only
// subscriber, invoking prefix_info! with a FormatProbe, and checking the
// probe's format count is one.
#[test]
fn test_log_formats_arguments_when_level_enabled() {
    let subscriber = tracing_subscriber::fmt()
        .with_max_level(tracing::Level::INFO)
        .finish();

    let logger = PrefixLogger::new("HttpClient", "api.example.com");
    let probe = FormatProbe::new();

    tracing::subscriber::with_default(subscriber, || {
        prefix_info!(logger, "value: {}", probe);

        assert_eq!(probe.count(), 1, "format args not evaluated when level enabled");
    });
}

// Test plan: Verify that prefix_info! does not format its arguments when the
// tracing subscriber's max level is below INFO by installing an ERROR-only
// subscriber, invoking prefix_info! with a FormatProbe, and checking the
// probe's format count remains zero.
#[test]
fn test_log_skips_formatting_when_level_disabled() {
    let subscriber = tracing_subscriber::fmt()
        .with_max_level(tracing::Level::ERROR)
        .finish();

    let logger = PrefixLogger::new("HttpClient", "api.example.com");
    let probe = FormatProbe::new();

    tracing::subscriber::with_default(subscriber, || {
        prefix_info!(logger, "value: {}", probe);
        assert_eq!(probe.count(), 0, "format args evaluated when level disabled");
    });
}

// Test plan: Verify that every_n_sec = suppresses repeated output by calling
// the macro three times within a 60s throttle window and asserting only one
// line is captured.
#[test]
fn test_throttled_warn_suppresses_after_first() {
    let logger = PrefixLogger::new("HttpClient", "api.example.com");
    let output = capture_at_level(tracing::Level::WARN, || {
        for _ in 0..3 {
            prefix_warn!(logger, every_n_sec = 60, "throttled warning");
        }
    });
    assert_eq!(
        output
            .matches("HttpClient[api.example.com]: throttled warning")
            .count(),
        1,
        "expected 1 log line, got: {output}"
    );
}

// Test plan: Verify that every_n_sec = works at all five log levels by
// capturing each at its expected level and checking the output.
#[test]
fn test_all_throttled_log_levels() {
    let logger = PrefixLogger::new("HttpClient", "api.example.com");

    assert!(capture_at_level(tracing::Level::ERROR, || {
        prefix_error!(logger, every_n_sec = 60, "te {}", 0);
    })
    .contains("HttpClient[api.example.com]: te 0"));

    assert!(capture_at_level(tracing::Level::WARN, || {
        prefix_warn!(logger, every_n_sec = 60, "tw");
    })
    .contains("HttpClient[api.example.com]: tw"));

    assert!(capture_at_level(tracing::Level::INFO, || {
        prefix_info!(logger, every_n_sec = 60, "ti {}", 1);
    })
    .contains("HttpClient[api.example.com]: ti 1"));

    assert!(capture_at_level(tracing::Level::DEBUG, || {
        prefix_debug!(logger, every_n_sec = 60, "td");
    })
    .contains("HttpClient[api.example.com]: td"));

    assert!(capture_at_level(tracing::Level::TRACE, || {
        prefix_trace!(logger, every_n_sec = 60, "tt {}", 2);
    })
    .contains("HttpClient[api.example.com]: tt 2"));
}

// Test plan: Verify that every_n_sec = does not format its arguments on
// throttled (suppressed) invocations by calling the macro three times with
// a FormatProbe and asserting the probe was formatted exactly once.
#[test]
fn test_throttled_log_skips_formatting_when_suppressed() {
    let logger = PrefixLogger::new("HttpClient", "api.example.com");
    let probe = FormatProbe::new();

    let _ = capture_at_level(tracing::Level::WARN, || {
        for _ in 0..3 {
            prefix_warn!(logger, every_n_sec = 60, "throttled {}", probe);
        }
    });

    assert_eq!(probe.count(), 1, "format args should be evaluated once");
}

// Test plan: Verify that prefix_degraded_alert! logs at WARN and increments the
// caching_errors metric by capturing at WARN level and comparing counter values
// before and after.
#[test]
fn test_alert_degraded_logs_and_increments_metric() {
    let logger = PrefixLogger::new("TestComponent", "test_alert");
    let before = caching_errors::get_caching_error_counter_value_for_test(
        Severity::Degraded,
        TEST_CODE,
        "test_alert",
    );

    let output = capture_at_level(tracing::Level::WARN, || {
        prefix_degraded_alert!(logger, TEST_CODE, "something went wrong");
    });

    let after = caching_errors::get_caching_error_counter_value_for_test(
        Severity::Degraded,
        TEST_CODE,
        "test_alert",
    );
    assert_eq!(after, before + 1);
    assert!(output.contains("[Degraded] PREFIX_LOGGER_TEST_ERROR: something went wrong"));
}

// Test plan: Verify that prefix_critical_alert! logs at ERROR and increments the
// caching_errors metric by capturing at ERROR level and comparing counter values
// before and after.
#[test]
fn test_alert_critical_logs_and_increments_metric() {
    let logger = PrefixLogger::new("TestComponent", "test_critical");
    let before = caching_errors::get_caching_error_counter_value_for_test(
        Severity::Critical,
        TEST_CODE,
        "test_critical",
    );

    let output = capture_at_level(tracing::Level::ERROR, || {
        prefix_critical_alert!(logger, TEST_CODE, "invariant violated");
    });

    let after = caching_errors::get_caching_error_counter_value_for_test(
        Severity::Critical,
        TEST_CODE,
        "test_critical",
    );
    assert_eq!(after, before + 1);
    assert!(output.contains("[Critical] PREFIX_LOGGER_TEST_ERROR: invariant violated"));
}

// Test plan: Verify that prefix_degraded_alert! correctly interpolates format
// arguments by firing the alert with a format string and checking the captured
// output contains the interpolated value.
#[test]
fn test_alert_with_format_args() {
    let logger = PrefixLogger::new("TestComponent", "test_fmt");
    let output = capture_at_level(tracing::Level::WARN, || {
        prefix_degraded_alert!(logger, TEST_CODE, "bad value for key {}", "some-key");
    });
    assert!(output.contains("bad value for key some-key"));
}

// Test plan: Verify that log_every_n_sec = throttles log output but increments
// the metric on every call by firing the alert three times and checking the
// metric incremented three times but only one log line was emitted.
#[test]
fn test_alert_log_every_n_sec_throttles_log_but_always_increments_metric() {
    let logger = PrefixLogger::new("TestComponent", "test_throttle");
    let before = caching_errors::get_caching_error_counter_value_for_test(
        Severity::Degraded,
        TEST_CODE,
        "test_throttle",
    );

    let output = capture_at_level(tracing::Level::WARN, || {
        for _ in 0..3 {
            prefix_degraded_alert!(logger, TEST_CODE, log_every_n_sec = 60, "throttled msg");
        }
    });

    let after = caching_errors::get_caching_error_counter_value_for_test(
        Severity::Degraded,
        TEST_CODE,
        "test_throttle",
    );
    assert_eq!(after, before + 3, "metric should increment on every call");
    assert_eq!(output.matches("throttled msg").count(), 1, "expected 1 log line, got: {output}");
}

// Test plan: Verify that log_every_n_sec = does not format args on suppressed
// calls by firing the macro three times with a FormatProbe and asserting the
// probe was formatted exactly once.
#[test]
fn test_throttled_alert_skips_formatting_when_suppressed() {
    let logger = PrefixLogger::new("HttpClient", "test_lazy_throttle");
    let probe = FormatProbe::new();

    let _ = capture_at_level(tracing::Level::WARN, || {
        for _ in 0..3 {
            prefix_degraded_alert!(logger, TEST_CODE, log_every_n_sec = 60, "throttled {}", probe);
        }
    });

    assert_eq!(probe.count(), 1, "format args should be evaluated once");
}

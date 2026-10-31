use caching_rust_util::caching_error_code::{AlertOwnerTeam, CachingErrorCode, Severity};
use caching_rust_util::prefix_logger::PrefixLogger;
use caching_rust_util::{caching_errors, prefix_critical_alert, prefix_degraded_alert};
use raf_metrics_testing::verify_encoded_counter;

/// Verifies the caching error counter value matches the expected value.
fn verify_caching_error_counter(
    severity: &str,
    error_code: CachingErrorCode,
    prefix: &str,
    expected_value: u64,
) {
    let sev: Severity = match severity {
        "DEGRADED" => Severity::Degraded,
        "CRITICAL" => Severity::Critical,
        _ => panic!("Unknown severity: {}", severity),
    };

    // `verify_encoded_counter` does not work with zero values, so we need to call the
    // `get_caching_error_counter_value_for_test` function to ensure the metric is initialized,
    // and then verify the value.
    let actual_value: u64 =
        caching_errors::get_caching_error_counter_value_for_test(sev, error_code, prefix);

    assert_eq!(
        actual_value,
        expected_value,
        "Expected counter value for severity: {}, error_code: {}, prefix: {} to be {}, but got {}",
        severity,
        error_code.error_code(),
        prefix,
        expected_value,
        actual_value
    );

    // Since we have initialized the metric, we can now use `verify_encoded_counter` to verify
    // the counter value.
    verify_encoded_counter(
        "caching_errors",
        "Counter for common errors with severity and error code.",
        &[
            ("severity", severity),
            ("error_code", error_code.error_code()),
            ("prefix", prefix),
            ("owner_team", error_code.owner_team().as_str()),
        ],
        expected_value,
    );
}

const TEST_ERROR_CODE: CachingErrorCode =
    CachingErrorCode::new("THIS_IS_SOME_TEST_ERROR_CODE", AlertOwnerTeam::Custom("eng-test-team"));
const OTHER_TEST_ERROR_CODE: CachingErrorCode = CachingErrorCode::new(
    "THIS_IS_SOME_OTHER_TEST_ERROR_CODE",
    AlertOwnerTeam::Custom("eng-test-team"),
);

#[test]
fn test_prefix_macros_increment_caching_errors() {
    // Test plan: verify that prefix_degraded_alert and prefix_critical_alert increment the
    // caching_errors counter for the specific severity and error code with a prefix.
    let logger = PrefixLogger::new("TestComponent", "test_prefix");

    // Verify that the counters are 0 before running the test.
    verify_caching_error_counter("DEGRADED", TEST_ERROR_CODE, "test_prefix", 0);
    verify_caching_error_counter("CRITICAL", TEST_ERROR_CODE, "test_prefix", 0);
    verify_caching_error_counter("DEGRADED", OTHER_TEST_ERROR_CODE, "another_test_prefix", 0);

    // prefix_degraded_alert fires with DEGRADED severity.
    prefix_degraded_alert!(logger, TEST_ERROR_CODE, "test message");

    // Verify that the DEGRADED counter was incremented.
    verify_caching_error_counter("DEGRADED", TEST_ERROR_CODE, "test_prefix", 1);

    // Call 5 more times with prefix_degraded_alert to verify cumulative counting.
    for _ in 0..5 {
        prefix_degraded_alert!(logger, TEST_ERROR_CODE, "test message");
    }
    verify_caching_error_counter("DEGRADED", TEST_ERROR_CODE, "test_prefix", 6);

    // Verify the CRITICAL counter remains unchanged (prefix_degraded_alert is DEGRADED only).
    verify_caching_error_counter("CRITICAL", TEST_ERROR_CODE, "test_prefix", 0);

    // prefix_critical_alert fires with CRITICAL severity.
    prefix_critical_alert!(logger, TEST_ERROR_CODE, "critical test message");
    verify_caching_error_counter("CRITICAL", TEST_ERROR_CODE, "test_prefix", 1);

    // Call with a different error code and prefix.
    let other_logger = PrefixLogger::new("TestComponent", "another_test_prefix");
    prefix_degraded_alert!(other_logger, OTHER_TEST_ERROR_CODE, "test message");

    // Verify that the counter for the new error code and prefix was incremented correctly.
    verify_caching_error_counter("DEGRADED", OTHER_TEST_ERROR_CODE, "another_test_prefix", 1);
}

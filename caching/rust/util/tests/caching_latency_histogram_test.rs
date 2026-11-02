//! Synchronous scenarios corresponding to `CachingLatencyHistogramSuite.scala`.
//! Rust represents Scala's thrown exceptions as returned `Status` errors.

use std::time::{Duration, SystemTime};

use prometheus_client::encoding::text::encode;
use tonic::{Code, Status};
use caching_rust_util::caching_latency_histogram::{
    CachingLatencyHistogram,
    CachingLatencyHistogramLabels,
};
#[allow(unused_imports)] // Allow extension to `tonic::Code` type.
use caching_rust_util::code_ext::CodeExt;
use caching_rust_util_testing::assertion_waiter::AssertionWaiter;
use caching_rust_util_testing::fake_clock::FakeClock;
use raf_metrics::histogram;
use raf_metrics_registry::PROMETHEUS_REGISTRY;
use raf_metrics_testing::{
    get_histogram_count as get_metric_histogram_count,
    ResetMetric,
};

// Name for the histogram metric.
const METRIC: &str = "softstore_testlib_op_latency";

const METRIC_LABEL_NAMES: [&str; 2] = ["namespace", "softmap"];
const NAMESPACE: &str = "MyNamespace";
const SOFTMAP: &str = "MySoftmap";
const METRIC_LABELS: [&str; 2] = [NAMESPACE, SOFTMAP];

/// A small set of buckets for use in many tests so that our tests will be comprehensible.
/// (We also have a "Test standard buckets" test that verifies that latencies are correctly
/// bucketed in the standard buckets.)
const BUCKETS_SECS: [f64; 4] = [0.001, 0.003, 0.005, 0.007];

// Expected values for the status label.
const SUCCESS: &str = "success";
const FAILURE: &str = "failure";

// Expected values for the operation label
const WRITE: &str = "write";

histogram!(
    SOFTSTORE_TESTLIB_OP_LATENCY,
    "Operation latency histogram for caching component",
    BUCKETS_SECS,
    CachingLatencyHistogramLabels
);
histogram!(
    SOFTSTORE_TESTLIB_STANDARD_OP_LATENCY,
    "Operation latency histogram for caching component",
    CachingLatencyHistogram::low_latency_bucket_secs(),
    CachingLatencyHistogramLabels
);

/// Wrapper for histogram assertions that factors out common parameters
/// that are fixed for these tests, and waits for it to eventually pass.
async fn await_histogram_counts(
    expected_bucket_counts: &[u64],
    metric: &str,
    operation: &str,
    status: &str,
    grpc_status_opt: Option<&str>,
    buckets: &[f64],
) {
    let mut labels = vec![("operation", operation), ("status", status)];
    labels.extend(METRIC_LABEL_NAMES.into_iter().zip(METRIC_LABELS));
    if let Some(grpc_status) = grpc_status_opt {
        labels.push(("grpc_status", grpc_status));
    }

    AssertionWaiter::new(format!("Waiting for expected bucket counts {expected_bucket_counts:?}"))
        .await_condition(|| {
            let mut encoded_registry = String::new();
            encode(&mut encoded_registry, &PROMETHEUS_REGISTRY.lock().unwrap()).unwrap();
            // Compare boundaries numerically: RAF's text lookup uses a different floating-point
            // formatter from the Prometheus encoder. Sum matching series as Scala's helper does.
            let mut actual_counts = vec![0; expected_bucket_counts.len()];
            for line in encoded_registry.lines() {
                let Some(sample) = line.strip_prefix(&format!("{metric}_bucket{{")) else {
                    continue;
                };
                let (sample_labels, value) = sample.split_once("} ").unwrap();
                if !labels.iter().all(|(name, value)| {
                    sample_labels
                        .split(',')
                        .any(|label| label == format!("{name}=\"{value}\""))
                }) {
                    continue;
                }
                let bound: f64 = sample_labels
                    .split(',')
                    .find_map(|label| {
                        label
                            .strip_prefix("le=\"")
                            .map(|value| value.trim_end_matches('"'))
                    })
                    .unwrap()
                    .parse()
                    .unwrap();
                let index = buckets
                    .iter()
                    .copied()
                    .chain([f64::INFINITY])
                    .position(|bucket| bucket == bound)
                    .expect("Unexpected histogram bucket");
                actual_counts[index] += value.parse::<u64>().unwrap();
            }
            assert_eq!(actual_counts, expected_bucket_counts);
        })
        .await;
}

/// Counts observations in the test histogram for the given operation and success/failure status.
/// Uses the fixed namespace and softmap labels, and includes all gRPC status codes unless one is
/// specified.
fn get_histogram_count(operation: &str, status: &str, grpc_status_opt: Option<&str>) -> u64 {
    let min_status_code: i32 = Code::Ok as i32;
    let max_status_code: i32 = Code::Unauthenticated as i32;
    (min_status_code..=max_status_code)
        .map(Code::from_i32)
        .filter(|code| grpc_status_opt.is_none_or(|grpc_status| code.as_upper_str() == grpc_status))
        .map(|code| {
            get_metric_histogram_count(METRIC, &metric_labels(operation, status, code)).unwrap_or(0)
        })
        .sum()
}

/// Complete labels in the order required by RAF's metric lookup helpers.
fn metric_labels<'a>(operation: &'a str, status: &'a str, code: Code) -> Vec<(&'a str, &'a str)> {
    let mut labels = vec![
        ("operation", operation),
        ("status", status),
        ("grpc_status", code.as_upper_str()),
        (
            "error_code",
            if code == Code::Ok {
                ""
            } else {
                code.error_code()
            },
        ),
    ];
    labels.extend(METRIC_LABEL_NAMES.into_iter().zip(METRIC_LABELS));
    labels
}

#[tokio::test]
async fn test_latency_metrics_for_successful_synchronous_call() {
    // Test plan: Record latency for a successful synchronous thunk.
    // Verify that the latency histogram metrics are correctly recorded. Record latency metrics for
    // a second synchronous call in a different set of bucket and verify again that the counts are
    // correct.
    let clock = FakeClock::create(SystemTime::UNIX_EPOCH);
    SOFTSTORE_TESTLIB_OP_LATENCY.reset();
    let histogram = CachingLatencyHistogram::create_for_test(
        &SOFTSTORE_TESTLIB_OP_LATENCY,
        clock.clone().into_clock(),
        METRIC_LABEL_NAMES.to_vec(),
    );

    let result = histogram.record_latency_sync(
        WRITE,
        |_| METRIC_LABELS.map(str::to_string).to_vec(),
        || {
            clock.advance(Duration::from_millis(4));
            Ok("result")
        },
    );
    assert_eq!(result.unwrap(), "result");

    // The total count should have been incremented.
    assert_eq!(get_histogram_count(WRITE, SUCCESS, Some("OK")), 1);

    // Since the latency was 4 milliseconds, we expect the following buckets to be incremented:
    // <=5 (bucket index 3), <=7 (index 4), and <= +Inf (index 5).
    await_histogram_counts(&[0, 0, 1, 1, 1], METRIC, WRITE, SUCCESS, Some("OK"), &BUCKETS_SECS)
        .await;

    // Make sure the failure bucket metrics did not change.
    assert_eq!(get_histogram_count(WRITE, FAILURE, None), 0);

    // Simulate another call that takes 6 milliseconds.
    let result2 = histogram.record_latency_sync(
        WRITE,
        |_| METRIC_LABELS.map(str::to_string).to_vec(),
        || {
            clock.advance(Duration::from_millis(6));
            Ok("result2")
        },
    );
    assert_eq!(result2.unwrap(), "result2");

    // This should increment the counts only in the last 2 buckets (indexes 4 and 5).
    await_histogram_counts(&[0, 0, 1, 2, 2], METRIC, WRITE, SUCCESS, Some("OK"), &BUCKETS_SECS)
        .await;
}

#[tokio::test]
async fn test_latency_metrics_for_failing_synchronous_thunk() {
    // Test plan: Record latency for a synchronous thunk that fails by throwing an exception.
    // Verify that the operation metrics are correctly recorded.
    let clock = FakeClock::create(SystemTime::UNIX_EPOCH);
    SOFTSTORE_TESTLIB_OP_LATENCY.reset();
    let histogram = CachingLatencyHistogram::create_for_test(
        &SOFTSTORE_TESTLIB_OP_LATENCY,
        clock.clone().into_clock(),
        METRIC_LABEL_NAMES.to_vec(),
    );

    let status = histogram
        .record_latency_sync::<()>(
            WRITE,
            |_| METRIC_LABELS.map(str::to_string).to_vec(),
            || {
                clock.advance(Duration::from_micros(500));
                Err(Status::internal("Internal error thrown"))
            },
        )
        .unwrap_err();
    assert_eq!(status.code(), Code::Internal);
    assert_eq!(status.message(), "Internal error thrown");

    // failure bucket for grpc_status = INTERNAL should increase
    await_histogram_counts(
        &[1, 1, 1, 1, 1],
        METRIC,
        WRITE,
        FAILURE,
        Some("INTERNAL"),
        &BUCKETS_SECS,
    )
    .await;

    // failure bucket for all failures should be the same as INTERNAL only
    await_histogram_counts(&[1, 1, 1, 1, 1], METRIC, WRITE, FAILURE, None, &BUCKETS_SECS).await;
    assert_eq!(
        get_metric_histogram_count(
            METRIC,
            &[
                ("operation", WRITE),
                ("status", FAILURE),
                ("grpc_status", "INTERNAL"),
                ("error_code", "INTERNAL_ERROR"),
                ("namespace", NAMESPACE),
                ("softmap", SOFTMAP)
            ]
        ),
        Some(1)
    );
}

#[tokio::test]
async fn test_standard_buckets() {
    // Test plan: Create a CachingLatencyHistogram using the standard buckets.
    // Make sure that the standard buckets occur at the expected intervals. Record a short
    // duration operation and make sure we got the units right on the buckets.
    let clock = FakeClock::create(SystemTime::UNIX_EPOCH);
    SOFTSTORE_TESTLIB_STANDARD_OP_LATENCY.reset();
    let bucket_secs: Vec<f64> = CachingLatencyHistogram::low_latency_bucket_secs().collect();
    let histogram = CachingLatencyHistogram::create_for_test(
        &SOFTSTORE_TESTLIB_STANDARD_OP_LATENCY,
        clock.clone().into_clock(),
        METRIC_LABEL_NAMES.to_vec(),
    );
    let call_duration = Duration::from_micros(125);
    histogram
        .record_latency_sync(
            WRITE,
            |_| METRIC_LABELS.map(str::to_string).to_vec(),
            || {
                clock.advance(call_duration);
                Ok("result")
            },
        )
        .unwrap();

    // The total count should have been incremented.
    assert_eq!(
        get_metric_histogram_count(
            "softstore_testlib_standard_op_latency",
            &metric_labels(WRITE, SUCCESS, Code::Ok)
        ),
        Some(1)
    );

    // Make sure that we have buckets at the expected intervals
    // (e.g. 10 microsecond intervals up to 100 microseconds.)
    assert_eq!(Duration::from_secs_f64(bucket_secs[0]), Duration::from_micros(10));
    assert_eq!(bucket_secs.len(), 17);
    let expected_bucket_ratio = 10.0_f64.powf(0.4);
    for i in 1..13 {
        assert!((bucket_secs[i] / bucket_secs[i - 1] - expected_bucket_ratio).abs() < 1e-12);
    }
    assert!((bucket_secs[13] / bucket_secs[12] - 10.0_f64.powf(0.6)).abs() < 1e-12);
    let expected_big_bucket_ratio = 10.0_f64.powf(0.8);
    for i in 14..bucket_secs.len() {
        assert!((bucket_secs[i] / bucket_secs[i - 1] - expected_big_bucket_ratio).abs() < 1e-12);
    }

    let mut expected_counts: Vec<u64> = bucket_secs
        .iter()
        .map(|bucket_less_than_duration| {
            u64::from(call_duration.as_secs_f64() < *bucket_less_than_duration)
        })
        .collect();
    expected_counts.push(1); // Append the expected +Inf count which is always 1.

    await_histogram_counts(
        &expected_counts,
        "softstore_testlib_standard_op_latency",
        WRITE,
        SUCCESS,
        Some("OK"),
        &bucket_secs,
    )
    .await;
}

#[test]
fn test_wrong_number_of_labels() {
    // Test plan: Provide wrong number of labels for latency observation and verify that an
    // error is returned.
    let clock = FakeClock::create(SystemTime::UNIX_EPOCH);
    SOFTSTORE_TESTLIB_OP_LATENCY.reset();
    let histogram = CachingLatencyHistogram::create_for_test(
        &SOFTSTORE_TESTLIB_OP_LATENCY,
        clock.clone().into_clock(),
        METRIC_LABEL_NAMES.to_vec(),
    );

    for labels in [
        vec![NAMESPACE.to_string()],
        vec![NAMESPACE.to_string(), SOFTMAP.to_string(), "extra_label".to_string()],
    ] {
        let status = histogram
            .record_latency_sync(
                WRITE,
                |_| labels,
                || {
                    clock.advance(Duration::from_millis(4));
                    Ok(())
                },
            )
            .unwrap_err();
        assert_eq!(status.code(), Code::InvalidArgument);
        assert_eq!(status.message(), "Incorrect number of labels.");
    }
    assert_eq!(get_histogram_count(WRITE, SUCCESS, None), 0);
    assert_eq!(get_histogram_count(WRITE, FAILURE, None), 0);
}

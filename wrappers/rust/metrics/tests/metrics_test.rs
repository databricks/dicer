use std::sync::{Arc, Barrier};
use std::thread::{self, Scope};

use prometheus_client::encoding::EncodeLabelSet;
use prometheus_client::metrics::histogram::Histogram;
use universe_raf_metrics::{counter, gauge, histogram, metric};
use universe_raf_metrics_testing::{
    encode_metrics_for_test,
    get_counter_value,
    get_histogram_bucket,
    get_histogram_count,
    get_histogram_sum,
    verify_encoded_counter,
    verify_encoded_gauge,
    verify_encoded_histogram,
};

#[derive(Clone, Debug, Hash, PartialEq, Eq, EncodeLabelSet)]
struct RequestLabels {
    method: String,
    status: String,
}

counter!(TEST_REQUESTS_TOTAL, "Number of simulated requests", RequestLabels);
counter!(TEST_STARTUPS, "Number of simulated process startups");
counter!(TEST_CPU_TIME_SECONDS_TOTAL, "Simulated CPU time in seconds", f64);
counter!(TEST_REVENUE_TOTAL, "Simulated revenue in dollars", RequestLabels, f64);
counter!(
    TEST_CONCURRENT_FIRST_ACCESS_TOTAL,
    "Number of simulated concurrent first accesses",
    RequestLabels
);
gauge!(TEST_ACTIVE_REQUESTS, "Number of simulated active requests", RequestLabels);
gauge!(TEST_LAZY_REGISTRATION, "Whether the simulated lazy metric was initialized", RequestLabels);
gauge!(TEST_READY, "Whether the simulated process is ready");
gauge!(TEST_CPU_TEMPERATURE, "Simulated CPU temperature in Celsius", f64);
gauge!(TEST_CORE_TEMPERATURE, "Simulated CPU core temperature in Celsius", RequestLabels, f64);
histogram!(TEST_PAYLOAD_SIZE_BYTES, "Simulated request payload size in bytes", [10.0, 100.0]);
histogram!(
    TEST_LATENCY_MS,
    "Simulated request latency in milliseconds",
    [1.0, 10.0],
    RequestLabels
);
metric!(
    TEST_DIRECT_GAUGE,
    prometheus_client::metrics::gauge::Gauge,
    default(),
    "test_direct_gauge",
    "Simulated gauge defined through metric"
);

#[test]
fn counters_with_and_without_total_suffix_encode_exactly_one_suffix() {
    // Test plan: Verify that counter names with and without `_TOTAL` encode exactly one `_total`
    // suffix. Do this by incrementing both forms and checking their metadata, labels, and values.
    let labels = RequestLabels {
        method: "get".to_owned(),
        status: "ok".to_owned(),
    };
    TEST_REQUESTS_TOTAL.get_or_create_owned(&labels).inc_by(2);
    TEST_STARTUPS.inc();

    verify_encoded_counter(
        "test_requests_total",
        "Number of simulated requests",
        &[("method", "get"), ("status", "ok")],
        2,
    );
    verify_encoded_counter("test_startups_total", "Number of simulated process startups", &[], 1);
}

#[test]
fn integer_gauges_encode_labeled_and_unlabeled_values() {
    // Test plan: Verify that integer gauges encode labeled and unlabeled values. Do this by setting
    // both forms and checking their metadata, labels, and signed values.
    let labels = RequestLabels {
        method: "list".to_owned(),
        status: "pending".to_owned(),
    };
    TEST_ACTIVE_REQUESTS.get_or_create_owned(&labels).set(-3);
    TEST_READY.set(1);

    verify_encoded_gauge(
        "test_active_requests",
        "Number of simulated active requests",
        &[("method", "list"), ("status", "pending")],
        -3,
    );
    verify_encoded_gauge("test_ready", "Whether the simulated process is ready", &[], 1);
}

#[test]
fn floating_point_counters_and_gauges_encode_labeled_and_unlabeled_values() {
    // Test plan: Verify that every floating-point counter and gauge form records values. Do this by
    // updating labeled and unlabeled metrics and checking their exact encoded samples.
    let labels = RequestLabels {
        method: "calculate".to_owned(),
        status: "ok".to_owned(),
    };
    TEST_CPU_TIME_SECONDS_TOTAL.inc_by(1.25);
    TEST_REVENUE_TOTAL.get_or_create_owned(&labels).inc_by(2.5);
    TEST_CPU_TEMPERATURE.set(65.5);
    TEST_CORE_TEMPERATURE
        .get_or_create_owned(&labels)
        .set(70.25);

    let encoded_metrics: String = encode_metrics_for_test();
    assert!(encoded_metrics.contains("test_cpu_time_seconds_total 1.25"));
    assert!(encoded_metrics.contains(r#"test_revenue_total{method="calculate",status="ok"} 2.5"#));
    assert!(encoded_metrics.contains("test_cpu_temperature 65.5"));
    assert!(
        encoded_metrics.contains(r#"test_core_temperature{method="calculate",status="ok"} 70.25"#)
    );
}

#[test]
fn histograms_encode_expected_sum_count_and_buckets() {
    // Test plan: Verify that labeled and unlabeled histograms encode complete distributions. Do
    // this by recording observations and checking their metadata, sum, count, and cumulative
    // buckets.
    TEST_PAYLOAD_SIZE_BYTES.observe(5.0);
    TEST_PAYLOAD_SIZE_BYTES.observe(50.0);
    TEST_PAYLOAD_SIZE_BYTES.observe(150.0);
    let labels = RequestLabels {
        method: "put".to_owned(),
        status: "ok".to_owned(),
    };
    let labeled_histogram: Histogram = TEST_LATENCY_MS.get_or_create_owned(&labels);
    labeled_histogram.observe(0.5);
    labeled_histogram.observe(7.0);
    labeled_histogram.observe(12.0);

    verify_encoded_histogram(
        "test_payload_size_bytes",
        "Simulated request payload size in bytes",
        &[],
        205.0,
        3,
        &[(10.0, 1), (100.0, 2), (f64::INFINITY, 3)],
    );
    let encoded_labels: [(&str, &str); 2] = [("method", "put"), ("status", "ok")];
    verify_encoded_histogram(
        "test_latency_ms",
        "Simulated request latency in milliseconds",
        &encoded_labels,
        19.5,
        3,
        &[(1.0, 1), (10.0, 2), (f64::INFINITY, 3)],
    );
    assert_eq!(get_histogram_sum("test_latency_ms", &encoded_labels), Some(19.5));
    assert_eq!(get_histogram_count("test_latency_ms", &encoded_labels), Some(3));
    assert_eq!(get_histogram_bucket("test_latency_ms", &encoded_labels, 10.0), Some(2));
}

#[test]
fn metrics_register_only_after_first_access() {
    // Test plan: Verify that metrics register lazily. Do this by checking that a unique metric is
    // absent before its first access and present after it is initialized.
    let encoded_before_access: String = encode_metrics_for_test();
    assert!(!encoded_before_access.contains("test_lazy_registration"));

    let labels = RequestLabels {
        method: "initialize".to_owned(),
        status: "ready".to_owned(),
    };
    TEST_LAZY_REGISTRATION.get_or_create_owned(&labels).set(1);

    verify_encoded_gauge(
        "test_lazy_registration",
        "Whether the simulated lazy metric was initialized",
        &[("method", "initialize"), ("status", "ready")],
        1,
    );
}

#[test]
fn concurrent_first_access_registers_one_shared_counter() {
    // Test plan: Verify that concurrent first access registers one shared counter. Do this by
    // releasing scoped threads from a barrier and checking one registration and the combined value.
    const NUM_THREADS: usize = 4;
    let start_barrier: Arc<Barrier> = Arc::new(Barrier::new(NUM_THREADS));
    let labels = RequestLabels {
        method: "get".to_owned(),
        status: "ok".to_owned(),
    };

    thread::scope(|scope: &Scope| {
        for _ in 0..NUM_THREADS {
            let start_barrier: Arc<Barrier> = Arc::clone(&start_barrier);
            let labels: RequestLabels = labels.clone();
            scope.spawn(move || {
                start_barrier.wait();
                TEST_CONCURRENT_FIRST_ACCESS_TOTAL
                    .get_or_create_owned(&labels)
                    .inc();
            });
        }
    });

    let encoded_metrics: String = encode_metrics_for_test();
    assert_eq!(
        encoded_metrics
            .matches("# HELP test_concurrent_first_access ")
            .count(),
        1
    );
    assert_eq!(
        encoded_metrics
            .matches("# TYPE test_concurrent_first_access counter")
            .count(),
        1
    );
    assert_eq!(
        get_counter_value(
            "test_concurrent_first_access_total",
            &[("method", "get"), ("status", "ok")],
        ),
        Some(NUM_THREADS as u64)
    );
}

#[test]
fn metric_macro_registers_the_requested_metric_type() {
    // Test plan: Verify that metric! registers its requested metric type. Do this by defining a
    // gauge directly through the macro and checking its metadata and value after initialization.
    TEST_DIRECT_GAUGE.set(7);

    verify_encoded_gauge("test_direct_gauge", "Simulated gauge defined through metric", &[], 7);
}

#[test]
fn registry_encoding_includes_initialized_metrics() {
    // Test plan: Verify that the test-only encoding API includes initialized metrics. Do this by
    // initializing a gauge and checking its encoded type and current value.
    TEST_READY.set(1);

    let encoded_metrics: String = encode_metrics_for_test();

    assert!(encoded_metrics.contains("# TYPE test_ready gauge"));
    assert!(encoded_metrics.contains("test_ready 1"));
}

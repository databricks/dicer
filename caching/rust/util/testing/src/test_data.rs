use std::sync::LazyLock;

use caching_util_test_proto_test_data_proto_buffa::databricks::caching::test::{
    BuildInfoTestDataP,
    EwmaAccumulatorTestDataP,
    EwmaAmplifierTestDataP,
    EwmaCounterTestDataP,
    EwmaQuantileSummariesTestDataP,
    ExponentialBackoffTestDataP,
    KubernetesClusterUriTestDataP,
    LossyEwmaCounterTestDataP,
    ProtocolDurationTestDataP,
    QuantileSummariesTestDataP,
    RegionUriTestDataP,
    Rfc1123TestDataP,
};

use super::parse_buffa_test_data;

/// Test data for RFC 1123 validation suites.
pub static RFC1123_TEST_DATA: LazyLock<Rfc1123TestDataP> =
    LazyLock::new(|| parse_buffa_test_data("caching/util/test/data/rfc1123_test_data.textproto"));

/// Test data shared by the Scala and Rust KubernetesClusterUri suites.
pub static KUBERNETES_CLUSTER_URI_TEST_DATA: LazyLock<KubernetesClusterUriTestDataP> =
    LazyLock::new(|| {
        parse_buffa_test_data("caching/util/test/data/kubernetes_cluster_uri_test_data.textproto")
    });

/// Test data shared by the Scala and Rust RegionUri suites.
pub static REGION_URI_TEST_DATA: LazyLock<RegionUriTestDataP> = LazyLock::new(|| {
    parse_buffa_test_data("caching/util/test/data/region_uri_test_data.textproto")
});

/// Test data for build info validation suites.
pub static BUILD_INFO_TEST_DATA: LazyLock<BuildInfoTestDataP> = LazyLock::new(|| {
    parse_buffa_test_data("caching/util/test/data/build_info_test_data.textproto")
});

/// Test data for EwmaAccumulator validation suites.
pub static EWMA_ACCUMULATOR_TEST_DATA: LazyLock<EwmaAccumulatorTestDataP> = LazyLock::new(|| {
    parse_buffa_test_data("caching/util/test/data/ewma_accumulator_test_data.textproto")
});

/// Test data for EwmaAmplifier validation suites.
pub static EWMA_AMPLIFIER_TEST_DATA: LazyLock<EwmaAmplifierTestDataP> = LazyLock::new(|| {
    parse_buffa_test_data("caching/util/test/data/ewma_amplifier_test_data.textproto")
});

/// Test data for EwmaCounter validation suites.
pub static EWMA_COUNTER_TEST_DATA: LazyLock<EwmaCounterTestDataP> = LazyLock::new(|| {
    parse_buffa_test_data("caching/util/test/data/ewma_counter_test_data.textproto")
});

/// Test data for LossyEwmaCounter validation suites.
pub static LOSSY_EWMA_COUNTER_TEST_DATA: LazyLock<LossyEwmaCounterTestDataP> =
    LazyLock::new(|| {
        parse_buffa_test_data("caching/util/test/data/lossy_ewma_counter_test_data.textproto")
    });

/// Test data for ProtocolDuration validation suites.
pub static PROTOCOL_DURATION_TEST_DATA: LazyLock<ProtocolDurationTestDataP> = LazyLock::new(|| {
    parse_buffa_test_data("caching/util/test/data/protocol_duration_test_data.textproto")
});

/// Test data for ExponentialBackoff validation suites.
pub static EXPONENTIAL_BACKOFF_TEST_DATA: LazyLock<ExponentialBackoffTestDataP> =
    LazyLock::new(|| {
        parse_buffa_test_data("caching/util/test/data/exponential_backoff_test_data.textproto")
    });

/// Test data for NonLossyQuantileSummaries and QuantileSummaries validation suites.
pub static QUANTILE_SUMMARIES_TEST_DATA: LazyLock<QuantileSummariesTestDataP> =
    LazyLock::new(|| {
        parse_buffa_test_data("caching/util/test/data/quantile_summaries_test_data.textproto")
    });

/// Test data for EwmaQuantileSummaries validation suites.
pub static EWMA_QUANTILE_SUMMARIES_TEST_DATA: LazyLock<EwmaQuantileSummariesTestDataP> =
    LazyLock::new(|| {
        parse_buffa_test_data("caching/util/test/data/ewma_quantile_summaries_test_data.textproto")
    });

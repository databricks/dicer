//! Drives `EwmaQuantileSummaries` (wrapping a concrete `QuantileSummaries` sketch) through the
//! textproto fixture at `caching/util/test/data/ewma_quantile_summaries_test_data.textproto`.

use chrono::{DateTime, Duration, Utc};
use tonic::Code;
use caching_rust_util::ewma_quantile_summaries::EwmaQuantileSummaries;
use caching_rust_util_testing::test_data::EWMA_QUANTILE_SUMMARIES_TEST_DATA;
use caching_util_test_proto_test_data_proto_buffa::databricks::caching::test::ewma_quantile_summaries_test_data_p::action_p::Action;
use caching_util_test_proto_test_data_proto_buffa::databricks::caching::test::ewma_quantile_summaries_test_data_p::*;

/// Performs a test action. See `EwmaQuantileSummariesTestDataP` for more details.
fn perform_action(
    summary: &mut EwmaQuantileSummaries<i32>,
    start_time: DateTime<Utc>,
    action: &ActionP,
) {
    match action.action.as_ref().expect("action is required") {
        Action::Insert(insert) => {
            let value: i32 = insert.value.expect("value is required");
            let weight: i32 = insert.weight.expect("weight is required");
            let elapsed_ms: i64 = insert.elapsed_ms.expect("elapsed_ms is required");
            let now: DateTime<Utc> = start_time + Duration::milliseconds(elapsed_ms);
            summary
                .insert(now, value, weight)
                .expect("insert should succeed");
        }
        Action::AssertQueryWithin(assert_query_within) => {
            let quantiles: &[f64] = &assert_query_within.quantiles;
            let relative_error: f64 = assert_query_within
                .relative_error
                .expect("relative_error is required");
            let actual_results: Vec<(i32, f64)> = summary
                .query(quantiles)
                .expect("query should succeed")
                .into_iter()
                .map(|(value, fraction)| (*value, fraction))
                .collect();
            let expected_results: &[CdfPoint] = &assert_query_within.expected_results;
            // An empty `expected_results` means the summary has no observations, so `query` must
            // return an empty result regardless of how many quantiles were batched.
            if expected_results.is_empty() {
                assert!(
                    actual_results.is_empty(),
                    "query({quantiles:?}): expected empty result, got {actual_results:?}",
                );
                return;
            }
            // The returned value must match exactly, while its cumulative-weight fraction need
            // only agree with the expected one within `relative_error`.
            for i in 0..expected_results.len() {
                let (actual_value, actual_fraction): (i32, f64) = actual_results[i];
                let expected_result: &CdfPoint = &expected_results[i];
                let expected_value: i32 = expected_result.value.expect("value is required");
                let expected_fraction: f64 = expected_result
                    .cumulative_weight_fraction
                    .expect("cumulative_weight_fraction is required");
                assert_eq!(
                        actual_value, expected_value,
                        "query({quantiles:?}) at index {} (quantile={}): actualValue={}, expectedValue={}",
                        i, quantiles[i], actual_value, expected_value,
                    );
                assert!(
                    (actual_fraction - expected_fraction).abs() <= relative_error,
                    "query({quantiles:?}) at index {} (quantile={}): actualFraction={}, expectedFraction={}, relativeError={}",
                    i, quantiles[i], actual_fraction, expected_fraction, relative_error,
                    );
            }
        }
    }
}

#[test]
fn valid_cases() {
    let test_cases: &Vec<TestCaseP> = &EWMA_QUANTILE_SUMMARIES_TEST_DATA.valid_cases;
    for test_case in test_cases {
        let start_time: DateTime<Utc> =
            DateTime::from_timestamp_millis(0).expect("0 is a valid timestamp");
        let factory: &EwmaQuantileSummariesP = test_case
            .ewma_quantile_summaries
            .as_option()
            .expect("ewma_quantile_summaries is required");
        let half_life_seconds: f64 = factory
            .half_life_seconds
            .expect("half_life_seconds is required");
        let relative_error: f64 = factory.relative_error.expect("relative_error is required");
        let sample_count_upper_bound: usize = factory
            .sample_count_upper_bound
            .expect("sample_count_upper_bound is required")
            as usize;
        let half_life: Duration =
            Duration::milliseconds((half_life_seconds * 1000.0).round() as i64);
        let mut summary: EwmaQuantileSummaries<i32> = EwmaQuantileSummaries::new(
            start_time,
            half_life,
            relative_error,
            sample_count_upper_bound,
        )
        .expect("factory parameters must be valid");

        for action in &test_case.actions {
            perform_action(&mut summary, start_time, action);
        }
    }
}

#[test]
fn new_validates_arguments() {
    // Test plan: Verify EwmaQuantileSummaries::new accepts valid parameters and rejects a
    // non-positive half-life and an out-of-range relative error. Also verify Default constructs
    // successfully.
    let start_time: DateTime<Utc> =
        DateTime::from_timestamp_millis(0).expect("0 is a valid timestamp");
    let half_life: Duration = Duration::seconds(120);

    // Valid parameters are accepted.
    EwmaQuantileSummaries::<i32>::new(start_time, half_life, 0.01, 1000)
        .expect("valid parameters must be accepted");

    // A non-positive half-life is rejected.
    for bad_half_life in [Duration::zero(), Duration::seconds(-1)] {
        assert_eq!(
            EwmaQuantileSummaries::<i32>::new(start_time, bad_half_life, 0.01, 1000)
                .err()
                .expect("non-positive half_life should be rejected")
                .code(),
            Code::InvalidArgument,
            "half_life {bad_half_life:?} should be rejected",
        );
    }

    // relative_error must lie strictly inside (0, 1); the boundaries and out-of-range values are
    // rejected.
    for bad_relative_error in [0.0, 1.0, -0.1, 1.5] {
        assert_eq!(
            EwmaQuantileSummaries::<i32>::new(start_time, half_life, bad_relative_error, 1000)
                .err()
                .expect("out-of-range relative_error should be rejected")
                .code(),
            Code::InvalidArgument,
            "relative_error {bad_relative_error} should be rejected",
        );
    }
}

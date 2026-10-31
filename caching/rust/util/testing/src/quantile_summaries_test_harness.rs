use std::collections::BTreeMap;

use rand::rngs::StdRng;
use rand::{Rng, SeedableRng};
use tonic::{Code, Status};
use caching_rust_util::caching_error_code::{
    Severity,
    QUANTILE_SUMMARIES_ACCURACY_INVARIANT_BROKEN,
};
use caching_rust_util::caching_errors;
use caching_rust_util::quantile_summaries;
use caching_rust_util::quantile_summaries::QuantileSummaries;
use caching_util_test_proto_test_data_proto_buffa::databricks::caching::test::quantile_summaries_test_data_p::{
    action_p::Action,
    distribution_p::Pattern,
    AssertQueryEqualsP,
    AssertQueryWithinErrorBoundP,
    DistributionP,
    InsertManyP,
    InsertP,
    TestCaseP,
    RescaleP,
};
use crate::non_lossy_quantile_summaries::NonLossyQuantileSummaries;

/// Default relative error when a case sets no relative_error of its own.
const DEFAULT_RELATIVE_ERROR: f64 = 0.01;

/// Default sample count upper bound when a case sets no sample_count_upper_bound of its own.
const DEFAULT_SAMPLE_COUNT_UPPER_BOUND: i32 = 2000;

/// Runs one test case and asserts its outcome matches the case's declaration:
///
/// - a valid case (cases without error_message_opt defined) must complete every action;
/// - an invalid case must fail with the declared error_message_opt.
///
/// `use_lossy_quantile_summaries` selects whether the lossy [`QuantileSummaries`] or
/// [`NonLossyQuantileSummaries`] will be used.
pub fn run_test_case(test_case: &TestCaseP, use_lossy_quantile_summaries: bool) {
    let test_name: &str = test_case
        .test_case_name
        .as_deref()
        .expect("test_name is required");
    let expected_error_opt: Option<&str> = test_case.error_message_opt.as_deref();

    // A case with no actions only validates the sketch's `new(relative_error)` construction; the
    // parameterless non-lossy oracle has no equivalent, so it skips such cases.
    if !use_lossy_quantile_summaries && expected_error_opt.is_some() && test_case.actions.is_empty()
    {
        return;
    }

    let relative_error: f64 = test_case
        .quantile_summaries
        .relative_error
        .unwrap_or(DEFAULT_RELATIVE_ERROR);
    let sample_count_upper_bound: usize = test_case
        .quantile_summaries
        .sample_count_upper_bound
        .unwrap_or(DEFAULT_SAMPLE_COUNT_UPPER_BOUND)
        as usize;
    let mut summary: QuantileSummariesWrapper = if use_lossy_quantile_summaries {
        match QuantileSummaries::<i32>::new(relative_error, sample_count_upper_bound) {
            Ok(summary) => QuantileSummariesWrapper::Lossy(summary),
            Err(status) => {
                assert_expected_error(&status, expected_error_opt, test_name);
                return;
            }
        }
    } else {
        QuantileSummariesWrapper::NonLossy(NonLossyQuantileSummaries::<i32>::new())
    };

    // Force compression deliberately breaks the per-sample uncertainty invariant, so a case that
    // expects a force merge disables the per-insert uncertainty assertion (the sentinel guarantee
    // still holds). Such a case declares its intent with an `assert_force_merged` action.
    let assert_uncertainty: bool = !test_case
        .actions
        .iter()
        .any(|action_p| matches!(action_p.action, Some(Action::AssertForceMerged(_))));

    // The force-compress alert counter value at the last `assert_force_merged` checkpoint (captured
    // now, before any action runs). The counter is process-global, so we compare against this
    // baseline rather than an absolute value. Only read for the lossy sketch.
    let mut prev_force_merge_count: u64 = force_merge_alert_count();

    // The exact distribution of the inserted values to their weights, used to verify query results.
    let mut value_to_weights_truth: BTreeMap<i32, f64> = BTreeMap::new();

    for action_p in &test_case.actions {
        let action: &Action = action_p
            .action
            .as_ref()
            .unwrap_or_else(|| panic!("test '{}': action must be set", test_name));
        // Perform each action. See QuantileSummariesTestDataP for the meaning of each action.
        match action {
            Action::Insert(insert) => {
                let InsertP { value, weight, .. } = insert.as_ref();
                let (value, weight): (i32, f64) = (value.unwrap(), weight.unwrap());

                if let Err(status) = summary.insert(value, weight, assert_uncertainty) {
                    assert_expected_error(&status, expected_error_opt, test_name);
                    return;
                }
                *value_to_weights_truth.entry(value).or_insert(0.0) += weight;
            }
            Action::InsertMany(insert_many) => {
                for (value, weight) in generate_insert_many_test_data(insert_many.as_ref()) {
                    if let Err(status) = summary.insert(value, weight, assert_uncertainty) {
                        assert_expected_error(&status, expected_error_opt, test_name);
                        return;
                    }
                    *value_to_weights_truth.entry(value).or_insert(0.0) += weight;
                }
            }
            Action::Rescale(rescale) => {
                let RescaleP { factor, .. } = rescale.as_ref();
                let factor: f64 = factor.unwrap();
                if let Err(status) = summary.rescale_weights(factor, assert_uncertainty) {
                    assert_expected_error(&status, expected_error_opt, test_name);
                    return;
                }
                for weight in value_to_weights_truth.values_mut() {
                    *weight *= factor;
                }
            }
            Action::AssertForceMerged(_) => match &summary {
                QuantileSummariesWrapper::Lossy(_) => {
                    // Force compression increments the accuracy-invariant alert counter. Assert it
                    // advanced since the previous checkpoint, i.e. the fallback fired at least once.
                    let force_merge_count: u64 = force_merge_alert_count();
                    assert!(
                        force_merge_count > prev_force_merge_count,
                        "test '{}': expected the force-compress fallback to fire, but the \
                         accuracy-invariant alert did not increment (still {})",
                        test_name,
                        prev_force_merge_count
                    );
                    prev_force_merge_count = force_merge_count;
                }
                QuantileSummariesWrapper::NonLossy(_) => {
                    // The exact oracle never force-merges, so it ignores this action.
                }
            },
            Action::AssertQueryEquals(assert_query_equals) => {
                let AssertQueryEqualsP {
                    quantiles,
                    expected_results,
                    ..
                } = assert_query_equals.as_ref();
                let actual_results: Vec<(&i32, f64)> = match summary.query(quantiles) {
                    Ok(actual_results) => actual_results,
                    Err(status) => {
                        assert_expected_error(&status, expected_error_opt, test_name);
                        return;
                    }
                };
                if expected_results.is_empty() {
                    // Empty `expected_results` means the summary has no observations, so `query`
                    // must return an empty sequence regardless of how many quantiles were asked.
                    assert!(
                        actual_results.is_empty(),
                        "test '{}': expected an empty result",
                        test_name
                    );
                } else {
                    assert_eq!(
                        actual_results.len(),
                        expected_results.len(),
                        "test '{}': one answer per queried quantile",
                        test_name
                    );
                    for (expected_cdf_point, &(actual_value, actual_fraction)) in
                        expected_results.iter().zip(&actual_results)
                    {
                        assert_eq!(
                            *actual_value,
                            expected_cdf_point
                                .value
                                .expect("expected value is required"),
                            "test '{}': value mismatch",
                            test_name
                        );
                        assert!(
                            (actual_fraction
                                - expected_cdf_point
                                    .cumulative_weight_fraction
                                    .expect("expected cumulative_weight_fraction is required"))
                            .abs()
                                < 1e-9,
                            "test '{}': fraction mismatch",
                            test_name
                        );
                    }
                }
            }
            Action::AssertQueryWithinErrorBound(assert_within) => {
                let assert_within: &AssertQueryWithinErrorBoundP = assert_within.as_ref();
                // The non-lossy QuantileSummaries is exact, so it always verifies against a relative
                // error of 0. The lossy sketch verifies against the action's `expected_relative_error`
                // when set, falling back to the summary's configured relative error; a
                // force-compression case sets a wider `expected_relative_error` to match its
                // deliberately relaxed guarantee.
                let relative_error_to_verify: f64 = if use_lossy_quantile_summaries {
                    assert_within
                        .expected_relative_error
                        .unwrap_or(relative_error)
                } else {
                    0.0
                };
                assert_within_error_bound(
                    &summary,
                    &value_to_weights_truth,
                    &assert_within.quantiles,
                    relative_error_to_verify,
                    test_name,
                );
            }
            Action::AssertSampleCountAtMost(assert_count) => match &summary {
                QuantileSummariesWrapper::Lossy(summary) => {
                    let expected_max_count: usize = assert_count.max_count.unwrap() as usize;
                    let actual_count: usize = quantile_summaries::for_test::sample_count(summary);
                    assert!(
                        actual_count <= expected_max_count,
                        "test '{}': retained {} stats, expected at most {}",
                        test_name,
                        actual_count,
                        expected_max_count
                    );
                }
                QuantileSummariesWrapper::NonLossy(_) => {
                    // The exact oracle retains every observation, so a sample-count bound does not
                    // apply; it ignores this action.
                }
            },
        }
    }

    // Every action succeeded, so there must be no expected_error_opt defined. Otherwise, it means
    // an invalid case failed to produce its declared error and the test should fail.
    assert!(
        expected_error_opt.is_none(),
        "test '{}': expected an error containing '{}', but every action succeeded",
        test_name,
        expected_error_opt.unwrap_or_default()
    );
}

// Below are private components of this module.

/// Enum of the two quantile-summary implementations behind a single interface, so [`run_test_case`]
/// can drive both.
enum QuantileSummariesWrapper {
    Lossy(QuantileSummaries<i32>),
    NonLossy(NonLossyQuantileSummaries<i32>),
}

impl QuantileSummariesWrapper {
    /// Inserts `(value, weight)` to the underlying QuantileSummaries. For the lossy summary, asserts
    /// the sentinel guarantee, and the uncertainty constraint when `assert_uncertainty` is set (a
    /// force-merge case clears it, since force compression intentionally breaks that invariant).
    fn insert(&mut self, value: i32, weight: f64, assert_uncertainty: bool) -> Result<(), Status> {
        match self {
            Self::Lossy(summary) => {
                summary.insert(value, weight)?;
                if assert_uncertainty {
                    quantile_summaries::for_test::assert_uncertainty_constraint(summary);
                }
                quantile_summaries::for_test::assert_sentinel_guarantee(summary);
                Ok(())
            }
            Self::NonLossy(summary) => summary.insert(value, weight),
        }
    }

    /// Queries the results from the underlying QuantileSummaries.
    fn query(&self, quantiles: &[f64]) -> Result<Vec<(&i32, f64)>, Status> {
        match self {
            Self::Lossy(summary) => summary.query(quantiles),
            Self::NonLossy(summary) => summary.query(quantiles),
        }
    }

    /// Rescales all weights by `factor`. For the lossy summary, asserts the sentinel guarantee, and
    /// the uncertainty constraint when `assert_uncertainty` is set (see [`Self::insert`]).
    fn rescale_weights(&mut self, factor: f64, assert_uncertainty: bool) -> Result<(), Status> {
        match self {
            Self::Lossy(summary) => {
                summary.rescale_weights(factor)?;
                if assert_uncertainty {
                    quantile_summaries::for_test::assert_uncertainty_constraint(summary);
                }
                quantile_summaries::for_test::assert_sentinel_guarantee(summary);
                Ok(())
            }
            Self::NonLossy(summary) => summary.rescale_weights(factor),
        }
    }
}

/// Reads the current value of the force-compress accuracy-invariant alert counter, so a test can
/// assert the fallback fired. The counter is process-global; callers compare it against a baseline
/// captured earlier rather than against an absolute value.
fn force_merge_alert_count() -> u64 {
    caching_errors::get_caching_error_counter_value_for_test(
        Severity::Degraded,
        QUANTILE_SUMMARIES_ACCURACY_INVARIANT_BROKEN,
        "",
    )
}

/// Asserts whether a returned error Status matches the expected error option required by the test
/// case.
fn assert_expected_error(status: &Status, expected_error_opt: Option<&str>, test_name: &str) {
    match expected_error_opt {
        None => panic!(
            "test '{}': input should be valid, but QuantileSummaries returned error: {}",
            test_name, status
        ),
        Some(expected) => {
            assert_eq!(
                status.code(),
                // Currently, the only possible error code from (NonLossy)QuantileSummaries is
                // InvalidArgument, so we just hardcode it here.
                Code::InvalidArgument,
                "test '{}' failed with unexpected status code: {}",
                test_name,
                status.code()
            );
            assert!(
                status.message().contains(expected),
                "test '{}' failed with unexpected error message: {}, expected to contain {}",
                test_name,
                status.message(),
                expected
            );
        }
    }
}

/// Asserts every value returned for `quantiles` from `summary` satisfies the error bound against
/// the exact weight distribution in `value_to_weights_truth`, with `relative_error` as the
/// tolerance.
///
/// Caller is responsible to set relative_error to 0 when testing NonLossyQuantileSummaries.
fn assert_within_error_bound(
    summary: &QuantileSummariesWrapper,
    value_to_weights_truth: &BTreeMap<i32, f64>,
    quantiles: &[f64],
    relative_error: f64,
    test_name: &str,
) {
    // The true cumulative weight up to and including each value, plus the total weight.
    let mut value_to_cumulative_weights_truth: BTreeMap<i32, f64> = BTreeMap::new();
    let mut current_cumulative_weight: f64 = 0.0;
    for (&value, &weight) in value_to_weights_truth {
        current_cumulative_weight += weight;
        value_to_cumulative_weights_truth.insert(value, current_cumulative_weight);
    }
    let total_weight: f64 = current_cumulative_weight;

    let query_results: Vec<(i32, f64)> = summary
        .query(quantiles)
        .unwrap_or_else(|status| {
            panic!("case '{}': query failed unexpectedly: {}", test_name, status)
        })
        .into_iter()
        .map(|(&value, fraction)| (value, fraction))
        .collect();
    assert_eq!(
        query_results.len(),
        quantiles.len(),
        "case '{}': expected one answer per queried quantile",
        test_name
    );

    // Floating-point accumulation over many weights introduces small errors, so the comparisons
    // below allow a tolerance.
    let floating_point_tolerance: f64 = 1e-9 * total_weight.max(1.0);

    // Check the total weight.
    match &summary {
        QuantileSummariesWrapper::Lossy(summary) => {
            let tracked_total_weight: f64 = quantile_summaries::for_test::total_weight(summary);
            assert!(
                (tracked_total_weight - total_weight).abs() <= floating_point_tolerance,
                "case '{}': sketch total weight {} != true total {}",
                test_name,
                tracked_total_weight,
                total_weight
            );
        }
        QuantileSummariesWrapper::NonLossy(_) => (),
    }

    // Verify each of the query result.
    for (&quantile, &(returned_value, returned_fraction)) in quantiles.iter().zip(&query_results) {
        // The truth of the cumulative weight up to the returned value.
        let cumulative_truth_at_returned_value: f64 = *value_to_cumulative_weights_truth
            .get(&returned_value)
            .unwrap_or_else(|| {
                panic!(
                    "case '{}': query returned {} which was never inserted",
                    test_name, returned_value
                )
            });
        // The truth of the cumulative weight below the returned value.
        let cumulative_truth_below_returned_value: f64 =
            cumulative_truth_at_returned_value - value_to_weights_truth[&returned_value];

        // Note that `relative_error` should be set to 0 by the caller when testing non-lossy
        // quantile summaries so this doesn't compromise the test accuracy.
        let error_budget: f64 = relative_error * total_weight + floating_point_tolerance;

        // Verify the relative error contract is achieved. See the main doc of QuantileSummaries for
        // the contract.
        assert!(
            cumulative_truth_at_returned_value >= quantile * total_weight - error_budget,
            "case '{}': query({}) returned {} whose cumulative weight {} is below the lower bound {}",
            test_name,
            quantile,
            returned_value,
            cumulative_truth_at_returned_value,
            quantile * total_weight - error_budget
        );
        assert!(
            cumulative_truth_below_returned_value < quantile * total_weight + error_budget,
            "case '{}': query({}) returned {} whose strictly-below cumulative weight {} exceeds the \
             upper bound {}",
            test_name,
            quantile,
            returned_value,
            cumulative_truth_below_returned_value,
            quantile * total_weight + error_budget
        );

        // In addition, verify that the quantile returned alongside with the value is within the
        // error bound.
        let quantile_truth_of_returned_value: f64 =
            cumulative_truth_at_returned_value / total_weight;
        let fraction_budget: f64 = 2.0 * relative_error + 1e-9;
        assert!(
            (returned_fraction - quantile_truth_of_returned_value).abs() <= fraction_budget,
            "case '{}': query({}) returned fraction {} for value {}, expected {} within {}",
            test_name,
            quantile,
            returned_fraction,
            returned_value,
            quantile_truth_of_returned_value,
            fraction_budget
        );
    }
}

/// Fixed seed so the `RandomP` stream is reproducible across runs.
const INSERT_MANY_RANDOM_SEED: u64 = 0x2545F491_4F6CDD1D;

/// Expands an `InsertManyP` into the `(value, weight)` input sequence it describes (see
/// [`DistributionP`]).
fn generate_insert_many_test_data(insert_many: &InsertManyP) -> Vec<(i32, f64)> {
    let num_inserts: i32 = insert_many.num_inserts.expect("num_inserts is required");
    let value_distribution: &DistributionP = insert_many
        .value_distribution
        .as_option()
        .expect("value_distribution is required");
    let weight_distribution: &DistributionP = insert_many
        .weight_distribution
        .as_option()
        .expect("weight_distribution is required");

    // One generator drives every `RandomP` draw so the whole stream stays reproducible.
    let mut rng: StdRng = StdRng::seed_from_u64(INSERT_MANY_RANDOM_SEED);
    (0..num_inserts)
        .map(|index| {
            let value: i32 = sample_distribution(value_distribution, index, &mut rng) as i32;
            let weight: f64 = sample_distribution(weight_distribution, index, &mut rng);
            (value, weight)
        })
        .collect()
}

/// Returns the `index`-th sample of `distribution`, advancing `rng` for a `RandomP` draw.
fn sample_distribution(distribution: &DistributionP, index: i32, rng: &mut StdRng) -> f64 {
    match distribution
        .pattern
        .as_ref()
        .expect("distribution pattern is required")
    {
        Pattern::Linear(linear) => {
            f64::from(linear.start.unwrap_or(0))
                + f64::from(linear.step.unwrap_or(0)) * f64::from(index)
        }
        Pattern::Exponential(exponential) => {
            f64::from(exponential.start.unwrap_or(0))
                * exponential.multiplier.unwrap_or(0.0).powi(index)
        }
        Pattern::Random(random) => {
            let min: f64 = f64::from(random.min.unwrap_or(0));
            let max: f64 = f64::from(random.max.unwrap_or(0));
            // Uniform draw over the half-open range [min, max), matching `RandomP`.
            rng.random_range(min..max)
        }
    }
}

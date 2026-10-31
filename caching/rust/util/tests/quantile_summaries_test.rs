use rand::rngs::StdRng;
use rand::{Rng, SeedableRng};
use caching_rust_util::quantile_summaries::{for_test, QuantileSummaries};
use caching_rust_util_testing::non_lossy_quantile_summaries::NonLossyQuantileSummaries;
use caching_rust_util_testing::quantile_summaries_test_harness::run_test_case;
use caching_rust_util_testing::test_data::QUANTILE_SUMMARIES_TEST_DATA as TEST_DATA;
use caching_util_test_proto_test_data_proto_buffa::databricks::caching::test::quantile_summaries_test_data_p::TestCaseP;

/// Runs `test_cases` using lossy QuantileSummaries.
fn run_test_cases(test_cases: &[TestCaseP]) {
    assert!(!test_cases.is_empty(), "expected at least one test case");
    for test_case in test_cases {
        run_test_case(test_case, true);
    }
}

// See the textproto definition for the test cases for the test plans.

#[test]
fn valid_cases() {
    run_test_cases(&TEST_DATA.valid_cases);
}

#[test]
fn invalid_cases() {
    run_test_cases(&TEST_DATA.invalid_cases);
}

// A few test cases that are inconvenient for cross-language sharing and are defined as Rust native.

#[test]
fn randomized_test() {
    // Test plan: A randomized test that supplies completely random inputs to the QuantileSummaries,
    // across several relative errors, and verifies each query result stays within the sketch's
    // error band of the exact NonLossyQuantileSummaries.

    struct TestCase {
        relative_error: f64,
        num_operations: usize,
        max_value: i32,
        max_weight: u32,
    }

    const TEST_CASES: [TestCase; 2] = [
        TestCase {
            relative_error: 0.005,
            num_operations: 100_000,
            max_value: 100,
            max_weight: 1_000_000,
        },
        TestCase {
            relative_error: 0.05,
            num_operations: 10_000,
            max_value: 100_000,
            max_weight: 1_000_000,
        },
    ];

    let seed: u64 = rand::random();
    let mut rng: StdRng = StdRng::seed_from_u64(seed);

    for test_case in TEST_CASES {
        let TestCase {
            relative_error,
            num_operations,
            max_value,
            max_weight,
        } = test_case;
        let mut summary: QuantileSummaries<i32> =
            QuantileSummaries::new(relative_error, 2000).unwrap();
        let mut non_lossy_summary: NonLossyQuantileSummaries<i32> =
            NonLossyQuantileSummaries::new();

        // Now randomly insert values or rescale weights.
        for _ in 0..num_operations {
            let value: i32 = rng.random_range(0..max_value);
            let weight: f64 = f64::from(rng.random_range(1..=max_weight));
            summary.insert(value, weight).unwrap();
            non_lossy_summary.insert(value, weight).unwrap();

            if rng.random_bool(0.01) {
                // Occasionally rescale, with a factor near 1 (the contract caps it at 1.0) so
                // repeated rescales don't shrink weights toward 0 and destabilize the test. When
                // rescaling weights to almost 0, some values will be discarded by the
                // QuantileSummaries because f64 cannot distinguish the cumulative weight difference
                // between these values, resulting in QuantileSummaries returning keys not within
                // the expected range.
                let factor: f64 = rng.random_range(0.95..=1.0);
                summary.rescale_weights(factor).unwrap();
                non_lossy_summary.rescale_weights(factor).unwrap();
            }

            // The invariant should not be broken.
            if rng.random_bool(0.05) {
                for_test::assert_uncertainty_constraint(&summary);
                for_test::assert_sentinel_guarantee(&summary);
            }

            if rng.random_bool(0.1) {
                // Query and verify a random quantile. Querying is expensive so we do it with less
                // frequency.
                let quantile: f64 = rng.random_range(0.0..=1.0);
                let actual_value: i32 = *summary.query(&[quantile]).unwrap()[0].0;

                // Allow a floating point error.
                let lower_quantile: f64 = (quantile - relative_error - 1e-2).max(0.0);
                let upper_quantile: f64 = (quantile + relative_error + 1e-2).min(1.0);
                let lower_value: i32 = *non_lossy_summary.query(&[lower_quantile]).unwrap()[0].0;
                let upper_value: i32 = *non_lossy_summary.query(&[upper_quantile]).unwrap()[0].0;

                assert!(
                    lower_value <= actual_value && actual_value <= upper_value,
                    "seed {}: relative_error {}, : query({}) returned {}, outside the exact \
                     oracle band [{}, {}] (quantiles [{}, {}])",
                    seed,
                    relative_error,
                    quantile,
                    actual_value,
                    lower_value,
                    upper_value,
                    lower_quantile,
                    upper_quantile
                );
            }
        }
    }
}

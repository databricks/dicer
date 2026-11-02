use caching_rust_util_testing::quantile_summaries_test_harness::run_test_case;
use caching_rust_util_testing::test_data::QUANTILE_SUMMARIES_TEST_DATA as TEST_DATA;
use caching_util_test_proto_test_data_proto_buffa::databricks::caching::test::quantile_summaries_test_data_p::TestCaseP;

/// Runs `test_cases` using NonLossyQuantileSummaries.
fn run_test_cases(test_cases: &[TestCaseP]) {
    assert!(!test_cases.is_empty(), "expected at least one test case");
    for test_case in test_cases {
        run_test_case(test_case, /* use_lossy_quantile_summaries= */ false);
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

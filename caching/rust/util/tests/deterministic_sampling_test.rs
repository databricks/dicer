use std::collections::HashSet;
use std::sync::LazyLock;

use rand::distr::{Alphanumeric, SampleString};
use rand::rng;
use tonic::{Code, Status};
use caching_rust_util::deterministic_sampling::is_sampled;
use caching_rust_util_testing::parse_buffa_test_data;
use caching_util_test_proto_test_data_proto_buffa::databricks::caching::test::DeterministicSamplingTestDataP;

static TEST_DATA: LazyLock<DeterministicSamplingTestDataP> = LazyLock::new(|| {
    parse_buffa_test_data("caching/util/test/data/deterministic_sampling_test_data.textproto")
});

#[test]
fn test_is_sampled_returns_the_expected_result_for_valid_inputs() {
    // Test plan: Verify that is_sampled returns the expected result for every valid input from the
    // shared textproto. Verify this by iterating over each `valid_test_cases` entry, calling
    // is_sampled, and asserting the result equals `expected_is_sampled`.
    for test_case in &TEST_DATA.valid_test_cases {
        let description = test_case
            .description
            .as_deref()
            .expect("description must be set in ValidTestCaseP");
        let item = test_case
            .item
            .as_deref()
            .expect("item must be set in ValidTestCaseP");
        let sample_namespace = test_case
            .sample_namespace
            .as_deref()
            .expect("sample_namespace must be set in ValidTestCaseP");
        let sample_fraction = test_case
            .sample_fraction
            .expect("sample_fraction must be set in ValidTestCaseP");
        let expected_is_sampled = test_case
            .expected_is_sampled
            .expect("expected_is_sampled must be set in ValidTestCaseP");
        let result: bool =
            is_sampled(item, sample_namespace, sample_fraction).unwrap_or_else(|err| {
                panic!("expected {description:?} to be accepted, got error: {err}")
            });
        assert_eq!(result, expected_is_sampled, "unexpected result for {description:?}");
    }
}

#[test]
fn test_is_sampled_rejects_invalid_inputs() {
    // Test plan: Verify that is_sampled rejects every invalid input from the shared textproto.
    // Verify this by iterating over each `invalid_test_cases` entry and asserting is_sampled
    // returns an INVALID_ARGUMENT error whose message contains the expected substring.
    for test_case in &TEST_DATA.invalid_test_cases {
        let description = test_case
            .description
            .as_deref()
            .expect("description must be set in InvalidTestCaseP");
        let item = test_case
            .item
            .as_deref()
            .expect("item must be set in InvalidTestCaseP");
        let sample_namespace = test_case
            .sample_namespace
            .as_deref()
            .expect("sample_namespace must be set in InvalidTestCaseP");
        let sample_fraction = test_case
            .sample_fraction
            .expect("sample_fraction must be set in InvalidTestCaseP");
        let expected_substring = test_case
            .expected_error_substring
            .as_deref()
            .expect("expected_error_substring must be set in InvalidTestCaseP");
        let err: Status = is_sampled(item, sample_namespace, sample_fraction).unwrap_err();
        assert_eq!(err.code(), Code::InvalidArgument, "unexpected code for {description:?}");
        assert!(
            err.message().contains(expected_substring),
            "expected error containing {expected_substring:?} for {description:?}, got: {err}"
        );
    }
}

#[test]
fn test_same_item_and_namespace_always_produce_the_same_sampling_result() {
    // Test plan: Verify that sampling is stable across repeated calls for the same (item,
    // sample_namespace) pair. Do this by generating a random item and namespace, calling
    // is_sampled 10 times, and asserting every result matches the first.
    let item: String = Alphanumeric.sample_string(&mut rng(), 10);
    let namespace: String = Alphanumeric.sample_string(&mut rng(), 10);

    // Get the result on the first call — this is our reference.
    let first_result: bool = is_sampled(&item, &namespace, 0.5).unwrap();

    // Call 9 more times and verify each result matches.
    for _ in 1..10 {
        let result: bool = is_sampled(&item, &namespace, 0.5).unwrap();
        assert_eq!(
            result, first_result,
            "is_sampled({item}, {namespace}) returned {result}, expected {first_result}"
        );
    }
}

#[test]
fn test_different_namespaces_can_yield_different_sampling_results_for_the_same_item() {
    // Test plan: Verify that the namespace acts as an independent salt so that the same item can
    // have different sampling decisions across namespaces. Do this by comparing the result for a
    // single item under two different namespaces, and asserting the results are not identical.
    let item = "some_item";
    let result_for_namespace_a: bool = is_sampled(item, "namespace_a", 0.5).unwrap();
    let result_for_namespace_b: bool = is_sampled(item, "namespace_b", 0.5).unwrap();
    assert_ne!(result_for_namespace_a, result_for_namespace_b);
}

#[test]
fn test_different_items_can_yield_different_sampling_results_for_the_same_namespace() {
    // Test plan: Verify that the item acts as an independent salt so that the same namespace can
    // have different sampling decisions across items. Do this by comparing the result for a single
    // namespace under two different items, and asserting the results are not identical.
    let namespace = "some_namespace";
    let result_for_item_a: bool = is_sampled("item_a", namespace, 0.5).unwrap();
    let result_for_item_b: bool = is_sampled("item_b", namespace, 0.5).unwrap();
    assert_ne!(result_for_item_a, result_for_item_b);
}

#[test]
fn test_pairs_with_the_same_concatenation_but_different_splits_yield_different_results() {
    // Test plan: Verify that (item, sample_namespace) pairs whose naive string concatenation is
    // identical are not treated as equivalent. Do this by sampling a set of such colliding pairs
    // at fraction 0.5 and asserting that not all pairs produce the same result — if the key were
    // just item + sample_namespace, every pair would hash identically and always agree.
    let colliding_pairs: [(&str, &str); 4] =
        [("hello", "world"), ("he", "lloworld"), ("hellow", "orld"), ("h", "elloworld")];
    let results: HashSet<bool> = colliding_pairs
        .iter()
        .map(|(item, namespace)| is_sampled(item, namespace, 0.5).unwrap())
        .collect();
    assert!(
        results.len() > 1,
        "Expected colliding pairs to yield different results, but all produced: {results:?}"
    );
}

#[test]
fn test_sampled_count_is_within_3_sigma_of_expectation_for_various_fractions() {
    // Test plan: Verify that the sampled count for 1e6 items is within 3 sigma of the expectation.
    // For Binomial(n, p): expectation = n*p, sigma = sqrt(n*p*(1-p)). Use 1e6 items so that even
    // extreme fractions (e.g. 0.0001) yield a large enough expected count for a meaningful bound.
    // This test is not flaky because the input is fixed and the sampling behavior is deterministic.
    let n: usize = 1_000_000;
    for fraction in [0.0001, 0.1000, 0.2345, 0.42, 0.5, 0.9, 0.9999] {
        let sampled_count: usize = (0..n)
            .filter(|i| is_sampled(&format!("item_{i}"), "sampling_namespace", fraction).unwrap())
            .count();

        let expected: f64 = n as f64 * fraction;
        let sigma: f64 = (n as f64 * fraction * (1.0 - fraction)).sqrt();
        assert!(
            (sampled_count as f64 - expected).abs() <= 3.0 * sigma,
            "Expected count near {expected} ± {}, got {sampled_count} at fraction {fraction}.",
            3.0 * sigma
        );
    }
}

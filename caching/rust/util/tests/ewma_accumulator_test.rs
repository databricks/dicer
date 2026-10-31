use tonic::Status;

use caching_rust_util::internal_for_test::EwmaAccumulator;
use caching_rust_util::internal_for_test::new_ewma_counter_config_from_alpha;
use caching_rust_util_testing::test_data::EWMA_ACCUMULATOR_TEST_DATA;
use caching_util_test_proto_test_data_proto_buffa::databricks::caching::test::ewma_accumulator_test_data_p::{test_action_p::Action, *};

/// The tolerance value used for floating-point comparisons in tests.
const EPSILON: f64 = 1e-10;

/// Asserts that the expected and actual values are approximately equal, and outputs a debug
/// message if they are not.
fn assert_approx_equal(expected: f64, actual: f64, debug_message_opt: Option<&String>) {
    assert!(
        (expected - actual).abs() < EPSILON,
        "{}",
        match debug_message_opt {
            Some(msg) => format!("{} Expected: {}, actual: {}.", msg, expected, actual),
            None => format!("Expected: {}, actual: {}.", expected, actual),
        }
    );
}

/// Performs a single action on `accumulator` based on the proto. A optional reference
/// accumulator can be provided to compare against the value of the `accumulator`.
fn perform_action(
    accumulator: &mut EwmaAccumulator,
    action_p: &TestActionP,
    reference_accumulator_opt: Option<&mut EwmaAccumulator>,
) -> Result<(), Status> {
    match action_p.action.as_ref().unwrap() {
        Action::AppendValue(value) => {
            accumulator.append_value(*value);
            Ok(())
        }

        Action::AppendZeros(count) => {
            accumulator.append_zeros(*count)?;
            Ok(())
        }

        Action::AssertValueEquals(proto) => {
            let expected: f64 = proto.expected.unwrap();
            let actual: f64 = accumulator.value();
            assert_approx_equal(expected, actual, proto.debug_message_opt.as_ref());
            Ok(())
        }

        Action::RepeatedAppendWithCheck(proto) => {
            let append_value: i64 = proto.value.unwrap();
            let repeat_count: i64 = proto.count.unwrap();

            for _ in 0..repeat_count {
                accumulator.append_value(append_value);
                if let Some(expected) = proto.expected_opt {
                    // Check the expected value if provided.
                    let actual: f64 = accumulator.value();
                    assert_approx_equal(expected, actual, None);
                }
            }
            Ok(())
        }

        Action::AssertAccumulatorsValueMatch(_) => {
            match &reference_accumulator_opt {
                Some(reference_accumulator) => {
                    let expected: f64 = reference_accumulator.value();
                    let actual: f64 = accumulator.value();
                    assert_approx_equal(expected, actual, None);
                }
                None => {
                    return Err(Status::invalid_argument("Expected a reference accumulator"));
                }
            }
            Ok(())
        }
    }
}

/// Creates an accumulator based on the factory parameters and performs the actions on it.
fn create_accumulator_and_perform_actions(
    alpha: f64,
    seed_opt: Option<f64>,
    actions: &Vec<TestActionP>,
) -> Result<(), Status> {
    // Setup: create the accumulator with the given alpha and seed.
    let config = new_ewma_counter_config_from_alpha(alpha)?;
    let mut accumulator = EwmaAccumulator::new(config, seed_opt);

    // Iterate over the actions and perform them on the accumulator.
    for action_p in actions {
        perform_action(&mut accumulator, action_p, None)?;
    }
    Ok(())
}

#[test]
fn test_invalid_cases() {
    // Test plan: verify that the accumulator works correctly for valid inputs.
    let invalid_test_cases: &Vec<TestCaseP> = &EWMA_ACCUMULATOR_TEST_DATA.invalid_cases;

    // Sanity check: test cases are not empty.
    assert!(!invalid_test_cases.is_empty());

    // For each test case, create an accumulator based on the input and iterate over the
    // actions.
    for test_case in invalid_test_cases {
        let factory_params: &EwmaAccumulatorP = &test_case.ewma_accumulator;
        let alpha: f64 = factory_params.alpha.unwrap();
        let seed_opt: Option<f64> = factory_params.seed_opt;
        let actions: &Vec<TestActionP> = &test_case.actions;

        create_accumulator_and_perform_actions(alpha, seed_opt, actions).unwrap_err();
    }
}

#[test]
fn test_valid_cases() {
    // Test plan: verify that the accumulator works correctly for valid inputs.
    let valid_test_cases: &Vec<TestCaseP> = &EWMA_ACCUMULATOR_TEST_DATA.valid_cases;

    // Sanity check: test cases are not empty.
    assert!(!valid_test_cases.is_empty());

    // For each test case, create an accumulator based on the input and iterate over the
    // actions.
    for test_case in valid_test_cases {
        let factory_params: &EwmaAccumulatorP = &test_case.ewma_accumulator;
        let alpha: f64 = factory_params.alpha.unwrap();
        let seed_opt: Option<f64> = factory_params.seed_opt;
        let actions: &Vec<TestActionP> = &test_case.actions;

        create_accumulator_and_perform_actions(alpha, seed_opt, actions).unwrap();
    }
}

#[test]
fn test_ewma_accumulator_append_zeros() {
    // Test plan: verify that the `appendZeros` method correctly accumulates zeros. This is
    // done by comparing the result of appending zeros to the result of appending values of
    // zero one by one.
    let test_input: &TestCaseP = &EWMA_ACCUMULATOR_TEST_DATA.append_zeros_and_compare;

    assert!(!test_input.actions.is_empty());
    assert!(test_input.reference_ewma_accumulator.is_set());
    assert!(!test_input.reference_accumulator_actions.is_empty());

    // Setup: create the accumulators with the given alpha and seed.
    let alpha: f64 = test_input.ewma_accumulator.alpha.unwrap();
    let seed_opt: Option<f64> = test_input.ewma_accumulator.seed_opt;

    let alpha2: f64 = test_input.reference_ewma_accumulator.alpha.unwrap();
    let seed_opt2: Option<f64> = test_input.reference_ewma_accumulator.seed_opt;

    let config = new_ewma_counter_config_from_alpha(alpha).unwrap();
    let mut accumulator = EwmaAccumulator::new(config, seed_opt);
    // Create a reference accumulator to compare against.
    let config2 = new_ewma_counter_config_from_alpha(alpha2).unwrap();
    let mut reference_accumulator = EwmaAccumulator::new(config2, seed_opt2);

    // Iterate over the actions and perform them on the accumulator.
    for action_p in &test_input.actions {
        // Perform the action on the accumulator and the reference accumulator.
        perform_action(&mut accumulator, action_p, None).unwrap();
    }

    for action_p in &test_input.reference_accumulator_actions {
        // Perform the action on the reference accumulator.
        perform_action(&mut reference_accumulator, action_p, Some(&mut accumulator)).unwrap();
    }

    // Sanity check: values of both accumulators should be non-zero and match.
    assert!(accumulator.value() != 0.0);
    assert!(reference_accumulator.value() != 0.0);
    assert_approx_equal(accumulator.value(), reference_accumulator.value(), None);
}

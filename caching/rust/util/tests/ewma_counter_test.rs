use chrono::{DateTime, Duration, Utc};
use tonic::{Code, Status};

use caching_rust_util::ewma_counter::{Config as EwmaCounterConfig, EwmaCounter, Value};
use caching_rust_util_testing::test_data::EWMA_COUNTER_TEST_DATA;
use caching_util_test_proto_test_data_proto_buffa::databricks::caching::test::ewma_counter_test_data_p::*;
use caching_util_test_proto_test_data_proto_buffa::databricks::caching::test::ewma_counter_test_data_p::action_p::Action;

use rand_distr::{Distribution, Poisson};
use rand::SeedableRng;
use rand::rngs::StdRng;

/// The tolerance value used for floating-point comparisons in tests.
const EPSILON: f64 = 1e-14;

/// Asserts that `actual` and `expected` have exactly the same window and that they have
/// approximately equal weighted values (difference is less than `EPSILON`). `start_time`
/// is used to provide a more meaningful debug messagem by displaying window bounds as
/// offsets from the test start time.
fn assert_approximately_equal(start_time: DateTime<Utc>, actual: &Value, expected: &Value) {
    assert_windows_match(start_time, actual, expected);
    assert!(
        (actual.weighted_value - expected.weighted_value).abs() < EPSILON,
        "Weighted value mismatch: expected {}, got {}",
        expected.weighted_value,
        actual.weighted_value
    );
}

/// Asserts that `actual` and `expected` have the same value window.
fn assert_windows_match(start_time: DateTime<Utc>, actual: &Value, expected: &Value) {
    assert_eq!(
        actual.window_low_inclusive,
        expected.window_low_inclusive,
        "Window low inclusive not equal: expected {}, got {}",
        actual.window_high_exclusive - start_time,
        expected.window_low_inclusive - start_time
    );
    assert_eq!(
        actual.window_high_exclusive,
        expected.window_high_exclusive,
        "Window high exclusive mismatch: expected {}, got {}",
        actual.window_high_exclusive - start_time,
        expected.window_high_exclusive - start_time
    );
}

/// Performs the action based on the proto, a reference counter can be optionally provided
/// to compare the result against the main counter.
fn perform_action(
    action: &ActionP,
    counter: &mut EwmaCounter,
    start_time: DateTime<Utc>,
    reference_counter: Option<&mut EwmaCounter>,
) -> Result<(), Status> {
    match action.action.as_ref().unwrap() {
        Action::IncrementBy(proto) => {
            let now: DateTime<Utc> = start_time + Duration::milliseconds(proto.elapsed_ms.unwrap());
            let perform_on_reference: bool = proto.perform_on_reference.unwrap_or(false);
            let ewma_counter: &mut EwmaCounter = if perform_on_reference {
                reference_counter.unwrap()
            } else {
                counter
            };
            ewma_counter.increment_by(now, proto.value.unwrap());
        }

        Action::AssertValueEquals(proto) => {
            let now: DateTime<Utc> = start_time + Duration::milliseconds(proto.elapsed_ms.unwrap());
            let actual: Value = counter.value(now);
            let expected: Value = Value {
                window_low_inclusive: start_time
                    + Duration::milliseconds(proto.window_low_elapsed_ms.unwrap()),
                window_high_exclusive: start_time
                    + Duration::milliseconds(proto.window_high_elapsed_ms.unwrap()),
                weighted_value: proto.expected.unwrap(),
            };
            assert_approximately_equal(start_time, &actual, &expected);
        }

        Action::AssertValueInRange(proto) => {
            let now: DateTime<Utc> = start_time + Duration::milliseconds(proto.elapsed_ms.unwrap());
            let actual: Value = counter.value(now);
            let expected: Value = Value {
                window_low_inclusive: start_time
                    + Duration::milliseconds(proto.window_low_elapsed_ms.unwrap()),
                window_high_exclusive: start_time
                    + Duration::milliseconds(proto.window_high_elapsed_ms.unwrap()),
                weighted_value: 0.0, // We only check the window, not the value.
            };
            assert_windows_match(start_time, &actual, &expected);
            assert!(
                (actual.weighted_value < proto.max_value.unwrap())
                    && (actual.weighted_value > proto.min_value.unwrap()),
                "Weighted value {} is not in range [{}, {}]",
                actual.weighted_value,
                proto.min_value.unwrap(),
                proto.max_value.unwrap()
            );
        }

        Action::RepeatedGetValue(proto) => {
            let mut now: DateTime<Utc> =
                start_time + Duration::milliseconds(proto.elapsed_ms.unwrap());
            let perform_on_reference: bool = proto.perform_on_reference.unwrap_or(false);
            let ewma_counter: &mut EwmaCounter = if perform_on_reference {
                reference_counter.unwrap()
            } else {
                counter
            };
            for _ in 0..proto.repeat_count.unwrap() {
                ewma_counter.value(now);
                now += Duration::milliseconds(proto.repeat_interval_ms.unwrap());
            }
        }

        Action::RepeatedIncrement(proto) => {
            let mut now: DateTime<Utc> =
                start_time + Duration::milliseconds(proto.elapsed_ms.unwrap());
            let perform_on_reference: bool = proto.perform_on_reference.unwrap_or(false);
            let ewma_counter: &mut EwmaCounter = if perform_on_reference {
                reference_counter.unwrap()
            } else {
                counter
            };
            for _ in 0..proto.repeat_count.unwrap() {
                ewma_counter.increment_by(now, proto.value.unwrap());
                now += Duration::milliseconds(proto.repeat_interval_ms.unwrap());
            }
        }

        Action::AssertCountersValueMatch(proto) => {
            let now_for_main: DateTime<Utc> =
                start_time + Duration::milliseconds(proto.main_elapsed_ms.unwrap());
            let now_for_ref: DateTime<Utc> =
                start_time + Duration::milliseconds(proto.reference_elapsed_ms.unwrap());

            let main_counter_value: Value = counter.value(now_for_main);
            let reference_counter_value: Value = reference_counter.unwrap().value(now_for_ref);
            assert_windows_match(start_time, &main_counter_value, &reference_counter_value);

            assert!(
                (main_counter_value.weighted_value - reference_counter_value.weighted_value).abs()
                    < EPSILON,
                "Main counter value {} does not match reference counter value {}",
                main_counter_value.weighted_value,
                reference_counter_value.weighted_value
            );
        }

        Action::RepeatedIncrementByPoissonSample(proto) => {
            let rate: f64 = proto.poisson_mean.unwrap();
            let distribution: Poisson<f64> = Poisson::new(rate).unwrap();
            let mut now: DateTime<Utc> =
                start_time + Duration::milliseconds(proto.elapsed_ms.unwrap());

            // Use a fixed seed random generator to make the test deterministic
            let mut rng = StdRng::seed_from_u64(42);

            for _ in 0..proto.repeat_count.unwrap() {
                let sample: i32 = distribution.sample(&mut rng).round() as i32;
                counter.increment_by(now, sample);
                now += Duration::seconds(1);
            }
        }
    }
    Ok(())
}

/// Creates a counter based on the proto provided.
fn create_counter(proto: &EwmaCounterP, start_time: DateTime<Utc>) -> Result<EwmaCounter, Status> {
    let half_life: Duration = Duration::seconds(proto.half_life_seconds.unwrap() as i64);
    let seed_opt: Option<f64> = proto.seed_opt;

    let config = EwmaCounterConfig::new(half_life)?;

    Ok(EwmaCounter::new(start_time, config, seed_opt))
}

#[test]
fn default_ewma_counter_config() {
    // Test plan: verify that the default EWMA counter config is valid.
    EwmaCounterConfig::default(); // Should not panic.
}

#[test]
fn test_invalid_cases() {
    // Test plan: verify that exceptions are thrown for invalid cases.
    let test_cases: &Vec<TestCaseP> = &EWMA_COUNTER_TEST_DATA.invalid_cases;

    for test_case in test_cases {
        let start_time: DateTime<Utc> = Utc::now();
        let error_message: &str = test_case.error_message_opt.as_ref().unwrap();
        let factory_params: &EwmaCounterP = &test_case.ewma_counter;

        let err: Status = create_counter(factory_params, start_time).unwrap_err();
        assert_eq!(err.code(), Code::InvalidArgument);
        assert!(
            err.message().contains(error_message),
            "Expected error message to contain '{}', but got '{}'",
            error_message,
            err.message()
        );
    }
}

#[test]
fn test_valid_cases() {
    // Test plan: verify that valid cases pass without throwing exceptions.
    let test_cases: &Vec<TestCaseP> = &EWMA_COUNTER_TEST_DATA.valid_cases;

    for test_case in test_cases {
        let start_time: DateTime<Utc> = Utc::now();
        let factory_params: &EwmaCounterP = &test_case.ewma_counter;

        let mut counter: EwmaCounter = create_counter(factory_params, start_time).unwrap();

        for action in &test_case.actions {
            let result: Result<(), Status> = perform_action(action, &mut counter, start_time, None);
            assert!(result.is_ok(), "Action failed: {:?}", action);
        }
    }
}

#[test]
fn test_reference_counter_cases() {
    // Test plan: verify that the reference counter cases pass without throwing exceptions.
    let test_cases: &Vec<TestCaseP> = &EWMA_COUNTER_TEST_DATA.reference_cases;

    for test_case in test_cases {
        let start_time: DateTime<Utc> = Utc::now();
        let factory_params: &EwmaCounterP = &test_case.ewma_counter;

        let mut counter: EwmaCounter = create_counter(factory_params, start_time).unwrap();
        let mut reference_counter: EwmaCounter =
            create_counter(factory_params, start_time).unwrap();

        for action in &test_case.actions {
            let result: Result<(), Status> =
                perform_action(action, &mut counter, start_time, Some(&mut reference_counter));
            assert!(result.is_ok(), "Action failed: {:?}", action);
        }
    }
}

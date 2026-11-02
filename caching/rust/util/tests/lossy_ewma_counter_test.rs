use caching_rust_util::lossy_ewma_counter::{
    for_test_amplification_overflow_count,
    for_test_total_sum_overflow_count,
    Config,
    LossyEwmaCounter,
    LossyEwmaCounterImpl,
};
use caching_rust_util_testing::test_data::LOSSY_EWMA_COUNTER_TEST_DATA as TEST_DATA;
mod inefficient_lossy_ewma_counter;
use inefficient_lossy_ewma_counter::InefficientLossyEwmaCounter;

use caching_util_test_proto_test_data_proto_buffa::databricks::caching::test::lossy_ewma_counter_test_data_p::{
    test_action_p::Action::*,
    start_time_p::Time,
    support_p::Support,
    half_life_second_p::HalfLifeSeconds,
    one_time_action_p::Action::*,
    error_p::Error as ErrorP,
    increment_value_p::*,
    *,
};

use anyhow::anyhow;
use chrono::{DateTime, Duration, Utc};
use rand::{rng, Rng};
use rand_distr::{Distribution, Zipf};
use std::collections::HashMap;
use tonic::{Code, Status};

/// Helper method to get the start time from proto.
fn get_start_time(proto: &LossyEwmaCounterP, test_name: &str) -> anyhow::Result<DateTime<Utc>> {
    let start_time = proto
        .start_time
        .as_option()
        .ok_or_else(|| anyhow!("Test {}: Start time unspecified", test_name))?;

    match &start_time.time {
        None => Err(anyhow!("Test {}: Start time unspecified", test_name)),
        Some(Time::Random(_)) => {
            // Use int32::MAX to avoid overflow/conversion issues.
            DateTime::from_timestamp_millis(rng().random_range(0..i32::MAX as i64))
                .ok_or_else(|| anyhow!("Test {}: Invalid timestamp generated", test_name))
        }
        Some(Time::UnixEpochMillis(epoch_millis)) => DateTime::from_timestamp_millis(*epoch_millis)
            .ok_or_else(|| anyhow!("Test {}: Invalid timestamp: {}", test_name, epoch_millis)),
    }
}

/// Helper method to get the support from proto.
fn get_support(proto: &LossyEwmaCounterP, test_name: &str) -> anyhow::Result<f64> {
    match proto
        .support
        .as_option()
        .ok_or_else(|| anyhow!("Test {}: Support unspecified", test_name))?
        .support
    {
        None => Err(anyhow!("Test {}: Support unspecified", test_name)),
        Some(Support::Random(_)) => Ok(rng().random_range(0.1..1.0)),
        Some(Support::Value(v)) => Ok(v),
    }
}

/// Helper method to get the error from proto.
fn get_error(proto: &LossyEwmaCounterP, support: f64, test_name: &str) -> anyhow::Result<f64> {
    match proto
        .error
        .as_option()
        .ok_or_else(|| anyhow!("Test {}: Error unspecified", test_name))?
        .error
    {
        None => Err(anyhow!("Test {}: Error unspecified", test_name)),
        Some(ErrorP::OneTenthSupport(_)) => Ok(support / 10.0),
        Some(ErrorP::Value(v)) => Ok(v),
    }
}

/// Helper method to get the half life from proto.
fn get_half_life(proto: &LossyEwmaCounterP, test_name: &str) -> anyhow::Result<Duration> {
    let half_life_milli: i64 = match proto
        .half_life_seconds
        .as_option()
        .ok_or_else(|| anyhow!("Test {}: Half life seconds unspecified", test_name))?
        .half_life_seconds
    {
        None => return Err(anyhow!("Test {}: half life seconds unspecified", test_name)),
        Some(HalfLifeSeconds::Random(_)) => rng().random_range(1..1_000_000),
        Some(HalfLifeSeconds::Value(v)) => (v * 1000.0).floor() as i64,
    };
    Ok(Duration::milliseconds(half_life_milli)) // Use milli to allow for sub-second precision.
}

/// Creates an inefficient lossy EWMA counter with the given proto, also returns the start time.
fn create_inefficient_lossy_ewma_counter(
    test_name: &str,
    proto: &LossyEwmaCounterP,
) -> anyhow::Result<(InefficientLossyEwmaCounter<String>, DateTime<Utc>)> {
    let start_time: DateTime<Utc> = get_start_time(proto, test_name)?;
    let support: f64 = get_support(proto, test_name)?;
    let error: f64 = get_error(proto, support, test_name)?;
    let half_life: Duration = get_half_life(proto, test_name)?;

    let config: Config = Config::new(support, error, half_life)?;
    Ok((InefficientLossyEwmaCounter::new(start_time, config), start_time))
}

/// Creates an lossy EWMA counter with the given proto, also returns the start time.
fn create_lossy_ewma_counter(
    test_name: &str,
    proto: &LossyEwmaCounterP,
) -> anyhow::Result<(LossyEwmaCounterImpl<String>, DateTime<Utc>)> {
    let start_time: DateTime<Utc> = get_start_time(proto, test_name)?;
    let support: f64 = get_support(proto, test_name)?;
    let error: f64 = get_error(proto, support, test_name)?;
    let half_life: Duration = get_half_life(proto, test_name)?;

    let config: Config = Config::new(support, error, half_life)?;
    Ok((LossyEwmaCounterImpl::new(start_time, config), start_time))
}

/// Performs the one-time action on `counter`, and returns the new time after the action.
fn perform_action(
    counter: &mut impl LossyEwmaCounter<String>,
    action_proto: &OneTimeActionP,
    start_time: DateTime<Utc>,
    test_name: &str,
) -> anyhow::Result<DateTime<Utc>> {
    let mut now: DateTime<Utc> = start_time;

    match action_proto.action.as_ref() {
        None => return Err(anyhow!("Test {}: Action unspecified", test_name)),
        Some(IncrementBy(proto)) => {
            let key: String = match &proto.key {
                None => return Err(anyhow!("Test {}: IncrementBy key unspecified", test_name)),
                Some(key) => key.clone(),
            };
            let value: i32 = match &proto.value.increment_value {
                None => return Err(anyhow!("Test {}: IncrementBy value unspecified", test_name)),
                Some(IncrementValue::Random(_)) => rng().random_range(1..i32::MAX),
                Some(IncrementValue::Value(v)) => *v,
            };
            counter.increment_by(now, key, value);
        }
        Some(AdvanceClock(proto)) => match proto.ms {
            None => return Err(anyhow!("Test {}: Advance clock ms unspecified", test_name)),
            Some(ms) => now += Duration::milliseconds(ms),
        },
        Some(CheckHotKeys(proto)) => {
            let expected: &Vec<KeyP> = &proto.expected;
            let actual: HashMap<&String, f64> = counter.get_hot_keys();

            // Create a map for expected hot keys for easier lookup.
            let expected: HashMap<String, f64> = expected
                .iter()
                .map(|entry: &KeyP| {
                    let key: String = match &entry.key {
                        None => return Err(anyhow!("Test {}: Hot key unspecified", test_name)),
                        Some(key) => key.clone(),
                    };

                    let contribution: f64 = match entry.contribution {
                        None => {
                            return Err(anyhow!("Test {}: contribution unspecified", test_name))
                        }
                        Some(contribution) => contribution,
                    };

                    Ok((key, contribution))
                })
                .collect::<anyhow::Result<HashMap<String, f64>>>()?;

            // Check if the number of actual hot keys matches the expected.
            if actual.len() != expected.len() {
                return Err(anyhow!(
                    "Test {} failed: expected {} hot keys, but got {}",
                    test_name,
                    expected.len(),
                    actual.len()
                ));
            }

            // Iterate through the actual hot keys and check against expected.
            for entry in actual {
                let (key, contribution): (&String, f64) = entry;
                // Check if the key is present in the expected hot keys.
                match expected.get(key) {
                    None => {
                        return Err(anyhow!(
                            "Test {} failed: unexpected hot key {}, not in expected",
                            test_name,
                            key
                        ));
                    }
                    Some(expected_contribution) => {
                        // Check if the contribution matches.
                        if (expected_contribution - contribution).abs() > 1e-10 {
                            return Err(anyhow!(
                                "Test {} failed: expected hot key ({} -> {}), but got ({} -> {})",
                                test_name,
                                key,
                                expected_contribution,
                                key,
                                contribution
                            ));
                        }
                    }
                }
            }
        }
    }

    Ok(now)
}

/// Loops through all actions and executes them on `counter`.
fn perform_actions(
    actions: &Vec<TestActionP>,
    counter: &mut impl LossyEwmaCounter<String>,
    start_time: DateTime<Utc>,
    test_name: &str,
) -> anyhow::Result<()> {
    let mut now: DateTime<Utc> = start_time;

    for action in actions {
        match &action.action {
            None => return Err(anyhow!("Test {}: Action unspecified", test_name)),
            Some(OneTimeAction(proto)) => {
                now = perform_action(counter, proto, now, test_name)?;
            }
            Some(RepeatedAction(proto)) => {
                let repetitions: i32 = match proto.repeat_count {
                    None => return Err(anyhow!("Test {}: Repeat count unspecified", test_name)),
                    Some(count) => count,
                };
                for _ in 0..repetitions {
                    for action in &proto.actions {
                        now = perform_action(counter, action, now, test_name)?;
                    }
                }
            }
        }
    }

    Ok(())
}

/// Runs the test case for [InefficientLossyEwmaCounter].
fn run_inefficient_counter_test_case(test_case: &TestCaseP, test_name: &str) -> anyhow::Result<()> {
    let factory_params: &LossyEwmaCounterP = &test_case.lossy_ewma_counter;

    let repetitions: i32 = test_case.repeated_count_opt.unwrap_or(1);
    let interval: i64 = test_case.repeated_interval_ms_opt.unwrap_or(0);

    for i in 0..repetitions {
        // Create the lossy EWMA counter.
        let (mut counter, start_time): (InefficientLossyEwmaCounter<String>, DateTime<Utc>) =
            create_inefficient_lossy_ewma_counter(test_name, factory_params)?;

        let now: DateTime<Utc> = start_time + Duration::milliseconds(interval * i as i64);
        // Test plan: verify that invalid cases return errors for [InefficientLossyEwmaCounter].
        perform_actions(&test_case.actions, &mut counter, now, test_name)?;
    }

    Ok(())
}

/// Runs the test case for [LossyEwmaCounter].
fn run_prod_counter_test_case(test_case: &TestCaseP, test_name: &str) -> anyhow::Result<()> {
    let factory_params: &LossyEwmaCounterP = &test_case.lossy_ewma_counter;

    let repetitions: i32 = test_case.repeated_count_opt.unwrap_or(1);
    let interval: i64 = test_case.repeated_interval_ms_opt.unwrap_or(0);

    // Perform the actions in the test case.
    for i in 0..repetitions {
        // Create the lossy EWMA counter.
        let (mut counter, start_time): (LossyEwmaCounterImpl<String>, DateTime<Utc>) =
            create_lossy_ewma_counter(test_name, factory_params)?;

        let now: DateTime<Utc> = start_time + Duration::milliseconds(interval * i as i64);

        perform_actions(&test_case.actions, &mut counter, now, test_name)?;
    }

    Ok(())
}

#[test]
fn default_lossy_ewma_config() {
    // Test plan: verify that the default lossy EWMA config is valid.
    Config::default(); // Should not panic.
}

#[test]
fn invalid_cases_inefficient_lossy_ewma_counter() {
    // Test plan: verify that invalid cases return errors for [InefficientLossyEwmaCounter].
    let invalid_cases: &Vec<TestCaseP> = &TEST_DATA.invalid_cases;

    for test_case in invalid_cases {
        // Requires the error message and test name to be set.
        let error_message: &str = test_case.error_message_opt.as_ref().unwrap();
        let test_name: &str = test_case.test_case_name.as_ref().unwrap();

        let result: anyhow::Result<()> = run_inefficient_counter_test_case(test_case, test_name);
        match result {
            Ok(_) => panic!("Test {} should have failed, but passed", test_name),
            Err(err) => match err.downcast_ref::<Status>() {
                None => panic!("Test {} failed with unexpected error: {:?}", test_name, err),
                Some(status) => {
                    assert_eq!(
                        status.code(),
                        Code::InvalidArgument,
                        "Test case {} failed with unexpected status code: {}",
                        test_name,
                        status.code()
                    );
                    assert!(
                        status.message().contains(error_message),
                        "Test {} failed with unexpected error message: {}, expected to contain {}",
                        test_name,
                        status.message(),
                        error_message
                    );
                }
            },
        }
    }
}

#[test]
fn valid_cases_inefficient_lossy_ewma_counter() {
    // Test plan: verify that valid cases pass for [InefficientLossyEwmaCounter].
    // Note: since the inefficient counter does not defer decay, we don't check for overflows.
    let valid_cases: &Vec<TestCaseP> = &TEST_DATA.valid_cases;

    for test_case in valid_cases {
        let test_name: &str = test_case.test_case_name.as_ref().unwrap();

        // Skip tests that expect overflows.
        if let Some(expected_overflow) = &test_case.expected_overflows_opt
            && *expected_overflow
        {
            continue; // Skip this test case.
        }

        let result: anyhow::Result<()> = run_inefficient_counter_test_case(test_case, test_name);
        assert!(
            result.is_ok(),
            "Test {} failed with unexpected error: {:?}",
            test_case.test_case_name.as_ref().unwrap(),
            result
        );
    }
}

#[test]
fn invalid_cases_lossy_ewma_counter() {
    // Test plan: verify that invalid cases return errors for [InefficientLossyEwmaCounter].
    let invalid_cases: &Vec<TestCaseP> = &TEST_DATA.invalid_cases;

    for test_case in invalid_cases {
        // Requires the error message and test name to be set.
        let error_message: &str = test_case.error_message_opt.as_ref().unwrap();
        let test_name: &str = test_case.test_case_name.as_ref().unwrap();

        let result: anyhow::Result<()> = run_prod_counter_test_case(test_case, test_name);
        match result {
            Ok(_) => panic!("Test {} should have failed, but passed", test_name),
            Err(err) => match err.downcast_ref::<Status>() {
                None => panic!("Test {} failed with unexpected error: {:?}", test_name, err),
                Some(status) => {
                    assert_eq!(
                        status.code(),
                        Code::InvalidArgument,
                        "Test case {} failed with unexpected status code: {}",
                        test_name,
                        status.code()
                    );
                    assert!(
                        status.message().contains(error_message),
                        "Test {} failed with unexpected error message: {}, expected to contain {}",
                        test_name,
                        status.message(),
                        error_message
                    );
                }
            },
        }
    }
}

#[test]
fn valid_cases_lossy_ewma_counter() {
    // Test plan: verify that valid cases pass for [LossyEwmaCounter].
    let valid_cases: &Vec<TestCaseP> = &TEST_DATA.valid_cases;

    // Get the number of overflows before running the tests.
    let amplication_overflows: usize = for_test_amplification_overflow_count();
    let total_sum_overflows: usize = for_test_total_sum_overflow_count();

    for test_case in valid_cases {
        let test_name: &str = test_case.test_case_name.as_ref().unwrap();

        let result: anyhow::Result<()> = run_prod_counter_test_case(test_case, test_name);
        assert!(
            result.is_ok(),
            "Test {} failed with unexpected error: {:?}",
            test_case.test_case_name.as_ref().unwrap(),
            result
        );

        // Skip tests that expect overflows.
        if let Some(expected_overflow) = &test_case.expected_overflows_opt
            && *expected_overflow
        {
            assert!(
                for_test_amplification_overflow_count() > amplication_overflows,
                "Test {} did not cause amplification overflow as expected, {} overflows before, {} after",
                test_name, amplication_overflows,
                for_test_amplification_overflow_count()
            );
            assert!(
                for_test_total_sum_overflow_count() > total_sum_overflows,
                "Test {} did not cause total sum overflow as expected, {} overflows before, {} after",
                test_name, total_sum_overflows,
                for_test_total_sum_overflow_count()
            );
        }
    }
}

/// PRECONDITION: The `counter` and `reference_counter` must have the same config and start time.
///
/// Checks that the hot keys returned by the [LossyEwmaCounter] are consistent with the
/// true contributions tracked in the reference [InefficientLossyEwmaCounter] (because the later
/// never drops state for any keys, it knows the true contributions of each key). This allows us to
/// check whether the implementation of [LossyEwmaCounter] satisfies its obligations,
/// specifically:
///
/// - It returns all true hot keys (i.e., those with `true_contribution > support`).
/// - While false negatives are permitted, the true contributions of those keys must be within the
///   configured error and tolerance (i.e. keys with `true_contribution >= support - error`)
/// - The estimated contributions must be within `[true_contribution - error, true_contribution]`,
///   underestimates by up to `error` are permitted, overestimates are not.
///
/// For all comparisons, `tolerance` is used to allow for some absolute floating point error. For
/// some test cases, the tolerance may be 0 (e.g., when using the "well-behaved" decay values like
/// 0.5 or 0.25).
fn check_hot_keys(
    counter: &mut LossyEwmaCounterImpl<i32>,
    reference_counter: &mut InefficientLossyEwmaCounter<i32>,
    tolerance: f64,
) {
    assert!(counter.config() == reference_counter.config(), "Counters must have the same config");
    assert!(
        counter.start_time() == reference_counter.start_time(),
        "Counters must have the same start time"
    );

    let hot_keys_original_map: HashMap<&i32, f64> = counter.get_hot_keys();
    let reference_hot_keys_original_map: HashMap<&i32, f64> = reference_counter.get_hot_keys();

    // Convert to maps where the key owns the data, for easier comparison.
    let hot_keys: HashMap<i32, f64> = hot_keys_original_map
        .iter()
        .map(|(k, v)| (**k, *v))
        .collect();
    let reference_hot_keys: HashMap<i32, f64> = reference_hot_keys_original_map
        .iter()
        .map(|(k, v)| (**k, *v))
        .collect();

    // The reference impl returns all hot keys with `contribution >= support - error - tolerance`,
    // and the actual implementation is permitted to return any of these. The actual implementation
    // is also required to return all true hot keys with `contribution > support + tolerance`.
    for entry in &hot_keys {
        let (key, contribution): (i32, f64) = (*entry.0, *entry.1);
        assert!(
            reference_hot_keys.contains_key(&key),
            "key {} is not a possible hot key (estimated contribution: {})",
            key,
            contribution
        );
        let true_contribution: f64 = *reference_hot_keys.get(&key).unwrap();

        // The lossy counter is allowed to underestimate the contribution by as much as `error`.
        assert!(
            contribution >= true_contribution - counter.config().error() - tolerance,
            "key {} estimated contribution {} is less than true contribution {} - error",
            key,
            contribution,
            true_contribution - counter.config().error() - tolerance,
        );

        // But it is not permitted to overestimate it.
        assert!(
            contribution <= true_contribution + tolerance,
            "key {} estimated contribution {} is greater than true contribution {}",
            key,
            contribution,
            true_contribution
        );

        // Verify that the implementation doesn't have any false negatives.
        for entry in &reference_hot_keys {
            let (ref_key, true_contribution): (i32, f64) = (*entry.0, *entry.1);
            if true_contribution > counter.config().support() + tolerance {
                assert!(
                    hot_keys.contains_key(&ref_key),
                    "missing hot key {} -> {}",
                    ref_key,
                    true_contribution,
                )
            }
        }
    }
}

#[test]
fn randomized() {
    // Test plan: Verify that the production LossyEwmaCounter generates the same output as the
    // InefficientLossyEwmaCounter reference implementation given the same input. Verify this by
    // generating random keys using a Power law/Zipfian distribution and recording random values in
    // both implementations and checking that the hot keys returned from the LossyEwmaCounter are
    // consistent with those returned by the reference implementation (see `check_hot_keys`).
    // Test case parameters:
    //  - should_interleave_get_hot_keys_calls: because accessing hot keys has side-effects on
    //    the internal representation used by the LossyEwmaCounter, we vary whether or not calls to
    //    `get_hot_keys` are interleaved with `increment_by` calls in each test case.
    //  - support: determines the Config.support configuration. For this test, we always use
    //    `error = support / 10`.
    //  - half_life: determines the Config.half_life configuration.
    //  - tolerance: floating point error tolerance (see `check_hot_keys` spec). The `half_life`
    //    value determines the tolerance in practice (1 is well-behaved, 10 is not!).
    // While the key generator generates up to 8000 distinct key values, for the Power law
    // distribution used, the vast majority of keys will have low frequency. We validate that the
    // trials generate at least 1000 distinct key values but that the lossy implementation maintains
    // state for at most 50 of those keys
    let test_case: &RandomizedReferenceTestCaseP = &TEST_DATA.randomized_with_reference_case;
    assert!(test_case.requires_hardcoding.unwrap());

    struct RandomizedSubTestCase<'a> {
        description: &'a str,
        should_interleave_get_hot_keys_calls: bool,
        support: f64,
        half_life: Duration,
        tolerance: f64,
    }

    let mut rng = rng();
    let zipf_distribution = Zipf::new(8000.0, 2.0).unwrap();
    let mut now: DateTime<Utc> = DateTime::<Utc>::from_timestamp_millis(0).unwrap();

    let sub_cases = &[
        RandomizedSubTestCase {
            description: "half_life = 1 second (interleaved gets)",
            should_interleave_get_hot_keys_calls: true,
            support: 0.05,
            half_life: Duration::seconds(1),
            tolerance: 0.0,
        },
        RandomizedSubTestCase {
            description: "half_life = 1 second (non-interleaved gets)",
            should_interleave_get_hot_keys_calls: false,
            support: 0.05,
            half_life: Duration::seconds(1),
            tolerance: 0.0,
        },
        RandomizedSubTestCase {
            description: "half_life = 10 seconds (interleaved gets)",
            should_interleave_get_hot_keys_calls: true,
            support: 0.05,
            half_life: Duration::seconds(10),
            tolerance: 1e-12,
        },
        RandomizedSubTestCase {
            description: "half_life = 10 seconds (non-interleaved gets)",
            should_interleave_get_hot_keys_calls: false,
            support: 0.05,
            half_life: Duration::seconds(10),
            tolerance: 1e-12,
        },
    ];

    for sub_case in sub_cases {
        let config = Config::new(
            sub_case.support,
            sub_case.support / 10.0, // error = support / 10
            sub_case.half_life,
        )
        .unwrap();

        let mut counter: LossyEwmaCounterImpl<i32> = LossyEwmaCounterImpl::new(now, config);
        let mut reference_counter: InefficientLossyEwmaCounter<i32> =
            InefficientLossyEwmaCounter::new_with_tolerance(now, config, sub_case.tolerance);

        for _ in 0..1_000_000 {
            // Increase by 0-1ms.
            now += Duration::nanoseconds(rng.random_range(0..1_000_000));
            let key: i32 = zipf_distribution.sample(&mut rng) as i32;
            counter.increment_by(now, key, 1);
            reference_counter.increment_by(now, key, 1);

            // Aperiodically check that the hot keys returned by the counters are consistent if we
            // are testing this interleaving in the current test variation.
            if sub_case.should_interleave_get_hot_keys_calls && rng.random_range(0.0..1.0) < 0.01 {
                check_hot_keys(&mut counter, &mut reference_counter, sub_case.tolerance);
            }
        }

        // Eventually check that the hot keys returned by the counters are consistent.
        check_hot_keys(&mut counter, &mut reference_counter, sub_case.tolerance);

        // Check that the number of keys tracked by the actual counter is substantially less than
        // the number tracked by the reference counter. In the current distribution, very few keys
        // should appear with significant frequency.
        assert!(
            reference_counter.num_keys() >= 1000,
            "Sub-case {}: Expected at least 1000 distinct keys, but got {}",
            sub_case.description,
            reference_counter.num_keys()
        );
        assert!(
            counter.for_test_num_keys() <= 50,
            "Sub-case {}: Expected at most 50 keys, but got {}",
            sub_case.description,
            counter.for_test_num_keys()
        );
    }
}

#[test]
fn tiny_half_life() {
    // Test plan: verify that a very tiny half-life is handled correctly by LossyEwmaCounter. This
    // is defined as a Rust-native test and not using the textproto fixture, because the test proto
    // only allows defining half-life in millisecond's granularity. So we have this extra case to
    // verify half-life below 1 millisecond.

    // 0.99 millisecond half-life.
    let half_life: Duration = Duration::microseconds(990);
    let support: f64 = 0.05;
    let error: f64 = 0.005;

    // Config should be successfully created, with a decay in (0, 1) rather than truncated to 0
    // (the old whole-millisecond behavior).
    let config: Config = Config::new(support, error, half_life)
        .expect("sub-millisecond half-life must produce a valid config");
    assert!(config.decay() > 0.0 && config.decay() < 1.0);

    let start_time: DateTime<Utc> = DateTime::<Utc>::from_timestamp_millis(0).unwrap();
    let mut counter: LossyEwmaCounterImpl<String> = LossyEwmaCounterImpl::new(start_time, config);

    let old_hot_key: String = "old".to_string();
    let new_hot_key: String = "new".to_string();

    // The old key has larger amount of load initially.
    counter.increment_by(start_time, old_hot_key.clone(), 10000);

    // One second later, a new key has relatively smaller load. However, the half-life is so tiny
    // that even after one second, the load from the old hot key is decayed to almost 0. So
    // eventually only the new kye will be reported as hot key.
    counter.increment_by(start_time + Duration::seconds(1), new_hot_key.clone(), 10);

    let actual_hot_keys: HashMap<String, f64> = counter
        .get_hot_keys()
        .into_iter()
        .map(|(key, contribution): (&String, f64)| (key.clone(), contribution))
        .collect();

    assert!(!actual_hot_keys.contains_key(&old_hot_key));
    // Most of the load contribution is from the new key.
    assert!((*actual_hot_keys.get(&new_hot_key).unwrap() - 1.0).abs() < 1e-9);
}

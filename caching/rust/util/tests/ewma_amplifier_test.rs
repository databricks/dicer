use std::panic::{catch_unwind, AssertUnwindSafe};

use chrono::{DateTime, Duration, Utc};
use tonic::{Code, Status};

use caching_rust_util::internal_for_test::EwmaAmplifier;
use caching_rust_util_testing::test_data::EWMA_AMPLIFIER_TEST_DATA as TEST_DATA;
use caching_util_test_proto_test_data_proto_buffa::databricks::caching::test::ewma_amplifier_test_data_p::{
    action_p::Action,
    AdvanceAndRescaleIfNeededP,
    AmplifyAndIncrementP,
    AssertTotalEqualsP,
    EwmaAmplifierP,
    RescaleP,
};

/// Relative tolerance for verifying the amplifier's returned f64 value results. This is more
/// relaxed than floating point error. The relaxed resolution allows writing shorter numbers in the
/// textproto test fixture as expected results.
const RELATIVE_TOLERANCE: f64 = 1e-6;

/// Constructs an `EwmaAmplifier` from the parameters in `proto` and `start_time`.
fn create_amplifier(
    proto: &EwmaAmplifierP,
    start_time: DateTime<Utc>,
) -> Result<EwmaAmplifier, tonic::Status> {
    let half_life_seconds: f64 = proto.half_life_seconds.unwrap();

    // This error handling is not for increasing coverage, but just to keep the behavior consistent
    // in Rust and Scala. The textproto text fixture specifies a half-life, whereas the
    // EwmaAmplifier factory in Rust accepts a decay directly. This code covers the gap and keeps
    // the same test fixture to work for both Rust and Scala tests.
    if half_life_seconds <= 0.0 || half_life_seconds.is_nan() {
        return Err(tonic::Status::invalid_argument(format!(
            "half_life_seconds must be positive, got {}",
            half_life_seconds
        )));
    }
    let decay: f64 = 2f64.powf(-1.0 / half_life_seconds);

    EwmaAmplifier::new(start_time, decay)
}

/// Runs the single `action` against the `amplifier`. See the proto definition for the meaning of
/// the actions.
fn perform_action(
    action: &Action,
    amplifier: &mut EwmaAmplifier,
    amplifier_start_time: DateTime<Utc>,
    test_name: &str,
) {
    match action {
        Action::AmplifyAndIncrement(amplify_and_increment) => {
            let AmplifyAndIncrementP {
                value,
                expected_amplified_value,
                error_message_opt,
                ..
            } = amplify_and_increment.as_ref();
            let result: Result<f64, Status> = amplifier.amplify_and_increment(value.unwrap());
            match error_message_opt {
                Some(expected_error_message) => {
                    // Verify the expected error message when that is set.
                    let actual_error_status: Status =
                        result.expect_err("amplify_and_increment must report the overflow");
                    assert!(actual_error_status
                        .message()
                        .contains(expected_error_message.as_str()));
                    // The only possible error from amplify_and_increment is FailedPrecondition.
                    assert!(actual_error_status.code() == Code::FailedPrecondition);
                }
                None => {
                    // When the expected error message is not set, verify the expected amplified
                    // value.
                    let actual_amplified_value: f64 = result.unwrap_or_else(|status| {
                        panic!(
                            "Test '{}': amplify_and_increment with value={} failed: {}",
                            test_name,
                            value.unwrap(),
                            status
                        )
                    });
                    let expected_amplified_value: f64 = expected_amplified_value.unwrap();
                    assert!(
                        (actual_amplified_value - expected_amplified_value).abs()
                            <= expected_amplified_value.abs() * RELATIVE_TOLERANCE
                    );
                }
            }
        }
        Action::Rescale(rescale) => {
            let RescaleP {
                expected_factor_opt,
                ..
            } = rescale.as_ref();
            let result: Option<f64> = amplifier.rescale();
            match expected_factor_opt {
                Some(expected_factor) => {
                    // When expected_factor_opt is defined, the actual factor must be the same as
                    // it.
                    let actual_factor: f64 = result.unwrap_or_else(|| {
                        panic!(
                            "Test '{}': rescale() returned no factor, expected {}",
                            test_name, expected_factor
                        )
                    });
                    assert!(
                        (actual_factor - expected_factor).abs()
                            <= expected_factor.abs() * RELATIVE_TOLERANCE
                    );
                }
                None => {
                    // When expected_factor_opt is empty, the actual factor must be also empty.
                    assert!(result.is_none());
                }
            }
        }
        Action::AdvanceAndRescaleIfNeeded(advance_and_rescale_if_needed) => {
            let AdvanceAndRescaleIfNeededP {
                elapsed_ms,
                expected_rescale_factor_opt,
                ..
            } = advance_and_rescale_if_needed.as_ref();
            let now: DateTime<Utc> =
                amplifier_start_time + Duration::milliseconds(elapsed_ms.unwrap());
            let result: Option<f64> = amplifier.advance_and_rescale_if_needed(now);
            match expected_rescale_factor_opt {
                Some(expected_rescale_factor) => {
                    // When expected_rescale_factor_opt is defined, a rescale must have happened and
                    // the returned factor must be the same as it.
                    let actual_rescale_factor: f64 = result.unwrap_or_else(|| {
                        panic!(
                            "Test '{}': advance_and_rescale_if_needed(now=+{}ms) did not \
                                 rescale, expected factor {}",
                            test_name,
                            elapsed_ms.unwrap(),
                            expected_rescale_factor
                        )
                    });
                    assert!(
                        (actual_rescale_factor - expected_rescale_factor).abs()
                            <= expected_rescale_factor.abs() * RELATIVE_TOLERANCE
                    );
                }
                None => {
                    // When expected_rescale_factor_opt is empty, no rescale must have happened.
                    assert!(result.is_none());
                }
            }
        }
        Action::AssertTotalEquals(assert_total_equals) => {
            let AssertTotalEqualsP { expected_total, .. } = assert_total_equals.as_ref();
            let actual_total: f64 = amplifier.total();
            let expected_total: f64 = expected_total.unwrap();
            assert!(
                (actual_total - expected_total).abs() <= expected_total.abs() * RELATIVE_TOLERANCE
            );
        }
    }
}

#[test]
fn run_all_cases() {
    // For each case, construct a new EwmaAmplifier from its factory parameters, and supply the
    // Amplifier to the rest of the test actions for that test case.
    for test_case in &TEST_DATA.test_cases {
        let test_name: &str = test_case
            .test_case_name
            .as_ref()
            .expect("test_case_name must be set on every case");
        let start_time: DateTime<Utc> = Utc::now();

        let ewma_amplifier_p: &EwmaAmplifierP = test_case.ewma_amplifier.as_option().unwrap();
        let create_result: Result<EwmaAmplifier, Status> =
            create_amplifier(ewma_amplifier_p, start_time);

        if let Some(expected_create_error) = ewma_amplifier_p.error_message_opt.as_ref() {
            // When an expected error message is set, the creation must fail with the expected
            // error.
            assert!(create_result.is_err(), "Test `{}` failed: missing expected error", test_name);
            let status = create_result.err().unwrap();
            assert_eq!(
                status.code(),
                Code::InvalidArgument,
                "Test '{}' failed with unexpected status code: {}",
                test_name,
                status.code()
            );
            assert!(
                status.message().contains(expected_create_error.as_str()),
                "Test '{}' failed with unexpected error message: '{}', expected to \
                             contain '{}'",
                test_name,
                status.message(),
                expected_create_error
            );
            continue;
        }

        // The creation succeeded. Run the rest of the actions using the created Amplifier.
        let mut amplifier: EwmaAmplifier = create_result
            .unwrap_or_else(|err| panic!("Test '{}': failed to construct: {:?}", test_name, err));
        for action_p in &test_case.actions {
            // AssertUnwindSafe is fine because a panic fails the whole test, so the possibly
            // half-updated amplifier is never used again.
            let perform_action_result = catch_unwind(AssertUnwindSafe(|| {
                perform_action(
                    action_p.action.as_ref().unwrap(),
                    &mut amplifier,
                    start_time,
                    test_name,
                );
            }));
            if let std::thread::Result::Err(error) = perform_action_result {
                panic!("Test '{}' failed with error '{:?}'", test_name, error);
            }
        }
    }
}

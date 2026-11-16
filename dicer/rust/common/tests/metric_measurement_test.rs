use dicer_rust_common::{
    validate_measurement,
    validate_reported_measurement,
    MAX_REPORTED_MEASUREMENT,
};

#[test]
fn test_validate_measurement() {
    // Test plan: Verify that `validate_measurement` correctly validates metric measurements.
    assert!(validate_measurement(0.0).is_ok());
    assert!(validate_measurement(1.0).is_ok());

    let err = validate_measurement(f64::INFINITY).unwrap_err();
    assert!(err.message().contains("must be finite"));

    let err = validate_measurement(f64::NAN).unwrap_err();
    assert!(err.message().contains("must be a number"));

    let err = validate_measurement(-1.0).unwrap_err();
    assert!(err.message().contains("must be non-negative"));
}

#[test]
fn test_validate_reported_measurement() {
    // Test plan: Verify that `validate_reported_measurement` accepts valid values up to and
    // including `MAX_REPORTED_MEASUREMENT`, and rejects larger values and invalid measurements.
    assert!(validate_reported_measurement(0.0).is_ok());
    assert!(validate_reported_measurement(MAX_REPORTED_MEASUREMENT).is_ok());

    let err = validate_reported_measurement(MAX_REPORTED_MEASUREMENT * 1.1).unwrap_err();
    assert!(err.message().contains("must be at most"));

    let err = validate_reported_measurement(f64::NAN).unwrap_err();
    assert!(err.message().contains("must be a number"));
}

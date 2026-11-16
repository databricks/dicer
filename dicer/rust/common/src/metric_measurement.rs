use tonic::Status;

/// The largest value a Slicelet may report for any metric (e.g. the load of a Slice replica or
/// key). It is far above any real value, and small enough that the Assigner can multiply reported
/// values by replica counts and add them up without reaching infinity.
pub const MAX_REPORTED_MEASUREMENT: f64 = 1e100;

/// Validates that the measured value of a metric (e.g. load or size) is a non-negative, finite
/// number.
pub fn validate_measurement(value: f64) -> Result<(), Status> {
    if value.is_nan() {
        return Err(Status::invalid_argument(format!("{value} must be a number")));
    }
    if value.is_sign_negative() {
        return Err(Status::invalid_argument(format!("{value} must be non-negative")));
    }
    if value.is_infinite() {
        return Err(Status::invalid_argument(format!("{value} must be finite")));
    }
    Ok(())
}

/// Checks a metric value reported by a Slicelet: it must be a valid measurement (see
/// [`validate_measurement`]) and no larger than [`MAX_REPORTED_MEASUREMENT`].
pub fn validate_reported_measurement(value: f64) -> Result<(), Status> {
    validate_measurement(value)?;
    if value > MAX_REPORTED_MEASUREMENT {
        return Err(Status::invalid_argument(format!(
            "{value} must be at most {MAX_REPORTED_MEASUREMENT}"
        )));
    }
    Ok(())
}

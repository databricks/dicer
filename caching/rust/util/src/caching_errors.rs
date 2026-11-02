use std::fmt::{self, Write};

use prometheus_client::encoding::{EncodeLabelSet, EncodeLabelValue, LabelValueEncoder};
use raf_metrics::counter;

pub use crate::caching_error_code::{AlertOwnerTeam, CachingErrorCode, Severity};

counter!(
    CACHING_ERRORS,
    "Counter for common errors with severity and error code.",
    CachingErrorLabels
);

/// Increments the `caching_errors` Prometheus counter without logging. Used by
/// [`crate::prefix_logger::PrefixLogger`] which handles logging separately.
pub(crate) fn increment_alert_counter(
    severity: Severity,
    error_code: CachingErrorCode,
    prefix: impl Into<String>,
) {
    let labels = CachingErrorLabels::new(
        severity,
        error_code.error_code(),
        prefix.into(),
        error_code.owner_team().as_str(),
    );
    CACHING_ERRORS.get_or_create_owned(&labels).inc();
}

/// TEST-ONLY: Gets the counter value for the given severity, error code, and prefix.
///
/// This function is necessary because metrics are wrapped in a `LazyLock` and won't appear
/// in Prometheus output until first accessed. This helper ensures the metric is initialized
/// so its value can be reliably retrieved for verification. In contrast `verify_encoded_counter`
/// does not work with zero value verification if the counter has never been incremented.
pub fn get_caching_error_counter_value_for_test(
    severity: Severity,
    error_code: CachingErrorCode,
    prefix: &str,
) -> u64 {
    let labels = CachingErrorLabels::new(
        severity,
        error_code.error_code(),
        prefix.to_string(),
        error_code.owner_team().as_str(),
    );
    CACHING_ERRORS.get_or_create_owned(&labels).get()
}

impl EncodeLabelValue for Severity {
    fn encode(&self, encoder: &mut LabelValueEncoder) -> Result<(), fmt::Error> {
        let value: &str = match self {
            Severity::Degraded => "DEGRADED",
            Severity::Critical => "CRITICAL",
        };
        encoder.write_str(value)
    }
}

/// Prometheus labels for the caching errors counter.
#[derive(Clone, Debug, Hash, PartialEq, Eq, EncodeLabelSet)]
struct CachingErrorLabels {
    severity: Severity,
    error_code: &'static str,
    prefix: String,
    owner_team: &'static str,
}

impl CachingErrorLabels {
    fn new(
        severity: Severity,
        error_code: &'static str,
        prefix: String,
        owner_team: &'static str,
    ) -> Self {
        CachingErrorLabels {
            severity,
            error_code,
            prefix,
            owner_team,
        }
    }
}

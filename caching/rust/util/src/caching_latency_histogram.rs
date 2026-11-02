//! Convenience methods for recording operation latency metrics.

use std::fmt;
use std::time::{Duration, Instant};

use prometheus_client::encoding::{EncodeLabel, EncodeLabelSet, LabelSetEncoder};
use prometheus_client::metrics::family::Family;
use prometheus_client::metrics::histogram::Histogram;
use tonic::{Code, Status};

use crate::code_ext::CodeExt;
use crate::{system_clock, Clock};

/// A thin wrapper on a Prometheus histogram for recording latency metrics. By default, the wrapper
/// provides a set of exponential buckets that are appropriate for local caches (tens of
/// microseconds), remote caches (milliseconds), and db operations (tens, hundreds, or thousands of
/// milliseconds), and it provides convenience methods for recording the latency of synchronous
/// thunks.
///
/// RAF registers metrics statically. The supplied histogram owns the metric name, bucket boundaries,
/// and registry registration. Use `low_latency_bucket_secs()` for the default buckets.
/// This wrapper is threadsafe.
#[derive(Clone)]
pub struct CachingLatencyHistogram {
    /// The clock to use for measuring latency.
    clock: Clock,
    /// The sequence of label names for labels that are attached to observations of this metric.
    /// The order dictates the order in which label values need to be passed to `record_latency_sync`.
    extra_label_names: Vec<&'static str>,
    /// The underlying histogram.
    histogram: &'static Family<CachingLatencyHistogramLabels, Histogram>,
}

impl CachingLatencyHistogram {
    /// Record latency metrics for a thunk that returns a value synchronously, with extra labels
    /// computed from the result of the thunk.
    ///
    /// * `operation`: The cache operation whose latency is being recorded, e.g. "read" or "write".
    /// * `compute_extra_labels`: A function that computes the extra labels based on the result of
    ///   `thunk`. The order of returned labels must match the order of the label names sequence
    ///   that was passed to the factory method that created this object.
    /// * `thunk`: Thunk to instrument.
    ///
    /// If `thunk` returns an error, the latency will still be recorded and the error returned.
    /// Returns `INVALID_ARGUMENT` if the number of label values is incorrect.
    pub fn record_latency_sync<T>(
        &self,
        operation: &str,
        compute_extra_labels: impl FnOnce(&Result<T, Status>) -> Vec<String>,
        thunk: impl FnOnce() -> Result<T, Status>,
    ) -> Result<T, Status> {
        let start_time = self.clock.instant();
        let result = thunk(); // invokes the thunk.
        match &result {
            Ok(_) => self.on_success(operation, start_time, compute_extra_labels(&result))?,
            Err(status) => {
                self.on_failure(operation, start_time, status, compute_extra_labels(&result))?;
            }
        }
        result
    }

    /// Helper function to observe the latency with the given labels.
    pub fn observe_latency(
        &self,
        latency_duration: Duration,
        operation: &str,
        status_code: Code,
        error_code_opt: Option<&'static str>,
        extra_labels: Vec<String>,
    ) -> Result<(), Status> {
        if extra_labels.len() != self.extra_label_names.len() {
            return Err(Status::invalid_argument("Incorrect number of labels."));
        }
        let status_label = if status_code == Code::Ok {
            "success"
        } else {
            "failure"
        };
        let status_code_label = status_code.as_upper_str();
        let error_code_label = error_code_opt.unwrap_or("");
        let all_labels = CachingLatencyHistogramLabels {
            operation: operation.to_string(),
            status: status_label,
            grpc_status: status_code_label,
            error_code: error_code_label,
            extra_labels: self
                .extra_label_names
                .iter()
                .copied()
                .zip(extra_labels)
                .collect(),
        };
        self.histogram
            .get_or_create_owned(&all_labels)
            .observe(latency_duration.as_secs_f64());
        Ok(())
    }

    /// Records the latency of a successful operation.
    fn on_success(
        &self,
        operation: &str,
        start_time: Instant,
        extra_labels: Vec<String>,
    ) -> Result<(), Status> {
        let latency_duration = self.clock.instant() - start_time;
        self.observe_latency(latency_duration, operation, Code::Ok, None, extra_labels)
    }

    /// Records the latency of a failed operation.
    fn on_failure(
        &self,
        operation: &str,
        start_time: Instant,
        status: &Status,
        extra_labels: Vec<String>,
    ) -> Result<(), Status> {
        let latency_duration = self.clock.instant() - start_time;
        self.observe_latency(
            latency_duration,
            operation,
            status.code(),
            Some(status.code().error_code()),
            extra_labels,
        )
    }

    /// The buckets are chosen to be exponentially spaced 10 microseconds to 631 seconds
    /// (10.5 minutes).
    /// 10, 25.12, 63.10 (microseconds)
    /// 158.49, 398.11, 1000 (microseconds)
    /// 2.51, 6.31 (milliseconds)
    /// 15.85, 39.81, 100 (milliseconds)
    /// 251.19, 630.96 (milliseconds)
    ///
    /// 2.51, 15.85, 100.0, 630.96 (seconds)
    ///
    /// 10 microseconds to 631 milliseconds [13 buckets] is mainly for cache latency
    /// f(i) = 10 * 10^(0.4 * i) / 1000000
    /// and 2.51 seconds to 631 seconds [4 buckets] is for cache miss database latency
    /// f(i) = 10^(0.4 * (2 * i - 1))
    ///
    /// total 13 + 4 = 17 buckets
    ///
    /// Public so dependent crates can use these buckets in RAF's static histogram declarations.
    pub fn low_latency_bucket_secs() -> impl Iterator<Item = f64> {
        (0..13)
            .map(|i| 10.0_f64 * 10.0_f64.powf(0.4 * i as f64) / 1_000_000.0)
            .chain((1..5).map(|i| 10.0_f64.powf(0.4 * (2 * i - 1) as f64)))
    }

    /// Factory method for creating a `CachingLatencyHistogram`.
    ///
    /// `histogram` supplies the metric name, bucket boundaries, and registry registration.
    /// `extra_label_names` is the sequence of label names attached to observations of this metric.
    pub fn new(
        histogram: &'static Family<CachingLatencyHistogramLabels, Histogram>,
        extra_label_names: Vec<&'static str>,
    ) -> Self {
        Self {
            clock: system_clock(), // Measure latency using the system clock.
            extra_label_names,
            histogram,
        }
    }

    /// Test factory method that allows the caller to override the clock and histogram.
    pub fn create_for_test(
        histogram: &'static Family<CachingLatencyHistogramLabels, Histogram>,
        clock: Clock,
        extra_label_names: Vec<&'static str>,
    ) -> Self {
        Self {
            clock,
            extra_label_names,
            histogram,
        }
    }
}

/// Labels for a latency observation, including the labels supplied by the caller.
#[derive(Clone, Debug, Hash, PartialEq, Eq)]
pub struct CachingLatencyHistogramLabels {
    operation: String,
    status: &'static str,
    grpc_status: &'static str,
    error_code: &'static str,
    extra_labels: Vec<(&'static str, String)>,
}

impl EncodeLabelSet for CachingLatencyHistogramLabels {
    fn encode(&self, encoder: &mut LabelSetEncoder) -> Result<(), fmt::Error> {
        ("operation", self.operation.as_str()).encode(encoder.encode_label())?;
        ("status", self.status).encode(encoder.encode_label())?;
        ("grpc_status", self.grpc_status).encode(encoder.encode_label())?;
        ("error_code", self.error_code).encode(encoder.encode_label())?;
        for (name, value) in &self.extra_labels {
            (*name, value.as_str()).encode(encoder.encode_label())?;
        }
        Ok(())
    }
}

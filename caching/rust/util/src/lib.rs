pub mod build_info;
pub mod caching_error_code;
pub mod caching_errors;
pub mod caching_latency_histogram;
pub mod code_ext;
pub mod consistent_hash_ring;
pub mod deterministic_sampling;
pub mod ewma_counter;
pub mod ewma_quantile_summaries;
pub mod exponential_backoff;
pub mod format_bytes;
pub mod hyper_log_log;
pub mod instantiation_tracker;
pub mod kubernetes_cluster_uri;
pub mod lossy_ewma_counter;
pub mod prefix_logger;
pub mod protocol_duration;
pub mod quantile_summaries;
pub mod region_uri;
pub mod rfc1123;
pub mod sequential_executor;
pub mod sliding_hyper_log_log;
pub mod timestamp;
pub mod token_bucket;
pub mod unwrap_ref_or_default;
pub mod watch_cell;
pub mod watch_value_cell_poll_adapter;

pub use clock::{
    saturated_system_time_to_unix_micros,
    saturated_system_time_to_unix_millis,
    system_clock,
    system_time_from_unix_millis,
    Clock,
    Elapsed,
    PrettySystemTime,
};

mod clock;
mod ewma_accumulator;
mod ewma_amplifier;
pub mod panic_util;

/// This module is public for testing purposes only and should not be used
/// directly outside of this library.
pub mod internal_for_test {
    use tonic::Status;

    pub use crate::clock::{Clock, ClockImpl};
    pub use crate::ewma_accumulator::EwmaAccumulator;
    pub use crate::ewma_amplifier::EwmaAmplifier;

    /// Creates a new EWMA counter configuration from an alpha value.
    pub fn new_ewma_counter_config_from_alpha(
        alpha: f64,
    ) -> Result<crate::ewma_counter::Config, Status> {
        crate::ewma_counter::Config::new_from_alpha_for_test(alpha)
    }

    /// Creates a clock from an inner implementation.
    pub fn clock_from_impl<T: ClockImpl>(inner: std::sync::Arc<T>) -> Clock {
        Clock::from_impl(inner)
    }
}

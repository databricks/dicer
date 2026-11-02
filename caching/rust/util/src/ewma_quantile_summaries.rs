use chrono::{DateTime, Duration, Utc};
use tonic::Status;

use crate::ewma_amplifier::EwmaAmplifier;
use crate::quantile_summaries::QuantileSummaries;

/// A summary structure maintaining *approximate* quantiles over a stream of weighted
/// (value, weight) observations where weights are decayed in an EWMA manner based on the time they
/// were inserted.
///
/// See [`QuantileSummaries`] for a detailed contract of how the observations can be inserted or
/// queried, and the contract for approximate results.
pub struct EwmaQuantileSummaries<T: Ord> {
    /// The underlying [QuantileSummaries] tracking the histogram from values to the
    /// EWMA-transformed weights.
    summary: QuantileSummaries<T>,

    /// Amplifies the incoming weights to effectively decay the historical weights.
    amplifier: EwmaAmplifier,
}

impl<T: Ord> EwmaQuantileSummaries<T> {
    /// Creates an EWMA layer that decays weights with the given `half_life`, using `start_time` as
    /// the basis for the decay. See [`QuantileSummaries`] for the meaning of relative_error and
    /// sample_count_upper_bound.
    ///
    /// # Errors
    ///
    /// Returns [`Status::invalid_argument`] if `half_life` is not positive or `relative_error` is
    /// not in `(0, 1)`, and propagates any [`Status`] from [`QuantileSummaries::new`] (e.g. an
    /// invalid `sample_count_upper_bound`).
    pub fn new(
        start_time: DateTime<Utc>,
        half_life: Duration,
        relative_error: f64,
        sample_count_upper_bound: usize,
    ) -> Result<Self, Status> {
        if half_life <= Duration::zero() {
            return Err(Status::invalid_argument(format!(
                "half_life must be positive, got {}",
                half_life
            )));
        }
        if !(relative_error > 0.0 && relative_error < 1.0) {
            return Err(Status::invalid_argument(format!(
                "relative_error must be in (0, 1), got {}",
                relative_error
            )));
        }

        let summary: QuantileSummaries<T> =
            QuantileSummaries::new(relative_error, sample_count_upper_bound)?;
        let half_life_seconds: f64 = half_life.as_seconds_f64();
        let decay: f64 = 2.0f64.powf(-1.0 / half_life_seconds);
        let amplifier: EwmaAmplifier = EwmaAmplifier::new(start_time, decay)?;
        Ok(Self { summary, amplifier })
    }

    /// Inserts an observation of `value` with integer `weight` at time `now`. `weight`s for the
    /// same `value` will be effectively EWMA-smoothed based on their insertion time `now`.
    ///
    /// If `now`s passed to multiple inserts are non-monotonic, the highest timestamp seen when
    /// `insert` is called will be used.
    ///
    /// Inserting zero or negative weight is a no-op.
    ///
    /// No `Err` is expected to return. The returned type `Result` is only for future extension and
    /// client-side code compliance to avoid possible panic behavior.
    ///
    /// Note the interface accepts a [DataTime] here instead of monotonic [Instant]. We can use
    /// DateTime as we do elsewhere (LossyEwmaCounter, EwmaCounter) because the downstream consumer
    /// (e.g. Dicer Assigner) eventually needs to compare these timestamps from multiple sources
    /// (e.g. Slicelets), and the accuracy requirements aren't so tight that we need to complicate
    /// the code by passing an extra Instant alongside the DateTime.
    pub fn insert(&mut self, now: DateTime<Utc>, value: T, weight: i32) -> Result<(), Status> {
        if weight <= 0 {
            return Ok(());
        }
        if let Some(factor) = self.amplifier.advance_and_rescale_if_needed(now) {
            self.summary.rescale_weights(factor)?;
        }
        let amplified_weight: f64 = self.amplifier.amplify_and_increment(weight)?;
        self.summary.insert(value, amplified_weight)?;
        Ok(())
    }

    /// Queries a batch of quantiles against the current EWMA-weighted distribution. See
    /// [`QuantileSummaries::query`] for the input and output format.
    ///
    /// # Errors
    ///
    /// Returns [`Status`] if any quantile is outside `[0, 1]`.
    pub fn query(&self, quantiles: &[f64]) -> Result<Vec<(&T, f64)>, Status> {
        self.summary.query(quantiles)
    }
}

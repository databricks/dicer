//! A helper module for [`crate::lossy_ewma_counter`] and
//! [`crate::ewma_quantile_summaries`]. The [`EwmaAmplifier`] struct contains the state and
//! logic to implement the trick that exponentially amplifies the newly incoming values to
//! effectively decay the previous values. See [`EwmaAmplifier`] for details.

use std::sync::atomic::{AtomicUsize, Ordering};

use chrono::{DateTime, Utc};
use tonic::Status;

/// Test-only counter for the number of rescaling due to amplification overflow. Read via
/// [`for_test_amplification_overflow_count`].
static FOR_TEST_AMPLIFICATION_OVERFLOW_COUNT: AtomicUsize = AtomicUsize::new(0);

/// Test-only counter for the number of rescaling due to total sum overflow. Read via
/// [`for_test_total_sum_overflow_count`].
static FOR_TEST_TOTAL_SUM_OVERFLOW_COUNT: AtomicUsize = AtomicUsize::new(0);

/// TEST-ONLY: Returns the number of rescaling due to amplification overflow.
pub fn for_test_amplification_overflow_count() -> usize {
    FOR_TEST_AMPLIFICATION_OVERFLOW_COUNT.load(Ordering::SeqCst)
}

/// TEST-ONLY: Returns the number of rescaling due to total sum overflow.
pub fn for_test_total_sum_overflow_count() -> usize {
    FOR_TEST_TOTAL_SUM_OVERFLOW_COUNT.load(Ordering::SeqCst)
}

/// Why this struct is needed:
///
/// This is a helper struct for [`crate::lossy_ewma_counter::LossyEwmaCounter`] and
/// [`crate::ewma_quantile_summaries::EwmaQuantileSummaries`]. This struct contains the state
/// and logic to implement the trick that exponentially amplifies the newly incoming values
/// to effectively decay the previous values. For example, when we want to track the load
/// contribution of a large number of keys within a key range, for each key, the EWMA-smoothed
/// load contribution from a key `k` at time `t` equals (assuming seeded with 0, and `i` is
/// the timestamp)
///
/// keyLoadContribution
/// = keyLoad / keyRangeLoad
/// = sum(i=0..t where key_i=k, (1-α)^(t-i) * α * x_i) / sum(i=0..t, (1-α)^(t-i) * α * x_i)
/// = sum(i=0..t where key_i=k, (1-α)^(-i) * α * x_i) / sum(i=0..t, (1-α)^(-i) * α * x_i)
/// = sum(i=0..t where key_i=k, β^i * α * x_i) / sum(i=0..t, β^i * α * x_i)
/// where β = 1/(1-α).
///
/// So we only need to track the time-amplified load (β^i * a * x_i) for each key, and the
/// amplification factor (β^i * α) is easy to calculate and maintain. When time advances, we
/// don't need to explicitly decay the load for all keys by (1-α), which can be expensive. The
/// EwmaAmplifier provides helper methods to conveniently implement this amplification trick.
///
/// See <internal link> and <internal link> for more
/// details.
///
/// How this struct is used and implemented:
///
/// This struct keeps track of the current time, calculates and maintains the amplification
/// factor for the current time, and tells the users the correct amplified value when they
/// want to increment a value at a given time. The struct also handles the logic to rescale
/// the amplification and accumulated values to avoid overflow.
///
/// When using this struct, users should always pair every
/// [Self::amplify_and_increment] with a preceding [Self::advance_and_rescale_if_needed]:
/// `advance_and_rescale_if_needed` advances the internally tracked time by the amplifier up
/// to `now` and conditionally rescales the amplification and total load when either is about
/// to overflow, while `amplify_and_increment` then computes the amplified value for the
/// just-advanced epoch. Callers can also call [Self::rescale] to unconditionally rescale.
///
/// Note this helper struct is only useful when the user wants to keep track of the EWMA
/// values that are attributed into multiple disjoint sub-categories (e.g. load attributed to
/// multiple keys or key sub-ranges), and it can correctly maintain the ratio between the
/// attributed value for each sub-category and the total accumulated value without updating
/// the metadata for each sub-category when the time advances. For example, in
/// [`crate::lossy_ewma_counter::LossyEwmaCounter`], this struct can be used to maintain the
/// EWMA values for each key; in [`crate::ewma_quantile_summaries::EwmaQuantileSummaries`], it
/// can be used to maintain the gap and delta metadata for each key range.
///
/// An example usage of this struct:
///
/// ```ignore
/// let factor_opt: Option<f64> = amplifier.advance_and_rescale_if_needed(now);
/// if let Some(factor) = factor_opt {
///     // Multiply the caller's external aggregates (estimated sums, summary, ...) for each
///     // sub-category by `factor`.
/// }
/// let amplified: f64 = amplifier.amplify_and_increment(value)?;
/// // Incorporate `amplified` into the caller's external aggregation in the correct
/// // sub-category.
/// ```
///
/// Note the absolute amplified value is meaningless by itself. Only the ratio of the
/// amplified value for a "sub-category" and the total amplified value will make sense.
pub struct EwmaAmplifier {
    /// The time at which the struct starts.
    start_time: DateTime<Utc>,

    /// The classic EWMA decay per second. Equal to `1 - alpha`. See derivation of alpha in
    /// <internal link>.
    decay: f64,

    /// The alpha value, which is the weight of all measurements at or before `base_epoch`
    /// (see <internal link> for background on alpha). It is equal to
    /// `1 - decay`.
    alpha: f64,

    /// Time-weighted sum of all values reported to [Self::amplify_and_increment]. When `base_epoch`
    /// is the same as `current_epoch` (which is true after rescaling), the total sum is also equal
    /// to the exponentially weighted moving average (EWMA) of the rate of increments across all
    /// epochs.
    total_sum: f64,

    /// INVARIANT: `current_epoch >= base_epoch`
    ///
    /// The (monotonically increasing) epoch. This is the latest epoch based on timestamps
    /// supplied to [Self::amplify_and_increment].
    current_epoch: i64,

    /// INVARIANT: `base_epoch <= current_epoch`
    ///
    /// The base, or "zero", epoch relative to which decay is computed. When the base epoch
    /// advances, the `total_sum` and the externally kept per-category sums should decay by
    /// `decay` for each elapsed epoch.
    base_epoch: i64,

    /// INVARIANT: `amplification == alpha * decay.powi(base_epoch - current_epoch)`
    ///
    /// Memoized factor by which values reported to
    /// [Self::amplify_and_increment] are amplified. This factor is updated whenever `base_epoch` or
    /// `current_epoch` is updated. This is an inverse decay factor that allows the counter
    /// implementation to defer rescaling of values after the epoch advances until the next
    /// [Self::rescale] or [Self::advance_and_rescale_if_needed] operation.
    amplification: f64,
}

impl EwmaAmplifier {
    /// Creates a new [`EwmaAmplifier`].
    ///
    /// # Arguments
    ///
    /// * `start_time` - The time at which the struct starts.
    /// * `decay` - The classic EWMA decay per second (equal to `1 - alpha`). Must be in
    ///   `(0, 1)` exclusive.
    ///
    /// # Errors
    ///
    /// Returns [`Status::invalid_argument`] if `decay` is not in `(0, 1)`.
    pub fn new(start_time: DateTime<Utc>, decay: f64) -> Result<Self, Status> {
        if !(decay > 0.0 && decay < 1.0) {
            return Err(Status::invalid_argument(format!(
                "decay must be in (0, 1), got {}",
                decay
            )));
        }

        let alpha: f64 = 1.0 - decay;

        Ok(Self {
            start_time,
            decay,
            alpha,
            total_sum: 0.0,
            current_epoch: 0,
            base_epoch: 0,
            // Initialize amplification to alpha; this will be useful when we want to seed the
            // [`crate::ewma_quantile_summaries::EwmaQuantileSummaries`] with historical load
            // information in the future (see the last section discussing seeding in
            // <internal link>).
            amplification: alpha,
        })
    }

    /// Returns the current running total sum of amplified values. Useful for callers that
    /// compute ratio thresholds against it.
    pub fn total(&self) -> f64 {
        self.total_sum
    }

    /// Amplifies `value` by the current amplification factor and adds the result to
    /// `total_sum`. Returns the amplified value.
    ///
    /// # Errors
    ///
    /// Returns [`Status::failed_precondition`] if the amplification and increment causes the
    /// internal state to overflow. This can be avoided by calling
    /// [Self::advance_and_rescale_if_needed] to reserve room before calling this method. On
    /// error `total_sum` is left unchanged, so the amplifier stays usable.
    pub fn amplify_and_increment(&mut self, value: i32) -> Result<f64, Status> {
        let amplified: f64 = self.amplification * value as f64;
        // Both operands are checked before `total_sum` is assigned so that an overflow leaves
        // the amplifier untouched rather than poisoning it with an infinite total.
        let total_sum: f64 = self.total_sum + amplified;
        if amplified.is_infinite() || total_sum.is_infinite() {
            return Err(Status::failed_precondition(
                "Call advance_and_rescale_if_needed before amplify_and_increment to reserve room \
                 and avoid overflow.",
            ));
        }
        self.total_sum = total_sum;
        Ok(amplified)
    }

    /// Tries to rescale the total accumulated sum and the amplification uniformly to smaller
    /// values to avoid number overflow. Returns a factor that those values are rescaled by, or
    /// None when the rescale didn't happen (when the `current_epoch` is the same as
    /// `base_epoch`). The caller is responsible to multiply their externally-kept values by the
    /// returned factor when rescale happens.
    pub fn rescale(&mut self) -> Option<f64> {
        let elapsed_epochs: i64 = self.current_epoch - self.base_epoch;
        assert!(
            elapsed_epochs >= 0,
            "base_epoch={} must trail current_epoch={}",
            self.base_epoch,
            self.current_epoch
        );
        if elapsed_epochs == 0 {
            return None;
        }
        // Folds the deferred EWMA decay into `total_sum`, advances `base_epoch`
        // up to the latest seen `current_epoch`, and resets `amplification` to `alpha`.
        // Returns `Some(decay.powf(current_epoch - base_epoch_before))`, the factor the caller
        // should multiply its own external aggregates by so that they stay consistent with
        // the `EwmaAmplifier`'s newly advanced base epoch.
        let decay_n_times: f64 = self.decay.powf(elapsed_epochs as f64);
        self.total_sum *= decay_n_times;
        self.base_epoch = self.current_epoch;
        // Theoretically this can be reset to any value because we only care about the ratio
        // between value attributed to a sub-category and the total value, and the initial
        // amplification is cancelled by the ratio. But we still reset it to alpha to
        // emphasize that we are calculating EWMA values.
        self.amplification = self.alpha;
        Some(decay_n_times)
    }

    /// Advances the internally tracked time to `now` and, when advancing plus making room
    /// for the maximum possible next increment value (i.e. `i32::MAX`) would overflow either
    /// `amplification` or `total_sum`, rescales. Returns the rescale factor the caller must
    /// multiply its external aggregates by, or `None` when no rescale was required.
    ///
    /// # Arguments
    ///
    /// * `now` - the current time to advance to.
    pub fn advance_and_rescale_if_needed(&mut self, now: DateTime<Utc>) -> Option<f64> {
        let projected_amplification: f64 = self.project_amplification(now);

        // Whether `amplification` will overflow.
        //
        // We fortunately rarely overflow `amplification`. Fortunate because the amortized
        // complexity analysis for the user of this struct to increment a value depends on
        // rescaling occurring only in response to size thresholds of the externally kept
        // data structure being exceeded, not the rescaling needed by EwmaAmplifier. Rare
        // because for a typical decay factor with (say) a half-life of 2 minutes, the
        // amplification factor will only overflow after
        // `log2(f64::MAX / alpha) * 2 minutes > log2(f64::MAX) * 2 minutes` which is greater
        // than 34 hours (assuming the half life is set to 2 minutes).
        let amplification_overflow: bool = projected_amplification.is_infinite();

        // Whether the total sum will overflow after adding the maximum possible value
        // (i32::MAX) that can be passed to amplify_and_increment(). We rescale if it
        // overflows to ensure the next amplify_and_increment() call will be safe.
        //
        // Since the maximum value that can be reported to `amplify_and_increment()` is
        // `i32::MAX` (~2^31), and one million calls a second is a reasonable upper bound
        // (~2^20), let's assume that `total_sum` is at most `2^51 * amplification`. Assuming
        // that decay is chosen such that the half-life is 2 minutes, then `amplification`
        // will ramp from `alpha` to `f64::MAX` over more than
        // `log2(f64::MAX / 2^51) * 2 minutes`, which is over 32 hours. (Note this analysis
        // is extremely pessimistic.)
        let total_overflow: bool = !amplification_overflow
            && (self.total_sum + projected_amplification * i32::MAX as f64).is_infinite();

        if !amplification_overflow && !total_overflow {
            // No rescale needed, just advance the epoch and amplification so the matching
            // amplify_and_increment runs against the new epoch's factor and then early
            // return.
            self.advance_epoch(now);
            return None;
        }

        if amplification_overflow {
            FOR_TEST_AMPLIFICATION_OVERFLOW_COUNT.fetch_add(1, Ordering::SeqCst);
        } else {
            FOR_TEST_TOTAL_SUM_OVERFLOW_COUNT.fetch_add(1, Ordering::SeqCst);
        }

        let target_epoch: i64 = self.epoch_of(now);
        if target_epoch > self.current_epoch {
            // Advance the current_epoch directly. We don't call advance_epoch() to advance
            // the time because it might overflow `amplification` (and `amplification` will
            // be reset to `alpha` in rescale() anyway, so we don't bother to change it).
            self.current_epoch = target_epoch;
        }
        self.rescale()
    }

    /// Computes what `amplification` would be after advancing the internal epoch to `now`'s
    /// epoch, without mutating any state.
    fn project_amplification(&self, now: DateTime<Utc>) -> f64 {
        let elapsed_epochs: i64 = self.epoch_of(now) - self.current_epoch;
        if elapsed_epochs <= 0 {
            self.amplification
        } else if elapsed_epochs == 1 {
            // Optimize for the common case.
            self.amplification / self.decay
        } else {
            self.amplification * self.decay.powf(-elapsed_epochs as f64)
        }
    }

    /// Advances `current_epoch` to `now`'s epoch when `now` is in a later epoch, updating
    /// `amplification` accordingly. No-op if `now` is no greater than `current_epoch`.
    fn advance_epoch(&mut self, now: DateTime<Utc>) {
        let epoch_of_now: i64 = self.epoch_of(now);
        if epoch_of_now > self.current_epoch {
            self.amplification = self.project_amplification(now);
            self.current_epoch = epoch_of_now;
        }
    }

    /// Maps a physical time to the integer epoch index relative to `start_time`.
    fn epoch_of(&self, now: DateTime<Utc>) -> i64 {
        (now - self.start_time).num_seconds()
    }
}

use std::collections::HashMap;
use std::hash::Hash;

use chrono::{DateTime, Duration, Utc};
use tonic::Status;

use crate::ewma_amplifier;
use crate::ewma_amplifier::EwmaAmplifier;

// Default configuration for the rate load measurements follows. All these parameters are somewhat
// arbitrarily chosen.

/// The interval over which the weight of a measurement decays by half.
const DEFAULT_HALF_LIFE: Duration = Duration::seconds(120);

/// A key's estimated fraction of the Slice load must exceed this value to be considered a top key.
/// See the doc of [`LossyEwmaCounter`] for more details on the meaning of "support".
const DEFAULT_TOP_KEYS_SUPPORT: f64 = 0.05;

/// The maximum allowed error in contribution for a top key. See the doc of [`LossyEwmaCounter`]
/// for details.
const DEFAULT_TOP_KEYS_ERROR: f64 = 0.005;

/// Trait defining the interface for a lossy EWMA counter that tracks the time-weighted
/// contributions of keys (of type K), allowing for the identification of hot keys.
///
/// # Glossary
///
/// **Contribution** is defined as the *fraction* of the total attributed to each key. For example,
/// if a key accounts for 10% of the total weighted-value its contribution is 0.1 regardless of
/// the underlying time-weighted value.
///
/// **Hot key** is one with a contribution ratio of at least `Config.support`.
///
/// **Lossy** abstraction is permitted to avoid tracking an unbounded number of keys. Specifically,
/// the contribution for a key may be underestimated by as much as `Config.error`. To avoid missing
/// potentially hot keys, the `get_hot_keys` method may have false positives, but the true
/// contribution of any returned keys will be at least `Config.support - Config.error`.
///
/// **EWMA** stands for Exponentially Weighted Moving Average, which is a technique used to
/// compute the time-weighted contribution of each key. The counter uses a decay factor to
/// reduce the weight of contributions over time, allowing it to adapt to changes in traffic
/// patterns while still retaining a history of contributions.
pub trait LossyEwmaCounter<K: Eq + Hash>: Sized {
    /// Create a new lossy EWMA counter with the given parameters.
    ///
    /// # Arguments
    ///
    /// * `start_time` - The time at which epoch 0 starts.
    /// * `config` - [Config] for the lossy EWMA counter.
    fn new(start_time: DateTime<Utc>, config: Config) -> Self;

    /// Increments the counter for `key` by `value` as of `now`. It's okay if `now` is less than
    /// some previously reported timestamp because of a non-monotonic clock: the counter uses the
    /// highest timestamp seen so far internally. `value` should be positive: zero or negative
    /// values are ignored.
    fn increment_by(&mut self, now: DateTime<Utc>, key: K, value: i32);

    /// Returns estimated contributions for all keys that may have a true contribution ratio of at
    /// least `support`. Contribution is defined as the *fraction* of the total attributed to each
    /// key.
    ///
    /// * Guarantees:
    ///   - The estimated contribution may be an underestimate by up to `Config.error` (never an
    ///     overestimate).
    ///   - Any key contributing more than `support` will be included in the result.
    ///   - Any key contributing less than `support - error` will not be included.
    ///   - Thus, the minimum possible value returned for any key is `support - error`.
    fn get_hot_keys(&mut self) -> HashMap<&K, f64>;
}

/// Configuration for [LossyEwmaCounter], fields are private to ensure valid by
/// construction via [Config::new].
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Config {
    support: f64,
    error: f64,
    decay: f64,
}

impl Config {
    /// Creates a new lossy EWMA counter configuration, see the documentation for
    /// [LossyEwmaCounter] for details on the meaning of lossy, EWMA and contribution.
    ///
    /// # Arguments
    ///
    /// * `support` - Minimum ratio of the total time-weighted contribution that a key must
    ///               account for to be considered hot. Must be in the range (0, 1), exclusive.
    /// * `error` - Maximum error in the ratio of a key's estimated contribution to its total
    ///             time-weighted contribution. Must be in the range (0, 1), exclusive, and
    ///             must be significantly less than `support` (i.e. `error <= support / 10`).
    ///             E.g. if `support` is 0.1, then `error` must be less than or equal to 0.01.
    /// * `half_life` - The half-life of the counter, which determines how long it takes for the
    ///                 weighted contribution of an input to be reduced by half. Must be positive.
    pub fn new(support: f64, error: f64, half_life: Duration) -> Result<Self, Status> {
        if support <= 0.0 || support >= 1.0 {
            return Err(Status::invalid_argument(format!(
                "support must be in (0, 1), exclusive. Got: {}",
                support
            )));
        }

        if error <= 0.0 || error >= 1.0 {
            return Err(Status::invalid_argument(format!(
                "error must be in (0, 1), exclusive. Got: {}",
                error
            )));
        }

        if error > support / 10.0 {
            return Err(Status::invalid_argument(format!(
                "error must be significantly less than support. Got: error = {}, support = {}",
                error, support,
            )));
        }

        if half_life <= Duration::zero() {
            return Err(Status::invalid_argument(format!(
                "half life must be positive. Got: {}",
                half_life
            )));
        }

        let half_life_seconds: f64 = half_life.as_seconds_f64();

        // Compute decay such that the weight of an input at time t is half that of an input at
        // time t + half_life_seconds. See derivation of α in <internal link>,
        // decay is 1-α.
        let decay: f64 = 2.0f64.powf(-1.0 / half_life_seconds);

        Ok(Self {
            support,
            error,
            decay,
        })
    }

    pub fn support(&self) -> f64 {
        self.support
    }

    pub fn error(&self) -> f64 {
        self.error
    }

    pub fn decay(&self) -> f64 {
        self.decay
    }
}

impl Default for Config {
    fn default() -> Self {
        Self::new(DEFAULT_TOP_KEYS_SUPPORT, DEFAULT_TOP_KEYS_ERROR, DEFAULT_HALF_LIFE)
            .expect("Default config should always be valid")
    }
}

/// Time-weighted sum estimate for a key.
struct EstimatedSum {
    /// The maximum possible error in the estimate of the weighted sum for the current key.
    max_error: f64,

    /// Inclusive lower bound on the true time-weighted sum of the current key. This may be an
    /// underestimate of the true time-weighted sum by up to `max_error`, but never an overestimate.
    min_sum: f64,
}

impl EstimatedSum {
    /// Creates a new `EstimatedSum` with the given maximum error. This is used when a key is
    /// encountered for the first time in `increment_by` (in which case the error is 0), or when
    /// a key was previously tracked but removed (in this case its true time-weighted sum is at
    /// most `max_error`). In either case, we set the maximum error to `max_error`, and accumulate
    /// the EWMA for both sum and error when the epoch advances. The true time-weighted sum is
    /// at least `min_sum` (if it's never removed), and the maximum possible time-weighted sum
    /// is `min_sum + max_error`.
    fn new(max_error: f64) -> Self {
        Self {
            min_sum: 0.0,
            max_error,
        }
    }

    /// Returns the inclusive upper bound on the true time-weighted sum of the current key.
    fn max_sum(&self) -> f64 {
        self.min_sum + self.max_error
    }
}

/// TEST-ONLY: Returns the number of times `scan()` was called due to amplification overflow.
pub fn for_test_amplification_overflow_count() -> usize {
    ewma_amplifier::for_test_amplification_overflow_count()
}

/// TEST-ONLY: Returns the number of times `scan()` was called due to total sum overflow.
pub fn for_test_total_sum_overflow_count() -> usize {
    ewma_amplifier::for_test_total_sum_overflow_count()
}

/// Implementation of the lossy EWMA counter, see <internal link> for background on
/// the implementation (particularly scaling of time-weighted sums and performance considerations).
pub struct LossyEwmaCounterImpl<K: Eq + Hash> {
    /// The time at which epoch 0 starts.
    start_time: DateTime<Utc>,

    /// The configuration for the lossy EWMA counter.
    config: Config,

    /// INVARIANT: includes all keys that have true time-weighted sums greater than
    /// `sum_error_threshold`. This means that an entry may only be removed from the collection
    /// when its maximum possible time-weighted sum is less than or equal to `sum_error_threshold`.
    /// Time-weighted estimated sums of values reported to `increment_by` per key. Samples recorded
    /// before a key was last removed from the collection do not contribute to the estimate, but
    /// must account for less than `sum_error_threshold`, per the invariant described above.
    estimated_sums: HashMap<K, EstimatedSum>,

    /// The EwmaAmplifier used to correctly maintain the amplification factor for the incoming
    /// values to effectively decay the previous values in EWMA manner.
    amplifier: EwmaAmplifier,

    /// When the size of `estimated_sums` reaches this threshold, a `scan()` should be performed.
    /// After a scan, a new value is chosen such that the amortized cost of `increment_by` is O(1)
    /// and the number of elements in `estimated_sums` remains within a constant factor of the
    /// optimal number of elements. See the comment in `scan()` for more details.
    estimated_sums_size_scan_threshold: i32,
}

impl<K> LossyEwmaCounter<K> for LossyEwmaCounterImpl<K>
where
    K: Eq + Hash,
{
    fn new(start_time: DateTime<Utc>, config: Config) -> Self {
        let amplifier: EwmaAmplifier = EwmaAmplifier::new(start_time, config.decay())
            .expect("Config::new creates decay in (0, 1); EwmaAmplifier::new must succeed");

        // Per discussion at <internal link>, we expect 7/error elements in steady
        // state, so we start with a somewhat lower threshold for the first scan. The choice of
        // initial value is somewhat arbitrary and has no effect on the amortized cost.
        let estimated_sums_size_scan_threshold: i32 = ((1.0 / config.error()).ceil() as i32).max(1);

        LossyEwmaCounterImpl {
            config,
            estimated_sums: HashMap::new(),
            start_time,
            amplifier,
            estimated_sums_size_scan_threshold,
        }
    }

    // Increments the counter for `key` by `value` as of `now`. It's ok if `now` is less than some
    // previously reported timestamp because of a non-monotonic clock: the counter uses the
    // highest timestamp seen so far internally. `value` should be positive: zero or negative
    // values are ignored.
    fn increment_by(&mut self, now: DateTime<Utc>, key: K, value: i32) {
        if value <= 0 {
            return;
        }

        let rescale_factor_opt: Option<f64> = self.amplifier.advance_and_rescale_if_needed(now);
        if let Some(rescale_factor) = rescale_factor_opt {
            self.scan(/* hot_keys_map_opt */ None, rescale_factor);
        }
        let Ok(amplified) = self.amplifier.amplify_and_increment(value) else {
            // This is an unreachable path because we already called advance_and_rescale_if_needed
            // for the increment before. But instead of using unwrap() or expect(), we just return
            // the call to reduce risk.
            return;
        };

        // Get or create the estimated sum for the key and update it.
        let sum_error_threshold: f64 = self.sum_error_threshold();
        let estimate = self
            .estimated_sums
            .entry(key)
            .or_insert(EstimatedSum::new(sum_error_threshold));

        // Since per-key sums must be <= `total_sum`, we can safely assume that overflow won't
        // occur when adding to the minimum sum.
        estimate.min_sum += amplified;

        // Perform a scan whenever we reach the `estimated_sums_size_scan_threshold`.
        if self.estimated_sums.len() as i64 >= self.estimated_sums_size_scan_threshold as i64 {
            let rescale_factor: f64 = self.amplifier.rescale().unwrap_or(1.0);
            self.scan(/* hot_keys_map_opt */ None, rescale_factor);
        }
    }

    /// Returns estimated contributions for all keys whose true contribution may be at least
    /// `Config.support`. See the documentation for [LossyEwmaCounter] for definitions of
    /// contribution.
    ///
    /// # Guarantees
    /// - The estimated contribution may underestimate the true value by up to `Config.error`,
    ///   but it is never an overestimate.
    /// - Any key with a true contribution greater than `support` will be included in the result.
    /// - Any key with a true contribution less than `support - error` will not be included.
    /// - Thus, the minimum possible value returned for any key is `support - error`.
    fn get_hot_keys(&mut self) -> HashMap<&K, f64> {
        let mut hot_keys_map: HashMap<&K, f64> = HashMap::new();
        // Since we are scanning all keys anyway, we take this chance to unconditionally
        // rescale all keys.
        let rescale_factor: f64 = self.amplifier.rescale().unwrap_or(1.0);
        self.scan(/* hot_keys_map_opt */ Some(&mut hot_keys_map), rescale_factor);

        hot_keys_map
    }
}

impl<K> LossyEwmaCounterImpl<K>
where
    K: Eq + Hash,
{
    /// The true time-weighted sum threshold below which an entry may be discarded because it's
    /// within the acceptable error bounds for the lossy count algorithm. Used both when deciding
    /// whether an entry can be evicted (during a scan) and to determine the maximum error
    /// associated with an entry when it is added to `estimated_sums` (when an untracked key is
    /// encountered in `increment_by`).
    fn sum_error_threshold(&self) -> f64 {
        self.amplifier.total() * self.config.error()
    }

    /// # Post-conditions
    ///
    /// - Keys whose maximum possible time-weighted sums are less than or equal to
    ///   `sum_error_threshold` are removed from the counter. This is safe due to the invariant:
    ///   if the key is added back later, the maximum error in its estimated time-weighted sum will
    ///   be at least as large as its unscaled sum at removal time.
    ///
    /// This method scans all keys in `estimated_sums` and discards those whose maximum possible
    /// time-weighted sum is less than or equal to the current error threshold, an optional
    /// `hot_keys_map_opt` can be provided to collect the hot keys during the scan. Rescales the
    /// load of all the scanned keys with `rescale_factor`. If the caller thinks rescaling is not
    /// needed, they can pass in `rescale_factor = 1.0`.
    fn scan<'a>(
        &'a mut self,
        hot_keys_map_opt: Option<&mut HashMap<&'a K, f64>>,
        rescale_factor: f64,
    ) {
        let total_sum: f64 = self.amplifier.total();
        let remove_threshold: f64 = self.sum_error_threshold();
        let support: f64 = self.config.support();

        // Iterate over all estimated sums. Rescale them and discard them if their maximum possible
        // time-weighted sum is less than or equal to the current error threshold. This is safe to
        // do because if the key were to be added back to the counter later, the (unscaled) error
        // threshold at that time will be at least as large as the current error threshold.
        self.estimated_sums
            .retain(|_: &K, estimated_sum: &mut EstimatedSum| {
                // Apply decay to the estimated sum and maximum error with the desired rescale
                // factor.
                estimated_sum.min_sum *= rescale_factor;
                estimated_sum.max_error *= rescale_factor;
                // If the key's maximum possible sum is not more than the error threshold, it
                // can be removed.
                estimated_sum.max_sum() > remove_threshold
            });

        // Collect hot keys. In theory, we could drop cold keys from `estimated_sums` and
        // collect hot keys in a single pass. However, that would require a mutable and an
        // immutable borrow of the `estimated_sums` at the same time, which is not allowed by
        // the Rust borrow checker.
        if let Some(hot_keys_map) = hot_keys_map_opt {
            for (key, estimated_sum) in self.estimated_sums.iter() {
                // If the maximum possible contribution for the current key is above the configured
                // support, include it in the hot keys result. Note that while we filter based on
                // the maximum possible contribution, we report the minimum possible contribution,
                // since our contract allows only underestimating, not overestimating.
                if total_sum > 0.0 && (estimated_sum.max_sum() / total_sum) > support {
                    hot_keys_map.insert(key, estimated_sum.min_sum / total_sum);
                }
            }
        }

        // Reset the size threshold at which a new scan should be triggered. We allow the number of
        // tracked keys to double before the next scan, which ensures that the amortized cost of
        // `increment_by` is O(1). It will take at least `n` calls before the size doubles (this is
        // the worst case where every call introduces a new key), which means that for each call to
        // `increment_by`, we need to scan at most 2 entries in the next call to `scan`.
        self.estimated_sums_size_scan_threshold = (self.estimated_sums.len() * 2).max(1) as i32;
    }

    /// Returns the start time of the counter.
    pub fn start_time(&self) -> DateTime<Utc> {
        self.start_time
    }

    /// Returns the configuration of the counter.
    pub fn config(&self) -> Config {
        self.config
    }

    /// TEST-ONLY: Returns the number of keys tracked by the counter.
    pub fn for_test_num_keys(&self) -> usize {
        self.estimated_sums.len()
    }
}

use std::collections::HashMap;
use std::hash::Hash;

use chrono::{DateTime, Utc};
use caching_rust_util::lossy_ewma_counter::{Config, LossyEwmaCounter};

/// A reference implementation of [LossyEwmaCounter] that tracks the total contribution
/// of each key without any error in the time-weighted sums. Intended for illustrative purposes
/// and testing. This implementation is inefficient, as it maintains memory proportional to the
/// total number of increment calls, making it unsuitable for production use.
#[derive(Debug)]
pub struct InefficientLossyEwmaCounter<K: Eq + Hash> {
    /// The time at which epoch 0 starts.
    start_time: DateTime<Utc>,

    /// The configuration for the lossy EWMA counter.
    config: Config,

    /// The total weighted sum of all `increment_by` calls.
    total_weighted_sum: f64,

    /// The weighted sum for each key passed to `increment_by`.
    sums: HashMap<K, f64>,

    /// The highest epoch observed so far.
    current_epoch: i64,

    /// The tolerance for the support threshold, allowing tests to declare an absolute tolerance
    /// for floating point error.
    tolerance: f64,
}

impl<K> LossyEwmaCounter<K> for InefficientLossyEwmaCounter<K>
where
    K: Eq + Hash,
{
    /// Creates a new instance of [InefficientLossyEwmaCounter] with the given configuration.
    fn new(start_time: DateTime<Utc>, config: Config) -> Self {
        InefficientLossyEwmaCounter {
            start_time,
            config,
            total_weighted_sum: 0.0,
            sums: HashMap::new(),
            current_epoch: 0,
            tolerance: 0.0,
        }
    }

    fn increment_by(&mut self, now: DateTime<Utc>, key: K, value: i32) {
        if value <= 0 {
            return;
        }

        // The epoch number is the number of seconds that have elapsed since `start_time`.
        let epoch: i64 = (now - self.start_time).num_seconds();
        if epoch > self.current_epoch {
            // Decay all existing counts if the epoch has advanced.
            let elapsed_epochs: i64 = epoch - self.current_epoch;
            let decay_n_times: f64 = self.config.decay().powf(elapsed_epochs as f64);
            for sum in self.sums.values_mut() {
                *sum *= decay_n_times;
            }
            self.total_weighted_sum *= decay_n_times;
            self.current_epoch = epoch;
        }

        // Update the sum for the given key.
        // We don't multiply by the smoothing factor (α) for either the key's weighted sum or the
        // total weighted sum, since we're only interested in the contribution (i.e., the ratio of
        // the former to the latter), where α cancels out in both the numerator and denominator.
        *self.sums.entry(key).or_insert(0.0) += value as f64;
        self.total_weighted_sum += value as f64;
    }

    /// Returns all keys that a correct [LossyEwmaCounter] implementation could return,
    /// i.e., those with time-weighted contribution ≥ `support - error - tolerance`.
    ///
    /// Note: The function returns the true contributions of all such keys.
    fn get_hot_keys(&mut self) -> HashMap<&K, f64> {
        let mut hot_keys: HashMap<&K, f64> = HashMap::new();
        for (key, sum) in self.sums.iter() {
            let contribution: f64 = if self.total_weighted_sum == 0.0 {
                0.0
            } else {
                *sum / self.total_weighted_sum
            };

            if contribution >= self.config.support() - self.config.error() - self.tolerance {
                hot_keys.insert(key, contribution);
            }
        }
        hot_keys
    }
}

impl<K> InefficientLossyEwmaCounter<K>
where
    K: Eq + Hash,
{
    /// Creates a new instance of [InefficientLossyEwmaCounter] with the given `start_time`,
    /// configuration and tolerance for floating point error.
    pub fn new_with_tolerance(start_time: DateTime<Utc>, config: Config, tolerance: f64) -> Self {
        InefficientLossyEwmaCounter {
            start_time,
            config,
            total_weighted_sum: 0.0,
            sums: HashMap::new(),
            current_epoch: 0,
            tolerance,
        }
    }

    /// Returns the start time of the counter.
    pub fn start_time(&self) -> DateTime<Utc> {
        self.start_time
    }

    /// Returns the configuration of the counter.
    pub fn config(&self) -> Config {
        self.config
    }

    /// Returns the total number of keys tracked by this counter.
    pub fn num_keys(&self) -> usize {
        self.sums.len()
    }
}

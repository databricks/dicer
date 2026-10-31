use std::collections::VecDeque;
use std::time::{Duration, Instant};

use crate::hyper_log_log::HyperLogLog;

/// SlidingHyperLogLog does cardinality estimation of observations for a sliding time window, for
/// example to estimate the number of distinct keys seen recently.
///
/// It provides an approximate cardinality over an approximate time window.
pub struct SlidingHyperLogLog {
    // The approximate retention period.
    retention: Duration,
    // How long each time bucket lasts.
    bucket_width: Duration,
    // The time the SlidingHyperLogLog was created, for bucketing timestamps into buckets.
    start: Instant,

    // Time buckets as (bucket_start, bucket). The front is the oldest bucket and the back the
    // newest.
    buckets: VecDeque<(Instant, HyperLogLog)>,
}

impl SlidingHyperLogLog {
    // The number of buckets we slice the retention window into, which is a tradeoff between
    // accuracy and time/space. Observations are kept for retention +/- (retention / N_BUCKETS)/2.
    // For example, with a 60 second retention window and 4 buckets an observation will live in the
    // SlidingHyperLogLog for between 52.5 and 67.5 seconds. More buckets means that error narrows,
    // but also means we need more HyperLogLogs and thus also have to merge more of them during
    // `recent()`.
    const N_BUCKETS: usize = 4;

    /// Returns a SlidingHyperLogLog with the given retention window and using the given clock for
    /// measuring retention.
    ///
    /// The retention window is approximate, observations added are retained for the retention
    /// window plus or minus a small factor.
    pub fn new(retention: Duration) -> Self {
        SlidingHyperLogLog {
            retention,
            bucket_width: retention / (Self::N_BUCKETS as u32),
            start: Instant::now(),

            buckets: VecDeque::with_capacity(Self::N_BUCKETS),
        }
    }

    /// Adds the key to the estimate. The key will remain part of the estimate for the retention
    /// window.
    pub fn add(&mut self, now: Instant, key: &[u8]) {
        self.current_bucket_mut(now).add(key);
    }

    /// Adds all of the keys from the given HyperLogLog to the estimate. The keys will remain part
    /// of the estimate for the retention window.
    pub fn merge_from(&mut self, now: Instant, hll: &HyperLogLog) {
        self.current_bucket_mut(now).merge_from(hll);
    }

    /// Returns a HyperLogLog that estimates the distinct count of the items observed in the
    /// retention window. Returned as a HyperLogLog rather than the raw estimate so that it can be
    /// combined with other estimates.
    pub fn recent(&self, now: Instant) -> HyperLogLog {
        let mut merged = HyperLogLog::new();
        for (bucket_start, bucket) in self.buckets.iter().rev() {
            if self.expired(now, *bucket_start) {
                break;
            }
            merged.merge_from(bucket);
        }

        merged
    }

    /// Returns the HyperLogLog for the current time bucket, creating one if necessary.
    fn current_bucket_mut(&mut self, now: Instant) -> &mut HyperLogLog {
        self.trim_expired(now);

        let current_bucket_start = self.to_bucket(now);

        let has_current_bucket = self
            .buckets
            .back()
            .is_some_and(|(latest_bucket_start, _)| *latest_bucket_start >= current_bucket_start);
        if !has_current_bucket {
            self.buckets
                .push_back((current_bucket_start, HyperLogLog::new()));
        }

        // This unwrap is unfortunate but the borrow checker is not quite smart enough to allow the
        // more natural pattern:
        //
        //   if let Some(bucket) = self.buckets.back_mut() {
        //     return bucket
        //   }
        //   self.buckets.push_back_mut(...)
        //
        // (See https://blog.rust-lang.org/2026/08/04/enabling-polonius-alpha-on-nightly/#okay-what-s-new)
        //
        // Safe because self.buckets cannot be empty here, the branch above would have been taken
        // and we would have done a push_back.
        let (_, current_bucket) = self.buckets.back_mut().unwrap();
        current_bucket
    }

    /// Rounds t down to the nearest multiple of `self.bucket_width`. Since [`Instant`]s have an
    /// arbitrary origin this truncated value doesn't have any real-world meaning, but it can be
    /// used to bucket [`Instant`]s together.
    fn to_bucket(&self, t: Instant) -> Instant {
        let since_start = t.duration_since(self.start);
        let truncated_nanos =
            (since_start.as_nanos() / self.bucket_width.as_nanos()) * self.bucket_width.as_nanos();
        let truncated_since_start = Duration::from_nanos(truncated_nanos as u64);
        self.start
            .checked_add(truncated_since_start)
            .unwrap_or(self.start)
    }

    /// Remove all buckets that have fallen off of retention.
    fn trim_expired(&mut self, now: Instant) {
        while self
            .buckets
            .front()
            .is_some_and(|(bucket_start, _)| self.expired(now, *bucket_start))
        {
            self.buckets.pop_front();
        }
    }

    /// Returns true if the bucket with the given start time has fallen off of retention and should
    /// not be included in the estimate.
    fn expired(&self, now: Instant, bucket_start: Instant) -> bool {
        // This extra bucketWidth/2 slop here is because observations will arrive on average halfway
        // through a time bucket, so evicting buckets when they're half a bucket older than the
        // retention window means that we retain observations for the retention window on average.
        if let Some(retention_cutoff) = now.checked_sub(self.retention + (self.bucket_width / 2)) {
            return bucket_start <= retention_cutoff;
        }

        // Implies that now is still very close to instant 0, which means that the bucket is not
        // expired.
        false
    }
}

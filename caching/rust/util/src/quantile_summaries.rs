use std::cmp::Reverse;
use std::collections::{BTreeMap, BinaryHeap};
use std::ops::Bound::{Excluded, Included, Unbounded};
use std::rc::Rc;

use ordered_float::OrderedFloat;
use tonic::Status;

use crate::caching_error_code::QUANTILE_SUMMARIES_ACCURACY_INVARIANT_BROKEN;
use crate::prefix_logger::PrefixLogger;

/// A summary structure for maintaining *approximate* quantiles over a stream of weighted
/// (value, weight) observations in a memory-bounded manner.
///
/// Callers use [`Self::insert`] with (value, weight) pairs to build up a weighted distribution,
/// then [`Self::query`] with quantiles in [0, 1] to look up values. For a queried quantile q,
/// [`Self::query`] returns a previously inserted value v whose cumulative weight fraction f (the
/// fraction of total weight from observations with value <= v) approximates q. Because q and f may
/// differ, [`Self::query`] returns f alongside v.
///
/// Formally, given the configured relative error 0 < epsilon < 1 and current total weight W, a
/// query for quantile `q` returns a value `v` such that
///
/// ```text
///   sum_{v_i <= v} w(v_i) >= q * W - epsilon * W, and
///   sum_{v_i <  v} w(v_i) <  q * W + epsilon * W
/// ```
///
/// For example, after inserting the following values with relative error epsilon = 0.01:
///
/// ```text
///   insert(10.0, weight = 50.0)
///   insert(20.0, weight = 1.00)
///   insert(30.0, weight = 49.0)
///
///   // Value  Quantile Range
///   // 10.0   (0.00, 0.50]
///   // 20.0   (0.50, 0.51]
///   // 30.0   (0.51, 1.00]
///
///   // Value 10.0's cumulative weight (0.00, 0.50] covers the queried quantile 0.10 and 0.30
///   // completely.
///   query(&[0.10])  ->  vec![(10.0, 0.50)]
///   query(&[0.30])  ->  vec![(10.0, 0.50)]
///
///   // With epsilon = 0.01, the error tolerance range for the queried quantile 0.50 is
///   // [0.49, 0.51]. So either value 10.0 (with quantile 0.50) or value 20.0 (with quantile 0.51)
///   // can be possibly returned for the query. But value 30.0 is not a valid answer because its
///   // quantile range (0.51, 1.00] doesn't overlap with the target error range.
///   query(&[0.50])  ->  vec![(10.0, 0.50)] or vec![(20.0, 0.51)]
/// ```
///
/// See <internal link> for a detailed description of the problem and the
/// algorithm.
pub struct QuantileSummaries<T: Ord> {
    /// Target relative error `epsilon`, in `(0, 1)`.
    relative_error: f64,

    /// Sorted map from retained sample values to their stats. Maintains two invariants:
    ///
    /// Uncertainty constraint:
    /// For each `stat: Stat` in the structure,
    ///
    /// `stat.gap + stat.delta - stat.weight <= 2 * relative_error * total_weight`,
    ///
    /// as long as [`Self::force_compress`] is not called. This ensures a query result within
    /// `relative_error` can always be found; see [`Self::query`] implementation for details.
    ///
    /// Sentinel guarantee:
    /// The stats for the smallest and largest values are never deleted, and their
    /// `Stat::delta` is always 0.
    samples: BTreeMap<T, Stat>,

    /// Upper bound on the retained-stats count. When the stats are more than `max_size`, some stats
    /// will be forcibly deleted even if that will break the invariants needed to guarantee
    /// `relative_error`. In practice, the max_size should therefore be set conservatively large
    /// with respect to the desired relative error. See the implementation of
    /// [QuantileSummaries::new] for how it is set.
    max_sample_count: usize,

    /// Total weight of every observation ever inserted.
    total_weight: f64,

    /// The logger.
    logger: PrefixLogger,
}

/// The stat that should be tracked for an individual value, needed to implement the quantile
/// query. See <internal link> for a formal definition of the fields in
/// the stats.
#[derive(Debug)]
struct Stat {
    /// The current tracked weight of the value. Note this is not necessarily the total weight of
    /// value observed by the quantile summaries so far, because there might be previous Stats
    /// for the same value being deleted. In that case, the contribution from the deleted previous
    /// weight of the value will be reflected in `delta`.
    weight: f64,

    /// Intuitively, this is the min possible "gap" between the cumulative weight of the current
    /// value and the cumulative weight of the predecessor of the current value (the first value
    /// less than it), and it reflects the weights from the possible deleted `Stat`s between
    /// these 2 values.
    ///
    /// Formally, if we define the minimum possible cumulative weight for the current value x_i as
    /// r_min(x_i), and the minimum possible cumulative weight for the first less value is
    /// r_min(x_{i-1}), then gap_i = r_min(x_i) - r_min(x_{i-1}).
    ///
    /// See the "g" value in <internal link>.
    gap: f64,

    /// The uncertainty of the cumulative weight of the current value, defined as the difference
    /// between the min possible and the max possible cumulative weight of the value. Formally, if
    /// we define the maximum possible cumulative weight for the current value x_i as
    /// r_max(x_i), then delta_i = r_max(x_i) - r_min(x_i).
    ///
    /// See the "delta" value in <internal link>.
    delta: f64,
}

impl<T: Ord> QuantileSummaries<T> {
    /// Creates a quantile summaries targeting `relative_error`. `sample_count_upper_bound`
    /// specifies an upper bound of the sample values that will be kept in the data structure, and
    /// the sample count kept will be proportional with the total memory usage of this data
    /// structure. The upper bound may not be fully utilized.
    ///
    /// The user must note that GK's theoretical space complexity is O((1/e) * log(e * n)),
    /// where e is the relative error and n is the number of unique values inserted. If
    /// `max_sample_upper_bound` is set insufficiently large to satisfy the relative_error and the
    /// number of unique values that will be inserted, the accuracy guarantee as documented in the
    /// main struct doc will be compromised. In practice, it's recommended to set the upper bound
    /// at least (1/e) * log10(n).
    ///
    /// # Errors
    ///
    /// Returns [`Status::invalid_argument`] if `relative_error` is not in `(0, 1)`.
    pub fn new(relative_error: f64, sample_count_upper_bound: usize) -> Result<Self, Status> {
        if !(relative_error > 0.0 && relative_error < 1.0) {
            return Err(Status::invalid_argument(format!(
                "relative_error must be in (0, 1), got {}",
                relative_error
            )));
        }

        // Here we set the memory bound. We first calculate an approximate sufficiently large max
        // count, then clamp it down to the user-specified upper bound if needed.
        //
        // GK's theoretical space complexity is O((1/e) * log(e * n)), where e is the relative error
        // and n is the number of unique values. For a fixed e, this still grows linearly in log(n),
        // so there is no constant theoretical bound. In practice, though, the retained-sample count
        // stays close to 1/e; the log(n) factor only appears under rare, adversarial access
        // patterns. We therefore cap it at 10/e, which leaves generous headroom so the
        // accuracy-breaking force-compress path is rarely hit.
        //
        // Then, we clamp it to the user specified value.
        let max_sample_count: usize =
            ((10.0 / relative_error) as usize).min(sample_count_upper_bound);

        Ok(Self {
            relative_error,
            max_sample_count,
            samples: BTreeMap::new(),
            total_weight: 0.0,
            logger: PrefixLogger::new("QuantileSummaries", ""),
        })
    }

    /// Inserts an observation of `value` with `weight`.
    ///
    /// # Errors
    ///
    /// Returns [`Status::invalid_argument`] if `weight` is negative or infinite.
    pub fn insert(&mut self, value: T, weight: f64) -> Result<(), Status> {
        if !weight.is_finite() || weight < 0.0 {
            return Err(Status::invalid_argument(format!(
                "weight must be strictly non-negative and finite, got {}",
                weight
            )));
        }
        // A zero-weight observation carries no information; treat it as a no-op so it never
        // occupies a sample slot or perturbs the stats.
        if weight == 0.0 {
            return Ok(());
        }
        self.total_weight += weight;

        if let Some(sample) = self.samples.get_mut(&value) {
            // A value already present absorbs the weight. The `delta` remains the same because the
            // increase of the weight doesn't introduce new uncertainty.
            sample.weight += weight;
            sample.gap += weight;
        } else {
            // The gap for the newly inserted value should be the same as weight. This is because
            // the gap between the cumulative weights of the new value and the first less value
            // is minimum when there is no other deleted stats between them, in which case the
            // gap is just the weight of the new value itself.
            let gap: f64 = weight;

            // Calculate delta based on the next greater value in the samples.
            let delta: f64 = match self.samples.range((Excluded(&value), Unbounded)).next() {
                Some((_, next_stats)) => {
                    // The maximum possible cumulative weight of the new value should be the
                    // maximum possible cumulative weight of the next existing value minus its own
                    // weight. See <internal link> for a formal proof.
                    // Note this also ensures the smallest value always has 0 delta.
                    //
                    // Mathematically this result should always be non-negative. But we still max it
                    // with 0.0 to prevent floating point error to result in a negative value.
                    (next_stats.gap + next_stats.delta - next_stats.weight).max(0.0)
                }
                // The implementation ensures the stats for the largest value is never deleted. So
                // the estimate of cumulative weight of the largest value is always accurate and
                // delta should be 0.
                None => 0.0,
            };

            self.samples.insert(value, Stat { weight, gap, delta });
        }

        self.compress_if_needed();

        Ok(())
    }

    /// Queries a batch of quantiles in a single pass. Returns one `(value, cumulative_fraction)`
    /// pair per requested quantile, in the same order as `quantiles`, or an empty vector if there
    /// are no observations yet. `cumulative_fraction` is the fraction of total weight covered by
    /// observations with value `<= value`, and may differ from the queried quantile.
    ///
    /// # Errors
    ///
    /// Returns [`Status::invalid_argument`] if any quantile is not in `[0, 1]`.
    pub fn query(&self, quantiles: &[f64]) -> Result<Vec<(&T, f64)>, Status> {
        for &quantile in quantiles {
            if !(0.0..=1.0).contains(&quantile) {
                return Err(Status::invalid_argument(format!(
                    "quantile must be in [0, 1], got {}",
                    quantile
                )));
            }
        }

        // Early return for the trivial cases to simplify the querying code below.
        if self.samples.is_empty() || quantiles.is_empty() {
            return Ok(Vec::new());
        }

        // Build a sorted map from the minimum cumulative weights to the values; then for each
        // queried quantile, use the sorted map to find the first value whose cumulative weight is
        // greater than or equal to a "target rank" (see below) as the returned result.
        let min_cumulative_weights_to_values: BTreeMap<OrderedFloat<f64>, &T> = {
            let mut builder = BTreeMap::<OrderedFloat<f64>, &T>::new();

            // The minimum possible cumulative weight for the value we are visiting.
            let mut current_min_cumulative_weight: f64 = 0.0;
            for (value, stats) in &self.samples {
                // Skip a value whose gap is too small to change the running cumulative weight (e.g.
                // after a `rescale_weights` with factor 0.0, or repeated `rescale_weights` with
                // tiny factors). Inserting it into
                // min_cumulative_weights_to_values would collide on the same key as its
                // predecessor and overwrite that smaller value, so the query would stop returning
                // the lowest value for that rank. Ignoring these values with near-zero weights
                // should not affect correctness.
                if current_min_cumulative_weight + stats.gap != current_min_cumulative_weight {
                    current_min_cumulative_weight += stats.gap;
                    builder.insert(OrderedFloat::from(current_min_cumulative_weight), value);
                }
            }
            builder
        };

        // We use the computed total weight instead of the stored self.total_weight, because they
        // may be slightly different due to summation order/floating point resolution.
        let Some(&OrderedFloat(total_weight)) = min_cumulative_weights_to_values.keys().next_back()
        else {
            // Defense against an extreme case: although [insert] only allows non-negative
            // weights, a [rescale_weights] call with factor 0.0, or multiple [rescale_weights]
            // calls with small factors, can underflow the total weight to 0.0. When that happens,
            // current_min_cumulative_weight above will remain 0
            // and min_cumulative_weights_to_values will be empty. In that case we return the
            // minimum tracked value as a fallback. Its cumulative load fraction is mathematically
            // undefined; we return 0.0 as a placeholder, matching NonLossyQuantileSummaries.
            let result: (&T, f64) = (
                self.samples
                    .keys()
                    .next()
                    .expect("samples is non-empty here"),
                0.0,
            );
            return Ok(vec![result; quantiles.len()]);
        };
        assert!(
            total_weight > 0.0,
            "min_cumulative_weights_to_values is non-empty, meaning current_min_cumulative_weight \
            doesn't remain 0, and total_weight must be positive"
        );

        // Build the result from the queried quantiles.
        let result: Vec<(&T, f64)> = quantiles
            .iter()
            .map(|&quantile| {
                let target_rank: f64 = quantile * total_weight - self.relative_error * total_weight;
                // Return the first value `v_i` with min cumulative weight >= target_rank. Why is
                // this value a valid result satisfying the error bound constraint? From
                // `cumulative weight >= target_rank` we already know that
                //
                // sum_{j|v_j <= v_i} w(v_j) >= min_cumulative_weight(v_i) >= q * W - epsilon * W.
                //
                // Then,
                // (1) if i == 0, then sum_{j|v_j < v_i} w(v_j) == 0 <= q * W + epsilon * W (see
                //     the "Sentinel Property" of self.samples).
                // (2) if i > 0, then min_cumulative_weight(v_{i-1}) < q * W - epsilon * W
                //     (because v_i is the first value whose min_cumulative_weight is no less than
                //     target_rank), then, due to "Uncertainty Constraint Property", we have
                //     sum_{j|v_j < v_i} w(v_j)
                //       == (sum_{j|v_j <= v_i} w(v_j)) - w(v_i)
                //       <= max_cumulative_weight(v_i) - w(v_i)
                //       == min_cumulative_weight(v_i) + delta_i - w(v_i)
                //       == min_cumulative_weight(v_{i-1}) + g_i + delta_i - w(v_i)
                //          (From Uncertainty Constraint Property)
                //       <= min_cumulative_weight(v_{i-1}) + 2 * epsilon * W
                //       <  q * W - epsilon * W + 2 * epsilon * W
                //       == q * W + epsilon * W.
                // So v_i is the value satisfying the requirement documented by the contract of
                // QuantileSummaries.
                //
                // TODO(<internal bug>): Although returning the first satisfactory answer is valid, it
                //               may not be optimal, as there may be other values that satisfy
                //               the error range. It is unclear how we can qualify which one is the
                //               "most accurate". One option is to return the value whose
                //               min_cumulative_weight + delta/2 deviates the least from the queried
                //               quantile, i.e. its "median" of possible weight ranges is closest to
                //               our query. We leave this as a future optimization.
                let (&OrderedFloat(min_cumulative_weight), &value): (&OrderedFloat<f64>, &&T) =
                    min_cumulative_weights_to_values
                        .range((Included(OrderedFloat::from(target_rank)), Unbounded))
                        .next()
                        .expect(
                            "the map is non empty and target_rank <= total_weight (the last map \
                             key), so there must be a value whose cumulative weight is no less \
                             than target",
                        );
                // Return the lower bound of the weight fraction of v, which could be different
                // from the queried quantile.
                (value, min_cumulative_weight / total_weight)
            })
            .collect();
        Ok(result)
    }

    /// Multiplies every existing observation's weight by `factor`. Since all stored weights scale
    /// uniformly, the ratios between them are preserved, so quantile queries over the
    /// currently-stored data return the same results. Its only observable effect is on subsequent
    /// inserts, where a later insert's weight is now larger/smaller relative to the rescaled
    /// history.
    ///
    /// # Errors
    ///
    /// Returns [`Status::invalid_argument`] if `factor` is not within [0.0, 1.0].
    pub fn rescale_weights(&mut self, factor: f64) -> Result<(), Status> {
        if !(0.0..=1.0).contains(&factor) {
            return Err(Status::invalid_argument(format!(
                "factor must be in [0, 1], got {}",
                factor
            )));
        }
        for sample in self.samples.values_mut() {
            // A rescale with factor 0.0, or multiple rounds of rescaling with small factors, can
            // underflow the weights and other stats down to 0.0. When that happens, we keep the
            // stats in the data structure instead of eagerly remove them. A stat with zero weight
            // and non-zero gap is still informative and helps improve the accuracy of queries. A
            // stat with zero weight and zero gap will be very likely removed in compression once
            // there are other non-zero weights inserted.
            sample.weight *= factor;
            sample.gap *= factor;
            sample.delta *= factor;
        }
        self.total_weight *= factor;
        Ok(())
    }

    /// Compresses the retained samples if needed.
    fn compress_if_needed(&mut self) {
        if self.samples.len() > self.max_sample_count {
            self.graceful_compress();

            // In most cases, graceful compression should bring the count well below the
            // max_sample_count/2 cap. If it could not reclaim enough, we fall back to a forceful
            // compression, which cuts down sample count regardless of the uncertainty constraint,
            // and may thus break the QuantileSummaries' accuracy guarantee.
            //
            // The target is set to self.max_sample_count / 2 because it allows sufficient number of
            // insertions before the next compress, so the overhead of compress is well amortized.
            let target_size: usize = self.max_sample_count / 2;
            if self.samples.len() > target_size {
                self.force_compress(target_size);
            }
        }
    }

    /// Compresses the existing sample stats in a manner that preserves the invariants necessary to
    /// satisfy the relative_error requirement.
    ///
    /// PRECONDITION: `self.samples` must be non-empty.
    fn graceful_compress(&mut self) {
        // The maximum allowed uncertainty for any sample to retain during graceful compression.
        let target_uncertainty: f64 = 2.0 * self.relative_error * self.total_weight;

        // We will put the new stats after compress into this map.
        let mut compressed_stats: BTreeMap<T, Stat> = BTreeMap::new();

        // 1. Protect the two sentinels and initialize the merge target.
        // The maximum simultaneously serves as the seed of the merge pass below, during which we
        // fold predecessors into it.
        let (mut merge_target_value, mut merge_target_stat): (T, Stat) = self
            .samples
            .pop_last()
            .expect("graceful_compress expects non-empty stats");
        if let Some((a, b)) = self.samples.pop_first() {
            // Ensures the minimum value is never deleted.
            compressed_stats.insert(a, b);
        }

        // 2. Then do the compression pass by traversing the rest of the values in descending order,
        // and attempt to fold values into the current merge target.
        for (value, stats) in std::mem::take(&mut self.samples).into_iter().rev() {
            if Self::combined_uncertainty(&stats, &merge_target_stat) <= target_uncertainty {
                // If the current `stats` can be combined into the head without breaking the
                // invariant, combine them.
                // See "Delete" method in <internal link> for why this
                // combination is correct.
                merge_target_stat.gap += stats.gap;
            } else {
                // We cannot safely combine the current stats into the head stats, so we put the
                // current head stats into the result set, and use the current stats as the new head
                // to try to combine its predecessor to it.
                compressed_stats.insert(merge_target_value, merge_target_stat);
                merge_target_value = value;
                merge_target_stat = stats;
            }
        }

        // 3. Store the final (smallest) surviving merge target.
        compressed_stats.insert(merge_target_value, merge_target_stat);

        self.samples = compressed_stats;
    }

    /// Forcefully compresses the stats to make the number of remained stats no more than
    /// `target_size`.
    fn force_compress(&mut self, target_size: usize) {
        // Fire an alert for this bad case when we need force compression.
        crate::prefix_degraded_alert!(
            self.logger,
            QUANTILE_SUMMARIES_ACCURACY_INVARIANT_BROKEN,
            log_every_n_sec = 60,
            "retained samples exceeded max {}, force-dropped to {} and broke the accuracy \
                 invariant; consider a larger max_size",
            self.max_sample_count,
            self.samples.len(),
        );

        // To give up as little accuracy as possible, we greedily merge the adjacent pair with the
        // lowest combined uncertainty first (tracked in a min heap keyed on that uncertainty) until
        // at most self.max_sample_count / 2 stats remain.

        // Preserve the sentinel property for the min value. The max value is preserved naturally
        // because we always merge a value into its successor.
        let (min_value, min_stat) = self.samples.pop_first().unwrap();

        // The remaining status should be keyed by a shared `Rc<T>` because we also need to store
        // the values in the mean heap.
        let mut remaining_stats: BTreeMap<Rc<T>, Stat> = std::mem::take(&mut self.samples)
            .into_iter()
            .map(|(value, stat)| (Rc::new(value), stat))
            .collect();

        /// A candidate merge pair of sample `prev` into its immediate successor `current`.
        /// Primarily ordered by `combined_uncertainty` descending so the heap can pop the pair with
        /// the lowest uncertainty.
        #[derive(PartialEq, Eq, PartialOrd, Ord)]
        struct CandidatePair<T: Ord> {
            combined_uncertainty: Reverse<OrderedFloat<f64>>,
            current_value: Rc<T>,
            prev_value: Rc<T>,
        }

        // Seed the heap with every adjacent (predecessor, successor) pair. Create a Vec of pairs
        // and then heapify it, as a small optimization to gain O(n) complexity.
        let pairs_min_heap: Vec<CandidatePair<T>> = remaining_stats
            .iter()
            .skip(1) // Zip each element with its predecessor.
            .zip(remaining_stats.iter())
            .map(|((current_value, current_stat), (prev_value, prev_stat))| CandidatePair {
                combined_uncertainty: Reverse(OrderedFloat(Self::combined_uncertainty(
                    prev_stat,
                    current_stat,
                ))),
                current_value: current_value.clone(),
                prev_value: prev_value.clone(),
            })
            .collect();
        let mut pairs_min_heap: BinaryHeap<CandidatePair<T>> = BinaryHeap::from(pairs_min_heap);

        // Merging requires at least two remaining samples. The +1 counts the minimum,
        // which was set aside and will be reinserted, toward the target size.
        while remaining_stats.len() >= 2 && remaining_stats.len() + 1 > target_size {
            let CandidatePair {
                combined_uncertainty,
                current_value,
                prev_value,
            } = pairs_min_heap
                .pop()
                .expect("remaining_stats is non-empty, so pairs_min_heap must be non-empty");
            // If the current pair with the lowest uncertainty is still up-to-date, combine the pair
            // in remaining_stats.
            if let Some(current_stat) = remaining_stats.get(&current_value)
                && let Some(prev_stat) = remaining_stats.get(&prev_value)
                && Reverse(OrderedFloat(Self::combined_uncertainty(prev_stat, current_stat)))
                    == combined_uncertainty
            {
                let prev_gap: f64 = remaining_stats.remove(&prev_value).unwrap().gap;
                remaining_stats.get_mut(&current_value).unwrap().gap += prev_gap;
                // After removing prev_value, if there's still a predecessor of current_value that
                // can form a pair with it, put that into the heap.
                if let Some((new_prev_value, new_prev_stat)) =
                    remaining_stats.range(..current_value.clone()).last()
                {
                    let current_stat: &Stat = &remaining_stats[&current_value];
                    let refreshed_uncertainty: f64 =
                        Self::combined_uncertainty(new_prev_stat, current_stat);
                    pairs_min_heap.push(CandidatePair {
                        combined_uncertainty: Reverse(OrderedFloat(refreshed_uncertainty)),
                        current_value: Rc::clone(&current_value),
                        prev_value: Rc::clone(new_prev_value),
                    })
                }
                // The `gap` of the current_stat is changed. If the current stat has a successor,
                // push the updated pair between the current stat and its successor into the heap.
                if let Some((next_value, next_stat)) = remaining_stats
                    .range((Excluded(current_value.clone()), Unbounded))
                    .next()
                {
                    let current_stat: &Stat = &remaining_stats[&current_value];
                    let refreshed_uncertainty: f64 =
                        Self::combined_uncertainty(current_stat, next_stat);
                    pairs_min_heap.push(CandidatePair {
                        combined_uncertainty: Reverse(OrderedFloat(refreshed_uncertainty)),
                        current_value: Rc::clone(next_value),
                        prev_value: Rc::clone(&current_value),
                    })
                }
            }
        }

        // Drop the heap so each retained value's `Rc` is solely owned by `remaining_stats`, letting us
        // unwrap it back to the owned key type.
        drop(pairs_min_heap);
        self.samples = remaining_stats
            .into_iter()
            .map(|(value, stat)| {
                (Rc::into_inner(value).expect("sole owner after the heap is dropped"), stat)
            })
            .collect();

        self.samples.insert(min_value, min_stat);
    }

    /// Returns the combined uncertainty from a pair of adjacent stats.
    fn combined_uncertainty(prev_stat: &Stat, current_stat: &Stat) -> f64 {
        prev_stat.gap + current_stat.gap + current_stat.delta - current_stat.weight
    }
}

/// The test functions of a [`QuantileSummaries`].
pub mod for_test {
    use super::{QuantileSummaries, Stat};

    /// Returns the current total weight of all observations inserted so far.
    pub fn total_weight<T: Ord>(summary: &QuantileSummaries<T>) -> f64 {
        summary.total_weight
    }

    /// Returns the number of retained samples, so tests can assert that compression actually
    /// bounds memory.
    pub fn sample_count<T: Ord>(summary: &QuantileSummaries<T>) -> usize {
        summary.samples.len()
    }

    /// Asserts that the uncertainty invariant of the structure is held.
    ///
    /// # Panics
    ///
    /// Panics if any retained sample violates the invariant.
    pub fn assert_uncertainty_constraint<T: Ord>(summary: &QuantileSummaries<T>) {
        let target_uncertainty: f64 = 2.0 * summary.relative_error * summary.total_weight;
        for stats in summary.samples.values() {
            assert!(stats.delta >= 0.0);
            assert!(stats.gap >= stats.weight);
            let uncertainty: f64 = stats.gap + stats.delta - stats.weight;
            assert!(
                uncertainty <= target_uncertainty,
                "accuracy invariant violated: gap + delta - weight = {} exceeds \
                 relative_error * total_weight = {}",
                uncertainty,
                target_uncertainty
            );
        }
    }

    /// Asserts that the sentinel guarantee of the structure is held.
    ///
    /// # Panics
    ///
    /// Panics if any retained sample violates the invariant.
    pub fn assert_sentinel_guarantee<T: Ord>(summary: &QuantileSummaries<T>) {
        let samples = &summary.samples;
        if !samples.is_empty() {
            let (_, first_stat) = samples.iter().next().unwrap();
            let (_, last_stat) = samples.iter().next_back().unwrap();

            // The lowest and highest entries should have no uncertainty.
            assert_eq!(first_stat.delta, 0.0);
            assert_eq!(last_stat.delta, 0.0);

            // Nothing is ever deleted below the tracked minimum value, so its gap is exactly its
            // own weight.
            assert_eq!(first_stat.gap, first_stat.weight);

            let calculated_total_weight = samples.values().map(|stat: &Stat| stat.gap).sum::<f64>();
            let tracked_total_weight = summary.total_weight;
            // The retained gaps sum to the tracked total, meaning nothing is ever deleted above
            // the current tracked maximum value. The summary.total_weight and the sum of all gaps
            // are calculated in different ways, so they are subject to a small float point error.
            let floating_point_tolerance: f64 = tracked_total_weight * 1e-10 + 1e-10;
            assert!(
                (calculated_total_weight - tracked_total_weight).abs() < floating_point_tolerance,
                "weights: {} {}",
                calculated_total_weight,
                tracked_total_weight
            );
        }
    }
}

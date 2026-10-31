use std::collections::BTreeMap;
use std::ops::Bound::{Included, Unbounded};

use ordered_float::OrderedFloat;
use tonic::Status;

/// A summary structure for maintaining exact quantiles over a stream of weighted (value, weight)
/// observations.
///
/// Callers use [`Self::insert`] with (value, weight) pairs to build up a weighted distribution,
/// then [`Self::query`] with quantiles in [0, 1] to look up values. For a queried quantile q,
/// [`Self::query`] returns a previously inserted value v whose cumulative weight fraction f (the
/// fraction of total weight from observations with value <= v) approximates q. Because q and f may
/// differ, [`Self::query`] returns f alongside v. For example, after inserting four
/// equally-weighted values:
///
/// ```text
///   insert(10.0, weight = 1.0)
///   insert(20.0, weight = 1.0)
///   insert(30.0, weight = 1.0)
///   insert(40.0, weight = 1.0)  // total weight W = 4.0
///
///   // Value  Quantile Range
///   // 10.0   (0.0, 0.25]
///   // 20.0   (0.25, 0.50]
///   // 30.0   (0.50, 0.75]
///   // 40.0   (0.75, 1.00]
///
///   // Value 30.0 covers the quantile range (0.50, 0.75], so any query inside that range
///   // returns 30.0 paired with the upper edge of its coverage (0.75):
///   query(&[0.75])  ->  vec![(30.0, 0.75)]
///   query(&[0.74])  ->  vec![(30.0, 0.75)]
///   query(&[0.51])  ->  vec![(30.0, 0.75)]
/// ```
///
/// This implementation retains every inserted observation and so answers exactly, with no relative
/// error. That makes it the reference oracle that memory-bounded sketches are compared against in
/// tests. It is testonly and not for production use, because its memory grows linearly with the
/// number of distinct values inserted.
///
/// See <internal link> for a detailed description of the problem and the
/// algorithm.
#[derive(Debug, Default)]
pub struct NonLossyQuantileSummaries<T: Ord> {
    /// Sorted map from each distinct inserted value to its total accumulated weight.
    observations: BTreeMap<T, f64>,
}

impl<T: Ord> NonLossyQuantileSummaries<T> {
    pub fn new() -> Self {
        Self {
            observations: BTreeMap::new(),
        }
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
        // occupies a slot in `observations`.
        if weight == 0.0 {
            return Ok(());
        }
        let existing_weight: f64 = *self.observations.get(&value).unwrap_or(&0.0);
        self.observations.insert(value, existing_weight + weight);
        Ok(())
    }

    /// Queries a batch of quantiles in a single pass. Returns one `(value, cumulative_fraction)`
    /// pair per requested quantile, in the same order as `quantiles`, or an empty vector if there
    /// are no observations yet. `cumulative_fraction` is the fraction of total weight covered by
    /// observations with value `<= value`, and may differ from the queried quantile. The batched
    /// call answers multiple quantiles in one pass.
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
        // No observed values exist in the quantile summaries, so there's nothing to return.
        if self.observations.is_empty() {
            return Ok(Vec::new());
        }

        // Build a sorted map from the cumulative weights to the values; then for each queried
        // quantile, use the sorted map to find the first value whose cumulative weight is greater
        // than or equal to the target rank as the returned result.
        let cumulative_weights_to_values: BTreeMap<OrderedFloat<f64>, &T> = {
            let mut builder = BTreeMap::<OrderedFloat<f64>, &T>::new();
            let mut current_cumulative_weight: f64 = 0.0;
            for value_with_weight in &self.observations {
                let (value, &weight) = value_with_weight;
                // `insert` requires strictly positive weights, but rescale_weights with 0.0 factor,
                // or repeated `rescale_weights` with tiny factors, can underflow a weight all the
                // way to 0.0. Skip a value whose weight is too small to change the running
                // cumulative weight: inserting it would collide on the same key as its predecessor
                // and overwrite that smaller value, so the query would stop returning the lowest
                // value for that rank. Ignoring these near-zero weights should not affect
                // correctness.
                if current_cumulative_weight + weight != current_cumulative_weight {
                    current_cumulative_weight += weight;
                    builder.insert(OrderedFloat::from(current_cumulative_weight), value);
                }
            }
            builder
        };

        // We use the computed total weight instead of the stored self.total_weight, because they
        // may be slightly different due to summation order/floating point imprecision.
        let Some(&OrderedFloat(total_weight)) = cumulative_weights_to_values.keys().next_back()
        else {
            // Although [insert] only allows positive weights, a [rescale_weights] call with 0.0
            // factor, or multiple [rescale_weights] calls with small factors can underflow the
            // total weight to 0.0. When that happens, current_cumulative_weight above will remain 0
            // and cumulative_weights_to_values will be empty. In that case we return the minimum
            // tracked value as a fallback. Its cumulative load fraction is mathematically
            // undefined, but we just return 0.0 as a placeholder.
            let result: (&T, f64) = (
                self.observations
                    .keys()
                    .next()
                    .expect("observations is non-empty here"),
                0.0,
            );
            return Ok(vec![result; quantiles.len()]);
        };
        assert!(
            total_weight > 0.0,
            "cumulative_weights_to_values is non-empty, meaning current_cumulative_weight \
            doesn't remain 0, and total_weight must be positive"
        );

        // Build the result from the queried quantiles.
        let result: Vec<(&T, f64)> = quantiles
            .iter()
            .map(|&quantile| {
                let target_rank: f64 = quantile * total_weight;
                // Return the first value with cumulative weight >= target_rank.
                match cumulative_weights_to_values
                    .range((Included(OrderedFloat::from(target_rank)), Unbounded))
                    .next()
                {
                    Some((&cumulative_weight, &value)) => {
                        (value, cumulative_weight.0 / total_weight)
                    }
                    None => panic!(
                        "The map is non empty and targetRank <= totalWeight (the last map key), \
                        so there must be a value whose cumulative weight is no less than target."
                    ),
                }
            })
            .collect();
        Ok(result)
    }

    /// Effectively multiplies EVERY EXISTING observation's weight by `factor`. Because every
    /// existing weight scales uniformly, quantile results for the already-inserted data are
    /// unchanged; the rescaling only affects the relative influence of future inserts.
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
        // Scaling every per-value weight and the running total by the same factor leaves the
        // ratio `cumulative_weight / total_weight` at any value unchanged, so quantile answers
        // do not move.
        for weight in self.observations.values_mut() {
            *weight *= factor;
        }
        Ok(())
    }
}

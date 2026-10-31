use tonic::Status;

use super::ewma_counter::Config;

/// The accumulator for the discrete EWMA, where the weight of each input decays
/// exponentially over time. This variation of EWMA is described in detail at
/// <internal link>.
///
/// **Note**: This struct is public for testing purposes only and should not be
/// used directly outside of this crate. Use
/// [`EwmaCounter`](crate::ewma_counter::EwmaCounter) instead.
#[derive(Debug)]
pub struct EwmaAccumulator {
    /// The weight coefficient for each input.
    alpha: f64,

    /// The latest value for $s_t$ as described in
    /// <internal link>. This is the numerator of the weighted
    /// average.
    weighted_sum: f64,

    /// The latest value for $d_t$ as described in
    /// <internal link>. This is the denominator of the weighted
    /// average.
    total_weight: f64,
}

/// The implementation of the EWMA accumulator.
impl EwmaAccumulator {
    /// Creates a new EWMA accumulator with the given config and an optional
    /// seed.
    pub fn new(config: Config, seed_opt: Option<f64>) -> Self {
        // When a seed is provided, the weighted sum is initialized to the seed,
        // and the total weight is initialized to 1.0. Otherwise, both are
        // initialized to 0.0, indicating that there is no historical value.
        let (weighted_sum, total_weight): (f64, f64) = if let Some(seed) = seed_opt {
            (seed, 1.0)
        } else {
            (0.0, 0.0)
        };

        Self {
            alpha: config.alpha,
            weighted_sum,
            total_weight,
        }
    }

    /// Accumulates an input to the running weighted average.
    ///
    /// # Arguments
    ///
    /// * `value` - The value to accumulate.
    pub fn append_value(&mut self, value: i64) {
        let value_f64: f64 = value as f64;
        self.weighted_sum = self.alpha * value_f64 + self.decay() * self.weighted_sum;

        // When the total weight is 1.0 (when a seed is provided, or when the
        // accumulator has converged because of limited float-point precision),
        // there's no need to update it: d_{t} = α + (1.0 - α) * d_{t-1} = α +
        // (1.0 - α) = 1.0
        if self.total_weight != 1.0 {
            // In theory, the total weight should asymptotically approach 1.0,
            // but in practice accumulating floating point errors may cause an
            // overshoot. To prevent this, we clamp the value to 1.0.
            self.total_weight = (self.alpha + self.decay() * self.total_weight).min(1.0);
        }
    }

    /// Appends `n` zero values to the accumulator, equivalent to calling
    /// `append_value(0)` `n` times. This is more efficient than calling
    /// `append_value(0)` multiple times as it uses a closed-form solution for
    /// the decay calculation.
    ///
    /// # Arguments
    ///
    /// * `n` - The number of zero values to append. Must be positive.
    pub fn append_zeros(&mut self, n: i64) -> Result<(), Status> {
        if n <= 0 {
            return Err(Status::invalid_argument("n must be positive"));
        }

        // Calculate how much the current values will decay over n steps.  Note:
        // we could use `powi` to optimize the case where `n` can fit in an
        // `i32`, but a quick benchmark shows that doesn't make a significant
        // difference in performance.  (14ns vs 16ns for a million calls).
        let decay_n_times: f64 = self.decay().powf(n as f64);

        // Update weighted sum.
        self.weighted_sum *= decay_n_times;

        // See comments in `append_value` about not updating the total weight
        // when it is 1.0.
        if self.total_weight != 1.0 {
            // Update `total_weight` using a closed-form formula matching the
            // result of repeatedly (`n` times) setting `total_weight = alpha +
            // decay * total_weight`.
            // If we represent the value of `total_weight` after `n` iterations
            // as `T_{n}`:
            //  T_{0} = w (w is shorthand for the original value of `total_weight`)
            //  T_{n} = decay * T_{n-1} + α
            // This is similar to the example discussed in some class notes at
            // https://courses.engr.illinois.edu/cs173/fa2011/lectures/recursive-definition.pdf.
            // Its closed form solution is:
            //  T_{n} = decay^n * w + α * $\sum_{i=0}^{n-1} decay^i$
            //        = decay^n * w + α * (1 - decay^n) / (1 - decay)
            //        = decay^n * w + α * (1 - decay^n) / α
            //        = decay^n * w + 1 - decay^n
            //        = (w - 1) * decay^n + 1
            self.total_weight = (decay_n_times * (self.total_weight - 1.0) + 1.0).min(1.0);
        }
        Ok(())
    }

    /// Returns the latest value for the weighted average.
    pub fn value(&self) -> f64 {
        if self.total_weight == 0.0 {
            0.0
        } else {
            self.weighted_sum / self.total_weight
        }
    }

    /// Returns the decay factor (1.0 - alpha) used in the EWMA calculation.
    /// This is the coefficient applied to the previous value in the weighted
    /// average.
    #[inline]
    fn decay(&self) -> f64 {
        1.0 - self.alpha
    }
}

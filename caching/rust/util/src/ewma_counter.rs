use chrono::{DateTime, Duration, Utc};
use tonic::Status;

use crate::ewma_accumulator::EwmaAccumulator;

/// The default interval over which the weight of a measurement decays by half,
/// see the doc of [`Config::new`] for details.
const DEFAULT_HALF_LIFE: Duration = Duration::seconds(120);

/// Exponentially Weighted Moving Average (EWMA) counter.
///
/// A counter whose incremented values decay exponentially over time, allowing
/// for a weighted average of the values over a specified time window (See
/// [Value]), and its value at any given moment is the weighted sum of all
/// recorded inputs. The weight of each input decays exponentially over time.
///
/// To simplify reasoning the counter advances in one-second increments: every
/// second, the aggregate rate over the previous second is added to an
/// underlying [EwmaAccumulator]. This allows us to use a straightforward
/// time-weighted average, but also means that the counter does not reflect
/// measurements from the latest one-second window.
///
/// See <internal link> for more details on this abstraction.
#[derive(Debug)]
pub struct EwmaCounter {
    /// The lower bound of the time window (inclusive) tracked for this counter.
    start_time: DateTime<Utc>,

    /// The underlying accumulator computing the weighted average of the
    /// recorded values.
    ewma_accumulator: EwmaAccumulator,

    /// The specific second during which a rate value is currently accumulating.
    /// For example, if `start_time` is Epoch + 10.2 seconds and
    /// `current_second_low` is 3, `current_second_count` includes the sum of
    /// all values passed to `increment_by` between Epoch + 13.2 seconds
    /// (inclusive) and Epoch + 14.2 seconds (exclusive).
    current_second_low: i64,

    /// The count so far in the 1-second window starting at `start_time` +
    /// `current_second_low`.
    current_second_count: i64,
}

impl EwmaCounter {
    /// Creates a new EWMA counter with the given start time, half-life in
    /// seconds, and an optional seed value.
    ///
    /// # Arguments
    ///
    ///  * `start_time` - The lower bound of the time window (inclusive) for the
    ///                   counter.
    ///  * `config` - See [`Config`] for details.  
    ///  * `seed_opt` - When provided, the counter uses this seed value as the
    ///                 historical value for the counter. Otherwise, the counter
    ///                 assumes that there is no history.
    pub fn new(start_time: DateTime<Utc>, config: Config, seed_opt: Option<f64>) -> Self {
        let ewma_accumulator: EwmaAccumulator = EwmaAccumulator::new(config, seed_opt);

        Self {
            start_time,
            ewma_accumulator,
            current_second_low: 0,
            current_second_count: 0,
        }
    }

    /// Increments the counter by `value` as of `now`. It's ok if `now` is less
    /// than some previously reported timestamp because of a non-monotonic
    /// clock: the accumulator uses the highest seen timestamp seen so far
    /// internally.
    pub fn increment_by(&mut self, now: DateTime<Utc>, value: i32) {
        self.advance_to(now);
        self.current_second_count += value as i64;
    }

    /// Returns the value of the EWMA counter as of `now` rounded down to the
    /// nearest second since `start_time`. Same as calling `increment_by`,
    /// non-monotonic clocks are allowed.
    pub fn value(&mut self, now: DateTime<Utc>) -> Value {
        self.advance_to(now);
        Value {
            window_low_inclusive: self.start_time,
            window_high_exclusive: self.start_time + Duration::seconds(self.current_second_low),
            weighted_value: self.ewma_accumulator.value(),
        }
    }

    /// Internal helper that advances the current second low and count to `now`
    /// and accumulates measurements staged in `current_second_count`.
    fn advance_to(&mut self, now: DateTime<Utc>) {
        // Calculate the number of seconds since the start time.
        let seconds_since_start: i64 = (now - self.start_time).num_seconds();

        if seconds_since_start > self.current_second_low {
            // Append value for the current second to the accumulator.
            self.ewma_accumulator
                .append_value(self.current_second_count);
            self.current_second_count = 0;
            self.current_second_low += 1;

            // Append zero values to catch up to the current second. Even when
            // there are no new inputs, previously accumulated values should
            // decay over time.
            if self.current_second_low < seconds_since_start {
                let n: i64 = seconds_since_start - self.current_second_low;
                self.ewma_accumulator.append_zeros(n).unwrap();
                self.current_second_low = seconds_since_start;
            }
        }
    }
}

/// Configuration for [`EwmaCounter`].
pub struct Config {
    /// The weight coefficient for each input.
    pub alpha: f64,
}

impl Config {
    /// Creates a new configuration for the EWMA counter with the given
    /// half-life.
    ///
    /// # Arguments
    ///
    /// * `half_life` - The half-life of the counter. Must be positive. Also
    ///                 require the derived α to be in the range (0.0, 1.0), see
    ///                 <internal link> for details.
    pub fn new(half_life: Duration) -> Result<Self, Status> {
        if half_life <= Duration::zero() {
            return Err(Status::invalid_argument("half_life must be positive"));
        }
        // Compute decay such that the weight of an input at time t is reduced
        // by half at time $t + half_life$. See derivation of $\alpha$ in
        // <internal link>.
        let alpha: f64 = 1.0 - (2.0f64.powf(-1.0 / half_life.num_seconds() as f64));

        Self::new_from_alpha(alpha)
    }

    /// Creates a new configuration for the EWMA counter given a raw alpha
    /// value. `alpha` must be in the range (0.0, 1.0), exclusive. This method
    /// is public for testing purposes only.
    pub(crate) fn new_from_alpha_for_test(alpha: f64) -> Result<Self, Status> {
        Self::new_from_alpha(alpha)
    }

    /// Creates a new configuration for the EWMA counter given a raw alpha
    /// value. `alpha` must be in the range (0.0, 1.0), exclusive.
    fn new_from_alpha(alpha: f64) -> Result<Self, Status> {
        // `alpha` must be in the range (0.0, 1.0), exclusive.
        if alpha <= 0.0 || alpha >= 1.0 {
            return Err(Status::invalid_argument(format!(
                "alpha must be in (0, 1), exclusive, got {}",
                alpha
            )));
        }
        Ok(Self { alpha })
    }
}

impl Default for Config {
    fn default() -> Self {
        Self::new(DEFAULT_HALF_LIFE).expect("Default config should always be valid")
    }
}

/// Encapsulates the window and the value of the EWMA counter.
#[derive(Debug, PartialEq)]
pub struct Value {
    /// The lower bound of the window, inclusive.
    pub window_low_inclusive: DateTime<Utc>,

    /// The upper bound of the window, exclusive.
    pub window_high_exclusive: DateTime<Utc>,

    /// The weighted value of the EWMA counter for the given window.
    pub weighted_value: f64,
}

impl std::fmt::Display for Value {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "Value {{ window: [{}, {}), weighted_value: {:.6} }}",
            self.window_low_inclusive, self.window_high_exclusive, self.weighted_value
        )
    }
}

use std::time::{Duration, SystemTime};

use tonic::Status;

/// A basic token bucket primitive that tracks available tokens. This class accepts the
/// current [`SystemTime`] in its APIs and is designed to be used in contexts like
/// a state machine where time is managed externally. Otherwise,
/// `RateLimiter` should be used instead. Note that the caller needs to explicitly refill the
/// bucket.
///
/// The bucket allows users to specify its capacity and refill rate. It starts with full capacity so
/// that it can start serving requests immediately.
///
/// Important note: all methods do NOT automatically refill the bucket. The caller must explicitly
/// call [`TokenBucket::refill`] with the current time to ensure tokens are up to date, if needed.
/// This is intentional — because automatically refilling the bucket in
/// [`TokenBucket::try_acquire`] makes it ambiguous how to handle cases where the caller provides
/// a time that moves backwards.
pub struct TokenBucket {
    /// The time when the bucket was last refilled.
    last_refill_time: SystemTime,
    /// The capacity of the bucket in terms of seconds of rate.
    capacity_in_seconds_of_rate: u64,
    /// The rate at which the bucket is refilled in tokens per second.
    rate: u64,
    /// The number of tokens currently available in the bucket. Bucket starts off full.
    available_tokens: f64,
}

impl TokenBucket {
    /// The maximum capacity of the bucket defined in terms of `MAX_RATE`. The maximum capacity
    /// in terms of the number of tokens is `MAX_CAPACITY_IN_SECONDS_OF_RATE` * `MAX_RATE`.
    // Please add tests against this upper bound when MAX_CAPACITY_IN_SECONDS_OF_RATE drops below
    // u64::MAX. These tests are missing now because there is no way to exceed u64::MAX.
    pub const MAX_CAPACITY_IN_SECONDS_OF_RATE: u64 = u64::MAX;

    /// The maximum tokens per second at which the bucket is refilled.
    // Please add tests against this upper bound when MAX_RATE drops below u64::MAX. These tests
    // are missing now because there is no way to exceed u64::MAX.
    pub const MAX_RATE: u64 = u64::MAX;

    /// The number of nanoseconds in one second.
    const NANOS_PER_SECOND: f64 = 1_000_000_000.0;

    /// Creates a `TokenBucket`.
    ///
    /// # Arguments
    ///
    /// * `capacity_in_seconds_of_rate` - the capacity of the bucket in terms of seconds of rate
    /// * `rate` - the rate at which the bucket is refilled in tokens per second
    /// * `init_time` - the time at which the bucket is initially filled with tokens
    ///
    /// # Errors
    ///
    /// Returns [`Status::invalid_argument`] if `capacity_in_seconds_of_rate` == 0.
    /// Returns [`Status::invalid_argument`] if `rate` == 0.
    pub fn new(
        capacity_in_seconds_of_rate: u64,
        rate: u64,
        init_time: SystemTime,
    ) -> Result<TokenBucket, Status> {
        Self::validate_capacity(capacity_in_seconds_of_rate)?;
        Self::validate_rate(rate)?;
        Ok(TokenBucket {
            last_refill_time: init_time,
            capacity_in_seconds_of_rate,
            rate,
            available_tokens: capacity_in_seconds_of_rate as f64 * rate as f64,
        })
    }

    /// Refills the bucket with the accumulated number of tokens between the last time provided to
    /// refill and `now`. If `now` is earlier than the last time this method was called, this method
    /// does nothing.
    ///
    /// # Arguments
    ///
    /// * `now` - the time at which the caller is refilling the bucket
    pub fn refill(&mut self, now: SystemTime) {
        // A backward clock skew surfaces as an error from `duration_since`.
        let elapsed: Duration = match now.duration_since(self.last_refill_time) {
            Ok(elapsed) => elapsed,
            Err(_) => return,
        };

        // Compute the number of newly generated tokens.
        let new_token_count: f64 = elapsed.as_secs_f64() * self.rate as f64;

        // If the bucket would overflow, let the extra tokens spill.
        if new_token_count >= self.maximum_capacity() - self.available_tokens {
            self.available_tokens = self.maximum_capacity();
        } else {
            self.available_tokens += new_token_count;
        }
        self.last_refill_time = now;
    }

    /// Acquires `count` tokens from the bucket if they can be obtained without delay.
    ///
    /// # Arguments
    ///
    /// * `count` - the number of tokens to acquire
    ///
    /// # Returns
    ///
    /// true if there are enough tokens in the bucket and they were acquired;
    /// false if there are not enough tokens available
    pub fn try_acquire(&mut self, count: u64) -> bool {
        let count_f64: f64 = count as f64;
        if self.available_tokens >= count_f64 {
            self.available_tokens -= count_f64;
            true
        } else {
            false
        }
    }

    /// Returns the earliest time `t` such that a call to [`refill(t)`](Self::refill) and then
    /// [`try_acquire(desired)`](Self::try_acquire) with no intervening calls will succeed. Returns
    /// the last time provided to refill if there are already at least `desired` tokens.
    ///
    /// # Arguments
    ///
    /// * `desired` - the desired number of tokens
    ///
    /// # Errors
    ///
    /// Returns [`Status::invalid_argument`] if `desired` > `maximum_capacity`.
    /// Returns [`Status::internal`] if the computed refill time overflows [`SystemTime`].
    pub fn time_when_refilled(&self, desired: u64) -> Result<SystemTime, Status> {
        let desired_f64: f64 = desired as f64;
        if desired_f64 > self.maximum_capacity() {
            return Err(Status::invalid_argument(format!(
                "Desired token number {} exceeds maximum capacity {}: rate {} * \
                 capacity_in_seconds_of_rate {}.",
                desired,
                self.maximum_capacity(),
                self.rate,
                self.capacity_in_seconds_of_rate
            )));
        }

        if self.available_tokens >= desired_f64 {
            // Already have enough tokens.
            Ok(self.last_refill_time)
        } else {
            // Calculate how many additional tokens are needed.
            let tokens_needed: f64 = desired_f64 - self.available_tokens;
            // Calculate how long it will take to generate those tokens.
            let seconds_needed_to_refill: f64 = tokens_needed / self.rate as f64;
            // `as` is a saturating cast, so it is safe. This saturation is acceptable because it
            // would only occur if `seconds_needed_to_refill` was unrealistically long.
            let refill_duration: Duration =
                Duration::from_nanos((seconds_needed_to_refill * Self::NANOS_PER_SECOND) as u64);
            // The Scala implementation saturates to `TickerTime.MAX` on overflow. Since
            // `SystemTime` does not have a saturating-add, we instead return an error on overflow.
            self.last_refill_time
                .checked_add(refill_duration)
                .ok_or_else(|| Status::internal("Computed refill time overflows SystemTime."))
        }
    }

    /// Updates bucket capacity.
    ///
    /// # Errors
    ///
    /// Returns [`Status::invalid_argument`] if `new_capacity_in_seconds_of_rate` == 0.
    pub fn set_capacity_in_seconds_of_rate(
        &mut self,
        new_capacity_in_seconds_of_rate: u64,
    ) -> Result<(), Status> {
        Self::validate_capacity(new_capacity_in_seconds_of_rate)?;
        self.capacity_in_seconds_of_rate = new_capacity_in_seconds_of_rate;
        // Clamp available tokens if they exceed the new maximum capacity.
        if self.available_tokens > self.maximum_capacity() {
            self.available_tokens = self.maximum_capacity();
        }
        Ok(())
    }

    /// Updates bucket rate.
    ///
    /// # Errors
    ///
    /// Returns [`Status::invalid_argument`] if `new_rate` == 0.
    pub fn set_rate(&mut self, new_rate: u64) -> Result<(), Status> {
        Self::validate_rate(new_rate)?;
        self.rate = new_rate;
        // Clamp available tokens if they exceed the new maximum capacity.
        if self.available_tokens > self.maximum_capacity() {
            self.available_tokens = self.maximum_capacity();
        }
        Ok(())
    }

    /// Returns the current `capacity_in_seconds_of_rate`.
    pub fn capacity_in_seconds_of_rate(&self) -> u64 {
        self.capacity_in_seconds_of_rate
    }

    /// Returns the current `rate`.
    pub fn rate(&self) -> u64 {
        self.rate
    }

    /// Returns the current fraction of the bucket that has been consumed, in `[0.0, 1.0]`.
    /// A full bucket (no recent consumption) returns 0.0; an empty bucket (consumption at or above
    /// the configured rate over the bucket's capacity window) returns 1.0.
    pub fn usage_ratio(&self) -> f64 {
        1.0 - (self.available_tokens / self.maximum_capacity())
    }

    /// Returns the maximum bucket capacity in terms of the number of tokens.
    fn maximum_capacity(&self) -> f64 {
        self.capacity_in_seconds_of_rate as f64 * self.rate as f64
    }

    /// Validates that the capacity is positive.
    ///
    /// # Errors
    ///
    /// Returns [`Status::invalid_argument`] if `capacity` == 0.
    fn validate_capacity(capacity: u64) -> Result<(), Status> {
        if capacity == 0 {
            return Err(Status::invalid_argument(format!(
                "Bucket capacity in seconds of rate should be positive, but was {capacity}."
            )));
        }
        Ok(())
    }

    /// Validates that the rate is positive.
    ///
    /// # Errors
    ///
    /// Returns [`Status::invalid_argument`] if `rate` == 0.
    fn validate_rate(rate: u64) -> Result<(), Status> {
        if rate == 0 {
            return Err(Status::invalid_argument(format!(
                "Bucket refill rate should be positive, but was {rate}."
            )));
        }
        Ok(())
    }
}

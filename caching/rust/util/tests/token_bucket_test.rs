use std::time::{Duration, SystemTime};

use tonic::Code;
use caching_rust_util::token_bucket::TokenBucket;
use caching_rust_util_testing::fake_clock::FakeClock;
use caching_rust_util_testing::test_utils::assert_status;

#[test]
fn test_create_token_bucket() {
    // Test plan: Create token buckets with invalid (zero rate or capacity) parameters, and
    // verify errors are returned accordingly. Also verify that a token bucket can be created with
    // valid parameters.

    let fake_clock: FakeClock = FakeClock::create(SystemTime::UNIX_EPOCH);
    let init_time: SystemTime = fake_clock.now();
    assert_status(
        TokenBucket::new(0, 5, init_time),
        Code::InvalidArgument,
        "Bucket capacity in seconds of rate should be positive, but was 0.",
    );
    assert_status(
        TokenBucket::new(5, 0, init_time),
        Code::InvalidArgument,
        "Bucket refill rate should be positive, but was 0.",
    );
    TokenBucket::new(5, 5, init_time).unwrap();
}

#[test]
fn test_token_bucket_starts_off_full() {
    // Test plan: Create a token bucket, verify that it starts off with full capacity, and its
    // attributes are set to the correct values.

    let capacity_in_seconds_of_rate: u64 = 5;
    let rate: u64 = 100;
    let capacity_token_count: u64 = capacity_in_seconds_of_rate * rate;
    let fake_clock: FakeClock = FakeClock::create(SystemTime::UNIX_EPOCH);
    let init_time: SystemTime = fake_clock.now();
    let mut bucket: TokenBucket =
        TokenBucket::new(capacity_in_seconds_of_rate, rate, init_time).unwrap();
    assert_eq!(bucket.capacity_in_seconds_of_rate(), capacity_in_seconds_of_rate);
    assert_eq!(bucket.rate(), rate);

    bucket.refill(fake_clock.now());
    assert!(bucket.try_acquire(capacity_token_count));
    assert!(!bucket.try_acquire(1));
}

#[test]
fn test_attempt_to_acquire_different_amounts_of_tokens() {
    // Test plan: Create a token bucket, and then attempt to acquire different amounts of tokens.
    // Make sure that we can't acquire more than the available number of tokens.

    let capacity_in_seconds_of_rate: u64 = 5;
    let rate: u64 = 100;
    let fake_clock: FakeClock = FakeClock::create(SystemTime::UNIX_EPOCH);
    let init_time: SystemTime = fake_clock.now();
    let capacity_token_count: u64 = capacity_in_seconds_of_rate * rate;
    let mut bucket: TokenBucket =
        TokenBucket::new(capacity_in_seconds_of_rate, rate, init_time).unwrap();

    // Invalid cases:

    bucket.refill(fake_clock.now());
    assert!(!bucket.try_acquire(capacity_token_count + 1));

    // Valid cases (bucket is full):

    assert!(bucket.try_acquire(0));
    // Verify that the bucket is left with capacity_token_count tokens.
    assert!(bucket.try_acquire(capacity_token_count));
    assert!(!bucket.try_acquire(1));
}

#[test]
fn test_bucket_can_store_fractional_tokens() {
    // Test plan: Verify that the bucket accumulates and preserves fractional tokens across refills.
    // Refill after an interval too short to generate a full token, and confirm no token is
    // acquireable yet. Then, refill again after some time so the fractions sum past 1.0 and
    // confirm a whole token can be acquired.

    let capacity_in_seconds_of_rate: u64 = 5;
    let rate: u64 = 100;
    let capacity_token_count: u64 = capacity_in_seconds_of_rate * rate;
    let fake_clock: FakeClock = FakeClock::create(SystemTime::UNIX_EPOCH);
    let init_time: SystemTime = fake_clock.now();
    let mut bucket: TokenBucket =
        TokenBucket::new(capacity_in_seconds_of_rate, rate, init_time).unwrap();

    // Empty the bucket.
    bucket.refill(fake_clock.now());
    assert!(bucket.try_acquire(capacity_token_count));
    assert!(!bucket.try_acquire(1));

    // At rate 100 tokens/second, 8 millis generates 0.8 tokens.
    fake_clock.advance(Duration::from_millis(8));
    bucket.refill(fake_clock.now());
    assert!(!bucket.try_acquire(1));

    // At rate 100 tokens/second, 5 millis generates 0.5 tokens.
    fake_clock.advance(Duration::from_millis(5));
    bucket.refill(fake_clock.now());
    // The bucket should have 1.3 tokens.
    assert!(bucket.try_acquire(1));
    assert!(!bucket.try_acquire(1));
}

#[test]
fn test_consecutive_attempts_to_acquire_tokens() {
    // Test plan: Send consecutive requests to acquire tokens, and verify that the request only
    // succeeds when there are sufficient tokens in the bucket. Verify across small bucket and
    // large bucket.

    let capacity_in_seconds_of_rate: u64 = 5;
    let rate: u64 = 100;
    let capacity_token_count: u64 = capacity_in_seconds_of_rate * rate;
    let fake_clock: FakeClock = FakeClock::create(SystemTime::UNIX_EPOCH);
    let init_time: SystemTime = fake_clock.now();
    let mut bucket: TokenBucket =
        TokenBucket::new(capacity_in_seconds_of_rate, rate, init_time).unwrap();

    bucket.refill(fake_clock.now());
    // Continuous attempts to acquire small amount of tokens should succeed.
    for _ in 1..=100 {
        assert!(bucket.try_acquire(5));
    }
    assert!(!bucket.try_acquire(1)); // Bucket is empty.

    // Continuous attempts to acquire large amount of tokens at the right interval should succeed.
    let token_count: u64 = 100;
    for _ in 1..=100 {
        let current_time: SystemTime = fake_clock.now();
        // Advance clock by the time needed to refill to `token_count` tokens.
        let time_when_refilled: SystemTime = bucket.time_when_refilled(token_count).unwrap();
        if time_when_refilled > current_time {
            fake_clock.advance(time_when_refilled.duration_since(current_time).unwrap());
        }
        bucket.refill(fake_clock.now());
        assert!(bucket.try_acquire(token_count));
    }

    // Refill the bucket.
    fake_clock.advance(Duration::from_hours(1));
    bucket.refill(fake_clock.now());

    // Continuous attempts to acquire large amount of tokens should succeed until the bucket runs
    // out of tokens, and fail afterwards.
    let expected_successful_attempts: u64 = capacity_token_count / token_count;
    for i in 1..=10 {
        assert_eq!(bucket.try_acquire(token_count), i <= expected_successful_attempts);
    }

    // Test with the maximum rate and capacity.
    let mut large_bucket: TokenBucket = TokenBucket::new(
        TokenBucket::MAX_CAPACITY_IN_SECONDS_OF_RATE,
        TokenBucket::MAX_RATE,
        fake_clock.now(),
    )
    .unwrap();

    // Verify that the capacity in tokens is not constrained by the limits of u64.
    large_bucket.refill(fake_clock.now());
    assert!(large_bucket.try_acquire(u64::MAX));
    assert!(large_bucket.try_acquire(u64::MAX));
}

#[test]
fn test_tokens_spill_when_bucket_is_full() {
    // Test plan: Create a token bucket and elapse some time when the bucket is already full.
    // Verify that all tokens generated during this time period are disregarded.

    let fake_clock: FakeClock = FakeClock::create(SystemTime::UNIX_EPOCH);
    let init_time: SystemTime = fake_clock.now();
    let mut bucket: TokenBucket = TokenBucket::new(1, 11, init_time).unwrap();

    // Since the bucket is full, tokens generated during this time period should spill.
    fake_clock.advance(Duration::from_millis(700));
    bucket.refill(fake_clock.now());
    // Clear the bucket.
    assert!(bucket.try_acquire(11));
    assert!(!bucket.try_acquire(1));

    // Make sure the tokens generated during the previous time window are disregarded, instead of
    // being counted towards the next refill. After advancing by 300 millis, only 3 tokens should
    // be generated instead of 11.
    fake_clock.advance(Duration::from_millis(300));
    bucket.refill(fake_clock.now());
    assert!(bucket.try_acquire(3));
    assert!(!bucket.try_acquire(1));
}

#[test]
fn test_set_new_capacity() {
    // Test plan: Create a token bucket, then increase and reduce its capacity in terms of seconds
    // of rate. Verify that the bucket is confined by the old capacity for time elapsed before the
    // change and by the new capacity afterwards, that reducing the capacity immediately clamps the
    // available tokens, and that invalid capacities are rejected.

    let fake_clock: FakeClock = FakeClock::create(SystemTime::UNIX_EPOCH);
    let init_time: SystemTime = fake_clock.now();
    let mut bucket: TokenBucket = TokenBucket::new(5, 100, init_time).unwrap();

    let last_refill_time1: SystemTime = fake_clock.now();
    bucket.refill(last_refill_time1);
    assert!(bucket.try_acquire(100));
    // Verify that the bucket has exactly 400 tokens by checking time_when_refilled.
    assert_eq!(bucket.time_when_refilled(400).unwrap(), last_refill_time1);
    assert!(bucket.time_when_refilled(401).unwrap() > last_refill_time1);

    // Advance by 3 seconds, and then increase the capacity. The bucket should still be confined
    // by the old capacity for this time period. So after increasing the capacity, the bucket should
    // contain 500 tokens instead of 700.
    fake_clock.advance(Duration::from_secs(3));
    let last_refill_time2: SystemTime = fake_clock.now();
    bucket.refill(last_refill_time2);
    bucket.set_capacity_in_seconds_of_rate(10).unwrap();
    assert_eq!(bucket.capacity_in_seconds_of_rate(), 10);
    // Verify that the bucket has exactly 500 tokens by checking time_when_refilled.
    assert_eq!(bucket.time_when_refilled(500).unwrap(), last_refill_time2);
    assert!(bucket.time_when_refilled(501).unwrap() > last_refill_time2);

    // Now the new capacity is set, the bucket should be confined by the new capacity.
    fake_clock.advance(Duration::from_secs(3));
    let last_refill_time3: SystemTime = fake_clock.now();
    bucket.refill(last_refill_time3);
    // Verify that the bucket has 800 tokens by checking time_when_refilled.
    assert_eq!(bucket.time_when_refilled(800).unwrap(), last_refill_time3);

    // Verify that the bucket is left with the correct number of tokens.
    assert!(bucket.try_acquire(800));
    assert!(!bucket.try_acquire(1));

    // The bucket should be bound by the new capacity.
    fake_clock.advance(Duration::from_hours(1));
    let last_refill_time4: SystemTime = fake_clock.now();
    bucket.refill(last_refill_time4);
    // Verify that the bucket has 1000 tokens by checking time_when_refilled and acquiring them.
    assert_eq!(bucket.time_when_refilled(1000).unwrap(), last_refill_time4);
    assert!(bucket.try_acquire(1000));
    assert!(!bucket.try_acquire(1));

    // Refill the bucket.
    fake_clock.advance(Duration::from_hours(1));
    let last_refill_time5: SystemTime = fake_clock.now();
    bucket.refill(last_refill_time5);

    // After reducing the capacity, the bucket should be confined by the new capacity, even though
    // previously there were more tokens in the bucket.
    bucket.set_capacity_in_seconds_of_rate(6).unwrap();
    assert_eq!(bucket.capacity_in_seconds_of_rate(), 6);
    // Verify that the bucket has 600 tokens by checking time_when_refilled.
    assert_eq!(bucket.time_when_refilled(600).unwrap(), last_refill_time5);

    fake_clock.advance(Duration::from_hours(1));
    bucket.refill(fake_clock.now());
    // Verify that the bucket still has 600 tokens.
    assert!(bucket.try_acquire(600));
    assert!(!bucket.try_acquire(1));

    // Test capacity_in_seconds_of_rate boundaries.
    bucket
        .set_capacity_in_seconds_of_rate(TokenBucket::MAX_CAPACITY_IN_SECONDS_OF_RATE)
        .unwrap();
    assert_status(
        bucket.set_capacity_in_seconds_of_rate(0),
        Code::InvalidArgument,
        "Bucket capacity in seconds of rate should be positive, but was 0.",
    );
}

#[test]
fn test_set_new_rate() {
    // Test plan: Create a token bucket, and then adjust the rate. Verify that the maximum capacity
    // in terms of token count is adjusted accordingly, and the bucket is refilled at the new rate.
    // Also verify that invalid rates are rejected.

    let fake_clock: FakeClock = FakeClock::create(SystemTime::UNIX_EPOCH);
    let init_time: SystemTime = fake_clock.now();
    let mut bucket: TokenBucket = TokenBucket::new(5, 100, init_time).unwrap();

    // Clear the bucket.
    bucket.refill(fake_clock.now());
    assert!(bucket.try_acquire(500));
    // Verify that the bucket is empty by checking that we can't acquire any more tokens.
    assert!(!bucket.try_acquire(1));

    // Advance by 3 seconds, and then increase the rate. The bucket should refill at the old
    // rate for this time period. So immediately after increasing the rate, the bucket should
    // contain 300 tokens instead of 600.
    fake_clock.advance(Duration::from_secs(3));
    let last_refill_time1: SystemTime = fake_clock.now();
    bucket.refill(last_refill_time1);
    bucket.set_rate(200).unwrap();
    assert_eq!(bucket.rate(), 200);
    // Verify that the bucket has exactly 300 tokens by checking time_when_refilled.
    assert_eq!(bucket.time_when_refilled(300).unwrap(), last_refill_time1);
    assert!(bucket.time_when_refilled(301).unwrap() > last_refill_time1);

    // Advance by another 4 seconds. Now the refill should be at the new rate, and the total
    // capacity in terms of token count also increases from 500 to 1000.
    fake_clock.advance(Duration::from_secs(4));
    let last_refill_time2: SystemTime = fake_clock.now();
    bucket.refill(last_refill_time2);
    // Verify that the bucket has 1000 tokens by checking time_when_refilled.
    assert_eq!(bucket.time_when_refilled(1000).unwrap(), last_refill_time2);

    // Reduce the rate. The total capacity in terms of token count should decrease to 250.
    bucket.set_rate(50).unwrap();
    assert_eq!(bucket.rate(), 50);
    assert!(!bucket.try_acquire(300));
    // Verify that the bucket has 250 tokens by checking time_when_refilled and acquiring them.
    assert_eq!(bucket.time_when_refilled(250).unwrap(), last_refill_time2);
    assert!(bucket.try_acquire(250));
    assert!(!bucket.try_acquire(1));

    // Test rate boundaries.
    bucket.set_rate(TokenBucket::MAX_RATE).unwrap();
    assert_status(
        bucket.set_rate(0),
        Code::InvalidArgument,
        "Bucket refill rate should be positive, but was 0.",
    );
}

#[test]
fn test_refill_with_non_positive_elapsed_time() {
    // Test plan: Create a token bucket, clear it, and then attempt to refill it using a
    // non-positive elapsed time (same or earlier time). Verify that refill does nothing when the
    // elapsed time is non-positive.

    // The clock starts one year after the Unix epoch so that refilling with an earlier time
    // does not risk underflowing `SystemTime`, as some operating systems do not support the
    // representation of timestamps before the Unix epoch.
    let fake_clock: FakeClock =
        FakeClock::create(SystemTime::UNIX_EPOCH + Duration::from_hours(365 * 24));
    let init_time: SystemTime = fake_clock.now();
    let mut bucket: TokenBucket = TokenBucket::new(5, 100, init_time).unwrap();

    // Clear the bucket.
    bucket.refill(fake_clock.now());
    assert!(bucket.try_acquire(500));
    assert!(!bucket.try_acquire(1));

    // Refill the bucket with `init_time`. It should be a no-op.
    bucket.refill(init_time);
    assert!(!bucket.try_acquire(1));

    // Refill the bucket with an earlier time. It should be a no-op.
    bucket.refill(init_time - Duration::from_hours(1));
    assert!(!bucket.try_acquire(1));

    // Advance the clock and refill the bucket. It should correctly refill the bucket.
    fake_clock.advance(Duration::from_hours(1));
    bucket.refill(fake_clock.now());
    assert!(bucket.try_acquire(500));

    // Refill the bucket with a backward `init_time`. It should be a no-op.
    bucket.refill(init_time);
    assert!(!bucket.try_acquire(1));
}

#[test]
fn test_time_when_refilled() {
    // Test plan: Verify that time_when_refilled correctly computes the refill time in both cases:
    // when the bucket already has enough `desired` tokens and when it does not. Also verify that
    // time_when_refilled throws on invalid inputs.

    let capacity_in_seconds_of_rate: u64 = 10;
    let rate: u64 = 100;
    let capacity_token_count: u64 = capacity_in_seconds_of_rate * rate;
    let fake_clock: FakeClock = FakeClock::create(SystemTime::UNIX_EPOCH);
    let init_time: SystemTime = fake_clock.now();
    let mut bucket: TokenBucket =
        TokenBucket::new(capacity_in_seconds_of_rate, rate, init_time).unwrap();

    // Verify: When the bucket already has enough tokens, time_when_refilled should return the last
    // refill time.
    let last_refill_time1: SystemTime = fake_clock.now();
    bucket.refill(last_refill_time1);
    // The bucket should have 1000 tokens based on its capacity and rate.
    assert_eq!(bucket.time_when_refilled(500).unwrap(), last_refill_time1);
    assert_eq!(bucket.time_when_refilled(1000).unwrap(), last_refill_time1);

    // Verify: When the bucket doesn't have enough tokens, time_when_refilled should compute the
    // correct time based on the refill rate.

    // Clear the bucket.
    assert!(bucket.try_acquire(1000));
    assert!(!bucket.try_acquire(1));

    // The bucket has 0 tokens and we need 0 tokens. Should return the last refill time.
    assert_eq!(bucket.time_when_refilled(0).unwrap(), last_refill_time1);

    // At rate 100 tokens/sec, it should take 10 millis to acquire 1 token.
    assert_eq!(
        bucket.time_when_refilled(1).unwrap(),
        last_refill_time1 + Duration::from_millis(10)
    );

    // At rate 100 tokens/sec, it should take 1 second to acquire 100 tokens.
    assert_eq!(bucket.time_when_refilled(100).unwrap(), last_refill_time1 + Duration::from_secs(1));

    // At rate 100 tokens/sec, it should take 10 seconds to acquire 1000 tokens.
    assert_eq!(
        bucket.time_when_refilled(1000).unwrap(),
        last_refill_time1 + Duration::from_secs(10)
    );

    // Partially refill the bucket.
    fake_clock.advance(Duration::from_secs(3));
    let last_refill_time2: SystemTime = fake_clock.now();
    bucket.refill(last_refill_time2);

    // The bucket has 300 tokens and we need 300 tokens. Should return the last refill time.
    assert_eq!(bucket.time_when_refilled(300).unwrap(), last_refill_time2);

    // The bucket has 300 tokens and we need 200 tokens. Should return the last refill time.
    assert_eq!(bucket.time_when_refilled(200).unwrap(), last_refill_time2);

    // The bucket has 300 tokens and we need 500 tokens. At rate 100 tokens/sec, it should take
    // 2 seconds to acquire 200 tokens.
    assert_eq!(bucket.time_when_refilled(500).unwrap(), last_refill_time2 + Duration::from_secs(2));

    // The bucket has 300 tokens and we need 800 tokens. At rate 100 tokens/sec, it should take
    // 5 seconds to acquire 500 tokens.
    assert_eq!(bucket.time_when_refilled(800).unwrap(), last_refill_time2 + Duration::from_secs(5));

    // Verify: Invalid inputs should throw IllegalArgumentException.
    assert_status(
        bucket.time_when_refilled(capacity_token_count + 1),
        Code::InvalidArgument,
        &format!(
            "Desired token number {} exceeds maximum capacity {capacity_token_count}",
            capacity_token_count + 1
        ),
    );
}

#[test]
fn test_time_when_refilled_supports_nanosecond_precision() {
    // Test plan: Verify that time_when_refilled correctly computes refill times with nanosecond
    // precision for high-rate buckets.

    let fake_clock: FakeClock = FakeClock::create(SystemTime::UNIX_EPOCH);
    let mut high_rate_bucket: TokenBucket =
        TokenBucket::new(10, 1000000000, fake_clock.now()).unwrap();
    let refill_time: SystemTime = fake_clock.now();
    high_rate_bucket.refill(refill_time);

    // Clear the bucket.
    assert!(high_rate_bucket.try_acquire(10000000000));
    assert!(!high_rate_bucket.try_acquire(1));

    // At rate 1,000,000,000 tokens/second, 1 token takes 1 nanosecond.
    assert_eq!(
        high_rate_bucket.time_when_refilled(1).unwrap(),
        refill_time + Duration::from_nanos(1)
    );

    // At rate 1,000,000,000 tokens/second, 10 tokens takes 10 nanoseconds.
    assert_eq!(
        high_rate_bucket.time_when_refilled(10).unwrap(),
        refill_time + Duration::from_nanos(10)
    );

    // At rate 1,000,000,000 tokens/second, 500 tokens takes 500 nanoseconds.
    assert_eq!(
        high_rate_bucket.time_when_refilled(500).unwrap(),
        refill_time + Duration::from_nanos(500)
    );
}

#[test]
fn test_usage_ratio_reflects_the_consumed_fraction_of_the_bucket() {
    // Test plan: Verify that usage_ratio accurately reflects the consumed fraction of the bucket.

    let capacity_in_seconds_of_rate: u64 = 5;
    let rate: u64 = 100;
    let capacity_token_count: u64 = capacity_in_seconds_of_rate * rate;
    let fake_clock: FakeClock = FakeClock::create(SystemTime::UNIX_EPOCH);
    let init_time: SystemTime = fake_clock.now();
    let mut bucket: TokenBucket =
        TokenBucket::new(capacity_in_seconds_of_rate, rate, init_time).unwrap();

    // Verify: A freshly created bucket starts full -> ratio 0.0.
    assert_eq!(bucket.usage_ratio(), 0.0);

    // Verify: After consuming half the capacity, ratio is 0.5.
    assert!(bucket.try_acquire(capacity_token_count / 2));
    assert_eq!(bucket.usage_ratio(), 0.5);

    // Verify: After consuming everything, ratio is 1.0.
    assert!(bucket.try_acquire(capacity_token_count / 2));
    assert_eq!(bucket.usage_ratio(), 1.0);

    // Verify: A failed try_acquire (insufficient tokens) does not change the ratio.
    assert!(!bucket.try_acquire(1));
    assert_eq!(bucket.usage_ratio(), 1.0);

    // Verify: Refilling 20% of the bucket's capacity drops the ratio to 0.8.
    fake_clock.advance(Duration::from_secs(1));
    bucket.refill(fake_clock.now());
    assert_eq!(bucket.usage_ratio(), 0.8);

    // Verify: Refilling fully restores the ratio to 0.0.
    fake_clock.advance(Duration::from_secs(capacity_in_seconds_of_rate - 1));
    bucket.refill(fake_clock.now());
    assert_eq!(bucket.usage_ratio(), 0.0);
}

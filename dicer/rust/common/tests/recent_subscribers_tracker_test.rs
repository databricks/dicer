use std::time::{Duration, SystemTime};

use rand::Rng;
use caching_rust_util::Clock;
use caching_rust_util_testing::fake_clock::FakeClock;
use dicer_rust_common::{RecentSubscribersTracker, SubscriberInfo};
/// Returns active subscribers in `tracker` as a sorted vector of (name, info) tuples.
fn get_sorted_active_subscribers(
    tracker: &mut RecentSubscribersTracker,
) -> Vec<(String, SubscriberInfo)> {
    let active_subscribers = tracker.get_active_subscribers();
    let mut active_subscribers_vec: Vec<(String, SubscriberInfo)> = active_subscribers
        .iter()
        .map(|(name, record)| (name.clone(), record.subscriber_info.clone()))
        .collect();
    active_subscribers_vec.sort_by(|a, b| a.0.cmp(&b.0));
    active_subscribers_vec
}

#[tokio::test]
async fn test_tracker_functionality() {
    // Test plan: Verify that the RecentSubscribersTracker can record and retrieve subscribers
    // correctly, and that subscribers expire after the specified duration. This is done by
    //  1. Creating a tracker with a short expiration duration, verify that no subscribers are
    //     initially present.
    //  2. Call `record_watch_request` on some clerks and slicelets and verifying their info can
    //     be retrieved correctly.
    //  3. Advancing time to a small period less than the expiration duration and verifying
    //     that the subscribers are still present.
    //  4. Call `record_watch_request` again on some of the subscribers to refresh their last
    //     active time, and call `record_watch_request` on some new subscribers.
    //  5. Advancing time to exceed the expiration duration and verifying that only the
    //     refreshed subscribers and the new subscribers are still present.
    let fake_clock = FakeClock::create(SystemTime::UNIX_EPOCH);
    let clock: Clock = fake_clock.clone().into_clock();

    // Step 1: Create a tracker and verify no subscribers initially.
    let mut tracker = RecentSubscribersTracker::new(clock, Duration::from_secs(10));
    let active_subscribers = tracker.get_active_subscribers();
    assert!(active_subscribers.is_empty());

    // Step 2: Call `record_watch_request` on some clerks and slicelets and verify their info.
    let clerk_info_1 = SubscriberInfo::ClerkInfo { version: 123_i64 };
    let clerk_info_2 = SubscriberInfo::ClerkInfo { version: 456_i64 };
    let slicelet_info_1 = SubscriberInfo::SliceletInfo {
        version: 789_i64,
        address: "192.168.1.1:8080".to_string(),
    };
    let slicelet_info_2 = SubscriberInfo::SliceletInfo {
        version: 101112_i64,
        address: "192.168.1.2:9090".to_string(),
    };

    // Verify the record_watch_request calls return None, indicating these are new subscribers.
    assert!(tracker
        .record_watch_request("clerk-1".to_string(), clerk_info_1.clone())
        .is_none());
    assert!(tracker
        .record_watch_request("clerk-2".to_string(), clerk_info_2.clone())
        .is_none());
    assert!(tracker
        .record_watch_request("slicelet-1".to_string(), slicelet_info_1.clone())
        .is_none());
    assert!(tracker
        .record_watch_request("slicelet-2".to_string(), slicelet_info_2.clone())
        .is_none());

    let expected_subscribers_1 = vec![
        ("clerk-1".to_string(), clerk_info_1.clone()),
        ("clerk-2".to_string(), clerk_info_2.clone()),
        ("slicelet-1".to_string(), slicelet_info_1.clone()),
        ("slicelet-2".to_string(), slicelet_info_2.clone()),
    ];

    // Verify all subscribers are present and have correct info.
    let active_subscribers = get_sorted_active_subscribers(&mut tracker);
    assert_eq!(active_subscribers.len(), 4);
    assert_eq!(active_subscribers, expected_subscribers_1);

    // Step 3: Advance time by 5 seconds (less than expiration) and verify subscribers are still
    // present.
    fake_clock.advance(Duration::from_secs(5));
    let active_subscribers = get_sorted_active_subscribers(&mut tracker);
    assert_eq!(active_subscribers.len(), 4);
    assert_eq!(active_subscribers, expected_subscribers_1);

    // Step 4: Call `record_watch_request` again on some subscribers to refresh their last active time,
    // and call `record_watch_request` on some new subscribers.
    let clerk_info_3 = SubscriberInfo::ClerkInfo { version: 789_i64 };
    let slicelet_info_3 = SubscriberInfo::SliceletInfo {
        version: 131415_i64,
        address: "192.168.1.3:7070".to_string(),
    };

    // Refresh old subscribers.
    assert!(tracker
        .record_watch_request("clerk-1".to_string(), clerk_info_1.clone())
        .is_some());
    assert!(tracker
        .record_watch_request("slicelet-1".to_string(), slicelet_info_1.clone())
        .is_some());
    // New subscribers.
    assert!(tracker
        .record_watch_request("clerk-3".to_string(), clerk_info_3.clone())
        .is_none());
    assert!(tracker
        .record_watch_request("slicelet-3".to_string(), slicelet_info_3.clone())
        .is_none());

    // Step 5: Advance time by another 6 seconds (total 11 seconds, exceeding expiration) and
    // verify that only the refreshed and new subscribers are present.
    fake_clock.advance(Duration::from_secs(6));
    let active_subscribers = get_sorted_active_subscribers(&mut tracker);
    assert_eq!(active_subscribers.len(), 4);
    let expected_subscribers_2 = vec![
        ("clerk-1".to_string(), clerk_info_1),
        ("clerk-3".to_string(), clerk_info_3),
        ("slicelet-1".to_string(), slicelet_info_1),
        ("slicelet-3".to_string(), slicelet_info_3),
    ];
    assert_eq!(active_subscribers, expected_subscribers_2);
}

#[tokio::test]
async fn test_random_access() {
    // Test plan: Verify that the subscriber information can be recorded and retrieved correctly
    // when there are many subscribers. This is done by:
    //  1. Creating a list of random subscriber debug names and info.
    //  2. Calling `record_watch_request` on all subscribers one-by-one.
    //  3. Verifying that all subscribers are recorded correctly.
    let fake_clock = FakeClock::create(SystemTime::UNIX_EPOCH);
    let clock: Clock = fake_clock.clone().into_clock();
    let mut tracker = RecentSubscribersTracker::new(clock, Duration::from_mins(10));

    // Generate 1000 random numbers from 0 to 9999 and collect them into a vec.
    let mut rng = rand::rng();
    let random_numbers: Vec<u32> = (0..1000).map(|_| rng.random_range(0..10000)).collect();

    // Create subscriber debug names and info based on the random numbers.
    let subscribers: Vec<(String, SubscriberInfo)> = random_numbers
        .into_iter()
        .map(|num| {
            if num % 2 == 0 {
                let debug_name = format!("clerk-{}", num);
                let info = SubscriberInfo::ClerkInfo {
                    version: num as i64,
                };
                (debug_name, info)
            } else {
                let debug_name = format!("slicelet-{}", num);
                let info = SubscriberInfo::SliceletInfo {
                    version: num as i64,
                    address: format!("10.0.0.{}:{}", num % 255, 8000 + (num % 1000)),
                };
                (debug_name, info)
            }
        })
        .collect();

    // Call `record_watch_request` on all subscribers sequentially.
    for (debug_name, info) in subscribers.clone() {
        tracker.record_watch_request(debug_name, info);
    }

    // Calculate the number of unique subscribers.
    let unique_subscriber_count: usize = {
        let mut unique_names = std::collections::HashSet::new();
        for (debug_name, _) in &subscribers {
            unique_names.insert(debug_name);
        }
        unique_names.len()
    };

    // Verify all subscribers were recorded.
    let active_subscribers = tracker.get_active_subscribers();
    assert_eq!(active_subscribers.len(), unique_subscriber_count);
}

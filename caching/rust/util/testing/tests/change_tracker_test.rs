use prometheus_client::encoding::EncodeLabelSet;
use caching_rust_util_testing::change_tracker::ChangeTracker;
use raf_metrics::{counter, gauge};

#[derive(Clone, Debug, Hash, PartialEq, Eq, EncodeLabelSet)]
struct TestLabels {
    label1: String,
    label2: String,
}

counter!(
    CACHING_CHANGE_TRACKER_TEST_COUNTER,
    "A test counter for testing change tracker functionality",
    TestLabels
);

gauge!(CACHING_CHANGE_TRACKER_TEST_GAUGE, "A test gauge for testing change tracker functionality");

#[test]
fn test_change_tracker() {
    // Test plan: Verify that ChangeTracker correctly tracks changes, this is done by creating
    // a counter and a gauge, then updating them and checking the reported changes.
    let labels = TestLabels {
        label1: "value1".to_string(),
        label2: "value2".to_string(),
    };

    // Setup: create a counter and a ChangeTracker to monitor it.
    let counter = CACHING_CHANGE_TRACKER_TEST_COUNTER.get_or_create_owned(&labels);
    let change_tracker = ChangeTracker::new(|| counter.get());

    // Verify: before any increments, change should be zero.
    assert_eq!(change_tracker.total_change(), 0);

    // Loop: increment the counter multiple times and verify the change.
    let mut expected_counter_change = 0;
    for i in 1..=5 {
        counter.inc_by(i);
        expected_counter_change += i;
        assert_eq!(change_tracker.total_change(), expected_counter_change);
    }

    // Setup: create a gauge with the initial value of 10 and a ChangeTracker to monitor it.
    CACHING_CHANGE_TRACKER_TEST_GAUGE.set(10);
    let gauge_change_tracker = ChangeTracker::new(|| CACHING_CHANGE_TRACKER_TEST_GAUGE.get());

    // Verify: before any changes, change should be zero.
    assert_eq!(gauge_change_tracker.total_change(), 0);

    // Update the gauge multiple times and verify the change.
    CACHING_CHANGE_TRACKER_TEST_GAUGE.set(15);
    assert_eq!(gauge_change_tracker.total_change(), 5);

    CACHING_CHANGE_TRACKER_TEST_GAUGE.set(10);
    assert_eq!(gauge_change_tracker.total_change(), 0);

    CACHING_CHANGE_TRACKER_TEST_GAUGE.set(7);
    assert_eq!(gauge_change_tracker.total_change(), -3);

    // Sanity check: counter change should remain unaffected.
    assert_eq!(change_tracker.total_change(), expected_counter_change);
}

use std::collections::HashSet;
use std::sync::Arc;
use std::time::{Duration, SystemTime};

use caching_rust_util::watch_cell;
use caching_rust_util_examples::{Assignment, AssignmentGenerator};
use caching_rust_util_testing::fake_clock::FakeClock;

/// Awaits an assignment in `assignment_cell` with the given
/// `expected_resources` and returns the assignment.
///
/// Fails if we don't find an assignment with the given `expected_resources`
/// within `max_generation`. For example, if a test is waiting for 3 newly
/// heartbeating resources to appear in the assignment, this could take between
/// 1 and 3 assignment generations, since heartbeats could be processed in any
/// order and with arbitrary batching.
async fn await_assignment(
    generator: &AssignmentGenerator,
    expected_resources: HashSet<String>,
    max_generation: u64,
) -> Assignment {
    let mut assignment = generator.assignment();
    while assignment.resources != expected_resources {
        assert!(
            assignment.generation <= max_generation,
            "Assignment not found within max generation"
        );
        generator.on_assignment_changed(assignment.generation).await;
        assignment = generator.assignment();
    }
    (*assignment).clone()
}

#[tokio::test]
async fn test_assignment_generator() {
    // Test plan: verify that the generator emits assignments when it discovers
    // new resources via heartbeats. Use a FakeClock so that no resources expire
    // due to lack of heartbeats in the course of the test (although in practice,
    // 30 seconds elapsing during the test is extremely unlikely).
    let clock = FakeClock::create(SystemTime::now());
    let generator = AssignmentGenerator::new(clock.into_clock());

    // Initial assignment should be accessible.
    let assignment = generator.assignment();
    assert_eq!(assignment.generation, 0);
    assert!(assignment.resources.is_empty());

    // Test resource heartbeats automatically update assignments.
    generator.on_resource_heartbeat("res1");

    generator.on_assignment_changed(0).await;
    let assignment: watch_cell::Ref<Assignment> = generator.assignment();
    assert_eq!(assignment.generation, 1);
    assert_eq!(assignment.resources, HashSet::from(["res1".to_string()]));

    generator.on_resource_heartbeat("res2");
    generator.on_assignment_changed(1).await;
    let assignment = generator.assignment();
    assert_eq!(assignment.generation, 2);
    assert_eq!(assignment.resources, HashSet::from(["res1".to_string(), "res2".to_string()]));
}

#[tokio::test]
async fn test_concurrent_heartbeats() {
    // Test plan: verify that concurrent heartbeats are processed correctly.
    // Due to non-deterministic scheduling, we may observe an assignment with
    // both resources in either the first or second assignment (generation 1 or
    // 2), but it must be one of those two!
    let clock = FakeClock::create(SystemTime::now());
    let generator = Arc::new(AssignmentGenerator::new(clock.into_clock()));

    // Test concurrent heartbeats using clone
    let generator1 = generator.clone();
    let generator2 = generator.clone();

    let handle1 = tokio::spawn(async move {
        generator1.on_resource_heartbeat("bg1");
    });
    let handle2 = tokio::spawn(async move {
        generator2.on_resource_heartbeat("bg2");
    });

    handle1.await.unwrap();
    handle2.await.unwrap();

    // Wait for an assignment that contains both resources. Due to
    // non-deterministic scheduling, this will be either the first or second
    // assignment we observe, but we can't rely on generation numbers since we
    // may skip generations via the watch cell.
    let expected_resources = HashSet::from(["bg1".to_string(), "bg2".to_string()]);
    let max_generation = 2;
    let _: Assignment = await_assignment(&generator, expected_resources, max_generation).await;
}

#[tokio::test]
async fn test_watch_cell_integration() {
    // Test plan: watch assignment changes using the generator's watch cell.
    // Signal a heartbeat on a spawned task and await an assignment change on
    // the test task.
    let clock = FakeClock::create(SystemTime::now());
    let generator = Arc::new(AssignmentGenerator::new(clock.clone().into_clock()));

    // Verify initial assignment is accessible
    let current: watch_cell::Ref<Assignment> = generator.assignment();
    assert_eq!(current.generation, 0);
    assert!(current.resources.is_empty());

    // Spawn a task to send a heartbeat after a delay. We use the FakeClock's
    // sleep so we can control when the heartbeat is sent.
    let generator_clone = generator.clone();
    let clock_clone = clock.clone().into_clock();
    tokio::spawn(async move {
        clock_clone.sleep(Duration::from_millis(10)).await;
        generator_clone.on_resource_heartbeat("new-res");
    });

    // Wait for the spawned task to register its sleep waiter. The expected count is 2 because
    // the generator's state machine runner is also registered as a waiter (sleeping indefinitely
    // until an event arrives).
    while clock.pending_waiter_count() < 2 {
        tokio::task::yield_now().await;
    }

    // Advance time to trigger the heartbeat
    clock.advance(Duration::from_millis(10));

    // Wait for the change using WatchCell. It should be generation 1.
    let max_generation = 1;
    await_assignment(&generator, HashSet::from(["new-res".to_string()]), max_generation).await;
}

#[tokio::test]
async fn test_resource_expiry_after_timeout() {
    // Test plan: verify that resources are automatically removed from
    // assignments when they haven't sent heartbeats in 30 seconds. We manually
    // advance time to trigger the expiry mechanism. res1 will send a heartbeat
    // before expiry (and remain), while res2 will not (and be removed).
    let clock = FakeClock::create(SystemTime::now());
    let generator = AssignmentGenerator::new(clock.clone().into_clock());

    // Send heartbeats for two resources
    generator.on_resource_heartbeat("res1");
    generator.on_resource_heartbeat("res2");

    // Wait for the assignment to update with both resources. Depending on
    // whether the generator handles the events in one or two advance calls, the
    // assignment containing both may have either generation 1 or 2.
    let max_generation = 2;
    let assignment: Assignment = await_assignment(
        &generator,
        HashSet::from(["res1".to_string(), "res2".to_string()]),
        max_generation,
    )
    .await;

    // Advance time by 20 seconds and send another heartbeat for res1 only.
    clock.advance(Duration::from_secs(20));
    generator.on_resource_heartbeat("res1");

    // Advance time by another 15 seconds (total 35 seconds from initial
    // heartbeats). This should expire res2 (35 seconds since its last
    // heartbeat) but not res1 (only 15 seconds since its last heartbeat).
    clock.advance(Duration::from_secs(15));

    // Wait for the assignment to update after res2 expires. This should be
    // the very next assignment.
    let max_generation = assignment.generation + 1;
    await_assignment(&generator, HashSet::from(["res1".to_string()]), max_generation).await;
}

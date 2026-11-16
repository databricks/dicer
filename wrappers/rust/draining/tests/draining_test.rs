use std::future::pending;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use raf_draining::{DrainableFuture, DrainingRegistry, GlobalDrainingBucket};
use tokio::sync::Notify;
use tokio::task::JoinHandle;
use tokio::time::Instant;

/// Timeout for drains. Note the tests use simulated time, so the value doesn't matter too much.
const DRAIN_TIMEOUT: Duration = Duration::from_secs(10);

/// Registers a component named `name` that, when drained, waits for `delay` and then appends
/// `name` to `log`. Returns whether the registration was accepted.
fn register_recording_component(
    registry: &DrainingRegistry,
    name: &'static str,
    delay: Duration,
    log: &Arc<Mutex<Vec<&'static str>>>,
) -> bool {
    let log: Arc<Mutex<Vec<&'static str>>> = Arc::clone(log);
    registry.add_component_to_bucket(
        DrainableFuture::new(name, move || async move {
            tokio::time::sleep(delay).await;
            log.lock().unwrap().push(name);
        }),
        GlobalDrainingBucket::Preparation,
    )
}

#[tokio::test(start_paused = true)]
async fn test_drain_in_registration_order() {
    // Test plan: verify sequential draining by registering a slow component before a fast one
    // and checking that they finish in registration order.
    let registry: DrainingRegistry = DrainingRegistry::new_for_test();
    let log: Arc<Mutex<Vec<&'static str>>> = Arc::new(Mutex::new(Vec::new()));
    assert!(register_recording_component(&registry, "slow", Duration::from_millis(100), &log));
    assert!(register_recording_component(&registry, "fast", Duration::ZERO, &log));

    assert!(registry.drain_and_await_completion(DRAIN_TIMEOUT).await);
    assert_eq!(*log.lock().unwrap(), vec!["slow", "fast"]);
}

#[tokio::test(start_paused = true)]
async fn test_registration_during_drain_rejected() {
    // Test plan: verify that a draining component can attempt registration without deadlocking
    // and that the registration is rejected without running the new component.
    let registry: Arc<DrainingRegistry> = Arc::new(DrainingRegistry::new_for_test());
    let registry_clone: Arc<DrainingRegistry> = Arc::clone(&registry);
    let log: Arc<Mutex<Vec<&'static str>>> = Arc::new(Mutex::new(Vec::new()));
    let registered_during_drain: Arc<Mutex<Option<bool>>> = Arc::new(Mutex::new(None));
    let registered_during_drain_clone: Arc<Mutex<Option<bool>>> =
        Arc::clone(&registered_during_drain);
    let log_clone: Arc<Mutex<Vec<&'static str>>> = Arc::clone(&log);

    assert!(registry.add_component_to_bucket(
        DrainableFuture::new("registers-during-drain", move || async move {
            let registered: bool = register_recording_component(
                &registry_clone,
                "registered-during-drain",
                Duration::ZERO,
                &log_clone,
            );
            *registered_during_drain_clone.lock().unwrap() = Some(registered);
        }),
        GlobalDrainingBucket::Preparation,
    ));

    assert!(registry.drain_and_await_completion(DRAIN_TIMEOUT).await);
    // The call to `add_component_to_bucket` should have returned `false`.
    assert_eq!(*registered_during_drain.lock().unwrap(), Some(false));
    assert!(log.lock().unwrap().is_empty());
}

#[tokio::test(start_paused = true)]
async fn test_registration_after_drain_rejected() {
    // Test plan: verify that a completed drain rejects new registrations, even when the registry
    // was empty, and that a subsequent drain does not run the rejected component.
    let registry: DrainingRegistry = DrainingRegistry::new_for_test();
    let log: Arc<Mutex<Vec<&'static str>>> = Arc::new(Mutex::new(Vec::new()));
    assert!(registry.drain_and_await_completion(DRAIN_TIMEOUT).await);

    assert!(!register_recording_component(
        &registry,
        "registered-after-drain",
        Duration::ZERO,
        &log
    ));
    assert!(registry.drain_and_await_completion(DRAIN_TIMEOUT).await);
    assert!(log.lock().unwrap().is_empty());
}

#[tokio::test(start_paused = true)]
async fn test_repeated_drain_is_noop() {
    // Test plan: verify that a second drain succeeds without repeating completed drain actions
    // by checking the recorded actions after each drain.
    let registry: DrainingRegistry = DrainingRegistry::new_for_test();
    let log: Arc<Mutex<Vec<&'static str>>> = Arc::new(Mutex::new(Vec::new()));
    assert!(register_recording_component(&registry, "component", Duration::ZERO, &log));
    assert!(registry.drain_and_await_completion(DRAIN_TIMEOUT).await);
    assert_eq!(*log.lock().unwrap(), vec!["component"]);

    // Second drain should be a no-op.
    assert!(registry.drain_and_await_completion(DRAIN_TIMEOUT).await);
    assert_eq!(*log.lock().unwrap(), vec!["component"]);
}

#[tokio::test(start_paused = true)]
async fn test_drain_timeout() {
    // Test plan: verify that one timeout covers the entire sequence by draining a delayed
    // component followed by one that never completes, then checking the elapsed virtual time.
    let registry: DrainingRegistry = DrainingRegistry::new_for_test();
    assert!(registry.add_component_to_bucket(
        DrainableFuture::new("delayed", || tokio::time::sleep(DRAIN_TIMEOUT / 2)),
        GlobalDrainingBucket::Preparation,
    ));
    assert!(registry.add_component_to_bucket(
        DrainableFuture::new("never-completes", pending::<()>),
        GlobalDrainingBucket::Preparation,
    ));

    let start: Instant = Instant::now();
    assert!(!registry.drain_and_await_completion(DRAIN_TIMEOUT).await);
    assert_eq!(start.elapsed(), DRAIN_TIMEOUT);
}

#[tokio::test(start_paused = true)]
async fn test_repeated_drain_while_first_times_out() {
    // Test plan: verify that a timeout prevents later components from running, and repeated calls
    // to drain immediately return `true`. Register a component that signals when its drain starts
    // and then never completes, followed by a component whose factory records whether it is called.
    // Start draining in a background task and wait for the start signal before requesting a second
    // drain. Verify that the second call returns `true` (without advancing virtual time). Then
    // await the first drain's timeout, verify that it returns `false`, and check that the later
    // component's factory was never called.
    let registry: Arc<DrainingRegistry> = Arc::new(DrainingRegistry::new_for_test());
    let drain_started: Arc<Notify> = Arc::new(Notify::new());
    let drain_started_clone: Arc<Notify> = Arc::clone(&drain_started);
    assert!(registry.add_component_to_bucket(
        DrainableFuture::new("never-completes", move || async move {
            drain_started_clone.notify_one();
            pending::<()>().await;
        }),
        GlobalDrainingBucket::Preparation,
    ));

    let later_component_started: Arc<Mutex<bool>> = Arc::new(Mutex::new(false));
    let later_component_started_clone: Arc<Mutex<bool>> = Arc::clone(&later_component_started);
    assert!(registry.add_component_to_bucket(
        DrainableFuture::new("after-never-completes", move || {
            *later_component_started_clone.lock().unwrap() = true;
            async {}
        }),
        GlobalDrainingBucket::Preparation,
    ));

    let registry_clone: Arc<DrainingRegistry> = Arc::clone(&registry);
    let first_drain: JoinHandle<bool> = tokio::spawn(async move {
        registry_clone
            .drain_and_await_completion(DRAIN_TIMEOUT)
            .await
    });
    drain_started.notified().await;

    let start: Instant = Instant::now();
    assert!(
        registry.drain_and_await_completion(DRAIN_TIMEOUT).await,
        "a second drain should return true while the first drain is in progress"
    );
    assert_eq!(start.elapsed(), Duration::ZERO);
    assert!(!first_drain.await.unwrap());
    assert!(!*later_component_started.lock().unwrap());
}

#[tokio::test(start_paused = true)]
#[should_panic(expected = "component panicked during drain")]
async fn test_panic_during_drain() {
    // Test plan: verify that a panic in a drain action propagates to the caller by registering
    // a panicking future and awaiting draining.
    let registry: DrainingRegistry = DrainingRegistry::new_for_test();
    assert!(registry.add_component_to_bucket(
        DrainableFuture::new("panicking-component", || async {
            panic!("component panicked during drain");
        }),
        GlobalDrainingBucket::Preparation,
    ));

    registry.drain_and_await_completion(DRAIN_TIMEOUT).await;
}

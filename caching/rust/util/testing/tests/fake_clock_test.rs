use std::future::Future;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime};

use caching_rust_util::{Clock, Elapsed};
use caching_rust_util_testing::fake_clock::FakeClock;
use raf_testing_async::eventually;

#[tokio::test(flavor = "multi_thread")]
async fn test_fake_clock() {
    // Test plan: create a fake clock that starts at one second after the
    // Unix epoch in the context of a "paused" Tokio runtime. Advance time
    // using `tokio::time::advance` and verify that the clock is advanced as
    // expected.
    let start_time: SystemTime = SystemTime::UNIX_EPOCH + Duration::from_secs(1);
    let clock = FakeClock::create(start_time);
    assert_eq!(clock.now(), start_time);

    clock.advance(Duration::from_secs(1));
    assert_eq!(clock.now(), start_time + Duration::from_secs(1));

    clock.advance(Duration::from_secs(2));
    assert_eq!(clock.now(), start_time + Duration::from_secs(3));
}

#[tokio::test(flavor = "multi_thread")]
async fn test_fake_clock_clone() {
    // Test plan: verify that cloned clocks expose the same time in the
    // context of a "paused" Tokio runtime.
    let start_time: SystemTime = SystemTime::UNIX_EPOCH + Duration::from_secs(1);
    let clock = FakeClock::create(start_time);
    let clone = clock.clone();
    assert_eq!(clock.now(), clone.now());

    // Advance time and verify that both clocks are advanced.
    clock.advance(Duration::from_secs(1));
    assert_eq!(clock.now(), start_time + Duration::from_secs(1));
    assert_eq!(clone.now(), start_time + Duration::from_secs(1));
}

#[tokio::test(flavor = "multi_thread")]
async fn test_sleep_basic_mechanics() {
    // Test plan: Verify basic sleep mechanics, i.e. it should complete when the deadline is
    // reached or passed. Test cases including zero-duration sleep, exact match, partial advance,
    // and over-advance scenarios.
    let clock = FakeClock::create(SystemTime::now());

    // 1. Verify that zero sleep completes immediately without advance.
    let sleep_fut = clock.sleep(Duration::ZERO);
    sleep_fut.await; // Should complete immediately

    // 2. Verify that sleep(10s) completes after 10s advance.
    let clock_clone = clock.clone();
    let handle = tokio::spawn(async move {
        clock_clone.sleep(Duration::from_secs(10)).await;
        true
    });
    while clock.pending_waiter_count() < 1 {
        tokio::task::yield_now().await; // Wait until the task registers.
    }

    // Advance exactly 10s and verify that the sleep completes.
    clock.advance(Duration::from_secs(10));
    let result = handle.await.unwrap();
    assert!(result, "Sleep should complete after exact advance");

    // 3. Verify that sleep(10s) is still pending after 5s advance.
    let clock_clone = clock.clone();
    let completed = Arc::new(AtomicBool::new(false));
    let completed_clone = completed.clone();

    let handle = tokio::spawn(async move {
        clock_clone.sleep(Duration::from_secs(10)).await;
        completed_clone.store(true, Ordering::SeqCst);
    });

    while clock.pending_waiter_count() < 1 {
        tokio::task::yield_now().await; // Wait until the task registers.
    }

    // Advance only 5s (not enough) and verify that the sleep is still pending.
    clock.advance(Duration::from_secs(5));
    while clock.pending_waiter_count() < 1 {
        tokio::task::yield_now().await; // Wait until the task polls.
    }
    assert!(!completed.load(Ordering::SeqCst), "Sleep should NOT complete with partial advance");

    // Now advance the remaining 5s and verify that the sleep completes.
    clock.advance(Duration::from_secs(5));
    handle.await.unwrap();
    assert!(completed.load(Ordering::SeqCst), "Sleep should complete after full advance");

    // 4. Verify that over-advance completes the sleep and instant() reflects the full advance.
    let initial_time: Instant = clock.instant();
    let clock_clone = clock.clone();

    let handle = tokio::spawn(async move {
        clock_clone.sleep(Duration::from_secs(10)).await;
    });

    while clock.pending_waiter_count() < 1 {
        tokio::task::yield_now().await; // Wait until the task registers.
    }

    // Advance 100s (overshooting the 10s sleep) and verify that the task completes.
    clock.advance(Duration::from_secs(100));
    handle.await.unwrap();

    // Verify instant() reflects the full 100s advance
    let elapsed: Duration = clock.instant().duration_since(initial_time);
    assert_eq!(
        elapsed,
        Duration::from_secs(100),
        "instant() should reflect full advance, not just sleep duration"
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn test_sleep_reason_count() {
    // Test plan: Verify that the sleep reason count is incremented when a sleep is registered
    // with a reason and that the sleeping count is decremented when the sleep completes.
    let clock = FakeClock::create(SystemTime::now());

    // Verify that the sleep reason count is 0 initially.
    assert_eq!(clock.sleep_reason_count("reason1"), 0);
    assert_eq!(clock.sleep_reason_count("reason2"), 0);
    assert!(!clock.is_sleeping_for_reason("reason1"));
    assert!(!clock.is_sleeping_for_reason("reason2"));

    // Register two sleeps with reason1, and one with reason2, and verify that eventually the
    // count for reason1 is 2 and the count for reason2 is 1.
    let clock_clone = clock.clone();
    let handle1 = tokio::spawn(async move {
        clock_clone
            .sleep_with_reason(Duration::from_secs(10), "reason1".to_string())
            .await;
    });

    let clock_clone = clock.clone();
    let handle2 = tokio::spawn(async move {
        clock_clone
            .sleep_with_reason(Duration::from_secs(10), "reason1".to_string())
            .await;
    });

    let clock_clone = clock.clone();
    let handle3 = tokio::spawn(async move {
        clock_clone
            .sleep_with_reason(Duration::from_secs(10), "reason2".to_string())
            .await;
    });

    let clock_for_eventually = clock.clone();
    eventually(
        || {
            let clock = clock_for_eventually.clone();
            async move {
                assert_eq!(clock.sleep_reason_count("reason1"), 2);
                assert_eq!(clock.sleep_reason_count("reason2"), 1);
                assert!(clock.is_sleeping_for_reason("reason1"));
                assert!(clock.is_sleeping_for_reason("reason2"));
            }
        },
        Some(Duration::from_secs(5)),
        Some(Duration::from_millis(10)),
    )
    .await;

    // Advance the time such that all sleep futures are completed, then add another sleep with
    // reason2.
    clock.advance(Duration::from_secs(10));
    for handle in [handle1, handle2, handle3] {
        handle.await.unwrap();
    }
    assert!(!clock.is_sleeping_for_reason("reason1"));
    assert!(!clock.is_sleeping_for_reason("reason2"));

    let clock_clone = clock.clone();
    tokio::spawn(async move {
        clock_clone
            .sleep_with_reason(Duration::from_secs(10), "reason2".to_string())
            .await;
    });

    // Verify that eventually the count for both reasons are 2 (i.e. counts don't decrement when
    // the sleeps complete).
    let clock_for_eventually = clock.clone();
    eventually(
        || {
            let clock = clock_for_eventually.clone();
            async move {
                assert_eq!(clock.sleep_reason_count("reason1"), 2);
                assert_eq!(clock.sleep_reason_count("reason2"), 2);
                assert!(!clock.is_sleeping_for_reason("reason1"));
                assert!(clock.is_sleeping_for_reason("reason2"));
            }
        },
        Some(Duration::from_secs(5)),
        Some(Duration::from_millis(10)),
    )
    .await;

    // Verify there is only a single pending waiter.
    assert_eq!(clock.pending_waiter_count(), 1);
}

#[test]
fn test_sleep_reason_handles_dropped_futures() {
    // Test plan: Verify that dropping sleep futures with the same reason leaves the reason active
    // until the final future is dropped, but does not change the cumulative count.
    let clock = FakeClock::create(SystemTime::now());
    let sleep_future1 = clock.sleep_with_reason(Duration::from_secs(10), "reason".to_string());
    let sleep_future2 = clock.sleep_with_reason(Duration::from_secs(10), "reason".to_string());

    assert!(clock.is_sleeping_for_reason("reason"));
    assert_eq!(clock.sleep_reason_count("reason"), 2);

    drop(sleep_future1);

    assert!(clock.is_sleeping_for_reason("reason"));
    assert_eq!(clock.sleep_reason_count("reason"), 2);

    drop(sleep_future2);

    assert!(!clock.is_sleeping_for_reason("reason"));
    assert_eq!(clock.sleep_reason_count("reason"), 2);
}

#[tokio::test(flavor = "multi_thread")]
async fn test_sleep_ordering_and_batching() {
    // Test plan: Verify that sleeps wake in the FIFO order based on their deadlines, and that
    // multiple sleeps with the same deadline wake together in a single batch when the time is
    // advanced.
    let clock = FakeClock::create(SystemTime::now());

    // Setup: Task A (10s), 5 batch tasks (all 20s)
    let task_a_done = Arc::new(AtomicBool::new(false));
    let clock_a = clock.clone();
    let done_a = task_a_done.clone();
    let handle_a = tokio::spawn(async move {
        clock_a.sleep(Duration::from_secs(10)).await;
        done_a.store(true, Ordering::SeqCst);
    });

    let batch_done: Vec<Arc<AtomicBool>> =
        (0..5).map(|_| Arc::new(AtomicBool::new(false))).collect();
    let mut batch_handles = Vec::new();
    for done in &batch_done {
        let clock_clone = clock.clone();
        let done_clone = done.clone();
        let handle = tokio::spawn(async move {
            clock_clone.sleep(Duration::from_secs(20)).await;
            done_clone.store(true, Ordering::SeqCst);
        });
        batch_handles.push(handle);
    }

    while clock.pending_waiter_count() < 6 {
        tokio::task::yield_now().await; // Wait until all tasks register.
    }

    // Advance to 15s: A should complete, all batch tasks should still be pending.
    clock.advance(Duration::from_secs(15));
    while !task_a_done.load(Ordering::SeqCst) {
        tokio::task::yield_now().await;
    }

    for (i, done) in batch_done.iter().enumerate() {
        assert!(!done.load(Ordering::SeqCst), "Batch task {i} should NOT be done at 15s",);
    }

    // Advance to 20s: all 5 batch tasks should complete together.
    clock.advance(Duration::from_secs(5));
    handle_a.await.unwrap();
    for handle in batch_handles {
        handle.await.unwrap();
    }

    for done in batch_done.iter() {
        assert!(done.load(Ordering::SeqCst), "All batch tasks should be done at 20s");
    }
}

#[tokio::test(flavor = "multi_thread")]
async fn test_sequential_sleeps_behavior() {
    // Test plan: Verify that advance() is a "jump" in time, not a continuous flow. If a task
    // performs two sequential 5s sleeps, an advance(10s) will ONLY complete the first one. The
    // second sleep starts at T=10 and ends at T=15.
    let clock = FakeClock::create(SystemTime::now());
    let counter = Arc::new(AtomicU32::new(0));

    let clock_clone = clock.clone();
    let counter_clone = counter.clone();

    tokio::spawn(async move {
        // First Sleep (0 -> 5)
        clock_clone.sleep(Duration::from_secs(5)).await;
        counter_clone.fetch_add(1, Ordering::SeqCst);

        // Second Sleep (Now -> Now + 5)
        clock_clone.sleep(Duration::from_secs(5)).await;
        counter_clone.fetch_add(1, Ordering::SeqCst);
    });

    while clock.pending_waiter_count() < 1 {
        tokio::task::yield_now().await; // Wait until the task registers.
    }

    // Advance 10s. This satisfies the first deadline (5s). The task wakes up, increments counter
    // to 1, and schedules next sleep. The next sleep is for 5 seconds, so the deadline is 15s.
    clock.advance(Duration::from_secs(10));

    // Assert that only the first sleep finished.
    while counter.load(Ordering::SeqCst) < 1 {
        tokio::task::yield_now().await;
    }

    // Wait for second sleep to be registered before advancing.
    while clock.pending_waiter_count() < 1 {
        tokio::task::yield_now().await;
    }

    // Advance another 5s to finish the second sleep.
    clock.advance(Duration::from_secs(5));
    while counter.load(Ordering::SeqCst) < 2 {
        tokio::task::yield_now().await;
    }
}

#[tokio::test(flavor = "multi_thread")]
async fn test_dropped_future_cancellation_safety() {
    // Test plan: Verify that dropping a sleep future before it completes does not cause panics
    // when the FakeClock later tries to wake the destroyed task.
    let clock = FakeClock::create(SystemTime::now());

    // Create a sleep future, poll it once to register the waker, then drop it before completion.
    let sleep_fut = clock.sleep(Duration::from_secs(10));
    let mut sleep_fut = Box::pin(sleep_fut);
    let waker = futures::task::noop_waker();
    let mut cx = std::task::Context::from_waker(&waker);
    let _ = sleep_fut.as_mut().poll(&mut cx);
    drop(sleep_fut);

    // Advance time past the deadline, and verify that no panics occur.
    clock.advance(Duration::from_secs(100));
}

#[tokio::test(flavor = "multi_thread")]
async fn test_interval_simulation() {
    // Test plan: Verify that FakeClock can be used to simulate interval-based periodic tasks
    // using a self-scheduling pattern. A task that sleeps for 10s and then reschedules itself
    // should execute exactly once per time advancement.
    let clock = FakeClock::create(SystemTime::now());
    let counter = Arc::new(AtomicU32::new(0));

    // Spawn a self-scheduling periodic task that runs indefinitely
    let clock_clone = clock.clone();
    let counter_clone = counter.clone();
    let _handle = tokio::spawn(async move {
        loop {
            clock_clone.sleep(Duration::from_secs(10)).await;
            counter_clone.fetch_add(1, Ordering::SeqCst);
        }
    });

    while clock.pending_waiter_count() < 1 {
        tokio::task::yield_now().await; // Wait until the task registers.
    }

    // Verify counter starts at 0
    assert_eq!(counter.load(Ordering::SeqCst), 0, "Counter should be 0 before any advance");

    // Loop 10 times: each iteration advances time by 10s and verifies counter increments by 1
    for i in 1..=10 {
        clock.advance(Duration::from_secs(10));
        while counter.load(Ordering::SeqCst) < i {
            tokio::task::yield_now().await;
        }

        // Wait for next sleep to be registered before next advance.
        while clock.pending_waiter_count() < 1 {
            tokio::task::yield_now().await;
        }
        assert_eq!(counter.load(Ordering::SeqCst), i, "Counter should be {i} after {i} advances",);
    }
}

#[tokio::test(flavor = "multi_thread")]
async fn test_clock_consistency() {
    // Test plan: Verify that instant() returns consistent and accurate time values. Specifically,
    // the elapsed time between two instant() calls should exactly match the total duration passed
    // to advance(). Multiple advances should accumulate correctly, and a zero-duration advance
    // should be a no-op.
    let clock = FakeClock::create(SystemTime::now());

    let start_time: Instant = clock.instant();

    clock.advance(Duration::from_secs(1));
    assert_eq!(clock.instant().duration_since(start_time), Duration::from_secs(1));

    // Multiple advances accumulate correctly
    clock.advance(Duration::from_millis(500));
    clock.advance(Duration::from_millis(500));
    assert_eq!(
        clock.instant().duration_since(start_time),
        Duration::from_secs(2),
        "Multiple advances should accumulate"
    );

    // Zero advance is a no-op
    let t_before: Instant = clock.instant();
    clock.advance(Duration::ZERO);
    let t_after: Instant = clock.instant();
    assert_eq!(t_before, t_after, "Zero advance should be a no-op");
}

#[tokio::test(flavor = "multi_thread")]
async fn test_independence_from_real_time() {
    // Test plan: Verify that FakeClock is completely decoupled from real wall-clock time,
    // and that the time only moves via explicit advance() calls (i.e. no auto-advance when idle).
    let clock = FakeClock::create(SystemTime::now());
    let initial_time: Instant = clock.instant();

    let clock_clone = clock.clone();
    let completed = Arc::new(AtomicBool::new(false));
    let completed_clone = completed.clone();

    let _handle = tokio::spawn(async move {
        clock_clone.sleep(Duration::from_hours(1)).await; // 1 hour
        completed_clone.store(true, Ordering::SeqCst);
    });

    while clock.pending_waiter_count() < 1 {
        tokio::task::yield_now().await; // Wait until the task registers.
    }

    // Sleep real wall-clock time.
    std::thread::sleep(Duration::from_millis(100));

    // Verify fake time has NOT advanced.
    assert_eq!(
        clock.instant(),
        initial_time,
        "Fake time should not advance with real wall-clock time"
    );

    // Verify task is still pending
    assert!(!completed.load(Ordering::SeqCst), "Task should not auto advance");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn test_cross_thread_waking() {
    // Test plan: Verify that FakeClock works correctly across threads. A task sleeping on
    // a worker thread A should wake up when advance() is called from thread B.
    let clock = FakeClock::create(SystemTime::now());
    let completed = Arc::new(AtomicBool::new(false));

    let clock_clone = clock.clone();
    let completed_clone = completed.clone();

    // Spawn on a worker thread.
    let handle = tokio::spawn(async move {
        clock_clone.sleep(Duration::from_secs(10)).await;
        completed_clone.store(true, Ordering::SeqCst);
    });

    while clock.pending_waiter_count() < 1 {
        tokio::task::yield_now().await; // Wait until the task registers.
    }

    // Advance from the main thread.
    clock.advance(Duration::from_secs(10));

    // Wait for completion.
    handle.await.unwrap();

    assert!(completed.load(Ordering::SeqCst), "Task should be completed");
}

#[tokio::test(flavor = "multi_thread")]
async fn test_timeout_success_and_failure() {
    // Test plan: Verify timeout succeeds when the inner future completes before the deadline, and
    // fails when the deadline elapses before the inner future completes. This also verifies that
    // the timeout can be called on a Clock.
    let fake_clock = FakeClock::create(SystemTime::now());
    let clock: Clock = fake_clock.clone().into_clock();

    // Verify that timeout succeeds when the inner future completes before the deadline.
    let result: Result<i32, Elapsed> = clock.timeout(Duration::from_secs(10), async { 42 }).await;
    assert!(result.is_ok(), "Timeout should succeed for instant task");
    assert_eq!(result.unwrap(), 42);

    // Verify that timeout fails when the deadline elapses before the inner future completes.
    let clock_clone = clock.clone();
    let handle = tokio::spawn(async move {
        let inner_clock = clock_clone.clone();
        clock_clone
            .timeout(Duration::from_secs(5), async move {
                // This inner task attempts to sleep for 10s
                inner_clock.sleep(Duration::from_secs(10)).await;
                42
            })
            .await
    });

    // Wait until the inner sleep and timeout are registered.
    while fake_clock.pending_waiter_count() < 2 {
        tokio::task::yield_now().await;
    }

    // Advance 6s - past the 5s timeout but before the 10s sleep.
    fake_clock.advance(Duration::from_secs(6));

    // Verify that the timeout fails.
    let result: Result<i32, Elapsed> = handle.await.unwrap();
    assert!(result.is_err(), "Timeout should fail when deadline elapses");

    // Verify Elapsed's Display implementation.
    assert_eq!(result.unwrap_err().to_string(), "deadline has elapsed");
}

#[tokio::test(flavor = "multi_thread")]
async fn test_timeout_bias_zero_duration() {
    // Test plan: Verify that the timeout function prefers the future completion over the sleep,
    // this is done by using a zero duration timeout with a future that completes immediately, and
    // verifying that the result is always Ok.
    let fake_clock = FakeClock::create(SystemTime::now());
    let clock: Clock = fake_clock.clone().into_clock();

    // Run many times to ensure deterministic behavior.
    for _ in 0..1000 {
        let result = clock.timeout(Duration::ZERO, async { 42 }).await;
        assert_eq!(result, Ok(42), "Zero duration timeout should prefer task completion");
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn test_concurrent_registration_and_waking() {
    // Test plan: Verify that advance() correctly wakes up tasks that have registered for sleep
    // across different threads.
    let clock = FakeClock::create(SystemTime::now());
    let completed_count = Arc::new(AtomicU32::new(0));

    let mut handles = Vec::new();

    // Spawn 100 tasks across different threads
    for _ in 0..100 {
        let clock_clone = clock.clone();
        let count = completed_count.clone();
        let handle = tokio::spawn(async move {
            clock_clone.sleep(Duration::from_secs(10)).await;
            count.fetch_add(1, Ordering::SeqCst);
        });
        handles.push(handle);
    }

    while clock.pending_waiter_count() < 100 {
        tokio::task::yield_now().await; // Wait until the tasks register.
    }

    // Advance time and verify that all tasks are woken.
    clock.advance(Duration::from_secs(10));

    // Wait for all to complete
    for handle in handles {
        handle.await.unwrap();
    }

    assert_eq!(completed_count.load(Ordering::SeqCst), 100);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn test_no_deadlock_under_concurrent_advance_and_registration() {
    // Test plan: Verify that FakeClock does not deadlock under high contention when multiple
    // threads are simultaneously calling advance() and registering new sleeps. This tests the
    // critical path where:
    //   1. advance() acquires the internal lock and wakes waiters.
    //   2. Woken tasks immediately try to register new sleeps (re-acquiring the lock).
    //   3. Other threads are also calling advance() or sleep() concurrently.
    const NUM_ADVANCERS: usize = 4;
    const NUM_SLEEPERS: usize = 20;
    const ITERATIONS_PER_SLEEPER: usize = 100;

    let clock = FakeClock::create(SystemTime::now());
    let barrier = Arc::new(tokio::sync::Barrier::new(NUM_ADVANCERS + NUM_SLEEPERS));
    let completed_count = Arc::new(AtomicU32::new(0));

    let mut handles = Vec::new();

    // Spawn advancer tasks: continuously call advance() until all sleepers complete.
    for _ in 0..NUM_ADVANCERS {
        let clock_clone = clock.clone();
        let barrier = barrier.clone();
        let completed = completed_count.clone();
        handles.push(tokio::spawn(async move {
            barrier.wait().await; // Synchronize start
            while completed.load(Ordering::SeqCst) < NUM_SLEEPERS as u32 {
                clock_clone.advance(Duration::from_millis(1));
                tokio::task::yield_now().await;
            }
        }));
    }

    // Spawn sleeper tasks: repeatedly sleep and re-register, then signal completion.
    for _ in 0..NUM_SLEEPERS {
        let clock = clock.clone();
        let barrier = barrier.clone();
        let completed = completed_count.clone();
        handles.push(tokio::spawn(async move {
            barrier.wait().await; // Synchronize start
            for _ in 0..ITERATIONS_PER_SLEEPER {
                clock.sleep(Duration::from_millis(1)).await;
            }
            completed.fetch_add(1, Ordering::SeqCst);
        }));
    }

    // Wait for all tasks to complete. If deadlock, this hangs (CI catches it).
    for handle in handles {
        handle.await.unwrap();
    }
}

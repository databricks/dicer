use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::Duration;

use tokio::time::Instant;
use tracing_test::traced_test;
use caching_rust_util_testing::assertion_waiter::AssertionWaiter;

#[tokio::test]
async fn test_await_condition_succeeds_immediately() {
    // Test plan: Verify that await_condition returns immediately if assertion passes on first try.
    //
    // Setup: Create a waiter and a condition that's already true.
    // Verify: await_condition completes without delay.

    let waiter = AssertionWaiter::new("test immediate success");
    let start: Instant = Instant::now();

    waiter
        .await_condition(|| {
            // Empty condition that always succeeds.
        })
        .await;

    let elapsed: Duration = start.elapsed();
    assert!(
        elapsed < Duration::from_millis(100),
        "Should complete immediately, took {:?}",
        elapsed
    );
}

#[tokio::test]
async fn test_await_condition_retries_until_success() {
    // Test plan: Verify that await_condition retries until assertion eventually passes.
    //
    // Setup: Create a counter that increments, waiter polls until it reaches expected value.
    // Verify: Waiter succeeds when condition is met.

    let counter = Arc::new(AtomicUsize::new(0));
    let counter_clone: Arc<AtomicUsize> = Arc::clone(&counter);

    // Spawn a task that increments counter after delay.
    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(100)).await;
        counter_clone.store(5, Ordering::SeqCst);
    });

    let waiter = AssertionWaiter::new("wait for counter")
        .with_timeout(Duration::from_secs(2))
        .with_poll_interval(Duration::from_millis(10));

    waiter
        .await_condition(|| {
            let value: usize = counter.load(Ordering::SeqCst);
            assert_eq!(value, 5, "Expected 5, got {}", value);
        })
        .await;
}

#[tokio::test]
#[should_panic(expected = "Sync timeout test failure message")]
async fn test_await_condition_panics_on_timeout() {
    // Test plan: Verify that await_condition panics if assertion never passes within timeout,
    // and that the panic includes the original error message.
    //
    // Setup: Create a waiter with short timeout and a condition that never passes.
    // Verify: Waiter panics with timeout message containing the panic reason.

    let waiter = AssertionWaiter::new("test timeout")
        .with_timeout(Duration::from_millis(100))
        .with_poll_interval(Duration::from_millis(10));

    waiter
        .await_condition(|| {
            panic!("Sync timeout test failure message");
        })
        .await;
}

#[tokio::test]
async fn test_await_condition_with_mutable_state() {
    // Test plan: Verify that await_condition works with mutable state in closure.
    //
    // Setup: Create a flag that gets set after delay.
    // Verify: Waiter succeeds when flag is set.

    let flag = Arc::new(AtomicBool::new(false));
    let flag_clone: Arc<AtomicBool> = Arc::clone(&flag);

    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(50)).await;
        flag_clone.store(true, Ordering::SeqCst);
    });

    let waiter = AssertionWaiter::new("wait for flag")
        .with_timeout(Duration::from_secs(2))
        .with_poll_interval(Duration::from_millis(10));

    waiter
        .await_condition(|| {
            assert!(flag.load(Ordering::SeqCst), "Flag should be true");
        })
        .await;
}

#[tokio::test]
async fn test_await_async_condition_succeeds_immediately() {
    // Test plan: Verify that await_async_condition returns immediately if assertion passes on
    // first try.
    //
    // Setup: Create a waiter and an async condition that's already true.
    // Verify: await_async_condition completes without delay.

    let waiter = AssertionWaiter::new("test async immediate success");
    let start: Instant = Instant::now();

    waiter
        .await_async_condition(|| async {
            // Async operation that completes immediately.
            tokio::time::sleep(Duration::from_millis(1)).await;
        })
        .await;

    let elapsed: Duration = start.elapsed();
    assert!(
        elapsed < Duration::from_millis(100),
        "Should complete immediately, took {:?}",
        elapsed
    );
}

#[tokio::test]
async fn test_await_async_condition_retries_until_success() {
    // Test plan: Verify that await_async_condition retries until async assertion eventually passes.
    //
    // Setup: Create a counter that increments via async task, waiter polls until it reaches
    // expected value.
    // Verify: Waiter succeeds when condition is met.

    let counter = Arc::new(AtomicUsize::new(0));
    let counter_clone: Arc<AtomicUsize> = Arc::clone(&counter);

    // Spawn a task that increments counter after delay.
    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(100)).await;
        counter_clone.store(7, Ordering::SeqCst);
    });

    let waiter = AssertionWaiter::new("wait for async counter")
        .with_timeout(Duration::from_secs(2))
        .with_poll_interval(Duration::from_millis(10));

    let counter_for_check: Arc<AtomicUsize> = Arc::clone(&counter);
    waiter
        .await_async_condition(|| async {
            // Simulate async operation (e.g., network call, database query).
            tokio::time::sleep(Duration::from_millis(1)).await;
            let value: usize = counter_for_check.load(Ordering::SeqCst);
            assert_eq!(value, 7, "Expected 7, got {}", value);
        })
        .await;
}

#[tokio::test]
#[should_panic(expected = "Async timeout test failure message")]
async fn test_await_async_condition_panics_on_timeout() {
    // Test plan: Verify that await_async_condition panics if async assertion never passes
    // within timeout, and that the panic includes the original error message.
    //
    // Setup: Create a waiter with short timeout and an async condition that never passes.
    // Verify: Waiter panics with timeout message containing the panic reason.

    let waiter = AssertionWaiter::new("test async timeout")
        .with_timeout(Duration::from_millis(100))
        .with_poll_interval(Duration::from_millis(10));

    waiter
        .await_async_condition(|| async {
            tokio::time::sleep(Duration::from_millis(1)).await;
            panic!("Async timeout test failure message");
        })
        .await;
}

#[tokio::test]
async fn test_await_async_condition_with_network_simulation() {
    // Test plan: Verify that await_async_condition works with simulated async network calls.
    //
    // Setup: Create a flag that gets set after delay, simulating network response.
    // Verify: Waiter succeeds when async operation returns expected result.

    let response_ready = Arc::new(AtomicBool::new(false));
    let response_ready_clone: Arc<AtomicBool> = Arc::clone(&response_ready);

    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(80)).await;
        response_ready_clone.store(true, Ordering::SeqCst);
    });

    let waiter = AssertionWaiter::new("wait for network response")
        .with_timeout(Duration::from_secs(2))
        .with_poll_interval(Duration::from_millis(10));

    let response_check: Arc<AtomicBool> = Arc::clone(&response_ready);
    waiter
        .await_async_condition(|| async {
            // Simulate async network call.
            tokio::time::sleep(Duration::from_millis(5)).await;
            assert!(response_check.load(Ordering::SeqCst), "Network response should be ready");
        })
        .await;
}

#[tokio::test]
#[traced_test]
async fn test_await_condition_logs_periodically() {
    // Test plan: Verify that await_condition logs "Waiting" and "Still waiting" messages.
    //
    // Setup: Create a waiter with short log interval and a condition that fails multiple times.
    // Verify: Logs are captured (enabled by no-env-filter feature in tracing-test patch).

    let counter = Arc::new(AtomicUsize::new(0));
    let counter_clone: Arc<AtomicUsize> = Arc::clone(&counter);

    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(150)).await;
        counter_clone.store(10, Ordering::SeqCst);
    });

    let waiter = AssertionWaiter::new("test periodic logging")
        .with_timeout(Duration::from_secs(2))
        .with_poll_interval(Duration::from_millis(10))
        .with_log_interval(Duration::from_millis(50));

    let counter_check: Arc<AtomicUsize> = Arc::clone(&counter);
    waiter
        .await_condition(|| {
            let value = counter_check.load(Ordering::SeqCst);
            assert_eq!(value, 10, "Expected 10, got {}", value);
        })
        .await;

    assert!(logs_contain("AssertionWaiter[test periodic logging]: Waiting"));
}

#[tokio::test]
#[traced_test]
async fn test_await_async_condition_logs_periodically() {
    // Test plan: Verify that await_async_condition logs "Waiting" and "Still waiting" messages.
    //
    // Setup: Create a waiter with short log interval and an async condition that fails multiple times.
    // Verify: Logs are captured (enabled by no-env-filter feature in tracing-test patch).

    let counter = Arc::new(AtomicUsize::new(0));
    let counter_clone: Arc<AtomicUsize> = Arc::clone(&counter);

    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(150)).await;
        counter_clone.store(15, Ordering::SeqCst);
    });

    let waiter = AssertionWaiter::new("test async periodic logging")
        .with_timeout(Duration::from_secs(2))
        .with_poll_interval(Duration::from_millis(10))
        .with_log_interval(Duration::from_millis(50));

    let counter_check: Arc<AtomicUsize> = Arc::clone(&counter);
    waiter
        .await_async_condition(|| async {
            tokio::time::sleep(Duration::from_millis(1)).await;
            let value = counter_check.load(Ordering::SeqCst);
            assert_eq!(value, 15, "Expected 15, got {}", value);
        })
        .await;

    assert!(logs_contain("AssertionWaiter[test async periodic logging]: Waiting"));
}

#[tokio::test]
#[traced_test]
async fn test_await_condition_with_string_panic() {
    // Test plan: Verify that await_condition handles String panics correctly and logs them.
    //
    // Setup: Create a condition that panics with a String and takes time to resolve.
    // Verify: Waiter logs "Still waiting" with the panic message.

    let should_fail = Arc::new(AtomicBool::new(true));
    let should_fail_clone: Arc<AtomicBool> = Arc::clone(&should_fail);

    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(100)).await;
        should_fail_clone.store(false, Ordering::SeqCst);
    });

    let waiter = AssertionWaiter::new("test string panic")
        .with_timeout(Duration::from_secs(2))
        .with_poll_interval(Duration::from_millis(10))
        .with_log_interval(Duration::from_millis(20));

    let should_fail_check: Arc<AtomicBool> = Arc::clone(&should_fail);
    waiter
        .await_condition(|| {
            if should_fail_check.load(Ordering::SeqCst) {
                let msg = String::from("This is a String panic");
                std::panic::panic_any(msg);
            }
        })
        .await;

    assert!(logs_contain("Still waiting: This is a String panic"));
}

#[tokio::test]
#[traced_test]
async fn test_await_condition_with_str_panic() {
    // Test plan: Verify that await_condition handles &str panics correctly and logs them.
    //
    // Setup: Create a condition that panics with a &str and takes time to resolve.
    // Verify: Waiter logs "Still waiting" with the panic message.

    let should_fail = Arc::new(AtomicBool::new(true));
    let should_fail_clone: Arc<AtomicBool> = Arc::clone(&should_fail);

    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(100)).await;
        should_fail_clone.store(false, Ordering::SeqCst);
    });

    let waiter = AssertionWaiter::new("test str panic")
        .with_timeout(Duration::from_secs(2))
        .with_poll_interval(Duration::from_millis(10))
        .with_log_interval(Duration::from_millis(20));

    let should_fail_check: Arc<AtomicBool> = Arc::clone(&should_fail);
    waiter
        .await_condition(|| {
            if should_fail_check.load(Ordering::SeqCst) {
                panic!("This is a &str panic");
            }
        })
        .await;

    assert!(logs_contain("Still waiting: This is a &str panic"));
}

#[tokio::test]
#[traced_test]
async fn test_await_condition_with_unknown_panic() {
    // Test plan: Verify that await_condition handles unknown panic types (not String or &str).
    //
    // Setup: Create a condition that panics with an i32 (non-string type).
    // Verify: Waiter logs "Still waiting: Unknown panic" when panic type isn't recognized.

    let should_fail = Arc::new(AtomicBool::new(true));
    let should_fail_clone: Arc<AtomicBool> = Arc::clone(&should_fail);

    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(100)).await;
        should_fail_clone.store(false, Ordering::SeqCst);
    });

    let waiter = AssertionWaiter::new("test unknown panic")
        .with_timeout(Duration::from_secs(2))
        .with_poll_interval(Duration::from_millis(10))
        .with_log_interval(Duration::from_millis(20));

    let should_fail_check: Arc<AtomicBool> = Arc::clone(&should_fail);
    waiter
        .await_condition(|| {
            if should_fail_check.load(Ordering::SeqCst) {
                std::panic::panic_any(42_i32);
            }
        })
        .await;

    assert!(logs_contain("Still waiting: Unknown panic"));
}

#[tokio::test]
#[traced_test]
async fn test_await_async_condition_with_string_panic() {
    // Test plan: Verify that await_async_condition handles String panics correctly and logs them.
    //
    // Setup: Create an async condition that panics with a String and takes time to resolve.
    // Verify: Waiter logs "Still waiting" with the panic message.

    let should_fail = Arc::new(AtomicBool::new(true));
    let should_fail_clone: Arc<AtomicBool> = Arc::clone(&should_fail);

    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(100)).await;
        should_fail_clone.store(false, Ordering::SeqCst);
    });

    let waiter = AssertionWaiter::new("test async string panic")
        .with_timeout(Duration::from_secs(2))
        .with_poll_interval(Duration::from_millis(10))
        .with_log_interval(Duration::from_millis(20));

    let should_fail_check: Arc<AtomicBool> = Arc::clone(&should_fail);
    waiter
        .await_async_condition(|| async {
            tokio::time::sleep(Duration::from_millis(1)).await;
            if should_fail_check.load(Ordering::SeqCst) {
                let msg = String::from("This is an async String panic");
                std::panic::panic_any(msg);
            }
        })
        .await;

    assert!(logs_contain("Still waiting: This is an async String panic"));
}

#[tokio::test]
#[traced_test]
async fn test_await_async_condition_with_str_panic() {
    // Test plan: Verify that await_async_condition handles &str panics correctly and logs them.
    //
    // Setup: Create an async condition that panics with a &str and takes time to resolve.
    // Verify: Waiter logs "Still waiting" with the panic message.

    let should_fail = Arc::new(AtomicBool::new(true));
    let should_fail_clone: Arc<AtomicBool> = Arc::clone(&should_fail);

    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(100)).await;
        should_fail_clone.store(false, Ordering::SeqCst);
    });

    let waiter = AssertionWaiter::new("test async str panic")
        .with_timeout(Duration::from_secs(2))
        .with_poll_interval(Duration::from_millis(10))
        .with_log_interval(Duration::from_millis(20));

    let should_fail_check: Arc<AtomicBool> = Arc::clone(&should_fail);
    waiter
        .await_async_condition(|| async {
            tokio::time::sleep(Duration::from_millis(1)).await;
            if should_fail_check.load(Ordering::SeqCst) {
                panic!("This is an async &str panic");
            }
        })
        .await;

    assert!(logs_contain("Still waiting: This is an async &str panic"));
}

#[tokio::test]
#[traced_test]
async fn test_await_async_condition_with_unknown_panic() {
    // Test plan: Verify that await_async_condition handles unknown panic types.
    //
    // Setup: Create an async condition that panics with an i32 (non-string type).
    // Verify: Waiter logs "Still waiting: Unknown panic" when panic type isn't recognized.

    let should_fail = Arc::new(AtomicBool::new(true));
    let should_fail_clone: Arc<AtomicBool> = Arc::clone(&should_fail);

    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(100)).await;
        should_fail_clone.store(false, Ordering::SeqCst);
    });

    let waiter = AssertionWaiter::new("test async unknown panic")
        .with_timeout(Duration::from_secs(2))
        .with_poll_interval(Duration::from_millis(10))
        .with_log_interval(Duration::from_millis(20));

    let should_fail_check: Arc<AtomicBool> = Arc::clone(&should_fail);
    waiter
        .await_async_condition(|| async {
            tokio::time::sleep(Duration::from_millis(1)).await;
            if should_fail_check.load(Ordering::SeqCst) {
                // Panic with an i32 - not String or &str
                std::panic::panic_any(42_i32);
            }
        })
        .await;

    assert!(logs_contain("Still waiting: Unknown panic"));
}

#[tokio::test]
async fn test_builder_methods() {
    // Test plan: Verify that builder methods (with_timeout, with_poll_interval, with_log_interval)
    // work correctly.
    //
    // Setup: Create waiter with custom values via builder methods.
    // Verify: Waiter uses the configured values.

    let flag = Arc::new(AtomicBool::new(false));
    let flag_clone: Arc<AtomicBool> = Arc::clone(&flag);

    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(30)).await;
        flag_clone.store(true, Ordering::SeqCst);
    });

    // Use builder methods to customize waiter.
    let waiter = AssertionWaiter::new("test builder methods")
        .with_timeout(Duration::from_secs(1))
        .with_poll_interval(Duration::from_millis(5))
        .with_log_interval(Duration::from_millis(100));

    let flag_check: Arc<AtomicBool> = Arc::clone(&flag);
    waiter
        .await_condition(|| {
            assert!(flag_check.load(Ordering::SeqCst), "Flag should be true");
        })
        .await;
}

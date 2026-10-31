use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime};

use futures_util::FutureExt;
use tokio::time::{sleep, timeout, Instant};
use caching_rust_util::sequential_executor::{Handle, SequentialExecutor};
use caching_rust_util::{system_clock, Clock};
use caching_rust_util_testing::fake_clock::FakeClock;

/// Helper function to wait for a condition to become true, polling repeatedly.
/// Avoids flaky tests by not relying on fixed sleep durations.
///
/// Note: We use a custom helper rather than `raf_testing_async::eventually`
/// because `eventually` retries assertions that can panic, which causes mutex poisoning
/// when used with `Mutex<Vec<String>>`. This helper simply polls a boolean condition
/// without panicking until timeout.
async fn wait_for<F>(check: F, message: &str)
where
    F: Fn() -> bool,
{
    let result = timeout(Duration::from_secs(5), async {
        while !check() {
            sleep(Duration::from_millis(10)).await;
        }
    })
    .await;

    if result.is_err() {
        panic!("Timeout waiting for: {}", message);
    }
}

/// Creates and starts an executor with the given state and clock.
fn create_and_start_executor<State: Send + 'static>(
    state: State,
    clock: Clock,
) -> SequentialExecutor<State> {
    let executor: SequentialExecutor<State> = SequentialExecutor::new(state, clock);
    let runtime_handle = tokio::runtime::Handle::current();
    assert!(executor.start(&runtime_handle)); // Assert the start is successful.
    executor
}

/// Test state that maintains a log of operations, with thread-safe access
/// to the entries for checking expectations without needing the executor.
#[derive(Clone)]
struct TestLog {
    entries: Arc<Mutex<Vec<String>>>,
}

impl TestLog {
    fn new() -> Self {
        Self {
            entries: Arc::new(Mutex::new(Vec::new())),
        }
    }

    fn append(&self, entry: impl Into<String>) {
        self.entries.lock().unwrap().push(entry.into());
    }

    fn get_entries(&self) -> Vec<String> {
        self.entries.lock().unwrap().clone()
    }

    /// Wait for the log to contain the expected entries.
    async fn wait_for_entries(&self, expected: Vec<&str>) {
        let expected: Vec<String> = expected.into_iter().map(|s| s.to_string()).collect();
        let expected_for_message = expected.clone();
        let entries = self.entries.clone();
        wait_for(
            move || entries.lock().unwrap().clone() == expected,
            &format!("log to contain {:?}", expected_for_message),
        )
        .await;
    }
}

#[tokio::test]
async fn test_clock_getter() {
    // Test plan: Verify that the clock getter returns the correct clock.
    let log = TestLog::new();
    let fake_clock = FakeClock::create(SystemTime::UNIX_EPOCH);
    let executor = create_and_start_executor(log.clone(), fake_clock.clone().into_clock());

    // Verify the getter returns a clock that matches our fake clock
    assert_eq!(executor.clock().now(), SystemTime::UNIX_EPOCH);

    fake_clock.advance(Duration::from_secs(10));
    assert_eq!(executor.clock().now(), SystemTime::UNIX_EPOCH + Duration::from_secs(10));
}

#[tokio::test]
async fn test_start_multiple_times() {
    // Test plan: Start the executor multiple times and verify subsequent starts are no-ops: they
    // return false, and the executor remains functional.
    let log = TestLog::new();
    let executor = SequentialExecutor::new(log.clone(), system_clock());
    let runtime_handle = tokio::runtime::Handle::current();

    // First start should succeed.
    assert!(executor.start(&runtime_handle));

    // Subsequent starts should return false.
    assert!(!executor.start(&runtime_handle));
    assert!(!executor.start(&runtime_handle));

    executor
        .call(|_executor, log| {
            log.append("task executed");
        })
        .await;

    assert_eq!(log.get_entries(), vec!["task executed"]);
}

#[tokio::test]
async fn test_enqueue_before_start() {
    // Verify that tasks can be submitted before start is called and are processed
    // once start is called.
    let log = TestLog::new();
    let executor = SequentialExecutor::new(log.clone(), system_clock());

    // Submit work BEFORE calling start().
    executor.execute(|_executor, log| {
        log.append("task executed");
    });
    // Sleep briefly to verify that the task hasn't run yet.
    tokio::time::sleep(Duration::from_millis(50)).await;
    assert!(log.get_entries().is_empty(), "Task should not run before start() is called");

    // Call start() and verify that the task is processed.
    let handle = tokio::runtime::Handle::current();
    assert!(executor.start(&handle));
    log.wait_for_entries(vec!["task executed"]).await;
}

#[tokio::test]
async fn test_basic_call() {
    // Test plan: Execute a task using `call()` and ensure it runs and returns the correct value.
    let log = TestLog::new();
    let executor = create_and_start_executor(log.clone(), system_clock());

    let result = executor
        .call(|_executor, log| {
            log.append("First");
            42
        })
        .await;

    assert_eq!(result, 42);
    assert_eq!(log.get_entries(), vec!["First"]);
}

#[tokio::test]
async fn test_two_calls_in_order() {
    // Test plan: Execute two tasks sequentially and ensure they run in order.
    let log = TestLog::new();
    let executor = create_and_start_executor(log.clone(), system_clock());

    executor
        .call(|_executor, log| {
            log.append("First");
        })
        .await;

    executor
        .call(|_executor, log| {
            log.append("Second");
        })
        .await;

    assert_eq!(log.get_entries(), vec!["First", "Second"]);
}

#[tokio::test]
async fn test_execute_fire_and_forget() {
    // Test plan: Use `execute()` for fire-and-forget execution and verify the task runs.
    let log = TestLog::new();
    let executor = create_and_start_executor(log.clone(), system_clock());

    executor.execute(|_executor, log| {
        log.append("Executed");
    });

    log.wait_for_entries(vec!["Executed"]).await;
}

#[tokio::test]
async fn test_schedule_with_delay() {
    // Test plan: Schedule a task with a delay and verify it runs after the delay.
    let log = TestLog::new();
    let executor = create_and_start_executor(log.clone(), system_clock());

    let start = Instant::now();
    executor.schedule(
        |_executor, log| {
            log.append("Delayed");
        },
        Duration::from_millis(100),
    );

    log.wait_for_entries(vec!["Delayed"]).await;

    let elapsed = start.elapsed();
    assert!(elapsed >= Duration::from_millis(100));
}

#[tokio::test]
async fn test_ordering_guarantee_immediate_tasks() {
    // Test plan: Verify that immediate tasks (zero delay) run in the order they're added.
    let log = TestLog::new();
    let executor = create_and_start_executor(log.clone(), system_clock());

    executor
        .call_with_delay(
            |_executor, log| {
                log.append("First");
            },
            Duration::ZERO,
        )
        .await;

    executor
        .call_with_delay(
            |_executor, log| {
                log.append("Second");
            },
            Duration::ZERO,
        )
        .await;

    executor
        .call_with_delay(
            |_executor, log| {
                log.append("Third");
            },
            Duration::ZERO,
        )
        .await;

    assert_eq!(log.get_entries(), vec!["First", "Second", "Third"]);
}

#[tokio::test]
async fn test_ordering_guarantee_increasing_delays() {
    // Test plan: Verify that tasks with increasing delays run in order.
    let log = TestLog::new();
    let executor = create_and_start_executor(log.clone(), system_clock());

    executor.schedule(
        |_executor, log| {
            log.append("First");
        },
        Duration::from_millis(10),
    );

    executor.schedule(
        |_executor, log| {
            log.append("Second");
        },
        Duration::from_millis(20),
    );

    executor.schedule(
        |_executor, log| {
            log.append("Third");
        },
        Duration::from_millis(30),
    );

    log.wait_for_entries(vec!["First", "Second", "Third"]).await;
}

#[tokio::test]
async fn test_ordering_guarantee_same_delay() {
    // Test plan: Verify that tasks with the same delay run in the order they're added.
    let log = TestLog::new();
    let executor = create_and_start_executor(log.clone(), system_clock());

    let delay = Duration::from_millis(10);
    executor.schedule(
        |_executor, log| {
            log.append("A");
        },
        delay,
    );

    executor.schedule(
        |_executor, log| {
            log.append("B");
        },
        delay,
    );

    executor.schedule(
        |_executor, log| {
            log.append("C");
        },
        delay,
    );

    log.wait_for_entries(vec!["A", "B", "C"]).await;
}

#[tokio::test]
async fn test_cancel_scheduled_task() {
    // Test plan: Schedule a task, cancel it, and verify it doesn't run.
    let log = TestLog::new();
    let fake_clock = FakeClock::create(SystemTime::now());
    let executor = create_and_start_executor(log.clone(), fake_clock.clone().into_clock());

    let handle = executor.schedule(
        |_executor, log| {
            log.append("Cancelled");
        },
        Duration::from_millis(100),
    );

    // Cancel immediately.
    handle.cancel();

    // Advance time past when the task would have run.
    fake_clock.advance(Duration::from_millis(150));

    // Flush the executor to ensure any pending tasks have run.
    executor.call(|_executor, _log| {}).await;

    assert_eq!(log.get_entries(), Vec::<String>::new());
}

#[tokio::test]
async fn test_cancel_does_not_affect_other_tasks() {
    // Test plan: Schedule multiple tasks, cancel one, and verify others still run.
    let log = TestLog::new();
    let executor = create_and_start_executor(log.clone(), system_clock());

    executor.schedule(
        |_executor, log| {
            log.append("First");
        },
        Duration::from_millis(10),
    );

    let handle = executor.schedule(
        |_executor, log| {
            log.append("Cancelled");
        },
        Duration::from_millis(20),
    );

    executor.schedule(
        |_executor, log| {
            log.append("Third");
        },
        Duration::from_millis(30),
    );

    handle.cancel();

    log.wait_for_entries(vec!["First", "Third"]).await;
}

#[tokio::test]
async fn test_immediate_before_scheduled() {
    // Test plan: Schedule a task for the future, then enqueue an immediate task.
    // Verify the immediate task runs first.
    let log = TestLog::new();
    let executor = create_and_start_executor(log.clone(), system_clock());

    executor.schedule(
        |_executor, log| {
            log.append("Scheduled");
        },
        Duration::from_millis(50),
    );

    executor.execute(|_executor, log| {
        log.append("Immediate");
    });

    log.wait_for_entries(vec!["Immediate", "Scheduled"]).await;
}

#[tokio::test]
async fn test_lifetime_of_max_delay_task() {
    // Test plan: Schedule a task with the largest public delay, then verify
    // that the executor still accepts and runs an immediate call. This covers
    // overflow while computing the scheduled task's desired execution time and
    // verifies that concrete deadlines are ordered before never-due tasks.
    // Also verify that cancelling the task with maximum delay does not panic,
    // the task is not run, and its captured state is only dropped after normal
    // cancellation.

    // Helper struct to track when a task is dropped.
    struct DropMarker(Arc<AtomicUsize>);
    impl Drop for DropMarker {
        fn drop(&mut self) {
            self.0.fetch_add(1, Ordering::SeqCst);
        }
    }

    let log = TestLog::new();
    let executor = create_and_start_executor(log.clone(), system_clock());
    let drop_count: Arc<AtomicUsize> = Arc::new(AtomicUsize::new(0));

    // Schedule a task with maximum delay.
    let drop_marker = DropMarker(Arc::clone(&drop_count));
    let handle: Handle = executor.schedule(
        move |_executor, log| {
            let _drop_marker = drop_marker;
            log.append("Max delay");
        },
        Duration::MAX,
    );

    // Run an immediate task and verify it runs successfully without panicking or timing out.
    let result = timeout(
        Duration::from_millis(500),
        executor.call(|_executor, log| {
            log.append("Immediate");
            42
        }),
    )
    .await;
    assert_eq!(result.expect("immediate call timed out"), 42);
    assert_eq!(log.get_entries(), vec!["Immediate"]);

    // Flush the executor to ensure any pending tasks have run, and verify that
    // the task with maximum delay is not dropped.
    executor.call(|_executor, _log| {}).await;
    assert_eq!(drop_count.load(Ordering::SeqCst), 0);

    // Cancel the task with maximum delay and verify that the task is not run,
    // that cancellation does not panic, and that captured state is dropped
    // through cancellation rather than during enqueue.
    handle.cancel();
    wait_for(|| drop_count.load(Ordering::SeqCst) == 1, "max-delay task to be cancelled").await;

    // Verify the executor still runs subsequent work and that the task with
    // maximum delay is not run.
    executor.call(|_executor, _log| {}).await;
    assert_eq!(log.get_entries(), vec!["Immediate"]);
}

#[tokio::test]
async fn test_concurrent_enqueueing() {
    // Test plan: Enqueue tasks from multiple concurrent tasks and verify all run.
    let log = TestLog::new();
    let executor = Arc::new(create_and_start_executor(log.clone(), system_clock()));

    let mut handles = vec![];
    for i in 0..10 {
        let executor = executor.clone();
        let handle = tokio::spawn(async move {
            executor.execute(move |_executor, log| {
                log.append(format!("Task-{}", i));
            });
        });
        handles.push(handle);
    }

    // Wait for all spawned tasks to enqueue their work.
    for handle in handles {
        handle.await.unwrap();
    }

    // Wait for all tasks to execute.
    let entries_clone = log.entries.clone();
    wait_for(|| entries_clone.lock().unwrap().len() == 10, "all 10 tasks to complete").await;

    let entries = log.get_entries();
    assert_eq!(entries.len(), 10);
    // Verify all tasks ran (order may vary due to concurrent enqueueing).
    for i in 0..10 {
        assert!(entries.contains(&format!("Task-{}", i)));
    }
}

#[tokio::test]
async fn test_call_cancellation_on_drop() {
    // Test plan: Drop the future returned by `call` before it completes
    // and verify the task is cancelled.
    let log = TestLog::new();
    let executor = create_and_start_executor(log.clone(), system_clock());

    let ran = Arc::new(AtomicBool::new(false));

    // Create a future but don't await it.
    let future = {
        let ran = ran.clone();
        executor.call(move |_executor, _log| {
            ran.store(true, Ordering::SeqCst);
        })
    };

    // Drop the future immediately. This test is not flaky because the tokio
    // test runtime uses a single thread by default. Since we haven't yielded
    // control (awaited), the executor's background task cannot have started
    // processing our request yet. The drop will trigger cancellation, and the
    // task will never run.
    drop(future);

    // Flush the executor to ensure any pending tasks have run.
    executor.call(|_executor, _log| {}).await;

    // Verify the task did not run.
    assert!(!ran.load(Ordering::SeqCst));

    // Verify executor is still functional.
    executor
        .call(|_executor, log| {
            log.append("After cancel");
        })
        .await;

    assert_eq!(log.get_entries(), vec!["After cancel"]);
}

#[tokio::test]
async fn test_call_with_delay_cancellation_on_drop() {
    // Test plan: Drop the future returned by `call_with_delay` before it completes
    // and verify the task is cancelled.
    let log = TestLog::new();
    let fake_clock = FakeClock::create(SystemTime::now());
    let executor = create_and_start_executor(log.clone(), fake_clock.clone().into_clock());

    let ran = Arc::new(AtomicBool::new(false));

    // Create a future but don't await it fully.
    let future = {
        let ran = ran.clone();
        executor.call_with_delay(
            move |_executor, _log| {
                ran.store(true, Ordering::SeqCst);
            },
            Duration::from_millis(100),
        )
    };

    // Drop the future immediately.
    drop(future);

    // Advance time past when the task would have run.
    fake_clock.advance(Duration::from_millis(150));

    // Flush the executor to ensure any pending tasks have run.
    executor.call(|_executor, _log| {}).await;

    // Verify the task did not run.
    assert!(!ran.load(Ordering::SeqCst));

    // Verify executor is still functional.
    executor
        .call(|_executor, log| {
            log.append("After cancel");
        })
        .await;

    assert_eq!(log.get_entries(), vec!["After cancel"]);
}

#[tokio::test]
async fn test_sequential_execution_no_overlap() {
    // Test plan: Verify that tasks run sequentially with no overlap by tracking
    // execution state.
    let executor = Arc::new(create_and_start_executor(0, system_clock()));

    let counter = Arc::new(AtomicUsize::new(0));

    let mut handles = vec![];
    for _ in 0..5 {
        let exec = executor.clone();
        let counter = counter.clone();
        let handle = tokio::spawn(async move {
            exec.call(move |_executor, running_count| {
                // Increment running count.
                *running_count += 1;

                // Should always be exactly 1 (no concurrent execution).
                assert_eq!(*running_count, 1);

                // Simulate some work.
                std::thread::sleep(Duration::from_millis(10));

                // Decrement running count.
                *running_count -= 1;

                counter.fetch_add(1, Ordering::SeqCst);
            })
            .await;
        });
        handles.push(handle);
    }

    // Wait for all tasks to complete.
    for handle in handles {
        handle.await.unwrap();
    }

    // Verify all tasks ran.
    assert_eq!(counter.load(Ordering::SeqCst), 5);
}

#[tokio::test]
async fn test_handle_weak_reference() {
    // Test plan: Verify that dropping the executor allows the Handle's weak reference
    // to become invalid, and cancellation becomes a no-op.
    let handle = {
        let log = TestLog::new();
        let executor = create_and_start_executor(log.clone(), system_clock());
        executor.schedule(
            |_executor, log| {
                log.append("Task");
            },
            Duration::from_secs(1),
        )
    }; // Executor is dropped here.

    // This should be a no-op since executor is dropped.
    handle.cancel();

    // Test passes if no panic occurs.
}

#[tokio::test]
async fn test_dropped_handle_allows_execution() {
    // Test plan: Verify that dropping a handle (without canceling) allows the task to run.
    let log = TestLog::new();
    let executor = create_and_start_executor(log.clone(), system_clock());

    {
        let _handle = executor.schedule(
            |_executor, log| {
                log.append("Ran");
            },
            Duration::from_millis(50),
        );
        // Handle is dropped here without calling cancel.
    }

    // Task should still run.
    log.wait_for_entries(vec!["Ran"]).await;
}

#[tokio::test]
async fn test_zero_delay() {
    // Test plan: Verify that zero delay results in immediate execution.
    let log = TestLog::new();
    let executor = create_and_start_executor(log.clone(), system_clock());

    executor.schedule(
        |_executor, log| {
            log.append("Zero");
        },
        Duration::ZERO,
    );

    log.wait_for_entries(vec!["Zero"]).await;
}

#[tokio::test]
async fn test_schedule_with_fake_clock() {
    // Test plan: Use a fake clock to test scheduling without waiting for real time.
    let log = TestLog::new();
    let fake_clock = FakeClock::create(SystemTime::now());
    let executor = create_and_start_executor(log.clone(), fake_clock.clone().into_clock());

    executor.schedule(
        |_executor, log| {
            log.append("Scheduled");
        },
        Duration::from_secs(10),
    );

    // Task should not have run yet.
    assert_eq!(log.get_entries(), Vec::<String>::new());

    // Advance time.
    fake_clock.advance(Duration::from_secs(10));

    // Flush the executor to ensure the task has run.
    executor.call(|_executor, _log| {}).await;

    // Now the task should have run.
    assert_eq!(log.get_entries(), vec!["Scheduled"]);
}

#[tokio::test]
async fn test_ordering_with_fake_clock() {
    // Test plan: Verify ordering guarantees with a fake clock. Also schedule a
    // never-due task and verify that it's never considered due.
    let log = TestLog::new();
    let fake_clock = FakeClock::create(SystemTime::now());
    let executor = create_and_start_executor(log.clone(), fake_clock.clone().into_clock());

    executor.schedule(
        |_executor, log| {
            log.append("never");
        },
        Duration::MAX,
    );

    executor.schedule(
        |_executor, log| {
            log.append("100ms");
        },
        Duration::from_millis(100),
    );

    executor.schedule(
        |_executor, log| {
            log.append("50ms");
        },
        Duration::from_millis(50),
    );

    executor.schedule(
        |_executor, log| {
            log.append("200ms");
        },
        Duration::from_millis(200),
    );

    // Advance time to run all tasks.
    fake_clock.advance(Duration::from_millis(200));

    // Flush the executor to ensure all tasks have run.
    executor.call(|_executor, _log| {}).await;

    assert_eq!(log.get_entries(), vec!["50ms", "100ms", "200ms"]);

    // Advance a long time (10 years) and verify that the never-due task is
    // not considered due.
    fake_clock.advance(Duration::from_secs(10 * 365 * 24 * 60 * 60));
    executor.call(|_executor, _log| {}).await;
    assert_eq!(log.get_entries(), vec!["50ms", "100ms", "200ms"]);
}

#[tokio::test]
async fn test_cancel_with_fake_clock() {
    // Test plan: Test cancellation with a fake clock.
    let log = TestLog::new();
    let fake_clock = FakeClock::create(SystemTime::now());
    let executor = create_and_start_executor(log.clone(), fake_clock.clone().into_clock());

    let handle = executor.schedule(
        |_executor, log| {
            log.append("Cancelled");
        },
        Duration::from_secs(10),
    );

    executor.schedule(
        |_executor, log| {
            log.append("Not cancelled");
        },
        Duration::from_secs(10),
    );

    // Cancel the first task.
    handle.cancel();

    // Advance time.
    fake_clock.advance(Duration::from_secs(10));

    // Flush the executor to ensure any pending tasks have run.
    executor.call(|_executor, _log| {}).await;

    assert_eq!(log.get_entries(), vec!["Not cancelled"]);
}

#[tokio::test]
async fn test_fairness_randomized() {
    // Test plan: Enqueue many tasks with random delays from multiple concurrent threads
    // and verify the fairness guarantee: if task1 is enqueued with delay X before task2
    // is enqueued with delay Y, and X <= Y, then task1 runs before task2.
    use std::sync::atomic::AtomicU64;

    #[derive(Clone)]
    struct TaskInfo {
        id: usize,
        enqueue_start: Instant,
        enqueue_end: Instant,
        delay: Duration,
    }

    let log = TestLog::new();
    let executor = Arc::new(create_and_start_executor(log.clone(), system_clock()));

    // Track task info and execution order.
    let task_infos = Arc::new(Mutex::new(Vec::new()));
    let execution_order = Arc::new(Mutex::new(Vec::new()));
    let next_id = Arc::new(AtomicU64::new(0));

    // Spawn multiple concurrent tasks that enqueue work.
    let num_enqueuers = 5;
    let tasks_per_enqueuer = 20;
    let mut handles = vec![];

    for _ in 0..num_enqueuers {
        let executor = executor.clone();
        let task_infos = task_infos.clone();
        let execution_order = execution_order.clone();
        let next_id = next_id.clone();

        let handle = tokio::spawn(async move {
            for _ in 0..tasks_per_enqueuer {
                let id = next_id.fetch_add(1, Ordering::SeqCst) as usize;
                let delay = Duration::from_millis((id % 50) as u64);

                // Capture time BEFORE starting enqueue.
                let enqueue_start = Instant::now();

                // Enqueue the task.
                let execution_order = execution_order.clone();
                executor.schedule(
                    move |_executor, _log| {
                        execution_order.lock().unwrap().push(id);
                    },
                    delay,
                );

                // Capture time AFTER enqueue completes.
                // Task A is enqueued before task B if A.enqueue_end <= B.enqueue_start.
                let enqueue_end = Instant::now();

                // Record task info.
                task_infos.lock().unwrap().push(TaskInfo {
                    id,
                    enqueue_start,
                    enqueue_end,
                    delay,
                });

                // Small delay to spread out enqueueing.
                tokio::time::sleep(Duration::from_micros(100)).await;
            }
        });
        handles.push(handle);
    }

    // Wait for all enqueuers to finish.
    for handle in handles {
        handle.await.unwrap();
    }

    // Wait for all tasks to execute.
    let total_tasks = num_enqueuers * tasks_per_enqueuer;
    {
        let execution_order = execution_order.clone();
        wait_for(
            move || execution_order.lock().unwrap().len() == total_tasks,
            &format!("all {} tasks to execute", total_tasks),
        )
        .await;
    }

    // Verify fairness property.
    let task_infos = task_infos.lock().unwrap();
    let execution_order = execution_order.lock().unwrap();

    // Build a map from task id to execution position.
    let mut execution_position = HashMap::new();
    for (pos, &id) in execution_order.iter().enumerate() {
        execution_position.insert(id, pos);
    }

    // Check fairness: if task1 was enqueued before task2 and has <= desired
    // execution time, then task1 should have executed before task2.
    // Task A is enqueued before task B if A.enqueue_end <= B.enqueue_start.
    let mut violations = 0;
    for i in 0..task_infos.len() {
        for j in 0..task_infos.len() {
            if i == j {
                continue;
            }

            let task1 = &task_infos[i];
            let task2 = &task_infos[j];

            // If task1 was enqueued strictly before task2.
            if task1.enqueue_end < task2.enqueue_start {
                // And task1's desired execution time <= task2's desired execution time.
                let task1_exec_time = task1.enqueue_end + task1.delay;
                let task2_exec_time = task2.enqueue_start + task2.delay;

                if task1_exec_time <= task2_exec_time {
                    // Then task1 should have executed before task2.
                    let pos1 = execution_position[&task1.id];
                    let pos2 = execution_position[&task2.id];

                    if pos1 > pos2 {
                        violations += 1;
                    }
                }
            }
        }
    }

    assert_eq!(violations, 0, "Found {} fairness violations", violations);
}

#[tokio::test]
async fn test_executor_dropped_with_pending_schedule() {
    // Test plan: Drop the executor with a pending scheduled task and verify
    // the task does not run. Note: This test uses system_clock because it's
    // testing the behavior of dropping the executor before real-time scheduling
    // occurs.
    let ran = Arc::new(AtomicBool::new(false));

    {
        let log = TestLog::new();
        let executor = create_and_start_executor(log.clone(), system_clock());

        // Schedule a task with a long delay.
        let ran_clone = ran.clone();
        executor.schedule(
            move |_executor, log| {
                ran_clone.store(true, Ordering::SeqCst);
                log.append("Scheduled");
            },
            Duration::from_secs(10),
        );

        // Executor is dropped here before the delay expires. The runner will
        // exit because the channel is closed, preventing the task from running.
    }

    // Give a small amount of time for the runner to exit.
    tokio::time::sleep(Duration::from_millis(50)).await;

    // Verify the task did not run.
    assert!(!ran.load(Ordering::SeqCst));
}

#[tokio::test]
async fn test_executor_dropped_with_scheduled_tasks() {
    // Test plan: Schedule multiple tasks with delays, drop the executor
    // immediately, and verify the scheduled tasks do not run. Note: This test
    // uses system_clock because it's testing the behavior of dropping the
    // executor before real-time scheduling occurs.
    let counter = Arc::new(AtomicUsize::new(0));

    {
        let log = TestLog::new();
        let executor = create_and_start_executor(log.clone(), system_clock());

        // Schedule delayed tasks with long delays.
        for i in 0..5 {
            let counter_clone = counter.clone();
            executor.schedule(
                move |_executor, log| {
                    counter_clone.fetch_add(1, Ordering::SeqCst);
                    log.append(format!("Schedule-{}", i));
                },
                Duration::from_secs(10 + i as u64),
            );
        }

        // Executor is dropped here before any scheduled tasks can run. The
        // runner will exit because the channel is closed.
    }

    // Give a small amount of time for the runner to exit.
    tokio::time::sleep(Duration::from_millis(50)).await;

    // Verify no scheduled tasks ran.
    assert_eq!(counter.load(Ordering::SeqCst), 0);
}

#[tokio::test]
async fn test_executor_dropped_with_cancelled_handle() {
    // Test plan: Verify that if a handle is cancelled after the executor is
    // dropped, no panic occurs and it's a safe no-op.=
    let handle = {
        let log = TestLog::new();
        let executor = create_and_start_executor(log.clone(), system_clock());
        executor.schedule(
            |_executor, log| {
                log.append("Task");
            },
            Duration::from_millis(100),
        )
    }; // Executor is dropped here.

    // Attempt to cancel after the executor is dropped. If this causes a panic,
    // it will happen synchronously, so no wait is needed.
    handle.cancel();

    // Test passes if no panic occurs.
}

#[tokio::test]
async fn test_self_scheduling_periodic_task() {
    // Test plan: A task that re-schedules itself should create a periodic pattern
    // until a termination condition is met.
    let log = TestLog::new();
    let fake_clock = FakeClock::create(SystemTime::now());
    let executor = create_and_start_executor(log.clone(), fake_clock.clone().into_clock());
    let counter = Arc::new(AtomicUsize::new(0));

    // Helper to schedule the repeating task.
    fn schedule_repeating_task(
        executor: &SequentialExecutor<TestLog>,
        counter: Arc<AtomicUsize>,
        max_runs: usize,
    ) {
        executor.schedule(
            move |executor, log| {
                let count = counter.fetch_add(1, Ordering::SeqCst);
                log.append(format!("Run-{}", count));

                // Reschedule if we haven't hit the limit.
                if count + 1 < max_runs {
                    schedule_repeating_task(executor, counter.clone(), max_runs);
                }
            },
            Duration::from_millis(10),
        );
    }

    schedule_repeating_task(&executor, counter.clone(), 5);

    // Advance time to run all scheduled tasks.
    for _ in 0..5 {
        fake_clock.advance(Duration::from_millis(10));
        // Flush the executor to process tasks.
        executor.call(|_executor, _log| {}).await;
    }

    assert_eq!(log.get_entries(), vec!["Run-0", "Run-1", "Run-2", "Run-3", "Run-4"]);
    assert_eq!(counter.load(Ordering::SeqCst), 5);
}

#[tokio::test]
async fn test_self_scheduling_task_chain() {
    // Test plan: A task schedules another task, which schedules another, forming a chain.
    let log = TestLog::new();
    let executor = create_and_start_executor(log.clone(), system_clock());

    executor.execute(|executor, log| {
        log.append("First");
        executor.schedule(
            |executor, log| {
                log.append("Second");
                executor.schedule(
                    |_executor, log| {
                        log.append("Third");
                    },
                    Duration::from_millis(10),
                );
            },
            Duration::from_millis(10),
        );
    });

    // Wait for all tasks to complete.
    log.wait_for_entries(vec!["First", "Second", "Third"]).await;
}

#[tokio::test]
async fn test_self_scheduling_multiple_branches() {
    // Test plan: A task that schedules multiple other tasks (fan-out pattern).
    let log = TestLog::new();
    let executor = create_and_start_executor(log.clone(), system_clock());

    executor.execute(|executor, log| {
        log.append("Parent");

        // Schedule three child tasks.
        for i in 0..3 {
            executor.schedule(
                move |_executor, log| {
                    log.append(format!("Child-{}", i));
                },
                Duration::from_millis(10 * (i as u64 + 1)),
            );
        }
    });

    // Wait for all tasks to complete.
    log.wait_for_entries(vec!["Parent", "Child-0", "Child-1", "Child-2"])
        .await;
}

#[tokio::test]
async fn test_self_scheduling_variable_intervals() {
    // Test plan: A task that re-schedules itself with variable intervals.
    let log = TestLog::new();
    let fake_clock = FakeClock::create(SystemTime::now());
    let executor = create_and_start_executor(log.clone(), fake_clock.clone().into_clock());

    fn schedule_with_backoff(
        executor: &SequentialExecutor<TestLog>,
        iteration: usize,
        max_iterations: usize,
    ) {
        if iteration >= max_iterations {
            return;
        }

        let delay = Duration::from_millis(10 * (1 << iteration)); // Exponential backoff
        executor.schedule(
            move |executor, log| {
                log.append(format!("Iter-{}", iteration));
                schedule_with_backoff(executor, iteration + 1, max_iterations);
            },
            delay,
        );
    }

    schedule_with_backoff(&executor, 0, 4);

    // Advance time to run all scheduled tasks (10 + 20 + 40 + 80 = 150ms)
    fake_clock.advance(Duration::from_millis(10));
    executor.call(|_executor, _log| {}).await;
    fake_clock.advance(Duration::from_millis(20));
    executor.call(|_executor, _log| {}).await;
    fake_clock.advance(Duration::from_millis(40));
    executor.call(|_executor, _log| {}).await;
    fake_clock.advance(Duration::from_millis(80));
    executor.call(|_executor, _log| {}).await;

    assert_eq!(log.get_entries(), vec!["Iter-0", "Iter-1", "Iter-2", "Iter-3"]);
}

#[tokio::test]
async fn test_self_scheduling_stops_on_executor_drop() {
    // Test plan: Verify that self-scheduling stops when executor is dropped.
    let ran_count = Arc::new(AtomicUsize::new(0));
    let fake_clock = FakeClock::create(SystemTime::now());

    {
        let log = TestLog::new();
        let executor = create_and_start_executor(log.clone(), fake_clock.clone().into_clock());

        fn schedule_infinite(executor: &SequentialExecutor<TestLog>, ran_count: Arc<AtomicUsize>) {
            executor.schedule(
                move |executor, log| {
                    let count = ran_count.fetch_add(1, Ordering::SeqCst);
                    log.append(format!("Run-{}", count));
                    // Reschedule unconditionally - would run forever if not stopped.
                    schedule_infinite(executor, ran_count.clone());
                },
                Duration::from_millis(10),
            );
        }

        schedule_infinite(&executor, ran_count.clone());

        // Let it run a few times.
        for _ in 0..5 {
            fake_clock.advance(Duration::from_millis(10));
            tokio::task::yield_now().await;
        }

        // Executor drops here. Drop calls abort() on the runner task, which
        // sends an abort signal (asynchronously). The abort takes effect when
        // we yield below in the single-threaded runtime.
    }

    let count_at_drop = ran_count.load(Ordering::SeqCst);
    assert!(count_at_drop > 0, "Should have run at least once");

    // Advance time and yield to allow the abort to take effect, then verify
    // no more tasks run.
    for _ in 0..10 {
        fake_clock.advance(Duration::from_millis(10));
        tokio::task::yield_now().await;
    }
    let final_count = ran_count.load(Ordering::SeqCst);

    assert_eq!(count_at_drop, final_count, "No new tasks should run after executor drop");
}

#[tokio::test]
async fn test_self_scheduling_ordering_with_immediate_reschedule() {
    // Test plan: Verify that tasks scheduled with zero delay maintain ordering.
    let log = TestLog::new();
    let executor = create_and_start_executor(log.clone(), system_clock());

    executor.execute(|executor, log| {
        log.append("A");
        executor.schedule(
            |_executor, log| {
                log.append("B");
            },
            Duration::ZERO,
        );
        executor.schedule(
            |_executor, log| {
                log.append("C");
            },
            Duration::ZERO,
        );
    });

    // Should execute in order: A, then B, then C (FIFO for zero delay)
    log.wait_for_entries(vec!["A", "B", "C"]).await;
}

#[tokio::test]
async fn test_self_scheduling_mixed_with_external_tasks() {
    // Test plan: Mix tasks scheduled externally with tasks that schedule additional work.
    let log = TestLog::new();
    let executor = create_and_start_executor(log.clone(), system_clock());

    // External task 1
    executor.execute(|_executor, log| {
        log.append("External-1");
    });

    // Task that schedules more work
    executor.execute(|executor, log| {
        log.append("Recursive-Start");
        executor.schedule(
            |_executor, log| {
                log.append("Recursive-Child");
            },
            Duration::from_millis(10),
        );
    });

    // External task 2
    executor.schedule(
        |_executor, log| {
            log.append("External-2");
        },
        Duration::from_millis(20),
    );

    // Wait for all tasks to complete.
    log.wait_for_entries(vec!["External-1", "Recursive-Start", "Recursive-Child", "External-2"])
        .await;
}

#[tokio::test]
async fn test_self_scheduling_deep_nesting() {
    // Test plan: Test deeply nested self-scheduling calls don't cause issues.
    let log = TestLog::new();
    let fake_clock = FakeClock::create(SystemTime::now());
    let executor = create_and_start_executor(log.clone(), fake_clock.clone().into_clock());

    fn schedule_nested(executor: &SequentialExecutor<TestLog>, depth: usize, max_depth: usize) {
        executor.schedule(
            move |executor, log| {
                log.append(format!("Depth-{}", depth));
                if depth < max_depth {
                    schedule_nested(executor, depth + 1, max_depth);
                }
            },
            Duration::from_millis(1),
        );
    }

    schedule_nested(&executor, 0, 10);

    // Advance time to run all scheduled tasks.
    for _ in 0..=10 {
        fake_clock.advance(Duration::from_millis(1));
        executor.call(|_executor, _log| {}).await;
    }

    let mut expected_entries = Vec::new();
    for i in 0..=10 {
        expected_entries.push(format!("Depth-{}", i));
    }

    assert_eq!(
        log.get_entries(),
        expected_entries
            .iter()
            .map(|s| s.as_str())
            .collect::<Vec<_>>()
    );
}

#[tokio::test]
async fn test_call_with_delay_dropped_executor() {
    // Test plan: drop the executor while there is an outstanding
    // call_with_delay. We don't expect the future to complete
    // in this case.
    let log = Arc::new(TestLog::new());
    let fake_clock = FakeClock::create(SystemTime::now());
    let executor = create_and_start_executor(log.clone(), fake_clock.clone().into_clock());

    let log_clone = log.clone();
    let future = executor.call_with_delay(
        move |_executor, _log| {
            log_clone.append("Ran");
        },
        Duration::from_millis(100),
    );

    // Drop the executor.
    drop(executor);

    // Advance time past when the task would have run. And
    // yield to (best-effort) flush the runner and allow the
    // task to run.
    fake_clock.advance(Duration::from_millis(150));
    tokio::task::yield_now().await;

    // Verify the task did not run.
    assert_eq!(log.get_entries(), Vec::<String>::new());
    assert!(future.now_or_never().is_none());
}

#[tokio::test]
async fn test_looping() {
    // Test plan: verify that it is possible to implement bounded recursion
    // using `call`. In this example, we will simulate a component that reads
    // and memoizes file contents. When it receives a request for a file batch,
    // it checks if all files are present. If they are, the corresponding file
    // contents are yielded immediately. Otherwise, the next missing file is
    // read and the request is repeated.

    type Executor = SequentialExecutor<HashMap<String, String>>;
    struct FileCache {
        executor: Executor,
    }
    impl FileCache {
        fn new() -> Self {
            let executor = create_and_start_executor(HashMap::new(), system_clock());
            Self { executor }
        }

        async fn read_files(&self, paths: &[&str]) -> Vec<String> {
            // Create a copy of the file paths we're reading that we can pass
            // to the executor.
            let paths_copy: Vec<String> = paths.iter().map(|path| path.to_string()).collect();

            // When accessing the state, we'll either find all of the requested
            // contents, or we'll load the next missing file.
            enum Result {
                Contents(Vec<String>),
                ReadNextPath(String),
            }
            match self
                .executor
                .call(move |_executor, cache| -> Result {
                    let mut contents: Vec<String> = vec![];
                    for path in paths_copy.iter() {
                        if let Some(path_contents) = cache.get(path) {
                            contents.push(path_contents.clone());
                        } else {
                            return Result::ReadNextPath(path.clone());
                        }
                    }
                    Result::Contents(contents)
                })
                .await
            {
                Result::Contents(contents) => contents,
                Result::ReadNextPath(path) => {
                    let path_contents = Self::read_contents(&path).await;
                    // Cache the results then repeat!
                    self.executor
                        .call(move |_executor, cache| {
                            cache.insert(path, path_contents);
                        })
                        .await;
                    // We need to box before recursion.
                    Box::pin(self.read_files(paths)).await
                }
            }
        }

        /// Simulates fs read.
        async fn read_contents(path: &str) -> String {
            sleep(Duration::from_millis(1)).await;
            format!("contents of {}", path)
        }
    }

    let cache = FileCache::new();
    assert_eq!(cache.read_files(&["foo", "bar"]).await, vec!["contents of foo", "contents of bar"]);
}

#[tokio::test]
async fn test_overdue_task_runs_immediately_after_long_task() {
    // Test plan: Verify that if a task takes a long time to execute, the next
    // overdue task runs immediately after the long task. This is to prevent
    // a potential bug where the internal `now` is not updated before the next
    // overdue task is scheduled.
    let log = TestLog::new();
    let fake_clock = FakeClock::create(SystemTime::now());
    let executor = create_and_start_executor(log.clone(), fake_clock.clone().into_clock());

    // Schedule Task 2 to run 500ms after now.
    executor.schedule(
        |_executor, log| {
            log.append("task 2");
        },
        Duration::from_millis(500),
    );

    // Execute Task 1 immediately, internally it advances the clock by 1000ms, to
    // simulate the task taking 1000ms to execute.
    let fake_clock_clone = fake_clock.clone();
    executor.execute(move |_executor, log| {
        fake_clock_clone.advance(Duration::from_secs(1));
        log.append("task 1")
    });

    // Without further advancing the clock, task 1 and 2 should run in order.
    log.wait_for_entries(vec!["task 1", "task 2"]).await;
}

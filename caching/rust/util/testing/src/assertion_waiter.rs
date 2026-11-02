use std::sync::Arc;
use std::time::Duration;

use futures_util::future::FutureExt;
use tokio::time::Instant;
use caching_rust_util::prefix_logger::PrefixLogger;
use caching_rust_util::{prefix_error, prefix_info};

/// A utility that helps in waiting for assertions to be satisfied. Similar to eventually-style
/// helpers, but also periodically logs assertion failures with the given log prefix tag to
/// facilitate debugging when tests fail.
///
/// # Examples
///
/// ## Synchronous condition with `await_condition`
///
/// ```
/// # use caching_rust_util_testing::assertion_waiter::AssertionWaiter;
/// # use std::sync::Arc;
/// # use std::sync::atomic::{AtomicUsize, Ordering};
/// #
/// # tokio::runtime::Runtime::new().unwrap().block_on(async {
/// let counter = Arc::new(AtomicUsize::new(0));
/// let counter_clone = Arc::clone(&counter);
///
/// // Spawn a task that increments the counter after some delay.
/// tokio::spawn(async move {
///     tokio::time::sleep(std::time::Duration::from_millis(100)).await;
///     counter_clone.store(5, Ordering::SeqCst);
/// });
///
/// // Wait for the counter to reach 5 using synchronous condition.
/// AssertionWaiter::new("expected counter value")
///     .await_condition(|| {
///         let value = counter.load(Ordering::SeqCst);
///         assert_eq!(value, 5, "Expected counter to be 5, got {}", value);
///     })
///     .await;
/// # });
/// ```
///
/// ## Async condition with `await_async_condition`
///
/// ```
/// # use caching_rust_util_testing::assertion_waiter::AssertionWaiter;
/// # use std::sync::Arc;
/// # use std::sync::atomic::{AtomicBool, Ordering};
/// #
/// # tokio::runtime::Runtime::new().unwrap().block_on(async {
/// let ready = Arc::new(AtomicBool::new(false));
/// let ready_clone = Arc::clone(&ready);
///
/// tokio::spawn(async move {
///     tokio::time::sleep(std::time::Duration::from_millis(100)).await;
///     ready_clone.store(true, Ordering::SeqCst);
/// });
///
/// // Wait for async condition that fetches state on each retry.
/// let ready_check = Arc::clone(&ready);
/// AssertionWaiter::new("wait for async response")
///     .await_async_condition(|| async {
///         // Simulate async operation (e.g., network call)
///         tokio::time::sleep(std::time::Duration::from_millis(1)).await;
///         assert!(ready_check.load(Ordering::SeqCst), "Should be ready");
///     })
///     .await;
/// # });
/// ```
pub struct AssertionWaiter {
    logger: PrefixLogger,
    timeout: Duration,
    poll_interval: Duration,
    log_interval: Duration,
}

impl AssertionWaiter {
    /// Creates a new AssertionWaiter with the given log prefix.
    ///
    /// # Arguments
    ///
    /// * `log_prefix` - Prefix included when logging assertion errors observed before timeout.
    ///
    /// Default values:
    /// * `timeout`: 5 seconds
    /// * `poll_interval`: 5 milliseconds
    /// * `log_interval`: 3 seconds
    pub fn new(log_prefix: impl Into<Arc<str>>) -> Self {
        Self {
            logger: PrefixLogger::new("AssertionWaiter", log_prefix),
            timeout: Duration::from_secs(5),
            poll_interval: Duration::from_millis(5),
            log_interval: Duration::from_secs(3),
        }
    }

    /// Sets a custom timeout for this waiter.
    pub fn with_timeout(mut self, timeout: Duration) -> Self {
        self.timeout = timeout;
        self
    }

    /// Sets a custom poll interval for this waiter.
    pub fn with_poll_interval(mut self, poll_interval: Duration) -> Self {
        self.poll_interval = poll_interval;
        self
    }

    /// Sets a custom log interval for this waiter.
    pub fn with_log_interval(mut self, log_interval: Duration) -> Self {
        self.log_interval = log_interval;
        self
    }

    /// Invokes the given async function repeatedly until it succeeds or until the timeout.
    ///
    /// Like eventually-style helpers, but this method also periodically logs assertion failures
    /// with the given log prefix tag to facilitate debugging when tests fail.
    ///
    /// # Arguments
    ///
    /// * `fun` - The async function to be invoked. Should contain assertions that will eventually pass.
    ///
    /// # Panics
    ///
    /// Panics if the function does not succeed within the timeout period, with the last error
    /// message from the function.
    pub async fn await_async_condition<F, Fut>(&self, mut fun: F)
    where
        F: FnMut() -> Fut,
        Fut: std::future::Future<Output = ()>,
    {
        prefix_info!(self.logger, "Waiting");

        let start_time: Instant = Instant::now();
        let mut last_log_time: Instant = start_time;

        loop {
            let elapsed: Duration = start_time.elapsed();

            // Try to run the async function.
            let result: Result<(), Box<dyn std::any::Any + Send>> =
                std::panic::AssertUnwindSafe(fun()).catch_unwind().await;

            match result {
                Ok(_) => {
                    // Success - assertion passed.
                    return;
                }
                Err(panic_info) => {
                    // Extract error message from panic.
                    let error_msg: String = extract_panic_message(&panic_info);

                    // Check if we've exceeded the timeout.
                    if elapsed >= self.timeout {
                        prefix_error!(
                            self.logger,
                            "Timeout after {:?}. Last error: {}",
                            self.timeout,
                            error_msg
                        );
                        panic!("Timeout after {:?}. Last error: {}", self.timeout, error_msg);
                    }

                    // Periodically log assertion errors so we can see the progression in test logs.
                    let now: Instant = Instant::now();
                    if now.duration_since(last_log_time) >= self.log_interval {
                        prefix_info!(self.logger, "Still waiting: {}", error_msg);
                        last_log_time = now;
                    }

                    // Sleep before next poll.
                    tokio::time::sleep(self.poll_interval).await;
                }
            }
        }
    }

    /// Invokes the given synchronous function repeatedly until it succeeds or until the timeout.
    ///
    /// Like eventually-style helpers, but this method also periodically logs assertion failures
    /// with the given log prefix tag to facilitate debugging when tests fail.
    ///
    /// # Arguments
    ///
    /// * `fun` - The synchronous function to be invoked. Should contain assertions that will eventually pass.
    ///
    /// # Panics
    ///
    /// Panics if the function does not succeed within the timeout period, with the last error
    /// message from the function.
    ///
    /// Corresponds to Scala's await method (lines 53-94)
    pub async fn await_condition<F>(&self, mut fun: F)
    where
        F: FnMut(),
    {
        prefix_info!(self.logger, "Waiting");

        let start_time: Instant = Instant::now();
        let mut last_log_time: Instant = start_time;

        loop {
            let elapsed: Duration = start_time.elapsed();

            // Try to run the function.
            let result: Result<(), Box<dyn std::any::Any + Send>> =
                std::panic::catch_unwind(std::panic::AssertUnwindSafe(&mut fun));

            match result {
                Ok(_) => {
                    // Success - assertion passed.
                    return;
                }
                Err(panic_info) => {
                    // Extract error message from panic.
                    let error_msg: String = extract_panic_message(&panic_info);

                    // Check if we've exceeded the timeout.
                    if elapsed >= self.timeout {
                        prefix_error!(
                            self.logger,
                            "Timeout after {:?}. Last error: {}",
                            self.timeout,
                            error_msg
                        );
                        panic!("Timeout after {:?}. Last error: {}", self.timeout, error_msg);
                    }

                    // Periodically log assertion errors so we can see the progression in test logs.
                    let now: Instant = Instant::now();
                    if now.duration_since(last_log_time) >= self.log_interval {
                        prefix_info!(self.logger, "Still waiting: {}", error_msg);
                        last_log_time = now;
                    }

                    // Sleep before next poll.
                    tokio::time::sleep(self.poll_interval).await;
                }
            }
        }
    }
}

/// Extracts a human-readable error message from a panic payload.
///
/// Handles the most common panic types (String and &str) and returns a fallback
/// message for unknown types.
fn extract_panic_message(panic_info: &Box<dyn std::any::Any + Send>) -> String {
    if let Some(s) = panic_info.downcast_ref::<String>() {
        s.clone()
    } else if let Some(s) = panic_info.downcast_ref::<&str>() {
        (*s).to_string()
    } else {
        "Unknown panic".to_string()
    }
}

use std::any::Any;
use std::future::Future;
use std::panic::{resume_unwind, AssertUnwindSafe};
use std::time::Duration;

use futures::FutureExt;
use tokio::time::error::Elapsed;
use tokio::time::{sleep, timeout};

/// Repeatedly runs an asynchronous closure until it completes without panicking,
/// or times out. If all attempts panic, the last panic is rethrown.
///
/// # Arguments
/// - `f`: An asynchronous closure to run.
/// - `timeout_duration`: Optional max time to wait (default: 1s).
/// - `poll_interval`: Optional delay between retries (default: 100ms).
///
/// # Panics
/// - Throws if the closure timed out before succeeding or panicking.
/// - Rethrows the last panic if the closure never succeeds within the timeout.
///
/// # Example
///
/// ```
/// eventually(
///     || async {
///         assert!(...);
///     },
///     Some(Duration::from_secs(1)),
///     Some(Duration::from_millis(100)),
/// )
/// .await;
/// ```
pub async fn eventually<F, Fut>(
    mut f: F,
    timeout_duration: Option<Duration>,
    poll_interval: Option<Duration>,
) where
    F: FnMut() -> Fut,
    Fut: Future<Output = ()>,
{
    let timeout_duration: Duration = timeout_duration.unwrap_or(Duration::from_secs(1));
    let poll_interval: Duration = poll_interval.unwrap_or(Duration::from_millis(100));
    let mut last_panic: Option<Box<dyn Any + Send + 'static>> = None;

    let result: Result<(), Elapsed> = timeout(timeout_duration, async {
        loop {
            let result: Result<(), Box<dyn Any + Send + 'static>> =
                AssertUnwindSafe(f()).catch_unwind().await;

            match result {
                Ok(()) => return,
                Err(panic) => {
                    last_panic = Some(panic);
                }
            }

            sleep(poll_interval).await;
        }
    })
    .await;

    match result {
        Ok(()) => {}
        Err(_) => {
            if let Some(panic) = last_panic {
                resume_unwind(panic);
            } else {
                panic!("Timed out without panic after {:?}", timeout_duration);
            }
        }
    }
}

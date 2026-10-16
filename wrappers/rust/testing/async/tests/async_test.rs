use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::Duration;

use tokio::time::sleep;
use universe_raf_testing_async::eventually;

#[tokio::test(start_paused = true)]
async fn succeeds_after_retry() {
    // Test plan: Verify that `eventually` succeeds after the closure panics on
    // early attempts. Simulate this by panicking until a counter reaches 2. Verify
    // the call completes without panicking.
    let counter: Arc<AtomicUsize> = Arc::new(AtomicUsize::new(0));
    eventually(
        move || {
            let c: Arc<AtomicUsize> = Arc::clone(&counter);
            async move {
                let count: usize = c.fetch_add(1, Ordering::SeqCst);
                if count < 2 {
                    panic!("not ready");
                }
            }
        },
        None,
        None,
    )
    .await;
}

#[tokio::test(start_paused = true)]
#[should_panic(expected = "failing repeatedly")]
async fn panics_if_never_succeeds() {
    // Test plan: Verify that `eventually` rethrows the last panic when the closure
    // never succeeds within the timeout. Simulate this by panicking on every
    // attempt with a fixed message. Verify the call panics with that message.
    eventually(
        || async {
            panic!("failing repeatedly");
        },
        None,
        None,
    )
    .await;
}

#[tokio::test(start_paused = true)]
#[should_panic(expected = "Timed out without panic after 1s")]
async fn panics_if_timeouts_without_panic() {
    // Test plan: Verify that `eventually` panics with a timeout message when the
    // closure neither succeeds nor panics before the deadline. Simulate this by
    // sleeping longer than the default timeout. Verify the call panics with
    // "Timed out without panic after 1s".
    eventually(
        || async {
            sleep(Duration::from_secs(2)).await;
        },
        None,
        None,
    )
    .await;
}

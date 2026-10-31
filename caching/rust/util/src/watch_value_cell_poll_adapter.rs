//! Polling adapter for [`WatchCell`]: populates the cell from a producer that only supports
//! polling for changes.
//!
//! See [`WatchValueCellPollAdapter`] for usage.

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::{Arc, Weak};
use std::time::Duration;

use tokio::task::AbortHandle;
use tonic::Status;

use crate::clock::Clock;
use crate::panic_util::extract_panic_message;
use crate::prefix_logger::PrefixLogger;
use crate::watch_cell::{Ref, Versioned, WatchCell, Watcher};
use crate::{caching_error_code, prefix_critical_alert};

/// Initial cell version before any poll has produced a value.
const INITIAL_VERSION: u64 = 0;

/// An abstraction for implementing a [`WatchCell`] on top of a producer that only supports
/// polling for determining when the underlying value has changed.
///
/// Polling starts at construction. The producer is invoked every `poll_interval` using `poller`;
/// `update` computes the next parsed value from a clone of the latest published value and the newly
/// polled raw value. The polling task is aborted when the adapter is dropped. The consumer can
/// register its subscription by cloning the [`Watcher`] returned from [`watcher`](Self::watcher).
/// If `R` contains shared interior mutability, `update` must not mutate previously published state
/// in place.
///
/// # Examples
///
/// ```
/// use std::time::Duration;
///
/// use tonic::Status;
/// use caching_rust_util::system_clock;
/// use caching_rust_util::watch_cell::Versioned;
/// use caching_rust_util::watch_value_cell_poll_adapter::WatchValueCellPollAdapter;
///
/// #[tokio::main(flavor = "current_thread")]
/// async fn main() {
///     let adapter = WatchValueCellPollAdapter::new(
///         system_clock(),
///         Some(0usize),
///         || Ok::<_, Status>("hello".to_string()),
///         |_, raw: String| Ok::<_, Status>(raw.len()),
///         Duration::from_millis(100),
///         "",
///     )
///     .expect("poll_interval is positive");
///
///     // Await the first poll and observe its result through the watcher.
///     let watcher = adapter.watcher();
///     let initial_version = watcher.get().version();
///     watcher.changed_since(initial_version).await.expect("adapter still alive");
///     assert_eq!(watcher.get().value_opt(), Some(&5));
/// }
/// ```
pub struct WatchValueCellPollAdapter<R>
where
    R: Clone + PartialEq + Send + Sync + 'static,
{
    /// Underlying cell holding the latest [`VersionedValue<R>`] produced by the polling task.
    cell: Arc<WatchCell<VersionedValue<R>>>,
    /// Aborts the polling task when the adapter is dropped.
    abort_handle: AbortHandle,
}

impl<R> WatchValueCellPollAdapter<R>
where
    R: Clone + PartialEq + Send + Sync + 'static,
{
    /// Creates an adapter with an optional initial value, then spawns a background task that
    /// polls the producer on the current Tokio runtime. The first poll runs immediately;
    /// subsequent polls run every `poll_interval`. The task is aborted when the returned adapter
    /// is dropped.
    ///
    /// `poller` and `update` are invoked serially on a Tokio worker, so they must be non-blocking.
    /// If either returns `Err`, the iteration is skipped (the cell retains its prior value) and
    /// polling continues. If `update` returns a value equal to the latest published value, the cell
    /// is not updated and existing watchers are not notified. `logger_prefix` is appended to the
    /// adapter's log prefix and surfaces as the `prefix` label on emitted metrics.
    ///
    /// # Errors
    /// Returns `INVALID_ARGUMENT` if `poll_interval` is zero.
    ///
    /// # Panics
    /// Panics if invoked outside a Tokio runtime.
    pub fn new<T>(
        clock: Clock,
        initial_value_opt: Option<R>,
        poller: impl Fn() -> Result<T, Status> + Send + Sync + 'static,
        update: impl Fn(Option<R>, T) -> Result<R, Status> + Send + Sync + 'static,
        poll_interval: Duration,
        logger_prefix: &str,
    ) -> Result<Self, Status>
    where
        T: Send + 'static,
    {
        if poll_interval.is_zero() {
            return Err(Status::invalid_argument(
                "WatchValueCellPollAdapter::new() requires a strictly positive poll_interval",
            ));
        }
        let initial = VersionedValue {
            value_opt: initial_value_opt,
            version: INITIAL_VERSION,
        };
        let logger = PrefixLogger::new("WatchValueCellPollAdapter", logger_prefix);
        let cell = Arc::new(WatchCell::new(initial));
        let task = tokio::spawn(polling_loop(
            Arc::downgrade(&cell),
            poller,
            update,
            poll_interval,
            clock,
            logger,
        ));
        Ok(Self {
            cell,
            abort_handle: task.abort_handle(),
        })
    }

    /// Returns a reference to the watcher for the underlying cell.
    pub fn watcher(&self) -> &Watcher<VersionedValue<R>> {
        self.cell.watcher()
    }

    /// Returns the current parsed value, or `None` if the cell is empty.
    pub fn value_opt(&self) -> Option<R> {
        self.cell.get().value_opt.clone()
    }
}

impl<R> Drop for WatchValueCellPollAdapter<R>
where
    R: Clone + PartialEq + Send + Sync + 'static,
{
    /// Aborts the background polling task. This is the sole mechanism that stops polling once
    /// [`new`](Self::new) has been called. `abort` cancels at the next `.await` point, so any
    /// in-flight `execute_poll` iteration (which is synchronous) runs to completion before the
    /// task stops.
    fn drop(&mut self) {
        self.abort_handle.abort();
    }
}

/// The wrapper has a monotonically increasing version to the parsed value so callers can wait for
/// updates with [`Watcher::changed_since`].
#[derive(Clone, Debug, PartialEq)]
pub struct VersionedValue<R> {
    value_opt: Option<R>,
    /// Monotonically increasing version. Strictly greater than the version of any
    /// previously-stored value.
    version: u64,
}

impl<R> VersionedValue<R> {
    /// Returns the parsed value, or `None` if no initial value was supplied at construction and
    /// no poll has produced a value yet.
    pub fn value_opt(&self) -> Option<&R> {
        self.value_opt.as_ref()
    }
}

impl<R: Send + Sync + 'static> Versioned for VersionedValue<R> {
    type Version = u64;

    fn version(&self) -> Self::Version {
        self.version
    }
}

/// Repeatedly polls the producer, computes each updated value, and writes the result into the cell
/// when it differs from the previous value. Catches per-iteration panics so a single bad poll
/// doesn't stop the schedule. Exits when the cell has been dropped (last strong reference gone),
/// which happens when the adapter is dropped and its abort takes effect.
async fn polling_loop<T, R>(
    cell_weak: Weak<WatchCell<VersionedValue<R>>>,
    poller: impl Fn() -> Result<T, Status> + Send + Sync + 'static,
    update: impl Fn(Option<R>, T) -> Result<R, Status> + Send + Sync + 'static,
    poll_interval: Duration,
    clock: Clock,
    logger: PrefixLogger,
) where
    T: Send + 'static,
    R: Clone + PartialEq + Send + Sync + 'static,
{
    loop {
        let Some(cell) = cell_weak.upgrade() else {
            return;
        };
        // Safety(UnwindSafe): execute_poll only reads from poller/update and writes to the cell
        // via a single watch::channel send.
        if let Err(payload) =
            catch_unwind(AssertUnwindSafe(|| execute_poll(&cell, &poller, &update)))
        {
            let message = extract_panic_message(&payload);
            // Throttle the log so a deterministic panic at a short `poll_interval` (e.g. a
            // poisoned `Mutex` captured by the user closure) does not flood the logs. The metric
            // counter still increments on every iteration.
            prefix_critical_alert!(
                logger,
                caching_error_code::WATCH_VALUE_CELL_POLL_ADAPTER_PANIC,
                log_every_n_sec = 30,
                "poll iteration panicked: {}",
                message
            );
        }
        // Drop the strong reference before sleeping so the cell can be dropped while we wait.
        drop(cell);
        clock.sleep(poll_interval).await;
    }
}

/// Executes a single poll operation. Skips the write if `poller` or `update` returns an error,
/// or if the new value is unchanged from the current value.
fn execute_poll<T, R>(
    cell: &WatchCell<VersionedValue<R>>,
    poller: &(impl Fn() -> Result<T, Status> + ?Sized),
    update: &(impl Fn(Option<R>, T) -> Result<R, Status> + ?Sized),
) where
    R: Clone + PartialEq + Send + Sync + 'static,
{
    let Ok(raw) = poller() else {
        return; // Skip if the poll fails.
    };

    let current: Ref<VersionedValue<R>> = cell.get();
    let previous_value_opt = current.value_opt.clone();
    let Ok(new_value) = update(previous_value_opt, raw) else {
        return; // Skip if the update fails.
    };
    if current.value_opt.as_ref() != Some(&new_value) {
        cell.set(VersionedValue {
            value_opt: Some(new_value),
            version: current.version + 1,
        });
    }
}

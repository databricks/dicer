use std::collections::{BTreeMap, HashMap};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant, SystemTime};

use futures::future::BoxFuture;
use scopeguard::guard;
use tokio::sync::Notify;
use caching_rust_util::internal_for_test::{clock_from_impl, ClockImpl};
use caching_rust_util::Clock;

// Ensure that `FakeClock` is `Send` and `Sync`.
static_assertions::assert_impl_all!(FakeClock: Send, Sync);

/// The internal state of the fake clock.
struct SleepState {
    /// The current simulated time (duration since creation).
    current_simulated_time: Duration,

    /// Priority queue of waiting tasks. Keyed by the simulated time when a task is scheduled to
    /// wake up, value is a list of wakers to notify when the time is reached.
    waiters: BTreeMap<Duration, Vec<Arc<Notify>>>,

    /// Tracks sleep reasons for test observability. Maps reason strings to statistics about
    /// `sleep()` calls with that reason. This enables tests to wait for specific sleeps to be
    /// registered without relying on timing. The map is not cleared when sleeps complete. This
    /// simplifies the implementation and is acceptable because it's only used in tests.
    sleep_reasons: HashMap<String, ReasonStats>,
}

/// Statistics for sleeps registered with a particular reason.
#[derive(Default)]
struct ReasonStats {
    /// The cumulative number of sleeps with this reason.
    total_count: usize,

    /// The number of currently active sleeps with this reason.
    sleeping_count: usize,
}

/// The internal implementation of the fake clock.
struct FakeClockImpl {
    /// Initial time of the clock.
    start_time: SystemTime,

    /// Initial monotonic instant time of the clock, used to synthesize valid `std::time::Instant`
    /// values for `instant()`.
    start_instant: Instant,

    /// Shared state, protected by a Mutex for thread safety.
    state: Arc<Mutex<SleepState>>,
}

/// A manual clock for deterministic testing in a multi-threaded runtime.
///
/// ## Why not use `tokio::time`?
///
/// While `tokio::time::pause()` allows for clock manipulation, it tightly couples the passage
/// of time to the scheduler's idleness, and is constrained to the current thread's runtime,
/// which introduces several challenges:
///
/// - **Busy-wait deadlocks**: If a background task never reaches an idle state (e.g.,
///   a loop that constantly yields but never sleeps), the clock will never advance.
/// - **Premature jumps**: The runtime automatically advances the clock to the next scheduled
///   timer as soon as all tasks are idle. This can cause unexpected behavior when precise control
///   over time increments is required.
/// - The runtime constraint makes it impossible to use paused time in multi-threaded tests.
///
/// # Caveat: "Teleportation" vs "Flow"
///
/// `FakeClock::advance(d)` does **not** simulate the continuous flow of time. It performs a
/// **discrete jump** from the current time `T` to `T+d`, and any tasks scheduled before `T+d` are
/// woken. For sequential sleeps, if a woken task immediately sleeps for `1s`, it calculates its
/// new deadline as `(T+d) + 1s`. It will **not** run again until another 1 second has passed.
///
/// ## Example
///
/// ```ignore
/// // A task that sleeps 5s, wakes, and sleeps 5s again.
/// tokio::spawn(async move {
///     clock.sleep(Duration::from_secs(5), None).await; // Deadline: 5s
///     println!("First");
///     clock.sleep(Duration::from_secs(5), None).await; // Deadline: Now + 5s
///     println!("Second");
/// });
///
/// // Incorrect assumption:
/// // "If I advance 10s, both sleeps will finish."
/// clock.advance(10s);
///
/// // Actual behavior:
/// // 1. Time jumps to 10s.
/// // 2. First sleep (Deadline 5s) wakes up.
/// // 3. Task runs, prints "First".
/// // 4. Task calls sleep(5s). New Deadline = 10s + 5s = 15s.
/// // 5. Task goes to sleep (Pending).
/// // 6. "Second" is NEVER printed until another 5 seconds have passed.
/// ```
#[derive(Clone)]
pub struct FakeClock {
    inner: Arc<FakeClockImpl>,
}

impl FakeClock {
    /// Creates a new `FakeClock` that starts at `start_time`.
    pub fn create(start_time: SystemTime) -> Self {
        Self {
            inner: Arc::new(FakeClockImpl {
                start_time,
                start_instant: Instant::now(),
                state: Arc::new(Mutex::new(SleepState {
                    current_simulated_time: Duration::ZERO,
                    waiters: BTreeMap::new(),
                    sleep_reasons: HashMap::new(),
                })),
            }),
        }
    }

    /// Manually advances the simulated clock by `duration`. Since `duration` is non-negative,
    /// time will never go backwards.
    pub fn advance(&self, duration: Duration) {
        // Get the ready tasks and remove them from the state. Wrapped in a block so lock is
        // released afterwards.
        let ready_tasks = {
            let mut state = self.inner.state.lock().unwrap();
            state.current_simulated_time += duration;

            let now = state.current_simulated_time;

            // `split_off` returns the keys including and after the cutoff, so this returns the
            // future tasks.
            let cutoff = now + Duration::from_nanos(1);
            let future_tasks = state.waiters.split_off(&cutoff);

            // Swap the future tasks back into the state, and take the ready tasks out.
            std::mem::replace(&mut state.waiters, future_tasks) // returns the ready tasks
        };

        // Notify all the ready tasks.
        for (_, notifies) in ready_tasks {
            for notify in notifies {
                notify.notify_one();
            }
        }
    }

    /// Returns the number of pending sleep waiters.
    ///
    /// Note that
    ///  - `sleep(Duration::MAX)` is also considered a pending waiter.
    ///  - When there are multiple components that have Clock, it can be tricky to reason about
    ///    the total number of pending waiters, consider using `sleep_reason_count` instead.
    pub fn pending_waiter_count(&self) -> usize {
        let state = self.inner.state.lock().unwrap();
        state.waiters.values().map(|v| v.len()).sum()
    }

    /// Returns the number of times `sleep()` has been called with the given `reason`.
    ///
    /// This is useful for test observability, verifying that a specific sleep has been
    /// registered without relying on timing. The count is cumulative and not decremented
    /// when sleeps complete.
    pub fn sleep_reason_count(&self, reason: &str) -> usize {
        let state = self.inner.state.lock().unwrap();
        state
            .sleep_reasons
            .get(reason)
            .map_or(0, |stats: &ReasonStats| stats.total_count)
    }

    /// Returns whether there are any in-flight sleeps with the given reason.
    pub fn is_sleeping_for_reason(&self, reason: &str) -> bool {
        let state = self.inner.state.lock().unwrap();
        state
            .sleep_reasons
            .get(reason)
            .is_some_and(|stats: &ReasonStats| stats.sleeping_count > 0)
    }

    /// Converts this FakeClock into a [`Clock`].
    pub fn into_clock(self) -> Clock {
        clock_from_impl(self.inner)
    }

    /// Similar to [`Clock::now`], but returns the current simulated time.
    pub fn now(&self) -> SystemTime {
        self.inner.now()
    }

    /// Similar to [`Clock::instant`], but returns the current simulated instant.
    pub fn instant(&self) -> Instant {
        self.inner.instant()
    }

    /// Similar to [`Clock::sleep`], but returns a future that waits for the current simulated
    /// time to be reached.
    pub fn sleep(&self, duration: Duration) -> BoxFuture<'static, ()> {
        self.inner.sleep(duration, None)
    }

    /// Similar to [`Clock::sleep_with_reason`], but returns a future that waits for the current
    /// simulated time to be reached.
    pub fn sleep_with_reason(&self, duration: Duration, reason: String) -> BoxFuture<'static, ()> {
        self.inner.sleep(duration, Some(reason))
    }
}

impl ClockImpl for FakeClockImpl {
    fn now(&self) -> SystemTime {
        let state = self.state.lock().unwrap();
        self.start_time + state.current_simulated_time // start time + fake offset
    }

    fn instant(&self) -> Instant {
        let state = self.state.lock().unwrap();
        self.start_instant + state.current_simulated_time // start instant + fake offset
    }

    fn sleep(&self, duration: Duration, reason: Option<String>) -> BoxFuture<'static, ()> {
        let notify = Arc::new(Notify::new());

        // Register the notification handle. Wrapped in a block so lock is released afterwards.
        {
            let mut state = self.state.lock().unwrap();
            let deadline: Duration = state.current_simulated_time.saturating_add(duration);

            // If the time has already passed, return immediately.
            if deadline <= state.current_simulated_time {
                return Box::pin(async {});
            }

            // Register the notification handle.
            state
                .waiters
                .entry(deadline)
                .or_default()
                .push(notify.clone());

            if let Some(reason) = reason.as_ref() {
                let stats: &mut ReasonStats =
                    state.sleep_reasons.entry(reason.clone()).or_default();
                stats.total_count += 1;
                stats.sleeping_count += 1;
            }
        }

        // This guard automatically decrements the sleeping count when the returned future is
        // dropped.
        let state = Arc::clone(&self.state);
        let decrement_guard = guard((), move |_| {
            if let Some(reason) = reason {
                let mut state: MutexGuard<SleepState> = state.lock().unwrap();
                let stats: &mut ReasonStats = state
                    .sleep_reasons
                    .get_mut(&reason)
                    .expect("Sleep reason should be registered");
                stats.sleeping_count -= 1;
            }
        });

        // Return a future that waits for the notification.
        Box::pin(async move {
            let _decrement_guard = decrement_guard;
            notify.notified_owned().await;
        })
    }
}

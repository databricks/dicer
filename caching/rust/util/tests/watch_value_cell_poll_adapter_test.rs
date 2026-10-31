use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime};

use tonic::Status;
use caching_rust_util::caching_error_code::{self, Severity};
use caching_rust_util::caching_errors;
use caching_rust_util::watch_cell::{Versioned, Watcher};
use caching_rust_util::watch_value_cell_poll_adapter::{
    VersionedValue,
    WatchValueCellPollAdapter,
};
use caching_rust_util_testing::assertion_waiter::AssertionWaiter;
use caching_rust_util_testing::fake_clock::FakeClock;

const POLL_INTERVAL: Duration = Duration::from_millis(20);

type RawValueMap = HashMap<String, String>;
type ParsedValueMap = HashMap<String, ParsedValue>;

#[derive(Clone, PartialEq, Eq, Debug)]
struct ParsedValue {
    value: String,
}

/// Parses a raw key/value map into a parsed map: prefixes each key with `parsed-` and wraps
/// each value in [`ParsedValue`]. Falls back to [`initial_parsed`] if any entry is malformed
/// (missing the `raw-` prefix).
fn parse_raw_value(raw: RawValueMap) -> Result<ParsedValueMap, Status> {
    let mut result = HashMap::new();
    for (raw_key, raw_value) in &raw {
        if !raw_key.starts_with("raw-") || !raw_value.starts_with("raw-") {
            return Ok(initial_parsed());
        }
        let parsed_key = format!("parsed-{}", raw_key.trim_start_matches("raw-"));
        result.insert(
            parsed_key,
            ParsedValue {
                value: raw_value.clone(),
            },
        );
    }
    Ok(result)
}

/// Returns the raw key/value map used as the starting fixture for the tests.
fn initial_raw() -> RawValueMap {
    HashMap::from([
        ("raw-key-1".to_string(), "raw-value-1".to_string()),
        ("raw-key-2".to_string(), "raw-value-2".to_string()),
        ("raw-key-3".to_string(), "raw-value-3".to_string()),
    ])
}

/// Returns the parsed counterpart of [`initial_raw`] -- the result of applying [`parse_raw_value`]
/// to it.
fn initial_parsed() -> ParsedValueMap {
    HashMap::from([
        (
            "parsed-key-1".to_string(),
            ParsedValue {
                value: "raw-value-1".to_string(),
            },
        ),
        (
            "parsed-key-2".to_string(),
            ParsedValue {
                value: "raw-value-2".to_string(),
            },
        ),
        (
            "parsed-key-3".to_string(),
            ParsedValue {
                value: "raw-value-3".to_string(),
            },
        ),
    ])
}

/// In-memory fake for the raw source the adapter polls; its value can be observed and mutated
/// from tests.
#[derive(Clone)]
struct FakeRawSource {
    state: Arc<Mutex<RawValueMap>>,
}

impl FakeRawSource {
    fn new(initial: RawValueMap) -> Self {
        Self {
            state: Arc::new(Mutex::new(initial)),
        }
    }

    /// Returns the source's current raw value.
    fn current(&self) -> RawValueMap {
        self.state.lock().unwrap().clone()
    }

    /// Updates an entry in the source's raw value.
    fn update(&self, key: impl Into<String>, value: impl Into<String>) {
        self.state.lock().unwrap().insert(key.into(), value.into());
    }

    /// Replaces the source's raw value.
    fn set(&self, value: RawValueMap) {
        *self.state.lock().unwrap() = value;
    }
}

/// Waits for `watcher` to observe `expected`, or panics on timeout.
async fn await_watcher_value<R>(
    watcher: &Watcher<VersionedValue<R>>,
    expected: R,
) -> VersionedValue<R>
where
    R: Clone + PartialEq + Send + Sync + 'static + std::fmt::Debug,
{
    let mut captured: Option<VersionedValue<R>> = None;
    AssertionWaiter::new(format!("watcher to observe {expected:?}"))
        .await_condition(|| {
            let snapshot = watcher.get();
            assert_eq!(snapshot.value_opt(), Some(&expected), "watcher value mismatch");
            captured = Some((*snapshot).clone());
        })
        .await;
    captured.expect("AssertionWaiter returned without capturing a snapshot")
}

/// Convenience wrapper that waits on the adapter's primary watcher.
async fn await_value<R>(adapter: &WatchValueCellPollAdapter<R>, expected: R) -> VersionedValue<R>
where
    R: Clone + PartialEq + Send + Sync + 'static + std::fmt::Debug,
{
    await_watcher_value(adapter.watcher(), expected).await
}

/// Waits until the polling task has registered a sleep waiter on the fake clock, advances the
/// clock by `POLL_INTERVAL` to trigger the next poll, then waits for that poll iteration to
/// complete (i.e. register the next sleep waiter). Returning only after the triggered iteration
/// has fully run prevents races where the test makes assertions before the polling task has
/// reached the next sleep.
async fn advance_one_poll(fake_clock: &FakeClock) {
    AssertionWaiter::new("polling task to register a sleep waiter")
        .await_condition(|| {
            assert!(
                fake_clock.pending_waiter_count() > 0,
                "polling task did not register a sleep waiter",
            );
        })
        .await;
    fake_clock.advance(POLL_INTERVAL);
    AssertionWaiter::new("triggered iteration to complete and re-register a sleep waiter")
        .await_condition(|| {
            assert!(
                fake_clock.pending_waiter_count() > 0,
                "polling task did not re-register a sleep waiter after the triggered iteration",
            );
        })
        .await;
}

/// Reads the `WATCH_VALUE_CELL_POLL_ADAPTER_PANIC` CRITICAL alert counter for the given
/// `logger_prefix`. Each panic test passes its own unique prefix to
/// [`WatchValueCellPollAdapter::new`] so the counter cell `(severity, error_code, prefix)`
/// observed here is attributable to that test's panics alone.
fn panic_alert_count(logger_prefix: &str) -> u64 {
    caching_errors::get_caching_error_counter_value_for_test(
        Severity::Critical,
        caching_error_code::WATCH_VALUE_CELL_POLL_ADAPTER_PANIC,
        logger_prefix,
    )
}

#[tokio::test]
async fn new_returns_err_for_zero_poll_interval() {
    // Test plan: Verify that constructing the adapter with a zero `poll_interval` returns an
    // INVALID_ARGUMENT `Status`. Verify this by passing `Duration::ZERO` and asserting the
    // returned `Result` is `Err` with the expected code.

    // Setup: Build an adapter with a zero `poll_interval`.
    let fake_clock = FakeClock::create(SystemTime::UNIX_EPOCH);
    let result: Result<WatchValueCellPollAdapter<ParsedValueMap>, Status> =
        WatchValueCellPollAdapter::new(
            fake_clock.into_clock(),
            Some(initial_parsed()),
            || Ok::<_, Status>(initial_raw()),
            |_, raw_value| parse_raw_value(raw_value),
            Duration::ZERO,
            "",
        );

    // Verify: The constructor returns an INVALID_ARGUMENT error.
    let err = result.err().expect("expected Err for zero poll_interval");
    assert_eq!(err.code(), tonic::Code::InvalidArgument);
}

#[tokio::test(flavor = "multi_thread")]
async fn single_subscriber_observes_initial_and_updated_values() {
    // Test plan: Verify that a single subscriber observes the initial value, then the updated
    // value after the underlying source changes, and falls back to the initial value when the
    // source is set to a malformed value. Verify this by constructing the adapter over a fake
    // source, advancing a `FakeClock` to drive each poll, and awaiting the watcher's value after
    // each source update.

    // Setup: Build an adapter over a fake source preloaded with the initial raw value.
    let fake_clock = FakeClock::create(SystemTime::UNIX_EPOCH);
    let source = FakeRawSource::new(initial_raw());
    let source_for_poller = source.clone();
    let adapter = WatchValueCellPollAdapter::new(
        fake_clock.clone().into_clock(),
        Some(initial_parsed()),
        move || Ok::<_, Status>(source_for_poller.current()),
        |_, raw_value| parse_raw_value(raw_value),
        POLL_INTERVAL,
        "",
    )
    .expect("poll_interval is positive");

    // Verify: The cell starts populated with the initial value.
    await_value(&adapter, initial_parsed()).await;

    // Setup: Update the source to a valid new value and drive one poll.
    source.update("raw-key-2", "raw-value-2-1");
    advance_one_poll(&fake_clock).await;
    let mut expected = initial_parsed();
    expected.insert(
        "parsed-key-2".to_string(),
        ParsedValue {
            value: "raw-value-2-1".to_string(),
        },
    );

    // Verify: The subscriber observes the parsed change.
    await_value(&adapter, expected).await;

    // Setup: Update the source to a malformed value and drive one poll.
    source.update("raw-key-2", "invalid-value-2-1");
    advance_one_poll(&fake_clock).await;

    // Verify: The parsed value falls back to the initial value.
    await_value(&adapter, initial_parsed()).await;
}

#[tokio::test(flavor = "multi_thread")]
async fn update_uses_latest_published_value_across_polls() {
    // Test plan: Verify that each update receives the value published by the previous poll. Start
    // without an initial value, poll three disjoint raw maps, and verify that the parsed value
    // accumulates all three entries.
    let fake_clock = FakeClock::create(SystemTime::UNIX_EPOCH);
    let source =
        FakeRawSource::new(HashMap::from([("raw-key-1".to_string(), "raw-value-1".to_string())]));
    let source_for_poller = source.clone();

    let adapter = WatchValueCellPollAdapter::new(
        fake_clock.clone().into_clock(),
        None,
        move || Ok::<_, Status>(source_for_poller.current()),
        |previous_value_opt: Option<ParsedValueMap>, raw_value: RawValueMap| {
            let mut next_value = previous_value_opt.unwrap_or_default();
            next_value.extend(parse_raw_value(raw_value)?);
            Ok(next_value)
        },
        POLL_INTERVAL,
        "",
    )
    .expect("poll_interval is positive");

    // The first update receives None and publishes the first parsed entry.
    let expected_after_first_poll = HashMap::from([(
        "parsed-key-1".to_string(),
        ParsedValue {
            value: "raw-value-1".to_string(),
        },
    )]);
    await_value(&adapter, expected_after_first_poll).await;

    // The second update receives the first published value and adds the second entry.
    source.set(HashMap::from([("raw-key-2".to_string(), "raw-value-2".to_string())]));
    advance_one_poll(&fake_clock).await;
    let expected_after_second_poll = HashMap::from([
        (
            "parsed-key-1".to_string(),
            ParsedValue {
                value: "raw-value-1".to_string(),
            },
        ),
        (
            "parsed-key-2".to_string(),
            ParsedValue {
                value: "raw-value-2".to_string(),
            },
        ),
    ]);
    await_value(&adapter, expected_after_second_poll).await;

    // The third update receives the second published value and adds the third entry.
    source.set(HashMap::from([("raw-key-3".to_string(), "raw-value-3".to_string())]));
    advance_one_poll(&fake_clock).await;
    let expected_after_third_poll = initial_parsed();
    await_value(&adapter, expected_after_third_poll).await;
}

#[tokio::test(flavor = "multi_thread")]
async fn multiple_subscribers_observe_the_same_updates() {
    // Test plan: Verify that several watchers -- including one that subscribes after the adapter
    // has already started -- observe the same sequence of values produced by a single adapter.
    // Verify this by cloning two watchers before the first update and one after, driving polls
    // via `FakeClock`, and checking each watcher's value directly after each update so a
    // regression in which cloned watchers stop receiving notifications would fail the
    // assertions.

    // Setup: Build an adapter and clone two watchers before any updates.
    let fake_clock = FakeClock::create(SystemTime::UNIX_EPOCH);
    let source = FakeRawSource::new(initial_raw());
    let source_for_poller = source.clone();
    let adapter = WatchValueCellPollAdapter::new(
        fake_clock.clone().into_clock(),
        Some(initial_parsed()),
        move || Ok::<_, Status>(source_for_poller.current()),
        |_, raw_value| parse_raw_value(raw_value),
        POLL_INTERVAL,
        "",
    )
    .expect("poll_interval is positive");
    let watcher_a = adapter.watcher().clone();
    let watcher_b = adapter.watcher().clone();

    // Verify: Both early watchers see the initial value.
    assert_eq!(watcher_a.get().value_opt(), Some(&initial_parsed()));
    assert_eq!(watcher_b.get().value_opt(), Some(&initial_parsed()));

    // Setup: Capture the version before updating the source (so the version-advanced assertion is
    // robust against the polling task observing the update before this thread reads it). Update
    // the source and drive one poll.
    let initial_version = watcher_a.get().version();
    source.update("raw-key-2", "raw-value-2-1");
    advance_one_poll(&fake_clock).await;
    let mut expected = initial_parsed();
    expected.insert(
        "parsed-key-2".to_string(),
        ParsedValue {
            value: "raw-value-2-1".to_string(),
        },
    );

    // Verify: Both early watchers observe the update and the version has advanced.
    await_watcher_value(&watcher_a, expected.clone()).await;
    assert_eq!(watcher_a.get().value_opt(), Some(&expected));
    assert_eq!(watcher_b.get().value_opt(), Some(&expected));
    assert!(watcher_a.get().version() > initial_version);

    // Setup: Subscribe a third watcher after the first update.
    let watcher_c = adapter.watcher().clone();

    // Verify: The late watcher sees the current value immediately.
    assert_eq!(watcher_c.get().value_opt(), Some(&expected));

    // Setup: Update two more source keys and drive one poll.
    source.update("raw-key-3", "raw-value-3-1");
    source.update("raw-key-1", "raw-value-1-1");
    advance_one_poll(&fake_clock).await;
    let mut expected2 = HashMap::new();
    expected2.insert(
        "parsed-key-1".to_string(),
        ParsedValue {
            value: "raw-value-1-1".to_string(),
        },
    );
    expected2.insert(
        "parsed-key-2".to_string(),
        ParsedValue {
            value: "raw-value-2-1".to_string(),
        },
    );
    expected2.insert(
        "parsed-key-3".to_string(),
        ParsedValue {
            value: "raw-value-3-1".to_string(),
        },
    );

    // Verify: All three watchers (early and late) observe the further update.
    await_watcher_value(&watcher_c, expected2.clone()).await;
    assert_eq!(watcher_a.get().value_opt(), Some(&expected2));
    assert_eq!(watcher_b.get().value_opt(), Some(&expected2));
    assert_eq!(watcher_c.get().value_opt(), Some(&expected2));
}

#[tokio::test(flavor = "multi_thread")]
async fn periodic_polling_continues_when_a_poll_panics() {
    // Test plan: Verify that polling continues even if an individual poll panics. Verify this by
    // wiring the closure to panic on its first invocation and succeed thereafter, advancing the
    // clock to drive the second (successful) poll, and awaiting the value it produces.

    // Setup: Build an adapter whose closure panics on the first call and reads the source on
    // subsequent calls.
    let fake_clock = FakeClock::create(SystemTime::UNIX_EPOCH);
    let poll_count = Arc::new(Mutex::new(0u32));
    let poll_count_for_poller = Arc::clone(&poll_count);
    let source = FakeRawSource::new(initial_raw());
    let source_for_poller = source.clone();
    let adapter = WatchValueCellPollAdapter::new(
        fake_clock.clone().into_clock(),
        // No initial value -- the first successful poll populates the cell.
        None,
        move || {
            let count = {
                let mut guard = poll_count_for_poller.lock().unwrap();
                let count = *guard;
                *guard += 1;
                count
            };
            if count == 0 {
                panic!("first poll intentionally fails");
            }
            Ok::<_, Status>(source_for_poller.current())
        },
        |_, raw_value| parse_raw_value(raw_value),
        POLL_INTERVAL,
        "test_poll_panic",
    )
    .expect("poll_interval is positive");

    // Setup: Advance the clock so the second (successful) poll runs after the first one panics.
    advance_one_poll(&fake_clock).await;

    // Verify: Despite the first poll panicking, the watcher eventually sees the initial parsed
    // value produced by the second (successful) poll.
    await_value(&adapter, initial_parsed()).await;

    // Setup: Update the source and drive one more poll.
    source.update("raw-key-2", "raw-value-2-1");
    advance_one_poll(&fake_clock).await;
    let mut expected = initial_parsed();
    expected.insert(
        "parsed-key-2".to_string(),
        ParsedValue {
            value: "raw-value-2-1".to_string(),
        },
    );

    // Verify: The watcher observes the updated value, proving polling is still alive.
    await_value(&adapter, expected).await;

    // Verify: The single panicked poll incremented the CRITICAL alert counter.
    assert_eq!(panic_alert_count("test_poll_panic"), 1);
}

#[tokio::test(flavor = "multi_thread")]
async fn periodic_polling_continues_when_a_scheduled_poll_panics() {
    // Test plan: Verify that when a later-iteration poll panics, the cell retains its prior
    // value and polling continues. Verify this by running a successful initial poll, arming the
    // poller to panic, driving a scheduled poll, asserting the cell still holds the prior value,
    // then disarming the poller and driving one more poll over an updated source.

    // Setup: Build an adapter whose closure panics when an `Arc<Mutex<bool>>` is true.
    let fake_clock = FakeClock::create(SystemTime::UNIX_EPOCH);
    let panic_armed = Arc::new(Mutex::new(false));
    let panic_armed_for_poller = Arc::clone(&panic_armed);
    let source = FakeRawSource::new(initial_raw());
    let source_for_poller = source.clone();
    let adapter = WatchValueCellPollAdapter::new(
        fake_clock.clone().into_clock(),
        Some(initial_parsed()),
        move || {
            if *panic_armed_for_poller.lock().unwrap() {
                panic!("scheduled poll intentionally fails");
            }
            Ok::<_, Status>(source_for_poller.current())
        },
        |_, raw_value| parse_raw_value(raw_value),
        POLL_INTERVAL,
        "test_scheduled_poll_panic",
    )
    .expect("poll_interval is positive");

    // Verify: The initial successful poll lands.
    await_value(&adapter, initial_parsed()).await;

    // Setup: Drive a disarmed scheduled poll before arming so the polling task deterministically
    // observes `panic_armed = false` once before the test flips it. Without this, a racy first
    // iteration that reads the freshly-armed state would produce a different (non-deterministic)
    // panic count below.
    advance_one_poll(&fake_clock).await;

    // Setup: Arm the poller to panic on the next invocation and drive a scheduled poll so the
    // panicking iteration runs and the loop continues to its next sleep.
    *panic_armed.lock().unwrap() = true;
    advance_one_poll(&fake_clock).await;

    // Verify: The cell retains the prior successful value (the panicked poll did not overwrite
    // it).
    assert_eq!(adapter.value_opt(), Some(initial_parsed()));

    // Setup: Disarm the poller, update the source, and drive one more poll.
    *panic_armed.lock().unwrap() = false;
    source.update("raw-key-2", "raw-value-2-1");
    advance_one_poll(&fake_clock).await;
    let mut expected = initial_parsed();
    expected.insert(
        "parsed-key-2".to_string(),
        ParsedValue {
            value: "raw-value-2-1".to_string(),
        },
    );

    // Verify: The watcher observes the updated value, proving polling is still alive after the
    // panicked iteration.
    await_value(&adapter, expected).await;

    // Verify: The single panicked poll incremented the CRITICAL alert counter.
    assert_eq!(panic_alert_count("test_scheduled_poll_panic"), 1);
}

#[tokio::test(flavor = "multi_thread")]
async fn periodic_polling_continues_when_update_panics() {
    // Test plan: Verify that when `update` panics during a scheduled poll, the cell retains
    // its prior value and polling continues. Architecturally `poller` and `update` share a
    // single `catch_unwind` boundary, so this should behave identically to a poller panic; the
    // test guards against future regressions that move the catch boundary. Verify this by
    // arming `update` (not `poller`) to panic for one scheduled poll, asserting the cell
    // still holds the prior value, then disarming and driving one more poll.

    // Setup: Build an adapter whose `update` panics when an `Arc<Mutex<bool>>` is true.
    let fake_clock = FakeClock::create(SystemTime::UNIX_EPOCH);
    let panic_armed = Arc::new(Mutex::new(false));
    let panic_armed_for_update = Arc::clone(&panic_armed);
    let source = FakeRawSource::new(initial_raw());
    let source_for_poller = source.clone();
    let adapter = WatchValueCellPollAdapter::new(
        fake_clock.clone().into_clock(),
        Some(initial_parsed()),
        move || Ok::<_, Status>(source_for_poller.current()),
        move |_, raw: RawValueMap| {
            if *panic_armed_for_update.lock().unwrap() {
                panic!("update intentionally fails");
            }
            parse_raw_value(raw)
        },
        POLL_INTERVAL,
        "test_update_panic",
    )
    .expect("poll_interval is positive");

    // Verify: The initial successful poll lands.
    await_value(&adapter, initial_parsed()).await;

    // Setup: Drive a disarmed scheduled poll before arming so the polling task deterministically
    // observes `panic_armed = false` once before the test flips it. Without this, a racy first
    // iteration that reads the freshly-armed state would produce a different (non-deterministic)
    // panic count below.
    advance_one_poll(&fake_clock).await;

    // Setup: Arm the update to panic, update the source, and drive a scheduled poll so the
    // panicking iteration runs and the loop continues to its next sleep.
    *panic_armed.lock().unwrap() = true;
    source.update("raw-key-2", "raw-value-2-1");
    advance_one_poll(&fake_clock).await;

    // Verify: The cell retains the prior value (the update panic prevented `cell.set`).
    assert_eq!(adapter.value_opt(), Some(initial_parsed()));

    // Setup: Disarm the update and drive one more poll.
    *panic_armed.lock().unwrap() = false;
    advance_one_poll(&fake_clock).await;
    let mut expected = initial_parsed();
    expected.insert(
        "parsed-key-2".to_string(),
        ParsedValue {
            value: "raw-value-2-1".to_string(),
        },
    );

    // Verify: The watcher observes the updated value, proving polling is still alive after the
    // update panic.
    await_value(&adapter, expected).await;

    // Verify: The single update panic incremented the CRITICAL alert counter.
    assert_eq!(panic_alert_count("test_update_panic"), 1);
}

#[tokio::test(flavor = "multi_thread")]
async fn cell_observes_first_poll_when_initial_value_is_none() {
    // Test plan: Verify that when no initial value is supplied, the watcher observes the value
    // produced by the first poll, and subsequent source updates propagate. Verify this by
    // constructing the adapter with `None`, awaiting the watcher's converged value, then
    // updating the source and awaiting again.

    // Setup: Build an adapter with no initial value.
    let fake_clock = FakeClock::create(SystemTime::UNIX_EPOCH);
    let source = FakeRawSource::new(initial_raw());
    let source_for_poller = source.clone();
    let adapter = WatchValueCellPollAdapter::new(
        fake_clock.clone().into_clock(),
        None,
        move || Ok::<_, Status>(source_for_poller.current()),
        |_, raw_value| parse_raw_value(raw_value),
        POLL_INTERVAL,
        "",
    )
    .expect("poll_interval is positive");

    // Verify: The watcher observes the value produced by the first poll.
    await_value(&adapter, initial_parsed()).await;

    // Setup: Update the source and drive one more poll.
    source.update("raw-key-2", "raw-value-2-1");
    advance_one_poll(&fake_clock).await;
    let mut expected = initial_parsed();
    expected.insert(
        "parsed-key-2".to_string(),
        ParsedValue {
            value: "raw-value-2-1".to_string(),
        },
    );

    // Verify: The watcher observes the updated value.
    await_value(&adapter, expected).await;
}

#[tokio::test(flavor = "multi_thread")]
async fn poller_error_skips_iteration_and_polling_continues() {
    // Test plan: Verify that a poller error preserves the last published value for the next update
    // and does not stop polling. Publish one entry, fail a poll after replacing the raw source,
    // then recover and verify that the next update combines both entries.
    let fake_clock = FakeClock::create(SystemTime::UNIX_EPOCH);
    let poller_fails = Arc::new(Mutex::new(false));
    let poller_fails_for_poller = Arc::clone(&poller_fails);
    let source =
        FakeRawSource::new(HashMap::from([("raw-key-1".to_string(), "raw-value-1".to_string())]));
    let source_for_poller = source.clone();
    let adapter = WatchValueCellPollAdapter::new(
        fake_clock.clone().into_clock(),
        None,
        move || {
            if *poller_fails_for_poller.lock().unwrap() {
                return Err(Status::internal("poller intentionally returns error"));
            }
            Ok::<_, Status>(source_for_poller.current())
        },
        |previous_value_opt: Option<ParsedValueMap>, raw_value: RawValueMap| {
            let mut next_value = previous_value_opt.unwrap_or_default();
            next_value.extend(parse_raw_value(raw_value)?);
            Ok(next_value)
        },
        POLL_INTERVAL,
        "",
    )
    .expect("poll_interval is positive");

    // Verify: The initial successful poll publishes the first entry.
    let expected_after_first_poll = HashMap::from([(
        "parsed-key-1".to_string(),
        ParsedValue {
            value: "raw-value-1".to_string(),
        },
    )]);
    await_value(&adapter, expected_after_first_poll.clone()).await;

    // Fail the poll after replacing the source and verify that the published value is unchanged.
    source.set(HashMap::from([("raw-key-2".to_string(), "raw-value-2".to_string())]));
    *poller_fails.lock().unwrap() = true;
    advance_one_poll(&fake_clock).await;
    assert_eq!(adapter.value_opt(), Some(expected_after_first_poll));

    // Recover the poller and verify that the next update receives the first published value.
    *poller_fails.lock().unwrap() = false;
    advance_one_poll(&fake_clock).await;
    let expected_after_recovery = HashMap::from([
        (
            "parsed-key-1".to_string(),
            ParsedValue {
                value: "raw-value-1".to_string(),
            },
        ),
        (
            "parsed-key-2".to_string(),
            ParsedValue {
                value: "raw-value-2".to_string(),
            },
        ),
    ]);
    await_value(&adapter, expected_after_recovery).await;
}

#[tokio::test(flavor = "multi_thread")]
async fn update_error_preserves_previous_value_for_next_update() {
    // Test plan: Verify that an update error preserves the last published value for the next update
    // and does not stop polling. Publish one entry, fail an update after replacing the raw source,
    // then recover and verify that the next update combines both entries.
    let fake_clock = FakeClock::create(SystemTime::UNIX_EPOCH);
    let update_fails = Arc::new(Mutex::new(false));
    let update_fails_for_update = Arc::clone(&update_fails);
    let source =
        FakeRawSource::new(HashMap::from([("raw-key-1".to_string(), "raw-value-1".to_string())]));
    let source_for_poller = source.clone();
    let adapter = WatchValueCellPollAdapter::new(
        fake_clock.clone().into_clock(),
        None,
        move || Ok::<_, Status>(source_for_poller.current()),
        move |previous_value_opt: Option<ParsedValueMap>, raw_value: RawValueMap| {
            if *update_fails_for_update.lock().unwrap() {
                return Err(Status::internal("update intentionally returns error"));
            }
            let mut next_value = previous_value_opt.unwrap_or_default();
            next_value.extend(parse_raw_value(raw_value)?);
            Ok(next_value)
        },
        POLL_INTERVAL,
        "",
    )
    .expect("poll_interval is positive");

    // The initial successful update publishes the first entry.
    let expected_after_first_poll = HashMap::from([(
        "parsed-key-1".to_string(),
        ParsedValue {
            value: "raw-value-1".to_string(),
        },
    )]);
    await_value(&adapter, expected_after_first_poll.clone()).await;

    // Fail the update after replacing the source and verify that the published value is unchanged.
    source.set(HashMap::from([("raw-key-2".to_string(), "raw-value-2".to_string())]));
    *update_fails.lock().unwrap() = true;
    advance_one_poll(&fake_clock).await;
    assert_eq!(adapter.value_opt(), Some(expected_after_first_poll));

    // Recover the update and verify that it receives the first published value.
    *update_fails.lock().unwrap() = false;
    advance_one_poll(&fake_clock).await;
    let expected_after_recovery = HashMap::from([
        (
            "parsed-key-1".to_string(),
            ParsedValue {
                value: "raw-value-1".to_string(),
            },
        ),
        (
            "parsed-key-2".to_string(),
            ParsedValue {
                value: "raw-value-2".to_string(),
            },
        ),
    ]);
    await_value(&adapter, expected_after_recovery).await;
}

#[tokio::test(flavor = "multi_thread")]
async fn no_op_polls_do_not_advance_the_version() {
    // Test plan: Verify that when the polled value does not change, the cell's version does not
    // advance, and that a real value change does advance it. Verify this by capturing the
    // version after the initial value lands, driving several no-op polls and asserting the
    // version is unchanged, then updating the source and asserting the version advances.

    // Setup: Build an adapter, wait for the initial value to land, and capture the watcher's
    // version before any source update.
    let fake_clock = FakeClock::create(SystemTime::UNIX_EPOCH);
    let source = FakeRawSource::new(initial_raw());
    let source_for_poller = source.clone();
    let adapter = WatchValueCellPollAdapter::new(
        fake_clock.clone().into_clock(),
        Some(initial_parsed()),
        move || Ok::<_, Status>(source_for_poller.current()),
        |_, raw_value| parse_raw_value(raw_value),
        POLL_INTERVAL,
        "",
    )
    .expect("poll_interval is positive");
    await_value(&adapter, initial_parsed()).await;
    let watcher = adapter.watcher().clone();
    let initial_version = watcher.get().version();

    // Setup: Advance the clock across several intervals without changing the underlying source.
    for _ in 0..4 {
        advance_one_poll(&fake_clock).await;
    }

    // Verify: No-op polls did not advance the version.
    assert_eq!(watcher.get().version(), initial_version);

    // Setup: Update the source and drive one poll so a real change reaches the cell.
    source.update("raw-key-2", "raw-value-2-1");
    advance_one_poll(&fake_clock).await;
    let mut expected = initial_parsed();
    expected.insert(
        "parsed-key-2".to_string(),
        ParsedValue {
            value: "raw-value-2-1".to_string(),
        },
    );

    // Verify: The real change advances the version.
    let updated = await_watcher_value(&watcher, expected).await;
    assert!(updated.version() > initial_version);
}

#[tokio::test(flavor = "multi_thread")]
async fn dropping_adapter_terminates_polling_task() {
    // Test plan: Verify that dropping the adapter causes the background polling task to stop and
    // release everything it owns. The `Drop` impl is the sole mechanism that stops polling, so a
    // regression that loses the abort (e.g. removing `abort_handle.abort()`) must be caught here.
    // Verify this by capturing an `Arc<()>` witness inside the poller closure and a `Weak<()>` in
    // the test; after the test drops both the adapter and its own strong reference, the polling
    // task is the sole strong owner. Once the task aborts and drops its closure, the witness Arc
    // has zero strong references and `weak.upgrade()` returns `None`.

    // Setup: Build an adapter whose poller closure captures an `Arc<()>` witness.
    let fake_clock = FakeClock::create(SystemTime::UNIX_EPOCH);
    let witness = Arc::new(());
    let witness_for_poller = Arc::clone(&witness);
    let weak_witness = Arc::downgrade(&witness);
    drop(witness);
    let source = FakeRawSource::new(initial_raw());
    let source_for_poller = source.clone();
    let adapter = WatchValueCellPollAdapter::new(
        fake_clock.clone().into_clock(),
        Some(initial_parsed()),
        move || {
            // Keep the witness alive for as long as the polling task owns this closure.
            let _ = &witness_for_poller;
            Ok::<_, Status>(source_for_poller.current())
        },
        |_, raw_value| parse_raw_value(raw_value),
        POLL_INTERVAL,
        "",
    )
    .expect("poll_interval is positive");

    // Setup: Wait until the polling task has begun its first sleep, so we know the task is alive
    // and owns the closure (and therefore the witness Arc).
    AssertionWaiter::new("polling task to register a sleep waiter")
        .await_condition(|| {
            assert!(
                fake_clock.pending_waiter_count() > 0,
                "polling task did not register a sleep waiter",
            );
        })
        .await;
    assert!(
        weak_witness.upgrade().is_some(),
        "witness Arc was dropped before the adapter was dropped",
    );
    drop(adapter);

    // Verify: After dropping the adapter, the polling task aborts and releases its closure.
    AssertionWaiter::new("polling task to release its closure")
        .await_condition(|| {
            assert!(
                weak_witness.upgrade().is_none(),
                "polling task still holds its closure (Arc strong count > 0) after adapter drop",
            );
        })
        .await;
}

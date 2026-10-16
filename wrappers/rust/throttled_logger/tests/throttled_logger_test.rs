use std::fmt::Debug;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Barrier, Mutex};
use std::time::Duration;

use tracing::field::{Field, FieldSet};
use tracing::metadata::Kind;
use tracing::span::{Attributes, Record};
use tracing::subscriber::DefaultGuard;
use tracing::{Event, Id, Level, Metadata};
use universe_common_logging_throttled_logger_rust::event_every_n_sec;

/// A single event captured by [`TestSubscriber`].
struct LoggedEvent {
    /// The event's metadata (level, file, target, etc.).
    metadata: Metadata<'static>,

    /// The rendered `message` field.
    message: String,
}

/// A [`tracing::Subscriber`] implementation that collects logged events in memory so that tests
/// can assert on them.
struct TestSubscriber {
    /// The logged events.
    log: Mutex<Vec<LoggedEvent>>,
}

impl TestSubscriber {
    /// Creates a new `TestSubscriber`.
    fn new() -> TestSubscriber {
        TestSubscriber {
            log: Mutex::new(Vec::new()),
        }
    }

    /// Returns the logged events.
    fn log(&self) -> Vec<LoggedEvent> {
        self.log
            .lock()
            .unwrap()
            .iter()
            .map(|event: &LoggedEvent| LoggedEvent {
                metadata: to_static_metadata(&event.metadata),
                message: event.message.clone(),
            })
            .collect()
    }

    /// A convenience wrapper around [`TestSubscriber::log`] that returns just the log level and
    /// message rather than the entire event.
    fn log_levels_and_messages(&self) -> Vec<(Level, String)> {
        self.log()
            .into_iter()
            .map(|event: LoggedEvent| (*event.metadata.level(), event.message))
            .collect()
    }
}

impl tracing::Subscriber for TestSubscriber {
    fn enabled(&self, _: &Metadata) -> bool {
        true
    }

    fn event(&self, event: &Event) {
        let mut message_opt: Option<String> = None;
        event.record(&mut |field: &Field, value: &dyn Debug| {
            if field.name() == "message" {
                message_opt = Some(format!("{value:?}"));
            }
        });
        self.log.lock().unwrap().push(LoggedEvent {
            metadata: to_static_metadata(event.metadata()),
            message: message_opt.unwrap(),
        });
    }

    fn new_span(&self, _: &Attributes) -> Id {
        unimplemented!()
    }
    fn record(&self, _: &Id, _: &Record) {}
    fn record_follows_from(&self, _: &Id, _: &Id) {}
    fn enter(&self, _: &Id) {}
    fn exit(&self, _: &Id) {}
}

/// Transforms the given [`Metadata`] instance with arbitrary lifetime into one with static
/// lifetime.
///
/// This is necessary because the instances passed to [`tracing::Subscriber::event`] only have the
/// lifetime of the function call, but we need to stash them in a vector for future reference.
fn to_static_metadata(metadata: &Metadata) -> Metadata<'static> {
    Metadata::new(
        metadata.name(),
        metadata.target().to_string().leak(),
        *metadata.level(),
        metadata.file().map(|s: &str| s.to_string().leak() as &_),
        metadata.line(),
        metadata
            .module_path()
            .map(|s: &str| s.to_string().leak() as &_),
        FieldSet::new(
            metadata
                .fields()
                .iter()
                .map(|field: Field| field.name())
                .collect::<Vec<&'static str>>()
                .leak(),
            metadata.fields().iter().next().unwrap().callsite(),
        ),
        if metadata.is_event() {
            Kind::EVENT
        } else if metadata.is_span() {
            Kind::SPAN
        } else {
            Kind::HINT
        },
    )
}

#[test]
fn test_proper_log_levels() {
    // Test plan: Verify that the macros record log entries at the expected log levels.
    let subscriber = Arc::new(TestSubscriber::new());
    let _subscriber_guard: DefaultGuard = tracing::subscriber::set_default(Arc::clone(&subscriber));

    event_every_n_sec!(10, Level::DEBUG, "Example debug log statement");
    event_every_n_sec!(10, Level::ERROR, "Example error log statement");
    event_every_n_sec!(10, Level::INFO, "Example info log statement");
    event_every_n_sec!(10, Level::TRACE, "Example trace log statement");
    event_every_n_sec!(10, Level::WARN, "Example warn log statement");
    event_every_n_sec!(10, Level::INFO, "Example event statement");

    assert_eq!(
        subscriber.log_levels_and_messages(),
        vec![
            (Level::DEBUG, "[every=10s] Example debug log statement".to_string()),
            (Level::ERROR, "[every=10s] Example error log statement".to_string()),
            (Level::INFO, "[every=10s] Example info log statement".to_string()),
            (Level::TRACE, "[every=10s] Example trace log statement".to_string()),
            (Level::WARN, "[every=10s] Example warn log statement".to_string()),
            (Level::INFO, "[every=10s] Example event statement".to_string()),
        ]
    );
}

#[test]
fn test_format_args() {
    // Test plan: Verify that the macros work properly with format arguments.
    let subscriber = Arc::new(TestSubscriber::new());
    let _subscriber_guard: DefaultGuard = tracing::subscriber::set_default(Arc::clone(&subscriber));

    let num = 42;
    event_every_n_sec!(10, Level::DEBUG, "num is {}", num);
    event_every_n_sec!(10, Level::ERROR, "num is {}", num);
    event_every_n_sec!(10, Level::INFO, "num is {}", num);
    event_every_n_sec!(10, Level::TRACE, "num is {}", num);
    event_every_n_sec!(10, Level::WARN, "num is {}", num);
    event_every_n_sec!(10, Level::INFO, "num is {}", num);

    assert_eq!(
        subscriber.log_levels_and_messages(),
        vec![
            (Level::DEBUG, "[every=10s] num is 42".to_string()),
            (Level::ERROR, "[every=10s] num is 42".to_string()),
            (Level::INFO, "[every=10s] num is 42".to_string()),
            (Level::TRACE, "[every=10s] num is 42".to_string()),
            (Level::WARN, "[every=10s] num is 42".to_string()),
            (Level::INFO, "[every=10s] num is 42".to_string()),
        ]
    );
}

#[test]
fn test_named_format_args() {
    // Test plan: Verify that the macros work properly with named format arguments.
    let subscriber = Arc::new(TestSubscriber::new());
    let _subscriber_guard: DefaultGuard = tracing::subscriber::set_default(Arc::clone(&subscriber));

    let num_var = 42;
    event_every_n_sec!(10, Level::DEBUG, "num is {num}", num = num_var);
    event_every_n_sec!(10, Level::ERROR, "num is {num}", num = num_var);
    event_every_n_sec!(10, Level::INFO, "num is {num}", num = num_var);
    event_every_n_sec!(10, Level::TRACE, "num is {num}", num = num_var);
    event_every_n_sec!(10, Level::WARN, "num is {num}", num = num_var);
    event_every_n_sec!(10, Level::INFO, "num is {num}", num = num_var);

    assert_eq!(
        subscriber.log_levels_and_messages(),
        vec![
            (Level::DEBUG, "[every=10s] num is 42".to_string()),
            (Level::ERROR, "[every=10s] num is 42".to_string()),
            (Level::INFO, "[every=10s] num is 42".to_string()),
            (Level::TRACE, "[every=10s] num is 42".to_string()),
            (Level::WARN, "[every=10s] num is 42".to_string()),
            (Level::INFO, "[every=10s] num is 42".to_string()),
        ]
    );
}

#[test]
fn test_interpolation() {
    // Test plan: Verify that the macros work properly with interpolated format arguments.
    let subscriber = Arc::new(TestSubscriber::new());
    let _subscriber_guard: DefaultGuard = tracing::subscriber::set_default(Arc::clone(&subscriber));

    let num = 42;
    event_every_n_sec!(10, Level::DEBUG, "num is {num}");
    event_every_n_sec!(10, Level::ERROR, "num is {num}");
    event_every_n_sec!(10, Level::INFO, "num is {num}");
    event_every_n_sec!(10, Level::TRACE, "num is {num}");
    event_every_n_sec!(10, Level::WARN, "num is {num}");
    event_every_n_sec!(10, Level::INFO, "num is {num}");

    assert_eq!(
        subscriber.log_levels_and_messages(),
        vec![
            (Level::DEBUG, "[every=10s] num is 42".to_string()),
            (Level::ERROR, "[every=10s] num is 42".to_string()),
            (Level::INFO, "[every=10s] num is 42".to_string()),
            (Level::TRACE, "[every=10s] num is 42".to_string()),
            (Level::WARN, "[every=10s] num is 42".to_string()),
            (Level::INFO, "[every=10s] num is 42".to_string()),
        ]
    );
}

#[test]
fn test_proper_call_site() {
    // Test plan: Verify that the logged events specify the top-level call site where the macro is
    // invoked, as opposed to the call site inside of the throttled macro definitions where the
    // underlying logging macros are invoked.
    let subscriber = Arc::new(TestSubscriber::new());
    let _subscriber_guard: DefaultGuard = tracing::subscriber::set_default(Arc::clone(&subscriber));

    event_every_n_sec!(10, Level::DEBUG, "Example log message");
    event_every_n_sec!(10, Level::ERROR, "Example log message");
    event_every_n_sec!(10, Level::INFO, "Example log message");
    event_every_n_sec!(10, Level::TRACE, "Example log message");
    event_every_n_sec!(10, Level::WARN, "Example log message");
    event_every_n_sec!(10, Level::INFO, "Example log message");

    assert_eq!(subscriber.log().len(), 6);
    for event in subscriber.log() {
        assert_eq!(event.metadata.file(), Some(file!()));
        assert_eq!(event.metadata.module_path(), Some(module_path!()));
    }
}

#[test]
fn test_no_throttling() {
    // Test plan: Verify that no throttling occurs if a throttling period of zero seconds is used.
    let subscriber = Arc::new(TestSubscriber::new());
    let _subscriber_guard: DefaultGuard = tracing::subscriber::set_default(Arc::clone(&subscriber));

    for _ in 0..3 {
        event_every_n_sec!(0, Level::INFO, "Example log message");
    }

    assert_eq!(
        subscriber.log_levels_and_messages(),
        vec![
            (Level::INFO, "[every=0s] Example log message".to_string()),
            (Level::INFO, "[every=0s] Example log message".to_string()),
            (Level::INFO, "[every=0s] Example log message".to_string()),
        ]
    );
}

#[tokio::test(start_paused = true)]
async fn test_throttling() {
    // Test plan: Verify that throttling works as expected and is indicated in the final logged
    // events.
    let subscriber = Arc::new(TestSubscriber::new());
    let _subscriber_guard: DefaultGuard = tracing::subscriber::set_default(Arc::clone(&subscriber));

    // Perform the following sequence of events with a throttling period of 1 second:
    // 1. Log 3 times (i=0,1,2)
    // 2. Advance time by 1 second (start of i=3)
    // 3. Log 2 times (i=3,4)
    // 4. Advance time by 1 second (start of i=5)
    // 5. Log one time (i=5)
    // 6. Advance time by 1 second (start of i=6)
    // 7. Log one time (i=6)
    for i in 0..7 {
        if i == 3 || i == 5 || i == 6 {
            tokio::time::advance(Duration::from_secs(1)).await;
        }
        event_every_n_sec!(1, Level::INFO, "Example log message");
    }

    assert_eq!(
        subscriber.log_levels_and_messages(),
        vec![
            (Level::INFO, "[every=1s] Example log message".to_string()),
            (Level::INFO, "[every=1s, skipped=2] Example log message".to_string()),
            (Level::INFO, "[every=1s, skipped=1] Example log message".to_string()),
            (Level::INFO, "[every=1s] Example log message".to_string()),
        ]
    );
}

#[tokio::test(start_paused = true)]
async fn test_throttle_window_boundary() {
    // Test plan: Verify suppression immediately before the deadline and admission exactly at it.
    let subscriber = Arc::new(TestSubscriber::new());
    let _subscriber_guard: DefaultGuard = tracing::subscriber::set_default(Arc::clone(&subscriber));

    for delay in [Duration::ZERO, Duration::from_millis(999), Duration::from_millis(1)] {
        tokio::time::advance(delay).await;
        event_every_n_sec!(1, Level::INFO, "boundary");
    }

    assert_eq!(
        subscriber.log_levels_and_messages(),
        vec![
            (Level::INFO, "[every=1s] boundary".to_string()),
            (Level::INFO, "[every=1s, skipped=1] boundary".to_string()),
        ]
    );
}

#[test]
fn test_call_sites_throttle_independently() {
    // Test plan: Verify two call sites with the same message each admit their first invocation.
    let subscriber = Arc::new(TestSubscriber::new());
    let _subscriber_guard: DefaultGuard = tracing::subscriber::set_default(Arc::clone(&subscriber));

    for _ in 0..3 {
        event_every_n_sec!(60, Level::INFO, "same message");
        event_every_n_sec!(60, Level::INFO, "same message");
    }

    assert_eq!(
        subscriber.log_levels_and_messages(),
        vec![(Level::INFO, "[every=60s] same message".to_string()); 2]
    );
}

#[test]
fn test_suppressed_arguments_are_not_evaluated() {
    // Test plan: Verify that suppressed events do not evaluate argument expressions with side
    // effects.
    let subscriber = Arc::new(TestSubscriber::new());
    let _subscriber_guard: DefaultGuard = tracing::subscriber::set_default(Arc::clone(&subscriber));
    let evaluations = AtomicUsize::new(0);

    for _ in 0..3 {
        event_every_n_sec!(60, Level::INFO, "value {}", evaluations.fetch_add(1, Ordering::SeqCst));
    }

    assert_eq!(evaluations.load(Ordering::SeqCst), 1);
    assert_eq!(
        subscriber.log_levels_and_messages(),
        vec![(Level::INFO, "[every=60s] value 0".to_string())]
    );
}

#[test]
fn test_concurrent_calls_admit_one_event() {
    // Test plan: Release threads together at one call site and verify only one event is admitted.
    let subscriber = Arc::new(TestSubscriber::new());
    let barrier = Barrier::new(16);

    std::thread::scope(|scope| {
        for _ in 0..16 {
            scope.spawn(|| {
                tracing::subscriber::with_default(Arc::clone(&subscriber), || {
                    barrier.wait();
                    event_every_n_sec!(60, Level::INFO, "concurrent");
                });
            });
        }
    });

    assert_eq!(
        subscriber.log_levels_and_messages(),
        vec![(Level::INFO, "[every=60s] concurrent".to_string())]
    );
}

use std::collections::BTreeSet;
use std::panic::AssertUnwindSafe;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, SystemTime};

use futures_util::FutureExt; // For `FutureExt::catch_unwind`
use tokio::sync::mpsc;
use caching_rust_util_state_machine::{
    system_clock,
    AlertOwnerTeam,
    Clock,
    EventHandler,
    StateMachine,
    StateMachineDriver,
    StateMachineOutput,
};
use caching_rust_util_testing::fake_clock::FakeClock;

/// A test alarm.
#[derive(Debug, PartialEq, Eq, Clone, PartialOrd, Ord)]
struct Alarm {
    time: SystemTime,
    message: String,
}

impl Alarm {
    fn new(time: SystemTime, message: impl Into<String>) -> Self {
        Self {
            time,
            message: message.into(),
        }
    }
}

/// An event to be processed by the test state machine.
#[derive(Debug, PartialEq, Eq, Clone)]
enum Event {
    /// Causes the state machine to panic in `StateMachine::advance`.
    PanicOnAdvance,
    /// Causes the state machine to panic in
    /// `StateMachineDriver::perform_action`.
    PanicOnPerform,
    /// Sets an alarm for the given time.
    SetAlarm(Alarm),
    /// Removes an alarm for the given time.
    RemoveAlarm(Alarm),
    /// Generic event triggering some action.
    Generic(Box<Action>),
}

/// An action to be performed by the test state machine driver.
#[derive(Debug, PartialEq, Eq, Clone)]
enum Action {
    /// Causes the state machine driver to panic in `perform_action`.
    PanicOnPerform,
    /// Sounds the given alarm.
    SoundAlarm(Alarm),
    /// Generic action triggering some event.
    Generic(Box<Event>),
}

/// Arguments to a `StateMachine::advance` call.
#[derive(Debug, PartialEq, Eq, Clone)]
struct Call {
    events: Vec<Event>,
    time: SystemTime,
}

/// A test state machine that records the calls to `StateMachine::advance` and
/// returns the actions requested by the events. This state machine manages
/// alarms and uses a closure-based action handler.
struct AlarmStateMachine {
    // A real state machine will not have an out-of-band communication channel,
    // and will instead communicate via `Action`s. This is a convenience for our
    // tests only.
    call_sender: mpsc::UnboundedSender<Call>,
    alarms: BTreeSet<Alarm>,
}

impl StateMachine for AlarmStateMachine {
    type Event = Event;
    type Action = Action;

    fn advance(&mut self, now: SystemTime, events: Vec<Event>) -> StateMachineOutput<Self::Action> {
        // Record the call arguments.
        self.call_sender
            .send(Call {
                time: now,
                events: events.clone(),
            })
            .unwrap();

        // Build output requesting all actions to be performed as given by the
        // events.
        let mut output = StateMachineOutput::new();
        for event in events {
            match event {
                Event::PanicOnAdvance => {
                    panic!("PanicOnAdvance");
                }
                Event::PanicOnPerform => {
                    output.append_action(Action::PanicOnPerform);
                }
                Event::SetAlarm(alarm) => {
                    self.alarms.insert(alarm);
                }
                Event::RemoveAlarm(alarm) => {
                    self.alarms.remove(&alarm);
                }
                Event::Generic(action) => {
                    output.append_action(*action);
                }
            }
        }
        // Sound all alarms that are due.
        let mut alarms_to_sound = Vec::new();
        for alarm in self.alarms.iter() {
            if now >= alarm.time {
                alarms_to_sound.push(alarm.clone());
            }
        }
        for alarm in alarms_to_sound.into_iter() {
            self.alarms.remove(&alarm);
            output.append_action(Action::SoundAlarm(alarm));
        }
        // Return the next time the state machine should be advanced.
        if let Some(alarm) = self.alarms.first() {
            output.ensure_advance_by(alarm.time);
        }
        output
    }
}

/// Wrapper around a `StateMachineDriver` that provides access to the test
/// state machine and the test driver.
struct AlarmDriver {
    /// Receiver used to observe the calls made to the state machine in our
    /// tests.
    call_receiver: mpsc::UnboundedReceiver<Call>,

    /// Receiver used to observe the alarms received by the action handler in
    /// our tests.
    alarm_receiver: mpsc::UnboundedReceiver<Alarm>,

    /// Sender used by the action handler to record alarms received.
    alarm_sender: mpsc::UnboundedSender<Alarm>,

    /// The test state machine driver.
    driver: StateMachineDriver<AlarmStateMachine>,
}

impl AlarmDriver {
    fn new(clock: Clock) -> Self {
        let (call_sender, call_receiver) = mpsc::unbounded_channel();
        let (alarm_sender, alarm_receiver) = mpsc::unbounded_channel();
        let driver = StateMachineDriver::new(
            clock,
            AlarmStateMachine {
                call_sender,
                alarms: BTreeSet::new(),
            },
            AlertOwnerTeam::CachingTeam,
        );
        Self {
            call_receiver,
            alarm_receiver,
            alarm_sender,
            driver,
        }
    }

    /// Starts the state machine driver using an action handler that
    /// records the actions requested by the state machine.
    fn start(&mut self) -> bool {
        let alarm_sender = self.alarm_sender.clone();
        let event_handler = self.driver.event_handler().clone();
        self.driver.start(move |actions: Vec<Action>| {
            for action in actions.into_iter() {
                match action {
                    Action::SoundAlarm(alarm) => {
                        alarm_sender.send(alarm).unwrap();
                    }
                    Action::Generic(event) => {
                        event_handler.handle_event(*event);
                    }
                    Action::PanicOnPerform => {
                        panic!("PanicOnPerform");
                    }
                }
            }
        })
    }

    /// Supplies the given event to the state machine.
    fn handle_event(&self, event: Event) {
        self.driver.event_handler().handle_event(event);
    }

    /// Returns a reference to the event handler for the state machine.
    fn event_handler(&self) -> &EventHandler<AlarmStateMachine> {
        self.driver.event_handler()
    }

    /// Reads the state machine using the given accessor function.
    async fn read<T: Send + 'static>(
        &self,
        accessor: impl Fn(&AlarmStateMachine) -> T + Send + 'static,
    ) -> T {
        self.driver.read(accessor).await
    }

    /// Flushes the state machine by awaiting a read accessor.
    async fn flush(&self) {
        self.read(|_machine: &AlarmStateMachine| {}).await;
    }

    /// Drains all calls received by the test state machine.
    async fn drain_calls(&mut self) -> Vec<Call> {
        self.flush().await;
        let mut calls = Vec::new();
        while let Ok(call) = self.call_receiver.try_recv() {
            calls.push(call);
        }
        calls
    }

    /// Drains an expected advance call with no events. Returns
    /// the time at which the advance was requested.
    async fn drain_expected_advance_call(&mut self) -> SystemTime {
        let calls = self.drain_calls().await;
        assert_eq!(calls.len(), 1);
        calls[0].time
    }

    /// Drains all alarms received by the test driver.
    async fn drain_alarms(&mut self) -> Vec<Alarm> {
        self.flush().await;
        let mut alarms = Vec::new();
        while let Ok(alarm) = self.alarm_receiver.try_recv() {
            alarms.push(alarm);
        }
        alarms
    }

    /// Asserts that the driver is poisoned by checking that `read` and
    /// `handle_event` panic.
    async fn assert_poisoned(&self) {
        // Verify that `read` panics first, then `handle_event`. We do it in
        // this order because the `read` call ensures that all async work that
        // may lead to panicking is completed.

        // We need to check for either panics creating the `read` future or
        // completing the `read` future. Depending on test timing, the poisoning
        // may happen before or after the `read` is completed.
        let read_future_result =
            std::panic::catch_unwind(AssertUnwindSafe(|| self.driver.read(|_machine| 42)));
        if let Ok(read_future) = read_future_result {
            // Expected panic didn't happen immediately, try to complete the
            // future.
            let read_result = AssertUnwindSafe(read_future).catch_unwind().await;
            assert!(read_result.is_err(), "Expected read to panic on poisoned driver");
        } // else: Expected panic happened immediately.

        // `handle_event` calls are ignored if the driver is poisoned.
        let event = Event::RemoveAlarm(Alarm::new(SystemTime::now(), "after_poison"));
        self.handle_event(event);
    }

    /// Drops the underlying driver and returns the alarm receiver (which can be
    /// used to test whether the runner exited).
    fn drop_underlying_driver(self) -> mpsc::UnboundedReceiver<Alarm> {
        self.alarm_receiver
    }
}

#[tokio::test]
async fn test_set_alarm() {
    // Test plan: verify that the state machine is advanced correctly given a
    // single input event setting an alarm. Use FakeClock so that the call times
    // are predictable. Arrange for the state machine to be advanced (absent
    // other events) after 1 second.
    let fake_clock = FakeClock::create(SystemTime::now());
    let clock = fake_clock.clone().into_clock();
    let start_time = clock.now();
    let mut driver = AlarmDriver::new(clock.clone());

    // Start the state machine driver and verify that it is advanced
    // immediately.
    driver.start();
    let advance_time = driver.drain_expected_advance_call().await;
    assert_eq!(advance_time, start_time);

    // Set an alarm that will fire in 1 second.
    let alarm = Alarm::new(start_time + Duration::from_secs(1), "wake up!");
    let event1 = Event::SetAlarm(alarm.clone());
    driver.handle_event(event1.clone());

    // Verify that the expected calls were made.
    let calls: Vec<Call> = driver.drain_calls().await;
    assert_eq!(calls.len(), 1);
    let call = &calls[0];
    assert_eq!(call.time, start_time);
    assert_eq!(call.events.len(), 1);
    assert_eq!(call.events[0], event1);

    // Verify that no alarms were received yet (since the clock hasn't been
    // advanced).
    let alarms: Vec<Alarm> = driver.drain_alarms().await;
    assert_eq!(alarms, vec![]);

    // Advance the clock to the next time the state machine should be advanced.
    fake_clock.advance(Duration::from_secs(1));
    assert_eq!(clock.now(), start_time + Duration::from_secs(1));

    // Verify that the state machine advances even though there are no pending
    // events.
    let calls: Vec<Call> = driver.drain_calls().await;
    assert_eq!(
        calls,
        vec![Call {
            time: start_time + Duration::from_secs(1),
            events: vec![],
        }]
    );

    // Verify that the alarm was received.
    let alarms: Vec<Alarm> = driver.drain_alarms().await;
    assert_eq!(alarms, vec![alarm]);
}

#[tokio::test]
async fn test_advance_past_next_time() {
    // Test plan: illustrate the effect of advancing time past the desired
    // next time before the drive is able to schedule an `advance` call. We do
    // this by setting two alarms and then advancing time past the second alarm.
    // Both alarms should be triggered by a single call to `advance`. We use
    // FakeClock so that the call times are predictable.
    let fake_clock = FakeClock::create(SystemTime::now());
    let clock = fake_clock.clone().into_clock();
    let start_time = clock.now();
    let mut driver = AlarmDriver::new(clock.clone());

    // Start the state machine driver and verify that it is advanced
    // immediately.
    driver.start();
    let advance_time = driver.drain_expected_advance_call().await;
    assert_eq!(advance_time, start_time);

    // Set two alarms that will fire in 1 and 2 seconds, respectively.
    let alarm1 = Alarm::new(start_time + Duration::from_secs(1), "wake up!");
    let alarm2 = Alarm::new(start_time + Duration::from_secs(2), "wake up again!");
    let event1 = Event::SetAlarm(alarm1.clone());
    let event2 = Event::SetAlarm(alarm2.clone());
    driver.handle_event(event1.clone());
    driver.handle_event(event2.clone());

    // Drain calls for the initial events (the events may be batched, we don't
    // care). No alarms are expected yet.
    assert!(!driver.drain_calls().await.is_empty());
    assert!(driver.drain_alarms().await.is_empty());

    // Advance the clock past the second alarm.
    fake_clock.advance(Duration::from_secs(3));
    assert_eq!(clock.now(), start_time + Duration::from_secs(3));

    // Verify that the state machine advances and that both alarms were
    // received.
    let calls: Vec<Call> = driver.drain_calls().await;
    assert_eq!(
        calls,
        vec![Call {
            time: start_time + Duration::from_secs(3),
            events: vec![],
        }]
    );
    let alarms: Vec<Alarm> = driver.drain_alarms().await;
    assert_eq!(alarms, vec![alarm1, alarm2]);
}

#[tokio::test]
async fn test_next_time_some_to_none() {
    // Test plan: the state machine can request to be advanced at a specific
    // time but then change its mind and request to be advanced at a later time
    // or never. We demonstrate this pattern by setting an alarm in our test
    // state machine, then removing it. We verify that the state machine is not
    // advanced until an explicit event is received, even if the time of the
    // original alarm has passed. We use FakeClock so that the call times are
    // predictable.
    let fake_clock = FakeClock::create(SystemTime::now());
    let clock = fake_clock.clone().into_clock();
    let start_time = clock.now();
    let mut driver = AlarmDriver::new(clock.clone());

    // Start the state machine driver and verify that it is advanced
    // immediately.
    driver.start();
    let advance_time = driver.drain_expected_advance_call().await;
    assert_eq!(advance_time, start_time);

    // Set an alarm that will fire in 10 seconds.
    let alarm1 = Alarm::new(start_time + Duration::from_secs(10), "wake up!");
    driver.handle_event(Event::SetAlarm(alarm1.clone()));

    // Drain calls for the initial event (the event may be batched, we don't
    // care). No alarms are expected yet.
    assert_eq!(
        driver.drain_calls().await,
        vec![Call {
            time: start_time,
            events: vec![Event::SetAlarm(alarm1.clone())]
        },]
    );
    assert_eq!(driver.drain_alarms().await, vec![]);

    // Advance the clock to a time before the alarm would fire and remove the
    // alarm.
    fake_clock.advance(Duration::from_secs(2));
    driver.handle_event(Event::RemoveAlarm(alarm1.clone()));
    assert_eq!(
        driver.drain_calls().await,
        vec![Call {
            time: start_time + Duration::from_secs(2),
            events: vec![Event::RemoveAlarm(alarm1.clone())]
        },]
    );
    assert_eq!(driver.drain_alarms().await, vec![]);

    // Advance the clock to a time after the alarm would have fired and verify
    // that nothing happens.
    fake_clock.advance(Duration::from_secs(10));
    assert_eq!(clock.now(), alarm1.time + Duration::from_secs(2));
    assert_eq!(driver.drain_calls().await, vec![]);
    assert_eq!(driver.drain_alarms().await, vec![]);

    // Add a new alarm set to fire now and verify that the state machine
    // advances and that the new alarm sounds.
    let alarm2 = Alarm::new(clock.now(), "wake up again!");
    driver.handle_event(Event::SetAlarm(alarm2.clone()));
    assert_eq!(
        driver.drain_calls().await,
        vec![Call {
            time: clock.now(),
            events: vec![Event::SetAlarm(alarm2.clone())]
        },]
    );
    assert_eq!(driver.drain_alarms().await, vec![alarm2]);
}

#[tokio::test]
async fn test_tightening_next_time() {
    // Test plan: verify that the state machine is advanced at the expected time
    // when in successive calls to `advance`, it returns earlier next time
    // values. We use FakeClock so that the call times are predictable.
    let fake_clock = FakeClock::create(SystemTime::now());
    let clock = fake_clock.clone().into_clock();
    let start_time = clock.now();
    let mut driver = AlarmDriver::new(clock.clone());

    // Start the state machine driver and verify that it is advanced
    // immediately.
    driver.start();
    let advance_time = driver.drain_expected_advance_call().await;
    assert_eq!(advance_time, start_time);

    // Set two alarms that will fire in 2 and 1 seconds, respectively.
    let alarm1 = Alarm::new(start_time + Duration::from_secs(2), "wake up!");
    let alarm2 = Alarm::new(start_time + Duration::from_secs(1), "wake up again!");
    driver.handle_event(Event::SetAlarm(alarm1.clone()));

    // Drain calls for the initial event (the events may be batched o/w, and
    // we wouldn't test the code path we're interested in). No alarms are
    // expected yet.
    assert_eq!(
        driver.drain_calls().await,
        vec![Call {
            time: start_time,
            events: vec![Event::SetAlarm(alarm1.clone())]
        },]
    );
    assert_eq!(driver.drain_alarms().await, vec![]);

    // Schedule a second alarm to ensure that the state machine is advanced at
    // start_time + 1 second.
    driver.handle_event(Event::SetAlarm(alarm2.clone()));
    assert_eq!(
        driver.drain_calls().await,
        vec![Call {
            time: start_time,
            events: vec![Event::SetAlarm(alarm2.clone())]
        },]
    );
    assert_eq!(driver.drain_alarms().await, vec![]);

    // Advance the clock to the time alarm2 should fire.
    fake_clock.advance(Duration::from_secs(1));
    assert_eq!(clock.now(), start_time + Duration::from_secs(1));

    // Verify that the state machine is advanced at the expected time.
    let calls: Vec<Call> = driver.drain_calls().await;
    assert_eq!(
        calls,
        vec![Call {
            time: start_time + Duration::from_secs(1),
            events: vec![],
        }]
    );
    assert_eq!(driver.drain_alarms().await, vec![alarm2]);
}

#[tokio::test]
async fn test_recursion() {
    // Test plan: verify that the state machine is advanced correctly given a
    // recursive event.
    let mut driver = AlarmDriver::new(system_clock());

    // Start the state machine driver and verify that it is advanced
    // immediately. Since we're using a real clock, we don't know the
    // exact time at which it will be advanced.
    driver.start();
    let _ = driver.drain_expected_advance_call().await;

    // Create an event triggering an action triggering an event triggering an
    // alarm.
    let alarm = Alarm::new(SystemTime::now(), "wake up!");
    let action = Action::SoundAlarm(alarm.clone());
    let event =
        Event::Generic(Box::new(Action::Generic(Box::new(Event::Generic(Box::new(action))))));
    driver.event_handler().handle_event(event);

    // Verify that the alarm was received.
    let alarms: Vec<Alarm> = driver.drain_alarms().await;
    assert_eq!(alarms, vec![alarm]);
}

#[tokio::test]
async fn test_runner_exits_when_event_handlers_dropped() {
    // Test plan: verify that the runner exits when the driver is dropped. We
    // add an alarm to the state machine, perform a read to ensure that the
    // event is picked up, and then drop the driver. We verify that the alarm is
    // not received after it's due. We use FakeClock so that the call times are
    // predictable.
    let fake_clock = FakeClock::create(SystemTime::now());
    let clock = fake_clock.clone().into_clock();
    let mut driver = AlarmDriver::new(clock.clone());

    // Start the state machine driver and verify that it is advanced
    // immediately.
    driver.start();
    let advance_time = driver.drain_expected_advance_call().await;
    assert_eq!(advance_time, clock.now());

    // Schedule an alarm to fire in 1 second (it shouldn't fire because we're
    // dropping the driver).
    let alarm = Alarm::new(clock.now() + Duration::from_secs(1), "wake up!");
    driver.handle_event(Event::SetAlarm(alarm.clone()));

    // Perform a read to ensure that the event is picked up.
    driver.read(|_machine| ()).await;

    // Drop the driver and verify that the alarm is not received after it's
    // due.
    let mut alarm_receiver = driver.drop_underlying_driver();
    fake_clock.advance(Duration::from_secs(2));
    tokio::task::yield_now().await;
    assert!(alarm_receiver.try_recv().is_err());
}

#[tokio::test]
async fn test_panic_in_read_accessor() {
    // Test plan: verify that panicking in a `read` accessor propagates the
    // panic to the caller but does NOT poison the driver. After the panic,
    // confirm the driver is still operational with a normal read.
    let mut driver = AlarmDriver::new(system_clock());
    driver.start();
    let _ = driver.drain_expected_advance_call().await;

    // Trigger an accessor panic and verify it propagates.
    let read_result = AssertUnwindSafe(driver.read(|_machine| {
        panic!("accessor function panicked");
    }))
    .catch_unwind()
    .await;
    assert!(read_result.is_err(), "Expected accessor panic to propagate to read caller");

    // The driver should NOT be poisoned — a subsequent read should succeed.
    let value: i32 = driver.read(|_machine| 42).await;
    assert_eq!(value, 42);

    // A second accessor panic should also propagate without poisoning.
    let read_result2 = AssertUnwindSafe(driver.read(|_machine| -> i32 {
        panic!("second accessor panic");
    }))
    .catch_unwind()
    .await;
    assert!(read_result2.is_err(), "Expected second accessor panic to propagate");

    // Events should still be processed after repeated accessor panics.
    let alarm = Alarm::new(SystemTime::now(), "after accessor panic");
    driver.handle_event(Event::SetAlarm(alarm.clone()));
    let alarms = driver.drain_alarms().await;
    assert_eq!(alarms, vec![alarm]);
}

#[tokio::test]
async fn test_panic_in_advance() {
    // Test plan: verify that panicking in `StateMachine::advance`
    // poisons the driver.
    let mut driver = AlarmDriver::new(system_clock());
    driver.start();
    let event = Event::PanicOnAdvance;
    driver.handle_event(event);
    driver.assert_poisoned().await;
}

#[tokio::test]
async fn test_read_after_advance_panic() {
    // Test plan: verify that calling read() on an already-poisoned driver
    // panics because the mpsc send fails (the runner has exited and dropped
    // the receiver). We use assert_poisoned() to wait for the runner to
    // fully exit, then confirm the next read panics synchronously.
    let mut driver = AlarmDriver::new(system_clock());
    driver.start();
    driver.handle_event(Event::PanicOnAdvance);
    driver.assert_poisoned().await;

    // The runner is gone. This read must panic on the send path.
    let read_result = std::panic::catch_unwind(AssertUnwindSafe(|| driver.read(|_machine| 42)));
    match read_result {
        Err(_) => {}
        Ok(future) => {
            let await_result = AssertUnwindSafe(future).catch_unwind().await;
            assert!(await_result.is_err(), "Expected read to panic on poisoned driver");
        }
    }

    // Poisoning is permanent — a second read must also panic.
    let read_result2 = std::panic::catch_unwind(AssertUnwindSafe(|| driver.read(|_machine| 99)));
    match read_result2 {
        Err(_) => {}
        Ok(future) => {
            let await_result = AssertUnwindSafe(future).catch_unwind().await;
            assert!(await_result.is_err(), "Expected second read to also panic on poisoned driver");
        }
    }
}

#[tokio::test]
async fn test_panic_in_advance_skips_batched_accessor() {
    // Test plan: verify that when an advance panic and a read accessor are
    // picked up in the same recv_many batch, the accessor is never invoked
    // and the driver is poisoned. We drain the initial advance, then enqueue
    // PanicOnAdvance followed by a read() without yielding so both land in
    // one batch. The accessor sets a flag if it runs; we assert it never does.
    let mut driver = AlarmDriver::new(system_clock());
    driver.start();
    let _ = driver.drain_expected_advance_call().await;

    let accessor_ran = Arc::new(AtomicBool::new(false));
    let flag = accessor_ran.clone();

    // Enqueue the poisoning event.
    driver.handle_event(Event::PanicOnAdvance);
    // Enqueue the read without yielding so both land in one batch.
    let read_future = driver.read(move |_machine| {
        flag.store(true, Ordering::SeqCst);
        42
    });

    // The read should panic because the driver is poisoned.
    let read_result = AssertUnwindSafe(read_future).catch_unwind().await;
    assert!(read_result.is_err(), "Expected read to panic on poisoned driver");

    // The accessor must never have run.
    assert!(!accessor_ran.load(Ordering::SeqCst), "Accessor ran on a poisoned machine");

    // Confirm the driver is fully poisoned and stays that way.
    driver.assert_poisoned().await;
    driver.assert_poisoned().await;
}

#[tokio::test]
async fn test_panic_in_perform_action() {
    // Test plan: verify that panicking in an action handler fires an alert
    // but does NOT poison the driver. The state machine should continue
    // operating normally after the panic.
    let mut driver = AlarmDriver::new(system_clock());
    driver.start();
    let _ = driver.drain_expected_advance_call().await;

    driver.handle_event(Event::PanicOnPerform);

    // The driver should NOT be poisoned — a subsequent read should succeed.
    let value: i32 = driver.read(|_machine| 42).await;
    assert_eq!(value, 42);

    // A second action handler panic should also be absorbed.
    driver.handle_event(Event::PanicOnPerform);
    let value2: i32 = driver.read(|_machine| 99).await;
    assert_eq!(value2, 99);

    // Events should still be processed after repeated handler panics.
    let alarm = Alarm::new(SystemTime::now(), "after action handler panic");
    driver.handle_event(Event::SetAlarm(alarm.clone()));
    let alarms = driver.drain_alarms().await;
    assert_eq!(alarms, vec![alarm]);
}

#[test]
fn test_state_machine_output_combinators() {
    // Test plan: verify that `StateMachineOutput` combinators work correctly
    // when starting from an empty output and modifying it using:
    //  - `ensure_advance_by` from an empty output, with earlier, and with later
    //    times
    //  - `append_action`
    let mut output = StateMachineOutput::default();
    assert_eq!(output.actions(), &[]);
    assert_eq!(output.next_time_opt(), None);

    // Call `ensure_advance_by` with t+2 second.
    let now = SystemTime::now();
    output.ensure_advance_by(now + Duration::from_secs(2));
    assert_eq!(output.actions(), &[]);
    assert_eq!(output.next_time_opt(), Some(now + Duration::from_secs(2)));

    // Call `ensure_advance_by` with t+1 second, which should override the
    // previous later value.
    output.ensure_advance_by(now + Duration::from_secs(1));
    assert_eq!(output.actions(), &[]);
    assert_eq!(output.next_time_opt(), Some(now + Duration::from_secs(1)));

    // Call `ensure_advance_by` with t+3 second, which should not override the
    // previous earlier value.
    output.ensure_advance_by(now + Duration::from_secs(3));
    assert_eq!(output.actions(), &[]);
    assert_eq!(output.next_time_opt(), Some(now + Duration::from_secs(1)));

    // Call `append_action` with a sound-alarm action and verify that the output
    // is updated correctly.
    let alarm1 = Alarm::new(now + Duration::from_secs(2), "wake up!");
    let action1 = Action::SoundAlarm(alarm1);
    output.append_action(action1.clone());
    assert_eq!(output.actions(), std::slice::from_ref(&action1));
    assert_eq!(output.next_time_opt(), Some(now + Duration::from_secs(1)));

    // Call `append_action` with a second sound-alarm action and verify that the
    // output is updated correctly.
    let alarm2 = Alarm::new(now + Duration::from_secs(3), "wake up again!");
    let action2 = Action::SoundAlarm(alarm2);
    output.append_action(action2.clone());
    assert_eq!(output.actions(), &[action1, action2]);
    assert_eq!(output.next_time_opt(), Some(now + Duration::from_secs(1)));
}

#[tokio::test]
async fn test_abort_read_future() {
    // Test plan: verify that aborting a `read` future does not poison the
    // driver, and that the accessor function is not called.
    let mut driver = AlarmDriver::new(system_clock());
    driver.start();
    let future = driver.read(|_machine| panic!("accessor function should not be called"));
    drop(future);

    // Perform another read to make sure the driver is not poisoned.
    driver.read(|_machine| ()).await;
}

#[tokio::test]
async fn test_start_repeatedly() {
    // Test plan: verify that a state machine driver can only be started
    // successfully once.
    let mut driver = AlarmDriver::new(system_clock());
    assert!(driver.start());
    assert!(!driver.start());
    assert!(!driver.start());
}

// `start_paused`: Tokio virtual time jumps to the next timer as soon as the
// runtime is idle, so a leaked runner makes the timeout below fail immediately instead of waiting
// wall-clock seconds.
#[tokio::test(start_paused = true)]
async fn test_drop_after_repeated_start_aborts_runner() {
    // Test plan: catch a leaked background runner. A second `start` must leave
    // the `JoinHandle` in the driver so `Drop` can still `abort` the task.
    // Without restoring after `mem::take`, the second `start` drops that handle
    // and detaches the task; `drop(driver)` then finds nothing to abort and the
    // runner (plus everything it owns) leaks.
    //
    // Probe: the first action handler owns the only sender of a channel. When
    // `Drop` aborts the task, that handler (and thus the sender) is dropped and
    // `recv` returns `None`. An `EventHandler` clone is also held in the handler
    // so the runner cannot exit just because all input senders disappeared.
    let (call_sender, _call_receiver) = mpsc::unbounded_channel();
    let driver = StateMachineDriver::new(
        system_clock(),
        AlarmStateMachine {
            call_sender,
            alarms: BTreeSet::new(),
        },
        AlertOwnerTeam::CachingTeam,
    );
    let (handler_sender, mut handler_receiver) = mpsc::unbounded_channel::<()>();
    let event_handler: EventHandler<AlarmStateMachine> = driver.event_handler().clone();
    // Keep these alive inside the spawned task for the lifetime of the runner.
    assert!(driver.start(move |_actions: Vec<Action>| {
        let _captures = (&handler_sender, &event_handler);
    }));
    // Rejected start; Drop below still needs the JoinHandle to abort the runner.
    assert!(!driver.start(|_actions: Vec<Action>| {}));

    // Cancels the background task via the still-stored `JoinHandle`.
    drop(driver);
    let closed = tokio::time::timeout(Duration::from_secs(5), handler_receiver.recv()).await;
    // Ok(None): channel closed after abort dropped the action handler's sender.
    // Err(_): leak — sender still alive, recv never completes.
    assert_eq!(closed, Ok(None), "runner was not aborted when the driver was dropped");
}

#[tokio::test]
async fn test_start_after_events() {
    // Test plan: verify that events supplied to the driver before starting are
    // handled shortly after starting.
    let fake_clock = FakeClock::create(SystemTime::now());
    let clock = fake_clock.clone().into_clock();
    let mut driver = AlarmDriver::new(clock.clone());
    let start_time = clock.now();

    // Send an event before starting the driver.
    driver.handle_event(Event::SetAlarm(Alarm::new(start_time, "wake up!")));

    // Start the driver and verify that the state machine is advanced
    // immediately.
    driver.start();

    // Verify that the event supplied before starting is processed.
    let calls: Vec<Call> = driver.drain_calls().await;
    assert_eq!(calls.len(), 1);
    let call = &calls[0];
    assert_eq!(call.time, start_time);
    assert_eq!(call.events.len(), 1);
    assert_eq!(call.events[0], Event::SetAlarm(Alarm::new(start_time, "wake up!")));

    // Send another event after starting the driver.
    driver.handle_event(Event::SetAlarm(Alarm::new(
        start_time + Duration::from_secs(1),
        "wake up again!",
    )));

    // Verify that the event supplied after starting is processed.
    let calls: Vec<Call> = driver.drain_calls().await;
    assert_eq!(calls.len(), 1);
    let call = &calls[0];
    assert_eq!(call.events.len(), 1);
    assert_eq!(
        call.events[0],
        Event::SetAlarm(Alarm::new(start_time + Duration::from_secs(1), "wake up again!"))
    );
}

#[tokio::test]
async fn event_ignored_after_driver_dropped() {
    // Test plan: verify that events supplied to an event handler after the
    // driver has been dropped are ignored.
    let mut driver = AlarmDriver::new(system_clock());
    let event_handler = driver.event_handler().clone();
    driver.start();
    drop(driver);
    event_handler.handle_event(Event::PanicOnAdvance);
}

#[tokio::test]
async fn state_machine_advanced_immediately_on_start() {
    // Test plan: verify that the state machine is advanced immediately on start,
    // even when there are no pending events.
    let mut driver = AlarmDriver::new(system_clock());
    driver.start();
    let calls: Vec<Call> = driver.drain_calls().await;
    assert_eq!(calls.len(), 1);
    let call = &calls[0];
    assert_eq!(call.events.len(), 0);
}

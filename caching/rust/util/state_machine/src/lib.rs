use std::any::Any;
use std::mem;
use std::panic::AssertUnwindSafe;
use std::sync::MutexGuard;
use std::time::{Duration, SystemTime};
use std::vec::Vec;

use tokio::sync::{mpsc, oneshot};
use tokio::task::JoinHandle;
pub use caching_rust_util::caching_error_code::AlertOwnerTeam;
use caching_rust_util::panic_util::extract_panic_message;
use caching_rust_util::prefix_logger::PrefixLogger;
use caching_rust_util::{caching_error_code, prefix_critical_alert, Elapsed};
pub use caching_rust_util::{system_clock, Clock};

/// A driver that collaborates with a [`StateMachine`] to manage a combination
/// of state and asynchronous activity. See <<internal link>>
/// for details and code samples in Scala (and feel free to add Rust examples).
///
/// The code follows an active-passive pattern where the driver is active but
/// simple, while the state machine is passive but "decisive", in the sense of
/// deciding actions the driver should take after each event based on the
/// current state. Factoring the logic in this fashion makes the interesting
/// state machine logic simpler, more obviously correct, and easier to evolve.
///
/// The active driver is responsible for delivering batches of events of type
/// `M::Event` to the state machine. In response, the state machine updates its
/// state and returns a sequence of actions of type `M::Action` for the driver
/// to perform. It also returns the next time at which the state machine should
/// be advanced assuming no other events are received in the interim. An empty
/// event batch is supplied to the state machine in this case.
///
/// The core [`StateMachineDriver`] implementation is responsible for invoking
/// the state machine at the appropriate times, and the driver initiates actions
/// requested by the state machine using an action handler closure supplied to
/// [`StateMachineDriver::start`].
///
/// # Examples
///
/// See the tests in [`state_machine_test.rs`](../tests/state_machine_test.rs),
/// and the simple "assignment generator" example in
/// [`assignment_generator.rs`](../../examples/src/assignment_generator.rs).
///
/// # Ordering
///
/// Events and accessors are handled by the state machine in the order they are
/// received (via events supplied to [`StateMachineDriver::event_handler`] and
/// [`StateMachineDriver::read`] respectively).
///
/// Actions are initiated by the driver in the order they are requested by the
/// state machine (via [`StateMachine::advance`]). In practice, this means that
/// synchronous actions have a predictable order and are executed serially.
///
/// # Remarks on logical lifetimes
///
/// The driver takes ownership of the state machine passed to
/// [`StateMachineDriver::new`] and the action handler passed to
/// [`StateMachineDriver::start`]. Shortly after the driver is dropped, the
/// state machine and action handler are also dropped, and subsequent events
/// supplied to [`EventHandler`] will be ignored.
///
/// There is no built-in support for graceful shutdown of the state machine, but
/// this can be achieved by defining a custom event to request shutdown and a
/// custom action to signal completion to the application. The application can
/// then defer dropping the driver until after the state machine has completed
/// its shutdown sequence.
///
/// # Panics
///
/// Your application code must not panic. But if it does, the driver handles
/// panics as follows:
///
///  * **State machine panics** (in [`StateMachine::advance`]):
///    the driver is **poisoned**. The state machine is dropped
///    and a caching error alert is fired. After poisoning,
///    [`read`](Self::read) panics and
///    [`EventHandler::handle_event`] silently drops events.
///
///  * **Action handler panics** (closure passed to
///    [`start`](Self::start)): a caching error alert is
///    fired but the driver is **not** poisoned, since the handler
///    never borrows the state machine. While larger application
///    invariants may be violated when actions panic, the state
///    machine itself remains consistent, so the defensive
///    (anti-)principle suggests we keep on running.
///
///  * **Accessor panics** (closures passed to [`read`](Self::read)):
///    the panic propagates to the `read` caller, analogous to
///    [`tokio::task::JoinHandle`]. The driver is **not** poisoned;
///    accessors observe `&M` and cannot corrupt `M` unless it has
///    interior mutability, which is the caller's responsibility.
///
/// Details of panic handling are subject to change.
pub struct StateMachineDriver<M: StateMachine> {
    // Implementation note: where's the actual state? An internal Runner owns
    // the state machine and runs on a background tokio task, which exits
    // shortly after this driver and all outstanding event handlers are dropped.
    event_handler: EventHandler<M>,

    /// High-level state of the driver. None in transitional states (while
    /// starting or dropping).
    state: std::sync::Mutex<Option<DriverState<M>>>,
}

/// Supplies events to the state machine. [`StateMachineDriver`] exposes an
/// event handler that can be cloned and used to supply events to the state
/// machine from multiple tasks, e.g., from an action handler supplied to
/// [`StateMachineDriver::start`].
pub struct EventHandler<M: StateMachine> {
    /// Sender used to supply events to the state machine. After the driver has
    /// been dropped, the receiver will be closed and subsequent events should
    /// be ignored.
    input_sender: mpsc::UnboundedSender<Input<M>>,
}

impl<M: StateMachine> EventHandler<M> {
    /// Enqueues `event` for the state machine. If the driver has
    /// been dropped or poisoned, the event is silently ignored.
    pub fn handle_event(&self, event: M::Event) {
        let _ = self.input_sender.send(Input::Event(event));
    }
}

// Manual implementation of Clone is required because M need not be Clone.
impl<M: StateMachine> Clone for EventHandler<M> {
    fn clone(&self) -> Self {
        Self {
            input_sender: self.input_sender.clone(),
        }
    }
}

impl<M: StateMachine> StateMachineDriver<M> {
    /// Creates a new [`StateMachineDriver`] with the specified clock, state
    /// machine, and alert owner team.
    ///
    /// # Arguments
    ///
    ///  * `clock` - The clock used to provide current time to the state machine
    ///  * `machine` - The passive state machine implementation
    ///  * `alert_owner_team` - The team used for alert routing on panics
    pub fn new(clock: Clock, machine: M, alert_owner_team: AlertOwnerTeam) -> Self {
        // StateMachineDriver is a generic utility parameterized by M -- it has no
        // domain-specific identity of its own to use as prefix. The concrete state
        // machine (M) owns the meaningful prefix for its own logging.
        let logger = PrefixLogger::new("StateMachineDriver", "");
        let (input_sender, input_receiver) = mpsc::unbounded_channel();
        let event_handler = EventHandler { input_sender };

        // The state machine should be advanced immediately on start, even if
        // there are no pending events.
        let next_time_opt = Some(clock.now());
        let runner = Runner {
            clock,
            input_receiver,
            machine,
            next_time_opt,
            alert_owner_team,
            logger,
        };
        let state = std::sync::Mutex::new(Some(DriverState::Init(runner)));
        Self {
            event_handler,
            state,
        }
    }

    /// Starts the state machine driver. Events passed to the driver before
    /// starting will be handled shortly after starting. Returns true if the
    /// state machine driver was started successfully, or false if it was already
    /// running.
    ///
    /// # Arguments
    ///
    ///  * `action_handler` - Called when the state machine requests actions.
    ///    Actions are supplied in a batch since there may be efficiency benefits
    ///    for some implementations; callers that want to process actions one by
    ///    one can loop. To enqueue events from the handler, clone
    ///    [`Self::event_handler`] before calling `start` and capture that clone.
    pub fn start(&self, action_handler: impl Fn(Vec<M::Action>) + Send + 'static) -> bool {
        let mut state: MutexGuard<'_, Option<DriverState<M>>> = self.state.lock().unwrap();
        // Need an owned `Runner` to move into the spawned task; take it out of
        // the mutex (non-`Init` values are restored below).
        match mem::take(&mut *state) {
            Some(DriverState::Init(mut runner)) => {
                let join_handle: JoinHandle<()> = tokio::task::spawn(async move {
                    runner.run(action_handler).await;
                });
                *state = Some(DriverState::Started(join_handle));
                true
            }
            other => {
                // Restore whatever we took (e.g. `Started`) so a no-op second
                // `start` does not drop the background-task handle.
                *state = other;
                false
            }
        }
    }

    /// Returns the event handler for the state machine driver.
    pub fn event_handler(&self) -> &EventHandler<M> {
        &self.event_handler
    }

    /// Reads the state machine using the given accessor function.
    ///
    /// # Panics
    ///
    /// Panics if the driver has been poisoned by a panic in
    /// [`StateMachine::advance`]. If the *accessor* itself panics,
    /// that panic propagates to this caller and the driver
    /// continues operating normally.
    pub async fn read<T: Send + 'static>(
        &self,
        accessor: impl FnOnce(&M) -> T + Send + 'static,
    ) -> T {
        // Use a oneshot channel to send the results of the accessor function
        // back to the caller.
        let (sender, receiver) = oneshot::channel::<Result<T, Box<dyn Any + Send>>>();
        // Wrap the accessor function in an Input::Access to be sent to the
        // runner.
        let f = move |machine: &M| {
            if sender.is_closed() {
                // Short-circuit if the receiver hangs up, which will happen if
                // the `read` future is abandoned.
                return;
            }
            // Safety(UnwindSafe): accessor holds &M (shared ref) and cannot
            // leave the machine in a partially-mutated state.
            let result = std::panic::catch_unwind(AssertUnwindSafe(|| accessor(machine)));
            // Drop send failures when `read` cancelled mid-accessor.
            let _ = sender.send(result);
        };
        let input: Input<M> = Input::Access(Box::new(f));

        // Send the input to the runner. Panics if the driver is already
        // poisoned because the mpsc receiver (owned by the runner) will have
        // been dropped.
        self.event_handler
            .input_sender
            .send(input)
            .expect("state machine driver is poisoned");

        // Await the result of the accessor function. Panics if the driver
        // is poisoned because the oneshot sender (owned by the runner) will
        // have been dropped.
        match receiver.await.expect("state machine driver is poisoned") {
            Ok(result) => result,
            Err(payload) => {
                // This is the case where the accessor function panicked.
                // Resume unwinding the panic to the caller.
                std::panic::resume_unwind(payload);
            }
        }
    }
}

impl<M: StateMachine> Drop for StateMachineDriver<M> {
    fn drop(&mut self) {
        // Abort background task when a driver that has been started is dropped.
        if let Ok(mut state) = self.state.lock() {
            let state = mem::take(&mut *state);
            if let Some(DriverState::Started(join_handle)) = state {
                join_handle.abort_handle().abort();
            }
        }
    }
}

/// Trait implemented by a state machine, which is a passive (but decisive)
/// partner to an active (but simple) [`StateMachineDriver`]. See
/// <<internal link>> for details and code samples.
///
/// By "decisive", we mean that it maintains the state machine in response to
/// events and decides what actions should be performed by the driver after each
/// event. For example, it might decide whether and when to start an RPC.
pub trait StateMachine: Send + 'static {
    type Event: Send + 'static;
    type Action: Send + 'static;

    /// Processes new input events received by the driver as of `now`. If the
    /// previous `StateMachineOutput` had a `next_time_opt` value and no events
    /// have been received in the interim, `advance` may be called with an
    /// empty batch.
    fn advance(
        &mut self,
        now: SystemTime,
        events: Vec<Self::Event>,
    ) -> StateMachineOutput<Self::Action>;
}

/// The output of a [`StateMachine`] after it processes an event.
pub struct StateMachineOutput<Action> {
    actions: Vec<Action>,

    next_time_opt: Option<SystemTime>,
}

impl<Action> StateMachineOutput<Action> {
    pub fn new() -> Self {
        Self {
            actions: Vec::new(),
            next_time_opt: None,
        }
    }

    /// Actions that must be executed by the driver for the state machine.
    /// Actions are supplied to the driver in this order.
    pub fn actions(&self) -> &[Action] {
        &self.actions
    }

    /// The next time the state machine should be advanced by calling
    /// [`StateMachine::advance`], assuming that no other signals are
    /// received in the meantime. As documented in [`StateMachine::advance`],
    /// it's correct (though wasteful) to advance earlier, and correct (though
    /// possibly sub-optimal) to advance later.
    ///
    /// In response to a subsequent signal, the state machine may return an
    /// earlier or later `next_time_opt` value, and caller need only respect the
    /// latest `next_time_opt` value returned by the state machine.
    ///
    /// When this value is `None`, it means that the state machine should only
    /// be called again if an explicit event is processed, not merely in
    /// response to the passage of time.
    ///
    /// This value supersedes any `next_time_opt` values that were previously
    /// returned by the state machine: it is ok for the state machine to say
    /// "call me later", "call me earlier", or "call me never".
    pub fn next_time_opt(&self) -> Option<SystemTime> {
        self.next_time_opt
    }

    /// Ensures that the state machine is advanced at `next_time` or earlier.
    pub fn ensure_advance_by(&mut self, next_time: SystemTime) {
        match self.next_time_opt {
            Some(current_next_time) => {
                if next_time < current_next_time {
                    self.next_time_opt = Some(next_time);
                }
            }
            None => {
                self.next_time_opt = Some(next_time);
            }
        }
    }

    /// Appends the given action to the state machine output. Actions will be
    /// supplied to the driver in the order they are added.
    pub fn append_action(&mut self, action: Action) {
        self.actions.push(action);
    }
}

impl<Action> Default for StateMachineOutput<Action> {
    fn default() -> Self {
        Self::new()
    }
}

/// An input supplied to the state machine runner. Either an event or a request
/// to access the state machine.
enum Input<M: StateMachine> {
    /// An event enqueued by [`StateMachineDriver::handle_event`].
    Event(M::Event),
    /// A request to access the state machine via [`StateMachineDriver::read`].
    Access(Box<dyn FnOnce(&M) + Send + 'static>),
}

/// High-level states for [`StateMachineDriver`].
enum DriverState<M: StateMachine> {
    /// Initial state of the driver before running `Runner` in background task.
    Init(Runner<M>),

    /// The driver has successfully started. The handle is used to abort the background
    /// runner task when the driver is dropped.
    Started(JoinHandle<()>),
}

/// A runner that receives events and advances the state machine.
struct Runner<M: StateMachine> {
    clock: Clock,
    input_receiver: mpsc::UnboundedReceiver<Input<M>>,
    machine: M,
    next_time_opt: Option<SystemTime>,
    /// The team used for alert routing on panics.
    alert_owner_team: AlertOwnerTeam,
    logger: PrefixLogger,
}

/// The maximum number of inputs to process in a single iteration of the
/// runner.
const MAX_INPUTS: usize = 128;

impl<M: StateMachine> Runner<M> {
    /// Runs the state machine driver loop.
    ///
    /// The `action_handler` is marked `mut` to allow passing `&mut` references
    /// to it. This signals to the compiler that references are exclusive,
    /// requiring only `Send` rather than `Sync`. We never actually mutate the
    /// handler since `Fn::call` only needs `&self`.
    async fn run(&mut self, mut action_handler: impl Fn(Vec<M::Action>) + Send + 'static) {
        loop {
            let now = self.clock.now();
            let sleep_for: Duration = match self.next_time_opt {
                // duration_since returns an error when next_time is earlier
                // than now, so we use Duration::ZERO instead, indicating that
                // the state machine should be advanced immediately, even if
                // there are no pending events.
                Some(next_time) => next_time.duration_since(now).unwrap_or(Duration::ZERO),
                None => Duration::MAX,
            };
            let mut inputs: Vec<Input<M>> = vec![];
            // Inputs take precedence over the timer; see Clock::timeout.
            match self
                .clock
                .timeout(sleep_for, self.input_receiver.recv_many(&mut inputs, MAX_INPUTS))
                .await
            {
                Ok(0) => {
                    // An empty buffer indicates that the sender has been dropped,
                    // which happens when the state machine driver is destroyed.
                    // Exit the loop.
                    return;
                }
                Ok(_) => {
                    // Inputs received; process them below.
                }
                Err(Elapsed) => {
                    // Timer fired with no inputs; advance the state machine below.
                }
            }

            let now = self.clock.now();

            // Process the events and inputs in order. Since the state machine
            // accepts event batches, we break them into batches potentially
            // interspersed with accessors.
            let mut events = vec![];
            for input in inputs {
                match input {
                    Input::Event(event) => {
                        events.push(event);
                    }
                    Input::Access(accessor) => {
                        // Flush any accumulated events to the state machine
                        // before calling the accessor to preserve ordering.
                        if !self
                            .maybe_advance(&mut action_handler, now, mem::take(&mut events))
                            .await
                        {
                            return;
                        }
                        accessor(&self.machine);
                    }
                }
            }
            // Flush the remaining events (if any).
            if !self.maybe_advance(&mut action_handler, now, events).await {
                return;
            }
        } // loop
    } // fn Runner::run

    /// Advances the state machine if `events` is non-empty or if
    /// `self.next_time_opt` is due.
    ///
    /// While this function is marked `async`, it is actually synchronous. It
    /// needs access to the task context to poll action futures to allow them
    /// to complete synchronously when possible. See remarks on
    /// [`StateMachineDriver`] about ordering of synchronous actions.
    /// Returns `false` if the driver has been poisoned and the runner should
    /// exit.
    async fn maybe_advance(
        &mut self,
        action_handler: &mut (impl Fn(Vec<M::Action>) + Send + 'static),
        now: SystemTime,
        events: Vec<M::Event>,
    ) -> bool {
        // When there are no events, advance only if next time is due.
        if events.is_empty() && self.next_time_opt.is_none_or(|next_time| now < next_time) {
            return true;
        }
        // Safety(UnwindSafe): if advance panics, the machine may be in an
        // inconsistent state, but we return false and run() exits immediately,
        // dropping the machine without further use.
        let advance_result =
            std::panic::catch_unwind(AssertUnwindSafe(|| self.machine.advance(now, events)));
        let output = match advance_result {
            Ok(output) => output,
            Err(payload) => {
                let msg = extract_panic_message(&payload);
                prefix_critical_alert!(
                    self.logger,
                    caching_error_code::state_machine_advance_panic(self.alert_owner_team),
                    "{}",
                    msg
                );
                return false;
            }
        };

        // We intentionally overwrite `next_time_opt` whenever the state
        // machine is advanced.
        self.next_time_opt = output.next_time_opt;

        // Safety(UnwindSafe): the handler is Fn (not FnMut), so it has no
        // exclusively-owned mutable state that a panic could corrupt. Interior
        // mutability (e.g. RefCell) is the caller's responsibility.
        let action_result =
            std::panic::catch_unwind(AssertUnwindSafe(|| action_handler(output.actions)));
        if let Err(payload) = action_result {
            let msg = extract_panic_message(&payload);
            prefix_critical_alert!(
                self.logger,
                caching_error_code::state_machine_action_handler_panic(self.alert_owner_team),
                "{}",
                msg
            );
        }
        true
    }
}

use std::collections::{BTreeMap, HashMap};
use std::future::Future;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant};

use tokio::sync::{mpsc, oneshot};
use tokio::task::JoinHandle;

use crate::Clock;

/// A sequential executor that provides an abstraction of a thread of
/// execution where each piece of work is executed sequentially one at a time,
/// i.e., not concurrently. This executor is designed for cooperative task
/// management where the executor guards some coarse-grained piece of state. You
/// can think of this as a "coarse-grained" lock with the nice property that you
/// don't have to explicitly acquire or release locks. However, it is the
/// responsibility of each work item to not use the executor for a "long time"
/// or block the executor (since remaining items will simply wait for such
/// long/blocking work items to finish).
///
/// # State Management
///
/// The executor manages exclusive state that can only be accessed within task
/// closures. If you need shared state that can be accessed both inside and
/// outside tasks, use `Arc<T>` and include a clone of the Arc in your exclusive
/// state.
///
/// # Lifecycle
///
/// While it is typical to call [SequentialExecutor::start] before submitting
/// work, tasks may be submitted before start is called. These tasks will be
/// buffered internally and processed once the background runner is started.
///
/// Note that regardless of when tasks are submitted, if the executor is dropped
/// before outstanding tasks are processed, those pending tasks may never run.
///
/// # Example
///
/// ```no_run
/// use std::sync::Arc;
/// use std::time::Duration;
/// use caching_rust_util::system_clock;
/// use caching_rust_util::sequential_executor::SequentialExecutor;
///
/// struct Config {
///     name: String,
/// }
///
/// struct Counter {
///     config: Arc<Config>,
///     value: i32,
/// }
///
/// # #[tokio::main]
/// # async fn main() {
/// let config = Arc::new(Config { name: "my-counter".to_string() });
/// let executor = SequentialExecutor::new(
///     Counter {
///         config: config.clone(),
///         value: 0,
///     },
///     system_clock(),
/// );
/// let runtime_handle = tokio::runtime::Handle::current();
/// executor.start(&runtime_handle);
///
/// // Tasks can access shared state via Arc and mutate exclusive state.
/// let result = executor.call(|_executor, counter| {
///     counter.value += 1;
///     println!("{}: incremented to {}", counter.config.name, counter.value);
///     counter.value
/// }).await;
///
/// // Tasks can schedule additional work using the executor reference.
/// // This is useful for implementing loops, periodic tasks, and task chains.
/// executor.execute(|executor, counter| {
///     counter.value += 10;
///
///     // Schedule a follow-up task.
///     executor.schedule(|_executor, counter| {
///         println!("Follow-up: value is {}", counter.value);
///     }, Duration::from_millis(100));
/// });
///
/// // Shared state is accessible outside tasks via the Arc.
/// println!("Executor name: {}", config.name);
/// # }
/// ```
///
/// # Ordering
///
/// The executor is a FIFO abstraction. Commands added with zero delay --
/// e.g. via `execute` or `call` -- will run in the order they are added.
///
/// The executor additionally maintains an "internal ordering guarantee"
/// that applies to all commands, both immediate and delayed:
///
/// **If task A is added with delay X before task B is added with delay Y,
/// and X <= Y, then A runs before B.**
///
/// However, the details of the contract for tasks added with delays may
/// change over time, and a looser fairness contract may ultimately be
/// implemented. FIFO will always be maintained for tasks added with zero
/// delay.
///
/// For example:
///
/// ```ignore
/// // a() runs before b().
/// executor.execute(|_executor, state| a(state));
/// executor.execute(|_executor, state| b(state));
///
/// // c() runs before d().
/// executor.schedule(|_executor, state| c(state), Duration::from_secs(10));
/// executor.schedule(|_executor, state| d(state), Duration::from_secs(10));
///
/// // e() runs before f().
/// executor.execute(|_executor, state| e(state));
/// executor.schedule(|_executor, state| f(state), Duration::ZERO);
///
/// // g() runs before h().
/// executor.schedule(|_executor, state| g(state), Duration::ZERO);
/// executor.execute(|_executor, state| h(state));
///
/// // i() runs before j().
/// executor.schedule(|_executor, state| i(state), Duration::from_secs(2));
/// executor.schedule(|_executor, state| j(state), Duration::from_secs(3));
///
/// // k() runs before l().
/// executor.execute(|_executor, state| k(state));
/// executor.schedule(|_executor, state| l(state), Duration::from_nanos(1));
///
/// // Ordering of m and n is non-deterministic (how much delay between
/// // calls?).
/// executor.schedule(|_executor, state| m(state), Duration::from_nanos(1));
/// executor.execute(|_executor, state| n(state));
/// ```
///
/// # Fairness
///
/// A common failure mode in executor implementations is starvation of scheduled
/// commands when the queue is backlogged with immediate commands. This can have
/// serious consequences. Consider for example what happens when scheduled
/// background garbage collection is deprioritized because a system is heavily
/// loaded: accumulation of garbage makes all work more expensive, which
/// exacerbates the work backlog, and you have a cascade failure. This executor
/// implements a simple policy to prevent this failure: when a command is added,
/// either for immediate execution or with some delay, its "desired execution
/// time" is recorded (basically, "now" plus some non-negative delay), and
/// commands are executed in order of their desired execution time. When two
/// commands have the same desired execution time, the implementation breaks
/// ties by using task IDs (which are monotonically increasing based on
/// insertion order). The effect of this policy is that all commands, whether
/// scheduled or immediate, experience the same delay relative to their desired
/// execution time based on the current backlog in the executor.
///
/// # Cancellation
///
/// All tasks can be cancelled best-effort. For `call` and `call_with_delay`,
/// dropping the future will cancel the task. For `execute` and `schedule`,
/// an explicit handle is returned that can be used to cancel the task.
///
/// - Cancelling does not require a scan of all scheduled tasks. Internally,
///   this implementation uses a `HashMap` to map task IDs to their ordering
///   keys, allowing O(1) lookup followed by O(log(n)) removal from the
///   `BTreeMap`. Cancelled tasks do not occupy any memory.
///
/// # Self-Scheduling Patterns
///
/// Tasks receive a reference to the executor, allowing them to schedule
/// additional work from within the task itself. This is commonly used for
/// implementing loops, periodic execution, and task chains without blocking
/// the executor.
///
/// ## Example: Periodic Task
///
/// ```no_run
/// use std::time::Duration;
/// use caching_rust_util::system_clock;
/// use caching_rust_util::sequential_executor::SequentialExecutor;
///
/// struct State {
///     counter: i32,
/// }
///
/// # #[tokio::main]
/// # async fn main() {
/// let executor = SequentialExecutor::new(State { counter: 0 }, system_clock());
/// let runtime_handle = tokio::runtime::Handle::current();
/// executor.start(&runtime_handle);
///
/// // Helper function to schedule a task that runs periodically (once per
/// // second) until the executor is dropped.
/// fn schedule_periodic_update(executor: &SequentialExecutor<State>) {
///     executor.schedule(
///         move |executor, state| {
///             state.counter += 1;
///             println!("Counter: {}", state.counter);
///             // Re-schedule itself for the next interval.
///             schedule_periodic_update(executor);
///         },
///         Duration::from_secs(1),
///     );
/// }
///
/// schedule_periodic_update(&executor);
/// # }
/// ```
///
/// ## Example: Task Chain
///
/// ```no_run
/// use std::time::Duration;
/// use caching_rust_util::system_clock;
/// use caching_rust_util::sequential_executor::SequentialExecutor;
///
/// # #[tokio::main]
/// # async fn main() {
/// let executor = SequentialExecutor::new(0, system_clock());
/// let runtime_handle = tokio::runtime::Handle::current();
/// executor.start(&runtime_handle);
///
/// executor.execute(|executor, state| {
///     println!("Step 1: state = {}", state);
///     *state += 1;
///
///     // Schedule next step.
///     executor.schedule(
///         |executor, state| {
///             println!("Step 2: state = {}", state);
///             *state += 1;
///
///             // Schedule final step.
///             executor.schedule(
///                 |_executor, state| {
///                     println!("Step 3: state = {}", state);
///                     *state += 1;
///                 },
///                 Duration::from_millis(10),
///             );
///         },
///         Duration::from_millis(10),
///     );
/// });
/// # }
/// ```
pub struct SequentialExecutor<State>
where
    State: Send + 'static,
{
    /// Sender for enqueuing, scheduling, and cancelling tasks.
    sender: mpsc::UnboundedSender<Input<State>>,

    /// Type-erased wrapper around `sender` for sending cancel messages.
    cancel_sender: Arc<dyn CancelSender>,

    /// Counter used to generate unique task IDs. These IDs are used to identify
    /// tasks for cancellation, and (since they are strictly monotonically
    /// increasing) to maintain the internal ordering guarantee.
    task_id_counter: Arc<AtomicU64>,

    /// Clock for time-related operations.
    clock: Clock,

    /// Runner state to track the background runner task, used for explicit shutdown.
    runner_state: Mutex<RunnerState<State>>,
}

impl<State> SequentialExecutor<State>
where
    State: Send + 'static,
{
    /// Creates a new, unstarted sequential executor with the given exclusive state
    /// and clock.
    ///
    /// This method does not require a Tokio runtime. Call [`Self::start`] with
    /// a Tokio runtime handle before submitting work.
    pub fn new(exclusive_state: State, clock: Clock) -> Self {
        let (sender, receiver) = mpsc::unbounded_channel::<Input<State>>();

        // Heap allocate a single dynamic dispatch object for the cancel
        // sender.
        let cancel_sender: Arc<dyn CancelSender> = Arc::new(CancelSenderImpl {
            sender: sender.clone(),
        });

        // Create the runner and initialize its state.
        let task_id_counter = Arc::new(AtomicU64::new(0));
        let runner = Runner::new(exclusive_state, receiver);
        let runner_state: Mutex<RunnerState<State>> = Mutex::new(RunnerState::Init(runner));

        Self {
            sender,
            cancel_sender,
            task_id_counter,
            clock,
            runner_state,
        }
    }

    /// Starts the background runner for this executor, using the provided
    /// Tokio runtime handle. Returns `true` if the runner was successfully
    /// started, or `false` if the executor is already running.
    pub fn start(&self, handle: &tokio::runtime::Handle) -> bool {
        let mut state_guard: MutexGuard<'_, RunnerState<State>> = self.runner_state.lock().unwrap();

        // Temporarily replace the runner state with Unowned, compute the new
        // state and put it back.
        let state = std::mem::replace(&mut *state_guard, RunnerState::Unowned);

        match state {
            RunnerState::Init(runner) => {
                let runner_handle: JoinHandle<()> = handle.spawn(runner.run(
                    self.sender.clone(),
                    Arc::clone(&self.cancel_sender),
                    Arc::clone(&self.task_id_counter),
                    self.clock.clone(),
                ));
                *state_guard = RunnerState::Started(runner_handle);
                true // started successfully
            }
            // If already started, just put the handle back (no-op as documented).
            RunnerState::Started(runner_handle) => {
                *state_guard = RunnerState::Started(runner_handle);
                false // already started
            }
            // If unowned (e.g. internal executor), keep it unowned.
            RunnerState::Unowned => {
                *state_guard = RunnerState::Unowned;
                false // already unowned
            }
        }
    }

    /// Returns the clock used by this executor.
    pub fn clock(&self) -> &Clock {
        &self.clock
    }

    /// Calls the given function on the executor and yields the result. Dropping
    /// the returned future will cancel execution of the function best-effort. If
    /// the executor is dropped before the task completes, it may never complete.
    ///
    /// If it is important to guarantee that the task will run to completion, you
    /// can either use `tokio::task::timeout` to set a timeout, or ensure that the
    /// executor survives long enough for the task to complete, e.g.:
    ///
    /// ```
    /// # use std::sync::Arc;
    /// # use caching_rust_util::{system_clock, Clock};
    /// # use caching_rust_util::sequential_executor::SequentialExecutor;
    /// struct MyClass {
    ///     executor: SequentialExecutor<i32>,
    /// }
    /// impl MyClass {
    ///     fn new(clock: Clock, runtime_handle: &tokio::runtime::Handle) -> Self {
    ///         let executor = SequentialExecutor::new(0, clock);
    ///         executor.start(runtime_handle);
    ///         Self { executor }
    ///     }
    ///     async fn increment_and_get(&self) -> i32 {
    ///         // The executor lives long enough for the task due to `&self`.
    ///         self.executor.call(|_executor, state| {*state += 1; *state}).await
    ///     }
    /// }
    /// # #[tokio::main]
    /// # async fn main() {
    ///     let runtime_handle = tokio::runtime::Handle::current();
    ///     let my_class = MyClass::new(system_clock(), &runtime_handle);
    ///     let result = my_class.increment_and_get().await;
    ///     assert_eq!(result, 1);
    /// # }
    /// ```
    pub fn call<T, R>(&self, task: T) -> impl Future<Output = R> + Send + 'static
    where
        T: FnOnce(&SequentialExecutor<State>, &mut State) -> R + Send + 'static,
        R: Send + 'static,
    {
        self.call_with_delay(task, Duration::ZERO)
    }

    /// Calls the given function on the executor with the given delay then
    /// yields the result. Dropping the returned future will cancel execution
    /// of the function best-effort. If the executor is dropped before the task
    /// completes, or if the scheduled execution time cannot be represented
    /// (e.g. due to overflow), the returned future may never complete.
    pub fn call_with_delay<T, R>(
        &self,
        task: T,
        delay: Duration,
    ) -> impl Future<Output = R> + Send + 'static
    where
        T: FnOnce(&SequentialExecutor<State>, &mut State) -> R + Send + 'static,
        R: Send + 'static,
    {
        // Supply the call result via a oneshot channel.
        let (tx, rx) = oneshot::channel();

        // Schedule a task accepting the executor and state, applying them to
        // the supplied task closure, and sending the result back via the
        // oneshot channel.
        let schedule_task = move |executor: &SequentialExecutor<State>, state: &mut State| {
            // Optimization: don't bother running the task if the oneshot
            // receiver has already been dropped (which happens if the future is
            // dropped before the task completes).
            if tx.is_closed() {
                return;
            }
            let result = task(executor, state);
            // Race: between tx.is_closed() and f(executor, state), the receiver
            // may be dropped. It's ok for the send to fail in this case.
            let _ = tx.send(result);
        };
        let handle = self.schedule(schedule_task, delay);

        // Create an RAII guard that will cancel the task if this future is
        // dropped before the task completes.
        struct CancelGuard {
            handle: Option<Handle>,
        }
        impl Drop for CancelGuard {
            fn drop(&mut self) {
                if let Some(handle) = self.handle.take() {
                    handle.cancel();
                }
            }
        }
        let mut guard = CancelGuard {
            handle: Some(handle),
        };
        async move {
            let result: Result<R, _> = rx.await;
            // Mark the task as completed so the guard doesn't cancel it.
            guard.handle = None;
            drop(guard);
            match result {
                Ok(result) => result,
                // rx.await fails if the sender is dropped before the task
                // completes. This may happen if there is a panic in the runner,
                // or if the executor is dropped. In the former case, we make no
                // guarantees about the future. In the latter case, we document
                // that the future may never complete, which is the effect of
                // awaiting a pending future.
                Err(_) => std::future::pending().await,
            }
        }
    }

    /// Fire-and-forget execution of a function, handle can be used to cancel
    /// the task best-effort. If the executor is dropped before the task
    /// completes, it may never run.
    pub fn execute<T>(&self, task: T) -> Handle
    where
        T: FnOnce(&SequentialExecutor<State>, &mut State) + Send + 'static,
    {
        self.schedule(task, Duration::ZERO)
    }

    /// Fire-and-forget scheduled execution of a function, handle can be used to
    /// cancel the task best-effort. If the executor is dropped before the task
    /// completes, it may never run. If the scheduled execution time cannot be
    /// represented (e.g. due to overflow), the task is retained but is never
    /// considered due.
    pub fn schedule<T>(&self, task: T, delay: Duration) -> Handle
    where
        T: FnOnce(&SequentialExecutor<State>, &mut State) + Send + 'static,
    {
        // Generate an id that is unique to this task. It will be used to
        // identify the task by the Runner if it is cancelled.
        let id: u64 = self.task_id_counter.fetch_add(1, Ordering::Relaxed);
        let enqueue_time = self.clock.instant();
        let task_fn = Box::new(task);
        let task = Task {
            id,
            enqueue_time,
            delay,
            task_fn,
        };
        let input = Input::Enqueue(task);
        self.sender
            .send(input)
            .expect("queue receiver unexpectedly closed: poisoned?");
        let cancel_sender = Arc::clone(&self.cancel_sender);
        Handle { cancel_sender, id }
    }
}

impl<State> Drop for SequentialExecutor<State>
where
    State: Send + 'static,
{
    fn drop(&mut self) {
        // Only external executors (those with a runner state) can abort the
        // runner task.
        let runner_state: MutexGuard<'_, RunnerState<State>> = self.runner_state.lock().unwrap();

        match &*runner_state {
            RunnerState::Init(_) => {}
            RunnerState::Started(join_handle) => {
                join_handle.abort();
            }
            RunnerState::Unowned => {}
        };
    }
}

/// A handle that can be used to cancel a scheduled task. Cancellation is
/// best-effort: if the task has already started running or the executor has
/// been dropped, cancellation will have no effect.
pub struct Handle {
    cancel_sender: Arc<dyn CancelSender>,
    id: u64,
}

impl Handle {
    pub fn cancel(self) {
        self.cancel_sender.cancel(self.id);
    }
}

//
// Internal implementation types and helpers below this point.
//

/// Desired execution time for a task. `Never` is used when the scheduled
/// execution time overflows; such tasks remain owned by the executor until
/// normal lifecycle events, such as cancellation or executor shutdown.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
enum DesiredExecutionTime {
    At(Instant),
    Never, // Put Never last so concrete deadlines are always considered first.
}

impl DesiredExecutionTime {
    /// Returns true if the desired execution time is at or before `now`.
    fn is_due(self, now: Instant) -> bool {
        match self {
            Self::At(desired_execution_time) => desired_execution_time <= now,
            Self::Never => false,
        }
    }
}

/// Ordering key for tasks in the executor. Tasks are ordered first by their
/// desired execution time, then by their task ID. Since task IDs are assigned
/// monotonically, this ensures the internal ordering guarantee: if task t1 is
/// added strictly before t2 and delay(t1) <= delay(t2), then t1 executes
/// before t2.
#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
struct TaskOrder {
    desired_execution_time: DesiredExecutionTime,
    task_id: u64,
}

/// High-level state of the background runner task.
enum RunnerState<State>
where
    State: Send + 'static,
{
    /// Initial state of the runner before running `Runner` in background task.
    Init(Runner<State>),

    /// The runner has successfully started. The handle is used to abort the
    /// background runner task when the application-owned executor is dropped.
    Started(JoinHandle<()>),

    /// The executor does not own the background task. This is used for the
    /// internal executor provided to task closures for rescheduling.
    Unowned,
}

/// Internal runner that executes tasks sequentially.
///
/// # IMPLEMENTATION STRATEGY
///
/// - The runner keeps track of all pending commands in two data structures:
///   `task_orders` (a `HashMap` for O(1) lookup by task ID) and `tasks` (a
///   `BTreeMap` for ordered retrieval by desired execution time and task ID).
/// - Commands are executed in the `run` method, which loops continuously: it
///   drains input from the channel, executes any tasks that are due, then waits
///   for the next concrete scheduled task deadline or new inputs.
/// - When a command is added (via `Enqueue` input), it is inserted into both
///   `task_orders` and `tasks` with its desired execution time computed as
///   enqueue time plus delay, or `Never` if the computation overflows.
/// - The monotonically increasing task IDs, combined with ordering by desired
///   execution time, maintain the internal ordering guarantee.
/// - When a command is cancelled (via `Cancel` input), it is removed from both
///   data structures.
/// - The runner terminates when the input channel is closed (all senders
///   dropped).
///
/// # INVARIANTS
///
/// These invariants ensure correct ordering and consistent state. As long as
/// they are maintained, the specific implementation details can be adjusted.
///
/// - For every entry in `desired_execution_times`, there is a corresponding
///   entry in `tasks` with the same `TaskOrder`, and vice versa.
/// - Task IDs are unique and monotonically increasing for the lifetime of the
///   executor, which allows us to maintain the internal ordering guarantee.
struct Runner<State>
where
    State: Send + 'static,
{
    state: State,
    receiver: mpsc::UnboundedReceiver<Input<State>>,

    /// Map from task ID to the desired execution time of the task. Used to
    /// efficiently locate the entry in `tasks` when cancelling a task.
    desired_execution_times: HashMap<u64, DesiredExecutionTime>,

    /// Map from task order (desired execution time and task ID) to the
    /// pending task.
    tasks: BTreeMap<TaskOrder, Task<State>>,
}

impl<State> Runner<State>
where
    State: Send + 'static,
{
    fn new(state: State, receiver: mpsc::UnboundedReceiver<Input<State>>) -> Self {
        let desired_execution_times = HashMap::<u64, DesiredExecutionTime>::new();
        let tasks = BTreeMap::<TaskOrder, Task<State>>::new();

        Self {
            state,
            receiver,
            desired_execution_times,
            tasks,
        }
    }

    fn remove_task(&mut self, task_id: u64) {
        if let Some(desired_execution_time) = self.desired_execution_times.remove(&task_id) {
            let task_order = TaskOrder {
                desired_execution_time,
                task_id,
            };
            self.tasks.remove(&task_order).expect("invariant violation");
        }
    }

    /// Adds a task to the pending task queue. The task's desired execution time
    /// is computed as `enqueue_time + delay`, or `Never` if that computation
    /// overflows. The task's ID (which is monotonically increasing) serves as a
    /// tie-breaker to maintain the internal ordering guarantee.
    fn push_task(&mut self, task: Task<State>) {
        let desired_execution_time: DesiredExecutionTime =
            match task.enqueue_time.checked_add(task.delay) {
                Some(desired_execution_time) => DesiredExecutionTime::At(desired_execution_time),
                None => DesiredExecutionTime::Never,
            };
        let task_order = TaskOrder {
            desired_execution_time,
            task_id: task.id,
        };
        self.desired_execution_times
            .insert(task.id, desired_execution_time);
        self.tasks.insert(task_order, task);
    }

    /// Removes and returns the next task if its desired execution time is at or
    /// before `now`. Returns `None` if no tasks are pending or if the next task
    /// is not yet due to run.
    fn pop_task_if_due(&mut self, now: Instant) -> Option<Task<State>> {
        if let Some(entry) = self.tasks.first_entry()
            && entry.key().desired_execution_time.is_due(now)
        {
            let (_, task) = entry.remove_entry();
            self.desired_execution_times
                .remove(&task.id)
                .expect("invariant violation");
            return Some(task);
        }
        None
    }

    fn process_input(&mut self, input: Input<State>) {
        match input {
            Input::Enqueue(task) => {
                self.push_task(task);
            }
            Input::Cancel(task_id) => {
                self.remove_task(task_id);
            }
        }
    }

    async fn run(
        mut self,
        sender: mpsc::UnboundedSender<Input<State>>,
        cancel_sender: Arc<dyn CancelSender>,
        task_id_counter: Arc<AtomicU64>,
        clock: Clock,
    ) {
        // Create the internal executor once and reuse it for all task
        // closures (e.g. for rescheduling).
        let internal_executor = SequentialExecutor {
            sender,
            cancel_sender,
            task_id_counter,
            clock,
            runner_state: Mutex::new(RunnerState::Unowned),
        };
        loop {
            // Drain all currently ready inputs from the mpsc channel.
            while let Ok(input) = self.receiver.try_recv() {
                self.process_input(input);
            }
            // Execute any tasks that are due. Between tasks, we consume budget
            // for this task in the tokio runtime so that other tasks get their
            // fair share. (Note that consume_budget() will cause us to yield
            // only once every ~128 times.)
            let now = internal_executor.clock().instant();
            while let Some(task) = self.pop_task_if_due(now) {
                (task.task_fn)(&internal_executor, &mut self.state);
                tokio::task::consume_budget().await;
            }
            // Now we're going to wait until either some new inputs are enqueued,
            // a scheduled task is due to run, or the receiver is closed.
            let outcome = match self.tasks.first_key_value() {
                Some(entry) => {
                    let (task_order, _task): (&TaskOrder, &Task<State>) = entry;
                    let desired_execution_time: DesiredExecutionTime =
                        task_order.desired_execution_time;
                    match desired_execution_time {
                        DesiredExecutionTime::At(desired_execution_time) => {
                            // Refresh `now` to account for time spent executing tasks.
                            let now = internal_executor.clock().instant();
                            let duration: Duration =
                                desired_execution_time.saturating_duration_since(now);
                            internal_executor
                                .clock()
                                .timeout(duration, self.receiver.recv())
                                .await
                        }
                        // Next task is never due, so we wait for new inputs.
                        DesiredExecutionTime::Never => Ok(self.receiver.recv().await),
                    }
                }
                None => Ok(self.receiver.recv().await),
            };
            match outcome {
                Ok(Some(input)) => {
                    // Process the input. If the input results in a new task
                    // being due, it will be executed in the next loop iteration.
                    self.process_input(input);
                }
                Ok(None) => {
                    // The receiver is closed, so we exit the loop.
                    return;
                }
                Err(_timeout_error) => {
                    // Any tasks that are now due will be executed in the next
                    // loop iteration.
                }
            }
        }
    }
}

/// Messages sent from the `SequentialExecutor` to the `Runner` to enqueue or
/// cancel tasks.
enum Input<State>
where
    State: Send + 'static,
{
    /// Request to enqueue a new task for execution.
    Enqueue(Task<State>),
    /// Request to cancel a previously enqueued task with the given ID
    /// (best-effort).
    Cancel(u64),
}

/// Type alias for the task function.
type TaskFn<State> = Box<dyn FnOnce(&SequentialExecutor<State>, &mut State) + Send + 'static>;

/// A task to be executed by the runner. The task's desired execution time is
/// computed as `enqueue_time + delay`.
struct Task<State>
where
    State: Send + 'static,
{
    id: u64,
    enqueue_time: Instant,
    delay: Duration,
    task_fn: TaskFn<State>,
}

/// A trait for sending Input::Cancel messages to the runner.
/// Type-erased so that the public Handle API doesn't need to
/// know the concrete type of the sender.
trait CancelSender: Send + Sync {
    fn cancel(&self, id: u64);
}

struct CancelSenderImpl<State>
where
    State: Send + 'static,
{
    sender: mpsc::UnboundedSender<Input<State>>,
}

impl<State> CancelSender for CancelSenderImpl<State>
where
    State: Send + 'static,
{
    fn cancel(&self, id: u64) {
        let input = Input::Cancel(id);
        // It's ok if the send fails, just means that the runner has already
        // dropped its receiver.
        let _ = self.sender.send(input);
    }
}

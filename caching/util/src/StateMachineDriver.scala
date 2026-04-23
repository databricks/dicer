package com.databricks.caching.util

import java.time.Instant
import scala.collection.mutable
import scala.util.control.NonFatal
import io.grpc.Status
import com.databricks.caching.util.CachingErrorCode.UNCAUGHT_STATE_MACHINE_ERROR
import com.databricks.caching.util.StateMachineDriver.{ConcurrencyDomain, sequentialDomain}
import com.google.common.base.Throwables

import scala.concurrent.duration.FiniteDuration

/**
 * A driver that collaborates with a [[StateMachine]] to manage a combination of state and
 * asynchronous activity. See <internal link> for details and code samples.
 *
 * The code follows an active-passive pattern where the driver is active but simple, while the state
 * machine is passive but "decisive", in the sense of deciding actions the driver should take
 * after each event based on the current state. Factoring the logic in this fashion makes the
 * interesting state machine logic simpler, more obviously correct, and easier to evolve.
 *
 * The active driver is responsible for delivering events of type [[EventT]] to the state machine.
 * In response, the state machine updates its state and returns a sequence of actions of type
 * [[ActionT]] for the driver to perform. It also returns the next time at which the state machine
 * should be called with an "advance" event, assuming no other events are handled in the interim.
 *
 * The core [[StateMachineDriver]] implementation is responsible for invoking the state machine at
 * the appropriate times, requiring only a `performAction` handler from concrete implementations.
 *
 * CONCURRENCY DISCIPLINE
 *
 * In contrast with most thread-safe components in the Caching team code base, the driver requires
 * that all public methods are called from within the configured concurrency domain (usually an
 * SEC) rather than scheduling itself to run asynchronously within the domain. This is because our
 * intention is for the driver object to be owned by some higher-level component (`class FooDriver`
 * in the example above) whose state is in the same concurrency domain as the driver instance.
 *
 * A consequence of this unopinionated concurrency discipline is that performance sensitive
 * code can use a StateMachineDriver with a [[HybridConcurrencyDomain]] and call into the driver
 * synchronously, enabling calls into the state machine without the overhead of transferring
 * control to another thread.
 *
 * @param domain the concurrency domain within which the driver runs and protects all driver state.
 * @param stateMachine the passive state machine implementation.
 * @param performAction the handler function responsible for initiating actions requested by the
 *                      state machine. Called in `domain`.
 * @tparam EventT the type of event handled by the state machine, typically a sealed trait so that
 *                possible event types can be exhaustively handled in a `match` block.
 * @tparam ActionT the type of action performed by the driver, typically a sealed trait so that
 *                 possible actions can be exhaustively handled in a `match` block.
 * @tparam MachineT the type of the state machine, which must extend
 *                  `StateMachine[EventT, ActionT]`.
 *
 * The caller MUST call [[start]] before calling any of the other methods in this class.
 */
sealed class StateMachineDriver[
    EventT,
    ActionT,
    MachineT <: StateMachine[EventT, ActionT]
] private (
    domain: ConcurrencyDomain,
    stateMachine: MachineT,
    performAction: ActionT => Unit,
    alertOwnerTeam: AlertOwnerTeam) {

  /**
   * Creates a new [[StateMachineDriver]] in the sequential domain defined by `sec`.
   *
   * @param alertOwnerTeam the team's registered alert routing name, e.g.
   *                       [[AlertOwnerTeam.CachingTeam.toString]] for Caching-owned state machines,
   *                       or "eng-my-team" for state machines owned by other teams. For alert
   *                       routing to work correctly, this must be used as the `owner_team_name`
   *                       for some Dicer target config.
   */
  def this(
      sec: SequentialExecutionContext,
      stateMachine: MachineT,
      performAction: ActionT => Unit,
      // TODO(<internal bug>): Make alertOwnerTeam required once all call sites are updated.
      alertOwnerTeam: String = AlertOwnerTeam.CachingTeam.toString) = {
    this(
      sequentialDomain(sec),
      stateMachine,
      performAction,
      AlertOwnerTeam.createFromString(alertOwnerTeam)
    )
  }

  private val logger = PrefixLogger.create(getClass, "")

  /**
   * Pending advance call, if any. There is at most a single pending advance call at any given time
   * because the state machine requests a new advance time every time it is called that supersedes
   * any previously supplied values.
   */
  private var pendingAdvanceCall: Option[AdvanceCall] = None

  /**
   * REQUIRES: called in `domain`
   *
   * Kicks off the state machine by supplying it with an `advance` event.
   */
  def start(): Unit = {
    domain.assertInDomain()
    handleAdvance(domain.getClock.tickerTime())
  }

  /**
   * REQUIRES: called in `domain`
   *
   * Supplies the given event to the state machine and handles the output of the state machine:
   *
   *  - Performs any requested actions, using the `performAction` function.
   *  - Ensures that an `advance` event is scheduled at the next time requested by the state
   *    machine when a callback is requested.
   */
  def handleEvent(event: EventT): Unit = {
    domain.assertInDomain()
    try {
      handleOutput(
        stateMachine.onEventInternal(
          tickerTime = domain.getClock.tickerTime(),
          instant = domain.getClock.instant(),
          event
        )
      )
    } catch {
      case NonFatal(e: Throwable) =>
        logger.alert(
          Severity.CRITICAL,
          UNCAUGHT_STATE_MACHINE_ERROR(alertOwnerTeam),
          s"Exception in handleEvent: ${Throwables.getStackTraceAsString(e)}"
        )
    }
  }

  /**
   * REQUIRES: called in `domain`
   *
   * Supplies the state machine with an `advance` event, adding appropriate logging/alerting if any
   * unexpected exception occurs.
   */
  private def handleAdvance(tickerTime: TickerTime): Unit = {
    try {
      handleOutput(
        stateMachine.onAdvanceInternal(tickerTime, instant = domain.getClock.instant())
      )
    } catch {
      case NonFatal(e: Throwable) =>
        logger.alert(
          Severity.CRITICAL,
          UNCAUGHT_STATE_MACHINE_ERROR(alertOwnerTeam),
          s"Exception in handleAdvance: ${Throwables.getStackTraceAsString(e)}"
        )
    }
  }

  /**
   * Handles output from the state machine, which is its response to `onEvent` or `onPassed`.
   * Performs all requested actions, then ensures that the state machine will be advanced again
   * (`onAdvance`) when requested.
   */
  private def handleOutput(output: StateMachineOutput[ActionT]): Unit = {
    // Apply each action from the output in order.
    for (action: ActionT <- output.actions) {
      performAction(action)
    }
    // `output.nextTickerTime` determines when `onAdvance` should be called next, superseding any
    // prior requested times. Cancel and replace the pending advance call if needed.
    this.pendingAdvanceCall match {
      case Some(call: AdvanceCall) =>
        // This means that an `onAdvance` call was scheduled but has not yet run.
        if (call.nextTickerTime == output.nextTickerTime) {
          // Already scheduled at the desired time! Nothing more to do.
          return
        }
        call.handle.cancel(Status.CANCELLED)
        this.pendingAdvanceCall = None
      case None =>
      // No existing pending advance call needs to be cleaned up.
    }
    if (output.nextTickerTime == TickerTime.MAX) {
      // TickerTime.MAX means never.
      return
    }
    this.pendingAdvanceCall = Some(new AdvanceCall(output.nextTickerTime))
  }

  /**
   * State associated with an `onAdvance` call scheduled in the `domain`. The reader should treat
   * this class as an implementation detail of `handleOutput`.
   *
   * @param nextTickerTime the time at which
   */
  private class AdvanceCall(val nextTickerTime: TickerTime) extends Runnable {
    domain.assertInDomain()

    // Schedule this advance call in the `domain` and remember the cancellation handle.
    var handle: Cancellable =
      domain.schedule("advance", delay = nextTickerTime - domain.getClock.tickerTime(), this)

    /**
     * Callback that we (expect) the `domain` to run at approximately `nextTickerTime`. If this
     * [[AdvanceCall]] instance is still "the" pending advance call for the driver and if
     * `nextTickerTime` is up, calls `onAdvance` on the state machine.
     */
    override def run(): Unit = {
      domain.assertInDomain()

      // Figure out if this is "the" pending advance call for the driver.
      val isPendingAdvanceCall = StateMachineDriver.this.pendingAdvanceCall.exists {
        pending: AdvanceCall =>
          pending eq AdvanceCall.this
      }
      if (!isPendingAdvanceCall) {
        // If we're not the pending advance call, don't run! Due to best-effort cancellation, we may
        // be executed by the `domain` even after being cancelled and/or being replaced with a
        // different advance call.
        return
      }
      // Figure out what needs to be done.
      val tickerTime: TickerTime = domain.getClock.tickerTime()
      if (tickerTime >= nextTickerTime) {
        // This advance call is no longer pending (it's running!)
        StateMachineDriver.this.pendingAdvanceCall = None
        handleAdvance(tickerTime)
      } else {
        // We've been woken too early (as the `domain` is permitted to do). Schedule ourselves again
        // with the remaining delay.
        this.handle = domain.schedule("advance-adjust", delay = nextTickerTime - tickerTime, this)
      }
    }
  }

  object forTest {

    /**
     * REQUIRES: must be called on `domain`.
     *
     * Gets the underlying state machine for tests.
     */
    def getStateMachine: MachineT = {
      domain.assertInDomain()
      stateMachine
    }

    /**
     * REQUIRES: must be called in `domain` and there must be a pending advance call.
     *
     * Runs the pending advance call now. Allows tests to manually trigger [[AdvanceCall.run]]
     * before it would normally run on the `domain`.
     */
    private[util] def runPendingAdvanceCall(): Unit = {
      domain.assertInDomain()
      pendingAdvanceCall.get.run()
    }
  }
}

object StateMachineDriver {
  object Metrics {}

  /**
   * Creates a new [[StateMachineDriver]] to be used in a hybrid concurrency domain.
   *
   * @param alertOwnerTeam the team's registered alert routing name, e.g.
   *                       [[AlertOwnerTeam.CachingTeam.toString]] for Caching-owned state machines,
   *                       or "eng-my-team" for state machines owned by other teams. For alert
   *                       routing to work correctly, this must be used as the `owner_team_name`
   *                       for some Dicer target config.
   */
  // Note: even though this method is public, visibility is limited in practice at the bazel level
  // by limiting visibility of `HybridConcurrencyDomain`.
  def inHybridDomain[EventT, ActionT, MachineT <: StateMachine[EventT, ActionT]](
      hybrid: HybridConcurrencyDomain,
      stateMachine: MachineT,
      performAction: ActionT => Unit,
      // TODO(<internal bug>): Make alertOwnerTeam required once all call sites are updated.
      alertOwnerTeam: String = AlertOwnerTeam.CachingTeam.toString
  ): StateMachineDriver[EventT, ActionT, MachineT] = {
    new StateMachineDriver(
      hybridDomain(hybrid),
      stateMachine,
      performAction,
      AlertOwnerTeam.createFromString(alertOwnerTeam)
    )
  }

  /**
   * [[StateMachineDriver]]-internal trait that allows the internals to abstract over
   * both sequential and hybrid concurrency domains. Visibility is only `private[util]` for
   * [[StateMachineDriverSuite]].
   */
  private[util] sealed trait ConcurrencyDomain {

    /** Throws if the current thread of execution is not within this domain. */
    def assertInDomain(): Unit

    /**
     * Schedules `runnable` to execute after the given `delay`. `name` is provided just for
     * debugging. Returns a handle that can be used to best-effort cancel the scheduled command.
     * `runnable` is allowed to execute before (or after) the specified delay; it's up to the
     * caller to handle early waking.
     */
    def schedule(name: String, delay: FiniteDuration, runnable: Runnable): Cancellable

    /** Returns the clock used to evaluate delays for scheduled commands. */
    def getClock: TypedClock
  }

  /** Returns a new sequential [[ConcurrencyDomain]] backed by `sec`. */
  private def sequentialDomain(sec: SequentialExecutionContext): ConcurrencyDomain = {
    new ConcurrencyDomain {
      override def assertInDomain(): Unit = sec.assertCurrentContext()

      override def schedule(
          name: String,
          delay: FiniteDuration,
          runnable: Runnable): Cancellable = {
        sec.schedule(name, delay, runnable)
      }

      override def getClock: TypedClock = sec.getClock
    }
  }

  /** Returns a new hybrid [[ConcurrencyDomain]] backed by `hybrid`. */
  private def hybridDomain(hybrid: HybridConcurrencyDomain): ConcurrencyDomain = {
    new ConcurrencyDomain {
      override def assertInDomain(): Unit = hybrid.assertInDomain()

      override def schedule(
          name: String,
          delay: FiniteDuration,
          runnable: Runnable): Cancellable = {
        hybrid.schedule(name, delay, runnable)
      }

      override def getClock: TypedClock = hybrid.getClock
    }
  }

  private[util] object forTestStatic {
    def sequentialDomain(sec: SequentialExecutionContext): ConcurrencyDomain = {
      StateMachineDriver.sequentialDomain(sec)
    }

    def hybridDomain(hybrid: HybridConcurrencyDomain): ConcurrencyDomain = {
      StateMachineDriver.hybridDomain(hybrid)
    }
  }
}

/**
 * The output of a [[StateMachine]] after it processes an event.
 *
 * @param nextTickerTime The next time the generator state machine should be advanced by calling
 *                       [[StateMachine.onAdvance]], assuming that no other signals are received in
 *                       the meantime. As documented in [[StateMachine.onAdvance]], it's correct
 *                       (though wasteful) to advance earlier, and correct (though possibly sub-
 *                       optimal) to advance later.
 *
 *                       In response to a subsequent signal, the state machine may return an earlier
 *                       or later [[nextTickerTime]] value, and caller need only respect the latest
 *                       [[nextTickerTime]] value returned by the generator.
 *
 *                       When this value is [[TickerTime.MAX]], it means that the state machine
 *                       should only be called again if an explicit event is processed, not merely
 *                       in response to the passage of time.
 *
 *                       This value supersedes any `nextTickerTime` values that were previously
 *                       returned by the state machine: it is ok for the state machine to say "call
 *                       me later", "call me earlier", or "call me never".
 * @param actions Actions that must be executed by the driver for the state machine. Actions are
 *                supplied to the driver in this order.
 */
case class StateMachineOutput[+ActionT](nextTickerTime: TickerTime, actions: Seq[ActionT])

object StateMachineOutput {
  private val EMPTY: StateMachineOutput[Nothing] = StateMachineOutput(TickerTime.MAX, Seq.empty)

  /** Empty output that requests no actions and call backs only in response to explicit events. */
  def empty[ActionT]: StateMachineOutput[ActionT] = EMPTY

  class Builder[ActionT] {
    private var nextTickerTime = TickerTime.MAX
    private val actions = mutable.ArrayBuffer[ActionT]()

    /** Ensures that the state machine is advanced at `nextTickerTime` or earlier. */
    def ensureAdvanceBy(nextTickerTime: TickerTime): this.type = {
      if (nextTickerTime < this.nextTickerTime) {
        this.nextTickerTime = nextTickerTime
      }
      this
    }

    /**
     * Appends the given action to the state machine output. Actions will be supplied to the driver
     * in the order they are added.
     */
    def appendAction(action: ActionT): this.type = {
      this.actions.append(action)
      this
    }

    /**
     * Builds output with the earliest `nextTickerTime` value requested using [[ensureAdvanceBy()]]
     * and all actions appended using [[appendAction]].
     */
    def build(): StateMachineOutput[ActionT] = StateMachineOutput(nextTickerTime, actions.toSeq)
  }
}

/**
 * Trait implemented by a state machine, which is a passive (but decisive) partner to an
 * active (but simple) [[StateMachineDriver]]. See <internal link> for details and
 * code samples.
 *
 * By "decisive", we mean that it maintains the state machine in response to events and decides what
 * actions should be performed by the driver after each event. For example, it might decide whether
 * and when to start an RPC.
 */
trait StateMachine[EventT, ActionT] {

  /**
   * Informs the state machine that time has passed, independent of any explicit signal. Based on
   * the passage of time and any recently incorporated signals, returns actions requested of the
   * driver and the next time at which the state machine should be advanced, absent explicit events.
   *
   * It is totally fine to spuriously call this method, i.e., before the most recently requested
   * [[StateMachineOutput.nextTickerTime]]. If the state machine is well-behaved, repeated spurious
   * calls will return identical output ("do nothing and call me back at time x"). Determining that
   * no work is needed may be expensive however, so spamming the state machine with `advance` events
   * is discouraged. (Note that the [[StateMachineDriver]] will never generate a spurious advance
   * event on its own. This commentary is primarily meant to encourage a particular mindset for
   * state machine developers!)
   *
   * It is also correct to advance the state machine later than desired, e.g. due to normal
   * executor delays. Significant delays may result in slower than desirable reaction times to
   * events however.
   */
  protected def onAdvance(tickerTime: TickerTime, instant: Instant): StateMachineOutput[ActionT]

  /**
   * Processes an input event from the [[StateMachineDriver]] and returns actions requested of the
   * driver and the next time at which the state machine should be advanced, absent explicit events.
   */
  protected def onEvent(
      tickerTime: TickerTime,
      instant: Instant,
      event: EventT): StateMachineOutput[ActionT]

  /**
   * Calls the protected [[onAdvance]] method. We want that method to be visible to only the
   * implementor of [[StateMachine]] and to [[StateMachineDriver]] (though `private[util]` means
   * that visibility remains a bit broader than we'd like).
   */
  @inline private[util] final def onAdvanceInternal(
      tickerTime: TickerTime,
      instant: Instant): StateMachineOutput[ActionT] = {
    onAdvance(tickerTime, instant)
  }

  /**
   * Calls the protected [[onEvent]] method. See remarks on [[onAdvanceInternal]] to understand
   * what's going on with method visibility.
   */
  @inline private[util] final def onEventInternal(
      tickerTime: TickerTime,
      instant: Instant,
      input: EventT): StateMachineOutput[ActionT] = {
    onEvent(tickerTime, instant, input)
  }
}

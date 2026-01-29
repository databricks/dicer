# Caching Utilities

This directory contains generally useful utilities that are not specific to caching team projects,
e.g., not narrowly applicable to [Dicer](<internal link>) or [Softstore](<internal link>). These
utilities could in theory be used by any team, but are locked down by default to avoid maintenance
overhead and to minimize feature creep. If you want to use any of these utilities, please contact
the [Caching team](<internal link>).

## [Intrusive Min Heap](src/IntrusiveMinHeap.scala)

A min-heap or priority queue implementation that allows for efficient removal and update of
arbitrary elements. It has the same time complexity as a standard min-heap for `push`, `peek`, and
`pop` but has asymptotically better time complexity for `remove` and `setPriority` (the latter is an
"update" operation):

- `peek`: $O(1)$
- `pop`: $O(log(n))$
- `push`: $O(log(n))$
- `remove`: $O(log(n))$, compared with $O(n)$ for a basic heap
- `setPriority`: $O(log(n))$, compared with $O(n)$ for a basic heap

The tradeoff is that the abstraction is "intrusive": elements must have the
`IntrusiveMinHeapElement` trait mixed in. This trait includes a back-pointer to the containing heap
and the index of the element in the heap. This back-pointer enables efficient update and removal of
elements by allowing the heap to jump directly to the affected element rather than searching for it
in its internal `ArrayBuffer` using a linear time scan.

### Sample

The following sample illustrates how to use the heap to track scheduled work items by desired
execution time (the next work item to be executed is the one with the earliest desired execution
time):

```scala
// The desired execution time determines the priority of a work item in the heap.
case class ExecTime(nanoTime: Long) extends AnyVal with Ordered[ExecTime] {
 override def compare(that: ExecTime): Int = Long.compare(this.nanoTime, that.nanoTime)
}

// A scheduled work item, which extends IntrusiveMinHeapElement so that it can be used as an element
// in the heap.
case class WorkItem(time: ExecTime, val work: Runnable) extends IntrusiveMinHeapElement[ExecTime] {
 setPriority(time)

 def execTime: ExecTime = getPriority()

 def updateExecTime(execTime: ExecTime): Unit = setPriority(execTime)
}

...
val workItems = new IntrusiveMinHeap[ExecTime, WorkItem]
...

// Examine the next work item scheduled for execution.
workItems.peek() match {
 case Some(item) if item.execTime < now => item.work.run()
 case _ =>
}

...
// Cancel a work item.
item.remove()
...
// Change the desired execution time for an item. This automatically updates the position of the
// item in the heap.
item.updateExecTime(ExecTime(clock.nanoTime() + 10))
```

### Possible extensions

- We may want to abstract out the back-pointer accessor for elements to allow elements to be
  contained in multiple heaps. This might be useful in the Dicer sharding algorithm, which at some
  point may need to order pods by multiple metrics (e.g., to efficiently track the pods that are
  using the most/least memory and the most/least CPU).
- `IntrusiveMinHeap` should really be replaced with an `IntrusiveMinMaxHeap`, or just
  `IntrusiveHeap`. The motivating example is the Dicer sharding algorithm, which during its
  "migration" phase reassigns Slices from the most to the least loaded pod, and it makes sense to
  track these in a single data structure.

## [State Machine](src/StateMachineDriver.scala)

<internal link>

A stateful component is one whose internal state evolves in response to external stimuli and that
juggles asynchronous operations like RPCs, database writes, etc. This is the trickiest category of
code to design and implement, as it must account for all possible stimuli in all possible internal
states. The `StateMachine` and `StateMachineDriver` together provide a framework for implementing
and thinking about stateful components. Code using this framework follows an active-passive pattern
where the driver is active but simple, while the state machine is passive but "decisive", in the
sense of deciding actions the driver should take after each event based on the current state.
Factoring the logic in this fashion makes the interesting state machine logic simpler, more
obviously correct, and easier to evolve.

Sample usages:

- [`AssignmentGenerator`](../../dicer/assigner/src/AssignmentGenerator.scala): input events are
  health and load signals from sharded services and output actions are things like "write the
  following assignment" or "distribute the following assignment". This is a fairly complex state
  machine that is decomposed into sub-machines including
  [`HealthWatcher`](../../dicer/assigner/src/HealthWatcher.scala).
- [`AssignmentSyncStateMachine`](../../dicer/client/src/AssignmentSyncStateMachine.scala): input
  events are assignment updates from "servers" or watch requests from "clients". Output actions are
  directives to sync or apply assignment state.

At a high-level, a type mixing in the `StateMachine` trait is responsible for:

- Updating its state in response to external events. For example, the Dicer health watcher may
  receive a heartbeat from the `softstore-storelet-2` pod as of 1:30, via the `onEvent(now, event)`
  method.
- Deciding when the state machine should next be consulted, absent any external events. For example,
  the Dicer health watcher may decide that it should be consulted again at 1:31 in case no
  additional heartbeat events are received before then.
- Updating its state in response to the passage of time. For the example, if the Dicer health
  watcher is consulted at 1:31 PM via the `onAdvance(now)` method, it may decide that the
  `softstore-storelet-2` pod is no longer healthy because no heartbeats have been received since
  1:30.
- Deciding which actions should be taken in response to input events and the passage of time. For
  example, in the workflow outlined above the Dicer health watcher may ask the assignment generator
  to exclude `softstore-storelet-2` from future assignments.

Notably, the `StateMachine` implementation is *not* responsible for:

- _Performing actions_: the driver is responsible for actuation. The output of the state machine is
  just data, which makes the internal behaviors easy to observe and test.
- _Thread safety_: the state machine can assume that it is called serially by the driver.
- _Scheduling work_: the state machine merely requests a callback time. It is not responsible for
  scheduling (or cancelling) delayed work on an executor. The driver ensures that the state machine
  is advanced at the appropriate times.

The `StateMachineDriver` is the active portion of the state machine framework. The shared
implementation takes care of scheduling calls to the state machine at the appropriate times, with
calls synchronized by a `SequentialExecutionContext`. It takes care of some tricky but generic
behaviors, supporting cancellation, and ensuring that at most a single outstanding task is scheduled
on the underlying executor at any given time. Only the action handler code is left to the
application.

### Sample Usage

The following sample code illustrates the organization of the state machine and driver component for
a generic `Foo` example. The passive state machine is implemented in `Foo.scala` and the active
driver is implemented in `FooDriver.scala`. This is a trivial implementation that performs `Action1`
in response to `Event1` inputs and `Action2` in response to `Event2` inputs. In addition, it
performs a `TimerAction` when it has been more than ten seconds since the previous timer action.

```scala
//
// Foo.scala contains the passive state machine logic.
//

import Foo.{DriverAction, Event}

object Foo {

  // Input events for the state machine.
  sealed trait Event
  object Event {
    case class Event1(payload: String) extends Event
    case class Event2(payload: String) extends Event
  }

  // Driver actions requested by the state machine.
  sealed trait DriverAction
  object DriverAction {
    case class Action1(payload: String) extends DriverAction
    case class Action2(payload: String) extends DriverAction
    case class TimerAction(payload: String) extends DriverAction
  }
}

class Foo extends StateMachine[Event, DriverAction] {
  private var lastTimerActionTime: TickerTime = TickerTime.MIN
  
  protected override def onEvent(tickerTime: TickerTime, instant: Instant, event: Event):
      StateMachineOutput[DriverAction] = {
    val builder = new StateMachineOutput.Builder[DriverAction]
    event match {
      case Event.Event1(payload) => builder.add(DriverAction.Action1(payload))
      case Event.Event2(payload) => builder.add(DriverAction.Action2(payload))
    }
    onEventInternal(tickerTime, builder)
  }
  
  protected override def onAdvance(tickerTime: TickerTime, instant: Instant):
      StateMachineOutput[DriverAction] = {
    onAdvanceInternal(tickerTime, new StateMachineOutput.Builder[DriverAction])
  }
  
  /**
   * Advances to `now`. Adds a `TimerAction` to `builder` when enough time has elapsed since the
   * last timer action as recorded in [[lastTimerActionTime]] and ensures that the state machine
   * will be advanced again when the next timer action is due.
   */
  private def onAdvanceInternal(
      now: TickerTime,
      builder: StateMachineOutput.Builder[DriverAction]): StateMachineOutput[DriverAction] = {
    if (now >= lastTimerActionTime + 10.seconds) {
      // If enough time has elapsed since the last timer action, request one now.
      builder.add(DriverAction.TimerAction("timer"))
      lastTimerActionTime = now
    }
    // Make sure the state machine is advanced when the next TimerAction is due.
    builder.ensureAdvanceBy(lastTimerActionTime + 10.seconds)
    builder.build()
  }
}
```

```scala
//
// FooDriver.scala contains the driver implementation.
//

import Foo.{DriverAction, Event}

class FooDriver {

 val sec: SequentialExecutionContext = ???
 val baseDriver =
     new StateMachineDriver[Event, DriverAction, Foo](sec, new Foo(), performAction)

 // Kicks off the state machine.
 sec.run { baseDriver.start() }

  def handleEvent1(string: Payload) = sec.run {
    // Inform the state machine of this event via the driver. The driver implementation will
    // call `performAction` and advance the underlying state machine for us as needed.
    baseDriver.handleEvent(Event.Event1(payload))
  }

  def handleEvent2(string: Payload) = sec.run { baseDriver.handleEvent(Event.Event2(payload)) }

  /** Performs actions requested of us by the state machine. */
  private def performAction(action: Action): Unit = {
    sec.assertRunningInContext() // baseDriver will call us on `sec`
    action match {
      case DriverAction.Action1(payload) => println(s"performing Action1: $payload")
      case DriverAction.Action2(payload) => println(s"performing Action2: $payload")
      case DriverAction.TimerAction(payload) => println(s"performing TimerAction: $payload")
    }
  }
}
```

Miscellaneous notes:

- Notice that the `Foo` type *is a* `StateMachine`, while `FooDriver` *has a* `StateMachineDriver`.
  This was a somewhat arbitrary choice in the API design, but it seems to work well in practice. It
  allows the driver to expose application-specific methods (e.g., `handleEvent1`) that are not part
  of the driver API, while reinforcing the "narrow" interface of the state machine itself.
- The driver does not "remember" that a particular scheduled time was requested by the state
  machine. The state machine is responsible for figuring out the next time it should be consulted
  every time it wakes up. In the example above, the state machine remembers the latest time at which
  it has requested `TimerAction` so that it always knows the next desired firing time. Many state
  machines implement an `onAdvanceInternal` method to encapsulate this logic. See the comments on
  `StateMachineOutput.nextTickerTime` for more details.
- The `StateMachineOutput.Builder` abstraction is a convenient way to collect actions across
  multiple subcomponents: it tracks the earliest desired schedule time requested via
  `ensureAdvanceBy` and accumulates actions via `appendAction`.

## [EwmaCounter](src/EwmaCounter.scala)

<internal link>

An Exponentially Weighted Moving Average (EWMA) counter.

A counter whose incremented values decay exponentially over time. It uses the simple exponential
smoothing approach described in
[Wikipedia](https://en.wikipedia.org/wiki/Moving_average#Exponential_moving_average), where the
smoothed value at time $t$ is given by:

$s_t = \alpha x_t + (1 - \alpha) s_{t-1}$

where $x_t$ is the value at time $t$, $s_t$ is the smoothed value at time $t$, and $\alpha$ is the
smoothing factor.

The motivating use case for the EWMA counter is load collection and reporting by Dicer-sharded
servers (a.k.a. Slicelets). Slicelets maintain counters per assigned Slice, and the ability to
succinctly represent load observed over time is critical to the load balancing design (see
<internal link> for more information). A few adaptations to the standard EWMA implementation are
required for this use case:

1. Load is not reported as a sequence of discrete measurements $x_0, x_1, \ldots$, but rather as a
   stream of load values reported at irregular intervals by the application. The implementation
   therefore accumulates load in 1-second windows, and then supplies the sum value to the private,
   nested `EwmaAccumulator` class at the end of each window. The choice of window size is arbitrary,
   but 1-second granularity seems sufficient for our use case and is easy to reason about.

1. EWMA is most useful in steady state, after a significant number of samples have been processed.
   Slicelets are frequently assigned new Slices however, and we need to think carefully about how to
   initialize the EWMA accumulator. See the discussion of [initialization](#initialization) below.

1. Tuning the counter with a smoothing factor value is non-intuitive, so we instead configure it
   with a half-life value, which is the time that it takes for the weight of a one-second window to
   decay by half. The weight of a measurement is proportional to

   $$(1-\alpha)^{age}$$

   since at every step, its contribution is multiplied by $(1-\alpha)$. We can therefore solve for
   $\alpha$ in

   $$\frac{1}{2}=(1-\alpha)^{halfLife}$$

   to get

   $$\alpha = 1 - 2^{-\frac{1}{halfLife}}$$

### Initialization

The accumulator optionally takes a `seed` value which in practice represents the steady state rate
over some interval prior to the creation of the accumulator. When this seed is available, it is
treated as the initial value of the EWMA accumulator ($x_0$) and the accumulator has $s_0 = x_0$,
which is the standard approach.

When the seed is not available, we could use the first measurement supplied to the accumulator to
initialize the accumulator state with $s_0 = x_0$ as with the seed value, but this would give
excessive weight to that first value, which may be an outlier. Another approach is to assume that
all values prior to $x_0$ are zero (and $s_0 = \alpha x_0$), but this approach results in average
values that are too low while the accumulator is "warming up".

We instead choose to incrementally compute a weighted average (see
[Weighted Arithmetic Mean](https://en.wikipedia.org/wiki/Weighted_arithmetic_mean) in Wikipedia)
where the weights of each measurement decay exponentially as with traditional EWMA. For inputs
$x_0, x_1, \ldots, x_t$, the weight of an input is:

$$w(i,t)=(1-\alpha)^{t-i}\alpha$$

(To see this, note that $x_i$ is initially multiplied by $\alpha$ and that at time $t$ it has been
multiplied $t - i$ times by the decay factor of $1-\alpha$.)

and the weighted average at time $t$ is:

$$m_t = \frac{\sum_i {w(i,t) x_i}}
             {\sum_i {w(i,t)}}
      = \frac{\sum_i (1-\alpha)^{t-i}\alpha x_i}
             {\sum_i (1-\alpha)^{t-i}\alpha}$$

If we separately track the numerator and denominator, we can incrementally compute the moving
average. The numerator is:

$$s_t = \sum_{i=0}^{t} {(1-\alpha)^{t-i}\alpha x_i}
      = \alpha x_t + (1-\alpha)s_{t-1}$$

and the denominator is:

$$d_t = \sum_{i=0}^{t} {(1-\alpha)^{t-i}\alpha}
      = \alpha + (1-\alpha)d_{t-1}$$

The following table illustrate the output of the three EWMA variants described above. The row
labelled $x_t$ contains input measurements. The next two rows show the classic EWMA strategies, for
the case where $s_0=x_0$ and the case where zero history is assumed respectively. The final row
illustrates the output of our implementation, which emits weighted averages. Note that $\alpha$ is
0.4 for all three variants in the example below.

| $t$                    | 0    | 1    | 2    | 3    | 4     | 5     | 6     | 7     | 8     |
| ---------------------- | ---- | ---- | ---- | ---- | ----- | ----- | ----- | ----- | ----- |
| $x_t$                  | 2    | 10   | 10   | 10   | 20    | 20    | 20    | 20    | 20    |
| $s_t$ (for $s_0=x_0$)  | 2.00 | 5.20 | 7.12 | 8.27 | 12.96 | 15.78 | 17.47 | 18.48 | 19.09 |
| $s_t$ (zero history)   | 0.80 | 4.48 | 6.69 | 8.01 | 12.81 | 15.68 | 17.41 | 18.45 | 19.07 |
| $m_t$ (implementation) | 2.00 | 7.00 | 8.53 | 9.21 | 13.89 | 16.45 | 17.91 | 18.76 | 19.26 |

Notice that over time, all three approaches converge, as the influence of the early values supplied
to the accumulator decays. Our approach is designed to minimize noise during the initial load
period, which is a more common occurrence for Dicer-sharded systems due to reassignments of key
ranges.

This approach is similar to the `pandas.DataFrame.ewm` method with `adjust=True` (documented
[here](https://pandas.pydata.org/docs/reference/api/pandas.DataFrame.ewm.html)), but with an
incremental implementation.

## [`LossyEwmaCounter`](src/LossyEwmaCounter.scala)

<internal link>

This section provides more formal definitions of the concepts introduced in the `LossyEwmaCounter`
class spec.

### Design overview

As an aid to understanding, here is an informal sketch of how the implementation lazily applies
decays to achieve the above contract efficiently:

Logically speaking, each value recorded in an exponentially weighted average is reduced by the decay
factor for each second that passes. However, eagerly decaying all values each second would be too
expensive.

Instead, the implementation lazily applies the decays, by "amplifying" newly added values by
$(1/decay)^n$, where $n$ is the number of seconds after a base epoch, and then lazily applying the
accumulated delays to all stored values when computing the hot keys or any other time it needs to
scan all of the keys (e.g. to prune rare keys, or to prevent overflow.) Scaling the values by the
accumulated decay allows us to avoid overflow, and once we have rescaled we can define a new base
epoch and reset the amplification factor.

For example, suppose that the decay is $\frac{1}{2}$. Conversely, the amplification factor is 2 for
each second past the base epoch. A value at $baseEpoch + 1$ should be given 2x the weight of a value
at $baseEpoch$, one at $baseEpoch + 2$ should be given 4x, and so forth. This maintains the right
relationship between values, but clearly we'll overflow if we continue the exponential amplification
indefinitely.

Suppose though that at $baseEpoch + 2$ we need to scan all the keys to identify the hot keys.
Dividing all values by $2^2 = 4$ applies the deferred decay and results in the usual EMWA weights,
e.g.  $(..., \frac{\alpha}{4}, \frac{\alpha}{2}, \alpha)$. We can then define the current epoch to
the new base epoch and reset the amplification factor to $\alpha$, preventing future overflow.

Please see the implementation comments for many additional important details, such as exactly when
and why we perform scans.

### Definitions

*Epoch*: a one-second time window. The epoch number for a sample is the number of complete
seconds that have elapsed since `Config.startTime`:

$$epoch = \lfloor \frac{time - startTime}{epochDuration} \rfloor$$

*Exponentially-weighted moving average (EWMA)*: the time-weighted sum of multiple recorded
values. Given values recorded at epochs ${(value_1, epoch_1), (value_2, epoch_2), \ldots,
(value_n, epoch_n)}$, the EWMA is:

$$\sum_{i=1}^n \alpha \cdot decay^{epoch_{current} - epoch_i} \cdot value_i$$

where $\alpha = 1 - decay$.

*Contribution*: the contribution of a key $k$ is the EWMA of all values recorded for the key divided
the EWMA of all values recorded for all keys:

$$
  \frac
    {\sum_{\\\{i|key_i=k\\\}} \alpha \cdot decay^{epoch_{current} - epoch_i} \cdot value_i}
    {\sum_i \alpha \cdot decay^{epoch_{current} - epoch_i} \cdot value_i}
$$

*Scaling*: notice that in our definition of the contribution above, the
$\alpha \cdot decay^{epoch_{current}}$ factors cancel out in the numerator and denominator. But we
can't simply eliminate scaling internally, as we need to avoid overflows (a real danger because of
the exponential growth of $decay^{-epoch_i}$). We also don't want to rescale all values after every
second (epoch), because that would be too expensive. We instead scale all time-weighted sums by
$\alpha \cdot decay^{baseEpoch}$, where $baseEpoch$ is initially zero. The $baseEpoch$ value is
opportunistically advanced to $epoch_{current}$ whenever elements in the counter are scanned.
Scans are performed when:

 1. The number of keys in the counter is double the number of keys present at the end of the
    previous scan. Such scans are performed to ensure that entries can be garbage-collected when 
    their maximum possible contribution is less than or equal to `Config.error`.
 1. There is a call to `getHotKeys()`. A scan is required to identify all keys whose contribution is
    potentially greater than `Config.support`, and the implementation  opportunistically rescales
    during that scan.
 1. An overflow is observed when incrementing the total weighted sum for the counter. Including the
    $\alpha$ term in the scaling factor ensures that the total weighted sum remains an EWMA of the
    rate as of the base epoch, which makes it easier to reason about the scale of the total weighted 
    sum. Search the implementation for "Base epoch rescaling" for more details.

NOTE: when reasoning about the correctness of the lossy counter implementation, it is easiest to
assume that scaling does not occur and that all time-weighted sums (error thresholds, totals, etc.)
are monotonic. In comments, we occasionally refer to "unscaled" values to make it clear that we are
relying on this simplifying assumption.

### Performance considerations

*Memory*: to satisfy the "no false negatives" rule, the counter internally _must_ track all keys
whose true contribution might be greater than `Config.error`. Occasionally, a full scan is performed
to remove keys whose contribution has fallen below this threshold. These scans are at least
triggered when the number of keys in the counter is double the number of keys present at the end of
the previous scan. As mentioned in the discussion of "Scaling" above, scans may also be triggered by
overflows or by `getHotKeys()` calls.

Motwani and Manku [^1] demonstrated that while, worst-case, the counter will need to track
$\frac{1}{error} \cdot log(error \cdot N)$ keys given $N$ increments, the expected number of
entries, assuming a fixed distribution over low-frequency keys, is $\frac{7}{error}$. The
adversarial workload for the algorithm is one in which a growing set of keys is kept at a "simmer",
where each key is incremented just frequently enough to remain above the (ever rising) error
threshold. However, the expectation is that the true contributions of all tracked keys will be some
significant (and fixed over time) fraction of the error threshold, resulting in stable key counts.

In contrast with the classic Lossy Count algorithm, which scans after
$\lceil \frac{1}{error} \rceil$ simple increments, the current implementation chooses to scan only
after the number of tracked keys doubles. This is important as a single call to `incrementBy()` may
increment the sum by more than $\lceil \frac{1}{error} \rceil$, and scanning after each call would
be prohibitively expensive. The scan-after-doubling policy ensures that the number of tracked keys
pending eviction is at worst double the optimal number.

*Runtime*: when considering size-triggered scans only, the amortized runtime cost of `incrementBy()`
is $O(1)$, since a scan is performed when the number of tracked keys doubles. Overflow scans are
infrequent (worst-case, they occur after 10s of hours) and in practice are irrelevant since scans
are triggered by `getHotKeys()` calls at a much higher frequency.

[^1]: Motwani, R; Manku, G.S (2002). "Approximate frequency counts over data streams".
VLDB '02 Proceedings of the 28th International Conference on Very Large Data Bases: 346–357.

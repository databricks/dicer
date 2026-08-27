package com.databricks.caching.util

import java.time.Instant
import java.util.concurrent.CompletableFuture

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.util.control.NonFatal

import com.databricks.caching.util.CachingErrorCode.ETCD_CLIENT_UNEXPECTED_WATCH_FAILURE
import com.google.protobuf.ByteString
import com.databricks.caching.util.EtcdClient.{
  JetcdWrapper,
  KeyNamespace,
  ScopedKey,
  Version,
  WatchArgs,
  WatchEvent
}
import io.grpc.Status
import io.prometheus.client.Gauge
import io.etcd.jetcd.{KeyValue, Watch, watch}
import io.etcd.jetcd.Watch.Listener
import io.etcd.jetcd.kv.GetResponse
import io.etcd.jetcd.options.{GetOption, WatchOption}
import io.etcd.jetcd.watch.WatchResponse
import io.etcd.jetcd.watch.{WatchEvent => JetcdWatchEvent}

import com.databricks.caching.util.AssertMacros.ifail
import com.databricks.caching.util.EtcdKeyValueMapper.ParsedVersionedKey
import com.databricks.caching.util.EtcdWatcher.{EtcdWatcherStateMachine, Metrics}
import com.databricks.caching.util.EtcdWatcher.EtcdWatcherStateMachine.{
  DriverAction,
  Event,
  RunState
}
import com.databricks.logging.AttributionContext
import com.databricks.logging.AttributionContextTracing

/**
 * State machine driver for an [[EtcdClient.watch]] request. Implements [[Cancellable]] so that the
 * watch can be interrupted. Note that cancellation has no effect until after an initial read has
 * been performed.
 *
 * The driver includes two features to mitigate the risk of a wedged watch, which is known issue
 * with the etcd client that we use:
 *
 * - Backup polling: The driver periodically performs a tail read at the most recently known
 *   version, so that even if the underlying watch is wedged the application continues to receive
 *   watch events.
 * - Bounded watch lifetime: The driver automatically cancels the watch after a specified duration
 *   so that the caller will create a new (and hopefully healthy) watch.
 *
 *  Both of these features are configurable via the [[Config]] object passed to the constructor.
 *
 * @param sec The sequential execution context used to protect watcher state.
 * @param jetcd The client used to reach the etcd cluster.
 * @param keyNamespace The namespace of the key being watched.
 * @param watchArgs The arguments to the watch request.
 * @param callbackOpt The callback to which watch events are supplied. When None, the watch has
 *                    received a terminal error.
 */
class EtcdWatcher private (
    sec: SequentialExecutionContext,
    jetcd: JetcdWrapper,
    keyNamespace: KeyNamespace,
    watchArgs: WatchArgs,
    private var callbackOpt: Option[StreamCallback[WatchEvent]])
    extends AttributionContextTracing
    with Cancellable {

  private val scopedKey = ScopedKey(keyNamespace, watchArgs.key)

  private val logger = PrefixLogger.create(this.getClass, scopedKey.toString)

  /** Generic driver implementation that drives the [[EtcdWatcherStateMachine]]. */
  private val baseDriver =
    new StateMachineDriver[Event, DriverAction, EtcdWatcherStateMachine](
      sec = sec,
      stateMachine = new EtcdWatcherStateMachine(watchArgs, loggerPrefix = scopedKey.toString),
      performAction = performAction,
      alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
    )

  /**
   * Handle to etcd watcher, which is used to cancel the watch request. Unset until the initial read
   * completes.
   */
  private var watcherOpt: Option[Watch.Watcher] = None

  /** Tracks the latest error reported by the etcd watcher. */
  private var errorOpt: Option[Status] = None

  /** Cancellation reason passed to [[cancel()]]. */
  private var cancellationStatusOpt: Option[Status] = None

  override def cancel(reason: Status): Unit = sec.run {
    handleCancelAction(reason)
  }

  /** Starts watching. Reads initial state from etcd and then starts a watch stream. */
  private def start(): Unit = {
    sec.assertCurrentContext()
    baseDriver.start()
  }

  /** Performs an action requested of this driver by the etcd watcher state machine. */
  private def performAction(action: DriverAction): Unit = {
    sec.assertCurrentContext()

    action match {
      case DriverAction.Read(knownVersion: Option[Version], pageReadLimit: Int) =>
        handleReadAction(knownVersion, pageReadLimit)

      case DriverAction.StartEtcdWatch(knownRevision: Long, knownVersionOpt: Option[Version]) =>
        handleWatchEtcdAction(knownRevision, knownVersionOpt)

      case DriverAction.CancelWatch(status: Status) =>
        handleCancelAction(status)

      case DriverAction.ExecuteOnSuccessCallback(event: WatchEvent) =>
        for (callback: StreamCallback[WatchEvent] <- callbackOpt) {
          callback.executeOnSuccess(event)
        }

      case DriverAction.ExecuteOnFailureCallback(status: Status) =>
        for (callback: StreamCallback[WatchEvent] <- callbackOpt) {
          callback.executeOnFailure(status)
        }
        callbackOpt = None

      case DriverAction.UpdateWatchLaggingGauge(key: String, isLagging: Boolean) =>
        val gaugeValue: Double = if (isLagging) 1.0 else 0.0
        Metrics.watchLaggingGauge.labels(key).set(gaugeValue)
    }
  }

  /**
   * Performs a strong read for all currently stored versions of the watched key greater than
   * `knownVersionOpt` (where None starts the read from the minimum possible version),
   * up to a maximum of `pageReadLimit` versions.
   */
  private def handleReadAction(knownVersionOpt: Option[Version], pageReadLimit: Int): Unit = {
    sec.assertCurrentContext()

    val inclusiveStartVersion: Version = knownVersionOpt match {
      case Some(knownVersion: Version) =>
        // Skip past known versions.
        knownVersion.copy(lowBits = knownVersion.lowBits.value + 1L)
      case None => Version.MIN
    }
    // The underlying jetcd client can throw synchronously (e.g. `ClosedClientException` once the
    // client has been closed during pod shutdown). Convert any synchronous throw into a failed
    // future so it flows through the same handler as an asynchronous failure rather than escaping
    // into the StateMachineDriver as an UNCAUGHT_STATE_MACHINE_ERROR.
    val getFuture: CompletableFuture[GetResponse] = try {
      jetcd.get(
        EtcdKeyValueMapper.getVersionedKeyKeyBytes(scopedKey, inclusiveStartVersion),
        GetOption
          .newBuilder()
          .withRange(EtcdKeyValueMapper.toVersionedKeyExclusiveLimitBytes(scopedKey))
          .withSortOrder(GetOption.SortOrder.ASCEND)
          .withSortField(GetOption.SortTarget.KEY)
          .withLimit(pageReadLimit)
          .build()
      )
    } catch {
      case NonFatal(t: Throwable) =>
        logger.warn(s"jetcd.get threw synchronously: $t")
        val failed: CompletableFuture[GetResponse] = new CompletableFuture[GetResponse]()
        failed.completeExceptionally(t)
        failed
    }
    getFuture
      .whenComplete(
        (response: GetResponse, throwable: Throwable) =>
          // Clear context because the code initially runs on non-instrumented threads from the etcd
          // library, and nothing needs to be propagated by the SequentialExecutionContext.
          withAttributionContext(AttributionContext.background) {
            sec.run {
              if (throwable != null) {
                logger.warn(s"read failed: $throwable")
                val status: Status = StatusUtils.convertExceptionToStatus(throwable)
                errorOpt = Some(status)
                baseDriver.handleEvent(Event.ReadFailed(status))
              } else {
                baseDriver.handleEvent(Event.ReadSucceeded(response))
              }
            }
          }
      )
  }

  /**
   * Starts watching etcd from the given known revision and version, or from the initial version if
   * `version` is empty.
   */
  private def handleWatchEtcdAction(knownRevision: Long, knownVersionOpt: Option[Version]): Unit = {
    sec.assertCurrentContext()
    logger.info(
      s"watch() with key=${watchArgs.key}, knownVersionOpt=$knownVersionOpt, " +
      s"knownRevision=$knownRevision"
    )
    val inclusiveStartVersion: Version = knownVersionOpt match {
      case Some(knownVersion: Version) =>
        // Skip past known versions.
        knownVersion.copy(lowBits = knownVersion.lowBits.value + 1L)
      case None => Version.MIN
    }
    // The underlying jetcd client can throw synchronously (e.g. `ClosedClientException` once the
    // watch client has been closed during pod shutdown). Route any synchronous throw through the
    // same failure path as an `onError`/`onCompleted` callback rather than letting it escape into
    // the StateMachineDriver as an UNCAUGHT_STATE_MACHINE_ERROR.
    this.watcherOpt = try {
      Some(
        jetcd.watch(
          EtcdKeyValueMapper.getVersionedKeyKeyBytes(scopedKey, inclusiveStartVersion),
          WatchOption
            .newBuilder()
            .withRange(EtcdKeyValueMapper.toVersionedKeyExclusiveLimitBytes(scopedKey))
            .withNoDelete(true) // we only care about PUTs
            .withRevision(knownRevision + 1) // skip past the revision we already know about
            .build(),
          new Listener {
            override def onNext(watchResponse: WatchResponse): Unit = {
              // Clear context because the code initially runs on non-instrumented threads from the
              // etcd library, and nothing needs to be propagated by the SequentialExecutionContext.
              withAttributionContext(AttributionContext.background) {
                sec.run {
                  logger.debug(s"onNext")
                  baseDriver.handleEvent(Event.EtcdWatchEvent(watchResponse))
                }
              }
            }

            override def onError(throwable: Throwable): Unit = {
              // Clear context because the code initially runs on non-instrumented threads from the
              // etcd library, and nothing needs to be propagated by the SequentialExecutionContext.
              withAttributionContext(AttributionContext.background) {
                sec.run {
                  logger.warn(s"onError($throwable)")
                  // An error has occurred which breaks the underlying etcd watch stream. Record the
                  // error so that we can report it to the state machine when we get the onCompleted
                  // event.
                  errorOpt = Some(StatusUtils.convertExceptionToStatus(throwable))
                }
              }
            }

            override def onCompleted(): Unit = {
              // Clear context because the code initially runs on non-instrumented threads from the
              // etcd library, and nothing needs to be propagated by the SequentialExecutionContext.
              withAttributionContext(AttributionContext.background) {
                sec.run {
                  handleWatchFailure()
                }
              }
            }
          }
        )
      )
    } catch {
      case NonFatal(t: Throwable) =>
        logger.warn(s"jetcd.watch threw synchronously: $t")
        errorOpt = Some(StatusUtils.convertExceptionToStatus(t))
        // Defer to a fresh SEC tick so we don't re-enter the state machine while still inside the
        // current `performAction` invocation.
        sec.run {
          handleWatchFailure()
        }
        None
    }
  }

  /** Reports a watch failure to the state machine. */
  private def handleWatchFailure(): Unit = {
    sec.assertCurrentContext()
    logger.info(s"Watch completed, last error seen - $errorOpt")

    // We only expect a completed callback when the watch is cancelled or when a fatal
    // failure is observed when doing etcd watch that cannot be retried.
    logger.expect(
      errorOpt.nonEmpty || cancellationStatusOpt.nonEmpty,
      ETCD_CLIENT_UNEXPECTED_WATCH_FAILURE,
      "Watch failed without cancellation and no reported errors"
    )

    val status: Status = cancellationStatusOpt.getOrElse(errorOpt.getOrElse(Status.UNKNOWN))
    baseDriver.handleEvent(Event.EtcdWatchFailed(status))
  }

  /**
   * Handles a cancel action requested by the caller or the state machine.
   *
   * The state machine can request cancellation in cases such as detected data corruption.
   */
  private def handleCancelAction(reason: Status): Unit = sec.run {
    sec.assertCurrentContext()

    logger.info(s"Cancelling watch, reason: $reason")

    // Record the cancellation reason so that we can report it to the state machine when we get the
    // onCompleted event.
    cancellationStatusOpt = Some(reason)

    if (watcherOpt.nonEmpty) {
      // If the etcd watcher has been started, close it now. This will invoke onCompleted in the
      // listener which in turn will call handleWatchFailure to report the cancellation reason to
      // the state machine.
      watcherOpt.get.close()
    } else {
      // The state machine cancelled during the initial read due to an error such as data
      // corruption. We still need to invoke the failure callback and sent the WatchFailed event
      // in this case.
      handleWatchFailure()
    }
  }
}
object EtcdWatcher {

  /** Creates and starts an [[EtcdWatcher]] instance. */
  def create(
      sec: SequentialExecutionContext,
      jetcd: JetcdWrapper,
      keyNamespace: KeyNamespace,
      watchArgs: WatchArgs,
      callback: StreamCallback[WatchEvent]): EtcdWatcher = {
    val watcher = new EtcdWatcher(sec, jetcd, keyNamespace, watchArgs, Some(callback))
    sec.run {
      watcher.start()
    }
    watcher
  }

  /** Metrics for the watcher. */
  object Metrics {
    val watchLaggingGauge: Gauge = Gauge
      .build()
      .name("dicer_etcd_client_watch_lagging")
      .help("A gauge set to 1 if the watch is lagging for the specified key label, 0 otherwise")
      .labelNames("key")
      .register()
  }

  /**
   * The state machine for the EtcdWatcher.
   *
   * See [[RunState]] for the states that this state machine can be in.
   *
   * See [[Event]] and [[DriverAction]] for the events and actions that this state machine handles
   * and emits.
   *
   * @param watchedKey The key from the [[EtcdClient.watch]] call.
   * @param loggerPrefix The logger prefix to use for this state machine.
   */
  private[util] class EtcdWatcherStateMachine(watchArgs: WatchArgs, loggerPrefix: String)
      extends StateMachine[Event, DriverAction] {

    private val logger = PrefixLogger.create(this.getClass, loggerPrefix)

    private var runState: RunState = RunState.Startup

    /** Highest known version. The initial value of None represents "no knowledge". */
    private var knownVersionOpt: Option[Version] = None

    /**
     * The latest version observed from a watch event.
     *
     * Used along with [[latestBackupPollingVersionOpt]] to determine when the watch is lagging.
     */
    private var latestWatchVersionOpt: Option[Version] = None

    /** The latest version observed from a backup polling event. */
    private var latestBackupPollingVersionOpt: Option[Version] = None

    /**
     * Next time to do backup polling to mitigate laggy or wedged watches.
     *
     * Backup polling occurs only in [[RunState.Watching]], so this value will be TickerTime.MAX in
     * all other states. While in the watching state, the state machine ensures that whenever the
     * ticker team advances past this time, it will issue a read at the latest known version and
     * increment the backup polling time by the backup polling interval.
     */
    private var nextBackupPollingTime: TickerTime = TickerTime.MAX

    /** The time to end the watch, set to its actual value when the watch begins. */
    private var watchEndTime: TickerTime = TickerTime.MAX

    override def onAdvance(
        tickerTime: TickerTime,
        instant: Instant): StateMachineOutput[DriverAction] = {
      val outputBuilder = new StateMachineOutput.Builder[DriverAction]
      onAdvanceInternal(tickerTime, outputBuilder)
    }

    private def onAdvanceInternal(
        tickerTime: TickerTime,
        outputBuilder: StateMachineOutput.Builder[DriverAction])
        : StateMachineOutput[DriverAction] = {

      runState match {
        case RunState.Startup =>
          logger.info("Beginning initial read")

          // Enter the `ProcessInitialRead` state and perform strong read for all currently stored
          // versions of the watched key greater than [[knownVersionOpt]] (initially None) which
          // starts the read from the minimum possible version) to get the initial state of the
          // watched object.The state machine remains in ProcessInitialRead until it has processed
          // all the pages of the initial state, after which it triggers a causal event and
          // commences a watch against the underlying store starting the latest known version.
          outputBuilder.appendAction(DriverAction.Read(knownVersionOpt, watchArgs.pageLimit))
          runState = RunState.ProcessInitialRead
          onAdvanceInternal(tickerTime, outputBuilder)

        case RunState.ProcessInitialRead =>
        // No scheduled advance calls to handle in ProcessInitialRead.

        case RunState.Watching =>
          if (tickerTime >= nextBackupPollingTime) {
            logger.info(s"Triggering backup polling at $knownVersionOpt", every = 1.hour)
            // Trigger the read of the first page which in turn will trigger reads of subsequent
            // pages.
            outputBuilder.appendAction(DriverAction.Read(knownVersionOpt, watchArgs.pageLimit))
            // Schedule the next backup polling time.
            nextBackupPollingTime = tickerTime + watchArgs.backupPollingInterval
          }

          if (tickerTime >= watchEndTime) {
            logger.info(s"Watch has reached end time, cancelling")
            outputBuilder.appendAction(DriverAction.CancelWatch(Status.CANCELLED))
            // No need for backup polling or further watch end time enforcement after ending.
            nextBackupPollingTime = TickerTime.MAX
            watchEndTime = TickerTime.MAX
          }

          // Schedule an advance call by the next backup polling time or watch end time, whichever
          // is earlier.  Note in all code paths above that we advance those times when we reach
          // them, so we always avoid an instant advance which would cause the state machine to
          // spin.
          outputBuilder.ensureAdvanceBy(nextBackupPollingTime)
          outputBuilder.ensureAdvanceBy(watchEndTime)

        case RunState.Failed =>
        // No scheduled advance calls to handle in ProcessInitialRead.
      }

      outputBuilder.build()
    }

    override def onEvent(
        tickerTime: TickerTime,
        instant: Instant,
        event: Event): StateMachineOutput[DriverAction] = {
      val outputBuilder = new StateMachineOutput.Builder[DriverAction]

      event match {
        case Event.ReadSucceeded(response: GetResponse) =>
          handleReadSucceeded(tickerTime, outputBuilder, response)

        case Event.ReadFailed(status: Status) =>
          logger.warn(s"Read request failed with $status.")

          runState match {
            case RunState.Startup =>
              // Our internal contract is that the first call to the state machine is onAdvance,
              // so if we are still in the Startup state here it indicates an internal bug.
              ifail("Initial onAdvance call required and expected to leave Startup state")

            case RunState.ProcessInitialRead =>
              // If the initial read fails, we cancel the watch with a failure. The calling code is
              // expected to retry the watch with exponential backoff.
              outputBuilder.appendAction(
                DriverAction.CancelWatch(status)
              )

              runState = RunState.Failed

            case RunState.Watching =>
              // If a backup polling read fails, we retry at the latest known version after the
              // backup polling interval. That interval is infrequent enough that exponential
              // backoff is not necessary.
              nextBackupPollingTime = tickerTime + watchArgs.backupPollingInterval

            case RunState.Failed =>
            // We received a delayed read failure after the watch failed. This is allowed and
            // no action is required; we don't need to issue further backup polling reads
            // after the watch has failed.
          }

        case Event.EtcdWatchEvent(watchResponse: watch.WatchResponse) =>
          handleWatchEvent(outputBuilder, watchResponse)

        case Event.EtcdWatchFailed(status: Status) =>
          logger.info(s"Watch failed with $status")
          runState = RunState.Failed
          outputBuilder.appendAction(
            DriverAction.ExecuteOnFailureCallback(status)
          )
          // As soon as the watch fails we can stop backup polling and enforcing watch end time,
          // even before the state machine receives the failure callback and enters the failed
          // state.
          nextBackupPollingTime = TickerTime.MAX
          watchEndTime = TickerTime.MAX
      }
      onAdvanceInternal(tickerTime, outputBuilder)
    }

    /** Handles a successful read response. */
    private def handleReadSucceeded(
        tickerTime: TickerTime,
        outputBuilder: StateMachineOutput.Builder[DriverAction],
        response: GetResponse): Unit = {
      // Process the key-value pairs in the response, but stop processing if we find that any of
      // them are invalid (e.g. unparseable).
      logger.debug(s"Read succeeded")

      var allValuesValid: Boolean = true
      for (kv: KeyValue <- response.getKvs.asScala) {
        // If any values are invalid, onKeyValue will report that the watch has failed to the
        // caller.
        allValuesValid = allValuesValid && onKeyValue(kv, isWatchEvent = false, outputBuilder)
      }
      if (allValuesValid) {
        if (response.isMore) {
          logger.info(s"Continuing read at $knownVersionOpt")
          outputBuilder.appendAction(DriverAction.Read(knownVersionOpt, watchArgs.pageLimit))
        } else { // No more values to read.
          runState match {
            case RunState.ProcessInitialRead =>
              // We have finished reading all the pages of the initial state. We can now emit a
              // causal event to the caller and start the watch at the latest known version.
              logger.info(s"Emitting causal event and starting watch at $knownVersionOpt")
              runState = RunState.Watching
              outputBuilder.appendAction(
                DriverAction.ExecuteOnSuccessCallback(WatchEvent.Causal)
              )
              outputBuilder.appendAction(
                DriverAction.StartEtcdWatch(response.getHeader.getRevision, knownVersionOpt)
              )
              // Schedule a backup poll in case the watch is laggy.
              nextBackupPollingTime = tickerTime + watchArgs.backupPollingInterval

              // Schedule a time to end the watch, if any.
              watchEndTime = watchArgs.watchDuration match {
                case duration: FiniteDuration => tickerTime + duration
                case _: Duration.Infinite => TickerTime.MAX
              }

            case _ =>
              logger.info(s"Completed backup polling at $knownVersionOpt", every = 1.hour)
          }
        }
      }
    }

    /** Handles all of the keys and values in a watch response event. */
    private def handleWatchEvent(
        outputBuilder: StateMachineOutput.Builder[DriverAction],
        watchResponse: WatchResponse): Unit = {
      watchResponse.getEvents.forEach { event: JetcdWatchEvent =>
        // We only care about PUT events.
        if (event.getEventType == JetcdWatchEvent.EventType.PUT) {
          onKeyValue(event.getKeyValue, isWatchEvent = true, outputBuilder)
        }
      }
    }

    /**
     * Handles a key-value pair from etcd, either from [[handleReadSucceeded()]] or
     * [[handleWatchEvent()]] and delivers it to the callback if it is valid and newer than
     * the current latest known version.
     *
     * In the event the key-value pair is invalid, the state machine will emit an action to cancel
     * the watch. The cancellation status will be set to DATA_LOSS if the key is malformed, or
     * FAILED_PRECONDITION if the key is incorrectly a global log versioned key.
     *
     * Returns false if the value was invalid, true otherwise.
     *
     * @param isWatchEvent True if the key-value pair is from a watch event, false if it is from a
     *                     read response.
     */
    private def onKeyValue(
        kv: KeyValue,
        isWatchEvent: Boolean,
        outputBuilder: StateMachineOutput.Builder[DriverAction]) = {
      try {
        EtcdKeyValueMapper.parseVersionedKey(kv.getKey) match {
          case ParsedVersionedKey(_, retVersion: Version) =>
            // Attempt to parse the VersionedValue.
            val value = ByteString.copyFrom(kv.getValue.getBytes)
            val event: WatchEvent = WatchEvent.VersionedValue(retVersion, value)

            if (isWatchEvent) {
              if (retVersion.compare(latestWatchVersionOpt) > 0) {
                latestWatchVersionOpt = Some(retVersion)
              }
            } else {
              if (retVersion.compare(latestBackupPollingVersionOpt) > 0) {
                latestBackupPollingVersionOpt = Some(retVersion)
              }
            }

            // Only process the event if it's newer than the last known version.
            if (retVersion.compare(this.knownVersionOpt) > 0) {
              this.knownVersionOpt = Some(retVersion)
              outputBuilder.appendAction(DriverAction.ExecuteOnSuccessCallback(event))
            } else {
              logger.info(
                s"Skipping stale event: got $retVersion, knownVersionOpt=$knownVersionOpt",
                every = 1.second
              )
            }
            true
        }
      } catch {
        // Handle invalid VersionedValue.
        case _: IllegalArgumentException =>
          try {
            EtcdKeyValueMapper.parseGlobalLogVersionedKey(kv.getKey)
            outputBuilder.appendAction(
              DriverAction.CancelWatch(
                Status.FAILED_PRECONDITION.withDescription(
                  s"Expected versioned key but got global log versioned key: ${kv.getKey}"
                )
              )
            )
            false
          } catch {
            case _: IllegalArgumentException =>
              // The key is neither a regular versioned key nor a global log versioned key.
              outputBuilder.appendAction(
                DriverAction.CancelWatch(
                  Status.DATA_LOSS.withDescription(
                    s"Data corruption: malformed key: ${kv.getKey}"
                  )
                )
              )
              false
          }
      } finally {
        val isWatchLagging: Boolean = latestBackupPollingVersionOpt.exists { backupPollingVersion =>
          backupPollingVersion.compare(latestWatchVersionOpt) > 0
        }
        outputBuilder.appendAction(
          DriverAction.UpdateWatchLaggingGauge(watchArgs.key, isWatchLagging)
        )
      }
    }
  }

  private[util] object EtcdWatcherStateMachine {

    /** Run states for the watcher state machine. */
    private sealed trait RunState
    private object RunState {

      /** The watcher is starting up. */
      case object Startup extends RunState

      /**
       * The watcher is performing a strong read for all currently stored versions of the watched
       * key to get the initial state of the watched object.
       */
      case object ProcessInitialRead extends RunState

      /**
       * The watcher has established the underlying etcd watch and is delivering watch events to the
       * callback.
       */
      case object Watching extends RunState

      /** The watch has failed. This is a terminal state. */
      case object Failed extends RunState
    }

    /** Events handled by the watcher state machine. */
    sealed trait Event
    object Event {

      /** The underlying watch has delivered a watch response.  */
      case class EtcdWatchEvent(watchResponse: watch.WatchResponse) extends Event

      /** The underlying watch has failed with `status`. */
      case class EtcdWatchFailed(status: Status) extends Event

      /**
       * A read action has succeeded. The state machine should incorporate the read results if they
       * are newer than what it knows.
       */
      case class ReadSucceeded(response: GetResponse) extends Event

      /** A read action has failed. */
      case class ReadFailed(status: Status) extends Event
    }

    sealed trait DriverAction
    object DriverAction {

      /**
       * Read up to `pageReadLimit` items starting at `knownVersionOpt`, or from the initial version
       * if `knownVersionOpt` is empty.
       */
      case class Read(knownVersionOpt: Option[Version], pageReadLimit: Int) extends DriverAction

      /** Start a watch against the underlying jetcd client. */
      case class StartEtcdWatch(knownRevision: Long, knownVersion: Option[Version])
          extends DriverAction

      /**
       *  Cancel the watch against the underlying jetcd client and trigger [[Event.EtcdWatchFailed]]
       *  against the state machine.
       */
      case class CancelWatch(status: Status) extends DriverAction

      /** Execute the caller's OnSuccess callback with an [[EtcdClient.WatchEvent]]. */
      case class ExecuteOnSuccessCallback(event: EtcdClient.WatchEvent) extends DriverAction

      /** Execute the caller's OnFailure callback with a watch failure status code.. */
      case class ExecuteOnFailureCallback(status: Status) extends DriverAction

      /** Update the watch lagging gauge. */
      case class UpdateWatchLaggingGauge(key: String, isLagging: Boolean) extends DriverAction
    }
  }
}

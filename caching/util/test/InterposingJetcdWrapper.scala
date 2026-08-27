package com.databricks.caching.util

import java.util.concurrent.CompletableFuture
import java.util.concurrent.locks.ReentrantLock
import javax.annotation.concurrent.GuardedBy
import scala.collection.{Seq, mutable}
import scala.collection.mutable.ArrayBuffer
import scala.compat.java8.FutureConverters
import scala.concurrent.Future
import io.etcd.jetcd
import io.etcd.jetcd.kv.{GetResponse, TxnResponse}
import io.etcd.jetcd.op.{Cmp, Op}
import io.etcd.jetcd.options.{GetOption, WatchOption}
import io.etcd.jetcd.watch.WatchResponse
import io.etcd.jetcd.{ByteSequence, Watch}
import com.databricks.caching.util.EtcdClient.{JetcdWrapper, JetcdWrapperImpl}
import com.databricks.caching.util.InterposingJetcdWrapper.{PausableWatchListener, logger}
import com.databricks.caching.util.Lock.withLock

/*
 * Wrapper around [[EtcdClient.JetcdWrapper]] supporting error injection and spy functionality.
 * Thread-safe.
 *
 * @sec An SEC on which to schedule callbacks and timeout events.
 * @param clock The clock to use for reporting times in [[getCallTimes()]].
 *
 * Implementation note: `sec.getClock` and `clock` are independent and do not have be the same.
 */
final class InterposingJetcdWrapper private (
    sec: SequentialExecutionContext,
    client: jetcd.Client,
    clock: TypedClock)
    extends JetcdWrapper {

  private val delegate: JetcdWrapper = new JetcdWrapperImpl(sec, client)

  /** The lock used to guard the internal state. */
  private val lock = new ReentrantLock()

  /** Failure for [[get()]] operations. */
  @GuardedBy("lock")
  private var getFailureOpt: Option[Throwable] = None

  /** Synchronous throw for [[get()]] operations, simulating a jetcd shutdown race. */
  @GuardedBy("lock")
  private var getSynchronousThrowOpt: Option[Throwable] = None

  /** Synchronous throw for [[watch()]] operations, simulating a jetcd shutdown race. */
  @GuardedBy("lock")
  private var watchSynchronousThrowOpt: Option[Throwable] = None

  /** Latest watch listener. */
  @GuardedBy("lock")
  private var latestWatchListenerOpt: Option[PausableWatchListener] = None

  /** Latest watch handle. */
  @GuardedBy("lock")
  private var latestWatchWatcherOpt: Option[Watch.Watcher] = None

  /** The ticker times at which [[get()]] calls were issued. */
  @GuardedBy("lock")
  private val getCallTimesBuffer: ArrayBuffer[TickerTime] = ArrayBuffer[TickerTime]()

  /** Whether to immediately and unexpected complete watches. */
  @GuardedBy("lock")
  private var unexpectedlyCompleteWatches: Boolean = false

  /** Whether to block incoming commit() calls. */
  @GuardedBy("lock")
  private var blockCommits: Boolean = false

  /** All commit operations currently blocked due to [[blockCommits]]. */
  @GuardedBy("lock")
  private val blockedCommits: ArrayBuffer[() => Future[Unit]] = ArrayBuffer()

  /**
   * Registers an exception that will be yielded by all subsequent [[JetcdWrapper.get()]] requests
   * until [[stopFailingGets()]] is called. When used with the EtcdClient, failing gets will prevent
   * watches from being re-established.
   */
  def startFailingGets(throwable: Throwable): Unit = withLock(lock) {
    getFailureOpt = Some(throwable)
  }

  /** Stops failing gets. */
  def stopFailingGets(): Unit = withLock(lock) {
    getFailureOpt = None
  }

  /**
   * Registers an exception that all subsequent [[JetcdWrapper.get()]] requests will throw
   * synchronously, until [[stopThrowingFromGets()]] is called. This simulates the jetcd client
   * throwing during pod shutdown (e.g. because the underlying gRPC channel has already been
   * closed).
   */
  def startThrowingFromGets(throwable: Throwable): Unit = withLock(lock) {
    getSynchronousThrowOpt = Some(throwable)
  }

  /** Stops throwing synchronously from gets. */
  def stopThrowingFromGets(): Unit = withLock(lock) {
    getSynchronousThrowOpt = None
  }

  /**
   * Registers an exception that all subsequent [[JetcdWrapper.watch()]] requests will throw
   * synchronously, until [[stopThrowingFromWatches()]] is called. This simulates the jetcd watch
   * client throwing `ClosedWatchClientException` during pod shutdown.
   */
  def startThrowingFromWatches(throwable: Throwable): Unit = withLock(lock) {
    watchSynchronousThrowOpt = Some(throwable)
  }

  /** Stops throwing synchronously from watches. */
  def stopThrowingFromWatches(): Unit = withLock(lock) {
    watchSynchronousThrowOpt = None
  }

  /**
   * Pauses the current watch, queueing rather than delivering watch events. This allows tests to
   * simulate the etcd watch becoming laggy or wedged.
   */
  def pauseCurrentWatch(): Unit = withLock(lock) {
    for (latestWatchListener: PausableWatchListener <- latestWatchListenerOpt) {
      latestWatchListener.pause()
    }
  }

  /**
   * Resume the latest watch, delivering all queued watch events and resuming normal delivery for
   * fture events.
   */
  def resumeCurrentWatch(): Unit = withLock(lock) {
    for (latestWatchListener: PausableWatchListener <- latestWatchListenerOpt) {
      latestWatchListener.resume()
    }
  }

  /**
   * REQUIRES: A [[JetcdWrapper.watch()]] request is already established.
   *
   * Eventually fails the current watch with an exception.
   */
  def failCurrentWatch(throwable: Throwable): Unit = withLock(lock) {
    require(latestWatchListenerOpt.nonEmpty, "Expected latest watch listener")
    require(latestWatchWatcherOpt.nonEmpty, "Expected latest watch watcher")

    // Set the error on listener, and close the watcher.
    for (latestWatchListener <- latestWatchListenerOpt;
      latestWatchWatcher <- latestWatchWatcherOpt) {
      latestWatchListener.onError(throwable)
      latestWatchWatcher.close()
    }

    // Reset the listener for next failures.
    latestWatchListenerOpt = None
  }

  /**
   * Sets the commit blocking behavior according to `blockCommits`. If `blockCommits` is true, then
   * all future [[commit()]] calls will be blocked (and can be executed one-by-one using
   * [[runEarliestBlockedCommit()]]). Otherwise, all currently blocked commit operations will be
   * executed and future [[commit()]] calls will be allowed to proceed.
   *
   * Note that this does not wait for the completion of the blocked commit operations. If this is
   * desired, the caller should use [[runEarliestBlockedCommit()]] repeatedly until all blocked are
   * executed.
   */
  def setBlockCommits(blockCommits: Boolean): Unit = withLock(lock) {
    this.blockCommits = blockCommits
    if (!blockCommits) {
      // Release all the pending commit ops.
      for (blockedCommit: (() => Future[Unit]) <- blockedCommits) {
        blockedCommit.apply()
      }
      blockedCommits.clear()
    }
  }

  /** Executes the first commit operation blocked due to [[setBlockCommits()]]. */
  def runEarliestBlockedCommit(): Future[Unit] = withLock(lock) {
    assert(blockedCommits.nonEmpty)
    val earliestCommitOp: () => Future[Unit] = blockedCommits.remove(0)
    earliestCommitOp.apply()
  }

  override def commit(
      ifs: Seq[Cmp],
      thens: Seq[Op],
      elses: Seq[Op]): CompletableFuture[TxnResponse] = withLock(lock) {
    if (blockCommits) {
      val javaFuture = new CompletableFuture[TxnResponse]()
      blockedCommits += { () =>
        // Issuing the commit and completing the javaFuture gives us another java future, which we
        // need to convert to scala future to return to the test owner.
        val voidFuture: CompletableFuture[Void] = delegate
          .commit(ifs, thens, elses)
          .thenAccept(javaFuture.complete _)

        // Avoid leaking Void type into Scala and remap it to Unit.
        FutureConverters.toScala(voidFuture).map(_ => ())(sec)
      }
      javaFuture
    } else {
      delegate.commit(ifs, thens, elses)
    }
  }

  /** Causes the wrapper to unexpectedly complete future watches immediately. */
  def startUnexpectedlyCompletingWatches(): Unit = withLock(lock) {
    unexpectedlyCompleteWatches = true
  }

  override def get(key: ByteSequence, getOption: GetOption): CompletableFuture[GetResponse] =
    withLock(lock) {
      getCallTimesBuffer += clock.tickerTime()
      for (throwable: Throwable <- getSynchronousThrowOpt) {
        logger.info(s"Injecting synchronous get throw $throwable")
        throw throwable
      }
      getFailureOpt match {
        case Some(getFailure) =>
          logger.info(s"Injecting get failure #$getFailure")
          val future = new CompletableFuture[GetResponse]()
          future.completeExceptionally(getFailure)
          future
        case None =>
          delegate.get(key, getOption)
      }
    }

  /**
   * The ticker times at which get calls were issued. Useful for verifying watch retry behavior in
   * the store.
   */
  override def watch(
      key: ByteSequence,
      option: WatchOption,
      listener: Watch.Listener): Watch.Watcher = withLock(lock) {

    for (throwable: Throwable <- watchSynchronousThrowOpt) {
      logger.info(s"Injecting synchronous watch throw $throwable")
      throw throwable
    }

    // Update the latest watcher and listener to support `failCurrentWatch`, `pauseCurrentWatch`,
    // and `resumeCurrentWatch`.
    latestWatchListenerOpt = Some(new PausableWatchListener(sec, lock, listener))
    latestWatchWatcherOpt = Some(delegate.watch(key, option, latestWatchListenerOpt.get))

    if (unexpectedlyCompleteWatches) {
      sec.run {
        listener.onCompleted()
      }
    }

    latestWatchWatcherOpt.get
  }

  /** The ticker times at which `get` calls were issued.  */
  def getCallTimes: Vector[TickerTime] = getCallTimesBuffer.toVector
}

object InterposingJetcdWrapper {
  private val logger = PrefixLogger.create(getClass, "")

  /**
   * Returns a new instance using `client` and `clock`.
   */
  def create(client: jetcd.Client, clock: TypedClock): InterposingJetcdWrapper = {
    val sec = SequentialExecutionContext.createWithDedicatedPool(
      name = "interposing-jetcd-wrapper",
      alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
    )
    new InterposingJetcdWrapper(sec, client, clock)
  }

  /** A watch listener wrapper that allows delivery of watch events to be paused and resumed. */
  private class PausableWatchListener(
      sec: SequentialExecutionContext,
      lock: ReentrantLock,
      listener: Watch.Listener)
      extends Watch.Listener {

    /** A queue of deferred events accumulated while delayWatchEvents is set. */
    @GuardedBy("lock")
    private val pausedWatchEventQueue = new mutable.Queue[WatchResponse]()

    /** Whether we should pause watch events by queueing them. */
    @GuardedBy("lock")
    private var pauseWatchEvents: Boolean = false

    override def onNext(watchResponse: WatchResponse): Unit = {
      withLock(lock) {
        if (pauseWatchEvents) {
          pausedWatchEventQueue.enqueue(watchResponse)
        } else {
          sec.run {
            listener.onNext(watchResponse)
          }
        }
      }
    }

    override def onError(throwable: Throwable): Unit = {
      listener.onError(throwable)
    }

    override def onCompleted(): Unit = {
      listener.onCompleted()
    }

    def pause(): Unit = withLock(lock) {
      pauseWatchEvents = true
    }

    def resume(): Unit = withLock(lock) {
      pauseWatchEvents = false
      while (pausedWatchEventQueue.nonEmpty) {
        val nextItem = pausedWatchEventQueue.dequeue()
        sec.run {
          listener.onNext(nextItem)
        }
      }
    }
  }
}

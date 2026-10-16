package com.databricks.caching.util

import java.util.concurrent.{LinkedBlockingQueue, CountDownLatch}

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration

import io.grpc.{Status, StatusRuntimeException}

import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.testing.DatabricksTest

class WatchCellSuite extends DatabricksTest {

  test("Set WatchCell value") {
    // Test plan: Create a watch cell and a watcher. Set a value - make sure that the value is
    // received by the watcher.
    val (cell, callback) = createCell("set value")
    val cancellable: Cancellable = cell.watch(callback)
    assert(callback.numElements == 0, "no callbacks expected yet")

    // Set the value and wait.
    cell.setValue("Hello")
    callback.waitForPredicate(_ == "Hello", 0)
    cancellable.cancel(Status.CANCELLED)

    assert(cell.getLatestValueOpt.get == "Hello")
    assert(cell.getStatus == Status.OK)

    // Ensure that the callback was removed from the cell. The `WatchCell.watch` contract requires
    // that the same callback not be used with `watch` on the same cell multiple times without in
    // intervening cancel. Therefore, cancel works correctly if adding the same callback does not
    // throw an error.
    cell.watch(callback)
    assert(cell.getStatus == Status.OK)
  }

  test("Set WatchCell value before watch") {
    // Test plan: Create a watch cell with a value before any watcher is started. Then start a
    // watcher and make sure that the initial value is received. Then check another value is
    // received by the watcher.
    val (cell, callback) = createCell("set initial value")
    assert(callback.numElements == 0, "no callbacks expected yet")
    cell.setValue("Hello")
    val cancellable: Cancellable = cell.watch(callback)
    callback.waitForPredicate(_ == "Hello", 0)
    assert(cell.getLatestValueOpt.get == "Hello")

    // Send another value.
    cell.setValue("Bye")
    callback.waitForPredicate(_ == "Bye", 1)
    assert(cell.getLatestValueOpt.get == "Bye")

    cancellable.cancel(Status.CANCELLED)
    // Verify that the watch for `callback` was cancelled by starting another watch for `callback`
    // and checking that no error is thrown.
    cell.watch(callback)
    assert(cell.getStatus == Status.OK)
  }

  test("Callback observes latest WatchCell value and status") {
    // Test plan: Verify that a callback reads the latest value of the WatchCell when it's
    // triggered. This is guarding against a specific ordering bug where in callbacks are scheduled
    // to run by `setValue` _before_ the value is written to the volatile state field, causing the
    // last invocation of a callback to observe a stale value.

    // HACK: This hijacks the SEC interface to run an immediate inline on the calling thread, rather
    // than enqueueing a task to run asynchronously. This simulates the case where the callback is
    // run extremely quickly.
    val inlineExecutor: SequentialExecutionContext = {
      val sec: SequentialExecutionContext = SequentialExecutionContext.createWithDedicatedPool(
        name = "Context-callback-observes-latest-value",
        alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
      )
      new DelegatingSequentialExecutionContext(sec) {
        override def run[U](func: => U): Unit = { func }
      }
    }

    val cell = new WatchCell[String]

    val observedValues = new LinkedBlockingQueue[Option[String]]()
    val observedStatuses = new LinkedBlockingQueue[Status]()
    val callback = new StreamCallback[String](inlineExecutor) {
      override protected def onSuccess(ignored: String): Unit = {
        observe()
      }

      override protected def onFailure(ignored: Status): Unit = {
        observe()
      }

      /** Records the cell's latest value and status in their respective queues. */
      private def observe(): Unit = {
        observedValues.add(cell.getLatestValueOpt)
        observedStatuses.add(cell.getStatus)
      }
    }

    cell.watch(callback)
    cell.setValue("Initial value")
    cell.setValue("Latest value")
    cell.setErrorStatus(Status.FAILED_PRECONDITION)

    assert(observedValues.size() == 3)
    assert(observedValues.take() == Some("Initial value"))
    assert(observedValues.take() == Some("Latest value"))
    assert(observedValues.take() == Some("Latest value"))

    assert(observedStatuses.size() == 3)
    assert(observedStatuses.take() == Status.OK)
    assert(observedStatuses.take() == Status.OK)
    assert(observedStatuses.take() == Status.FAILED_PRECONDITION)
  }

  test("Set WatchCell error") {
    // Test plan: Create a cell and a watcher. Provide an error and ensure that this error is
    // received by the watcher.
    val (cell, callback) = createCell("set error")
    cell.watch(callback)
    assert(callback.numElements == 0, "no callbacks expected yet")

    // Set error and wait.
    cell.setErrorStatus(Status.FAILED_PRECONDITION)
    callback.waitForStatus(Status.FAILED_PRECONDITION, 0)

    // No need to cancel since the cell has already cancelled the callback.
    // Verify that the watch for `callback` was cancelled by starting another watch for `callback`
    // and checking that no error is thrown.
    cell.watch(callback)
    assert(cell.getLatestValueOpt.isEmpty)
    assert(cell.getStatus == Status.FAILED_PRECONDITION)
  }

  test("Set WatchCell value then error") {
    // Test plan: Create a cell and a watcher. Provide a value and then error and ensure that this
    // error is received by the watcher. Also, check that the latest value is available.
    val (cell, callback) = createCell("set value then error")
    cell.watch(callback)
    assert(callback.numElements == 0, "no callbacks expected yet")

    cell.setValue("Hello")
    callback.waitForPredicate(_ == "Hello", 0)

    // Set error and wait.
    cell.setErrorStatus(Status.FAILED_PRECONDITION)
    callback.waitForStatus(Status.FAILED_PRECONDITION, 1)

    // No need to cancel since the cell has already cancelled the callback.
    // Verify that the watch for `callback` was cancelled by starting another watch for `callback`
    // and checking that no error is thrown.
    cell.watch(callback)

    // Check that both the latest value and error conditions are available as expected.
    assert(cell.getLatestValueOpt.get == "Hello")
    assert(cell.getStatus == Status.FAILED_PRECONDITION)
  }

  test("Set WatchCell error before watch") {
    // Test plan: Create a cell and have its initial state be an error state. Start a watcher and
    // make sure that it gets that error and is unsubscribed.
    val (cell, callback) = createCell("set value")
    cell.setErrorStatus(Status.FAILED_PRECONDITION)

    // Start the watch and wait for the error.
    cell.watch(callback)
    callback.waitForStatus(Status.FAILED_PRECONDITION, 0)
    assert(callback.numElements == 1, "one callback expected with the pre-set error")

    // Check that the callback has been cancelled by the cell.
    // Verify that the watch for `callback` was cancelled by starting another watch for `callback`
    // and checking that no error is thrown.
    cell.watch(callback)
    assert(cell.getLatestValueOpt.isEmpty)
    assert(cell.getStatus == Status.FAILED_PRECONDITION)
  }

  test("Set WatchCell value multiple subscribers") {
    // Test plan: Create a cell and multiple watchers. Make sure that values are received by all
    // watchers.
    val (cell, callback) = createCell("set value")
    val callback2 = new LoggingStreamCallback[String](callback)

    // Start both the watchers.
    val cancellable: Cancellable = cell.watch(callback)
    val cancellable2: Cancellable = cell.watch(callback2)
    assert(callback.numElements == 0, "no callbacks expected yet")
    assert(callback2.numElements == 0, "no callbacks expected yet")

    // Set the value and wait for both subscriber to receive the value.
    cell.setValue("Bye")
    callback.waitForPredicate(_ == "Bye", 0)
    callback2.waitForPredicate(_ == "Bye", 0)
    assert(cell.getLatestValueOpt.get == "Bye")
    assert(cell.getStatus == Status.OK)

    // Cancel both callbacks and ensure that the cell has no watchers.
    cancellable.cancel(Status.CANCELLED)
    cancellable2.cancel(Status.CANCELLED)
    // Verify that the watches were cancelled by starting watches for the same callbacks and
    // checking that no error is thrown.
    cell.watch(callback)
    cell.watch(callback2)
  }

  test("Concurrent writers") {
    // Test plan: verify that all watchers receive the values in a single order even in the presence
    // of concurrent writers, and that this order is also consistent with the order in which the
    // values are exposed by `getLatestValueOpt`.
    val numWatchers: Int = 8
    val sec = SequentialExecutionContext.createWithDedicatedPool(
      name = "Context-concurrent-writers",
      alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
    )
    val cell = new WatchValueCell[Int]
    val callbacks: Vector[LoggingStreamCallback[Int]] =
      Vector.fill(numWatchers)(new LoggingStreamCallback[Int](sec))
    for (callback: LoggingStreamCallback[Int] <- callbacks) {
      cell.watch(callback)
    }

    val numWriters: Int = 8
    val valuesPerWriter: Int = 25
    val numValues = numWriters * valuesPerWriter
    val startLatch = new CountDownLatch(1)
    for (writerIndex: Int <- 0 until numWriters) {
      val writer: Runnable = new Runnable {
        override def run(): Unit = {
          startLatch.await()
          for (valueIndex <- 0 until valuesPerWriter) {
            cell.setValue(writerIndex * valuesPerWriter + valueIndex)
          }
        }
      }
      val thread: Thread = new Thread(writer, s"watch-cell-writer-$writerIndex")
      thread.start()
    }

    // Start the tasks around the same time.
    startLatch.countDown()

    // Wait for each watcher to receive all values.
    for (callback: LoggingStreamCallback[Int] <- callbacks) {
      AssertionWaiter("Wait for all concurrent WatchCell values").await {
        assert(callback.numElements == numValues)
      }
    }

    // Verify that all watchers received the same values in the same order.
    val callbackValues: Vector[Vector[Int]] = callbacks.map {
      callback: LoggingStreamCallback[Int] =>
        callback.getLog.map { value: StatusOr[Int] =>
          assert(value.isOk)
          value.get
        }
    }
    val headObserved: Vector[Int] = callbackValues.head
    for (otherObserved: Vector[Int] <- callbackValues.tail) {
      assert(headObserved == otherObserved)
    }

    // Verify that the value in the cell is the last value received by all watchers. While this
    // doesn't guarantee that every value was published through `getLatestValueOpt` in an order
    // consistent with the callbacks, we would expect this assertion to flake if that weren't the
    // case.
    assert(cell.getLatestValueOpt.get == headObserved.last)
  }

  test("Set WatchCell error multiple subscribers") {
    // Test plan: Create a cell and multiple watchers. Make sure that errors are received by all
    // watchers.
    val (cell, callback) = createCell("set value")
    val callback2 = new LoggingStreamCallback[String](callback)

    // Start both the watchers.
    val cancellable: Cancellable = cell.watch(callback)
    val cancellable2: Cancellable = cell.watch(callback2)
    assert(callback.numElements == 0, "no callbacks expected yet")
    assert(callback2.numElements == 0, "no callbacks expected yet")

    // Set the error state on the cell and wait for both subscriber to receive the error.
    cell.setErrorStatus(Status.FAILED_PRECONDITION)
    callback.waitForStatus(Status.FAILED_PRECONDITION, 0)
    callback2.waitForStatus(Status.FAILED_PRECONDITION, 0)

    // Cancel both callbacks and ensure that the cell has no watchers.
    cancellable.cancel(Status.CANCELLED)
    cancellable2.cancel(Status.CANCELLED)
    // Verify that the watches were cancelled by starting watches for the same callbacks and
    // checking that no error is thrown.
    cell.watch(callback)
    cell.watch(callback2)
    assert(cell.getStatus == Status.FAILED_PRECONDITION)
  }

  test("Test WatchCell assert violations") {
    // Test plan: Provide the cell with bad values or values/errors when the cell is in the error
    // state. Ensure that the assertions are raised when the conditions are violated. Typically,
    // such tests are not written but we are adding them so that our assumptions of the erroneous
    // states are verified.

    val cell = new WatchCell[String]
    // Check for simple assertion errors on the main thread itself.
    assertThrow[IllegalArgumentException]("Value cannot be set to null") {
      cell.setValue(null)
    }
    assertThrow[IllegalArgumentException]("Cannot set status to be ok for error") {
      cell.setErrorStatus(Status.OK)
    }

    // Set an Error status and then set a value. Ensure that an assertion is raised.
    cell.setErrorStatus(Status.FAILED_PRECONDITION)
    assertThrow[IllegalStateException]("Setting value when an error already exists") {
      cell.setValue("Some value")
    }

    // Create another cell.
    val cell2 = new WatchCell[String]

    // Set an error and then another error. Ensure that an assertion is raised.
    cell2.setErrorStatus(Status.ALREADY_EXISTS)
    assertThrow[IllegalStateException]("Setting status when an error already exist") {
      cell2.setErrorStatus(Status.ABORTED)
    }

    // Watch using the same stream callback.
    val (cell3, callback3) = createCell("Same stream callback")
    cell3.watch(callback3)
    assertThrow[IllegalArgumentException]("Callback already present") {
      cell3.watch(callback3)
    }
  }

  test("Test WatchValueCell") {
    // Test plan: Create a WatchValueCell and do a simple watch. Then try to setError and check
    // that an exception is thrown.
    val sec = SequentialExecutionContext.createWithDedicatedPool(
      name = "Context-WatchValueCell",
      alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
    )
    val cell = new WatchValueCell[String]
    val latch = new CountDownLatch(1)
    val callback = new ValueStreamCallback[String](sec) {
      override protected def onSuccess(value: String): Unit = {
        latch.countDown()
      }
    }
    val cancellable: Cancellable = cell.watch(callback)

    // Set the value and wait.
    cell.setValue("Hello")
    latch.await()
    assert(cell.getLatestValueOpt.get == "Hello")

    // Check that the value cell returns OK for its status.
    assert(cell.getStatus.isOk)

    // Cancel the callback so that the cell has no callbacks registered.
    cancellable.cancel(Status.CANCELLED)

    // Verify that the watch for `callback` was cancelled by starting another watch for `callback`
    // and checking that no error is thrown.
    cell.watch(callback)
  }

  test("WatchCell notifyInitial()") {
    // Test plan: Verify that the Future returned by `notifyInitial()` completes correctly in
    // various conditions.

    // An executor to run callbacks for the initial value future synchronously.
    val directExecutionContext: ExecutionContext =
      ExecutionContext.fromExecutor((runnable: Runnable) => runnable.run())

    // `notifyInitial()` called before a value is set; `setValue` completes the Future successfully.
    {
      val watchCell = new WatchCell[String]
      val (future, _): (Future[Unit], Cancellable) = watchCell.notifyInitial()
      val stateObserved: Future[Unit] = future.map { _ =>
        assert(watchCell.getLatestValueOpt.contains("Hello"))
        assert(watchCell.getStatus == Status.OK)
        ()
      }(directExecutionContext)
      assert(!future.isCompleted, "notifyInitial Future should not complete before a value is set")
      watchCell.setValue("Hello")
      Await.result(stateObserved, Duration.Inf)
    }

    // `notifyInitial()` called before an error is set; `setErrorStatus` completes the Future
    // successfully.
    {
      val watchCell = new WatchCell[String]
      val (future, _): (Future[Unit], Cancellable) = watchCell.notifyInitial()
      val stateObserved: Future[Unit] = future.map { _ =>
        assert(watchCell.getLatestValueOpt.isEmpty)
        assert(watchCell.getStatus == Status.FAILED_PRECONDITION)
        ()
      }(directExecutionContext)
      assert(!future.isCompleted, "notifyInitial Future should not complete before an error is set")
      watchCell.setErrorStatus(Status.FAILED_PRECONDITION)
      Await.result(stateObserved, Duration.Inf)
    }

    // `notifyInitial`'s returned cancellable is called; the Future fails with the cancel reason as
    // a StatusRuntimeException.
    {
      val watchCell = new WatchCell[String]
      val (future, cancellable): (Future[Unit], Cancellable) = watchCell.notifyInitial()
      assert(!future.isCompleted, "Future should not complete before cancel")
      cancellable.cancel(Status.CANCELLED)
      assertThrow[StatusRuntimeException]("CANCELLED") {
        Await.result(future, Duration.Inf)
      }
    }

    // `notifyInitial` is called after a value is already set; returns an already-completed
    // successful Future.
    {
      val watchCell = new WatchCell[String]
      watchCell.setValue("Hello")
      val (future, _): (Future[Unit], Cancellable) = watchCell.notifyInitial()
      assert(future.isCompleted, "Future should be complete when a value is preset")
      Await.result(future, Duration.Inf)
    }

    // `ready` called after an error is already set returns an already-completed successful Future.
    {
      val watchCell = new WatchCell[String]
      watchCell.setErrorStatus(Status.FAILED_PRECONDITION)
      val (ready, _): (Future[Unit], Cancellable) = watchCell.notifyInitial()
      assert(ready.isCompleted, "ready Future should be complete when an error is preset")
      Await.result(ready, Duration.Inf)
    }
  }

  /**
   * Creates a [[WatchCell]] with the given name (for debugging) and new ExecutionContext. Returns
   * it along with a stream callback that can be used by a watcher for watching this cell.
   */
  private def createCell(name: String): (WatchCell[String], LoggingStreamCallback[String]) = {
    val sec = SequentialExecutionContext.createWithDedicatedPool(
      name = "Context-" + name,
      alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
    )
    val cell = new WatchCell[String]
    val callback = new LoggingStreamCallback[String](sec)
    (cell, callback)
  }
}

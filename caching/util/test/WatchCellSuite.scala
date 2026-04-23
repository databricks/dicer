package com.databricks.caching.util

import java.util.concurrent.CountDownLatch

import io.grpc.Status

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
    assertThrow[AssertionError]("Value cannot be set to null") {
      cell.setValue(null)
    }
    assertThrow[AssertionError]("Cannot set status to be ok for error") {
      cell.setErrorStatus(Status.OK)
    }

    // Set an Error status and then set a value. Ensure that an assertion is raised.
    cell.setErrorStatus(Status.FAILED_PRECONDITION)
    assertThrow[AssertionError]("Setting value when an error already exists") {
      cell.setValue("Some value")
    }

    // Create another cell.
    val cell2 = new WatchCell[String]

    // Set an error and then another error. Ensure that an assertion is raised.
    cell2.setErrorStatus(Status.ALREADY_EXISTS)
    assertThrow[AssertionError]("Setting status when an error already exist") {
      cell2.setErrorStatus(Status.ABORTED)
    }

    // Watch using the same stream callback.
    val (cell3, callback3) = createCell("Same stream callback")
    cell3.watch(callback3)
    assertThrow[AssertionError]("Callback already present") {
      cell3.watch(callback3)
    }
  }

  test("Test WatchValueCell") {
    // Test plan: Create a WatchValueCell and do a simple watch. Then try to setError and check
    // that an exception is thrown.
    val sec = SequentialExecutionContext.createWithDedicatedPool("Context-WatchValueCell")
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

  /**
   * Creates a [[WatchCell]] with the given name (for debugging) and new ExecutionContext. Returns
   * it along with a stream callback that can be used by a watcher for watching this cell.
   */
  private def createCell(name: String): (WatchCell[String], LoggingStreamCallback[String]) = {
    val sec = SequentialExecutionContext.createWithDedicatedPool("Context-" + name)
    val cell = new WatchCell[String]
    val callback = new LoggingStreamCallback[String](sec)
    (cell, callback)
  }
}

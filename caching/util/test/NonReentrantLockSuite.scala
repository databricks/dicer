package com.databricks.caching.util

import java.util.concurrent.TimeUnit

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.Random

import com.databricks.caching.util.Lock.withLock
import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.testing.DatabricksTest

/**
 * Test suite for [[NonReentrantLock]]. Since we largely rely on
 * [[java.util.concurrent.locks.ReentrantLock]] for the actual locking, this is not an extremely
 * comprehensive test of all the functionality of the lock, instead getting coverage for all methods
 * and verifying that attempting to acquire the lock reentrantly fails.
 */
private class ParameterizedNonReentrantLockSuite(useFairUnderlyingLock: Boolean = false)
    extends DatabricksTest
    with TestUtils.ParameterizedTestNameDecorator {

  override def paramsForDebug: Map[String, Any] = Map(
    "useFairUnderlyingLock" -> useFairUnderlyingLock
  )

  test("Basic lock and unlock") {
    // Test plan: Verify that lock and unlock from a single thread work.
    val lock = new NonReentrantLock(useFairUnderlyingLock)
    lock.lock()
    assert(lock.isHeldByCurrentThread)
    lock.unlock()
    assert(!lock.isHeldByCurrentThread)
  }

  test("Reentrant locking fails") {
    // Test plan: Verify that a thread cannot acquire or try to acquire a lock it already holds.
    val lock = new NonReentrantLock(useFairUnderlyingLock)
    lock.lock()
    assertThrow[IllegalMonitorStateException]("attempting to reacquire lock") {
      lock.lock()
    }
    assertThrow[IllegalMonitorStateException]("attempting to reacquire lock") {
      lock.tryLock()
    }
    assertThrow[IllegalMonitorStateException]("attempting to reacquire lock") {
      lock.tryLock(0, TimeUnit.MILLISECONDS)
    }
    assertThrow[IllegalMonitorStateException]("attempting to reacquire lock") {
      lock.lockInterruptibly()
    }
    lock.unlock()
  }

  test("Unlock without lock") {
    // Test plan: Verify that unlocking a lock that isn't held throws an exception.
    val lock = new NonReentrantLock(useFairUnderlyingLock)
    assertThrows[IllegalMonitorStateException] {
      lock.unlock()
    }
  }

  test("tryLock when free") {
    // Test plan: Verify `tryLock`, with and without timeout, succeeds when lock is available.
    val lock = new NonReentrantLock(useFairUnderlyingLock)
    assert(lock.tryLock())
    assert(lock.isHeldByCurrentThread)
    lock.unlock()

    assert(lock.tryLock(0, TimeUnit.MILLISECONDS))
    assert(lock.isHeldByCurrentThread)
    lock.unlock()
  }

  test("lockInterruptibly when free") {
    // Test plan: Verify `lockInterruptibly` succeeds when lock is available.
    val lock = new NonReentrantLock(useFairUnderlyingLock)
    lock.lockInterruptibly()
    assert(lock.isHeldByCurrentThread)
    lock.unlock()
  }

  test("Condition variables") {
    // Test plan: Verify that `newCondition` works as expected. Do this by creating a thread and
    // using the condition variable to coordinate between it and the main thread. We first detect
    // that the new thread is started, and then signal it to exit.
    val lock = new NonReentrantLock(useFairUnderlyingLock)
    val condition = lock.newCondition()
    // `threadStarted` and `shouldExitThread` are protected by `lock`: every read/write below
    // happens inside `withLock(lock)`, which establishes the happens-before edge between the
    // worker thread and the main thread. Do not access these vars outside the lock.
    var threadStarted = false
    var shouldExitThread = false

    val thread = new Thread(() => {
      withLock(lock) {
        threadStarted = true
        // Wake up main thread if waiting on `threadStarted`.
        condition.signal()
        // Loop on `await()` to guard against spurious wakeups: only exit when `shouldExitThread`
        // has actually been set.
        while (!shouldExitThread) {
          condition.await()
        }
      }
    })
    thread.start()

    withLock(lock) {
      // Loop on `await()` to guard against spurious wakeups, as above.
      while (!threadStarted) {
        condition.await()
      }
      shouldExitThread = true
      // Wake up the thread to exit it.
      condition.signal()
    }

    thread.join()
  }

  test("Reentrant locking from multiple threads") {
    // Test plan: Verify that the reentrancy check still rejects reentrant acquisitions when the
    // underlying lock is contended by multiple threads. Create multiple threads that
    // lock/unlock with some small delay in between to produce contention; with some probability
    // each thread attempts to reentrantly acquire the lock it already holds and we assert that
    // this fails (each reentrant attempt is from the holding thread, just as in the
    // single-threaded "Reentrant locking fails" test).
    val lock = new NonReentrantLock(useFairUnderlyingLock)
    val numThreads = 4
    val numIterations = 100

    val executionContext =
      ExecutorUtil.createContextPropagatingExecutionContext("Reentrant-Test", numThreads)
    // Use a Future so that any potential exceptions get propagated when we do `Await.result`.
    // Create one future per thread that runs `numIterations`.
    val futures = (0 until numThreads).map(_ => {
      Future {
        for (_ <- 0 until numIterations) {
          lock.lock()
          // With 10% chance, try to reentrantly acquire the lock and verify it fails.
          if (Random.nextDouble() < 0.1) {
            assertThrow[IllegalMonitorStateException]("attempting to reacquire lock") {
              lock.lock()
            }
          }
          lock.unlock()
          // Try to get more interleaving.
          Thread.sleep(1)
        }
      }(executionContext)
    })

    for (future <- futures) {
      Await.result(future, Duration.Inf)
    }
  }
}

private class NonReentrantLockSuite extends ParameterizedNonReentrantLockSuite

private class NonReentrantLockFairSuite
    extends ParameterizedNonReentrantLockSuite(useFairUnderlyingLock = true)

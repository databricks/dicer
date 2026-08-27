package com.databricks.caching.util

import com.databricks.testing.DatabricksTest
import org.scalatest.Suite

import java.util.concurrent.CountDownLatch
import scala.collection.{immutable, mutable}
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{Await, Future, Promise}
import scala.util.Random

class HybridConcurrencyDomainSuite extends DatabricksTest {

  override def nestedSuites: immutable.IndexedSeq[Suite] = {
    Vector(
      new BaseHybridConcurrencyDomainSuite(enableContextPropagation = true),
      new BaseHybridConcurrencyDomainSuite(enableContextPropagation = false)
    )
  }
}

class BaseHybridConcurrencyDomainSuite(enableContextPropagation: Boolean)
    extends DatabricksTest
    with ServerTestUtils.AttributionContextPropagationTester
    with TestUtils.TestName {

  private val pool =
    SequentialExecutionContextPool.create(
      poolName = s"test-pool",
      numThreads = 2,
      alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME,
      enableContextPropagation = enableContextPropagation
    )

  /**
   * Creates and returns a new [[Thread]] which executes `thunk`.
   */
  private def spawnThread(thunk: => Unit): Thread = {
    val thread = new Thread() {
      override def run(): Unit = {
        thunk
      }
    }
    thread.start()
    thread
  }

  test("concurrency test - serializes sync and async tasks") {
    // Test plan: verify that both sync and async tasks executed in the concurrency domain are
    // serialized with other tasks.
    val domain = HybridConcurrencyDomain.create(
      name = getSafeName,
      alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME,
      enableContextPropagation = enableContextPropagation
    )

    // Simulate concurrent callers that each attempt to increment a shared counter and verify that
    // the count is equal to the total number of callers. This is likely to flake if there's a
    // concurrency control bug.
    var counter: Int = 0

    def nonAtomicIncrement(): Unit = {
      // Exaggerate the non-atomicity of the increment by explicitly sleeping between the
      // read and write of the shared counter. Otherwise, this test is likely to spuriously
      // pass even with incorrect concurrency control because the read and write are so
      // fast.
      val curr: Int = counter
      Thread.sleep(1)
      counter = curr + 1
    }

    val rng: Random = TestUtils.newRandomWithLoggedSeed()
    val joins: mutable.ArrayBuffer[() => Unit] = mutable.ArrayBuffer.empty

    for (_ <- 0 until 1000) {
      val choice: Int = rng.nextInt(3)
      val join: () => Unit = if (choice == 0) {
        val thread: Thread = spawnThread {
          Await.result(
            Pipeline {
              domain.assertInDomain()
              nonAtomicIncrement()
            }(domain).toFuture,
            Duration.Inf
          )
        }
        () => thread.join()
      } else if (choice == 1) {
        val thread: Thread = spawnThread {
          domain.executeSync {
            domain.assertInDomain()
            nonAtomicIncrement()
          }
        }
        () => thread.join()
      } else {
        val latch: CountDownLatch = new CountDownLatch(1)
        domain.schedule("increment", 2.millis, () => {
          domain.assertInDomain()
          nonAtomicIncrement()
          latch.countDown()
        })
        () => latch.await()
      }
      joins += join
    }

    for (join: (() => Unit) <- joins) {
      join()
    }

    assert(counter == 1000)
  }

  test("scheduling delay") {
    // Test plan: verify that a scheduled task executes after the expected delay elapses.
    val fakeClock = new FakeTypedClock()
    val fakeSec =
      FakeSequentialExecutionContext.create("fake-sec", Some(fakeClock), pool)
    val domain =
      HybridConcurrencyDomain.forTest.create(getSafeName, fakeSec, enableContextPropagation)

    // Use a delay so long that it could not elapse before the test would time out if we were
    // erroneously using the system clock.
    val delay = 10.minutes
    val latch = new CountDownLatch(1)

    domain.schedule("delayed-task", delay, () => latch.countDown())
    fakeClock.advanceBy(delay)
    latch.await()
  }

  test("cancel scheduled task") {
    // Test plan: verify that a scheduled task which is successfully cancelled does not run. We
    // use a fake clock to prevent the passage of time while we cancel the task, guaranteeing that
    // the cancel runs before the task is executed.
    val fakeClock = new FakeTypedClock()
    val fakeSec =
      FakeSequentialExecutionContext.create("fake-sec", Some(fakeClock), pool)
    val domain =
      HybridConcurrencyDomain.forTest.create(getSafeName, fakeSec, enableContextPropagation)
    val latch = new CountDownLatch(1)
    val token: Cancellable = domain.schedule("delayed-task", 100.millis, () => latch.countDown())
    token.cancel()
    fakeClock.advanceBy(1.second)
    TestUtils.shamefullyAwait200msForNonEventInAsyncTest()
    assert(latch.getCount == 1)
  }

  test("async tasks can still be scheduled when task is active") {
    // Test plan: verify that requests to schedule an async task does not block even when there's a
    // task actively running in the domain. This behavior is fairly obvious from the current
    // implementation which delegates to an internal SES, as that has its own internal task queue
    // lock, but is important to verify nonetheless in case the implementation evolves to manage its
    // own task queue.
    val domain = HybridConcurrencyDomain.create(
      name = getSafeName,
      alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME,
      enableContextPropagation = enableContextPropagation
    )

    // Put a blocking task in the domain.
    val latch = new CountDownLatch(1)
    val thread: Thread = spawnThread {
      domain.executeSync {
        latch.await()
      }
    }

    // This schedules a task in `domain` and should not block, even though the domain is busy.
    val asyncResult: Future[Int] = Pipeline {
      domain.assertInDomain()
      42
    }(domain).toFuture
    assert(thread.isAlive)

    // Scheduling a delayed task should also not block.
    domain.schedule("delayed-task", 2.millis, () => ())

    latch.countDown()
    assert(Await.result(asyncResult, Duration.Inf) == 42)
  }

}

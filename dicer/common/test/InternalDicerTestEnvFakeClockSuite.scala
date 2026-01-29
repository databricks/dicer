package com.databricks.dicer.common

import com.databricks.caching.util.TestUtils
import com.databricks.caching.util.TestUtils.TestName
import com.databricks.caching.util.{
  AssertionWaiter,
  FakeSequentialExecutionContextPool,
  FakeTypedClock
}
import com.databricks.conf.Configs
import com.databricks.dicer.assigner.conf.DicerAssignerConf
import com.databricks.dicer.external.{Clerk, ResourceAddress, Slicelet, Target}
import com.databricks.dicer.friend.Squid
import com.databricks.testing.DatabricksTest

import scala.concurrent.duration.Duration
import scala.concurrent.duration._

class InternalDicerTestEnvFakeClockSuite extends DatabricksTest with TestName {

  private val fakeClock = new FakeTypedClock

  private val THREAD_NUM = 10

  /** The fake sequential execution context pool with the given fake clock */
  private val fakeSecPool =
    FakeSequentialExecutionContextPool.create("FakePool", THREAD_NUM, fakeClock)

  /** The test environment used in the suite. */
  private val testEnv =
    InternalDicerTestEnvironment.create(allowEtcdMode = true, secPool = fakeSecPool)

  override def beforeEach(): Unit = {
    testEnv.clear()
  }

  override def afterAll(): Unit = {
    testEnv.stop()
  }

  test("Assignment generation") {
    // Test plan: Ensure that assignments are generated when the test environment uses a fake
    // clock. First, start a single assigner, two Slicelets and two Clerks. Verify that:
    //  - Both Slicelets and both Clerks get the assignment.
    //  - The assignment generation matches the time of the fake clock.
    val storeIncarnation = Incarnation(42)

    val etcdConf: DicerAssignerConf = new DicerAssignerConf(
      Configs.parseMap(
        "databricks.dicer.assigner.store.type" -> "etcd",
        "databricks.dicer.assigner.storeIncarnation" -> storeIncarnation.value
      )
    )

    testEnv.addAssigner(TestAssigner.Config.create(etcdConf))

    val target = Target(getSafeName)
    val slicelet0: Slicelet =
      testEnv.createSlicelet(target).start(selfPort = 1234, listenerOpt = None)
    val slicelet1: Slicelet =
      testEnv.createSlicelet(target).start(selfPort = 12345, listenerOpt = None)
    val pod0: Squid = slicelet0.impl.squid
    val pod1: Squid = slicelet1.impl.squid

    fakeClock.advanceBy(10.seconds)

    AssertionWaiter("TestAssigner").await {
      val assignmentOpt: Option[Assignment] =
        TestUtils.awaitResult(testEnv.testAssigner.getAssignment(target), Duration.Inf)
      assert(assignmentOpt.isDefined)
      val assignment: Assignment = assignmentOpt.get
      val assignedResources: Set[Squid] = assignment.assignedResources
      assert(
        assignedResources.contains(pod0) && assignedResources.contains(pod1)
      )
    }

    // Make sure that the Slicelets have the assignment as well.
    AssertionWaiter("Slicelet0").await {
      assert(slicelet0.impl.forTest.getLatestAssignmentOpt.isDefined)
    }
    AssertionWaiter("Slicelet1").await {
      assert(slicelet1.impl.forTest.getLatestAssignmentOpt.isDefined)
    }

    // Now start a Clerk for slicelet0, and another Clerk that directly connects to the Assigner.
    // Verify that they both get the assignment.
    val clerk1: Clerk[ResourceAddress] = testEnv.createClerk(slicelet0)
    TestUtils.awaitResult(clerk1.ready, Duration.Inf)
    assert(clerk1.impl.forTest.getLatestAssignmentOpt.isDefined)

    val clerk2: Clerk[ResourceAddress] = testEnv.createDirectClerk(target, initialAssignerIndex = 0)
    TestUtils.awaitResult(clerk2.ready, Duration.Inf)
    assert(clerk2.impl.forTest.getLatestAssignmentOpt.isDefined)

    // Verify that the generation of the assignment matches the time of the fake clock.
    // The expected generation number is the instant of the fake clock plus one, because it is
    // how the store computes the initial generation number. See `Generation.createForCurrentTime`.
    val expectedGeneration = Generation(
      incarnation = storeIncarnation,
      number = fakeClock.instant().toEpochMilli + 1
    )

    assert(slicelet0.impl.forTest.getLatestAssignmentOpt.get.generation == expectedGeneration)
    assert(slicelet1.impl.forTest.getLatestAssignmentOpt.get.generation == expectedGeneration)
    assert(clerk1.impl.forTest.getLatestAssignmentOpt.get.generation == expectedGeneration)
    assert(clerk2.impl.forTest.getLatestAssignmentOpt.get.generation == expectedGeneration)

    // Cleanup: stop the Slicelets and Clerks.
    slicelet0.forTest.stop()
    slicelet1.forTest.stop()
    clerk1.forTest.stop()
    clerk2.forTest.stop()
  }

}

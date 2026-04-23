package com.databricks.dicer.assigner

import java.nio.charset.StandardCharsets.UTF_8

import scala.concurrent.duration._

import com.databricks.caching.util.AssertionWaiter
import com.databricks.caching.util.TestUtils
import com.databricks.caching.util.TestUtils.TestName
import com.databricks.conf.Config
import com.databricks.conf.Configs
import com.databricks.dicer.assigner.conf.DicerAssignerConf
import com.databricks.dicer.common.{
  Assignment,
  Generation,
  InternalDicerTestEnvironment,
  TestAssigner
}
import com.databricks.dicer.external.{Slicelet, Target}
import com.databricks.caching.util.{EtcdTestEnvironment, EtcdClient, EtcdKeyValueMapper}
import com.databricks.caching.util.UnixTimeVersion
import com.databricks.dicer.friend.{SliceletAccessor, Squid}
import com.databricks.testing.DatabricksTest

/**
 * The suite testing the behavior of dicer assigner using an etcd store, focusing on the stability
 * of the system when facing data corruption or loss.
 */
class EtcdAssignerSuite extends DatabricksTest with TestName {

  /** Configuration that connects the test dicer assigner to the dockerized etcd. */
  private[this] val assignerConfig: Config = Configs.parseMap(
    "databricks.dicer.assigner.store.type" -> "etcd",
    "databricks.dicer.assigner.preferredAssigner.etcd.sslEnabled" -> false,
    "databricks.dicer.assigner.storeIncarnation" -> 2
  )

  private[this] val testEnv =
    InternalDicerTestEnvironment.create(
      config = TestAssigner.Config.create(new DicerAssignerConf(assignerConfig)),
      allowEtcdMode = true
    )

  private[this] val dockerizedEtcd: EtcdTestEnvironment = testEnv.dockerizedEtcdOpt.get

  /**
   * Waits for and returns an assignment for `target` that contains the resource of `slicelet` and
   * has higher generation than `highestKnownGeneration`.
   */
  private[this] def awaitAssignmentFor(
      target: Target,
      slicelet: Slicelet,
      highestKnownGeneration: Generation): Assignment = {
    AssertionWaiter("wait for new assignments").await {
      val assignmentOpt: Option[Assignment] =
        TestUtils.awaitResult(testEnv.testAssigner.getAssignment(target), Duration.Inf)
      assert(assignmentOpt.isDefined)
      val assignment: Assignment = assignmentOpt.get
      assert(
        assignment.assignedResources
          .exists((_: Squid).resourceAddress == SliceletAccessor.resourceAddress(slicelet))
      )
      assert(highestKnownGeneration < assignment.generation)
      assignment
    }
  }

  /**
   * Assert that there is no new assignment for `target` whose generation is higher than
   * `highestKnownGeneration` generated within `duration` time after this function is called.
   *
   * It's impossible to assert in finite time that something _never_ happens, so waiting a fixed
   * amount of time is best-effort in nature.
   */
  private[this] def assertNoNewAssignmentFor(
      target: Target,
      highestKnownGeneration: Generation,
      duration: FiniteDuration = 1.second): Unit = {
    Thread.sleep(duration.toMillis)
    val latestAssignment: Option[Assignment] =
      TestUtils.awaitResult(testEnv.testAssigner.getAssignment(target), Duration.Inf)
    assert(latestAssignment.exists { assignment: Assignment =>
      assignment.generation == highestKnownGeneration
    })
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    testEnv.clear()
  }

  override def afterAll(): Unit = {
    testEnv.stop()
    super.afterAll()
  }

  test("etcd assigner returns correct assignment when etcd has no data loss") {
    // Test plan: Verify the etcd assigner works correctly when there is no data loss or corruption.
    // Verify this by scaling a target several times, with no bad data injected to etcd, then
    // checking the assignments are successfully generated and distributed.

    testEnv.addAssigner(TestAssigner.Config.create(new DicerAssignerConf(assignerConfig)))

    val target = Target(getSafeName)
    var highestGeneration = Generation.EMPTY

    val slicelet1: Slicelet =
      testEnv.createSlicelet(target).start(selfPort = 1234, listenerOpt = None)
    highestGeneration = awaitAssignmentFor(target, slicelet1, highestGeneration).generation

    val slicelet2: Slicelet =
      testEnv.createSlicelet(target).start(selfPort = 1234, listenerOpt = None)
    highestGeneration = awaitAssignmentFor(target, slicelet2, highestGeneration).generation

    val slicelet3: Slicelet =
      testEnv.createSlicelet(target).start(selfPort = 1234, listenerOpt = None)
    highestGeneration = awaitAssignmentFor(target, slicelet3, highestGeneration).generation
  }

  test("deleted assignment in etcd does not crash assigner") {
    // Test plan: Verify that deleted assignment in etcd will not crash the assigner. Verify this by
    // triggering some initial assignments for target 1 and target 2, then deleting the data for
    // target 1 in etcd. The following assignment generation for target 2 should not be affected.

    testEnv.addAssigner(TestAssigner.Config.create(new DicerAssignerConf(assignerConfig)))

    val target1 = Target(s"$getSafeName-1")
    val target2 = Target(s"$getSafeName-2")
    var highestGenerationForTarget1 = Generation.EMPTY
    var highestGenerationForTarget2 = Generation.EMPTY

    // Generate some assignment for both target 1 and target 2.
    val slicelet1ForTarget1: Slicelet =
      testEnv.createSlicelet(target1).start(selfPort = 1234, listenerOpt = None)
    highestGenerationForTarget1 =
      awaitAssignmentFor(target1, slicelet1ForTarget1, highestGenerationForTarget1).generation

    val slicelet1ForTarget2: Slicelet =
      testEnv.createSlicelet(target2).start(selfPort = 1234, listenerOpt = None)
    highestGenerationForTarget2 =
      awaitAssignmentFor(target2, slicelet1ForTarget2, highestGenerationForTarget2).generation

    // Explicitly delete the data for target 1.
    dockerizedEtcd.deleteAllWithPrefix(
      s"assignments/${target1.toParseableDescription}".getBytes(UTF_8)
    )
    // Verify the deleted data caused new assignment generation fails for target 1.
    testEnv.createSlicelet(target1).start(selfPort = 1234, listenerOpt = None)
    assertNoNewAssignmentFor(target1, highestGenerationForTarget1)

    // The following assignment generation for target 2 should not be affected.
    val _: Slicelet =
      testEnv.createSlicelet(target2).start(selfPort = 1234, listenerOpt = None)
    highestGenerationForTarget2 =
      awaitAssignmentFor(target2, slicelet1ForTarget2, highestGenerationForTarget2).generation
  }

  test("corrupted data in etcd does not crash assigner") {
    // Test plan: Verify that the corrupted data in etcd will not crash the assigner. Verify this by
    // triggering some assignments for target 1 and target 2, then corrupt the data in etcd for
    // target 1. The following assignment generation for target 2 should not be affected.

    testEnv.addAssigner(TestAssigner.Config.create(new DicerAssignerConf(assignerConfig)))

    val target1 = Target(s"$getSafeName-1")
    val target2 = Target(s"$getSafeName-2")
    var highestGenerationForTarget1 = Generation.EMPTY
    var highestGenerationForTarget2 = Generation.EMPTY

    // Generate some assignment for both target 1 and target 2.
    val slicelet1ForTarget1: Slicelet =
      testEnv.createSlicelet(target1).start(selfPort = 1234, listenerOpt = None)
    highestGenerationForTarget1 =
      awaitAssignmentFor(target1, slicelet1ForTarget1, highestGenerationForTarget1).generation

    val slicelet1ForTarget2: Slicelet =
      testEnv.createSlicelet(target2).start(selfPort = 1234, listenerOpt = None)
    highestGenerationForTarget2 =
      awaitAssignmentFor(target2, slicelet1ForTarget2, highestGenerationForTarget2).generation

    // Explicitly corrupt the data for target 1.
    for (tuple <- dockerizedEtcd.getPrefix(s"assignments/${target1.toParseableDescription}")) {
      val (key, _): (String, _) = tuple
      dockerizedEtcd.put(key, "data_corrupted")
    }
    // Verify new assignment generation fails for target 1.
    testEnv.createSlicelet(target1).start(selfPort = 1234, listenerOpt = None)
    assertNoNewAssignmentFor(target1, highestGenerationForTarget1)

    // The following assignment generation for target 2 should not be affected.
    val _: Slicelet =
      testEnv.createSlicelet(target2).start(selfPort = 1234, listenerOpt = None)
    highestGenerationForTarget2 =
      awaitAssignmentFor(target2, slicelet1ForTarget2, highestGenerationForTarget2).generation
  }

  test("corrupted loose incarnation in etcd does not crash assigner") {
    // Test plan: Verify that the versions corresponding to loose incarnation in etcd will not crash
    // the assigner. Verify this by triggering some assignments for target 1 and target 2, then
    // corrupt the data in etcd for target 1 with some loose incarnation. The following assignment
    // generation for target 2 should not be affected.
    val conf = new DicerAssignerConf(assignerConfig)
    testEnv.addAssigner(TestAssigner.Config.create(conf))

    val target1 = Target(getSuffixedSafeName("1"))
    val target2 = Target(getSuffixedSafeName("2"))
    var highestGenerationForTarget1 = Generation.EMPTY
    var highestGenerationForTarget2 = Generation.EMPTY

    // Generate some assignment for both target 1 and target 2.
    val slicelet1ForTarget1: Slicelet =
      testEnv.createSlicelet(target1).start(selfPort = 1234, listenerOpt = None)
    highestGenerationForTarget1 =
      awaitAssignmentFor(target1, slicelet1ForTarget1, highestGenerationForTarget1).generation

    val slicelet1ForTarget2: Slicelet =
      testEnv.createSlicelet(target2).start(selfPort = 1234, listenerOpt = None)
    highestGenerationForTarget2 =
      awaitAssignmentFor(target2, slicelet1ForTarget2, highestGenerationForTarget2).generation

    // Write a loose incarnation version into etcd for target1, and further write corrupt data
    // for that version.
    val looseIncarnationVersion = EtcdClient.Version(highBits = 3, lowBits = UnixTimeVersion.MIN)
    dockerizedEtcd.put(
      EtcdKeyValueMapper.ForTest
        .toVersionKeyString(
          Assigner.getAssignmentsEtcdNamespace(conf),
          target1.toParseableDescription
        ),
      EtcdKeyValueMapper.ForTest.toVersionValueString(looseIncarnationVersion)
    )
    dockerizedEtcd.put(
      EtcdKeyValueMapper.ForTest.toVersionedKeyString(
        Assigner.getAssignmentsEtcdNamespace(conf),
        target1.toParseableDescription,
        looseIncarnationVersion
      ),
      "corrupted data"
    )
    // Verify new assignment generation fails for target 1.
    testEnv.createSlicelet(target1).start(selfPort = 1234, listenerOpt = None)
    assertNoNewAssignmentFor(target1, highestGenerationForTarget1)

    // The following assignment generation for target 2 should not be affected.
    val _: Slicelet =
      testEnv.createSlicelet(target2).start(selfPort = 1234, listenerOpt = None)
    highestGenerationForTarget2 =
      awaitAssignmentFor(target2, slicelet1ForTarget2, highestGenerationForTarget2).generation
  }

  test("Assignments are written to the expected etcd namespace") {
    // Test plan: verify that the Assigner writes assignments to the expected etcd namespace.

    testEnv.addAssigner(TestAssigner.Config.create(new DicerAssignerConf(assignerConfig)))

    val target: Target = Target(getSafeName)

    // Connecting a Slicelet should prompt assignment generation
    testEnv.createSlicelet(target).start(selfPort = 1234, listenerOpt = None)

    AssertionWaiter("Wait for data in etcd").await {
      assert(
        dockerizedEtcd.getPrefix(s"assignments/${target.toParseableDescription}").nonEmpty
      )
    }
  }

  private val bumpStoreIncarnationParams: Seq[(Config, Config)] = Seq(
    (
      Configs.parseMap(
        "databricks.dicer.assigner.store.type" -> "etcd",
        "databricks.dicer.assigner.preferredAssigner.etcd.sslEnabled" -> false,
        "databricks.dicer.assigner.storeIncarnation" -> 2
      ),
      Configs.parseMap(
        "databricks.dicer.assigner.store.type" -> "in_memory",
        "databricks.dicer.assigner.preferredAssigner.etcd.sslEnabled" -> false,
        "databricks.dicer.assigner.storeIncarnation" -> 3
      )
    ),
    (
      Configs.parseMap(
        "databricks.dicer.assigner.store.type" -> "in_memory",
        "databricks.dicer.assigner.preferredAssigner.etcd.sslEnabled" -> false,
        "databricks.dicer.assigner.storeIncarnation" -> 3
      ),
      Configs.parseMap(
        "databricks.dicer.assigner.store.type" -> "etcd",
        "databricks.dicer.assigner.preferredAssigner.etcd.sslEnabled" -> false,
        "databricks.dicer.assigner.storeIncarnation" -> 4
      )
    )
  )

  gridTest("bump store incarnation and assert slicelets observe it")(bumpStoreIncarnationParams) {
    case (oldAssignerConfig: Config, newAssignerConfig: Config) =>
      // Test plan: Verify an assigner with a higher store incarnation generates and distributes
      // assignments even in the presence of a lower incarnation assignment. Verifying this by
      // create an assigner with etcd store and a non-loose incarnation, then bump the incarnation
      // for it and change it to in-memory store, verifying the slicelet observe assignment from
      // higher store incarnation. Also test this in reversed order.

      val oldConf = TestAssigner.Config.create(new DicerAssignerConf(oldAssignerConfig))
      testEnv.addAssigner(oldConf)

      val target = Target(getSafeName)
      val slicelet: Slicelet = testEnv.createSlicelet(target).start(1234, None)

      // The slicelet should observe the assignments from the old store incarnation.
      AssertionWaiter("wait for assignment from old incarnation").await {
        assert(
          slicelet.impl.forTest.getLatestAssignmentOpt.get.generation.incarnation ==
          oldConf.assignerConf.storeIncarnation
        )
        assert(
          slicelet.impl.forTest.getLatestAssignmentOpt.get.assignedResources == Set(
            slicelet.impl.squid
          )
        )
      }

      // A test assigner config with new store incarnation and type, while having the same assigner
      // port as the old assigner, so that the slicelet can still talk to the new assigner.
      val newConfigWithSamePort = TestAssigner.Config.create(
        new DicerAssignerConf(newAssignerConfig),
        designatedDicerAssignerRpcPort = Some(testEnv.testAssigner.localUri.getPort)
      )

      testEnv.restartAssigner(
        index = 0,
        newConfigWithSamePort
      )

      // The slicelet should observe the assignments from the new store incarnation.
      AssertionWaiter("wait for assignment from new incarnation").await {
        assert(
          slicelet.impl.forTest.getLatestAssignmentOpt.get.generation.incarnation ==
          newConfigWithSamePort.assignerConf.storeIncarnation
        )
        assert(
          slicelet.impl.forTest.getLatestAssignmentOpt.get.assignedResources == Set(
            slicelet.impl.squid
          )
        )
      }
  }
}

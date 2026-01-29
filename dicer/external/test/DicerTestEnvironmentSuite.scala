package com.databricks.dicer.external

import scala.concurrent.duration.Duration
import scala.collection.mutable
import com.google.common.collect.ImmutableRangeSet
import com.google.common.hash.Hashing
import com.databricks.backend.common.util.Project
import com.databricks.caching.util.{AssertionWaiter, TestUtils}
import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.conf.Configs
import com.databricks.dicer.client.{TestClientUtils, TlsFilePaths}
import com.databricks.dicer.common.TestSliceUtils
import com.databricks.dicer.external.DicerTestEnvironment.AssignmentHandle
import com.databricks.dicer.external.Samples.{SampleClientConf, SampleServer, SampleServerConf}
import com.databricks.testing.DatabricksTest


import scala.util.Using
import com.databricks.caching.util.TestUtils

/** Variation of the test that directly instantiates a [[DicerTestEnvironment]]. */
class DicerTestEnvironmentSuite extends BaseDicerTestEnvironmentSuite {

  override protected val env: DicerTestEnvironment = DicerTestEnvironment.create()

  override def afterAll(): Unit = env.stop()
}

abstract class BaseDicerTestEnvironmentSuite extends DatabricksTest with TestUtils.TestName {
  // For the Dicer team - this code must not use any internal Dicer code or utilities so that
  // customers can see how to use Dicer using only Dicer's public interfaces.

  /** The test environment used for all tests. */
  protected val env: DicerTestEnvironment

  /** Creates a server to be sharded. */
  private def createSampleServer(target: Target, selfPort: Int): SampleServer = {
    // Build a SampleServerConf conf with test overrides.
    //  1. Inject connection config for Slicelets to be able to talk to Dicer.
    //  2. Overrides sslArgs to start the server in SampleServer.
    val confForTesting = new SampleServerConf(
      Project.TestProject,
      // Inject connection config for Slicelets to be able to talk to Dicer.
      Configs.empty.withFallback(env.getConnectionConfigForNewSlicelet)
    )
    new SampleServer(confForTesting, target).start(selfPort)
  }

  /** Creates a client to access `slicelet` for the given `target`. */
  private def createClerk(target: Target, slicelet: Slicelet): Clerk[ResourceAddress] = {
    // Build a SampleClientConf conf with test overrides.
    //  1. Inject connection config for Clerk to be able to talk to Dicer.
    //  2. Overrides sslArgs so the Clerk can talk to `slicelet`.
    val confForTesting = new SampleClientConf(
      Project.TestProject,
      Configs.empty.withFallback(env.getConnectionConfigForNewClerk(slicelet))
    )
    Clerk.create(
      confForTesting,
      target,
      sliceletHostName = "localhost",
      resourceAddress => resourceAddress
    )
  }

  /** [[SliceKeyFunction]] using FarmHash Fingerprint64. */
  private object FarmHashFingerprint64 extends SliceKeyFunction {
    override def apply(applicationKey: Array[Byte]): Array[Byte] = {
      Hashing.farmHashFingerprint64().hashBytes(applicationKey).asBytes
    }
  }

  /** Given a string `key`, returns the corresponding SliceKey using FarmHash Fingerprint64. */
  private def makeKey(key: String): SliceKey = SliceKey(key, FarmHashFingerprint64)

  test("With Slicelets and Clerk") {
    // Test Plan: Create a couple of Slicelets and a Clerk. Make sure that they all get the
    // assignments from Dicer.
    val target = Target(getSafeName)
    val server0 = createSampleServer(target, selfPort = 0)
    val server1 = createSampleServer(target, selfPort = 1)
    val pod0 = server0.getSlicelet.forTest.resourceAddress
    val pod1 = server1.getSlicelet.forTest.resourceAddress
    val clerk = createClerk(target, server0.getSlicelet)

    // Make sure that all of them get the assignment (determined using their specific public APIs).
    // For customers: AssertionWaiter can be replaced by the AsyncTestHelper.eventually {} block.
    AssertionWaiter("Slicelets and Clerk see non-empty assignments").await {
      assert(!server0.Listener.getAssignedRanges.isEmpty)
      assert(!server1.Listener.getAssignedRanges.isEmpty)
      val resource: Option[ResourceAddress] = clerk.getStubForKey(makeKey("Hello"))

      // Using `resource.contains(foo)` rather than `resource.get == foo` so that the assert
      // prettifier displays a more useful error message when the resource option is not defined.
      assert(resource.contains(pod0) || resource.contains(pod1))
    }

    // Stop Slicelets in SampleServer.
    server0.getSlicelet.forTest.stop()
    server1.getSlicelet.forTest.stop()

    // Stop Clerk.
    clerk.forTest.stop()
  }

  test("Assign slices to multiple resources") {
    // Test Plan: Verify that DicerTestEnvironment allows uses to set and freeze an assignment that
    // assigns each slice to multiple resources. Verify this by creating multiple slicelets and
    // connect a clerk to them, set and freeze the test env with an assignment that assigns each
    // slice key to multiple resources. Then call Clerk.getStubForKey and verify that the expected
    // set of resources are returned.
    //
    // The ability to assign slices to multiple resources is enabled by the Dicer asymmetric key
    // replication (<internal link>) feature.

    // Setup: test slice keys.
    val keys: Seq[SliceKey] =
      Seq(
        makeKey(""),
        makeKey("Balin"),
        makeKey("Fili"),
        makeKey("Nori"),
        makeKey("Thorin"),
        makeKey("Gandalf")
      ).sorted

    // Setup: Test target, slicelets and clerk.
    val target = Target(getSafeName)
    val server0 = createSampleServer(target, selfPort = 0)
    val server1 = createSampleServer(target, selfPort = 1)
    val server2 = createSampleServer(target, selfPort = 1)
    val slicelet0: Slicelet = server0.getSlicelet
    val slicelet1: Slicelet = server1.getSlicelet
    val slicelet2: Slicelet = server2.getSlicelet
    val address0: ResourceAddress = slicelet0.forTest.resourceAddress
    val address1: ResourceAddress = slicelet1.forTest.resourceAddress
    val address2: ResourceAddress = slicelet2.forTest.resourceAddress
    val clerk: Clerk[ResourceAddress] = createClerk(target, server0.getSlicelet)

    env.setAndFreezeAssignment(
      target,
      env
        .newAssignmentBuilder()
        .add(Slice(SliceKey.MIN, keys(0)), Iterable(slicelet0, slicelet1))
        .add(Slice(keys(0), keys(4)), Iterable(slicelet0, slicelet0, slicelet2))
        .add(Slice(keys(4), keys(5)), Iterable(slicelet0))
        .add(keys(5), Iterable(slicelet1, slicelet2))
        .build()
    )

    // Setup: Expected assigned resources for slice keys based on the frozen assigned above.
    val expectedKeysWithAssignedResources: Map[SliceKey, Set[ResourceAddress]] = Map(
      SliceKey.MIN -> Set(address0, address1),
      keys(0) -> Set(address0, address1, address2),
      keys(1) -> Set(address0, address1, address2),
      keys(2) -> Set(address0, address1, address2),
      keys(3) -> Set(address0, address1, address2),
      keys(4) -> Set(address0),
      keys(5) -> Set(address1, address2)
    )

    TestUtils.awaitResult(clerk.ready, Duration.Inf)
    AssertionWaiter("Clerk sees multiple resources assigned on slice keys").await {
      for (keyWithAssignedResources <- expectedKeysWithAssignedResources) {
        val (sliceKey, expectedResources): (SliceKey, Set[ResourceAddress]) =
          keyWithAssignedResources
        // Setup: Call Clerk.getStubForKey for 100 times and collect the returned resource
        // addresses.
        val returnedResources = mutable.Set[ResourceAddress]()
        for (_ <- 0 until 100) {
          returnedResources += clerk.getStubForKey(sliceKey).get
        }
        // Verify: The returned addresses are as expected.
        assert(returnedResources == expectedResources)
      }
    }

    // Cleanup: Stop Slicelets and clerk.
    server0.getSlicelet.forTest.stop()
    server1.getSlicelet.forTest.stop()
    server2.getSlicelet.forTest.stop()
    clerk.forTest.stop()
  }

  test("Freeze and unfreeze assignment") {
    // Test plan: Verify that DicerTestEnvironment can dynamically freeze and unfreeze assignments.
    // Verify this by creating a server, receiving its assignment, then adding another server and
    // freezing the assignment. Make sure that the assignment is received. Then unfreeze, add a
    // server and check that the server receives an assignment.

    // Start a server and a clerk. Make sure both of them receive an assignment.
    val target = Target(getSafeName)
    val server0 = createSampleServer(target, selfPort = 0)
    val clerk: Clerk[ResourceAddress] = createClerk(target, server0.getSlicelet)
    val slicelet0: Slicelet = server0.getSlicelet

    AssertionWaiter("Initial assignment").await {
      val resourceAddress: Option[ResourceAddress] = clerk.getStubForKey(makeKey("Hello"))
      assert(resourceAddress.exists(resourceAddress => resourceAddress.uri.toString != ""))
      assert(!server0.Listener.getAssignedRanges.isEmpty)
    }

    // Create another server.
    val server1 = createSampleServer(target, selfPort = 1)
    // Freeze the assignment and make sure that an assignment is received by the Clerk that obeys
    // the assignment below (check via getStubForKey). We create a sequence of 6 sorted keys and
    // assign single key slices of keys(0) and keys(2) to server0, and to server1 a single key slice
    // of keys(1) and a slice [keys(3), keys(5)) (so that we can check that keys(4) which is
    // contained in the slice [keys(3), keys(5)) is assigned to server1).
    val keys: Seq[SliceKey] =
      Seq(
        makeKey(""),
        makeKey("Balin"),
        makeKey("Fili"),
        makeKey("Nori"),
        makeKey("Thorin"),
        makeKey("Gandalf"),
        makeKey("Dori")
      ).sorted
    val slicelet1: Slicelet = server1.getSlicelet
    val asn = env
      .newAssignmentBuilder()
      .add(keys.head, slicelet0)
      .add(keys(1), slicelet1)
      .add(keys(2), slicelet0)
      .add(Slice(keys(3), keys(5)), slicelet1)
      // The ability to assign slices to multiple resources is enabled by the Dicer asymmetric key
      // replication (<internal link>) feature.
      .add(keys(6), Seq(slicelet0, slicelet1))
      .build()
    val asnHandle: AssignmentHandle = env.setAndFreezeAssignment(target, asn)
    logger.info(s"The assignment handle is $asnHandle")

    AssertionWaiter("Clerk receives the assignment").await {
      assert(env.hasReceivedAtLeast(asnHandle, clerk))
    }

    // Using `clerk.getStubForKey(foo).contains(bar)` rather than
    // `clerk.getStubForKey(foo).get == bar` so that the assert prettifier displays a more useful
    // error message when the returned stub (which is of type `Option[ResourceAddress]`) is not
    // defined.
    assert(clerk.getStubForKey(keys.head).contains(slicelet0.forTest.resourceAddress))
    assert(clerk.getStubForKey(keys(1)).contains(slicelet1.forTest.resourceAddress))
    assert(clerk.getStubForKey(keys(2)).contains(slicelet0.forTest.resourceAddress))
    assert(clerk.getStubForKey(keys(3)).contains(slicelet1.forTest.resourceAddress))
    // Note: keys(4) should be contained in the slice [keys(3), keys(5)) assigned to server1.
    assert(clerk.getStubForKey(keys(4)).contains(slicelet1.forTest.resourceAddress))
    // keys(6) is assigned to both slicelet0 and slicelet1. Verify this by calling getStubForKey
    // for a large number of times and verify the set of returned resource addresses.
    assert {
      (0 until 100).flatMap { _ =>
        clerk.getStubForKey(keys(6))
      }.toSet == Set(slicelet0.forTest.resourceAddress, slicelet1.forTest.resourceAddress)
    }

    // Verify that a key that is not included in the frozen assignment is assigned to neither
    // slicelet0 nor slicelet1 but rather to a blackhole address. Since keys(5) is the exclusive
    // upper bound of the slice [keys(3), keys(5)), it is not assigned in `asn` above, and so should
    // be assigned to a blackhole address.
    val expectedBlackholeResourceOpt: Option[ResourceAddress] = clerk.getStubForKey(keys(5))
    assert(expectedBlackholeResourceOpt.isDefined)
    val expectedBlackholeResource: ResourceAddress = expectedBlackholeResourceOpt.get
    assert(expectedBlackholeResource != slicelet0.forTest.resourceAddress)
    assert(expectedBlackholeResource != slicelet1.forTest.resourceAddress)
    assert(expectedBlackholeResource.uri.toString == "http://192.0.2.0:8080")

    // Make sure that the assignment is received by the new server has the assigned ranges.
    AssertionWaiter("New Slicelet receives the assignment").await {
      assert(env.hasReceivedAtLeast(asnHandle, server1.getSlicelet))
    }

    assert(!server1.Listener.getAssignedRanges.isEmpty)
    val assigned: ImmutableRangeSet[SliceKey] = server1.Listener.getAssignedRanges
    assert(assigned.contains(keys(1)))
    assert(assigned.contains(keys(3)))
    assert(assigned.contains(keys(4)))

    // Create a new server and check its state is empty.
    val server2 = createSampleServer(target, selfPort = 2)
    assert(server2.Listener.getAssignedRanges.isEmpty)

    // Now unfreeze the assignment and let the normal generation proceed.
    TestUtils.awaitResult(env.unfreezeAssignment(target), Duration.Inf)
    logger.info(s"Assignments unfrozen")

    // Make sure that it receives a listener call.
    AssertionWaiter("New Slicelet receives an assignment after unfreeze").await {
      assert(!server2.Listener.getAssignedRanges.isEmpty)
    }
  }

  test("Invalid assignments") {
    // Test plan: Build an invalid assignment and make sure that the right exception is raised.
    val target = Target(getSafeName)
    val slicelet0: Slicelet = createSampleServer(target, selfPort = 0).getSlicelet
    val slicelet1: Slicelet = createSampleServer(target, selfPort = 1).getSlicelet

    // Repeated entries.
    assertThrow[IllegalArgumentException]("Slices must not overlap") {
      env
        .newAssignmentBuilder()
        .add(makeKey("Balin"), slicelet0)
        .add(makeKey("Balin"), slicelet1)
        .build()
    }

    // Empty assigned slicelets.
    assertThrow[IllegalArgumentException]("The assigned resources must be non-empty") {
      env
        .newAssignmentBuilder()
        .add(makeKey("Balin"), Vector.empty)
        .build()
    }
    assertThrow[IllegalArgumentException]("The assigned resources must be non-empty") {
      env
        .newAssignmentBuilder()
        .add(Slice(makeKey("Fili"), makeKey("Kili")), Vector.empty)
        .build()
    }
  }

  test("getTotalAttributedLoad increases with more load rate") {
    // Test plan: verify that getTotalAttributedLoad returns the expected result based on the
    // latest received watch request.

    // Before any watch requests, total load is 0.
    val target = Target(getSafeName)
    assert(env.getTotalAttributedLoad(target) == 0)

    val slicelet: Slicelet = createSampleServer(target, selfPort = 0).getSlicelet
    val sliceKey: SliceKey = TestSliceUtils.fp("my-application-key")
    val assignmentHandle: AssignmentHandle = env.setAndFreezeAssignment(
      target,
      env
        .newAssignmentBuilder()
        .add(sliceKey, slicelet)
        .build()
    )

    // Ensure that the Slicelet receives the assignment before incrementing the load so it counts
    // as attributed.
    AssertionWaiter("wait for asn").await {
      assert(env.hasReceivedAtLeast(assignmentHandle, slicelet))
    }
    Using.resource(slicelet.createHandle(TestSliceUtils.fp("my-application-key"))) {
      handle: SliceKeyHandle =>
        handle.incrementLoadBy(1)
    }
    val initialLoad: Double = AssertionWaiter("wait for nonzero load").await {
      // It's hard to assert on an exact figure here due to timing, but these assertions should be
      // realistic enough since customers would have similar difficulty with exact values.
      val initialLoad: Double = env.getTotalAttributedLoad(target)
      assert(initialLoad > 0)
      initialLoad
    }

    Using.resource(slicelet.createHandle(TestSliceUtils.fp("my-application-key"))) {
      handle: SliceKeyHandle =>
        handle.incrementLoadBy(100)
    }
    AssertionWaiter("wait for increased load").await {
      assert(env.getTotalAttributedLoad(target) > initialLoad)
    }
  }

  test("getTotalAttributedLoad error when latest request is from Clerk") {
    // Test plan: verify that getTotalAttributedLoad returns 0 when a Clerk directly connects
    // to the Assigner instead of a Slicelet.

    // Set up: create a Clerk is pointed at the Assigner.
    val target = Target(getSafeName)
    TestClientUtils.createClerk(
      target,
      clerkConf = TestClientUtils.createTestDirectClerkConf(
        assignerPort = env.getAssignerPort,
        clientTlsFilePathsOpt = env.tlsFilePathsConfigOpt.map(
          config =>
            TlsFilePaths(config.clientTlsPaths.keystorePath, config.clientTlsPaths.truststorePath)
        )
      )
    )

    assert(env.getTotalAttributedLoad(target) == 0)
  }


}

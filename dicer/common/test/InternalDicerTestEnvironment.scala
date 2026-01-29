package com.databricks.dicer.common

import com.databricks.caching.util.TestUtils
import com.databricks.caching.util.{
  ConfigScope,
  EtcdTestEnvironment,
  SequentialExecutionContext,
  SequentialExecutionContextPool
}
import com.databricks.rpc.armeria.ReadinessProbeTracker
import com.databricks.common.status.ProbeStatuses
import java.util.concurrent.locks.ReentrantLock
import javax.annotation.concurrent.GuardedBy

import scala.collection.{Seq, mutable}
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future

import com.databricks.dicer.assigner.config.{
  StaticTargetConfigProvider,
  InternalTargetConfig,
  InternalTargetConfigMap,
  TargetName
}
import com.databricks.dicer.assigner.config.TargetConfigProvider.DEFAULT_INITIAL_POLL_TIMEOUT
import com.databricks.dicer.assigner.{
  Assigner,
  InterposingEtcdPreferredAssignerDriver,
  TestableDicerAssignerConf
}
import com.databricks.dicer.client.{TestClientUtils, TlsFilePaths}
import com.databricks.dicer.common.InternalDicerTestEnvironment.initializeEtcdNamespaces
import com.databricks.dicer.external.{
  Clerk,
  ClerkConf,
  HighSliceKey,
  ResourceAddress,
  Slice,
  SliceKey,
  Slicelet,
  Target
}
import com.databricks.dicer.friend.{SliceMap, Squid}
import com.databricks.caching.util.Lock.withLock
import java.net.URI
import java.time.Instant
import java.util.UUID

import scala.concurrent.duration.Duration

import com.databricks.dicer.assigner.conf.DicerAssignerConf

/**
 * InternalDicerTestEnvironment encapsulates a full Dicer environment that can be used in tests
 * (currently only for internal users). It spins up a server that [[Clerk]]s and [[Slicelet]]s
 * can connect to.
 *
 * See [[InternalDicerTestEnvironment.create]] for parameter details.
 */
class InternalDicerTestEnvironment private (
    assignerConf: TestableDicerAssignerConf,
    val dockerizedEtcdOpt: Option[EtcdTestEnvironment],
    val tlsFilePathsOpt: Option[TlsFilePaths],
    val sliceletHostNameOpt: Option[String],
    dynamicConfigProvider: StaticTargetConfigProvider,
    secPool: SequentialExecutionContextPool,
    assignerClusterUri: URI) {

  /** The lock used to protect all state in the test environment. */
  private val lock = new ReentrantLock()

  /** The Clerks that have been created as part of the test environment. */
  @GuardedBy("lock")
  private val clerkMap =
    new mutable.HashMap[Target, mutable.ArrayBuffer[Clerk[ResourceAddress]]]

  /** The Slicelets that have been created as part of the test environment. */
  @GuardedBy("lock")
  private val sliceletMap = new mutable.HashMap[Target, mutable.ArrayBuffer[Slicelet]]

  @GuardedBy("lock")
  /** The Assigners that have been created as part of the test environment. */
  private val assigners: mutable.ArrayBuffer[TestAssigner] =
    new mutable.ArrayBuffer[TestAssigner]

  /**
   * REQUIRES: `slicelet` has been started.
   *
   * Creates a Clerk that watches assignment via RPCs to the given `slicelet`.
   *
   * This is *not* equivalent to `Clerk.createFollowingSlicelet(slicelet)`, which is used to share
   * in-memory state with a collocated Slicelet.
   */
  def createClerk(slicelet: Slicelet): Clerk[ResourceAddress] = withLock(lock) {
    val target: Target = slicelet.impl.target
    val clerkConf: ClerkConf =
      TestClientUtils
        .createTestClerkConf(slicelet.impl.forTest.sliceletPort, tlsFilePathsOpt)
    val clerk: Clerk[ResourceAddress] = TestClientUtils.createClerk(target, clerkConf)
    val clerks = clerkMap.getOrElseUpdate(target, new ArrayBuffer[Clerk[ResourceAddress]])
    clerks.append(clerk)
    clerk
  }

  /**
   * Creates a clerk that directly watches assignments from the Assigner for `target`.
   *
   * As outlined in <internal link>, a clerk typically watches and syncs assignments from a
   * Slicelet, rather than the Assigner. However, in certain use cases, the Clerk needs to watch
   * the Assigner directly due to the absence of dynamic assignment and assignment sync
   * functionalities in the Rust Slicelet.
   *
   * In production, the initial watch request would be against the cluster IP address for
   * the assigner deployment and would be routed to a random assigner instance. However, for tests
   * we require the initial assigner index to be specified explicitly, rather than chosen randomly,
   * because we want to encourage test authors to write explicit tests for each of the possible
   * cases (e.g. a case where the initial assigner is preferred and other where it is a standby.)
   *
   * @param initialAssignerIndex The index of the Assigner to issue the initial watch request.
   * @param branchOpt The optional branch name to use for the Clerk.
   */
  def createDirectClerk(
      target: Target,
      initialAssignerIndex: Int,
      branchOpt: Option[String] = None): Clerk[ResourceAddress] =
    withLock(lock) {
      val assignerPort = testAssigners(initialAssignerIndex).localUri.getPort
      val directClerkConf: ClerkConf =
        TestClientUtils.createTestDirectClerkConf(assignerPort, tlsFilePathsOpt, branchOpt)
      val clerk: Clerk[ResourceAddress] =
        TestClientUtils.createClerk(target, directClerkConf)
      clerkMap.getOrElseUpdate(target, new ArrayBuffer[Clerk[ResourceAddress]]).append(clerk)
      clerk
    }

  /**
   * Creates a Slicelet for the given `target`, i.e., the set of pods that need to
   * be sharded as named by `target`, issuing the initial watch request against the Assigner with
   * the specified index.
   *
   * In production, the initial watch request would be against the cluster IP address for
   * the assigner deployment and would be routed to a random assigner instance. However, for tests
   * we require the initial assigner index to be specified explicitly, rather than chosen randomly,
   * because we want to encourage test authors to write explicit tests for each of the possible
   * cases (e.g. a case where the initial assigner is preferred and other where it is a standby.)
   *
   * See [[Slicelet.apply]] for specs of the parameters. The Slicelet MUST be started with
   * [[Slicelet.start]] before it is used.
   *
   * @param initialAssignerIndex The index of the Assigner to issue the initial watch request.
   * @param watchFromDataPlane Whether [[SliceletConf.watchFromDataPlane]] will be set to true for
   *                           the created Slicelet. When set to true, callers are responsible for
   *                           setting the "LOCATION" environment variable appropriately based on
   *                           their desired testing behaviors.
   */
  def createSlicelet(
      target: Target,
      initialAssignerIndex: Int,
      watchFromDataPlane: Boolean): Slicelet = withLock(lock) {
    val initialAssignerPort: Int = testAssigners(initialAssignerIndex).localUri.getPort
    val slicelets = sliceletMap.getOrElseUpdate(target, new ArrayBuffer[Slicelet])
    val podOrdinal = slicelets.size

    val sliceletHostName: String = sliceletHostNameOpt.getOrElse {
      // Replace ':' with '-' in the slicelet host name since URI cannot parse names with ':' in
      // them.
      s"$target-$podOrdinal".replace(':', '-')
    }
    val slicelet: Slicelet = TestClientUtils.createSlicelet(
      initialAssignerPort,
      target,
      sliceletHostName,
      clientTlsFilePathsOpt = tlsFilePathsOpt,
      serverTlsFilePathsOpt = tlsFilePathsOpt,
      watchFromDataPlane
    )
    slicelets.append(slicelet)
    slicelet
  }

  /**
   * Stops and clears all the test Assigners, Slicelets, and Clerks within this test environment;
   * clears the data in dockerized etcd if defined.
   */
  def clear(): Unit = withLock(lock) {
    for (clerk: Clerk[ResourceAddress] <- clerkMap.values.flatten) {
      clerk.forTest.stop()
    }
    clerkMap.clear()

    for (slicelet: Slicelet <- sliceletMap.values.flatten) {
      slicelet.forTest.stop()
    }
    sliceletMap.clear()

    for (testAssigner <- assigners) {
      testAssigner.stop(InterposingEtcdPreferredAssignerDriver.ShutdownOption.ABRUPT)
    }
    assigners.clear()

    for (dockerizedEtcd: EtcdTestEnvironment <- dockerizedEtcdOpt) {
      dockerizedEtcd.deleteAll()
      initializeEtcdNamespaces(dockerizedEtcd, assignerConf)
    }
  }

  /**
   * Stops the test environment, including all the test Assigners, Slicelets, and Clerks, and
   * waits until it is fully shut down.
   */
  def stop(): Unit = withLock(lock) {
    clear()
    for (dockerizedEtcd: EtcdTestEnvironment <- dockerizedEtcdOpt) {
      dockerizedEtcd.close()
    }
  }

  /** Returns a snapshot of current test Assigners in the test environment. */
  def testAssigners: IndexedSeq[TestAssigner] = withLock(lock) {
    assigners.toIndexedSeq
  }

  // Convenience methods for test environments that contain only a single assigner. Because the
  // assigner index is always 0 in this case it doesn't need to be specified explicitly.

  /**
   * REQUIRES: There is exactly one test Assigner.
   *
   * Returns the sole test assigner in test environments where this is exactly one Assigner. This
   * is for conciseness in tests that only have a single Assigner.
   */
  def testAssigner: TestAssigner = withLock(lock) {
    testAssignerInternal
  }

  /**
   * REQUIRES: There is exactly one test Assigner.
   *
   * Returns the local port that the sole Assigner instance is listening on.
   */
  def getAssignerPort: Int = withLock(lock) {
    testAssignerInternal.localUri.getPort
  }

  /**
   * REQUIRES: There is exactly one test Assigner.
   *
   * Sets the assignment for `target` to one with the given `proposedAssignment`. The Assigner
   * chooses a generation, which is populated in the returned assignment. Any changes to the
   * Slicelets, e.g., adding more, will have no effect on the assignment unless the caller calls
   * [[setAndFreezeAssignment]] again or calls [[unfreezeAssignment]] which resumes assignment
   * generation by Dicer.
   *
   * This is for conciseness in tests that only use a single Assigner; use the
   * [[TestAssigner.setAndFreezeAssignment]] method if there are multiple Assigners.
   */
  def setAndFreezeAssignment(
      target: Target,
      proposedAssignment: SliceMap[ProposedSliceAssignment]): Future[Assignment] =
    withLock(lock) {
      testAssignerInternal.setAndFreezeAssignment(target, proposedAssignment)
    }

  /**
   * REQUIRES: There is exactly one test Assigner.
   *
   * Unfreezes the assignment for the given `target`. If already unfrozen, this is a no-op. Yields
   * the thawed assignment, which may be `None` when the target has no assignment.
   *
   * This is for conciseness in tests that only use a single Assigner; use the
   * [[TestAssigner.unfreezeAssignment]] method if there are multiple Assigners.
   */
  def unfreezeAssignment(target: Target): Future[Option[Assignment]] = withLock(lock) {
    testAssignerInternal.unfreezeAssignment(target)
  }

  /**
   * Like `createSlicelet(target, initialAssignerIndex, watchFromDataPlane)`, but uses the first
   * Assigner instance and sets `watchFromDataPlane` to be false.
   */
  def createSlicelet(target: Target): Slicelet = {
    createSlicelet(target, initialAssignerIndex = 0, watchFromDataPlane = false)
  }

  /** Updates the dynamic configuration targets to include the given parsed `config`. */
  def putDynamicTargetConfig(targetName: TargetName, config: InternalTargetConfig): Unit = {
    assignerConf.putDynamicTargetConfig(targetName, config)
  }

  /** Updates the dynamic configuration targets to include the given parsed `config`. */
  def putDynamicTargetConfig(targetName: String, jsonConfig: String): Unit = {
    assignerConf.putDynamicTargetConfig(targetName, jsonConfig)
  }

  /** Clears the dynamic config so that the static fallback is used. */
  def clearDynamicConfigs(): Unit = {
    assignerConf.clearDynamicConfigs()
  }

  /** Turn on / off the dynamic config in the test environment. */
  def setDynamicConfig(enabled: Boolean): Unit = {
    assignerConf.setDynamicConfig(enabled)
  }

  /**
   * Dynamically adds a new [[TestAssigner]] to the test environment with designated `config`.
   *
   * @return The tuple containing the newly added Assigner and the index of it in the test
   *         environment.
   */
  def addAssigner(config: TestAssigner.Config): (TestAssigner, Int) = {
    val newTestAssigner =
      TestAssigner.createAndStart(
        secPool,
        config,
        dynamicConfigProvider,
        dockerizedEtcdOpt,
        assignerClusterUri
      )
    withLock(lock) {
      assigners.append(newTestAssigner)
      (newTestAssigner, assigners.length - 1)
    }
  }

  /**
   * REQUIRES: `index` is no less than 0 and less than the number of current Assigners in the test
   *           environment.
   *
   * Stops the `index`th Assigner in the test environment. If the Assigner is already stopped, it
   * does a no-op.
   *
   * @note This stop method is asynchronous, and only stops the RPC server.
   */
  def stopAssigner(index: Int): Unit = {
    withLock(lock) {
      require(0 <= index && index < assigners.length)
      assigners(index).stop(InterposingEtcdPreferredAssignerDriver.ShutdownOption.ABRUPT)
    }
  }

  /**
   * REQUIRES: `index` is no less than 0 and less than the number of current Assigners in the test
   *           environment.
   *
   * Restarts the `index`th Assigner in the test environment with `newConfig`. If the Assigner is
   * still running upon calling this function, it will be first stopped and then restarted.
   */
  def restartAssigner(index: Int, newConfig: TestAssigner.Config): Unit = withLock(lock) {
    restartAssignerInternal(index, newConfig)
  }

  /**
   * Restarts the `index`th Assigner with exactly the same configuration as the currently running
   * one, including the Assigner port. This can be used when we want the previously created
   * Slicelets to talk to the newly started Assigner.
   */
  def restartAssignerWithSameConfig(index: Int): Unit = {
    withLock(lock) {
      val previousPort: Int = assigners(index).localUri.getPort
      val newConfig = TestAssigner.Config.create(
        assigners(index).conf,
        designatedDicerAssignerRpcPort = Some(previousPort)
      )
      restartAssignerInternal(index, newConfig)
    }
  }

  /**
   * Reads the value of the internal Dicer metric for recording the total reported load on keys that
   * were assigned at the time of load reporting.
   *
   * IMPORTANT NOTE: Since this is metrics based, it means the value is not isolated across test
   * environments. Globally unique target names must be used to avoid interference.
   */
  def getTotalAssignedLoadMetric(target: Target): Long = withLock(lock) {
    TestClientUtils.getAttributedLoadMetric(target).toLong
  }

  /**
   * Reads the value of the internal Dicer metric for recording the total reported load on keys that
   * were not assigned at the time of load reporting.
   *
   * IMPORTANT NOTE: Since this is metrics based, it means the value is not isolated across test
   * environments. Globally unique target names must be used to avoid interference.
   */
  def getTotalUnassignedLoadMetric(target: Target): Long = withLock(lock) {
    TestClientUtils.getUnattributedLoadMetric(target).toLong
  }

  /**
   * Reads the value of the internal Dicer metric for recording the total number of SliceKeyHandles
   * where [[com.databricks.dicer.external.SliceKeyHandle.close]] has not been called.
   *
   * IMPORTANT NOTE: Since this is metrics based, it means the value is not isolated across test
   * environments. Globally unique target names must be used to avoid interference.
   */
  def getSliceKeyHandlesOutstandingMetric(target: Target): Int = withLock(lock) {
    // TODO(<internal bug>): implement this without using metrics, and then move it to
    //   DicerTestEnvironment.
    TestClientUtils.getSliceKeyHandlesOutstandingMetric(target).toInt
  }

  /**
   * Reads the value of the internal Dicer metric for recording the total number of SliceKeyHandles
   * that have been created.
   *
   * IMPORTANT NOTE: Since this is metrics based, it means the value is not isolated across test
   * environments. Globally unique target names must be used to avoid interference.
   */
  def getSliceKeyHandlesCreatedMetric(target: Target): Int = withLock(lock) {
    TestClientUtils.getSliceKeyHandlesCreatedMetric(target).toInt
  }

  /**
   * Creates a new [[SequentialExecutionContext]] from the test environment's pool.
   *
   * @param contextName The name for the execution context (for debugging).
   * @return A new [[SequentialExecutionContext]] from the pool.
   */
  private[dicer] def createExecutionContext(contextName: String): SequentialExecutionContext = {
    secPool.createExecutionContext(contextName)
  }

  /**
   * REQUIRES: There is exactly one test Assigner.
   *
   * Returns the sole test Assigner in the test environment.
   */
  private def testAssignerInternal: TestAssigner = withLock(lock) {
    assert(
      assigners.length == 1,
      "testAssigner can only be used if there is exactly one assigner."
    )
    assigners.head
  }

  /** See [[InternalDicerTestEnvironment.restartAssigner]]. */
  private def restartAssignerInternal(index: Int, newConfig: TestAssigner.Config): Unit = {
    require(0 <= index && index < assigners.length)

    // We await the asynchronous shutdown to complete, ensuring the new Assigner starts only after
    // the port is released. This is necessary because the stop and start are on different `sec`s,
    // and the start might execute before the stop. If that happens and the restart is configured
    // to be on the same URI, the RPC server will fail to bind to the port.
    TestUtils.awaitReady(
      assigners(index).stop(InterposingEtcdPreferredAssignerDriver.ShutdownOption.ABRUPT),
      Duration.Inf
    )

    assigners(index) = TestAssigner.createAndStart(
      secPool,
      newConfig,
      dynamicConfigProvider,
      dockerizedEtcdOpt,
      assignerClusterUri
    )
  }
}

/** Companion object to provide a factory method for creating a test Dicer Assigner. */
object InternalDicerTestEnvironment {

  /**
   * A test internal target config map that always returns a new InternalTargetConfig for a target
   * if it does not exist in the original config map. This is useful in testing where we want to
   * use targets without setting up configuration.
   *
   * @param targetConfigMap The original target config map.
   */
  private[dicer] class InternalTargetConfigMapWithDefault(targetConfigMap: InternalTargetConfigMap)
      extends InternalTargetConfigMap {
    val configScopeOpt: Option[ConfigScope] = targetConfigMap.configScopeOpt
    val configMap: Map[TargetName, InternalTargetConfig] = targetConfigMap.configMap

    override def get(targetName: TargetName): Option[InternalTargetConfig] =
      targetConfigMap.get(targetName).orElse(Some(InternalTargetConfig.forTest.DEFAULT))
  }

  /**
   * REQUIRES: `numAssigners` >= 0.
   *
   * Returns an internal test environment with a low initial assignment delay to ensure that
   * assignments are generated quickly in a test. The test environment will initially have
   * `numAssigners` test Assigners with the configuration specified by `config`.
   *
   * Dynamic config is disabled by default.
   *
   * @param config The configuration for the test Assigners created by this test environment.
   * @param targetConfigMap The map of target configs for the test environment. If
   *                        `withDefaultTargetConfig` is set to true, the test environment will
   *                        return a default target config for all targets that do not have a config
   *                        in this map.
   * @param tlsFilePathsOpt When specified, the keystore/truststore paths used by Clerks and
   *                        Slicelets to connect to Slicelets and Assigners respectively. When not
   *                        configured, default test TLS options are used. This is configurable to
   *                        simulation platform tests.
   * @param sliceletHostNameOpt When specified, this host name is used by all Slicelets started by
   *                            this test environment. If not defined, a default name consisting of
   *                            the pod and the target name is used.
   * @param enableDynamicConfig Whether the test environment should have dynamic target config
   *                            enabled.
   * @param numAssigners The number of test Assigners to initially create in the test environment.
   * @param allowEtcdMode Whether the test environment allows its test assigners to be in etcd store
   *                      mode or shadow etcd store mode. When this is set to false, creating
   *                      assigners in etcd mode or shadow etcd mode in this test environment
   *                      will fail and throw.
   * @param withDefaultTargetConfig Whether the test environment should have a default target config
   *                                for all targets. By default, this is set to true. This means
   *                                that, unlike in production, if a target does not have a config,
   *                                the test environment will return a default target config.
   *                                If this is set to false, the test environment will behave like
   *                                the production environment, where the Assigner will reject any
   *                                assignment requests for targets that do not have a config.
   * @param secPool The [[SequentialExecutionContextPool]] used by this test environment. The caller
   *                can provide a [[FakeSequentialExecutionContextPool]] to make all Assigners and
   *                their state machine components in the test environment share the same fake
   *                clock.
   * @param assignerClusterUri The URI of the kubernetes cluster that the assigners created in the
   *                           test environment will run in (see <internal link>).
   *                           Defaults to "kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01"
   */
  def create(
      config: TestAssigner.Config = TestAssigner.Config.create(),
      targetConfigMap: InternalTargetConfigMap = InternalTargetConfigMap
        .create(configScopeOpt = None, Map.empty),
      tlsFilePathsOpt: Option[TlsFilePaths] = None,
      sliceletHostNameOpt: Option[String] = None,
      enableDynamicConfig: Boolean = false,
      numAssigners: Int = 1,
      allowEtcdMode: Boolean = false,
      withDefaultTargetConfig: Boolean = true,
      secPool: SequentialExecutionContextPool =
        SequentialExecutionContextPool.create("testEnvPool", numThreads = 10),
      assignerClusterUri: URI = new URI("kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01")
  ): InternalDicerTestEnvironment = {
    require(numAssigners >= 0)

    val assignerConf: TestableDicerAssignerConf = config.assignerConf
    assignerConf.setDynamicConfig(enableDynamicConfig)

    val configMap: InternalTargetConfigMap = if (withDefaultTargetConfig) {
      new InternalTargetConfigMapWithDefault(targetConfigMap)
    } else {
      targetConfigMap
    }

    // Initialize and start the dynamic target config provider.
    // TODO(<internal bug>): Make each assigner has individual dynamic config provider.
    val dynamicConfigProvider: StaticTargetConfigProvider =
      StaticTargetConfigProvider.create(
        staticTargetConfigMap = configMap,
        assignerConf
      )
    dynamicConfigProvider.startBlocking(DEFAULT_INITIAL_POLL_TIMEOUT)

    val dockerizedEtcdOpt: Option[EtcdTestEnvironment] =
      if (allowEtcdMode) {
        val dockerizedEtcd = EtcdTestEnvironment.create()
        initializeEtcdNamespaces(dockerizedEtcd, assignerConf)
        Some(dockerizedEtcd)
      } else {
        None
      }

    val internalDicerTestEnvironment = new InternalDicerTestEnvironment(
      assignerConf,
      dockerizedEtcdOpt,
      tlsFilePathsOpt,
      sliceletHostNameOpt,
      dynamicConfigProvider,
      secPool,
      assignerClusterUri
    )

    for (_ <- 0 until numAssigners) {
      internalDicerTestEnvironment.addAssigner(config)
    }

    // In tests, the only instance in which the [[ReadinessProbeTracker]] becomes ready is from an
    // explicit call to [[ReadinessProbeTracker.updatePodStatusForTesting]]. We set the
    // [[ReadinessProbeTracker]] to report ready by default to avoid test failures when Slicelets
    // are configured to observe readiness status.
    //
    // Note: Tests using the external Slicelet factory method (like customer tests using
    // [[DicerTestEnvironment]]) cannot set the readiness statuses for individual Slicelets, since
    // [[ReadinessProbeTracker]] is a global singleton. Tests may update the reported status for all
    // Slicelets if desired.
    ReadinessProbeTracker.updatePodStatusForTesting(ProbeStatuses.OK_STATUS)

    internalDicerTestEnvironment
  }

  /** Initializes all known EtcdClient namespaces in `etcd`. */
  private def initializeEtcdNamespaces(etcd: EtcdTestEnvironment, conf: DicerAssignerConf): Unit = {
    etcd.initializeStore(Assigner.getAssignmentsEtcdNamespace(conf))
    etcd.initializeStore(Assigner.getPreferredAssignerEtcdNamespace(conf))
  }

  /** The address of the resource to which all unassigned keys are routed. */
  private[dicer] val BLACKHOLE_RESOURCE: Squid = Squid(
    ResourceAddress(URI.create("http://192.0.2.0:8080")),
    creationTimeMillis = Instant.EPOCH.toEpochMilli,
    new UUID(0, 0)
  )

  /**
   * REQUIRES: No assignments can reference the same key or overlapping keys.
   *
   * Completes the input sorted [[ProposedSliceAssignment]]s. Any keys that have
   * not been added will be assigned to http://192.0.2.0:8080, which is a black hole
   * (documentation only) address.
   *
   * Unit tests in DicerTestEnvironmentSuite.
   * Tested via DicerTestEnvironment.TestAssignment.build().
   */
  private[dicer] def sliceAssigmentsToSliceMap(
      sliceAssignments: Seq[ProposedSliceAssignment]
  ): SliceMap[ProposedSliceAssignment] = {
    // Sort the slice assignments so that we can easily identify overlaps and fill gaps in the
    // assignment.
    val sorted: Seq[ProposedSliceAssignment] = sliceAssignments.sortBy {
      sliceAssignment: ProposedSliceAssignment =>
        sliceAssignment.slice
    }
    // Create slice assignments in a sorted array by iterating through the sorted slice
    // assignments. All unassigned slices are mapped to BLACKHOLE_RESOURCE_ADDRESS.
    //
    // For example, given slice assignments:
    //    [A,B)=>res0
    //    [C,D)=>res1
    //    [E,E\0)=>res0 # Singleton slice including just E
    // The filled out assignment is:
    //    ["",A)=>BLACKHOLE_RESOURCE
    //    [A,B)=>res0
    //    [B,C)=>BLACKHOLE_RESOURCE
    //    [C,D)=>res1
    //    [D,E)=>BLACKHOLE_RESOURCE
    //    [E,E\0)=>res0
    //    [E\0,∞)=>BLACKHOLE_RESOURCE
    val completeSliceAssignments = Vector.newBuilder[ProposedSliceAssignment]
    var previousHigh: HighSliceKey = SliceKey.MIN
    for (sliceAssignment: ProposedSliceAssignment <- sorted) {
      val low: SliceKey = sliceAssignment.slice.lowInclusive
      previousHigh match {
        case previousHigh: SliceKey if previousHigh < low =>
          // Fill in gap between the previous Slice and the current Slice.
          completeSliceAssignments += ProposedSliceAssignment(
            Slice(previousHigh, low),
            resources = Set(BLACKHOLE_RESOURCE),
            primaryRateLoadOpt = None
          )
        case _ =>
          if (previousHigh > low) {
            throw new IllegalArgumentException(
              s"Slices must not overlap: $previousHigh > $low"
            )
          }
      }
      completeSliceAssignments += sliceAssignment
      previousHigh = sliceAssignment.slice.highExclusive
    }
    // Last slice high value must be ∞.
    if (previousHigh.isFinite) {
      val previousLow: SliceKey = previousHigh.asFinite
      completeSliceAssignments += ProposedSliceAssignment(
        Slice.atLeast(previousLow),
        resources = Set(BLACKHOLE_RESOURCE),
        primaryRateLoadOpt = None
      )
    }
    val proposedAssignment: SliceMap[ProposedSliceAssignment] =
      SliceMapHelper.ofProposedSliceAssignments(completeSliceAssignments.result())

    proposedAssignment
  }
}

package com.databricks.dicer.assigner

import java.net.URI
import java.time.Instant

import scala.collection.{immutable, mutable}
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Random, Success}

import io.grpc.Status
import io.grpc.Status.Code

import com.databricks.api.proto.dicer.common.ClientRequestP.SliceletDataP
import com.databricks.api.proto.dicer.external.LoadBalancingMetricConfigP.ImbalanceToleranceHintP
import com.databricks.caching.util.TestUtils.{ParameterizedTestNameDecorator, TestName}
import com.databricks.caching.util._
import com.google.protobuf
import com.databricks.conf.Configs
import com.databricks.dicer.assigner.AssignmentGenerator.AssignmentGenerationDecision.{
  GenerateReason,
  SkipReason
}
import com.databricks.dicer.assigner.AssignmentGenerator.DriverAction.StartKubernetesTargetWatcher
import com.databricks.dicer.assigner.AssignmentGenerator._
import com.databricks.dicer.assigner.AssignmentStats.AssignmentChangeStats
import com.databricks.dicer.assigner.CommonAssignmentGeneratorSuite.resource
import com.databricks.dicer.assigner.config.{ChurnConfig, InternalTargetConfig}
import com.databricks.dicer.assigner.config.InternalTargetConfig._
import com.databricks.dicer.assigner.algorithm.{Algorithm, LoadMap, Resources}
import com.databricks.dicer.assigner.algorithm.LoadMap.Entry
import com.databricks.dicer.assigner.Store.WriteAssignmentResult
import com.databricks.dicer.assigner.TargetMetrics.AssignmentDistributionSource.AssignmentDistributionSource
import com.databricks.dicer.assigner.TargetMetrics.{
  AssignmentDistributionSource,
  AssignmentGeneratorOpType,
  IncarnationMismatchType,
  KeyOfDeathTransitionType
}
import com.databricks.dicer.assigner.conf.{DicerAssignerConf, LoadWatcherConf}
import com.databricks.dicer.common.Assignment.DiffUnused.DiffUnused
import com.databricks.dicer.common.Assignment.{AssignmentValueCellConsumer, DiffUnused}
import com.databricks.dicer.common.SliceletData.SliceLoad
import com.databricks.dicer.common.SliceletState
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.common.WatchServerHelper.WATCH_RPC_TIMEOUT
import com.databricks.dicer.common._

import com.databricks.dicer.external.{InfinitySliceKey, Slice, SliceKey, Target}
import com.databricks.dicer.friend.{SliceMap, Squid}
import com.databricks.testing.DatabricksTest
import com.google.common.primitives.Longs
import io.prometheus.client.CollectorRegistry

/**
 * The suite containing setup and tests specific to the AssignmentGenerator while using
 * InMemoryStore.
 *
 * There should be very few tests here; new tests should always be added to
 * [[CommonAssignmentGeneratorSuite]] when possible.
 */
abstract class InMemoryStoreAssignmentGeneratorSuite(
    observeSliceletReadiness: Boolean,
    permitRunningToNotReady: Boolean)
    extends CommonAssignmentGeneratorSuite(observeSliceletReadiness, permitRunningToNotReady) {

  override def paramsForDebug: Map[String, Any] = Map(
    "observeSliceletReadiness" -> observeSliceletReadiness,
    "permitRunningToNotReady" -> permitRunningToNotReady
  )

  override def createStore(sec: SequentialExecutionContext, incarnation: Incarnation): Store =
    InMemoryStore(sec, incarnation)

  override def createLargerValidIncarnation(lowerBound: Incarnation): Incarnation = {
    // InMemoryStore requires a loose incarnation.
    var incarnation = Incarnation(lowerBound.value + random.nextInt(100) + 1)
    while (incarnation.isNonLoose) {
      incarnation = Incarnation(incarnation.value + random.nextInt(100) + 1)
    }
    incarnation
  }

  // This test doesn't apply to etcd because subscribers should not know more than the store within
  // the same incarnation.
  test("Assignment synced from subscriber") {
    // Test plan: send a watch request from a Slicelet including an assignment with a
    // higher/lower/same generation than that known by the generator. Verify that assignments with
    // higher generations are distributed, and that other assignments are ignored.

    def getNumUnusedAssignmentDiffs(
        target: Target): Map[(AssignmentDistributionSource, DiffUnused), Long] = {

      (for (source: AssignmentDistributionSource <- AssignmentDistributionSource.values;
        diffUnusedReason: DiffUnused <- DiffUnused.values;
        metricValue: Double <- MetricUtils.getMetricValueOpt(
          registry,
          metric = "dicer_assigner_num_unused_assignment_diffs",
          Map(
            "targetCluster" -> target.getTargetClusterLabel,
            "targetName" -> target.getTargetNameLabel,
            "targetInstanceId" -> target.getTargetInstanceIdLabel,
            "source" -> source.toString,
            "reason" -> diffUnusedReason.toString
          )
        )) yield {
        (source, diffUnusedReason) -> metricValue.toLong
      }).toMap
    }

    def getNumAssignmentStoreIncarnationMismatch(
        target: Target): Map[IncarnationMismatchType.Value, Long] = {
      (for {
        mismatchType: IncarnationMismatchType.Value <- IncarnationMismatchType.values
        metricValue: Double <- MetricUtils.getMetricValueOpt(
          registry,
          metric = "dicer_assigner_num_store_incarnation_mismatch",
          Map(
            "targetCluster" -> target.getTargetClusterLabel,
            "targetName" -> target.getTargetNameLabel,
            "targetInstanceId" -> target.getTargetInstanceIdLabel,
            "mismatchType" -> mismatchType.toString
          )
        )
      } yield {
        mismatchType -> metricValue.toLong
      }).toMap
    }

    val incarnation: Incarnation = createLargerValidIncarnation(Incarnation.MIN)
    val (driver, store): (AssignmentGeneratorDriver, InterceptableStore) =
      createDriverAndStore(storeIncarnationOpt = Some(incarnation))

    // Keep track of the expected distributed and undistributed assignments (by source) in the
    // generator.
    val Source = AssignmentDistributionSource
    val Reason = DiffUnused
    val expectedDistributedAssignments =
      mutable.Map[Source.AssignmentDistributionSource, Long]().withDefaultValue(0)
    var expectedAssignmentSlices = 0
    val expectedStoreIncarnationMismatch =
      mutable.Map[IncarnationMismatchType.Value, Long]().withDefaultValue(0)
    val expectedUnusedAssignments = mutable
      .Map[(Source.AssignmentDistributionSource, Reason.DiffUnused), Long]()
      .withDefaultValue(0)
    def assertMetrics(): Unit = {
      AssertionWaiter("Wait for metrics to be updated", ecOpt = Some(sec)).await {
        assert(getNumDistributedAssignmentsMap(defaultTarget) == expectedDistributedAssignments)
        assert(TargetMetricsUtils.getNumAssignmentSlices(defaultTarget) == expectedAssignmentSlices)
        assert(getNumUnusedAssignmentDiffs(defaultTarget) == expectedUnusedAssignments)
        assert(
          getNumAssignmentStoreIncarnationMismatch(defaultTarget) ==
          expectedStoreIncarnationMismatch
        )
      }
    }

    // Helper creating a random assignment with a generation tracking the given time.
    val rng = new Random(42)
    def createAssignment(time: Instant, storeIncarnation: Incarnation): Assignment = {
      val proposal = ProposedAssignment(
        predecessorOpt = None,
        createRandomProposal(
          10,
          IndexedSeq(resource(0), resource(1)),
          numMaxReplicas = 2,
          rng
        )
      )
      proposal.commit(
        isFrozen = false,
        AssignmentConsistencyMode.Affinity,
        Generation(storeIncarnation, time.toEpochMilli)
      )
    }

    // Inform the generator of a new assignment via a Slicelet heartbeat. This simulates the case
    // where the assigner is learning about existing assignments when it starts up. Advance time by
    // 10 seconds first so that this heartbeat occurs +10s into the test, and registers a health
    // signal for resource(0) that will last until +40s into the test. Note that resource(1)'s
    // health is bootstrapped and will expire at +30s into the test.
    //
    // An informed assignment will both be distributed immediately and trigger health bootstrapping
    // and thus assignment generation. Wait for the new assignment to be generated based on the
    // informed assignment and check metrics. Notice that both the informed and newly
    // generated assignments (assignment1 and assignment2) are reflected back to the generator via
    // the store watcher (hence the "uninteresting" assignments from "Store" source).
    // To deterministically test the unused assignments metric, we stop the store watcher until the
    // Generator has generating the first assignment based on the informed assignment. Otherwise,
    // the notification from the store would race with completing this initial write.
    sec.run {
      store.blockWatchAssignmentsCallbacks(defaultTarget)
    }
    sec.advanceBySync(10.seconds)
    val assignment1: Assignment =
      createAssignment(
        sec.getClock.instant().minusSeconds(10),
        incarnation
      )
    driver.onWatchRequest(createSliceletRequest(0, assignment1))
    val assignment2: Assignment = awaitNewerAssignment(driver, assignment1.generation)
    sec.run {
      store.unblockWatchAssignmentsCallbacks(defaultTarget)
    }
    expectedDistributedAssignments(Source.Store) += 1
    expectedDistributedAssignments(Source.Slicelet) += 1
    expectedAssignmentSlices = assignment2.sliceAssignments.size
    expectedUnusedAssignments((Source.Store, Reason.DIFF_MATCHES_KNOWN)) += 1
    expectedUnusedAssignments((Source.Store, Reason.TOO_STALE_DIFF)) += 1
    assertMetrics()

    // Inform the generator of the same assignment via a Clerk watch request. Should be ignored.
    driver.onWatchRequest(createClerkRequest(assignment2))
    expectedUnusedAssignments((Source.Clerk, Reason.DIFF_MATCHES_KNOWN)) += 1
    assertMetrics()

    // Advance to the expiration of resource(1)'s healthy status (+30s) and wait for the generator
    // to successfully commit a new assignment.
    sec.advanceBySync(20.seconds)
    expectedDistributedAssignments(Source.Store) += 1
    expectedUnusedAssignments((Source.Store, Reason.DIFF_MATCHES_KNOWN)) += 1
    val assignment3: Assignment = awaitNewerAssignment(driver, assignment2.generation)
    assert(assignment3.assignedResources == Set(resource(0)))
    expectedAssignmentSlices = assignment3.sliceAssignments.size
    assertMetrics()

    // Now have resource(1) come back and supply the first assignment to the generator. That
    // assignment is uninteresting because it has an old version, but the revival of resource(1)
    // should trigger generation of a new assignment.
    driver.onWatchRequest(createSliceletRequest(1, assignment1))
    val assignment4: Assignment = awaitNewerAssignment(driver, assignment3.generation)
    assert(assignment4.assignedResources == Set(resource(0), resource(1)))
    expectedDistributedAssignments(Source.Store) += 1
    expectedAssignmentSlices = assignment4.sliceAssignments.size
    expectedUnusedAssignments((Source.Store, Reason.DIFF_MATCHES_KNOWN)) += 1
    expectedUnusedAssignments((Source.Slicelet, Reason.TOO_STALE_DIFF)) += 1
    assertMetrics()

    // Another Clerk now sends a watch request with a newer assignment generation. This simulates
    // the case where either the Assigner is split-brained (another Assigner is running in a
    // different container) or where a previous store incarnation of the Assigner had a clock that
    // was running faster than the current clock.
    val assignment5: Assignment =
      createAssignment(
        sec.getClock.instant().plusSeconds(10),
        incarnation
      )
    driver.onWatchRequest(createClerkRequest(assignment5))
    expectedDistributedAssignments(Source.Clerk) += 1
    expectedAssignmentSlices = assignment5.sliceAssignments.size
    expectedUnusedAssignments((Source.Store, Reason.DIFF_MATCHES_KNOWN)) += 1
    assertMetrics()

    // Inform the generator of another assignment from a higher store incarnation. It should be
    // distributed.
    val higherIncarnation: Incarnation = createLargerValidIncarnation(incarnation)
    driver.onWatchRequest(
      createClerkRequest(
        createAssignment(
          sec.getClock.instant().plusSeconds(10),
          higherIncarnation
        )
      )
    )
    expectedStoreIncarnationMismatch(IncarnationMismatchType.ASSIGNMENT_HIGHER) += 1
    expectedDistributedAssignments(Source.Clerk) += 1
    awaitNewerAssignment(driver, Generation(higherIncarnation, 0))
    assertMetrics()
  }

  // This test doesn't apply to etcd store, because it is an unexpected case that the slicelet
  // has an known assignment but the etcd store is empty.
  test(
    "Known assignment sync-ed from slicelet bootstraps health information when in-memory " +
    "store is empty"
  ) {
    // Test plan: Verify that when an assignment generator is started and the in-memory store is
    // empty, a known assignment from slicelet can bypass the generator's initialization health
    // delay and bootstrap its health information with assigned resources. This is to simulate a
    // common case in production where a new assigner using in-memory store is started for an
    // existing target.

    val targetConfig: InternalTargetConfig = defaultTargetConfig
    val (driver, store): (AssignmentGeneratorDriver, Store) = createDriverAndStore(targetConfig)
    val rng = new Random()

    // An assignment with resources 0, 1, 2 assigned to be informed to the generator via slicelet.
    sec.advanceBySync(1.second)
    val informedAssignment: Assignment = ProposedAssignment(
      predecessorOpt = None,
      createRandomProposal(
        10,
        Vector(resource(0), resource(1), resource(2)),
        numMaxReplicas = 3,
        rng
      )
    ).commit(
      isFrozen = false,
      consistencyMode = AssignmentConsistencyMode.Affinity,
      generation = Generation(store.storeIncarnation, number = 42)
    )

    // Inform the generator about the assignment by sending it along inside a watch request from
    // a slicelet.
    driver.onWatchRequest(
      createSliceletRequest(0, knownAssignment = informedAssignment)
    )

    // Initial health delay should be passed and an updated assignment should be generated,
    // containing the resources assigned in the informed assignment.
    val updatedAssignment1: Assignment =
      awaitNewerAssignment(driver, informedAssignment.generation)
    assert(
      updatedAssignment1.assignedResources == Set(
        resource(0),
        resource(1),
        resource(2)
      )
    )

    // Confirm the generator performs correctly after the health delay by informing it about a
    // new resource and verify a new assignment containing the new resource is generated.
    driver.onWatchRequest(
      createSliceletRequest(3, knownGeneration = Generation.EMPTY)
    )
    val updatedAssignment2: Assignment =
      awaitNewerAssignment(driver, updatedAssignment1.generation)
    assert(
      updatedAssignment2.assignedResources == Set(
        resource(0),
        resource(1),
        resource(2),
        resource(3)
      )
    )
  }

  // This test doesn't apply to etcd store, because it is unexpected that a slicelet knows a newer
  // generation than etcd.
  test("Stale assignment from in-memory store doesn't pollute initial assignment generation") {
    // Test plan: Verify that when an assignment generator is started while the in-memory store
    // contains a stale assignment which is older than some assignments known by the slicelets,
    // the stale assignment will not be used to bootstrap the generator's health information.

    val rng = new Random()
    val store = createStore(sec, Incarnation(41))

    val staleProposedAssignment = ProposedAssignment(
      predecessorOpt = None,
      createRandomProposal(
        10,
        Vector(resource(0), resource(1), resource(2)),
        numMaxReplicas = 3,
        rng
      )
    )
    val staleAssignment: Assignment = TestUtils.awaitResult(
      store.writeAssignment(
        defaultTarget,
        shouldFreeze = false,
        staleProposedAssignment
      ),
      Duration.Inf
    ) match {
      case WriteAssignmentResult.Committed(assignment: Assignment) => assignment
      case other => throw new AssertionError(f"Write failed: $other")
    }

    val driver: AssignmentGeneratorDriver = createDriverUsingStore(store)

    // Verify the generator gets and distributes the stale assignment.
    awaitAssignment(
      driver,
      debugMessage = s"wait for assignment no older than ${staleAssignment.generation}"
    )(
      (_: Assignment).generation >= staleAssignment.generation
    )
    // But the generator does not generate a new assignment atop it, as it hasn't pass its initial
    // health delay (and don't know any healthy resource).
    assertNoNewerAssignment(driver, staleAssignment.generation)

    // A new assignment that is not in store, but assuming it's known by some slicelet.
    val freshAssignment: Assignment = ProposedAssignment(
      predecessorOpt = Some(staleAssignment),
      createRandomProposal(
        10,
        Vector(resource(3), resource(4), resource(5)),
        numMaxReplicas = 3,
        rng
      )
    ).commit(
      isFrozen = false,
      consistencyMode = AssignmentConsistencyMode.Affinity,
      generation =
        Generation(store.storeIncarnation, number = staleAssignment.generation.number.value + 1)
    )

    // Supply the generator with a watch request from slicelet containing a new set of resources.
    driver.onWatchRequest(
      createSliceletRequest(3, freshAssignment)
    )
    val updatedAssignment1: Assignment =
      awaitNewerAssignment(driver, freshAssignment.generation)

    // Inform the generator of a new resource, resource 3, and verify that the generator reacts by
    // including it in the assignment.
    sec.advanceBySync(1.second)
    driver.onWatchRequest(createSliceletRequest(3, knownAssignment = freshAssignment))
    // Stale resources are not used in the new assignment.
    assert(
      updatedAssignment1.assignedResources == Set(
        resource(3),
        resource(4),
        resource(5)
      )
    )
  }
}

/**
 * The suite containing setup and tests specific to the AssignmentGenerator when used with
 * EtcdStore.
 *
 * As stated above, new tests should be added to [[CommonAssignmentGeneratorSuite]] whenever
 * possible.
 */
abstract class EtcdStoreAssignmentGeneratorSuite(
    observeSliceletReadiness: Boolean,
    permitRunningToNotReady: Boolean)
    extends CommonAssignmentGeneratorSuite(observeSliceletReadiness, permitRunningToNotReady) {

  private val ETCD_NAMESPACE = EtcdClient.KeyNamespace("test-namespace")

  private val etcd = EtcdTestEnvironment.create()

  override def paramsForDebug: Map[String, Any] = Map(
    "observeSliceletReadiness" -> observeSliceletReadiness,
    "permitRunningToNotReady" -> permitRunningToNotReady
  )

  override def beforeEach(): Unit = {
    etcd.deleteAll()
    etcd.initializeStore(ETCD_NAMESPACE)
  }

  override def createStore(
      sec: SequentialExecutionContext,
      incarnation: Incarnation): InterceptableStore = {
    new InterceptableStore(
      sec,
      EtcdStore
        .create(
          sec,
          etcd.createEtcdClient(EtcdClient.Config(ETCD_NAMESPACE)),
          EtcdStoreConfig.create(incarnation),
          random
        )
    )
  }

  override def createLargerValidIncarnation(lowerBound: Incarnation): Incarnation = {
    // EtcdStore requires a non-loose incarnation.
    var incarnation = Incarnation(lowerBound.value + random.nextInt(100) + 1)
    while (incarnation.isLoose) {
      incarnation = Incarnation(incarnation.value + random.nextInt(100) + 1)
    }
    incarnation
  }

  test(
    "Can overwrite existing assignment from prior incarnation even if not most recent"
  ) {
    // Test plan: verify that the generator is able to commit a new assignment atop an existing
    // assignment in the store, even if a more recent assignment is known from a subscriber, which
    // it should use to bootstrap.
    //
    // This is not relevant to InMemoryStore because it does not persist assignments across
    // restarts, so we won't start and find an existing assignment from a prior store incarnation.

    val incarnation0: Incarnation = createLargerValidIncarnation(Incarnation.MIN)
    // For realism, use a loose Incarnation here, but it isn't important.
    val incarnation1: Incarnation = Incarnation(incarnation0.value + 1)
    val incarnation2: Incarnation = createLargerValidIncarnation(incarnation1)
    val (driver, incarnation2Store): (AssignmentGeneratorDriver, InterceptableStore) =
      createDriverAndStore(storeIncarnationOpt = Some(incarnation2))

    // To validate that we use the more recent assignment as the base, we provide two assignments,
    // each assigning one slice to a different resource. We apply a load distribution which balances
    // both assignments, and we should find that the newly generated assignment resembles the later
    // assignment.
    val slices = Seq[Slice](
      "" -- 0XA000000000000000L,
      0XA000000000000000L -- ∞
    )
    val incarnation0Proposal: SliceMap[ProposedSliceAssignment] =
      createProposal(
        slices(0) -> Seq(resource(0)),
        slices(1) -> Seq(resource(1))
      )
    val incarnation1Generation = Generation(incarnation1, 42)
    val incarnation1Assignment: Assignment = createAssignment(
      incarnation1Generation,
      AssignmentConsistencyMode.Affinity,
      slices(0) @@ incarnation1Generation -> Seq(resource(1)),
      slices(1) @@ incarnation1Generation -> Seq(resource(0))
    )
    val loadMap: LoadMap = LoadMap
      .newBuilder()
      .putLoad(slices.map(slice => Entry(slice, load = 10.0)): _*)
      .build()

    // Write an assignment from a lower incarnation into the store, and prevent the driver from
    // learning about it to avoid triggering immediate assignment generation based on this older
    // assignment.
    sec.run {
      incarnation2Store.blockWatchAssignmentsCallbacks(defaultTarget)
    }
    val incarnation0Store: InterceptableStore = createStore(sec, incarnation0)
    val incarnation0Assignment: Assignment = TestUtils.awaitResult(
      incarnation0Store.writeAssignment(
        defaultTarget,
        shouldFreeze = false,
        ProposedAssignment(predecessorOpt = None, incarnation0Proposal)
      ),
      Duration.Inf
    ) match {
      case occFailure: WriteAssignmentResult.OccFailure =>
        throw new AssertionError(s"Conflicting write? Detail: $occFailure")
      case WriteAssignmentResult.Committed(assignment: Assignment) => assignment
    }

    // incarnation0Assignment and incarnation1Assignment are constructed such that there should a
    // complete reassignment between them.
    val loadBalancingChurnRatio0: Double = AssignmentChangeStats
      .calculate(
        incarnation0Assignment,
        incarnation1Assignment,
        loadMap
      )
      .loadBalancingChurnRatio
    assert(loadBalancingChurnRatio0 > 0.9)

    // Inform the generator of a newer assignment; it should use this one as the base for its
    // generated assignment and be able to overwrite the existing assignment in the store.
    sec.advanceBySync(20.seconds)
    // Inform the driver of the latest assignment and the healthy resources.
    driver.onWatchRequest(createSliceletRequest(0, incarnation1Assignment))
    driver.onWatchRequest(createSliceletRequest(1, incarnation1Assignment))

    // Advance beyond the healthy timeout after the driver has the assignment and healthy resources.
    sec.advanceBySync(10.seconds)

    val newlyGeneratedAssignment: Assignment =
      awaitNewerAssignment(driver, Generation(incarnation2, number = 0))
    val loadBalancingChurnRatio1: Double = AssignmentChangeStats
      .calculate(
        incarnation1Assignment,
        newlyGeneratedAssignment,
        loadMap
      )
      .loadBalancingChurnRatio
    assert(loadBalancingChurnRatio1 < 0.1)
  }
}

/**
 * The base set of tests which validate behaviors for AssignmentGenerator regardless of store type.
 *
 * Implementing suites are expected to provide the [[createStore()]] and
 * [[createLargerValidIncarnation()]] implementations.
 *
 * @param observeSliceletReadiness if true, the HealthWatcher faithfully reports the
 *                                 Slicelet-reported health status for a pod. If false, masks the
 *                                 health status so that NotReady is masked to Running.
 * @param permitRunningToNotReady  if true, a pod that reports NotReady while Running is
 *                                 transitioned to NotReady by the HealthWatcher. Only valid when
 *                                 observeSliceletReadiness is also true.
 */
abstract class CommonAssignmentGeneratorSuite(
    observeSliceletReadiness: Boolean,
    permitRunningToNotReady: Boolean)
    extends DatabricksTest
    with TestName
    with ParameterizedTestNameDecorator {
  import CommonAssignmentGeneratorSuite.{getAssignedResources, resource}

  /** Returns a [[Store]] for the store incarnation `incarnation` which uses `sec`. */
  def createStore(sec: SequentialExecutionContext, incarnation: Incarnation): Store

  /**
   * Returns an [[Incarnation]] larger than `lowerBound` which is valid for the [[createStore()]]
   * implementation.
   */
  def createLargerValidIncarnation(lowerBound: Incarnation): Incarnation

  /** Random number generator used by the test suite, with seed logged for debugging. */
  protected val random: Random = TestUtils.newRandomWithLoggedSeed()

  private val INITIAL_HEALTH_REPORT_DELAY_PERIOD: FiniteDuration = 30.seconds
  private val TERMINATING_TIMEOUT_PERIOD: FiniteDuration = 60.seconds
  private val UNHEALTHY_TIMEOUT_PERIOD: FiniteDuration = 30.seconds
  private val NOT_READY_TIMEOUT_PERIOD: FiniteDuration = 10.seconds

  /** The generator's cluster location. */
  private val GENERATOR_CLUSTER_URI: URI =
    URI.create("kubernetes-cluster:test-env/cloud1/public/region1/clustertype3/01")

  /** The default k8s namespace used by targets. */
  private val DEFAULT_K8S_NAMESPACE: String = "localhostNamespace"

  protected val sec: FakeSequentialExecutionContext =
    FakeSequentialExecutionContext.create("Generator")

  /** Gets the default target for the test. */
  protected def defaultTarget: Target = Target(getSafeName)

  /**
   * Gets the map between [[AssignmentDistributionSource]] to the number of distributed
   * assignments.
   */
  protected def getNumDistributedAssignmentsMap(
      target: Target): Map[AssignmentDistributionSource, Long] = {
    AssignmentDistributionSource.values
      .map { source =>
        source -> TargetMetricsUtils.getNumDistributedAssignments(target, source.toString)
      }
      .filter { case (_, count: Long) => count > 0 }
      .toMap
  }

  private def kubernetesWatchTarget: KubernetesWatchTarget =
    KubernetesWatchTarget(
      appName = defaultTarget.getKubernetesAppName,
      namespace = DEFAULT_K8S_NAMESPACE
    )

  private def createGeneratorConfig(): AssignmentGenerator.Config = AssignmentGenerator.Config(
    storeIncarnation = createLargerValidIncarnation(Incarnation.MIN),
    LoadWatcher.StaticConfig(allowTopKeys = false),
    GENERATOR_CLUSTER_URI,
    minAssignmentGenerationInterval = Duration.Zero
  )

  protected def defaultTargetConfig: InternalTargetConfig = {
    InternalTargetConfig.forTest.DEFAULT.copy(
      healthWatcherConfig = HealthWatcherTargetConfig(
        observeSliceletReadiness = observeSliceletReadiness,
        permitRunningToNotReady = permitRunningToNotReady
      )
    )
  }

  private val fakeKubernetesTargetWatcherFactory = new FakeKubernetesTargetWatcherFactory

  /** The [[CollectorRegistry]] for which to fetch metric samples for. */
  protected val registry: CollectorRegistry = CollectorRegistry.defaultRegistry

  /** Creates a generator driver and store that runs on `sec`. */
  protected def createDriverAndStore(
      targetConfig: InternalTargetConfig = defaultTargetConfig,
      storeIncarnationOpt: Option[Incarnation] = None,
      target: Target = defaultTarget,
      assignerProtoLogger: AssignerProtoLogger = AssignerProtoLogger.createNoop(sec))
      : (AssignmentGeneratorDriver, InterceptableStore) = {
    val storeIncarnation: Incarnation =
      storeIncarnationOpt.getOrElse(createLargerValidIncarnation(Incarnation.MIN))
    val store: InterceptableStore =
      new InterceptableStore(sec, createStore(sec, storeIncarnation))
    (
      createDriverUsingStore(
        store,
        targetConfig,
        target,
        assignerProtoLogger = assignerProtoLogger
      ),
      store
    )
  }

  /**
   * PRECONDITION: `store` is running on `sec`.
   *
   * Creates a generator driver running on `sec` that watches assignment from and writes
   * assignments to `store`.
   */
  protected def createDriverUsingStore(
      store: Store,
      targetConfig: InternalTargetConfig = defaultTargetConfig,
      target: Target = defaultTarget,
      minAssignmentGenerationInterval: FiniteDuration = Duration.Zero,
      assignerProtoLogger: AssignerProtoLogger = AssignerProtoLogger.createNoop(sec))
      : AssignmentGeneratorDriver = {
    val conf = new DicerAssignerConf(
      Configs.parseMap(
        "databricks.dicer.assigner.unhealthyTimeoutPeriodSeconds" ->
        UNHEALTHY_TIMEOUT_PERIOD.toSeconds,
        "databricks.dicer.assigner.terminatingTimeoutPeriod" ->
        TERMINATING_TIMEOUT_PERIOD.toSeconds
      )
    )
    AssignmentGeneratorDriver.create(
      sec,
      conf: LoadWatcherConf,
      target,
      targetConfig,
      store,
      fakeKubernetesTargetWatcherFactory,
      HealthWatcher.DefaultFactory
        .create(
          target,
          HealthWatcher.StaticConfig.fromConf(conf),
          targetConfig.healthWatcherConfig
        ),
      new KeyOfDeathDetector(
        target,
        KeyOfDeathDetector.Config.defaultConfig()
      ),
      GENERATOR_CLUSTER_URI,
      minAssignmentGenerationInterval,
      DicerTeeEventEmitter.getNoopEmitter,
      assignerProtoLogger
    )
  }

  /** Creates a generator driver that runs on `sec`. */
  protected def createDriver(
      targetConfig: InternalTargetConfig = defaultTargetConfig,
      storeIncarnationOpt: Option[Incarnation] = None,
      target: Target = defaultTarget,
      assignerProtoLogger: AssignerProtoLogger = AssignerProtoLogger.createNoop(sec))
      : AssignmentGeneratorDriver = {
    val (driver, _): (AssignmentGeneratorDriver, _) =
      createDriverAndStore(
        targetConfig,
        storeIncarnationOpt,
        target,
        assignerProtoLogger
      )
    driver
  }

  /** Creates a generator. */
  private def createGenerator(
      generatorConfig: AssignmentGenerator.Config,
      targetConfig: InternalTargetConfig,
      clock: TypedClock,
      target: Target = defaultTarget): AssignmentGenerator = {
    val generator = new AssignmentGenerator(
      generatorConfig,
      target,
      targetConfig,
      HealthWatcher.DefaultFactory.create(
        target,
        HealthWatcher.StaticConfig(
          initialHealthReportDelayPeriod = INITIAL_HEALTH_REPORT_DELAY_PERIOD,
          unhealthyTimeoutPeriod = UNHEALTHY_TIMEOUT_PERIOD,
          terminatingTimeoutPeriod = TERMINATING_TIMEOUT_PERIOD,
          notReadyTimeoutPeriod = NOT_READY_TIMEOUT_PERIOD
        ),
        targetConfig.healthWatcherConfig
      ),
      new KeyOfDeathDetector(
        target,
        KeyOfDeathDetector.Config.defaultConfig()
      )
    )

    // The base StateMachineDriver does an initial onAdvance call so we do the same here. This gives
    // the generator the chance to emit startup actions and enter the running state.
    generator.forTest.onAdvance(clock.tickerTime(), clock.instant())
    generator
  }

  /**
   * Blocks until `driver` emits an assignment which satisfies `cond`, and returns this assignment.
   *
   * @param debugMessage A debug message to be printed while waiting for the desired assignment.
   */
  @throws[AssertionError]("If blocks for more than 30 seconds.")
  protected def awaitAssignment(driver: AssignmentGeneratorDriver, debugMessage: String)(
      cond: Assignment => Boolean): Assignment = {
    val cell: AssignmentValueCellConsumer = driver.getGeneratorCell
    AssertionWaiter(debugMessage, ecOpt = Some(sec)).await {
      val assignmentOpt: Option[Assignment] = cell.getLatestValueOpt
      assert(assignmentOpt.isDefined)
      val assignment: Assignment = assignmentOpt.get
      assert(cond(assignment))
      assignment
    }
  }

  /**
   * Blocks until `generator` emits an assignment with `generation > knownGeneration`, and returns
   * this assignment.
   */
  @throws[AssertionError]("If blocks for more than 30 seconds.")
  protected def awaitNewerAssignment(
      driver: AssignmentGeneratorDriver,
      knownGeneration: Generation): Assignment = {
    awaitAssignment(driver, debugMessage = s"wait for assignment newer than $knownGeneration")(
      (_: Assignment).generation > knownGeneration
    )
  }

  /**
   * Blocks until `driver` is no longer performing a write.
   *
   * @param debugMessage A debug message to be printed while waiting for the desired assignment.
   */
  protected def waitForWriteToComplete(
      driver: AssignmentGeneratorDriver,
      debugMessage: String): Unit = {
    AssertionWaiter(debugMessage).await {
      assert(!TestUtils.awaitResult(driver.forTest.isAssignmentWriteInProgress, Duration.Inf))
    }
  }

  /**
   * Asserts that there is no new assignment generated by `driver` with a generation higher than
   * `knownGeneration` generated within `duration` time after this function is called.
   *
   * It's impossible to assert in finite time that something _never_ happens, so waiting a fixed
   * amount of time is best-effort in nature.
   */
  @throws[AssertionError]("If there is an assignment newer than knownGeneration generated.")
  protected def assertNoNewerAssignment(
      driver: AssignmentGeneratorDriver,
      knownGeneration: Generation,
      duration: FiniteDuration = 100.milliseconds): Unit = {
    Thread.sleep(duration.toMillis)
    assert(
      driver.getGeneratorCell.getLatestValueOpt
        .forall((_: Assignment).generation <= knownGeneration)
    )
  }

  /** Create client request sent by a Slicelet with the given `id` and assignment to sync. */
  protected def createSliceletRequest(id: Int, knownAssignment: Assignment): ClientRequest = {
    createSliceletRequest(id, SyncAssignmentState.KnownAssignment(knownAssignment))
  }

  /** Create client request sent by a Slicelet with the given `id`. */
  protected def createSliceletRequest(id: Int, knownGeneration: Generation): ClientRequest = {
    createSliceletRequest(id, SyncAssignmentState.KnownGeneration(knownGeneration))
  }

  /** Creates client request sent by a Slicelet with the given `id` for the given `target`. */
  private def createSliceletRequestForTarget(
      id: Int,
      knownGeneration: Generation,
      target: Target): ClientRequest = {
    createSliceletRequest(id, SyncAssignmentState.KnownGeneration(knownGeneration), target = target)
  }

  /** Creates client request sent by a Slicelet with the given `id`, and `attributedLoads`. */
  private def createSliceletRequestWithAttributedLoads(
      id: Int,
      assignment: Assignment,
      window: (Instant, Instant)): ClientRequest = {
    createSliceletRequest(
      id = id,
      syncState = SyncAssignmentState.KnownGeneration(assignment.generation),
      attributedLoads = createAttributedLoads(assignment, resource(id), window)
    )
  }

  /**
   * Creates client request sent by a Slicelet with the given `id`, `syncState`, and
   * `attributedLoads`.
   */
  private def createSliceletRequest(
      id: Int,
      syncState: SyncAssignmentState,
      attributedLoads: Vector[SliceLoad] = Vector.empty,
      target: Target = defaultTarget,
      state: SliceletDataP.State = SliceletDataP.State.RUNNING): ClientRequest = {
    ClientRequest(
      target,
      syncState,
      "subscriber",
      WATCH_RPC_TIMEOUT,
      SliceletData(
        resource(id),
        SliceletState.fromProto(state, target),
        DEFAULT_K8S_NAMESPACE,
        attributedLoads = attributedLoads,
        unattributedLoadOpt = None
      ),
      supportsSerializedAssignment = true
    )
  }

  /** Create loads for all the slices of a `squid` in an assignment `assignment`. */
  private def createAttributedLoads(
      assignment: Assignment,
      squid: Squid,
      window: (Instant, Instant),
      loadMap: LoadMap = LoadMap.UNIFORM_LOAD_MAP): Vector[SliceLoad] = {
    val (windowLowInclusive, windowHighExclusive): (Instant, Instant) = window

    assignment.sliceAssignments
      .filter((p: SliceAssignment) => p.resources.contains(squid))
      .map(
        entry =>
          SliceletData.SliceLoad(
            slice = entry.slice,
            primaryRateLoad = loadMap.getLoad(entry.slice) / entry.resources.size,
            windowLowInclusive = windowLowInclusive,
            windowHighExclusive = windowHighExclusive,
            topKeys = Seq.empty,
            numReplicas = 1
          )
      )
  }

  /** Create client request sent by a Clerk with the given assignment to sync. */
  protected def createClerkRequest(
      knownAssignment: Assignment,
      target: Target = defaultTarget): ClientRequest = {
    ClientRequest(
      target,
      SyncAssignmentState.KnownAssignment(knownAssignment),
      "subscriber",
      WATCH_RPC_TIMEOUT,
      ClerkData,
      supportsSerializedAssignment = true
    )
  }

  /** Gets the map from [[Code]]s to corresponding number of assignment writes. */
  private def getCodesToNumAssignmentWrites(target: Target): Map[Code, Long] = {
    Code
      .values()
      .map { code =>
        code -> (MetricUtils
          .getMetricValue(
            registry,
            metric = "dicer_assigner_num_assignment_writes",
            labels = Map(
              "targetCluster" -> target.getTargetClusterLabel,
              "targetName" -> target.getTargetNameLabel,
              "targetInstanceId" -> target.getTargetInstanceIdLabel,
              "canonicalCode" -> code.toString,
              "isMeaningfulAssignmentChange" -> true.toString
            )
          )
          .toLong +
        MetricUtils
          .getMetricValue(
            registry,
            metric = "dicer_assigner_num_assignment_writes",
            labels = Map(
              "targetCluster" -> target.getTargetClusterLabel,
              "targetName" -> target.getTargetNameLabel,
              "targetInstanceId" -> target.getTargetInstanceIdLabel,
              "canonicalCode" -> code.toString,
              "isMeaningfulAssignmentChange" -> false.toString
            )
          )
          .toLong)
      }
      .filter { case (_, count: Long) => count > 0 }
      .toMap
  }

  private def getLatestWrittenGenerationMetric(target: Target, isMeaningful: Boolean): Long = {
    MetricUtils
      .getMetricValue(
        registry,
        "dicer_assigner_latest_written_assignment_generation_gauge",
        labels = Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel,
          "isMeaningfulAssignmentChange" -> isMeaningful.toString
        )
      )
      .toLong
  }

  private def getLatestWrittenIncarnationMetric(target: Target): Long = {
    MetricUtils
      .getMetricValue(
        registry,
        "dicer_assigner_latest_written_assignment_incarnation_gauge",
        labels = Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel
        )
      )
      .toLong
  }

  private def getLatestKnownGenerationMetric(target: Target): Long = {
    MetricUtils
      .getMetricValue(
        registry,
        "dicer_assigner_latest_known_assignment_generation_gauge",
        labels = Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel
        )
      )
      .toLong
  }

  private def getLatestKnownIncarnationMetric(target: Target): Long = {
    MetricUtils
      .getMetricValue(
        registry,
        "dicer_assigner_latest_known_assignment_incarnation_gauge",
        labels = Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel
        )
      )
      .toLong
  }

  private def getAssignmentGeneratorLatencyHistogramCount(
      target: Target,
      operation: AssignmentGeneratorOpType.Value): Int = {
    MetricUtils.getHistogramCount(
      registry,
      "dicer_assigner_generator_latency_secs",
      labels = Map(
        "targetCluster" -> target.getTargetClusterLabel,
        "targetName" -> target.getTargetNameLabel,
        "targetInstanceId" -> target.getTargetInstanceIdLabel,
        "operation" -> operation.toString
      )
    )
  }

  private def convertToSliceMap(assignment: Assignment): SliceMap[SliceWithResources] = {
    assignment.sliceMap.map((_: SliceWithResources).slice) { sliceAssignment: SliceAssignment =>
      SliceWithResources(sliceAssignment.slice, sliceAssignment.resources)
    }
  }

  /** Verifies the key of death detector metrics matches expectations for the given target. */
  private def verifyKodDetectorMetrics(
      target: Target,
      expectedHeuristicValue: Double,
      expectedNumCrashedResourcesGauge: Int,
      expectedNumCrashedResourcesTotal: Int,
      expectedEstimatedResourceWorkloadSize: Int,
      expectedTransitions: Map[KeyOfDeathTransitionType, Int]): Unit = {
    assert(TargetMetricsUtils.getKeyOfDeathHeuristic(target) == expectedHeuristicValue)
    assert(
      TargetMetricsUtils.getNumCrashedResourcesGauge(target) == expectedNumCrashedResourcesGauge
    )
    assert(
      TargetMetricsUtils.getNumCrashedResourcesTotal(target) == expectedNumCrashedResourcesTotal
    )
    assert(
      TargetMetricsUtils
        .getEstimatedResourceWorkloadSize(target) == expectedEstimatedResourceWorkloadSize
    )
    for (expectedTransition: (KeyOfDeathTransitionType, Int) <- expectedTransitions) {
      val (transitionType, expectedCount): (KeyOfDeathTransitionType, Int) = expectedTransition
      assert(
        TargetMetricsUtils.getKeyOfDeathStateTransitions(target, transitionType) == expectedCount
      )
    }
  }

  test("No assignment within initialization delay") {
    // Test plan: create a generator with a frozen clock and verify that it does not attempt to
    // generate a new assignment because it hasn't yet advanced past the health initialization
    // delay.
    val targetConfig: InternalTargetConfig = defaultTargetConfig
    val driver: AssignmentGeneratorDriver = createDriver(targetConfig)

    // We'd like to assert on lack of an event, so the best we can do is wait for a bit.
    Thread.sleep(100)
    assert(driver.getGeneratorCell.getLatestValueOpt.isEmpty)

    AssertionWaiter("wait for metric").await {
      assert(
        TargetMetricsUtils
          .getAssignmentGenerationSkipDecisions(defaultTarget, SkipReason.IncompleteResources) == 1
      )
    }
  }

  test("Test initial assignment") {
    // Test plan: Create a generator and have a client request be delivered to it. Check that
    // an assignment is generated for the given resource.
    val targetConfig: InternalTargetConfig = defaultTargetConfig
    val driver: AssignmentGeneratorDriver = createDriver(targetConfig)

    // Deliver a heartbeat and advance past initial health report delay to allow the initial
    // assignment to be generated for the resource.
    sec.advanceBySync(20.seconds)
    val request = createSliceletRequest(0, Generation.EMPTY)
    driver.onWatchRequest(request)
    // Do this on the sec to ensure the clock advancement doesn't race with the heartbeat.
    sec.advanceBySync(10.seconds)

    // Watch the generator's cell to get the initial assignment.
    val assignment = awaitNewerAssignment(driver, Generation.EMPTY)

    // Check that the expected resources were used and validate the assignment.
    assertDesirableAssignmentProperties(
      targetConfig,
      assignment,
      Resources.create(Seq(resource(0)))
    )
  }

  test("NOT_READY Slicelet excluded from assignment until it becomes RUNNING") {
    // Test plan: verify that a Slicelet reporting itself to be in the NOT_READY state is excluded
    // from the assignment until it reports RUNNING at least once (when
    // observeSliceletReadiness=true). When observeSliceletReadiness=false (status masking enabled),
    // the HealthWatcher masks NOT_READY as RUNNING, so both Slicelets should be included.
    val targetConfig: InternalTargetConfig = defaultTargetConfig
    val driver: AssignmentGeneratorDriver = createDriver(targetConfig)

    // Deliver two heartbeats and advance past initial health report delay to allow the initial
    // assignment to be generated for the two resources.
    sec.advanceBySync(20.seconds)
    val request1: ClientRequest = createSliceletRequest(
      0,
      SyncAssignmentState.KnownGeneration(Generation.EMPTY),
      state = SliceletDataP.State.NOT_READY
    )
    val request2: ClientRequest = createSliceletRequest(
      1,
      SyncAssignmentState.KnownGeneration(Generation.EMPTY),
      state = SliceletDataP.State.RUNNING
    )
    driver.onWatchRequest(request1)
    driver.onWatchRequest(request2)
    sec.advanceBySync(10.seconds)

    val assignment: Assignment = awaitNewerAssignment(driver, Generation.EMPTY)

    // When observeSliceletReadiness=true, only the RUNNING resource is included in the initial
    // assignment. The NOT_READY Slicelet should be excluded since it has never reported RUNNING.
    // When observeSliceletReadiness=false, both resources are included because the HealthWatcher
    // masks NOT_READY as RUNNING.
    val expectedResources: Resources =
      if (observeSliceletReadiness) Resources.create(Seq(resource(1)))
      else Resources.create(Seq(resource(0), resource(1)))

    assertDesirableAssignmentProperties(targetConfig, assignment, expectedResources)
  }

  test("Slicelet flapping between NOT_READY and RUNNING") {
    // Test plan: verify that a Slicelet whose health reports flap between NOT_READY and RUNNING
    // handles each transition correctly. When permitRunningToNotReady=false (the default), the
    // NOT_READY reports while Running are ignored and the Slicelet stays in the assignment
    // throughout. When permitRunningToNotReady=true, the Slicelet is de-assigned when it reports
    // NOT_READY and re-assigned when it reports RUNNING again (after the flapping protection
    // window elapses). With loadBalancingInterval=25s and notReadyTimeoutPeriod=10s, the
    // protection expires before each RUNNING iteration so the transitions are always clean.
    // resource(1) always reports RUNNING to ensure availableResources is never empty.
    // AssignmentGenerator skips generating an assignment when there are no available resources,
    // so we'd never see resource(0) get removed without resource(1) in the RUNNING state.

    // Set the load balancing interval to less than the unhealthy timeout period so we don't have to
    // do extra heartbeats to keep the Slicelet alive between assignment generation attempts.
    val loadBalancingInterval: FiniteDuration = UNHEALTHY_TIMEOUT_PERIOD - 5.seconds
    val targetConfig: InternalTargetConfig =
      defaultTargetConfig.copy(
        loadBalancingConfig = LoadBalancingConfig(
          loadBalancingInterval,
          ChurnConfig.DEFAULT,
          LoadBalancingMetricConfig(maxLoadHint = 100, ImbalanceToleranceHintP.LOOSE)
        )
      )
    val driver: AssignmentGeneratorDriver = createDriver(targetConfig)
    sec.advanceBySync(20.seconds)
    var reportedState: SliceletDataP.State = SliceletDataP.State.RUNNING
    var lastGeneration: Generation = Generation.EMPTY

    for (_ <- 0 until 10) {
      sec.advanceBySync(loadBalancingInterval)
      val request: ClientRequest = createSliceletRequest(
        0,
        SyncAssignmentState.KnownGeneration(lastGeneration),
        state = reportedState
      )
      driver.onWatchRequest(request)
      driver.onWatchRequest(
        createSliceletRequest(
          1,
          SyncAssignmentState.KnownGeneration(lastGeneration),
          state = SliceletDataP.State.RUNNING
        )
      )

      waitForWriteToComplete(driver, "waiting for write to complete")
      val assignment: Assignment = awaitNewerAssignment(driver, lastGeneration)

      // When permitRunningToNotReady=true and observeSliceletReadiness=true, resource(0) is
      // de-assigned when it reports NOT_READY, leaving only resource(1). Otherwise both stay.
      val expectedResources: Resources =
        if (permitRunningToNotReady && observeSliceletReadiness &&
          reportedState == SliceletDataP.State.NOT_READY) {
          Resources.create(Seq(resource(1)))
        } else {
          Resources.create(Seq(resource(0), resource(1)))
        }

      assertDesirableAssignmentProperties(targetConfig, assignment, expectedResources)

      lastGeneration = assignment.generation
      if (reportedState == SliceletDataP.State.RUNNING) {
        reportedState = SliceletDataP.State.NOT_READY
      } else {
        reportedState = SliceletDataP.State.RUNNING
      }
    }
  }

  test("Test initial assignment with multiple resources") {
    // Test plan: Create a generator and have 2 client requests be delivered to it. Check that
    // an assignment is generated for the given 2 resources.
    val targetConfig: InternalTargetConfig = defaultTargetConfig
    val driver: AssignmentGeneratorDriver = createDriver(targetConfig)

    // Deliver heartbeats and advance past initial health report delay to allow the initial
    // assignment to be generated for the resources.
    sec.advanceBySync(20.seconds)
    val request1 = createSliceletRequest(0, Generation.EMPTY)
    val request2 = createSliceletRequest(1, Generation.EMPTY)
    driver.onWatchRequest(request1)
    driver.onWatchRequest(request2)
    // Do this on the sec to ensure the clock advancement doesn't race with the heartbeats.
    sec.advanceBySync(10.seconds)

    // Watch the generator's cell to get the initial assignment.
    val assignment: Assignment = awaitNewerAssignment(driver, Generation.EMPTY)

    // Check that the expected resources were used and validate the assignment.
    assertDesirableAssignmentProperties(
      targetConfig,
      assignment,
      Resources.create(Seq(resource(0), resource(1)))
    )
  }

  gridTest("Test initial assignment uses key replication config")(
    Seq(
      KeyReplicationConfig.DEFAULT_SINGLE_REPLICA,
      KeyReplicationConfig(minReplicas = 3, maxReplicas = 3)
    )
  ) { keyReplicationConfig: KeyReplicationConfig =>
    // Test plan: Verify that the initial assignment respects the key replication configuration.
    // Verify this by configuring the target to have various KeyReplicationConfigs (but
    // maxReplicas < 5), having 5 resources show up before the initial health report delay, then
    // advancing past the delay to trigger initial assignment generation, and verifying that the
    // initial assignment has the number of replicas as configured.

    val targetConfig: InternalTargetConfig =
      defaultTargetConfig.copy(keyReplicationConfig = keyReplicationConfig)
    val driver: AssignmentGeneratorDriver = createDriver(targetConfig)

    // Deliver heartbeats and advance past initial health report delay to allow the initial
    // assignment to be generated for the resources. Note that the health delay is not bypassed by
    // the watch requests despite that they are from Slicelets, because these watch requests
    // contain EMPTY generation.
    sec.advanceBySync(20.seconds)
    val request1 = createSliceletRequest(0, Generation.EMPTY)
    val request2 = createSliceletRequest(1, Generation.EMPTY)
    val request3 = createSliceletRequest(2, Generation.EMPTY)
    val request4 = createSliceletRequest(3, Generation.EMPTY)
    val request5 = createSliceletRequest(4, Generation.EMPTY)
    driver.onWatchRequest(request1)
    driver.onWatchRequest(request2)
    driver.onWatchRequest(request3)
    driver.onWatchRequest(request4)
    driver.onWatchRequest(request5)
    // Do this on the sec to ensure the clock advancement doesn't race with the heartbeats.
    sec.advanceBySync(10.seconds)

    // Watch the generator's cell to get the initial assignment.
    val assignment: Assignment = awaitNewerAssignment(driver, Generation.EMPTY)

    // Check that the assignment has number of replicas configured in
    // `targetConfig.keyReplicationConfig`.
    assertDesirableAssignmentProperties(
      targetConfig,
      assignment,
      Resources.create(Seq(resource(0), resource(1), resource(2), resource(3), resource(4)))
    )
  }

  gridTest("Test updated assignment with changing resources")(
    Seq(() => defaultTarget, () => Target.createAppTarget(getSafeAppTargetName, "instance-id"))
  ) { (targetFactory: () => Target) =>
    // Test plan: Create a generator that observes evolving available resources. Verify that new,
    // valid assignments are generated as new resources signal they are healthy. Verify that a new,
    // valid assignment is generated when one resource fails to signal it is healthy for the
    // `healthCheckPeriod`.
    val targetConfig: InternalTargetConfig = defaultTargetConfig
    val target: Target = targetFactory()
    val driver: AssignmentGeneratorDriver = createDriver(targetConfig, target = target)

    // Deliver heartbeats and advance past initial health report delay to allow the initial
    // assignment to be generated for the resources. Initially, resources 0 and 1 are available.
    sec.advanceBySync(20.seconds)
    driver.onWatchRequest(createSliceletRequestForTarget(0, Generation.EMPTY, target))
    driver.onWatchRequest(createSliceletRequestForTarget(1, Generation.EMPTY, target))
    // Do this on the sec to ensure the clock advancement doesn't race with the heartbeats.
    sec.advanceBySync(10.seconds)

    // Watch the generator's cell to get the initial assignment.
    var assignment: Assignment = awaitNewerAssignment(driver, Generation.EMPTY)

    // Check that the initial assignment includes resources 0 and 1.
    val availableResources: mutable.Set[Squid] = mutable.Set()
    availableResources += resource(0)
    availableResources += resource(1)
    assertDesirableAssignmentProperties(
      targetConfig,
      assignment,
      Resources.create(availableResources)
    )

    // Add some new resources and check that new assignments including them are added.
    var expectedAssignmentsTriggeredByHealthChange: Int = 0
    val MAX_RESOURCE_COUNT = 5
    for (i <- 2 until MAX_RESOURCE_COUNT) {
      driver.onWatchRequest(createSliceletRequestForTarget(i, assignment.generation, target))
      expectedAssignmentsTriggeredByHealthChange += 1

      // Watch the generator's cell to get the updated assignment.
      assignment = awaitNewerAssignment(driver, assignment.generation)
      assert(
        TargetMetricsUtils
          .getAssignmentGenerationGenerateDecisions(target, GenerateReason.HealthChange)
        == expectedAssignmentsTriggeredByHealthChange
      )

      // Check that the new resource is part of the new assignment.
      availableResources += resource(i)
      assertDesirableAssignmentProperties(
        targetConfig,
        assignment,
        Resources.create(availableResources)
      )
    }

    // Now arrange for resource 0 to appear unhealthy by having it fail to submit a health check for
    // 30 seconds. We need to submit health check signals for all of the _other_ resources after a
    // delay, since otherwise they will also show up as unhealthy after 30 seconds.
    sec.advanceBySync(10.seconds)
    for (i <- 1 until MAX_RESOURCE_COUNT) {
      driver.onWatchRequest(createSliceletRequestForTarget(i, assignment.generation, target))
    }
    // Now advance beyond `unhealthyTimeoutPeriod` for resource 0 and await updated assignment.
    sec.advanceBySync(20.seconds)
    assignment = awaitNewerAssignment(driver, assignment.generation)

    // The new assignment ought to exclude resource 0, which is now unhealthy.
    availableResources.remove(resource(0))
    assertDesirableAssignmentProperties(
      targetConfig,
      assignment,
      Resources.create(availableResources)
    )
    expectedAssignmentsTriggeredByHealthChange += 1
    assert(
      TargetMetricsUtils
        .getAssignmentGenerationGenerateDecisions(target, GenerateReason.HealthChange)
      == expectedAssignmentsTriggeredByHealthChange
    )
  }

  test("Available resources change while write in progress") {
    // Test plan: inform the generator of newly available resources while an assignment write is in
    // progress. Verify that a new assignment including the new resources is emitted on completion
    // of the first write.

    val (driver, store): (AssignmentGeneratorDriver, InterceptableStore) = createDriverAndStore()

    // Initially, resources 0 and 1 are available.
    sec.advanceBySync(20.seconds)
    driver.onWatchRequest(createSliceletRequest(0, Generation.EMPTY))
    driver.onWatchRequest(createSliceletRequest(1, Generation.EMPTY))

    // Prevent the initial assignment from committing by blocking the store.
    sec.call {
      store.blockAssignmentWrites(defaultTarget)
    }

    // Advance past initialization delay to allow the initial assignment to be generated (but not
    // yet written). Do this on the sec to ensure the clock advancement doesn't race with the
    // heartbeats.
    sec.advanceBySync(10.seconds)

    // Wait for the initial assignment to be received (and blocked) by the interceptable store.
    AssertionWaiter("Wait for the initial assignment", ecOpt = Some(sec)).await {
      val proposals: Seq[ProposedAssignment] =
        store.getDeferredAssignments(defaultTarget)
      assert(proposals.size == 1)
      val proposal: ProposedAssignment = proposals.head
      val actualResources: Set[Squid] = getAssignedResources(proposal)
      assert(actualResources == Set(resource(0), resource(1)))
    }

    // Inform the generator of two more available resources.
    driver.onWatchRequest(createSliceletRequest(2, Generation.EMPTY))
    driver.onWatchRequest(createSliceletRequest(3, Generation.EMPTY))

    AssertionWaiter("Wait for metrics to be updated").await {
      assert(
        TargetMetricsUtils
          .getAssignmentGenerationSkipDecisions(defaultTarget, SkipReason.InFlightWrite) == 2
      )
    }

    // Unfreeze the store _after_ the above requests are received, which allows the initial
    // assignment to commit and for the next assignment to be generated.
    sec.run {
      store.unblockAssignmentWrites(defaultTarget)
    }

    // Wait for the generator's cell to expose an assignment with all resources.
    AssertionWaiter("Wait for the new assignment", ecOpt = Some(sec)).await {
      val latestAssignmentOpt: Option[Assignment] =
        driver.getGeneratorCell.getLatestValueOpt
      assert(latestAssignmentOpt.isDefined)
      val latestAssignment: Assignment = latestAssignmentOpt.get
      val actualResources: Set[Squid] = latestAssignment.assignedResources
      assert(actualResources == Set(resource(0), resource(1), resource(2), resource(3)))
    }
  }

  test("Retry on write failure") {
    // Test plan: verify that the generator requests another assignment write when there is a
    // failure. The second assignment write request should incorporate any signals received while
    // the first write attempt was in-flight.
    val clock = new FakeTypedClock
    val targetConfig: InternalTargetConfig = defaultTargetConfig
    val generator: AssignmentGenerator =
      createGenerator(createGeneratorConfig(), targetConfig, clock)

    // Inform the generator of one available slicelet.
    clock.advanceBy(20.seconds)
    val watchOutput1: Output =
      generator.forTest.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.WatchRequest(createSliceletRequest(0, Generation.EMPTY))
      )
    assert(
      watchOutput1.nextTickerTime == clock.tickerTime() + 10.seconds,
      "call back when initial health report delay is up"
    )
    val watchTarget = kubernetesWatchTarget
    assert(watchOutput1.actions == Seq(StartKubernetesTargetWatcher(watchTarget)))

    // Advance to initial health report time, at which point the generator should request an
    // assignment write.
    clock.advanceBy(10.seconds)
    val initialReportOutput: Output =
      generator.forTest.onAdvance(clock.tickerTime(), clock.instant())
    assert(initialReportOutput.actions.size == 1)
    val writeAction1: DriverAction = initialReportOutput.actions.head
    writeAction1 match {
      case DriverAction.WriteAssignment(proposal, None) =>
        assert(proposal.predecessorOpt.isEmpty)
        assert(getAssignedResources(proposal) == Set(resource(0)))
      case _ =>
        fail(s"unexpected action: $writeAction1")
    }

    // Inform the generator of another available slicelet before the first write completes.
    val watchOutput2: Output =
      generator.forTest.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.WatchRequest(createSliceletRequest(1, Generation.EMPTY))
      )
    assert(watchOutput2.actions.isEmpty, "no action expected while write is outstanding")

    // Inform the generator that the initial write failed. Expect a request to write a new
    // assignment include both Slicelets.
    val writeFailureOutput: Output =
      generator.forTest.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.AssignmentWriteComplete(
          result = Failure(Status.INTERNAL.asRuntimeException()),
          contextOpt = None
        )
      )
    assert(writeFailureOutput.actions.size == 1)
    val writeAction2: DriverAction = writeFailureOutput.actions.head
    writeAction2 match {
      case DriverAction.WriteAssignment(proposal, None) =>
        assert(proposal.predecessorOpt.isEmpty)
        assert(getAssignedResources(proposal) == Set(resource(0), resource(1)))
      case _ =>
        fail(s"unexpected action: $writeAction2")
    }

    // Verify the expected metrics were incremented as part of this workload.
    assert(
      TargetMetricsUtils.getAssignmentGenerationGenerateDecisions(
        defaultTarget,
        GenerateReason.FirstAssignment
      ) == 2
    )
    assert(getNumDistributedAssignmentsMap(defaultTarget).isEmpty)
    assert(getCodesToNumAssignmentWrites(defaultTarget) == Map(Code.INTERNAL -> 1))
  }

  test("No retry on OCC failure") {
    // Test plan: simulate a OCC failure where the existing assignment is "good" (has the right
    // assigned resources). Verify that the generator distributes the existing assignment and no
    // more (it should not request an assignment write).
    val clock = new FakeTypedClock
    val targetConfig: InternalTargetConfig = defaultTargetConfig
    val generator: AssignmentGenerator =
      createGenerator(createGeneratorConfig(), targetConfig, clock)

    // Inform the generator of one available slicelet.
    clock.advanceBy(20.seconds)
    val watchOutput: Output =
      generator.forTest.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.WatchRequest(createSliceletRequest(0, Generation.EMPTY))
      )
    assert(
      watchOutput.nextTickerTime == clock.tickerTime() + 10.seconds,
      "call back when initial health report delay is up"
    )
    val watchTarget = kubernetesWatchTarget
    assert(watchOutput.actions == Seq(StartKubernetesTargetWatcher(watchTarget)))

    // Advance to initial health report time, at which point the generator should request an
    // assignment write.
    clock.advanceBy(10.seconds)
    val initialReportOutput: Output =
      generator.forTest.onAdvance(clock.tickerTime(), clock.instant())
    assert(initialReportOutput.actions.size == 1)
    val writeAction1: DriverAction = initialReportOutput.actions.head
    writeAction1 match {
      case DriverAction.WriteAssignment(proposal, None) =>
        assert(proposal.predecessorOpt.isEmpty)
        assert(getAssignedResources(proposal) == Set(resource(0)))
      case _ =>
        fail(s"unexpected action: $writeAction1")
    }
    // Simulate an OCC failure with a "good" assignment that includes the available resource. That
    // assignment should be distributed (and no assignment write should be requested).
    val resources = Resources.create(Seq(resource(0)))
    val target2 = Target("a-target")
    val existingAssignment: Assignment = ProposedAssignment(
      predecessorOpt = None,
      Algorithm
        .generateInitialAssignment(target2, resources, KeyReplicationConfig.DEFAULT_SINGLE_REPLICA)
    ).commit(
      isFrozen = false,
      AssignmentConsistencyMode.Affinity,
      Generation(Incarnation.MIN, 42)
    )
    val occFailureOutput: Output = generator.forTest.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.AssignmentWriteComplete(
        Success(Store.WriteAssignmentResult.OccFailure(existingAssignment.generation)),
        None
      )
    )
    assert(occFailureOutput.actions.size == 1)
    val writeAction2: DriverAction = occFailureOutput.actions.head
    writeAction2 match {
      case DriverAction.WriteAssignment(proposal: ProposedAssignment, None) =>
        assert(proposal.predecessorOpt.isEmpty)
        assert(getAssignedResources(proposal) == existingAssignment.assignedResources)
      case _ =>
        fail(s"unexpected action: $writeAction2")
    }

    // Verify the expected metrics were incremented as part of this workload.
    assert(
      TargetMetricsUtils.getAssignmentGenerationGenerateDecisions(
        defaultTarget,
        GenerateReason.FirstAssignment
      ) == 2
    )
    assert(
      getCodesToNumAssignmentWrites(defaultTarget) ==
      Map(Code.FAILED_PRECONDITION -> 1)
    )
  }

  test("Retry on OCC failure") {
    // Test plan: simulate a OCC failure where the existing assignment is "bad" (has the wrong
    // assigned resources). Verify that the generator distributes the existing assignment and
    // requests that a new assignment is written.
    val clock = new FakeTypedClock
    val targetConfig: InternalTargetConfig = defaultTargetConfig
    val generator: AssignmentGenerator =
      createGenerator(createGeneratorConfig(), targetConfig, clock)

    // Inform the generator of one available slicelet.
    clock.advanceBy(20.seconds)
    val watchOutput1: Output =
      generator.forTest.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.WatchRequest(createSliceletRequest(0, Generation.EMPTY))
      )
    assert(
      watchOutput1.nextTickerTime == clock.tickerTime() + 10.seconds,
      "call back when initial health report delay is up"
    )
    val watchTarget = kubernetesWatchTarget
    assert(watchOutput1.actions == Seq(StartKubernetesTargetWatcher(watchTarget)))

    // Advance to initial health report time, at which point the generator should request an
    // assignment write.
    clock.advanceBy(10.seconds)
    val initialReportOutput: Output =
      generator.forTest.onAdvance(clock.tickerTime(), clock.instant())
    assert(initialReportOutput.actions.size == 1)
    val writeAction1: DriverAction = initialReportOutput.actions.head
    writeAction1 match {
      case DriverAction.WriteAssignment(proposal, None) =>
        assert(proposal.predecessorOpt.isEmpty)
        assert(getAssignedResources(proposal) == Set(resource(0)))
      case _ =>
        fail(s"unexpected action: $writeAction1")
    }

    // Inform the generator of another available slicelet before the first write completes.
    val watchOutput2: Output =
      generator.forTest.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.WatchRequest(createSliceletRequest(1, Generation.EMPTY))
      )
    assert(watchOutput2.actions.isEmpty, "no action expected while write is outstanding")

    // Simulate an OCC failure with a "bad" assignment that does not include both available
    // resources. That assignment should be distributed and a new assignment should be requested.
    val resources = Resources.create(Seq(resource(0)))
    val target2 = Target("occ-retry-target")
    val externalAssignment: Assignment = ProposedAssignment(
      predecessorOpt = None,
      Algorithm
        .generateInitialAssignment(target2, resources, KeyReplicationConfig.DEFAULT_SINGLE_REPLICA)
    ).commit(
      isFrozen = false,
      AssignmentConsistencyMode.Affinity,
      Generation(createGeneratorConfig().storeIncarnation, 42)
    )
    val occFailureOutput: Output = generator.forTest.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.AssignmentWriteComplete(
        Success(Store.WriteAssignmentResult.OccFailure(externalAssignment.generation)),
        None
      )
    )
    assert(occFailureOutput.actions.size == 1)
    val writeAction2: DriverAction = occFailureOutput.actions.head
    writeAction2 match {
      case DriverAction.WriteAssignment(proposal, _) =>
        assert(proposal.predecessorOpt.isEmpty)
        assert(getAssignedResources(proposal) == Set(resource(0), resource(1)))
      case _ =>
        fail(s"unexpected action: $writeAction2")
    }

    // Verify the expected metrics were incremented as part of this workload.
    assert(
      TargetMetricsUtils.getAssignmentGenerationGenerateDecisions(
        defaultTarget,
        GenerateReason.FirstAssignment
      ) == 2
    )
    assert(getNumDistributedAssignmentsMap(defaultTarget) == Map.empty)
    assert(
      getCodesToNumAssignmentWrites(defaultTarget) ==
      Map(Code.FAILED_PRECONDITION -> 1)
    )
  }

  test("Assignment stats published to metrics only on successful write") {
    // Test plan: create an assignment, then simulate an OCC failure writing a new assignment.
    // Verify metrics containing assignment stats are not updated. Retry the write, this time
    // successfully, and verify the metrics are updated.

    def getAccumulatedAdditionChurnRatio(target: Target): Double = {
      MetricUtils.getMetricValue(
        registry,
        metric = "dicer_assigner_churn_addition_counter",
        Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel
        )
      )
    }

    val clock = new FakeTypedClock
    val targetConfig: InternalTargetConfig = defaultTargetConfig
    val generatorConfig: AssignmentGenerator.Config = createGeneratorConfig()
    val generator: AssignmentGenerator =
      createGenerator(generatorConfig, targetConfig, clock)

    // Inform the generator of one available slicelet.
    clock.advanceBy(20.seconds)
    val watchOutput1: Output =
      generator.forTest.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.WatchRequest(createSliceletRequest(0, Generation.EMPTY))
      )
    assert(
      watchOutput1.nextTickerTime == clock.tickerTime() + 10.seconds,
      "call back when initial health report delay is up"
    )
    val watchTarget = kubernetesWatchTarget
    assert(watchOutput1.actions == Seq(StartKubernetesTargetWatcher(watchTarget)))

    // Advance to initial health report time, at which point the generator should request an
    // assignment write.
    clock.advanceBy(10.seconds)
    val initialReportOutput: Output =
      generator.forTest.onAdvance(clock.tickerTime(), clock.instant())
    assert(initialReportOutput.actions.size == 1)
    val writeAction1: DriverAction = initialReportOutput.actions.head
    val proposal1: ProposedAssignment = writeAction1 match {
      case DriverAction.WriteAssignment(proposal, None) => proposal
      case _ => fail(s"unexpected action: $writeAction1")
    }
    // Let initial assignment write go through successfully.
    val assignment1: Assignment =
      proposal1.commit(
        isFrozen = false,
        AssignmentConsistencyMode.Affinity,
        Generation(generatorConfig.storeIncarnation, 10)
      )
    generator.forTest.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.AssignmentWriteComplete(
        Success(Store.WriteAssignmentResult.Committed(assignment1)),
        None
      )
    )

    // Inform the generator of another available slicelet.
    val watchOutput2: Output =
      generator.forTest.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.WatchRequest(createSliceletRequest(1, Generation.EMPTY))
      )
    assert(watchOutput2.actions.size == 1)
    val writeAction2: DriverAction = watchOutput2.actions.head
    val (proposal2, statsBuilder): (ProposedAssignment, AssignmentGenerationContext) =
      writeAction2 match {
        case DriverAction.WriteAssignment(proposal, Some(statsBuilder)) =>
          (proposal, statsBuilder)
        case _ => fail(s"unexpected action: $writeAction1")
      }
    val occFailureOutput: Output = generator.forTest.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.AssignmentWriteComplete(
        Success(Store.WriteAssignmentResult.OccFailure(assignment1.generation)),
        Some(statsBuilder)
      )
    )
    // There should be no assignment stats published.
    assert(getAccumulatedAdditionChurnRatio(defaultTarget) == 0)

    assert(occFailureOutput.actions.size == 1)
    occFailureOutput.actions.head match {
      case DriverAction.WriteAssignment(_, _) => ()
      case _ => fail(s"unexpected action: $occFailureOutput")
    }
    generator.forTest.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.AssignmentWriteComplete(
        Success(
          Store.WriteAssignmentResult
            .Committed(
              proposal2.commit(
                isFrozen = false,
                AssignmentConsistencyMode.Affinity,
                Generation(generatorConfig.storeIncarnation, 11)
              )
            )
        ),
        Some(statsBuilder)
      )
    )
    assert(getAccumulatedAdditionChurnRatio(defaultTarget) > 0)
  }

  test("No assignment generation for frozen assignment") {
    // Test plan: write a frozen assignment to the store. Verify that the generator does not emit a
    // new assignment until it is unfrozen.

    val (driver, store): (AssignmentGeneratorDriver, InterceptableStore) = createDriverAndStore()

    val proposal = createProposal(
      ("" -- 10) -> Seq("resource0"),
      (10 -- 20) -> Seq("resource1"),
      (20 -- ∞) -> Seq("resource2")
    )
    val frozenWriteResult: Store.WriteAssignmentResult =
      TestUtils.awaitResult(
        store.writeAssignment(
          defaultTarget,
          shouldFreeze = true,
          ProposedAssignment(predecessorOpt = None, proposal)
        ),
        Duration.Inf
      )
    val frozenAssignment: Assignment = frozenWriteResult match {
      case Store.WriteAssignmentResult.Committed(assignment: Assignment) => assignment
      case _ => fail(s"Unexpected write result: $frozenWriteResult")
    }

    // Register a callback to observe generated assignments.
    val callback = new LoggingStreamCallback[Assignment](sec)
    val cancellable: Cancellable = driver.getGeneratorCell.watch(callback)

    // Await the pre-populated assignment.
    AssertionWaiter("Wait for the pre-populated assignment", ecOpt = Some(sec)).await {
      assert(callback.getLog == Seq(StatusOr.success(frozenAssignment)))
    }
    assert(
      TargetMetricsUtils
        .getAssignmentGenerationSkipDecisions(defaultTarget, SkipReason.FrozenAssignment) == 1
    )

    // Initially, resources 0 and 1 are available. Importantly, these are different resources than
    // are assigned in the frozen assignment. Deliver these health signals at +20s so that their
    // health status lasts until +50s. The resources in the frozen assignment will have their health
    // status bootstrapped and will expire at +30s.
    sec.advanceBySync(20.seconds)
    driver.onWatchRequest(createSliceletRequest(0, Generation.EMPTY))
    driver.onWatchRequest(createSliceletRequest(1, Generation.EMPTY))

    // Advance to +30s so that the bootstrapped health status of the resources in the frozen
    // assignment expire.
    sec.advanceBySync(10.seconds)

    // Add resources 2 and 3 so that we can verify that the first assignment emitted by the
    // generator is not just for the initial set (that includes only 0 and 1).
    driver.onWatchRequest(createSliceletRequest(2, Generation.EMPTY))
    driver.onWatchRequest(createSliceletRequest(3, Generation.EMPTY))

    // Now unfreeze the assignment and verify that we get the unfrozen assignment followed by an
    // assignment including all four resources.
    val unfrozenWriteResult = TestUtils.awaitResult(
      store.writeAssignment(
        defaultTarget,
        shouldFreeze = false,
        ProposedAssignment(predecessorOpt = Some(frozenAssignment), proposal)
      ),
      Duration.Inf
    )
    val unfrozenAssignment: Assignment = unfrozenWriteResult match {
      case Store.WriteAssignmentResult.Committed(assignment: Assignment) => assignment
      case _ => fail(s"Unexpected write result: $unfrozenWriteResult")
    }
    AssertionWaiter("Wait for subsequent assignments", ecOpt = Some(sec)).await {
      assert(callback.getLog.length == 3)
    }
    val log: Vector[StatusOr[Assignment]] = callback.getLog

    assert(log(0) == StatusOr.success(frozenAssignment))
    assert(log(1) == StatusOr.success(unfrozenAssignment))
    val generatedAssignment: StatusOr[Assignment] = log(2)
    assert(generatedAssignment.isOk)
    assert(
      generatedAssignment.get.assignedResources == Set(
        resource(0),
        resource(1),
        resource(2),
        resource(3)
      )
    )

    // Clean up watcher.
    cancellable.cancel(Status.CANCELLED)
  }

  test("Assignment write cadence") {
    // Test plan: Verify that assignments written by the generator include historical load
    // information, and that new assignments are written every
    // `LoadBalancingConfig.loadBalancingInterval`, even if the assignment contents and metadata
    // (i.e. load) are unchanging.

    val clockIncrement: FiniteDuration = 1.seconds
    val loadBalancingInterval: FiniteDuration = 5.seconds
    val driver: AssignmentGeneratorDriver = createDriver(
      InternalTargetConfig.forTest.DEFAULT.copy(
        loadBalancingConfig = LoadBalancingConfig(
          loadBalancingInterval,
          ChurnConfig.DEFAULT,
          LoadBalancingMetricConfig(maxLoadHint = 100, ImbalanceToleranceHintP.LOOSE)
        )
      )
    )

    // Log all assignments that are written by the generator.
    val callback = new LoggingStreamCallback[Assignment](sec)
    driver.getGeneratorCell.watch(callback)

    // Generate initial assignment with two Slicelets.
    sec.advanceBySync(20.seconds)
    driver.onWatchRequest(createSliceletRequest(0, Generation.EMPTY))
    driver.onWatchRequest(createSliceletRequest(1, Generation.EMPTY))
    sec.advanceBySync(10.seconds) // advance beyond initial health report delay
    val initialAssignment: Assignment = {
      waitForWriteToComplete(driver, "waiting for assignment write to complete")
      AssertionWaiter("initial assignment").await {
        val assignmentOpt: Option[Assignment] =
          driver.getGeneratorCell.getLatestValueOpt
        assert(assignmentOpt.isDefined)
        assignmentOpt.get
      }
    }
    val initialWriteTime = initialAssignment.generation.toTime
    assert(initialWriteTime == sec.getClock.instant())
    assert(initialAssignment.assignedResources == Set(resource(0), resource(1)))

    // Helper reporting load for the Slicelets. Every time this helper is called, Slicelets 0 and 1
    // report uniform load adding up to `totalLoad` across the full key space based on their current
    // assignments. We apply uniform load to ensure that new assignments and metadata are written
    // even when load balancing is not done.
    def reportLoad(totalLoad: Double): Unit = {
      val latestAssignment: Assignment = driver.getGeneratorCell.getLatestValueOpt.get
      for (id <- Seq(0, 1)) {
        val request = createSliceletRequest(id, latestAssignment.generation)
        val sliceletData = request.subscriberData.asInstanceOf[SliceletData]

        // Construct load report for the Slicelet based on its assigned Slices.
        val attributedLoads: Vector[SliceLoad] = latestAssignment.sliceAssignments.flatMap {
          sliceAssignment: SliceAssignment =>
            if (sliceAssignment.resources.contains(sliceletData.squid)) {
              val slice: Slice = sliceAssignment.slice
              val primaryRateLoad: Double = LoadMap.UNIFORM_LOAD_MAP.getLoad(slice) * totalLoad /
                sliceAssignment.resources.size
              Some(
                SliceLoad(
                  primaryRateLoad,
                  sec.getClock.instant().minusSeconds(120),
                  sec.getClock.instant(),
                  sliceAssignment.slice,
                  topKeys = Seq.empty,
                  numReplicas = 1
                )
              )
            } else {
              None
            }
        }
        val requestWithLoad =
          request.copy(subscriberData = sliceletData.copy(attributedLoads = attributedLoads))
        driver.onWatchRequest(requestWithLoad)
      }
    }

    // Repeatedly report load and advance the clock. Verify that the generator writes only one
    // assignment after we've reached the last load balancing iteration (if the generator were
    // to generate more frequently than expected, we'd expect this to flake).
    val expectedWriteTime1: Instant = initialWriteTime.plusNanos(loadBalancingInterval.toNanos)
    var totalLoad = 2.0
    while (sec.getClock.instant().isBefore(expectedWriteTime1)) {
      // Increase total load so that every time we do load balancing, the historical load
      // measurements change.
      totalLoad *= 2
      reportLoad(totalLoad)
      sec.advanceBySync(clockIncrement)
    }
    waitForWriteToComplete(driver, "waiting for first load-only assignment write to complete")
    val loadOnlyAssignment1: Assignment =
      AssertionWaiter("first load assignment").await {
        val assignmentLog: Seq[StatusOr[Assignment]] = callback.getLog
        assert(assignmentLog.size == 2)
        assignmentLog.last.get
      }
    // Verify that the last assignment was written at the expected time.
    assert(loadOnlyAssignment1.generation.toTime == expectedWriteTime1)
    assert(
      TargetMetricsUtils
        .getAssignmentGenerationGenerateDecisions(defaultTarget, GenerateReason.LoadBalancing) == 1
    )

    // Now verify that another assignment is written at the same interval, even though load and
    // health are unchanging.
    val expectedWriteTime2: Instant =
      loadOnlyAssignment1.generation.toTime.plusNanos(loadBalancingInterval.toNanos)
    while (sec.getClock.instant().isBefore(expectedWriteTime2)) {
      reportLoad(totalLoad)
      sec.advanceBySync(clockIncrement)
    }
    waitForWriteToComplete(driver, "waiting for second load-only assignment write to complete")
    val loadOnlyAssignment2: Assignment =
      AssertionWaiter("second load assignment").await {
        val assignmentLog: Seq[StatusOr[Assignment]] = callback.getLog
        assert(assignmentLog.size == 3)
        assignmentLog.last.get
      }

    // Finally, confirm that all generated assignments have the same Slice->Slicelet assignments,
    // and have expected total load measurements.
    def getTotalLoad(assignment: Assignment): Double = {
      assignment.sliceAssignments.flatMap { sliceAssignment: SliceAssignment =>
        sliceAssignment.primaryRateLoadOpt
      }.sum
    }
    assert(convertToSliceMap(initialAssignment) == convertToSliceMap(loadOnlyAssignment1))
    assert(convertToSliceMap(loadOnlyAssignment1) == convertToSliceMap(loadOnlyAssignment2))
    assert(getTotalLoad(initialAssignment) != getTotalLoad(loadOnlyAssignment1))
    assert(getTotalLoad(loadOnlyAssignment1) == getTotalLoad(loadOnlyAssignment2))
  }

  test("Watch from Slicelet triggers KubernetesTargetWatcher only once") {
    // Test plan: Verify that a KubernetesTargetWatcher is created/started only once for a given
    // target. Verify this by creating an assignment generator and sending it slicelet requests for
    // the same target, and checking that only the first triggers a StartKubernetesTargetWatcher
    // action.
    val clock = new FakeTypedClock
    val targetConfig: InternalTargetConfig = defaultTargetConfig
    val generator: AssignmentGenerator =
      createGenerator(createGeneratorConfig(), targetConfig, clock)

    // Create first watch for the target. Should trigger StartKubernetesTargetWatcher action.
    val watchOutput1: Output =
      generator.forTest.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.WatchRequest(createSliceletRequest(0, Generation.EMPTY))
      )
    assert(
      watchOutput1.nextTickerTime == clock.tickerTime() + 30.seconds
    )
    val watchTarget = kubernetesWatchTarget
    assert(watchOutput1.actions == Seq(StartKubernetesTargetWatcher(watchTarget)))

    // Create second watch for the target. Should NOT trigger StartKubernetesTargetWatcher action.
    val watchOutput2: Output =
      generator.forTest.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.WatchRequest(createSliceletRequest(0, Generation.EMPTY))
      )
    assert(
      watchOutput2.nextTickerTime == clock.tickerTime() + 30.seconds
    )
    assert(watchOutput2.actions.isEmpty)

    // Create third watch for the target with a new namespace, should not trigger
    // StartKubernetesTargetWatcher action.
    val req = createSliceletRequest(0, Generation.EMPTY)
    val sliceletRequest = req.copy(
      subscriberData =
        req.subscriberData.asInstanceOf[SliceletData].copy(kubernetesNamespace = "foobar")
    )
    val watchOutput3: Output =
      generator.forTest.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.WatchRequest(sliceletRequest)
      )
    assert(
      watchOutput3.nextTickerTime == clock.tickerTime() + 30.seconds
    )
    assert(watchOutput3.actions.isEmpty)
  }

  test("Watch from local cluster Slicelet triggers KubernetesTargetWatcher, else does not") {
    // Test plan: Verify that a watch request from a local cluster Slicelet triggers creation of a
    // Kubernetes watcher for the target, and otherwise does not. Verify this by delivering a
    // WatchRequest event to the state machine for local cluster targets (where some have the
    // cluster URI explicitly set in the Target and some do not) and also remote cluster targets,
    // and checking that the generator issues a StartKubernetesTargetWatcher action in response to
    // the former but not the latter.
    val clock = new FakeTypedClock

    // Verify: Case 1 - Local cluster Slicelet watch request where Target has no explicit cluster
    // URI (triggers creation of a k8s target watcher).
    {
      val target = Target("implicitly-local-target")
      val generator: AssignmentGenerator =
        createGenerator(createGeneratorConfig(), defaultTargetConfig, clock, target)
      val watchOutput: Output =
        generator.forTest.onEvent(
          clock.tickerTime(),
          clock.instant(),
          Event.WatchRequest(createSliceletRequestForTarget(0, Generation.EMPTY, target))
        )
      assert(
        watchOutput.actions == Seq(
          StartKubernetesTargetWatcher(
            KubernetesWatchTarget(
              appName = target.getKubernetesAppName,
              namespace = DEFAULT_K8S_NAMESPACE
            )
          )
        )
      )
    }

    // Verify: Case 2 - Local cluster Slicelet watch request where Target has cluster URI equal to
    // the generator's cluster (triggers creation of a k8s target watcher).
    {
      val target = Target.createKubernetesTarget(GENERATOR_CLUSTER_URI, "explicitly-local-target")
      val generator: AssignmentGenerator =
        createGenerator(createGeneratorConfig(), defaultTargetConfig, clock, target)
      val watchOutput: Output =
        generator.forTest.onEvent(
          clock.tickerTime(),
          clock.instant(),
          Event.WatchRequest(createSliceletRequestForTarget(0, Generation.EMPTY, target))
        )
      assert(
        watchOutput.actions == Seq(
          StartKubernetesTargetWatcher(
            KubernetesWatchTarget(
              appName = target.getKubernetesAppName,
              namespace = DEFAULT_K8S_NAMESPACE
            )
          )
        )
      )
    }

    // Verify: Case 3 - Remote cluster Slicelet watch request (does not trigger creation of a k8s
    // target watcher).
    {
      val clusterUri = URI.create("kubernetes-cluster:test-env/cloud1/public/region1/clustertype1/kjfna2")
      assert(GENERATOR_CLUSTER_URI != clusterUri)
      val target = Target.createKubernetesTarget(clusterUri, "remote-cluster-target")
      val generator: AssignmentGenerator =
        createGenerator(createGeneratorConfig(), defaultTargetConfig, clock, target)
      val watchOutput: Output =
        generator.forTest.onEvent(
          clock.tickerTime(),
          clock.instant(),
          Event.WatchRequest(createSliceletRequestForTarget(0, Generation.EMPTY, target))
        )
      assert(watchOutput.actions.isEmpty)
    }
  }

  test("Watch from Slicelet with empty k8s namespace does not trigger k8s watch, fires alert") {
    // Test plan: Verify that a client request from a Slicelet with an empty k8s namespace does not
    // trigger a StartKubernetesTargetWatcher action, and instead triggers a Caching.DEGRADED alert
    // with error code CachingErrorCode.SLICELET_EMPTY_NAMESPACE. Verify this by delivering such a
    // request to the state machine, checking that the generator doesn't return a
    // StartKubernetesTargetWatcher action, and that the PrefixLogger's error_count metric is
    // incremented for the expected labels.
    val clock = new FakeTypedClock
    val targetConfig: InternalTargetConfig = defaultTargetConfig
    val config: AssignmentGenerator.Config = createGeneratorConfig()
    val generator: AssignmentGenerator = createGenerator(config, targetConfig, clock)

    // Setup: Capture initial metric count so we can check the diff at the end.
    val initialErrorCountMetricValue: Int =
      MetricUtils.getPrefixLoggerErrorCount(
        Severity.DEGRADED,
        CachingErrorCode.SLICELET_EMPTY_NAMESPACE,
        s"${defaultTarget.getLoggerPrefix}, incarnation: ${config.storeIncarnation}"
      )

    // Setup: Send a Slicelet request without Kubernetes namespace.
    val req = createSliceletRequest(0, Generation.EMPTY)
    val sliceletRequest = req.copy(
      subscriberData = req.subscriberData.asInstanceOf[SliceletData].copy(kubernetesNamespace = "")
    )

    val watchOutput1: Output =
      generator.forTest.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.WatchRequest(sliceletRequest)
      )
    assert(
      watchOutput1.nextTickerTime == clock.tickerTime() + 30.seconds
    )
    assert(watchOutput1.actions.isEmpty)

    // Verify: The PrefixLogger's error_count metric was incremented for the expected target.
    assert(
      MetricUtils.getPrefixLoggerErrorCount(
        Severity.DEGRADED,
        CachingErrorCode.SLICELET_EMPTY_NAMESPACE,
        s"${defaultTarget.getLoggerPrefix}, incarnation: ${config.storeIncarnation}"
      ) == initialErrorCountMetricValue + 1
    )
  }

  test("Watch from Slicelets from same target but with different k8s namespaces fires alert") {
    // Test plan: Verify that client requests from Slicelets for the same target but with different
    // k8s namespaces triggers a Caching.DEGRADED alert with error code
    // CachingErrorCode.SLICELET_NAMESPACE_MISMATCH. Verify this by delivering such requests to the
    // state machine, checking that the generator and checking that the PrefixLogger's error_count
    // metric is incremented for the expected labels.
    val clock = new FakeTypedClock
    val targetConfig: InternalTargetConfig = defaultTargetConfig
    val config: AssignmentGenerator.Config = createGeneratorConfig()
    val generator: AssignmentGenerator = createGenerator(config, targetConfig, clock)

    // Setup: Capture initial metric count so we can check the diff at the end.
    val initialErrorCountMetricValue: Int =
      MetricUtils.getPrefixLoggerErrorCount(
        Severity.DEGRADED,
        CachingErrorCode.SLICELET_NAMESPACE_MISMATCH,
        s"${defaultTarget.getLoggerPrefix}, incarnation: ${config.storeIncarnation}"
      )

    // Verify: Send a Slicelet request reporting "namespace-foo", check that it triggers a watch.
    {
      val req = createSliceletRequest(0, Generation.EMPTY)
      val sliceletRequest = req.copy(
        subscriberData =
          req.subscriberData.asInstanceOf[SliceletData].copy(kubernetesNamespace = "namespace-foo")
      )
      val watchOutput: Output =
        generator.forTest.onEvent(
          clock.tickerTime(),
          clock.instant(),
          Event.WatchRequest(sliceletRequest)
        )
      assert(
        watchOutput.actions == Seq(
          StartKubernetesTargetWatcher(
            KubernetesWatchTarget(
              appName = defaultTarget.getKubernetesAppName,
              namespace = "namespace-foo"
            )
          )
        )
      )
    }

    // Verify: Send a Slicelet request for the same target (`defaultTarget`) reporting
    // "namespace-bar", check that it does not start a (new) watch, and instead triggers the
    // expected alert.
    {
      val req = createSliceletRequest(0, Generation.EMPTY)
      val sliceletRequest = req.copy(
        subscriberData =
          req.subscriberData.asInstanceOf[SliceletData].copy(kubernetesNamespace = "namespace-bar")
      )
      val watchOutput: Output =
        generator.forTest.onEvent(
          clock.tickerTime(),
          clock.instant(),
          Event.WatchRequest(sliceletRequest)
        )
      assert(watchOutput.actions.isEmpty)
    }

    // Verify: The PrefixLogger's error_count metric was incremented for the expected target.
    assert(
      MetricUtils.getPrefixLoggerErrorCount(
        Severity.DEGRADED,
        CachingErrorCode.SLICELET_NAMESPACE_MISMATCH,
        s"${defaultTarget.getLoggerPrefix}, incarnation: ${config.storeIncarnation}"
      ) == initialErrorCountMetricValue + 1
    )
  }

  test("Watches from remote cluster Slicelets does not trigger empty/mismatched namespace alerts") {
    // Test plan: Verify that no alerts are fired for remote cluster Slicelets with empty or
    // mismatching k8s namespaces (since k8s watching is not supported for remote clusters, we do
    // not want to alert on these cases). Verify this by delivering such requests to the state
    // machine and checking that the generator does increment the PrefixLogger's error_count metric.
    val clock = new FakeTypedClock
    val clusterUri = URI.create("kubernetes-cluster:test-env/cloud1/public/region1/clustertype1/kjfna2")
    assert(GENERATOR_CLUSTER_URI != clusterUri)
    val target = Target.createKubernetesTarget(clusterUri, "remote-cluster-target")
    val config: AssignmentGenerator.Config = createGeneratorConfig()
    val generator: AssignmentGenerator =
      createGenerator(config, defaultTargetConfig, clock, target)

    // Verify: Issue watch requests for a remote cluster Slicelet for `target` empty and mismatching
    // k8s namespaces, and check that a watch is never started, and that the PrefixLogger's
    // error_count metric is never incremented. Note that when we check the metric, we check for
    // exactly 0 instead of a diff w/ an initial value, since this metric should never be
    // incremented for this target across all invocations of this test in all test suite subclasses.
    for (namespace: String <- Seq("", "namespace-foo", "namespace-bar")) {
      val req: ClientRequest = createSliceletRequestForTarget(0, Generation.EMPTY, target)
      val sliceletRequest: ClientRequest = req.copy(
        subscriberData =
          req.subscriberData.asInstanceOf[SliceletData].copy(kubernetesNamespace = namespace)
      )
      val watchOutput: Output =
        generator.forTest.onEvent(
          clock.tickerTime(),
          clock.instant(),
          Event.WatchRequest(sliceletRequest)
        )
      assert(watchOutput.actions.isEmpty)
      assert(
        MetricUtils.getPrefixLoggerErrorCount(
          Severity.DEGRADED,
          CachingErrorCode.SLICELET_EMPTY_NAMESPACE,
          s"${target.getLoggerPrefix}, incarnation: ${config.storeIncarnation}"
        ) == 0
      )
      assert(
        MetricUtils.getPrefixLoggerErrorCount(
          Severity.DEGRADED,
          CachingErrorCode.SLICELET_NAMESPACE_MISMATCH,
          s"${target.getLoggerPrefix}, incarnation: ${config.storeIncarnation}"
        ) == 0
      )
    }
  }

  test("Assignment generation triggered at regular intervals when LB is enabled") {
    // Test plan: configure the generator to perform load balancing at 10 second intervals. Verify
    // that the generator emits an assignment at this interval unless it is reset by a resource
    // health change (which also triggers assignment generation).

    val lbConfig = LoadBalancingConfig(
      loadBalancingInterval = 10.seconds,
      ChurnConfig.DEFAULT,
      primaryRateMetric = LoadBalancingMetricConfig(
        maxLoadHint = 1000,
        imbalanceToleranceHint = ImbalanceToleranceHintP.DEFAULT
      )
    )
    val targetConfig: InternalTargetConfig = defaultTargetConfig.copy(
      loadBalancingConfig = lbConfig
    )
    val fakeClock = new FakeTypedClock
    val generatorConfig: AssignmentGenerator.Config = createGeneratorConfig()
    val generator: AssignmentGenerator = createGenerator(generatorConfig, targetConfig, fakeClock)

    // Track initial time of the clock to sanity check the time at different points in the test.
    val initialTime: TickerTime = fakeClock.tickerTime()

    // Simulate a heartbeat from a single Slicelet.
    fakeClock.advanceBy(25.seconds)
    assert(fakeClock.tickerTime() == initialTime + 25.seconds)
    // now == 25.seconds.
    // initialHealthDelay == 30.seconds.
    // nextSlicezDataCheckTime == 30.seconds.

    val request1: ClientRequest = createSliceletRequest(0, Generation.EMPTY)
    val watchOutput25Sec: Output = generator.forTest.onEvent(
      fakeClock.tickerTime(),
      fakeClock.instant(),
      Event.WatchRequest(request1)
    )
    // Next advance time should be the time to exit initial health delay, and also to check slicez
    // data (but no UpdateAssignmentGeneratorTargetSlicezData action emitted because of absence of
    // load information).
    assert(watchOutput25Sec.nextTickerTime == fakeClock.tickerTime() + 5.seconds)
    assert(watchOutput25Sec.nextTickerTime == initialTime + 30.seconds)
    val watchTarget = kubernetesWatchTarget
    assert(watchOutput25Sec.actions == Seq(StartKubernetesTargetWatcher(watchTarget)))

    // Advance to the initial assignment generation time and verify that the generator emits an
    // assignment.
    fakeClock.advanceBy(5.seconds) // advance by initial health report delay.
    assert(fakeClock.tickerTime() == initialTime + 30.seconds)
    // now == 30.seconds.
    // nextLoadBalancingTime == N/A
    // nextSlicezDataCheckTime == 35.seconds
    val advanceOutput30Sec: Output =
      generator.forTest.onAdvance(fakeClock.tickerTime(), fakeClock.instant())
    assert(advanceOutput30Sec.actions.size == 1)
    val proposal1: ProposedAssignment = advanceOutput30Sec.actions.head match {
      case DriverAction.WriteAssignment(proposal, None) => proposal
      case _ => fail(s"unexpected action: ${advanceOutput30Sec.actions.head}")
    }

    // Once the assignment write completes, the generator should request advancement after the
    // configured load balancing interval. Simulate async write completion after 1 second to test
    // whether the LB interval is respected
    fakeClock.advanceBy(1.second)
    assert(fakeClock.tickerTime() == initialTime + 31.seconds)
    // now == 31.seconds.
    // nextLoadBalancingTime == N/A.
    // nextSlicezDataCheckTime == 35.seconds.

    val writeCommitOutput31Sec: Output = generator.forTest.onEvent(
      fakeClock.tickerTime(),
      fakeClock.instant(),
      Event.AssignmentWriteComplete(
        Success(
          Store.WriteAssignmentResult.Committed(
            proposal1.commit(
              isFrozen = false,
              AssignmentConsistencyMode.Affinity,
              Generation(generatorConfig.storeIncarnation, 42)
            )
          )
        ),
        None
      )
    )
    // now == 31.seconds.
    // nextLoadBalancingTime == 41.seconds.
    // nextSlicezDataCheckTime == 35.seconds.

    assert(writeCommitOutput31Sec.nextTickerTime == fakeClock.tickerTime() + 4.seconds)
    assert(writeCommitOutput31Sec.nextTickerTime == initialTime + 35.seconds)
    // Not time (41.seconds) for next load balance yet.
    assert(
      TargetMetricsUtils
        .getAssignmentGenerationSkipDecisions(defaultTarget, SkipReason.TooSoonToLoadBalance) == 1
    )

    fakeClock.advanceBy(4.seconds)
    assert(fakeClock.tickerTime() == initialTime + 35.seconds)
    val advanceOutput35Sec: Output =
      generator.forTest.onAdvance(fakeClock.tickerTime(), fakeClock.instant())
    // now == 35.seconds.
    // nextLoadBalancingTime == 41.seconds.
    // nextSlicezDataCheckTime == 40.seconds.

    assert(advanceOutput35Sec.nextTickerTime == fakeClock.tickerTime() + 5.seconds)
    assert(advanceOutput35Sec.nextTickerTime == initialTime + 40.seconds)
    // Not time for next load balance yet.
    assert(
      TargetMetricsUtils
        .getAssignmentGenerationSkipDecisions(defaultTarget, SkipReason.TooSoonToLoadBalance) == 2
    )

    // Simulate heartbeats for the first Slicelet. This should not reset the clock.
    fakeClock.advanceBy(3.seconds)
    assert(fakeClock.tickerTime() == initialTime + 38.seconds)
    val watchOutput38Sec: Output = generator.forTest.onEvent(
      fakeClock.tickerTime(),
      fakeClock.instant(),
      Event.WatchRequest(request1)
    )
    // now == 38.seconds.
    // nextLoadBalancingTime == 41.seconds.
    // nextSlicezDataCheckTime == 40.seconds.

    // After the heartbeat for the known Slicelet, the generator should request advancement at
    // the next slicez data check time, which is earlier than the original future load balancing
    // time.
    assert(watchOutput38Sec.nextTickerTime == fakeClock.tickerTime() + 2.seconds)
    assert(watchOutput38Sec.nextTickerTime == initialTime + 40.seconds)
    // Still, not time for load balancing yet.
    assert(
      TargetMetricsUtils
        .getAssignmentGenerationSkipDecisions(defaultTarget, SkipReason.TooSoonToLoadBalance) == 3
    )

    fakeClock.advanceBy(2.seconds)
    assert(fakeClock.tickerTime() == initialTime + 40.seconds)
    // now == 40.seconds.
    // nextLoadBalancingTime == 41.seconds.
    // nextSlicezDataCheckTime == 45.seconds.
    val advanceOutput40Sec: Output =
      generator.forTest.onAdvance(fakeClock.tickerTime(), fakeClock.instant())
    assert(advanceOutput40Sec.nextTickerTime == fakeClock.tickerTime() + 1.seconds)
    assert(advanceOutput40Sec.nextTickerTime == initialTime + 41.seconds)
    assert(
      TargetMetricsUtils
        .getAssignmentGenerationSkipDecisions(defaultTarget, SkipReason.TooSoonToLoadBalance) == 4
    )

    // Request from a second Slicelet to trigger assignment generation. This should reset the clock
    // after write commit.
    val request2: ClientRequest = createSliceletRequest(1, Generation.EMPTY)
    val watchOutput40Sec: Output = generator.forTest.onEvent(
      fakeClock.tickerTime(),
      fakeClock.instant(),
      Event.WatchRequest(request2)
    )
    assert(watchOutput40Sec.actions.size == 1)
    val proposal2: ProposedAssignment = watchOutput40Sec.actions.head match {
      case DriverAction.WriteAssignment(proposal, _) => proposal
      case _ => fail(s"unexpected action: ${watchOutput40Sec.actions.head}")
    }

    // Once the second assignment write completes, the generator should request advancement after
    // the configured load balancing interval again, which resets after the health-driven
    // reassignment completes.
    fakeClock.advanceBy(2.second)
    assert(fakeClock.tickerTime() == initialTime + 42.seconds)
    val writeCommitOutput42Sec: Output = generator.forTest.onEvent(
      fakeClock.tickerTime(),
      fakeClock.instant(),
      Event.AssignmentWriteComplete(
        Success(
          Store.WriteAssignmentResult.Committed(
            proposal2.commit(
              isFrozen = false,
              AssignmentConsistencyMode.Affinity,
              Generation(generatorConfig.storeIncarnation, 47)
            )
          )
        ),
        None
      )
    )
    // now == 42.seconds.
    // nextLoadBalancingTime == 52.seconds.
    // nextSlicezDataCheckTime == 45.seconds.

    // nextTickerTime is set to the next slicez data check time.
    assert(writeCommitOutput42Sec.nextTickerTime == fakeClock.tickerTime() + 3.seconds)
    assert(writeCommitOutput42Sec.nextTickerTime == initialTime + 45.seconds)
    assert(
      TargetMetricsUtils
        .getAssignmentGenerationSkipDecisions(defaultTarget, SkipReason.TooSoonToLoadBalance) == 5
    )

    // Bypass the next 2 rounds of slicez data check time, to verify the next load balancing time
    // at 52.seconds.

    fakeClock.advanceBy(3.seconds)
    assert(fakeClock.tickerTime() == initialTime + 45.seconds)
    val advanceOutput45Sec: Output =
      generator.forTest.onAdvance(fakeClock.tickerTime(), fakeClock.instant())
    // now == 45.seconds.
    // nextLoadBalancingTime == 52.seconds.
    // nextSlicezDataCheckTime == 50.seconds.
    assert(advanceOutput45Sec.nextTickerTime == initialTime + 50.seconds)
    assert(
      TargetMetricsUtils
        .getAssignmentGenerationSkipDecisions(defaultTarget, SkipReason.TooSoonToLoadBalance) == 6
    )

    fakeClock.advanceBy(5.seconds)
    assert(fakeClock.tickerTime() == initialTime + 50.seconds)
    val advanceOutput50Sec: Output =
      generator.forTest.onAdvance(fakeClock.tickerTime(), fakeClock.instant())
    // now == 50.seconds.
    // nextLoadBalancingTime == 52.seconds.
    // nextSlicezDataCheckTime == 55.seconds.
    assert(advanceOutput50Sec.nextTickerTime == initialTime + 52.seconds)
    assert(
      TargetMetricsUtils
        .getAssignmentGenerationSkipDecisions(defaultTarget, SkipReason.TooSoonToLoadBalance) == 7
    )

    fakeClock.advanceBy(2.seconds)
    assert(fakeClock.tickerTime() == initialTime + 52.seconds)
    // Now we reached the next load balancing time. Generator should try to write new assignment.
    val advanceOutput52Sec: Output =
      generator.forTest.onAdvance(fakeClock.tickerTime(), fakeClock.instant())
    // now == 52.seconds.
    // nextLoadBalancingTime == 62.seconds.
    // nextSlicezDataCheckTime == 55.seconds.
    assert(advanceOutput52Sec.actions.size == 1)
    advanceOutput52Sec.actions.head match {
      case _: DriverAction.WriteAssignment => succeed
      case _ => fail(s"unexpected action: ${advanceOutput52Sec.actions.head}")
    }
  }

  test(
    "UpdateAssignmentGeneratorTargetSlicezData action triggered at stats update interval when " +
    "load information is available"
  ) {
    // Test plan: Verify that UpdateAssignmentGeneratorTargetSlicezData action is generated or
    // not as expected. Create AssignmentGenerator with 10 seconds of load balancing interval (and
    // thus 5 seconds of interval to check for slicez data update). Manually create resources and
    // watch requests, then check whether an action of UpdateAssignmentGeneratorTargetSlicezData
    // is generated in various cases:
    // 1) incomplete load (which means there is no previous assignment - otherwise uniform load
    //    distribution is used as fallback even if there is insufficient load from slicelets).
    // 2) stats update interval hasn't passed.
    // 3) update interval passed.
    val clock = new FakeTypedClock
    val generatorConfig: AssignmentGenerator.Config = createGeneratorConfig()
    val generator: AssignmentGenerator =
      createGenerator(
        generatorConfig,
        InternalTargetConfig.forTest.DEFAULT.copy(
          loadBalancingConfig = LoadBalancingConfig(
            loadBalancingInterval = 10.seconds,
            ChurnConfig.DEFAULT,
            LoadBalancingMetricConfig(1000, ImbalanceToleranceHintP.DEFAULT)
          )
        ),
        clock
      )

    // Track initial time of the clock to sanity check the time at different points in the test.
    val initialTime: TickerTime = clock.tickerTime()

    // Send a watch request from resource 0 that can be used to generate the initial assignment
    // after the health delay.
    clock.advanceBy(15.seconds)
    assert(clock.tickerTime() == initialTime + 15.seconds)
    generator.forTest.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.WatchRequest(createSliceletRequest(0, Generation.EMPTY))
    )

    // Advance past initialization delay to allow the initial assignment to be generated.
    clock.advanceBy(15.seconds)
    assert(clock.tickerTime() == initialTime + 30.seconds)

    // Let assignment generator generate the initial assignment.
    val advanceOutput30Sec: Output = generator.forTest.onAdvance(
      clock.tickerTime(),
      clock.instant()
    )
    // now == 30.seconds.
    // nextSlicezDataCheckTime == 35.seconds.

    // Output should only contain a WriteAssignment action for the initial assignment, since no
    // latestAssignment is "known" by the generator yet.
    assert(advanceOutput30Sec.nextTickerTime == clock.tickerTime() + 5.seconds)
    assert(advanceOutput30Sec.nextTickerTime == initialTime + 35.seconds)
    assert(advanceOutput30Sec.actions.size == 1)
    val writeAction1: DriverAction = advanceOutput30Sec.actions.head
    val proposal1: ProposedAssignment = writeAction1 match {
      case DriverAction.WriteAssignment(proposal, None) => proposal
      case _ => fail(s"unexpected action: $writeAction1")
    }

    // Simulate a write success.
    // - UpdateAssignmentGeneratorTargetSlicezData: No, this is the initial assignment and load
    //   information is incomplete.
    // - DistributeAssignment: Yes.
    val writeCommitOutput30Sec: Output = generator.forTest.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.AssignmentWriteComplete(
        Success(
          Store.WriteAssignmentResult
            .Committed(
              proposal1.commit(
                isFrozen = false,
                AssignmentConsistencyMode.Affinity,
                Generation(generatorConfig.storeIncarnation, 10)
              )
            )
        ),
        None
      )
    )
    // now == 30.seconds.
    // nextSlicezDataCheckTime == 35.seconds.
    // nextLoadBalancingTime == 40.seconds.

    assert(writeCommitOutput30Sec.nextTickerTime == clock.tickerTime() + 5.seconds)
    assert(writeCommitOutput30Sec.nextTickerTime == initialTime + 35.seconds)
    // Expect 2 actions: DistributeAssignment + LogAssignment
    assert(writeCommitOutput30Sec.actions.size == 2)
    val distributeAction1 = writeCommitOutput30Sec.actions.head
    assert(distributeAction1.isInstanceOf[DriverAction.DistributeAssignment])
    val logAction1 = writeCommitOutput30Sec.actions(1)
    assert(logAction1.isInstanceOf[DriverAction.LogAssignment])

    // Send a watch request from resource 1 to generate a new assignment.
    val watchOutput30Sec: Output =
      generator.forTest.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.WatchRequest(createSliceletRequest(1, Generation.EMPTY))
      )
    // now == 30.seconds.
    // nextSlicezDataCheckTime == 35.seconds.
    // nextLoadBalancingTime == 40.seconds.

    // Output should only contain a WriteAssignment action for the new assignment. No
    // UpdateAssignmentGeneratorTargetSlicezData since it not next time to check slicez
    // data update yet.
    assert(watchOutput30Sec.actions.size == 1)
    val writeAction2: DriverAction = watchOutput30Sec.actions.head
    val (proposal2, asnGenContext): (ProposedAssignment, AssignmentGenerationContext) =
      writeAction2 match {
        case DriverAction.WriteAssignment(proposal, Some(statsBuilder)) =>
          (proposal, statsBuilder)
        case _ => fail(s"unexpected action: $writeAction1")
      }

    // Simulate a write success.
    // - UpdateAssignmentGeneratorTargetSlicezData for churn ratios: Yes. This one is to
    //   incorporate churn ratios computed based on the previous and new assignment.
    // - UpdateAssignmentGeneratorTargetSlicezData for load: No. The interval is not
    //   reached yet.
    // - DistributeAssignment: Yes.
    val secondWriteCommitOutput30Sec = generator.forTest.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.AssignmentWriteComplete(
        Success(
          Store.WriteAssignmentResult
            .Committed(
              proposal2.commit(
                isFrozen = false,
                AssignmentConsistencyMode.Affinity,
                Generation(generatorConfig.storeIncarnation, 11)
              )
            )
        ),
        Some(asnGenContext)
      )
    )
    // now == 30.seconds.
    // nextSlicezDataCheckTime == 35.seconds.
    // nextLoadBalancingTime == 40.seconds.
    assert(secondWriteCommitOutput30Sec.nextTickerTime == clock.tickerTime() + 5.seconds)
    assert(secondWriteCommitOutput30Sec.nextTickerTime == initialTime + 35.seconds)
    // Expect 3 actions:
    // UpdateAssignmentGeneratorTargetSlicezData + DistributeAssignment + LogAssignment
    assert(secondWriteCommitOutput30Sec.actions.size == 3)

    val updateAction1: DriverAction = secondWriteCommitOutput30Sec.actions.head
    assert(updateAction1.isInstanceOf[DriverAction.UpdateAssignmentGeneratorTargetSlicezData])
    val distributeAction2 = secondWriteCommitOutput30Sec.actions(1)
    assert(distributeAction2.isInstanceOf[DriverAction.DistributeAssignment])
    val logAction2 = secondWriteCommitOutput30Sec.actions(2)
    assert(logAction2.isInstanceOf[DriverAction.LogAssignment])

    // Advance clock beyond the next scheduled [[GeneratorTargetSlicezData]] update time.
    // Output should contain an UpdateAssignmentGeneratorTargetSlicezData. This one is to
    // incorporate load information. This will also schedule the next time for
    // `onAdvanceInternal`, which is 5 secs later.
    clock.advanceBy(5.seconds)
    assert(clock.tickerTime() == initialTime + 35.seconds)
    val advanceOutput35Sec: Output =
      generator.forTest.onAdvance(clock.tickerTime(), clock.instant())
    // now == 35.seconds.
    // nextSlicezDataCheckTime = 40.seconds.
    // nextLoadBalancingTime == 40.seconds.
    assert(advanceOutput35Sec.nextTickerTime == clock.tickerTime() + 5.seconds)
    assert(advanceOutput35Sec.nextTickerTime == initialTime + 40.seconds)
    assert(advanceOutput35Sec.actions.size == 1)
    val updateAction3 = advanceOutput35Sec.actions.head
    assert(updateAction3.isInstanceOf[DriverAction.UpdateAssignmentGeneratorTargetSlicezData])

    // Advance clock, not exceed the next scheduled `onAdvanceInternal` time.
    // Output should contain no action.
    clock.advanceBy(2.seconds)
    assert(clock.tickerTime() == initialTime + 37.seconds)
    // now == 37.seconds.
    // nextSlicezDataCheckTime = 40.seconds.
    // nextLoadBalancingTime == 40.seconds.
    val advanceOutput37Sec: Output =
      generator.forTest.onAdvance(clock.tickerTime(), clock.instant())
    assert(advanceOutput37Sec.actions.isEmpty)
    assert(advanceOutput37Sec.nextTickerTime == clock.tickerTime() + 3.seconds)
    assert(advanceOutput37Sec.nextTickerTime == initialTime + 40.seconds)

    // Advance clock beyond the next scheduled slicez update time and load balancing time, which
    // should request both slicez update and assignment write. It should also request another
    // advance call in 5 seconds.
    clock.advanceBy(3.seconds)
    assert(clock.tickerTime() == initialTime + 40.seconds)
    val advanceOutput40Sec: Output =
      generator.forTest.onAdvance(clock.tickerTime(), clock.instant())
    // now == 40.seconds.
    // nextSlicezDataCheckTime = 45.seconds.
    // nextLoadBalancingTime == 50.seconds.
    assert(advanceOutput40Sec.nextTickerTime == clock.tickerTime() + 5.seconds)
    assert(advanceOutput40Sec.nextTickerTime == initialTime + 45.seconds)
    assert(advanceOutput40Sec.actions.size == 2)
    assert(advanceOutput40Sec.actions.exists { action: DriverAction =>
      action.isInstanceOf[DriverAction.UpdateAssignmentGeneratorTargetSlicezData]
    })
    assert(advanceOutput40Sec.actions.exists { action: DriverAction =>
      action.isInstanceOf[DriverAction.WriteAssignment]
    })
  }

  /** Waits for the slicez data to reflect stats from the latest assignment. */
  private def awaitGeneratorTargetSlicezData(
      driver: AssignmentGeneratorDriver,
      assignment: Assignment): Unit = {
    AssertionWaiter(
      "wait for [[GeneratorTargetSlicezData]] to be updated",
      timeout = 2.seconds
    ).await {
      // Await the result of the future.
      val generatorTargetSlicezDataFuture: Future[GeneratorTargetSlicezData] =
        driver.getGeneratorTargetSlicezData
      val generatorTargetSlicezData: GeneratorTargetSlicezData =
        TestUtils.awaitResult(generatorTargetSlicezDataFuture, Duration.Inf)

      assert(generatorTargetSlicezData.reportedLoadPerSliceOpt.nonEmpty)
      assert(generatorTargetSlicezData.reportedLoadPerResourceOpt.nonEmpty)
      assertResult(assignment.assignedResources.size)(
        generatorTargetSlicezData.reportedLoadPerResourceOpt.get.size
      )
      assertResult(assignment.sliceAssignments.size)(
        generatorTargetSlicezData.reportedLoadPerSliceOpt.get.size
      )
    }
  }

  test("[[GeneratorTargetSlicezData]] correctly updated in the driver") {
    // Test plan: manually create resources and driver events, check if
    // [[GeneratorTargetSlicezData]] is correctly updated.
    val driver: AssignmentGeneratorDriver = createDriver(
      InternalTargetConfig.forTest.DEFAULT.copy(
        loadBalancingConfig = LoadBalancingConfig(
          loadBalancingInterval = 10.seconds,
          ChurnConfig.DEFAULT,
          LoadBalancingMetricConfig(1000, ImbalanceToleranceHintP.DEFAULT)
        )
      )
    )

    // Send watch requests from resources 0 and 1.
    sec.advanceBySync(15.seconds)
    driver.onWatchRequest(createSliceletRequest(0, Generation.EMPTY))
    driver.onWatchRequest(createSliceletRequest(1, Generation.EMPTY))

    // Advance past initialization delay to allow the initial assignment to be generated.
    sec.advanceBySync(15.seconds)

    // Obtain the latest assignment.
    val assignment1 = awaitNewerAssignment(driver, Generation.EMPTY)

    // Since there's no previous assignment, no Assignment stats should exist.

    val startTime: Instant = sec.getClock.instant()
    val window: (Instant, Instant) = (
      startTime,
      // Reports with window shorter than config.minDuration will be discarded, so here we
      // set windowHighExclusive to something greater than config.minDuration.
      startTime
        .plusSeconds(defaultTargetConfig.loadWatcherConfig.minDuration.toSeconds)
        .plusSeconds(10)
    )

    // Send two subsequent watch requests with existing resources, reporting load information.
    driver.onWatchRequest(createSliceletRequestWithAttributedLoads(0, assignment1, window))
    driver.onWatchRequest(createSliceletRequestWithAttributedLoads(1, assignment1, window))

    // Advance clock pass the next `onAdvanceInternal` time, so that stats is updated.
    // The next `onAdvanceInternal` event is scheduled to 5 secs later.
    sec.advanceBySync(5.seconds)
    assertResult(expected = 2)(assignment1.assignedResources.size)
    awaitGeneratorTargetSlicezData(driver, assignment1)

    // Send a subsequent watch request with additional resource.
    driver.onWatchRequest(createSliceletRequest(2, assignment1.generation))

    // Obtain the latest assignment. This new assignment generation should trigger the update of
    // the [[GeneratorTargetSlicezData]].
    val assignment2 = awaitNewerAssignment(driver, assignment1.generation)

    // Advance clock pass the next `onAdvanceInternal` time, so that stats is updated.
    // The next `onAdvanceInternal` event is scheduled to 5 secs later.
    // Check if AssignmentGenerationStats contains stats for the additional resources.
    sec.advanceBySync(5.seconds)
    assertResult(expected = 3)(assignment2.assignedResources.size)
    awaitGeneratorTargetSlicezData(driver, assignment2)

    // Simulate a pod removal.
    fakeKubernetesTargetWatcherFactory.simulateTerminationSignal(
      kubernetesWatchTarget,
      resource(0).resourceUuid
    )

    // Obtain the latest assignment. This new assignment generation should trigger the update of
    // the [[GeneratorTargetSlicezData]].
    val assignment3 = awaitNewerAssignment(driver, assignment2.generation)

    // Advance clock pass the next `onAdvanceInternal` time, so that stats is updated.
    sec.advanceBySync(5.seconds)
    assertResult(expected = 2)(assignment3.assignedResources.size)
    awaitGeneratorTargetSlicezData(driver, assignment3)
  }

  test("Assignment generator stops generating assignments after shutdown") {
    // Test plan: Create a generator with an active resource and allow an initial assignment to be
    // written. Send a shutdown event to the generator. Change the available resources and verify
    // that the assignment is not updated.
    val driver: AssignmentGeneratorDriver = createDriver()

    // Make resource 0 active and get the initial assignment.
    sec.advanceBySync(20.seconds)
    driver.onWatchRequest(createSliceletRequest(0, Generation.EMPTY))
    sec.advanceBySync(10.seconds)
    val assignment: Assignment = awaitNewerAssignment(driver, Generation.EMPTY)

    driver.shutdown()

    // Change the available resources and verify that the assignment does not change.
    driver.onWatchRequest(createSliceletRequest(1, Generation.EMPTY))
    sec.advanceBySync(10.seconds)

    val assignment2 = awaitNewerAssignment(driver, Generation.EMPTY)
    assert(assignment2 == assignment)
  }

  test("Generator shutdown stops watchers and advance calls") {
    // Test plan: Create a generator and send the shutdown event. Verify that the output actions
    // include both `StopKubernetesTargetWatcher` and `StopStoreWatcher` and that the nextTickerTime
    // is never (`TickerTime.MAX`),
    val clock = new FakeTypedClock
    val targetConfig: InternalTargetConfig = defaultTargetConfig
    val generator: AssignmentGenerator =
      createGenerator(createGeneratorConfig(), targetConfig, clock)

    val shutdownOutput: Output =
      generator.forTest.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.Shutdown()
      )

    assert(shutdownOutput.actions.contains(DriverAction.StopKubernetesTargetWatcher()))
    assert(shutdownOutput.actions.contains(DriverAction.StopStoreWatcher()))
    assert(
      shutdownOutput.nextTickerTime == TickerTime.MAX,
      "Shutdown should not schedule another onAdvance call"
    )
  }

  test("Generator shutdown clears gauge metrics") {
    // Test plan: Create a generator, let it generate some assignments, and then send a shutdown
    // event. Verify that the gauge metrics for the target are cleared after the corresponding
    // generator is stopped, while the metrics for other targets are unaffected.
    val targetConfig: InternalTargetConfig = defaultTargetConfig
    val target1: Target = Target(getSuffixedSafeName("-1"))
    val target2: Target = Target(getSuffixedSafeName("-2"))

    val (driver1, store1): (AssignmentGeneratorDriver, InterceptableStore) =
      createDriverAndStore(targetConfig, target = target1)
    // driver2 and driver1 should have the same store incarnation.
    val driver2: AssignmentGeneratorDriver =
      createDriver(targetConfig, Some(store1.storeIncarnation), target = target2)

    val kubernetesWatchTarget1: KubernetesWatchTarget =
      KubernetesWatchTarget(target1.getKubernetesAppName, namespace = DEFAULT_K8S_NAMESPACE)

    // Deliver heartbeats and advance past initial health report delay to allow the initial
    // assignment to be generated for the resources.
    sec.advanceBySync(20.seconds)
    val request1a = createSliceletRequestForTarget(0, Generation.EMPTY, target = target1)
    val request1b = createSliceletRequestForTarget(1, Generation.EMPTY, target = target1)
    driver1.onWatchRequest(request1a)
    driver1.onWatchRequest(request1b)

    // Await the assignment for `target1`.
    sec.advanceBySync(10.seconds)
    val assignment1: Assignment = awaitNewerAssignment(driver1, Generation.EMPTY)

    val request2a = createSliceletRequestForTarget(2, Generation.EMPTY, target = target2)
    val request2b = createSliceletRequestForTarget(3, Generation.EMPTY, target = target2)
    driver2.onWatchRequest(request2a)
    // Wait for the assignment for `target2`. We do this for each new assignment to ensure that the
    // metrics get updated for `target2`.
    val assignment2a: Assignment = awaitNewerAssignment(driver2, Generation.EMPTY)
    driver2.onWatchRequest(request2b)
    awaitNewerAssignment(driver2, assignment2a.generation)

    // Deliver a termination signal for resource 0.
    sec.advanceBySync(1.second)
    fakeKubernetesTargetWatcherFactory.simulateTerminationSignal(
      kubernetesWatchTarget1,
      resource(0).resourceUuid
    )
    // Await the assignments.
    awaitNewerAssignment(driver1, assignment1.generation)

    // Verify that the gauge metrics are updated for target1.
    AssertionWaiter("gauge metrics are updated for target1").await {
      assert(TargetMetricsUtils.getNumActiveGenerators(target1) == 1)
      assert(TargetMetricsUtils.getPodSetSize(target1, "Running") > 0)
      assert(TargetMetricsUtils.getPodSetSize(target1, "Terminating") > 0)
      assert(TargetMetricsUtils.getNumAssignmentSlices(target1) > 0)
      assert(
        getLatestWrittenGenerationMetric(target1, isMeaningful = true) > 0 ||
        getLatestWrittenGenerationMetric(target1, isMeaningful = false) > 0
      )
      assert(getLatestKnownGenerationMetric(target1) > 0)
      assert(TargetMetricsUtils.getSliceReplicaCountDistribution(target1).size > 0)
    }

    // Send a shutdown event.
    driver1.shutdown()

    // Verify that the gauge metrics are cleared for `target1`.
    AssertionWaiter("gauge metrics are cleared for target1").await {
      assert(TargetMetricsUtils.getNumActiveGenerators(target1) == 0)
      assert(TargetMetricsUtils.getNumAssignmentSlices(target1) == 0)
      assert(TargetMetricsUtils.getPodSetSize(target1, "Running") == 0)
      assert(TargetMetricsUtils.getPodSetSize(target1, "Terminating") == 0)
      assert(getLatestKnownGenerationMetric(target1) == 0)
      assert(
        getLatestWrittenGenerationMetric(target1, isMeaningful = true) == 0 &&
        getLatestWrittenGenerationMetric(target1, isMeaningful = false) == 0
      )
      assert(getLatestWrittenIncarnationMetric(target1) == 0)
      assert(getLatestKnownGenerationMetric(target1) == 0)
      assert(TargetMetricsUtils.getSliceReplicaCountDistribution(target1).size == 0)
    }
    // Verify that the gauge metrics are not cleared for `target2`.
    AssertionWaiter("gauge metrics are not cleared for target2").await {
      assert(TargetMetricsUtils.getNumActiveGenerators(target2) == 1)
      assert(TargetMetricsUtils.getNumAssignmentSlices(target2) > 0)
      assert(TargetMetricsUtils.getPodSetSize(target2, "Running") > 0)
      assert(
        getLatestWrittenGenerationMetric(target2, isMeaningful = true) > 0 ||
        getLatestWrittenGenerationMetric(target2, isMeaningful = false) > 0
      )
      assert(getLatestKnownGenerationMetric(target2) > 0)
      assert(TargetMetricsUtils.getSliceReplicaCountDistribution(target2).size > 0)
    }
    driver2.shutdown()
  }

  test("Generator shutdown idempotent") {
    // Test plan: verify that shutting down an already shut-down generator is idempotent by
    // interacting with the state machine and ensuring that the second shutdown requests no further
    // actions from the driver.
    val clock = RealtimeTypedClock
    val generator: AssignmentGenerator =
      createGenerator(createGeneratorConfig(), defaultTargetConfig, clock)

    // Don't inspect the outputs from the first shutdown event; the side effects here are tested
    // separately above.
    generator.forTest.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.Shutdown()
    )

    // We shouldn't get anything back from any future shutdown events.
    for (_ <- 0 until 3) {
      assert(
        generator.forTest
          .onEvent(
            clock.tickerTime(),
            clock.instant(),
            Event.Shutdown()
          )
          .actions
          .isEmpty
      )
    }
  }

  test("Termination removes resource") {
    // Test plan: Verify that a resource is removed from the assignment when a termination signal is
    // received. Do this by creating 2 resources, verifying a valid assignment is generated,
    // then marking one as terminating, and verifying a new assignment without it is generated.
    val targetConfig: InternalTargetConfig = defaultTargetConfig
    val driver: AssignmentGeneratorDriver = createDriver(targetConfig)

    // Deliver heartbeats and advance past initial health report delay to allow the initial
    // assignment to be generated for the resources.
    sec.advanceBySync(20.seconds)
    val request1 = createSliceletRequest(0, Generation.EMPTY)
    val request2 = createSliceletRequest(1, Generation.EMPTY)
    driver.onWatchRequest(request1)
    driver.onWatchRequest(request2)
    // Do this on the sec to ensure the clock advancement doesn't race with the heartbeats.
    sec.advanceBySync(10.seconds)

    // Verify the generated assignment.
    val assignment1: Assignment = awaitNewerAssignment(driver, Generation.EMPTY)
    assert(assignment1.assignedResources == Set(resource(0), resource(1)))

    // Now deliver a termination signal for resource 0.
    sec.advanceBySync(1.second)
    fakeKubernetesTargetWatcherFactory.simulateTerminationSignal(
      kubernetesWatchTarget,
      resource(0).resourceUuid
    )
    // Verify a new assignment is generated with resource 0 removed.
    val assignment2: Assignment = awaitNewerAssignment(driver, assignment1.generation)
    assert(
      TargetMetricsUtils
        .getAssignmentGenerationGenerateDecisions(defaultTarget, GenerateReason.HealthChange) == 1
    )
    assert(assignment2.assignedResources == Set(resource(1)))
  }

  test("Assignment sync from slicelet bypasses initialization") {
    // Test plan: Verify that the generator bypasses the initialization delay and becomes active
    // when it learns of an existing assignment from a slicelet. Verify this by delivering an
    // assignment to the generator via store and verifying no new assignment generated (still in
    // health delay); then delivering a watch request from a slicelet containing this assignment
    // and verifying a newer assignment is generated. Subsequently, informing it of a new resource
    // and also a terminating resource and verifying assignment change, all before one unhealthy
    // timeout period has elapsed, to confirm the initial health delay is bypassed.

    val targetConfig: InternalTargetConfig = defaultTargetConfig
    val (driver, store): (AssignmentGeneratorDriver, Store) = createDriverAndStore(targetConfig)
    val rng = new Random()

    // Inform the generator of an existing assignment to resources 0, 1, 2, by first injecting it
    // into the store and then injecting it into a watch request from slicelet and supplying the
    // request to the assignment generator.

    // We first inject it to the store so that:
    // - We verify the assignment sync-ed from store doesn't bypass initialization delay.
    // - When using etcd store, we need to maintain the invariance that the etcd store knows the
    //   assignment with highest generation; otherwise the assigner cannot write to store because of
    //   the occ check.

    // Deliver it to the generator 1 second into the generator's life.
    sec.advanceBySync(1.second)
    val informedAssignment = ProposedAssignment(
      predecessorOpt = None,
      createRandomProposal(
        10,
        Vector(resource(0), resource(1), resource(2)),
        numMaxReplicas = 3,
        rng
      )
    )
    val writeResult: WriteAssignmentResult = TestUtils.awaitResult(
      store.writeAssignment(
        defaultTarget,
        shouldFreeze = false,
        informedAssignment
      ),
      Duration.Inf
    )

    // Verify the generator gets and distributes the assignment, but does not generate a new one
    // atop it as the generator is still within the initial health delay.
    val initialAssignment: Assignment = writeResult match {
      case WriteAssignmentResult.Committed(assignment: Assignment) => assignment
      case other => throw new AssertionError(f"Write failed: $other")
    }
    assertNoNewerAssignment(driver, initialAssignment.generation)

    // Inform the assigner about the initial assignment via slicelet.
    driver.onWatchRequest(
      createSliceletRequest(0, knownAssignment = initialAssignment)
    )
    // Newer assignment should be generated as the watch request from slicelet bypasses the
    // initial health delay.
    val updatedAssignment1: Assignment =
      awaitNewerAssignment(driver, initialAssignment.generation)
    // The resource health information in initialAssignment is used to generate new assignment.
    assert(updatedAssignment1.assignedResources == Set(resource(0), resource(1), resource(2)))

    // Inform the generator of a new resource, resource 3, and verify that the generator reacts by
    // including it in the assignment.
    sec.advanceBySync(1.second)
    driver.onWatchRequest(
      createSliceletRequest(3, SyncAssignmentState.KnownGeneration(Generation.EMPTY))
    )
    val updatedAssignment2: Assignment =
      awaitNewerAssignment(driver, updatedAssignment1.generation)
    assert(
      updatedAssignment2.assignedResources == Set(
        resource(0),
        resource(1),
        resource(2),
        resource(3)
      )
    )

    // Now deliver a termination signal for resource 0.
    sec.advanceBySync(1.second)
    fakeKubernetesTargetWatcherFactory.simulateTerminationSignal(
      kubernetesWatchTarget,
      resource(0).resourceUuid
    )
    // Verify that the generator reacts to the termination signal by removing resource 0 from the
    // assignment.
    val updatedAssignment3: Assignment =
      awaitNewerAssignment(driver, updatedAssignment2.generation)
    assert(
      updatedAssignment3.assignedResources == Set(resource(1), resource(2), resource(3))
    )
  }

  test("Known prior incarnation assignment from slicelet bypasses initialization delay") {
    // Test plan: Verify that the generator bypasses the initialization delay when it learns
    // of an existing assignment from even a prior incarnation to the assignment generator. This is
    // because the bootstrapping is based on the heuristic assumption that the Slicelets know fresh
    // assignment, and we believe this is true even if the Slicelet knows a prior incarnation. (e.g.
    // when we have just bumped the assigner's incarnation, the Slicelets only know a lower
    // incarnation but should still contain relatively fresh resource information.)

    val priorIncarnation = Incarnation(12)
    val currentIncarnation = createLargerValidIncarnation(priorIncarnation)
    val targetConfig: InternalTargetConfig = defaultTargetConfig
    val (driver, _): (AssignmentGeneratorDriver, Store) =
      createDriverAndStore(targetConfig, Some(currentIncarnation))
    val rng = new Random()

    sec.advanceBySync(1.second)
    val priorIncarnationAssignment: Assignment = ProposedAssignment(
      predecessorOpt = None,
      createRandomProposal(
        10,
        Vector(resource(0), resource(1), resource(2)),
        numMaxReplicas = 3,
        rng
      )
    ).commit(
      isFrozen = false,
      AssignmentConsistencyMode.Affinity,
      Generation(priorIncarnation, 42)
    )
    driver.onWatchRequest(
      createSliceletRequest(
        3,
        SyncAssignmentState.KnownAssignment(priorIncarnationAssignment.toDiff(Generation.EMPTY))
      )
    )
    val updatedAssignment: Assignment =
      awaitNewerAssignment(driver, priorIncarnationAssignment.generation)
    assert(updatedAssignment.generation.incarnation == currentIncarnation)
    assert(
      updatedAssignment.assignedResources == Set(
        resource(0),
        resource(1),
        resource(2),
        resource(3)
      )
    )
  }

  test("Stale assignment from clerk doesn't pollute initial assignment generation") {
    // Test plan: Verify that an assignment sync-ed from clerk doesn't pollute the assignment
    // generator's health bootstrapping. As of Feb 2025 there's no direct assignment synchronization
    // between clerk and dicer-assigner in production, but watch request from clerk is a legal
    // input for assignment generator, so we add this extra verification.

    val targetConfig: InternalTargetConfig = defaultTargetConfig
    val (driver, store): (AssignmentGeneratorDriver, Store) = createDriverAndStore(targetConfig)
    val rng = new Random()

    // Write a stale assignment to store, and let it be sync-ed from a clerk to assignment
    // generator.
    sec.advanceBySync(1.second)
    val staleAssignment: Assignment = TestUtils.awaitResult(
      store
        .writeAssignment(
          defaultTarget,
          shouldFreeze = false,
          ProposedAssignment(
            predecessorOpt = None,
            createRandomProposal(
              10,
              Vector(resource(0), resource(1), resource(2)),
              numMaxReplicas = 3,
              rng
            )
          )
        ),
      Duration.Inf
    ) match {
      case WriteAssignmentResult.Committed(assignment: Assignment) => assignment
      case other => throw new AssertionError(f"Write failed: $other")
    }
    driver.onWatchRequest(
      createClerkRequest(knownAssignment = staleAssignment)
    )
    // No assignment should be generated.
    assertNoNewerAssignment(driver, staleAssignment.generation)

    // Write a fresh assignment to store, and let it be sync-ed from slicelet to assignment
    // generator.
    val freshAssignment: Assignment = TestUtils.awaitResult(
      store
        .writeAssignment(
          defaultTarget,
          shouldFreeze = false,
          ProposedAssignment(
            predecessorOpt = Some(staleAssignment),
            createRandomProposal(
              10,
              Vector(resource(3), resource(4), resource(5)),
              numMaxReplicas = 3,
              rng
            )
          )
        ),
      Duration.Inf
    ) match {
      case WriteAssignmentResult.Committed(assignment: Assignment) => assignment
      case other => throw new AssertionError(f"Write failed: $other")
    }
    driver.onWatchRequest(createSliceletRequest(3, knownAssignment = freshAssignment))
    // There should be new assignment generated, containing only the resources in the fresh
    // assignment.
    val updatedAssignment1: Assignment =
      awaitNewerAssignment(driver, freshAssignment.generation)
    assert(updatedAssignment1.assignedResources == Set(resource(3), resource(4), resource(5)))
  }

  test("Fresh assignment in store can be used to bootstrap health") {
    // Test plan: Verify that if the store knows a fresher assignment than a watch request from the
    // slicelet, the fresher assignment will be used to bootstrap health.

    val rng = new Random()
    val store = createStore(sec, createLargerValidIncarnation(Incarnation(41)))

    // Prepare a stale and a fresh assignment. Write them all to store to avoid occ check failure.
    val staleAssignment: Assignment = TestUtils.awaitResult(
      store
        .writeAssignment(
          defaultTarget,
          shouldFreeze = false,
          ProposedAssignment(
            predecessorOpt = None,
            createRandomProposal(
              10,
              Vector(resource(0), resource(1), resource(2)),
              numMaxReplicas = 3,
              rng
            )
          )
        ),
      Duration.Inf
    ) match {
      case WriteAssignmentResult.Committed(assignment: Assignment) => assignment
      case other => throw new AssertionError(f"Write failed: $other")
    }
    val freshAssignment: Assignment = TestUtils.awaitResult(
      store
        .writeAssignment(
          defaultTarget,
          shouldFreeze = false,
          ProposedAssignment(
            predecessorOpt = Some(staleAssignment),
            createRandomProposal(
              10,
              Vector(resource(3), resource(4), resource(5)),
              numMaxReplicas = 3,
              rng
            )
          )
        ),
      Duration.Inf
    ) match {
      case WriteAssignmentResult.Committed(assignment: Assignment) => assignment
      case other => throw new AssertionError(f"Write failed: $other")
    }

    val driver: AssignmentGeneratorDriver =
      createDriverUsingStore(store, defaultTargetConfig, defaultTarget)
    // No assignment should be generated.
    assertNoNewerAssignment(driver, freshAssignment.generation)

    // Watch request from slicelet containing the stale assignment. It should trigger the health
    // bootstrapping, but the actual resources used to bootstrap health should be from the fresh
    // assignment.
    driver.onWatchRequest(createSliceletRequest(0, knownAssignment = staleAssignment))
    val updatedAssignment1: Assignment =
      awaitNewerAssignment(driver, freshAssignment.generation)
    assert(
      updatedAssignment1.assignedResources == Set(
        // The slicelet sending the watch request.
        resource(0),
        // Resources from the fresh assignment.
        resource(3),
        resource(4),
        resource(5)
      )
    )
  }

  test("Higher incarnation assignment is distributed") {
    // Test plan: Verify that the generator distributes an assignment from a higher incarnation
    // when synced from a subscriber.
    val targetConfig: InternalTargetConfig = defaultTargetConfig
    val incarnation: Incarnation = createLargerValidIncarnation(Incarnation.MIN)
    val driver: AssignmentGeneratorDriver =
      createDriver(targetConfig, storeIncarnationOpt = Some(incarnation))
    val higherIncarnation: Incarnation = createLargerValidIncarnation(incarnation)
    val higherIncarnationAssignment: Assignment = ProposedAssignment(
      predecessorOpt = None,
      createRandomProposal(
        10,
        IndexedSeq(resource(0), resource(1)),
        numMaxReplicas = 2,
        new Random()
      )
    ).commit(
      isFrozen = false,
      AssignmentConsistencyMode.Affinity,
      Generation(higherIncarnation, 42)
    )

    driver.onWatchRequest(createClerkRequest(higherIncarnationAssignment))

    assert(awaitNewerAssignment(driver, Generation.EMPTY) == higherIncarnationAssignment)
  }

  test("Known higher incarnation assignment does not request immediate onAdvance") {
    // Test plan: Verify that when the generator knows of a higher incarnation assignment, it does
    // not repeatedly request immediate onAdvance calls to attempt load balancing since it's unable
    // to create a higher generation assignment.
    val clock = new FakeTypedClock
    val targetConfig: InternalTargetConfig = defaultTargetConfig
    val config: AssignmentGenerator.Config = createGeneratorConfig()
    val generator: AssignmentGenerator = createGenerator(config, targetConfig, clock)

    // First, advance the generator past the initial health delay and "complete" its initial write
    // Otherwise, it'll be unwilling to generate assignments because it lacks health information or
    // has an inflight write rather than knowing of a higher incarnation assignment.
    clock.advanceBy(UNHEALTHY_TIMEOUT_PERIOD)
    val initialWriteRequest: DriverAction.WriteAssignment = generator.forTest
      .onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.WatchRequest(createSliceletRequest(0, Generation.EMPTY))
      )
      .actions
      .filter { action: DriverAction =>
        action.isInstanceOf[DriverAction.WriteAssignment]
      } match {
      case Seq(writeAction: DriverAction.WriteAssignment) => writeAction
      case other => fail(s"0 or multiple write requests: $other")
    }
    generator.forTest.onEvent(
      clock.tickerTime(),
      clock.instant(),
      Event.AssignmentWriteComplete(
        Success(
          Store.WriteAssignmentResult.Committed(
            initialWriteRequest.proposal.commit(
              isFrozen = false,
              AssignmentConsistencyMode.Affinity,
              Generation(config.storeIncarnation, 42)
            )
          )
        ),
        initialWriteRequest.contextOpt
      )
    )

    // Inform the generator of a higher incarnation assignment, which should make it unwilling
    // to generate any more assignments due to its lower incarnation.
    val higherIncarnation: Incarnation = createLargerValidIncarnation(config.storeIncarnation)
    val higherIncarnationAssignment: Assignment = ProposedAssignment(
      predecessorOpt = None,
      createRandomProposal(
        10,
        IndexedSeq(resource(0), resource(1)),
        numMaxReplicas = 2,
        new Random()
      )
    ).commit(
      isFrozen = false,
      AssignmentConsistencyMode.Affinity,
      Generation(higherIncarnation, 42)
    )
    val watchOutput: Output =
      generator.forTest.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.WatchRequest(createSliceletRequest(0, higherIncarnationAssignment))
      )
    clock.advanceBy(watchOutput.nextTickerTime - clock.tickerTime())
    assert(
      TargetMetricsUtils.getAssignmentGenerationSkipDecisions(
        defaultTarget,
        SkipReason.KnownHigherIncarnationAssignment
      )
      == 1
    )

    for (i <- 0 until 10) {
      // Ensure that the state machine asks for a callback strictly in the future rather than
      // immediately (or even in the past) in a sequence of onEvent calls (we expect that the
      // state machine still wants callbacks to be able to update its slicez data). We use
      // WatchRequests to do this to keep resource 0 alive; otherwise, the generator wouldn't
      // attempt load balancing at all due to lack of healthy resources even if it didn't know of
      // a higher incarnation assignment.
      //
      // This idea may be useful as a general testing utility to check (under certain conditions)
      // that state machines don't enter a tight loop of requesting onAdvance calls.
      val output: Output = generator.forTest.onEvent(
        clock.tickerTime(),
        clock.instant(),
        Event.WatchRequest(createSliceletRequest(0, Generation.EMPTY))
      )
      assert(output.nextTickerTime > clock.tickerTime())
      clock.advanceBy(output.nextTickerTime - clock.tickerTime())
      assert(
        TargetMetricsUtils.getAssignmentGenerationSkipDecisions(
          defaultTarget,
          SkipReason.KnownHigherIncarnationAssignment
        )
        == i + 2
      )
    }
  }

  test("State transfer information is generated") {
    // Test plan: Verify that AssignmentGenerator generates assignments with state transfer
    // information. Verify this by creating a driver based on the given config, generating an
    // initial assignment, then causing some reassignment by adding another resource. Verify that
    // state transfer information is generated. Note this test is for checking the plumbing of state
    // transfer information from ProposedAssignment to the generator, and the main tests to verify
    // the correctness of state transfer information is in
    // com.databricks.dicer.assigner.ProposedAssignmentSuite.

    val driver: AssignmentGeneratorDriver = createDriver(defaultTargetConfig)
    // Send watch requests from resources 0.
    sec.advanceBySync(15.seconds)
    driver.onWatchRequest(createSliceletRequest(0, Generation.EMPTY))

    // Advance past initialization delay to allow the initial assignment to be generated.
    sec.advanceBySync(15.seconds)
    // Wait for the initial assignment.
    val assignment1: Assignment = awaitNewerAssignment(driver, Generation.EMPTY)

    // Add another resource.
    driver.onWatchRequest(createSliceletRequest(1, Generation.EMPTY))
    // Wait for the new assignment.
    val assignment2: Assignment = awaitNewerAssignment(driver, assignment1.generation)

    assert(TestSliceUtils.hasStateTransfers(assignment2))
  }

  test("Metrics with isMeaningfulAssignmentChange are set correctly") {
    // Test plan: Verify that the number of assignment writes and generation number metric are
    // updated correctly.
    // Verify this by creating two slicelets and an initial assignment, reporting load uniformly so
    // that there is an assignment write but the slicemap does not change, reporting
    // heavily skewed load so that an assignment change is generated, and verify the metrics are
    // correct between each step.
    val loadBalancingInterval: FiniteDuration = 5.seconds
    val targetConfig: InternalTargetConfig = defaultTargetConfig.copy(
      loadBalancingConfig = LoadBalancingConfig(
        loadBalancingInterval,
        ChurnConfig.DEFAULT,
        LoadBalancingMetricConfig(maxLoadHint = 100, ImbalanceToleranceHintP.DEFAULT)
      )
    )

    val driver: AssignmentGeneratorDriver = createDriver(targetConfig)

    val callback = new LoggingStreamCallback[Assignment](sec)
    driver.getGeneratorCell.watch(callback)

    // Generate initial assignment.
    val initialNumMeaningfulAssignmentWrites: Long =
      TargetMetricsUtils.getNumAssignmentWrites(defaultTarget, Code.OK, isMeaningful = true)
    val initialNumNonMeaningfulAssignmentWrites: Long =
      TargetMetricsUtils.getNumAssignmentWrites(defaultTarget, Code.OK, isMeaningful = false)

    // Deliver heartbeats and advance past initial health report delay to allow the initial
    // assignment to be generated for the resources.
    sec.advanceBySync(20.seconds)
    driver.onWatchRequest(createSliceletRequest(0, Generation.EMPTY))
    driver.onWatchRequest(createSliceletRequest(1, Generation.EMPTY))
    // Do this on the sec to ensure the clock advancement doesn't race with the heartbeats.
    sec.advanceBySync(10.seconds)

    val initialAssignment: Assignment = awaitNewerAssignment(driver, Generation.EMPTY)
    var latestWrittenMeaningfulGeneration = 0L
    var latestWrittenNonMeaningfulGeneration = 0L
    AssertionWaiter("Wait for metrics to update").await {
      val numMeaningfulAssignmentWrites: Long =
        TargetMetricsUtils.getNumAssignmentWrites(defaultTarget, Code.OK, isMeaningful = true)
      val numNonMeaningfulAssignmentWrites: Long =
        TargetMetricsUtils.getNumAssignmentWrites(defaultTarget, Code.OK, isMeaningful = false)
      latestWrittenMeaningfulGeneration =
        getLatestWrittenGenerationMetric(defaultTarget, isMeaningful = true)
      latestWrittenNonMeaningfulGeneration =
        getLatestWrittenGenerationMetric(defaultTarget, isMeaningful = false)

      assert(numMeaningfulAssignmentWrites == initialNumMeaningfulAssignmentWrites + 1)
      assert(numNonMeaningfulAssignmentWrites == initialNumNonMeaningfulAssignmentWrites)
      assert(latestWrittenMeaningfulGeneration == initialAssignment.generation.number.value)
      assert(latestWrittenNonMeaningfulGeneration == 0L)
    }

    // Generate a new assignment with no meaningful change by submitting uniform load reports
    for (resourceId <- Seq(0, 1)) {
      val request = createSliceletRequest(
        resourceId,
        SyncAssignmentState.KnownGeneration(initialAssignment.generation),
        createAttributedLoads(
          initialAssignment,
          resource(resourceId),
          (sec.getClock.instant().minusSeconds(120), sec.getClock.instant())
        )
      )
      driver.onWatchRequest(request)
    }
    sec.advanceBySync(loadBalancingInterval) // Advance to the next write time.

    val lastAssignment = awaitNewerAssignment(driver, initialAssignment.generation)
    var lastNumMeaningfulAssignmentWrites =
      TargetMetricsUtils.getNumAssignmentWrites(defaultTarget, Code.OK, isMeaningful = true)
    var lastNumNonMeaningfulAssignmentWrites =
      TargetMetricsUtils.getNumAssignmentWrites(defaultTarget, Code.OK, isMeaningful = false)
    var lastLatestWrittenMeaningfulGeneration =
      getLatestWrittenGenerationMetric(defaultTarget, isMeaningful = true)
    var lastLatestWrittenNonMeaningfulGeneration =
      getLatestWrittenGenerationMetric(defaultTarget, isMeaningful = false)

    // Reconfirm the slice map did not change.
    assert(convertToSliceMap(lastAssignment) == convertToSliceMap(initialAssignment))

    // Non-meaningful metrics should have been updated while meaningful metrics should not have.
    AssertionWaiter("Wait for metrics to update.").await {
      lastNumNonMeaningfulAssignmentWrites =
        TargetMetricsUtils.getNumAssignmentWrites(defaultTarget, Code.OK, isMeaningful = false)
      lastNumMeaningfulAssignmentWrites =
        TargetMetricsUtils.getNumAssignmentWrites(defaultTarget, Code.OK, isMeaningful = true)
      lastLatestWrittenMeaningfulGeneration =
        getLatestWrittenGenerationMetric(defaultTarget, isMeaningful = true)
      lastLatestWrittenNonMeaningfulGeneration =
        getLatestWrittenGenerationMetric(defaultTarget, isMeaningful = false)

      assert(lastNumMeaningfulAssignmentWrites == initialNumMeaningfulAssignmentWrites + 1)
      assert(lastNumNonMeaningfulAssignmentWrites == initialNumNonMeaningfulAssignmentWrites + 1)
      assert(lastLatestWrittenMeaningfulGeneration == latestWrittenMeaningfulGeneration)
      assert(lastLatestWrittenNonMeaningfulGeneration > latestWrittenNonMeaningfulGeneration)
    }

    // Generate a new assignment with a meaningful change.
    for (resourceId <- Seq(0, 1)) {
      val request = createSliceletRequest(
        resourceId,
        SyncAssignmentState.KnownGeneration(initialAssignment.generation),
        createAttributedLoads(
          initialAssignment,
          squid = resource(resourceId),
          (sec.getClock.instant().minusSeconds(120), sec.getClock.instant()),
          loadMap =
            if (resourceId == 0) LoadMap.UNIFORM_LOAD_MAP.withAddedUniformLoad(10000000)
            else LoadMap.UNIFORM_LOAD_MAP
        )
      )
      driver.onWatchRequest(request)
    }

    sec.advanceBySync(loadBalancingInterval) // Advance to the next write time.
    val finalAssignment = awaitNewerAssignment(driver, lastAssignment.generation)

    var finalNumMeaningfulAssignmentWrites =
      TargetMetricsUtils.getNumAssignmentWrites(defaultTarget, Code.OK, isMeaningful = true)
    var finalNumNonMeaningfulAssignmentWrites =
      TargetMetricsUtils.getNumAssignmentWrites(defaultTarget, Code.OK, isMeaningful = false)
    var finalLatestWrittenMeaningfulGeneration =
      getLatestWrittenGenerationMetric(defaultTarget, isMeaningful = true)
    var finalLatestWrittenNonMeaningfulGeneration =
      getLatestWrittenGenerationMetric(defaultTarget, isMeaningful = false)

    // Reconfirm the slice map changed.
    assert(convertToSliceMap(finalAssignment) != convertToSliceMap(lastAssignment))

    AssertionWaiter("Wait for metrics to update.").await {
      finalNumMeaningfulAssignmentWrites =
        TargetMetricsUtils.getNumAssignmentWrites(defaultTarget, Code.OK, isMeaningful = true)
      finalNumNonMeaningfulAssignmentWrites =
        TargetMetricsUtils.getNumAssignmentWrites(defaultTarget, Code.OK, isMeaningful = false)
      finalLatestWrittenMeaningfulGeneration =
        getLatestWrittenGenerationMetric(defaultTarget, isMeaningful = true)
      finalLatestWrittenNonMeaningfulGeneration =
        getLatestWrittenGenerationMetric(defaultTarget, isMeaningful = false)

      // Meaningful metrics should have been updated while meaningful metrics should not have.
      assert(finalNumMeaningfulAssignmentWrites == lastNumMeaningfulAssignmentWrites + 1)
      assert(finalNumNonMeaningfulAssignmentWrites == lastNumNonMeaningfulAssignmentWrites)
      assert(finalLatestWrittenMeaningfulGeneration > lastLatestWrittenMeaningfulGeneration)
      assert(finalLatestWrittenNonMeaningfulGeneration == lastLatestWrittenNonMeaningfulGeneration)
    }
  }

  test("isMeaningfulAssignment for numAssignmentWrites on failures") {
    // Test plan: Verify that the number of assignment writes is incremented correctly under write
    // failure conditions.
    // Verify this by injecting failure events into the driver: one containing a write "success"
    // with an OCC failure, and then one write failure event with each status code.
    val driver: AssignmentGeneratorDriver = createDriver(defaultTargetConfig)
    assert(
      TargetMetricsUtils
        .getNumAssignmentWrites(defaultTarget, Code.FAILED_PRECONDITION, isMeaningful = true)
      == 0
    )
    assert(
      TargetMetricsUtils
        .getNumAssignmentWrites(defaultTarget, Code.FAILED_PRECONDITION, isMeaningful = false)
      == 0
    )

    // Inject a write "success" with OCC failure response.
    sec.call {
      driver.baseDriver.handleEvent(
        Event.AssignmentWriteComplete(
          Success(WriteAssignmentResult.OccFailure(Generation.EMPTY)),
          None
        )
      )
    }

    AssertionWaiter("Waiting for Failed_Precondition write metric").await {
      assert(
        TargetMetricsUtils.getNumAssignmentWrites(
          defaultTarget,
          Code.FAILED_PRECONDITION,
          isMeaningful = false
        ) == 1
      )
      assert(
        TargetMetricsUtils.getNumAssignmentWrites(
          defaultTarget,
          Code.FAILED_PRECONDITION,
          isMeaningful = true
        ) == 0
      )
    }

    // Inject a write failure for every possible status code.
    for (code <- Code.values()) {
      val initialFailureNumAssignmentWrites =
        TargetMetricsUtils.getNumAssignmentWrites(defaultTarget, code, isMeaningful = false)
      sec.call {
        driver.baseDriver.handleEvent(
          Event.AssignmentWriteComplete(
            result = Failure(Status.fromCodeValue(code.value()).asRuntimeException()),
            contextOpt = None
          )
        )
      }

      AssertionWaiter("Waiting for failure metrics").await {
        // Code.FAILED_PRECONDITION was incremented as a result of the write success with OCC
        // failure above.
        assert(
          TargetMetricsUtils.getNumAssignmentWrites(defaultTarget, code, isMeaningful = false)
          == initialFailureNumAssignmentWrites + 1
        )
        assert(
          TargetMetricsUtils.getNumAssignmentWrites(defaultTarget, code, isMeaningful = true) == 0
        )
      }
    }
  }

  test("Latest known generation metrics are set correctly") {
    // Test plan: Verify that the latest known generation metrics are updated correctly.
    // Verify this by creating a generator and an initial assignment via a watch request from a
    // slicelet and checking that the latest meaningful written generation and the latest known
    // generation are the initial assignment. Then inject a received assignment as if the generator
    // received an assignment from the store, and verify that the latest written generation did not
    // change but the latest known generation did. Then send a watch request with yet another
    // assignment and verifying again that the latest written generation did not change but the
    // latest known generation did.
    val (driver, store): (AssignmentGeneratorDriver, InterceptableStore) = createDriverAndStore()
    val incarnation: Incarnation = store.storeIncarnation
    val rng = new Random()

    // Generate an initial assignment
    // Inject a watch request so that the generator generates an assignment.
    sec.advanceBySync(30.seconds)
    driver.onWatchRequest(createSliceletRequest(0, Generation.EMPTY))
    sec.advanceBySync(30.seconds)
    val initialAssignment: Assignment = awaitNewerAssignment(driver, Generation.EMPTY)

    // Verify the last written assignment and the latest known are the same
    AssertionWaiter(
      s"Waiting for meaningful latest written generation to be $initialAssignment.generation"
    ).await {
      assert(
        getLatestWrittenGenerationMetric(defaultTarget, isMeaningful = true)
        == initialAssignment.generation.number.value
      )
      assert(
        getLatestWrittenIncarnationMetric(defaultTarget)
        == initialAssignment.generation.incarnation.value
      )
    }
    AssertionWaiter(s"Waiting for latest known Generation to be ${initialAssignment.generation}")
      .await {
        assert(
          getLatestKnownGenerationMetric(defaultTarget) ==
          initialAssignment.generation.number.value
        )
        assert(
          getLatestKnownIncarnationMetric(defaultTarget) ==
          initialAssignment.generation.incarnation.value
        )
      }
    assert(
      getLatestWrittenGenerationMetric(defaultTarget, isMeaningful = true) !=
      getLatestWrittenGenerationMetric(defaultTarget, isMeaningful = false)
    )

    // Write an assignment to the store to be picked up by a watch later.
    // Advance by one second so sec.getClock.instant() is different from the previous assignment.
    sec.advanceBySync(1.second)
    val proposedAssignment = ProposedAssignment(
      predecessorOpt = Some(initialAssignment),
      createRandomProposal(
        2,
        IndexedSeq(resource(0), resource(1)),
        numMaxReplicas = 2,
        rng
      )
    )
    // Get the assignment from the proposal to make sure what was committed was what we wrote.
    val newAssignment = proposedAssignment.commit(
      isFrozen = false,
      AssignmentConsistencyMode.Affinity,
      Generation(incarnation, sec.getClock.instant().toEpochMilli)
    )
    TestUtils.awaitResult(
      store.writeAssignment(
        defaultTarget,
        shouldFreeze = false,
        proposedAssignment
      ),
      Duration.Inf
    ) match {
      case WriteAssignmentResult.Committed(assignment: Assignment) =>
        assert(assignment == newAssignment)
      case other => throw new AssertionError(f"Write failed: $other")
    }
    sec.advanceBySync(30.seconds)

    // Verify that latest known gets updated but latest written did not.
    // First wait for the new assignment.
    assert(awaitNewerAssignment(driver, initialAssignment.generation) == newAssignment)
    // Wait for the latest known metric to be equal to the new assignment then ensure that the
    // metric tracking the latest written generation was not updated, also ensure non-meaningful
    // metrics are not updated and the incarnation metric did not change.
    AssertionWaiter(s"Waiting for latest known Generation to be ${newAssignment.generation}")
      .await {
        assert(
          getLatestKnownGenerationMetric(defaultTarget) ==
          newAssignment.generation.number.value
        )
        assert(
          getLatestKnownIncarnationMetric(defaultTarget) ==
          newAssignment.generation.incarnation.value
        )
      }

    // Check the latest written.
    assert(
      getLatestWrittenGenerationMetric(defaultTarget, isMeaningful = true) ==
      initialAssignment.generation.number.value
    )
    assert(
      getLatestWrittenGenerationMetric(defaultTarget, isMeaningful = false) !=
      initialAssignment.generation.number.value
    )
    assert(
      getLatestWrittenGenerationMetric(defaultTarget, isMeaningful = false) !=
      newAssignment.generation.number.value
    )
    assert(
      getLatestWrittenIncarnationMetric(defaultTarget) ==
      initialAssignment.generation.incarnation.value
    )

    // Prevent later assignments from being written by the store so that the Assigner is not able to
    // commit a new assignment in response to the watch request below. This is so we can check
    // equivalence of metric and generation.
    sec.run {
      store.blockAssignmentWrites(defaultTarget)
    }

    // Now inform the assigner of a new assignment via a watch request.
    // Note: this assignment is created out-of-band and given to the Assigner as if the slicelet
    // received it from some trusted source but it did not. This make the Assigner and store state
    // inconsistent. Be careful adding code to the end of this test.
    val finalAssignment: Assignment = ProposedAssignment(
      Some(newAssignment),
      createRandomProposal(
        2,
        IndexedSeq(resource(0), resource(1)),
        numMaxReplicas = 2,
        rng
      )
    ).commit(
      isFrozen = false,
      AssignmentConsistencyMode.Affinity,
      Generation(incarnation, sec.getClock.instant().toEpochMilli)
    )
    driver.onWatchRequest(
      createSliceletRequest(0, finalAssignment)
    )
    sec.advanceBySync(30.seconds)

    // Verify again that latest known gets updated but latest written does not.
    assert(awaitNewerAssignment(driver, newAssignment.generation) == finalAssignment)
    // Wait for the latest known metric to be equal to the new assignment then ensure that the
    // metric tracking the latest written generation was not updated, also ensure non-meaningful
    // metrics are not updated and the incarnation metric did not change.
    AssertionWaiter(s"Waiting for latest known Generation to be ${finalAssignment.generation}")
      .await {
        assert(
          getLatestKnownGenerationMetric(defaultTarget) ==
          finalAssignment.generation.number.value
        )
        assert(
          getLatestKnownIncarnationMetric(defaultTarget) ==
          finalAssignment.generation.incarnation.value
        )
      }

    // Check latest written.
    assert(
      getLatestWrittenGenerationMetric(defaultTarget, isMeaningful = true) ==
      initialAssignment.generation.number.value
    )
    assert(
      getLatestWrittenGenerationMetric(defaultTarget, isMeaningful = false) !=
      initialAssignment.generation.number.value
    )
    assert(
      getLatestWrittenGenerationMetric(defaultTarget, isMeaningful = false) !=
      finalAssignment.generation.number.value
    )
    assert(
      getLatestWrittenIncarnationMetric(defaultTarget) ==
      initialAssignment.generation.incarnation.value
    )
  }

  private val loadBalancedAssignments: Seq[Assignment] = {
    // Create an arbitrary generation for the input assignments.
    val generation: Generation = Generation(Incarnation(7), 52)

    // Create three initial slices to be used in the input assignments.
    val slices: Seq[Slice] = Seq(
      "" -- "Dori",
      "Dori" -- "Fili",
      "Fili" -- ∞
    )

    // Create three input assignments that represent different shapes of the same load distribution.
    // The assignments are already load balanced, with a load of 1 on each slice. By testing with
    // different input assignment shapes, we reduce the probability that this test spuriously passes
    // due to the initial assignment algorithm coincidentally generating the same assignments.
    Seq(
      createAssignment(
        generation,
        AssignmentConsistencyMode.Affinity,
        Seq(
          SliceAssignment(
            SliceWithResources(slices(0), Set(resource(0))),
            generation,
            Map(),
            primaryRateLoadOpt = Some(1)
          ),
          SliceAssignment(
            SliceWithResources(slices(1), Set(resource(1))),
            generation,
            Map(),
            primaryRateLoadOpt = Some(1)
          ),
          SliceAssignment(
            SliceWithResources(slices(2), Set(resource(2))),
            generation,
            Map(),
            primaryRateLoadOpt = Some(1)
          )
        )
      ),
      createAssignment(
        generation,
        AssignmentConsistencyMode.Affinity,
        Seq(
          SliceAssignment(
            SliceWithResources(slices(0), Set(resource(1))),
            generation,
            Map(),
            primaryRateLoadOpt = Some(1)
          ),
          SliceAssignment(
            SliceWithResources(slices(1), Set(resource(2))),
            generation,
            Map(),
            primaryRateLoadOpt = Some(1)
          ),
          SliceAssignment(
            SliceWithResources(slices(2), Set(resource(0))),
            generation,
            Map(),
            primaryRateLoadOpt = Some(1)
          )
        )
      ),
      createAssignment(
        generation,
        AssignmentConsistencyMode.Affinity,
        Seq(
          SliceAssignment(
            SliceWithResources(slices(0), Set(resource(2))),
            generation,
            Map(),
            primaryRateLoadOpt = Some(1)
          ),
          SliceAssignment(
            SliceWithResources(slices(1), Set(resource(0))),
            generation,
            Map(),
            primaryRateLoadOpt = Some(1)
          ),
          SliceAssignment(
            SliceWithResources(slices(2), Set(resource(1))),
            generation,
            Map(),
            primaryRateLoadOpt = Some(1)
          )
        )
      )
    )
  }

  // Note: it's important to use `gridTest` here because we need to run the `beforeEach()` test hook
  // for EtcdStoreAssignmentGeneratorSuite to clear the data written to etcd between test runs.
  gridTest("Bootstraps from assignment in previous store incarnation")(
    loadBalancedAssignments
  ) { loadBalancedAssignment: Assignment =>
    // Test plan: Verify that the generator bootstraps from an assignment in the previous store
    // incarnation. Do this by creating several initial assignments generated in a previous store
    // incarnation that are already load balanced. For each of these assignments, supply them to
    // a new generator with a higher store incarnation and verify that the churn ratio is small.

    val loadBalancingInterval: FiniteDuration = UNHEALTHY_TIMEOUT_PERIOD
    val targetConfig: InternalTargetConfig = defaultTargetConfig.copy(
      loadBalancingConfig = LoadBalancingConfig(
        loadBalancingInterval,
        ChurnConfig.DEFAULT,
        LoadBalancingMetricConfig(maxLoadHint = 1, ImbalanceToleranceHintP.TIGHT)
      )
    )

    // For each input assignment, verify that a new generator with a higher store incarnation
    // bootstraps from the input assignment and generates a new assignment with a small churn ratio.
    // Create a new generator with a higher store incarnation.
    val incarnation = createLargerValidIncarnation(loadBalancedAssignment.generation.incarnation)
    val (driver, _): (AssignmentGeneratorDriver, InterceptableStore) =
      createDriverAndStore(targetConfig, Some(incarnation))

    // Keep the existing load-balanced assignment by carrying the existing load forward.
    val loadMapBuilder: LoadMap.Builder = LoadMap.newBuilder()
    for (sliceAssignment: SliceAssignment <- loadBalancedAssignment.sliceAssignments) {
      loadMapBuilder.putLoad(
        Entry(sliceAssignment.slice, sliceAssignment.primaryRateLoadOpt.getOrElse(0))
      )
    }
    val loadMap: LoadMap = loadMapBuilder.build()

    // Advance the clock less than the UNHEALTHY_TIMEOUT_PERIOD to ensure that the generator
    // considers the resources healthy.
    sec.advanceBySync(20.seconds)
    // For each resource, send a watch request with the input assignment and load information.
    for (id <- Seq(0, 1, 2)) {
      val request = createSliceletRequest(id, loadBalancedAssignment)
      val sliceletData = request.subscriberData.asInstanceOf[SliceletData]

      // Use the existing load in the input assignment so that the current assignment is still
      // load balanced.
      val attributedLoads: Vector[SliceLoad] = createAttributedLoads(
        loadBalancedAssignment,
        sliceletData.squid,
        loadMap = loadMap,
        window = (sec.getClock.instant().minusSeconds(120), sec.getClock.instant())
      )
      val requestWithLoad =
        request.copy(subscriberData = sliceletData.copy(attributedLoads = attributedLoads))
      driver.onWatchRequest(requestWithLoad)
    }

    // Advance the clock the rest of the UNHEALTHY_TIMEOUT_PERIOD so that the generator will
    // generate its first assignment.
    sec.advanceBySync(10.seconds)

    val newAsn: Assignment =
      awaitNewerAssignment(driver, loadBalancedAssignment.generation)
    val loadBalancingChurnRatio: Double = AssignmentChangeStats
      .calculate(
        loadBalancedAssignment,
        newAsn,
        loadMap
      )
      .loadBalancingChurnRatio
    assert(loadBalancingChurnRatio < 0.1)
  }

  test("Bootstrapping incorporates new load signals") {
    // Test plan: Verify that the generator incorporates load signals when bootstrapping with an
    // assignment from a previous store incarnation. Do this by creating an initial assignment that
    // is load balanced, changing the load distribution, and supplying the new load signals with the
    // previous assignment to a new generator with a higher store incarnation. Verify that the
    // initial assignment of the new generator has desirable assignment properties (e.g., load
    // balanced).

    val loadBalancingInterval: FiniteDuration = UNHEALTHY_TIMEOUT_PERIOD
    val targetConfig: InternalTargetConfig = defaultTargetConfig.copy(
      loadBalancingConfig = LoadBalancingConfig(
        loadBalancingInterval,
        ChurnConfig.DEFAULT,
        LoadBalancingMetricConfig(maxLoadHint = 2, ImbalanceToleranceHintP.DEFAULT)
      )
    )

    // Create an arbitrary generation for the input assignments.
    val initialIncarnation: Incarnation = createLargerValidIncarnation(Incarnation.MIN)
    val generation: Generation = Generation(initialIncarnation, 52)
    // Create three initial slices to be used in the input assignment.
    val slices: Seq[Slice] = Seq(
      Slice(
        SliceKey.MIN,
        SliceKey.fromRawBytes(
          protobuf.ByteString.copyFrom(Longs.toByteArray(0X5000000000000000L))
        )
      ),
      Slice(
        SliceKey.fromRawBytes(
          protobuf.ByteString.copyFrom(Longs.toByteArray(0X5000000000000000L))
        ),
        SliceKey.fromRawBytes(
          protobuf.ByteString.copyFrom(Longs.toByteArray(0XA000000000000000L))
        )
      ),
      Slice(
        SliceKey.fromRawBytes(
          protobuf.ByteString.copyFrom(Longs.toByteArray(0XA000000000000000L))
        ),
        InfinitySliceKey
      )
    )

    // Create an input assignment that is load balanced.
    val asn: Assignment = createAssignment(
      generation,
      AssignmentConsistencyMode.Affinity,
      Seq(
        SliceAssignment(
          SliceWithResources(slices(0), Set(resource(0))),
          generation,
          Map(),
          primaryRateLoadOpt = Some(1)
        ),
        SliceAssignment(
          SliceWithResources(slices(1), Set(resource(1))),
          generation,
          Map(),
          primaryRateLoadOpt = Some(1)
        ),
        SliceAssignment(
          SliceWithResources(slices(2), Set(resource(2))),
          generation,
          Map(),
          primaryRateLoadOpt = Some(1)
        )
      )
    )

    // Create a load map that is different from the load distribution of the input assignment. This
    // load map represents a heavily imbalanced load distribution.
    val loadMap: LoadMap =
      LoadMap
        .newBuilder()
        .putLoad(
          Entry(slices(0), 4),
          Entry(slices(1), 1),
          Entry(slices(2), 1)
        )
        .build()

    // Create a new generator with a higher store incarnation.
    val driver: AssignmentGeneratorDriver =
      createDriver(targetConfig, Some(createLargerValidIncarnation(initialIncarnation)))

    // Advance the clock less than the UNHEALTHY_TIMEOUT_PERIOD to ensure that the generator
    // considers the resources healthy.
    sec.advanceBySync(20.seconds)
    // For each resource, send a watch request with the input assignment and new load information.
    for (id <- Seq(0, 1, 2)) {
      val request = createSliceletRequest(id, asn)
      val sliceletData = request.subscriberData.asInstanceOf[SliceletData]

      // Construct load report for the Slicelet based on its assigned slices.
      val attributedLoads: Vector[SliceLoad] = createAttributedLoads(
        asn,
        sliceletData.squid,
        loadMap = loadMap,
        window = (sec.getClock.instant().minusSeconds(120), sec.getClock.instant())
      )
      val requestWithLoad =
        request.copy(subscriberData = sliceletData.copy(attributedLoads = attributedLoads))
      driver.onWatchRequest(requestWithLoad)
    }
    // Advance the clock the rest of the UNHEALTHY_TIMEOUT_PERIOD so that the generator will
    // generate its first assignment.
    sec.advanceBySync(10.seconds)

    val newAsn: Assignment = awaitNewerAssignment(driver, asn.generation)
    val resources: Resources = Resources.create(Seq(resource(0), resource(1), resource(2)))
    assert(newAsn.generation > generation)
    // Assert that the new assignment has desirable assignment properties.
    assertDesirableAssignmentProperties(targetConfig, newAsn, resources)
  }

  test("Generator correctly interprets the age of load signals") {
    // Test plan: Verify that the generator correctly translates the measurement time of load
    // signals as recorded on the Slicelet into local TickerTime when reporting those signals to the
    // load watcher. Verify this by creating a load report such that the load signals should be
    // considered too old for use in load estimates (determined by a max age configuration parameter
    // to the load watcher) at the time of assignment generation, and checking that the generated
    // assignment indeed does not make use of those load signals. Then create a load report where
    // the load signals should be considered fresh enough for use in load estimates at the time of
    // assignment generation, and checking that the generated assignment does make use of those load
    // signals.
    //
    // Note: This is a regression test for a bug where the generator would incorrectly translate the
    // Slicelet's load signal timestamp to a local TickerTime, leading to incorrect load signal age
    // calculations.

    // Setup: Use a target config where the max age of a load report is 3 minutes. We will expect
    // that load information older than 3 minutes at the time of assignment generation should be
    // ignored.
    val targetConfig: InternalTargetConfig = defaultTargetConfig.copy(
      loadWatcherConfig = defaultTargetConfig.loadWatcherConfig.copy(maxAge = 3.minutes)
    )
    val driver: AssignmentGeneratorDriver = createDriver(targetConfig)

    // Setup: Advance past the initial health watching period to allow the initial assignment to be
    // generated.
    sec.advanceBySync(30.seconds)
    val initialWatchRequest: ClientRequest = createSliceletRequest(0, Generation.EMPTY)
    driver.onWatchRequest(initialWatchRequest)
    val initialAssignment: Assignment = awaitNewerAssignment(driver, Generation.EMPTY)

    // Setup: Create a load report (with 1000 load per slice) that is 1 second past 3 minutes old.
    // This should be ignored by the generator, and not show up in the next assignment generated.
    val loadMap: LoadMap = {
      val loadMapBuilder: LoadMap.Builder = LoadMap.newBuilder()
      for (sliceAssignment: SliceAssignment <- initialAssignment.sliceAssignments) {
        loadMapBuilder.putLoad(
          Entry(sliceAssignment.slice, 1000)
        )
      }
      loadMapBuilder.build()
    }
    val watchRequestWithOldLoadReport: ClientRequest = createSliceletRequest(
      0,
      SyncAssignmentState.KnownGeneration(initialAssignment.generation),
      createAttributedLoads(
        initialAssignment,
        resource(0),
        (
          sec.getClock.instant().minusSeconds(3 * 60 + 120 + 1),
          sec.getClock.instant().minusSeconds(3 * 60 + 1) // 1s past 3m
        ),
        loadMap
      )
    )
    driver.onWatchRequest(watchRequestWithOldLoadReport)

    // Verify: The load report gets discarded due to being too old. Verify this by having another
    // Slicelet connect to trigger assignment generation, and checking that the load recorded in the
    // assignment is still the default LoadMap.UNIFORM_LOAD_MAP load, which should sum to 1.0.
    driver.onWatchRequest(createSliceletRequest(1, Generation.EMPTY))
    val newAssignment1: Assignment =
      awaitNewerAssignment(driver, initialAssignment.generation)
    val asn1TotalLoad: Double = newAssignment1.sliceAssignments.map {
      sliceAssignment: SliceAssignment =>
        sliceAssignment.primaryRateLoadOpt.getOrElse(0.0)
    }.sum
    assert(asn1TotalLoad == 1.0)

    // Setup: Now create a load report (still with 1000 load per slice) that is 1 second short of 3
    // minutes old, which should not be considered too stale by the generator, and should be used
    // for estimating load. Now that we have two Slicelets, we need to report the load across them
    // both.
    val loadMap2: LoadMap = {
      val loadMapBuilder: LoadMap.Builder = LoadMap.newBuilder()
      for (sliceAssignment: SliceAssignment <- newAssignment1.sliceAssignments) {
        loadMapBuilder.putLoad(Entry(sliceAssignment.slice, 1000))
      }
      loadMapBuilder.build()
    }
    val watchRequest0WithNewerLoadReport: ClientRequest = createSliceletRequest(
      0,
      SyncAssignmentState.KnownGeneration(newAssignment1.generation),
      createAttributedLoads(
        newAssignment1,
        resource(0),
        (
          sec.getClock.instant().minusSeconds(3 * 60 + 120 - 1),
          sec.getClock.instant().minusSeconds(3 * 60 - 1) // 1s short of 3m
        ),
        loadMap2
      )
    )
    val watchRequest1WithNewerLoadReport: ClientRequest = createSliceletRequest(
      1,
      SyncAssignmentState.KnownGeneration(newAssignment1.generation),
      createAttributedLoads(
        newAssignment1,
        resource(1),
        (
          sec.getClock.instant().minusSeconds(3 * 60 + 120 - 1),
          sec.getClock.instant().minusSeconds(3 * 60 - 1) // 1s short of 3m
        ),
        loadMap2
      )
    )
    driver.onWatchRequest(watchRequest0WithNewerLoadReport)
    driver.onWatchRequest(watchRequest1WithNewerLoadReport)

    // Verify: The load report is observed. Verify this by having another Slicelet connect to
    // trigger assignment generation, and checking that the load recorded in the assignment now sums
    // to the expected amount.
    driver.onWatchRequest(createSliceletRequest(2, Generation.EMPTY))
    val newAssignment2: Assignment =
      awaitNewerAssignment(driver, newAssignment1.generation)
    val asn2TotalLoad: Double = newAssignment2.sliceAssignments.map {
      sliceAssignment: SliceAssignment =>
        sliceAssignment.primaryRateLoadOpt.getOrElse(0.0)
    }.sum
    // As the absolute value for the loads are large in this test case, we allow a small floating
    // point calculation error (1e-10) from LoadWatcher.
    assert(Math.abs(asn2TotalLoad - 1000.0 * newAssignment1.sliceAssignments.size) < 1e-10)
  }

  test("Slicelet with UNKNOWN proto state") {
    // Test plan: Create a generator and have 2 client requests be delivered to it, one of which has
    // an UNKNOWN proto state. Verify that the UNKNOWN Slicelet's state is normalised to Running at
    // the proto boundary, and both Slicelets are included in the generated assignment regardless of
    // `observeSliceletReadiness`.

    val targetConfig: InternalTargetConfig = defaultTargetConfig
    val driver: AssignmentGeneratorDriver = createDriver(targetConfig)

    // Setup: Deliver heartbeats and advance past initial health report delay to allow the initial
    // assignment to be generated for the resources.
    sec.advanceBySync(20.seconds)
    val request1 = createSliceletRequest(id = 0, Generation.EMPTY)
    val request2 = createSliceletRequest(
      1,
      SyncAssignmentState.KnownGeneration(Generation.EMPTY),
      state = SliceletDataP.State.UNKNOWN
    )
    driver.onWatchRequest(request1)
    driver.onWatchRequest(request2)
    // Do this on the sec to ensure the clock advancement doesn't race with the heartbeats.
    sec.advanceBySync(10.seconds)

    // Setup: Watch the generator's cell to get the initial assignment.
    val assignment: Assignment = awaitNewerAssignment(driver, Generation.EMPTY)

    // Verify: resource(1)'s UNKNOWN state is normalised to Running at the proto boundary, so both
    // resources appear in the assignment regardless of observeSliceletReadiness.
    assertDesirableAssignmentProperties(
      targetConfig,
      assignment,
      Resources.create(Seq(resource(0), resource(1)))
    )
  }

  gridTest("Number of replicas in SliceLoad report is respected")(Seq(1, 2, 5, 10)) {
    injectedNumReplicas: Int =>
      // Test plan: Verify that the `SliceLoad.numReplicas` field in the watch requests from
      // Slicelets is respected by the assignment generator and will be used in load calculation.
      // Verify this by manually sending WatchRequests to the generator, containing a SliceLoad with
      // customized per-replica load values and `numReplicas` values. Verifying that the generator
      // will generate a new assignment whose total load equals to the total customized per-replica
      // load value scaled by the customized number of replicas, meaning that the `numReplicas` is
      // accepted by the generator and considered in the load calculation.
      //
      // Note that the main tests for how the number of replicas in the load report factor into load
      // calculations is in the LoadWatcher unit tests. This test is for checking the plumbing of
      // this information from the generator to the load watcher.

      val targetConfig: InternalTargetConfig = defaultTargetConfig
      val driver: AssignmentGeneratorDriver = createDriver(targetConfig)

      // Setup: Advance the clock to bypass the health delay and send a watch request to the
      // generator to trigger it generating the first assignment. Note that the Slicelet's watch
      // request doesn't bypass the health delay because it's known generation is empty.
      sec.advanceBySync(30.seconds)
      driver.onWatchRequest(createSliceletRequest(0, Generation.EMPTY))
      val initialAssignment: Assignment = awaitNewerAssignment(driver, Generation.EMPTY)

      // Sanity check: The initial assignment should have 0 total load on it.
      assert(
        initialAssignment.sliceAssignments
          .map((_: SliceAssignment).primaryRateLoadOpt.getOrElse(0.0))
          .sum == 0
      )

      // Setup: Create and send a watch request containing SliceLoads to the generator. The
      // SliceLoads have an arbitrary total load value, which will be used to compare against the
      // load in the new assignment. The SliceLoads' `numReplicas` fields are overridden with the
      // injected value.
      val injectedLoadMap: LoadMap = LoadMap.UNIFORM_LOAD_MAP.withAddedUniformLoad(20250308)
      val sliceLoadsWithInjectedNumReplicas: Vector[SliceLoad] = {
        val sliceLoads: Vector[SliceLoad] = createAttributedLoads(
          initialAssignment,
          resource(0),
          window = (sec.getClock.instant().minusSeconds(120), sec.getClock.instant()),
          injectedLoadMap
        )
        sliceLoads.map { sliceLoad: SliceLoad =>
          sliceLoad.copy(numReplicas = injectedNumReplicas)
        }
      }
      driver.onWatchRequest(
        createSliceletRequest(
          1,
          syncState = SyncAssignmentState.KnownGeneration(Generation.EMPTY),
          sliceLoadsWithInjectedNumReplicas
        )
      )
      val updatedAssignment: Assignment =
        awaitNewerAssignment(driver, initialAssignment.generation)

      // Sanity check: Updated assignment has both resources the generator sees.
      assert(updatedAssignment.assignedResources == Set(resource(0), resource(1)))

      // Verify: The injected load reports, with the injected number of replicas in them, are
      // accepted by the generator, and the total load in the load reports is the same as the
      // injected total per-replica load times the injected number of replicas, proving the
      // `numReplicas` fields in SliceLoads are considered by the assignment generator.
      assert(
        updatedAssignment.sliceAssignments
          .map((_: SliceAssignment).primaryRateLoadOpt.getOrElse(0.0))
          .sum == injectedLoadMap.getLoad(Slice.FULL) * injectedNumReplicas
      )
  }

  /**
   * REQUIRES: Buckets in `expectedBucketValues` are a subset of defined buckets for the metric.
   *
   * Checks that the slice replica count distribution metric for `target` has the expected bucket
   * values. Also checks the other metric buckets that are not listed in `expectedBucketValues` to
   * ensure that they are also as expected (including +Inf).
   */
  private def assertSliceReplicaCountDistributionHistogram(
      target: Target,
      expectedBucketValues: Map[Int, Int],
      expectedTotal: Int): Unit = {
    // Verify: Check that the buckets for the metric for `target` are exactly `expectedBuckets` +
    // "+Inf".
    val expectedBuckets = Array[Int](1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 16, 32, 64, 128)
    val actualDistribution: Map[String, Double] =
      TargetMetricsUtils.getSliceReplicaCountDistribution(target)
    assert(
      actualDistribution.keySet == expectedBuckets.map(i => i.toDouble.toString).toSet ++ Set(
        "+Inf"
      )
    )

    // Ensure that supplied buckets to check are valid.
    assert(expectedBucketValues.keys.forall(expectedBuckets.contains))

    // Compute the expected distribution. Do this by creating a sorted map of all the expected
    // bucket values, creating a Map[String, Double] representing the distribution, and comparing it
    // with the actual distribution.
    val sortedExpectedBucketValues = mutable.SortedMap[Int, Int](expectedBucketValues.toSeq: _*)
    var maxCDFValue: Int = 0
    for (expectedBucket: Int <- expectedBuckets) {
      maxCDFValue = sortedExpectedBucketValues.getOrElseUpdate(expectedBucket, maxCDFValue)
    }
    val expectedDistribution: Map[String, Double] =
      sortedExpectedBucketValues.map {
        case (k, v) => k.toDouble.toString -> v.toDouble
      }.toMap + ("+Inf" -> expectedTotal.toDouble)

    assert(actualDistribution == expectedDistribution)
  }

  test("slice replica count distribution histogram is correctly updated") {
    // Test plan: Verify that AssignmentGenerator correctly updates the replica count
    // distribution histogram in the Prometheus metrics upon learning about a newer assignment,
    // for various assignments with various distributions of slice replica counts. Do this by
    // creating the assignments and manually syncing them to the generator with increasing
    // generations, and verify the updated histogram upon each sync.

    val targetConfig: InternalTargetConfig = defaultTargetConfig
    val driver: AssignmentGeneratorDriver = createDriver(targetConfig)
    val incarnation: Incarnation = createLargerValidIncarnation(Incarnation.MIN)

    // Variable of the generations of assignments created by
    // `createAssignmentWithReplicaCountDistribution`, incremented once upon calling, to make sure
    // the assignments created have unique and increasing generation number.
    var generationNumber: Long = 1

    // Helper to create an assignment that has the given distribution of slice replica counts (i.e.
    // a key in the map is a replica count, and the value is the number of slices in the resulting
    // assignment that have that number of replicas).
    //
    // Implementation note: The assignment is constructed by creating single key slices ["", 0), [1,
    // 2), [3, 4), etc. where for each replica count in the provided distribution with a value of N,
    // we create N such slices.
    def createAssignmentWithReplicaCountDistribution(
        sliceCountsByReplicasDistribution: Map[Int, Int]): Assignment = {
      generationNumber += 1

      val sortedDistribution = immutable.SortedMap(sliceCountsByReplicasDistribution.toSeq: _*)
      val sliceAssignmentsBuilder = Vector.newBuilder[SliceAssignment]
      var previousHigh: SliceKey = ""
      var index: Int = 0
      for (entry <- sortedDistribution) {
        val (replicaCount, sliceCount): (Int, Int) = if (entry == sortedDistribution.last) {
          // Stop 1 slice short so that we can add a trailing slice to ∞ at the end.
          (entry._1, entry._2 - 1)
        } else {
          entry
        }
        for (_: Int <- 0 until sliceCount) {
          val slice: Slice = previousHigh -- index
          sliceAssignmentsBuilder += slice @@ Generation(incarnation, generationNumber) -> Seq
            .tabulate(replicaCount)(i => s"pod$i")
          previousHigh = slice.highExclusive.asFinite
          index += 1
        }
      }
      // Add trailing Slice, as the assignment must cover all keys.
      val (replicaCount, _): (Int, Int) = sortedDistribution.last
      sliceAssignmentsBuilder += (previousHigh -- ∞) @@ Generation(
        incarnation,
        generationNumber
      ) -> Seq.tabulate(replicaCount)(
        i => s"pod$i"
      )
      Assignment(
        // Suppress assignments generation by the generator itself that may disrupt the test.
        isFrozen = true,
        AssignmentConsistencyMode.Affinity,
        Generation(incarnation, generationNumber),
        SliceMapHelper.ofSliceAssignments(sliceAssignmentsBuilder.result())
      )
    }

    // Each test case consists of an assignment with some distribution of slice replica counts, and
    // a corresponding set of expected bucket values for the replica count distribution histogram.
    // Also includes an expected total to check the +Inf histogram bucket.
    case class TestCase(
        description: String,
        assignment: Assignment,
        expectedFiniteBucketValues: Map[Int, Int],
        expectedTotal: Int)

    val testCases = Seq(
      TestCase(
        description = "single replica assignment",
        assignment = createAssignmentWithReplicaCountDistribution(Map(1 -> 96)),
        expectedFiniteBucketValues = Map(1 -> 96),
        expectedTotal = 96
      ),
      TestCase(
        description = "multi-replica assignment with minReplica = 2",
        assignment = createAssignmentWithReplicaCountDistribution(Map(2 -> 96)),
        expectedFiniteBucketValues = Map(2 -> 96),
        expectedTotal = 96
      ),
      TestCase(
        description = "single hot key/slice with 8 replicas",
        assignment = createAssignmentWithReplicaCountDistribution(Map(1 -> 96, 8 -> 1)),
        expectedFiniteBucketValues = Map(1 -> 96, 8 -> 97),
        expectedTotal = 97
      ),
      TestCase(
        description = "many replicated keys/slices",
        assignment = createAssignmentWithReplicaCountDistribution(
          Map(1 -> 123, 2 -> 234, 3 -> 2, 8 -> 1, 11 -> 3, 15 -> 4)
        ),
        expectedFiniteBucketValues =
          Map(1 -> 123, 2 -> 357, 3 -> 359, 8 -> 360, 10 -> 360, 16 -> 367),
        expectedTotal = 367
      ),
      TestCase(
        description = "one slice of each replica count",
        assignment = createAssignmentWithReplicaCountDistribution((1 to 256).map(_ -> 1).toMap),
        expectedFiniteBucketValues =
          Seq(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 16, 32, 64, 128).map(i => i -> i).toMap,
        expectedTotal = 256
      )
    )

    for (testCase: TestCase <- testCases) {
      driver.onWatchRequest(
        createSliceletRequest(id = 0, SyncAssignmentState.KnownAssignment(testCase.assignment))
      )
      AssertionWaiter("SliceReplicaCountDistributionhistogram is correctly updated").await {
        assertSliceReplicaCountDistributionHistogram(
          defaultTarget,
          testCase.expectedFiniteBucketValues,
          testCase.expectedTotal
        )
      }
    }
  }

  test("Assignment generating rate from health change is limited") {
    // Test plan: Verify that the assignment generating rate from resource health change is limited
    // by AssignmentGenerator.Config.minAssignmentGenerationInterval. Verify this by create
    // assignment generator with a customized generating interval, adding and expiring resources at
    // various times, and checking that new assignment is only generated at the expected time
    // points.

    val minAssignmentGenerationInterval: FiniteDuration = 11.seconds

    val target: Target = defaultTarget
    val targetConfig: InternalTargetConfig = defaultTargetConfig
    // Sanity check: The default load balancing interval is 1 minute and doesn't affect this test.
    assert(targetConfig.loadBalancingConfig.loadBalancingInterval == 1.minute)

    val driver: AssignmentGeneratorDriver =
      createDriverUsingStore(
        createStore(sec, createLargerValidIncarnation(Incarnation.MIN)),
        targetConfig,
        target,
        minAssignmentGenerationInterval
      )

    // Setup: Advance the clock to bypass the health delay and send a watch request to the
    // generator to trigger it generating the first assignment. Note that the Slicelet's watch
    // request doesn't bypass the health delay because it's known generation is empty.
    sec.advanceBySync(30.seconds)
    driver.onWatchRequest(createSliceletRequest(0, Generation.EMPTY))
    waitForWriteToComplete(driver, "waiting for assignment0 write to complete")
    val assignment0: Assignment = awaitNewerAssignment(driver, Generation.EMPTY)
    assert(assignment0.assignedResources == Set(resource(0)))

    // Initial count of some metrics, used for sanity checks.
    val initialHealthChangeGenerationCount: Long =
      TargetMetricsUtils.getAssignmentGenerationGenerateDecisions(
        target,
        GenerateReason.HealthChange
      )
    val initialRateLimitSkipCount: Long =
      TargetMetricsUtils.getAssignmentGenerationSkipDecisions(
        target,
        SkipReason.RateLimited(GenerateReason.HealthChange)
      )
    val initialTime: TickerTime = sec.getClock.tickerTime()

    // We start counting since this time:
    // Now = 0s (rate limited).
    // NextNonRateLimitedTime = 11s.
    // resource0ExpireTime = 30s.
    // resource1ExpireTime = 30s.
    assert(sec.getClock.tickerTime() == initialTime + 0.second)

    // Setup: Send watch request from a new resource when the generating rate is limited.
    driver.onWatchRequest(createSliceletRequest(1, Generation.EMPTY))
    // Verify: No assignment is generated when the generating rate is limited, even if there is
    // a new resource observed.
    assertNoNewerAssignment(driver, assignment0.generation)
    // Sanity check: Metrics are as expected.
    AssertionWaiter("Rate limit count is increased by 1").await {
      assert(
        TargetMetricsUtils.getAssignmentGenerationSkipDecisions(
          target,
          SkipReason.RateLimited(GenerateReason.HealthChange)
        )
        == initialRateLimitSkipCount + 1
      )
      assert(
        TargetMetricsUtils
          .getAssignmentGenerationGenerateDecisions(target, GenerateReason.HealthChange) ==
        initialHealthChangeGenerationCount
      )
    }

    // Setup: Advance the clock so that:
    // Now = 10s.
    // NextNonRateLimitedTime = 11s.
    // resource0ExpireTime = 30s.
    // resource1ExpireTime = 30s.
    sec.advanceBySync(10.seconds)
    assert(sec.getClock.tickerTime() == initialTime + 10.seconds)
    assertNoNewerAssignment(driver, assignment0.generation)
    AssertionWaiter("Health change generation count is not increased").await {
      assert(
        TargetMetricsUtils
          .getAssignmentGenerationGenerateDecisions(target, GenerateReason.HealthChange) ==
        initialHealthChangeGenerationCount
      )
    }

    // Setup: Advance the clock so that:
    // now = 11s.
    // NextNonRateLimitedTime = 11s --> 22s.
    // resource0ExpireTime = 30s.
    // resource1ExpireTime = 30s.
    sec.advanceBySync(1.seconds)
    assert(sec.getClock.tickerTime() == initialTime + 11.seconds)
    // Verify: New assignment is generated after the rate limited time. The generation is because of
    // the previous health change of newly added resource1.
    waitForWriteToComplete(driver, "waiting for assignment1 write to complete")
    val assignment1: Assignment = awaitNewerAssignment(driver, assignment0.generation)
    // Sanity check:
    assert(assignment1.assignedResources == Set(resource(0), resource(1)))
    AssertionWaiter("Health change generation count is increased by 1").await {
      assert(
        TargetMetricsUtils
          .getAssignmentGenerationGenerateDecisions(target, GenerateReason.HealthChange) ==
        initialHealthChangeGenerationCount + 1
      )
    }

    // Setup: Advance the clock so that:
    // now = 29s (not rate limited).
    // NextNonRateLimitedTime = 22s --> 40s. (now + 11s).
    // resource0ExpireTime = 30s.
    // resource1ExpireTime = 30s.
    // resource2ExpireTime = 59s.
    sec.advanceBySync(18.seconds)
    assert(sec.getClock.tickerTime() == initialTime + 29.seconds)
    // Setup: A new resource observed at a time when the generating rate is not limited.
    driver.onWatchRequest(createSliceletRequest(2, Generation.EMPTY))
    // Verify: A new assignment is generated after observing the new resource2 when the generating
    // rate is not limited.
    waitForWriteToComplete(driver, "waiting for assignment2 write to complete")
    val assignment2: Assignment = awaitNewerAssignment(driver, assignment1.generation)
    // Sanity check:
    assert(assignment2.assignedResources == Set(resource(0), resource(1), resource(2)))
    AssertionWaiter("Health change generation count is increased by 1").await {
      assert(
        TargetMetricsUtils.getAssignmentGenerationSkipDecisions(
          target,
          SkipReason.RateLimited(GenerateReason.HealthChange)
        )
        == initialRateLimitSkipCount + 1 // Rate limit count not changed.
      )
      assert(
        TargetMetricsUtils
          .getAssignmentGenerationGenerateDecisions(target, GenerateReason.HealthChange) ==
        initialHealthChangeGenerationCount + 2 // Health change generation count increased by 1.
      )
    }

    // Setup: Advance the clock so that:
    // now = 35s (rate limited).
    // nextNonRateLimitedTime = 40s.
    // resource0ExpireTime = 30s. Expired.
    // resource1ExpireTime = 30s. Expired.
    // resource2ExpireTime = 59s.
    sec.advanceBySync(6.seconds)
    assert(sec.getClock.tickerTime() == initialTime + 35.seconds)
    // Verify: Even though resource0 and resource1 are newly expired, we are limited by the rate
    // so no new assignment is generated.
    assertNoNewerAssignment(driver, assignment2.generation)
    // Sanity check:
    AssertionWaiter("Rate limit count is increased by 1").await {
      assert(
        TargetMetricsUtils.getAssignmentGenerationSkipDecisions(
          target,
          SkipReason.RateLimited(GenerateReason.HealthChange)
        )
        == initialRateLimitSkipCount + 2 // Rate limit count increased by 1.
      )
      assert(
        TargetMetricsUtils
          .getAssignmentGenerationGenerateDecisions(target, GenerateReason.HealthChange) ==
        initialHealthChangeGenerationCount + 2 // Health change generation count not changed.
      )
    }

    // Setup: Advance the clock so that:
    // now = 40s (not rate limited).
    // nextNonRateLimitedTime = 51s.
    // resource0ExpireTime = 30s. Expired.
    // resource1ExpireTime = 30s. Expired.
    // resource2ExpireTime = 59s.
    sec.advanceBySync(5.seconds)
    assert(sec.getClock.tickerTime() == initialTime + 40.seconds)
    // Verify: After the rate limited time, the previously expired resources are reflected in the
    // new assignment.
    waitForWriteToComplete(driver, "waiting for assignment3 write to complete")
    val assignment3: Assignment = awaitNewerAssignment(driver, assignment2.generation)
    assert(assignment3.assignedResources == Set(resource(2)))
    // Sanity check:
    AssertionWaiter("Health change generation count is increased by 1").await {
      assert(
        TargetMetricsUtils
          .getAssignmentGenerationGenerateDecisions(target, GenerateReason.HealthChange) ==
        initialHealthChangeGenerationCount + 3 // Health change generation count increased by 1.
      )
    }
  }

  test("Assignment generating rate from load balancing is limited") {
    // Test plan: Verify that the assignment generating rate because of load balancing is limited
    // by AssignmentGenerator.Config.minAssignmentGenerationInterval. Verify this by create
    // assignment generator with a customized generating interval and load balancing interval, and
    // checking that load balancing is only generated at the expected time points.

    val minAssignmentGenerationInterval: FiniteDuration = 11.seconds
    val loadBalancingInterval: FiniteDuration = 9.seconds

    val target: Target = defaultTarget
    // Override the load balancing interval.
    val targetConfig: InternalTargetConfig =
      defaultTargetConfig.copy(
        loadBalancingConfig = defaultTargetConfig.loadBalancingConfig
          .copy(loadBalancingInterval = loadBalancingInterval)
      )
    assert(targetConfig.loadBalancingConfig.loadBalancingInterval == 9.seconds)

    val driver: AssignmentGeneratorDriver =
      createDriverUsingStore(
        createStore(sec, createLargerValidIncarnation(Incarnation.MIN)),
        targetConfig,
        target,
        minAssignmentGenerationInterval
      )

    // Setup: Advance the clock to bypass the health delay and send a watch request to the
    // generator to trigger it generating the first assignment. Note that the Slicelet's watch
    // request doesn't bypass the health delay because it's known generation is empty.
    sec.advanceBySync(30.seconds)
    // Setup: supply a health resource so that assignments can be generated.
    driver.onWatchRequest(createSliceletRequest(0, Generation.EMPTY))
    val assignment0: Assignment = awaitNewerAssignment(driver, Generation.EMPTY)
    assert(assignment0.assignedResources == Set(resource(0)))
    // Setup: Despite that we have observed the new assignment from the generator, the store's
    // write result may not have been received by the generator now! (The generator may learn the
    // assignment by watching the store.) This case should be very rare. We explicitly wait for the
    // generator to receive write result here before advancing the fake clock, so the following
    // verification of RateLimited metrics won't be interfered by the InFlightWrite SkipReason.
    waitForWriteToComplete(driver, "The store write result is received by the generator")

    val initialLoadBalancingGenerationCount: Long =
      TargetMetricsUtils.getAssignmentGenerationGenerateDecisions(
        target,
        GenerateReason.LoadBalancing
      )
    val initialRateLimitSkipCount: Long =
      TargetMetricsUtils.getAssignmentGenerationSkipDecisions(
        target,
        SkipReason.RateLimited(GenerateReason.HealthChange)
      )

    // Start counting time here.
    val initialTime: TickerTime = sec.getClock.tickerTime()

    // Now = 9s (rate limited).
    // NextNonRateLimitedTime = 11s.
    // NextExpectedLoadBalancingTime = 9s.
    sec.advanceBySync(9.seconds)
    assert(sec.getClock.tickerTime() == initialTime + 9.second)
    // Verify: No load balancing happened even it's already passed the first load balancing time,
    // because the generator is rate limited.
    assertNoNewerAssignment(driver, assignment0.generation)
    // Sanity check:
    AssertionWaiter("Rate limit count is increased by 1").await {
      assert(
        TargetMetricsUtils.getAssignmentGenerationSkipDecisions(
          target,
          SkipReason.RateLimited(GenerateReason.LoadBalancing)
        )
        == initialRateLimitSkipCount + 1 // Rate limit count increased by 1.
      )
      assert(
        TargetMetricsUtils
          .getAssignmentGenerationGenerateDecisions(target, GenerateReason.LoadBalancing)
        == initialLoadBalancingGenerationCount // Load balancing count not increased.
      )
    }

    // Now = 11s (non rate limited).
    // NextNonRateLimitedTime = 11s.
    // NextExpectedLoadBalancingTime = 9s.
    sec.advanceBySync(2.seconds)
    assert(sec.getClock.tickerTime() == initialTime + 11.second)
    // Verify: Load balancing only happened after the rate limitation.
    val assignment1: Assignment = awaitNewerAssignment(driver, assignment0.generation)
    assert(assignment1.assignedResources == Set(resource(0)))
    // Sanity check:
    AssertionWaiter("Load balancing generation count is increased by 1").await {
      assert(
        TargetMetricsUtils.getAssignmentGenerationSkipDecisions(
          target,
          SkipReason.RateLimited(GenerateReason.LoadBalancing)
        )
        == initialRateLimitSkipCount + 1 // Rate limit count not changed.
      )
      assert(
        TargetMetricsUtils
          .getAssignmentGenerationGenerateDecisions(target, GenerateReason.LoadBalancing)
        == initialLoadBalancingGenerationCount + 1 // Load balancing count increased by 1.
      )
    }
  }

  test("Assignment generating algorithm latency metrics are recorded") {
    // Test plan: Verify that the assignment generating algorithm latency is updated by
    // measuring the total count of elements in the histogram before and after an assignment
    // is generated.
    val targetConfig: InternalTargetConfig = defaultTargetConfig
    val driver: AssignmentGeneratorDriver = createDriver(targetConfig)

    // Verify that both the `generateInitialAssignment` and `generateAssignment` operations have
    // a count of 0.
    val initialGenInitCount: Int = getAssignmentGeneratorLatencyHistogramCount(
      defaultTarget,
      AssignmentGeneratorOpType.GENERATE_INITIAL_ASSIGNMENT
    )
    val initialGenCount: Int = getAssignmentGeneratorLatencyHistogramCount(
      defaultTarget,
      AssignmentGeneratorOpType.GENERATE_ASSIGNMENT
    )
    assert(initialGenInitCount == 0)
    assert(initialGenCount == 0)

    // Deliver heartbeats and advance past initial health report delay to allow the initial
    // assignment to be generated for the resources. Initially, resources 0 and 1 are available.
    sec.advanceBySync(20.seconds)
    driver.onWatchRequest(createSliceletRequest(0, Generation.EMPTY))
    driver.onWatchRequest(createSliceletRequest(1, Generation.EMPTY))
    // Do this on the sec to ensure the clock advancement doesn't race with the heartbeats.
    sec.advanceBySync(10.seconds)

    // Watch the generator's cell to get the initial assignment.
    val initialAssignment: Assignment = awaitNewerAssignment(driver, Generation.EMPTY)

    // Verify that the histogram count is increased by 1 for the `generateInitialAssignment`
    // operation.
    val initialAssignmentGenInitCount: Int = getAssignmentGeneratorLatencyHistogramCount(
      defaultTarget,
      AssignmentGeneratorOpType.GENERATE_INITIAL_ASSIGNMENT
    )
    val initialAssignmentGenCount: Int = getAssignmentGeneratorLatencyHistogramCount(
      defaultTarget,
      AssignmentGeneratorOpType.GENERATE_ASSIGNMENT
    )
    assert(initialAssignmentGenInitCount == 1)
    assert(initialAssignmentGenCount == 0)

    // Add a new resource, resulting in a new assignment including it.
    driver.onWatchRequest(createSliceletRequest(2, initialAssignment.generation))

    // Watch the generator's cell to get the updated assignment.
    awaitNewerAssignment(driver, initialAssignment.generation)

    // Verify that the histogram count is increased by 1 for the `generateAssignment`
    // operation.
    val finalGenInitCount: Int = getAssignmentGeneratorLatencyHistogramCount(
      defaultTarget,
      AssignmentGeneratorOpType.GENERATE_INITIAL_ASSIGNMENT
    )
    val finalGenCount: Int = getAssignmentGeneratorLatencyHistogramCount(
      defaultTarget,
      AssignmentGeneratorOpType.GENERATE_ASSIGNMENT
    )
    assert(finalGenInitCount == 1)
    assert(finalGenCount == 1)
  }

  test("Assignment generator load watcher getPrimaryRateLoadMap latency is recorded") {
    // Test plan: Verify that the latency of the `getPrimaryRateLoadMap` method in the load watcher
    // is recorded.
    val targetConfig: InternalTargetConfig = defaultTargetConfig
    val driver: AssignmentGeneratorDriver = createDriver(targetConfig)

    // Verify that the histogram count is 0 for the `getPrimaryRateLoadMap` operation, since
    // no assignment has been generated yet and `getPrimaryRateLoadMap` would not have been called.
    val initialGetPrimaryRateLoadMapCount: Int = getAssignmentGeneratorLatencyHistogramCount(
      defaultTarget,
      AssignmentGeneratorOpType.GET_PRIMARY_RATE_LOAD_MAP
    )
    assert(initialGetPrimaryRateLoadMapCount == 0)

    // Deliver a heartbeat and advance past initial health report delay to allow the initial
    // assignment to be generated for the resource.
    sec.advanceBySync(20.seconds)
    val request = createSliceletRequest(0, Generation.EMPTY)
    driver.onWatchRequest(request)
    // Do this on the sec to ensure the clock advancement doesn't race with the heartbeat.
    sec.advanceBySync(10.seconds)

    // Watch the generator's cell to get the initial assignment.
    val initialAssignment: Assignment = awaitNewerAssignment(driver, Generation.EMPTY)

    // Verify that the histogram count has not increased for the `getPrimaryRateLoadMap` operation,
    // as `getPrimaryRateLoadMap` is not called since there is no prior assignment.
    val afterInitAssignmentGetPrimaryRateLoadMapCount: Int =
      getAssignmentGeneratorLatencyHistogramCount(
        defaultTarget,
        AssignmentGeneratorOpType.GET_PRIMARY_RATE_LOAD_MAP
      )
    assert(afterInitAssignmentGetPrimaryRateLoadMapCount == 0)

    // Advance the clock to bypass half of the load balancing interval (1 minute by default) so that
    // `maybeExportAssignmentSnapshot` successfully calls `getPrimaryRateLoadMap`.
    // This is because `latestSlicezDataUpdateTime` was last set by the first watch request.
    sec.advanceBySync(40.seconds)

    // Verify that `getPrimaryRateLoadMap` is called by
    // `maybeExportAssignmentSnapshot`, increasing the histogram count by 1.
    // `maybeGenerateAssignment` should not call `generateAssignment`, as there have been no health
    // changes and the load balancing interval has not elapsed.
    AssertionWaiter("Wait for getPrimaryRateLoadMap to be called", ecOpt = Some(sec)).await {
      assert(
        getAssignmentGeneratorLatencyHistogramCount(
          defaultTarget,
          AssignmentGeneratorOpType.GET_PRIMARY_RATE_LOAD_MAP
        ) == 1
      )
    }

    // Add a new resource, resulting in a new assignment including it.
    driver.onWatchRequest(createSliceletRequest(1, initialAssignment.generation))

    // Watch the generator's cell to get the updated assignment.
    awaitNewerAssignment(driver, initialAssignment.generation)

    // Verify that the histogram count has increased for the `getPrimaryRateLoadMap` operation
    // by 1 as `generateAssignment` calls `getPrimaryRateLoadMap` when there exists a prior
    // assignment and a new resource is added.
    val afterAssignmentGetPrimaryRateLoadMapCount: Int =
      getAssignmentGeneratorLatencyHistogramCount(
        defaultTarget,
        AssignmentGeneratorOpType.GET_PRIMARY_RATE_LOAD_MAP
      )
    assert(afterAssignmentGetPrimaryRateLoadMapCount == 2)
  }

  test("Assignment generator load watcher reportLoad latency is recorded") {
    // Test plan: Verify that the latency of the `reportLoad` method in the load watcher is
    // recorded.
    val targetConfig: InternalTargetConfig = defaultTargetConfig
    val driver: AssignmentGeneratorDriver = createDriver(targetConfig)

    // Verify that the histogram count is 0 for the `reportLoad` operation, since
    // this operation is only called upon a `handleWatchRequest` call.
    val initialReportLoadCount: Int = getAssignmentGeneratorLatencyHistogramCount(
      defaultTarget,
      AssignmentGeneratorOpType.REPORT_LOAD
    )
    assert(initialReportLoadCount == 0)

    // Deliver a heartbeat and advance past initial health report delay to allow the initial
    // assignment to be generated for the resource.
    sec.advanceBySync(20.seconds)
    driver.onWatchRequest(createSliceletRequest(0, Generation.EMPTY))
    // Do this on the sec to ensure the clock advancement doesn't race with the heartbeat.
    sec.advanceBySync(10.seconds)

    // Verify that the histogram count has increased for the `reportLoad` operation
    val afterWatchRequestReportLoadCount: Int = getAssignmentGeneratorLatencyHistogramCount(
      defaultTarget,
      AssignmentGeneratorOpType.REPORT_LOAD
    )
    assert(afterWatchRequestReportLoadCount == 1)

    // Watch the generator's cell to get the initial assignment. Even though this assignment
    // is unused and has no impact on the statistics count, it is still necessary to call
    // to ensure that the write to the EtcdStore (when running in
    // `EtcdStoreAssignmentGeneratorSuite`) has completed and can be properly cleaned up prior to
    // the start of the next test.
    awaitNewerAssignment(driver, Generation.EMPTY)
  }

  test("Healthy and terminated resources do not affect key of death detector heuristics") {
    // Test plan: Verify that new and continuously healthy resources do not affect the key
    // of death detector heuristic metrics, nor do newly terminated resources. Other aspects,
    // such as the secondary heuristic's estimated resource workload size, should see updates.
    val driver: AssignmentGeneratorDriver = createDriver()

    // Within the initial health report delay and with no resources, the key of death detector
    // metrics should be 0.
    sec.advanceBySync(20.seconds)
    verifyKodDetectorMetrics(
      defaultTarget,
      expectedHeuristicValue = 0.0,
      expectedNumCrashedResourcesGauge = 0,
      expectedNumCrashedResourcesTotal = 0,
      expectedEstimatedResourceWorkloadSize = 0,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // Add a new resource and advance past the health report delay, resulting in a new assignment
    // including it. The key of death detector metrics should remain as 0.
    driver.onWatchRequest(createSliceletRequest(0, Generation.EMPTY))
    sec.advanceBySync(10.seconds)
    AssertionWaiter("New healthy resource updates workload size").await {
      verifyKodDetectorMetrics(
        defaultTarget,
        expectedHeuristicValue = 0.0,
        expectedNumCrashedResourcesGauge = 0,
        expectedNumCrashedResourcesTotal = 0,
        expectedEstimatedResourceWorkloadSize = 1,
        expectedTransitions = Map(
          KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 0,
          KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
          KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
          KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
        )
      )
    }

    // Add in another resource alongside the first, resulting in a new assignment including both.
    // The key of death detector metrics should remain as 0.
    sec.advanceBySync(10.seconds)
    driver.onWatchRequest(createSliceletRequest(0, Generation.EMPTY))
    driver.onWatchRequest(createSliceletRequest(1, Generation.EMPTY))
    sec.advanceBySync(10.seconds)
    AssertionWaiter("New healthy resource updates workload size").await {
      verifyKodDetectorMetrics(
        defaultTarget,
        expectedHeuristicValue = 0.0,
        expectedNumCrashedResourcesGauge = 0,
        expectedNumCrashedResourcesTotal = 0,
        expectedEstimatedResourceWorkloadSize = 2,
        expectedTransitions = Map(
          KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 0,
          KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
          KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
          KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
        )
      )
    }

    // Explicitly terminate the second resource, resulting in a new assignment excluding it.
    // The key of death detector metrics should remain as 0.
    sec.advanceBySync(10.seconds)
    driver.onWatchRequest(createSliceletRequest(0, Generation.EMPTY))
    driver.onWatchRequest(
      createSliceletRequest(
        1,
        SyncAssignmentState.KnownGeneration(Generation.EMPTY),
        state = SliceletDataP.State.TERMINATING
      )
    )
    sec.advanceBySync(10.seconds)
    AssertionWaiter("Terminated resource decreases workload size").await {
      verifyKodDetectorMetrics(
        defaultTarget,
        expectedHeuristicValue = 0.0,
        expectedNumCrashedResourcesGauge = 0,
        expectedNumCrashedResourcesTotal = 0,
        expectedEstimatedResourceWorkloadSize = 1,
        expectedTransitions = Map(
          KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 0,
          KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
          KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
          KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
        )
      )
    }
  }

  test("End-to-end key of death scenario is correctly detected") {
    // Test plan: Verify that an end-to-end key of death scenario is correctly detected and
    // reported in the key of death detector metrics. This includes setting up three initial
    // resources and crashing two of them to indicate the start of a key of death scenario.
    // The key of death scenario will only be resolved when all recorded crashes expire.
    val driver: AssignmentGeneratorDriver = createDriver()

    // Deliver heartbeats and advance past the health report delay to allow the initial
    // assignment to be generated for the available resources. There are three resources available.
    sec.advanceBySync(20.seconds)
    driver.onWatchRequest(createSliceletRequest(0, Generation.EMPTY))
    driver.onWatchRequest(createSliceletRequest(1, Generation.EMPTY))
    driver.onWatchRequest(createSliceletRequest(2, Generation.EMPTY))
    sec.advanceBySync(10.seconds)
    AssertionWaiter("Initial workload size is 3").await {
      verifyKodDetectorMetrics(
        defaultTarget,
        expectedHeuristicValue = 0.0,
        expectedNumCrashedResourcesGauge = 0,
        expectedNumCrashedResourcesTotal = 0,
        expectedEstimatedResourceWorkloadSize = 3,
        expectedTransitions = Map(
          KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 0,
          KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
          KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 0,
          KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
        )
      )
    }

    // Crash two of the resources, resulting in the key of death scenario being detected.
    // The crashes are simulated by not sending heartbeats for the second and third resources.
    // This crash occurs 55 seconds into the test.
    sec.advanceBySync(10.seconds)
    driver.onWatchRequest(createSliceletRequest(0, Generation.EMPTY))
    sec.advanceBySync(15.seconds)
    AssertionWaiter("Key of death scenario is detected").await {
      verifyKodDetectorMetrics(
        defaultTarget,
        expectedHeuristicValue = 2.0 / 3.0,
        expectedNumCrashedResourcesGauge = 2,
        expectedNumCrashedResourcesTotal = 2,
        expectedEstimatedResourceWorkloadSize = 3,
        expectedTransitions = Map(
          KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 1,
          KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
          KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 1,
          KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
        )
      )
    }

    // Simulate restarts for the two crashed resources. Even though the resources are restarted,
    // the key of death detector metrics should be unchanged, as the estimated resource workload
    // size should be frozen, and the number of crashed resources remains the same.
    // Heartbeat time: 65 seconds.
    sec.advanceBySync(10.seconds)
    driver.onWatchRequest(createSliceletRequest(0, Generation.EMPTY))
    driver.onWatchRequest(createSliceletRequest(1, Generation.EMPTY))
    driver.onWatchRequest(createSliceletRequest(2, Generation.EMPTY))
    sec.advanceBySync(10.seconds)
    verifyKodDetectorMetrics(
      defaultTarget,
      expectedHeuristicValue = 2.0 / 3.0,
      expectedNumCrashedResourcesGauge = 2,
      expectedNumCrashedResourcesTotal = 2,
      expectedEstimatedResourceWorkloadSize = 3,
      expectedTransitions = Map(
        KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 1,
        KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
        KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 1,
        KeyOfDeathTransitionType.POISONED_TO_STABLE -> 0
      )
    )

    // Since the crashes are recorded for one hour, we send heartbeats for each resource
    // until one hour has passed.
    // The current time is 75 seconds, and the crash expiry time is 1 hours and 50 seconds.
    var currentTime: Duration = 75.seconds
    val crashExpiryTime: Duration = 1.hours + 55.seconds
    while (currentTime < crashExpiryTime) {
      sec.advanceBySync(10.seconds)
      driver.onWatchRequest(createSliceletRequest(0, Generation.EMPTY))
      driver.onWatchRequest(createSliceletRequest(1, Generation.EMPTY))
      driver.onWatchRequest(createSliceletRequest(2, Generation.EMPTY))
      sec.advanceBySync(15.seconds)
      currentTime += 25.seconds
    }
    AssertionWaiter("Wait for crash expiry").await {
      verifyKodDetectorMetrics(
        defaultTarget,
        expectedHeuristicValue = 0.0,
        expectedNumCrashedResourcesGauge = 0,
        expectedNumCrashedResourcesTotal = 2,
        expectedEstimatedResourceWorkloadSize = 3,
        expectedTransitions = Map(
          KeyOfDeathTransitionType.STABLE_TO_ENDANGERED -> 1,
          KeyOfDeathTransitionType.ENDANGERED_TO_STABLE -> 0,
          KeyOfDeathTransitionType.ENDANGERED_TO_POISONED -> 1,
          KeyOfDeathTransitionType.POISONED_TO_STABLE -> 1
        )
      )
    }
  }

  test("onAdvance should return early when generator is shutdown") {
    // Test plan:
    // This test sets up conditions where onAdvance() would normally generate a new assignment,
    // but after shutdown it should return early instead. This ensures we test the actual
    // early return path in onAdvance().

    val driver: AssignmentGeneratorDriver = createDriver()

    // Set up conditions for assignment generation.
    sec.advanceBySync(20.seconds)
    driver.onWatchRequest(createSliceletRequest(0, Generation.EMPTY))

    // Now shutdown the generator - sets runState = Shutdown
    driver.shutdown()

    // At this point, if we called onAdvance() on a running generator, it would
    // process the watch request and potentially generate an assignment.
    // But since we're shutdown, it should return early instead.

    // Manually trigger onAdvance() directly - this should hit the early return path.
    // Note: Ideally we would not call onAdvance() directly, but we need it here to trigger
    // the early return path.
    sec.run {
      val tickerTime = sec.getClock.tickerTime()
      val instant = sec.getClock.instant()
      driver.baseDriver.forTest.getStateMachine.forTest.onAdvance(tickerTime, instant)
    }

    // Verify that onAdvance early return worked - no assignment should exist
    // (the watch request was not processed due to shutdown).
    assertNoNewerAssignment(driver, Generation.EMPTY)
  }

  test("WatchRequest events should NOT be processed after shutdown") {
    // Test plan:
    // Shutdown the generator and verify that WatchRequest events are NOT processed after shutdown.
    // This test:
    // 1. Gets the generator running with one resource.
    // 2. Shuts down the generator.
    // 3. Verifies that the WatchRequest events are NOT processed after shutdown.
    // 4. Verifies that the pod set size is cleared to 0 by handleShutdown and remains 0.
    // 5. Verifies that no new assignments are generated.
    // This test will FAIL if there is a race condition that allows WatchRequest events
    //to be processed after shutdown.

    val driver: AssignmentGeneratorDriver = createDriver()

    // Initial setup - get generator running with one resource.
    sec.advanceBySync(20.seconds) // advance past initialization delays.
    driver.onWatchRequest(createSliceletRequest(0, Generation.EMPTY))
    sec.advanceBySync(10.seconds) // advance to allow assignment to be generated.
    val initialAssignment = awaitNewerAssignment(driver, Generation.EMPTY)

    // Record initial metrics to detect changes after shutdown
    val initialPodSetSize = TargetMetricsUtils.getPodSetSize(defaultTarget, "Running")
    val initialGeneratedAssignments =
      TargetMetricsUtils.getAllAssignmentGenerationGenerateDecisions(defaultTarget)

    // Verify initial state assumptions
    assert(initialPodSetSize == 1, s"Should have 1 running pod initially, got $initialPodSetSize")
    assert(
      initialGeneratedAssignments == 1,
      s"Should have generated at least 1 assignment, got $initialGeneratedAssignments"
    )

    // Shutdown the generator - this sets runState = Shutdown.
    driver.shutdown()

    // Verify shutdown occurred by checking that onAdvance-based processing stops
    // (i.e. no new assignment should be generated).
    assertNoNewerAssignment(driver, initialAssignment.generation)

    // Now send a WatchRequest AFTER shutdown - this should NOT be processed!
    // This guards against the race: event handlers should check runState.
    driver.onWatchRequest(createSliceletRequest(1, Generation.EMPTY))

    // Allow some time for any potential processing (but there shouldn't be any).
    sec.advanceBySync(1.second)

    // Verify the WatchRequest was NOT processed after shutdown
    // The correct behavior: setPodSetSize should NOT be called after shutdown
    val currentPodSetSize = TargetMetricsUtils.getPodSetSize(defaultTarget, "Running")
    assert(
      currentPodSetSize == 0,
      s"Pod set size should be cleared to 0 by handleShutdown and remain 0 after WatchRequest. " +
      s"Got: $currentPodSetSize"
    )

    // Verify no newer assignment was generated.
    assertNoNewerAssignment(driver, initialAssignment.generation)

    // Verify that no new assignments were generated
    // Note: assignmentGenerationDecisions is not cleared during shutdown.
    val finalGeneratedAssignments =
      TargetMetricsUtils.getAllAssignmentGenerationGenerateDecisions(defaultTarget)
    assert(
      finalGeneratedAssignments == initialGeneratedAssignments,
      s"No new assignments should be generated after shutdown. " +
      s"Initial: $initialGeneratedAssignments, Final: $finalGeneratedAssignments"
    )
  }

  test("Async write completion events should NOT be processed after shutdown") {
    // Test plan: Ensure that shutdown is atomic and that async write completion events do not
    // trigger a race condition where write completion events are processed after shutdown.
    // This test will FAIL if there is a race condition that allows write completion events.
    // The test proceeds as follows:
    // 1. Start assignment write (blocked by interceptable store).
    // 2. Call shutdown() while write is in progress.
    // 3. Verify generator is in shutdown state.
    // 4. Complete the blocked write.
    // 5. Verify write completion event is NOT processed after shutdown.
    // 6. Check that metrics are NOT updated after shutdown.

    val (driver, store): (AssignmentGeneratorDriver, InterceptableStore) = createDriverAndStore()

    // Generate initial assignment by making resources available.
    sec.advanceBySync(20.seconds)
    driver.onWatchRequest(createSliceletRequest(0, Generation.EMPTY))
    driver.onWatchRequest(createSliceletRequest(1, Generation.EMPTY))
    sec.advanceBySync(10.seconds)

    waitForWriteToComplete(driver, "wait for assignment write to complete")
    val initialAssignment: Assignment = awaitNewerAssignment(driver, Generation.EMPTY)

    // Verify initial state assumptions
    AssertionWaiter("wait for metrics to update").await {
      val initialWriteCount: Long =
        TargetMetricsUtils.getNumAssignmentWrites(defaultTarget, Code.OK, isMeaningful = true)
      val initialGeneratedAssignments: Long =
        TargetMetricsUtils.getAllAssignmentGenerationGenerateDecisions(defaultTarget)

      assert(
        initialAssignment.assignedResources == Set(resource(0), resource(1)),
        s"Initial assignment should have 2 resources, got ${initialAssignment.assignedResources}"
      )
      assert(
        initialWriteCount >= 1,
        s"Should have completed at least 1 assignment write initially, got $initialWriteCount"
      )
      assert(
        initialGeneratedAssignments >= 1,
        s"Should have generated at least 1 assignment initially, got $initialGeneratedAssignments"
      )
    }

    // Retrieve this value again; it gets used later.
    val initialWriteCount =
      TargetMetricsUtils.getNumAssignmentWrites(defaultTarget, Code.OK, isMeaningful = true)

    // Block future assignment writes at the store level.
    sec.run {
      store.blockAssignmentWrites(defaultTarget)
    }

    // Debug: Verify blocking is set up correctly
    val preRequestDeferredCount = TestUtils.awaitResult(sec.call {
      store.getDeferredAssignments(defaultTarget).size
    }, Duration.Inf)
    assert(
      preRequestDeferredCount == 0,
      s"Should have no deferred assignments initially, got $preRequestDeferredCount"
    )

    // Trigger a new assignment generation (its write to store will be blocked).
    driver.onWatchRequest(createSliceletRequest(2, Generation.EMPTY))
    sec.advanceBySync(1.second)

    // Verify the write is blocked (there should be a deferred assignment)
    AssertionWaiter("Wait for assignment write to be blocked", ecOpt = Some(sec)).await {
      val deferredAssignments = store.getDeferredAssignments(defaultTarget)
      assert(
        deferredAssignments.size == 1,
        s"Expected 1 deferred assignment, got ${deferredAssignments.size}"
      )
    }
    // Now shutdown the generator WHILE the write is in progress.
    driver.shutdown()

    // Verify shutdown was processed - no more assignments should be generated.
    assertNoNewerAssignment(driver, initialAssignment.generation)

    // Now unblock the store to allow the async write to complete.
    sec.run {
      store.unblockAssignmentWrites(defaultTarget)
    }

    // Allow time for any potential (incorrect) processing, then verify it didn't happen.
    sec.advanceBySync(2.seconds)

    // Verify the async write completion callback was NOT processed after shutdown.
    // The correct behavior: write completion events should be ignored after shutdown.
    val currentWriteCount =
      TargetMetricsUtils.getNumAssignmentWrites(defaultTarget, Code.OK, isMeaningful = true)
    assert(
      currentWriteCount == initialWriteCount,
      s"Write completion metrics should NOT be updated after shutdown. " +
      s"Initial: $initialWriteCount, Current: $currentWriteCount"
    )

    // Verify that no new assignment was distributed (completion event should be ignored).
    assertNoNewerAssignment(driver, initialAssignment.generation)

    // Confirm that the pod set size is cleared to 0 by handleShutdown and remains 0.
    val currentPodSetSize = TargetMetricsUtils.getPodSetSize(defaultTarget, "Running")
    assert(
      currentPodSetSize == 0,
      s"Pod set size should be cleared to 0 by handleShutdown and remain 0 after WatchRequest. " +
      s"Got: $currentPodSetSize"
    )

  }
}

object CommonAssignmentGeneratorSuite {

  /** Returns all resources that have assignments in the given proposal. */
  private def getAssignedResources(proposal: ProposedAssignment): Set[Squid] = {
    proposal.sliceAssignments.flatMap { sliceAssignment =>
      sliceAssignment.resources
    }.toSet
  }

  def resource(id: Int): Squid = createTestSquid(s"pod-$id")

}

// Note: (observeSliceletReadiness = false, permitRunningToNotReady = true) is not a valid
// configuration. See [[InternalTargetConfig.HealthWatcherTargetConfig]] for details.
class InMemoryStoreAssignmentGeneratorWithStatusMaskingSuite
    extends InMemoryStoreAssignmentGeneratorSuite(
      observeSliceletReadiness = false,
      permitRunningToNotReady = false
    )

class InMemoryStoreAssignmentGeneratorWithoutStatusMaskingSuite
    extends InMemoryStoreAssignmentGeneratorSuite(
      observeSliceletReadiness = true,
      permitRunningToNotReady = false
    )

class InMemoryStoreAssignmentGeneratorWithPermitRunningToNotReadySuite
    extends InMemoryStoreAssignmentGeneratorSuite(
      observeSliceletReadiness = true,
      permitRunningToNotReady = true
    )

class EtcdStoreAssignmentGeneratorWithStatusMaskingSuite
    extends EtcdStoreAssignmentGeneratorSuite(
      observeSliceletReadiness = false,
      permitRunningToNotReady = false
    )

class EtcdStoreAssignmentGeneratorWithoutStatusMaskingSuite
    extends EtcdStoreAssignmentGeneratorSuite(
      observeSliceletReadiness = true,
      permitRunningToNotReady = false
    )

class EtcdStoreAssignmentGeneratorWithPermitRunningToNotReadySuite
    extends EtcdStoreAssignmentGeneratorSuite(
      observeSliceletReadiness = true,
      permitRunningToNotReady = true
    )

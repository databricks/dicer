package com.databricks.dicer.client

import java.net.{InetAddress, URI}
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.collection.immutable

import javax.annotation.concurrent.ThreadSafe

import com.databricks.caching.util.AssertMacros.iassert
import com.databricks.caching.util.{
  AlertOwnerTeam,
  ConsistentHashRing,
  PrefixLogger,
  SequentialExecutionContext,
  SequentialExecutionContextPool,
  ValueStreamCallback,
  WatchValueCell,
  WhereAmIHelper
}
import com.google.protobuf.ByteString
import com.databricks.dicer.client.ClerkMetrics.ClerkFactoryContext
import com.databricks.dicer.common.{
  Assignment,
  AssignmentMetricsSource,
  ClerkData,
  ClientType,
  Generation,
  SliceAssignment,
  Version
}
import com.databricks.dicer.external.{
  AppTarget,
  ClerkConf,
  KubernetesTarget,
  ResourceAddress,
  Slice,
  SliceKey,
  Target
}
import com.databricks.dicer.friend.Squid
import com.databricks.rpc.tls.TLSOptions
import javax.annotation.concurrent.GuardedBy

/**
 * The implementation for the Clerk.
 *
 * @param sec                 Execution context used to run the callbacks when the Clerk receives an
 *                            assignment.
 * @param target              See [[Clerk.create]].
 * @param factoryContext      Identifies the entry point used to create this Clerk. Used as a
 *                            low-cardinality label on Clerk metrics for usage attribution.
 * @param lookup              The [[SliceLookup]] that queries and caches the assignment for the
 *                            Clerk.
 * @param subscriberDebugName The debug name shown in the log and string representation of the
 *                            Clerk.
 * @param stubFactory         See [[Clerk.create]].
 */
@ThreadSafe
private[dicer] class ClerkImpl[Stub <: AnyRef] private (
    sec: SequentialExecutionContext,
    target: Target,
    factoryContext: ClerkFactoryContext,
    lookup: SliceLookup,
    subscriberDebugName: String,
    stubFactory: ResourceAddress => Stub) {

  private val logger = PrefixLogger.create(getClass, subscriberDebugName)

  // For capturing metrics for this target and creation context.
  private val clerkMetrics = new ClerkMetrics(target, factoryContext)
  clerkMetrics.incrementClerkCreatedCount()

  /**
   * Cell holding the latest [[ClerkAssignment]], populated on the Clerk's [[sec]] from
   * [[ClerkWatchCallback]] each time a new assignment is reported. Empty until the first
   * assignment is received.
   */
  private val clerkAssignmentCell: WatchValueCell[ClerkAssignment] =
    new WatchValueCell[ClerkAssignment]

  /**
   * ResourceRouter for caching the mapping from resource addresses to application-defined stubs
   * (e.g. RPC stubs).
   */
  private val resourceRouter =
    new ResourceRouter[Stub](
      clerkAssignmentCell,
      logPrefix = s"Router-$target",
      stubFactory,
      stubCacheLifetime = 1.hour
    )

  /** A promise that is set when the initial assignment is received. */
  private val assignmentReceived = Promise[Unit]

  /**
   * Whether [[stop]] has been called. Used to prevent [[ClerkWatchCallback.onSuccess]] from
   * re-adding per-target metric gauge samples after [[stop]] has removed them.
   */
  @GuardedBy("sec")
  private var isStopped: Boolean = false

  /** Callback methods for the Clerk. */
  private object ClerkWatchCallback extends ValueStreamCallback[Assignment](sec) {

    protected override def onSuccess(assignment: Assignment): Unit = {
      // Suppress known spurious wakeups where the recorded ClerkAssignment is at least as new as
      // the new `assignment`.
      val latestKnownGeneration: Generation = clerkAssignmentCell.getLatestValueOpt
        .map(
          (clerkAssignment: ClerkAssignment) => clerkAssignment.assignment.generation
        )
        .getOrElse(Generation.EMPTY)
      if (assignment.generation <= latestKnownGeneration) {
        logger.debug(
          s"Spurious wakeup for ClerkAssignment: " +
          s"${assignment.generation} <= ${latestKnownGeneration}"
        )
      } else {
        // Given this new assignment, create and remember a new ClerkAssignment that computes the
        // two-level sharding hash rings up-front for each slice that is assigned to more than one
        // resource. Publish before completing `assignmentReceived` so any caller awaiting
        // [[ready]] observes a populated cell.
        val clerkAssignment: ClerkAssignment = ClerkAssignment.create(assignment)
        clerkAssignmentCell.setValue(clerkAssignment)
        // An initial assignment (at least!) has been received. Make sure the assignmentReceived
        // promise is completed.
        if (assignmentReceived.trySuccess(())) {
          logger.info(s"Initial clerk assignment received: ${assignment.generation}")
        }
        if (!isStopped) {
          // When the Clerk is stopped, we avoid exporting the metrics to cause alert and monitoring
          // noise. Note that we still allow the stopped Clerk to incorporate new assignments if it
          // receives one even after stop() is called.
          ClientMetrics.updateOnNewAssignment(
            assignment.generation,
            target,
            AssignmentMetricsSource.Clerk
          )
        }
      }
    }
  }

  /**
   * Future that completes when the clerk is ready to route requests (after an initial assignment
   * has been received from Dicer). If [[stop]] is called before it becomes ready, the returned
   * future may never complete.
   */
  def ready: Future[Unit] = {
    assignmentReceived.future
  }

  /**
   * See specs for the [[com.databricks.dicer.external.Clerk.getStubForKey]]. If it is called after
   * [[stop]], the returned stub may not be to the most recently assigned resource.
   */
  def getStubForKey(key: SliceKey): Option[Stub] = {
    clerkMetrics.incrementClerkGetStubForKeyCallCount()
    resourceRouter.getStubForKey(key)
  }

  /**
   * Two-level sharding variant of [[getStubForKey]]. See specs for
   * [[com.databricks.dicer.friend.external.TwoLevelShardingClerkAccessor.getStubForKey]]. If it is
   * called after [[stop]], the returned stub may not be to the most recently assigned resource.
   */
  def getStubForKey(primaryKey: SliceKey, secondaryKey: SliceKey): Option[Stub] = {
    clerkMetrics.incrementClerkGetStubForKeyCallCount()
    resourceRouter.getStubForKey(primaryKey, secondaryKey)
  }

  /**
   * Stops all the asynchronous activities (e.g., cancels the [[SliceLookup]] that communicates with
   * the remote service to obtain assignments and incorporates new assignments). Also unregisters
   * Slicez and removes the per-target Prometheus gauges so the stopped Clerk does not leave stale
   * samples behind. Note that this method stops the Clerk asynchronously. Although other methods
   * may still be called after [[stop]], the Clerk becomes inert and no longer receives assignment
   * updates.
   */
  def stop(): Unit = sec.run {
    lookup.cancel()
    // Set isStopped to true to prevent the metrics being resurrected by any pending callbacks
    // scheduled on `sec`.
    isStopped = true
    ClientMetrics.removeGaugesForTarget(target, AssignmentMetricsSource.Clerk)
    logger.info("Stopped Clerk")
  }

  override def toString: String = subscriberDebugName

  /**
   * Starts the Clerk by kicking off slice looking up, registering callbacks to execute on receiving
   * assignments, and registering Slicez.
   */
  private def start(): Unit = {
    lookup.start(() => ClerkData)
    lookup.cellConsumer.watch(ClerkWatchCallback)
  }

  object forTest {

    /** Returns the latest assignment known to the Clerk. */
    def getLatestAssignmentOpt: Option[Assignment] =
      clerkAssignmentCell.getLatestValueOpt.map(
        (clerkAssignment: ClerkAssignment) => clerkAssignment.assignment
      )

    /**
     * Verifies invariants on the latest [[ClerkAssignment]] derived from the latest assignment.
     * No-op until the first assignment has been received.
     */
    def checkInvariants(): Unit = {
      for (clerkAssignment: ClerkAssignment <- clerkAssignmentCell.getLatestValueOpt) {
        clerkAssignment.forTest.checkInvariants()
      }
    }

    /** Injects an assignment in the Clerk. */
    def injectAssignment(assignment: Assignment): Unit = {
      lookup.forTest.injectAssignment(assignment)
    }
  }
}

/** Companion object for [[ClerkImpl]]. */
private[dicer] object ClerkImpl {

  private val logger = PrefixLogger.create(this.getClass, "")

  /**
   * SliceLookup instances which may be reused across Clerk instances. This is a defensive measure
   * to protect backend services against misconfigured clients which create too many Clerk
   * instances. Without this cache, a client may create an unbounded number of Clerks (and thus
   * SliceLookups), each of which perform their own assignment sync in the background. Eventually,
   * this may be enough to overload the backend.
   *
   * Note that we cache SliceLookups instead of ClerkImpls due to the lack of an meaningful
   * equivalence relation for the Clerk's `stubFactory`; most stub factories are created using
   * anonymous function, so reference equality would be too strict to ever catch a misbehaving
   * client. See [[SliceLookupCache]] for more details.
   */
  private val lookupCache: SliceLookupCache = new SliceLookupCache()

  /** See specs for the external [[Clerk.create()]] for details. */
  def create[Stub <: AnyRef](
      clerkConf: ClerkConf,
      target: Target,
      watchAddress: URI,
      stubFactory: ResourceAddress => Stub): ClerkImpl[Stub] = {
    val targetBestEffortFullyQualified: Target = target match {
      case kubernetesTarget: KubernetesTarget =>
        tryInsertInferredTargetCluster(kubernetesTarget)
      case _: AppTarget =>
        // AppTargets are always fully qualified by `instanceId`.
        target
    }

    val reuseLookups: Boolean = clerkConf.allowMultipleClerksShareLookupPerTarget

    val podName: String = InetAddress.getLocalHost.getHostName
    val clerkIndex: Int = assignNextClerkIndex()
    val clerkDebugName = s"C$clerkIndex-$targetBestEffortFullyQualified-$podName"

    Version.recordClientVersion(
      targetBestEffortFullyQualified,
      AssignmentMetricsSource.Clerk,
      clerkConf.branch
    )

    val config: InternalClientConfig = InternalClientConfig(
      SliceLookupConfig(
        ClientType.Clerk,
        watchAddress,
        clerkConf.getDicerClientTlsOptions,
        targetBestEffortFullyQualified,
        clientIdOpt = resolveClientUuid(clerkConf.clientUuidOpt, target),
        SliceLookupConfig.DEFAULT_WATCH_STUB_CACHE_TIME,
        watchFromDataPlane = false,
        // TODO(<internal bug>): Use client side feature flag to gradually rollout rate limiting.
        enableRateLimiting = false
      ),
      subscriberDebugName = clerkDebugName
    )

    createInternal(
      secPoolOpt = None,
      protoLogger =
        DicerClientProtoLogger.create(ClientType.Clerk, clerkConf, ownerName = clerkDebugName),
      config,
      clerkIndex,
      factoryContext = "clerk",
      stubFactory,
      reuseLookups
    )
  }

  /**
   * PRECONDITION: `target` must have the cluster URI populated.
   *
   * Creates a clerk that directly watches the Assigner from the data plane. This is for supporting
   * internal-system and internal-system use cases specifically before the Rust Slicelet is able to
   *
   * @param secPoolOpt If provided, the SEC pool to use for the clerk's async operations.
   *                   If None, a dedicated pool is created for this clerk.
   */
  def createForDataPlaneDirectClerk[Stub <: AnyRef](
      secPoolOpt: Option[SequentialExecutionContextPool],
      clerkConf: ClerkConf,
      target: Target,
      assignerAddress: URI,
      stubFactory: ResourceAddress => Stub): ClerkImpl[Stub] = {
    createForDataPlaneCommon(
      secPoolOpt,
      sharedProtoLoggerOpt = None,
      clerkConf,
      target,
      assignerAddress,
      stubFactory,
      factoryContext = "dataPlaneDirectClerk",
      reuseLookups = clerkConf.allowMultipleClerksShareLookupPerTarget
    )
  }

  /**
   * PRECONDITION: `target` must have the cluster URI populated.
   *
   * Creates a clerk for use in a MultiClerk setup, where multiple clerks share execution context
   * pools and a proto logger, and watch the Assigner from the control plane.
   *
   * @param secPoolOpt  If provided, the SEC pool to use for the clerk's async operations.
   *                    If None, a dedicated pool is created for this clerk.
   * @param protoLogger The shared proto logger.
   */
  def createForMultiClerk[Stub <: AnyRef](
      secPoolOpt: Option[SequentialExecutionContextPool],
      protoLogger: DicerClientProtoLogger,
      clerkConf: ClerkConf,
      target: Target,
      assignerAddress: URI,
      stubFactory: ResourceAddress => Stub): ClerkImpl[Stub] = {
    createForDataPlaneCommon(
      secPoolOpt,
      sharedProtoLoggerOpt = Some(protoLogger),
      clerkConf,
      target,
      assignerAddress,
      stubFactory,
      factoryContext = "multiClerk",
      // MultiClerk doesn't support lookup reuse, as it can arbitrarily stop Clerks.
      reuseLookups = false
    )
  }

  /**
   * Creates and returns a new [[ClerkImpl]] specifically for a Dicer-integrated stub.
   */
  // TODO(<internal bug>): Enforce the singleton Clerk constraint.
  def createForShardedStub(
      target: Target,
      watchAddress: URI,
      protoLoggerConf: DicerClientProtoLoggerConf,
      tlsOptions: Option[TLSOptions],
      clientUuidOpt: Option[String]
  ): ClerkImpl[ResourceAddress] = {

    val targetBestEffortFullyQualified: Target = target match {
      case kubernetesTarget: KubernetesTarget =>
        tryInsertInferredTargetCluster(kubernetesTarget)
      case _: AppTarget =>
        // AppTargets are always fully qualified by `instanceId`.
        target
    }

    val podName: String = InetAddress.getLocalHost.getHostName
    val clerkIndex: Int = assignNextClerkIndex()
    val clerkDebugName: String =
      s"C-$targetBestEffortFullyQualified-$podName-sharded-stub-$clerkIndex"

    val config = InternalClientConfig(
      SliceLookupConfig(
        ClientType.Clerk,
        watchAddress,
        tlsOptions,
        targetBestEffortFullyQualified,
        clientIdOpt = resolveClientUuid(clientUuidOpt, target),
        SliceLookupConfig.DEFAULT_WATCH_STUB_CACHE_TIME,
        watchFromDataPlane = false,
        // TODO(<internal bug>): Use client side feature flag to gradually rollout rate limiting.
        enableRateLimiting = false
      ),
      subscriberDebugName = clerkDebugName
    )
    createInternal(
      secPoolOpt = None,
      protoLogger = DicerClientProtoLogger
        .create(ClientType.Clerk, protoLoggerConf, ownerName = clerkDebugName),
      config,
      clerkIndex,
      factoryContext = "shardedStub",
      stubFactory = (resourceAddress: ResourceAddress) => resourceAddress,
      // TODO(<internal bug>): Enable lookup reuse for sharded stubs, once rolled out to all clusters.
      reuseLookups = false
    )
  }

  /**
   *  - If the `target` is not qualified with a cluster URI, queries the WhereAmI environment
   *    variable and returns a qualified Target by overriding `target` with the local cluster URI
   *  - If `target` is already qualified with a cluster URI, returns it untouched.
   *  - If `target` is unqualified but the location is unavailable, the method still returns the
   *    unqualified Target and let the caller thread proceed (rather than throwing) as we don't want
   *    to take a hard dependency on WhereAmI yet.
   */
  private def tryInsertInferredTargetCluster(target: KubernetesTarget): Target = {
    target.clusterOpt match {
      case Some(_: URI) => target
      case None =>
        WhereAmIHelper.getClusterUri match {
          case Some(clusterUri: URI) => Target.createKubernetesTarget(clusterUri, target.name)
          case None => target
        }
    }
  }

  /**
   * See specs for the external `Clerk.create` for details.
   *
   * This is factored into its own method so that the Dicer-Armeria integration can create a Clerk
   * by generating an [[InternalClientConfig]] from its own arguments rather than from a
   * [[ClerkConf]].
   *
   * @param secPoolOpt   If provided, the SEC pool to use for the clerk's async operations.
   *                     If None, a dedicated pool is created for this clerk.
   * @param protoLogger  The Clerk's proto logger.
   * @param reuseLookups If true, the [[SliceLookup]] instance for the given config is
   *                     reused from the cache. If false, a new [[SliceLookup]] instance
   *                     is created.
   */
  private def createInternal[Stub <: AnyRef](
      secPoolOpt: Option[SequentialExecutionContextPool],
      protoLogger: DicerClientProtoLogger,
      config: InternalClientConfig,
      clerkIndex: Int,
      factoryContext: ClerkFactoryContext,
      stubFactory: ResourceAddress => Stub,
      reuseLookups: Boolean): ClerkImpl[Stub] = {

    val sliceLookupConfig: SliceLookupConfig = config.sliceLookupConfig
    val subscriberDebugName: String = config.subscriberDebugName
    // Note: This SEC (and the proto logger created by the caller) are allocated unconditionally,
    // even when the lookup cache below returns a hit. Our intent for lookup caching is to protect
    // servers from being overloaded by misbehaving clients that create too many Clerks, but each
    // creation will still leak resources (threads) in the client.
    val sec: SequentialExecutionContext =
      createExecutor(secPoolOpt, sliceLookupConfig.target, clerkIndex, secNameSuffix = "")

    // TODO(<internal bug>): Once lookup reuse is rolled out everywhere, we should be able to retire the
    // reuseLookups flag usage in tests and instead use different clientIds to instantiate distinct
    // Clerks/SliceLookups.
    val lookup: SliceLookup = if (reuseLookups) {
      // Important things to note when we get a "cache hit".
      // 1. A hit generally indicates an error by the caller, as they should be creating Clerks
      //    once per target and reusing them.
      // 2. The lookup may already be started; calling `lookup.start()` later is a no-op in that
      //    case.
      // 3. The `subscriberDebugName` used by the lookup will not be the same as the one used for
      //    this ClerkImpl, creating some mismatches in the logs and z-pages.
      // 4. The SEC used by the lookup will not be the same as the one used for this ClerkImpl, so
      //    ClerkImpl code must not depend on running in the same concurrency domain as the lookup.
      lookupCache.getOrElseCreate(
        sliceLookupConfig,
        createLookup(sec, config, protoLogger)
      )
    } else {
      createLookup(sec, config, protoLogger)
    }

    val clerk = new ClerkImpl[Stub](
      sec,
      sliceLookupConfig.target,
      factoryContext,
      lookup,
      subscriberDebugName,
      stubFactory
    )
    clerk.start()
    clerk.logger.info(s"Starting Clerk, awaiting assignment from ${sliceLookupConfig.watchAddress}")
    clerk
  }

  /**
   * Creates an unstarted [[SliceLookup]] instance for the given configuration.
   *
   * @param sec         The [[SequentialExecutionContext]] for the lookup's async operations. Note
   *                    that [[SliceLookup]] is independently thread-safe and makes no assumptions
   *                    about the caller's concurrency domain; cached lookups may be used by
   *                    multiple Clerks in different SECs.
   * @param config      The client configuration containing target and watch address.
   * @param protoLogger The Clerk's proto logger.
   * @return An unstarted [[SliceLookup]] instance.
   */
  private def createLookup(
      sec: SequentialExecutionContext,
      config: InternalClientConfig,
      protoLogger: DicerClientProtoLogger
  ): SliceLookup = {
    SliceLookup.createUnstarted(
      sec,
      config,
      protoLogger,
      serviceBuilderOpt = None
    )
  }

  /**
   * Creates an executor used by an async/background part of the Clerk code. When `secPoolOpt` is
   * empty, it creates a dedicated pool for the SEC (this class is the "top"/ "main" class for the
   * Clerk and hence it may create threads). Otherwise, `secPoolOpt` is used to allow the caller to
   * inject a shared thread pool. The SEC is passed down to the background components of ClerkImpl
   * but is not used for the ClerkImpl's own isolation. The ClerkImpl is thread-safe because all
   * its internal components are thread-safe, and it doesn't hold any cross-component invariant.
   *
   * @param secNameSuffix A suffix to append to the SEC name, used to distinguish between different
   *                      SECs for the same clerk (e.g., "" for the main SEC, "-proto-logger" for
   *                      the proto logger SEC).
   */
  private def createExecutor(
      secPoolOpt: Option[SequentialExecutionContextPool],
      target: Target,
      clerkIndex: Int,
      secNameSuffix: String): SequentialExecutionContext = {
    val secName: String = s"ClerkExecutor-$target-$clerkIndex$secNameSuffix"
    secPoolOpt match {
      case Some(secPool: SequentialExecutionContextPool) =>
        SequentialExecutionContext.create(secPool, secName)
      case None =>
        // This execution context does not propagate the context to the threads it creates to avoid
        // the overhead of unnecessarily copying the context to background threads.
        SequentialExecutionContext.createWithDedicatedPool(
          secName,
          enableContextPropagation = false,
          alertOwnerTeam = AlertOwnerTeam.CachingTeam.toString
        )
    }
  }

  /**
   * Create a Clerk for the data plane.
   *
   * @param secPoolOpt      If provided, the SEC pool to use for the clerk's async operations.
   *                        If None, a dedicated pool is created for this clerk.
   * @param sharedProtoLoggerOpt  If provided, the shared proto logger to use by the Clerk.
   *                        If None, a dedicated proto logger is created for the Clerk.
   * @param clerkConf       The Clerk configuration.
   * @param target          The target to create the Clerk for.
   * @param assignerAddress The address of the assigner to create the Clerk for.
   * @param stubFactory     The factory to create the stub for the Clerk.
   * @param factoryContext Identifies the entry point used to create this Clerk, surfaced as a
   *                        label on Clerk metrics.
   * @param reuseLookups    If true, the [[SliceLookup]] instance for the given config is
   *                        reused from the cache. If false, a new [[SliceLookup]] instance
   *                        is created.
   */
  private def createForDataPlaneCommon[Stub <: AnyRef](
      secPoolOpt: Option[SequentialExecutionContextPool],
      sharedProtoLoggerOpt: Option[DicerClientProtoLogger],
      clerkConf: ClerkConf,
      target: Target,
      assignerAddress: URI,
      stubFactory: ResourceAddress => Stub,
      factoryContext: ClerkFactoryContext,
      reuseLookups: Boolean): ClerkImpl[Stub] = {
    target match {
      case kubernetesTarget: KubernetesTarget =>
        iassert(kubernetesTarget.clusterOpt.isDefined, "target must have the cluster URI populated")
      case _: AppTarget =>
        // AppTargets differentiate themselves from other instances with the same target name in
        // the same cluster as the assigner with their globally unique instance IDs.
        ()
    }

    val podName: String = InetAddress.getLocalHost.getHostName
    val clerkIndex: Int = assignNextClerkIndex()
    val clerkDebugName = s"C$clerkIndex-$target-$podName"

    val protoLogger: DicerClientProtoLogger = sharedProtoLoggerOpt.getOrElse(
      DicerClientProtoLogger.create(ClientType.Clerk, clerkConf, ownerName = clerkDebugName)
    )

    val config = InternalClientConfig(
      SliceLookupConfig(
        ClientType.Clerk,
        assignerAddress,
        clerkConf.getDicerClientTlsOptions,
        target,
        clientIdOpt = resolveClientUuid(clerkConf.clientUuidOpt, target),
        SliceLookupConfig.DEFAULT_WATCH_STUB_CACHE_TIME,
        watchFromDataPlane = true,
        // TODO(<internal bug>): Use client side feature flag to gradually rollout rate limiting.
        enableRateLimiting = false
      ),
      subscriberDebugName = clerkDebugName
    )

    Version.recordClientVersion(target, AssignmentMetricsSource.Clerk, clerkConf.branch)
    createInternal(
      secPoolOpt,
      protoLogger,
      config,
      clerkIndex,
      factoryContext,
      stubFactory,
      reuseLookups
    )
  }

  /**
   * Parses a UUID from the given string if present. Returns None if the string is absent or
   * malformed. Records a metric tracking the resolution status.
   *
   * TODO(<internal bug>): Make the clientUuid required once all Dicer client deployments are confirmed to
   * set POD_UID (i.e. throw an exception if clientUuidOpt is absent).
   */
  private def resolveClientUuid(uuidStrOpt: Option[String], target: Target): Option[UUID] = {
    val (uuidOpt, status): (Option[UUID], ClientMetrics.ClientUuidStatus) = uuidStrOpt match {
      case None =>
        (None, ClientMetrics.ClientUuidStatus.Missing)
      case Some(uuidStr: String) =>
        try {
          (Some(UUID.fromString(uuidStr)), ClientMetrics.ClientUuidStatus.Valid)
        } catch {
          case _: IllegalArgumentException =>
            logger.error(s"Malformed client UUID: $uuidStr")
            (None, ClientMetrics.ClientUuidStatus.Malformed)
        }
    }
    ClientMetrics.recordClientUuidStatus(target, ClientType.Clerk, status)
    uuidOpt
  }

  /** Assigns an index number for the next Clerk to be created. */
  private def assignNextClerkIndex(): Int = nextClerkIndex.getAndIncrement()

  /**
   * Variable that keeps track of the index number of the next Clerk to be created. Primarily,
   * for debugging purposes - can be used in subscriber name.
   */
  private val nextClerkIndex = new AtomicInteger()

}

/**
 * An [[Assignment]] augmented with precomputed [[ConsistentHashRing]]s for multi-replica slices,
 * used for efficiently serving two-level sharding lookups. The primary key identifies the slice
 * and the set of resources it is assigned to, and the secondary key picks a specific resource
 * among those using the corresponding hash ring.
 *
 * @param assignment        the underlying Dicer assignment.
 * @param hashRingsBySlice  per-slice consistent hash rings, populated only for slices with more
 *                          than one indexed resource.
 */
private class ClerkAssignment private (
    val assignment: Assignment,
    val hashRingsBySlice: immutable.Map[Slice, ConsistentHashRing[Squid, SliceKey]]) {

  object forTest {

    /**
     * Checks that [[hashRingsBySlice]] contains entries for only the multi-replica slices in
     * [[assignment]] and that the nodes on a hash ring are the same as the resources for the
     * corresponding slice in the assignment.
     */
    def checkInvariants(): Unit = {
      val multipleReplicaSliceAssignments: Vector[SliceAssignment] =
        assignment.sliceMap.entries.filter { sliceAssignment: SliceAssignment =>
          sliceAssignment.indexedResources.size > 1
        }

      // Only multi-replica slices should have hash rings.
      val multipleReplicaSlices: Set[Slice] =
        multipleReplicaSliceAssignments
          .map(
            (sliceAssignment: SliceAssignment) => sliceAssignment.slice
          )
          .toSet
      iassert(
        hashRingsBySlice.keySet == multipleReplicaSlices,
        s"hashRingsBySlice keys ${hashRingsBySlice.keySet} do not match multi-replica slices " +
        s"$multipleReplicaSlices"
      )

      // The nodes on the hash rings should be the same as the resources for that slice in the
      // assignment.
      for (sliceAssignment: SliceAssignment <- multipleReplicaSliceAssignments) {
        val ring: ConsistentHashRing[Squid, SliceKey] = hashRingsBySlice(sliceAssignment.slice)
        val expectedNodes: Vector[Squid] = sliceAssignment.indexedResources
        iassert(
          ring.nodes == expectedNodes,
          s"ring nodes ${ring.nodes} do not match resources $expectedNodes"
        )
      }
    }
  }
}

private object ClerkAssignment {

  /** Default number of virtual nodes per physical node on the two-level sharding ring. */
  private val VNODES_PER_RESOURCE: Int = 128

  /**
   * Maps [[Squid]] and [[SliceKey]] to their respective byte representations that the
   * [[ConsistentHashRing]] hashes.
   */
  private object RingTypeMapper extends ConsistentHashRing.TypeMapper[Squid, SliceKey] {

    /** Maps a [[Squid]] to its 16-byte big-endian encoding of its [[Squid.resourceUuid]]. */
    override def mapNode(node: Squid): ByteString = {
      val uuid: UUID = node.resourceUuid
      ByteString.copyFrom(
        ByteBuffer
          .allocate(16)
          .putLong(uuid.getMostSignificantBits)
          .putLong(uuid.getLeastSignificantBits)
          .array()
      )
    }

    /** Maps a [[SliceKey]] to its raw bytes. */
    override def mapKey(key: SliceKey): ByteString = key.toRawBytes
  }

  /**
   * Builds a [[ClerkAssignment]] wrapping `assignment` together with the consistent hash rings
   * precomputed for each multi-replica slice.
   */
  def create(assignment: Assignment): ClerkAssignment = {
    val sliceToRingBuilder = Map.newBuilder[Slice, ConsistentHashRing[Squid, SliceKey]]
    for (sliceAssignment: SliceAssignment <- assignment.sliceMap.entries) {
      if (sliceAssignment.indexedResources.size > 1) {
        val ring: ConsistentHashRing[Squid, SliceKey] =
          ConsistentHashRing.create[Squid, SliceKey](
            nodes = sliceAssignment.indexedResources,
            vnodesPerNode = VNODES_PER_RESOURCE,
            typeMapper = RingTypeMapper
          )
        sliceToRingBuilder += (sliceAssignment.slice -> ring)
      }
    }
    new ClerkAssignment(assignment, sliceToRingBuilder.result())
  }
}

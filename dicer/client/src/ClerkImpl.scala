package com.databricks.dicer.client

import java.net.{InetAddress, URI}
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}

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
  AssignerServiceInfo,
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
  SliceKey,
  Target,
  Slice
}
import com.databricks.dicer.friend.{SliceMap, Squid}
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
      val previousAssignmentOpt: Option[Assignment] = clerkAssignmentCell.getLatestValueOpt.map(
        (clerkAssignment: ClerkAssignment) => clerkAssignment.assignment
      )
      // Suppress known spurious wakeups where the recorded ClerkAssignment is at least as new as
      // the new `assignment`.
      val latestKnownGeneration: Generation = previousAssignmentOpt
        .map((previousAssignment: Assignment) => previousAssignment.generation)
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
            AssignmentMetricsSource.Clerk,
            previousAssignmentOpt.flatMap(_.assignerServiceInfoOpt),
            assignment.assignerServiceInfoOpt
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
    clerkMetrics.incrementClerkGetStubForKeyCallCount(secondaryKeyProvided = false)
    resourceRouter.getStubForKey(key)
  }

  /**
   * Two-level sharding variant of [[getStubForKey]]. See specs for
   * [[com.databricks.dicer.friend.external.TwoLevelShardingClerkAccessor.getStubForKey]]. If it is
   * called after [[stop]], the returned stub may not be to the most recently assigned resource.
   */
  def getStubForKey(primaryKey: SliceKey, secondaryKey: SliceKey): Option[Stub] = {
    clerkMetrics.incrementClerkGetStubForKeyCallCount(secondaryKeyProvided = true)
    resourceRouter.getStubForKey(primaryKey, secondaryKey)
  }

  /** See [[ResourceRouter.getNextStubForKey]] for spec details. */
  def getNextStubForKey(
      key: SliceKey,
      retryTokenOpt: Option[RetryTokenImpl]): Option[(Stub, RetryTokenImpl)] = {
    // TODO(<internal bug>): Add a getNextStubForKey metric and increment it here.
    resourceRouter.getNextStubForKey(key, retryTokenOpt)
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
    val latestAssignerServiceInfoOpt: Option[AssignerServiceInfo] =
      clerkAssignmentCell.getLatestValueOpt.flatMap(
        (clerkAssignment: ClerkAssignment) => clerkAssignment.assignment.assignerServiceInfoOpt
      )
    ClientMetrics.removeGaugesForTarget(
      target,
      AssignmentMetricsSource.Clerk,
      latestAssignerServiceInfoOpt
    )
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
        // We currently have no way to automatically infer the alternativeTargetOpt for the
        // Clerk's target. This depends on support from DBNS, which will be the source-of-truth
        // for this information. Until that functionality is available, it is OK to leave
        // alternativeTargetOpt unpopulated.
        // - Slicelets handling Clerk requests without alternativeTargetOpt will just respond with
        //   their current assignments.
        // - Clerks directly watching the Assigner will be manually updated to populate the
        //   alternativeTargetOpt as part of the AppTarget migration.
        // TODO(<internal bug>): Populate alternativeTargetOpt once DBNS can supply it for the target.
        alternativeTargetOpt = None,
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
        // We currently have no way to automatically infer the alternativeTargetOpt for the
        // Clerk's target. This depends on support from DBNS, which will be the source-of-truth
        // for this information. Until that functionality is available, it is OK to leave
        // alternativeTargetOpt unpopulated.
        // - Slicelets handling Clerk requests without alternativeTargetOpt will just respond with
        //   their current assignments.
        // - Clerks directly watching the Assigner will be manually updated to populate the
        //   alternativeTargetOpt as part of the AppTarget migration.
        // TODO(<internal bug>): Populate alternativeTargetOpt once DBNS can supply it for the target.
        alternativeTargetOpt = None,
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
          name = secName,
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
        // We currently have no way to automatically infer the alternativeTargetOpt for the
        // Clerk's target. This depends on support from DBNS, which will be the source-of-truth
        // for this information. Until that functionality is available, it is OK to leave
        // alternativeTargetOpt unpopulated.
        // - Slicelets handling Clerk requests without alternativeTargetOpt will just respond with
        //   their current assignments.
        // - Clerks directly watching the Assigner will be manually updated to populate the
        //   alternativeTargetOpt as part of the AppTarget migration.
        // TODO(<internal bug>): Populate alternativeTargetOpt for Direct Clerks
        alternativeTargetOpt = None,
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
 * The client-side routing information for a [[Slice]] used to serve [[ResourceRouter]] two-level
 * sharding getStubForKey and getNextStubForKey.
 *
 * @param slice the slice being served by this SliceInfo.
 * @param twoLevelHashRingOpt consistent hash ring for two-level sharding, populated only for slices
 *                        with more than one assigned resource.
 * @param fallbackSquidOpt the fallback squid for the slice, or None if the assignment has no squid
 *                         that is unassigned to this slice. One fallback squid is constructed per
 *                         slice to bound the blast radius of fallback traffic. For example, in the
 *                         key-of-death scenario, a key-of-death can spread to at most one other
 *                         slicelet. TODO(<internal bug>): Freeze fallback when key-of-death is detected
 *                         to minimize the blast radius.
 */
private case class SliceInfo(
    slice: Slice,
    twoLevelHashRingOpt: Option[ConsistentHashRing[Squid, SliceKey]],
    fallbackSquidOpt: Option[Squid])

/**
 * An [[Assignment]] augmented with precomputed per-slice routing information (a two-level sharding
 * hash ring and a fallback squid per slice). Recreated on every assignment update.
 *
 * @param assignment   The underlying Dicer assignment.
 * @param sliceInfoMap A sliceMap with client-side metadata computed for each assignment received.
 */
private class ClerkAssignment private (
    val assignment: Assignment,
    val sliceInfoMap: SliceMap[SliceInfo]) {

  object forTest {

    /**
     * 1. Checks that [[sliceInfoMap]] has a hash ring ONLY for multi-replica slices
     * and that the nodes on a hash ring are the same as the slice's assigned resources
     * in [[assignment]].
     *
     * 2. Checks that `assignment.sliceMap` boundaries match [[sliceInfoMap]] slice boundaries.
     *
     * 3. Checks that a [[SliceInfo]] has a fallback squid if and only if the assignment has at
     * least one resource unassigned to the slice.
     *
     * 4. Checks that each sliceInfo's fallback squid, if present, is one of the assignment's
     * assigned resources, and is not assigned to the slice itself.
     *
     */
    def checkInvariants(): Unit = {
      iassert(assignment.sliceMap.entries.size == sliceInfoMap.entries.size)
      for (sliceAssignment: SliceAssignment <- assignment.sliceMap.entries) {
        val sliceInfo: SliceInfo = sliceInfoMap.lookUp(sliceAssignment.slice.lowInclusive)
        iassert(sliceInfo.slice == sliceAssignment.slice)
        val isMultiReplica: Boolean = sliceAssignment.resources.size > 1
        iassert(
          sliceInfo.twoLevelHashRingOpt.isDefined == isMultiReplica,
          s"slice ${sliceInfo.slice} hash ring presence " +
          s"${sliceInfo.twoLevelHashRingOpt.isDefined} " +
          s"doesn't match multi-replica status $isMultiReplica"
        )
        for (ring: ConsistentHashRing[Squid, SliceKey] <- sliceInfo.twoLevelHashRingOpt) {
          iassert(
            ring.nodes.toSet == sliceAssignment.resources,
            s"ring nodes ${ring.nodes.toSet} do not match sliceAssignment resources " +
            s"${sliceAssignment.resources}"
          )
        }
        // Whether the assignment has at least one resource unassigned to `sliceAssignment.slice`.
        val assignmentHasFallbackResource: Boolean =
          assignment.assignedResources.exists(!sliceAssignment.resources.contains(_))
        iassert(sliceInfo.fallbackSquidOpt.isDefined == assignmentHasFallbackResource)

        sliceInfo.fallbackSquidOpt match {
          case Some(fallbackSquid: Squid) =>
            iassert(assignment.assignedResources.contains(fallbackSquid))
            iassert(!sliceAssignment.resources.contains(fallbackSquid))
          case None => ()
        }
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
   * Builds a [[ClerkAssignment]] wrapping `assignment` together with the per-slice routing
   * information (two-level sharding hash ring, fallback squid) precomputed for each slice.
   */
  def create(assignment: Assignment): ClerkAssignment = {
    val sliceAssignments: Vector[SliceAssignment] = assignment.sliceMap.entries

    // A map of a slice to the slice's fallback squid (if any)
    val sliceToFallbackSquid: Map[Slice, Squid] = buildSliceFallbackSquidMap(assignment)

    val sliceInfoEntries: Vector[SliceInfo] =
      sliceAssignments.map {
        case (sliceAssignment: SliceAssignment) =>
          val twoLevelHashRingOpt: Option[ConsistentHashRing[Squid, SliceKey]] =
            if (sliceAssignment.indexedResources.size > 1) {
              Some(
                ConsistentHashRing.create[Squid, SliceKey](
                  nodes = sliceAssignment.indexedResources,
                  vnodesPerNode = VNODES_PER_RESOURCE,
                  typeMapper = RingTypeMapper
                )
              )
            } else {
              None
            }
          SliceInfo(
            sliceAssignment.slice,
            twoLevelHashRingOpt,
            sliceToFallbackSquid.get(sliceAssignment.slice)
          )
      }

    /**
     * [[SliceMap.validateCompleteSlices]] validates that `sliceInfoEntries` are valid
     * (i.e. ordered, disjoint, and cover the full SliceKey space)
     */
    val sliceMap: SliceMap[SliceInfo] =
      new SliceMap[SliceInfo](sliceInfoEntries, getSlice = (_: SliceInfo).slice)
    new ClerkAssignment(assignment, sliceMap)
  }

  /**
   * Builds a map from each slice in `assignment.sliceMap` to the fallback squid chosen for that
   * slice, used as the fallback resource by [[ResourceRouter]]. A slice has a fallback squid if
   * and only if the assignment has at least one resource unassigned to the slice.
   *
   * A consistent hash ring is built over all of the assignment's resources. For each slice a walk
   * starts at the resource owning the `slice.lowInclusive` key and returns the first resource it
   * encounters on the clock-wise walk that is not one of the slice's assigned resources. Using a
   * consistent hashing algorithm (rather than picking a random unassigned squid) ensures every
   * clerk picks the same fallback squid for a slice to ensure fallback traffic is affinitized.
   *
   * Only a single fallback squid is collected per slice today. This could be extended to collect a
   * list of unassigned squids to fall back through, but we keep it to one for now, partly to
   * limit the blast radius of a key-of-death scenario where such a key could take down all the
   * fallback squids for a slice.
   *
   * @param assignment The assignment to construct fallback squids for.
   * @return A map from a slice to its fallback squid, omitting slices that have none.
   */
  private def buildSliceFallbackSquidMap(assignment: Assignment): Map[Slice, Squid] = {
    val assignmentResources: Vector[Squid] = assignment.assignedResources.toVector
    val sliceAssignments: Vector[SliceAssignment] = assignment.sliceMap.entries

    val fallbackRing: ConsistentHashRing[Squid, SliceKey] =
      ConsistentHashRing.create[Squid, SliceKey](
        nodes = assignmentResources,
        // `vnodesPerNode` is irrelevant since the ring is not used for balancing keys across the
        // ring. It is only used to construct a deterministic mapping from a slice to a resource.
        vnodesPerNode = 1,
        typeMapper = RingTypeMapper
      )

    sliceAssignments.iterator.flatMap {
      case sliceAssignment: SliceAssignment =>
        // Skip for slices that are assigned to all resources.
        if (sliceAssignment.resources.size == assignmentResources.size) {
          None
        } else {
          // Walk the ring from the resource owning the `slice.lowInclusive` key and take the first
          // resource that is not one of the slice's own resources.
          // Note: This walk has a bias in the case where the `slice.lowInclusive` key lands on an
          // assigned resource, as it will always return the next unassigned resource on the ring.
          // This is acceptable because balancing load across fallback squids is not a requirement.
          fallbackRing
            .lookupIterator(key = sliceAssignment.slice.lowInclusive)
            .find((squid: Squid) => !sliceAssignment.resources.contains(squid))
            .map((squid: Squid) => sliceAssignment.slice -> squid)
        }
    }.toMap
  }
}

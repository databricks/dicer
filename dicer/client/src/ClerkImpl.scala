package com.databricks.dicer.client

import java.net.{InetAddress, URI}
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}

import javax.annotation.concurrent.ThreadSafe

import com.databricks.caching.util.AssertMacros.iassert
import com.databricks.caching.util.{
  PrefixLogger,
  SequentialExecutionContext,
  SequentialExecutionContextPool,
  ValueStreamCallback,
  WhereAmIHelper
}
import com.databricks.dicer.common.{
  Assignment,
  AssignmentMetricsSource,
  ClerkData,
  ClientType,
  Version
}
import com.databricks.dicer.external.{
  AppTarget,
  ClerkConf,
  KubernetesTarget,
  ResourceAddress,
  SliceKey,
  Target
}
import com.databricks.rpc.tls.TLSOptions

/**
 * The implementation for the Clerk.
 *
 * @param sec                 Execution context used to run the callbacks when the Clerk receives an
 *                            assignment.
 * @param target              See [[Clerk.create]].
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
    lookup: SliceLookup,
    subscriberDebugName: String,
    stubFactory: ResourceAddress => Stub) {

  private val logger = PrefixLogger.create(getClass, subscriberDebugName)

  /**
   * ResourceRouter for caching the mapping from resource addresses to application-defined stubs
   * (e.g. RPC stubs).
   */
  private val resourceRouter =
    new ResourceRouter[Stub](
      lookup.cellConsumer,
      logPrefix = s"Router-$target",
      stubFactory,
      stubCacheLifetime = 1.hour
    )

  /** A promise that is set when the initial assignment is received. */
  private val assignmentReceived = Promise[Unit]

  /** Callback methods for the Clerk. */
  private object ClerkWatchCallback extends ValueStreamCallback[Assignment](sec) {

    protected override def onSuccess(assignment: Assignment): Unit = {
      // An initial assignment (at least!) has been received. Make sure the assignmentReceived
      // promise is completed.
      if (assignmentReceived.trySuccess(())) {
        logger.info(s"Initial clerk assignment received: ${assignment.generation}")
      }
      ClientMetrics.updateOnNewAssignment(
        assignment.generation,
        target,
        AssignmentMetricsSource.Clerk
      )
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
    resourceRouter.getStubForKey(key)
  }

  /**
   * Stops all the asynchronous activities (e.g., cancels the [[SliceLookup]] that communicates with
   * the remote service to obtain assignments and incorporates new assignments). Also unregisters
   * Slicez. Note that this method stops the Clerk asynchronously. Although other methods may still
   * be called after [[stop]], the Clerk becomes inert and no longer receives assignment updates.
   */
  def stop(): Unit = {
    lookup.cancel()
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
      lookup.cellConsumer.getLatestValueOpt

    /** Injects an assignment in the Clerk. */
    def injectAssignment(assignment: Assignment): Unit = {
      lookup.forTest.injectAssignment(assignment)
    }
  }
}

/** Companion object for [[ClerkImpl]]. */
private[dicer] object ClerkImpl {

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

    val podName: String = InetAddress.getLocalHost.getHostName
    val clerkIndex: Int = assignNextClerkIndex()
    val clerkDebugName = s"C$clerkIndex-$targetBestEffortFullyQualified-$podName"

    Version.recordClientVersion(
      targetBestEffortFullyQualified,
      AssignmentMetricsSource.Clerk,
      clerkConf.branch
    )

    val config: InternalClientConfig = InternalClientConfig(
      ClientType.Clerk,
      watchAddress,
      clerkConf.getDicerClientTlsOptions,
      targetBestEffortFullyQualified,
      clerkConf.watchStubCacheTimeSeconds.seconds,
      watchFromDataPlane = false,
      enableRateLimiting = clerkConf.enableClerkRateLimiting
    )

    createInternal(
      secPoolOpt = None,
      protoLoggerSecPoolOpt = None,
      config,
      clerkIndex,
      clerkDebugName,
      stubFactory
    )
  }

  /**
   * PRECONDITION: `target` must have the cluster URI populated.
   *
   * Creates a clerk that directly watches the Assigner from the data plane. This is for supporting
   * internal-system and internal-system use cases specifically before the Rust Slicelet is able to
   *
   * @param secPoolOpt            If provided, the SEC pool to use for the clerk's async operations
   *                              other than proto logging. If None, a dedicated pool is created for
   *                              this clerk.
   * @param protoLoggerSecPoolOpt If provided, the SEC pool to use for the proto logger's async
   *                              operations. If None, a dedicated pool is created for this clerk's
   *                              proto logger.
   */
  def createForDataPlaneDirectClerk[Stub <: AnyRef](
      secPoolOpt: Option[SequentialExecutionContextPool],
      protoLoggerSecPoolOpt: Option[SequentialExecutionContextPool],
      clerkConf: ClerkConf,
      target: Target,
      assignerAddress: URI,
      stubFactory: ResourceAddress => Stub): ClerkImpl[Stub] = {
    createForDataPlaneCommon(
      secPoolOpt,
      protoLoggerSecPoolOpt,
      clerkConf,
      target,
      assignerAddress,
      stubFactory
    )
  }

  /**
   * PRECONDITION: `target` must have the cluster URI populated.
   *
   * Creates a clerk for use in a MultiClerk setup, where multiple clerks share execution context
   * pools and watch the Assigner from the control plane.
   *
   * @param secPoolOpt            If provided, the SEC pool to use for the clerk's async operations
   *                              other than proto logging. If None, a dedicated pool is created for
   *                              this clerk.
   * @param protoLoggerSecPoolOpt If provided, the SEC pool to use for the proto logger's async
   *                              operations. If None, a dedicated pool is created for this clerk's
   *                              proto logger.
   */
  def createForMultiClerk[Stub <: AnyRef](
      secPoolOpt: Option[SequentialExecutionContextPool],
      protoLoggerSecPoolOpt: Option[SequentialExecutionContextPool],
      clerkConf: ClerkConf,
      target: Target,
      assignerAddress: URI,
      stubFactory: ResourceAddress => Stub): ClerkImpl[Stub] = {
    createForDataPlaneCommon(
      secPoolOpt,
      protoLoggerSecPoolOpt,
      clerkConf,
      target,
      assignerAddress,
      stubFactory
    )
  }

  /**
   * Creates and returns a new [[ClerkImpl]] specifically for a Dicer-integrated stub.
   */
  // TODO(<internal bug>): Enforce the singleton Clerk constraint.
  def createForShardedStub(
      target: Target,
      watchAddress: URI,
      tlsOptions: Option[TLSOptions]
  ): ClerkImpl[ResourceAddress] = {
    // See https://src.dev.databricks.com/databricks-eng/universe@e71a7d85903f5d32801235994951c33ddb2629c0/-/blob/dicer/external/src/Conf.scala?L72-80
    // for why we use this particular value. ShardedStubs don't offer a way to customize the
    // internal Clerk-Slicelet connection idle timeout, so we simply hardcode it.
    val watchStubCacheTime: FiniteDuration = 5.minutes

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
      ClientType.Clerk,
      watchAddress,
      tlsOptions,
      targetBestEffortFullyQualified,
      watchStubCacheTime,
      watchFromDataPlane = false,
      // TODO(<internal bug>): Enable rate limiting for the sharded stub.
      enableRateLimiting = false
    )
    createInternal(
      secPoolOpt = None,
      protoLoggerSecPoolOpt = None,
      config,
      clerkIndex,
      clerkDebugName,
      stubFactory = (resourceAddress: ResourceAddress) => resourceAddress
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
   * @param secPoolOpt            If provided, the SEC pool to use for the clerk's async operations
   *                              other than proto logging. If None, a dedicated pool is created for
   *                              this clerk.
   * @param protoLoggerSecPoolOpt If provided, the SEC pool to use for the proto logger's async
   *                              operations. If None, a dedicated pool is created for this clerk's
   *                              proto logger.
   */
  private def createInternal[Stub <: AnyRef](
      secPoolOpt: Option[SequentialExecutionContextPool],
      protoLoggerSecPoolOpt: Option[SequentialExecutionContextPool],
      config: InternalClientConfig,
      clerkIndex: Int,
      subscriberDebugName: String,
      stubFactory: ResourceAddress => Stub): ClerkImpl[Stub] = {

    val sec: SequentialExecutionContext =
      createExecutor(secPoolOpt, config.target, clerkIndex, secNameSuffix = "")
    val protoLoggerSec: SequentialExecutionContext =
      createExecutor(
        protoLoggerSecPoolOpt,
        config.target,
        clerkIndex,
        secNameSuffix = "-proto-logger"
      )

    val protoLogger: DicerClientProtoLogger = DicerClientProtoLogger.create(
      conf = DicerClientProtoLoggerConf,
      clientType = config.clientType,
      subscriberDebugName = subscriberDebugName,
      sec = protoLoggerSec
    )

    val lookup = SliceLookup.createUnstarted(
      sec,
      config,
      subscriberDebugName,
      protoLogger,
      serviceBuilderOpt = None
    )

    val clerk = new ClerkImpl[Stub](
      sec,
      config.target,
      lookup,
      subscriberDebugName,
      stubFactory
    )
    clerk.start()
    clerk.logger.info(s"Starting Clerk, awaiting assignment from ${config.watchAddress}")
    clerk
  }

  /**
   * Creates an executor used by an async/background part of the Clerk code. When `secPoolOpt` is
   * empty, it creates a dedicated pool for the SEC (this class is the "top"/ "main" class for the
   * Clerk and hence it may create threads). Otherwise, `secPoolOpt` is used to allow the caller to
   * inject a shared thread pool. The SEC is passed down to the background components of ClerkImpl
   * but is not used for the ClerkImpl's own isolation. The ClerkImpl is thread-safe because all its
   * components are thread-safe, and it doesn't hold any cross-component invariant.
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
          enableContextPropagation = false
        )
    }
  }

  /**
   * Create a Clerk for the data plane.
   *
   * @param secPoolOpt            If provided, the SEC pool to use for the clerk's async operations
   *                              other than proto logging. If None, a dedicated pool is created
   *                              for this clerk.
   * @param protoLoggerSecPoolOpt If provided, the SEC pool to use for the proto logger's async
   *                              operations. If None, a dedicated pool is created for this clerk's
   *                              proto logger.
   * @param clerkConf             The Clerk configuration.
   * @param target                The target to create the Clerk for.
   * @param assignerAddress       The address of the assigner to create the Clerk for.
   * @param stubFactory           The factory to create the stub for the Clerk.
   * @return The created Clerk.
   */
  private def createForDataPlaneCommon[Stub <: AnyRef](
      secPoolOpt: Option[SequentialExecutionContextPool],
      protoLoggerSecPoolOpt: Option[SequentialExecutionContextPool],
      clerkConf: ClerkConf,
      target: Target,
      assignerAddress: URI,
      stubFactory: ResourceAddress => Stub): ClerkImpl[Stub] = {
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

    val config = InternalClientConfig(
      ClientType.Clerk,
      assignerAddress,
      clerkConf.getDicerClientTlsOptions,
      target,
      clerkConf.watchStubCacheTimeSeconds.seconds,
      watchFromDataPlane = true,
      enableRateLimiting = clerkConf.enableClerkRateLimiting
    )

    Version.recordClientVersion(target, AssignmentMetricsSource.Clerk, clerkConf.branch)
    createInternal(
      secPoolOpt,
      protoLoggerSecPoolOpt,
      config,
      clerkIndex,
      clerkDebugName,
      stubFactory
    )
  }

  /** Assigns an index number for the next Clerk to be created. */
  private def assignNextClerkIndex(): Int = nextClerkIndex.getAndIncrement()

  /**
   * Variable that keeps track of the index number of the next Clerk to be created. Primarily,
   * for debugging purposes - can be used in subscriber name.
   */
  private val nextClerkIndex = new AtomicInteger()

}

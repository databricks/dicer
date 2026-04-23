package com.databricks.dicer.client

import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.matching.Regex

import io.grpc.Status

import com.databricks.dicer.common.ClientType

import com.databricks.api.proto.dicer.common.ClientRequestP.SliceletDataP
import com.databricks.caching.util.InstantiationTracker.{
  PerProcessSingleton,
  PerProcessSingletonType
}
import com.databricks.caching.util.{
  AlertOwnerTeam,
  Cancellable,
  GenericRpcServiceBuilder,
  InstantiationTracker,
  PrefixLogger,
  RealtimeTypedClock,
  SequentialExecutionContext,
  UnixTimeVersion,
  ValueStreamCallback,
  WatchValueCell,
  WatchValueCellPollAdapter,
  WhereAmIHelper
}
import com.databricks.common.util.ShutdownHookManager
import com.databricks.dicer.client.SliceletImpl.SLICELET_LOAD_ACCUMULATOR_UPDATE_METRICS_INTERVAL
import com.databricks.dicer.common.{
  Assignment,
  AssignmentMetricsSource,
  ClientType,
  Generation,
  SliceSetImpl,
  SliceletData,
  SliceletState,
  SubsliceAnnotationsMap,
  Version,
  WatchServerHelper
}
import com.databricks.dicer.external.{
  AppTarget,
  KubernetesTarget,
  ResourceAddress,
  SliceKey,
  SliceletConf,
  SliceletListener,
  Target
}
import com.databricks.dicer.friend.ShutdownHookConstants.SLICELET_TERMINATION_SHUTDOWN_HOOK_PRIORITY
import com.databricks.dicer.friend.SliceMap.GapEntry
import com.databricks.dicer.friend.{SliceWithStateProvider, Squid}
import com.databricks.rpc.DatabricksServerWrapper
import com.databricks.rpc.armeria.ReadinessProbeTracker
import javax.annotation.concurrent.GuardedBy

/** Implementation of `Slicelet`. */
private[dicer] class SliceletImpl private (
    sec: SequentialExecutionContext,
    sliceletConf: SliceletConf,
    config: InternalClientConfig,
    subscriberDebugName: String,
    sliceletHostName: String,
    sliceletUuid: UUID,
    server: DatabricksServerWrapper,
    private[client] val metrics: SliceletMetrics,
    private[client] val loadAccumulator: SliceletLoadAccumulator,
    lookup: SliceletSliceLookup) {

  private val sliceLookupConfig: SliceLookupConfig = config.sliceLookupConfig

  private val logger = PrefixLogger.create(getClass, subscriberDebugName)

  metrics.recordLocationInfo()

  Version.recordClientVersion(target, AssignmentMetricsSource.Slicelet, sliceletConf.branch)

  /**
   * Dedicated sequential execution context used to make upcalls to the application code: sequential
   * because we promise the callbacks will be made serially; dedicated because we don't want to
   * block the Dicer client library's internal threads when calling user code.
   */
  private val listenerSec =
    SequentialExecutionContext.createWithDedicatedPool("SliceletListenerExecutor")

  /** Lower bound on the latest generation known to the listener. */
  @GuardedBy("listenerSec")
  private var listenerGenerationLowerBound = Generation.EMPTY

  /** Handle for the background task which updates Prometheus metrics. */
  private val backgroundUpdateMetricsHandle: Cancellable = sec.scheduleRepeating(
    "updateMetrics",
    interval = SLICELET_LOAD_ACCUMULATOR_UPDATE_METRICS_INTERVAL,
    () => loadAccumulator.updateMetrics()
  )

  /**
   * Create a handle that can be used to track ownership of a key during an in-progress operation.
   * See the comment for [[SliceKeyHandleImpl]] for more details.
   */
  def createHandle(key: SliceKey): SliceKeyHandleImpl = {
    new SliceKeyHandleImpl(this, key, lookup.latestSliceletAssignment.generationOrEmpty)
  }

  /** See `Slicelet.assignedSlices`. */
  def assignedSlicesSet: SliceSetImpl = lookup.latestSliceletAssignment.assignedSlicesSet

  /**
   * REQUIRES: [[start]] has been called.
   *
   * Globally unique identifier for the current Slicelet incarnation.
   */
  def squid: Squid = lookup.squidOrThrowIfUnstarted()

  /** The sharded target. */
  def target: Target = sliceLookupConfig.target

  /** See `Slicelet.start()`. */
  def start(selfPort: Int, listenerOpt: Option[SliceletListener]): Unit = {
    // Register a callback so that we can inform the listener (if any) of changes to the assignment.
    lookup.watch(new ValueStreamCallback[SliceletAssignment](listenerSec) {

      override protected def onSuccess(ignored: SliceletAssignment): Unit = {
        // The assignment passed to the callback is `ignored` in favor of whatever the most recent
        // assignment is from `lookup`.
        listenerSec.assertCurrentContext()
        val latestGeneration: Generation = lookup.latestSliceletAssignment.generationOrEmpty
        if (latestGeneration <= listenerGenerationLowerBound) {
          // Known spurious wakeup, e.g. due to collapsed consecutive assignment signals. While we
          // can't reliably detect all spurious wakeups, we can at least suppress signals that are
          // clearly spurious.
          logger.debug(s"Spurious wakeup for $latestGeneration <= $listenerGenerationLowerBound")
        } else {
          for (listener: SliceletListener <- listenerOpt) {
            try {
              listener.onAssignmentUpdated()
            } catch {
              case NonFatal(exception: Throwable) =>
                logger.warn(
                  s"Listener $listener threw exception on assignment update for " +
                  s"$latestGeneration: $exception",
                  every = 1.minute
                )
            }
          }
          // `latestGeneration`is a lower bound on what the listener knows because by the time it
          // gets around to reading its assignment from the Slicelet in `onAssignmentUpdated`, the
          // assignment may have advanced again. For this reason, the listener may get spurious
          // calls even when, from its perspective, the assignment has not changed. We update the
          // lower bound even when the listener throws to avoid giving the illusion that we retry
          // on failures.
          listenerGenerationLowerBound = latestGeneration
        }
      }
    })

    // Now start the server and lookup.
    // Implementation note: there are some use cases where it would be reasonable to have one
    // Slicelet per target rather than per process, but we have yet to have a need for this. We
    // would need to refactor the way in which we start the servers below, as we'll otherwise get
    // an error attempting to bind to the same port when creating more Slicelets.
    server.start()
    val resourceAddress = ResourceAddress(URI.create(s"https://$sliceletHostName:$selfPort"))

    lookup.start(sliceletUuid, resourceAddress)
    logger.info(
      s"Started Slicelet ($sliceletUuid) with address = $resourceAddress communicating with " +
      s"${sliceLookupConfig.watchAddress}. Watch requests handled on port ${server.activePort()}."
    )
  }

  /** See `SliceletAccessor.assignedSlicesWithStateProvider`. */
  def assignedSlicesWithStateProvider(): Seq[SliceWithStateProvider] = {
    val subsliceAnnotationsMap: SubsliceAnnotationsMap =
      lookup.latestSliceletAssignment.subsliceAnnotationsMap
    // Go through the non-gap Slices and convert the `SliceWithAnnotations` to
    // `SliceWithStateProvider`.
    subsliceAnnotationsMap.entries.flatMap {
      case GapEntry.Some(value: SubsliceAnnotationsMap.SliceWithAnnotations) =>
        Some(
          SliceWithStateProvider(
            value.slice,
            value.stateProviderOpt.map { squid: Squid =>
              squid.resourceAddress
            }
          )
        )
      case GapEntry.Gap(_) => None
    }
  }

  /** See `SliceletAccessor.getKnownResourceAddresses`. */
  def getKnownResourceAddresses: Set[ResourceAddress] = {
    lookup.latestSliceletAssignment.assignmentOpt
      .map { assignment: Assignment =>
        assignment.assignedResources.map { squid: Squid =>
          squid.resourceAddress
        }
      }
      .getOrElse(Set.empty)
  }

  override def toString: String = subscriberDebugName

  /**
   * Get the first generation when `key` was assigned to `resourceAddress`, or [[Generation.EMPTY]]
   * if it is not currently assigned to `resourceAddress`.
   */
  private[client] def getContinuouslyAssignedGeneration(key: SliceKey): Generation = {
    val latestSliceletAssignment: SliceletAssignment = lookup.latestSliceletAssignment
    latestSliceletAssignment.assignmentOpt match {
      case Some(latestAssignment: Assignment) =>
        val generationNumberOpt =
          latestSliceletAssignment.subsliceAnnotationsMap.continuouslyAssignedGeneration(key)
        generationNumberOpt match {
          case Some(generationNumber: UnixTimeVersion) =>
            latestAssignment.generation.copy(number = generationNumber)
          case None =>
            Generation.EMPTY
        }
      case None =>
        Generation.EMPTY
    }
  }

  /** Set the Slicelet state reported in watch requests to TERMINATING. */
  private def setTerminatingState(): Unit = {
    lookup.setTerminatingState()
  }

  object forTest {

    /**
     * Returns a lower bound on the generation of the assignment currently known to the listener, or
     * to the Slicelet when no listener is registered.
     */
    def getListenerGenerationLowerBound: Future[Generation] = listenerSec.call {
      listenerGenerationLowerBound
    }

    /** See [[SliceletImpl.setTerminatingState()]]. */
    def setTerminatingState(): Unit = SliceletImpl.this.setTerminatingState()

    /** Returns the latest assignment known to the Slicelet. */
    def getLatestAssignmentOpt: Option[Assignment] =
      lookup.latestSliceletAssignment.assignmentOpt

    /** Returns the port on which the Slicelet RPC service is listening. */
    def sliceletPort: Int = server.activePort()

    /** Injects an assignment in the lookup. */
    def injectAssignment(assignment: Assignment): Unit = {
      lookup.forTest.injectAssignment(assignment)
    }

    /**
     * Stops the Slicelet for testing by setting the terminating state while allowing the
     * assignment watcher to continue sending heartbeats to the Assigner. This ensures the Assigner
     * recognizes the Slicelet as terminated and removes it from assignments.
     *
     * Note: This method does NOT fully cancel the SliceLookup connection to the Assigner.
     * For a complete shutdown that cancels all connections, see [[cancel]].
     *
     * @see [[SliceletSliceLookup.forTest.stop]] for the underlying SliceLookup stop behavior.
     * @see <internal bug> for the tracking JIRA on unifying stop/cancel behavior.
     */
    def stop(): Unit = {
      lookup.forTest.stop()
      stopMetricsAndServer()
      logger.info(s"Stopped Slicelet (terminating state set, heartbeats may continue)")
    }

    /**
     * Fully stops the Slicelet in the test environment by cancelling the SliceLookup connection
     * to the Assigner and stopping the Slicelet RPC service. Unlike [[stop]], this method
     * immediately cancels all communication with the Assigner rather than setting a terminating
     * state. This method is limited to Dicer team for internal use only.
     *
     * Note: This method is a stop gap solution until <internal bug> is resolved.
     *
     * TODO(<internal bug>): Once we verify that no customer tests depend on the assigner getting a
     * termination notice from the Slicelet after `SliceletImpl.forTest.stop` is called, we should
     * replace `SliceletImpl.forTest.stop` with this method.
     */
    private[dicer] def cancel(): Unit = {
      lookup.forTest.cancel()
      stopMetricsAndServer()
      logger.info(s"Fully stopped Slicelet (SliceLookup connection cancelled)")
    }

    // Cancels background metric updates and stops the Slicelet RPC server asynchronously.
    // Synchronously stopping the server was measured to take about 2s in tests, causing test
    // times for suites making use of many Slicelets to balloon significantly. We avoid this by
    // stopping the server asynchronously instead.
    private def stopMetricsAndServer(): Unit = {
      backgroundUpdateMetricsHandle.cancel(Status.CANCELLED)
      server.stopAsync()
    }
  }
}

/** Companion object for [[SliceletImpl]]. */
private[dicer] object SliceletImpl {

  /** Interval with which to regularly update load accumulator metrics. */
  private val SLICELET_LOAD_ACCUMULATOR_UPDATE_METRICS_INTERVAL: FiniteDuration = 5.seconds

  /** The interval at which the readiness poller polls the readiness provider for readiness. */
  private val READINESS_PROVIDER_POLL_INTERVAL: FiniteDuration = 1.second

  /**
   * Used to enforce that only a single `SliceletImpl` instance is created per process, except in
   * unit tests.
   */
  private val instantiationTracker: InstantiationTracker[PerProcessSingletonType] =
    InstantiationTracker.create()

  /**
   * Variable that keeps track of the index number of the current Slicelet that was last created.
   * Primarily, for debugging/monitoring purposes - can be used in subscriber name.
   */
  private val sliceletNum: AtomicInteger = new AtomicInteger(-1)

  /**
   * See specs for the external `Slicelet.apply`. Takes an explicit sequential execution context
   * used for async/background work in the Slicelet. The caller MUST call [[SliceletImpl.start()]]
   * before using the Slicelet.
   *
   * For data plane Slicelets (where [[SliceletConf.watchFromDataPlane]] is set to true) the
   * `target` parameter is expected to already be populated with the cluster URI (if available).
   *
   * @param assignerWatchAddress the address of the assigner to send the watch address to.
   */
  def create(
      sec: SequentialExecutionContext,
      assignerWatchAddress: URI,
      sliceletConf: SliceletConf,
      target: Target,
      readinessProvider: BlockingReadinessProvider): SliceletImpl = {
    if (!sliceletConf.allowMultipleSliceletInstances) {
      instantiationTracker.enforceAndRecord(PerProcessSingleton)
    }

    // Configure the Slicelet.
    val hostName: String = sliceletConf.sliceletHostNameOpt match {
      case Some(sliceletHostName: String) => sliceletHostName
      case None =>
        throw new IllegalArgumentException(
          "Unable to determine Slicelet host name. " +
          "Set the \"databricks.dicer.slicelet.hostname\" configuration property."
        )
    }

    // TODO(<internal bug>): Replace sliceletUuidOpt with clientUuidOpt. Verify both config keys resolve
    //               to the same value in all deployed environments before migration.
    val uuid: UUID = sliceletConf.sliceletUuidOpt match {
      case Some(sliceletUuid: String) if sliceletUuid.nonEmpty => UUID.fromString(sliceletUuid)
      case _ =>
        throw new IllegalArgumentException(
          s"Unable to determine Slicelet UUID. " +
          "Set the \"databricks.dicer.slicelet.uuid\" configuration property to a valid UUID."
        )
    }

    val sliceletDebugName: String = getDebugName(target, hostName)

    val kubernetesNamespace: String = sliceletConf.sliceletKubernetesNamespaceOpt match {
      case Some(k8sNamespace: String) if k8sNamespace.nonEmpty => k8sNamespace
      case _ =>
        throw new IllegalArgumentException(
          s"Unable to determine Kubernetes namespace. " +
          "Set the \"databricks.dicer.slicelet.kubernetesNamespace\" configuration property."
        )
    }

    val sliceLookupConfig: SliceLookupConfig = SliceLookupConfig(
      ClientType.Slicelet,
      assignerWatchAddress,
      sliceletConf.getDicerClientTlsOptions,
      target,
      clientIdOpt = Some(uuid),
      SliceLookupConfig.DEFAULT_WATCH_STUB_CACHE_TIME,
      sliceletConf.watchFromDataPlane,
      // TODO(<internal bug>): Use client side feature flag to gradually rollout rate limiting.
      enableRateLimiting = false
    )
    val config: InternalClientConfig = InternalClientConfig(sliceLookupConfig)

    // Create a service builder on which the SliceLookup can register a watch handler.
    val serviceBuilder: GenericRpcServiceBuilder = GenericRpcServiceBuilder.create()

    // Create the proto logger for assignment propagation latency events.
    val protoLogger: DicerClientProtoLogger = DicerClientProtoLogger.create(
      clientType = sliceLookupConfig.clientType,
      conf = sliceletConf,
      ownerName = sliceletDebugName
    )

    // The readiness provider runs on a separate thread from the main Slicelet thread because
    // isReady() calls an external API on the ReadinessProbeTracker. This is currently a
    // non-blocking call, but there is no guarantee it will remain non-blocking. We don't want to
    // risk blocking the main Slicelet thread, which handles assignment updates and client requests.
    val readinessProviderSec: SequentialExecutionContext =
      SequentialExecutionContext.createWithDedicatedPool("Slicelet-ReadinessProvider")
    val readinessPoller =
      new WatchValueCellPollAdapter[Boolean, Boolean](
        initialValueOpt = None,
        poller = () => readinessProvider.isReady,
        transform = identity,
        pollInterval = SliceletImpl.READINESS_PROVIDER_POLL_INTERVAL,
        sec = readinessProviderSec
      )

    // Accumulates load observed by this Slicelet.
    val metrics = new SliceletMetrics(target)
    val loadAccumulator = new SliceletLoadAccumulator(sec.getClock, target, metrics)
    val lookup =
      new SliceletSliceLookup(
        sec,
        sliceLookupConfig,
        readinessPoller,
        serviceBuilder,
        loadAccumulator,
        kubernetesNamespace,
        metrics,
        protoLogger,
        sliceletDebugName
      )

    // Create the server, which will be started in start().
    val server = WatchServerHelper.createWatchServer(
      sliceletConf,
      sliceletConf.dicerSliceletRpcPort,
      // Never enable <internal link> for the Slicelet's watch server, as it is not the primary
      // server.
      localPort = None,
      serviceBuilder
    )

    new SliceletImpl(
      sec,
      sliceletConf,
      config,
      sliceletDebugName,
      hostName,
      uuid,
      server,
      metrics,
      loadAccumulator,
      lookup
    )
  }

  /**
   * See specs for the external [[Slicelet.apply]]. This method is completely a delegate of
   * [[Slicelet.apply]] in order to hide implementation details in the external [[Slicelet]] class
   * while also handling `sec` creation and target cluster uri overriding on top of
   * [[SliceletImpl.create]], so that the test code will have the freedom to inject desired
   * dependencies to [[SliceletImpl]] using [[SliceletImpl.create]].
   */
  def createForExternal(sliceletConf: SliceletConf, target: Target): SliceletImpl = {
    createForExternalWithReadinessProvider(
      sliceletConf,
      target,
      readinessProvider = RealBlockingReadinessProvider
    )
  }

  /**
   * Creates a [[SliceletImpl]] for the given `sliceletConf` and `target`. See the specs of the
   * external [[Slicelet.apply]] for details on how these parameters are used to instantiate the
   * Slicelet. This factory method also takes a `readinessProvider` as a parameter, which allows
   * test code to inject a dependency while otherwise initializing the Slicelet as would be done
   * in production code.
   */
  def createForExternalWithReadinessProvider(
      sliceletConf: SliceletConf,
      target: Target,
      readinessProvider: BlockingReadinessProvider): SliceletImpl = {
    // This execution context does not propagate the context to the threads it creates to avoid
    // the overhead of unnecessarily copying the context to background threads.
    val sec = SequentialExecutionContext.createWithDedicatedPool(
      "SliceletExecutor",
      enableContextPropagation = false
    )
    // The Target carrying the information for the assigner to distinguish the Slicelets from
    // different clusters or app instances.
    //
    // TODO(<internal bug>): We only include this information for data plane Slicelets of
    // KubernetesTargets, since we need Slicelets from multiple clusters to be part of the same
    // target for SMK migrations, and also because we do not want to put existing control plane
    // Slicelets at risk of taking a WhereAmI dependency that they don't need today. However we
    // should eventually include ClusterURI information everywhere once it is has matured, and take
    // necessary steps to check its validity (that it does in fact align with the local Assigner's
    // cluster URI) before including it in control plane Slicelet target identifiers.
    val fullyQualifiedTargetIfNeeded: Target =
      if (sliceletConf.watchFromDataPlane) {
        // The Slicelet is in the data plane watching the assigner in the general cluster.
        //
        // If the target is a KubernetesTarget, capture the URI of the cluster  that this Slicelet
        // is running in and annotate the KubernetesTarget with this URI. If the URI is not
        // available, it is not safe to construct the Slicelet, as Dicer will not be able to
        // distinguish the Slicelet from those running in the other clusters (WhereAmI is a
        // documented hard dependency for data plane Slicelets).
        //
        // If the target is an AppTarget then the cluster URI is ignored. Note however that we still
        // throw if the cluster URI is not available. This is intentional: WhereAmI is a hard
        // dependency for Dicer use in the data plane which we intend to generally leverage (e.g.,
        // at least monitoring and alerting), so we intentionally do not relax this dependency on
        // WhereAmI for AppTargets in the data plane.
        WhereAmIHelper.getClusterUri match {
          case Some(clusterUri: URI) =>
            target match {
              case _: KubernetesTarget =>
                Target.createKubernetesTarget(clusterUri, target.name)
              case _: AppTarget =>
                target
            }
          case None =>
            throw new IllegalStateException(
              "Slicelet was configured to connect to Dicer from the data plane " +
              "(databricks.dicer.client.watchFromDataPlane was set), but is unable to connect " +
              "because the required cluster location information is not available in the " +
              "binary (provided by WhereAmI via environment variables). This indicates a " +
              "critical issue with your release which should be rolled back immediately. See " +
              "<internal link> for more information."
            )
        }
      } else {
        // The Slicelet will be watching the assigner in the local cluster and there is no need to
        // attach cluster URI to the Target. See TODO(<internal bug>).
        target
      }

    val assignerURI: URI = WatchAddressHelper.getAssignerURI(
      sliceletConf.assignerHost,
      sliceletConf.dicerAssignerRpcPort
    )

    val slicelet = SliceletImpl.create(
      sec,
      assignerURI,
      sliceletConf,
      fullyQualifiedTargetIfNeeded,
      readinessProvider
    )

    // In production, make the transition to the terminating state automatically when the process
    // receives a shutdown signal. Tests should manually induce the terminating state on indivudal
    // Slicelets by calling `SliceletImpl.forTest.setTerminatingState()`.
    //
    // Note that we do not use a `DrainingComponent` here because
    // execution of DrainingComponents orders _after_ the grace period within the server shutdown
    // sequence, after which the server framework will automatically reject incoming requests. We
    // want to notify Dicer and have it reassign traffic before this happens, so we use a shutdown
    // hook instead.
    ShutdownHookManager
      .addShutdownHook(SLICELET_TERMINATION_SHUTDOWN_HOOK_PRIORITY) {
        slicelet.setTerminatingState()
      }

    slicelet
  }

  /** Given a Slicelet hostname, returns a name that is useful for debugging. */
  def getDebugName(target: Target, hostName: String): String = {
    val num = sliceletNum.addAndGet(1)

    // Use just the first part of the hostname if it starts with a letter (so if the name is
    // 123.foo.xyz, we will use the full hostname - that is ok since such names are not seen
    // typically in production and this is for debugging only).
    val dottedHostPattern: Regex = raw"([A-Za-z][^.]*)[.].*".r
    val resourceName = hostName match {
      case dottedHostPattern(firstPart) => firstPart
      case _ => hostName
    }
    s"S$num-$target-$resourceName"
  }
}

/**
 * A trait for a blocking readiness provider that can be used to query the current readiness status.
 */
private[dicer] trait BlockingReadinessProvider {

  /** Returns the current readiness status. `true` means ready, `false` means not ready. */
  def isReady: Boolean
}

/**
 * Real implementation of [[BlockingReadinessProvider]] that queries the [[ReadinessProbeTracker]].
 */
private[dicer] object RealBlockingReadinessProvider extends BlockingReadinessProvider {
  override def isReady: Boolean = ReadinessProbeTracker.isPodReady
}

/**
 * View of an assignment including the collection of Slices assigned to a particular Slicelet.
 * Exposes [[assignedSlicesSet]] and [[subsliceAnnotationsMap]] which enable fast lookup of
 * information relevant to the Squid - see the specs of each variable below for more details. If
 * either parameter is None, it will behave as if no Slices are assigned to the Slicelet.
 *
 * @param squidOpt the resource address and incarnation UUID for the Slicelet.
 * @param assignmentOpt the whole assignment.
 */
private[dicer] case class SliceletAssignment(
    squidOpt: Option[Squid],
    assignmentOpt: Option[Assignment]) {

  /** The Slices assigned to the current Slicelet. Derived from [[assignmentOpt]]. */
  val assignedSlicesSet: SliceSetImpl = (squidOpt, assignmentOpt) match {
    case (Some(squid: Squid), Some(assignment: Assignment)) =>
      assignment.getSliceSetForResource(squid)
    case _ => SliceSetImpl.empty
  }

  /**
   * A map tracking the available annotations for Slices assigned to the current Slicelet. Derived
   * from [[assignmentOpt]].
   */
  private[client] val subsliceAnnotationsMap: SubsliceAnnotationsMap =
    (squidOpt, assignmentOpt) match {
      case (Some(squid: Squid), Some(assignment: Assignment)) =>
        SubsliceAnnotationsMap(assignment, squid)
      case _ => SubsliceAnnotationsMap.EMPTY
    }

  /** The generation of [[assignmentOpt]] when it is defined or [[Generation.EMPTY]] otherwise. */
  def generationOrEmpty: Generation = assignmentOpt match {
    case Some(assignment: Assignment) => assignment.generation
    case None => Generation.EMPTY
  }
}

object SliceletAssignment {

  /** An empty view with no Squid or Assignment, which will have no slices assigned. */
  val EMPTY: SliceletAssignment = SliceletAssignment(squidOpt = None, assignmentOpt = None)
}

/**
 * Wrapper around [[SliceLookup]] exposing [[SliceletAssignment]] rather than [[Assignment]]. The
 * lookup MUST be started using [[start()]] before use.
 *
 * @param protoLogger Logger for assignment propagation latency events.
 */
private[client] final class SliceletSliceLookup(
    sec: SequentialExecutionContext,
    config: SliceLookupConfig,
    readinessPoller: WatchValueCellPollAdapter[Boolean, Boolean],
    serviceBuilder: GenericRpcServiceBuilder,
    loadAccumulator: SliceletLoadAccumulator,
    kubernetesNamespace: String,
    metrics: SliceletMetrics,
    protoLogger: DicerClientProtoLogger,
    subscriberDebugName: String) {
  import SliceletSliceLookup.StateEnum

  private val logger = PrefixLogger.create(this.getClass, subscriberDebugName)

  /**
   * The delay before attempting to start the lookup if the readiness poller is blocked. This
   * ensures the lookup starts even if readiness checking encounters issues.
   */
  private val BLOCKED_READINESS_CHECK_START_DELAY: FiniteDuration = 5.seconds

  /** The wrapped [[SliceLookup]] instance. */
  private val baseLookup =
    SliceLookup.createUnstarted(
      sec,
      config,
      subscriberDebugName,
      protoLogger,
      serviceBuilderOpt = Some(serviceBuilder)
    )

  /**
   * Cell in which the latest [[SliceletAssignment]] is recorded. It is initialized with an empty
   * assignment.
   */
  private val cell: WatchValueCell[SliceletAssignment] = {
    val cell = new WatchValueCell[SliceletAssignment]
    // Bootstrap the cell with an empty view, so that the Slicelet is usable immediately.
    cell.setValue(SliceletAssignment.EMPTY)
    cell
  }

  /** Handle that can be used to cancel the watch stream established in `start`. */
  @GuardedBy("sec")
  private var cellHandle: Cancellable = _

  /** The current state of the Slicelet. */
  @GuardedBy("sec")
  private var state: StateEnum = StateEnum.NotReady

  /** Supplies assignments as they are updated to the given `callback`. */
  def watch(callback: ValueStreamCallback[SliceletAssignment]): Unit = cell.watch(callback)

  /**
   * REQUIRES: this method has not been called for this Slicelet yet.
   *
   * Starts the lookup and creates/populates the squid for this Slicelet. Also starts a poller to
   * watch the readiness status of the Slicelet.
   *
   * In the spirit of coarse-grained locking, we prefer for method definitions to be enclosed
   * entirely by `sec.run`. This method deviates from that convention because it must make the squid
   * available immediately after the method returns. Callers have no way to determine whether the
   * sec tasks have been executed yet. We thus set the squid synchronously before running any sec
   * tasks.
   *
   * After this call returns, [[squidOrThrowIfUnstarted()]] will return successfully.
   */
  def start(resourceUuid: UUID, resourceAddress: ResourceAddress): Unit = {
    // Create a globally unique identifier for the current Slicelet incarnation, that is different
    // across pod restarts. Calling this method multiple times will create multiple Squids for the
    // same Slicelet, which is why start() must only be called once.
    val squid = Squid.createForNewIncarnation(RealtimeTypedClock, resourceAddress, resourceUuid)

    // Make the resource address available via `Slicelet.resourceAddress` immediately after `start`
    // returns by executing this outside of `sec.run`. This is required because callers may access
    // the squid after this method returns, but they have no way to determine whether the sec tasks
    // have been executed yet.
    cell.setValue(SliceletAssignment(Some(squid), None))

    sec.run {
      cellHandle = baseLookup.cellConsumer.watch(new ValueStreamCallback[Assignment](sec) {
        override protected def onSuccess(assignment: Assignment): Unit = {
          // Set the latest Slicelet assignment if the given assignment is new.
          if (assignment.generation > latestSliceletAssignment.generationOrEmpty) {
            loadAccumulator.onAssignmentChanged(squid, assignment)
            ClientMetrics
              .updateOnNewAssignment(
                assignment.generation,
                config.target,
                AssignmentMetricsSource.Slicelet
              )
            cell.setValue(SliceletAssignment(Some(squid), Some(assignment)))
          }
        }
      })

      readinessPoller.watch(new ValueStreamCallback[Boolean](sec) {
        @GuardedBy("sec")
        private var gotFirstStatus: Boolean = false

        override protected def onSuccess(isReady: Boolean): Unit = {
          // Start the lookup on first status update (regardless of ready/not ready).
          if (!gotFirstStatus) {
            gotFirstStatus = true
            baseLookup.start(() => createSliceletData())
          }

          // Update state based on readiness, preserving Terminating state.
          val newState: StateEnum = state match {
            case StateEnum.Terminating =>
              StateEnum.Terminating
            case StateEnum.NotReady | StateEnum.Running =>
              if (isReady) StateEnum.Running else StateEnum.NotReady
          }

          state = newState
        }
      })

      readinessPoller.start()

      // Schedule a call to start the `baseLookup` in case the `readinessPoller` is blocked. It's
      // safe to start the lookup multiple times. If the lookup was already started, the underlying
      // state machine will already be in a started state and will not re-initiate the connection or
      // watch.
      sec.schedule(
        name = "lookup-start",
        delay = BLOCKED_READINESS_CHECK_START_DELAY,
        () => baseLookup.start(() => createSliceletData())
      )
    }
  }

  /** Returns the latest assignment known to the lookup. */
  def latestSliceletAssignment: SliceletAssignment = {
    val latestAssignmentOpt: Option[SliceletAssignment] = cell.getLatestValueOpt
    // `cell` is initialized with a value, so it should always be defined.
    latestAssignmentOpt.get
  }

  /**
   * REQUIRES: [[start]] has been called.
   *
   * Returns the squid for this Slicelet.
   *
   * @throws IllegalStateException if start() has not been called
   */
  def squidOrThrowIfUnstarted(): Squid = {
    latestSliceletAssignment.squidOpt match {
      case Some(squid: Squid) => squid
      case None =>
        throw new IllegalStateException("Slicelet must be started before accessing squid")
    }
  }

  /** Set the state reported by [[baseLookup]] to TERMINATING. */
  def setTerminatingState(): Unit = sec.run {
    logger.info(s"Setting state of ${latestSliceletAssignment.squidOpt} to terminating.")

    // `Terminating`` is a terminal state, and is ok set blindly.
    state = StateEnum.Terminating
  }

  /**
   * REQUIRES: [[start]] has written a non-empty squidOpt to the cell. Note that start() may not
   * have returned yet when this is called, since this method is invoked as a callback from within
   * start()'s sec.run block. However, it is safe to call [[squidOrThrowIfUnstarted()]] because
   * start() writes the squid to the cell synchronously before entering the sec.run block.
   *
   * Creates [[SliceletData]] instance describing the current Slicelet and providing a summary of
   * the load it is handling. The Slicelet's current state is selected and included in the
   * [[SliceletData]].
   */
  private def createSliceletData(): SliceletData = {
    sec.assertCurrentContext()

    val squid: Squid = squidOrThrowIfUnstarted()
    val (attributedLoads, unattributedLoad): (
        Vector[SliceletData.SliceLoad],
        SliceletData.SliceLoad) = loadAccumulator.readLoad()

    val stateP: SliceletDataP.State = StateEnum.toProto(state)
    metrics.onHeartbeat(stateP)

    SliceletData(
      squid,
      SliceletState.fromProto(stateP, config.target),
      kubernetesNamespace,
      attributedLoads,
      Some(unattributedLoad)
    )
  }

  object forTest {

    /** Injects an assignment in the lookup. */
    def injectAssignment(assignment: Assignment): Unit = {
      baseLookup.forTest.injectAssignment(assignment)
    }

    /**
     * Stops the Slicelet by setting the terminating state while allowing heartbeats to continue.
     *
     * This method sets the Slicelet's state to terminating so the Assigner will ignore future
     * heartbeats and remove the Slicelet from resources. However, unlike [[cancel]], heartbeats
     * continue to flow, allowing the Assigner to receive the termination notice.
     *
     * @see <internal bug> for the tracking JIRA on unifying stop/cancel behavior.
     */
    def stop(): Unit = {
      sec.run {
        if (cellHandle != null) {
          cellHandle.cancel(Status.CANCELLED)
        }
        // Prior to the introduction of `SliceLookup.cancel()` there was no way to stop the
        // SliceLookup from sending heartbeats, but tests needed to effectively "stop" the Slicelet,
        // so instead, this `forTest.stop` method simply set the Slicelet's state to terminating
        // (while heartbeats would continue to to flow), so the assigner will ignore its future
        // heartbeats (and remove it from resources) after receiving a heartbeat with the
        // terminating state.
        //
        // Now there is a way to stop the SliceLookup (using `SliceLookup.cancel()`) but
        // unfortunately we can't use it here because it turns out that there are customer tests
        // which are depending on the assigner receiving a termination notice from the Slicelet and
        // getting cleared from the assignment (i.e. they use the same Target name for Slicelets in
        // each test case, and are depending on previous tests' Slicelets being cleared from the
        // assignment).
        //
        // TODO(<internal bug>): We should use `baseLookup.stop()` once we verify that no customer tests
        // depend on the assigner getting a termination notice from the Slicelet after
        // `Slicelet.forTest.stop` is called.
        baseLookup.forTest.cancelHandler()

        // Mark the Slicelet as terminating, so the Assigner will recognize it as terminated on the
        // next heartbeat and ignore any subsequent heartbeats.
        setTerminatingState()
      }
    }

    /**
     * Cancels the underlying [[SliceLookup]] instance for the [[SliceletSliceLookup]].
     *
     * This method immediately terminates all communication with the Assigner (hard break),
     * but does not send a termination notification. The Assigner will only detect the Slicelet
     * is gone when heartbeats stop arriving (after the heartbeat timeout expires).
     *
     * In contrast, [[stop]] sends a termination notice to the Assigner but never actually
     * cancels the communication, leaving the subscriber running which can lead to unwanted
     * behavior in tests (e.g. flakiness due to ongoing heartbeats).
     *
     * @see [[stop]] for termination with notification (but without cancelling communication).
     */
    private[dicer] def cancel(): Unit = baseLookup.cancel()
  }
}

/** Companion object for [[SliceletSliceLookup]]. */
private[dicer] object SliceletSliceLookup {

  /**
   * Represents the readiness and lifecycle state of a [[Slicelet]].
   *
   * The states are:
   *  - [[StateEnum.NotReady]]: The Slicelet is not ready to serve traffic.
   *  - [[StateEnum.Running]]: The Slicelet is ready to serve traffic.
   *  - [[StateEnum.Terminating]]: The Slicelet is shutting down and should not be assigned.
   */
  private sealed trait StateEnum
  private object StateEnum {
    case object NotReady extends StateEnum
    case object Running extends StateEnum
    case object Terminating extends StateEnum

    /** Converts a [[StateEnum]] to its protobuf representation. */
    def toProto(state: StateEnum): SliceletDataP.State = state match {
      case StateEnum.NotReady => SliceletDataP.State.NOT_READY
      case StateEnum.Running => SliceletDataP.State.RUNNING
      case StateEnum.Terminating => SliceletDataP.State.TERMINATING
    }
  }
}

/**
 * Actual implementation of `SliceKeyHandle`. See its specs for more details.
 * `startingGeneration` should be set to the highest assignment generation known by `slicelet`, or
 * [[Generation.EMPTY]] if the initial assignment hasn't been received yet.
 */
private[dicer] final class SliceKeyHandleImpl private[client] (
    slicelet: SliceletImpl,
    val key: SliceKey,
    startingGeneration: Generation)
    extends AutoCloseable {
  // Note: `startingGeneration` will be compared against `getContinuouslyAssignedGeneration`
  // so it is best for it to be as high as possible. Thus we ask to be passed the highest known
  // generation rather than the continuously assigned generation.

  slicelet.metrics.onSliceKeyHandleCreated()

  /**
   * Implementation of `SliceKeyHandle.isAssignedContinuously` API - see its specs for more details.
   * The caveats mentioned there are as follows:
   *  - It is possible for another server to also believe the same key is assigned to it at the same
   *    moment, as this check is performed locally against the latest assignment known to this
   *    Slicelet and different Slicelets will not learn about assignment changes at the same
   *    instant.
   *  - Generation is not currently guaranteed to be monotonically increasing, and it is possible
   *    the key was reassigned at some intermediate point e.g. in some cases when the Assigner
   *    restarts. For more details see the comment for LOOSE_INCARNATION in Generation.scala.
   */
  def isAssignedContinuously: Boolean = {
    val assignedSince = slicelet.getContinuouslyAssignedGeneration(key)
    if (assignedSince == Generation.EMPTY) {
      return false
    }
    assignedSince <= startingGeneration
  }

  /** See `SliceKeyHandle.incrementLoadBy`. */
  def incrementLoadBy(value: Int): Unit = {
    slicelet.loadAccumulator.incrementPrimaryRateBy(key, value)
  }

  /** Implementation of `SliceKeyHandle.close` API - see its specs for more details. */
  override def close(): Unit = {
    slicelet.metrics.onSliceKeyHandleClosed()
    // Only updates a metric for now - this is expected to eventually be used once we track more
    // state around handles (e.g. keys corresponding to in-progress requests).
  }
}

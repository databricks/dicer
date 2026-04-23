package com.databricks.dicer.client

import java.net.URI
import java.time.Instant

import io.grpc.Status
import io.grpc.Status.Code
import scala.collection.immutable.SortedMap

import com.github.blemale.scaffeine.{Cache, Scaffeine}
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import com.databricks.api.proto.dicer.common.AssignmentServiceGrpc.AssignmentServiceStub
import com.databricks.api.proto.dicer.common.{ClientRequestP, ClientResponseP}
import com.databricks.rpc.RPCContext
import io.grpc.Deadline

import com.databricks.caching.util.{
  GenericRpcServiceBuilder,
  PrefixLogger,
  SequentialExecutionContext,
  StateMachineDriver,
  StatusUtils,
  WhereAmIHelper
}
import com.databricks.common.instrumentation.SCaffeineCacheInfoExporter
import com.databricks.context.Ctx
import com.databricks.dicer.client.AssignmentSyncStateMachine.{DriverAction, Event}
import com.databricks.dicer.client.SliceLookupConfig.DEADLINE_BUFFER
import com.databricks.dicer.common.Assignment.{AssignmentValueCell, AssignmentValueCellConsumer}
import com.databricks.dicer.common.{
  Assignment,
  ClerkData,
  ClerkSubscriberSlicezData,
  ClientRequest,
  ClientResponse,
  ClientType,
  Redirect,
  SliceletData,
  SliceletSubscriberSlicezData,
  SubscriberData,
  SubscriberHandler,
  SyncAssignmentState,
  TargetUnmarshaller,
  WatchServerHelper
}
import com.databricks.dicer.external.{Slice, SliceKey}
import com.databricks.dicer.friend.Squid
import com.databricks.logging.activity.ActivityContextFactory.withBackgroundActivity
import java.util.Random
import javax.annotation.concurrent.{GuardedBy, ThreadSafe}

/**
 * An abstraction that actively and asynchronously queries and caches the assignment from the
 * Assigner or Slicelets.
 *
 * A caller can synchronously access the latest assignment via [[assignmentOpt]] or watch for
 * changes to the assignment via [[cellConsumer]]. Both accessors are backed by a
 * [[AssignmentValueCell]], which is why it is safe to call them from any thread.
 *
 * For details on the other fields, see [[SliceLookup.createUnstarted()]].
 *
 * The caller MUST call [[start]] before calling any of the methods in this class.
 *
 * Note: This class makes no assumptions about the caller's concurrency domain. Despite taking
 * a [[SequentialExecutionContext]], it is independently thread-safe, as each external call is
 * queued on the [[SequentialExecutionContext]]. Since SliceLookup can be cached and reused by
 * multiple Clerks in different SECs, it must be usable from callers in different domains.
 */
@ThreadSafe
class SliceLookup private (
    sec: SequentialExecutionContext,
    config: SliceLookupConfig,
    subscriberDebugName: String,
    protoLogger: DicerClientProtoLogger)
    extends ClientTargetSlicezDataExporter {

  private val logger = PrefixLogger.create(this.getClass, subscriberDebugName)

  /**
   * The Kubernetes cluster URI of the pod running this client, captured once at construction time.
   * Used to determine cross-cluster/region indicators on the slicez page.
   */
  private val clientClusterOpt: Option[URI] = WhereAmIHelper.getClusterUri

  /** The cell used for communicating a new `Assignment` to various parts of the Clerk/Slicelet. */
  private val cell = new AssignmentValueCell

  /**
   * Manages the creation of watch stubs for the SliceLookup.
   *
   * Note: If this is a data plane client, `config.watchAddress` is the address of the s2sproxy
   * which forwards our watch requests.
   */
  private val watchStubManager = new WatchStubManager(
    clientName = config.clientName,
    subscriberDebugName = subscriberDebugName,
    defaultWatchAddress = config.watchAddress,
    tlsOptionsOpt = config.tlsOptionsOpt,
    watchFromDataPlane = config.watchFromDataPlane,
    target = config.target,
    clientIdOpt = config.clientIdOpt
  )

  /**
   * Supplies [[SubscriberData]] to include in watch requests. Set when [[start()]] is called.
   *
   * This is guaranteed not to be invoked before [[start()]] returns.
   */
  @GuardedBy("sec")
  private var subscriberDataSupplier: () => SubscriberData = _

  /** Tracks whether [[start()]] has been called to ensure idempotency. */
  @GuardedBy("sec")
  private var started: Boolean = false

  /**
   * Cache of address to its stub. Used when we are asked to redirect a request to a specific
   * address.
   *
   * TODO(<internal bug>): Now that we will be using dynamicGrpcChannel in [[WatchStubManager]],
   * creating watch stubs to preferred assigner targets is a very lightweight operation.
   * Therefore, this cache is no longer necessary and should be removed.
   */
  @GuardedBy("sec")
  private val watchStubsCache: Cache[URI, AssignmentServiceStub] =
    SCaffeineCacheInfoExporter.registerCache(
      "slice_lookup_watch_stubs",
      Scaffeine()
        .expireAfterAccess(config.watchStubCacheTime)
        .build()
    )

  /**
   * The last address on which we issued the watch RPC, or None if we used `config.watchAddress`.
   * Used for Slicez.
   */
  @GuardedBy("sec")
  private var lastWatchAddress: Option[URI] = None

  /** The last time that `lastWatchAddress` was modified. */
  @GuardedBy("sec")
  private var lastWatchAddressUsedSince: Instant = sec.getClock.instant()

  /**
   * The last time that an OK reply was received from the remote assignment distributor,
   * e.g., Assigner.
   */
  @GuardedBy("sec")
  private var lastSuccessfulHeartbeat: Instant = Instant.EPOCH

  /**
   * Driver for the [[AssignmentSyncStateMachine]] state machine. Performs actions requested by the
   * syncer, and "drives" it using a [[StateMachineDriver]] (see that class for remarks on the
   * responsibilities of the driver and state machine).
   */
  private val driver =
    new StateMachineDriver[Event, DriverAction, AssignmentSyncStateMachine](
      sec,
      new AssignmentSyncStateMachine(config, new Random, subscriberDebugName),
      performAction
    )

  /** The handler that receives the watch calls from remote clients. */
  private val handler =
    new SubscriberHandler(
      sec,
      config.target,
      // TODO(<internal bug>): Figure out whether the suggested RPC timeout should be set to the value
      // defined in `WatchServerConf`.
      getSuggestedClerkRpcTimeoutFn = () => config.watchRpcTimeout,
      suggestedSliceletRpcTimeout = config.watchRpcTimeout,
      getHandlerLocation
    )

  /** Cell consumer exposing the latest assignments.  */
  def cellConsumer: AssignmentValueCellConsumer = cell

  /** The latest assignment. */
  def assignmentOpt: Option[Assignment] = cell.getLatestValueOpt

  /**
   * Starts the syncer to watch for assignments, handle requests, etc. Also registers this lookup
   * to [[ClientSlicez]].
   *
   * Only the first call to this method will start the SliceLookup. Subsequent calls will be
   * ignored, even if a different subscriberDataSupplier is provided.
   *
   * We pass the subscriberDataSupplier in as a parameter to guarantee it is not called before this
   * method.
   *
   * @param subscriberDataSupplier A function that returns the latest [[SubscriberData]]. This will
   *                               be invoked on `sec` and is guaranteed not to be called before
   *                               this method returns.
   */
  def start(subscriberDataSupplier: () => SubscriberData): Unit = sec.run {
    if (!started) {
      started = true
      this.subscriberDataSupplier = subscriberDataSupplier
      ClientSlicez.register(this)
      driver.start()
    }
  }

  /**
   * Stops watching for changes from the remote server, unregisters this lookup from
   * [[ClientSlicez]], and updates the related metrics.
   */
  def cancel(): Unit = sec.run {
    driver.handleEvent(Event.Cancel)
    handler.cancel()
    ClientSlicez.unregister(this)
    ClientMetrics.decrementNumActiveSliceLookups(config.target, config.clientType)
  }

  override def getSlicezData: Future[ClientTargetSlicezData] = sec.flatCall {
    handler.getSlicezData.map {
      slicezData: (Seq[SliceletSubscriberSlicezData], Seq[ClerkSubscriberSlicezData]) =>
        val (sliceletSubscriberData, clerkSubscriberData): (
            Seq[SliceletSubscriberSlicezData],
            Seq[ClerkSubscriberSlicezData]) = slicezData

        // Display either the URI to which the last watch request was sent (when set) or the
        // configured watch address.
        val watchAddress: URI = lastWatchAddress.getOrElse(config.watchAddress)

        // Get the assignment-related statistics information.
        val subscriberData: SubscriberData = subscriberDataSupplier()
        subscriberData match {
          case sliceletData: SliceletData =>
            // Compute load per slice stats.
            val reportedLoadBySlice: Map[Slice, Double] = sliceletData.attributedLoads.map {
              load: SliceletData.SliceLoad =>
                load.slice -> load.primaryRateLoad
            }.toMap
            val unattributedLoadBySlice: Map[Slice, Double] = sliceletData.unattributedLoadOpt.map {
              load: SliceletData.SliceLoad =>
                load.slice -> load.primaryRateLoad
            }.toMap

            // Compute load per resource stats. To keep `loadByResource` consistent with Assigner
            // side, here it only counts attributed load information.
            val reportedLoadByResource: Map[Squid, Double] =
              Map(sliceletData.squid -> reportedLoadBySlice.values.sum)

            // Compute top key stats.
            val attributedTopKeys: Seq[SliceletData.KeyLoad] =
              sliceletData.attributedLoads.flatMap { (load: SliceletData.SliceLoad) =>
                load.topKeys
              }
            val unattributedTopKeys: Seq[SliceletData.KeyLoad] =
              sliceletData.unattributedLoadOpt
                .map((_: SliceletData.SliceLoad).topKeys)
                .getOrElse(Seq.empty)
            val combinedTopKeys: Seq[(SliceKey, Double)] =
              (attributedTopKeys ++ unattributedTopKeys).map { keyLoad =>
                keyLoad.key -> keyLoad.underestimatedPrimaryRateLoad
              }

            ClientTargetSlicezData(
              config.target,
              sliceletSubscriberData,
              clerkSubscriberData,
              this.assignmentOpt,
              reportedLoadPerResourceOpt = Some(reportedLoadByResource),
              reportedLoadPerSliceOpt = Some(reportedLoadBySlice),
              topKeysOpt = Some(SortedMap.empty[SliceKey, Double] ++ combinedTopKeys),
              squidOpt = Some(sliceletData.squid),
              unattributedLoadBySliceOpt = Some(unattributedLoadBySlice),
              subscriberDebugName,
              watchAddress,
              lastWatchAddressUsedSince,
              lastSuccessfulHeartbeat,
              clientClusterOpt = this.clientClusterOpt
            )
          case ClerkData =>
            // No assignment stats is maintained in Clerks, so all assignment-related statistics
            // are set to None.
            ClientTargetSlicezData(
              config.target,
              sliceletSubscriberData,
              clerkSubscriberData,
              this.assignmentOpt,
              reportedLoadPerResourceOpt = None,
              reportedLoadPerSliceOpt = None,
              topKeysOpt = None,
              squidOpt = None,
              unattributedLoadBySliceOpt = None,
              subscriberDebugName,
              watchAddress,
              lastWatchAddressUsedSince,
              lastSuccessfulHeartbeat,
              clientClusterOpt = this.clientClusterOpt
            )
        }
    }(sec)
  }

  /** Performs an action requested by [[AssignmentSyncStateMachine]]. */
  private def performAction(action: DriverAction): Unit = {
    sec.assertCurrentContext()
    action match {
      case DriverAction.UseAssignment(assignment: Assignment) =>
        // Record the propagation latency from assignment generation to client application
        ClientMetrics.recordAssignmentPropagationLatency(
          generationTime = assignment.generation.toTime,
          currentTime = sec.getClock.instant(),
          target = config.target
        )
        // Log assignment propagation latency via structured logging
        protoLogger.logAssignmentPropagationLatency(
          generation = assignment.generation,
          currentTime = sec.getClock.instant(),
          subscriberDebugName = subscriberDebugName
        )
        cell.setValue(assignment)
      case DriverAction.SendRequest(
          addressOpt: Option[URI],
          opId: Long,
          syncState: SyncAssignmentState,
          watchRpcTimeout: FiniteDuration
          ) =>
        startSyncRpc(addressOpt, opId, syncState, watchRpcTimeout)
    }
  }

  /**
   * Performs a Watch RPC to the remote server using the given sync state. See
   * [[DriverAction.SendRequest]] for the semantics of `addressOpt`.
   */
  private def startSyncRpc(
      addressOpt: Option[URI],
      opId: Long,
      syncState: SyncAssignmentState,
      watchRpcTimeout: FiniteDuration): Unit = {
    sec.assertCurrentContext()
    // withBackgroundActivity clears the attribution context so the watch RPC is not attributed to
    // the client that initiated the watch.
    withBackgroundActivity(onlyWarnOnAttrTagViolation = true, addUserContextTags = false) {
      _: Ctx =>
        val subscriberData: SubscriberData = subscriberDataSupplier()
        val request: ClientRequest = ClientRequest(
          config.target,
          syncState,
          subscriberDebugName,
          watchRpcTimeout,
          subscriberData,
          supportsSerializedAssignment = true
        )
        val stub: AssignmentServiceStub = addressOpt match {
          case Some(address: URI) =>
            if (!lastWatchAddress.contains(address)) {
              lastWatchAddress = Some(address)
              lastWatchAddressUsedSince = sec.getClock.instant()
            }
            watchStubsCache.get(
              address,
              _ => watchStubManager.createWatchStub(Some(address))
            )
          case None =>
            if (lastWatchAddress.isDefined) {
              lastWatchAddress = None
              lastWatchAddressUsedSince = sec.getClock.instant()
            }
            // Creates a stub to the default watch address (config.watchAddress) that was provided
            // when the WatchStubManager was instantiated.
            watchStubManager.createWatchStub(redirectAddressOpt = None)
        }
        val responseFuture: Future[ClientResponse] = performWatchCall(stub, request)
        // Handle the read success/failure and call the corresponding syncer method.
        responseFuture.onComplete {
          case Success(response) =>
            sec.assertCurrentContext()
            lastSuccessfulHeartbeat = sec.getClock.instant()
            ClientMetrics.recordWatchRequest(config.target, config.clientType, statusCode = Code.OK)
            driver.handleEvent(Event.ReadSuccess(addressOpt, opId, response))

          case Failure(exception) =>
            sec.assertCurrentContext()
            val status: Status = StatusUtils.convertExceptionToStatus(exception)
            ClientMetrics.recordWatchRequest(
              config.target,
              config.clientType,
              statusCode = status.getCode
            )
            logger.info(s"Failed watch request, detail: $status", every = 30.seconds)
            driver.handleEvent(Event.ReadFailure(opId, status))
        }(sec)
    }
  }

  /**
   * Calls the remote server with the given `clientRequest` using `watchStub` and yields the result
   * via a `Future`. Sets a deadline for the RPC equal to [[ClientRequest.timeout]] plus
   * [[DEADLINE_BUFFER]]. This buffer allows the server time to cleanly terminate the stream before
   * it is cancelled by the client.
   */
  private def performWatchCall(
      watchStub: AssignmentServiceStub,
      clientRequest: ClientRequest): Future[ClientResponse] = {
    val deadline = Deadline.after((clientRequest.timeout + DEADLINE_BUFFER).toNanos, NANOSECONDS)
    val requestProto: ClientRequestP = clientRequest.toProto

    ClientMetrics.recordClientRequestProtoSize(
      requestProto.serializedSize,
      config.target,
      config.clientType
    )

    val response: Future[ClientResponseP] =
      watchStub.withDeadline(deadline).watch(requestProto)
    response.map(ClientResponse.fromProto)(sec)
  }

  /** Handles a watch request from a remote client. */
  private def handleWatchRequest(
      rpcContext: RPCContext,
      request: ClientRequest): Future[ClientResponseP] = {
    sec.flatCall {
      // Forward the request to the sync state machine in case the client's telling us about an
      // up-to-date assignment we don't know about yet, and then hand it to the handler which
      // is responsible for handling the request.
      driver.handleEvent(AssignmentSyncStateMachine.Event.WatchRequest(request))
      handler.handleWatch(rpcContext, request, cell)
    }
  }

  /** Returns the handler location for the lookup. */
  private def getHandlerLocation: SubscriberHandler.Location = {
    config.clientType match {
      case ClientType.Slicelet => SubscriberHandler.Location.Slicelet
      case ClientType.Clerk => SubscriberHandler.Location.Clerk
    }
  }

  private[client] object forTest {

    /**
     * Returns whether the watcher has received some errors and hence is in exponential backoff mode
     * w.r.t. communicating with the assigner
     */
    def isInBackoff: Future[Boolean] = sec.call {
      driver.forTest.getStateMachine.forTest.isInBackoff
    }

    /** Injects an assignment so it becomes known to the lookup. */
    def injectAssignment(assignment: Assignment): Unit = sec.run {
      // The state machine will incorporate any newer assignment it receives, regardless of `opId`.
      // We just use `opId` of 0 which is never a real `opId`, so it means this response will not
      // affect any other state regarding the watch, and only be used to update the assignment.
      val response = ClientResponse(
        SyncAssignmentState.KnownAssignment(assignment),
        1.second,
        // Redirect and token map don't need updating based on this forTest method.
        redirect = Redirect.EMPTY
      )
      driver.handleEvent(
        AssignmentSyncStateMachine.Event.ReadSuccess(addressOpt = None, opId = 0, response)
      )
    }

    /** Get the current size of the watch stubs cache. */
    def getWatchStubCacheSize: Future[Long] = sec.call {
      // Note we technically don't need to run on `sec` here since Scaffeine is thread-safe, but
      // we do so to avoid having to ever think about it.

      // We need to cleanUp to get eviction to trigger. Otherwise it triggers on reads/writes, but
      // those may not be happening in a test.
      watchStubsCache.cleanUp()
      watchStubsCache.estimatedSize()
    }

    /**
     * Cancels the [[SubscriberHandler]]. See [[SubsciberHandler.cancel]] for more details.
     *
     * The reason for having this forTest method for cancelling only the handler (as opposed to just
     * calling the public [[SliceLookup.cancel]]) is because [[SliceLookup.cancel]] previously only
     * cancelled the handler but was updated to actually cancel the lookup as a whole (causing it to
     * stop sending watch RPCs), but currently some customer tests rely on heartbeats eventually
     * communicating a terminating state after stopping the Slicelet. See todo below.
     */
    // TODO(<internal bug>): Once we update the `Slicelet.forTest.stop` contract to clearly state that it
    // only stops the slicelet (without unregistering it from the assigner), and verify that no
    // customer tests depend on the old behavior, we can remove this method. See the Jira ticket
    // description for examples of tests that currently depend on slicelet unregistration in
    // `Slicelet.forTest.stop`.
    def cancelHandler(): Unit = sec.run {
      handler.cancel()
    }
  }
}

object SliceLookup {

  /**
   * Returns a [[SliceLookup]] instance for the given args. Callers must still call
   * [[SliceLookup.start]] before any other methods.
   *
   * @param sec Used for the asynchronous isolation domain that fetches assignments.
   * @param config The internal configuration parameters used by the Clerk/Slicelet.
   * @param protoLogger Logger for assignment propagation latency events.
   * @param subscriberDebugName The debug name for the subscriber.
   * @param serviceBuilderOpt If present, the builder on which the created lookup can add a service
   *                          for listening to RPCs.
   */
  def createUnstarted(
      sec: SequentialExecutionContext,
      config: SliceLookupConfig,
      subscriberDebugName: String,
      protoLogger: DicerClientProtoLogger,
      serviceBuilderOpt: Option[GenericRpcServiceBuilder]
  ): SliceLookup = {
    val lookup =
      new SliceLookup(sec, config, subscriberDebugName, protoLogger)

    // Register an AssignmentService to serve watch requests (from Clerks or other Slicelets).
    for (serviceBuilder: GenericRpcServiceBuilder <- serviceBuilderOpt) {
      WatchServerHelper.registerAssignmentService(
        serviceBuilder,
        (rpcContext: RPCContext, req: ClientRequestP) =>
          lookup.handleWatchRequest(
            rpcContext,
            ClientRequest.fromProto(TargetUnmarshaller.CLIENT_UNMARSHALLER, req)
          )
      )
    }

    ClientMetrics.incrementNumSliceLookups(config.target, config.clientType)
    lookup
  }
}

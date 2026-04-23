package com.databricks.dicer.common

import com.databricks.api.proto.dicer.assigner.{HeartbeatRequestP, HeartbeatResponseP}

import java.net.URI
import java.time.Instant
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._
import scala.util.Random
import scala.util.control.NonFatal
import com.databricks.rpc.{HttpMethod, RequestHeaders, RequestHeadersBuilder}
import com.databricks.api.proto.dicer.common.{ClientRequestP, ClientResponseP}
import com.databricks.common.http.HttpRequestInfo
import com.databricks.rpc.RPCContext
import com.databricks.caching.util.{
  FakeProxy,
  FakeSequentialExecutionContextPool,
  PrefixLogger,
  RealtimeTypedClock,
  SequentialExecutionContext,
  SequentialExecutionContextPool,
  TestUtils,
  TickerTime
}
import com.databricks.conf.Configs
import com.databricks.dicer.assigner.InterposingEtcdPreferredAssignerDriver.ShutdownOption
import com.databricks.dicer.assigner.Store.WriteAssignmentResult
import com.databricks.dicer.assigner.conf.DicerAssignerConf
import com.databricks.dicer.assigner.conf.StoreConf.StoreEnum.{ETCD, IN_MEMORY}
import com.databricks.dicer.assigner.config.StaticTargetConfigProvider
import com.databricks.dicer.assigner.{
  Assigner,
  AssignerInfo,
  AssignerRpcTestHelper,
  AssignmentGeneratorDriver,
  DisabledPreferredAssignerDriver,
  EtcdPreferredAssignerDriver,
  EtcdPreferredAssignerStore,
  EtcdStore,
  EtcdStoreConfig,
  FakeKubernetesTargetWatcherFactory,
  HealthWatcher,
  InMemoryStore,
  InterposingEtcdPreferredAssignerDriver,
  InterposingEtcdPreferredAssignerStore,
  PreferredAssignerDriver,
  Store,
  TestableDicerAssignerConf
}
import com.databricks.dicer.common.TestAssigner.AssignerReplyType
import com.databricks.dicer.external.Target
import com.databricks.dicer.friend.{SliceMap, Squid}
import com.databricks.caching.util.Lock.withLock
import com.databricks.caching.util.{EtcdClient, EtcdTestEnvironment}
import com.databricks.dicer.assigner.config.InternalTargetConfig.HealthWatcherTargetConfig
import com.databricks.rpc.tls.TLSOptions
import com.databricks.rpc.testing.TestTLSOptions
import com.databricks.threading.NamedExecutor

/**
 * A test Assigner that allows some test control, e.g.,sending a bogus RPC response to the Clerk.
 * It can operate in two "modes" - one is the normal mode in which it is generating assignments
 * based on the signals received from the Slicelets/environment. A second mode is one in which
 * the caller can set and freeze the assignment - in that case the Assigner does not generate
 * new assignments on its unless unfrozen.
 *
 * See [[Assigner()]] for details about constructor parameters.
 *
 * @param interceptableStore The store used by the test Assigner which allows interposing on the
 *                           storage layer.
 */
class TestAssigner private (
    secPool: SequentialExecutionContextPool,
    sec: SequentialExecutionContext,
    private[common] val conf: TestableDicerAssignerConf,
    preferredAssignerDriver: PreferredAssignerDriver,
    storeFactory: TestAssigner.InterceptableStoreFactory,
    fakeKubernetesTargetWatcherFactory: FakeKubernetesTargetWatcherFactory,
    healthWatcherFactory: HealthWatcher.Factory,
    configProvider: StaticTargetConfigProvider,
    uuid: UUID = UUID.randomUUID(),
    hostName: String = "localhost",
    assignerClusterUri: URI,
    minAssignmentGenerationInterval: FiniteDuration,
    dPageNamespaceOpt: Option[String])
    extends Assigner.BaseForTest(
      secPool,
      sec,
      conf,
      preferredAssignerDriver,
      storeFactory,
      fakeKubernetesTargetWatcherFactory,
      healthWatcherFactory,
      configProvider,
      uuid,
      hostName,
      assignerClusterUri,
      minAssignmentGenerationInterval,
      dPageNamespaceOpt
    ) {

  /**
   * The store used by this test Assigner; same instance returned by the factory for all
   * generators.
   */
  private val interceptableStore: InterceptableStore = storeFactory.getStore()

  private val logger: PrefixLogger = PrefixLogger.create(this.getClass, "")

  /** The lock used to protect all state in the test Assigner. */
  private val lock = new ReentrantLock()

  /**
   * The latest, valid Clerk watch request, together with its headers, received for
   * each [[Target]].
   */
  private val latestValidClerkWatchRequestsByTarget =
    mutable.Map[Target, (RequestHeaders, ClientRequest)]()

  /**
   * The latest, valid slicelet watch request, together with its headers, received for
   * each [[Target]].
   */
  private val latestValidSliceletWatchRequestsByTarget =
    mutable.Map[Target, (RequestHeaders, ClientRequest)]()

  /**
   * The latest, valid slicelet watch request, together with its headers, received for each
   * [[Target]], by target and squid.
   */
  private val latestValidSliceletWatchRequests =
    mutable.Map[(Target, Squid), (RequestHeaders, ClientRequest)]()

  /** The reply type to send to the subscribers when a watch request is received. */
  private var replyType: AssignerReplyType.ReplyType = AssignerReplyType.Normal

  /** The number of heartbeat requests the current assigner has received. */
  private var heartbeatReceivedCount: Long = 0

  /** Whether the current assigner is paused from responding heartbeats. */
  private var pauseHandlingHeartbeat: Boolean = false

  /**
   * Stops the Assigner RPC server and returns a future that completes when the async stop is
   * executed.
   *
   * @param shutdownOption The option for shutting down the preferred Assigner driver.
   */
  def stop(shutdownOption: ShutdownOption): Future[Unit] = withLock(lock) {
    // Shut down the preferred assigner driver before stopping the assigner RPC server,
    // since the `forTest.stopAsync()` can result in an abdication write if the assigner is
    // preferred, and we need to block that write if `shutdownOption` is `ABRUPT`.
    preferredAssignerDriver match {
      case driver: InterposingEtcdPreferredAssignerDriver =>
        TestUtils.awaitResult(driver.shutdown(shutdownOption), Duration.Inf)
      case _ => // Do nothing
    }
    forTest.stopAsync()
  }

  /** Returns the URI for accessing this assigner's server locally. */
  def localUri: URI = {
    getAssignerInfoBlocking().uri
  }

  /** Blocks and returns the [[AssignerInfo]] identifying this Assigner. */
  def getAssignerInfoBlocking(): AssignerInfo = {
    TestUtils.awaitResult(getAssignerInfo, Duration.Inf)
  }

  /** Returns the [[FakeKubernetesTargetWatcherFactory]] used by this test Assigner. */
  def getFakeKubernetesTargetWatcherFactory: FakeKubernetesTargetWatcherFactory = {
    fakeKubernetesTargetWatcherFactory
  }

  /** Sets the reply type from the Assigner to the Clerk/Slicelet. */
  def setReplyType(replyType: AssignerReplyType.ReplyType): Unit = withLock(lock) {
    logger.info(s"Reply type set to $replyType")
    this.replyType = replyType
  }

  /** Returns the incarnation used for all assignments in the assignment store. */
  def storeIncarnation: Incarnation = interceptableStore.storeIncarnation

  /** Converts the HttpRequest from RPCContext to Armeria RequestHeaders. */
  private def convertToRequestHeaders(rpcContext: RPCContext): RequestHeaders = {
    val httpRequest: HttpRequestInfo = rpcContext.httpRequest
    val builder: RequestHeadersBuilder = RequestHeaders.builder()

    // Add the HTTP method and path (required for RequestHeaders)
    builder.method(HttpMethod.valueOf(httpRequest.getMethod))
    builder.path(httpRequest.getRequestURI)

    // Copy all headers from HttpRequestInfo
    for {
      headerName: String <- httpRequest.getHeaderNames
      headerValue: String <- httpRequest.getHeader(headerName)
    } {
      builder.add(headerName, headerValue)
    }

    builder.build()
  }

  override def handleWatch(rpcContext: RPCContext, req: ClientRequestP): Future[ClientResponseP] =
    withLock(lock) {
      // Expect that the request came through the fake S2S Proxy if and only if we expected it to.
      // This ensures that data plane clients in tests don't have bugs that cause them to try to
      // talk directly to the Assigner instead of going through S2S Proxy.
      val hasS2SProxyHeader: Boolean =
        rpcContext.httpRequest.getHeader(FakeProxy.ADDED_HEADER).isDefined
      if (conf.expectRequestsThroughS2SProxy) {
        require(
          hasS2SProxyHeader,
          "Expected request to come through FakeS2SProxy, but it didn't"
        )
      } else {
        require(
          !hasS2SProxyHeader,
          "Expected request not to come through FakeS2SProxy, but it did"
        )
      }

      try {
        // Parse the request proto and update the latest slicelet watch requests if the request is
        // valid and from a Slicelet.
        val clientRequest = ClientRequest.fromProto(forTest.getTargetUnmarshaller, req)
        logger.info(s"Received request from subscriber: $clientRequest", every = 5.second)

        // Convert HttpRequestInfo headers to RequestHeaders for storage
        val requestHeaders: RequestHeaders = convertToRequestHeaders(rpcContext)

        clientRequest.subscriberData match {
          case sliceletData: SliceletData =>
            latestValidSliceletWatchRequestsByTarget(clientRequest.target) =
              (requestHeaders, clientRequest)
            latestValidSliceletWatchRequests((clientRequest.target, sliceletData.squid)) =
              (requestHeaders, clientRequest)
            logger.trace(s"Added request info to the latest slicelet watch request: $req")
          case ClerkData =>
            latestValidClerkWatchRequestsByTarget(clientRequest.target) =
              (requestHeaders, clientRequest)
            logger.trace(s"Added request info to the latest clerk watch request: $req")
        }
      } catch {
        case NonFatal(e) =>
          logger.trace(s"Didn't update the latest slicelet watch request due to an exception, $e")
      }

      replyType match {
        case AssignerReplyType.Normal => super.handleWatch(rpcContext, req)
        case AssignerReplyType.InvalidProto =>
          Future.successful(ClientResponseP.defaultInstance)
        case AssignerReplyType.Error(exception: Exception) => Future.failed(exception)
        case AssignerReplyType.OverwriteRedirect(redirect: Redirect) =>
          val reply = super.handleWatch(rpcContext, req)
          reply.map(_.withRedirect(redirect.toProto))(sec)
        case AssignerReplyType.FutureOverride(future: Future[ClientResponseP]) =>
          future
      }
    }

  override def handleHeartbeat(req: HeartbeatRequestP): Future[HeartbeatResponseP] =
    withLock(lock) {
      heartbeatReceivedCount += 1
      if (pauseHandlingHeartbeat) {
        logger.info("Heartbeat handling paused.")
        Promise[HeartbeatResponseP]().future
      } else {
        super.handleHeartbeat(req)
      }
    }

  def getNumberOfHeartbeatsReceived: Long = withLock(lock) {
    heartbeatReceivedCount
  }

  /**
   * Returns the latest clerk watch request (including the headers) received for the given
   * target (if any).
   */
  def getLatestClerkWatchRequest(target: Target): Option[(RequestHeaders, ClientRequest)] =
    withLock(lock) {
      latestValidClerkWatchRequestsByTarget.get(target)
    }

  /**
   * Returns the latest slicelet watch request (including the headers) received for the given
   * target (if any).
   */
  def getLatestSliceletWatchRequest(target: Target): Option[(RequestHeaders, ClientRequest)] =
    withLock(lock) {
      latestValidSliceletWatchRequestsByTarget.get(target)
    }

  /**
   * Returns the latest slicelet watch request (including the headers) received for a given target
   * and Slicelet (`squid`), if any.
   */
  def getLatestSliceletWatchRequest(
      target: Target,
      squid: Squid): Option[(RequestHeaders, ClientRequest)] =
    withLock(lock) {
      latestValidSliceletWatchRequests.get((target, squid))
    }

  /**
   * Allow the assignment generator to perform normal assignment generation rather than having
   * assignments be set directly. Yields the thawed assignment, which may be `None` when the target
   * has no assignment.
   */
  def unfreezeAssignment(target: Target): Future[Option[Assignment]] = {
    interceptableStore
      .getLatestKnownAssignment(target)
      .flatMap {
        case Some(latestAssignment: Assignment) =>
          if (latestAssignment.isFrozen) {
            // Write an assignment that is identical to the latest assignment but with the frozen
            // bit cleared. We create a proposal carrying forward all details of the frozen
            // assignment, including the load metrics from the frozen assignment.
            val sliceAssignments: SliceMap[ProposedSliceAssignment] =
              latestAssignment.sliceMap.map(
                SliceMapHelper.PROPOSED_SLICE_ASSIGNMENT_ACCESSOR
              ) { sliceAssignment =>
                ProposedSliceAssignment(
                  sliceAssignment.slice,
                  sliceAssignment.resources,
                  sliceAssignment.primaryRateLoadOpt
                )
              }
            val proposal = ProposedAssignment(Some(latestAssignment), sliceAssignments)
            interceptableStore
              .writeAssignment(
                target,
                shouldFreeze = false,
                proposal
              )
              .flatMap {
                case WriteAssignmentResult.OccFailure(actualGeneration: Generation) =>
                  // Retry! Another assignment write conflicted with the current write attempt.
                  logger.warn(
                    s"Retrying unfreeze for $target after OCC failure: " +
                    s"actualGeneration=$actualGeneration, " +
                    s"expectedGeneration=${latestAssignment.generation}"
                  )
                  unfreezeAssignment(target)
                case WriteAssignmentResult.Committed(assignment: Assignment) =>
                  Future.successful(Some(assignment))
              }(sec)
          } else {
            // Already unfrozen. Since the underlying store is in-memory, we don't need to worry
            // about stale cached assignments.
            Future.successful(Some(latestAssignment))
          }
        case None =>
          // When the store has no assignment, the generator does not consider it to be frozen.
          // Since the underlying store is in-memory, we don't need to worry about stale cached
          // assignments.
          Future.successful(None)
      }(sec)
  }

  /**
   * Uses the `proposedAssignment` to set the assignment to be sent to the clients and disable
   * assignment generation. The assigner chooses a generation, which is populated in the returned
   * assignment.
   */
  def setAndFreezeAssignment(
      target: Target,
      proposal: SliceMap[ProposedSliceAssignment]): Future[Assignment] = {
    interceptableStore
      .getLatestKnownAssignment(target)
      .flatMap { predecessorOpt: Option[Assignment] =>
        val proposedAssignment = ProposedAssignment(predecessorOpt, proposal)
        interceptableStore
          .writeAssignment(
            target,
            shouldFreeze = true,
            proposedAssignment
          )
          .flatMap {
            case WriteAssignmentResult.OccFailure(actualGeneration: Generation) =>
              // Retry! Another assignment write conflicted with the current write attempt.
              logger.warn(
                s"Retrying assignment write for $target after OCC failure: " +
                s"actualGeneration=$actualGeneration, " +
                s"expectedPredecessor=$predecessorOpt"
              )
              setAndFreezeAssignment(target, proposal)
            case WriteAssignmentResult.Committed(assignment: Assignment) =>
              Future.successful(assignment)
          }(sec)
      }(sec)
  }

  /** Blocks assignment writes for the given target. */
  def blockAssignment(target: Target): Future[Unit] = interceptableStore.sec.call {
    interceptableStore.blockAssignmentWrites(target)
  }

  /** Unblocks assignment writes for the given target. */
  def unblockAssignment(target: Target): Future[Unit] = interceptableStore.sec.call {
    interceptableStore.unblockAssignmentWrites(target)
  }

  /**
   * Returns the current assignment for the given `target` if any (that has been
   * propagated to subscribers).
   */
  def getAssignment(target: Target): Future[Option[Assignment]] = {
    forTest
      .getGeneratorFromMap(target)
      .map { generatorOpt: Option[AssignmentGeneratorDriver] =>
        generatorOpt.flatMap { generator: AssignmentGeneratorDriver =>
          generator.getGeneratorCell.getLatestValueOpt
        }
      }(NamedExecutor.globalImplicit)
  }

  /**
   * Returns the current assignment for the given `target` if any. Similar to [[getAssignment]]
   * but it can be called even if the assignment generator might not yet exist. By using
   * `lookupOrCreateGenerator`, it forces the creation of a generator without active subscribers.
   * Can be used to verify generator creation or assignment propagation from store on Assigners
   * without subscribers.
   *
   * Deprecated - use [[getAssignment]] instead and rethink test design.
   */
  def getAssignmentCreatingGeneratorDeprecated(target: Target): Future[Option[Assignment]] = {
    forTest
      .lookupOrCreateGenerator(target)
      .map { generatorOpt: Option[AssignmentGeneratorDriver] =>
        generatorOpt.flatMap { generator: AssignmentGeneratorDriver =>
          generator.getGeneratorCell.getLatestValueOpt
        }
      }(NamedExecutor.globalImplicit)
  }

  /** Sends a termination notice to the preferred assigner driver. */
  def sendTerminationNotice(): Unit = {
    preferredAssignerDriver.sendTerminationNotice()
  }

  /** Shuts down the preferred assigner driver. */
  def shutDownPreferredAssignerDriver(): Unit = {
    preferredAssignerDriver match {
      case driver: InterposingEtcdPreferredAssignerDriver =>
        driver.shutdown(ShutdownOption.ABRUPT)
      case _ => // Do nothing
    }
  }

  /**
   * Gets the highest successful heartbeat `opId` this assigner has ever sent to another
   * assigner.
   */
  def getHighestSucceededHeartbeatOpID: Future[Long] = {
    preferredAssignerDriver match {
      case driver: InterposingEtcdPreferredAssignerDriver =>
        driver.getHighestSucceededOpID
      case _ => Future.successful(0L)
    }
  }

  /** Pauses handling heartbeats. */
  def pauseHeartbeatResponse(): Unit = withLock(lock) {
    pauseHandlingHeartbeat = true
  }

  /** Resumes handling heartbeats. */
  def resumeHeartbeatResponse(): Unit = withLock(lock) {
    pauseHandlingHeartbeat = false
  }
}

/** Companion object for [[TestAssigner]]. */
object TestAssigner {

  /**
   * A [[Assigner.StoreFactory]] that always returns the same [[InterceptableStore]], allowing
   * tests to avoid casting when they need the interceptable store.
   */
  final class InterceptableStoreFactory(store: InterceptableStore) extends Assigner.StoreFactory {
    override def getStore(): InterceptableStore = store
  }

  private val logger = PrefixLogger.create(TestAssigner.getClass, "")

  /**
   * Configuration for the test assigner.
   *
   * @param assignerConf Assigner configuration.
   * @param preferredAssignerDriverConfig Preferred assigner driver configuration.
   * It provides tests the ability to provide faster timeouts and intervals to speed up scenarios
   * like testing preferred assigner failovers, for example. By default, it will use the production
   * config found in [[EtcdPreferredAssignerDriver.Config]].
   */
  class Config private (
      val assignerConf: TestableDicerAssignerConf,
      val preferredAssignerDriverConfig: EtcdPreferredAssignerDriver.Config)

  /** Companion object for [[Config]]. */
  object Config {

    /**
     * Creates a configuration for a test Assigner based on the given parameters.
     */
    def create(
        assignerConf: DicerAssignerConf = new DicerAssignerConf(Configs.empty),
        tlsOptionsOpt: Option[TLSOptions] = None,
        designatedDicerAssignerRpcPort: Option[Int] = None,
        expectRequestsThroughS2SProxy: Boolean = false,
        preferredAssignerDriverConfig: EtcdPreferredAssignerDriver.Config =
          EtcdPreferredAssignerDriver.Config()): Config = {
      // This is needed because Scala anonymous classes are not able to capture and refer to
      // variables with the same name as a method in the class.
      val expectRequestsThroughS2SProxyVar: Boolean = expectRequestsThroughS2SProxy

      // Override the port and ssl args before starting the server, so each test doesn't need
      // to do this.
      val testConf = new TestableDicerAssignerConf(assignerConf.rawConfig) {
        override val dicerAssignerRpcPort: Int = designatedDicerAssignerRpcPort.getOrElse(0)

        // Use a short poll interval for tests.
        override val dynamicConfigPollInterval: FiniteDuration = 100.milliseconds

        override val dicerClientTlsOptions: Option[TLSOptions] =
          tlsOptionsOpt.orElse(TestTLSOptions.clientTlsOptionsOpt)

        override val dicerServerTlsOptions: Option[TLSOptions] =
          tlsOptionsOpt.orElse(TestTLSOptions.serverTlsOptionsOpt)

        override val expectRequestsThroughS2SProxy: Boolean = expectRequestsThroughS2SProxyVar
      }
      new Config(testConf, preferredAssignerDriverConfig)
    }
  }

  object AssignerReplyType {

    /** Types of reply that TestAssigner can send. */
    sealed trait ReplyType
    object Normal extends ReplyType

    /** Proto is the right type but fails validation. */
    object InvalidProto extends ReplyType

    /** Send an error response, defaulting to aborted status exception. */
    case class Error(
        exception: Exception = AssignerRpcTestHelper.createAbortedStatusException("Fake exception"))
        extends ReplyType

    /** Handle the request as per normal but overwrite the redirect field in the response. */
    case class OverwriteRedirect(redirect: Redirect) extends ReplyType

    /** Override the response to be `future`. */
    case class FutureOverride(future: Future[ClientResponseP]) extends ReplyType
  }

  /**
   * Factory for returning health watchers that have already passed the starting state and are
   * aware that there are currently no healthy resources for their targets, so they are capable of
   * generating a health report immediately.
   */
  private[dicer] class TestHealthWatcherFactory(storeIncarnation: Incarnation)
      extends HealthWatcher.Factory {
    override def create(
        target: Target,
        config: HealthWatcher.StaticConfig,
        healthWatcherTargetConfig: HealthWatcherTargetConfig): HealthWatcher = {
      val healthWatcher = new HealthWatcher(target, config, healthWatcherTargetConfig)

      // We bypass the starting phase of HealthWatcher by advancing the HealthWater on two different
      // times with an interval no less than the bootstrapping delay.
      val currentTickerTime: TickerTime = RealtimeTypedClock.tickerTime()
      val currentInstant: Instant = RealtimeTypedClock.instant()
      // Kickoff health watcher into starting state.
      healthWatcher.onAdvance(
        currentTickerTime - config.unhealthyTimeoutPeriod,
        currentInstant.minusNanos(config.unhealthyTimeoutPeriod.toNanos)
      )
      // Advance health watcher to bypass starting state.
      healthWatcher.onAdvance(currentTickerTime, currentInstant)
      healthWatcher
    }
  }

  /**
   * Returns a running TestAssigner listening for watch requests. Clients should ensure they
   * call [[TestAssigner.stop]] after they are done with it.
   *
   * @param secPool The [[SequentialExecutionContextPool]] used by this test Assigner. The caller
   *                can provide a [[FakeSequentialExecutionContextPool]] to make all of the
   *                Assigner's state machine components share the same fake clock.
   * @param config The configuration for this test Assigner. See [[DicerAssignerConf]] for supported
   *               configurations.
   * @param configProvider The provider of target configurations for this test Assigner.
   * @param dockerizedEtcdOpt When specified and the Assigner is configured to use etcd, this test
   *                          Assigner uses the etcd instance contained within for durable storage.
   *                          See [[DicerAssignerConf.store]] and
   *                          [[DicerAssignerConf.preferredAssignerEnabled]] for configurations
   *                          which use etcd.
   * @param assignerClusterUri The URI of the kubernetes cluster that the assigner will be running
   *                           in (see <internal link>).
   */
  def createAndStart(
      secPool: SequentialExecutionContextPool,
      config: Config,
      configProvider: StaticTargetConfigProvider,
      dockerizedEtcdOpt: Option[EtcdTestEnvironment],
      assignerClusterUri: URI,
      dPageNamespaceOpt: Option[String] = None): TestAssigner = {
    logger.info(s"Starting TestAssigner")
    val sec: SequentialExecutionContext = secPool.createExecutionContext("test-assigner-store")
    val assignerSec: SequentialExecutionContext = secPool.createExecutionContext("test-assigner")
    val store: Store = createStore(sec, config.assignerConf, dockerizedEtcdOpt)

    val paSec: SequentialExecutionContext =
      secPool.createExecutionContext("test-preferred-assigner-sec")
    val preferredAssignerDriver: PreferredAssignerDriver = createPreferredAssignerDriver(
      paSec,
      config.assignerConf,
      dockerizedEtcdOpt,
      driverConfig = config.preferredAssignerDriverConfig
    )

    val minAssignmentGenerationInterval: FiniteDuration = secPool match {
      case _: FakeSequentialExecutionContextPool =>
        // Set the assignment generating interval restriction to 0 to exclude its affect to tests
        // using fake clock.
        Duration.Zero
      case _: SequentialExecutionContextPool =>
        // When the tests are using real clock, set the minimum generating interval to 50ms so that
        // it won't cause the test to run too long but still provide a chance to exercise the
        // assignment generating rate limiting.
        50.milliseconds
    }

    val interceptableStore: InterceptableStore = new InterceptableStore(sec, store)
    val storeFactory: TestAssigner.InterceptableStoreFactory =
      new TestAssigner.InterceptableStoreFactory(interceptableStore)
    val testAssigner: TestAssigner = new TestAssigner(
      secPool,
      assignerSec,
      config.assignerConf,
      preferredAssignerDriver,
      storeFactory,
      // Use fake kubernetes watcher, since we can't interact with Kubernetes API server.
      new FakeKubernetesTargetWatcherFactory(),
      new TestHealthWatcherFactory(config.assignerConf.storeIncarnation),
      configProvider,
      assignerClusterUri = assignerClusterUri,
      minAssignmentGenerationInterval = minAssignmentGenerationInterval,
      dPageNamespaceOpt = dPageNamespaceOpt
    )
    testAssigner.start()
    testAssigner
  }

  /**
   * REQUIRES: When the config specifies the Assigner to use etcd mode,
   * `dockerizedEtcdOpt` must be defined.
   *
   * Returns a store running on `sec` with the configuration specified in `conf`.
   */
  private def createStore(
      sec: SequentialExecutionContext,
      conf: DicerAssignerConf,
      dockerizedEtcdOpt: Option[EtcdTestEnvironment]): Store = {
    conf.store match {
      case IN_MEMORY =>
        logger.info("Initializing InMemoryStore.")
        InMemoryStore(
          sec,
          conf.storeIncarnation
        )
      case ETCD =>
        logger.info("Initializing EtcdStore.")
        require(
          dockerizedEtcdOpt.isDefined,
          "dockerizedEtcdOpt must be defined for assigner using etcd store mode. Please check if " +
          "allowEtcdMode is set to true if you are using InternalDicerTestEnvironment."
        )
        val etcdClient: EtcdClient = dockerizedEtcdOpt.get.createEtcdClient(
          EtcdClient.Config(Assigner.getAssignmentsEtcdNamespace(conf))
        )
        val etcdStoreConfig = EtcdStoreConfig.create(conf.storeIncarnation)
        EtcdStore.create(sec, etcdClient, etcdStoreConfig, new Random)
    }
  }

  /**
   * REQUIRES: when `conf.preferredAssignerEnabled` is true, `dockerizedEtcdOpt` must be defined.
   *
   * Creates a preferred Assigner driver.
   */
  private def createPreferredAssignerDriver(
      sec: SequentialExecutionContext,
      conf: DicerAssignerConf,
      dockerizedEtcdOpt: Option[EtcdTestEnvironment],
      storeConfig: EtcdPreferredAssignerStore.Config = EtcdPreferredAssignerStore.DEFAULT_CONFIG,
      driverConfig: EtcdPreferredAssignerDriver.Config,
      random: Random = new Random): PreferredAssignerDriver = {
    if (conf.preferredAssignerEnabled) {
      require(
        dockerizedEtcdOpt.isDefined,
        "dockerizedEtcdOpt must be defined when preferred assigner is enabled."
      )
      val etcd: EtcdTestEnvironment = dockerizedEtcdOpt.get
      val preferredAssignerEtcdNamespace = Assigner.getPreferredAssignerEtcdNamespace(conf)
      val etcdClientConfig = EtcdClient.Config(preferredAssignerEtcdNamespace)
      val storeIncarnation: Incarnation = Incarnation(conf.preferredAssignerStoreIncarnation)
      val store = InterposingEtcdPreferredAssignerStore
        .create(sec, storeIncarnation, etcd, etcdClientConfig, random, storeConfig)
      logger.info("Initializing EtcdPreferredAssignerDriver.")
      val tlsOptions: Option[TLSOptions] = conf.getDicerClientTlsOptions
      new InterposingEtcdPreferredAssignerDriver(sec, tlsOptions, store, driverConfig)
    } else {
      logger.info("Initializing DisabledPreferredAssignerDriver.")
      new DisabledPreferredAssignerDriver(Incarnation(conf.preferredAssignerStoreIncarnation))
    }

  }
}

package com.databricks.dicer.common

import com.databricks.api.proto.dicer.assigner.{HeartbeatRequestP, HeartbeatResponseP}
import com.databricks.dicer.common.TargetHelper.TargetOps
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.apis.CoreV1Api
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
import com.databricks.caching.util.AssertMacros.iassert
import com.databricks.conf.Configs
import com.databricks.dicer.assigner.InterposingEtcdPreferredAssignerDriver.ShutdownOption
import com.databricks.dicer.assigner.Store.WriteAssignmentResult
import com.databricks.dicer.assigner.conf.DicerAssignerConf
import com.databricks.dicer.assigner.config.{TargetConfigProvider, TargetMigrationConfig}
import com.databricks.dicer.assigner.{
  Assigner,
  AssignerInfo,
  AssignerRpcTestHelper,
  AssignmentGeneratorDriver,
  ConsistentHashingPreferredAssignerDriver,
  DisabledPreferredAssignerDriver,
  EtcdPreferredAssignerDriver,
  EtcdPreferredAssignerStore,
  FakeKubernetesTargetWatcherFactory,
  HealthWatcher,
  InMemoryStore,
  InterposingEtcdPreferredAssignerDriver,
  InterposingEtcdPreferredAssignerStore,
  KubernetesMembershipChecker,
  MigrationMode,
  MigrationPreferredAssignerDriver,
  PreferredAssignerDriver,
  Store,
  TargetMigrator,
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
    conf: TestableDicerAssignerConf,
    preferredAssignerDriver: PreferredAssignerDriver,
    storeFactory: TestAssigner.InterceptableStoreFactory,
    fakeKubernetesTargetWatcherFactory: FakeKubernetesTargetWatcherFactory,
    healthWatcherFactory: HealthWatcher.Factory,
    configProvider: TargetConfigProvider,
    uuid: UUID,
    hostName: String = "localhost",
    assignerClusterUri: URI,
    minAssignmentGenerationInterval: FiniteDuration,
    dPageNamespaceOpt: Option[String],
    targetMigrator: TargetMigrator,
    localClusterMembershipChecker: KubernetesMembershipChecker,
    assignerServiceInfoOpt: Option[AssignerServiceInfo])
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
      dPageNamespaceOpt,
      targetMigrator = targetMigrator,
      localClusterMembershipChecker = localClusterMembershipChecker,
      assignerServiceInfoOpt = assignerServiceInfoOpt
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
   * The latest, valid Clerk watch request, together with its headers, received for each [[Target]]
   * (where [[Target]] is the Assigner normalized representation of the target identifier, see
   * [[getAssignerNormalizedTarget]]).
   */
  private val latestValidClerkWatchRequestsByTarget =
    mutable.Map[Target, (RequestHeaders, ClientRequest)]()

  /**
   * The latest, valid Slicelet watch request, together with its headers, received for each
   * [[Target]] (where [[Target]] is the Assigner normalized representation of the target
   * identifier, see [[getAssignerNormalizedTarget]]).
   */
  private val latestValidSliceletWatchRequestsByTarget =
    mutable.Map[Target, (RequestHeaders, ClientRequest)]()

  /**
   * The latest, valid Slicelet watch request, together with its headers, received for each
   * [[Target]], by target and squid (where [[Target]] is the Assigner normalized representation of
   * the target identifier, see [[getAssignerNormalizedTarget]]).
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
    // preferred, and we need to block that write if `shutdownOption` is `ABRUPT`. Route through
    // `etcdDriverForTest` so this also blocks the write when the driver is migration-wrapped.
    etcdDriverForTest.foreach { driver: InterposingEtcdPreferredAssignerDriver =>
      TestUtils.awaitResult(driver.shutdown(shutdownOption), Duration.Inf)
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
      latestValidClerkWatchRequestsByTarget.get(getAssignerNormalizedTarget(target))
    }

  /**
   * Returns the latest slicelet watch request (including the headers) received for the given
   * target (if any).
   */
  def getLatestSliceletWatchRequest(target: Target): Option[(RequestHeaders, ClientRequest)] =
    withLock(lock) {
      latestValidSliceletWatchRequestsByTarget.get(getAssignerNormalizedTarget(target))
    }

  /**
   * Returns the latest slicelet watch request (including the headers) received for a given target
   * and Slicelet (`squid`), if any.
   */
  def getLatestSliceletWatchRequest(
      target: Target,
      squid: Squid): Option[(RequestHeaders, ClientRequest)] =
    withLock(lock) {
      latestValidSliceletWatchRequests.get((getAssignerNormalizedTarget(target), squid))
    }

  /**
   * Allow the assignment generator to perform normal assignment generation rather than having
   * assignments be set directly. Yields the thawed assignment, which may be `None` when the target
   * has no assignment.
   */
  def unfreezeAssignment(target: Target): Future[Option[Assignment]] = {
    val normalizedTarget: Target = getAssignerNormalizedTarget(target)
    interceptableStore
      .getLatestKnownAssignment(normalizedTarget)
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
            val proposal = ProposedAssignment(
              Some(latestAssignment),
              sliceAssignments,
              assignerServiceInfoOpt
            )
            interceptableStore
              .writeAssignment(
                normalizedTarget,
                shouldFreeze = false,
                proposal
              )
              .flatMap {
                case WriteAssignmentResult.OccFailure(actualGeneration: Generation) =>
                  // Retry! Another assignment write conflicted with the current write attempt.
                  logger.warn(
                    s"Retrying unfreeze for $normalizedTarget after OCC failure: " +
                    s"actualGeneration=$actualGeneration, " +
                    s"expectedGeneration=${latestAssignment.generation}"
                  )
                  unfreezeAssignment(normalizedTarget)
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
    val normalizedTarget: Target = getAssignerNormalizedTarget(target)
    interceptableStore
      .getLatestKnownAssignment(normalizedTarget)
      .flatMap { predecessorOpt: Option[Assignment] =>
        val proposedAssignment =
          ProposedAssignment(
            predecessorOpt,
            sliceMap = proposal,
            assignerServiceInfoOpt
          )
        interceptableStore
          .writeAssignment(
            normalizedTarget,
            shouldFreeze = true,
            proposedAssignment
          )
          .flatMap {
            case WriteAssignmentResult.OccFailure(actualGeneration: Generation) =>
              // Retry! Another assignment write conflicted with the current write attempt.
              logger.warn(
                s"Retrying assignment write for $normalizedTarget after OCC failure: " +
                s"actualGeneration=$actualGeneration, " +
                s"expectedPredecessor=$predecessorOpt"
              )
              setAndFreezeAssignment(normalizedTarget, proposal)
            case WriteAssignmentResult.Committed(assignment: Assignment) =>
              Future.successful(assignment)
          }(sec)
      }(sec)
  }

  /** Blocks assignment writes for the given target. */
  def blockAssignment(target: Target): Future[Unit] = interceptableStore.sec.call {
    interceptableStore.blockAssignmentWrites(getAssignerNormalizedTarget(target))
  }

  /** Unblocks assignment writes for the given target. */
  def unblockAssignment(target: Target): Future[Unit] = interceptableStore.sec.call {
    interceptableStore.unblockAssignmentWrites(getAssignerNormalizedTarget(target))
  }

  /**
   * Returns the current assignment for the given `target` if any (that has been
   * propagated to subscribers).
   */
  def getAssignment(target: Target): Future[Option[Assignment]] = {
    forTest
      .getGeneratorFromMap(getAssignerNormalizedTarget(target))
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
      .lookupOrCreateGenerator(getAssignerNormalizedTarget(target))
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
    etcdDriverForTest.foreach(_.shutdown(ShutdownOption.ABRUPT))
  }

  /**
   * Simulates the etcd store disappearing for this assigner: the interposing etcd driver starts
   * failing reads/watches and blocks writes, mirroring the production effect of losing the etcd
   * cluster without tearing the assigner down. Used to verify that a
   * [[MigrationMode.ConsistentHashingPrimaryEtcdWritesMode]] assigner keeps electing (its reads are
   * consistent-hashing-driven and never read etcd back). Returns a [[Future]] that completes once
   * the fault is armed.
   */
  def failEtcdForTest(): Future[Unit] = {
    etcdDriverForTest match {
      case Some(driver: InterposingEtcdPreferredAssignerDriver) =>
        driver.shutdown(ShutdownOption.ABRUPT)
      case None =>
        Future.successful(())
    }
  }

  /**
   * The interposing etcd driver backing this assigner, whether it is the assigner's driver directly
   * or the old driver wrapped inside a [[MigrationPreferredAssignerDriver]]; `None` when preferred
   * assigner is disabled.
   */
  private def etcdDriverForTest: Option[InterposingEtcdPreferredAssignerDriver] =
    preferredAssignerDriver match {
      case driver: InterposingEtcdPreferredAssignerDriver => Some(driver)
      case migration: MigrationPreferredAssignerDriver =>
        migration.forTest.oldDriver match {
          case driver: InterposingEtcdPreferredAssignerDriver => Some(driver)
          case _ => None
        }
      case _ => None
    }

  /**
   * Gets the highest successful heartbeat `opId` this assigner has ever sent to another
   * assigner.
   */
  def getHighestSucceededHeartbeatOpID: Future[Long] = {
    etcdDriverForTest
      .map(_.getHighestSucceededOpID)
      .getOrElse(Future.successful(0L))
  }

  /** Pauses handling heartbeats. */
  def pauseHeartbeatResponse(): Unit = withLock(lock) {
    pauseHandlingHeartbeat = true
  }

  /** Resumes handling heartbeats. */
  def resumeHeartbeatResponse(): Unit = withLock(lock) {
    pauseHandlingHeartbeat = false
  }

  /**
   * Returns the normalized representation of the target identifier that the Assigner uses
   * internally to identify `target`. See [[TargetUnmarshaller]].
   */
  private def getAssignerNormalizedTarget(target: Target): Target = {
    forTest.getTargetUnmarshaller.fromProto(target.toProto)
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
   * Builds a [[PreferredAssignerDriver]] for a test assigner from the driver's
   * [[SequentialExecutionContext]] and the membership checker built for the assigner (if any). Any
   * dockerized etcd a driver needs is captured by the factory itself (the default binds the one the
   * test environment threads in), so it is not a parameter here. See
   * [[defaultPreferredAssignerDriverFactory]] for the conf-derived default.
   */
  type PreferredAssignerDriverFactory =
    (SequentialExecutionContext, Option[KubernetesMembershipChecker]) => PreferredAssignerDriver

  /**
   * The default [[PreferredAssignerDriverFactory]]: builds the driver derived from `conf` and
   * `driverConfig`, backed by `dockerizedEtcdOpt`.
   */
  def defaultPreferredAssignerDriverFactory(
      conf: DicerAssignerConf,
      driverConfig: EtcdPreferredAssignerDriver.Config,
      dockerizedEtcdOpt: Option[EtcdTestEnvironment]): PreferredAssignerDriverFactory =
    (sec: SequentialExecutionContext, membershipCheckerOpt: Option[KubernetesMembershipChecker]) =>
      createPreferredAssignerDriver(
        sec,
        conf,
        dockerizedEtcdOpt,
        driverConfig,
        membershipCheckerOpt
      )

  /**
   * Configuration for the test assigner.
   *
   * @param assignerConf Assigner configuration.
   * @param preferredAssignerDriverConfig Preferred assigner driver configuration. It provides tests
   *                                      the ability to provide faster timeouts and intervals to
   *                                      speed up scenarios like testing preferred assigner
   *                                      failovers, for example. By default, it will use the
   *                                      production config found in
   *                                      [[EtcdPreferredAssignerDriver.Config]].
   * @param targetMigratorOpt a [[TargetMigrator]] to inject into the constructed [[TestAssigner]].
   *                          Tests exercising a behavior the real migrator cannot serve yet (e.g.
   *                          an active migration) inject one here. Otherwise, a real
   *                          [[TargetMigrator]] is built against the no-op migration config.
   * @param membershipCheckerFactoryOpt When defined, the test assigner runs the production
   *                                    [[MigrationPreferredAssignerDriver]] (etcd-backed old driver
   *                                    plus a consistent-hashing new driver from this factory),
   *                                    exactly as production does; the stage is taken from
   *                                    [[DicerAssignerConf.preferredAssignerMigrationMode]]. When
   *                                    `None`, the assigner runs the etcd-backed driver alone,
   *                                    preserving the historical default for suites that don't
   *                                    exercise consistent-hashing membership.
   * @param preferredAssignerDriverFactoryOverride When defined, builds the assigner's
   *                                               [[PreferredAssignerDriver]] instead of the
   *                                               conf-derived default, letting a test inject a
   *                                               fake driver (e.g. one that always reports a
   *                                               standby role) without the etcd-backed election
   *                                               machinery. When `None`, the test environment
   *                                               supplies the conf-derived default (see
   *                                               [[defaultPreferredAssignerDriverFactory]]) bound
   *                                               to its dockerized etcd.
   */
  class Config private (
      val assignerConf: TestableDicerAssignerConf,
      val preferredAssignerDriverConfig: EtcdPreferredAssignerDriver.Config,
      val targetMigratorOpt: Option[TargetMigrator],
      val membershipCheckerFactoryOpt: Option[KubernetesMembershipChecker.Factory],
      val preferredAssignerDriverFactoryOverride: Option[PreferredAssignerDriverFactory])

  /** Companion object for [[Config]]. */
  object Config {

    // If a field is added here, update
    // [[InternalDicerTestEnvironment.restartAssignerWithSameConfig]] to forward it, otherwise a
    // restarted Assigner silently drops it.
    /** Creates a configuration for a test Assigner based on the given parameters. */
    def create(
        assignerConf: DicerAssignerConf = new DicerAssignerConf(Configs.empty),
        tlsOptionsOpt: Option[TLSOptions] = None,
        designatedDicerAssignerRpcPort: Option[Int] = None,
        expectRequestsThroughS2SProxy: Boolean = false,
        preferredAssignerDriverConfig: EtcdPreferredAssignerDriver.Config =
          EtcdPreferredAssignerDriver.Config(),
        targetMigratorOpt: Option[TargetMigrator] = None,
        membershipCheckerFactoryOpt: Option[KubernetesMembershipChecker.Factory] = None,
        // When `None`, the test environment supplies the conf-derived default bound to its
        // dockerized etcd (which only the environment owns).
        preferredAssignerDriverFactoryOverride: Option[PreferredAssignerDriverFactory] = None)
        : Config = {
      // This is needed because Scala anonymous classes are not able to capture and refer to
      // variables with the same name as a method in the class.
      val expectRequestsThroughS2SProxyVar: Boolean = expectRequestsThroughS2SProxy

      // Override the port and ssl args before starting the server, so each test doesn't need
      // to do this.
      val testConf = new TestableDicerAssignerConf(assignerConf.rawConfig) {
        override val dicerAssignerRpcPort: Int = designatedDicerAssignerRpcPort.getOrElse(0)

        // Use a short poll interval for tests.
        override val dynamicConfigPollInterval: FiniteDuration = 100.milliseconds

        // Likewise poll the target migration config quickly so SAFE-driven config changes
        // propagate within a test's timeout.
        override val dynamicTargetMigrationConfigPollInterval: FiniteDuration = 100.milliseconds

        override val dicerClientTlsOptions: Option[TLSOptions] =
          tlsOptionsOpt.orElse(TestTLSOptions.clientTlsOptionsOpt)

        override val dicerServerTlsOptions: Option[TLSOptions] =
          tlsOptionsOpt.orElse(TestTLSOptions.serverTlsOptionsOpt)

        override val expectRequestsThroughS2SProxy: Boolean = expectRequestsThroughS2SProxyVar
      }
      // This is a temporary workaround until the real TargetMigrator implementation is complete.
      // For now, an injected `targetMigratorOpt` is always a [[FakeTargetMigrator]] used to
      // exercise a specific behavior (e.g. an active migration) that the real migrator cannot serve
      // yet. When no migrator is injected, we seed the SAFE value with a no-op target migration
      // config and let [[TestAssigner.createAndStart]] build the real migrator (which only supports
      // no-op migrations for now).
      //
      // TODO(<internal bug>): Once the real TargetMigrator supports active migrations, remove the
      // `targetMigratorOpt` parameter and instead accept a `TargetMigrationConfig` to seed here, so
      // tests drive both no-op and active migrations through the real migrator.
      if (targetMigratorOpt.isEmpty) {
        testConf.putDynamicTargetMigrationConfig(
          TargetMigrationConfig.toJsonString(TargetMigrationConfig.NO_MIGRATION)
        )
      }

      new Config(
        testConf,
        preferredAssignerDriverConfig,
        targetMigratorOpt,
        membershipCheckerFactoryOpt,
        preferredAssignerDriverFactoryOverride
      )
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
   * @param preferredAssignerDriverFactory Builds the assigner's [[PreferredAssignerDriver]]. The
   *                                       caller (the test environment) supplies it, binding the
   *                                       dockerized etcd an etcd-backed driver needs; see
   *                                       [[defaultPreferredAssignerDriverFactory]].
   * @param assignerClusterUri The URI of the kubernetes cluster that the assigner will be running
   *                           in (see <internal link>).
   * @param assignerServiceInfoOpt The service info of the Assigner, used to uniquely identify an
   *                               assigner instance, or [[None]] when not available.
   */
  def createAndStart(
      secPool: SequentialExecutionContextPool,
      config: Config,
      configProvider: TargetConfigProvider,
      preferredAssignerDriverFactory: PreferredAssignerDriverFactory,
      assignerClusterUri: URI,
      assignerServiceInfoOpt: Option[AssignerServiceInfo],
      dPageNamespaceOpt: Option[String] = None): TestAssigner = {
    logger.info(s"Starting TestAssigner")
    val sec: SequentialExecutionContext = secPool.createExecutionContext("test-assigner-store")
    val assignerSec: SequentialExecutionContext = secPool.createExecutionContext("test-assigner")
    val store: Store = InMemoryStore(sec, config.assignerConf.storeIncarnation)

    val paSec: SequentialExecutionContext =
      secPool.createExecutionContext("test-preferred-assigner-sec")
    val uuid: UUID = UUID.randomUUID()
    // The checker a supplied factory builds (typically backed by a FakeKubernetesServer), if any.
    // This drives which preferred-assigner driver the test runs (see below).
    val factoryMembershipCheckerOpt: Option[KubernetesMembershipChecker] =
      config.membershipCheckerFactoryOpt.map { factory: KubernetesMembershipChecker.Factory =>
        factory.create(uuid)
      }
    // The Assigner always requires a membership checker. Tests that don't supply a factory get an
    // inert checker purely to satisfy the constructor; it is left unwired from the driver (the
    // etcd-only driver above ignores it) and never polls (1-hour interval), so it has no effect on
    // those tests. The Assigner stops it on teardown. This mirrors
    // FakeKubernetesTestSupport.inertMembershipCheckerFactory but is kept local because that helper
    // is 2.12-only (it depends on the fake K8s server), while TestAssigner also cross-builds 2.13.
    val localClusterMembershipChecker: KubernetesMembershipChecker =
      factoryMembershipCheckerOpt.getOrElse(buildInertMembershipChecker(secPool, uuid))
    // The caller supplies the driver factory (binding any dockerized etcd it needs); here we just
    // build the driver from the driver's SEC and the membership checker.
    val preferredAssignerDriver: PreferredAssignerDriver =
      preferredAssignerDriverFactory(paSec, factoryMembershipCheckerOpt)

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

    // This is a temporary workaround until the real TargetMigrator implementation is complete.
    // For now, if a `targetMigratorOpt` is injected, it is always a [[FakeTargetMigrator]] used
    // to exercise a specific behavior (e.g. an active migration) that the real migrator cannot
    // serve yet. When no migrator is injected, we build the real migrator against the no-op
    // migration config that `Config.create` seeded into SAFE.
    //
    // TODO(<internal bug>): Once the real TargetMigrator supports active migrations, we will always build
    // the real migrator here and no longer take in an injected `targetMigratorOpt` (see the
    // corresponding TODO in `Config.create`).
    val targetMigrator: TargetMigrator = config.targetMigratorOpt.getOrElse {
      val targetMigratorSec: SequentialExecutionContext =
        secPool.createExecutionContext("test-target-migrator")
      // Since we're only building a real migrator against the no-op migration config for now (see
      // comments above), it never enters an active migration: we pass no remote membership checker
      // factory, which is never invoked.
      TargetMigrator.create(
        targetMigratorSec,
        config.assignerConf,
        assignerUuid = UUID.randomUUID(),
        assignerClusterUri,
        remoteClusterMembershipCheckerFactoryOpt = None,
        TargetMigrator.DEFAULT_INITIAL_TARGET_OWNERSHIP_RESOLVER_AWAIT_TIMEOUT
      )
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
      uuid = uuid,
      assignerClusterUri = assignerClusterUri,
      minAssignmentGenerationInterval = minAssignmentGenerationInterval,
      dPageNamespaceOpt = dPageNamespaceOpt,
      targetMigrator = targetMigrator,
      localClusterMembershipChecker = localClusterMembershipChecker,
      assignerServiceInfoOpt = assignerServiceInfoOpt
    )
    testAssigner.start()
    testAssigner
  }

  /**
   * Builds the preferred-Assigner driver for the test assigner, backing the etcd driver with the
   * dockerized etcd the test supplies. When the preferred-assigner mode is disabled, returns a
   * [[DisabledPreferredAssignerDriver]]. When a `membershipCheckerOpt` is supplied, the assigner
   * runs the [[MigrationPreferredAssignerDriver]] (etcd-backed old driver plus a
   * [[ConsistentHashingPreferredAssignerDriver]] new driver), with the migration stage read from
   * [[DicerAssignerConf.preferredAssignerMigrationMode]]; otherwise it runs the etcd-backed driver
   * alone.
   *
   * NOTE: this keys the driver on whether a checker *factory* was supplied, which production no
   * longer does (production always builds the migration driver when the PA is enabled). Tests that
   * don't exercise consistent-hashing membership pass no factory and get the etcd-only driver, so
   * they aren't perturbed by a never-polling checker. PRECONDITION: when
   * `conf.preferredAssignerEnabled` is true, `dockerizedEtcdOpt` is defined.
   */
  private def createPreferredAssignerDriver(
      sec: SequentialExecutionContext,
      conf: DicerAssignerConf,
      dockerizedEtcdOpt: Option[EtcdTestEnvironment],
      driverConfig: EtcdPreferredAssignerDriver.Config,
      membershipCheckerOpt: Option[KubernetesMembershipChecker]): PreferredAssignerDriver = {
    if (!conf.preferredAssignerEnabled) {
      logger.info("Initializing DisabledPreferredAssignerDriver.")
      new DisabledPreferredAssignerDriver(Incarnation(conf.preferredAssignerStoreIncarnation))
    } else {
      iassert(
        dockerizedEtcdOpt.isDefined,
        "TestAssigner requires a dockerized etcd when preferredAssignerEnabled is true"
      )
      val etcdDriver: InterposingEtcdPreferredAssignerDriver =
        buildInterposingEtcdDriver(sec, conf, dockerizedEtcdOpt.get, driverConfig)
      membershipCheckerOpt match {
        case Some(membershipChecker: KubernetesMembershipChecker) =>
          // Run the migration driver (as production does when the PA is enabled): the etcd driver
          // is the authoritative old driver and a consistent-hashing new driver runs alongside,
          // with the migration stage read from conf rather than a test-only abstraction.
          val newDriver: ConsistentHashingPreferredAssignerDriver =
            new ConsistentHashingPreferredAssignerDriver(sec, membershipChecker)
          val migrationMode: MigrationMode = conf.preferredAssignerMigrationMode
          logger.info(
            s"Initializing MigrationPreferredAssignerDriver in mode ${migrationMode.name}."
          )
          new MigrationPreferredAssignerDriver(
            sec = sec,
            migrationMode = migrationMode,
            oldDriver = etcdDriver,
            newDriver = newDriver
          )
        case None =>
          logger.info("Initializing etcd-backed preferred-assigner driver.")
          etcdDriver
      }
    }
  }

  /**
   * Builds an inert [[KubernetesMembershipChecker]] for tests that don't supply their own factory.
   * It targets a bare `CoreV1Api` and uses a one-hour poll interval, so it never polls within a
   * test; it exists only to satisfy the Assigner's required-checker constructor and is not wired
   * into the driver, so tests that don't exercise membership never observe it.
   */
  private def buildInertMembershipChecker(
      secPool: SequentialExecutionContextPool,
      uuid: UUID): KubernetesMembershipChecker = {
    val checkerSec: SequentialExecutionContext =
      secPool.createExecutionContext("test-membership-checker")
    new KubernetesMembershipChecker(
      checkerSec,
      new CoreV1Api(new ApiClient()),
      assignerUuid = uuid,
      namespace = "test-namespace",
      appName = "test-app",
      pollingInterval = 1.hour,
      rpcPort = 1,
      kubeContextLabelOpt = None
    )
  }

  /**
   * Builds an [[InterposingEtcdPreferredAssignerDriver]] backed by the given dockerized etcd. Used
   * directly as the assigner's driver in the default (non-migration) case, and as the authoritative
   * old driver inside the [[MigrationPreferredAssignerDriver]] otherwise.
   */
  private def buildInterposingEtcdDriver(
      sec: SequentialExecutionContext,
      conf: DicerAssignerConf,
      dockerizedEtcd: EtcdTestEnvironment,
      driverConfig: EtcdPreferredAssignerDriver.Config): InterposingEtcdPreferredAssignerDriver = {
    val preferredAssignerEtcdNamespace: EtcdClient.KeyNamespace =
      Assigner.getPreferredAssignerEtcdNamespace(conf)
    val etcdClientConfig: EtcdClient.Config = EtcdClient.Config(preferredAssignerEtcdNamespace)
    val storeIncarnation: Incarnation = Incarnation(conf.preferredAssignerStoreIncarnation)
    // Each driver's store gets its own independent RNG instance so that the assigners' stores don't
    // share random state.
    val random: Random = new Random
    val store: InterposingEtcdPreferredAssignerStore = InterposingEtcdPreferredAssignerStore
      .create(
        sec,
        storeIncarnation,
        dockerizedEtcd,
        etcdClientConfig,
        random,
        EtcdPreferredAssignerStore.DEFAULT_CONFIG
      )
    new InterposingEtcdPreferredAssignerDriver(
      sec,
      conf.getDicerClientTlsOptions,
      store,
      driverConfig
    )
  }
}

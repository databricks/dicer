package com.databricks.dicer.assigner

import com.databricks.api.proto.dicer.assigner.{
  GossipRequestP,
  GossipResponseP,
  HeartbeatRequestP,
  HeartbeatResponseP
}
import com.databricks.api.proto.dicer.common.{ClientRequestP, ClientResponseP}
import com.databricks.caching.util.AlertOwnerTeam
import com.databricks.caching.util.AssertMacros.iassert
import com.databricks.caching.util.{
  CachingErrorCode,
  EtcdClient,
  GenericRpcServiceBuilder,
  PrefixLogger,
  SequentialExecutionContext,
  SequentialExecutionContextPool,
  Severity,
  TickerTime,
  ValueStreamCallback,
  WatchValueCell,
  WatchValueCellPollAdapter
}
import com.databricks.common.util.ShutdownHookManager
import com.databricks.dicer.assigner.Assigner.logger
import com.databricks.dicer.assigner.AssignmentGenerator.GeneratorTargetSlicezData
import com.databricks.dicer.assigner.conf.{DicerAssignerConf, HealthConf, LoadWatcherConf}
import com.databricks.dicer.assigner.config.{
  Authorizer,
  AuthorizerMetrics,
  InternalTargetConfig,
  InternalTargetConfigMap,
  InternalTargetConfigMetrics,
  TargetConfigProvider,
  TargetMigrationConfig,
  UnauthorizedException
}
import com.databricks.dicer.common.TargetName
import com.databricks.dicer.assigner.TargetMetrics.GeneratorShutdownReason
import com.databricks.dicer.assigner.config.AuthorizerMetrics.WatchError
import com.databricks.dicer.common.SyncAssignmentState.KnownGeneration
import com.databricks.dicer.common.{
  Assignment,
  AssignerServiceInfo,
  ClerkSubscriberSlicezData,
  ClientRequest,
  ClientResponse,
  ClientType,
  Generation,
  Incarnation,
  Redirect,
  SliceletSubscriberSlicezData,
  TargetUnmarshaller,
  WatchServerHelper
}
import com.databricks.api.base.DatabricksServiceException
import com.databricks.ErrorCode
import com.databricks.dicer.external.{AppTarget, Target}
import com.databricks.rpc.{DatabricksServerWrapper, RPCContext}
import com.databricks.rpc.tls.TLSOptions
import io.grpc.{Status, StatusRuntimeException}
import java.net.URI
import java.util.UUID
import javax.annotation.concurrent.{GuardedBy, ThreadSafe}

import scala.collection.mutable
import scala.compat.java8.FutureConverters._
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Random

/**
 * The main controlling class for the Assigner. It is the entry point to start the Assigner.
 *
 * @param assignerSecPool Shared execution pool primarily used to create per-target SECs. Each SEC
 *                        represents an isolation domain, usually for a specific use case. We
 *                        maintain one SEC per generator and subscriber handler for each target, so
 *                        that different targets don't interfere with each other. The pool allows
 *                        multiple operations to run concurrently without requiring a dedicated
 *                        thread per component. If a long-running operation occurs, other operations
 *                        can still make progress as long as there aren't more concurrent long-lived
 *                        operations than threads in the pool.
 * @param sec The [[SequentialExecutionContext]] that protects the state of the [[Assigner]].
 * @param conf assigner configuration
 * @param storeFactory when applied, returns a [[Store]] for assignment storage.
 * @param assignerClusterUri The URI of the Assigner cluster. This is used to normalize targets.
 * @param minAssignmentGenerationInterval See remarks for [[AssignmentGenerator.config.
 *                                        minAssignmentGenerationInterval]]. Will be used for the
 *                                        assignment generators for all targets. This parameter is
 *                                        lifted here in Assigner's constructor so we can inject
 *                                        different values for TestAssigner.
 * @param dPageNamespace The namespace used for DPage registration. In production this is
 *                       "dicer"; in tests each [[Assigner]] instance uses its UUID so that
 *                       concurrent instances register under unique DAction names.
 * @param targetMigrator Watches the current target migration state and exposes it to
 *                       consumers that need to react to the active migration.
 * @param localClusterMembershipChecker Local-cluster membership checker (not yet started). Backs
 *                                      consistent-hashing selection and the readiness probe; not
 *                                      the checker used by [[TargetMigrator]] (that one lives
 *                                      inside the TargetMigrator instance).
 * @param assignerServiceInfoOpt The service info of the Assigner, used to uniquely identify
 *                               an assigner instance. It should be populated but may be absent
 *                               if the service info is not available due to an outdated binary
 *                               or invalid metadata.
 */
@ThreadSafe
class Assigner private (
    assignerSecPool: SequentialExecutionContextPool,
    sec: SequentialExecutionContext,
    private val conf: DicerAssignerConf,
    preferredAssignerDriver: PreferredAssignerDriver,
    storeFactory: Assigner.StoreFactory,
    kubernetesTargetWatcherFactory: KubernetesTargetWatcher.Factory,
    healthWatcherFactory: HealthWatcher.Factory,
    private val configProvider: TargetConfigProvider,
    uuid: UUID,
    hostName: String,
    assignerClusterUri: URI,
    minAssignmentGenerationInterval: FiniteDuration,
    dPageNamespace: String,
    targetMigrator: TargetMigrator,
    localClusterMembershipChecker: KubernetesMembershipChecker,
    assignerServiceInfoOpt: Option[AssignerServiceInfo])
    extends AssignerSlicezDataExporter {
  import Assigner.AssignmentGeneratorHandle

  /** The abstraction that manages all subscriber connections and messages. */
  @GuardedBy("sec")
  private[this] val subscriberManager: SubscriberManager =
    new SubscriberManager(
      assignerSecPool,
      getSuggestedClerkRpcTimeoutFn = () => conf.getAssignerSuggestedClerkWatchTimeout,
      suggestedSliceletRpcTimeout = conf.watchServerSuggestedRpcTimeout,
      maxSubscribersPromptedForAssignmentRecovery = conf.maxClientsPromptedForAssignmentRecovery
    )

  /** A map that keeps track of all generators. */
  @GuardedBy("sec")
  private val generatorMap = new mutable.HashMap[Target, AssignmentGeneratorHandle]

  /**
   * The rpc server for the assigner.
   *
   * Initialized in [[start()]] which the factory method ensures is always called.
   */
  @GuardedBy("sec")
  private[this] var server: DatabricksServerWrapper = _

  /**
   * The preferred assigner config for this assigner.
   *
   * Initialized in [[start()]] which the factory method ensures is always called, and updated by
   * watching the preferred assigner driver with [[onPreferredAssignerConfigChange()]]
   */
  @GuardedBy("sec")
  private[this] var preferredAssignerConfig: PreferredAssignerConfig = _

  /**
   * The assigner info for this assigner instance.
   *
   * Initialized in [[start()]] which the factory method ensures is always called.
   */
  @GuardedBy("sec")
  private[this] var assignerInfo: AssignerInfo = _

  /**
   * The Assigner's watch-request rate limiting strategy. Always installed (so it records rate-limit
   * decision metrics) once the server starts; the `enableWatchRequestRateLimiting` flag controls
   * only whether its decisions are enforced. `None` until [[createAndStartDatabricksServer()]]
   * initializes it.
   */
  @GuardedBy("sec")
  private[this] var rateLimitingStrategyOpt: Option[WatchRequestRateLimitingStrategy] = None

  /**
   * The proto logger for Assigner-specific logging events.
   *
   * Initialized in [[start()]] which the factory method ensures is always called.
   */
  @GuardedBy("sec")
  private var assignerProtoLogger: AssignerProtoLogger = _

  /**
   * A shared thread pool for the SECs used by [[assignerProtoLogger]] and the sample fraction
   * poller.
   *
   * Context propagation is disabled because proto logging runs in the background and is not part of
   * any user request path.
   */
  private val protoLoggerSecPool: SequentialExecutionContextPool =
    SequentialExecutionContextPool.create(
      poolName = "assigner-proto-logger-pool",
      numThreads = 2,
      alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME,
      enableContextPropagation = false
    )

  /**
   * Unmarshaller for [[Target]] protos that are received by the assigner. The unmarshaller is
   * responsible for normalizing the target based on the assigner's cluster.
   */
  private[this] val targetUnmarshaller =
    TargetUnmarshaller.createAssignerUnmarshaller(assignerClusterUri)

  /**
   * The latest [[TargetOwnershipResolver]] published by [[targetMigrator]]. Initialized
   * synchronously from the migrator at construction time (see [[TargetMigrator.getLatestResolver]]
   * for why this is safe to call immediately) and updated by watching the migrator.
   */
  @GuardedBy("sec")
  private var latestTargetOwnershipResolver: TargetOwnershipResolver =
    targetMigrator.getLatestResolver

  /** The emitter for sending [[AssignmentGenerator.Event]] to Dicer Tee. */
  private val dicerTeeEventEmitter: DicerTeeEventEmitter =
    if (conf.enableDicerTeeForwarding) {
      DicerTeeEventEmitter.create(
        assignerSecPool.createExecutionContext("tee-event-emitter"),
        URI.create(conf.dicerTeeURI),
        // Since event emitter is a client of DicerTeeBackend, we are using clientSslArgs here.
        // In prod, conf.dicerClientSslArgs should be the same as conf.sslArgs. However, in tests
        // conf.dicerClientSslArgs is overridden as TestSslArguments.clientSslArgs while
        // conf.sslArgs is not, and we can only pass tests with conf.dicerClientSslArgs.
        conf.getDicerClientTlsOptions,
        timeoutMs = DicerTeeEventEmitter.DEFAULT_TIMEOUT_MS,
        numRetryAttempts = DicerTeeEventEmitter.DEFAULT_NUM_RETRY_ATTEMPTS
      )
    } else {
      // If Dicer Tee is disabled, create a NoopEmitter placeholder that does nothing.
      DicerTeeEventEmitter.getNoopEmitter
    }

  /**
   * The emitter for logging [[AssignmentGenerator.Event]]s to Lumberjack for replay by the Dicer
   * Simulator.
   */
  private val dicerSimulatorEventLogEmitter: DicerSimulatorEventEmitter =
    if (conf.enableDicerSimulatorEventLogging) {
      // Dedicated SEC for simulator event logging so it cannot block the main SEC.
      val loggingSec: SequentialExecutionContext =
        protoLoggerSecPool.createExecutionContext("simulator-event-log-emitter")
      DicerSimulatorEventEmitter.create(loggingSec)
    } else {
      // If Dicer Simulator event logging is disabled, use a no-op emitter that does nothing.
      DicerSimulatorEventEmitter.getNoopEmitter
    }

  /**
   * Handles the watch call from a Clerk/Slicelet and returns the relevant response.
   *
   * @note The [[targetOwnershipResolver]] is checked first. If it returns
   *       [[RoutingVerdict.Reroute]], the request is immediately rerouted to the specified
   *       assigner. If the request carries an inbound `redirectTokenOpt` whose
   *       `targetMigrationConfigVersion` is newer than the local config, the resolver forces a
   *       [[RoutingVerdict.Handle]] to avoid ping-ponging the client back to the sender while a
   *       new [[TargetMigrationConfig]] propagates to this Assigner.
   *       Otherwise, routing proceeds as per the preferred assigner's role:
   *        - If the current assigner is a standby, it redirects to the preferred assigner.
   *        - If the current assigner is preferred, it handles the request and responds with a
   *          redirect to be [[Redirect.EMPTY]] if the preferred assigner mode is disabled, or a
   *          redirect to the current assigner if the preferred assigner is enabled.
   */
  def handleWatch(rpcContext: RPCContext, req: ClientRequestP): Future[ClientResponseP] = {
    sec.flatCall {
      val request = ClientRequest.fromProto(targetUnmarshaller, req)

      // Record the reported alternative_target along with the `target` *before* canonicalization.
      // This is emitted for every watch (with empty alternative_target labels when the request
      // carries none), so it detects both when clients for the same `target` report inconsistent
      // alternative targets across their requests and when some clients are not reporting one at
      // all -- both severe issues after canonicalization has been enabled for the target. See
      // `replaceWithAlternativeTarget` for more details on canonicalization and alternative
      // targets.
      TargetMetrics.incrementReportedAlternativeTargets(
        request.target,
        request.alternativeTargetOpt
      )

      // Try to parse the inbound redirect token, transparently treating invalid tokens as absent
      // (`RedirectToken.tryFromBytes` will fire a DEGRADED alert on parse failure).
      val inboundRedirectTokenOpt: Option[RedirectToken] =
        request.redirectTokenOpt.flatMap(RedirectToken.tryFromBytes)

      // Check the target ownership resolver first to determine whether to handle or reroute the
      // target.
      latestTargetOwnershipResolver.getRoutingVerdict(
        request.target,
        inboundRedirectTokenOpt
      ) match {
        case RoutingVerdict.Reroute(peerAssignerUri: URI, redirectToken: RedirectToken) =>
          Future.successful(
            ClientResponse(
              syncState = KnownGeneration(Generation.EMPTY),
              suggestedRpcTimeout = getSuggestedWatchRpcTimeout(request),
              redirect = Redirect(
                addressOpt = Some(peerAssignerUri),
                redirectTokenOpt = Some(redirectToken.toBytes)
              )
            ).toProto
          )
        case RoutingVerdict.Handle(redirectTokenOpt: Option[RedirectToken]) =>
          // We still attach the latest known redirect token so that if we redirect to the preferred
          // assigner, it will see a `targetMigrationConfigVersion` >= the one we received here. In
          // case the preferred assigner has a stale migration config, this avoids ping-ponging the
          // client back to the peer cluster.
          val outboundRedirect: Redirect = preferredAssignerConfig.preferredAssignerUriOpt match {
            case Some(preferredAssignerUri: URI) =>
              Redirect(
                addressOpt = Some(preferredAssignerUri),
                redirectTokenOpt = redirectTokenOpt.map((_: RedirectToken).toBytes)
              )
            case None =>
              Redirect.EMPTY
          }
          preferredAssignerConfig.role match {
            case AssignerRole.Preferred =>
              // If the current assigner is preferred, handle the watch request. Canonicalize the
              // request to its AppTarget identity (when use_alternative_target is enabled) only
              // here, where we handle it locally: a rerouted or standby request is canonicalized by
              // the preferred Assigner that owns the target, so it is left unchanged above.
              // Handling proceeds in `handleWatchAsPreferredAssigner`, which takes only the
              // canonicalized request so the incoming (pre-canonicalization) `request` cannot be
              // used past this point.
              val canonicalizedRequest: ClientRequest = replaceWithAlternativeTarget(request)
              handleWatchAsPreferredAssigner(rpcContext, canonicalizedRequest, outboundRedirect)
            case AssignerRole.Standby =>
              // If the current assigner is a standby, redirect the watch request to the preferred
              // assigner.
              Future.successful(
                ClientResponse(
                  syncState = KnownGeneration(Generation.EMPTY),
                  suggestedRpcTimeout = getSuggestedWatchRpcTimeout(request),
                  redirect = outboundRedirect
                ).toProto
              )
          }
      }
    }
  }

  /** Handles the heartbeat call from the assigners who identify themselves as standbys. */
  def handleHeartbeat(req: HeartbeatRequestP): Future[HeartbeatResponseP] = sec.flatCall {
    val request = HeartbeatRequest.fromProto(req)
    preferredAssignerDriver
      .handleHeartbeatRequest(request)
      .map { response: HeartbeatResponse =>
        response.toProto
      }(sec)
  }

  /** Handles a gossip round initiated by a peer Assigner. */
  def handleGossip(reqProto: GossipRequestP): Future[GossipResponseP] = sec.flatCall {
    // Convert from the proto to the parsed form. We expect at most a singular
    // [[TargetMigrationConfig]], and otherwise throw an error, resulting in a failed RPC.
    val request = GossipRequest.fromProto(reqProto)
    logger.debug(
      s"Received gossip request from ${request.self} with config " +
      s"${request.targetMigrationConfigOpt}.",
      every = 10.seconds
    )

    // Exchange the gossiped target migration config with its owning component, collecting the
    // config (if any) to gossip back.
    val responseConfigOptFuture: Future[Option[TargetMigrationConfig]] =
      targetMigrator.handleGossipRequest(request.targetMigrationConfigOpt)
    responseConfigOptFuture.map { responseConfigOpt: Option[TargetMigrationConfig] =>
      GossipResponse(getAssignerInfoSync, responseConfigOpt).toProto
    }(sec)
  }

  override def getSlicezData: Future[AssignerSlicezData] = sec.flatCall {
    // Collect the data for all generators and all subscribers.
    val targetSlicezDataSeq: Seq[Future[AssignerTargetSlicezData]] =
      (for (entry <- generatorMap) yield {
        val (target, generatorHandle): (Target, AssignmentGeneratorHandle) = entry
        getTargetSlicezData(target, generatorHandle.getGeneratorDriver)
      }).toSeq

    val aggregatedTargetSlicezData: Future[Seq[AssignerTargetSlicezData]] =
      Future.sequence(targetSlicezDataSeq)(implicitly, sec)
    // Surface the consistent-hashing snapshot from the active driver so the debug page reflects
    // shadow-mode operation. The etcd-backed and disabled drivers, which do not run a
    // consistent-hashing election, report `None`. The migration mode is read from conf here (rather
    // than from the driver) since it is fixed for the process's lifetime.
    val consistentHashingStateFuture: Future[Option[ConsistentHashingState]] =
      preferredAssignerDriver.consistentHashingStateView
    aggregatedTargetSlicezData
      .zip(consistentHashingStateFuture)
      .map { tuple: (Seq[AssignerTargetSlicezData], Option[ConsistentHashingState]) =>
        val (targetSlicezData, consistentHashingStateOpt): (
            Seq[AssignerTargetSlicezData],
            Option[ConsistentHashingState]) = tuple
        AssignerSlicezData(
          this.getAssignerInfoSync,
          PreferredAssignerSlicezData(
            preferredAssignerConfig.knownPreferredAssigner,
            consistentHashingStateOpt,
            conf.preferredAssignerMigrationMode
          ),
          targetSlicezData
        )
      }(sec)
  }

  /** Asynchronously returns the [[AssignerInfo]] identifying this Assigner. */
  def getAssignerInfo: Future[AssignerInfo] = sec.call {
    getAssignerInfoSync
  }

  /**
   * Synchronously returns the [[AssignerInfo]] identifying this Assigner to skip an executor hop in
   * cases we know we're on [[sec]].
   *
   * PRECONDITION: Must be called on [[sec]].
   */
  private[this] def getAssignerInfoSync: AssignerInfo = {
    sec.assertCurrentContext()
    assignerInfo
  }

  /**
   * The local Kubernetes membership checker's connection-health cell, consumed by the Assigner's
   * readiness probe.
   */
  private[assigner] def probePollHealthWatchCell: WatchValueCell.Consumer[Boolean] =
    localClusterMembershipChecker.connectionHealthCell

  /** Starts the RPC server, dynamic config watch, etc. */
  protected def start(): Unit = sec.run {
    server = createAndStartDatabricksServer()
    startWatchingConfig()
    // Log information about the server we just started.
    logger.info(
      s"Started assigner on port ${server.activePort()}\n" +
      s"Configs: ${configProvider.getLatestTargetConfigMap}"
    )

    // Start the periodic inactive generator cleanup scan.
    sec.scheduleRepeating(
      name = "generator-inactivity-check",
      interval = conf.generatorInactivityScanInterval,
      () => cleanupInactiveGenerators()
    )

    // Add a shutdown hook to terminate the preferred Assigner driver. Note that we do not use a
    // `DrainingComponent` here because the execution of DrainingComponents occurs _after_ the
    // grace period within the server shutdown sequence, after which the server framework
    // automatically rejects incoming requests. When an Assigner is terminating, we want the
    // abdication to happen as early as possible, so there is a better chance for a standby
    // Assigner to take over quickly. This allows the current Assigner to redirect requests to the
    // new preferred Assigner, ensuring a smooth transition. Therefore, we use a shutdown hook to
    // terminate the PA driver.
    //
    // Note that we use the `addShutdownHook` overload which includes `timeoutMillisOpt`, as the
    // overload which doesn't include it silently drops the hook priority.
    ShutdownHookManager
      .addShutdownHook(Assigner.TERMINATION_SHUTDOWN_HOOK_PRIORITY, timeoutMillisOpt = None) {
        onAssignerTerminating()
      }
  }

  /** Terminates the preferred assigner driver on SIGTERM. */
  private[this] def onAssignerTerminating(): Unit = {
    // Currently we don't have an integration test using a real Kubernetes environment, but we
    // have test coverage in EtcdPreferredAssignerIntegrationSuite that verifies the preferred
    // assigner abdicates when it receives a termination notice.
    logger.info("Shutdown hook triggered.")
    preferredAssignerDriver.sendTerminationNotice()
    // Stop the membership checker so its polling loop and K8s client are released on shutdown. This
    // method can run more than once (the shutdown hook and test teardown both call it), which is
    // safe because stopAsync() is idempotent.
    localClusterMembershipChecker.stopAsync()
  }

  /**
   * Creates and starts a [[DatabricksServerWrapper]] exposing this `assigner`.
   *
   * PRECONDITION: Must be called on [[sec]].
   */
  private[this] def createAndStartDatabricksServer(): DatabricksServerWrapper = {
    sec.assertCurrentContext()
    val serviceBuilder: GenericRpcServiceBuilder = GenericRpcServiceBuilder.create()
    // Register the assignment, preferred assigner, and gossip services with `serviceBuilder`. Using
    // helpers here so that the registration process can use vanilla gRPC.
    WatchServerHelper.registerAssignmentService(
      serviceBuilder,
      (rpcContext: RPCContext, req: ClientRequestP) => this.handleWatch(rpcContext, req)
    )
    PreferredAssignerServerHelper.registerPreferredAssignerService(
      serviceBuilder,
      (req: HeartbeatRequestP) => this.handleHeartbeat(req)
    )
    GossipRpcHelper.registerGossipService(
      serviceBuilder,
      (req: GossipRequestP) => this.handleGossip(req)
    )

    // The rate limiting strategy is always installed so that it records rate-limit decision
    // metrics. The `enableWatchRequestRateLimiting` flag controls only whether those decisions are
    // enforced: when disabled, the strategy runs in shadow mode and admits requests it would
    // otherwise reject.
    //
    // We allocate a dedicated SEC for the rate limiting strategy to asynchronously process config
    // updates without blocking the Assigner's SEC.
    val rateLimitingSec: SequentialExecutionContext =
      assignerSecPool.createExecutionContext("watch-rate-limiting")
    rateLimitingStrategyOpt = Some(
      new WatchRequestRateLimitingStrategy(
        sec = rateLimitingSec,
        initialConfigMap = configProvider.getLatestTargetConfigMap,
        allowDefaultConfigForExperimentalTargets =
          conf.allowDefaultTargetConfigForExperimentalTargets,
        enforceRateLimit = conf.enableWatchRequestRateLimiting,
        clock = rateLimitingSec.getClock
      )
    )

    val server: DatabricksServerWrapper = WatchServerHelper.createWatchServer(
      conf,
      conf.dicerAssignerRpcPort,
      conf.loopbackRpcPortOpt,
      serviceBuilder,
      rateLimitingStrategyOpt = rateLimitingStrategyOpt
    )
    server.start()
    assignerInfo =
      AssignerInfo(uuid, AssignerUri(host = hostName, port = server.activePort()).toUri)
    assignerProtoLogger = createAssignerProtoLogger()
    // Start the membership checker before the preferred-assigner driver, which (in
    // consistent-hashing mode) needs the checker running.
    localClusterMembershipChecker.start(assignerProtoLogger)
    preferredAssignerDriver.start(assignerInfo, assignerProtoLogger)

    // Initially, we don't know who the preferred is, but `preferredAssignerDriver` will tell us.
    onPreferredAssignerConfigChange(
      PreferredAssignerConfig.create(
        PreferredAssignerValue.NoAssigner(Generation.EMPTY),
        assignerInfo
      )
    )

    // Setup the ZPages and DPages. ZPages provide a legacy HTML fallback when
    // DBInspect is unavailable to serve the React DView.
    AssignerSlicez.setup(this, assignerInfo.toString)
    AssignerDPage.setup(this, dPageNamespace)
    logger.info(s"Registered Assigner DPages for: $assignerInfo")

    // Start watching the preferred assigner config from the preferred assigner driver.
    preferredAssignerDriver.watch(new ValueStreamCallback[PreferredAssignerConfig](sec) {
      override def onSuccess(newConfig: PreferredAssignerConfig): Unit = {
        sec.assertCurrentContext()
        onPreferredAssignerConfigChange(newConfig)
      }
    })
    server
  }

  /**
   * Creates the [[AssignerProtoLogger]] backed by a [[WatchValueCellPollAdapter]] that periodically
   * polls the [[DicerAssignerConf.protoLoggerGenerationSampleFractionFlag]] SAFE flag for the
   * sample fraction.
   *
   * Intended to be called exactly once, from [[createAndStartDatabricksServer()]] during Assigner
   * startup.
   *
   * PRECONDITION: [[assignerInfo]] must be initialized.
   * PRECONDITION: Must be called on [[sec]].
   */
  private[this] def createAssignerProtoLogger(): AssignerProtoLogger = {
    sec.assertCurrentContext()

    // Dedicated SEC for proto logging operations to avoid blocking the main SEC.
    val loggingSec: SequentialExecutionContext =
      protoLoggerSecPool.createExecutionContext("assigner-proto-logger")

    // Dedicated SEC for polling the sample-fraction SAFE flag, separate from the logging SEC so a
    // slow SAFE call cannot block log submission.
    val pollSec: SequentialExecutionContext =
      protoLoggerSecPool.createExecutionContext("assigner-proto-logger-poller")
    // We initialize the sample fraction to 0.0, so no logs will be emitted until the first poll
    // completes and the SAFE flag value is observed.
    val sampleFractionPollAdapter: WatchValueCellPollAdapter[Double, Double] =
      new WatchValueCellPollAdapter[Double, Double](
        initialValueOpt = Some(0.0),
        poller = () => conf.protoLoggerGenerationSampleFractionFlag.getCurrentValue(),
        update = (_, sampleFraction: Double) => sampleFraction,
        pollInterval = conf.protoLoggerGenerationSampleFractionPollInterval,
        sec = pollSec
      )
    sampleFractionPollAdapter.start()

    AssignerProtoLogger.create(assignerInfo, sampleFractionPollAdapter, loggingSec)
  }

  /**
   * Shuts down all the generators if the current assigner is a standby.
   *
   * PRECONDITION: Must be called on [[sec]].
   */
  private[this] def onPreferredAssignerConfigChange(newConfig: PreferredAssignerConfig): Unit = {
    sec.assertCurrentContext()
    newConfig.role match {
      case AssignerRole.Preferred => // Do nothing.
      case AssignerRole.Standby =>
        // Shut down all generators, since a standby Assigner does not generate assignments. Collect
        // the entries first to avoid mutating `generatorMap` while iterating it.
        for (entry <- generatorMap.toSeq) {
          val (target, generatorHandle): (Target, AssignmentGeneratorHandle) = entry
          shutdownGenerator(
            target,
            generatorHandle,
            GeneratorShutdownReason.PREFERRED_ASSIGNER_CHANGE
          )
        }
    }
    logger.info(s"[$assignerInfo] updated config from $preferredAssignerConfig to $newConfig")
    preferredAssignerConfig = newConfig
  }

  /**
   * Adopts `newResolver` as the latest [[TargetOwnershipResolver]] and shuts down the generators of
   * any targets it would reroute to another Assigner. Those targets' watch requests will no longer
   * be handled by this Assigner, so their generators must stop generating assignments — otherwise
   * this Assigner keeps generating for a target now owned by the peer cluster. This is important
   * because otherwise the generated assignments between the owning assigners would diverge, and if
   * the ownership is moved back, this Assigner would reuse a stale assignment and create undue
   * churn potentially causing an outage. Beyond this, it would also muddy metrics making them more
   * difficult to reason about.
   *
   * PRECONDITION: Must be called on [[sec]].
   */
  private[this] def onTargetOwnershipResolverChange(newResolver: TargetOwnershipResolver): Unit = {
    sec.assertCurrentContext()
    val previousConfigVersion: Int = latestTargetOwnershipResolver.configVersion
    latestTargetOwnershipResolver = newResolver
    // Only sweep when the config version advances. Target ownership is a function of the config, so
    // a resolver carrying the same version reroutes exactly the targets we already shut down; this
    // skips resolver updates that only change the peer Assigner endpoint or as the result of a
    // kubernetes resource version bump.
    if (newResolver.configVersion > previousConfigVersion) {
      // Collect the entries first to avoid mutating `generatorMap` while iterating it. We use
      // `wouldReroute` rather than `getRoutingVerdict` because this is off the request path: it
      // must not record request-path metrics and must not fail.
      val reroutedEntries: Vector[(Target, AssignmentGeneratorHandle)] =
        generatorMap.filterKeys(newResolver.wouldReroute).toVector
      val firstFewReroutedTargets: Vector[Target] = reroutedEntries.take(5).map {
        entry: (Target, AssignmentGeneratorHandle) =>
          val (target, _): (Target, AssignmentGeneratorHandle) = entry
          target
      }
      logger.info(
        s"Config version advanced from $previousConfigVersion to ${newResolver.configVersion}; " +
        s"shutting down ${reroutedEntries.size} rerouted generator(s). First few: " +
        s"${firstFewReroutedTargets.mkString(", ")}"
      )
      for (entry <- reroutedEntries) {
        val (target, generatorHandle): (Target, AssignmentGeneratorHandle) = entry
        shutdownGenerator(target, generatorHandle, GeneratorShutdownReason.TARGET_MIGRATION_REROUTE)
      }
    }
  }

  /**
   * Shuts down `target`'s generator, removes it from [[generatorMap]], and records `reason`.
   *
   * PRECONDITION: `target` is present in [[generatorMap]] with `generatorHandle`.
   * PRECONDITION: Must be called on [[sec]].
   */
  private[this] def shutdownGenerator(
      target: Target,
      generatorHandle: AssignmentGeneratorHandle,
      reason: GeneratorShutdownReason): Unit = {
    sec.assertCurrentContext()
    iassert(generatorMap.get(target).contains(generatorHandle), "target must be in generatorMap")
    TargetMetrics.incrementGeneratorsRemoved(target, reason)
    TargetMetrics.updateTargetsWithActiveGenerators(target, 0)
    generatorHandle.getGeneratorDriver.shutdown()
    generatorMap.remove(target)
  }

  /**
   * Starts watching config value changes from SAFE for per-target config, and updates to target
   * migrator resulting in an updated [[TargetOwnershipResolver]].
   */
  private[this] def startWatchingConfig(): Unit = {
    val configUpdatedCallback: ValueStreamCallback[InternalTargetConfigMap] = {
      new ValueStreamCallback[InternalTargetConfigMap](sec) {
        override def onSuccess(configMap: InternalTargetConfigMap): Unit = {
          sec.assertCurrentContext()
          for (entry <- configMap.iterator) {
            val (targetName, config): (TargetName, InternalTargetConfig) = entry
            updateTargetConfig(targetName, config)
            // Update the metrics with the new config value.
            InternalTargetConfigMetrics.exportAssignerConfigStats(targetName, config)
          }
          // Forward the update to the rate limiting strategy if enabled. The strategy schedules
          // the update on its own SEC, so this does not block the Assigner's SEC.
          for (strategy: WatchRequestRateLimitingStrategy <- rateLimitingStrategyOpt) {
            strategy.updateTargetConfigMapAsync(configMap)
          }
        }
      }
    }
    // Start watching config changes.
    configProvider.watch(configUpdatedCallback)

    // Subscribe to TargetOwnershipResolver updates from the migrator. On each update we shut down
    // the generators of any targets the new resolver reroutes to another Assigner.
    targetMigrator.watch(
      new ValueStreamCallback[TargetOwnershipResolver](sec) {
        override protected def onSuccess(resolver: TargetOwnershipResolver): Unit = {
          sec.assertCurrentContext()
          onTargetOwnershipResolverChange(resolver)
        }
      }
    )
  }

  /**
   * Handles a watch `request` that this Assigner owns as the preferred Assigner, responding with an
   * assignment for `request.target` (looked up and validated here) and attaching
   * `outboundRedirect`. `request` is expected to already be canonicalized to its AppTarget identity
   * where applicable, so this operates only on the canonicalized request rather than the Assigner's
   * incoming request.
   *
   * PRECONDITION: The current assigner must be preferred.
   * PRECONDITION: Must be called on [[sec]].
   */
  private[this] def handleWatchAsPreferredAssigner(
      rpcContext: RPCContext,
      request: ClientRequest,
      outboundRedirect: Redirect): Future[ClientResponseP] = {
    sec.assertCurrentContext()
    iassert(
      preferredAssignerConfig.role == AssignerRole.Preferred,
      "current assigner must be preferred"
    )
    validateTarget(request.target, rpcContext, request.getClientType)
    lookupGenerator(request.target) match {
      case Some(generatorHandle: AssignmentGeneratorHandle) =>
        val generator: AssignmentGeneratorDriver = generatorHandle.getGeneratorDriver
        generator.onWatchRequest(request)

        // Track the last activity time for the target.
        val currentTime: TickerTime = sec.getClock.tickerTime()
        generatorHandle.updateLastWatchTime(currentTime)

        subscriberManager.handleWatchRequest(
          rpcContext,
          request,
          generator.getGeneratorCell,
          outboundRedirect,
          currentTime
        )
      case None =>
        logger.warn(
          s"Received watch request for unknown target: ${request.target}",
          every = 10.seconds
        )
        // To avoid circular dependencies, the target watch errors live in
        // `AuthorizerMetrics`.
        AuthorizerMetrics.incrementNumTargetWatchErrors(
          request.target,
          WatchError.NO_CONFIG
        )
        Future.failed(
          DatabricksServiceException(
            ErrorCode.NOT_FOUND,
            s"Missing target config: ${request.target}"
          )
        )
    }
  }

  /**
   * Returns whether `use_alternative_target` is enabled in the advanced config for `target`. The
   * config is keyed by target name, which is invariant across KubernetesTarget -> AppTarget
   * canonicalization, so this may be called on the request's incoming target before any
   * canonicalization. Returns false when no config exists for the target.
   *
   * PRECONDITION: Must be called on [[sec]].
   */
  private[this] def useAlternativeTargetEnabled(target: Target): Boolean = {
    sec.assertCurrentContext()
    val targetName: TargetName = TargetName.forTarget(target)
    configProvider.getLatestTargetConfigMap
      .get(targetName)
      .exists((_: InternalTargetConfig).useAlternativeTarget)
  }

  /**
   * Replaces `request`'s target with the AppTarget in its `alternative_target` when
   * `use_alternative_target` is enabled in the target's advanced config. This translates the
   * request to its AppTarget identity, so downstream handling (generator lookup, validation) runs
   * on the AppTarget rather than the incoming KubernetesTarget. Called only for a request this
   * Assigner handles locally as the preferred Assigner; a rerouted or standby request is
   * canonicalized by the preferred Assigner that owns the target. See the design doc:
   * <internal link>
   *
   * When enabled, the request is expected to carry an `alternativeTarget` AppTarget
   * (`use_alternative_target` is only turned on after metrics confirm every client of the target
   * populates it). If present, returns `request` with its `target` replaced by that AppTarget. If
   * absent, fires a [[Severity.CRITICAL]] alert and rejects the watch with
   * [[Status.FAILED_PRECONDITION]]: serving the request under its incoming KubernetesTarget would
   * split the target's identity from its canonicalized peers, so it is safer to fail the regressed
   * client than to assign it under a divergent identity. See the decision doc:
   * <internal link>
   *
   * The `alternative_target` must share the incoming target's name: ownership and routing are
   * decided on the incoming target name, while everything after the replacement (validation,
   * generator lookup, subscriber, metrics) runs on the `alternative_target`. A name mismatch would
   * route the request as one target and serve it as another, so it fires a [[Severity.CRITICAL]]
   * alert and rejects the watch with [[Status.INVALID_ARGUMENT]].
   *
   * When disabled, returns `request` unchanged.
   *
   * PRECONDITION: Must be called on [[sec]].
   */
  @throws[StatusRuntimeException](
    "if use_alternative_target is enabled but the request omits alternative_target or names a " +
    "different target"
  )
  private[this] def replaceWithAlternativeTarget(request: ClientRequest): ClientRequest = {
    sec.assertCurrentContext()
    if (!useAlternativeTargetEnabled(request.target)) {
      request
    } else {
      request.alternativeTargetOpt match {
        case Some(alternativeTarget: AppTarget) =>
          if (alternativeTarget.name != request.target.name) {
            val mismatchDescription: String =
              s"Watch request for ${request.target} set an alternative_target that names a " +
              s"different target (${alternativeTarget.name})"
            logger.alert(
              Severity.CRITICAL,
              CachingErrorCode.ASSIGNER_MISMATCHED_ALTERNATIVE_TARGET_NAME,
              s"$mismatchDescription; the client populated alternative_target incorrectly and " +
              "will not be assigned correctly.",
              every = 30.seconds
            )
            throw Status.INVALID_ARGUMENT
              .withDescription(
                s"$mismatchDescription; alternative_target must name the same target because " +
                "use_alternative_target is enabled for this target."
              )
              .asRuntimeException()
          }
          request.copy(target = alternativeTarget)
        case None =>
          logger.alert(
            Severity.CRITICAL,
            CachingErrorCode.ASSIGNER_MISSING_EXPECTED_ALTERNATIVE_TARGET,
            s"Watch request for ${request.target} omitted alternative_target while " +
            s"use_alternative_target is enabled for the target; the client cannot be " +
            s"canonicalized to the target's AppTarget identity and will not be assigned correctly.",
            every = 30.seconds
          )
          throw Status.FAILED_PRECONDITION
            .withDescription(
              s"Watch request for ${request.target} must set alternative_target (an AppTarget) " +
              "because use_alternative_target is enabled for this target."
            )
            .asRuntimeException()
      }
    }
  }

  /**
   * Returns the Assignment generator corresponding to the `target`, creating one if necessary based
   * on the target config. If no config exists and `allowDefaultTargetConfigForExperimentalTargets`
   * is enabled, then will create a generator using
   * [[InternalTargetConfig.DEFAULT_FOR_EXPERIMENTAL_TARGETS]]. If none of the above conditions are
   * met, returns `None`.
   *
   * Note: Stale generators are removed from the `generatorMap` via the inactivity callback, so if
   * a generator is present in the map, it is considered active and reusable.
   *
   * PRECONDITION: Must be called on [[sec]].
   */
  private[this] def lookupGenerator(target: Target): Option[AssignmentGeneratorHandle] = {
    sec.assertCurrentContext()
    // If a generator already exists in the generator map, it is returned directly. Otherwise, if a
    // config exists (or if a config doesn't exist but
    // `allowDefaultTargetConfigForExperimentalTargets` is enabled), a new generator is created,
    // cached, and returned.
    val existingGeneratorHandleOpt: Option[AssignmentGeneratorHandle] = generatorMap.get(target)
    existingGeneratorHandleOpt match {
      case Some(_: AssignmentGeneratorHandle) => existingGeneratorHandleOpt
      case None =>
        val targetName: TargetName = TargetName.forTarget(target)
        val targetConfigOpt: Option[InternalTargetConfig] =
          configProvider.getLatestTargetConfigMap.get(targetName).orElse {
            if (conf.allowDefaultTargetConfigForExperimentalTargets) {
              logger.info(
                s"No config found for $target, using default config for experimental targets " +
                "(allowDefaultTargetConfigForExperimentalTargets is enabled)"
              )
              Some(InternalTargetConfig.DEFAULT_FOR_EXPERIMENTAL_TARGETS)
            } else {
              None
            }
          }
        targetConfigOpt.map { targetConfig: InternalTargetConfig =>
          logger.info(s"New generator being created for $target: $targetConfig")

          // Update the active generator count only on new generator creation.
          TargetMetrics.updateTargetsWithActiveGenerators(target, 1)
          val generator: AssignmentGeneratorDriver = createGenerator(target, targetConfig)
          val generatorHandle: AssignmentGeneratorHandle =
            new AssignmentGeneratorHandle(generator, sec.getClock.tickerTime())
          generatorMap.put(target, generatorHandle)
          generatorHandle
        }
    }
  }

  /**
   * Creates a new assignment generator driver for the given `target`.
   *
   * @param target The target for which to create an assignment generator.
   * @param targetConfig The configuration for the target.
   * @return A new [[AssignmentGeneratorDriver]] instance.
   */
  private def createGenerator(
      target: Target,
      targetConfig: InternalTargetConfig): AssignmentGeneratorDriver = {
    val targetName = TargetName.forTarget(target)
    InternalTargetConfigMetrics.exportAssignerConfigStats(targetName, targetConfig)
    AssignmentGeneratorDriver.create(
      assignerSecPool.createExecutionContext(s"generation-$target"),
      conf: LoadWatcherConf,
      target,
      targetConfig,
      storeFactory.getStore(),
      kubernetesTargetWatcherFactory,
      healthWatcher = healthWatcherFactory.create(
        target,
        HealthWatcher.StaticConfig.fromConf(conf: HealthConf),
        targetConfig.healthWatcherConfig
      ),
      // TODO(<internal bug>): While we do not expose the specific `crashRecordRetention`
      // and `heuristicThreshold` configurations to the user currently, future
      // configuration options for the key of death detector should be incorporated here,
      // such as whether or not key of death protection is enabled.
      keyOfDeathDetector = new KeyOfDeathDetector(
        target,
        KeyOfDeathDetector.Config.defaultConfig()
      ),
      assignerClusterUri,
      minAssignmentGenerationInterval,
      dicerTeeEventEmitter,
      dicerSimulatorEventLogEmitter,
      assignerProtoLogger,
      assignerServiceInfoOpt
    )
  }

  /**
   * Update the config for the `target` in `updatedConfig` if the config has changed, such
   * that subsequent assignments will be generated using the updated configuration.
   *
   * PRECONDITION: Must be called on [[sec]].
   */
  private[this] def updateTargetConfig(
      targetName: TargetName,
      updatedConfig: InternalTargetConfig): Unit = {
    sec.assertCurrentContext()
    // Multiple targets may match the same target name, so we need to update the config for all
    // targets that match the target name.
    // Collect entries first to avoid ConcurrentModificationException during iteration.
    val matchingEntries: Seq[(Target, AssignmentGeneratorHandle)] =
      generatorMap.filterKeys(targetName.matches).toSeq

    for (entry <- matchingEntries) {
      val (target, generatorHandle): (Target, AssignmentGeneratorHandle) = entry
      val generator: AssignmentGeneratorDriver = generatorHandle.getGeneratorDriver
      // When a generator with a different configuration for target is running, shut it down and
      // remove it from the generator map to ensure the creation of an updated generator the next
      // time an event arrives for the target.
      if (generator.targetConfig != updatedConfig) {
        logger.info(
          s"Config for $target is updated from ${generator.targetConfig} to $updatedConfig"
        )
        generator.shutdown()
        generatorMap.remove(target)

        // Track generator removal due to config change
        TargetMetrics.incrementGeneratorsRemoved(
          target,
          GeneratorShutdownReason.TARGET_CONFIG_CHANGE
        )
        TargetMetrics.updateTargetsWithActiveGenerators(target, 0)
        // Note there is no need to explicitly recreate the generator here because a new generator
        // will be created the next time an event arrives for the target. (Kubernetes termination
        // signals are also delivered via watch requests from the Slicelet so it is not necessary
        // for the driver to immediately watch the Kubernetes signals.)
      }
    }
  }

  /**
   * Asynchronously collects debugging and monitoring slicez data for a specific target.
   *
   * This method aggregates various information related to the target, including:
   * - Assignment generation.
   * - Subscriber information.
   * - Target config details.
   *
   * PRECONDITION: Must be called on [[sec]].
   */
  private[this] def getTargetSlicezData(
      target: Target,
      generator: AssignmentGeneratorDriver): Future[AssignerTargetSlicezData] = {
    sec.assertCurrentContext()
    val assignmentOpt: Option[Assignment] =
      generator.getGeneratorCell.getLatestValueOpt
    // Get the generator target slicez data.
    val generatorTargetSlicezData: Future[GeneratorTargetSlicezData] =
      generator.getGeneratorTargetSlicezData
    // Get the subscriber data for this particular target.
    val subscriberSlicezData
        : Future[(Seq[SliceletSubscriberSlicezData], Seq[ClerkSubscriberSlicezData])] =
      subscriberManager.getSlicezData(target)

    // Check in which way the target is configured:
    // - If the target is not in the latest config map, it is considered default configured for
    //   experimental targets. Dynamic config may add targets to this map; removals take effect
    //   only after restart, or when dynamic config is disabled.
    // - Else if dynamic config is enabled, it is considered dynamically configured.
    // - Otherwise, it is considered statically configured.
    val targetConfig: InternalTargetConfig = generator.targetConfig
    val targetConfigSlicezData: AssignerTargetSlicezData.TargetConfigData =
      if (!configProvider.getLatestTargetConfigMap.targetNames.contains(
          TargetName.forTarget(target)
        )) {
        AssignerTargetSlicezData.TargetConfigData(
          targetConfig,
          AssignerTargetSlicezData.TargetConfigMethod.DefaultForExperimentalTargets
        )
      } else if (configProvider.isDynamicConfigEnabled) {
        AssignerTargetSlicezData.TargetConfigData(
          targetConfig,
          AssignerTargetSlicezData.TargetConfigMethod.Dynamic
        )
      } else {
        AssignerTargetSlicezData.TargetConfigData(
          targetConfig,
          AssignerTargetSlicezData.TargetConfigMethod.Static
        )
      }

    // Combine both futures into a single future containing AssignerTargetSlicezData
    generatorTargetSlicezData.flatMap { generatorTargetSlicezData: GeneratorTargetSlicezData =>
      subscriberSlicezData.map {
        subscriberSlicezData: (Seq[SliceletSubscriberSlicezData], Seq[ClerkSubscriberSlicezData]) =>
          val (sliceletData, clerkData): (
              Seq[SliceletSubscriberSlicezData],
              Seq[ClerkSubscriberSlicezData]) = subscriberSlicezData

          AssignerTargetSlicezData(
            target,
            sliceletData,
            clerkData,
            assignmentOpt,
            generatorTargetSlicezData,
            targetConfigSlicezData
          )
      }(sec)
    }(sec)
  }

  /**
   * Validates that the given `target` is allowed to be specified by a watch request with the
   * given `rpcContext` and `clientType`.
   *
   * The validation policy is implemented by [[Authorizer]]. This method only applies the feature
   * gate, and selects the configured or default authorizer.
   */
  @throws[IllegalArgumentException](
    "if the target is invalid or not authorized in the given context"
  )
  private[this] def validateTarget(
      target: Target,
      rpcContext: RPCContext,
      clientType: ClientType): Unit = {
    if (!conf.enableTargetValidationViaAppIdentifierHeaders) {
      return
    }

    val authorizerOpt: Option[Authorizer] =
      configProvider.getLatestTargetConfigMap
        .get(TargetName.forTarget(target))
        .map((_: InternalTargetConfig).authorizer)

    // If we don't have a target config, don't do any validation. In practice the request will fail
    // later due to missing target config, and this behavior preserves the better error message.
    for (authorizer: Authorizer <- authorizerOpt) {
      try {
        authorizer.checkAuthorized(
          target,
          rpcContext,
          clientType,
          conf.trustedWatchAnyTargetServices
        )
      } catch {
        case e: UnauthorizedException =>
          throw new IllegalArgumentException(e.getMessage, e)
      }
    }
  }

  /** Returns the suggested watch RPC timeout. */
  private[this] def getSuggestedWatchRpcTimeout(clientRequest: ClientRequest): FiniteDuration = {
    clientRequest.getClientType match {
      case ClientType.Clerk => conf.getAssignerSuggestedClerkWatchTimeout
      case ClientType.Slicelet => conf.watchServerSuggestedRpcTimeout
    }
  }

  /**
   * Checks all the generators for inactivity and cleans up any that have not received any watch
   * requests for longer than `conf.generatorInactivityDuration`.
   *
   * PRECONDITION: Must be called on [[sec]].
   */
  private[this] def cleanupInactiveGenerators(): Unit = {
    sec.assertCurrentContext()
    val currentTime: TickerTime = sec.getClock.tickerTime()

    // Collect inactive targets first to avoid ConcurrentModificationException during iteration.
    val inactiveEntries: Seq[(Target, AssignmentGeneratorHandle)] =
      generatorMap.filter { entry =>
        val (target, generatorHandle): (Target, AssignmentGeneratorHandle) = entry
        val inactivityDuration: FiniteDuration = currentTime - generatorHandle.getLastWatchTime
        inactivityDuration >= conf.generatorInactivityDeadline
      }.toSeq

    // Now safely remove and shutdown the inactive generators.
    for (entry <- inactiveEntries) {
      val (target, generatorHandle): (Target, AssignmentGeneratorHandle) = entry
      val generator: AssignmentGeneratorDriver = generatorHandle.getGeneratorDriver
      val inactivityDuration: FiniteDuration = currentTime - generatorHandle.getLastWatchTime
      logger.info(
        s"Shutting down inactive generator for target $target after " +
        s"$inactivityDuration of inactivity."
      )
      generator.shutdown()
      generatorMap.remove(target)

      TargetMetrics.incrementGeneratorsRemoved(
        target,
        GeneratorShutdownReason.GENERATOR_INACTIVITY
      )
      TargetMetrics.updateTargetsWithActiveGenerators(target, 0)
    }

    // Clean up subscriber handlers that have not received watch requests within the inactivity
    // threshold. These handlers' subscribers have drained and can be safely removed.
    //
    // Ordering invariant: generators are shut down first (above), but this is safe because
    // in-flight watch RPCs are Promise-based and will complete either via the cell's watch
    // callback or the scheduled timeout. cancel() on the subscriber handler only stops metrics
    // export and background tasks — it does not cancel in-flight RPCs.
    // The subscriber inactivity threshold reuses the generator inactivity deadline because
    // subscriber handlers are logically tied to generators: once a generator is eligible for
    // cleanup due to inactivity, its corresponding subscriber handler should be too.
    subscriberManager.removeInactiveHandlers(
      currentTime,
      inactivityThreshold = conf.generatorInactivityDeadline
    )
  }

  object forTest {

    /**
     * Returns the generator for the given target from the map, without creating a new one if it
     * doesn't exist. This is useful for testing.
     */
    def getGeneratorFromMap(target: Target): Future[Option[AssignmentGeneratorDriver]] = sec.call {
      generatorMap.get(target).map((_: AssignmentGeneratorHandle).getGeneratorDriver)
    }

    /**
     * Returns the generator for the given target from the map, if it exists. Otherwise, creates a
     * new generator and returns it, after storing it in `generatorMap`. This is useful for testing.
     *
     * Note: This method has side effects (it may modify `generatorMap`), so it should only be used
     * in test cases that require Assigner introspection without Slicelet connection (e.g., verify
     * that a standby Assigner can read assignments from store). Prefer using
     * [[getGeneratorFromMap]] in almost all cases.
     */
    /** See [[Assigner.lookupGenerator()]]. */
    def lookupOrCreateGenerator(target: Target): Future[Option[AssignmentGeneratorDriver]] =
      sec.call {
        Assigner.this.lookupGenerator(target).map((_: AssignmentGeneratorHandle).getGeneratorDriver)
      }

    /** Stops the assigner watch server asynchronously. */
    def stopAsync(): Future[Unit] = sec.flatCall {
      onAssignerTerminating()
      server.stopAsync().toScala.map(_ => ())(sec)
    }

    def getTargetUnmarshaller: TargetUnmarshaller = targetUnmarshaller

    /** Returns this Assigner's current view of the preferred assigner. */
    def getPreferredAssignerConfig: Future[PreferredAssignerConfig] = sec.call {
      preferredAssignerConfig
    }
  }
}

/** Companion object for [[Assigner]]. */
object Assigner {

  /**
   * Provides a [[Store]]. Each call returns a new instance or a shared one depending on
   * configuration.
   */
  trait StoreFactory {

    def getStore(): Store
  }

  private val logger = PrefixLogger.create(this.getClass, "")

  /**
   * A wrapper class that contains both the generator driver and the last time the generator
   * received a watch request.
   *
   * @param generatorDriver The assignment generator driver for a target.
   * @param lastWatchTime The last time the generator received a watch request, used for tracking
   *                      generator activity for inactivity cleanup purposes.
   */
  private[assigner] class AssignmentGeneratorHandle(
      generatorDriver: AssignmentGeneratorDriver,
      private var lastWatchTime: TickerTime) {

    /** Returns the wrapped [[AssignmentGeneratorDriver]]. */
    def getGeneratorDriver: AssignmentGeneratorDriver = generatorDriver

    /** Updates the last watch time for this generator. */
    def updateLastWatchTime(time: TickerTime): Unit = {
      lastWatchTime = time
    }

    /** Returns the last watch time for this generator. */
    def getLastWatchTime: TickerTime = lastWatchTime
  }

  /** The [[EtcdClient]] namespace suffix in which preferred Assigner records are written. */
  private[dicer] val PREFERRED_ASSIGNER_ETCD_NAMESPACE_SUFFIX = "preferred-assigner"

  /**
   * The priority at which to register the Assigner shutdown hook, which triggers the Assigner to
   * abdicate if it is the preferred Assigner. This priority is higher than the default priority
   * to buy more time for the abdication process, resulting in a smoother preferred Assigner
   * transition.
   */
  private val TERMINATION_SHUTDOWN_HOOK_PRIORITY
      : Int = ShutdownHookManager.DEFAULT_SHUTDOWN_PRIORITY + 1

  /**
   * The fixed value of [[Assigner.minAssignmentGenerationInterval]] used in production. 10 second
   * is a time that effectively protects the assigner/slicelet/store from frequent assignment
   * generation or distribution, while still allowing the assigner to act quickly upon resource
   * health change or load balancing.
   */
  private[dicer] val MIN_ASSIGNMENT_GENERATION_INTERVAL: FiniteDuration = 10.seconds

  /**
   * Allows tests to override methods in [[Assigner]], which is not generally permitted.
   *
   * @param dPageNamespaceOpt when set, overrides the DPage namespace; otherwise
   *                          defaults to the assigner's UUID so concurrent
   *                          instances in the same test JVM get unique DAction names
   * @param assignerServiceInfoOpt The service info of the Assigner, used to uniquely identify an
   *                               assigner instance, or [[None]] when not available.
   */
  class BaseForTest(
      assignerSecPool: SequentialExecutionContextPool,
      sec: SequentialExecutionContext,
      conf: DicerAssignerConf,
      preferredAssignerDriver: PreferredAssignerDriver,
      storeFactory: Assigner.StoreFactory,
      kubernetesTargetWatcherFactory: KubernetesTargetWatcher.Factory,
      healthWatcherFactory: HealthWatcher.Factory,
      configProvider: TargetConfigProvider,
      uuid: UUID,
      hostName: String,
      assignerClusterUri: URI,
      minAssignmentGenerationInterval: FiniteDuration,
      dPageNamespaceOpt: Option[String],
      targetMigrator: TargetMigrator,
      localClusterMembershipChecker: KubernetesMembershipChecker,
      assignerServiceInfoOpt: Option[AssignerServiceInfo])
      extends Assigner(
        assignerSecPool,
        sec,
        conf,
        preferredAssignerDriver,
        storeFactory,
        kubernetesTargetWatcherFactory,
        healthWatcherFactory,
        configProvider,
        uuid,
        hostName,
        assignerClusterUri,
        minAssignmentGenerationInterval,
        dPageNamespace = dPageNamespaceOpt.getOrElse(uuid.toString),
        targetMigrator = targetMigrator,
        localClusterMembershipChecker = localClusterMembershipChecker,
        assignerServiceInfoOpt
      )

  /**
   * Creates an assigner using the given Kubernetes watcher factory and default health watcher
   * factory, and starts the RPC server. The membership checker is created here, so its creation
   * failures (bad env vars, K8s client init) fail startup.
   */
  @throws[IllegalArgumentException]("if the checker's namespace or app name is empty")
  @throws[java.io.IOException]("if the in-cluster Kubernetes client config is unavailable")
  def createAndStart(
      conf: DicerAssignerConf,
      configProvider: TargetConfigProvider,
      uuid: UUID,
      hostName: String,
      assignerClusterUri: URI,
      kubernetesTargetWatcherFactory: KubernetesTargetWatcher.Factory,
      localClusterMembershipCheckerFactory: KubernetesMembershipChecker.Factory,
      remoteClusterMembershipCheckerFactoryOpt: Option[KubernetesMembershipChecker.Factory],
      assignerServiceInfoOpt: Option[AssignerServiceInfo]): Assigner = {
    // The membership checker is a required dependency of the Assigner service: it discovers the
    // assigner set that consistent-hashing selection needs and backs the readiness probe. A pod
    // that cannot create one fails startup here (the factory throws) rather than running blind.
    val localClusterMembershipChecker: KubernetesMembershipChecker =
      localClusterMembershipCheckerFactory.create(uuid)
    val assignerSecPool =
      SequentialExecutionContextPool.create(
        poolName = "Assigner",
        numThreads = conf.secPoolThreadCount,
        alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
      )
    val assignerSec = SequentialExecutionContext.createWithDedicatedPool(
      name = "assigner-main",
      alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
    )
    val targetMigratorSec: SequentialExecutionContext =
      assignerSecPool.createExecutionContext("target-migrator")
    val assigner: Assigner = new Assigner(
      assignerSecPool,
      assignerSec,
      conf,
      Assigner.createPreferredAssignerDriver(conf, localClusterMembershipChecker),
      Assigner.createStoreFactory(conf),
      kubernetesTargetWatcherFactory,
      HealthWatcher.DefaultFactory,
      configProvider,
      uuid,
      hostName,
      assignerClusterUri,
      MIN_ASSIGNMENT_GENERATION_INTERVAL,
      dPageNamespace = "dicer",
      targetMigrator = TargetMigrator.create(
        targetMigratorSec,
        conf,
        uuid,
        assignerClusterUri,
        remoteClusterMembershipCheckerFactoryOpt,
        TargetMigrator.DEFAULT_INITIAL_TARGET_OWNERSHIP_RESOLVER_AWAIT_TIMEOUT
      ),
      localClusterMembershipChecker = localClusterMembershipChecker,
      assignerServiceInfoOpt
    )
    assigner.start()
    assigner
  }

  /**
   * Returns a [[StoreFactory]] for the given `conf` producing a fresh [[InMemoryStore]] per call.
   */
  private[assigner] def createStoreFactory(conf: DicerAssignerConf): StoreFactory = {
    // Stores share a [[SequentialExecutionContextPool]]; each store gets a new
    // [[SequentialExecutionContext]] from that pool.
    val inMemoryStoreSecPool: SequentialExecutionContextPool =
      SequentialExecutionContextPool.create(
        poolName = "assigner-in-memory-store",
        numThreads = 8,
        alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
      )
    new StoreFactory {

      override def getStore(): Store = {
        val sec: SequentialExecutionContext =
          inMemoryStoreSecPool.createExecutionContext("assigner-store")
        val storeIncarnation: Incarnation = conf.storeIncarnation
        logger.info(s"Initializing InMemoryStore with store incarnation [$storeIncarnation].")
        InMemoryStore(sec, storeIncarnation)
      }
    }
  }

  /**
   * REQUIRES: `conf.preferredAssignerEnabled` is true.
   * REQUIRES: `conf.preferredAssignerEtcdEndpoints` is non-empty.
   * REQUIRES: `conf.preferredAssignerStoreIncarnation` is not loose.
   *
   * Returns a new preferred assigner store, or throws [[IllegalArgumentException]] if the `conf`
   * isn't valid for creating a preferred assigner store. See [[EtcdPreferredAssignerStore.create]]
   * for documentation on the parameters.
   */
  private[dicer] def createPreferredAssignerStore(
      conf: DicerAssignerConf,
      random: Random = new Random,
      storeConfig: EtcdPreferredAssignerStore.Config = EtcdPreferredAssignerStore.DEFAULT_CONFIG
  ): EtcdPreferredAssignerStore = {
    require(conf.preferredAssignerEnabled, "Preferred assigner is not enabled.")
    val preferredAssignerEtcdNamespace: EtcdClient.KeyNamespace =
      Assigner.getPreferredAssignerEtcdNamespace(conf)
    val preferredAssignerStoreIncarnation = Incarnation(conf.preferredAssignerStoreIncarnation)
    logger.info(
      s"Creating EtcdPreferredAssignerStore with store incarnation: " +
      s"$preferredAssignerStoreIncarnation"
    )

    val client = createEtcdClient(conf, preferredAssignerEtcdNamespace)
    EtcdPreferredAssignerStore.create(
      preferredAssignerStoreIncarnation,
      client,
      random,
      storeConfig
    )
  }

  /**
   * Creates the [[PreferredAssignerDriver]] for the given config: a
   * [[MigrationPreferredAssignerDriver]] (etcd + consistent-hashing) when the preferred assigner is
   * enabled, else a [[DisabledPreferredAssignerDriver]] (which does not use the checker).
   */
  @throws[IllegalArgumentException](
    "if the migration mode requires a KubernetesMembershipChecker but none is provided"
  )
  private[dicer] def createPreferredAssignerDriver(
      conf: DicerAssignerConf,
      localClusterMembershipChecker: KubernetesMembershipChecker,
      driverConfig: EtcdPreferredAssignerDriver.Config = EtcdPreferredAssignerDriver.Config()
  ): PreferredAssignerDriver = {
    if (conf.preferredAssignerEnabled) {
      val store: EtcdPreferredAssignerStore = createPreferredAssignerStore(conf)
      // Use `getDicerClientTlsOptions` so it can send heartbeats to the preferred assigner.
      val tlsOptions: Option[TLSOptions] = conf.getDicerClientTlsOptions
      // REQUIRED: `etcdDriver`, `chDriver`, and the wrapping `MigrationPreferredAssignerDriver`
      // must all run on the same `SequentialExecutionContext` — see the class scaladoc on
      // `MigrationPreferredAssignerDriver`. This is load-bearing in the consistent-hashing modes,
      // where picks are forwarded between the drivers; other modes don't require it, but all
      // construction sites MUST preserve this property.
      val driverSec: SequentialExecutionContext =
        SequentialExecutionContext.createWithDedicatedPool(
          name = "preferred-assigner-driver",
          alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
        )
      val etcdDriver: EtcdPreferredAssignerDriver = new EtcdPreferredAssignerDriver(
        driverSec,
        tlsOptions,
        store,
        driverConfig
      )
      val chDriver: ConsistentHashingPreferredAssignerDriver =
        new ConsistentHashingPreferredAssignerDriver(driverSec, localClusterMembershipChecker)
      val migrationMode: MigrationMode = conf.preferredAssignerMigrationMode
      logger.info(s"Constructing MigrationPreferredAssignerDriver in mode: ${migrationMode.name}")
      new MigrationPreferredAssignerDriver(
        driverSec,
        migrationMode = migrationMode,
        oldDriver = etcdDriver,
        newDriver = chDriver
      )
    } else {
      val preferredAssignerStoreIncarnation = Incarnation(conf.preferredAssignerStoreIncarnation)
      logger.info(
        "Creating DisabledPreferredAssignerDriver with store incarnation " +
        s"$preferredAssignerStoreIncarnation."
      )
      new DisabledPreferredAssignerDriver(preferredAssignerStoreIncarnation)
    }
  }

  /**
   * Returns the etcd namespace in which the assigner for the given `conf` stores preferred assigner
   * metadata.
   */
  private[dicer] def getPreferredAssignerEtcdNamespace(
      conf: DicerAssignerConf): EtcdClient.KeyNamespace = {
    val prefixSeparator: String = if (conf.storeNamespacePrefix.isEmpty) "" else "-"
    EtcdClient.KeyNamespace(
      s"${conf.storeNamespacePrefix}$prefixSeparator" +
      PREFERRED_ASSIGNER_ETCD_NAMESPACE_SUFFIX
    )
  }

  /**
   * Creates an etcd client based on the Assigner configuration and the etcd key namespace.
   */
  private[this] def createEtcdClient(
      conf: DicerAssignerConf,
      etcdKeyNamespace: EtcdClient.KeyNamespace): EtcdClient = {
    val endpoints: Seq[String] = conf.preferredAssignerEtcdEndpoints
    val tlsOptionsOpt: Option[TLSOptions] =
      if (conf.preferredAssignerEtcdSslEnabled) conf.dicerTlsOptions else None
    logger.info("Creating etcd client with endpoints: " + endpoints)
    EtcdClient.create(
      endpoints,
      tlsOptionsOpt,
      EtcdClient.Config(etcdKeyNamespace)
    )
  }
}

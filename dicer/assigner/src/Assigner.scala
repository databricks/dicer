package com.databricks.dicer.assigner

import com.databricks.api.proto.dicer.assigner.{HeartbeatRequestP, HeartbeatResponseP}
import com.databricks.api.proto.dicer.common.{ClientRequestP, ClientResponseP}
import com.databricks.caching.util.{
  EtcdClient,
  GenericRpcServiceBuilder,
  PrefixLogger,
  SequentialExecutionContext,
  SequentialExecutionContextPool,
  TickerTime,
  ValueStreamCallback
}
import com.databricks.common.http.Headers.{
  HEADER_DATABRICKS_APP_INSTANCE_ID,
  HEADER_DATABRICKS_APP_SPEC_NAME
}
import com.databricks.common.util.ShutdownHookManager
import com.databricks.dicer.assigner.Assigner.logger
import com.databricks.dicer.assigner.AssignmentGenerator.GeneratorTargetSlicezData
import com.databricks.dicer.assigner.conf.StoreConf.StoreEnum.{ETCD, IN_MEMORY}
import com.databricks.dicer.assigner.conf.{DicerAssignerConf, HealthConf, LoadWatcherConf}
import com.databricks.dicer.assigner.config.{
  StaticTargetConfigProvider,
  InternalTargetConfig,
  InternalTargetConfigMap,
  InternalTargetConfigMetrics
}
import com.databricks.dicer.common.TargetName
import com.databricks.dicer.assigner.TargetMetrics.{GeneratorShutdownReason, WatchError}
import com.databricks.dicer.common.SyncAssignmentState.KnownGeneration
import com.databricks.dicer.common.{
  Assignment,
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
import com.databricks.dicer.external.{AppTarget, KubernetesTarget, Target}
import com.databricks.rpc.{DatabricksServerWrapper, RPCContext}
import com.databricks.rpc.tls.TLSOptions
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
 * @param storeFactory when applied, returns a [[Store]] for assignment storage. For IN_MEMORY
 *                     config each application returns a new [[InMemoryStore]]; for ETCD
 *                     each application returns the same shared store instance.
 * @param assignerClusterUri The URI of the Assigner cluster. This is used to normalize targets.
 * @param minAssignmentGenerationInterval See remarks for
 *        [[AssignmentGenerator.config.minAssignmentGenerationInterval]]. Will be used for the
 *        assignment generators for all targets. This parameter is lifted here in Assigner's
 *        constructor so we can inject different values for TestAssigner.
 * @param dPageNamespace The namespace used for DPage registration. In production this is
 *        "dicer"; in tests each [[Assigner]] instance uses its UUID so that concurrent
 *        instances register under unique DAction names.
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
    private val configProvider: StaticTargetConfigProvider,
    uuid: UUID,
    hostName: String,
    assignerClusterUri: URI,
    minAssignmentGenerationInterval: FiniteDuration,
    dPageNamespace: String)
    extends AssignerSlicezDataExporter {
  import Assigner.AssignmentGeneratorHandle

  /** The abstraction that manages all subscriber connections and messages. */
  @GuardedBy("sec")
  private[this] val subscriberManager: SubscriberManager =
    new SubscriberManager(
      assignerSecPool,
      getSuggestedClerkRpcTimeoutFn = () => conf.getAssignerSuggestedClerkWatchTimeout,
      suggestedSliceletRpcTimeout = conf.watchServerSuggestedRpcTimeout
    )

  /** A map that keeps track of all generators.  */
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
   * The proto logger for Assigner-specific logging events.
   *
   * Initialized in [[start()]] which the factory method ensures is always called.
   */
  @GuardedBy("sec")
  private var assignerProtoLogger: AssignerProtoLogger = _

  /**
   * Unmarshaller for [[Target]] protos that are received by the assigner. The unmarshaller is
   * responsible for normalizing the target based on the assigner's cluster.
   */
  private[this] val targetUnmarshaller =
    TargetUnmarshaller.createAssignerUnmarshaller(assignerClusterUri)

  /**
   * Determines whether this assigner should handle the target locally or redirect to
   * another assigner.
   */
  private val targetMigrationRouter: TargetMigrationRouter = TargetMigrationRouter.ALWAYS_HANDLE

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
   * Handles the watch call from a Clerk/Slicelet and returns the relevant response.
   *
   * @note The [[targetMigrationRouter]] is checked first. If it returns [[Verdict.Redirect]], the
   *       request is immediately redirected to the specified assigner. Otherwise, routing proceeds
   *       as per the preferred assigner's role:
   *        - If the current assigner is a standby, it redirects to the preferred assigner.
   *        - If the current assigner is preferred, it handles the request and responds with a
   *          redirect to be [[Redirect.EMPTY]] if the preferred assigner mode is disabled, or a
   *          redirect to the current assigner if the preferred assigner is enabled.
   */
  def handleWatch(rpcContext: RPCContext, req: ClientRequestP): Future[ClientResponseP] = {
    sec.flatCall {
      val request = ClientRequest.fromProto(targetUnmarshaller, req)

      // Check the migration router first to determine whether to handle or redirect the target.
      targetMigrationRouter.getVerdict(request.target) match {
        case Verdict.Redirect(handlingAssigner: URI) =>
          Future.successful(
            ClientResponse(
              syncState = KnownGeneration(Generation.EMPTY),
              suggestedRpcTimeout = getSuggestedWatchRpcTimeout(request),
              redirect = Redirect(Some(handlingAssigner))
            ).toProto
          )
        case Verdict.Handle =>
          preferredAssignerConfig.role match {
            case AssignerRole.Preferred =>
              // If the current assigner is preferred, handle the watch request.
              validateTarget(request.target, rpcContext, request.getClientType)
              lookupGenerator(request.target) match {
                case Some(generatorHandle: AssignmentGeneratorHandle) =>
                  val generator: AssignmentGeneratorDriver = generatorHandle.getGeneratorDriver
                  val redirect: Redirect = preferredAssignerConfig.redirect
                  generator.onWatchRequest(request)

                  // Track the last activity time for the target.
                  val currentTime: TickerTime = sec.getClock.tickerTime()
                  generatorHandle.updateLastWatchTime(currentTime)

                  subscriberManager.handleWatchRequest(
                    rpcContext,
                    request,
                    generator.getGeneratorCell,
                    redirect,
                    currentTime
                  )
                case None =>
                  logger.warn(
                    s"Received watch request for unknown target: ${request.target}",
                    every = 10.seconds
                  )
                  TargetMetrics.incrementNumTargetWatchErrors(request.target, WatchError.NO_CONFIG)
                  Future.failed(
                    DatabricksServiceException(
                      ErrorCode.NOT_FOUND,
                      s"Missing target config: ${request.target}"
                    )
                  )
              }
            case AssignerRole.Standby =>
              // If the current assigner is a standby, redirect the watch request to the preferred
              // assigner.
              Future.successful(
                ClientResponse(
                  syncState = KnownGeneration(Generation.EMPTY),
                  suggestedRpcTimeout = getSuggestedWatchRpcTimeout(request),
                  redirect = preferredAssignerConfig.redirect
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

  override def getSlicezData: Future[AssignerSlicezData] = sec.flatCall {
    // Collect the data for all generators and all subscribers.
    val targetSlicezDataSeq: Seq[Future[AssignerTargetSlicezData]] =
      (for (entry <- generatorMap) yield {
        val (target, generatorHandle): (Target, AssignmentGeneratorHandle) = entry
        getTargetSlicezData(target, generatorHandle.getGeneratorDriver)
      }).toSeq

    val aggregatedTargetSlicezData: Future[Seq[AssignerTargetSlicezData]] =
      Future.sequence(targetSlicezDataSeq)(implicitly, sec)
    aggregatedTargetSlicezData
      .map { targetSlicezData: Seq[AssignerTargetSlicezData] =>
        AssignerSlicezData(
          this.getAssignerInfoSync,
          preferredAssignerConfig.knownPreferredAssigner,
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
   */
  private[this] def getAssignerInfoSync: AssignerInfo = {
    sec.assertCurrentContext()
    assignerInfo
  }

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
  }

  /** Creates and starts a [[DatabricksServerWrapper]] exposing this `assigner`. */
  private[this] def createAndStartDatabricksServer(): DatabricksServerWrapper = {
    sec.assertCurrentContext()
    val serviceBuilder: GenericRpcServiceBuilder = GenericRpcServiceBuilder.create()
    // Register the assignment and preferred assigner services with `serviceBuilder`. Using helpers
    // here so that the registration process can use vanilla gRPC.
    WatchServerHelper.registerAssignmentService(
      serviceBuilder,
      (rpcContext: RPCContext, req: ClientRequestP) => this.handleWatch(rpcContext, req)
    )
    PreferredAssignerServerHelper.registerPreferredAssignerService(
      serviceBuilder,
      (req: HeartbeatRequestP) => this.handleHeartbeat(req)
    )
    val server: DatabricksServerWrapper = WatchServerHelper.createWatchServer(
      conf,
      conf.dicerAssignerRpcPort,
      conf.loopbackRpcPortOpt,
      serviceBuilder
    )
    server.start()
    assignerInfo = AssignerInfo(uuid, URI.create(s"https://$hostName:${server.activePort()}"))
    // Dedicated SEC for proto logging operations to avoid blocking the main SEC.
    // Note: Context propagation is disabled because proto logging is asynchronous and not part of a
    // user request path, so there is no request context to propagate.
    val protoLoggerSec: SequentialExecutionContext =
      SequentialExecutionContext.createWithDedicatedPool(
        "assigner-proto-logger",
        enableContextPropagation = false
      )
    assignerProtoLogger = AssignerProtoLogger.create(
      assignerInfo,
      conf.protoLoggerGenerationSampleFraction,
      protoLoggerSec
    )
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

  /** Shuts down all the generators if the current assigner is a standby. */
  private[this] def onPreferredAssignerConfigChange(newConfig: PreferredAssignerConfig): Unit = {
    sec.assertCurrentContext()
    newConfig.role match {
      case AssignerRole.Preferred => // Do nothing.
      case AssignerRole.Standby =>
        // Shut down all generators in an idempotent manner.
        for (tuple <- generatorMap) {
          val (target, generatorHandle): (Target, AssignmentGeneratorHandle) = tuple
          TargetMetrics.incrementGeneratorsRemoved(
            target,
            GeneratorShutdownReason.PREFERRED_ASSIGNER_CHANGE
          )
          TargetMetrics.updateTargetsWithActiveGenerators(target, 0)
          generatorHandle.getGeneratorDriver.shutdown()
        }
        generatorMap.clear()
    }
    logger.info(s"[$assignerInfo] updated config from $preferredAssignerConfig to $newConfig")
    preferredAssignerConfig = newConfig
  }

  /** Starts watching config value changes, e.g., from SAFE. */
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
        }
      }
    }
    // Start watching config changes.
    configProvider.watch(configUpdatedCallback)
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
      assignerProtoLogger
    )
  }

  /**
   * Update the config for the `target` in `updatedConfig` if the config has changed, such
   * that subsequent assignments will be generated using the updated configuration.
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
    // - If the target is not in the config map, it is considered default configured for
    //   experimental targets (which it must be, because the set of target names in config is fixed
    //   for the lifetime of the Assigner, as targets are not allowed to be added or removed
    //   dynamically).
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
   * Validates that the given `target` is allowed to be specified by a request with the given
   * `rpcContext` and `clientType`.
   *
   * If target validation is disabled, this method immediately returns without an error. Otherwise,
   * validation proceeds as follows:
   *   1. If the request does not contain App Identifier headers AND the client is a
   *      Clerk, we waive validation for backwards compatibility with non-Clerk callers.
   *   2. If the request does not contain App Identifier headers AND the client is a
   *      Slicelet, `target` is invalid.
   *   3. If the request contains App Identifier headers and the client's App
   *      Name is part of a trusted set of services, `target` is valid.
   *   4. Otherwise, if the target is a KubernetesTarget, `target` is invalid
   *      (KubernetesTargets can't be validated with App Identifiers).
   *   5. Otherwise, if the target is an AppTarget and the client's App Name and 
   *      App Instance ID match the AppTarget's name and instanceId, the target is valid.
   *   6. Otherwise, the target is invalid.
   *
   * Note: Step 1 is a temporary compatibility path for Clerk callers that cannot yet supply
   * headers (e.g. dp-apiproxy). In the current setup, DP requests can reach the Assigner
   * only after passing authz in:
   *   1. S2SProxy configuration, which only forwards watch requests from restricted and validated
   *      access patterns.
   *   2. Assigner's S2S authz policy, which restricts communication to control-plane clients
   *       (in this case, via S2SProxy).
   * Together, these mechanisms ensure that only trusted DP → CP callers can reach the Assigner.
   */
  @throws[IllegalArgumentException]("if the target is invalid in the given context")
  private[this] def validateTarget(
      target: Target,
      rpcContext: RPCContext,
      clientType: ClientType): Unit = {
    if (!conf.enableTargetValidationViaAppIdentifierHeaders) {
      return
    }

    // Attempt to read the App Identifier headers from the request context.
    // Waive validation if the headers are missing AND the client is a Clerk to support
    // non-Clerk callers.
    val (appName, appInstanceId): (String, String) = (for {
      appName <- rpcContext.httpRequest.getHeader(HEADER_DATABRICKS_APP_SPEC_NAME)
      appInstanceId <- rpcContext.httpRequest.getHeader(HEADER_DATABRICKS_APP_INSTANCE_ID)
    } yield (appName, appInstanceId))
      .getOrElse {
        TargetMetrics.incrementNumTargetWatchErrors(target, WatchError.INVALID_TARGET_NO_HEADERS)
        clientType match {
          case ClientType.Clerk =>
            // TODO(<internal bug>): Throw exception on missing headers once all trusted
            //                    services use compatible certs.
            return
          case ClientType.Slicelet =>
            throw new IllegalArgumentException(
              s"Request is missing required App Identifier header."
            )
        }
      }

    if (conf.trustedWatchAnyTargetServices.contains(appName)) {
      // The request is from a service that is allowed to send requests for any `target`, so no
      // further validation is needed.
      return
    }

    target match {
      case _: KubernetesTarget =>
        TargetMetrics.incrementNumTargetWatchErrors(target, WatchError.INVALID_TARGET_NOT_APP)
        throw new IllegalArgumentException(
          "AppTarget must be specified for request with App Identifier headers."
        )
      case appTarget: AppTarget =>
        if (appTarget.name != appName) {
          TargetMetrics.incrementNumTargetWatchErrors(
            target,
            WatchError.INVALID_TARGET_NAME_MISMATCH
          )
          throw new IllegalArgumentException(
            s"$appTarget name does not match App Name header $appName"
          )
        }
        if (appTarget.instanceId != appInstanceId) {
          TargetMetrics.incrementNumTargetWatchErrors(
            target,
            WatchError.INVALID_TARGET_INSTANCE_ID_MISMATCH
          )
          throw new IllegalArgumentException(
            s"$appTarget instanceId does not match App Instance ID header $appInstanceId"
          )
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
  }
}

/** Companion object for [[Assigner]]. */
object Assigner {

  /**
   * Provides a [[Store]]. Each call returns a new instance or a shared one depending on
   * configuration.
   */
  trait StoreFactory {

    /** Returns a new [[Store]] when called. */
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

  // Preferred assigner and durable assignments are given separate etcd namespaces so avoid
  // any potential collisions between their keys.

  /** The [[EtcdClient]] namespace suffix in which preferred Assigner records are written. */
  private[dicer] val PREFERRED_ASSIGNER_ETCD_NAMESPACE_SUFFIX = "preferred-assigner"

  /** The [[EtcdClient]] namespace suffix in which assignments are written. */
  private[dicer] val ASSIGNMENTS_ETCD_NAMESPACE_SUFFIX = "assignments"

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
   */
  class BaseForTest(
      assignerSecPool: SequentialExecutionContextPool,
      sec: SequentialExecutionContext,
      conf: DicerAssignerConf,
      preferredAssignerDriver: PreferredAssignerDriver,
      storeFactory: Assigner.StoreFactory,
      kubernetesTargetWatcherFactory: KubernetesTargetWatcher.Factory,
      healthWatcherFactory: HealthWatcher.Factory,
      configProvider: StaticTargetConfigProvider,
      uuid: UUID,
      hostName: String,
      assignerClusterUri: URI,
      minAssignmentGenerationInterval: FiniteDuration,
      dPageNamespaceOpt: Option[String])
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
        dPageNamespace = dPageNamespaceOpt.getOrElse(uuid.toString)
      )

  /**
   * Creates an assigner using the given Kubernetes watcher factory and default health watcher
   * factory. Also, starts the assigner rpc server.
   */
  def createAndStart(
      conf: DicerAssignerConf,
      configProvider: StaticTargetConfigProvider,
      uuid: UUID,
      hostName: String,
      assignerClusterUri: URI,
      kubernetesTargetWatcherFactory: KubernetesTargetWatcher.Factory,
      membershipCheckerFactory: KubernetesMembershipChecker.Factory): Assigner = {
    // How do we choose the number of threads? Resilience to long-running commands requires us to
    // have more threads than cores, but we don't want lots of preemption (we prefer a cooperative
    // concurrency model after all, see <internal link>) or the overhead cost of
    // running lots of threads. Given that the Assigner is currently a small server, we go with 8!
    val assignerSecPool = SequentialExecutionContextPool.create("Assigner", numThreads = 8)
    val assignerSec = SequentialExecutionContext.createWithDedicatedPool("assigner-main")
    val assigner: Assigner = new Assigner(
      assignerSecPool,
      assignerSec,
      conf,
      Assigner.createPreferredAssignerDriver(conf, membershipCheckerFactory),
      Assigner.createStoreFactory(conf),
      kubernetesTargetWatcherFactory,
      HealthWatcher.DefaultFactory,
      configProvider,
      uuid,
      hostName,
      assignerClusterUri,
      MIN_ASSIGNMENT_GENERATION_INTERVAL,
      dPageNamespace = "dicer"
    )
    assigner.start()
    assigner
  }

  /**
   * Returns a [[StoreFactory]] that, when applied, returns a new [[InMemoryStore]] per call if
   * `conf.store` is IN_MEMORY, or the same shared store instance if ETCD.
   *
   * @param conf the assigner configuration.
   * @return a [[StoreFactory]] that, when applied, returns a new [[Store]]
   */
  private[assigner] def createStoreFactory(conf: DicerAssignerConf): StoreFactory = {
    conf.store match {
      case IN_MEMORY =>
        // For IN_MEMORY, stores share a [[SequentialExecutionContextPool]]; each store gets a new
        // [[SequentialExecutionContext]] from that pool.
        val inMemoryStoreSecPool: SequentialExecutionContextPool =
          SequentialExecutionContextPool.create("assigner-in-memory-store", numThreads = 8)
        new StoreFactory {

          override def getStore(): Store = {
            val sec: SequentialExecutionContext =
              inMemoryStoreSecPool.createExecutionContext("assigner-store")
            val storeIncarnation: Incarnation = conf.storeIncarnation
            logger.info(s"Initializing InMemoryStore with store incarnation [$storeIncarnation].")
            InMemoryStore(sec, storeIncarnation)
          }
        }
      case ETCD =>
        val sec: SequentialExecutionContext =
          SequentialExecutionContext.createWithDedicatedPool("assigner-store")
        val storeIncarnation: Incarnation = conf.storeIncarnation
        val etcdClient: EtcdClient = createEtcdClient(conf, getAssignmentsEtcdNamespace(conf))

        logger.info(s"Initializing EtcdStore with store incarnation [$storeIncarnation].")
        val sharedStore: Store =
          EtcdStore.create(sec, etcdClient, EtcdStoreConfig.create(storeIncarnation), new Random)
        new StoreFactory {
          override def getStore(): Store = sharedStore
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

  /** Creates a [[PreferredAssignerDriver]] with the given Dicer and driver configurations. */
  private[dicer] def createPreferredAssignerDriver(
      conf: DicerAssignerConf,
      membershipCheckerFactory: KubernetesMembershipChecker.Factory,
      driverConfig: EtcdPreferredAssignerDriver.Config = EtcdPreferredAssignerDriver.Config()
  ): PreferredAssignerDriver = {
    if (conf.preferredAssignerEnabled) {
      val store: EtcdPreferredAssignerStore = createPreferredAssignerStore(conf)
      // Use `getDicerClientTlsOptions` so it can send heartbeats to the preferred assigner.
      val tlsOptions: Option[TLSOptions] = conf.getDicerClientTlsOptions
      val sec = SequentialExecutionContext.createWithDedicatedPool("etcd-preferred-assigner-driver")
      new EtcdPreferredAssignerDriver(
        sec,
        tlsOptions,
        store,
        driverConfig,
        membershipCheckerFactory
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

  /** Returns the etcd namespace in which the assigner for the given `conf` stores assignments. */
  private[dicer] def getAssignmentsEtcdNamespace(
      conf: DicerAssignerConf): EtcdClient.KeyNamespace = {
    val prefixSeparator: String = if (conf.storeNamespacePrefix.isEmpty) "" else "-"
    EtcdClient.KeyNamespace(
      s"${conf.storeNamespacePrefix}$prefixSeparator" +
      ASSIGNMENTS_ETCD_NAMESPACE_SUFFIX
    )
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

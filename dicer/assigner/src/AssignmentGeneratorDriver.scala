package com.databricks.dicer.assigner

import java.util.UUID

import java.net.URI

import scala.concurrent.Future
import scala.util.Try

import io.grpc.Status

import com.databricks.caching.util.AssertMacros.iassert
import com.databricks.caching.util.{
  Cancellable,
  PrefixLogger,
  SequentialExecutionContext,
  StateMachineDriver,
  ValueStreamCallback
}
import com.databricks.dicer.assigner.config.InternalTargetConfig
import com.databricks.dicer.assigner.AssignmentGenerator.{
  AssignmentGenerationContext,
  DriverAction,
  Event,
  GeneratorTargetSlicezData
}
import com.databricks.dicer.common.ProposedAssignment
import com.databricks.dicer.common.Assignment.{AssignmentValueCell, AssignmentValueCellConsumer}
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.common.{Assignment, ClientRequest}
import com.databricks.dicer.external.Target

import scala.concurrent.duration.FiniteDuration

import com.databricks.dicer.assigner.conf.LoadWatcherConf

/**
 * Driver for the [[AssignmentGenerator]] state machine. Performs actions requested by the
 * generator, and "drives" it using a [[StateMachineDriver]] (see that class for remarks on the
 * responsibilities of the driver and state machine).
 */
class AssignmentGeneratorDriver private (
    sec: SequentialExecutionContext,
    loadWatcherConf: LoadWatcherConf,
    target: Target,
    val targetConfig: InternalTargetConfig,
    store: Store,
    kubernetesTargetWatcherFactory: KubernetesTargetWatcher.Factory,
    healthWatcher: HealthWatcher,
    keyOfDeathDetector: KeyOfDeathDetector,
    clusterUri: URI,
    minAssignmentGeneration: FiniteDuration,
    dicerTeeEventEmitter: DicerTeeEventEmitter,
    assignerProtoLogger: AssignerProtoLogger) {

  /** Generic driver implementation that drives the [[AssignmentGenerator]] state machine. */
  val baseDriver = new StateMachineDriver[Event, DriverAction, AssignmentGenerator](
    sec,
    new AssignmentGenerator(
      AssignmentGenerator.Config
        .fromConfs(store.storeIncarnation, loadWatcherConf, clusterUri, minAssignmentGeneration),
      target,
      targetConfig,
      healthWatcher,
      keyOfDeathDetector
    ),
    performAction
  )

  /**
   * Cell containing the latest assignment from the generator. It is updated in response to
   * [[DriverAction.DistributeAssignment]] requests from the generator.
   */
  private val assignmentCell = new AssignmentValueCell

  /**
   * Statistics information tracked by the generator and to be displayed on the Assigner ZPage.
   * See [[GeneratorTargetSlicezData]] for more details. It is updated in response to
   * [[DriverAction.UpdateAssignmentGeneratorTargetSlicezData]] requests from the generator.
   */
  private var generatorTargetSlicezData = GeneratorTargetSlicezData.EMPTY

  private val logger = PrefixLogger.create(this.getClass, target.getLoggerPrefix)

  /** Optional cancellable for watching store assignment updates. */
  private var assignmentWatcherCancellableOpt: Option[Cancellable] = None

  /** Optional watcher for Kubernetes termination signals . */
  private var kubernetesTargetWatcherOpt: Option[KubernetesTargetWatcher] = None

  /**
   * Read-only cell containing the latest assignment from the generator.
   *
   * Important: Note that if the generator is shut down, then no further updates will be
   * delivered on this generator cell. To learn about new assignments for a target, calling code
   * *must* watch the generator cell from the latest incarnation of the assignment
   * generator for that target, rather than continuing to use a cached cell indefinitely.
   */
  def getGeneratorCell: AssignmentValueCellConsumer = assignmentCell

  /** Collects the [[GeneratorTargetSlicezData]] statistics information. */
  def getGeneratorTargetSlicezData: Future[GeneratorTargetSlicezData] = {
    sec.call {
      generatorTargetSlicezData
    }
  }

  /** Informs the generator of a watch request from a Slicelet or Clerk. */
  def onWatchRequest(request: ClientRequest): Unit = sec.run {
    handleEvent(Event.WatchRequest(request))
  }

  /** Stops new assignment generation in response to subsequent events. */
  def shutdown(): Unit = sec.run {
    sec.assertCurrentContext()

    handleEvent(Event.Shutdown())
  }

  /** A wrapper method for passing the event to baseDriver and also emitting it to Tee. */
  private def handleEvent(event: Event): Unit = {
    sec.assertCurrentContext()
    baseDriver.handleEvent(event)
    dicerTeeEventEmitter.maybeEmitEvent(target, event)
  }

  /**
   * Informs the generator of a termination signal from Kubernetes for the Slicelet with
   * `resourceUuid`.
   */
  private def onTerminationSignalFromKubernetes(resourceUuid: UUID): Unit = sec.run {
    logger.info(s"Received termination signal from Kubernetes for resourceUuid: $resourceUuid")
    handleEvent(Event.TerminationSignalFromKubernetes(resourceUuid))
  }

  /** Starts the driver. */
  protected def start(): Unit = {
    sec.assertCurrentContext()

    // Kick-off the state machine.
    baseDriver.start()
  }

  /** Performs an action requested of this driver by the generator state machine. */
  private def performAction(action: DriverAction): Unit = {
    sec.assertCurrentContext()
    action match {
      case DriverAction.StartKubernetesTargetWatcher(watchTarget: KubernetesWatchTarget) =>
        // Start watching Kubernetes termination signals.
        iassert(kubernetesTargetWatcherOpt.isEmpty)
        logger.info(s"Starting KubernetesTargetWatcher for $watchTarget")
        val kubernetesTargetWatcher =
          kubernetesTargetWatcherFactory.create(watchTarget, onTerminationSignalFromKubernetes)
        kubernetesTargetWatcher.start()
        kubernetesTargetWatcherOpt = Some(kubernetesTargetWatcher)

      case DriverAction.StopKubernetesTargetWatcher() =>
        // Stop watching kubernetes termination signals.
        if (kubernetesTargetWatcherOpt.isDefined) {
          kubernetesTargetWatcherOpt.get.stop()
          kubernetesTargetWatcherOpt = None
        }

      case DriverAction.StartStoreWatcher() =>
        // Watch the store for assignment changes. Retain the cancellation handle so we can stop
        // watching after shutdown.
        iassert(assignmentWatcherCancellableOpt.isEmpty)
        assignmentWatcherCancellableOpt = Some(
          store.watchAssignments(
            target,
            new ValueStreamCallback[Assignment](sec) {
              override protected def onSuccess(assignment: Assignment): Unit = {
                handleEvent(Event.AssignmentReceived(assignment))
              }
            }
          )
        )

      case DriverAction.StopStoreWatcher() =>
        // Stop watching assignment changes from the store.
        if (assignmentWatcherCancellableOpt.isDefined) {
          assignmentWatcherCancellableOpt.get.cancel(Status.CANCELLED)
          assignmentWatcherCancellableOpt = None
        }

      case DriverAction.DistributeAssignment(assignment: Assignment) =>
        // Inform the store of the assignment. The store may already be aware of the assignment (it
        // may have read it or written it), but the assignment may also have been discovered via the
        // sync protocol from a Slicelet or Clerk so we pass it to the storage layer in case it
        // needs to be cached there.
        store.informAssignment(target, assignment)

        // Set the assignment on the cell used to trigger distribution of assignments to subscribers
        // (Slicelets and Clerks).
        assignmentCell.setValue(assignment)

      case DriverAction.LogAssignment(
          assignment: Assignment,
          contextOpt: Option[AssignmentGenerationContext]
          ) =>
        // Log the assignment update for debugging purposes. This is only called for assignments
        // that we generate and commit ourselves, not for assignments received from other sources.
        assignerProtoLogger.logAssignmentUpdate(
          target = target,
          assignment = assignment,
          contextOpt = contextOpt
        )

      case DriverAction.WriteAssignment(
          proposal: ProposedAssignment,
          contextOpt: Option[AssignmentGenerationContext]
          ) =>
        store
          .writeAssignment(target, shouldFreeze = false, proposal)
          .onComplete { result: Try[Store.WriteAssignmentResult] =>
            // Pass the complete result to the generator, which will unpack and handle it.
            handleEvent(Event.AssignmentWriteComplete(result, contextOpt))
          }(sec)

      case DriverAction.UpdateAssignmentGeneratorTargetSlicezData(newGeneratorTargetSlicezData) =>
        // Update the internal [[GeneratorTargetSlicezData]].
        generatorTargetSlicezData = newGeneratorTargetSlicezData
    }
  }

  object forTest {

    /**
     * Returns a [[Future]] which completes with a boolean describing whether or not an assignment
     * write is currently in progress.
     *
     * Checking for an in-progress write is useful for tests where we trigger multiple assignment
     * writes, as advancing the clock must be done after a previous write has completed to trigger
     * an additional write. Sequencing simply on the externalization of a new assignment is
     * insufficient because the generator may first learn of a newly written assignment through the
     * store's watch cell and externalize it while the write is still "in-flight".
     */
    private[assigner] def isAssignmentWriteInProgress: Future[Boolean] = sec.call {
      baseDriver.forTest.getStateMachine.forTest.isAssignmentWriteInProgress
    }
  }
}

object AssignmentGeneratorDriver {

  /**
   * Creates a generator for `target`.
   *
   * @param sec The [[SequentialExecutionContext]] on which the generator will run.
   * @param loadWatcherConf The configuration for watching load.
   * @param target The target for which the generator will generate assignments.
   * @param targetConfig The configuration for the target.
   * @param store The store to use for reading and writing assignments.
   * @param kubernetesTargetWatcherFactory Factory for creating a k8s watcher for pod signals.
   * @param healthWatcher The health watcher to use for tracking pod health.
   * @param keyOfDeathDetector The key of death detector to use for tracking recent crashes and
   *                           declaring key of death scenarios.
   * @param clusterUri The URI of the cluster in which the generator is running.
   * @param minAssignmentGenerationInterval The minimum interval between assignment generations.
   * @param dicerTeeEventEmitter The emitter used to optionally
   *                             send state machine events to Dicer Tee.
   * @param assignerProtoLogger The proto logger for Assigner-specific logging events.
   */
  def create(
      sec: SequentialExecutionContext,
      loadWatcherConf: LoadWatcherConf,
      target: Target,
      targetConfig: InternalTargetConfig,
      store: Store,
      kubernetesTargetWatcherFactory: KubernetesTargetWatcher.Factory,
      healthWatcher: HealthWatcher,
      keyOfDeathDetector: KeyOfDeathDetector,
      clusterUri: URI,
      minAssignmentGenerationInterval: FiniteDuration,
      dicerTeeEventEmitter: DicerTeeEventEmitter,
      assignerProtoLogger: AssignerProtoLogger): AssignmentGeneratorDriver = {
    val driver =
      new AssignmentGeneratorDriver(
        sec,
        loadWatcherConf,
        target,
        targetConfig,
        store,
        kubernetesTargetWatcherFactory,
        healthWatcher,
        keyOfDeathDetector,
        clusterUri,
        minAssignmentGenerationInterval,
        dicerTeeEventEmitter,
        assignerProtoLogger
      )
    sec.run { driver.start() }
    driver
  }

  /**
   * Allows tests to override methods in [[AssignmentGeneratorDriver]],
   * which is not generally permitted.
   */
  class BaseForTest(
      sec: SequentialExecutionContext,
      loadWatcherConf: LoadWatcherConf,
      target: Target,
      targetConfig: InternalTargetConfig,
      store: Store,
      kubernetesTargetWatcherFactory: KubernetesTargetWatcher.Factory,
      healthWatcher: HealthWatcher,
      keyOfDeathDetector: KeyOfDeathDetector,
      clusterUri: URI,
      minAssignmentGeneration: FiniteDuration,
      dicerTeeEventEmitter: DicerTeeEventEmitter,
      assignerProtoLogger: AssignerProtoLogger)
      extends AssignmentGeneratorDriver(
        sec,
        loadWatcherConf,
        target,
        targetConfig,
        store,
        kubernetesTargetWatcherFactory,
        healthWatcher,
        keyOfDeathDetector,
        clusterUri,
        minAssignmentGeneration,
        dicerTeeEventEmitter,
        assignerProtoLogger
      )
}

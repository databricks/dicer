package com.databricks.dicer.assigner

import java.net.URI
import javax.annotation.concurrent.GuardedBy
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal
import io.grpc.Deadline
import com.databricks.api.proto.dicer.assigner.HeartbeatResponseP
import com.databricks.api.proto.dicer.assigner.PreferredAssignerServiceGrpc.PreferredAssignerServiceStub
import com.databricks.caching.util.AssertMacros.iassert
import com.databricks.caching.util.{
  Cancellable,
  PrefixLogger,
  SequentialExecutionContext,
  StateMachineDriver,
  TickerTime,
  ValueStreamCallback,
  WatchValueCell
}
import com.databricks.context.Ctx
import com.databricks.dicer.assigner.EtcdPreferredAssignerDriver.{HeartbeatAddressAndStub, logger}
import com.databricks.dicer.assigner.EtcdPreferredAssignerStateMachine.{DriverAction, Event}
import com.databricks.dicer.assigner.EtcdPreferredAssignerStore.PreferredAssignerProposal
import com.databricks.logging.activity.ActivityContextFactory.withBackgroundActivity
import com.databricks.rpc.tls.TLSOptions

class EtcdPreferredAssignerDriver(
    sec: SequentialExecutionContext,
    assignerTlsOptionsOpt: Option[TLSOptions],
    store: EtcdPreferredAssignerStore,
    config: EtcdPreferredAssignerDriver.Config,
    membershipCheckerFactory: KubernetesMembershipChecker.Factory
) extends PreferredAssignerDriver {

  /** The timeout for the heartbeat RPC call. */
  private val HEARTBEAT_RPC_TIMEOUT = config.heartbeatInterval

  /**
   * The current preferred assigner config, which is guaranteed to have non-empty value after
   * `start()` is called.
   */
  private val preferredAssignerConfigWatchCell = new WatchValueCell[PreferredAssignerConfig]()

  /** A helper class for creating PreferredAssigner stubs. */
  private val preferredAssignerServerHelper = new PreferredAssignerServerHelper(
    assignerTlsOptionsOpt
  )

  /**
   * Keeps track of the heartbeat address and its corresponding stub, which is updated when the
   * preferred Assigner changes.
   */
  @GuardedBy("sec")
  private var heartbeatAddressAndStubOpt: Option[HeartbeatAddressAndStub] = None

  /** The base state machine driver, populated in [[start()]]. */
  @GuardedBy("sec")
  private var baseDriver
      : StateMachineDriver[Event, DriverAction, EtcdPreferredAssignerStateMachine] = _

  /** The proto logger for Assigner-specific logging events. */
  @GuardedBy("sec")
  private var assignerProtoLogger: AssignerProtoLogger = _

  override def sendTerminationNotice(): Unit = sec.run {
    iassert(baseDriver != null, "must not be called before start().")
    baseDriver.handleEvent(Event.TerminationNoticeReceived)
  }

  override def watch(callback: ValueStreamCallback[PreferredAssignerConfig]): Cancellable = {
    sec.run { iassert(baseDriver != null, "must not be called before start().") }
    preferredAssignerConfigWatchCell.watch(callback)
  }

  override def handleHeartbeatRequest(request: HeartbeatRequest): Future[HeartbeatResponse] =
    sec.flatCall {
      iassert(baseDriver != null, "must not be called before start().")
      // Inform the store of the preferred assigner value received (non-blocking).
      store.informPreferredAssigner(request.preferredAssignerValue)

      val responsePromise = Promise[HeartbeatResponse]()
      baseDriver.handleEvent(
        Event.HeartbeatRequestReceived(request, opaqueContext = responsePromise)
      )
      responsePromise.future
    }

  /** Starts the driver with the given Assigner info. */
  override def start(assignerInfo: AssignerInfo, assignerProtoLogger: AssignerProtoLogger): Unit =
    sec.run {
      this.assignerProtoLogger = assignerProtoLogger

      // Create and start the base driver.
      baseDriver = new StateMachineDriver[Event, DriverAction, EtcdPreferredAssignerStateMachine](
        sec,
        new EtcdPreferredAssignerStateMachine(assignerInfo, store.storeIncarnation, config),
        performAction
      )
      baseDriver.start()
      watchPreferredAssignerValueChanges()

      // Start the Kubernetes membership checker, recording a metric on both success and
      // failure so oncall can monitor rollout health.
      try {
        membershipCheckerFactory.create(assignerInfo, assignerProtoLogger) match {
          case Some(checker) =>
            checker.start()
            KubernetesMembershipChecker.recordInitSuccess()
          case None =>
          // Checker intentionally disabled by the factory.
        }
      } catch {
        case NonFatal(ex) =>
          KubernetesMembershipChecker.recordInitFailure()
          logger.warn(s"Failed to create KubernetesMembershipChecker: $ex")
      }
    }

  /**
   * Performs the heartbeat RPC call against the preferred assigner. Exposed as protected to allow
   * test subclasses to layer on test functionality such as recording the OpID.
   */
  protected def performHeartbeatCall(
      stub: PreferredAssignerServiceStub,
      heartbeatRequest: HeartbeatRequest): Future[HeartbeatResponseP] = {
    // withBackgroundActivity sets the context for accessing SAFE flags from an async task.
    // See https://docs.google.com/document/d/1bRnTScFpOZvYArGN3UzVMwlR51n8dvMNloy5pL_2-YU/edit
    // for details.
    withBackgroundActivity(onlyWarnOnAttrTagViolation = true, addUserContextTags = false) {
      _: Ctx =>
        // Set the deadline to be the same as the heartbeat interval.
        val deadline = Deadline.after(HEARTBEAT_RPC_TIMEOUT.toNanos, NANOSECONDS)
        stub.withDeadline(deadline).heartbeat(heartbeatRequest.toProto)
    }
  }

  private def performAction(action: DriverAction): Unit = {
    sec.assertCurrentContext()

    action match {
      case DriverAction.SendHeartbeat(heartbeatRequest: HeartbeatRequest) =>
        val preferredAssignerInfo: AssignerInfo =
          heartbeatRequest.preferredAssignerValue.assignerInfo
        startHeartbeatRequestRpc(heartbeatRequest, preferredAssignerInfo)
      case DriverAction.RespondToHeartbeat(
          response: Try[HeartbeatResponse],
          responsePromise: Promise[HeartbeatResponse]
          ) =>
        responsePromise.complete(response)
      case DriverAction.Write(startTime: TickerTime, proposal: PreferredAssignerProposal) =>
        logger.info(s"Writing preferred assigner proposal $proposal to store")
        store
          .write(proposal)
          .onComplete { result =>
            // Pass the full result (with start time for latency recording) to the state machine.
            baseDriver.handleEvent(Event.WriteResultReceived(startTime, result))
          }(sec)
      case DriverAction.UsePreferredAssignerConfig(
          updatedConfig: PreferredAssignerConfig
          ) =>
        preferredAssignerConfigWatchCell.setValue(updatedConfig)
        logger.info("The preferred assigner config has been updated to: " + updatedConfig)
    }
  }

  /**
   * Incorporates the new preferred assigner value if a new one is received, and updates the
   * `preferredAssignerConfig` accordingly.
   */
  private def onPreferredAssignerValueChange(
      preferredAssignerValue: PreferredAssignerValue): Unit = {
    sec.assertCurrentContext()
    logger.info("Received preferred assigner value: " + preferredAssignerValue)

    assignerProtoLogger.logPreferredAssignerChange(preferredAssignerValue)

    baseDriver.handleEvent(Event.PreferredAssignerReceived(preferredAssignerValue))
  }

  /** Watches the preferred assigner value changes in the store. */
  private def watchPreferredAssignerValueChanges(): Unit = {
    sec.assertCurrentContext()
    store.getPreferredAssignerWatchCell.watch(
      new ValueStreamCallback[PreferredAssignerValue](sec) {
        override def onSuccess(preferredAssignerValue: PreferredAssignerValue): Unit = {
          sec.assertCurrentContext()
          onPreferredAssignerValueChange(preferredAssignerValue)
        }
      }
    )
  }

  /** Starts a heartbeat request RPC. */
  private def startHeartbeatRequestRpc(
      heartbeatRequest: HeartbeatRequest,
      preferredAssignerInfo: AssignerInfo): Unit = {
    sec.assertCurrentContext()
    val address: URI = preferredAssignerInfo.uri

    // Get the stub for the preferred assigner address.
    val stub: PreferredAssignerServiceStub =
      if (heartbeatAddressAndStubOpt.isEmpty || heartbeatAddressAndStubOpt.get.address != address) {
        logger.info(s"Creating a heartbeat stub for address: $address")
        val heartbeatStub: PreferredAssignerServiceStub =
          preferredAssignerServerHelper.createStub(address)
        heartbeatAddressAndStubOpt = Some(HeartbeatAddressAndStub(address, heartbeatStub))
        heartbeatStub
      } else {
        heartbeatAddressAndStubOpt.get.stub
      }

    val opId: Long = heartbeatRequest.opId
    logger.info(s"Sending heartbeat (ID: $opId) to preferred assigner: $preferredAssignerInfo")
    performHeartbeatCall(stub, heartbeatRequest).onComplete {
      // TODO(<internal bug>) Handling should move into EtcdPreferredAssignerStateMachine.
      case Success(response: HeartbeatResponseP) =>
        logger.info(s"Successfully Received heartbeat (ID: $opId) response.")
        val heartbeatResponse = HeartbeatResponse.fromProto(response)
        baseDriver.handleEvent(
          Event.HeartbeatSuccess(response.getOpId, heartbeatResponse.preferredAssignerValue)
        )
      case Failure(exception: Throwable) =>
        logger.warn(s"Failed to send heartbeat (ID: $opId) to $preferredAssignerInfo: $exception")
    }(sec)
  }

}

object EtcdPreferredAssignerDriver {

  /** A class encapsulating the heartbeat address and its stub. */
  private case class HeartbeatAddressAndStub(address: URI, stub: PreferredAssignerServiceStub) {}

  private val logger = PrefixLogger.create(this.getClass, prefix = "dicer-preferred-assigner")

  /**
   * REQUIRES: `initialPreferredAssignerTimeout` is positive
   * REQUIRES: `heartbeatInterval` is positive
   * REQUIRES: `writeRetryInterval` is positive
   * REQUIRES: `heartbeatFailureThreshold` is positive
   *
   * The configuration of the preferred assigner state machine.
   *
   * @param initialPreferredAssignerTimeout how long to wait to learn of a preferred assigner during
   *                                        startup before assuming there is none.
   * @param heartbeatInterval               the interval between heartbeats. This is also the
   *                                        interval after which a heartbeat is considered to have
   *                                        failed. This is also used as the deadline for the
   *                                        heartbeat RPC call.
   * @param writeRetryInterval              the interval between preferred assigner write retries.
   * @param heartbeatFailureThreshold       The count of consecutive failed heartbeats at which the
   *                                        the preferred assigner is considered to be unhealthy. A
   *                                        standby tries to become preferred at this count and
   *                                        retries periodically as long is the failure count >= the
   *                                        threshold.
   */
  case class Config(
      initialPreferredAssignerTimeout: FiniteDuration = 10.seconds,
      heartbeatInterval: FiniteDuration = 5.seconds,
      writeRetryInterval: FiniteDuration = 10.seconds,
      heartbeatFailureThreshold: Int = 3
  ) {
    require(initialPreferredAssignerTimeout > Duration.Zero)
    require(heartbeatInterval > Duration.Zero)
    require(writeRetryInterval > Duration.Zero)
    require(heartbeatFailureThreshold > 0)
  }
}

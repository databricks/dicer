package com.databricks.dicer.assigner
import com.databricks.api.proto.dicer.assigner.{HeartbeatResponseP, PreferredAssignerServiceGrpc}
import com.databricks.caching.util.{PrefixLogger, SequentialExecutionContext}
import com.databricks.dicer.assigner.InterposingEtcdPreferredAssignerDriver.ShutdownOption
import com.databricks.rpc.tls.TLSOptions

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

/**
 * The interposing [[EtcdPreferredAssignerDriver]] which supports driver shutdown and heartbeat
 * opId tracking.
 */
class InterposingEtcdPreferredAssignerDriver(
    sec: SequentialExecutionContext,
    assignerTlsOptionsOpt: Option[TLSOptions],
    val store: InterposingEtcdPreferredAssignerStore,
    config: EtcdPreferredAssignerDriver.Config,
    membershipCheckerFactory: KubernetesMembershipChecker.Factory =
      new KubernetesMembershipChecker.Factory {
        override def create(
            assignerInfo: AssignerInfo,
            assignerProtoLogger: AssignerProtoLogger): Option[KubernetesMembershipChecker] = None
      }
) extends EtcdPreferredAssignerDriver(
      sec,
      assignerTlsOptionsOpt,
      store,
      config,
      membershipCheckerFactory
    ) {

  private val logger = PrefixLogger.create(getClass, "")

  /** Whether the driver has been shut down. */
  private var isShutdown: Boolean = false

  /**
   * The highest successful opId for a heartbeat request that has been responded to by
   * another assigner.
   */
  private var highestSucceededOpID: Long = 0

  /** Handles the heartbeat request if the driver hasn't been shutdown. */
  override def handleHeartbeatRequest(request: HeartbeatRequest): Future[HeartbeatResponse] =
    sec.flatCall {
      if (!isShutdown) {
        super.handleHeartbeatRequest(request)
      } else {
        // Return a future that will never complete, simulating a shutdown server that never
        // responds.
        Promise().future
      }
    }

  /** Sends the heartbeat request if the driver hasn't been shutdown. */
  override def performHeartbeatCall(
      stub: PreferredAssignerServiceGrpc.PreferredAssignerServiceStub,
      heartbeatRequest: HeartbeatRequest): Future[HeartbeatResponseP] = {
    if (!isShutdown) {
      val responseFut = super.performHeartbeatCall(stub, heartbeatRequest)
      responseFut.onComplete {
        case Success(responseP: HeartbeatResponseP) =>
          val response = HeartbeatResponse.fromProto(responseP)
          logger.info(s"Received heartbeat response $response from the preferred assigner")
          highestSucceededOpID = math.max(highestSucceededOpID, response.opId)
        case Failure(_) => // Do nothing
      }(sec)
      responseFut
    } else {
      // Return a future that will never complete, simulating a shutdown server that sends
      // heartbeats.
      Promise().future
    }
  }

  /** Gets the highest successful heartbeat `opId` that has ever sent to another assigner. */
  def getHighestSucceededOpID: Future[Long] = sec.call { highestSucceededOpID }

  /**
   * Shuts down the driver by stopping the underlying store and making the driver not send or
   * respond to heartbeat requests. `shutdownOption` controls the behavior of the shutdown (see
   * [[ShutdownOption]]).
   */
  def shutdown(shutdownOption: ShutdownOption): Future[Unit] = sec.call {
    isShutdown = true
    store.shutdown(shutdownOption)
  }
}
object InterposingEtcdPreferredAssignerDriver {

  /** Shutdown options for the InterposingEtcdPreferredAssignerDriver. */
  sealed trait ShutdownOption

  object ShutdownOption {

    /**
     * Abruptly shuts down the preferred assigner driver in a best-effort manner by cancelling the
     * etcd watches, blocking the store writes, and not responding to future heartbeat requests.
     * This can prevent the preferred assigner abdication write from being leaked to the following
     * tests.
     */
    case object ABRUPT extends ShutdownOption

    /**
     * Same as the `ABRUPT` option except this allows the preferred assigner to abdicate by writing
     * a `NoAssigner` value to the store.
     */
    case object ALLOW_ABDICATION extends ShutdownOption
  }
}

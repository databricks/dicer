package com.databricks.dicer.common

import java.net.URI
import scala.concurrent.Future
import scala.concurrent.duration._
import com.databricks.api.proto.dicer.common.ClientResponseP
import com.databricks.caching.util.{FakeSequentialExecutionContext, TestUtils}
import com.databricks.dicer.common.Assignment.AssignmentValueCell
import com.databricks.dicer.common.SubscriberHandler.Location
import com.databricks.dicer.external.Target
import com.databricks.rpc.RPCContext
import com.databricks.rpc.testing.JettyTestRPCContext

/**
 * Tests for the Scala implementation of SubscriberHandler, including both shared tests
 * (from [[SubscriberHandlerSuiteBase]]) and Scala-specific tests.
 */
class ScalaSubscriberHandlerSuite extends SubscriberHandlerSuiteBase {

  /** Scala-specific driver that wraps a FakeSequentialExecutionContext and SubscriberHandler. */
  private class ScalaDriver(
      sec: FakeSequentialExecutionContext,
      handler: SubscriberHandler,
      cell: AssignmentValueCell)
      extends SubscriberHandlerDriver {

    override def setAssignment(assignment: Assignment): Unit = {
      cell.setValue(assignment)
      drainSec()
    }

    override def handleWatch(
        request: ClientRequest,
        redirectOpt: Option[Redirect]): Future[ClientResponseP] = {
      val redirect = redirectOpt.getOrElse(Redirect.EMPTY)
      handler.handleWatch(createRPCContext(), request, cell, redirect)
    }

    /** Blocks until all pending commands on [[sec]] have drained. */
    private def drainSec(): Unit = TestUtils.awaitResult(sec.call {}, Duration.Inf)

    /** Creates a dummy RPC for use in tests. */
    private def createRPCContext(): RPCContext = {
      JettyTestRPCContext
        .builder()
        .method("POST")
        .uri("/")
        .build()
    }
  }

  override protected def createDriver(
      handlerLocation: Location,
      handlerTarget: Target): SubscriberHandlerDriver = {
    val sec = FakeSequentialExecutionContext.create(getSafeName)
    val cell = new AssignmentValueCell
    val handler = new SubscriberHandler(
      sec,
      handlerTarget,
      getSuggestedClerkRpcTimeoutFn = () => TIMEOUT,
      suggestedSliceletRpcTimeout = TIMEOUT,
      handlerLocation
    )
    new ScalaDriver(sec, handler, cell)
  }

  test("Non-empty Redirect") {
    // Test plan: Verify that the subscriber handler responds to watch requests with the same
    // redirect address that was passed to `handleWatch`, regardless of what request was sent.
    // NOTE: this behavior is Assigner-specific and used to support preferred Assigner redirection.
    val driver = createDriver(Location.Assigner, target)

    // Set an assignment so there is something to watch.
    val assignment1: Assignment = createRandomAssignment(
      generation = Generation(Incarnation(1), 42),
      Vector("pod0", "pod1")
    )
    driver.setAssignment(assignment1)

    val request = createClientRequest(Generation.EMPTY, ClerkData, "subscriber1")
    val redirectURI = URI.create("example-uri")
    val fut: Future[ClientResponseP] =
      driver.handleWatch(request, redirectOpt = Some(Redirect(Some(redirectURI))))

    val response = ClientResponse.fromProto(TestUtils.awaitResult(fut, Duration.Inf))
    assert(response.redirect.addressOpt.get == redirectURI)
  }
}

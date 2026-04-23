package com.databricks.instrumentation.test

import com.databricks.instrumentation._
import com.databricks.testing.DatabricksTest
import org.scalatest.concurrent.Eventually._

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.locks.ReentrantLock

import com.databricks.HasDebugString
import com.databricks.common.status.{ProbeStatus, ProbeStatusSource, ProbeStatuses}
import com.databricks.common.util.Lock
import com.databricks.common.web.InfoService
import com.databricks.rpc.armeria.ReadinessProbeTracker

class InfoServiceSuite extends DatabricksTest {

  /** A map from URI to expected content. */
  private val expectedContent: Map[String, String] = Map(
    "" -> "Admin Debug", // Root page
    "/tracez" -> "TraceZ",
    "/statsz" -> "StatsZ",
    "/rpcz" -> "RPC Stats"
  )

  /** A ProbeStatusSource whose status can be changed between test cases. */
  private val readinessSource: TestProbeStatusSource = new TestProbeStatusSource

  /**
   * A ProbeStatusSource whose status can be changed at any time for testing. Thread-safe via
   * volatile read/write.
   */
  private class TestProbeStatusSource extends ProbeStatusSource {

    @volatile private var currentStatus: ProbeStatus = ProbeStatuses.ok("default")

    /** Updates the status that will be returned by subsequent calls to [[getStatus]]. */
    def setStatus(status: ProbeStatus): Unit = {
      currentStatus = status
    }

    override def getStatus: ProbeStatus = currentStatus
  }

  /** Makes an HTTP GET request and returns the response body as a string. Asserts status 200. */
  private def makeHttpRequest(url: String): String = {
    val (statusCode, body): (Int, String) = makeHttpRequestRaw(url)
    assert(statusCode == 200, s"Request to $url failed with status: $statusCode")
    body
  }

  /** Makes an HTTP GET request and returns the (status code, body) pair without assertions. */
  private def makeHttpRequestRaw(url: String): (Int, String) = {
    val client: java.net.http.HttpClient = java.net.http.HttpClient.newHttpClient()
    val request: java.net.http.HttpRequest = java.net.http.HttpRequest
      .newBuilder()
      .uri(java.net.URI.create(url))
      .timeout(java.time.Duration.ofSeconds(5))
      .GET()
      .build()

    val response: java.net.http.HttpResponse[String] =
      client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
    (response.statusCode(), response.body())
  }

  /** Makes an HTTP POST request with form data and returns the response body as a string. */
  private def makeHttpPostRequest(url: String, postData: String): String = {
    val client: java.net.http.HttpClient = java.net.http.HttpClient.newHttpClient()
    val request: java.net.http.HttpRequest = java.net.http.HttpRequest
      .newBuilder()
      .uri(java.net.URI.create(url))
      .timeout(java.time.Duration.ofSeconds(5))
      .header("Content-Type", "application/x-www-form-urlencoded")
      .POST(java.net.http.HttpRequest.BodyPublishers.ofString(postData))
      .build()

    val response: java.net.http.HttpResponse[String] =
      client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
    val responseCode: Int = response.statusCode()
    assert(responseCode == 200, s"POST request to $url failed with status: $responseCode")
    response.body()
  }

  override def beforeAll(): Unit = {
    // Verify that before the server is started, the port is 0.
    assert(InfoService.getActivePort() == 0)
    ReadinessProbeTracker.resetForTesting()
    InfoService.start(port = 0, readinessStatusSourceOpt = Some(readinessSource))
  }

  override def afterAll(): Unit = {
    InfoService.stop(delaySeconds = 0)
    assert(InfoService.getActivePort() == 0)
    // Verify that a stopped server can be restarted.
    InfoService.start(port = 0)
    assert(InfoService.getActivePort() != 0)
    InfoService.stop(delaySeconds = 0)
    assert(InfoService.getActivePort() == 0)
    ReadinessProbeTracker.resetForTesting()
  }

  test("ZPages Server content") {
    // Test plan: makes HTTP requests to each expected endpoint, and asserts that the content
    // contains the expected text.

    // Verify that we can't start a started server.
    assertThrows[IllegalStateException] {
      InfoService.start(port = 0)
    }

    // The server is still running from beforeAll(); the exception above was from the double-start.
    val port: Int = InfoService.getActivePort()
    val baseUrl: String = s"http://localhost:$port"

    // Test each expected endpoint.
    eventually {
      for ((uri, expectedText) <- expectedContent) {
        val url: String = if (uri.isEmpty) baseUrl else s"$baseUrl$uri"

        val content: String = makeHttpRequest(url)
        assert(
          content.contains(expectedText),
          s"Response from $url does not contain '$expectedText'. Got: ${content.take(200)}..."
        )
      }
    }
  }

  test("Dynamically add components to the debugging page") {
    // Test plan: verify that the debugging page starts empty, and we can add components to it.
    val port: Int = InfoService.getActivePort()
    val baseUrl: String = s"http://localhost:$port"

    val content: String = makeHttpRequest(s"$baseUrl/admin/debug")
    assert(content.contains("No components registered"))

    val component1 = new HasDebugString {
      override def debugHtml: String = "This-is-a-test-component"
    }

    val component2 = new HasDebugString {
      override def debugHtml: String = "This-is-another-test-component"
    }

    DebugStringServletRegistry.registerComponent("test", component1)
    DebugStringServletRegistry.registerComponent("test2", component2)

    eventually {
      val content2: String = makeHttpRequest(s"$baseUrl/admin/debug")
      assert(content2.contains("This-is-a-test-component"))
      assert(content2.contains("This-is-another-test-component"))
    }
  }

  test("Register action with makeActionInput form submission") {
    // Test plan: register an action, create an input form, submit it, and verify action is
    // executed.
    val port: Int = InfoService.getActivePort()
    val baseUrl: String = s"http://localhost:$port"

    // State to track if action was executed.
    val lock = new ReentrantLock()
    var actionExecuted = false // Guarded by lock.
    var actionArgs: Map[String, String] = Map.empty // Guarded by lock.

    // Register an action that sets the state when executed.
    val testAction: DebugStringServletRegistry.Action = (args: Map[String, String]) => {
      Lock.withLock(lock) {
        actionExecuted = true
        actionArgs = args
      }
    }
    DebugStringServletRegistry.registerAction("testInputAction", testAction, "POST")

    // Create a component with an action input form.
    val component = new HasDebugString {
      override def debugHtml: String = {
        DebugStringServlet.makeActionInput("testInputAction", Map("key1" -> "value1"), "Test form")
      }
    }
    DebugStringServletRegistry.registerComponent("actionTestComponent", component)

    // Get the debug page to see the form.
    val debugContent: String =
      makeHttpRequest(s"$baseUrl/admin/debug?component=actionTestComponent")
    assert(debugContent.contains("testInputAction"))
    assert(debugContent.contains("Test form"))

    // Simulate form submission by making POST request with action parameters in query string.
    val jsonArgs = """{"key1":"value1"}"""
    val encodedArgs: String = URLEncoder.encode(jsonArgs, StandardCharsets.UTF_8.name())
    val actionUrl: String =
      s"$baseUrl/admin/debug?action=testInputAction&args=$encodedArgs&userInput=testValue"
    makeHttpPostRequest(actionUrl, "")

    // Verify the action was executed with correct parameters.
    eventually {
      Lock.withLock(lock) {
        assert(actionExecuted, "Action should have been executed")
        assert(actionArgs.contains("key1"))
        assert(actionArgs("key1") == "value1")
        assert(actionArgs.contains("userInput"))
        assert(actionArgs("userInput") == "testValue")
      }
    }
  }

  test("Register action with makeActionLink") {
    // Test plan: register an action, create an action link, trigger it, and verify action is
    // executed.
    val port: Int = InfoService.getActivePort()
    val baseUrl: String = s"http://localhost:$port"

    // State to track if action was executed.
    val linkLock = new ReentrantLock()
    var linkActionExecuted = false // Guarded by lock.
    var linkActionArgs: Map[String, String] = Map.empty // Guarded by lock.

    // Register an action that sets the state when executed.
    val linkAction: DebugStringServletRegistry.Action = (args: Map[String, String]) => {
      Lock.withLock(linkLock) {
        linkActionExecuted = true
        linkActionArgs = args
      }
    }
    DebugStringServletRegistry.registerAction("testLinkAction", linkAction, "GET")

    // Create a component with an action link.
    val linkComponent = new HasDebugString {
      override def debugHtml: String = {
        DebugStringServlet.makeActionLink(
          "testLinkAction",
          Map("linkKey" -> "linkValue"),
          useGet = true
        )
      }
    }
    DebugStringServletRegistry.registerComponent("linkTestComponent", linkComponent)

    // Get the debug page to see the link.
    val debugContent: String = makeHttpRequest(s"$baseUrl/admin/debug?component=linkTestComponent")
    assert(debugContent.contains("testLinkAction"))

    // Simulate clicking the link by making GET request with action parameters.
    val jsonArgs = """{"linkKey":"linkValue"}"""
    val encodedArgs: String = URLEncoder.encode(jsonArgs, StandardCharsets.UTF_8.name())
    val actionUrl: String = s"$baseUrl/admin/debug?action=testLinkAction&args=$encodedArgs"
    makeHttpRequest(actionUrl)

    // Verify the action was executed with correct parameters.
    eventually {
      Lock.withLock(linkLock) {
        assert(linkActionExecuted, "Link action should have been executed")
        assert(linkActionArgs.contains("linkKey"))
        assert(linkActionArgs("linkKey") == "linkValue")
      }
    }
  }

  test("ready endpoint returns OK when source reports ready") {
    // Test plan: Configure the controllable source to report OK, hit the /ready endpoint, and
    // verify the response has a 200 status code with the expected body text.
    readinessSource.setStatus(ProbeStatuses.ok("TestService"))

    val port: Int = InfoService.getActivePort()
    val (statusCode, body): (Int, String) = makeHttpRequestRaw(s"http://localhost:$port/ready")

    assert(statusCode == ProbeStatuses.OK_STATUS)
    assert(body.contains("TestService is available"))
  }

  test("ready endpoint returns not-yet-ready when source reports not ready") {
    // Test plan: Configure the controllable source to report NOT_YET_READY, hit the /ready
    // endpoint, and verify the response has a 418 status code with the expected body text.
    readinessSource.setStatus(ProbeStatuses.notYetReady("TestService"))

    val port: Int = InfoService.getActivePort()
    val (statusCode, body): (Int, String) = makeHttpRequestRaw(s"http://localhost:$port/ready")

    assert(statusCode == ProbeStatuses.NOT_YET_READY_STATUS)
    assert(body.contains("TestService has not yet been initialized"))
  }

  test("ready endpoint updates ReadinessProbeTracker") {
    // Test plan: Set the source to NOT_YET_READY, hit /ready, and verify that
    // ReadinessProbeTracker reflects pod-not-ready state. Then switch to OK, hit /ready again,
    // and verify the tracker transitions to ready.
    ReadinessProbeTracker.resetForTesting()
    assert(ReadinessProbeTracker.isPodReady, "precondition: tracker starts ready")

    val port: Int = InfoService.getActivePort()

    // Transition to not-ready.
    readinessSource.setStatus(ProbeStatuses.notYetReady("TestService"))
    makeHttpRequestRaw(s"http://localhost:$port/ready")
    assert(!ReadinessProbeTracker.isPodReady)

    // Transition back to ready.
    readinessSource.setStatus(ProbeStatuses.ok("TestService"))
    makeHttpRequestRaw(s"http://localhost:$port/ready")
    assert(ReadinessProbeTracker.isPodReady)
  }

}

package com.databricks.instrumentation.test

import com.databricks.instrumentation._
import com.databricks.testing.DatabricksTest
import org.scalatest.concurrent.Eventually._

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.locks.ReentrantLock

import com.databricks.HasDebugString
import com.databricks.common.util.Lock

class DebugHttpServerSuite extends DatabricksTest {

  /** A map from URI to expected content */
  private val expectedContent: Map[String, String] = Map(
    "" -> "Admin Debug", // Root page
    "/tracez" -> "TraceZ",
    "/statsz" -> "StatsZ",
    "/rpcz" -> "RPC Stats"
  )

  override def beforeAll(): Unit = {
    // Verify that before the server is started, the port is None.
    assert(DebugHttpServer.getPort.isEmpty)
    DebugHttpServer.start()
  }

  override def afterAll(): Unit = {
    DebugHttpServer.stop(0)
    // Verify that we can't start a stopped server.
    assertThrows[IllegalStateException] {
      DebugHttpServer.start()
    }
  }

  test("ZPages Server content") {
    // Test plan: makes HTTP requests to each expected endpoint, and asserts that the content
    // contains the expected text.

    // Verify that we can't start a started server.
    assertThrows[IllegalStateException] {
      DebugHttpServer.start()
    }

    val port: Int = DebugHttpServer.getPort.get
    val baseUrl: String = s"http://localhost:$port"

    // Test each expected endpoint
    eventually {
      for ((uri, expectedText) <- expectedContent) {
        val url = if (uri.isEmpty) baseUrl else s"$baseUrl$uri"

        val content = makeHttpRequest(url)
        assert(
          content.contains(expectedText),
          s"Response from $url does not contain '$expectedText'. Got: ${content.take(200)}..."
        )
      }
    }
  }

  test("Dynamically add components to the debugging page") {
    // Test plan: verify that the debugging page starts empty, and we can add components to it.
    val port: Int = DebugHttpServer.getPort.get
    val baseUrl: String = s"http://localhost:$port"

    val content = makeHttpRequest(s"$baseUrl/debug")
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
      val content2 = makeHttpRequest(s"$baseUrl/debug")
      assert(content2.contains("This-is-a-test-component"))
      assert(content2.contains("This-is-another-test-component"))
    }
  }

  test("Register action with makeActionInput form submission") {
    // Test plan: register an action, create an input form, submit it, and verify action is
    // executed.
    val port: Int = DebugHttpServer.getPort.get
    val baseUrl: String = s"http://localhost:$port"

    // State to track if action was executed.
    val lock = new ReentrantLock()
    var actionExecuted = false // Guarded by lock.
    var actionArgs: Map[String, String] = Map.empty // Guarded by lock.

    // Register an action that sets the state when executed
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
    val debugContent = makeHttpRequest(s"$baseUrl/debug?component=actionTestComponent")
    assert(debugContent.contains("testInputAction"))
    assert(debugContent.contains("Test form"))

    // Simulate form submission by making POST request with action parameters in query string.
    val jsonArgs = """{"key1":"value1"}"""
    val encodedArgs = URLEncoder.encode(jsonArgs, StandardCharsets.UTF_8.name())
    val actionUrl = s"$baseUrl/debug?action=testInputAction&args=$encodedArgs&userInput=testValue"
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
    val port: Int = DebugHttpServer.getPort.get
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

    // Create a component with an action link
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

    // Get the debug page to see the link
    val debugContent = makeHttpRequest(s"$baseUrl/debug?component=linkTestComponent")
    assert(debugContent.contains("testLinkAction"))

    // Simulate clicking the link by making GET request with action parameters.
    val jsonArgs = """{"linkKey":"linkValue"}"""
    val encodedArgs = URLEncoder.encode(jsonArgs, StandardCharsets.UTF_8.name())
    val actionUrl = s"$baseUrl/debug?action=testLinkAction&args=$encodedArgs"
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

  /** Makes an HTTP GET request and returns the response body as a string. */
  private def makeHttpRequest(url: String): String = {
    val client = java.net.http.HttpClient.newHttpClient()
    val request = java.net.http.HttpRequest
      .newBuilder()
      .uri(java.net.URI.create(url))
      .timeout(java.time.Duration.ofSeconds(5))
      .GET()
      .build()

    val response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
    val responseCode = response.statusCode()
    assert(responseCode == 200, s"Request to $url failed with status: $responseCode")
    response.body()
  }

  /** Makes an HTTP POST request with form data and returns the response body as a string. */
  private def makeHttpPostRequest(url: String, postData: String): String = {
    val client = java.net.http.HttpClient.newHttpClient()
    val request = java.net.http.HttpRequest
      .newBuilder()
      .uri(java.net.URI.create(url))
      .timeout(java.time.Duration.ofSeconds(5))
      .header("Content-Type", "application/x-www-form-urlencoded")
      .POST(java.net.http.HttpRequest.BodyPublishers.ofString(postData))
      .build()

    val response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
    val responseCode = response.statusCode()
    assert(responseCode == 200, s"POST request to $url failed with status: $responseCode")
    response.body()
  }
}

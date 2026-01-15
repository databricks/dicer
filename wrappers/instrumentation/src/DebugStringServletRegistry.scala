package com.databricks.instrumentation

import com.databricks.HasDebugString
import com.sun.net.httpserver.{HttpExchange, HttpHandler}

import javax.annotation.concurrent.ThreadSafe
import scala.util.control.NonFatal
import scala.collection.JavaConverters._
import scalatags.Text.all._
import scalatags.Text.TypedTag

import java.net.{URLDecoder, URLEncoder}
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * The global registry that allows dynamically adding components to the debugging page. This is
 * implemented as a singleton to align with the internal codebase, allowing callers to register
 * custom debugging pages in a same way as the internal codebase does.
 */
@ThreadSafe
object DebugStringServletRegistry extends HttpHandler {

  /**
   * Action that may be taken when someone clicks a link.
   * Takes a map of parameters where keys are parameter names and values are parameter values.
   */
  type Action = Map[String, String] => Unit
  type ActionWithResult = Action // We don't use it but keep it for compatibility
  case class ActionKey(actionName: String, httpMethod: String)

  /** The URL path for the debugging page. */
  val DEBUG_URL_PATH: String = "/debug"

  // NOTE: it's not a good practice to make the `actions` and `components` public, but it's done
  // here for compatibility with the internal codebase.
  /** The map of registered actions. */
  val actions: ConcurrentHashMap[ActionKey, ActionWithResult] =
    new ConcurrentHashMap[ActionKey, ActionWithResult]()

  /** Flag to control whether debug actions are enabled. Should only be true in development. */
  @volatile private var debugActionsEnabled: Boolean = false

  /**
   * Enables or disables debug actions. This should only be called during initialization in
   * controlled environments (e.g., development/testing).
   */
  def setDebugActionsEnabled(enabled: Boolean): Unit = {
    debugActionsEnabled = enabled
  }

  /** The map of component names to their debug string. */
  val components: ConcurrentHashMap[String, HasDebugString] =
    new ConcurrentHashMap[String, HasDebugString]()

  /**
   * Registers a component for debug display.
   *
   * @param name The display name for this component
   * @param component The component that provides debug information
   */
  def registerComponent(name: String, component: HasDebugString): Unit = {
    components.put(name, component)
  }

  /**
   * Registers an action that can be executed from the debugging page.
   *
   * @param name The name of the action
   * @param action The function to execute when the action is triggered
   * @param method The HTTP method to use for this action
   */
  def registerAction(name: String, action: Action, method: String): Unit = {
    val ac: Action = (args: Map[String, String]) => { action(args) }
    actions.put(ActionKey(name, method), ac)
  }

  /** Handles the HTTP request by displaying all registered components. */
  def handle(exchange: HttpExchange): Unit = {
    try {
      // Check if the request is for an action
      val requestMethod: String = exchange.getRequestMethod
      val queryParams: String = Option(exchange.getRequestURI.getQuery).getOrElse("")
      val queryMap: Map[String, String] = parseQueryString(queryParams)

      val actionNameOpt: Option[String] = queryMap.get(QueryParam.ACTION)
      val componentOpt: Option[String] = queryMap.get(QueryParam.COMPONENT)

      // Authenticate and authorize before rendering debug page or executing actions
      if (!isAuthorizedRequest(exchange)) {
        exchange.sendResponseHeaders(401, 0)
        val errorMessage = "Unauthorized: Authentication required to access debug information."
        exchange.getResponseBody.write(errorMessage.getBytes("UTF-8"))
        exchange.getResponseBody.close()
        return
      }

      if (actionNameOpt.isDefined) {
        // Verify that debug actions are explicitly enabled before allowing execution
        if (!debugActionsEnabled) {
          exchange.sendResponseHeaders(403, 0)
          val errorMessage = "Forbidden: Debug actions are not enabled."
          exchange.getResponseBody.write(errorMessage.getBytes("UTF-8"))
          exchange.getResponseBody.close()
          return
        }
        val userInputOpt: Option[String] = queryMap.get(QueryParam.USER_INPUT)
        val argsOpt: Option[String] = queryMap.get(QueryParam.ARGS)
        performAction(requestMethod, actionNameOpt.get, userInputOpt, argsOpt)
      }
      renderDebugPage(exchange, componentOpt)
    } catch {
      case NonFatal(e) =>
        exchange.sendResponseHeaders(500, 0)
        val errorMessage = s"Error processing request: ${e.getMessage}"
        exchange.getResponseBody.write(errorMessage.getBytes("UTF-8"))
        exchange.getResponseBody.close()
    }
  }

  /** Checks if the request is authorized to execute debug actions. */
  private def isAuthorizedRequest(exchange: HttpExchange): Boolean = {
    // Check if request is from localhost only (most secure for debug server)
    val remoteAddress = exchange.getRemoteAddress
    if (remoteAddress != null) {
      val hostAddress = remoteAddress.getAddress.getHostAddress
      // Only allow localhost connections for debug actions
      hostAddress == "127.0.0.1" || hostAddress == "::1" || hostAddress == "localhost"
    } else {
      false
    }
  }

  /** Renders the debugging page with all registered components or a specific component. */
  def renderDebugPage(exchange: HttpExchange, componentOpt: Option[String]): Unit = {
    val content: TypedTag[String] =
      componentOpt match {
        case Some(component) =>
          // A specific component was requested.
          components.asScala.get(component) match {
            case Some(debugString) =>
              div(
                a(href := DEBUG_URL_PATH)("Back to all components"),
                h2(s"Debugging Component: $component"),
                div(raw(debugString.debugHtml))
              )
            case None =>
              div(
                a(href := DEBUG_URL_PATH)("Back to all components"),
                h2(s"Component $component not found.")
              )
          }
        case None =>
          // Show all registered components (or a helpful message if none are registered).
          if (components.isEmpty) {
            div(h3("No components registered"))
          } else {
            div(
              components.asScala.toSeq
                .sortBy(_._1)
                .map {
                  case (component, content) =>
                    div(
                      h2(
                        a(
                          cls := "componentLink",
                          href := s"?component=${URLEncoder.encode(
                            component,
                            StandardCharsets.UTF_8.name()
                          )}"
                        )(component)
                      ),
                      div(raw(content.debugHtml))
                    )
                }
                .toSeq: _*
            )
          }
      }

    val pageContent: TypedTag[String] = html(
      head(tag("title")("Debugging Page"), meta(charset := "UTF-8")),
      body(content)
    )

    val htmlBytes: Array[Byte] = ("<!DOCTYPE html>\n" + pageContent.render).getBytes("UTF-8")

    // Set response headers and write the HTML response.
    exchange.getResponseHeaders.set("Content-Type", "text/html; charset=UTF-8")
    exchange.sendResponseHeaders(200, htmlBytes.length)

    val outputStream = exchange.getResponseBody
    outputStream.write(htmlBytes)
    outputStream.close()
  }

  /** Parses the query string from the HTTP request. */
  private def parseQueryString(query: String): Map[String, String] = {
    if (query.isEmpty) Map.empty
    else {
      val stringSplit: Array[String] = query.split("&") // Split by '&' to get key-value pairs
      stringSplit.map { pair: String =>
        val parts: Array[String] = pair.split("=", 2)
        if (parts.length == 2) {
          val key = URLDecoder.decode(parts(0), StandardCharsets.UTF_8.name())
          val value = URLDecoder.decode(parts(1), StandardCharsets.UTF_8.name())
          key -> value
        } else {
          // If no '=' found, treat the whole string as a key with empty value
          URLDecoder.decode(parts(0), StandardCharsets.UTF_8.name()) -> ""
        }
      }.toMap
    }
  }

  /** Performs an action and returns the response if the action name is registered. */
  private def performAction(
      httpMethod: String,
      actionName: String,
      userInput: Option[String],
      argsInput: Option[String]): Unit = {
    val args: Option[Map[String, String]] = argsInput
      .map(a => jsonToMap(URLDecoder.decode(a, StandardCharsets.UTF_8.name())))
      .map(a => a + ("userInput" -> userInput.getOrElse("")))
    val actionKey = ActionKey(actionName, httpMethod)
    actions.asScala.get(actionKey) match {
      case Some(callback) => callback(args.getOrElse(Map.empty))
      case None =>
        throw new IllegalArgumentException(
          s"Action '$actionName' with method '$httpMethod' is not registered."
        )
    }
  }

  /** Converts a JSON string to a Map. */
  private def jsonToMap(json: String): Map[String, String] = {
    com.databricks.rpc.DatabricksObjectMapper.fromJson[Map[String, String]](json)
  }

}

object DebugStringServlet {

  /**
   * Creates an anchor link for an action that can be clicked to trigger a POST/GET request.
   * Defaults to POST.
   */
  def makeActionLink(
      actionDescription: String,
      args: Map[String, String],
      useGet: Boolean = false): String = {
    val argStr = URLEncoder.encode(mapToJson(args), StandardCharsets.UTF_8)
    val anchorClass = if (useGet) "get-anchor-tag" else "post-anchor-tag"
    a(
      href := "#",
      cls := anchorClass,
      data("action") := actionDescription,
      data("args") := argStr
    )(actionDescription).render
  }

  /** Creates a form for submitting an action with user input. Defaults to POST. */
  def makeActionInput(
      actionDescription: String,
      args: Map[String, String],
      annotation: String = "",
      useGet: Boolean = false): String = {
    val argsStr = URLEncoder.encode(mapToJson(args), StandardCharsets.UTF_8)
    val formClass: String = if (useGet) "get-action-input-form" else "post-action-input-form"
    form(cls := formClass)(
      p(style := "display: inline-block")(actionDescription),
      input(`type` := "hidden", name := QueryParam.ACTION, value := actionDescription),
      input(`type` := "hidden", name := QueryParam.ARGS, value := argsStr),
      input(`type` := "text", name := QueryParam.USER_INPUT, style := "width: 400px;"),
      input(`type` := "submit", value := "Submit"),
      p(style := "display: inline")(s" $annotation ")
    ).render
  }

  /** Converts a map to a JSON string. */
  private def mapToJson(m: Map[String, String]): String = {
    com.databricks.rpc.DatabricksObjectMapper.toJson(m)
  }
}

/** Constants for query parameters used in the debugging page. */
private object QueryParam {
  val ACTION: String = "action"
  val ARGS: String = "args"
  val USER_INPUT: String = "userInput"
  val COMPONENT: String = "component"
}

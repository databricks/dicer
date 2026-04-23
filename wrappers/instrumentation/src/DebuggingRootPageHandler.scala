package com.databricks.instrumentation

import com.sun.net.httpserver.{HttpExchange, HttpHandler}

import java.io.OutputStream

import scalatags.Text.all._
import scalatags.Text.TypedTag

/** Handles the root page for the local debugging HTTP server. */
private[databricks] class DebuggingRootPageHandler(dashboardTitle: String) extends HttpHandler {

  /** Handles the HTTP request. */
  def handle(exchange: HttpExchange): Unit = {
    try {
      val dashboardHtml = generateDashboardHtml()

      exchange.getResponseHeaders.set("Content-Type", "text/html; charset=UTF-8")
      exchange.sendResponseHeaders(200, dashboardHtml.getBytes.length)

      val os: OutputStream = exchange.getResponseBody
      os.write(dashboardHtml.getBytes)
      os.close()
    } catch {
      case e: Exception =>
        val errorHtml =
          s"<html><body><h1>Error generating dashboard</h1><p>${e.getMessage}</p></body></html>"
        exchange.sendResponseHeaders(500, errorHtml.getBytes.length)
        val os: OutputStream = exchange.getResponseBody
        os.write(errorHtml.getBytes)
        os.close()
    }
  }

  /** Generates the HTML for the root page. */
  private def generateDashboardHtml(): String = {
    val titleTag: TypedTag[String] = tag("title")(dashboardTitle)
    val styleTag: TypedTag[String] = tag("style")(
      """|body { font-family: Arial, sans-serif; margin: 40px; }
         |h1 { color: #333; }
         |.links { margin: 30px 0; }
         |.link {
         |  display: block;
         |  margin: 15px 0;
         |  padding: 10px;
         |  background: #f0f0f0;
         |  border: 1px solid #ccc;
         |  text-decoration: none;
         |  color: #333;
         |}
         |.link:hover { background: #e0e0e0; }
         |.time { margin-top: 30px; font-size: 18px; }
         |""".stripMargin
    )

    val pageContent = html(
      head(
        titleTag,
        styleTag,
        meta(charset := "UTF-8")
      ),
      body(
        h1("Dicer Debugging Dashboard"),
        div(cls := "links")(
          a(href := DebugStringServletRegistry.DEBUG_URL_PATH, cls := "link")("Admin Debug"),
          a(href := "/tracez", cls := "link")("TraceZ - View traces"),
          a(href := "/statsz", cls := "link")("StatsZ - View statistics"),
          a(href := "/rpcz", cls := "link")("RpcZ - View RPC stats")
        )
      )
    )

    "<!DOCTYPE html>\n" + pageContent.render
  }

}

package com.databricks.common.web

import java.io.OutputStream

import com.sun.net.httpserver.{HttpExchange, HttpHandler}

import com.databricks.common.status.{ProbeStatus, ProbeStatusSource}

/** HTTP handler that returns readiness status. */
private[web] class ReadinessHandler(source: ProbeStatusSource) extends HttpHandler {
  override def handle(exchange: HttpExchange): Unit = {
    val status: ProbeStatus = source.getStatus
    val body: Array[Byte] = status.content.getBytes("UTF-8")
    exchange.sendResponseHeaders(status.code, body.length)
    val os: OutputStream = exchange.getResponseBody
    os.write(body)
    os.close()
  }
}

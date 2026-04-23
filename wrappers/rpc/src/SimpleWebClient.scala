package com.databricks.web

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

/**
 * Open source version of internal web client. Only supports HTTP GET, which is all that is needed
 * for readiness probe testing.
 */
object SimpleWebClient {
  private lazy val singletonClient: WebClient = new WebClient()
  def webClient(): WebClient = singletonClient
}

/** Minimal HTTP client wrapping [[java.net.http.HttpClient]]. */
class WebClient {
  private val client: HttpClient = HttpClient.newHttpClient()

  /** Sends a synchronous HTTP GET request to the given `uri`. */
  def get(uri: String): HttpResponseFuture = {
    val request: HttpRequest = HttpRequest.newBuilder(URI.create(uri)).GET().build()
    client.send(request, HttpResponse.BodyHandlers.discarding())
    new HttpResponseFuture()
  }
}

/** No-op placeholder for an HttpResponse future that is always completed synchronously. */
class HttpResponseFuture {
  def aggregate(): HttpResponseFuture = this
}

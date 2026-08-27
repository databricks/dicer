package com.databricks.rpc

/** Dummy implementation of request headers in OSS. */
class RequestHeaders {
  def contains(header: String): Boolean = false
}

object RequestHeaders {
  def builder(): RequestHeadersBuilder = new RequestHeadersBuilder()
}

/**
 * No-op implementation of a request headers builder; exists purely for compatibility with
 * internal APIs.
 */
class RequestHeadersBuilder {
  def method(method: HttpMethod): RequestHeadersBuilder = this
  def path(path: String): RequestHeadersBuilder = this
  def add(headerName: String, headerValue: String): RequestHeadersBuilder = this
  def build(): RequestHeaders = new RequestHeaders()
}

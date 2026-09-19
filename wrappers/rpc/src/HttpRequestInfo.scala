package com.databricks.common.http

/** Provides a read-only view for the HTTP request. Dummy implementation in OSS. */
class HttpRequestInfo {
  def getHeader(name: String): Option[String] = None
  def getHeaderNames: Set[String] = Set.empty
  def getMethod: String = "POST"
  def getRemoteAddr: String = ""
  def getRequestURI: String = "/"
}

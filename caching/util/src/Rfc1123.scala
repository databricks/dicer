package com.databricks.caching.util

import scala.util.matching.Regex

/** Utilities for validating RFC 1123 conformity. */
object Rfc1123 {

  /** The regex for RFC 1123 conformant strings. */
  val REGEX: Regex = """[0-9a-z]([0-9-a-z]{0,61}[0-9a-z])?""".r

  /** Returns whether the given string conforms to RFC 1123. */
  def isValid(s: String): Boolean = REGEX.pattern.matcher(s).matches()
}

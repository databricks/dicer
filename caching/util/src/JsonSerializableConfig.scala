package com.databricks.caching.util

/** A trait for objects that can be serialized as JSON strings. */
trait JsonSerializableConfig {
  def toJsonString: String
}

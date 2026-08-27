package com.databricks.rpc

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper

/** Provides JSON serialization and deserialization with support for Scala types. */
final class DatabricksObjectMapper private (delegate: ObjectMapper with ScalaObjectMapper) {

  /** Deserializes JSON string to a Scala type. */
  def readValue[T: Manifest](json: String): T = {
    delegate.readValue[T](json)
  }

  /** Serializes a Scala object to JSON string. */
  def writeValueAsString[T](obj: T): String = {
    delegate.writeValueAsString(obj)
  }
}

object DatabricksObjectMapper {

  /** Singleton instance of the configured ObjectMapper. */
  val mapper = new DatabricksObjectMapper({
    val objectMapper = new ObjectMapper with ScalaObjectMapper
    objectMapper.registerModule(DefaultScalaModule)
    objectMapper
  })

  /** Serializes a Scala object to JSON string. */
  def toJson[T](obj: T): String = {
    mapper.writeValueAsString(obj)
  }

  /** Deserializes JSON string to a Scala type. */
  def fromJson[T: Manifest](json: String): T = {
    mapper.readValue[T](json)
  }
}

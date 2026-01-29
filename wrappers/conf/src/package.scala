package com.databricks

import scala.collection.JavaConverters._

import com.typesafe.config.ConfigFactory

/**
 * Provides config management methods. This is a thin wrapper around the Typesafe Config library,
 * kept to maintain the same structure as the internal version.
 */
package object conf {
  type Config = com.typesafe.config.Config

  // ===== Parsing and Utility Methods =====

  /** Parses a JSON-formatted string into a Config. */
  def parseString(str: String): Config = {
    ConfigFactory.parseString(str)
  }

  /** Converts a map into a Config. */
  def parseMap(map: Map[String, Any]): Config = {
    ConfigFactory.parseMap(map.asJava)
  }

  /** Convenience function for parseMap that accepts a set of (k, v) pairs. */
  def parseMap(items: (String, Any)*): Config = parseMap(items.toMap)

  /** An empty Config. */
  val empty: Config = ConfigFactory.empty()

  // ===== Extension Methods =====

  /** Convenience methods for dealing with Config objects. */
  implicit class RichConfig(config: Config) {

    /**
     * Merges two configs. In the case of duplicates, the latter (i.e. `other`) config will override
     * the former.
     */
    def merge(other: Config): Config = {
      other.withFallback(config)
    }
  }

  // ===== Configs Object =====

  /**
   * Allows code to call methods through `Configs` object. All methods delegate to the package-level
   * functions.
   */
  object Configs {
    def parseString(str: String): Config = conf.parseString(str)
    def parseMap(map: Map[String, Any]): Config = conf.parseMap(map)
    def parseMap(items: (String, Any)*): Config = conf.parseMap(items: _*)
    val empty: Config = conf.empty
  }
}

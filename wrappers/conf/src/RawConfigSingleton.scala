package com.databricks.conf

import java.util.concurrent.locks.ReentrantLock
import java.util.{List => JList, Map => JMap}

import scala.collection.JavaConverters._

import com.databricks.common.util.Lock.withLock
import com.databricks.logging.ConsoleLogging
import com.databricks.rpc.DatabricksObjectMapper

/**
 * Tracks a singleton [[Config]] object. This is either initialized from the DB_CONF environment
 * variable (expected to be JSON), or explicitly set with [[initializeConfig]].
 */
object RawConfigSingleton extends ConsoleLogging {
  override val loggerName: String = "RawConfigSingleton"

  /** Lock used to protect internal state. */
  private val lock: ReentrantLock = new ReentrantLock()

  /** Lazily loaded configuration from the DB_CONF environment variable. */
  private lazy val baseConf: Config = {
    val configJson: String = sys.env.getOrElse("DB_CONF", "{}")
    val configMap: Map[String, Any] =
      DatabricksObjectMapper.fromJson[Map[String, Any]](configJson)
    // Conversion to java is needed here because parseMap only supports parsing java objects
    // during recursive parsing
    Configs.parseMap(javafyJsonMap(configMap).asScala.toMap)
  }

  /** The overridden configuration, if any. */
  private var overriddenConf: Option[Config] = None

  /**
   * Returns the singleton configuration, either the base DB_CONF or the overridden configuration.
   */
  def conf: Config = withLock(lock) {
    overriddenConf.getOrElse(baseConf)
  }

  /** Initialize the config to a specific value, in open source version just used for testing. */
  def initializeConfig(newConf: Config): Config = withLock(lock) {
    overriddenConf = Some(newConf)
    newConf
  }

  /** Converts the given Scala JSON map to a Java-based one. */
  private def javafyJsonMap(map: Map[String, Any]): JMap[String, AnyRef] = {
    javafy(map).asInstanceOf[JMap[String, AnyRef]]
  }

  /**
   * Converts the given Scala map/list/object into a Java one, recursively.
   * Assumes map keys are Strings.
   */
  private def javafy(obj: AnyRef): Object = {
    obj match {
      case map: Map[String @unchecked, AnyRef @unchecked] =>
        map.mapValues(javafy).toMap.asJava: JMap[String, Object]
      case list: List[AnyRef @unchecked] =>
        list.map(javafy).asJava: JList[Object]
      case atom =>
        atom
    }
  }
}

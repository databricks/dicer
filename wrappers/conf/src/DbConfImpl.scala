package com.databricks.conf

import com.databricks.logging.ConsoleLogging
import com.databricks.rpc.DatabricksObjectMapper
import com.typesafe.config.{ConfigRenderOptions, ConfigValue}
import scala.util.control.NonFatal

/**
 * Provides a concrete implementation of DbConf which uses Typesafe Config. User code should
 * generally use ProjectConf rather than using this class directly.
 */
class DbConfImpl(baseConfig: Config) extends DbConf with ConsoleLogging {
  override val loggerName = "DbConfImpl"

  /** If true, we are allowed to print configuration for debugging purposes. */
  protected val confLoggingEnabled = false
  if (confLoggingEnabled) {
    logger.info(
      "Loaded configuration:\n" +
      baseConfig.root().render(ConfigRenderOptions.defaults().setOriginComments(false))
    )
  }

  override protected def doConfigure[T: Manifest](
      propertyName: String,
      parser: ConfigParser[T]): Option[T] = {
    DbConfImpl.doConfigureWithConfig(propertyName, baseConfig, parser)
  }
}

object DbConfImpl {
  private[conf] val mapper = DatabricksObjectMapper.mapper

  /**
   * Returns the value of the given property as a type T if it exists in `baseConfig`, or None if
   * not.
   * @throws ConfigParseException if parsing fails.
   */
  def doConfigureWithConfig[T: Manifest](
      propertyName: String,
      baseConfig: Config,
      parser: ConfigParser[T]): Option[T] = {
    val valueOpt: Option[ConfigValue] = if (baseConfig.hasPath(propertyName)) {
      Some(baseConfig.getValue(propertyName))
    } else {
      None
    }
    valueOpt.map { value: ConfigValue =>
      val json: String = value.render(ConfigRenderOptions.concise())
      try {
        parser.parse(DbConfImpl.mapper, json)
      } catch {
        case NonFatal(e) =>
          throw new ConfigParseException(
            s"Failed to parse option '$propertyName'. " +
            s"Expected type: ${manifest[T].runtimeClass}. Input: $json",
            e
          )
      }
    }
  }
}

/**
 * Provides an implementation of DbConf initialized with [[RawConfigSingleton.conf]]. This is
 * implemented as a class (rather than an object) because it needs to be extended by other conf
 * instantiations. Singleton is a bit of a misnomer but kept for backwards compatibility of existing
 * code.
 */
class DbConfSingletonImpl extends DbConf {
  override protected def doConfigure[T: Manifest](
      propertyName: String,
      parser: ConfigParser[T]): Option[T] = {
    DbConfImpl.doConfigureWithConfig(propertyName, RawConfigSingleton.conf, parser)
  }
}

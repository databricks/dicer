package com.databricks.logging

import com.typesafe.scalalogging.Logger

/** Provides basic console-level logging. */
trait ConsoleLogging {

  /** Gets the name of the logger. */
  def loggerName: String

  /** The logger instance. */
  lazy val logger: Logger = Logger(loggerName)

  /**
   * A `log` string interpolator. In open source this has the same behavior as the
   * built-in `s` interpolator.
   */
  implicit class LogInterpolatorStringContext(val stringContext: StringContext) {
    def log(args: Any*): String = {
      stringContext.s(args: _*)
    }
  }
}

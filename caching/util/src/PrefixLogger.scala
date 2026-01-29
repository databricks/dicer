package com.databricks.caching.util

import java.util.concurrent.ConcurrentHashMap

import scala.concurrent.duration.{Duration, FiniteDuration}

import com.typesafe.scalalogging.{Logger => TrackingLogger}
import io.prometheus.client.Counter
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.appender.ConsoleAppender

import com.databricks.caching.util.PrefixLogger.{FileLine, InternalLogger, LogTime, makeMessage}
import com.databricks.logging.ConsoleLogging
import com.databricks.macros.sourcecode.File
import com.databricks.macros.sourcecode.Line
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.config.Configurator
import org.apache.logging.log4j.core.{Appender, LoggerContext, Logger}
import org.apache.logging.log4j.core.layout.PatternLayout

/**
 * A logger that prefixes each logging statement with `className` and `prefix` (the caller is
 * expected to pass getClass.getName for the class name). It also adds the file name and the line
 * number in the prefix as well. It does so using compile-time implicit file and line number
 * parameters without adding the significant overhead that log4j typically has for these values.
 * Each call also has an `every` parameter that allows the caller to indicate that the logging
 * statement on that file/line must be logged after `every` duration has passed, i.e., it logs it
 * the first time and then only if `every` time has passed since the last time of that line's log
 * was emitted.
 *
 * Note that all logs from PrefixLogger currently get attributed to the same line, which may hit the
 * located message limit (see <internal link>). As the method to locate log lines
 * is quite expensive, for now in caching services we have disabled the located/nonlocated limits.
 * See <internal link> for more details.
 */
class PrefixLogger private (className: String, prefix: String, clock: TypedClock) {

  /** The actual logger used for logging the messages. */
  private val logger = new InternalLogger(className, prefix)

  /**
   * Hash table to track information for `every`, i.e., the last time an `every` call was printed
   * for a particular logging statement for a given file and line.
   */
  private lazy val lastLog = new ConcurrentHashMap[FileLine, LogTime]

  /** Logs the `message` at the error level. See class specs for specs on `every`. */
  @SuppressWarnings(
    Array("ConsoleLogWithoutLogInterpolator", "reason:grandfathered-e5954032b5e5c1e0")
  )
  @inline final def error(message: => Any, every: FiniteDuration = Duration.Zero)(
      implicit file: File,
      line: Line): Unit = {
    if (isLoggable(file, line, every)) {
      logger.log.error(makeMessage(file, line, message, every))
    }
  }

  /** Logs the `message` at the warn level. See class specs for specs on `every`. */
  @SuppressWarnings(
    Array("ConsoleLogWithoutLogInterpolator", "reason:grandfathered-5478df0533909425")
  )
  @inline final def warn(message: => Any, every: FiniteDuration = Duration.Zero)(
      implicit file: File,
      line: Line): Unit = {
    if (isLoggable(file, line, every)) {
      logger.log.warn(makeMessage(file, line, message, every))
    }
  }

  /** Logs the `message` at the info level. See class specs for specs on `every`. */
  @SuppressWarnings(
    Array("ConsoleLogWithoutLogInterpolator", "reason:grandfathered-41533fa90a5f4c4d")
  )
  @inline final def info(message: => Any, every: FiniteDuration = Duration.Zero)(
      implicit file: File,
      line: Line): Unit = {
    if (isLoggable(file, line, every)) {
      logger.log.info(makeMessage(file, line, message, every))
    }
  }

  /** Logs the `message` at the debug level. See class specs for specs on `every`. */
  @SuppressWarnings(
    Array("ConsoleLogWithoutLogInterpolator", "reason:grandfathered-4999ca0766be9e2f")
  )
  @inline final def debug(message: => Any, every: FiniteDuration = Duration.Zero)(
      implicit file: File,
      line: Line): Unit = {
    if (isLoggable(file, line, every)) {
      logger.log.debug(makeMessage(file, line, message, every))
    }
  }

  /** Logs the `message` at the trace level. See class specs for specs on `every`. */
  @SuppressWarnings(
    Array("ConsoleLogWithoutLogInterpolator", "reason:grandfathered-ce4742aceb00a4cd")
  )
  @inline final def trace(message: => Any, every: FiniteDuration = Duration.Zero)(
      implicit file: File,
      line: Line): Unit = {
    if (isLoggable(file, line, every)) {
      logger.log.trace(makeMessage(file, line, message, every))
    }
  }

  /**
   * Something unexpected happened and we ought to throw exception and interrupt further execution.
   * Logs ERROR level, and applies [[Severity.CRITICAL]], use with caution!
   *
   * @throws AssertionError When the assertion evaluates to false.
   */
  @inline def assert(
      assertion: Boolean,
      errorCode: CachingErrorCode,
      message: => String,
      every: FiniteDuration = Duration.Zero)(implicit file: File, line: Line): Unit = {
    if (!assertion) {
      alert(Severity.CRITICAL, errorCode, message, every)(file, line)
      Predef.assert(assertion, s"$errorCode $message")
    }
  }

  /**
   * Something unexpected happened, we can continue execution. But this is is worthy of
   * investigation. Logs WARN level, and applies [[Severity.DEGRADED]].
   */
  @inline def expect(
      assertion: Boolean,
      errorCode: CachingErrorCode,
      message: => String,
      every: FiniteDuration = Duration.Zero)(implicit file: File, line: Line): Unit = {
    if (!assertion) {
      alert(Severity.DEGRADED, errorCode, message, every)(file, line)
    }
  }

  /**
   * Something unexpected happened, log a message and increment a metric which will fire an alert
   * (in Caching team services) at the appropriate level. It is recommended to use [[assert]] or
   * [[expect]] rather than this method when possible. [[Severity.DEGRADED]] logs at warning level,
   * whereas [[Severity.CRITICAL]] logs at error level.
   */
  @inline def alert(
      severity: Severity.Value,
      errorCode: CachingErrorCode,
      message: String,
      every: FiniteDuration = Duration.Zero)(implicit file: File, line: Line): Unit = {
    // Log for a given file and line.
    val msg = s"[$severity] $errorCode: $message"
    severity match {
      case Severity.DEGRADED =>
        warn(msg, every)(file, line)
      case Severity.CRITICAL =>
        error(msg, every)(file, line)
    }

    // Increment the metric.
    PrefixLogger.errorCount
      .labels(severity.toString, errorCode.toString, prefix, errorCode.alertOwnerTeam.toString)
      .inc()
  }

  /**
   * Returns whether the logging statement in `file` and `line` has had sufficient time passed since
   * the last call on that location (based on `every`). If `every` is 0, returns true.
   */
  private final def isLoggable(file: File, line: Line, every: Duration): Boolean = {
    if (every == Duration.Zero) return true

    val now: TickerTime = clock.tickerTime()
    var toPrint = false
    lastLog.compute(
      FileLine(file, line),
      (_, lastCallTime: LogTime) => {
        // Print if it is the first time or last log time was too old.
        if (lastCallTime == null || (now - lastCallTime.time >= every)) {
          toPrint = true
          LogTime(now)
        } else {
          lastCallTime
        }
      }
    )
    toPrint
  }
}

/** Companion object to provide a factory method for creating a [[PrefixLogger]]. */
object PrefixLogger {

  configureCachingTestLogging()

  /** Clock used for checking with the `every` duration (if used) in the logging calls. */
  private val realClock = RealtimeTypedClock

  /** Metric to record error code and severity. */
  private val errorCount: Counter = Counter
    .build()
    .name("caching_errors")
    .labelNames("severity", "error_code", "prefix", "owner_team")
    .help("Counter for common errors with severity and error code.")
    .register()

  /** A class to track a file name and line number of a logging statement.  */
  private case class FileLine(file: File, line: Line)

  /**
   * A class to track time - used for the `every` call that uses ConcurrentHashMap's compute. That
   * method requires the value to be a class and not a primitive or AnyVal value.
   */
  private case class LogTime(time: TickerTime)

  /**
   * An internal logger class that utilizes [[ConsoleLogging]] but does not expose it to the
   * caller of [[PrefixLogger]].
   */
  private class InternalLogger(className: String, prefix: String) extends ConsoleLogging {
    private val loggerPrefix = s"$className[$prefix]:"
    override def loggerName: String = loggerPrefix
    def log: TrackingLogger = logger
  }

  private object DurationConstants {
    final val MS_IN_ONE_SECOND = 1000
    final val MS_IN_ONE_MINUTE = 60 * MS_IN_ONE_SECOND
    final val MS_IN_ONE_HOUR = 60 * MS_IN_ONE_MINUTE
  }

  /**
   * Creates a logger for the given `clazz` with `prefix` being added to every logging message
   * (in the beginning). The `clock` is used to measure time for the `every` parameter passed
   * to the logging calls.
   */
  def create(clazz: Class[_], prefix: String, clock: TypedClock = realClock): PrefixLogger = {
    new PrefixLogger(clazz.getName, prefix, clock)
  }

  /**
   * Guesses whether we're currently running a caching team test, and if so, configures the log
   * format and enables DEBUG logging for caching code. Runs once when the [[PrefixLogger]]
   * companion object is initialized.
   */
  private def configureCachingTestLogging(): Unit = {
    // Names of the top level directories containing caching team code (Dicer, Softstore, and shared
    // utilities).
    val topLevelDirs = List("dicer", "softstore", "caching")
    if (sys.env.get("TEST_TARGET") match {
        case Some(target: String) =>
          // Tests run with TEST_SRCDIR set to the runfiles path, e.g.:
          // .../universe/bazel-out/darwin_arm64-fastbuild/bin/dicer/common/SliceMapSuite.runfiles
          // We match on the architecture agnostic substring /bin/<dir>/ to determine if we're
          // likely to be running a caching team test. False positives are possible, but the
          // environment variable test suggests we're not running in a production environment.
          topLevelDirs.exists { dir: String =>
            target.contains(s"//$dir/")
          }
        case None =>
          false
      }) {
      // Reconfigure the log format, output, and log level for the root logger.
      val rootLogger: Logger = LogManager.getRootLogger.asInstanceOf[Logger]

      val layout = PatternLayout
        .newBuilder()
        .withPattern(
          // This pattern is loosely based on common/logging/src/test/resources/log4j2.xml, with the
          // following key changes:
          //  - Add SSS to show milliseconds.
          //  - Show full logger name (which includes the PrefixLogger's prefix) `%c` rather than
          //    just suffix `%c{1}`.
          //  - Remove MDC context `%X{traceInfo}` as it is quite verbose (includes e.g. spanId,
          //    requestId) and not very useful in tests.
          //  - Use negative message size to truncate from the end rather than start.
          "%d{yyyy/MM/dd HH:mm:ss.SSS} %p %c %.-16384m%n"
        )
        .build()

      // This is the default appender name created by log4j if no other configuration is specified.
      // We update/override it so that we don't have multiple console appenders causing duplicated
      // output.
      val consoleAppenderName = "Console"
      val updatedConsoleAppender = ConsoleAppender
        .newBuilder()
        .asInstanceOf[ConsoleAppender.Builder[_]]
        .setName(consoleAppenderName)
        .setTarget(ConsoleAppender.Target.SYSTEM_OUT)
        .setLayout(layout)
        // Set "follow" property so that we can reassign System.out to test the output format - we
        // do this in the test "Prefix included in log message".
        .setFollow(true)
        .build()
      updatedConsoleAppender.start()
      val defaultConsoleAppenderOpt: Option[Appender] = Option(
        rootLogger.getAppenders.get(consoleAppenderName)
      )
      defaultConsoleAppenderOpt match {
        case Some(appender: Appender) => rootLogger.removeAppender(appender)
        case None => // No default console appender found, nothing to remove.
      }
      rootLogger.addAppender(updatedConsoleAppender)

      // Enable DEBUG logging for caching code.
      for (dir: String <- topLevelDirs) {
        Configurator.setAllLevels(s"com.databricks.$dir", Level.DEBUG)
      }

      // Apply the new configuration.
      // Note that Log4j uses a hierarchical strategy for logger configurations based on the
      // logger's name. When multiple configurations exist (e.g., ``, `com.databricks` and
      // `com.databricks.caching`), Log4j applies the most specific configuration available for a
      // given logger. Therefore, this root logger configuration should not interfere with any
      // existing configurations for more specific loggers.
      LogManager.getContext(false).asInstanceOf[LoggerContext].updateLoggers()
    }
  }

  /**
   * Given `line`, `path` and `every` for prefixing with `message`, returns the prefixed message.
   */
  private def makeMessage(
      file: File,
      line: Line,
      message: => Any,
      every: FiniteDuration): String = {
    val filename = file.value.split("/").last
    val everyStr: String =
      if (every == Duration.Zero) "" else s" [every=${durationToCompactString(every)}]"
    s"[$filename:${line.value}]$everyStr $message"
  }

  /** Print a compact representation of the given duration, tailored to PrefixLogger's use case. */
  private def durationToCompactString(duration: FiniteDuration): String = {
    import PrefixLogger.DurationConstants._
    duration.toMillis match {
      case ms if ms < MS_IN_ONE_SECOND => s"${ms}ms"
      case ms if ms < MS_IN_ONE_MINUTE => s"${ms / MS_IN_ONE_SECOND}s"
      case ms if ms < MS_IN_ONE_HOUR => s"${ms / MS_IN_ONE_MINUTE}m"
      case ms =>
        // We don't expect such long `every` in practice, just have some placeholder value.
        "1h+"
    }
  }
}

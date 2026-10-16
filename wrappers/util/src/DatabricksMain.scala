package com.databricks

import java.util.concurrent.CountDownLatch

import com.databricks.backend.common.util.CurrentProject
import com.databricks.backend.common.util.Project
import com.databricks.common.web.InfoService
import com.databricks.conf.{Config, RawConfigSingleton}
import com.databricks.conf.Constants.INFO_SERVICE_PORT
import com.databricks.logging.ConsoleLogging

/**
 * Abstract base class that implements a main method common to all Databricks services. It starts
 * the InfoService and keeps the JVM alive until shutdown.
 */
abstract class DatabricksMain(project: Project.Project, rawConfigOpt: Option[Config] = None)
    extends ConsoleLogging {
  CurrentProject.initializeProject(project)

  override def loggerName: String = s"DatabricksMain(${project.name})"

  /** The configuration: the explicitly-provided override if any, else the environment config. */
  protected val rawConfig: Config = rawConfigOpt.getOrElse(RawConfigSingleton.conf)

  /**
   * Latch to keep the JVM alive. A shutdown hook decrements this to allow the main thread to
   * exit.
   */
  private val keepAliveLatch = new CountDownLatch(1)

  /** Main entry point that sets up the service and calls wrappedMain. */
  def main(args: Array[String]): Unit = {
    try {
      logger.info(s"Starting ${project.name} service...")

      // Register shutdown hook to release the latch on JVM exit.
      Runtime.getRuntime.addShutdownHook(new Thread(() => {
        logger.info(s"${project.name} shutting down")
        keepAliveLatch.countDown()
      }, s"${project.name}-ShutdownHook"))

      InfoService.start(port = INFO_SERVICE_PORT)

      // Call the wrapped main implementation.
      wrappedMain(args)

      // Keep the service running until shutdown.
      logger.info(s"${project.name} initialization complete, waiting for shutdown signal")
      keepAliveLatch.await()

      logger.info(s"${project.name} shutdown complete")
    } catch {
      case e: Exception =>
        logger.error(s"Fatal error in ${project.name}: ${e.getMessage}")
        e.printStackTrace()
        sys.exit(1)
    }
  }

  /** Main logic to be implemented by the service. */
  protected def wrappedMain(args: Array[String]): Unit
}

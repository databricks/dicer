package com.databricks.conf.trusted

import scala.util.control.NonFatal

import com.fasterxml.jackson.module.scala.{DefaultScalaModule, ScalaObjectMapper}
import com.fasterxml.jackson.databind.{
  DeserializationFeature,
  ObjectMapper,
  PropertyNamingStrategies
}
import com.databricks.conf.{DbConf, DbConfSingletonImpl}

/**
 * Tells the process "where" it is running. The information must be static for a given compute node,
 * and is considered immutable for the life of the node and any process that runs on it.
 * @param kubernetesClusterUri The URI of the Kubernetes cluster where the current process is
 *                             running, or None if not set/unknown.
 * @param regionUri The URI of the region where the current process is running, or None if not
 *                  set/unknown.
 */
case class KubernetesLocation(
    kubernetesClusterUri: Option[String] = None,
    regionUri: Option[String] = None) {

  /** The URI of the Kubernetes cluster where the current process is running, or null if not set. */
  def getKubernetesClusterUri: String = kubernetesClusterUri.orNull

  /** The URI of the region where the current process is running, or null if not set. */
  def getRegionUri: String = regionUri.orNull
}

/**
 * Provides information about the currently running process, as needed by ConfigScope and
 * WhereAmIHelper.
 */
trait LocationConf extends DbConf {

  /** The system environment variables. Can be overridden for testing. */
  protected def sysEnv(): Map[String, String] = sys.env

  /**
   * Whether to fail on unknown keys when deserializing the LOCATION environment variable. Can be
   * overridden for testing.
   */
  protected def shouldFailOnUnknownProperties(): Boolean = true

  /** The branch name used to build the running binary. Expected to be set by the release system. */
  val branch = configure[String]("databricks.branch.name", "development")

  /** The cloud where the shard is running (e.g., "aws", "azure", "gcp"). */
  val shardCloud = configure[String]("databricks.cloud", "unknown")

  /** The region where the shard is running (e.g., "us-west-2", "westus"). */
  val region = configure[String]("databricks.region.name", "region1")

  /**
   * Location information about the currently running process. Picked up from the LOCATION
   * environment variable.
   */
  val location: KubernetesLocation = {
    val locationOpt: Option[KubernetesLocation] =
      try {
        sysEnv().get("LOCATION").map { locationString: String =>
          // We can't use DatabricksObjectMapper because it doesn't allow configuring the
          // ObjectMapper.
          val objectMapper = new ObjectMapper() with ScalaObjectMapper
          objectMapper.registerModule(DefaultScalaModule)
          objectMapper.configure(
            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
            shouldFailOnUnknownProperties()
          )
          objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
          objectMapper.readValue[KubernetesLocation](locationString)
        }
      } catch {
        case NonFatal(e) =>
          None // If parsing fails, fall back to None.
      }
    locationOpt.getOrElse(KubernetesLocation(None))
  }
}

object LocationConf {

  /** Immutable default LocationConf singleton. */
  private val defaultSingletonConf: LocationConf = new DbConfSingletonImpl with LocationConf

  /** The internal singleton for LocationConf, only allowed to be updated in a Bazel test. */
  private var singletonInternal: LocationConf = defaultSingletonConf

  /** Returns the singleton for LocationConf. */
  def singleton: LocationConf = singletonInternal

  /** Restores [[singleton]] to the default value. Not thread-safe. */
  def restoreSingletonForTest(): Unit = {
    require(isBazelTest(), "restoreSingletonForTest can only be called in a Bazel test")
    singletonInternal = defaultSingletonConf
  }

  /** Sets a test singleton for LocationConf. Not thread-safe. */
  def testSingleton(locationConf: LocationConf): Unit = {
    require(isBazelTest(), "testSingleton can only be called in a Bazel test")
    singletonInternal = locationConf
  }

  private def isBazelTest(): Boolean = {
    System.getenv("BAZEL_TEST") == "1"
  }
}

/** The deployment modes of a process. */
object DeploymentModes extends Enumeration {
  type Value = super.Value

  final val Development = Value("development")
  final val Production = Value("production")
  final val Staging = Value("staging")

  /** Converts a string representation of a deployment mode to a [[Value]]. */
  def fromString(deploymentModeStr: String): Value = {
    deploymentModeStr match {
      case "development" => Development
      case "production" => Production
      case "staging" => Staging
      case _ => throw new IllegalArgumentException(s"Unknown deployment mode: $deploymentModeStr")
    }
  }
}

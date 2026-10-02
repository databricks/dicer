package com.databricks.dicer.common

import java.util.concurrent.locks.ReentrantLock
import javax.annotation.concurrent.GuardedBy

import com.databricks.caching.util.Lock.withLock

/**
 * An identifier for an instance of an app. This identifier uniquely identifies the instance of
 * the app across the app's known universe of deployment environments.
 *
 * The only way to obtain an [[AppIdentifier]] is through [[AppIdentifier.getFromEnv]], so a
 * holder of an identifier knows that it was constructed with verified values.
 *
 * @param name The name of the app. Must:
 *             - contain at most 42 characters
 *             - start with a lowercase letter (`a-z`)
 *             - then contain only lowercase letters, digits, and hyphens
 *             - use hyphens only to separate alphanumeric segments
 * @param instanceId The identifier of the running instance of the app. Must satisfy the same
 *                   character constraints as `name`, and contain at most 63 characters.
 * @throws IllegalArgumentException if the `name` or `instanceId` is invalid.
 */
private[dicer] class AppIdentifier @throws[IllegalArgumentException]() private (
    val name: String,
    val instanceId: String) {

  require(
    name != null && name.length <= AppIdentifier.MAX_NAME_LENGTH && name.matches(
      AppIdentifier.IDENTIFIER_REGEX
    ),
    s"Invalid app identifier '$name'."
  )
  require(
    instanceId != null && instanceId.length <= AppIdentifier.MAX_INSTANCE_ID_LENGTH &&
    instanceId.matches(AppIdentifier.IDENTIFIER_REGEX),
    s"Invalid app instance identifier '$instanceId'."
  )

  override def equals(other: Any): Boolean = other match {
    case that: AppIdentifier => name == that.name && instanceId == that.instanceId
    case _ => false
  }

  override def hashCode(): Int = (name, instanceId).hashCode()

  override def toString: String = s"AppIdentifier($name, $instanceId)"
}

/** Companion object for [[AppIdentifier]]. */
private[dicer] object AppIdentifier {

  /** Regex used to validate the characters in `name` and `instanceId`. */
  private val IDENTIFIER_REGEX = "[a-z][a-z0-9]*(-[a-z0-9]+)*"

  /** Maximum length of a valid app name. */
  private val MAX_NAME_LENGTH = 42

  /** Maximum length of a valid instance id. */
  private val MAX_INSTANCE_ID_LENGTH = 63

  /** Lock protecting [[nameAndInstanceIdOpt]]. */
  private val lock: ReentrantLock = new ReentrantLock()

  /** Raw app name and instance id, or `None` if neither is set. */
  @GuardedBy("lock")
  private var nameAndInstanceIdOpt: Option[(String, String)] = None

  /**
   * Returns the validated [[AppIdentifier]] of the current process, or `None` if no identifier
   * is set.
   */
  @throws[IllegalArgumentException](
    "if the app metadata was set to invalid values, see AppIdentifier"
  )
  def getFromEnv: Option[AppIdentifier] = withLock(lock) {
    nameAndInstanceIdOpt match {
      case Some((name, instanceId)) => Some(new AppIdentifier(name, instanceId))
      case _ => None
    }
  }

  /**
   * Sets the raw app metadata for unit tests. Both values must be present to set the metadata,
   * or both absent to clear it.
   *
   * We do not validate `name` or `instanceId` here, so tests can store invalid values.
   *
   * @param nameOpt The app name, or `None`.
   * @param instanceIdOpt The app instance id, or `None`.
   */
  @throws[IllegalArgumentException](
    "if exactly one of name or instance id is provided"
  )
  private[dicer] def setInstanceForTest(
      nameOpt: Option[String],
      instanceIdOpt: Option[String]): Unit = withLock(lock) {
    require(
      nameOpt.isDefined == instanceIdOpt.isDefined,
      "App name and instance id must both be present or both be absent"
    )
    nameAndInstanceIdOpt = (nameOpt, instanceIdOpt) match {
      case (Some(name), Some(instanceId)) =>
        Some((name, instanceId))
      case _ =>
        None
    }
  }
}

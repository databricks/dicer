package com.databricks.dicer.common

/**
 * An identifier for an instance of an app. This identifier uniquely identifies
 * the instance of the app across the app's known universe of deployment
 * environments.
 *
 * The only way to obtain an AppIdentifier is through [[AppIdentifier.getFromEnv]], so a holder of
 * an identifier knows that it was constructed with verified values.
 *
 * @param name The name of the app.
 * @param instanceId The identifier of the running instance of the app.
 */
private[dicer] class AppIdentifier private (val name: String, val instanceId: String) {

  override def equals(other: Any): Boolean = other match {
    case that: AppIdentifier => name == that.name && instanceId == that.instanceId
    case _ => false
  }

  override def hashCode(): Int = (name, instanceId).hashCode()

  override def toString: String = s"AppIdentifier($name, $instanceId)"
}

/** Companion object for [[AppIdentifier]]. */
private[dicer] object AppIdentifier {

  /**
   * Returns the [[AppIdentifier]] of the current process, or `None` if the process has
   * no identifier. This is a no-op implementation for OSS builds.
   */
  def getFromEnv: Option[AppIdentifier] = None
}

package com.databricks.backend.common.util

import java.util.concurrent.locks.ReentrantLock

import com.databricks.common.util.Lock.withLock

/**
 * Lightweight accessor for the current project's name. Populated automatically by
 * [[CurrentProject.initializeProject]] at service startup.
 */
object CurrentProjectInfo {

  /** Lock used to protect internal state. */
  private val lock: ReentrantLock = new ReentrantLock()

  /** The current project's name, or [[None]] if not yet initialized. */
  private var nameInternal: Option[String] = None

  /** Sets the current project's name. */
  @throws[IllegalArgumentException]("if the project name is empty")
  private[util] def set(projectName: String): Unit = withLock(lock) {
    require(projectName.nonEmpty, "project name must not be empty")
    nameInternal = Some(projectName)
  }

  /** Returns the current project's name, or None if not yet initialized. */
  def projectNameOpt: Option[String] = withLock(lock) {
    nameInternal
  }
}

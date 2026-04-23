package com.databricks.backend.common.util

import java.util.concurrent.locks.ReentrantLock

import com.databricks.common.util.Lock.withLock

/** Allows setting and retrieving the current project configuration. */
object CurrentProject {

  /** Lock used to protect internal state. */
  private val lock: ReentrantLock = new ReentrantLock()

  /** The current project, or [[None]] if not yet initialized. */
  private var projectInternal: Option[Project.Project] = None

  /** Returns the optional singleton for the current project. */
  def projectOpt: Option[Project.Project] = withLock(lock) {
    projectInternal
  }

  /**
   * REQUIRES: Must only be called once.
   *
   * Initialize the project (called by DatabricksMain).
   */
  @throws[IllegalStateException]("if the project has already been initialized")
  def initializeProject(project: Project.Project): Unit = withLock(lock) {
    if (projectInternal.isDefined) {
      throw new IllegalStateException(
        s"Project already initialized to '${projectInternal.get.name}'. " +
        "initializeProject must not be called more than once."
      )
    }
    projectInternal = Some(project)
  }

  /** Returns the project name, or "NoServiceName" if not initialized. */
  def getProjectName: String = withLock(lock) {
    projectInternal match {
      case Some(project) => project.name
      case None => "NoServiceName"
    }
  }
}

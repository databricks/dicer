package com.databricks.backend.common.util

/** Enum containing different services. Minimal open source version. */
object Project extends Enumeration {
  type Project = ProjectDetails

  /** Details about a project - stores the name. */
  protected case class ProjectDetails(name: String) extends super.Val(nextId, name) {}

  val DicerAssigner = ProjectDetails("dicer-assigner")
  val DemoServer = ProjectDetails("dicer-demo-server")
  val DemoClient = ProjectDetails("dicer-demo-client")

  /** Project for unit tests. */
  val TestProject = ProjectDetails("test")
}

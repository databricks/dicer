package com.databricks.conf.trusted

import com.databricks.conf.DbConfImpl
import com.databricks.backend.common.util.Project
import com.typesafe.config.Config

/**
 * Minimal open source version of ProjectConf which just delegates to DbConfImpl. This version does
 * not support project-specific configuration.
 */
class ProjectConf(val project: Project.Project, val rawConfig: Config)
    extends DbConfImpl(rawConfig) {

  /**
   * String-named auxiliary constructor, for compatibility with internal code.
   */
  @throws[NoSuchElementException]("if no project with the given name exists")
  def this(projectName: String, rawConfig: Config) =
    this(Project.fromName(projectName), rawConfig)
}

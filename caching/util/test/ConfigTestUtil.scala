package com.databricks.caching.util
import java.io.File

import os.Path

/**
 * Utility object for configuration testing, shared by projects that read configs from a pair of
 * directories.
 */
object ConfigTestUtil {

  /**
   * A helper that writes configs into temporary directories, so that tests can point a config
   * reader or validator at directories whose contents they own.
   */
  class ConfigWriter {

    // Temporary directories created for this writer.
    private val path: Path = os.temp.dir()
    private val advancedPath: Path = os.temp.dir()

    /**
     * Writes a file with the given `filename` and `contents` to the temporary directory from which
     * primary configuration files will be read. `filename` may include subdirectories (e.g.
     * `staging/namespace1.textproto`), missing parent folders are created. We intentionally take a
     * [[String]] for the contents to test invalid config inputs.
     */
    def writeConfig(filename: String, contents: String): Unit = {
      val configPath: Path = path / os.RelPath(filename)
      os.write(configPath, contents, createFolders = true)
    }

    /**
     * Writes a file with the given `filename` and `contents` to the temporary directory from which
     * advanced configuration files will be read. See remarks on [[writeConfig]].
     */
    def writeAdvancedConfig(filename: String, contents: String): Unit = {
      val configPath: Path = advancedPath / os.RelPath(filename)
      os.write(configPath, contents, createFolders = true)
    }

    /** Returns the directory for primary configs. */
    def getConfigDirectory: File = new File(path.toString())

    /** Returns the directory for advanced configs. */
    def getAdvancedConfigDirectory: File = new File(advancedPath.toString())
  }
}

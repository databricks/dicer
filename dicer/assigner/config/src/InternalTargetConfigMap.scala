package com.databricks.dicer.assigner.config

import java.io.File

import com.databricks.caching.util.ConfigScope
import com.databricks.dicer.assigner.config.TargetConfigReader.readScopeConfigMapFromDirectories
import com.databricks.dicer.common.TargetName

/**
 * The abstraction that tracks the configuration for each sharded target.
 */
private[dicer] trait InternalTargetConfigMap {
  // The scope of the configs used by the map (for debugging purposes).
  val configScopeOpt: Option[ConfigScope]

  /** Returns the number of targets in this config map. */
  def size: Int

  /** Returns the set of target names in this config map. */
  def targetNames: Set[TargetName]

  /** Returns an iterator over all (targetName, config) entries in this map. */
  def iterator: Iterator[(TargetName, InternalTargetConfig)]

  /** Returns the config for the given target name, if it exists. */
  def get(targetName: TargetName): Option[InternalTargetConfig]
}

object InternalTargetConfigMap {

  /**
   * The implementation of InternalTargetConfigMap.
   *
   * @param configScopeOpt scope of the configs used by the map (for debugging purposes).
   * @param configMap map from target name to its config. Configs are shared for all targets with
   *                  the same name, regardless of their cluster.
   */
  private final class InternalTargetConfigMapImpl(
      val configScopeOpt: Option[ConfigScope],
      private val configMap: Map[TargetName, InternalTargetConfig])
      extends InternalTargetConfigMap {

    override def size: Int = configMap.size

    override def targetNames: Set[TargetName] = configMap.keySet

    override def iterator: Iterator[(TargetName, InternalTargetConfig)] = configMap.iterator

    override def get(targetName: TargetName): Option[InternalTargetConfig] =
      configMap.get(targetName)

    override def toString: String = {
      s"InternalTargetConfigMap(configScopeOpt=$configScopeOpt, ${configMap.values.mkString(", ")})"
    }
  }

  /**
   * Extracts configurations, optionally merging overrides for the given `configScopeOpt`, by
   * reading the config files from the given `targetConfigDirectory` and
   * `advancedTargetConfigDirectory` paths. These paths should contain the contents of (the
   * appropriate one of) `dicer/external/config/(dev|staging|prod)` in universe.
   *
   * @param configScopeOpt scope of the configs exposed by the map.
   * @param targetConfigDirectory Contains a copy of the files at
   *                              //universe/dicer/external/config/(dev|staging|prod)
   * @param advancedTargetConfigDirectory Contains a copy of the files at
   *                                      //universe/dicer/assigner/config/(dev|staging|prod)
   */
  def create(
      configScopeOpt: Option[ConfigScope],
      targetConfigDirectory: File,
      advancedTargetConfigDirectory: File): InternalTargetConfigMap = {
    val targetConfigMap: Map[TargetName, InternalTargetConfig] = readScopeConfigMapFromDirectories(
      configScopeOpt,
      targetConfigDirectory,
      advancedTargetConfigDirectory
    )
    new InternalTargetConfigMapImpl(configScopeOpt, targetConfigMap)
  }

  /**
   * Creates a InternalTargetConfigMap based on a mapping from target names to InternalTargetConfig.
   */
  def create(
      configScopeOpt: Option[ConfigScope],
      targetConfigMap: Map[TargetName, InternalTargetConfig]): InternalTargetConfigMap =
    new InternalTargetConfigMapImpl(configScopeOpt, targetConfigMap)
}

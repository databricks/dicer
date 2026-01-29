package com.databricks.dicer.assigner.config
import java.io.File

import scala.collection.mutable
import scala.io.{BufferedSource, Source}

import com.databricks.api.proto.caching.external.ConfigScopeP
import com.databricks.api.proto.dicer.assigner.config.{
  AdvancedTargetConfigFieldsP,
  AdvancedTargetConfigOverrideP,
  AdvancedTargetConfigP
}
import com.databricks.api.proto.dicer.external.{
  TargetConfigFieldsP,
  TargetConfigOverrideP,
  TargetConfigP
}
import com.databricks.caching.util.{ConfigScope, PrefixLogger}
import com.databricks.common.alias.RichScalaPB.RichMessage
import com.google.protobuf.CodedInputStream
import scalapb.TextFormatException

/**
 * This object encapsulates operations related to reading and parsing target config files.
 *
 * It provides the following entry points:
 *  - [[readScopeConfigMapFromDirectories]]: returns a mapping from [[Target]]s to
 *    [[InternalTargetConfig]]s for the specified scope.
 *  - [[readFullConfigMapFromDirectories]]: returns a mapping from [[Target]]s to derived
 *    [[InternalTargetConfig]] for all scopes.
 */
object TargetConfigReader {

  private val logger = PrefixLogger.create(this.getClass, "")

  private val CONFIG_FILE_SUFFIX: String = ".textproto"

  /**
   * A case class that contains all the computed [[InternalTargetConfig]]s for a target.
   * It contains the one derived from the default sub-configs, and those after applying per-scope
   * overrides.
   */
  case class TargetDefaultAndOverride(
      default: InternalTargetConfig,
      overrides: Map[ConfigScope, InternalTargetConfig])

  /**
   * Creates a mapping from [[TargetName]]s to [[InternalTargetConfig]]s specifically for
   * `configScopeOpt`.
   *
   * @param configScopeOpt the [[ConfigScope]] that this assigner is currently running in, or None
   *                       if the (cloud, region) tuple cannot be parsed into a valid scope, in
   *                       which case default configs are used.
   * @note `new java.io.File` only throws if a null path argument is provided.
   */
  private[assigner] def readScopeConfigMapFromDirectories(
      configScopeOpt: Option[ConfigScope],
      targetConfigDirectory: File,
      advancedTargetConfigDirectory: File): Map[TargetName, InternalTargetConfig] = {

    val targetConfigMap: Map[TargetName, InternalTargetConfig] = readConfigMap(
      targetConfigDirectory,
      advancedTargetConfigDirectory
    ).map {
      case (
          targetName: TargetName,
          (targetConfigProto: TargetConfigP, advancedConfigProto: AdvancedTargetConfigP)
          ) =>
        // Compute scope specific overrides.
        val (targetOverrideOpt, advancedTargetOverrideOpt): (
            Option[TargetConfigFieldsP],
            Option[AdvancedTargetConfigFieldsP]) =
          getScopeOverride(configScopeOpt, targetConfigProto, advancedConfigProto)

        // Merge the base and override configs to get the "fields" config protos.
        val configFields: TargetConfigFieldsP =
          mergeOverride(targetConfigProto.getDefaultConfig, targetOverrideOpt)
        val advancedConfigFields: AdvancedTargetConfigFieldsP =
          mergeOverride(
            advancedConfigProto.getDefaultConfig,
            advancedTargetOverrideOpt
          )

        targetName -> InternalTargetConfig.fromProtos(
          configFields,
          advancedConfigFields
        )
    }

    logger.info(s"Registered targets - ${targetConfigMap.keys.mkString(",")}")
    targetConfigMap
  }

  /**
   * Computes a map from [[Target]] names to [[TargetDefaultAndOverride]]s. Each value represents
   * the computed [[InternalTargetConfig]]s for all scopes within a target.
   *
   * @note `new java.io.File` only throws if a null path argument is provided.
   */
  private[config] def readFullConfigMapFromDirectories(
      targetConfigDirectory: File,
      advancedTargetConfigDirectory: File): Map[TargetName, TargetDefaultAndOverride] = {
    val fullConfigMap: Map[TargetName, TargetDefaultAndOverride] = readConfigMap(
      targetConfigDirectory,
      advancedTargetConfigDirectory
    ).map {
      case (
          targetName: TargetName,
          (targetConfigProto: TargetConfigP, advancedConfigProto: AdvancedTargetConfigP)
          ) =>
        targetName -> readConfigsForAllScopes(targetName, targetConfigProto, advancedConfigProto)
    }
    fullConfigMap
  }

  /**
   * Returns the map of [[Target]] name to owning team from the configs in `targetConfigDirectory`.
   * It is also used in the `dicer/tools` to generate owner team name mappings.
   */
  private[dicer] def readConfigOwners(targetConfigDirectory: File): Map[TargetName, String] =
    readTargetConfigProtos(TargetConfigP, targetConfigDirectory)
      .mapValues(_.getOwnerTeamName)
      .toMap

  /**
   * REQUIRES: All advanced configs should have corresponding target configs.
   *
   * Reads and parses configuration files from the target and advanced target directories,
   * constructs a mapping from [[Target]] names to corresponding config objects.
   */
  private def readConfigMap(targetConfigDirectory: File, advancedTargetConfigDirectory: File)
      : Map[TargetName, (TargetConfigP, AdvancedTargetConfigP)] = {
    logger.info(s"Reading target configuration in $targetConfigDirectory.")
    val configProtoMap: Map[TargetName, TargetConfigP] =
      readTargetConfigProtos(TargetConfigP, targetConfigDirectory)
    logger.info(s"Reading advanced target configurations in $advancedTargetConfigDirectory.")
    val advancedConfigProtoMap: Map[TargetName, AdvancedTargetConfigP] =
      readTargetConfigProtos(AdvancedTargetConfigP, advancedTargetConfigDirectory)

    // Verify that there are target configs corresponding to each advanced config (the reverse
    // does not need to hold: advanced configs are optional).
    val missingConfigs: Set[TargetName] = advancedConfigProtoMap.keySet.diff(configProtoMap.keySet)
    require(
      missingConfigs.isEmpty,
      s"${missingConfigs.mkString(",")} should have corresponding target configs."
    )

    configProtoMap.map {
      case (targetName: TargetName, targetConfig: TargetConfigP) =>
        (
          targetName,
          (
            targetConfig,
            advancedConfigProtoMap.getOrElse(targetName, AdvancedTargetConfigP.defaultInstance)
          )
        )
    }
  }

  /**
   * Reads all target config protos from the given directory.
   *
   * @throws IllegalArgumentException if the given path is not a directory or any configs cannot be
   *                                  parsed.
   */
  @throws[IllegalArgumentException]
  private def readTargetConfigProtos[
      ConfigP <: scalapb.Message[ConfigP] with scalapb.GeneratedMessage](
      messageCompanion: scalapb.GeneratedMessageCompanion[ConfigP],
      file: File): Map[TargetName, ConfigP] = {
    if (!file.exists) {
      // TODO(<internal bug>): Change this to a requirement once we enforce target configuration.
      logger.warn(s"No directory found for target configuration at $file")
      return Map.empty
    }
    if (!file.isDirectory) {
      throw new IllegalArgumentException(
        s"Expected directory with target configuration. $file is not a directory."
      )
    } else {
      file.listFiles.filter((file: File) => file.isFile)
    }
    val map = Map.newBuilder[TargetName, ConfigP]
    for (file: File <- file.listFiles) {
      // Skip files that don't end with .textproto.
      if (file.isFile && file.getName.endsWith(CONFIG_FILE_SUFFIX)) {
        val targetName = TargetName(file.getName.stripSuffix(CONFIG_FILE_SUFFIX))
        val fileSource: BufferedSource = Source.fromFile(file, "utf-8")
        val blob: String = fileSource.mkString
        try {
          val proto: ConfigP = messageCompanion.fromAscii(blob)
          map += targetName -> proto
        } catch {
          case e: TextFormatException =>
            throw new IllegalArgumentException(s"Bad textproto format: ${e.getMessage}", e)
        }
      }
    }
    map.result()
  }

  /**
   * REQUIRES: [[InternalTargetConfig]] derived from default sub-configs is valid.
   * REQUIRES: [[InternalTargetConfig]]s derived after applying the overrides are valid.
   *
   * Computes [[InternalTargetConfig]]s for all scopes within a target.
   */
  private def readConfigsForAllScopes(
      targetName: TargetName,
      targetConfig: TargetConfigP,
      advancedConfig: AdvancedTargetConfigP): TargetDefaultAndOverride = {

    // Compute overrides for all the scopes in the target config.
    val targetConfigOverrides: Map[ConfigScope, TargetConfigFieldsP] =
      getScopeToOverrideMap(
        targetConfig.overrides,
        (scopeOverride: TargetConfigOverrideP) =>
          (scopeOverride.overrideScopes, scopeOverride.getOverrideConfig)
      )
    // Compute overrides for all the scopes in the advanced target config.
    val advancedConfigOverrides: Map[ConfigScope, AdvancedTargetConfigFieldsP] =
      getScopeToOverrideMap(
        advancedConfig.overrides,
        (scopeOverride: AdvancedTargetConfigOverrideP) =>
          (scopeOverride.overrideScopes, scopeOverride.getOverrideConfig)
      )

    // Compute the default config values.
    val defaultTargetConfig: TargetConfigFieldsP = targetConfig.getDefaultConfig
    val defaultAdvancedConfig: AdvancedTargetConfigFieldsP = advancedConfig.getDefaultConfig

    // Create InternalTargetConfig based on the default sub-configs.
    val defaultConfig: InternalTargetConfig = InternalTargetConfig.fromProtos(
      defaultTargetConfig,
      defaultAdvancedConfig
    )

    // Create InternalTargetConfig based on per-scope overrides.
    val scopes: Set[ConfigScope] = targetConfigOverrides.keySet ++ advancedConfigOverrides.keySet
    val overrideConfigs: Map[ConfigScope, InternalTargetConfig] = {
      scopes.map { scope: ConfigScope =>
        val targetConfigOverrideOpt: Option[TargetConfigFieldsP] = targetConfigOverrides.get(scope)
        val advancedConfigOverrideOpt: Option[AdvancedTargetConfigFieldsP] =
          advancedConfigOverrides.get(scope)
        scope ->
        InternalTargetConfig.fromProtos(
          mergeOverride(defaultTargetConfig, targetConfigOverrideOpt),
          mergeOverride(defaultAdvancedConfig, advancedConfigOverrideOpt)
        )
      }
    }.toMap
    TargetDefaultAndOverride(defaultConfig, overrideConfigs)
  }

  /**
   * REQUIRES: the overrides do not include duplicated scopes.
   *
   * Generates a mapping from all the scopes to their corresponding target or advance target
   * overrides.
   *
   * @param scopeOverrides a list of overrides defined in a config file.
   * @param getScopeAndOverride A function that takes in an [[OverrideP]] and returns specific
   *                            [[FieldsP]] and the list of scopes these values apply to.
   * @tparam OverrideP Type of the override proto message, which is typically
   *                   [[TargetConfigOverrideP]] and [[AdvancedTargetConfigOverrideP]]
   * @tparam FieldsP Type of the field proto message, which is typically
   *                 [[TargetConfigFieldsP]] and [[AdvancedTargetConfigFieldsP]]
   */
  private def getScopeToOverrideMap[
      OverrideP <: scalapb.Message[OverrideP],
      FieldsP <: scalapb.Message[FieldsP]](
      scopeOverrides: Seq[OverrideP],
      getScopeAndOverride: OverrideP => (Seq[ConfigScopeP], FieldsP)): Map[ConfigScope, FieldsP] = {
    val scopeToOverride = mutable.Map[ConfigScope, FieldsP]()
    for (scopeOverride: OverrideP <- scopeOverrides) {
      val (scopePs, overrideValues): (Seq[ConfigScopeP], FieldsP) =
        getScopeAndOverride(scopeOverride)
      for (scopeP: ConfigScopeP <- scopePs) {
        val scope: ConfigScope = ConfigScope.fromProto(scopeP)
        require(!scopeToOverride.contains(scope), "Scope cannot be duplicated in overrides")
        scopeToOverride(scope) = overrideValues
      }
    }
    scopeToOverride.toMap
  }

  /** Merges the default value and the override value using proto-merge. */
  private def mergeOverride[T <: scalapb.Message[T] with scalapb.GeneratedMessage](
      baseMessage: T,
      overrideMessageOpt: Option[T]): T = {
    overrideMessageOpt match {
      case Some(overrideMessage: T) =>
        baseMessage.mergeFrom(CodedInputStream.newInstance(overrideMessage.toByteArray))
      case None =>
        baseMessage
    }
  }

  /**
   * Finds the target and advance target config overrides (if any) for the given `configScopeOpt`.
   */
  private def getScopeOverride(
      configScopeOpt: Option[ConfigScope],
      targetConfig: TargetConfigP,
      advancedConfig: AdvancedTargetConfigP)
      : (Option[TargetConfigFieldsP], Option[AdvancedTargetConfigFieldsP]) = {
    val targetConfigOverrideOpt: Option[TargetConfigFieldsP] =
      configScopeOpt.flatMap { scope: ConfigScope =>
        ConfigScope
          .findShardOverride(scope, targetConfig.overrides.map {
            overrideProto: TargetConfigOverrideP =>
              (overrideProto.overrideScopes, overrideProto.getOverrideConfig)
          })
      }

    val advancedConfigOverrideOpt: Option[AdvancedTargetConfigFieldsP] =
      configScopeOpt.flatMap { scope: ConfigScope =>
        ConfigScope
          .findShardOverride(
            scope,
            advancedConfig.overrides.map { overrideProto: AdvancedTargetConfigOverrideP =>
              (overrideProto.overrideScopes, overrideProto.getOverrideConfig)
            }
          )
      }
    (targetConfigOverrideOpt, advancedConfigOverrideOpt)
  }
}

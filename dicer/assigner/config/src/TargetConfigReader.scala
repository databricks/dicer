package com.databricks.dicer.assigner.config

import java.io.File
import java.nio.file.{Files, Path}
import java.util.stream.{Stream => JavaStream}

import scala.collection.mutable
import scala.io.{BufferedSource, Source}
import scala.util.control.NonFatal

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
import com.databricks.dicer.common.TargetName
import com.google.protobuf.{CodedInputStream, DynamicMessage, TextFormat, TypeRegistry}

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

  /**
   * Maximum depth, relative to the config root directory, that [[readTargetConfigProtos]] walks
   * when discovering textprotos. The supported layouts are flat (`<root>/<service>.textproto`, at
   * depth 1) and per-service subdirectory (`<root>/<service>/<service>.textproto`, at depth 2), so
   * a depth of 2 covers both. Bounding the walk documents the layout contract and prevents a stray
   * `.textproto` nested deeper than the layout allows from being silently registered as a target.
   */
  private val MAX_CONFIG_WALK_DEPTH: Int = 2

  private val CONFIG_FILE_SUFFIX: String = ".textproto"

  /**
   * A [[TypeRegistry]] containing every concrete authorizer-config descriptor that may appear
   * inside the textproto `authorizer` `Any` field on `TargetConfigFieldsP`. Used by
   * [[TEXT_FORMAT_PROTO_ANY_PARSER]] to resolve the `[type.googleapis.com/<name>]` extension
   * syntax. Defined separately from
   * [[InternalTargetConfigJsonConverter.typeRegistry]] (which serves the JSON path) because the
   * textproto and JSON paths can evolve independently and the JSON-side type registry is not
   * available in OSS.
   */
  private val textProtoTypeRegistry: TypeRegistry = TypeRegistry
    .newBuilder()
    .build()

  /**
   * A textproto parser configured with [[textProtoTypeRegistry]] so that [[google.protobuf.Any]]
   * fields can be written using the human-readable extension syntax
   * `[type.googleapis.com/MyType] { field: value }` instead of the binary-encoded `value` bytes.
   *
   * A [[TextFormat.Parser]] reads protobuf text format and applies it to a Java
   * [[com.google.protobuf.Message.Builder]] via [[TextFormat.Parser.merge]]; [[parseTextProto]] is
   * the only call site. The parser uses the descriptors registered on the `TypeRegistry` to
   * resolve a `type.googleapis.com/<name>` URL to the concrete proto message type at parse time;
   * URLs not in the registry raise [[TextFormat.ParseException]] during `merge`. ScalaPB's own
   * text parser does not accept a `TypeRegistry`, which is why the Java parser is used here.
   *
   * Reference: https://protobuf.dev/reference/java/api-docs/com/google/protobuf/TextFormat.Parser
   */
  private val TEXT_FORMAT_PROTO_ANY_PARSER: TextFormat.Parser = TextFormat.Parser
    .newBuilder()
    .setTypeRegistry(textProtoTypeRegistry)
    .build()

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

        // Merge the base and override configs to get the "fields" config protos. The authorizer
        // lives on TargetConfigFieldsP, so InternalTargetConfig.fromProtos parses it from the
        // (already merged) `configFields`. Per-scope overrides for the authorizer field are not
        // supported by convention (see the proto Scaladoc); callers should set it only on
        // `default_config`.
        val configFields: TargetConfigFieldsP =
          mergeOverride(targetConfigProto.getDefaultConfig, targetOverrideOpt)
        val advancedConfigFields: AdvancedTargetConfigFieldsP =
          mergeOverride(
            advancedConfigProto.getDefaultConfig,
            advancedTargetOverrideOpt
          )

        targetName -> InternalTargetConfig.fromProtos(configFields, advancedConfigFields)
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
   * Reads all target config protos from the given directory tree using
   * [[TEXT_FORMAT_PROTO_ANY_PARSER]] with [[TypeRegistry]] support, allowing
   * `[type.googleapis.com/MyType] { field: value }` syntax for [[google.protobuf.Any]] fields.
   *
   * Walks `configRootDir` to a depth of [[MAX_CONFIG_WALK_DEPTH]]. Any file whose name ends in
   * `.textproto` is parsed; other files (e.g. OWNERS, README.md) are ignored. The target name is
   * the textproto filename without the extension, regardless of which subdirectory the file lives
   * in. Symlinks are not followed: [[Files.walk]] without [[java.nio.file.FileVisitOption]] skips
   * them, and the config tree is not expected to contain symlinked textprotos.
   *
   * Target names must be unique across the entire tree. We check for duplicate target names
   * explicitly and fail loudly rather than letting one config silently shadow another.
   *
   * @throws IllegalArgumentException if `configRootDir` exists but is not a directory, two
   *                                  textprotos resolve to the same target name, or any config
   *                                  cannot be read or parsed.
   */
  @throws[IllegalArgumentException]
  private def readTargetConfigProtos[
      ConfigP <: scalapb.Message[ConfigP] with scalapb.GeneratedMessage](
      messageCompanion: scalapb.GeneratedMessageCompanion[ConfigP],
      configRootDir: File): Map[TargetName, ConfigP] = {
    if (!configRootDir.exists) {
      // TODO(<internal bug>): Change this to a requirement once we enforce target configuration.
      logger.warn(s"No directory found for target configuration at $configRootDir")
      return Map.empty
    }
    if (!configRootDir.isDirectory) {
      throw new IllegalArgumentException(
        s"Expected directory with target configuration. $configRootDir is not a directory."
      )
    }
    val configMap = mutable.Map.empty[TargetName, ConfigP]
    // Tracks the file each target name was first seen in, so the error message can name both paths.
    val configPaths = mutable.Map.empty[TargetName, File]
    val stream: JavaStream[Path] = Files.walk(configRootDir.toPath, MAX_CONFIG_WALK_DEPTH)
    try {
      val iter: java.util.Iterator[Path] = stream.iterator()
      while (iter.hasNext) {
        val configFile: File = iter.next().toFile
        if (configFile.isFile && configFile.getName.endsWith(CONFIG_FILE_SUFFIX)) {
          val targetName: TargetName =
            TargetName(configFile.getName.stripSuffix(CONFIG_FILE_SUFFIX))
          configPaths.get(targetName) match {
            case Some(existingFile: File) =>
              throw new IllegalArgumentException(
                s"Duplicate target configuration for $targetName: " +
                s"${existingFile.getPath} and ${configFile.getPath}"
              )
            case None =>
              configPaths += targetName -> configFile
              configMap += targetName -> readTargetConfigProto(messageCompanion, configFile)
          }
        }
      }
    } finally {
      stream.close()
    }
    configMap.toMap
  }

  /**
   * Reads `configFile` and parses its contents into a `ConfigP` (via
   * [[TEXT_FORMAT_PROTO_ANY_PARSER]]). The file is always closed before this method returns,
   * including on failure. Both read and parse failures name `configFile`'s path so the offending
   * file is identifiable when a single bad config aborts the whole load.
   *
   * @throws IllegalArgumentException if `configFile` cannot be read, or its contents cannot be
   *                                  parsed as `ConfigP`.
   */
  @throws[IllegalArgumentException]
  private def readTargetConfigProto[
      ConfigP <: scalapb.Message[ConfigP] with scalapb.GeneratedMessage](
      messageCompanion: scalapb.GeneratedMessageCompanion[ConfigP],
      configFile: File): ConfigP = {
    val fileSource: BufferedSource = Source.fromFile(configFile, "utf-8")
    val blob: String =
      try {
        fileSource.mkString
      } catch {
        case NonFatal(e) =>
          throw new IllegalArgumentException(
            s"Failed to read target configuration ${configFile.getPath}: ${e.getMessage}",
            e
          )
      } finally {
        fileSource.close()
      }

    try {
      parseTextProto(blob, messageCompanion)
    } catch {
      case e: TextFormat.ParseException =>
        throw new IllegalArgumentException(
          s"Bad textproto format in ${configFile.getPath}: ${e.getMessage}",
          e
        )
    }
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
   * @param scopeOverrides      a list of overrides defined in a config file.
   * @param getScopeAndOverride A function that takes in an [[OverrideP]] and returns specific
   *                            [[FieldsP]] and the list of scopes these values apply to.
   * @tparam OverrideP Type of the override proto message, which is typically
   *                   [[TargetConfigOverrideP]] and [[AdvancedTargetConfigOverrideP]]
   * @tparam FieldsP   Type of the field proto message, which is typically
   *                   [[TargetConfigFieldsP]] and [[AdvancedTargetConfigFieldsP]]
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
          .findScopeOverride(scope, targetConfig.overrides.map {
            overrideProto: TargetConfigOverrideP =>
              (overrideProto.overrideScopes, overrideProto.getOverrideConfig)
          })
      }

    val advancedConfigOverrideOpt: Option[AdvancedTargetConfigFieldsP] =
      configScopeOpt.flatMap { scope: ConfigScope =>
        ConfigScope
          .findScopeOverride(
            scope,
            advancedConfig.overrides.map { overrideProto: AdvancedTargetConfigOverrideP =>
              (overrideProto.overrideScopes, overrideProto.getOverrideConfig)
            }
          )
      }
    (targetConfigOverrideOpt, advancedConfigOverrideOpt)
  }

  /** Parses a textproto blob into a ScalaPB message via [[TEXT_FORMAT_PROTO_ANY_PARSER]]. */
  @throws[TextFormat.ParseException](
    "if `blob` is not valid textproto for `messageCompanion`'s descriptor, or contains a " +
    "`[type.googleapis.com/...]` URL not registered on [[TEXT_FORMAT_PROTO_ANY_PARSER]]."
  )
  private def parseTextProto[ConfigP <: scalapb.Message[ConfigP] with scalapb.GeneratedMessage](
      blob: String,
      messageCompanion: scalapb.GeneratedMessageCompanion[ConfigP]): ConfigP = {
    // Processing steps: textproto string -> [[DynamicMessage]] -> bytes -> ScalaPB.
    //
    // Why we route through DynamicMessage and bytes? In the config textproto, we want to support
    // configuring the authorizer (e.g. AppIdentifierWorkloadAuthorizerConfigP) as an [[Any]] field
    // using the human-readable `[type.googleapis.com/...] {app_name: ...}` syntax. The Scala
    // textproto parser (e.g. `messageCompanion.fromAscii`) does not understand this syntax, but
    // the Java parser does. So we first use the Java parser to read the textproto into a Java
    // proto instance, then serialize it to bytes -- a representation that is not human-readable
    // but that our Scala library can consume -- and finally let the Scala library parse those
    // bytes to produce the Scala proto message.
    //
    // 1. Configured by the user:
    //
    //    authorizer {
    //      [type.googleapis.com/databricks.dicer.external.AppIdentifierWorkloadAuthorizerConfigP] {
    //        app_name: "auth-app"
    //      }
    //    }
    //
    // 2. Parsed by the Java proto library into a Java proto representation (inside `javaBuilder`).
    //
    // 3. Serialized again to a byte representation that the Scala library can consume,
    //    conceptually equivalent to:
    //
    //    authorizer {
    //      type_url: ".../databricks.dicer.external.AppIdentifierWorkloadAuthorizerConfigP"
    //      value: "0x03ef2a32"
    //    }
    //
    // 4. Read by the Scala proto library, yielding the final Scala `ConfigP`.
    //
    // We isolate this logic in its own function because `Message.Builder` is mutable: a caller
    // that held onto one could call `merge` repeatedly and accumulate state across blobs.
    // Restricting `javaBuilder`'s scope to this function prevents that.

    // A Java proto message builder that holds the intermediate Java representation of `ConfigP`.
    // We use [[DynamicMessage]] because the repo has no Java codegen for `ConfigP`, so we cannot
    // construct a concrete `Message.Builder`. To avoid adding unnecessary Java proto codegen and
    // increasing build time, we use [[DynamicMessage.newBuilder]], which yields a builder driven
    // entirely by a runtime descriptor obtained from the ScalaPB companion's `javaDescriptor`, so
    // no Java codegen is required. Spec:
    // https://protobuf.dev/reference/java/api-docs/com/google/protobuf/DynamicMessage.
    val javaBuilder: DynamicMessage.Builder =
      DynamicMessage.newBuilder(messageCompanion.javaDescriptor)

    // Parse the textproto blob and write the resulting Java proto message into `javaBuilder`.
    // TEXT_FORMAT_PROTO_ANY_PARSER knows how to parse the `[type.googleapis.com/...]` syntax for
    // the [[Any]] field in the textproto; `javaBuilder` knows the final `ConfigP` type. Note that
    // `ConfigP` type doesn't know the concrete authorizer type (as it holds an "Any" type as the
    // authorizer field), so we need the TEXT_FORMAT_PROTO_ANY_PARSER to tell us how to parse the
    // AppIdentifierWorkloadAuthorizerP from the textproto. See more comments on
    // TEXT_FORMAT_PROTO_ANY_PARSER definition.
    TEXT_FORMAT_PROTO_ANY_PARSER.merge(blob, javaBuilder)

    // Convert the Java proto representation to the Scala proto by first serializing it to bytes
    // and then parsing those bytes back through the Scala proto library.
    messageCompanion.parseFrom(javaBuilder.build().toByteArray)
  }
}

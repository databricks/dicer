package com.databricks.dicer.assigner

import scala.collection.mutable

import com.databricks.api.proto.caching.external.ConfigScopeP
import com.databricks.api.proto.dicer.assigner.config.{
  InternalDicerOverrideP,
  InternalDicerTargetConfigP
}
import com.databricks.caching.util.SafeConfigUtil.DICER_TARGET_CONFIG_FLAGS_NAME_PREFIX
import com.databricks.caching.util.{ClusterConfigScope, ConfigScope, InstanceConfigScope}
import com.databricks.dicer.assigner.conf.DicerAssignerConf
import com.databricks.featureflag.client.experimentation.MockFeatureFlagReaderProvider
import com.databricks.featureflag.client.utils.RuntimeContext
import com.databricks.rpc.DatabricksObjectMapper
import com.typesafe.config.Config

import com.databricks.dicer.assigner.config.{
  InternalTargetConfig,
  InternalTargetConfigJsonConverter,
  NamedInternalTargetConfig
}
import com.databricks.dicer.common.TargetName

/**
 * Specialization of [[DicerAssignerConf]] that mixes in test support and fakes out SAFE feature
 * flag support.
 */
class TestableDicerAssignerConf(config: Config)
    extends DicerAssignerConf(config)
    with MockFeatureFlagReaderProvider {

  /** Sets the test value for the logging sample fraction SAFE flag. */
  def setLoggingSampleFraction(fraction: Double): Unit = {
    mockFeatureFlagReader.setMockValue(
      protoLoggerGenerationSampleFractionFlag.flagName,
      fraction,
      runtimeCtxOpt = Some(RuntimeContext.EMPTY)
    )
  }

  /** Names of targets that have dynamic configurations. */
  private val targetNames = mutable.ArrayBuffer.empty[String]

  /**
   * Whether requests to the Assigner are expected to come through S2S Proxy.
   *
   * The `TestAssigner` checks verifies that requests come through `FakeS2SProxy` proxy if and only
   * if this is true.
   */
  def expectRequestsThroughS2SProxy: Boolean = false

  /**
   * Updates dynamic configuration targets to include the given parsed `config` and
   * `overrides`.
   */
  def putDynamicTargetConfig(
      targetName: TargetName,
      config: InternalTargetConfig,
      overrides: Map[ConfigScope, InternalTargetConfig]): Unit = {
    // NamedInternalTargetConfig.toProto omits scoped entries, so add them to the proto's
    // overrides field explicitly.
    // TODO(<internal bug>): Use toJsonString directly once it serializes overrides.
    val protoWithEmptyEffectiveConfigMap: InternalDicerTargetConfigP =
      NamedInternalTargetConfig(targetName, config, overrides = Map.empty).toProto
    val overrideProtos: Seq[InternalDicerOverrideP] = overrides.map {
      case (scope: ConfigScope, scopedConfig: InternalTargetConfig) =>
        val configScopeProto: ConfigScopeP = scope match {
          case ClusterConfigScope(clusterUri: String) => ConfigScopeP().withClusterUri(clusterUri)
          case InstanceConfigScope(instanceId: String) => ConfigScopeP().withInstanceId(instanceId)
        }
        // Creating a NamedInternalTargetConfig is a hack to extract the target_config and
        // advanced_config proto fields.
        // TODO(<internal bug>): This will be removed once toJsonString serializes the overrides.
        val scopedProto: InternalDicerTargetConfigP =
          NamedInternalTargetConfig(
            targetName,
            scopedConfig,
            overrides = Map.empty
          ).toProto
        InternalDicerOverrideP(
          overrideScope = Some(configScopeProto),
          targetConfig = scopedProto.targetConfig,
          advancedConfig = scopedProto.advancedConfig
        )
    }.toSeq
    val protoWithOverrides: InternalDicerTargetConfigP =
      protoWithEmptyEffectiveConfigMap.copy(overrides = overrideProtos)
    val json: String = InternalTargetConfigJsonConverter.toJsonString(protoWithOverrides)
    putDynamicTargetConfig(targetName.value, json)
  }

  /**
   * Updates dynamic configuration to include an entry with the given (possibly invalid) target name
   * and (possibly invalid) config json.
   */
  def putDynamicTargetConfig(targetName: String, jsonConfig: String): Unit = {
    val key: String = s"$DICER_TARGET_CONFIG_FLAGS_NAME_PREFIX$targetName"
    mockFeatureFlagReader.setMockValue(key, jsonConfig)

    // The batch flag must enumerate the dynamically configured target flags.
    targetNames += targetName
    val flagNames = targetNames.map { targetName: String =>
      s"$DICER_TARGET_CONFIG_FLAGS_NAME_PREFIX$targetName"
    }
    mockFeatureFlagReader.setMockValue(
      targetConfigBatchFlag.flagName,
      DatabricksObjectMapper.toJson(flagNames)
    )
  }

  /** Switches on / off dynamic config by setting the value of enableDynamicConfig. */
  def setDynamicConfig(enabled: Boolean): Unit = {
    // According to the guide of SAFE flag unit tests:
    // <internal link>
    // due to the design limitations of the test-only `mockFeatureFlagReader`, it's important that
    // the runtime context where we set the value with `mockFeatureFlagReader.setMockValue` matches
    // the context where we retrieve the value using `getCurrentValue`. In production Dicer,
    // we use  `getCurrentValue(RuntimeContext.EMPTY)` for the `enableDynamicConfig` flag.
    // Therefore, to ensure consistency, we should use the same runtime context setting in the unit
    // tests here.
    // Please note this is a test-only limitation. In production code, the `getCurrentValue` will
    // get the correct value whatever the runtime context we use when setting the flag value.
    mockFeatureFlagReader
      .setMockValue(
        enableDynamicConfig.flagName,
        value = enabled,
        runtimeCtxOpt = Some(RuntimeContext.EMPTY)
      )
  }

  /** Clears all dynamic updates. */
  def clearDynamicConfigs(): Unit = {
    targetNames.clear()
    mockFeatureFlagReader.setMockValue(
      targetConfigBatchFlag.flagName,
      DatabricksObjectMapper.toJson(Seq.empty)
    )
  }
}

package com.databricks.dicer.assigner.config

import com.databricks.dicer.assigner.conf.DicerAssignerConf

import scala.concurrent.duration.FiniteDuration

/** Creates the static target config provider used by OSS builds. */
object TargetConfigProviderFactory {

  /**
   * Creates the OSS static target config provider. The signature matches the internal factory, and
   * `initialPollTimeout` is ignored.
   *
   * @param staticTargetConfigMap the static target configs shipped with the Assigner binary.
   * @param assignerConf the Assigner configuration passed through to the provider.
   * @param initialPollTimeout ignored in OSS; accepted to match the internal factory signature.
   */
  def createBlocking(
      staticTargetConfigMap: InternalTargetConfigMap,
      assignerConf: DicerAssignerConf,
      initialPollTimeout: FiniteDuration): TargetConfigProvider = {
    StaticTargetConfigProvider.createBlocking(
      staticTargetConfigMap,
      assignerConf,
      initialPollTimeout
    )
  }
}

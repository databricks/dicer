package com.databricks.dicer.client.featurerollouts

import com.databricks.dicer.external.Target
import javax.annotation.concurrent.ThreadSafe

/**
 * Determines whether a named Dicer client feature is enabled for a given target. The only
 * production implementation is [[DicerClientFeatureRolloutFlagImpl]]. See
 * `dicer/client/feature-rollouts/proto/dicer_client_feature_rollout_config.proto` for a detailed
 * usage.
 *
 * User code obtains the production instance through `DicerClientConf.dicerClientFeatureRollout`;
 * the trait itself will be used by both the production code and the unit test for test value
 * injection purpose.
 * TODO(<internal bug>): Add the above features in a following-up PR.
 *
 * In production code, isEnabled it should be queried once at Slicelet or Clerk creation time at the
 * top-level, and the flag value should be passed into the rest of the code via InternalClientConfig
 * or directly passed to the components consuming this config.
 */
@ThreadSafe
trait DicerClientFeatureRolloutFlag {

  /**
   * Returns whether the feature identified by `flagName` is enabled for `target` in the current
   * region. Returns false when the feature has no configured rollout for the current region, when
   * `flagName` matches no known feature, or when the deployment environment or region cannot be
   * resolved at process startup. In these unexpected cases, an CachingDegradedError alert will be
   * fired.
   */
  def isEnabled(flagName: String, target: Target): Boolean
}

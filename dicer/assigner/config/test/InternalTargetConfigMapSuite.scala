package com.databricks.dicer.assigner.config

import java.io.File

import scala.concurrent.duration._

import com.databricks.caching.util.ConfigScope
import com.databricks.dicer.assigner.config.InternalTargetConfig.LoadWatcherTargetConfig
import com.databricks.dicer.common.TargetName
import com.databricks.testing.DatabricksTest

class InternalTargetConfigMapSuite extends DatabricksTest {

  private val AWS_US_WEST_2_SCOPE = ConfigScope("kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01")

  test("InternalTargetConfigMap.contains checks existence of configs correctly") {
    // Test plain: Verify that [[InternalTargetConfigMap]]'s `contains` method returns whether
    // it contains the config for a target.
    // Check this by creating InternalTargetConfigMap from a directory with target config files and
    // a directory with advanced target config files, verifying that `contains` returns `true` for
    // the targets that exists under these directories and returns `false` for the non existing
    // ones.
    val targetConfigMap =
      InternalTargetConfigMap.create(
        Some(AWS_US_WEST_2_SCOPE),
        new File("dicer/external/config/dev"),
        new File("dicer/assigner/advanced_config/dev")
      )

    val existingTargetName = TargetName("softstore-storelet")
    val nonExistentTargetName = TargetName("foo-bar")

    assert(targetConfigMap.get(existingTargetName).isDefined)
    assert(targetConfigMap.get(nonExistentTargetName).isEmpty)
  }

  test("InternalTargetConfigMap fallbacks to default configuration for test environment") {
    // Test plan: Create InternalTargetConfigMap.forTest with a specified map of configuration.
    // Expect it to load configuration from the specified map and fallback to default configuration
    // for targets that do not exist.

    // Create custom target config, something other than default.
    val customTargetConfig = InternalTargetConfig.forTest.DEFAULT
      .copy(loadWatcherConfig = LoadWatcherTargetConfig.DEFAULT.copy(minDuration = 5.minutes))

    val targetConfigMap = InternalTargetConfigMap.create(
      Some(AWS_US_WEST_2_SCOPE),
      Map(
        TargetName("softstore-storelet") -> customTargetConfig
      )
    )

    // Should load configuration from map.
    assert(targetConfigMap.get(TargetName("softstore-storelet")).contains(customTargetConfig))

    // Should return empty, since target does not exist in the specified map.
    assert(targetConfigMap.get(TargetName("foo-bar")).isEmpty)
  }
}

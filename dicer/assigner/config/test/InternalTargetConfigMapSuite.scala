package com.databricks.dicer.assigner.config

import java.io.File

import scala.concurrent.duration._

import com.databricks.caching.util.ClusterConfigScope
import com.databricks.dicer.assigner.config.InternalTargetConfig.LoadWatcherTargetConfig
import com.databricks.dicer.common.TargetName
import com.databricks.testing.DatabricksTest

class InternalTargetConfigMapSuite extends DatabricksTest {

  private val TEST_CLUSTER_SCOPE = ClusterConfigScope(
    "kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01"
  )

  test(
    "InternalTargetConfigMap.getClusterScoped returns Some for targets that exist in config " +
    "directory and None for those that don't"
  ) {
    // Test plan: Verify that [[InternalTargetConfigMap]]'s `getClusterScoped` method returns the
    // config for a target if present in the config directory and `None` otherwise.
    // Check this by creating InternalTargetConfigMap from a directory with target config files and
    // a directory with advanced target config files, verifying that `getClusterScoped` returns
    // `Some` for targets in these directories and `None` for missing targets.
    val targetConfigMap =
      InternalTargetConfigMap.create(
        Some(TEST_CLUSTER_SCOPE),
        new File("dicer/external/config/dev"),
        new File("dicer/assigner/advanced_config/dev")
      )

    val existingTargetName = TargetName("softstore-storelet")
    val nonExistentTargetName = TargetName("foo-bar")

    assert(targetConfigMap.getClusterScoped(existingTargetName).isDefined)
    assert(targetConfigMap.getClusterScoped(nonExistentTargetName).isEmpty)
  }

  test(
    "InternalTargetConfigMap.create from a map returns Some for supplied configs " +
    "and None otherwise"
  ) {
    // Test plan: Verify that an InternalTargetConfigMap built from an explicit
    // (TargetName -> InternalTargetConfig) map returns the supplied config via `getClusterScoped`
    // for known targets, and returns `None` for targets absent from the map.

    // Create custom target config, something other than default.
    val customTargetConfig = InternalTargetConfig.forTest.DEFAULT
      .copy(loadWatcherConfig = LoadWatcherTargetConfig.DEFAULT.copy(minDuration = 5.minutes))

    val targetConfigMap = InternalTargetConfigMap.create(
      Some(TEST_CLUSTER_SCOPE),
      Map(
        TargetName("softstore-storelet") -> customTargetConfig
      )
    )

    // Should load configuration from map.
    assert(
      targetConfigMap
        .getClusterScoped(TargetName("softstore-storelet"))
        .contains(customTargetConfig)
    )

    // Should return empty, since target does not exist in the specified map.
    assert(targetConfigMap.getClusterScoped(TargetName("foo-bar")).isEmpty)
  }
}

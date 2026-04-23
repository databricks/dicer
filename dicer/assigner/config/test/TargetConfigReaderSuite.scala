package com.databricks.dicer.assigner.config

import java.io.File

import com.databricks.api.proto.dicer.external.LoadBalancingMetricConfigP.{
  ImbalanceToleranceHintP,
  ReservationHintP
}
import com.databricks.caching.util.{ConfigScope, TestUtils}
import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.dicer.assigner.config.ConfigTestUtil.{ConfigWriter, createConfig}
import com.databricks.dicer.assigner.config.TargetConfigReader.TargetDefaultAndOverride
import com.databricks.dicer.common.TargetName
import com.databricks.testing.DatabricksTest

class TargetConfigReaderSuite extends DatabricksTest with TestUtils.TestName {

  test("Not throw if invalid directory is specified") {
    // Test plan: Read configs from a invalid path and expect it to return an empty map.
    assert(
      TargetConfigReader
        .readScopeConfigMapFromDirectories(
          configScopeOpt = None,
          targetConfigDirectory = new File("dicer/external/config/foo"),
          advancedTargetConfigDirectory = new File("dicer/external/config/bar")
        )
        .isEmpty
    )
  }

  test("Throws if a file is specified for config directory") {
    // Test plan: Attempt to read configs from a file instead of directory and expect it to throw.
    assertThrow[IllegalArgumentException](
      "Expected directory with target configuration"
    ) {
      TargetConfigReader
        .readScopeConfigMapFromDirectories(
          configScopeOpt = None,
          targetConfigDirectory = new File(
            "dicer/external/config/dev/softstore-storelet.textproto"
          ),
          advancedTargetConfigDirectory = new File("dicer/assigner/advanced_config/dev")
        )
    }
    assertThrow[IllegalArgumentException](
      "Expected directory with target configuration"
    ) {
      TargetConfigReader
        .readScopeConfigMapFromDirectories(
          configScopeOpt = None,
          targetConfigDirectory = new File("dicer/external/config/dev"),
          advancedTargetConfigDirectory =
            new File("dicer/assigner/advanced_config/dev/softstore-storelet.textproto")
        )
    }
  }

  test("Invalid config") {
    // Test plan: write invalid configurations and verify that attempts to construct
    // InternalTargetConfig fail with the expected error messages.
    case class TestCase(
        description: String,
        filename: String,
        contents: String,
        expectedMessage: String)
    val testCases = Seq[TestCase](
      TestCase(
        "Invalid textproto",
        "invalid.textproto",
        "this is not a textproto",
        "Bad textproto format"
      ),
      TestCase(
        "Illegal target name",
        "target_names_have_no_undescores.textproto",
        """default_config {
          |  primary_rate_metric_config {
          |    max_load_hint: 1000
          |  }
          |}""".stripMargin,
        "Target name must match regex"
      ),
      TestCase(
        "Missing primary rate max_load_hint",
        "softstore-storelet.textproto",
        """default_config {
          |  primary_rate_metric_config {
          |    # max_load_hint: 1000
          |  }
          |}""".stripMargin,
        "requirement failed: max load must be a positive, finite number"
      )
    )
    for (testCase: TestCase <- testCases) {
      val configWriter = new ConfigWriter
      configWriter.writeConfig(testCase.filename, testCase.contents)
      logger.info(s"Parsing invalid config: $testCase.description")
      assertThrow[IllegalArgumentException](testCase.expectedMessage) {
        TargetConfigReader.readScopeConfigMapFromDirectories(
          configScopeOpt = None,
          configWriter.getTargetConfigDirectory,
          configWriter.getAdvancedTargetConfigDirectory
        )
      }
    }
  }

  test("All advanced configs should have corresponding target configs") {
    // Test plan: create a target whose target config is missing, expect exceptions being thrown.
    val configWriter = new ConfigWriter
    val targetName = TargetName("target-without-target-config")
    configWriter.writeAdvancedConfig(
      s"$targetName.textproto",
      """default_config {
        |  load_watcher_config {
        |    min_duration_seconds: 30
        |  }
        |}
        |""".stripMargin
    )
    assertThrow[IllegalArgumentException](
      "target-without-target-config should have corresponding target configs."
    ) {
      TargetConfigReader.readScopeConfigMapFromDirectories(
        configScopeOpt = None,
        configWriter.getTargetConfigDirectory,
        configWriter.getAdvancedTargetConfigDirectory
      )
    }
  }

  test("Regional overrides are correctly applied") {
    // Test plan: Create InternalTargetConfigs from a valid file path including configurations
    // with overrides. Validate that the expected override is used for each region. Overrides are
    // defined for the cons-manager target in cloud1-region2 (`tightConfig`), and in
    // cloud1-region1/cloud2-region4 (`highCapacityConfig`). All other scopes should use the default
    // configuration (`defaultManagerConfig`). Target softstore-storelet  does not have any regular
    // config overrides, but does specify an advanced override in cloud1-region2
    // (`lessHistoryConfig`).

    // Define targets for which we have explicit configurations.
    val managerTargetName = TargetName("cons-manager")
    val softstoreTargetName = TargetName("softstore-storelet")

    // Write configuration files.
    val configWriter = new ConfigWriter
    configWriter.writeConfig(
      s"$managerTargetName.textproto",
      """default_config {
        |  primary_rate_metric_config {
        |    # 4500(AWS-MT) * 0.85(threshold) * 1000(millis/seconds)
        |    max_load_hint: 3825000
        |    imbalance_tolerance_hint: DEFAULT
        |    uniform_load_reservation_hint: MEDIUM_RESERVATION
        |  }
        |}
        |overrides {
        |  # `tightConfig` override.
        |  override_scopes {
        |    cluster_uri: "kubernetes-cluster:test-env/cloud1/public/region2/clustertype2/01"
        |  }
        |  override_config {
        |    primary_rate_metric_config {
        |      imbalance_tolerance_hint: TIGHT
        |      uniform_load_reservation_hint: LARGE_RESERVATION
        |    }
        |  }
        |}
        |overrides {
        |  # `highCapacityConfig` override.
        |  override_scopes {
        |    cluster_uri: "kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01"
        |  }
        |  override_scopes {
        |    cluster_uri: "kubernetes-cluster:test-env/cloud2/public/region4/clustertype2/01"
        |  }
        |  override_config {
        |    primary_rate_metric_config {
        |      max_load_hint: 4000000
        |    }
        |  }
        |}
        |""".stripMargin
    )
    configWriter.writeConfig(
      s"$softstoreTargetName.textproto",
      """default_config {
        |  primary_rate_metric_config {
        |    max_load_hint: 1610
        |    imbalance_tolerance_hint: TIGHT
        |  }
        |}
        |overrides {
        |  override_scopes {
        |    cluster_uri: "kubernetes-cluster:test-env/cloud2/public/region4/clustertype2/01"
        |  }
        |  override_config {
        |    primary_rate_metric_config {
        |      max_load_hint: 2000
        |    }
        |  }
        |}
        |""".stripMargin
    )
    configWriter.writeAdvancedConfig(
      s"$softstoreTargetName.textproto",
      """
        |overrides {
        |  # `lessHistoryConfig` override.
        |  override_scopes {
        |    cluster_uri: "kubernetes-cluster:test-env/cloud1/public/region2/clustertype2/01"
        |  }
        |  override_config {
        |    load_watcher_config {
        |      min_duration_seconds: 30 # half of the default
        |    }
        |  }
        |}
        |""".stripMargin
    )

    // Define expected configurations.
    val defaultManagerConfig: InternalTargetConfig = createConfig(
      primaryRateMaxLoadHint = 3825000,
      primaryRateReservationHintOpt = Some(ReservationHintP.MEDIUM_RESERVATION)
    )
    val tightConfig: InternalTargetConfig =
      createConfig(
        primaryRateMaxLoadHint = 3825000,
        Some(ImbalanceToleranceHintP.TIGHT),
        Some(ReservationHintP.LARGE_RESERVATION)
      )
    val highCapacityConfig: InternalTargetConfig = createConfig(
      4000000,
      primaryRateReservationHintOpt = Some(ReservationHintP.MEDIUM_RESERVATION)
    )
    val softstoreConfig: InternalTargetConfig =
      createConfig(
        1610,
        Some(ImbalanceToleranceHintP.TIGHT)
      )
    val lessHistoryConfig: InternalTargetConfig =
      createConfig(
        1610,
        Some(ImbalanceToleranceHintP.TIGHT),
        watchMinDurationSecondsOpt = Some(30)
      )
    val largerMaxLoadConfig: InternalTargetConfig =
      createConfig(
        2000,
        Some(ImbalanceToleranceHintP.TIGHT)
      )

    // 1. Verify that the configs map for each scope is correct.
    // Load target maps for all shards with overrides, and one additional shard that has no
    // overrides declared.
    val west2Map: Map[TargetName, InternalTargetConfig] =
      TargetConfigReader.readScopeConfigMapFromDirectories(
        Some(ConfigScope("kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01")),
        configWriter.getTargetConfigDirectory,
        configWriter.getAdvancedTargetConfigDirectory
      )
    val region4Map: Map[TargetName, InternalTargetConfig] =
      TargetConfigReader.readScopeConfigMapFromDirectories(
        Some(ConfigScope("kubernetes-cluster:test-env/cloud2/public/region4/clustertype2/01")),
        configWriter.getTargetConfigDirectory,
        configWriter.getAdvancedTargetConfigDirectory
      )

    val east1Map: Map[TargetName, InternalTargetConfig] =
      TargetConfigReader.readScopeConfigMapFromDirectories(
        Some(ConfigScope("kubernetes-cluster:test-env/cloud1/public/region2/clustertype2/01")),
        configWriter.getTargetConfigDirectory,
        configWriter.getAdvancedTargetConfigDirectory
      )
    val otherMap: Map[TargetName, InternalTargetConfig] =
      TargetConfigReader.readScopeConfigMapFromDirectories(
        Some(ConfigScope("kubernetes-cluster:test-env/cloud3/public/region7/clustertype2/01")),
        configWriter.getTargetConfigDirectory,
        configWriter.getAdvancedTargetConfigDirectory
      )
    val noScopeMap: Map[TargetName, InternalTargetConfig] =
      TargetConfigReader.readScopeConfigMapFromDirectories(
        configScopeOpt = None,
        configWriter.getTargetConfigDirectory,
        configWriter.getAdvancedTargetConfigDirectory
      )

    // Verify expectations!
    assert(west2Map(managerTargetName) == highCapacityConfig)
    assert(region4Map(managerTargetName) == highCapacityConfig)
    assert(east1Map(managerTargetName) == tightConfig)
    assert(otherMap(managerTargetName) == defaultManagerConfig)
    assert(noScopeMap(managerTargetName) == defaultManagerConfig)
    for (map: Map[TargetName, InternalTargetConfig] <- Seq(west2Map, otherMap, noScopeMap)) {
      assert(map(softstoreTargetName) == softstoreConfig)
    }
    assert(region4Map(softstoreTargetName) == largerMaxLoadConfig)
    assert(east1Map(softstoreTargetName) == lessHistoryConfig)

    // 2. Verify that the full configs map is correct.
    val fullConfigs: Map[TargetName, TargetDefaultAndOverride] =
      TargetConfigReader.readFullConfigMapFromDirectories(
        configWriter.getTargetConfigDirectory,
        configWriter.getAdvancedTargetConfigDirectory
      )
    val managerConfigs: TargetDefaultAndOverride = fullConfigs(managerTargetName)
    val softstoreConfigs: TargetDefaultAndOverride = fullConfigs(softstoreTargetName)

    assert(
      managerConfigs.overrides(ConfigScope("kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01")) ==
      highCapacityConfig
    )
    assert(
      managerConfigs.overrides(ConfigScope("kubernetes-cluster:test-env/cloud2/public/region4/clustertype2/01")) ==
      highCapacityConfig
    )
    assert(
      managerConfigs
        .overrides(ConfigScope("kubernetes-cluster:test-env/cloud1/public/region2/clustertype2/01")) == tightConfig
    )
    assert(managerConfigs.default == defaultManagerConfig)

    assert(
      softstoreConfigs.overrides(ConfigScope("kubernetes-cluster:test-env/cloud1/public/region2/clustertype2/01"))
      == lessHistoryConfig
    )
    assert(
      softstoreConfigs.overrides(ConfigScope("kubernetes-cluster:test-env/cloud2/public/region4/clustertype2/01")) ==
      largerMaxLoadConfig
    )
    assert(softstoreConfigs.default == softstoreConfig)

    // cloud3-region7 has no overrides defined, so it shouldn't appear in the overrides map.
    assert(
      !managerConfigs.overrides
        .contains(ConfigScope("kubernetes-cluster:test-env/cloud3/public/region7/clustertype2/01"))
    )
    assert(
      !softstoreConfigs.overrides
        .contains(ConfigScope("kubernetes-cluster:test-env/cloud3/public/region7/clustertype2/01"))
    )
  }

  test("Scopes do not interfere with each other - duplicate overrides") {
    // Test plan: define configurations such that some config scopes are included in multiple
    // overrides. Verify that creating InternalTargetConfigs for those scopes fails, but that
    // creating InternalTargetConfigs for any other scopes (without duplicates) succeeds.
    val configWriter = new ConfigWriter
    val targetName = TargetName("dup-target")
    configWriter.writeConfig(
      s"$targetName.textproto",
      """default_config {
        |  primary_rate_metric_config {
        |    max_load_hint: 1000
        |  }
        |}
        |overrides {
        |  override_scopes {
        |    cluster_uri: "kubernetes-cluster:test-env/cloud1/public/region2/clustertype2/01"
        |  }
        |  # This duplicate is OK because it's in the same override.
        |  override_scopes {
        |    cluster_uri: "kubernetes-cluster:test-env/cloud1/public/region2/clustertype2/01"
        |  }
        |  override_scopes {
        |    cluster_uri: "kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01"
        |  }
        |  override_config {
        |    primary_rate_metric_config {
        |      max_load_hint: 2000
        |    }
        |  }
        |}
        |overrides {
        |  # This duplicate is not OK because it's in a different override.
        |  override_scopes {
        |    cluster_uri: "kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01"
        |  }
        |  override_scopes {
        |    cluster_uri: "kubernetes-cluster:test-env/cloud2/public/region4/clustertype2/01"
        |  }
        |  override_config {
        |    primary_rate_metric_config {
        |      max_load_hint: 3000
        |    }
        |  }
        |}
        |""".stripMargin
    )

    // Expect an exception creating a config map for cloud1-region1 because it is referenced in two
    // different overrides.
    assertThrow[IllegalArgumentException](
      "At most one override can be defined for the scope: " +
      "kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01"
    ) {
      TargetConfigReader.readScopeConfigMapFromDirectories(
        Some(ConfigScope("kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01")),
        configWriter.getTargetConfigDirectory,
        configWriter.getAdvancedTargetConfigDirectory
      )
    }
    // cloud1-east-1 should get the first override; cloud2-region4 should get the second override.
    val east1Config: InternalTargetConfig =
      TargetConfigReader.readScopeConfigMapFromDirectories(
        Some(ConfigScope("kubernetes-cluster:test-env/cloud1/public/region2/clustertype2/01")),
        configWriter.getTargetConfigDirectory,
        configWriter.getAdvancedTargetConfigDirectory
      )(targetName)
    val firstOverrideConfig: InternalTargetConfig = createConfig(2000)
    assert(east1Config == firstOverrideConfig)
    val region4Config: InternalTargetConfig =
      TargetConfigReader.readScopeConfigMapFromDirectories(
        Some(ConfigScope("kubernetes-cluster:test-env/cloud2/public/region4/clustertype2/01")),
        configWriter.getTargetConfigDirectory,
        configWriter.getAdvancedTargetConfigDirectory
      )(targetName)

    val secondOverrideConfig: InternalTargetConfig = createConfig(3000)
    assert(region4Config == secondOverrideConfig)

    // All other scopes should get the default config.
    val defaultConfig: InternalTargetConfig = createConfig(1000)
    for (configScopeOpt: Option[ConfigScope] <- Seq(
        None,
        Some(ConfigScope("kubernetes-cluster:test-env/cloud1/public/region9/clustertype2/01"))
      )) {
      assert(
        TargetConfigReader.readScopeConfigMapFromDirectories(
          configScopeOpt,
          configWriter.getTargetConfigDirectory,
          configWriter.getAdvancedTargetConfigDirectory
        )(targetName) == defaultConfig
      )
    }
  }

  test("Scopes do not interfere with each other - invalid values") {
    // Test plan: define configurations such that some config scopes have invalid config values.
    // Verify that creating InternalTargetConfigs for those scopes fails, but that creating
    // InternalTargetConfigs for any other scopes (with valid values) succeeds.
    val configWriter = new ConfigWriter
    val targetName = TargetName("dup-target")
    configWriter.writeConfig(
      s"$targetName.textproto",
      """default_config {
        |  primary_rate_metric_config {
        |    max_load_hint: 1000
        |  }
        |}
        |overrides {
        |  override_scopes {
        |    cluster_uri: "kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01"
        |  }
        |  override_config {
        |    primary_rate_metric_config {
        |      max_load_hint: -1
        |    }
        |  }
        |}
        |overrides {
        |  override_scopes {
        |    cluster_uri: "kubernetes-cluster:test-env/cloud2/public/region4/clustertype2/01"
        |  }
        |  override_scopes {
        |    cluster_uri: "kubernetes-cluster:test-env/cloud1/public/region2/clustertype2/01"
        |  }
        |  override_config {
        |    primary_rate_metric_config {
        |      max_load_hint: 3000
        |    }
        |  }
        |}
        |""".stripMargin
    )

    // Expect an exception creating a config map for cloud1-region1 because it is referenced in two
    // different overrides.
    assertThrow[IllegalArgumentException](
      "max load must be a positive, finite number"
    ) {
      TargetConfigReader.readScopeConfigMapFromDirectories(
        Some(ConfigScope("kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01")),
        configWriter.getTargetConfigDirectory,
        configWriter.getAdvancedTargetConfigDirectory
      )(targetName)
    }
    // cloud1-east-1 and cloud2-region4 should get the second override.
    val east1Config: InternalTargetConfig =
      TargetConfigReader.readScopeConfigMapFromDirectories(
        Some(ConfigScope("kubernetes-cluster:test-env/cloud1/public/region2/clustertype2/01")),
        configWriter.getTargetConfigDirectory,
        configWriter.getAdvancedTargetConfigDirectory
      )(targetName)
    val firstOverrideConfig: InternalTargetConfig = createConfig(3000)
    assert(east1Config == firstOverrideConfig)
    val region4Config: InternalTargetConfig =
      TargetConfigReader.readScopeConfigMapFromDirectories(
        Some(ConfigScope("kubernetes-cluster:test-env/cloud2/public/region4/clustertype2/01")),
        configWriter.getTargetConfigDirectory,
        configWriter.getAdvancedTargetConfigDirectory
      )(targetName)
    val secondOverrideConfig: InternalTargetConfig = createConfig(3000)
    assert(region4Config == secondOverrideConfig)

    // All other scopes should get the default config.
    val defaultConfig: InternalTargetConfig = createConfig(1000)
    for (configScopeOpt: Option[ConfigScope] <- Seq(
        None,
        Some(ConfigScope("kubernetes-cluster:test-env/cloud1/public/region9/clustertype2/01"))
      )) {
      assert(
        TargetConfigReader.readScopeConfigMapFromDirectories(
          configScopeOpt,
          configWriter.getTargetConfigDirectory,
          configWriter.getAdvancedTargetConfigDirectory
        )(targetName) == defaultConfig
      )
    }
  }

  test("Throws if any scope is duplicated when reading the full config") {
    // Test plan: define configurations such that some config scopes are specified multiple times.
    // The attempt to read configs for all scopes should fail.

    // Define targets for which we have explicit configurations.
    val configWriter = new ConfigWriter
    val targetName1 = TargetName("dup-in-one-override")
    configWriter.writeConfig(
      s"$targetName1.textproto",
      """default_config {
        |  primary_rate_metric_config {
        |    max_load_hint: 1000
        |  }
        |}
        |overrides {
        |  override_scopes {
        |    cluster_uri: "kubernetes-cluster:test-env/cloud1/public/region2/clustertype2/01"
        |  }
        |  override_scopes {
        |    cluster_uri: "kubernetes-cluster:test-env/cloud1/public/region2/clustertype2/01"
        |  }
        |  override_config {
        |    primary_rate_metric_config {
        |      max_load_hint: 2000
        |    }
        |  }
        |}
        |overrides {
        |  override_scopes {
        |    cluster_uri: "kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01"
        |  }
        |  override_config {
        |    primary_rate_metric_config {
        |      max_load_hint: 3000
        |    }
        |  }
        |}
        |""".stripMargin
    )

    assertThrow[IllegalArgumentException](
      "Scope cannot be duplicated in overrides"
    ) {
      TargetConfigReader.readFullConfigMapFromDirectories(
        configWriter.getTargetConfigDirectory,
        configWriter.getAdvancedTargetConfigDirectory
      )
    }

    val targetName2 = TargetName("dup-in-two-override")
    configWriter.writeConfig(
      s"$targetName2.textproto",
      """default_config {
        |  primary_rate_metric_config {
        |    max_load_hint: 1000
        |  }
        |}
        |overrides {
        |  override_scopes {
        |    cluster_uri: "kubernetes-cluster:test-env/cloud1/public/region2/clustertype2/01"
        |  }
        |  override_config {
        |    primary_rate_metric_config {
        |      max_load_hint: 2000
        |    }
        |  }
        |}
        |overrides {
        |  override_scopes {
        |    cluster_uri: "kubernetes-cluster:test-env/cloud1/public/region2/clustertype2/01"
        |  }
        |  override_scopes {
        |    cluster_uri: "kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01"
        |  }
        |  override_config {
        |    primary_rate_metric_config {
        |      max_load_hint: 3000
        |    }
        |  }
        |}
        |""".stripMargin
    )

    assertThrow[IllegalArgumentException](
      "Scope cannot be duplicated in overrides"
    ) {
      TargetConfigReader.readFullConfigMapFromDirectories(
        configWriter.getTargetConfigDirectory,
        configWriter.getAdvancedTargetConfigDirectory
      )
    }
  }

  test("Throws if any scope has invalid config values when reading the full config") {
    // Test plan: define configurations such that some config scopes have invalid config values.
    // The attempt to read configs for all scopes should fail.
    val configWriter = new ConfigWriter
    val targetName = TargetName("target")
    configWriter.writeConfig(
      s"$targetName.textproto",
      """default_config {
        |  primary_rate_metric_config {
        |    max_load_hint: 1000
        |  }
        |}
        |overrides {
        |  override_scopes {
        |    cluster_uri: "kubernetes-cluster:test-env/cloud1/public/region2/clustertype2/01"
        |  }
        |  override_config {
        |    primary_rate_metric_config {
        |      max_load_hint: -1
        |    }
        |  }
        |}
        |overrides {
        |  override_scopes {
        |    cluster_uri: "kubernetes-cluster:test-env/cloud2/public/region4/clustertype2/01"
        |  }
        |  override_scopes {
        |    cluster_uri: "kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01"
        |  }
        |  override_config {
        |    primary_rate_metric_config {
        |      max_load_hint: 3000
        |    }
        |  }
        |}
        |""".stripMargin
    )
    assertThrow[IllegalArgumentException](
      "max load must be a positive, finite number"
    ) {
      TargetConfigReader.readFullConfigMapFromDirectories(
        configWriter.getTargetConfigDirectory,
        configWriter.getAdvancedTargetConfigDirectory
      )
    }
  }

  test("readConfigOwners() returns owner string") {
    // Test plan: verify that readConfigOwners returns the expected owner string for each target.
    val targetName1 = TargetName(s"$getSafeName-1")
    val targetName2 = TargetName(s"$getSafeName-2")
    val configWriter = new ConfigWriter
    configWriter.writeConfig(
      s"$targetName1.textproto",
      """
        | owner_team_name: "platform-team"
        |""".stripMargin
    )
    configWriter.writeConfig(
      s"$targetName2.textproto",
      """
        | owner_team_name: "storage-team"
        |""".stripMargin
    )

    assert(
      TargetConfigReader.readConfigOwners(configWriter.getTargetConfigDirectory) == Map(
        targetName1 -> "platform-team",
        targetName2 -> "storage-team"
      )
    )
  }

  test("readConfigOwners() normalizes absent owner to empty string") {
    // Test plan: verify that the empty string is returned for a config which does not have any
    // value for the owner team.
    val targetName = TargetName(getSafeName)
    val configWriter = new ConfigWriter
    configWriter.writeConfig(
      s"$targetName.textproto",
      ""
    )

    assert(
      TargetConfigReader
        .readConfigOwners(configWriter.getTargetConfigDirectory) == Map(
        targetName -> ""
      )
    )
  }
}

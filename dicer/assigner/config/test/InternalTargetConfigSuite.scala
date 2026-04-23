package com.databricks.dicer.assigner.config

import java.time.Instant
import scala.concurrent.duration._

import com.databricks.api.proto.dicer.assigner.config.{
  AdvancedTargetConfigFieldsP,
  HealthWatcherConfigP,
  LoadWatcherConfigP,
  TargetWatchRequestRateLimitConfigP
}
import com.databricks.api.proto.dicer.external.LoadBalancingMetricConfigP.{
  ImbalanceToleranceHintP,
  ReservationHintP
}
import com.databricks.api.proto.dicer.external.{
  LoadBalancingMetricConfigP,
  TargetConfigFieldsP,
  KeyReplicationConfigP
}
import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.dicer.assigner.config.InternalTargetConfig.{
  HealthWatcherTargetConfig,
  KeyOfDeathProtectionConfig,
  KeyReplicationConfig,
  LoadBalancingConfig,
  LoadBalancingMetricConfig,
  LoadWatcherTargetConfig,
  TargetWatchRequestRateLimitConfig
}
import com.databricks.dicer.common.TargetName
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.common.{SliceAssignment, SubsliceAnnotation}
import com.databricks.dicer.friend.Squid
import com.databricks.rpc.DatabricksObjectMapper
import com.databricks.testing.DatabricksTest

class InternalTargetConfigSuite extends DatabricksTest {

  test("LoadBalancingMetricConfig tolerance") {
    // Test plan: verify the expected imbalance tolerance ratios and values are returned for various
    // LoadBalancingMetric values.

    case class TestCase(
        metric: LoadBalancingMetricConfig,
        expectedRatio: Double,
        expectedValue: Double)
    val testCases = Seq(
      TestCase(
        LoadBalancingMetricConfig(maxLoadHint = 1000, ImbalanceToleranceHintP.DEFAULT),
        expectedRatio = 0.1,
        expectedValue = 100
      ),
      TestCase(
        LoadBalancingMetricConfig(maxLoadHint = 1000, ImbalanceToleranceHintP.TIGHT),
        expectedRatio = 0.025,
        expectedValue = 25
      ),
      TestCase(
        LoadBalancingMetricConfig(maxLoadHint = 1000, ImbalanceToleranceHintP.LOOSE),
        expectedRatio = 0.4,
        expectedValue = 400
      ),
      TestCase(
        LoadBalancingMetricConfig(maxLoadHint = 100, ImbalanceToleranceHintP.DEFAULT),
        expectedRatio = 0.1,
        expectedValue = 10
      ),
      TestCase(
        LoadBalancingMetricConfig(maxLoadHint = 100, ImbalanceToleranceHintP.TIGHT),
        expectedRatio = 0.025,
        expectedValue = 2.5
      ),
      TestCase(
        LoadBalancingMetricConfig(maxLoadHint = 100, ImbalanceToleranceHintP.LOOSE),
        expectedRatio = 0.4,
        expectedValue = 40
      )
    )
    for (testCase <- testCases) {
      val actualRatio = testCase.metric.imbalanceToleranceRatio
      val actualValue = testCase.metric.absoluteImbalanceTolerance
      assert(actualRatio == testCase.expectedRatio, s"for $testCase")
      assert(actualValue == testCase.expectedValue, s"for $testCase")
    }
  }

  test("InternalTargetConfig.fromProto") {
    // Test plan: verify that parsing valid `TargetConfigP` protos yields the expected
    // `InternalTargetConfig` values.

    case class TestCase(
        proto: TargetConfigFieldsP,
        advancedProto: AdvancedTargetConfigFieldsP,
        expected: InternalTargetConfig)
    val testCases = Seq(
      TestCase(
        TargetConfigFieldsP(
          primaryRateMetricConfig = Some(
            LoadBalancingMetricConfigP(
              maxLoadHint = Some(1000)
            )
          )
        ),
        AdvancedTargetConfigFieldsP(),
        InternalTargetConfig(
          LoadWatcherTargetConfig.DEFAULT,
          LoadBalancingConfig(
            loadBalancingInterval = 1.minute,
            ChurnConfig.DEFAULT,
            LoadBalancingMetricConfig(1000, ImbalanceToleranceHintP.DEFAULT)
          ),
          KeyReplicationConfig.DEFAULT_SINGLE_REPLICA,
          HealthWatcherTargetConfig.DEFAULT,
          KeyOfDeathProtectionConfig.DEFAULT,
          TargetWatchRequestRateLimitConfig.DEFAULT
        )
      ),
      TestCase(
        TargetConfigFieldsP(
          primaryRateMetricConfig = Some(
            LoadBalancingMetricConfigP(
              maxLoadHint = Some(1000)
            )
          )
        ),
        AdvancedTargetConfigFieldsP(
          loadWatcherConfig = Some(
            LoadWatcherConfigP(
              minDurationSeconds = Some(42),
              maxAgeSeconds = Some(47)
            )
          )
        ),
        InternalTargetConfig(
          LoadWatcherTargetConfig(
            minDuration = 42.seconds,
            maxAge = 47.seconds,
            useTopKeys = true
          ),
          LoadBalancingConfig(
            loadBalancingInterval = 1.minute,
            ChurnConfig.DEFAULT,
            LoadBalancingMetricConfig(1000, ImbalanceToleranceHintP.DEFAULT)
          ),
          KeyReplicationConfig.DEFAULT_SINGLE_REPLICA,
          HealthWatcherTargetConfig.DEFAULT,
          KeyOfDeathProtectionConfig.DEFAULT,
          TargetWatchRequestRateLimitConfig.DEFAULT
        )
      ),
      TestCase(
        TargetConfigFieldsP(
          primaryRateMetricConfig = Some(
            LoadBalancingMetricConfigP(
              maxLoadHint = Some(1000)
            )
          )
        ),
        AdvancedTargetConfigFieldsP(
          loadWatcherConfig = Some(
            LoadWatcherConfigP(useTopKeys = Some(true))
          )
        ),
        InternalTargetConfig(
          LoadWatcherTargetConfig.DEFAULT.copy(useTopKeys = true),
          LoadBalancingConfig(
            loadBalancingInterval = 1.minute,
            ChurnConfig.DEFAULT,
            LoadBalancingMetricConfig(1000, ImbalanceToleranceHintP.DEFAULT)
          ),
          KeyReplicationConfig.DEFAULT_SINGLE_REPLICA,
          HealthWatcherTargetConfig.DEFAULT,
          KeyOfDeathProtectionConfig.DEFAULT,
          TargetWatchRequestRateLimitConfig.DEFAULT
        )
      ),
      TestCase(
        TargetConfigFieldsP(
          primaryRateMetricConfig = Some(
            LoadBalancingMetricConfigP(
              maxLoadHint = Some(100),
              imbalanceToleranceHint = Some(ImbalanceToleranceHintP.TIGHT)
            )
          )
        ),
        AdvancedTargetConfigFieldsP(),
        InternalTargetConfig(
          LoadWatcherTargetConfig.DEFAULT,
          LoadBalancingConfig(
            loadBalancingInterval = 1.minute,
            ChurnConfig.DEFAULT,
            LoadBalancingMetricConfig(100, ImbalanceToleranceHintP.TIGHT)
          ),
          KeyReplicationConfig.DEFAULT_SINGLE_REPLICA,
          HealthWatcherTargetConfig.DEFAULT,
          KeyOfDeathProtectionConfig.DEFAULT,
          TargetWatchRequestRateLimitConfig.DEFAULT
        )
      ),
      TestCase(
        TargetConfigFieldsP(
          primaryRateMetricConfig = Some(
            LoadBalancingMetricConfigP(
              maxLoadHint = Some(100),
              imbalanceToleranceHint = Some(ImbalanceToleranceHintP.TIGHT)
            )
          )
        ),
        AdvancedTargetConfigFieldsP(
          keyReplicationConfig = Some(
            KeyReplicationConfigP(minReplicas = Some(5), maxReplicas = Some(10))
          )
        ),
        InternalTargetConfig(
          LoadWatcherTargetConfig.DEFAULT,
          LoadBalancingConfig(
            loadBalancingInterval = 1.minute,
            ChurnConfig.DEFAULT,
            LoadBalancingMetricConfig(100, ImbalanceToleranceHintP.TIGHT)
          ),
          KeyReplicationConfig(minReplicas = 5, maxReplicas = 10),
          HealthWatcherTargetConfig.DEFAULT,
          KeyOfDeathProtectionConfig.DEFAULT,
          TargetWatchRequestRateLimitConfig.DEFAULT
        )
      ),
      TestCase(
        TargetConfigFieldsP(
          primaryRateMetricConfig = Some(
            LoadBalancingMetricConfigP(
              maxLoadHint = Some(100),
              imbalanceToleranceHint = Some(ImbalanceToleranceHintP.TIGHT)
            )
          )
        ),
        AdvancedTargetConfigFieldsP(
          healthWatcherConfig = Some(
            HealthWatcherConfigP()
          )
        ),
        InternalTargetConfig(
          LoadWatcherTargetConfig.DEFAULT,
          LoadBalancingConfig(
            loadBalancingInterval = 1.minute,
            ChurnConfig.DEFAULT,
            LoadBalancingMetricConfig(100, ImbalanceToleranceHintP.TIGHT)
          ),
          KeyReplicationConfig.DEFAULT_SINGLE_REPLICA,
          HealthWatcherTargetConfig.DEFAULT,
          KeyOfDeathProtectionConfig.DEFAULT,
          TargetWatchRequestRateLimitConfig.DEFAULT
        )
      ),
      TestCase(
        TargetConfigFieldsP(
          primaryRateMetricConfig = Some(
            LoadBalancingMetricConfigP(
              maxLoadHint = Some(100)
            )
          )
        ),
        AdvancedTargetConfigFieldsP(
          targetWatchRequestRateLimitConfig = Some(
            TargetWatchRequestRateLimitConfigP(
              clientRequestsPerSecond = Some(500L)
            )
          )
        ),
        InternalTargetConfig(
          LoadWatcherTargetConfig.DEFAULT,
          LoadBalancingConfig(
            loadBalancingInterval = 1.minute,
            ChurnConfig.DEFAULT,
            LoadBalancingMetricConfig(100, ImbalanceToleranceHintP.DEFAULT)
          ),
          KeyReplicationConfig.DEFAULT_SINGLE_REPLICA,
          HealthWatcherTargetConfig.DEFAULT,
          KeyOfDeathProtectionConfig.DEFAULT,
          TargetWatchRequestRateLimitConfig(clientRequestsPerSecond = 500L)
        )
      )
    )
    for (testCase <- testCases) {
      val expected: InternalTargetConfig = testCase.expected
      val actual =
        InternalTargetConfig.fromProtos(testCase.proto, testCase.advancedProto)
      assert(actual == expected, s"for $testCase")
    }
  }

  test("InternalTargetConfig.fromProto negative") {
    // Test plan: verify that parsing invalid `TargetConfigFieldsP` or `AdvancedTargetConfigFieldsP`
    // protos throws the expected exception.

    // In our test cases, we will alter a valid base protos to make them invalid.
    val validProto = TargetConfigFieldsP(
      primaryRateMetricConfig = Some(
        LoadBalancingMetricConfigP(
          maxLoadHint = Some(1000)
        )
      )
    )
    val validAdvancedProto = AdvancedTargetConfigFieldsP(
      loadWatcherConfig = Some(
        LoadWatcherConfigP(
          minDurationSeconds = Some(50)
        )
      )
    )
    // Verify that parsing valid protos does not throw.
    InternalTargetConfig.fromProtos(validProto, validAdvancedProto)

    case class TestCase(
        proto: TargetConfigFieldsP,
        advancedProto: AdvancedTargetConfigFieldsP,
        expectedErrorMessage: String)
    val testCases = Seq(
      TestCase(
        validProto.clearPrimaryRateMetricConfig,
        validAdvancedProto,
        "requirement failed: max load must be a positive, finite number"
      ),
      TestCase(
        validProto.withPrimaryRateMetricConfig(
          LoadBalancingMetricConfigP(
            maxLoadHint = Some(-1)
          )
        ),
        validAdvancedProto,
        "requirement failed: max load must be a positive, finite number"
      ),
      TestCase(
        validProto.withPrimaryRateMetricConfig(
          LoadBalancingMetricConfigP(
            maxLoadHint = Some(0)
          )
        ),
        validAdvancedProto,
        "requirement failed: max load must be a positive, finite number"
      ),
      TestCase(
        validProto.withPrimaryRateMetricConfig(
          LoadBalancingMetricConfigP(
            maxLoadHint = Some(Double.PositiveInfinity)
          )
        ),
        validAdvancedProto,
        "requirement failed: max load must be a positive, finite number"
      ),
      TestCase(
        validProto.withPrimaryRateMetricConfig(
          LoadBalancingMetricConfigP(
            maxLoadHint = Some(Double.NaN)
          )
        ),
        validAdvancedProto,
        "requirement failed: max load must be a positive, finite number"
      ),
      TestCase(
        validProto,
        validAdvancedProto.withKeyReplicationConfig(
          KeyReplicationConfigP(
            minReplicas = Some(-1),
            maxReplicas = Some(2)
          )
        ),
        "minReplicas -1 less than 1"
      ),
      TestCase(
        validProto,
        validAdvancedProto.withKeyReplicationConfig(KeyReplicationConfigP()),
        "minReplicas is not defined in proto"
      )
    )
    for (testCase <- testCases) {
      assertThrow[IllegalArgumentException](testCase.expectedErrorMessage) {
        InternalTargetConfig.fromProtos(testCase.proto, testCase.advancedProto)
      }
    }
  }

  test("InternalTargetConfig.toString") {
    // Test plan: Verify that InternalTargetConfig.toString correctly prints the non-default
    // configurations and emits default configurations. Verify this by checking the toString()
    // results of 2 InternalTargetConfigs, where the first InternalTargetConfig has non-default
    // values for all fields, and the second IntervalTargetConfig has the default
    // KeyReplicationConfig and non-default values for other fields.

    val nonDefaultLoadBalancingConfig = LoadBalancingConfig(
      LoadBalancingConfig.DEFAULT_LOAD_BALANCING_INTERVAL + 1.minute,
      ChurnConfig.DEFAULT,
      LoadBalancingMetricConfig(maxLoadHint = 1.0)
    )
    val nonDefaultLoadWatcherConfig =
      LoadWatcherTargetConfig(minDuration = 2.minutes, maxAge = 20.minutes, useTopKeys = false)
    val nonDefaultKeyReplicationConfig = KeyReplicationConfig(minReplicas = 5, maxReplicas = 10)
    val nonDefaultHealthWatcherConfig =
      HealthWatcherTargetConfig(
        observeSliceletReadiness = true,
        permitRunningToNotReady = true
      )
    val nonDefaultKeyOfDeathProtectionConfig =
      KeyOfDeathProtectionConfig(homomorphicGenerationEnabled = true)

    val nonDefaultTargetWatchRequestRateLimitConfig =
      TargetWatchRequestRateLimitConfig(clientRequestsPerSecond = 1000L)

    val internalTargetConfigWithAllNonDefault = InternalTargetConfig(
      nonDefaultLoadWatcherConfig,
      nonDefaultLoadBalancingConfig,
      nonDefaultKeyReplicationConfig,
      nonDefaultHealthWatcherConfig,
      nonDefaultKeyOfDeathProtectionConfig,
      nonDefaultTargetWatchRequestRateLimitConfig
    )
    val expectedStringWithAllNonDefault = "InternalTargetConfig(, " +
      "LoadWatcherTargetConfig(minDuration=2 minutes, maxAge=20 minutes, useTopKeys=false), " +
      "LoadBalancingConfig(primaryRate=LoadBalancingMetricConfig(maxLoadHint=1.0), " +
      "LoadBalancingInterval=2 minutes), " +
      "KeyReplicationConfig(minReplicas=5, maxReplicas=10), " +
      "HealthWatcherConfig(observeSliceletReadiness=true, permitRunningToNotReady=true), " +
      "KeyOfDeathProtectionConfig(homomorphicGenerationEnabled=true), " +
      "TargetWatchRequestRateLimitConfig(clientRequestsPerSecond=1000, burstCapacity=10000))"
    assert(
      internalTargetConfigWithAllNonDefault.toString ==
      expectedStringWithAllNonDefault
    )

    val internalTargetConfigWithDefaultKeyReplication = InternalTargetConfig(
      nonDefaultLoadWatcherConfig,
      nonDefaultLoadBalancingConfig,
      KeyReplicationConfig.DEFAULT_SINGLE_REPLICA,
      nonDefaultHealthWatcherConfig,
      nonDefaultKeyOfDeathProtectionConfig,
      nonDefaultTargetWatchRequestRateLimitConfig
    )
    val expectedStringWithDefaultKeyReplication = "InternalTargetConfig(, " +
      "LoadWatcherTargetConfig(minDuration=2 minutes, maxAge=20 minutes, useTopKeys=false), " +
      "LoadBalancingConfig(primaryRate=LoadBalancingMetricConfig(maxLoadHint=1.0), " +
      "LoadBalancingInterval=2 minutes), " +
      "HealthWatcherConfig(observeSliceletReadiness=true, permitRunningToNotReady=true), " +
      "KeyOfDeathProtectionConfig(homomorphicGenerationEnabled=true), " +
      "TargetWatchRequestRateLimitConfig(clientRequestsPerSecond=1000, burstCapacity=10000))"

    assert(
      internalTargetConfigWithDefaultKeyReplication.toString ==
      expectedStringWithDefaultKeyReplication
    )
  }

  test("KeyReplicationConfig verification and round-trip") {
    // Test plan: Verify that KeyReplicationConfig and KeyReplicationConfig.fromProto rejects
    // invalid parameters and protos, and that KeyReplicationConfig can be converted to proto and
    // back.

    assertThrow[IllegalArgumentException]("minReplicas -1 less than 1") {
      KeyReplicationConfig(minReplicas = -1, maxReplicas = 5)
    }
    assertThrow[IllegalArgumentException]("minReplicas 0 less than 1") {
      KeyReplicationConfig(minReplicas = 0, maxReplicas = 5)
    }
    assertThrow[IllegalArgumentException]("minReplicas 2 greater than maxReplicas 1") {
      KeyReplicationConfig(minReplicas = 2, maxReplicas = 1)
    }

    // Valid constructor parameters.
    KeyReplicationConfig(minReplicas = 1, maxReplicas = 5)
    KeyReplicationConfig(minReplicas = 4, maxReplicas = 5)
    KeyReplicationConfig(minReplicas = 1, maxReplicas = Int.MaxValue)

    val validProto: KeyReplicationConfigP =
      KeyReplicationConfig(minReplicas = 3, maxReplicas = 5).toProto
    assert(
      KeyReplicationConfig.fromProto(validProto) ==
      KeyReplicationConfig(minReplicas = 3, maxReplicas = 5)
    )

    assertThrow[IllegalArgumentException]("minReplicas is not defined in proto.") {
      KeyReplicationConfig.fromProto(validProto.clearMinReplicas)
    }
    assertThrow[IllegalArgumentException]("maxReplicas is not defined in proto.") {
      KeyReplicationConfig.fromProto(validProto.clearMaxReplicas)
    }
  }

  test("HealthWatcherTargetConfig verification and round-trip") {
    // Test plan: Verify that HealthWatcherTargetConfig enforces the invariant that
    // permitRunningToNotReady requires observeSliceletReadiness, can be converted to proto and
    // back, and that default values are handled correctly.

    // Test that an invalid config (permitRunningToNotReady=true with
    // observeSliceletReadiness=false) is rejected.
    assertThrow[IllegalArgumentException](
      "permitRunningToNotReady can only be true if observeSliceletReadiness is also true"
    ) {
      HealthWatcherTargetConfig(
        observeSliceletReadiness = false,
        permitRunningToNotReady = true
      )
    }

    // Test round-trip with non-default value (true).
    val config1 =
      HealthWatcherTargetConfig(observeSliceletReadiness = true, permitRunningToNotReady = true)
    val proto1: HealthWatcherConfigP = config1.toProto
    val roundTrippedConfig1 = HealthWatcherTargetConfig.fromProto(proto1)
    assertResult(config1)(roundTrippedConfig1)

    // Test round-trip with default value (false).
    val config2 = HealthWatcherTargetConfig.DEFAULT
    val proto2: HealthWatcherConfigP = config2.toProto
    val roundTrippedConfig2 = HealthWatcherTargetConfig.fromProto(proto2)
    assertResult(config2)(roundTrippedConfig2)

    // Test fromProto with empty proto (should return default).
    val emptyProto = HealthWatcherConfigP()
    assertResult(config2)(HealthWatcherTargetConfig.fromProto(emptyProto))
  }

  test("InternalTargetConfig backward-compatability with proto") {
    // Test plan: Verify that InternalTargetConfig can still parse the deprecated
    // KeyReplicationConfigP field in advance config, and when the KeyReplicationConfigP field in
    // the external TargetConfigP is defined, the deprecated KeyReplicationConfigP in advanced
    // config is ignored.
    //
    // TODO(<internal bug>): Remove this test when the deprecated KeyReplicationConfigP in advanced
    //                  config is cleaned up everywhere.

    val validTargetConfigFieldsP = TargetConfigFieldsP(
      primaryRateMetricConfig = Some(
        LoadBalancingMetricConfigP(
          maxLoadHint = Some(1000)
        )
      )
    )
    val validAdvancedTargetConfigFieldsP = AdvancedTargetConfigFieldsP()

    // Deprecated proto can still be parsed.
    assert(
      InternalTargetConfig
        .fromProtos(
          validTargetConfigFieldsP,
          validAdvancedTargetConfigFieldsP.withKeyReplicationConfig(
            KeyReplicationConfigP().withMinReplicas(3).withMaxReplicas(5)
          )
        )
        .keyReplicationConfig == KeyReplicationConfig(minReplicas = 3, maxReplicas = 5)
    )

    // Deprecated proto validation.
    assertThrow[IllegalArgumentException]("minReplicas is not defined") {
      InternalTargetConfig
        .fromProtos(
          validTargetConfigFieldsP,
          validAdvancedTargetConfigFieldsP.withKeyReplicationConfig(
            KeyReplicationConfigP().withMaxReplicas(3)
          )
        )
    }
    assertThrow[IllegalArgumentException]("maxReplicas is not defined") {
      InternalTargetConfig
        .fromProtos(
          validTargetConfigFieldsP,
          validAdvancedTargetConfigFieldsP.withKeyReplicationConfig(
            KeyReplicationConfigP().withMinReplicas(3)
          )
        )
    }

    // Deprecated KeyReplicationConfigP is ignored when the external KeyReplicationConfigP is
    // defined.
    assert(
      InternalTargetConfig
        .fromProtos(
          validTargetConfigFieldsP.withKeyReplicationConfig(
            KeyReplicationConfigP().withMinReplicas(10).withMaxReplicas(100)
          ),
          validAdvancedTargetConfigFieldsP.withKeyReplicationConfig(
            KeyReplicationConfigP().withMinReplicas(3).withMaxReplicas(5)
          )
        )
        .keyReplicationConfig == KeyReplicationConfig(minReplicas = 10, maxReplicas = 100)
    )
  }

  test("InternalTargetConfig forward-compatability") {
    // Test plan: Verify that InternalTargetConfig can still generate dynamic config that can be
    // read by the old scala binary. This is basically to verify that the deprecated
    // KeyReplicationConfig field in advanced config can still be output in the proto and jsonnet,
    // so that during the migration of KeyReplicationConfig from advanced config to external config,
    // the KeyReplicationConfig can be recognized by old assigner binary that tries to read
    // KeyReplicationConfig only from advanced config but not external config.

    val keyReplicationConfig = KeyReplicationConfig(minReplicas = 4, maxReplicas = 5)
    val namedInternalTargetConfig = NamedInternalTargetConfig(
      TargetName("softstore-storelet"),
      InternalTargetConfig(
        LoadWatcherTargetConfig.DEFAULT,
        LoadBalancingConfig(
          loadBalancingInterval = 1.minute,
          ChurnConfig.DEFAULT,
          LoadBalancingMetricConfig(1000, ImbalanceToleranceHintP.DEFAULT)
        ),
        keyReplicationConfig,
        HealthWatcherTargetConfig.DEFAULT,
        KeyOfDeathProtectionConfig.DEFAULT,
        TargetWatchRequestRateLimitConfig.DEFAULT
      )
    )
    // It's hard to access a real stale version of InternalTargetConfig in test and verify this in
    // scala class level, so we just verify the KeyReplicationConfig still exists in the advanced
    // config proto.
    assert(namedInternalTargetConfig.toProto.advancedConfig.get.keyReplicationConfig.isDefined)
  }

  test("TargetWatchRequestRateLimitConfig rejects negative clientRequestsPerSecond") {
    // Test plan: Verify that clientRequestsPerSecond must be >= 0.
    assertThrow[IllegalArgumentException]("clientRequestsPerSecond must be >= 0") {
      TargetWatchRequestRateLimitConfig(clientRequestsPerSecond = -1L)
    }
    // Verify that 0 is valid (used to disable traffic for a target).
    TargetWatchRequestRateLimitConfig(clientRequestsPerSecond = 0L)
  }

  test("TargetWatchRequestRateLimitConfig caps burstCapacity at Long.MaxValue on overflow") {
    // Test plan: Verify that overflow in burstCapacity calculation is capped at Long.MaxValue.

    // Long.MaxValue * 10 (BURST_CAPACITY_IN_SECONDS) will overflow, but should be capped.
    val config = TargetWatchRequestRateLimitConfig(clientRequestsPerSecond = Long.MaxValue)
    assert(config.burstCapacity == Long.MaxValue)
  }

  test("TargetWatchRequestRateLimitConfig.fromProto with empty proto uses DEFAULT values") {
    // Test plan: Verify that fromProto uses DEFAULT values when the proto is empty.
    val emptyProto = TargetWatchRequestRateLimitConfigP()
    val config = TargetWatchRequestRateLimitConfig.fromProto(emptyProto)
    assert(config == TargetWatchRequestRateLimitConfig.DEFAULT)
  }

  test("TargetWatchRequestRateLimitConfig toProto and fromProto round-trip") {
    // Test plan: Verify that clientRequestsPerSecond survives round-trip through proto.
    val config = TargetWatchRequestRateLimitConfig(clientRequestsPerSecond = 100L)
    val proto = config.toProto
    val roundTripped = TargetWatchRequestRateLimitConfig.fromProto(proto)
    assert(roundTripped == config)
  }

  test("TargetWatchRequestRateLimitConfig.fromProto rejects negative clientRequestsPerSecond") {
    // Test plan: Verify that fromProto rejects negative clientRequestsPerSecond values.
    val protoRateNegative =
      TargetWatchRequestRateLimitConfigP.of(clientRequestsPerSecond = Some(-1L))
    assertThrow[IllegalArgumentException]("clientRequestsPerSecond must be >= 0") {
      TargetWatchRequestRateLimitConfig.fromProto(protoRateNegative)
    }
  }

}

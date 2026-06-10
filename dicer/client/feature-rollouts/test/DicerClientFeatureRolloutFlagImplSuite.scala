package com.databricks.dicer.client.featurerollouts

import java.net.URI

import com.databricks.caching.util.{CachingErrorCode, MetricUtils, Severity}
import com.databricks.caching.util.MetricUtils.ChangeTracker
import com.databricks.dicer.external.Target
import com.databricks.testing.DatabricksTest

class DicerClientFeatureRolloutFlagImplSuite extends DatabricksTest {

  /** Directory containing test textproto fixtures for this suite. */
  private val testdataDirPath: String = "dicer/client/feature-rollouts/test/testdata"

  /** Prefix used by [[DicerClientFeatureRolloutFlagImpl]]'s PrefixLogger. */
  private val ALERT_PREFIX: String = "dicer-client-feature-rollout"

  /**
   * A list of distinct targets used by tests that verify behavior must hold uniformly across all
   * targets (e.g., fraction 1.0 enables every target, fraction 0.0 disables every target). The
   * sample spans every Target variant (in-cluster KubernetesTarget, cross-cluster KubernetesTarget
   * with an explicit cluster URI, and AppTarget) so that the rule logic is exercised against each
   * representation.
   */
  private val testTargets: Seq[Target] = Seq(
    // KubernetesTargets without an explicit cluster URI (in-cluster sharding).
    Target("service-a"),
    Target("service-b"),
    Target("service-c"),
    Target("service-d"),
    // KubernetesTargets with an explicit cluster URI (cross-cluster sharding).
    Target.createKubernetesTarget(
      cluster = new URI("kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01"),
      name = "service-e"
    ),
    Target.createKubernetesTarget(
      cluster = new URI("kubernetes-cluster:test-env/cloud1/public/region2/clustertype2/02"),
      name = "service-f"
    ),
    Target.createKubernetesTarget(
      cluster = new URI("kubernetes-cluster:test-env/cloud2/public/eastus/clustertype2/01"),
      name = "service-g"
    ),
    // AppTargets identified by App Identifier (name + instanceId).
    Target.createAppTarget(name = "app-service-a", instanceId = "instance-1"),
    Target.createAppTarget(name = "app-service-b", instanceId = "instance-2"),
    Target.createAppTarget(name = "app-service-c", instanceId = "instance-3")
  )

  /**
   * Returns a sample of distinct Target variants that all share the given `targetName`. Used by
   * tests that verify behavior keyed on `Target.name` (e.g., `force_enable_target_names`,
   * `force_disable_target_names`) applies across every Target variant carrying that name.
   */
  private def targetsNamed(targetName: String): Seq[Target] = Seq(
    Target(targetName),
    Target.createKubernetesTarget(
      cluster = new URI("kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01"),
      name = targetName
    ),
    Target.createKubernetesTarget(
      cluster = new URI("kubernetes-cluster:test-env/cloud2/public/eastus/clustertype2/01"),
      name = targetName
    ),
    Target.createAppTarget(name = targetName, instanceId = "instance-1"),
    Target.createAppTarget(name = targetName, instanceId = "instance-2")
  )

  /** Tracks the count of a single PrefixLogger error code for [[ALERT_PREFIX]]. */
  private def alertTracker(errorCode: CachingErrorCode): ChangeTracker[Int] =
    ChangeTracker[Int](
      () =>
        MetricUtils.getPrefixLoggerErrorCount(Severity.DEGRADED, errorCode, prefix = ALERT_PREFIX)
    )

  test("isEnabled returns true for all targets when fraction is 1.0") {
    // Test plan: Verify that a feature configured with target_instance_enable_fraction == 1.0 is
    // enabled for all targets in any region. Do this by creating a flag from the
    // all-enabled-feature testdata fixture and asserting isEnabled returns true for each target in
    // [[testTargets]].
    val flag: DicerClientFeatureRolloutFlagImpl =
      DicerClientFeatureRolloutFlagImpl.create(
        configDirPath = testdataDirPath,
        regionUri = "region:dev/cloud1/public/region1"
      )
    for (target: Target <- testTargets) {
      assert(
        flag.isEnabled(flagName = "all-enabled-feature", target),
        s"expected enabled for target=$target"
      )
    }
  }

  test("isEnabled returns false for all targets when fraction is 0.0") {
    // Test plan: Verify that a feature configured with target_instance_enable_fraction == 0.0 is
    // disabled for all targets in any region. Do this by creating a flag from the
    // all-disabled-feature testdata fixture and asserting isEnabled returns false for each target
    // in [[testTargets]].
    val flag: DicerClientFeatureRolloutFlagImpl =
      DicerClientFeatureRolloutFlagImpl.create(
        configDirPath = testdataDirPath,
        regionUri = "region:dev/cloud1/public/region1"
      )
    for (target: Target <- testTargets) {
      assert(
        !flag.isEnabled(flagName = "all-disabled-feature", target),
        s"expected disabled for target=$target"
      )
    }
  }

  test("isEnabled returns true for force-enabled target names even when fraction is 0.0") {
    // Test plan: Verify that force_enable_target_names takes precedence over
    // target_instance_enable_fraction == 0.0, and that the match is keyed on Target.name regardless
    // of the underlying Target variant. Do this by using the force-enable-feature testdata fixture
    // (where "my-service" is force-enabled and the default fraction is 0.0) and asserting
    // isEnabled returns true for every Target variant sharing the name "my-service".
    val flag: DicerClientFeatureRolloutFlagImpl =
      DicerClientFeatureRolloutFlagImpl.create(
        configDirPath = testdataDirPath,
        regionUri = "region:dev/cloud1/public/region1"
      )
    for (target: Target <- targetsNamed("my-service")) {
      assert(
        flag.isEnabled(flagName = "force-enable-feature", target),
        s"expected force-enabled for target=$target"
      )
    }
  }

  test("isEnabled returns false for force-disabled target names even when fraction is 1.0") {
    // Test plan: Verify that force_disable_target_names takes precedence over
    // target_instance_enable_fraction == 1.0, and that the match is keyed on Target.name regardless
    // of the underlying Target variant. Do this by using the force-disable-feature testdata
    // fixture (where "my-service" is force-disabled and the default fraction is 1.0) and asserting
    // isEnabled returns false for every Target variant sharing the name "my-service".
    val flag: DicerClientFeatureRolloutFlagImpl =
      DicerClientFeatureRolloutFlagImpl.create(
        configDirPath = testdataDirPath,
        regionUri = "region:dev/cloud1/public/region1"
      )
    for (target: Target <- targetsNamed("my-service")) {
      assert(
        !flag.isEnabled(flagName = "force-disable-feature", target),
        s"expected force-disabled for target=$target"
      )
    }
  }

  test("isEnabled applies region override rule when region matches") {
    // Test plan: Verify that when the current region matches an override_scopes entry, the
    // override rule is used instead of the default rule. Do this by using the
    // region-override-feature fixture (default fraction 0.0, override for
    // region:dev/cloud1/public/region2 with fraction 1.0) and asserting isEnabled returns true for
    // each target in [[testTargets]] when running in the override region.
    val flag: DicerClientFeatureRolloutFlagImpl =
      DicerClientFeatureRolloutFlagImpl.create(
        configDirPath = testdataDirPath,
        regionUri = "region:dev/cloud1/public/region2"
      )
    for (target: Target <- testTargets) {
      assert(
        flag.isEnabled(flagName = "region-override-feature", target),
        s"expected enabled for target=$target"
      )
    }
  }

  test("isEnabled uses default rule when region does not match any override") {
    // Test plan: Verify that when the current region does not match any override_scopes entry,
    // the default rule is applied. Do this by using the region-override-feature fixture (default
    // fraction 0.0, override only for region:dev/cloud1/public/region2) and asserting isEnabled
    // returns false for each target in [[testTargets]] when running in a non-override region.
    val flag: DicerClientFeatureRolloutFlagImpl =
      DicerClientFeatureRolloutFlagImpl.create(
        configDirPath = testdataDirPath,
        regionUri = "region:dev/cloud1/public/region1"
      )
    for (target: Target <- testTargets) {
      assert(
        !flag.isEnabled(flagName = "region-override-feature", target),
        s"expected disabled for target=$target"
      )
    }
  }

  test("isEnabled returns false and fires alert when flag name is not found") {
    // Test plan: Verify that isEnabled returns false when the given flag name does not correspond
    // to any textproto file in the config directory, and that a DICER_CLIENT_FEATURE_ROLLOUT_FLAG_
    // NOT_FOUND alert is fired. Do this by tracking the alert counter, calling isEnabled with a
    // flag name that does not exist in the testdata directory, and asserting the result is false
    // and the counter incremented by 1.
    val flag: DicerClientFeatureRolloutFlagImpl =
      DicerClientFeatureRolloutFlagImpl.create(
        configDirPath = testdataDirPath,
        regionUri = "region:dev/cloud1/public/region1"
      )
    val alertCount: ChangeTracker[Int] =
      alertTracker(CachingErrorCode.DICER_CLIENT_FEATURE_ROLLOUT_FLAG_NOT_FOUND)
    assert(!flag.isEnabled(flagName = "nonexistent-feature", target = Target("any-service")))
    assert(alertCount.totalChange() == 1)
  }

  test("isEnabled is deterministic for the same target and flag name") {
    // Test plan: Verify that repeated calls to isEnabled with the same flag name and target always
    // return the same result. Do this by calling isEnabled three times on a half-enabled feature
    // (fraction 0.5) with the same target, and asserting all results are identical.
    val flag: DicerClientFeatureRolloutFlagImpl =
      DicerClientFeatureRolloutFlagImpl.create(
        configDirPath = testdataDirPath,
        regionUri = "region:dev/cloud1/public/region1"
      )
    val target: Target = Target("any-service")
    val firstResult: Boolean = flag.isEnabled(flagName = "half-enabled-feature", target)
    val secondResult: Boolean = flag.isEnabled(flagName = "half-enabled-feature", target)
    val thirdResult: Boolean = flag.isEnabled(flagName = "half-enabled-feature", target)
    assert(firstResult == secondResult)
    assert(secondResult == thirdResult)
  }

  test("isEnabled samples on fully qualified target") {
    // Test plan: Verify that the deterministic sampler keys on the fully qualified target string
    //  and not just `Target.name`. Do this by building 20 KubernetesTargets that share the name
    //  "shared-service" but each carries a distinct cluster URI, calling isEnabled on the
    //  half-enabled-feature fixture (fraction == 0.5), and asserting both enabled and disabled
    //  outcomes are present in the results.
    val flag: DicerClientFeatureRolloutFlagImpl =
      DicerClientFeatureRolloutFlagImpl.create(
        configDirPath = testdataDirPath,
        regionUri = "region:dev/cloud1/public/region1"
      )
    val sharedName: String = "shared-service"
    val targets: Seq[Target] = (1 to 20).map { i: Int =>
      Target.createKubernetesTarget(
        cluster = new URI(s"kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/$i"),
        name = sharedName
      )
    }
    val results: Set[Boolean] =
      targets.map { target: Target =>
        flag.isEnabled(flagName = "half-enabled-feature", target)
      }.toSet
    assert(
      results == Set(true, false),
      s"expected both enabled and disabled outcomes across cluster URIs, got: $results"
    )
  }

  test("isEnabled enabled count is within 3 sigma of expected for fraction 0.5") {
    // Test plan: Verify that the deterministic sampler distributes targets according to the
    // configured fraction. Do this by calling isEnabled on the half-enabled-feature fixture
    // (fraction 0.5) for SAMPLE_SIZE distinct target names and asserting the enabled
    // count is within 3 standard deviations of the binomial expectation SAMPLE_SIZE * 0.5. The
    // sampler is deterministic, so this test is also deterministic given the fixed input set.
    val flag: DicerClientFeatureRolloutFlagImpl =
      DicerClientFeatureRolloutFlagImpl.create(
        configDirPath = testdataDirPath,
        regionUri = "region:dev/cloud1/public/region1"
      )
    val SAMPLE_SIZE: Int = 1000
    val FRACTION: Double = 0.5
    val targets: Seq[Target] = (1 to SAMPLE_SIZE).map { i: Int =>
      Target(s"sigma-svc-$i")
    }
    val enabledCount: Int =
      targets.count { target: Target =>
        flag.isEnabled(flagName = "half-enabled-feature", target)
      }
    val expectedCount: Double = SAMPLE_SIZE * FRACTION
    val stdDev: Double = math.sqrt(SAMPLE_SIZE * FRACTION * (1.0 - FRACTION))
    val tolerance: Double = 3.0 * stdDev
    assert(
      math.abs(enabledCount - expectedCount) <= tolerance,
      s"enabledCount=$enabledCount outside 3-sigma window: expected=$expectedCount +/- $tolerance"
    )
  }

  test("create throws when configDirPath does not exist") {
    // Test plan: Verify that create() fails fast with IllegalArgumentException when the
    // configured directory does not exist, so a misconfigured deployment is caught at boot
    // rather than silently rolling out as a no-feature instance. Do this by passing a path that
    // is guaranteed not to exist (a sibling of the real testdata directory) and asserting the
    // exception message names the bad path.
    val nonExistentDirPath: String = s"$testdataDirPath/non-existent"
    val ex: IllegalArgumentException = intercept[IllegalArgumentException] {
      DicerClientFeatureRolloutFlagImpl.create(
        configDirPath = nonExistentDirPath,
        regionUri = "region:dev/cloud1/public/region1"
      )
    }
    assert(ex.getMessage.contains(nonExistentDirPath))
  }

  test("create throws when a textproto file is malformed") {
    // Test plan: Verify that create() fails fast with IllegalArgumentException when any
    // .textproto file in the config directory cannot be parsed, so a bad config shipped to
    // production crashes the service at boot instead of silently disabling that feature. Do this
    // by pointing create() at the checked-in `testdata/malformed/` fixture directory (which holds
    // one unparseable textproto) and asserting the exception message names that file.
    val malformedDirPath: String = s"$testdataDirPath/malformed"
    val ex: IllegalArgumentException = intercept[IllegalArgumentException] {
      DicerClientFeatureRolloutFlagImpl.create(
        configDirPath = malformedDirPath,
        regionUri = "region:dev/cloud1/public/region1"
      )
    }
    assert(ex.getMessage.contains("broken-feature.textproto"))
  }
}

package com.databricks.dicer.assigner

import java.net.URI
import java.util.UUID

import com.databricks.api.base.DatabricksServiceException
import com.databricks.ErrorCode
import com.databricks.caching.util.MetricUtils
import com.databricks.caching.util.MetricUtils.ChangeTracker
import com.databricks.caching.util.TestUtils.{ParameterizedTestNameDecorator, assertThrow}
import com.databricks.dicer.assigner.config.{
  TargetMigrationConfig,
  TargetMigrationRole,
  TargetMigrationType
}
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.common.TargetName
import com.databricks.dicer.external.Target
import com.databricks.testing.DatabricksTest
import io.prometheus.client.CollectorRegistry
import org.scalatest.Suite
import scala.collection.immutable.IndexedSeq
import scala.collection.mutable

import TargetOwnershipResolverSuite._

/**
 * The suite that tests the [[TargetOwnershipResolver]] for an Assigner assuming either
 * [[TargetMigrationRole.Source]] or [[TargetMigrationRole.Destination]] in the target migration.
 * Each parameterized test scenario uses a single migration config (matching production, where one
 * config flows to all participating Assigners) and asserts the verdict appropriate for the
 * Assigner's role. For test scenarios that need to inspect both roles simultaneously, they live
 * as unparameterized tests directly on this class.
 */
class TargetOwnershipResolverSuite extends DatabricksTest {
  override def nestedSuites: IndexedSeq[Suite] = IndexedSeq(
    new ParameterizedTargetOwnershipResolverSuite(assignerRole = TargetMigrationRole.Source),
    new ParameterizedTargetOwnershipResolverSuite(assignerRole = TargetMigrationRole.Destination)
  )

  test(
    "destinationTargetNameFraction of 50% splits targets between the Source and Destination " +
    "Assigners"
  ) {
    // Test plan: With a destination rollout fraction of 50%, simulate a resolver belonging to a
    // Source Assigner and a resolver belonging to a Destination Assigner with the same config.
    // Each target has a single owner role, so the two resolvers must produce opposite verdicts for
    // that target — when one Handles, the other Reroutes to it. Across a population of targets,
    // both Handle and Reroute must occur at least once for each Assigner, and neither verdict
    // may cover the entire population; otherwise the underlying rollout fraction function has
    // degenerated.
    val config: TargetMigrationConfig = makeActiveTargetMigrationConfig(
      destinationTargetNameFraction = 0.5
    )
    val sourceResolver: TargetOwnershipResolver = makeResolver(
      TargetMigrationSnapshot.ActiveMigration(
        targetMigrationConfig = config,
        targetMigrationRole = TargetMigrationRole.Source,
        peerAssignerUris = Seq(EXAMPLE_DESTINATION_ASSIGNER_URI)
      )
    )
    val destinationResolver: TargetOwnershipResolver = makeResolver(
      TargetMigrationSnapshot.ActiveMigration(
        targetMigrationConfig = config,
        targetMigrationRole = TargetMigrationRole.Destination,
        peerAssignerUris = Seq(EXAMPLE_SOURCE_ASSIGNER_URI)
      )
    )

    val targets: Seq[Target] = (0 until 100).map { i: Int =>
      Target(s"target-$i")
    }
    val sourceVerdicts: Seq[RoutingVerdict] = targets.map { target: Target =>
      sourceResolver.getRoutingVerdict(target, inboundRedirectTokenOpt = None)
    }
    val destinationVerdicts: Seq[RoutingVerdict] = targets.map { target: Target =>
      destinationResolver.getRoutingVerdict(target, inboundRedirectTokenOpt = None)
    }

    // Per-target invariant: the two resolvers must produce opposite verdicts for the same target.
    targets.indices.foreach { i: Int =>
      val target: Target = targets(i)
      val sourceVerdict: RoutingVerdict = sourceVerdicts(i)
      val destinationVerdict: RoutingVerdict = destinationVerdicts(i)
      withClue(
        s"target=$target, source=$sourceVerdict, destination=$destinationVerdict: "
      ) {
        (sourceVerdict, destinationVerdict) match {
          case (_: RoutingVerdict.Handle, EXPECTED_REROUTE_TO_SOURCE) => ()
          case (EXPECTED_REROUTE_TO_DESTINATION, _: RoutingVerdict.Handle) => ()
          case _ =>
            fail(
              "Expected opposite verdicts (one Handle and one Reroute to the other Assigner)."
            )
        }
      }
    }

    // Population invariant for the Source Assigner: both Handle and Reroute must occur, and
    // neither verdict may cover the entire population.
    val sourceHandleCount: Int = sourceVerdicts.count {
      case _: RoutingVerdict.Handle => true
      case _: RoutingVerdict.Reroute => false
    }
    val sourceRerouteCount: Int = sourceVerdicts.count {
      case _: RoutingVerdict.Reroute => true
      case _: RoutingVerdict.Handle => false
    }
    assert(sourceHandleCount > 0, "Expected at least one Handle for the Source Assigner.")
    assert(
      sourceHandleCount < targets.size,
      "Expected not all targets to be Handle for the Source Assigner."
    )
    assert(sourceRerouteCount > 0, "Expected at least one Reroute for the Source Assigner.")
    assert(
      sourceRerouteCount < targets.size,
      "Expected not all targets to be Reroute for the Source Assigner."
    )

    // Population invariant for the Destination Assigner: both Handle and Reroute must occur,
    // and neither verdict may cover the entire population.
    val destinationHandleCount: Int = destinationVerdicts.count {
      case _: RoutingVerdict.Handle => true
      case _: RoutingVerdict.Reroute => false
    }
    val destinationRerouteCount: Int = destinationVerdicts.count {
      case _: RoutingVerdict.Reroute => true
      case _: RoutingVerdict.Handle => false
    }
    assert(
      destinationHandleCount > 0,
      "Expected at least one Handle for the Destination Assigner."
    )
    assert(
      destinationHandleCount < targets.size,
      "Expected not all targets to be Handle for the Destination Assigner."
    )
    assert(
      destinationRerouteCount > 0,
      "Expected at least one Reroute for the Destination Assigner."
    )
    assert(
      destinationRerouteCount < targets.size,
      "Expected not all targets to be Reroute for the Destination Assigner."
    )
  }

  test("NoActiveMigration always yields Handle with no outbound redirect_token") {
    // Test plan: With no active migration, the resolver always yields Handle. The Handle's outbound
    // `redirect_token` is always `None` regardless of any inbound token.
    val resolver: TargetOwnershipResolver =
      makeResolver(TargetMigrationSnapshot.NoActiveMigration(TargetMigrationConfig.NO_MIGRATION))
    val target = Target("foo")
    // The expected verdict is always the same regardless of the inbound token.
    val expectedVerdict = RoutingVerdict.Handle(redirectTokenOpt = None)
    val inboundTokens: Seq[Option[RedirectToken]] = Seq(
      None,
      Some(RedirectToken(targetMigrationConfigVersion = 0)),
      Some(RedirectToken(targetMigrationConfigVersion = MIGRATION_CONFIG_VERSION + 5))
    )
    for (inboundTokenOpt: Option[RedirectToken] <- inboundTokens) {
      assertResult(expectedVerdict)(
        resolver.getRoutingVerdict(target, inboundRedirectTokenOpt = inboundTokenOpt)
      )
    }
  }

  test("Reroute picks a peer from the full set of peer Assigner URIs") {
    // Test plan: Verify that when multiple peer Assigner URIs are known, the resolver picks one at
    // random. Do this by creating an active migration with multiple peer URIs, then repeatedly
    // rerouting the same target until every peer URI has been chosen, confirming each reroute
    // lands on a member of the set.
    val peerAssignerUris: Set[URI] = Set(
      URI.create("https://peer-1.test:24500"),
      URI.create("https://peer-2.test:24500"),
      URI.create("https://peer-3.test:24500")
    )
    // Pin the target to Destination while this resolver is the Source Assigner, so the baseline
    // verdict is a Reroute to a peer.
    val peerOwnedTargetName: TargetName = TargetName("pinned-to-destination")
    val config: TargetMigrationConfig =
      makeActiveTargetMigrationConfig(forceToDestinationTargetNames = Set(peerOwnedTargetName))
    val resolver: TargetOwnershipResolver = makeResolver(
      TargetMigrationSnapshot.ActiveMigration(
        targetMigrationConfig = config,
        targetMigrationRole = TargetMigrationRole.Source,
        peerAssignerUris = peerAssignerUris.toSeq
      )
    )
    val target: Target = Target.createAppTarget(peerOwnedTargetName.value, "instance-id")

    // Maximum number of verdicts we will check, to see if all peer URIs are seen. If not, seen by
    // then, the test fails.
    val maxRerouteAttempts: Int = 1000
    val chosenPeerUris: mutable.Set[URI] = mutable.Set.empty
    var attempts: Int = 0
    while (chosenPeerUris != peerAssignerUris) {
      assert(
        attempts < maxRerouteAttempts,
        s"Did not observe every peer URI within $maxRerouteAttempts reroutes; only saw " +
        s"$chosenPeerUris out of $peerAssignerUris"
      )
      attempts += 1
      resolver.getRoutingVerdict(target, inboundRedirectTokenOpt = None) match {
        case reroute: RoutingVerdict.Reroute =>
          assert(
            peerAssignerUris.contains(reroute.peerAssignerUri),
            s"Reroute targeted ${reroute.peerAssignerUri}, which is not one of $peerAssignerUris"
          )
          chosenPeerUris.add(reroute.peerAssignerUri)
        case handle: RoutingVerdict.Handle =>
          fail(s"Expected a Reroute to a peer, but got $handle")
      }
    }
  }

  test("Reroute with no peer Assigner available fails with TEMPORARILY_UNAVAILABLE") {
    // Test plan: Verify that when an active migration would Reroute but no peer Assigner URIs are
    // provided, the resolver throws a DatabricksServiceException with
    // ErrorCode.TEMPORARILY_UNAVAILABLE. Also verify the RerouteFailed verdict counter is
    // incremented.
    val peerOwnedTargetName: TargetName = TargetName("pinned-to-destination")
    val config: TargetMigrationConfig =
      makeActiveTargetMigrationConfig(forceToDestinationTargetNames = Set(peerOwnedTargetName))
    val resolver: TargetOwnershipResolver = makeResolver(
      TargetMigrationSnapshot.ActiveMigration(
        targetMigrationConfig = config,
        targetMigrationRole = TargetMigrationRole.Source,
        peerAssignerUris = Seq.empty
      )
    )
    val target: Target = Target.createAppTarget(peerOwnedTargetName.value, "instance-id")
    val rerouteFailedTracker: ChangeTracker[Long] =
      ChangeTracker[Long] { () =>
        getRoutingVerdictCount(target, verdict = "RerouteFailed")
      }

    val exception: DatabricksServiceException = assertThrow[DatabricksServiceException](
      EXAMPLE_ASSIGNER_UUID.toString
    ) {
      resolver.getRoutingVerdict(target, inboundRedirectTokenOpt = None)
    }
    assertResult(ErrorCode.TEMPORARILY_UNAVAILABLE)(exception.errorCode)
    assertResult(1L)(rerouteFailedTracker.totalChange())
  }
}

object TargetOwnershipResolverSuite {

  // Example URIs used to assert the precise URI a reroute verdict carries.
  val EXAMPLE_SOURCE_ASSIGNER_URI: URI =
    URI.create("https://source-assigner.test:24500")
  val EXAMPLE_DESTINATION_ASSIGNER_URI: URI =
    URI.create("https://destination-assigner.test:24500")

  /** UUID identifying the Assigner that owns the resolver under test. */
  val EXAMPLE_ASSIGNER_UUID = new UUID(123, 456)

  /**
   * [[TargetMigrationConfig.version]] used by every active migration config built in this suite.
   * The config version is always positive in practice, we use 2 here so
   * `MIGRATION_CONFIG_VERSION - 1` can also be used in tests.
   */
  val MIGRATION_CONFIG_VERSION: Int = 2

  /**
   * The Handle verdict expected when the local config is at [[MIGRATION_CONFIG_VERSION]] and the
   * inbound token version (if any) is `<= MIGRATION_CONFIG_VERSION`.
   */
  val EXPECTED_HANDLE_LOCAL: RoutingVerdict.Handle =
    RoutingVerdict.Handle(redirectTokenOpt = Some(RedirectToken(MIGRATION_CONFIG_VERSION)))

  /**
   * Reroute verdict expected from the resolver when the target's owner is the Source Assigner, and
   * it wants to reroute to the Destination Assigner.
   */
  val EXPECTED_REROUTE_TO_SOURCE: RoutingVerdict.Reroute =
    RoutingVerdict.Reroute(
      peerAssignerUri = EXAMPLE_SOURCE_ASSIGNER_URI,
      redirectToken = RedirectToken(MIGRATION_CONFIG_VERSION)
    )

  /**
   * Reroute verdict expected from the resolver when the target's owner is the Destination Assigner,
   * and it wants to reroute to the Source Assigner.
   */
  val EXPECTED_REROUTE_TO_DESTINATION: RoutingVerdict.Reroute =
    RoutingVerdict.Reroute(
      peerAssignerUri = EXAMPLE_DESTINATION_ASSIGNER_URI,
      redirectToken = RedirectToken(MIGRATION_CONFIG_VERSION)
    )

  /**
   * Returns the value of the [[TargetOwnershipResolver]] routing verdict counter for the given
   * `target` and `verdict` label, or 0 if no value has been recorded.
   */
  def getRoutingVerdictCount(target: Target, verdict: String): Long =
    MetricUtils
      .getMetricValue(
        CollectorRegistry.defaultRegistry,
        metric = "dicer_assigner_target_migration_verdicts_total",
        labels = Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel,
          "verdict" -> verdict
        )
      )
      .toLong

  /**
   * Builds an active [[TargetMigrationConfig]] (i.e. one whose [[TargetMigrationType]] is not
   * [[TargetMigrationType.NoMigration]]) with the given overrides. The resolver's behavior is the
   * same for every active migration type, so any active type works here;
   * [[TargetMigrationType.GeneralToSmk]] is used because it is currently the only one.
   */
  def makeActiveTargetMigrationConfig(
      forceToSourceTargetNames: Set[TargetName] = Set.empty,
      forceToDestinationTargetNames: Set[TargetName] = Set.empty,
      destinationTargetNameFraction: Double = 0.0): TargetMigrationConfig =
    TargetMigrationConfig(
      version = MIGRATION_CONFIG_VERSION,
      migrationType = TargetMigrationType.GeneralToSmk,
      forceToSourceTargetNames = forceToSourceTargetNames,
      forceToDestinationTargetNames = forceToDestinationTargetNames,
      destinationTargetNameFraction = destinationTargetNameFraction
    )

  /** Builds a [[TargetOwnershipResolver]] pinned to the given snapshot. */
  def makeResolver(snapshot: TargetMigrationSnapshot): TargetOwnershipResolver =
    new TargetOwnershipResolver(EXAMPLE_ASSIGNER_UUID, snapshot)

  /**
   * Returns the value of the [[TargetOwnershipResolver]] override counter for the given
   * `target`, or 0 if no value has been recorded.
   */
  def getOverrideCount(target: Target): Long =
    MetricUtils
      .getMetricValue(
        CollectorRegistry.defaultRegistry,
        metric = "dicer_assigner_target_migration_routing_overrides_total",
        labels = Map(
          "targetCluster" -> target.getTargetClusterLabel,
          "targetName" -> target.getTargetNameLabel,
          "targetInstanceId" -> target.getTargetInstanceIdLabel
        )
      )
      .toLong
}

/**
 * Each test in this suite runs once per [[assignerRole]]. The test body asserts the verdict
 * appropriate for the role under test.
 */
class ParameterizedTargetOwnershipResolverSuite(assignerRole: TargetMigrationRole)
    extends DatabricksTest
    with ParameterizedTestNameDecorator {

  /**
   * Parameters whose values get appended to each test name. Required by
   * [[ParameterizedTestNameDecorator]] so the role under test is visible in test reports.
   */
  override val paramsForDebug: Map[String, Any] = Map("assignerRole" -> assignerRole)

  /** URI of the peer Assigner. Source's peer is the Destination Assigner, and vice versa. */
  private val peerAssignerUri: URI = assignerRole match {
    case TargetMigrationRole.Source => EXAMPLE_DESTINATION_ASSIGNER_URI
    case TargetMigrationRole.Destination => EXAMPLE_SOURCE_ASSIGNER_URI
  }

  /**
   * Builds a [[TargetOwnershipResolver]] pinned to an [[TargetMigrationSnapshot.ActiveMigration]]
   * with the given config and the suite's [[assignerRole]] / [[peerAssignerUri]].
   */
  private def makeActiveResolver(config: TargetMigrationConfig): TargetOwnershipResolver =
    makeResolver(
      TargetMigrationSnapshot.ActiveMigration(
        targetMigrationConfig = config,
        targetMigrationRole = assignerRole,
        peerAssignerUris = Seq(peerAssignerUri)
      )
    )

  test("All targets must be handled by the receiving Assigner when no migration is active") {
    // Test plan: A `NoActiveMigration` snapshot short-circuits the resolver regardless of the
    // target. Exercise multiple distinct targets to confirm the short-circuit applies for any
    // input.
    val expected = RoutingVerdict.Handle(redirectTokenOpt = None)

    val resolver: TargetOwnershipResolver =
      makeResolver(TargetMigrationSnapshot.NoActiveMigration(TargetMigrationConfig.NO_MIGRATION))
    Seq(Target("foo"), Target("bar"), Target("baz")).foreach { target: Target =>
      assertResult(expected)(
        resolver.getRoutingVerdict(target, inboundRedirectTokenOpt = None)
      )
    }
  }

  test("forceToSourceTargetNames pins targets to the Source Assigner") {
    // Test plan: Targets in `forceToSourceTargetNames` have an owner role of Source, and each
    // verdict increments only the matching Handle/Reroute metric bucket.
    val expected: RoutingVerdict = assignerRole match {
      case TargetMigrationRole.Source => EXPECTED_HANDLE_LOCAL
      case TargetMigrationRole.Destination => EXPECTED_REROUTE_TO_SOURCE
    }
    val expectedVerdict: String = assignerRole match {
      case TargetMigrationRole.Source => "Handle"
      case TargetMigrationRole.Destination => "Reroute"
    }
    val oppositeVerdict: String = assignerRole match {
      case TargetMigrationRole.Source => "Reroute"
      case TargetMigrationRole.Destination => "Handle"
    }

    val pinnedTargets: Seq[Target] =
      Seq(Target("foo"), Target("bar"), Target.createAppTarget("baz", "instance-id"))
    val config: TargetMigrationConfig = makeActiveTargetMigrationConfig(
      forceToSourceTargetNames = pinnedTargets.map(TargetName.forTarget).toSet
    )
    val resolver: TargetOwnershipResolver = makeActiveResolver(config)
    pinnedTargets.foreach { target: Target =>
      val verdictTracker: ChangeTracker[Long] =
        ChangeTracker[Long] { () =>
          getRoutingVerdictCount(target, verdict = expectedVerdict)
        }
      val oppositeVerdictTracker: ChangeTracker[Long] =
        ChangeTracker[Long] { () =>
          getRoutingVerdictCount(target, verdict = oppositeVerdict)
        }
      assertResult(expected)(
        resolver.getRoutingVerdict(target, inboundRedirectTokenOpt = None)
      )
      assertResult(1L)(verdictTracker.totalChange())
      assertResult(0L)(oppositeVerdictTracker.totalChange())
    }
  }

  test("forceToDestinationTargetNames pins targets to the Destination Assigner") {
    // Test plan: Targets in `forceToDestinationTargetNames` have an owner role of Destination, and
    // each verdict increments only the matching Handle/Reroute metric bucket.
    val expected: RoutingVerdict = assignerRole match {
      case TargetMigrationRole.Source => EXPECTED_REROUTE_TO_DESTINATION
      case TargetMigrationRole.Destination => EXPECTED_HANDLE_LOCAL
    }
    val expectedVerdict: String = assignerRole match {
      case TargetMigrationRole.Source => "Reroute"
      case TargetMigrationRole.Destination => "Handle"
    }
    val oppositeVerdict: String = assignerRole match {
      case TargetMigrationRole.Source => "Handle"
      case TargetMigrationRole.Destination => "Reroute"
    }

    val pinnedTargets: Seq[Target] =
      Seq(
        Target.createKubernetesTarget(
          URI.create("kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01"),
          "foo"
        ),
        Target.createAppTarget("bar", "instance-id"),
        Target("baz")
      )
    val config: TargetMigrationConfig = makeActiveTargetMigrationConfig(
      forceToDestinationTargetNames = pinnedTargets.map(TargetName.forTarget).toSet
    )
    val resolver: TargetOwnershipResolver = makeActiveResolver(config)
    pinnedTargets.foreach { target: Target =>
      val verdictTracker: ChangeTracker[Long] =
        ChangeTracker[Long] { () =>
          getRoutingVerdictCount(target, verdict = expectedVerdict)
        }
      val oppositeVerdictTracker: ChangeTracker[Long] =
        ChangeTracker[Long] { () =>
          getRoutingVerdictCount(target, verdict = oppositeVerdict)
        }
      assertResult(expected)(
        resolver.getRoutingVerdict(target, inboundRedirectTokenOpt = None)
      )
      assertResult(1L)(verdictTracker.totalChange())
      assertResult(0L)(oppositeVerdictTracker.totalChange())
    }
  }

  test(
    "destinationTargetNameFraction of 0% routes all unpinned targets to the Source Assigner"
  ) {
    // Test plan: With a destination rollout fraction of 0%, all unpinned targets have an owner
    // role of Source. A separate target is also pinned to Destination to verify that the pin
    // does not accidentally influence the verdicts for the unpinned targets, but the pin still
    // applies for this target as expected.
    val expectedForUnpinned: RoutingVerdict = assignerRole match {
      case TargetMigrationRole.Source => EXPECTED_HANDLE_LOCAL
      case TargetMigrationRole.Destination => EXPECTED_REROUTE_TO_SOURCE
    }
    val expectedForPinnedToDestination: RoutingVerdict = assignerRole match {
      case TargetMigrationRole.Source => EXPECTED_REROUTE_TO_DESTINATION
      case TargetMigrationRole.Destination => EXPECTED_HANDLE_LOCAL
    }

    val config: TargetMigrationConfig = makeActiveTargetMigrationConfig(
      forceToDestinationTargetNames = Set(TargetName("pinned-to-destination")),
      destinationTargetNameFraction = 0.0
    )
    val resolver: TargetOwnershipResolver = makeActiveResolver(config)
    Seq(Target("foo"), Target("bar"), Target("baz")).foreach { target: Target =>
      assertResult(expectedForUnpinned)(
        resolver.getRoutingVerdict(target, inboundRedirectTokenOpt = None)
      )
    }
    assertResult(expectedForPinnedToDestination)(
      resolver.getRoutingVerdict(Target("pinned-to-destination"), inboundRedirectTokenOpt = None)
    )
  }

  test(
    "destinationTargetNameFraction of 100% routes all unpinned targets to the Destination Assigner"
  ) {
    // Test plan: With a destination rollout fraction of 100%, all unpinned targets have an owner
    // role of Destination. A separate target is also pinned to Source to verify that the pin
    // does not accidentally influence the verdicts for the unpinned targets, but the pin still
    // applies for this target as expected.
    val expectedForUnpinned: RoutingVerdict = assignerRole match {
      case TargetMigrationRole.Source => EXPECTED_REROUTE_TO_DESTINATION
      case TargetMigrationRole.Destination => EXPECTED_HANDLE_LOCAL
    }
    val expectedForPinnedToSource: RoutingVerdict = assignerRole match {
      case TargetMigrationRole.Source => EXPECTED_HANDLE_LOCAL
      case TargetMigrationRole.Destination => EXPECTED_REROUTE_TO_SOURCE
    }

    val config: TargetMigrationConfig = makeActiveTargetMigrationConfig(
      forceToSourceTargetNames = Set(TargetName("pinned-to-source")),
      destinationTargetNameFraction = 1.0
    )
    val resolver: TargetOwnershipResolver = makeActiveResolver(config)
    Seq(Target("foo"), Target("bar"), Target("baz")).foreach { target: Target =>
      assertResult(expectedForUnpinned)(
        resolver.getRoutingVerdict(target, inboundRedirectTokenOpt = None)
      )
    }
    assertResult(expectedForPinnedToSource)(
      resolver.getRoutingVerdict(Target("pinned-to-source"), inboundRedirectTokenOpt = None)
    )
  }

  test("forceToSourceTargetNames takes precedence over destinationTargetNameFraction") {
    // Test plan: With a destination rollout fraction of 100%, every unpinned target would
    // otherwise be routed to Destination. The pin to Source overrides this, so the target's
    // owner role is Source.
    val expected: RoutingVerdict = assignerRole match {
      case TargetMigrationRole.Source => EXPECTED_HANDLE_LOCAL
      case TargetMigrationRole.Destination => EXPECTED_REROUTE_TO_SOURCE
    }

    val config: TargetMigrationConfig = makeActiveTargetMigrationConfig(
      forceToSourceTargetNames = Set(TargetName("pinned-to-source")),
      destinationTargetNameFraction = 1.0
    )
    val resolver: TargetOwnershipResolver = makeActiveResolver(config)
    assertResult(expected)(
      resolver.getRoutingVerdict(Target("pinned-to-source"), inboundRedirectTokenOpt = None)
    )
  }

  test("forceToDestinationTargetNames takes precedence over destinationTargetNameFraction") {
    // Test plan: With a destination rollout fraction of 0%, every unpinned target would
    // otherwise stay on Source. The pin to Destination overrides this, so the target's owner
    // role is Destination.
    val expected: RoutingVerdict = assignerRole match {
      case TargetMigrationRole.Source => EXPECTED_REROUTE_TO_DESTINATION
      case TargetMigrationRole.Destination => EXPECTED_HANDLE_LOCAL
    }

    val config: TargetMigrationConfig = makeActiveTargetMigrationConfig(
      forceToDestinationTargetNames = Set(TargetName("pinned-to-destination")),
      destinationTargetNameFraction = 0.0
    )
    val resolver: TargetOwnershipResolver = makeActiveResolver(config)
    assertResult(expected)(
      resolver.getRoutingVerdict(Target("pinned-to-destination"), inboundRedirectTokenOpt = None)
    )
  }

  test(
    "Inbound RedirectToken overrides Reroute to Handle only when its config version is strictly " +
    "newer"
  ) {
    // Test plan: Verify that with an active migration config, an inbound RedirectToken can force
    // the verdict from Reroute to Handle, but only with a strictly higher
    // `targetMigrationConfigVersion`. We construct a migration config with a target that is
    // pinned to the opposite role. When queried without a token or with a token where
    // `targetMigrationConfigVersion` <= local config version, the verdict must be Reroute to the
    // peer and the routing-overrides counter must not increment. With a strictly higher version
    // token, the verdict must be Handle, the outbound token must echo the inbound token so
    // subsequent in-cluster hops can apply the same override, and the routing-overrides counter
    // must advance by exactly one.
    val peerOwnedTargetName: TargetName = assignerRole match {
      // Pin the target to the role opposite to the receiving Assigner so that local routing's
      // baseline verdict for this target is Reroute to the peer.
      case TargetMigrationRole.Source => TargetName("pinned-to-destination")
      case TargetMigrationRole.Destination => TargetName("pinned-to-source")
    }
    val peerOwnedTarget: Target = Target.createAppTarget(peerOwnedTargetName.value, "instance-id")
    val config: TargetMigrationConfig = assignerRole match {
      case TargetMigrationRole.Source =>
        makeActiveTargetMigrationConfig(forceToDestinationTargetNames = Set(peerOwnedTargetName))
      case TargetMigrationRole.Destination =>
        makeActiveTargetMigrationConfig(forceToSourceTargetNames = Set(peerOwnedTargetName))
    }
    val resolver: TargetOwnershipResolver = makeActiveResolver(config)
    // If the local config is same or newer, the Reroute that we expect to receive.
    val expectedReroute: RoutingVerdict.Reroute = RoutingVerdict.Reroute(
      peerAssignerUri = peerAssignerUri,
      redirectToken = RedirectToken(MIGRATION_CONFIG_VERSION)
    )
    // Track the change in the override counter for `peerOwnedTarget`.
    val overrideTracker: ChangeTracker[Long] =
      ChangeTracker[Long] { () =>
        getOverrideCount(peerOwnedTarget)
      }

    // Without the token, the baseline verdict is Reroute to the peer and the override counter is
    // unchanged.
    assertResult(expectedReroute)(
      resolver.getRoutingVerdict(peerOwnedTarget, inboundRedirectTokenOpt = None)
    )
    assertResult(0L)(overrideTracker.totalChange())

    // With a token whose `targetMigrationConfigVersion` is <= local config version, the verdict
    // must remain Reroute to the peer and the override counter is unchanged.
    for (tokenVersion: Int <- Seq(MIGRATION_CONFIG_VERSION - 1, MIGRATION_CONFIG_VERSION)) {
      val sameOrOlderToken: RedirectToken =
        RedirectToken(targetMigrationConfigVersion = tokenVersion)
      assertResult(expectedReroute)(
        resolver.getRoutingVerdict(
          peerOwnedTarget,
          inboundRedirectTokenOpt = Some(sameOrOlderToken)
        )
      )
      assertResult(0L)(overrideTracker.totalChange())
    }

    // With a strictly-newer token, the override forces Handle, the inbound token is echoed
    // forward in the verdict so subsequent in-cluster hops can apply the same override, and the
    // override counter advances by exactly one.
    val newerToken: RedirectToken =
      RedirectToken(targetMigrationConfigVersion = MIGRATION_CONFIG_VERSION + 1)
    assertResult(RoutingVerdict.Handle(redirectTokenOpt = Some(newerToken)))(
      resolver.getRoutingVerdict(peerOwnedTarget, inboundRedirectTokenOpt = Some(newerToken))
    )
    assertResult(1L)(overrideTracker.totalChange())
  }

  test(
    "Handle's RedirectToken is max(inbound, local) config version"
  ) {
    // Test plan: Verify that on the local-Handle path, the outbound `redirectToken` carries
    // `max(inboundVersion, localVersion)`, and the routing-overrides counter never increments
    // since the override only fires on the Reroute path. We construct a migration config with a
    // target pinned to the receiving Assigner's own role so the baseline verdict is Handle, and
    // exercise the cases where the inbound token is absent, older, equal, and strictly newer
    // than the local config version.
    val selfOwnedTarget: Target = assignerRole match {
      // Pin the target to the receiving Assigner's own role so the baseline verdict is Handle.
      case TargetMigrationRole.Source => Target("pinned-to-source")
      case TargetMigrationRole.Destination => Target("pinned-to-destination")
    }
    val config: TargetMigrationConfig = assignerRole match {
      case TargetMigrationRole.Source =>
        makeActiveTargetMigrationConfig(
          forceToSourceTargetNames = Set(TargetName.forTarget(selfOwnedTarget))
        )
      case TargetMigrationRole.Destination =>
        makeActiveTargetMigrationConfig(
          forceToDestinationTargetNames = Set(TargetName.forTarget(selfOwnedTarget))
        )
    }
    val resolver: TargetOwnershipResolver = makeActiveResolver(config)
    // Track the change in the override counter for `selfOwnedTarget`.
    val overrideTracker: ChangeTracker[Long] =
      ChangeTracker[Long] { () =>
        getOverrideCount(selfOwnedTarget)
      }

    // Absent, older, or equal inbound token: outbound token version equals the local config
    // version and the override counter is unchanged.
    val inboundTokensAtOrBelowLocal: Seq[Option[RedirectToken]] = Seq(
      None,
      Some(RedirectToken(targetMigrationConfigVersion = MIGRATION_CONFIG_VERSION - 1)),
      Some(RedirectToken(targetMigrationConfigVersion = MIGRATION_CONFIG_VERSION))
    )
    for (inboundTokenOpt: Option[RedirectToken] <- inboundTokensAtOrBelowLocal) {
      assertResult(EXPECTED_HANDLE_LOCAL)(
        resolver.getRoutingVerdict(selfOwnedTarget, inboundRedirectTokenOpt = inboundTokenOpt)
      )
      assertResult(0L)(overrideTracker.totalChange())
    }

    // Strictly newer inbound token: outbound token version equals the inbound version (so
    // subsequent in-cluster hops see the freshest known version), and the override counter is
    // still unchanged because the override only fires on the Reroute path.
    val newerToken: RedirectToken =
      RedirectToken(targetMigrationConfigVersion = MIGRATION_CONFIG_VERSION + 9)
    assertResult(RoutingVerdict.Handle(redirectTokenOpt = Some(newerToken)))(
      resolver.getRoutingVerdict(selfOwnedTarget, inboundRedirectTokenOpt = Some(newerToken))
    )
    assertResult(0L)(overrideTracker.totalChange())
  }

  test("wouldReroute is always false when no migration is active") {
    // Test plan: A `NoActiveMigration` snapshot means this Assigner handles every target locally,
    // so `wouldReroute` must be false for any target.
    val resolver: TargetOwnershipResolver =
      makeResolver(TargetMigrationSnapshot.NoActiveMigration(TargetMigrationConfig.NO_MIGRATION))
    Vector(Target("foo"), Target("bar"), Target("baz")).foreach { target: Target =>
      withClue(s"target=$target: ") {
        assert(!resolver.wouldReroute(target))
      }
    }
  }

  test("configVersion reflects the backing config's version for active and inactive snapshots") {
    // Test plan: Verify `configVersion` returns the version of the config backing the resolver, for
    // both an active-migration snapshot (which carries the suite's MIGRATION_CONFIG_VERSION) and a
    // no-active-migration snapshot (which carries NO_MIGRATION's version of 0).
    val activeResolver: TargetOwnershipResolver =
      makeActiveResolver(makeActiveTargetMigrationConfig())
    assertResult(MIGRATION_CONFIG_VERSION)(activeResolver.configVersion)

    val inactiveResolver: TargetOwnershipResolver =
      makeResolver(TargetMigrationSnapshot.NoActiveMigration(TargetMigrationConfig.NO_MIGRATION))
    assertResult(TargetMigrationConfig.NO_MIGRATION.version)(inactiveResolver.configVersion)
  }

}

package com.databricks.dicer.assigner

import java.net.URI
import javax.annotation.concurrent.Immutable

import com.databricks.caching.util.DeterministicSampling
import com.databricks.dicer.assigner.config.{
  TargetMigrationConfig,
  TargetMigrationRole,
  TargetMigrationType
}
import com.databricks.dicer.common.TargetName
import io.prometheus.client.Counter

/**
 * Resolves, for each [[TargetName]], whether the Assigner should handle the target's watch requests
 * locally ([[RoutingVerdict.Handle]]) or reroute them to another Assigner
 * ([[RoutingVerdict.Reroute]]).
 *
 * This class is immutable and pinned to a specific [[TargetMigrationSnapshot]] captured at
 * construction time. This makes reasoning about the semantics and concurrency model
 * straightforward.
 *
 * @param snapshot The [[TargetMigrationSnapshot]] used to determine all verdicts from this
 *                 resolver.
 */
@Immutable
class TargetOwnershipResolver(snapshot: TargetMigrationSnapshot) {

  /**
   * Returns the [[RoutingVerdict]] for the given [[TargetName]], indicating whether this Assigner
   * should handle the target's watch requests or reroute them to another Assigner.
   *
   * @param inboundRedirectTokenOpt The [[RedirectToken]] received from the client from a prior
   *                                redirect. When present and its `targetMigrationConfigVersion`
   *                                exceeds the local config's version, the verdict is forced to
   *                                [[RoutingVerdict.Handle]] — this prevents reroute ping-pong
   *                                while a new [[TargetMigrationConfig]] rolls out and reaches
   *                                different Assigners at different times.
   */
  def getRoutingVerdict(
      targetName: TargetName,
      inboundRedirectTokenOpt: Option[RedirectToken]): RoutingVerdict = {
    snapshot match {
      case _: TargetMigrationSnapshot.NoActiveMigration =>
        // No migration is active locally, so always handle. We assume that if we're in this mode,
        // the migration is quiesced - either not started or complete, so propagating the token is
        // not necessary.
        RoutingVerdict.Handle(redirectTokenOpt = None)
      case TargetMigrationSnapshot.ActiveMigration(
          targetMigrationConfig: TargetMigrationConfig,
          currentAssignerRole: TargetMigrationRole,
          peerAssignerUri: URI
          ) =>
        val targetOwnerRole: TargetMigrationRole =
          resolveTargetOwnerRole(targetName, targetMigrationConfig)
        val localVerdictIsReroute: Boolean = targetOwnerRole != currentAssignerRole
        val senderConfigIsNewer: Boolean =
          inboundRedirectTokenOpt.exists(
            (inboundToken: RedirectToken) =>
              inboundToken.targetMigrationConfigVersion > targetMigrationConfig.version
          )
        if (localVerdictIsReroute) {
          if (senderConfigIsNewer) {
            // The sender redirected based on a config newer than ours, and we would otherwise
            // reroute back to them. Trust the sender and handle locally to avoid bouncing the
            // client back and forth until our config catches up. The outbound token is the
            // inbound token, since in this case we know inbound > local.
            TargetOwnershipResolver.targetMigrationRoutingOverrides
              .labels(targetName.toString)
              .inc()
            RoutingVerdict.Handle(inboundRedirectTokenOpt)
          } else {
            // Use the local config version for the redirect token, since in this case we know
            // it's >= the inbound token's version.
            RoutingVerdict.Reroute(
              peerAssignerUri = peerAssignerUri,
              redirectToken = RedirectToken(
                targetMigrationConfigVersion = targetMigrationConfig.version
              )
            )
          }
        } else {
          // The outbound token's version is max(inbound, local) so any further hop (e.g. for
          // preferred assigner) sees the freshest known version. This can help avoid ping-ponging
          // if the preferred assigner has not received this version of the config yet.
          val outboundVersion: Int =
            inboundRedirectTokenOpt match {
              case Some(inboundToken: RedirectToken) =>
                math.max(inboundToken.targetMigrationConfigVersion, targetMigrationConfig.version)
              case None =>
                targetMigrationConfig.version
            }
          RoutingVerdict.Handle(Some(RedirectToken(targetMigrationConfigVersion = outboundVersion)))
        }
    }
  }

  /**
   * Resolves whether the `SOURCE` or `DESTINATION` Assigner is responsible for a given target.
   *
   * Order of evaluation precedence:
   *   1. [[TargetMigrationConfig.forceToSourceTargetNames]] - the target is unconditionally
   *      handled by the `SOURCE` Assigner.
   *   2. [[TargetMigrationConfig.forceToDestinationTargetNames]] - the target is unconditionally
   *      handled by the `DESTINATION` Assigner.
   *   3. [[TargetMigrationConfig.destinationTargetNameFraction]] - the target is handled by the
   *      `DESTINATION` Assigner if it falls into the fraction of targets that are selected
   *       deterministically to be handled by the `DESTINATION` Assigner.
   */
  private def resolveTargetOwnerRole(
      targetName: TargetName,
      targetMigrationConfig: TargetMigrationConfig): TargetMigrationRole = {
    if (targetMigrationConfig.forceToSourceTargetNames.contains(targetName)) {
      TargetMigrationRole.Source
    } else if (targetMigrationConfig.forceToDestinationTargetNames.contains(targetName)) {
      TargetMigrationRole.Destination
    } else {
      val shouldRouteToDestination: Boolean = DeterministicSampling.isSampled(
        item = targetName.value,
        sampleNamespace = targetMigrationConfig.migrationType.toString,
        sampleFraction = targetMigrationConfig.destinationTargetNameFraction
      )
      if (shouldRouteToDestination) TargetMigrationRole.Destination else TargetMigrationRole.Source
    }
  }
}

object TargetOwnershipResolver {

  /**
   * Counts the number of times the routing verdict was forced to [[RoutingVerdict.Handle]]
   * based on the inbound [[RedirectToken.targetMigrationConfigVersion]] being newer than the
   * local config's version. Only incremented on the override path (i.e. when local routing
   * would otherwise have rerouted).
   */
  private[assigner] val targetMigrationRoutingOverrides: Counter = Counter
    .build()
    .name("dicer_assigner_target_migration_routing_overrides_total")
    .help(
      "Number of times the routing verdict was forced to Handle based on the inbound " +
      "redirect token being newer than the local config version."
    )
    .labelNames("targetName")
    .register()
}

/** A snapshot of the current target migration state. */
private[dicer] sealed trait TargetMigrationSnapshot

private[dicer] object TargetMigrationSnapshot {

  /**
   * Snapshot when no target migration is currently active. Used exclusively for
   * [[TargetMigrationType.NoMigration]].
   *
   * @throws IllegalArgumentException If `targetMigrationConfig.migrationType` is not
   *                                  [[TargetMigrationType.NoMigration]].
   */
  final case class NoActiveMigration @throws[IllegalArgumentException]()(
      targetMigrationConfig: TargetMigrationConfig)
      extends TargetMigrationSnapshot {
    require(
      targetMigrationConfig.migrationType == TargetMigrationType.NoMigration,
      s"The `NoActiveMigration` snapshot must wrap a `NoMigration` target migration config, but " +
      s"got ${targetMigrationConfig.migrationType}"
    )
  }

  /**
   * Snapshot when a target migration is currently active. Used for every [[TargetMigrationType]]
   * except for [[TargetMigrationType.NoMigration]].
   *
   * @param targetMigrationConfig The currently active [[TargetMigrationConfig]].
   * @param targetMigrationRole   The role this Assigner plays in the migration — `Source` or
   *                              `Destination`. A verdict is `Handle` when the resolved owner role
   *                              for a target matches this, and `Reroute` otherwise.
   * @param peerAssignerUri       URI of the peer Assigner on the *other* side of the migration.
   *                              Used with [[RoutingVerdict.Reroute]] to inform the client where to
   *                              go next.
   * @throws IllegalArgumentException If `targetMigrationConfig.migrationType` is
   *                                  [[TargetMigrationType.NoMigration]].
   */
  final case class ActiveMigration @throws[IllegalArgumentException]()(
      targetMigrationConfig: TargetMigrationConfig,
      targetMigrationRole: TargetMigrationRole,
      peerAssignerUri: URI)
      extends TargetMigrationSnapshot {
    require(
      targetMigrationConfig.migrationType != TargetMigrationType.NoMigration,
      "The `ActiveMigration` snapshot must not wrap a `NoMigration` target migration config."
    )
  }
}

/**
 * The verdict returned by [[TargetOwnershipResolver.getRoutingVerdict]], indicating how the
 * Assigner should handle a given target during a target migration.
 */
private[assigner] sealed trait RoutingVerdict

private[assigner] object RoutingVerdict {

  /**
   * The Assigner should handle the target's watch requests in the local cluster.
   *
   * @param redirectTokenOpt The optional [[RedirectToken]] to attach to the response's
   *                         redirect.
   */
  final case class Handle(redirectTokenOpt: Option[RedirectToken]) extends RoutingVerdict

  /**
   * The Assigner should reroute the client to the peer Assigner.
   *
   * @param peerAssignerUri    URI of the peer Assigner the client should follow next.
   * @param redirectToken      The [[RedirectToken]] to attach to the response's redirect.
   */
  final case class Reroute(peerAssignerUri: URI, redirectToken: RedirectToken)
      extends RoutingVerdict
}

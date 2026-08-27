package com.databricks.dicer.assigner

import java.net.URI
import java.util.UUID
import javax.annotation.concurrent.Immutable

import scala.util.Random

import com.databricks.api.base.DatabricksServiceException
import com.databricks.ErrorCode
import com.databricks.caching.util.DeterministicSampling
import com.databricks.dicer.assigner.config.{
  TargetMigrationConfig,
  TargetMigrationRole,
  TargetMigrationType
}
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.common.TargetName
import com.databricks.dicer.external.Target
import io.prometheus.client.Counter

/**
 * Resolves, for each [[Target]], whether the Assigner should handle the target's watch requests
 * locally ([[RoutingVerdict.Handle]]) or reroute them to another Assigner
 * ([[RoutingVerdict.Reroute]]).
 *
 * This class is immutable and pinned to a specific [[TargetMigrationSnapshot]] captured at
 * construction time. This makes reasoning about the semantics and concurrency model
 * straightforward.
 *
 * @param assignerUuidForDebug The UUID of the Assigner that owns this resolver, used only for
 *                             logs/exceptions.
 * @param snapshot             The [[TargetMigrationSnapshot]] used to determine all verdicts from
 *                             this resolver.
 */
@Immutable
class TargetOwnershipResolver(assignerUuidForDebug: UUID, snapshot: TargetMigrationSnapshot) {

  /**
   * Returns the [[RoutingVerdict]] for the given [[Target]], indicating whether this Assigner
   * should handle the target's watch requests or reroute them to another Assigner. Delegates the
   * reroute-vs-handle decision to [[wouldReroute]] and the outbound token to [[getRedirectToken]],
   * then on a reroute attaches the peer Assigner endpoint.
   *
   * @param inboundRedirectTokenOpt The [[RedirectToken]] received from the client from a prior
   *                                redirect. When present and its `targetMigrationConfigVersion`
   *                                exceeds the local config's version, the verdict is forced to
   *                                [[RoutingVerdict.Handle]] — this prevents reroute ping-pong
   *                                while a new [[TargetMigrationConfig]] rolls out and reaches
   *                                different Assigners at different times.
   */
  @throws[DatabricksServiceException](
    "if the target should be rerouted but no peer Assigner endpoint is currently available."
  )
  def getRoutingVerdict(
      target: Target,
      inboundRedirectTokenOpt: Option[RedirectToken]): RoutingVerdict = {
    val verdict: RoutingVerdict = snapshot match {
      case _: TargetMigrationSnapshot.NoActiveMigration =>
        // No migration is active locally, so always handle. We assume that if we're in this mode,
        // the migration is quiesced - either not started or complete, so propagating the token is
        // not necessary.
        RoutingVerdict.Handle(redirectTokenOpt = None)
      case TargetMigrationSnapshot.ActiveMigration(
          targetMigrationConfig: TargetMigrationConfig,
          _: TargetMigrationRole,
          peerAssignerUris: Seq[URI]
          ) =>
        val outboundToken: RedirectToken =
          createRedirectToken(inboundRedirectTokenOpt, targetMigrationConfig)
        if (wouldReroute(target)) {
          val senderConfigIsNewer: Boolean =
            inboundRedirectTokenOpt.exists(
              (inboundToken: RedirectToken) =>
                inboundToken.targetMigrationConfigVersion > targetMigrationConfig.version
            )
          if (senderConfigIsNewer) {
            // The sender redirected based on a config newer than ours, and we would otherwise
            // reroute back to them. Trust the sender and handle locally to avoid bouncing the
            // client back and forth until our config catches up.
            TargetOwnershipResolver.targetMigrationRoutingOverrides
              .labels(
                target.getTargetClusterLabel,
                target.getTargetNameLabel,
                target.getTargetInstanceIdLabel
              )
              .inc()
            RoutingVerdict.Handle(Some(outboundToken))
          } else {
            // Note: `selectPeerAssignerUri` may throw if we don't know any peer URIs.
            RoutingVerdict.Reroute(
              peerAssignerUri = selectPeerAssignerUri(target, peerAssignerUris),
              redirectToken = outboundToken
            )
          }
        } else {
          RoutingVerdict.Handle(Some(outboundToken))
        }
    }
    val routingVerdictLabel: TargetOwnershipResolver.RoutingVerdictLabel = verdict match {
      case _: RoutingVerdict.Reroute => TargetOwnershipResolver.RoutingVerdictLabel.Reroute
      case _: RoutingVerdict.Handle => TargetOwnershipResolver.RoutingVerdictLabel.Handle
    }
    TargetOwnershipResolver.recordTargetMigrationVerdict(target, routingVerdictLabel)
    verdict
  }

  /**
   * Returns whether this resolver would reroute `target`'s watch requests to another Assigner,
   * absent any inbound [[RedirectToken]] override. Always `false` when no migration is active.
   *
   * This is the single source of truth for the reroute-vs-handle decision. Unlike
   * [[getRoutingVerdict]], it records no metrics and does not fail, so it is also safe to call off
   * the request path.
   */
  def wouldReroute(target: Target): Boolean = {
    val targetName: TargetName = TargetName.forTarget(target)
    snapshot match {
      case _: TargetMigrationSnapshot.NoActiveMigration =>
        false
      case TargetMigrationSnapshot.ActiveMigration(
          targetMigrationConfig: TargetMigrationConfig,
          currentAssignerRole: TargetMigrationRole,
          _: Seq[URI]
          ) =>
        val targetOwnerRole: TargetMigrationRole =
          resolveTargetOwnerRole(targetName, targetMigrationConfig)
        targetOwnerRole != currentAssignerRole
    }
  }

  /**
   * The [[TargetMigrationConfig.version]] of the config backing this resolver. Because target
   * ownership is a function of the config (and this Assigner's fixed role), two resolvers with the
   * same version produce the same [[getRoutingVerdict]] verdict type (but possibly different peer
   * URIs) for every target.
   */
  def configVersion: Int = snapshot.configVersion

  /**
   * Returns the [[RedirectToken]] to attach to the outbound verdict during an active migration. Its
   * version is `max(inbound, local)` so any further hop (e.g. the preferred assigner) sees the
   * freshest known version, which helps avoid ping-ponging when a hop has not yet received this
   * config version. This is the same whether the verdict is a handle or a reroute, so it is
   * computed independently of the reroute decision.
   */
  private def createRedirectToken(
      inboundRedirectTokenOpt: Option[RedirectToken],
      targetMigrationConfig: TargetMigrationConfig): RedirectToken = {
    val outboundConfigVersion: Int =
      inboundRedirectTokenOpt match {
        case Some(inboundToken: RedirectToken) =>
          math.max(inboundToken.targetMigrationConfigVersion, targetMigrationConfig.version)
        case None =>
          targetMigrationConfig.version
      }
    RedirectToken(targetMigrationConfigVersion = outboundConfigVersion)
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

  /**
   * Selects a peer Assigner URI to reroute `target` to, chosen uniformly at random from
   * `peerAssignerUris`. This makes routing resilient to individual unhealthy peer pods. Any
   * Assigner in the remote cluster suffices: we redirect a target to that cluster, and the
   * cluster's preferred-assigner mechanism then forwards it to the appropriate preferred Assigner.
   *
   * An empty `peerAssignerUris` means no peer Assigner is currently available. In that case we
   * throw [[DatabricksServiceException]] with `ErrorCode.TEMPORARILY_UNAVAILABLE`. This is not
   * catastrophic since clients will retry their watch requests and eventually, by random chance,
   * have these requests end up on an Assigner in the intended/correct cluster (since we will set up
   * the Assigner's ClusterIP service and DBNS target to span both the General and SMK clusters).
   */
  @throws[DatabricksServiceException]("if no peer Assigner endpoint is available")
  private def selectPeerAssignerUri(target: Target, peerAssignerUris: Seq[URI]): URI = {
    if (peerAssignerUris.isEmpty) {
      val targetName: TargetName = TargetName.forTarget(target)
      TargetOwnershipResolver.recordTargetMigrationVerdict(
        target,
        TargetOwnershipResolver.RoutingVerdictLabel.RerouteFailed
      )
      // Include the Assigner UUID in the error message so that if we see the failure in client
      // logs, we can attribute it to the offending Assigner.
      throw DatabricksServiceException(
        ErrorCode.TEMPORARILY_UNAVAILABLE,
        s"Assigner $assignerUuidForDebug cannot reroute target $targetName because no peer " +
        s"Assigner URI is currently known."
      )
    }
    peerAssignerUris(Random.nextInt(peerAssignerUris.size))
  }
}

object TargetOwnershipResolver {

  /** The final routing verdict recorded in the [[targetMigrationVerdicts]] metric for a target. */
  private sealed trait RoutingVerdictLabel

  private object RoutingVerdictLabel {

    /** The target's watch requests are handled locally. */
    case object Handle extends RoutingVerdictLabel

    /** The target is rerouted to a peer Assigner. */
    case object Reroute extends RoutingVerdictLabel

    /**
     * A reroute was required but no peer Assigner is known, so the request was failed. See
     * `selectPeerAssignerUri` for more details.
     */
    case object RerouteFailed extends RoutingVerdictLabel
  }

  private[assigner] val targetMigrationVerdicts: Counter = Counter
    .build()
    .name("dicer_assigner_target_migration_verdicts_total")
    .help(
      "Target migration routing verdicts, labelled by target identity and the verdict: Handle, " +
      "Reroute, or RerouteFailed."
    )
    .labelNames("targetCluster", "targetName", "targetInstanceId", "verdict")
    .register()

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
    .labelNames("targetCluster", "targetName", "targetInstanceId")
    .register()

  /** Records the final routing `verdict` returned for `target`. */
  private def recordTargetMigrationVerdict(target: Target, verdict: RoutingVerdictLabel): Unit = {
    targetMigrationVerdicts
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        verdict.toString
      )
      .inc()
  }
}

/** A snapshot of the current target migration state. */
private[dicer] sealed trait TargetMigrationSnapshot {

  /** The [[TargetMigrationConfig.version]] of the config backing this snapshot. */
  def configVersion: Int
}

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

    override def configVersion: Int = targetMigrationConfig.version
  }

  /**
   * Snapshot when a target migration is currently active. Used for every [[TargetMigrationType]]
   * except for [[TargetMigrationType.NoMigration]].
   *
   * @param targetMigrationConfig The currently active [[TargetMigrationConfig]].
   * @param targetMigrationRole   The role this Assigner plays in the migration — `Source` or
   *                              `Destination`. A verdict is `Handle` when the resolved owner role
   *                              for a target matches this, and `Reroute` otherwise.
   * @param peerAssignerUris      URIs of the peer Assigners on the *other* side of the migration.
   *                              On a [[RoutingVerdict.Reroute]], the resolver picks one at random
   *                              to inform the client where to go next.
   * @throws IllegalArgumentException If `targetMigrationConfig.migrationType` is
   *                                  [[TargetMigrationType.NoMigration]].
   */
  final case class ActiveMigration @throws[IllegalArgumentException]()(
      targetMigrationConfig: TargetMigrationConfig,
      targetMigrationRole: TargetMigrationRole,
      peerAssignerUris: Seq[URI])
      extends TargetMigrationSnapshot {
    require(
      targetMigrationConfig.migrationType != TargetMigrationType.NoMigration,
      "The `ActiveMigration` snapshot must not wrap a `NoMigration` target migration config."
    )

    override def configVersion: Int = targetMigrationConfig.version
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

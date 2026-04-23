package com.databricks.caching.util

/**
 * Severity of the error. Calling [[PrefixLogger.alert]] with these severities increments a metric,
 * for which we have defined alerts in Caching team services. Note that this means it will NOT
 * create alerts if called in other services. Alerts are created based on every unique combination
 * of error code and prefix.
 */
object Severity extends Enumeration {

  /**
   * Degraded severity, system will continue to work albeit in degraded state. This should though be
   * investigated. Pages SEV1 i.e. only during business hours.
   */
  val DEGRADED: Value = Value

  /**
   * Critical severity, an invariant is violated and the system may not be working correctly. We
   * don't expect this to happen. Pages SEV0 i.e. at all hours.
   */
  val CRITICAL: Value = Value
}

/**
 * Error code used in logging, metrics and alerts.
 *
 * This list of error codes is shared across both Softstore and Dicer projects.
 */
sealed trait CachingErrorCode {

  /** The team responsible for handling alerts for this error code. */
  val alertOwnerTeam: AlertOwnerTeam
}

object CachingErrorCode {

  // Assigner errors

  /** Failure to initialize Kubernetes client on service startup. */
  case object KUBERNETES_INIT extends CachingErrorCode {
    override val alertOwnerTeam: AlertOwnerTeam = AlertOwnerTeam.CachingTeam
  }

  /**
   * The namespace for Kubernetes watch for the given target does not match the namespace seen in
   * SliceletData.
   */
  case object SLICELET_NAMESPACE_MISMATCH extends CachingErrorCode {
    override val alertOwnerTeam: AlertOwnerTeam = AlertOwnerTeam.CachingTeam
  }

  /**
   * Slicelet is sending empty namespace. This is not expected, all target applications should have
   * namespace populated in SliceletData.
   */
  case object SLICELET_EMPTY_NAMESPACE extends CachingErrorCode {
    override val alertOwnerTeam: AlertOwnerTeam = AlertOwnerTeam.CachingTeam
  }

  /**
   * A Slicelet reported a proto state value that the Assigner does not recognize. This may arise
   * when a Slicelet binary is newer than the Assigner (forward compatibility). The unrecognized
   * state is normalized to [[com.databricks.dicer.common.SliceletState.Running]] on ingestion.
   */
  case object SLICELET_UNKNOWN_PROTO_STATE extends CachingErrorCode {
    override val alertOwnerTeam: AlertOwnerTeam = AlertOwnerTeam.CachingTeam
  }

  /**
   * The Dicer Assigner produced an assignment with too many Slices given the number of available
   * resources in the sharded service.
   */
  case object ASSIGNER_TOO_MANY_SLICES extends CachingErrorCode {
    override val alertOwnerTeam: AlertOwnerTeam = AlertOwnerTeam.CachingTeam
  }

  /**
   * The Dicer Assigner produced an assignment with too few Slices given the number of available
   * resources in the sharded service. This may occur if:
   *
   *  - There are no remaining splittable Slices in the assignment: all Slices in the assignment
   *    either have zero load or contain a single key. If this is the case, we should consider
   *    performing splits of zero-load Slices to account for "potential" future load in all parts of
   *    the key space, and adjust our load balancing objective function accordingly.
   *  - There is some issue with the sharding Algorithm preventing creation of splits.
   */
  case object ASSIGNER_TOO_FEW_SLICES extends CachingErrorCode {
    override val alertOwnerTeam: AlertOwnerTeam = AlertOwnerTeam.CachingTeam
  }

  /**
   * The Dicer Assigner produced an assignment where multiple resource Squids had the same UUID,
   * which is unexpected.
   */
  case object ASSIGNER_ASSIGNED_SQUIDS_WITH_SAME_UUID extends CachingErrorCode {
    override val alertOwnerTeam: AlertOwnerTeam = AlertOwnerTeam.CachingTeam
  }

  /**
   * The Dicer Assigner produced a homomorphic assignment that fails the validation check, either
   * due to slice boundaries or replica groups changing.
   */
  case object ASSIGNER_INVALID_HOMOMORPHIC_ASSIGNMENT extends CachingErrorCode {
    override val alertOwnerTeam: AlertOwnerTeam = AlertOwnerTeam.CachingTeam
  }

  /**
   * The Dicer Assigner attempted to write an assignment to a durable store based on a previous
   * assignment with a generation that is greater than the latest one known to the store. Since
   * assignments must be persisted before being distributed in the system, this indicates either
   * data loss in the store, or that an assignment was externalized before being made durable.
   */
  case object ASSIGNER_KNOWS_GREATER_ASSIGNMENT_GENERATION_THAN_DURABLE_STORE
      extends CachingErrorCode {
    override val alertOwnerTeam: AlertOwnerTeam = AlertOwnerTeam.CachingTeam
  }

  /**
   * The Dicer assigner attempted to write an preferred assigner value to a durable store based on
   * a previous preferred assigner value with a generation that is greater than the latest one
   * known to the store. Since preferred assigner values must be persisted before being distributed
   * in the system, this indicates either data loss in the store, or that a preferred assigner
   * value was externalized before being made durable.
   */
  case object ASSIGNER_KNOWS_LATER_PREFERRED_ASSIGNER_VALUE_THAN_DURABLE_STORE
      extends CachingErrorCode {
    override val alertOwnerTeam: AlertOwnerTeam = AlertOwnerTeam.CachingTeam
  }

  /**
   * The `onComplete` in etcd client watch listener is expected to be called either when the watch
   * is cancelled explicitly or when there is a fatal failure from etcd server. Any other scenario
   * is unexpected.
   */
  case object ETCD_CLIENT_UNEXPECTED_WATCH_FAILURE extends CachingErrorCode {
    override val alertOwnerTeam: AlertOwnerTeam = AlertOwnerTeam.CachingTeam
  }

  /** The Dicer assignment data stored in etcd is corrupted. */
  case object ETCD_ASSIGNMENT_STORE_CORRUPTION extends CachingErrorCode {
    override val alertOwnerTeam: AlertOwnerTeam = AlertOwnerTeam.CachingTeam
  }

  /**
   * The current assignment in the assigner store does not match the incarnation of the store. This
   * violates the store invariant and indicates a bug in our code.
   */
  case object STORE_INCARNATION_ASSIGNMENT_MISMATCH extends CachingErrorCode {
    override val alertOwnerTeam: AlertOwnerTeam = AlertOwnerTeam.CachingTeam
  }

  /**
   * At startup, Caching services attempt to determine which "shard" (e.g., "prod-cloud1-region1")
   * they're running in by accessing LocationConf properties. This condition triggers when parsing
   * the shard context fails. The shard is used to determine whether configuration overrides are
   * applicable to the current shard, so a failure to determine the local shard may result in the
   * incorrect configuration being applied.
   */
  case object BAD_SHARD_CONFIGURATION extends CachingErrorCode {
    override val alertOwnerTeam: AlertOwnerTeam = AlertOwnerTeam.CachingTeam
  }

  /**
   * The assigner received a watch request from a Slicelet where the sum of load from top keys
   * exceeds the total load from the Slice. This can happen due to accumulated floating point
   * errors. We rescale the top keys load in this case, but we may want to investigate and find ways
   * to reduce the floating point errors in LossyEwmaCounter.
   */
  case object TOP_KEYS_LOAD_EXCEEDS_SLICE_LOAD extends CachingErrorCode {
    override val alertOwnerTeam: AlertOwnerTeam = AlertOwnerTeam.CachingTeam
  }

  /**
   * The preferred assigner store is unable to parse the preferred assigner value from etcd.
   * This is an error which requires immediate mitigation, and pager must go off.
   *
   * This can occur in the following scenarios:
   * 1. we have a bug in [[EtcdPreferredAssignerStore]] code that writes malformed values to Etcd.
   * 2. someone successfully writes a malformed value to Etcd.
   * 3. [[PreferredAssignerValueP]] evolves into a format that's not compatible with previous
   *    versions.
   *
   * Please check the preferred assigner logs and the Etcd status for the exact cause, and follow
   * <internal link> to disable preferred assigner if necessary.
   */
  case object PREFERRED_ASSIGNER_STORE_CORRUPTED extends CachingErrorCode {
    override val alertOwnerTeam: AlertOwnerTeam = AlertOwnerTeam.CachingTeam
  }

  /**
   * An instance of [[StateMachine]] or [[StateMachineDriver]] threw an exception. This will likely
   * cause it to behave unexpectedly and e.g. miss running some actions. It should be investigated
   * and fixed, and may need some immediate mitigation (e.g. restarting the affected service).
   *
   * @param ownerTeam the team that owns the state machine and should receive the alert.
   */
  case class UNCAUGHT_STATE_MACHINE_ERROR(ownerTeam: AlertOwnerTeam) extends CachingErrorCode {
    override val alertOwnerTeam: AlertOwnerTeam = ownerTeam
    // Override toString to return a clean name without the ownerTeam parameter, since the
    // error code string is used as an alert label and a parameterized name (e.g.
    // "UNCAUGHT_STATE_MACHINE_ERROR(platform-team)") would break alert queries.
    override def toString: String = "UNCAUGHT_STATE_MACHINE_ERROR"
  }

  /**
   * An uncaught exception was thrown in a [[SequentialExecutionContextPool]]. Check the logs for
   * more details of the exception and whether any mitigation is required.
   *
   * @param ownerTeam the team that owns the pool and should receive the alert.
   */
  case class UNCAUGHT_SEC_POOL_ERROR(ownerTeam: AlertOwnerTeam) extends CachingErrorCode {
    override val alertOwnerTeam: AlertOwnerTeam = ownerTeam
    // Override toString to return a clean name without the ownerTeam parameter, since the
    // error code string is used as an alert label and a parameterized name (e.g.
    // "UNCAUGHT_SEC_POOL_ERROR(platform-team)") would break alert queries.
    override def toString: String = "UNCAUGHT_SEC_POOL_ERROR"
  }

  /**
   * The dynamic config lacks certain namespaces found in the Softstore static config. This
   * typically suggests either one of the following:
   * 1. The dynamic config is stale and missing newly added namespaces in the static config.
   *    The oncall should run the config tool to refresh the dynamic config.
   * 2. SAFE is malfunctioning and returning the defaults (empty map) or throwing exceptions.
   *    There is no action needed from the oncall, as storelet will fallback to static config
   *    until SAFE is back to normal.
   */
  case object MISSING_NAMESPACES_IN_DYNAMIC_CONFIG extends CachingErrorCode {
    override val alertOwnerTeam: AlertOwnerTeam = AlertOwnerTeam.CachingTeam
  }

  /**
   * The dynamic config lacks certain targets found in the Dicer static config. See the comment for
   * `MISSING_NAMESPACES_IN_DYNAMIC_CONFIG` for potential causes and actions.
   */
  case object MISSING_TARGETS_IN_DYNAMIC_CONFIG extends CachingErrorCode {
    override val alertOwnerTeam: AlertOwnerTeam = AlertOwnerTeam.CachingTeam
  }

  /**
   * In the context of state transfer, the state provider is requested to start providing
   * application state before it has received the requested Slice. This is a violation of our
   * invariants and indicates a code bug to be investigated.
   */
  case object STATE_PROVIDER_NOT_READY extends CachingErrorCode {
    override val alertOwnerTeam: AlertOwnerTeam = AlertOwnerTeam.CachingTeam
  }

  /**
   * An unexpected assignment with multiple replicas is flowing in the Dicer system, and the system
   * doesn't know how to handle it and will de-replicate it to a single-replica assignment by brute
   * force.
   */
  case object UNEXPECTED_MULTI_REPLICA_ASSIGNMENT extends CachingErrorCode {
    override val alertOwnerTeam: AlertOwnerTeam = AlertOwnerTeam.CachingTeam
  }

  /**
   * The method to set the maximum per-map bytes was called on a
   * [[com.databricks.caching.core.CaffeineCache]] which does not support this operation as it
   * does not support per-map eviction. This indicates a misconfiguration where eviction isolation
   * is expected but is not being used.
   */
  case object CAFFEINE_CACHE_SET_MAXIMUM_PER_MAP_BYTES_CALLED extends CachingErrorCode {
    override val alertOwnerTeam: AlertOwnerTeam = AlertOwnerTeam.CachingTeam
  }

  /**
   * Indicates that an entry was removed from the Caffeine cache due to an unexpected cause
   * (i.e. COLLECTED or EXPIRED). This is unexpected and should be investigated.
   */
  case object CAFFEINE_UNEXPECTED_REMOVAL_CAUSE extends CachingErrorCode {
    override val alertOwnerTeam: AlertOwnerTeam = AlertOwnerTeam.CachingTeam
  }

  /**
   * Indicates that CoreCache metrics are inconsistent with the cache state. For example, an
   * attempt was made to unobserve an entry size for a map that has no recorded estimated total
   * bytes. This is an invariant violation and should be investigated.
   */
  case object CORE_CACHE_METRICS_INCONSISTENT_WITH_CACHE_STATE extends CachingErrorCode {
    override val alertOwnerTeam: AlertOwnerTeam = AlertOwnerTeam.CachingTeam
  }

  /**
   * Indicates that [[SubscriberHandler]] received an assignment that is older than its cached
   * assignment. The subscriber handler will continue using the newer cached assignment, but this
   * indicates an issue with assignment distribution, likely a bug in the [[AssignmentGenerator]].
   */
  case object SUBSCRIBER_HANDLER_ASSIGNMENT_CACHE_AHEAD_OF_SOURCE extends CachingErrorCode {
    override val alertOwnerTeam: AlertOwnerTeam = AlertOwnerTeam.CachingTeam
  }

  /**
   * After the assigner Algorithm merges a Slice, the per-replica load on the Slice becomes too
   * hot (exceeding replica threshold). This is unexpected and should be investigated, see
   * [[com.databricks.dicer.assigner.Algorithm.Merger]] where this error is logged for why.
   */
  case object ASSIGNER_SLICE_AFTER_MERGE_TOO_HOT extends CachingErrorCode {
    override val alertOwnerTeam: AlertOwnerTeam = AlertOwnerTeam.CachingTeam
  }

  /**
   * Indicates an internal invariant violation in
   * [[com.databricks.dicer.client.AssignmentSyncStateMachine.ReadScheduler]]. This may cause watch
   * requests to be sent more or less frequently than expected.
   */
  case object DICER_CLIENT_READ_SCHEDULER_ERROR extends CachingErrorCode {
    // $COVERAGE-OFF$: This alert is used to indicate potential future invariant violation in
    // `AssignmentSyncStateMachine.ReadScheduler`, and it cannot be triggered by the current code in
    // tests.
    override val alertOwnerTeam: AlertOwnerTeam = AlertOwnerTeam.CachingTeam
    // $COVERAGE-ON$
  }

}

/**
 * Teams responsible for handling errors from client services. These values must match the
 * team names used to generate alerts.
 */
sealed trait AlertOwnerTeam

object AlertOwnerTeam {
  case object CachingTeam extends AlertOwnerTeam {
    override def toString: String = "platform-team"
  }

  /**
   * An [[AlertOwnerTeam]] for teams not represented by a named case object.
   * Pass the team's alert routing name directly (e.g. "eng-my-team").
   */
  case class Custom(teamName: String) extends AlertOwnerTeam {
    override def toString: String = teamName
  }

  /**
   * Returns the [[AlertOwnerTeam]] for the given team name. Returns the named case object
   * if the name matches a known team (e.g. [[CachingTeam]]), otherwise returns [[Custom]].
   */
  def createFromString(teamName: String): AlertOwnerTeam = teamName match {
    case t if t == CachingTeam.toString => CachingTeam
    case _ => Custom(teamName)
  }
}

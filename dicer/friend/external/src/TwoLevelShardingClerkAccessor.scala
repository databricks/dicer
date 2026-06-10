package com.databricks.dicer.friend.external

import com.databricks.dicer.external.{Clerk, SliceKey}

/**
 * Provides friend access to Dicer two-level sharding [[Clerk]] routing. Usage of this API is
 * currently restricted. Please reach out to the maintainers with any questions.
 *
 * Two-level sharding uses two coordinated [[SliceKey]]s: a primary key that selects a set of
 * pods and a secondary key that selects a subset among them, providing affinity for requests
 * to the same `(primaryKey, secondaryKey)` pair under the same assignment.
 *
 * This object lives in its own file (and Bazel target) separate from [[TwoLevelShardingHeaders]]
 * because [[TwoLevelShardingHeaders]] is built for the Spark cross-trees while [[Clerk]] is not.
 * See the BUILD comment on `:two_level_sharding_clerk_accessor` for details.
 */
object TwoLevelShardingClerkAccessor {

  /**
   * For the given (`primaryKey`, `secondaryKey`) pair, returns the stub for the resource
   * affinitized to that key pair under the latest assignment.
   *
   * No resources will be known until the `clerk` has connected to Dicer and has received an
   * initial assignment. You can determine when the `clerk` has received an initial assignment by
   * checking the value of the [[Clerk.ready]] future.
   */
  def getStubForKey[Stub <: AnyRef](
      clerk: Clerk[Stub],
      primaryKey: SliceKey,
      secondaryKey: SliceKey): Option[Stub] =
    clerk.impl.getStubForKey(primaryKey, secondaryKey)
}

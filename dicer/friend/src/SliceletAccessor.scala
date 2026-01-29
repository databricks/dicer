package com.databricks.dicer.friend

import java.net.URI

import com.databricks.dicer.external.{ResourceAddress, Slicelet, Target}

/**
 * This object exposes additional methods on a [[Slicelet]] for Caching team use (i.e. Softstore),
 * with access restricted using Bazel visibility.
 *
 * These additional methods are currently to enable state transfer functionality.
 */
object SliceletAccessor {

  /**
   * Returns a list of all the ordered, disjoint Slices that are assigned to this Slicelet, along
   * with the URIs of their state providers (if available).
   *
   * See remarks on [[SliceKeyHandle.isAssignedContinuously]] for details around what it means for a
   * Slice to be assigned to this server.
   */
  def assignedSlicesWithStateProvider(slicelet: Slicelet): Seq[SliceWithStateProvider] = {
    // Implementation detail: `assignedSlices` currently coalesces adjacent slices, whereas
    // `assignedSlicesMetadata` does not.
    slicelet.impl.assignedSlicesWithStateProvider()
  }

  /** Returns the Dicer target for the Slicelet. */
  def target(slicelet: Slicelet): Target = slicelet.impl.target

  /**
   * Returns a set of all the resource addresses that this Slicelet knows of. This is determined by
   * looking at all of the slices in the current assignment and collecting the unique resources to
   * which they are assigned. Note that this set may not contain all of Slicelets in the application
   * deployment, e.g., if some Slicelets are not assigned any resources, which can happen when
   * a Slicelet is terminating.
   */
  def getKnownResourceAddresses(slicelet: Slicelet): Set[ResourceAddress] =
    slicelet.impl.getKnownResourceAddresses

  /**
   * If [[start]] has been called, returns the resource address for this Slicelet, i.e., the
   * address on which the application is listening.
   */
  @throws[IllegalStateException]("if start() has not been called")
  def resourceAddress(slicelet: Slicelet): ResourceAddress = slicelet.impl.squid.resourceAddress

  /**
   * If [[start]] has been called, returns the Squid for the given Slicelet.
   * @throws IllegalStateException if start() has not been called
   */
  @throws[IllegalStateException]("if start() has not been called")
  def getSquid(slicelet: Slicelet): Squid = slicelet.impl.squid
}

object ResourceAddressAccessor {

  /** Enables constructing [[ResourceAddress]]es from a [[URI]]. */
  def create(uri: URI): ResourceAddress = ResourceAddress(uri)
}

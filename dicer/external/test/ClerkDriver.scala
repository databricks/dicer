package com.databricks.dicer.external

import com.databricks.caching.util.TestUtils
import com.databricks.dicer.common.{
  ClerkGetStubForKeyRequestP,
  ClerkGetStubForKeyResponseP,
  ClerkReadyRequestP,
  ClerkReadyResponseP,
  ClerkSampleStubForKeyRequestP,
  ClerkSampleStubForKeyResponseP,
  CreateClerkRequestP,
  CreateClerkResponseP,
  CreateCrossClusterClerkRequestP,
  CreateCrossClusterClerkResponseP,
  GetClerkDebugNameRequestP,
  GetClerkDebugNameResponseP,
  StopClerkRequestP
}
import com.databricks.dicer.common.TargetHelper.TargetOps
import scala.concurrent.duration.Duration
import scala.concurrent.Future
import scala.collection.mutable

import com.databricks.threading.NamedExecutor
import java.net.URI

import com.databricks.dicer.common.ClerkSampleStubForKeyResponseP.ResultEntry

object ClerkDriver {

  /**
   * Computes the expected target identifier used by a Clerk internally (for sending requests and
   * metrics labeling).
   *
   * This is necessary because the Scala Clerk public API only supports creating a [[Clerk]] with
   * a [[Target]] without a cluster URI. The cluster URI is best-effort picked up from the LOCATION
   * environment variable during the Clerk's creation process to form a fully-qualified target and
   * the fully-qualified target is used for metrics labeling internally.
   *
   * @param target The target used to create the Clerk.
   * @param clusterUriOpt The cluster URI from the location config, if provided.
   */
  def computeExpectedTargetIdentifier(target: Target, clusterUriOpt: Option[URI]): Target = {
    target match {
      case _: AppTarget => target
      case _: KubernetesTarget =>
        clusterUriOpt match {
          case Some(uri) => Target.createKubernetesTarget(uri, target.name)
          case None => target
        }
    }
  }

  /**
   * Compares two [[ResourceAddress]] instances for equality, normalizing trailing slashes.
   *
   * This is necessary because Rust's Uri applies root-path normalization where a trailing slash
   * is added when accessing the root path URI as a string. Whereas, Java's `java.net.URI` preserves
   * the original form without adding the trailing slash. Since `URI.equals()` considers these as
   * different URIs, we normalize by stripping trailing slashes before comparing.
   *
   * TODO(<internal bug>): Normalize URI for ResourceAddress in Scala/Java.
   */
  def resourceAddressEquals(actual: ResourceAddress, expected: ResourceAddress): Boolean = {
    actual.uri.toString.stripSuffix("/") == expected.uri.toString.stripSuffix("/")
  }
}

/**
 * A wrapper around a [[Clerk]] to provide a common interface to both the Scala version (running
 * in the main test process) or the Rust version (running in a subprocess).
 *
 * This allows the same test suite to be run against both implementations.
 */
trait ClerkDriver {

  /** The expected target identifier of the Clerk. */
  def expectedClerkTargetIdentifier: Target

  /** See [[Clerk.ready]]. */
  def ready: Future[Unit]

  /** See [[Clerk.getStubForKey]]. */
  def getStubForKey(key: SliceKey): Option[ResourceAddress]

  /**
   * Samples `getStubForKey` multiple times and returns a map of results.
   *
   * This method exists because the Rust Clerk runs in a subprocess, and each `getStubForKey` call
   * requires an RPC round-trip. For tests that need samples, the per-call RPC adds significant
   * overhead.
   *
   * @param key The slice key to look up.
   * @param sampleCount The number of times to sample.
   * @return A map from resource address to hit count.
   */
  def sampleStubForKey(key: SliceKey, sampleCount: Int): Map[ResourceAddress, Int]

  /** See [[Clerk.forTest.stop]]. */
  def stop(): Unit

  /** Gets the debug name for the Clerk. */
  def getDebugName: String
}

/**
 * The [[ClerkDriver]] that exercises the Scala [[Clerk]] implementation.
 *
 * @param clerk The Scala Clerk instance.
 * @param expectedTargetIdentifier The expected target identifier of the Clerk.
 */
class ScalaClerkDriver private (
    clerk: Clerk[ResourceAddress],
    expectedTargetIdentifier: Target
) extends ClerkDriver {

  override def expectedClerkTargetIdentifier: Target = expectedTargetIdentifier

  override def ready: Future[Unit] = clerk.ready

  override def getStubForKey(key: SliceKey): Option[ResourceAddress] = clerk.getStubForKey(key)

  override def sampleStubForKey(key: SliceKey, sampleCount: Int): Map[ResourceAddress, Int] = {
    val hitCounts = mutable.Map[ResourceAddress, Int]().withDefaultValue(0)
    for (_ <- 0 until sampleCount) {
      clerk
        .getStubForKey(key)
        .map((addr: ResourceAddress) => {
          hitCounts(addr) += 1
        })
    }
    hitCounts.toMap
  }

  override def stop(): Unit = clerk.forTest.stop()

  override def getDebugName: String = clerk.impl.toString
}

object ScalaClerkDriver {

  def create(clerk: Clerk[ResourceAddress], expectedTargetIdentifier: Target): ScalaClerkDriver = {
    new ScalaClerkDriver(clerk, expectedTargetIdentifier)
  }
}


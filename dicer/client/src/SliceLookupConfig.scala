package com.databricks.dicer.client

import java.net.URI
import java.util.{Objects, UUID}

import scala.concurrent.duration._

import com.databricks.caching.util.{KubernetesClusterUri, RegionUri, WhereAmIHelper}
import com.databricks.dicer.common.ClientType
import com.databricks.dicer.common.WatchServerHelper.{WATCH_RPC_TIMEOUT, validateWatchRpcTimeout}
import com.databricks.dicer.external.{AppTarget, Target}
import com.databricks.rpc.tls.TLSOptions

/**
 * REQUIRES: `watchRpcTimeout` is at least 500 milliseconds
 *
 * Configuration for SliceLookup used by Clerk or Slicelet clients.
 *
 * Note: The config is used as a key for indexing into a map. Therefore, it should contain only the
 * information that is needed to uniquely identify the SliceLookup instance.
 *
 * Instances are created through [[SliceLookupConfig.apply]], which populates `clientClusterUriOpt`
 * and `clientRegionUriOpt` from [[WhereAmIHelper]]; the primary constructor is `private` so callers
 * cannot bypass that population.
 *
 * @param clientType Type of the client, either Clerk or Slicelet
 * @param watchAddress URI to which the client connects to watch assignments
 * @param tlsOptionsOpt TLS options for the gRPC connection
 * @param target Resources for which the assignment is being watched
 * @param watchStubCacheTime How long to cache the gRPC stub for the watch RPC
 * @param watchFromDataPlane Whether the client is running in the data plane and watching
 *                           assignments from the Dicer Assigner running in the region's general
 *                           cluster.
 * @param watchRpcTimeout The deadline sent in the client request for each RPC.
 * @param clientIdOpt Unique identifier for this client, if available; typically the Kubernetes
 *                    pod UID. None when the client deployment has not yet been configured with
 *                    a client UUID. TODO(<internal bug>): Make required once rolled out everywhere.
 * @param minRetryDelay Minimum time to retry a failed RPC call for exponential backoff.
 * @param maxRetryDelay Maximum time to retry a failed RPC call for exponential backoff.
 * @param enableRateLimiting Whether rate limiting is enabled for watch RPC calls.
 * @param alternativeTargetOpt See [[ClientRequestP.alternativeTarget]].
 * @param clientClusterUriOpt The Kubernetes cluster URI of the pod running this client, or `None`
 *                            when WhereAmI is unavailable.
 * @param clientRegionUriOpt The region URI of the pod running this client, or `None` when
 *                           WhereAmI is unavailable.
 */
// This is deliberately not a `case class`: the synthetic `copy` of a case class is public even when
// the primary constructor is `private`, which would let any caller derive a config carrying an
// arbitrary `clientClusterUriOpt` / `clientRegionUriOpt` instead of the ones `apply` captures from
// [[WhereAmIHelper]].
class SliceLookupConfig private (
    val clientType: ClientType,
    val watchAddress: URI,
    val tlsOptionsOpt: Option[TLSOptions],
    val target: Target,
    val clientIdOpt: Option[UUID],
    val watchStubCacheTime: FiniteDuration,
    val watchFromDataPlane: Boolean,
    val alternativeTargetOpt: Option[AppTarget],
    val watchRpcTimeout: FiniteDuration,
    val minRetryDelay: FiniteDuration,
    val maxRetryDelay: FiniteDuration,
    val enableRateLimiting: Boolean,
    val clientClusterUriOpt: Option[KubernetesClusterUri],
    val clientRegionUriOpt: Option[RegionUri]) {
  validateWatchRpcTimeout(watchRpcTimeout)

  /** Client name to use for the RPC stub. */
  val clientName: String = s"dicer-$clientType-${target.name}"

  override def equals(obj: Any): Boolean = obj match {
    case that: SliceLookupConfig =>
      clientType == that.clientType &&
      watchAddress == that.watchAddress &&
      tlsOptionsOpt == that.tlsOptionsOpt &&
      target == that.target &&
      clientIdOpt == that.clientIdOpt &&
      watchStubCacheTime == that.watchStubCacheTime &&
      watchFromDataPlane == that.watchFromDataPlane &&
      watchRpcTimeout == that.watchRpcTimeout &&
      minRetryDelay == that.minRetryDelay &&
      maxRetryDelay == that.maxRetryDelay &&
      enableRateLimiting == that.enableRateLimiting &&
      alternativeTargetOpt == that.alternativeTargetOpt &&
      clientClusterUriOpt == that.clientClusterUriOpt &&
      clientRegionUriOpt == that.clientRegionUriOpt
    case _ => false
  }

  override def hashCode(): Int = Objects.hash(
    clientType,
    watchAddress,
    tlsOptionsOpt,
    target,
    clientIdOpt,
    watchStubCacheTime,
    watchFromDataPlane: java.lang.Boolean,
    watchRpcTimeout,
    minRetryDelay,
    maxRetryDelay,
    enableRateLimiting: java.lang.Boolean,
    alternativeTargetOpt,
    clientClusterUriOpt,
    clientRegionUriOpt
  )

  override def toString: String =
    s"SliceLookupConfig(clientType=$clientType, watchAddress=$watchAddress, " +
    s"tlsOptionsOpt=$tlsOptionsOpt, target=$target, clientIdOpt=$clientIdOpt, " +
    s"watchStubCacheTime=$watchStubCacheTime, watchFromDataPlane=$watchFromDataPlane, " +
    s"watchRpcTimeout=$watchRpcTimeout, minRetryDelay=$minRetryDelay, " +
    s"maxRetryDelay=$maxRetryDelay, enableRateLimiting=$enableRateLimiting, " +
    s"alternativeTargetOpt=$alternativeTargetOpt, " +
    s"clientClusterUriOpt=$clientClusterUriOpt, clientRegionUriOpt=$clientRegionUriOpt)"
}

object SliceLookupConfig {

  /**
   * Buffer to add to RPC layer deadlines relative to the explicit deadline in the client request.
   * This buffer offsets network latency and other delays in the RPC client and server layers (e.g.,
   * connection establishment, executor delays, etc.).
   *
   * This should have the same value as DEADLINE_BUFFER in slice_lookup_config.rs.
   */
  private[client] final val DEADLINE_BUFFER: FiniteDuration = 10.seconds

  /**
   * The client may be directed to use specific addresses for its watch, which are stored in a
   * cache. This conf controls how long we keep unaccessed stubs in the cache. We want this value
   * to be longer than the connection idle timeout, which is currently 60 seconds by default (see
   * `.withTimeoutMs` in <internal link>)
   * so that we don't end up creating multiple connections to the same endpoint.
   */
  private[client] final val DEFAULT_WATCH_STUB_CACHE_TIME: FiniteDuration = 5.minutes

  /**
   * Creates a [[SliceLookupConfig]], capturing the current pod's Kubernetes cluster and region URIs
   * from [[WhereAmIHelper]] and validating them against the binary's embedded model. Each resolves
   * to `None` when WhereAmI is unavailable.
   *
   * Validation resolving to `None` is a theoretical case rather than an expected one: the URIs name
   * the cluster and region this pod is itself running in, so the embedded model is essentially
   * guaranteed to include them, as deploying a pod to a cluster the model does not know would be
   * nonsensical. Resolving to `None` rather than throwing keeps that theoretical case from failing
   * config creation.
   */
  def apply(
      clientType: ClientType,
      watchAddress: URI,
      tlsOptionsOpt: Option[TLSOptions],
      target: Target,
      clientIdOpt: Option[UUID],
      watchStubCacheTime: FiniteDuration,
      watchFromDataPlane: Boolean,
      alternativeTargetOpt: Option[AppTarget],
      watchRpcTimeout: FiniteDuration = WATCH_RPC_TIMEOUT,
      minRetryDelay: FiniteDuration = 1.second,
      maxRetryDelay: FiniteDuration = 10.seconds,
      enableRateLimiting: Boolean): SliceLookupConfig = {
    val clientClusterUriOpt: Option[KubernetesClusterUri] =
      WhereAmIHelper.getClusterUri.flatMap { (uri: URI) =>
        KubernetesClusterUri.fromUri(uri.toASCIIString)
      }
    val clientRegionUriOpt: Option[RegionUri] =
      WhereAmIHelper.getRegionUri.flatMap(RegionUri.fromUri)
    new SliceLookupConfig(
      clientType = clientType,
      watchAddress = watchAddress,
      tlsOptionsOpt = tlsOptionsOpt,
      target = target,
      clientIdOpt = clientIdOpt,
      watchStubCacheTime = watchStubCacheTime,
      watchFromDataPlane = watchFromDataPlane,
      alternativeTargetOpt = alternativeTargetOpt,
      watchRpcTimeout = watchRpcTimeout,
      minRetryDelay = minRetryDelay,
      maxRetryDelay = maxRetryDelay,
      enableRateLimiting = enableRateLimiting,
      clientClusterUriOpt = clientClusterUriOpt,
      clientRegionUriOpt = clientRegionUriOpt
    )
  }
}

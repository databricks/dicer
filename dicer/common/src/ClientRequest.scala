package com.databricks.dicer.common

import java.net.URI
import java.time.Instant
import scala.concurrent.duration._
import com.databricks.api.proto.dicer.common.ClientRequestP.SubscriberDataP.{
  ClerkFields,
  SliceletFields
}
import com.databricks.api.proto.dicer.common.ClientRequestP.{
  ClientFeatureSupportP,
  ClerkDataP,
  SliceletDataP,
  SubscriberDataP
}
import com.databricks.api.proto.dicer.common.{
  ClientRequestP,
  ClientResponseP,
  DiffAssignmentP,
  GenerationP,
  RedirectP,
  SyncAssignmentStateP,
  TargetP
}
import com.databricks.caching.util.{
  CachingErrorCode,
  HyperLogLog,
  KubernetesClusterUri,
  PrefixLogger,
  RegionUri,
  Severity
}
import com.google.protobuf.ByteString
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.common.Version.{LATEST_VERSION, UNKNOWN_VERSION}
import com.databricks.dicer.common.WatchServerHelper.validateWatchRpcTimeout
import com.databricks.dicer.external.{AppTarget, KubernetesTarget, Slice, SliceKey, Target}
import com.databricks.dicer.friend.Squid

// This file contains abstractions that wrap proto messages from/to Clerks/Slicelets and the
// Assigner.

/** A trait to allow patten matching for ClerkData or SliceletData. */
sealed trait SubscriberData

/** Extra Clerk data sent in a client request. */
case object ClerkData extends SubscriberData {
  override def toString: String = "Clerk"
}

/**
 * The state reported by a Slicelet in its heartbeat watch request.
 *
 * Derived from [[SliceletDataP.State]] at the proto boundary. [[SliceletDataP.State.UNKNOWN]] is
 * not a valid Slicelet heartbeat state; it is normalized to [[SliceletState.Running]] on ingestion
 * (see [[SliceletState.fromProto]]).
 */
sealed trait SliceletState

object SliceletState {

  private val logger: PrefixLogger = PrefixLogger.create(classOf[SliceletState], "")

  /** The Slicelet is not yet healthy and should not receive slice assignments. */
  case object NotReady extends SliceletState

  /** The Slicelet is healthy and may be assigned slices. */
  case object Running extends SliceletState

  /** The Slicelet is shutting down and should be removed from the assignment. */
  case object Terminating extends SliceletState

  /**
   * Converts a [[SliceletDataP.State]] to a [[SliceletState]].
   *
   * [[SliceletDataP.State.UNKNOWN]] is not a valid Slicelet heartbeat state. It arises when a
   * Slicelet binary is newer than the Assigner and reports a proto state value the Assigner does
   * not recognize (forward compatibility). It is normalized to [[Running]] on ingestion.
   *
   * @param targetForErrorLogging
   *   The target associated with the Slicelet, included in any alert messages to aid diagnostics.
   */
  def fromProto(state: SliceletDataP.State, targetForErrorLogging: Target): SliceletState = {
    state match {
      case SliceletDataP.State.UNKNOWN =>
        // Fire a DEGRADED alert so operators can detect the binary version skew. Rate-limit to
        // avoid flooding logs when a Slicelet keeps sending UNKNOWN state on every heartbeat.
        logger.alert(
          Severity.DEGRADED,
          CachingErrorCode.SLICELET_UNKNOWN_PROTO_STATE,
          s"Received unknown proto state from Slicelet for target $targetForErrorLogging; " +
          "binary version skew suspected. Normalizing to Running.",
          every = 30.seconds
        )
        Running
      case SliceletDataP.State.NOT_READY => NotReady
      case SliceletDataP.State.RUNNING => Running
      case SliceletDataP.State.TERMINATING => Terminating
    }
  }

  /**
   * Converts a [[SliceletState]] back to its [[SliceletDataP.State]] proto representation.
   *
   * Note: there is no [[SliceletState]] value corresponding to [[SliceletDataP.State.UNKNOWN]];
   * that proto value is normalized to [[Running]] on ingestion (see [[fromProto]]).
   */
  def toProto(state: SliceletState): SliceletDataP.State = {
    state match {
      case NotReady => SliceletDataP.State.NOT_READY
      case Running => SliceletDataP.State.RUNNING
      case Terminating => SliceletDataP.State.TERMINATING
    }
  }
}

/** Extra Slicelet data sent in a client request. */
case class SliceletData(
    squid: Squid,
    state: SliceletState,
    kubernetesNamespace: String,
    attributedLoads: Vector[SliceletData.SliceLoad],
    unattributedLoadOpt: Option[SliceletData.SliceLoad],
    keyCardinalityEstimateOpt: Option[HyperLogLog])
    extends SubscriberData {

  override def toString: String = {
    // Rather than displaying detailed load reports, just display total affinitized and
    // unaffinitized load in debug string.
    val affinitizedLoad: Double = attributedLoads.map { load: SliceletData.SliceLoad =>
      load.primaryRateLoad
    }.sum
    val unaffinitizedLoad: Double = unattributedLoadOpt
      .map { load: SliceletData.SliceLoad =>
        load.primaryRateLoad
      }
      .getOrElse(0.0)
    s"[$squid, $state, $kubernetesNamespace, affinitizedLoad=$affinitizedLoad, " +
    s"unaffinitizedLoad=$unaffinitizedLoad]"
  }

  /** Converts to corresponding proto representation. */
  def toProto: SliceletDataP = {
    new SliceletDataP(
      state = Some(SliceletState.toProto(state)),
      squid = Some(squid.toProto),
      attributedLoads = attributedLoads.map { load: SliceletData.SliceLoad =>
        load.toProto
      },
      unattributedLoad = unattributedLoadOpt.map { load: SliceletData.SliceLoad =>
        load.toProto
      },
      kubernetesNamespace = Some(kubernetesNamespace),
      keyCardinalityEstimate = keyCardinalityEstimateOpt.map {
        keyCardinalityEstimate: HyperLogLog =>
          keyCardinalityEstimate.toProto
      }
    )
  }
}
object SliceletData {

  private val logger = PrefixLogger.create(classOf[SliceletData], "")

  /**
   * Parses and validates the given proto representation of [[SliceletData]].
   *
   * @param targetForErrorLogging
   *   The target associated with the Slicelet, included in any alert messages to aid diagnostics.
   */
  def fromProto(proto: SliceletDataP, targetForErrorLogging: Target): SliceletData = {
    val squid = Squid.fromProto(proto.getSquid)
    val attributedLoads: Vector[SliceletData.SliceLoad] =
      proto.attributedLoads.map(SliceletData.SliceLoad.fromProto).toVector
    val unattributedLoadOpt: Option[SliceletData.SliceLoad] =
      proto.unattributedLoad.map(SliceletData.SliceLoad.fromProto)
    // Since this is for telemetry only we'd rather not report it than fail the watch.
    val keyCardinalityEstimate: Option[HyperLogLog] = try {
      proto.keyCardinalityEstimate.map(HyperLogLog.fromProto)
    } catch {
      case e: IllegalArgumentException =>
        logger.alert(
          Severity.DEGRADED,
          CachingErrorCode.DICER_CLIENT_REQUEST_MALFORMED_CARDINALITY_ESTIMATE,
          s"Received malformed key_cardinality_estimate from Slicelet for target " +
          s"$targetForErrorLogging; dropping: $e",
          every = 30.seconds
        )
        None
    }

    SliceletData(
      squid,
      SliceletState.fromProto(proto.getState, targetForErrorLogging),
      proto.getKubernetesNamespace,
      attributedLoads,
      unattributedLoadOpt,
      keyCardinalityEstimate
    )
  }

  /**
   * Aggregate load measurements over some time window and Slice.
   *
   * @param primaryRateLoad The primary rate load (e.g., time-weighted mean of QPS). "Primary" to
   *                        distinguish from the (as yet unsupported) other load measurements.
   *                        "Rate" to distinguish from (as yet unsupported) gauge measurements
   *                        (e.g., memory usage) which must be provided through synthesized
   *                        incremental rate updates (e.g., incrementing memory usage by 1MiB every
   *                        1s to represent a 1MiB memory footprint). This includes the load from
   *                        `topKeys`.
   * @param windowLowInclusive The inclusive start of the time window over which the load
   *                           measurements are aggregated.
   * @param windowHighExclusive The exclusive limit of the time window over which the load
   *                            measurements are aggregated.
   * @param slice The range of keys to which the load measurement applies.
   * @param topKeys Top keys within this Slice that have the highest estimated load.
   * @param numReplicas The number of replicas of `slice` known by the Slicelet when generating this
   *                    SliceLoad.
   * @param loadDistributionOpt Optional approximate distribution (CDF) of load across keys within
   *                            `slice`, present for range-sharded targets that collect it.
   *
   * @throws IllegalArgumentException If `primaryRateLoad` is a negative or infinite value.
   * @throws IllegalArgumentException If any key in `topKeys` is not contained within `slice`.
   * @throws IllegalArgumentException If `numReplicas` <= 0.
   * @throws IllegalArgumentException If any keys in `loadDistributionOpt` are not contained within
   *                                 `slice`.
   */
  case class SliceLoad @throws[IllegalArgumentException]()(
      primaryRateLoad: Double,
      windowLowInclusive: Instant,
      windowHighExclusive: Instant,
      slice: Slice,
      topKeys: Seq[KeyLoad],
      numReplicas: Int,
      loadDistributionOpt: Option[LoadDistribution] = None) {
    LoadMeasurement.requireValidLoadMeasurement(primaryRateLoad)
    require(
      windowHighExclusive.compareTo(windowLowInclusive) >= 0,
      s"High exclusive time must be >= low inclusive time: " +
      s"$windowHighExclusive < $windowLowInclusive."
    )
    for (keyLoad: KeyLoad <- topKeys) {
      val key: SliceKey = keyLoad.key
      require(slice.contains(key), s"Top key $key must be contained in slice $slice.")
    }
    if (numReplicas <= 0) {
      throw new IllegalArgumentException(s"numReplicas must be positive: $numReplicas.")
    }
    for (distribution: LoadDistribution <- loadDistributionOpt) {
      if (distribution.points.nonEmpty) {
        // CDF points in the distribution are strictly ascending by key, so it suffices to check
        // that the first and last keys are within `slice` to ensure all the keys are within it.
        val firstKey: SliceKey = distribution.points.head.key
        val lastKey: SliceKey = distribution.points.last.key
        require(
          slice.contains(firstKey) && slice.contains(lastKey),
          s"Load distribution's lowest key $firstKey and highest key $lastKey must be contained " +
          s"in slice $slice."
        )
      }
    }

    /** Returns non-negative duration of the window for this load measurement. */
    def windowDuration: FiniteDuration = {
      (windowHighExclusive.toEpochMilli - windowLowInclusive.toEpochMilli).millis
    }

    private[common] def toProto: SliceletDataP.SliceLoadP = {
      import SliceHelper.RichSlice
      new SliceletDataP.SliceLoadP(
        primaryRateLoad = Some(primaryRateLoad),
        windowLowInclusiveSeconds = Some(windowLowInclusive.getEpochSecond),
        windowHighExclusiveSeconds = Some(windowHighExclusive.getEpochSecond),
        slice = Some(slice.toProto),
        topKeys = topKeys.map(_.toProto),
        numReplicas = Some(numReplicas),
        loadDistribution = loadDistributionOpt.map((_: LoadDistribution).toProto)
      )
    }
  }

  object SliceLoad {

    /**
     * Converts the given `proto` to a [[SliceLoad]] instance.
     *
     * @throws IllegalArgumentException if the proto is not valid.
     */
    private[common] def fromProto(proto: SliceletDataP.SliceLoadP): SliceLoad = {
      SliceLoad(
        primaryRateLoad = proto.getPrimaryRateLoad,
        windowLowInclusive = Instant.ofEpochSecond(proto.getWindowLowInclusiveSeconds),
        windowHighExclusive = Instant.ofEpochSecond(proto.getWindowHighExclusiveSeconds),
        slice = SliceHelper.fromProto(proto.getSlice),
        topKeys = proto.topKeys.map(KeyLoad.fromProto),
        // For backward compatibility, if the `numReplicas` field is not defined in the SliceLoadP
        // (e.g. when the SliceLoadP is reported by some Slicelets in stale versions), we set the
        // value of this field to 1 by default in the returned SliceLoad scala class, rather than
        // failing the fromProto() method.
        numReplicas = proto.numReplicas.getOrElse(1),
        loadDistributionOpt = proto.loadDistribution.map(LoadDistribution.fromProto)
      )
    }

    object forTest {

      /** Test-only method to convert the given `proto` to a `SliceLoad` instance. */
      def SliceLoadfromProto(proto: SliceletDataP.SliceLoadP): SliceLoad = {
        fromProto(proto)
      }
    }
  }

  /**
   * REQUIRES: `underestimatedPrimaryRateLoad` is a non-negative, finite value.
   *
   * Load measurement for a single key.
   *
   * @param key The key for which this load measurement applies.
   * @param underestimatedPrimaryRateLoad Estimated primary rate load for this particular key. Note
   *                                      this should be an underestimate, i.e. the real
   *                                      time-weighted load is greater than or equal to this value.
   */
  case class KeyLoad(key: SliceKey, underestimatedPrimaryRateLoad: Double) {
    LoadMeasurement.requireValidLoadMeasurement(underestimatedPrimaryRateLoad)

    private[common] def toProto: SliceletDataP.KeyLoadP = {
      new SliceletDataP.KeyLoadP(
        sliceKey = Some(key.bytes),
        underestimatedPrimaryRateLoad = Some(underestimatedPrimaryRateLoad)
      )
    }
  }

  object KeyLoad {

    /**
     * Converts the given `proto` to a [[KeyLoad]] instance.
     *
     * @throws IllegalArgumentException if the proto is not valid.
     */
    @throws[IllegalArgumentException]
    private[common] def fromProto(proto: SliceletDataP.KeyLoadP): KeyLoad = {
      require(proto.sliceKey.isDefined, "KeyLoadP must have a slice key")
      KeyLoad(
        key = SliceKey.fromRawBytes(proto.getSliceKey),
        underestimatedPrimaryRateLoad = proto.getUnderestimatedPrimaryRateLoad
      )
    }
  }

  /**
   * Approximate distribution of load across keys within a Slice.
   *
   * @param points           Samples of the load CDF, ordered by `key` ascending. Empty when no
   *                         distribution has been reported for the Slice; consumers may assume that
   *                         the load is evenly distributed across the Slice.
   * @param maxErrorFraction The maximum error in any point's `cumulativeLoadFraction`, as a
   *                         fraction of the Slice's total load.
   *
   * @throws IllegalArgumentException If `maxErrorFraction` is not in the half-open interval
   *                                  [0.0, 1.0).
   * @throws IllegalArgumentException If `points` are not strictly ascending by `key` (i.e. not
   *                                  ordered, or with duplicate keys).
   * @throws IllegalArgumentException If any point's `cumulativeLoadFraction` is less than the
   *                                  preceding point's (i.e. not non-decreasing).
   */
  case class LoadDistribution @throws[IllegalArgumentException]()(
      points: Seq[LoadDistribution.CdfPoint],
      maxErrorFraction: Double) {
    require(
      maxErrorFraction >= 0.0 && maxErrorFraction < 1.0,
      s"maxErrorFraction must be in [0, 1): $maxErrorFraction"
    )
    for (pair <- points.zip(points.drop(1))) {
      val (prev, next): (LoadDistribution.CdfPoint, LoadDistribution.CdfPoint) = pair
      require(
        prev.key.compare(next.key) < 0,
        "points must be strictly ascending by key (ordered, with unique keys)"
      )
      require(
        prev.cumulativeLoadFraction <= next.cumulativeLoadFraction,
        "cumulativeLoadFraction must be non-decreasing across points"
      )
    }

    /** Converts to corresponding proto representation. */
    def toProto: SliceletDataP.LoadDistributionP = {
      new SliceletDataP.LoadDistributionP(
        points = points.map((point: LoadDistribution.CdfPoint) => point.toProto),
        maxErrorFraction = Some(maxErrorFraction)
      )
    }
  }

  object LoadDistribution {

    /** Converts the given `proto` to a [[LoadDistribution]] instance. */
    @throws[IllegalArgumentException]("if the proto is not valid")
    def fromProto(proto: SliceletDataP.LoadDistributionP): LoadDistribution = {
      require(
        proto.maxErrorFraction.isDefined,
        "LoadDistributionP must have a max_error_fraction"
      )
      LoadDistribution(
        points = proto.points.map(CdfPoint.fromProto).toSeq,
        maxErrorFraction = proto.getMaxErrorFraction
      )
    }

    /**
     * A single point on the load CDF: `cumulativeLoadFraction` (the quantile) is the fraction of
     * the Slice's total load attributed to keys at or below `key`.
     *
     * @throws IllegalArgumentException If `cumulativeLoadFraction` is not in the closed interval
     *                                  [0.0, 1.0].
     */
    case class CdfPoint @throws[IllegalArgumentException]()(
        key: SliceKey,
        cumulativeLoadFraction: Double) {
      require(
        cumulativeLoadFraction >= 0.0 && cumulativeLoadFraction <= 1.0,
        s"cumulativeLoadFraction must be in [0, 1]: $cumulativeLoadFraction"
      )

      /** Converts to corresponding proto representation. */
      def toProto: SliceletDataP.LoadDistributionP.CdfPointP = {
        new SliceletDataP.LoadDistributionP.CdfPointP(
          key = Some(key.bytes),
          cumulativeLoadFraction = Some(cumulativeLoadFraction)
        )
      }
    }

    object CdfPoint {

      /** Converts the given `proto` to a [[CdfPoint]] instance. */
      @throws[IllegalArgumentException]("if the proto is not valid")
      def fromProto(proto: SliceletDataP.LoadDistributionP.CdfPointP): CdfPoint = {
        require(proto.key.isDefined, "CdfPointP must have a key")
        require(
          proto.cumulativeLoadFraction.isDefined,
          "CdfPointP must have a cumulative_load_fraction"
        )
        CdfPoint(
          key = SliceKey.fromRawBytes(proto.getKey),
          cumulativeLoadFraction = proto.getCumulativeLoadFraction
        )
      }
    }
  }
}

/** A class that encapsulates [[SyncAssignmentStateP]]. */
sealed trait SyncAssignmentState {

  def getKnownGeneration: Generation = {
    this match {
      case SyncAssignmentState.KnownGeneration(generation) => generation
      case SyncAssignmentState.KnownAssignment(assignment) => assignment.generation
    }
  }

  def toProto: SyncAssignmentStateP = {
    this match {
      case SyncAssignmentState.KnownGeneration(generation) =>
        new SyncAssignmentStateP(
          state = SyncAssignmentStateP.State.KnownGeneration(generation.toProto)
        )
      case SyncAssignmentState.KnownAssignment(diffAssignment: DiffAssignment) =>
        new SyncAssignmentStateP(
          state = SyncAssignmentStateP.State.KnownAssignment(diffAssignment.toProto)
        )
    }
  }
}

object SyncAssignmentState {

  /**
   * Generation of the latest assignment known to the sender. This case is used when the sender
   * believes the remote server knows of an assignment with a higher generation or may learn of one
   * before the sender.
   */
  case class KnownGeneration(generation: Generation) extends SyncAssignmentState

  /**
   * The latest assignment known to the sender. This case is used when the sender believes the
   * remote server has an assignment with a generation that is less than `assignment.generation` or
   * no assignment.
   */
  case class KnownAssignment(diffAssignment: DiffAssignment) extends SyncAssignmentState
  object KnownAssignment {

    /** Creates sync state with full assignment (no diff). */
    def apply(assignment: Assignment): KnownAssignment = {
      KnownAssignment(assignment.toDiff(Generation.EMPTY))
    }

    /**
     * Returns both the structured ([[SyncAssignmentStateP.State.KnownAssignment]]) and serialized
     * ([[SyncAssignmentStateP.State.KnownSerializedAssignment]]) [[SyncAssignmentStateP]] proto
     * representations of the given [[DiffAssignment]], so that they can be cached and reused for
     * multiple clients.
     */
    def toCachedProtos(
        diffAssignment: DiffAssignment
    ): (SyncAssignmentStateP, SyncAssignmentStateP) = {
      val diffProto: DiffAssignmentP = diffAssignment.toProto
      val structured = new SyncAssignmentStateP(
        state = SyncAssignmentStateP.State.KnownAssignment(diffProto)
      )
      val serialized = new SyncAssignmentStateP(
        state = SyncAssignmentStateP.State.KnownSerializedAssignment(diffProto.toByteString)
      )
      (structured, serialized)
    }
  }

  /** Creates sync state with full assignment (no diff). */
  def apply(assignment: Assignment): SyncAssignmentState = KnownAssignment(assignment)

  def fromProto(proto: SyncAssignmentStateP): SyncAssignmentState = {
    proto.state match {
      case SyncAssignmentStateP.State.KnownSerializedAssignment(serializedAssignment: ByteString) =>
        val diffAssignmentProto: DiffAssignmentP =
          DiffAssignmentP.parseFrom(serializedAssignment.toByteArray)
        KnownAssignment(DiffAssignment.fromProto(diffAssignmentProto))
      case SyncAssignmentStateP.State.KnownAssignment(diffAssignmentProto: DiffAssignmentP) =>
        KnownAssignment(DiffAssignment.fromProto(diffAssignmentProto))
      case SyncAssignmentStateP.State.KnownGeneration(generationProto: GenerationP) =>
        KnownGeneration(Generation.fromProto(generationProto))
      case SyncAssignmentStateP.State.Empty =>
        throw new IllegalArgumentException("SyncAssignmentStateP state must not be empty.")
    }
  }
}

/**
 * Encapsulates and validates [[RedirectP]].
 *
 * @param addressOpt URI to which the client should send future requests. If `None`, the client
 *                   falls back to its default server-selection behavior.
 * @param redirectTokenOpt Optional opaque token produced by the server alongside `addressOpt`. The
 *                         client echoes this back in the next [[ClientRequest]] sent to
 *                         `addressOpt`.
 *
 * @throws IllegalArgumentException If `addressOpt` is not None but contains an empty URI.
 * @throws IllegalArgumentException If `redirectTokenOpt` is set but `addressOpt` is not.
 *
 * TODO(<internal bug>): rename this to `RoutingHint`.
 */
case class Redirect @throws[IllegalArgumentException]() private (
    addressOpt: Option[URI],
    redirectTokenOpt: Option[ByteString]) {
  if (addressOpt.isDefined) {
    require(addressOpt.get.toString.nonEmpty, "Redirect address must not be empty")
  } else {
    require(
      redirectTokenOpt.isEmpty,
      "Redirect token must not be set without a redirect address"
    )
  }

  def toProto: RedirectP = {
    new RedirectP(
      address = addressOpt.map(_.toString),
      redirectToken = redirectTokenOpt
    )
  }
}

object Redirect {

  /** The empty redirect, which causes the sender to send to a random address. */
  val EMPTY: Redirect = Redirect(addressOpt = None, redirectTokenOpt = None)

  /**
   * Create [[Redirect]] from `proto` if it is valid.
   *
   * @throws IllegalArgumentException if `proto` is invalid.
   */
  @throws[IllegalArgumentException]
  def fromProto(proto: RedirectP): Redirect = {
    val addressOpt: Option[URI] = if (proto.getAddress.isEmpty) {
      None
    } else {
      Some(new URI(proto.getAddress))
    }
    Redirect(addressOpt, proto.redirectToken)
  }
}

/**
 * A class that encapsulates [[ClientRequestP]] and validates that proto.
 *
 * @param supportsSerializedAssignment indicates whether the client supports parsing serialized
 *                                     assignments. If true, the server may return a serialized
 *                                     assignment in response to this watch request.
 * @param redirectTokenOpt Opaque token echoed back from the most recent
 *                         [[Redirect.redirectTokenOpt]] the client received. `None` when the client
 *                         is not currently acting on a redirect. The client does not inspect the
 *                         bytes — the server on the redirected address is responsible for decoding.
 * @param alternativeTargetOpt See [[ClientRequestP.alternativeTarget]].
 * @param clusterUriOpt Sender pod's Kubernetes cluster IDM URI, if available from WhereAmI;
 *                      region consistency with `regionUriOpt` is not enforced.
 * @param regionUriOpt Sender pod's region IDM URI, if available from WhereAmI.
 *
 * @throws IllegalArgumentException If `timeout` is not positive.
 * @throws IllegalArgumentException If `subscriberDebugName` is empty.
 */
case class ClientRequest @throws[IllegalArgumentException]("if an argument is invalid")(
    target: Target,
    syncAssignmentState: SyncAssignmentState,
    subscriberDebugName: String,
    timeout: FiniteDuration,
    subscriberData: SubscriberData,
    supportsSerializedAssignment: Boolean,
    redirectTokenOpt: Option[ByteString],
    version: Long = LATEST_VERSION,
    alternativeTargetOpt: Option[AppTarget],
    // TODO(<internal bug>): Remove once DBNS replaces the Kubernetes API server termination-signal watch
    // and once clusterUriOpt is no longer used to determine the cluster type of the sender for the
    // process of rejecting new Serverless Platform customers that use KubernetesTarget.
    clusterUriOpt: Option[KubernetesClusterUri],
    regionUriOpt: Option[RegionUri]) {
  require(timeout.toMillis > 0, s"Positive timeout value needed: $timeout.")
  require(subscriberDebugName.nonEmpty, "Subscriber debug name must not be empty.")

  def toProto: ClientRequestP = {
    val subData: SubscriberDataP =
      subscriberData match {
        case ClerkData =>
          ClientRequestP.SubscriberDataP.ClerkFields(new ClerkDataP)
        case sliceletData: SliceletData =>
          ClientRequestP.SubscriberDataP.SliceletFields(sliceletData.toProto)
      }
    ClientRequestP(
      target = Some(target.toProto),
      syncAssignmentState = Some(syncAssignmentState.toProto),
      subscriberDebugName = Some(subscriberDebugName),
      chosenRpcTimeoutMillis = Some(timeout.toMillis),
      version = Some(version),
      subscriberDataP = subData,
      clientFeatureSupport = Some(
        ClientFeatureSupportP(supportsSerializedAssignment = Some(supportsSerializedAssignment))
      ),
      redirectToken = redirectTokenOpt,
      alternativeTarget = alternativeTargetOpt.map((_: AppTarget).toProto),
      clusterUri = clusterUriOpt.map((_: KubernetesClusterUri).uri),
      regionUri = regionUriOpt.map((_: RegionUri).uri)
    )
  }

  // Various accessors.
  def getKnownGeneration: Generation = syncAssignmentState.getKnownGeneration

  /** Returns the client type. */
  def getClientType: ClientType = {
    subscriberData match {
      case ClerkData => ClientType.Clerk
      case _: SliceletData => ClientType.Slicelet
    }
  }
}

object ClientRequest {

  private val logger: PrefixLogger = PrefixLogger.create(classOf[ClientRequest], "")

  /**
   * Create [[ClientRequest]] from `proto` if it is valid.
   *
   * @throws IllegalArgumentException if `proto` is invalid, including if `alternativeTarget` is set
   *                                  but is not a valid `AppTarget`.
   */
  def fromProto(targetUnmarshaller: TargetUnmarshaller, proto: ClientRequestP): ClientRequest = {
    // Parse Target first so it can be included in any error alerts emitted during SliceletData
    // parsing (e.g., when a Slicelet reports an UNKNOWN proto state due to version skew).
    val target: Target = targetUnmarshaller.fromProto(
      proto.target.getOrElse(
        throw new IllegalArgumentException("Target must be defined in ClientRequestP")
      )
    )

    // Create the subscriber data depending on whether it is a Clerk or a Slicelet request.
    val subscriberData: SubscriberData =
      proto.subscriberDataP match {
        case ClerkFields(_) => ClerkData
        case SliceletFields(sliceletDataProto: SliceletDataP) =>
          SliceletData.fromProto(sliceletDataProto, target)
        case _ =>
          throw new IllegalArgumentException(
            s"One of ClerkDataP or SliceletDataP must be defined: $proto"
          )
      }

    val chosenRpcTimeout: FiniteDuration = try {
      proto.getChosenRpcTimeoutMillis.milliseconds
    } catch {
      case e: IllegalArgumentException =>
        throw new IllegalArgumentException(s"Exceeds maximum supported duration", e)
    }

    val clientFeatureSupport: ClientFeatureSupportP = proto.getClientFeatureSupport
    val supportsSerializedAssignment: Boolean = clientFeatureSupport.getSupportsSerializedAssignment

    val alternativeTargetOpt: Option[AppTarget] = proto.alternativeTarget.map {
      alternativeTargetP: TargetP =>
        val alternativeTarget: Target = try {
          targetUnmarshaller.fromProto(alternativeTargetP)
        } catch {
          case e: IllegalArgumentException =>
            throw new IllegalArgumentException(
              s"alternativeTarget is ill-formed: ${e.getMessage}",
              e
            )
        }
        alternativeTarget match {
          case appTarget: AppTarget => appTarget
          case _: KubernetesTarget =>
            throw new IllegalArgumentException(
              s"alternativeTarget must be an AppTarget; got a KubernetesTarget: $alternativeTarget"
            )
        }
    }

    // A URI that is malformed or names a resource absent from IDM is dropped to None rather than
    // rejecting the request: these fields are advisory sender metadata, so an unrecognized value
    // should not fail an otherwise-valid watch.
    val clusterUriOpt: Option[KubernetesClusterUri] =
      proto.clusterUri.flatMap { uri: String =>
        val parsedUriOpt: Option[KubernetesClusterUri] = KubernetesClusterUri.fromUri(uri)
        if (parsedUriOpt.isEmpty) {
          logger.warn(
            s"Unrecognized clusterUri $uri in request for $target from subscriber " +
            s"${proto.getSubscriberDebugName}."
          )
        }
        parsedUriOpt
      }
    val regionUriOpt: Option[RegionUri] =
      proto.regionUri.flatMap { uri: String =>
        val parsedUriOpt: Option[RegionUri] = RegionUri.fromUri(uri)
        if (parsedUriOpt.isEmpty) {
          logger.warn(
            s"Unrecognized regionUri $uri in request for $target from subscriber " +
            s"${proto.getSubscriberDebugName}."
          )
        }
        parsedUriOpt
      }

    new ClientRequest(
      target,
      SyncAssignmentState.fromProto(proto.getSyncAssignmentState),
      proto.getSubscriberDebugName,
      chosenRpcTimeout,
      subscriberData,
      supportsSerializedAssignment,
      proto.redirectToken,
      version = proto.version.getOrElse(UNKNOWN_VERSION),
      alternativeTargetOpt = alternativeTargetOpt,
      clusterUriOpt = clusterUriOpt,
      regionUriOpt = regionUriOpt
    )
  }
}

/** The class corresponding to [[ClientResponseP]]. */
case class ClientResponse(
    syncState: SyncAssignmentState,
    suggestedRpcTimeout: FiniteDuration,
    redirect: Redirect) {
  validateWatchRpcTimeout(suggestedRpcTimeout)

  def toProto: ClientResponseP = {
    new ClientResponseP(
      syncAssignmentState = Some(syncState.toProto),
      suggestedRpcTimeoutMillis = Some(suggestedRpcTimeout.toMillis),
      redirect = Some(redirect.toProto)
    )
  }
}

object ClientResponse {

  /**
   * Parses the given client response proto.
   *
   * @throws IllegalArgumentException if the response is invalid.
   */
  @throws[IllegalArgumentException]
  def fromProto(proto: ClientResponseP): ClientResponse = {
    val rpcTimeout: FiniteDuration = try {
      proto.getSuggestedRpcTimeoutMillis.milliseconds
    } catch {
      case e: IllegalArgumentException =>
        throw new IllegalArgumentException(s"Exceeds maximum supported duration", e)
    }

    ClientResponse(
      SyncAssignmentState.fromProto(proto.getSyncAssignmentState),
      rpcTimeout,
      Redirect.fromProto(proto.getRedirect)
    )
  }

  /** Creates a [[ClientResponseP]] using the given [[SyncAssignmentStateP]]. */
  def createProtoWithSyncStateP(
      syncStateP: SyncAssignmentStateP,
      suggestedRpcTimeout: FiniteDuration,
      redirect: Redirect): ClientResponseP = {
    new ClientResponseP(
      syncAssignmentState = Some(syncStateP),
      suggestedRpcTimeoutMillis = Some(suggestedRpcTimeout.toMillis),
      redirect = Some(redirect.toProto)
    )
  }
}

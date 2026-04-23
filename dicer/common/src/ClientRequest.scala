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
  SyncAssignmentStateP
}
import com.databricks.caching.util.{CachingErrorCode, PrefixLogger, Severity}
import com.google.protobuf.ByteString
import com.databricks.dicer.common.TargetHelper.TargetOps
import com.databricks.dicer.common.Version.{LATEST_VERSION, UNKNOWN_VERSION}
import com.databricks.dicer.common.WatchServerHelper.validateWatchRpcTimeout
import com.databricks.dicer.external.{Slice, SliceKey, Target}
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
    unattributedLoadOpt: Option[SliceletData.SliceLoad])
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
      kubernetesNamespace = Some(kubernetesNamespace)
    )
  }
}
object SliceletData {

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
    SliceletData(
      squid,
      SliceletState.fromProto(proto.getState, targetForErrorLogging),
      proto.getKubernetesNamespace,
      attributedLoads,
      unattributedLoadOpt
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
   *
   * @throws IllegalArgumentException If `primaryRateLoad` is a negative or infinite value.
   * @throws IllegalArgumentException If any key in `topKeys` is not contained within `slice`.
   * @throws IllegalArgumentException If `numReplicas` <= 0.
   */
  case class SliceLoad @throws[IllegalArgumentException]()(
      primaryRateLoad: Double,
      windowLowInclusive: Instant,
      windowHighExclusive: Instant,
      slice: Slice,
      topKeys: Seq[KeyLoad],
      numReplicas: Int) {
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

    /** Returns non-negative duration of the window for this load measurement. */
    def windowDuration: FiniteDuration = {
      (windowHighExclusive.toEpochMilli - windowLowInclusive.toEpochMilli).millis
    }

    def toProto: SliceletDataP.SliceLoadP = {
      import SliceHelper.RichSlice
      new SliceletDataP.SliceLoadP(
        primaryRateLoad = Some(primaryRateLoad),
        windowLowInclusiveSeconds = Some(windowLowInclusive.getEpochSecond),
        windowHighExclusiveSeconds = Some(windowHighExclusive.getEpochSecond),
        slice = Some(slice.toProto),
        topKeys = topKeys.map(_.toProto),
        numReplicas = Some(numReplicas)
      )
    }
  }

  object SliceLoad {

    /**
     * Converts the given `proto` to a [[SliceLoad]] instance.
     *
     * @throws IllegalArgumentException if the proto is not valid.
     */
    def fromProto(proto: SliceletDataP.SliceLoadP): SliceLoad = {
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
        numReplicas = proto.numReplicas.getOrElse(1)
      )
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

    def toProto: SliceletDataP.KeyLoadP = {
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
    def fromProto(proto: SliceletDataP.KeyLoadP): KeyLoad = {
      require(proto.sliceKey.isDefined, "KeyLoadP must have a slice key")
      KeyLoad(
        key = SliceKey.fromRawBytes(proto.getSliceKey),
        underestimatedPrimaryRateLoad = proto.getUnderestimatedPrimaryRateLoad
      )
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
 * REQUIRES: If `addressOpt` is not None, it must contain a non-empty URI.
 *
 * Encapsulates and validates [[RedirectP]].
 *
 * TODO(<internal bug>): rename this to `RoutingHint`.
 */
case class Redirect private (addressOpt: Option[URI]) {
  if (addressOpt.isDefined) {
    require(addressOpt.get.toString.nonEmpty, "Redirect address must not be empty")
  }

  def toProto: RedirectP = {
    new RedirectP(
      address = addressOpt.map(_.toString)
    )
  }
}

object Redirect {

  /** The empty redirect, which causes the sender to send to a random address. */
  val EMPTY: Redirect = Redirect(None)

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
    Redirect(addressOpt)
  }
}

/**
 * A class that encapsulates [[ClientRequestP]] and validates that proto.
 *
 * @param supportsSerializedAssignment indicates whether the client supports parsing serialized
 *                                     assignments. If true, the server may return a serialized
 *                                     assignment in response to this watch request.
 */
case class ClientRequest(
    target: Target,
    syncAssignmentState: SyncAssignmentState,
    subscriberDebugName: String,
    timeout: FiniteDuration,
    subscriberData: SubscriberData,
    supportsSerializedAssignment: Boolean,
    version: Long = LATEST_VERSION) {
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
      )
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

  /**
   * Create [[ClientRequest]] from `proto` if it is valid.
   *
   * @throws IllegalArgumentException if `proto` is invalid.
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
    new ClientRequest(
      target,
      SyncAssignmentState.fromProto(proto.getSyncAssignmentState),
      proto.getSubscriberDebugName,
      chosenRpcTimeout,
      subscriberData,
      supportsSerializedAssignment,
      proto.version.getOrElse(UNKNOWN_VERSION)
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

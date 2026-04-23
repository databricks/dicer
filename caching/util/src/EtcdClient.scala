package com.databricks.caching.util

import java.util.concurrent.CompletableFuture
import scala.collection.JavaConverters._
import scala.collection.{Seq, mutable}
import scala.compat.java8.FutureConverters._
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Try
import io.grpc.{Status, StatusException}
import io.etcd.jetcd
import io.etcd.jetcd.Watch.Listener
import io.etcd.jetcd.kv.{GetResponse, TxnResponse}
import io.etcd.jetcd.op.{Cmp, CmpTarget, Op}
import io.etcd.jetcd.options.{DeleteOption, GetOption, PutOption, WatchOption}
import io.etcd.jetcd.resolver.IPResolverProvider
import io.etcd.jetcd.{ByteSequence, KeyValue, Watch}
import io.grpc.NameResolverRegistry
import com.databricks.caching.util.AssertMacros.{iassert, ifail}
import com.databricks.caching.util.Pipeline.InlinePipelineExecutor
import com.google.protobuf.ByteString
import com.databricks.caching.util.EtcdClient.WriteResponse.KeyState
import com.databricks.caching.util.EtcdClient.{
  Config,
  GlobalLogEntry,
  JetcdWrapper,
  NewKeyVersionTxnBuilder,
  ReadResponse,
  ReadResumeToken,
  ScopedKey,
  Txn,
  VERSION_HIGH_WATERMARK_BLOCK_INCREMENT,
  Version,
  VersionedValue,
  WatchArgs,
  WatchEvent,
  WriteResponse,
  bytesToDebugString,
  computeOperationResultFromWriteResponse
}
import com.databricks.rpc.tls.{JetcdTLS, TLSOptions}

/**
 * Opinionated wrapper around the etcd client that is optimized for values up to ~100KiB in size
 * that are incrementally updated. For example, when Dicer generates a new assignment, it writes
 * only "diffs" from the previous assignment to etcd, not the entire assignment. Features and
 * opinions:
 *
 *  - Keys are Unicode strings rather than arbitrary byte sequences. This allows us to more easily
 *    debug the etcd store in production (e.g., running `etcdctl get --prefix targetname` and
 *    understanding the output).
 *
 *  - Operations are scoped to a particular namespace within which all keys are unique.
 *
 *  - The user chooses version numbers for keys on writes, which are 128-bit values (see
 *    [[Version]])
 *
 *    Note that the Versions exposed and maintained by this client are different from the "versions"
 *    and "revisions" exposed by etcd, which are used internally in the [[EtcdClient]]
 *    implementation but are not exposed to the application:
 *
 *     - etcd version: applies to an individual key in etcd. For keys that do not exist, the version
 *       is 0. Whenever a key value is modified, the version is incremented, and the version is
 *       reset to 0 when the key is deleted.
 *     - etcd revision: applies to the entire etcd store, strictly increasing with each write.
 *
 *  - EtcdClient enforces that key versions are strictly monotonically increasing within each
 *    [[Config.keyNamespace]], even across creations/deletions of the key. To ensure this
 *    monotonicity across multiple incarnations of a key, the client maintains a global version high
 *    watermark and performs an OCC check against it during write. Inadmissible writes (proposed
 *    version too low for new keys, or too high for updates) will result in an error to the caller.
 *    See the note at the bottom for the versioning scheme in more detail.
 *
 *  - The client allows incremental updates to values. Each incremental update is represented as
 *    a separate entry in the underlying store, keyed by the logical key and version number of the
 *    update. The policy on how to combine incremental deltas to yield a logical value is up to the
 *    application. For example, an application could write an initial assignment in the namespace
 *    "ns" for "t1" with Version(highBits=0x2, lowBits=0x18C82CB0BDE) (Tue Dec 19 2023
 *    15:56:34 UTC), and then write an incremental update at Version(0x2, 0x18C82CEBBDF) (a few
 *    minutes later) which splits the second Slice in the initial assignment. This results in the
 *    following key-value pairs in the store:
 *
 *    {{{
 *      ns/t1/0000000000000002/0000018C82CB0BDE -> {["".."Dori"):"pod1", ["Dori"..∞):"pod2"}
 *      ns/t1/0000000000000002/0000018C82CEBBDF -> {["Dori".."Fili"):"pod0", ["Fili"..∞):"pod2"}
 *      ns/t1/version -> "0000000000000002/0000018C82CEBBDF"
 *    }}}
 *
 *    The reader of the store is responsible for combining the incremental updates, which in this
 *    case would yield the following assignment:
 *
 *    {{{
 *      { ["" .. "Dori"): "pod1", ["Dori" .. "Fili"): "pod0", ["Fili", ∞): "pod2" }
 *    }}}
 *
 *    In addition to storing incremental state for different versions of the logical key "t1",
 *    the client writes the latest version of "t1" to the etcd key "ns/t1/version". This
 *    version entry enables OCC checks when performing writes, since it is not possible to condition
 *    a transaction on a range of entries.
 *
 *    The client allows values to be written non-incrementally, in which case the non-incremental
 *    row replaces all of the previous incremental values. This achieves compaction. For example, we
 *    can non-incrementally write a complete assignment for "t1" at KeyVersion
 *    0x18C82D27EE8, which results in the following key-value pairs in the store:
 *
 *    {{{
 *      ns/t1/0000000000000002/0000018C82D27EE8 -> {["".."Fili"):"pod1", ["Fili"..∞):"pod0"}
 *      ns/t1/version -> "0000000000000002/0000018C82D27EE8"
 *    }}}
 *
 *    The entry with the lowest version for a key is always non-incremental, as there are no prior
 *    versions to incrementally update. In the examples above, the entries with KeyVersions
 *    0x18C82CB0BDE and 0x18C82D27EE8 are non-incremental and therefore both contain
 *    "complete" assignments covering the full key space.
 *
 *  - Writes are performed in transactions. This allows us to perform OCC checks, which ensure that
 *    the values being written are consistent with the latest read values.
 *
 * Implementation notes on the 'version high watermark':
 * The watermark is used to guarantee strictly increasing versions in the face of key deletions
 * within the same key namespace.
 * The write scheme to maintain this property is as follows:
 *
 * Suppose the current watermark is w. There are two types of writes to consider:
 *   - To update an existing key at version v1, its proposed next version v2 must satisfy
 *     v1 < v2 <= w. Because v1 < v2, this straightforwardly guarantees increasing versions for
 *     key updates.
 *
 *   - To insert a key k which does not currently exist in the store, its initial version v0 must
 *     satisfy w < v0, and the watermark must be transactionally increased to w' > v0. Even if k
 *     previously existed in etcd at some version vp but was deleted from the store, when
 *     combined with the version upper bound on key updates, these constraints guarantee
 *     vp < w < v0 and thus strictly key increasing versions for insertions as well (this scheme
 *     actually has a stronger property, which is that new keys may only be inserted with the
 *     greatest version across all keys *ever* in the store, but we only care that it's greater
 *     than any previous version for that particular key).
 *
 * Given the scheme above, writes are inadmissible in two cases:
 *   - An *update* write is inadmissible if its proposed version exceeds the the version high
 *     watermark. In this case, EtcdClient runs a new txn to increase the watermark and returns an
 *     error to the caller, and a retry will succeed.
 *
 *   - An *insert* write is inadmissible if its proposed version does not exceed the version high
 *     watermark. In this case, it's up to the caller to retry with a higher proposed version, which
 *     is returned to the caller.
 *
 * Note that this scheme is correct even when keys are accidentally deleted. If we could reliably
 * interpose on all deletions, a simpler scheme would be to just increase the watermark on deletion
 * events instead.
 *
 * For additional background on the etcd data model, see
 * https://etcd.io/docs/v3.5/learning/data_model/.
 */
final class EtcdClient private (jetcd: JetcdWrapper, val config: Config) {
  import NewKeyVersionTxnBuilder.FailedTxnReadResponse
  import com.databricks.caching.util.EtcdKeyValueMapper.{
    ParsedGlobalLogVersionedKey,
    ParsedVersionedKey
  }

  private val logger = PrefixLogger.create(this.getClass, s"namespace: ${config.keyNamespace}")

  /**
   * REQUIRES: `key` must be valid. See [[EtcdKeyValueMapper.isAllowedInEtcdKey()]].
   * REQUIRES: If `previousVersionOpt` is given, `version` must be greater.
   *
   * Writes `value` to `key` at `version`. If `isIncremental` is false, deletes all previous
   * versions of `key` (otherwise old versions are kept). Performs an OCC check on the current
   * version of the key being `previousVersionOpt`. If `previousVersionOpt` is None then the OCC
   * check is conditioned on the non-existence of the key in the store and also on the given
   * `version` being greater than any version that could have been in use previously for the key.
   * If OCC checks fail, returns [[WriteResponse.OccFailure]] with the current state of the key
   * which can be used to retry the write. If the write succeeds, returns
   * [[WriteResponse.Committed]]. If the write fails for some other reason, the returned future may
   * be completed with a [[Throwable]] error, which should be interpreted using the
   * [[com.databricks.caching.util.StatusUtils.convertExceptionToStatus()]] helper:
   *
   *  - `DATA_LOSS` indicates that the store is corrupted (e.g., contains unparseable data).
   *    Manual recovery is required in this case, as there is no automated repair mechanism.
   *  - Other errors may be returned by the underlying etcd client or the etcd gRPC service, but
   *    these errors are not documented in the etcd API.
   *
   * If an optional [[GlobalLogEntry]] `globalLogEntryOpt` is specified, writes the
   * `globalLogEntryOpt.entry` to the global log `globalLogEntryOpt.logName` at `version`. Multiple
   * writes can be written for the same global log `globalLogEntryOpt.logName` and version - they
   * are differentiated by the `key` specified when writing. Log operations are also scoped to the
   * configured namespace, and given a namespace `ns`, the value is stored with the etcd key
   * `ns/globalLogEntryOpt.logName/version/key`.
   *
   * @param key                Key whose version is checked as a condition for performing the
   *                           write(s).
   * @param version            Version of the value(s) being written.
   * @param value              Value being written.
   * @param previousVersionOpt Conditions the write on the latest version of the key being
   *                           `previousVersionOpt`. None is used to condition the write on the
   *                           non-existence of the key in the store, as well as on the provided
   *                           `version` having a value greater than any that could have been used
   *                           previously. When OCC checks fail, [[WriteResponse.OccFailure]] is
   *                           returned with the current state of the key.
   * @param isIncremental      Whether the value is being written incrementally. When true, the
   *                           given value is appended and all existing values are preserved. When
   *                           false, the given value replaces all existing values. Must be false
   *                           for new keys being created (i.e. when previousVersionOpt is None).
   * @param globalLogEntryOpt  Optionally write a separate value to a separate global log.
   * @return A future that completes with a [[WriteResponse]].
   */
  def write(
      key: String,
      version: Version,
      value: ByteString,
      previousVersionOpt: Option[Version],
      isIncremental: Boolean,
      globalLogEntryOpt: Option[GlobalLogEntry] = None): Future[WriteResponse] = {
    require(
      EtcdKeyValueMapper.isAllowedInEtcdKey(key),
      f"Key $key contains illegal characters"
    )
    previousVersionOpt match {
      case Some(previousVersion: Version) =>
        update(key, version, value, previousVersion, isIncremental, globalLogEntryOpt).toFuture
      case None => create(key, version, value, globalLogEntryOpt).toFuture
    }
  }

  /**
   * REQUIRES: `resumeTokenOpt` is not defined or `resumeTokenOpt` was returned from a previous call
   * to read range with the same `key`.
   *
   * Reads a range of rows under the given key. When `resumeTokenOpt` is specified, starts the range
   * of the read from the end of a previous read. A read with a resume token will only return values
   * that have been written since the last read.
   *
   * @param key             The key to read from.
   * @param limit           The maximum number of entries to read.
   * @param resumeTokenOpt  A token from a previous read of `key` that designates where to start
   *                        reading in the range for `key`.
   * @return                A future that completes with a [[ReadResponse]] on success. If the read
   *                        fails for some other reason, the returned future may be completed with a
   *                        [[Throwable]] error, which should be interpreted using the
   *                        [[StatusUtils.convertExceptionToStatus()]] helper:
   *                          - `DATA_LOSS` indicates that the store is corrupted (e.g., contains
   *                            unparseable data). Manual recovery is required in this case, as
   *                            there is no automated repair mechanism.
   *                          - Other errors may be returned by the underlying etcd client or the
   *                            etcd gRPC service, but these errors are not documented in the etcd
   *                            API.
   */
  def read(
      key: String,
      limit: Int = 10,
      resumeTokenOpt: Option[ReadResumeToken] = None): Future[ReadResponse] = {
    require(
      resumeTokenOpt.isEmpty || resumeTokenOpt.get.key == key,
      "Can only use resume token from previous read of same key"
    )

    val scopedKey = ScopedKey(config.keyNamespace, key)
    val inclusiveStart: ByteSequence =
      EtcdKeyValueMapper.getVersionedKeyKeyBytes(scopedKey, Version.MIN)
    val exclusiveEnd: ByteSequence = EtcdKeyValueMapper.toVersionedKeyExclusiveLimitBytes(scopedKey)
    val lastMaxCreateRevision: Long = resumeTokenOpt match {
      case Some(resumeToken: ReadResumeToken) => resumeToken.lastMaxCreateRevision
      case None => -1L
    }

    val getPipeline: Pipeline[GetResponse] = Pipeline.fromFuture(
      jetcd
        .get(
          inclusiveStart,
          GetOption
            .newBuilder()
            .withRange(exclusiveEnd)
            .withSortOrder(GetOption.SortOrder.ASCEND)
            .withSortField(GetOption.SortTarget.CREATE)
            .withMinCreateRevision(lastMaxCreateRevision + 1L)
            .withLimit(limit)
            .build()
        )
        .toScala
    )

    val readPipeline: Pipeline[ReadResponse] = getPipeline.map { result: GetResponse =>
      val versionedValues: mutable.Buffer[VersionedValue] = result.getKvs.asScala.map {
        kv: KeyValue =>
          try {
            parseVersionedKeyValue(kv.getKey, kv.getValue)
          } catch {
            case ex: IllegalArgumentException =>
              throw Status.DATA_LOSS
                .withDescription(s"Data corruption: malformed key: ${kv.getKey}")
                .withCause(ex)
                .asException()
          }
      }

      val maxCreateRevision =
        result.getKvs.asScala.foldLeft(lastMaxCreateRevision)((revision: Long, kv: KeyValue) => {
          math.max(revision, kv.getCreateRevision)
        })

      ReadResponse(versionedValues, result.isMore, ReadResumeToken(key, maxCreateRevision))
    }(InlinePipelineExecutor) // no side-effects or mutable state in the transform
    readPipeline.toFuture
  }

  /**
   * Watches all versions of `watchArgs.key`. All currently known versions are supplied in order to
   * `callback`, followed by a [[WatchEvent.Causal]] event, and then all subsequent versions are
   * supplied to `callback` as they are written until the returned [[Cancellable]] handle is
   * cancelled. The causal event allows the caller to determine when they've caught up with the
   * latest version of a key at startup time. For example, Dicer's Assigner will consider adjusting
   * the latest assignment for a target when it starts up, but does not want to waste time
   * considering adjustments to earlier versions. Since the Assigner does not assume or assert
   * exclusive ownership of a target, it's still possible for it to derive a new assignment from an
   * assignment that has been overwritten by some other Assigner server, but an OCC failure will be
   * returned when the Assigner attempts to write the conflicting derived assignment in that case.
   *
   * For example, if the store contains the following versions of key `t1` as of the
   * [[watch()]] call:
   *
   * {{{
   *   ns/t1/000200000000004A/0000018C82CB0BDE -> {["".."Dori"):"pod1", ["Dori"..∞):"pod2"}
   *   ns/t1/000200000000004A/0000018C82CEBBDF -> {["Dori".."Fili"):"pod0", ["Fili"..∞):"pod2"}
   *   ns/t1/version -> "000200000000004A/0000018C82CEBBDF"
   * }}}
   *
   * a watcher will first receive the following events:
   *
   * {{{
   *   VersionedValue(
   *        version = 000200000000004A/0000018C82CB0BDE),
   *        value = {["".."Dori"):"pod1", ["Dori"..∞):"pod2"}
   *   ),
   *
   *   VersionedValue(
   *        version = 000200000000004A/0000018C82CEBBDF,
   *        value = {["Dori".."Fili"):"pod0", ["Fili"..∞):"pod2"}
   *   ),
   *
   *   Causal
   * }}}
   *
   * If the store is subsequently updated with the following version of `t1`:
   *
   * {{{
   *   ns/t1/000200000000004A/0000018C82D27EE8 -> {["".."Fili"):"pod1", ["Fili"..∞):"pod0"}
   *   ns/t1/version -> "000200000000004A/0000018C82D27EE8"
   * }}}
   *
   * the watcher will then receive:
   *
   * {{{
   *   VersionedValue(
   *     version = 000200000000004A/0000018C82D27EE8,
   *     value = {["".."Fili"):"pod1", ["Fili"..∞):"pod0" }
   *   )
   * }}}
   *
   * Events, which may include a combination of incremental and non-incremental values, will
   * continue until the [[Cancellable]] returned by [[watch]] is cancelled, at which point
   * [[StreamCallback.executeOnFailure()]] will be invoked with the cancellation reason.
   *
   * Failures, which are supplied to [[StreamCallback.executeOnFailure()]], include:
   *  - `FAILED_PRECONDITION` indicates that `key` does not conform to expected watch format.
   *  - `DATA_LOSS` indicates that the store is corrupted (e.g., contains unparseable data).
   *    Manual recovery is required in this case, as there is no automated repair mechanism.
   *  - When the returned [[Cancellable]] is cancelled, the supplied `reason` code is used.
   *  - Other errors may be returned by the underlying etcd client or the etcd gRPC service, but
   *    these errors are not documented.
   */
  def watch(watchArgs: WatchArgs, callback: StreamCallback[WatchEvent]): Cancellable = {
    EtcdWatcher.create(callback.sec, jetcd, config.keyNamespace, watchArgs, callback)
  }

  /**
   * REQUIRES: MUST be called with a `version` that is greater than any previously written
   * watermark.
   *
   * Initializes the version high watermark for [[config.keyNamespace]] for a new store. This is a
   * required step during new store bring-up. Writes will fail if a watermark is absent from the
   * store.
   *
   * Throws a status exception on error:
   *  - `ALREADY_EXISTS` indicates the watermark already exists.
   *  - `DATA_LOSS` indicates a watermark exists but is corrupted.
   *
   * Note: initialization must be performed manually since EtcdClient cannot distinguish
   * between an uninitialized store and one where the watermark has been lost (e.g. due to store
   * wipeout). In the latter case, the store is no longer able to be used safely (it cannot
   * guarantee key version monotonicity). A new, larger watermark must be re-written to the store
   * to recover.
   */
  def initializeVersionHighWatermarkUnsafe(watermark: Version): Future[Unit] = {
    val versionHighWatermarkKey: ByteSequence =
      EtcdKeyValueMapper.getVersionHighWatermarkKeyBytes(config.keyNamespace)
    // Conditioned on its non-existence in the store (if), initialize the version high watermark
    // (then), otherwise read the existing high watermark to return for debugging (else).
    val ifs = Seq[Cmp](new Cmp(versionHighWatermarkKey, Cmp.Op.EQUAL, CmpTarget.version(0)))
    val initialWatermark: ByteSequence = EtcdKeyValueMapper.getVersionValueBytes(watermark)
    val thens = Seq[Op](Op.put(versionHighWatermarkKey, initialWatermark, PutOption.DEFAULT))
    val elses = Seq[Op](Op.get(versionHighWatermarkKey, GetOption.DEFAULT))
    Pipeline
      .fromFuture(jetcd.commit(ifs, thens, elses).toScala)
      .map { response: TxnResponse =>
        if (response.isSucceeded) {
          val revision: Long = response.getHeader.getRevision
          logger.info(
            s"Version high watermark initialized to $watermark at revision=$revision"
          )
        } else {
          // The comparison failed, so a watermark already exists in etcd.
          val getResponses: Seq[GetResponse] = response.getGetResponses.asScala

          // We issued a single Get Op for a single key; check that the dimensions of the result
          // matches.
          iassert(
            getResponses.size == 1 && getResponses.head.getKvs.size() == 1,
            "In the Else clause for version high watermark initialization we read the " +
            "current watermark, which must exist if the transaction failed"
          )

          val versionHighWatermarkBytes: ByteSequence =
            getResponses.head.getKvs.asScala.head.getValue
          val existingWatermark: Version = try {
            EtcdKeyValueMapper.parseVersionValue(versionHighWatermarkBytes)
          } catch {
            case ex: IllegalArgumentException =>
              throw new StatusException(
                Status.DATA_LOSS
                  .withDescription(
                    s"Data corruption: malformed key " +
                    EtcdKeyValueMapper.getVersionHighWatermarkKeyDebugString(config.keyNamespace) +
                    s" with value: $versionHighWatermarkBytes"
                  )
                  .withCause(ex)
              )
          }
          throw new StatusException(
            Status.ALREADY_EXISTS.withDescription(
              s"version high watermark already exists with value: $existingWatermark"
            )
          )
        }
      }(InlinePipelineExecutor)
      .toFuture // Non-blocking; only side effect is logging.
  }

  /** Creates a new versioned key in the store. See [[write()]] comments. */
  private def create(
      key: String,
      version: Version,
      value: ByteString,
      globalLogEntryOpt: Option[GlobalLogEntry]): Pipeline[WriteResponse] = {
    val scopedKey = ScopedKey(config.keyNamespace, key)
    logger.debug(s"Creating new key $scopedKey at version=$version")
    val txnBuilder = new NewKeyVersionTxnBuilder(scopedKey, version)

    // Write the key with value at version if the key doesn't exist and the version is safe,
    // i.e. is greater than any version used previously, confirmed via the version high
    // watermark.
    txnBuilder.ifNonExistent()
    txnBuilder.ifNewVersionExceedsVersionHighWatermark()

    txnBuilder.thenWriteNewValue(value)

    // Maintain the invariant that the watermark is greater than any version by increasing it
    // transactionally. Assuming the Cmp succeeds,
    // newWatermark > version > read(__version_high_watermark), so this is guaranteed to only
    // increase the watermark in the store.
    val newWatermark: Version = createNextVersionHighWatermarkFromProposedVersion(version)
    txnBuilder.thenUpdateVersionHighWatermark(newWatermark)

    // If a global log entry was given for the write, write it too.
    if (globalLogEntryOpt.isDefined) {
      txnBuilder.thenWriteGlobalLogEntry(globalLogEntryOpt.get)
    }

    // The write may fail either because the key already exists or because the version high
    // watermark in the store is too high. In both cases, we'll return the version high watermark
    // so that the caller can update their cache if it's out-of-date, and in the former case, we'll
    // also return the current key version.
    txnBuilder.elseReadCurrentVersionAndVersionHighWatermark()

    val txn: Txn = txnBuilder.build()

    EtcdClient.histogram
      .recordLatencyAsync(
        OperationType.CREATE,
        config.keyNamespace,
        computeOperationResultFromWriteResponse
      ) {
        val commitPipeline: Pipeline[TxnResponse] = Pipeline.fromFuture(jetcd.commit(txn).toScala)
        commitPipeline.map { result: TxnResponse =>
          if (result.isSucceeded) {
            val revision: Long = result.getHeader.getRevision
            logger.debug(
              s"Creation of key $scopedKey at version=$version committed at revision=$revision"
            )
            WriteResponse.Committed(Some(newWatermark))
          } else {
            val FailedTxnReadResponse(
              actualVersionOpt: Option[Version],
              actualVersionHighWatermarkOpt: Option[Version]
            ) = NewKeyVersionTxnBuilder.parseCurrentKeyVersionAndVersionHighWatermark(result)

            (actualVersionOpt, actualVersionHighWatermarkOpt) match {
              case (_, None) =>
                throw new StatusException(
                  Status.DATA_LOSS
                    .withDescription(
                      s"Data corruption: " +
                      EtcdKeyValueMapper
                        .getVersionHighWatermarkKeyDebugString(config.keyNamespace) +
                      " is missing from the store"
                    )
                )
              case (None, Some(actualVersionHighWatermark: Version)) =>
                logger.debug(
                  s"Creation of $scopedKey failed because the proposed version=$version was " +
                  s" not greater than the store's version high watermark " +
                  s"$actualVersionHighWatermark"
                )
                WriteResponse.OccFailure(
                  WriteResponse.KeyState.Absent(actualVersionHighWatermark)
                )
              case (Some(actualVersion: Version), Some(actualVersionHighWatermark: Version)) =>
                logger.debug(
                  s"Creation of $scopedKey at version=$version failed because the key " +
                  s"already exists in the store with version $actualVersion and watermark " +
                  s"$actualVersionHighWatermark"
                )
                WriteResponse
                  .OccFailure(
                    WriteResponse.KeyState.Present(actualVersion, actualVersionHighWatermark)
                  )
            }
          }
        }(InlinePipelineExecutor) // no side-effects or mutable state in the transform
      }
  }

  /** Updates an existing key in the store. See [[write()]] comments. */
  private def update(
      key: String,
      version: Version,
      value: ByteString,
      previousVersion: Version,
      isIncremental: Boolean,
      globalLogEntryOpt: Option[GlobalLogEntry]): Pipeline[WriteResponse] = {
    require(
      version > previousVersion,
      s"Versions must increase: $version <= $previousVersion"
    )
    val scopedKey = ScopedKey(config.keyNamespace, key)
    logger.debug(
      s"(${if (isIncremental) "incrementally" else "non-incrementally"}) " +
      s"updating $scopedKey to version=$version conditioned on previousVersion=$previousVersion"
    )
    val txnBuilder = new NewKeyVersionTxnBuilder(scopedKey, version)

    // Write the key with value at version if the key currently has `previousVersion` and is safe,
    // i.e. it has a version that's less than the current version high watermark.
    txnBuilder.ifCurrentVersionMatches(previousVersion)
    txnBuilder.ifNewVersionBelowVersionHighWatermark()

    txnBuilder.thenWriteNewValue(value)

    // Non-incremental writes make previous versions of the key obsolete, so delete them.
    if (!isIncremental) {
      txnBuilder.thenDeleteEarlierVersions()
    }

    // If a global log entry was given for the write, write it too.
    if (globalLogEntryOpt.isDefined) {
      txnBuilder.thenWriteGlobalLogEntry(globalLogEntryOpt.get)
    }

    // The write may fail either because the key already exists or because the version high
    // watermark in the store is too low. In the former case, we'll inform the caller of the
    // existing version and the current watermark so they can update their cache. In the latter
    // case, we'll attempt to bump the watermark and return the newly written watermark to the
    // caller so they can try again with a (hopefully safe) value.
    txnBuilder.elseReadCurrentVersionAndVersionHighWatermark()

    val txn: Txn = txnBuilder.build()

    EtcdClient.histogram
      .recordLatencyAsync(
        OperationType.UPDATE,
        config.keyNamespace,
        computeOperationResultFromWriteResponse
      ) {
        val commitPipeline: Pipeline[TxnResponse] = Pipeline.fromFuture(jetcd.commit(txn).toScala)
        commitPipeline.flatMap { result: TxnResponse =>
          if (result.isSucceeded) {
            val revision: Long = result.getHeader.getRevision
            logger.debug(
              s"Update to $scopedKey at version=$version committed at revision=$revision"
            )
            Pipeline.successful(WriteResponse.Committed(newKeyVersionLowerBoundExclusiveOpt = None))
          } else {
            // The Cmp failed; either the current version doesn't match the expected predecessor,
            // the watermark is too high, or both.
            val FailedTxnReadResponse(
              actualVersionOpt: Option[Version],
              actualVersionHighWatermarkOpt: Option[Version]
            ) = NewKeyVersionTxnBuilder.parseCurrentKeyVersionAndVersionHighWatermark(result)
            (actualVersionOpt, actualVersionHighWatermarkOpt) match {
              case (_, None) =>
                throw new StatusException(
                  Status.DATA_LOSS
                    .withDescription(
                      s"Data corruption: " +
                      EtcdKeyValueMapper
                        .getVersionHighWatermarkKeyDebugString(config.keyNamespace) +
                      " is missing from the store"
                    )
                )
              case (None, Some(actualVersionHighWatermark: Version)) =>
                logger.debug(
                  s"Updating $scopedKey to version=$version from " +
                  s"previousVersion=$previousVersion failed because the predecessor " +
                  s"$previousVersion does not exist"
                )
                // This is generally unexpected; the caller knew some previous version for the key
                // and wanted to update it, but the store doesn't have it.
                Pipeline.successful(
                  WriteResponse.OccFailure(
                    WriteResponse.KeyState.Absent(
                      actualVersionHighWatermark
                    )
                  )
                )
              case (Some(actualVersion: Version), Some(actualVersionHighWatermark: Version)) =>
                logger.debug(
                  s"Updating $scopedKey to version=$version from " +
                  s"previousVersion=$previousVersion failed. Actual version is $actualVersion " +
                  s"and version high watermark is $actualVersionHighWatermark"
                )
                if (actualVersion == previousVersion) {
                  // The key exists at the expected previous version, so the txn must have failed
                  // because the watermark was too low. Push the watermark forward so that a retry
                  // from the caller may succeed, and then report the failure to the caller so
                  // they may retry.
                  val newWatermark: Version = createNextVersionHighWatermarkFromProposedVersion(
                    version
                  )
                  logger.info(
                    s"Attempting to increase the version high watermark to at least " +
                    s"$newWatermark from $actualVersionHighWatermark for write of $scopedKey " +
                    s"at $version"
                  )
                  attemptIncreaseVersionHighWatermark(newWatermark).map {
                    actualNewWatermark: Version =>
                      // After successfully bumping the watermark, we could retry internally, but
                      // the caller needs to handle retries anyway, so we rely on that instead.
                      throw new StatusException(
                        Status.INTERNAL.withDescription(
                          f"Unable to update key to version $version because " +
                          f"previous watermark $actualVersionHighWatermark was too low. " +
                          f"Watermark was updated to $actualNewWatermark and a retry should " +
                          f"succeed."
                        )
                      )
                  }(InlinePipelineExecutor) // no side-effects or mutable state in the transform
                } else {
                  // The key exists at some other version, so there must have been an intervening
                  // write. It's possible that the watermark may also need to be updated, but we
                  // don't complicate the code here by handling that possibility. We inform
                  // the caller of the intervening write, and a retry with the correct previous
                  // version can separately discover that the watermark needs to be updated.
                  Pipeline.successful(
                    WriteResponse.OccFailure(
                      WriteResponse.KeyState.Present(actualVersion, actualVersionHighWatermark)
                    )
                  )
                }
            }
          }
        }(InlinePipelineExecutor) // no side-effects or mutable state in the transform
      }
  }

  /**
   * Returns a new [[Version]] to write as the version high watermark from the newly proposed
   * version `proposedVersion`. The return value is guaranteed the be greater than
   * `proposedVersion`, but it's still the caller's responsibility to transactionally verify that
   * this value is greater than the current watermark in the store.
   */
  private def createNextVersionHighWatermarkFromProposedVersion(
      proposedVersion: Version): Version = {
    // Updates require that the watermark is higher than the proposed version, so we bump the
    // watermark in large chunks so that a single bump can accommodate many updates, which would
    // otherwise be forced to abort and first attempt to bump the watermark.
    val newLowBits: Long = proposedVersion.lowBits.value + VERSION_HIGH_WATERMARK_BLOCK_INCREMENT

    // Note: if the existing watermark in the store is already far in the future, every new key will
    // just push it farther by another large, constant block. To handle this, you could check the
    // local clock and advance it by a smaller margin if the existing watermark is far ahead which
    // would allow it to catch up more quickly. However, because new keys are rare, this
    // reconciliation should happen even without this optimization, as key updates will just
    // avoid updating the watermark if their proposals fall below the current watermark, which will
    // be the case if an errant clock pushed it far into the future.
    // TODO(<internal bug>): Add a clock to EtcdClient and implement the scheme described above.

    proposedVersion.copy(lowBits = newLowBits)
  }

  /**
   * Runs an etcd transaction which attempts to *increase* the current version high watermark in
   * the store to `versionHighWatermarkLowerBound`. This will only increase the value in the
   * store; if the existing value is already at least the desired value, the existing value is
   * read and returned instead, without modifying the contents of the store.
   * */
  private def attemptIncreaseVersionHighWatermark(
      versionHighWatermarkLowerBound: Version): Pipeline[Version] = {
    // If the current watermark is less than the desired watermark lower bound, write the new
    // watermark. Otherwise, if the watermark is at least the desired value, read it and return it
    // to the caller. Note that a bounded comparison is used rather than strict equality on the
    // existing watermark to avoid unnecessary logical conflicts between concurrent writers: imagine
    // one wants to write a value 40 while another wants to write 50. If the write for 40 wins the
    // race, a strict equality check would cause the writer for 50 to fail (however, at present,
    // concurrent writes should be rare as there is generally only a single active Assigner).
    val ifs = Seq[Cmp](
      new Cmp(
        EtcdKeyValueMapper.getVersionHighWatermarkKeyBytes(config.keyNamespace),
        Cmp.Op.LESS,
        CmpTarget.value(EtcdKeyValueMapper.getVersionValueBytes(versionHighWatermarkLowerBound))
      )
    )
    val thens = Seq[Op](
      Op.put(
        EtcdKeyValueMapper.getVersionHighWatermarkKeyBytes(config.keyNamespace),
        EtcdKeyValueMapper.getVersionValueBytes(versionHighWatermarkLowerBound),
        PutOption.DEFAULT
      )
    )
    val elses = Seq[Op](
      Op.get(
        EtcdKeyValueMapper.getVersionHighWatermarkKeyBytes(config.keyNamespace),
        GetOption.DEFAULT
      )
    )
    Pipeline
      .fromFuture(jetcd.commit(Txn(ifs, thens, elses)).toScala)
      .map { response: TxnResponse =>
        if (response.isSucceeded) {
          logger.info(
            s"Version high watermark increased to $versionHighWatermarkLowerBound"
          )
          versionHighWatermarkLowerBound
        } else {
          val getResponses: Seq[GetResponse] = response.getGetResponses.asScala
          iassert(
            getResponses.size == 1 && getResponses.head.getKvs.size() == 1,
            "In the Else clause for version high watermark increase we read the current " +
            "watermark, which must exist if the transaction failed"
          )
          val versionHighWatermarkBytes: ByteSequence =
            getResponses.head.getKvs.asScala.head.getValue
          val existingWatermark: Version = try {
            EtcdKeyValueMapper.parseVersionValue(versionHighWatermarkBytes)
          } catch {
            case ex: IllegalArgumentException =>
              throw new StatusException(
                Status.DATA_LOSS
                  .withDescription(
                    s"Data corruption: malformed key " +
                    EtcdKeyValueMapper.getVersionHighWatermarkKeyDebugString(config.keyNamespace) +
                    s" with value: $versionHighWatermarkBytes"
                  )
                  .withCause(ex)
              )
          }
          logger.info(
            s"Version high watermark already at $existingWatermark, not increasing it"
          )
          existingWatermark
        }
      }(InlinePipelineExecutor) // no side-effects or mutable state in the transform
  }

  /**
   * Attempts to parse the given versioned `key` and `value` into a [[VersionedValue]].
   *
   * @throws IllegalArgumentException if parsing failed.
   */
  @throws[IllegalArgumentException]
  private def parseVersionedKeyValue(key: ByteSequence, value: ByteSequence): VersionedValue = {
    try {
      EtcdKeyValueMapper.parseVersionedKey(key) match {
        case ParsedVersionedKey(_, retVersion: Version) =>
          VersionedValue(retVersion, ByteString.copyFrom(value.getBytes), None)
      }
    } catch {
      case _: IllegalArgumentException =>
        try {
          EtcdKeyValueMapper.parseGlobalLogVersionedKey(key) match {
            case ParsedGlobalLogVersionedKey(
                _,
                retVersion: Version,
                scopedKey: ScopedKey
                ) =>
              VersionedValue(
                retVersion,
                ByteString.copyFrom(value.getBytes),
                Some(scopedKey.key)
              )
          }
        } catch {
          case _: IllegalArgumentException =>
            throw new IllegalArgumentException(
              s"key was not a valid versioned key nor a valid global log key: " +
              s"${bytesToDebugString(key)}"
            )
        }
    }
  }
}

object EtcdClient {

  /**
   * Configuration for the client.
   */
  case class Config(keyNamespace: KeyNamespace)

  /**
   * REQUIRES: `keyNamespace` must be non-empty and valid according to
   *           [[EtcdKeyValueMapper.isAllowedInEtcdKey]].
   */
  case class KeyNamespace(value: String) {
    require(value.nonEmpty, "KeyNamespace must be non-empty")
    require(
      EtcdKeyValueMapper.isAllowedInEtcdKey(value),
      s"KeyNamespace $value contains illegal characters"
    )

    override def toString: String = value
  }

  /**
   * REQUIRES: `pageLimit` must be positive.
   * REQUIRES: `backupPollingInterval` must be at least 5 seconds.
   * REQUIRES: `watchDuration` must be at least 15 seconds.
   *
   *
   * Options to configure the behavior of [[EtcdClient.watch]]. See the class documentation for
   * [[EtcdWatcher]] for more information on the `backupPollingInterval` and `watchDuration`
   * parameters.
   *
   * We limit the minimum duration for the polling and watch duration parameters to prevent
   * misconfigurations that could lead to excessive load on the etcd server.
   *
   * @param key the application key to watch.
   * @param pageLimit Limit on the number of versions for the initial reads and backup polling in
   *                  [[EtcdClient.watch()]]. The implementation will paginate if necessary to
   *                  get all versions.
   * @param backupPollingInterval Interval at which to do backup reads while a watch is in progress.
   * @param watchDuration How long to keep the watch open before failing with CANCELLED, or
   *                      Duration.Inf to try to keep the watch open indefinitely.
   */
  case class WatchArgs(
      key: String,
      pageLimit: Int = 10,
      backupPollingInterval: FiniteDuration = 30.seconds,
      watchDuration: Duration = Duration.Inf) {
    require(pageLimit > 0, "pageLimit must be positive")
    require(backupPollingInterval >= 1.second, "Backup polling interval too short")
    require(watchDuration >= 15.seconds, "Watch duration too short")
  }

  private[util] val histogram = EtcdClientLatencyHistogram("dicer_etcd_client_op_latency")

  /**
   * REQUIRES: `highBits` and `lowBits` are individually non-negative.
   *
   * Represents the full 128-bit version of a key. Versions are guaranteed to be strictly
   * increasing for a key, even across multiple creations and deletions of the key in the store.
   *
   * For best performance, users should choose values for `lowBits` which track unix time in
   * milliseconds. Otherwise, users may observe more spurious failures due to EtcdClient's scheme to
   * guarantee increasing versions, which is designed to work well with this choice.
   */
  case class Version(highBits: Long, lowBits: UnixTimeVersion) extends Ordered[Version] {
    require(
      highBits >= 0 && lowBits >= 0,
      f"Version high and low bits must be non-negative (high=$highBits, low=$lowBits)"
    )

    def compare(that: Version): Int = {
      if (this.highBits != that.highBits) {
        this.highBits.compare(that.highBits)
      } else {
        this.lowBits.compare(that.lowBits)
      }
    }

    /**
     * Compare this version to an `Option[Version]`, where this version always compares greater than
     * None and otherwise compares normally with any other version.
     */
    def compare(thatVersionOpt: Option[Version]): Int = {
      thatVersionOpt match {
        case None => 1 // This version is always greater than `None`.
        case Some(thatVersion: Version) => this.compare(thatVersion)
      }
    }

    override def toString: String = {
      f"$highBits#$lowBits"
    }
  }
  object Version {
    val MIN: Version = Version(0, UnixTimeVersion.MIN)
  }

  /**
   * Represents a log entry `entry` for global log with name `logName`.
   *
   * @param logName Name of the global log for the entry.
   * @param entry   Entry value.
   */
  case class GlobalLogEntry(logName: String, entry: ByteString)

  /**
   * Possible outcomes of an [[EtcdClient.write()]].
   *
   * To guarantee increasing versions for potentially deleted keys, EtcdClient only admits writes
   * for new keys whose proposed version exceeds some lower bound. This value is returned to
   * callers in [[WriteResponse]] which can then be used to propose versions for future writes.
   */
  sealed trait WriteResponse
  object WriteResponse {

    /**
     * Current state of a key reported in the event of an OCC failure. Includes information that can
     * be used to retry the write.
     */
    sealed trait KeyState

    object KeyState {

      /**
       * The key is present in the store at the given `version`, and new keys may only be written
       * to the store if their version strictly exceeds `newKeyVersionLowerBoundExclusive`.
       */
      case class Present(version: Version, newKeyVersionLowerBoundExclusive: Version)
          extends KeyState {
        // Provide clarity in debug messages on what the contents is.
        override def toString: String =
          s"""Present(version=$version,
             | newKeyVersionLowerBoundExclusive=$newKeyVersionLowerBoundExclusive)""".stripMargin
      }

      /**
       * The key is absent from the store, and writes for any new keys will only be permitted if
       * their version strictly exceeds `newKeyVersionLowerBoundExclusive`.
       */
      case class Absent(newKeyVersionLowerBoundExclusive: Version) extends KeyState {
        // Provide clarity in debug messages on what the contents is.
        override def toString: String =
          s"Absent(newKeyVersionLowerBoundExclusive=$newKeyVersionLowerBoundExclusive)"
      }
    }

    /** The write committed successfully. */
    // Consider reading the watermark and returning a newKeyVersionLowerBound here as well, given
    // that we're interacting with etcd anyway.
    case class Committed(newKeyVersionLowerBoundExclusiveOpt: Option[Version]) extends WriteResponse

    /**
     * There was an OCC failure. This can be either due to a key version mismatch or, when writing a
     * new key to the store, use of an insufficiently high version number for which the
     * client is unable to guarantee version monotonicity across deletions of the key in the
     * store. In either case, the write was not performed and the current state of the key is
     * `keyState`, which includes information that can be used to retry the write.
     */
    case class OccFailure(keyState: KeyState) extends WriteResponse
  }

  /**
   * A token that can be used to resume a [[EtcdClient.read()]] from where it left off. A read
   * with a resume token will only return values that have been written since the last read.
   *
   * @param key                   The key of the preceding call to [[EtcdClient.read()]].
   * @param lastMaxCreateRevision The maximum create revision of the results returned in the
   *                              preceding call to [[EtcdClient.read()]].
   */
  case class ReadResumeToken(
      private[EtcdClient] val key: String,
      private[EtcdClient] val lastMaxCreateRevision: Long)

  /**
   * A version of a value for a key.
   *
   * @param version The version of this value of the key.
   * @param value   The value of the key at this version.
   * @param globalLogApplicationKeyOpt If this value represents a global log entry, this is the
   *                                   original application key for which the log entry was written.
   */
  case class VersionedValue(
      version: Version,
      value: ByteString,
      globalLogApplicationKeyOpt: Option[String] = None) {
    override def toString: String = globalLogApplicationKeyOpt match {
      case Some(globalLogApplicationKey: String) =>
        s"${Bytes.toString(value)}@$version/$globalLogApplicationKey"
      case None => s"${Bytes.toString(value)}@$version"
    }
  }

  /**
   * A successful outcome of a [[EtcdClient.read()]].
   *
   * @param values      The range of values with versions in the range specified by the read
   *                    invocation.
   * @param more        Whether there are more values in the range that were not returned. The
   *                    maximum number of values returned in a single invocation of
   *                    [[EtcdClient.read()]] is defined by [[Config.pageLimit]].
   * @param resumeToken A token for resuming reading after the read of this response.
   */
  case class ReadResponse(values: Seq[VersionedValue], more: Boolean, resumeToken: ReadResumeToken)

  /** Event emitted by [[EtcdClient.watch()]]. */
  sealed trait WatchEvent
  object WatchEvent {

    /**
     * A version of a value for the watched key. Note that the caller is responsible for determining
     * whether the value is incremental or not based on its contents, which are opaque to
     * [[EtcdClient]].
     */
    case class VersionedValue(version: Version, value: ByteString) extends WatchEvent {
      override def toString: String = s"${Bytes.toString(value)}@$version"
    }

    /**
     * Signals to the watcher that it has caught up with the latest value of the store as of the
     * initiation of its [[EtcdClient.watch()]] call. This allows the caller to accumulate
     * incremental updates and then supply the latest complete value from the store to a higher-
     * level watcher. For example, the Dicer Assigner cares only about the latest assignment, and
     * not the incremental updates that contribute to that assignment.
     */
    case object Causal extends WatchEvent
  }

  /** Create [[EtcdClient]] with given endpoints. */
  def create(
      etcdEndpoints: Seq[String],
      tlsOptionsOpt: Option[TLSOptions],
      config: Config
  ): EtcdClient = {
    require(etcdEndpoints.nonEmpty)
    val timeoutEc: SequentialExecutionContext =
      SequentialExecutionContext.create(
        timeoutEcPool,
        s"etcd-client-timeout-${config.keyNamespace}"
      )
    new EtcdClient(
      new JetcdWrapperImpl(timeoutEc, createJetcdClient(etcdEndpoints, tlsOptionsOpt)),
      config
    )
  }

  /** Create Jetcd [[jetcd.Client]] with the given endpoints. */
  private[util] def createJetcdClient(
      etcdEndpoints: Seq[String],
      tlsOptionsOpt: Option[TLSOptions]): jetcd.Client = {
    // Client requires us to add providers to the default registry before building it or else it
    // errors.
    NameResolverRegistry.getDefaultRegistry.register(new IPResolverProvider())

    val client: jetcd.ClientBuilder = jetcd.Client.builder
      .endpoints(etcdEndpoints.toSeq: _*)
      // Initially, we used the "pick_first" load balancing policy, but this causes availability
      // issues when the client connects to an etcd pod that becomes unable to process requests
      // while not triggering errors on the connection. The "round_robin" policy allows us to
      // periodically skip such an indeterminate failed connection, increasing availability.
      .loadBalancerPolicy("round_robin")
      // set waitForReady = false so that etcd client will not retry forever on connect error.
      // Retrying forever is problematic because
      // 1) in some cases it will not help (if there is a mTLS misconfig)
      // 2) it obscures the underlying issue (hangs instead of throwing exception)
      // 3) it prevents having a fine grained retry strategy.
      // Please see https://github.com/etcd-io/jetcd/issues/970 for more background
      .waitForReady(false)
      .connectTimeout(java.time.Duration.ofSeconds(10))

    tlsOptionsOpt.map(JetcdTLS.configureClient(_, client))

    client.build
  }

  /**
   * Low level etcd interface (compared to the higher level interface exposed by [[EtcdClient]] to
   * expose only a subset of the full jetcd API to callers, and to support error injection in tests.
   */
  private[util] trait JetcdWrapper {

    /** Equivalent to `jetcd.KV.txn().If(ifs).Then(thens).Else(elses).commit(). */
    def commit(ifs: Seq[Cmp], thens: Seq[Op], elses: Seq[Op]): CompletableFuture[TxnResponse]

    /** See `jetcd.KV.get(ByteSequence, GetOption)`. */
    def get(key: ByteSequence, option: GetOption): CompletableFuture[GetResponse]

    /** See `jetcd.Watch.watch(ByteSequence, WatchOption, Listener)`. */
    def watch(key: ByteSequence, option: WatchOption, listener: Listener): Watch.Watcher

    /** Convenience for `commit(txn.ifs, txn.thens, txn.elses)`. */
    final def commit(txn: Txn): CompletableFuture[TxnResponse] = {
      commit(txn.ifs, txn.thens, txn.elses)
    }
  }

  /**
   * [[JetcdWrapper]] implementation which simply delegates to an internal [[jetcd.Client]].
   *
   * @param timeoutEc an executor on which to schedule timeout events for outgoing requests to
   *                  etcd. We only use this for scheduling commands and do not rely on the
   *                  mutual exclusion given by the SEC.
   * @param jetcdClient the underlying jetcd client.
   */
  private[util] final class JetcdWrapperImpl(
      timeoutEc: SequentialExecutionContext,
      jetcdClient: jetcd.Client)
      extends JetcdWrapper {

    override def commit(
        ifs: Seq[Cmp],
        thens: Seq[Op],
        elses: Seq[Op]): CompletableFuture[TxnResponse] = {
      withTimeout(
        jetcdClient.getKVClient
          .txn()
          .If(ifs.toSeq: _*)
          .Then(thens.toSeq: _*)
          .Else(elses.toSeq: _*)
          .commit()
      )
    }

    override def get(key: ByteSequence, option: GetOption): CompletableFuture[GetResponse] = {
      withTimeout(jetcdClient.getKVClient.get(key, option))
    }

    override def watch(
        key: ByteSequence,
        option: WatchOption,
        listener: Listener): Watch.Watcher = {
      jetcdClient.getWatchClient.watch(key, option, listener)
    }

    /**
     * Schedules a future command to complete `asyncWork` with an exception if it does not otherwise
     * complete within [[ETCD_REQUEST_TIMEOUT]].
     */
    private def withTimeout[T](asyncWork: CompletableFuture[T]): CompletableFuture[T] = {
      // Note: timing out these events this way may leave lingering state in the jetcd client
      // and slowly leak memory. However, this is the recommended solution in
      // https://github.com/etcd-io/jetcd/issues/1326. We could consider ways to reclaim this
      // memory if it becomes a problem, e.g. by periodically recreating the client. However, the
      // rate of leakage should be generally be very low.

      // We unfortunately can't simply use CompletableFuture.orTimeout here because we're not on
      // Java 9.
      val cancellable: Cancellable = timeoutEc.schedule(
        "timeout",
        ETCD_REQUEST_TIMEOUT,
        () => {
          // Execution of this timeout task may race with successful completion of the promise and
          // thus this call may return false. This is fine and doesn't require any special handling.
          asyncWork.completeExceptionally(new StatusException(Status.DEADLINE_EXCEEDED))
        }
      )

      // Optimization: if the async work completes successfully before the timeout, attempt to
      // cancel the timeout event to avoid pileup on the timeout executor. If the timeout does
      // execute after the work successfully completes, it's a functional no-op.
      asyncWork.whenComplete { (_, _) =>
        cancellable.cancel()
      }
    }
  }

  /** Bundles the ifs, thens, and elses of an etcd transaction. */
  private[util] case class Txn private (ifs: Seq[Cmp], thens: Seq[Op], elses: Seq[Op])

  /**
   * A value used to construct higher version high watermarks by summing with a proposed
   * [[KeyVersion]].
   *
   * [[KeyVersion]] values track the current time in milliseconds, so the size of the block to
   * advance the watermark is approximately the minimum duration of time that may pass before an
   * update must advance it again (since version updates must stay below the watermark). For that
   * reason, we pick a value that's large enough to allow most writes to avoid bumping the
   * watermark, but small enough to avoid a large gap between the generation number and the current
   * time for new keys, whose versions must always exceed the current watermark.
   */
  private val VERSION_HIGH_WATERMARK_BLOCK_INCREMENT: Long = 1.hour.toMillis

  /**
   * The duration after which etcd requests which get completed with an error if they fail to
   * return.
   */
  private val ETCD_REQUEST_TIMEOUT: FiniteDuration = 10.seconds

  /**
   * Builder for EtcdClient transactions which write a new `version` of a `key` to the store.
   * Defines high level EtcdClient operations, like writing a global log entry and deleting all
   * previous versions of a key. For example, a non-incremental write transaction to an existing key
   * which also updates the global log might look like:
   *
   * {{{
   *   val txnBuilder = new NewKeyVersionTxnBuilder(key, version)
   *   txnBuilder.ifCurrentVersionMatches(previousVersion)
   *   txnBuilder.thenWriteNewValue(value)
   *   txnBuilder.thenDeleteEarlierVersions()
   *   txnBuilder.thenWriteGlobalLogEntry(globalLogEntry)
   *   txnBuilder.elseReadCurrentVersion()
   *   val txn = txnBuilder.build()
   * }}}
   */
  private class NewKeyVersionTxnBuilder(scopedKey: ScopedKey, version: Version) {

    private val ifs = Seq.newBuilder[Cmp]
    private val thens = Seq.newBuilder[Op]
    private val elses = Seq.newBuilder[Op]

    private val versionKey: ByteSequence = EtcdKeyValueMapper.getKeyVersionKeyBytes(scopedKey)
    private val nextVersionValue: ByteSequence = EtcdKeyValueMapper.getVersionValueBytes(version)
    private val nextVersionedKey: ByteSequence =
      EtcdKeyValueMapper.getVersionedKeyKeyBytes(scopedKey, version)

    /**
     * Adds an if condition to the transaction that the current version of the key matches the given
     * `previousVersion`.
     */
    def ifCurrentVersionMatches(previousVersion: Version): this.type = {
      ifs += new Cmp(
        versionKey,
        Cmp.Op.EQUAL,
        CmpTarget.value(EtcdKeyValueMapper.getVersionValueBytes(previousVersion))
      )
      this
    }

    /** Adds an if condition to the transaction that the key doesn't currently exist. */
    def ifNonExistent(): this.type = {
      ifs += new Cmp(versionKey, Cmp.Op.EQUAL, CmpTarget.version(0))
      this
    }

    /**
     * Adds an if condition to the transaction that the proposed new version for the key is below
     * the version high watermark.
     */
    def ifNewVersionBelowVersionHighWatermark(): this.type = {
      ifs += new Cmp(
        EtcdKeyValueMapper.getVersionHighWatermarkKeyBytes(scopedKey.namespace),
        Cmp.Op.GREATER,
        CmpTarget.value(nextVersionValue)
      )
      this
    }

    /**
     * Adds an if condition to the transaction that the proposed new version for the key is below
     * the version high watermark.
     */
    def ifNewVersionExceedsVersionHighWatermark(): this.type = {
      ifs += new Cmp(
        EtcdKeyValueMapper.getVersionHighWatermarkKeyBytes(scopedKey.namespace),
        Cmp.Op.LESS,
        CmpTarget.value(nextVersionValue)
      )
      this
    }

    /**
     * Adds a then statement to the transaction that writes the new key `value` to the store at
     * `version`.
     */
    def thenWriteNewValue(value: ByteString): this.type = {
      thens += Op.put(versionKey, nextVersionValue, PutOption.DEFAULT)
      thens += Op.put(nextVersionedKey, ByteSequence.from(value.toByteArray), PutOption.DEFAULT)
      this
    }

    /**
     * Adds a then statement to the transaction that deletes all previous versions of the key.
     */
    def thenDeleteEarlierVersions(): this.type = {
      val inclusiveStart: ByteSequence =
        EtcdKeyValueMapper.getVersionedKeyKeyBytes(scopedKey, Version.MIN)
      val exclusiveEnd: ByteSequence = nextVersionedKey
      thens += Op.delete(
        inclusiveStart,
        DeleteOption.newBuilder().withRange(exclusiveEnd).build()
      )
      this
    }

    /** Adds a then statement to the transaction which writes `version` as the new watermark. */
    def thenUpdateVersionHighWatermark(newWatermark: Version): this.type = {
      thens += Op.put(
        EtcdKeyValueMapper.getVersionHighWatermarkKeyBytes(scopedKey.namespace),
        EtcdKeyValueMapper.getVersionValueBytes(newWatermark),
        PutOption.DEFAULT
      )
      this
    }

    /** Adds a then statement to the transaction which writes the given `globalLogEntry`. */
    def thenWriteGlobalLogEntry(globalLogEntry: GlobalLogEntry): this.type = {
      val globalLogVersionedKey: ByteSequence = EtcdKeyValueMapper.getGlobalLogVersionedKeyBytes(
        globalLogEntry.logName,
        version,
        scopedKey
      )
      thens += Op.put(
        globalLogVersionedKey,
        ByteSequence.from(globalLogEntry.entry.toByteArray),
        PutOption.DEFAULT
      )
      this
    }

    /**
     * Adds an else statement to the transaction which reads the current version and the version
     * high watermark in the store.
     */
    def elseReadCurrentVersionAndVersionHighWatermark(): this.type = {
      elses += Op.get(versionKey, GetOption.DEFAULT)
      elses += Op.get(
        EtcdKeyValueMapper.getVersionHighWatermarkKeyBytes(scopedKey.namespace),
        GetOption.DEFAULT
      )
      this
    }

    /** Returns a [[Txn]] representing this transaction. */
    def build(): Txn = {
      Txn(ifs.result(), thens.result(), elses.result())
    }
  }

  private object NewKeyVersionTxnBuilder {

    /**
     * The result of a [[Txn]] built by [[NewKeyVersionTxnBuilder]] where the guard
     * check failed and thus only read current metadata from etcd instead of writing.
     */
    case class FailedTxnReadResponse(
        actualVersionOpt: Option[Version],
        actualVersionHighWatermarkOpt: Option[Version])

    /**
     * Parses the result of the read constructed by
     * [[NewKeyVersionTxnBuilder.elseReadCurrentVersionAndVersionHighWatermark()]].
     */
    def parseCurrentKeyVersionAndVersionHighWatermark(
        result: TxnResponse): FailedTxnReadResponse = {
      iassert(
        result.getGetResponses.size() == 2,
        "For key writing transactions, in the Else case we perform two `get` " +
        "operations, one for the current key version (if any), and one for the current " +
        "version high watermark."
      )
      try {
        FailedTxnReadResponse(
          actualVersionOpt = parseVersionFromSingleKeyGetResponse(result.getGetResponses.get(0)),
          actualVersionHighWatermarkOpt =
            parseVersionFromSingleKeyGetResponse(result.getGetResponses.get(1))
        )
      } catch {
        case ex: IllegalArgumentException =>
          throw new StatusException(
            Status.DATA_LOSS
              .withDescription("Data corruption: malformed version")
              .withCause(ex)
          )
      }
    }
  }

  /**
   * REQUIRES: `response` is the result of an etcd single-key read of a version key.
   *
   * Parses the version out of the given get response for the read of a version key. Returns None if
   * the get response indicates that the key does not exist.
   *
   * @throws IllegalArgumentException if the version value in the get response failed to be parsed.
   */
  @throws[IllegalArgumentException]
  private def parseVersionFromSingleKeyGetResponse(response: GetResponse): Option[Version] = {
    val actualVersionKvs: Seq[KeyValue] = response.getKvs.asScala
    actualVersionKvs match {
      case Seq() =>
        // The version is not present. 0 is used for this case.
        None
      case Seq(actualVersionKv: KeyValue) =>
        val versionValueBytes: ByteSequence = actualVersionKv.getValue
        Some(EtcdKeyValueMapper.parseVersionValue(versionValueBytes))
      case _ =>
        ifail(
          "In the Else case, we attempt to read a single key which either exists (1 kv) " +
          s"or doesn't (0 kvs), but got: $actualVersionKvs"
        )
    }
  }

  /**
   * Returns `bytes` as a string if it is valid UTF-8, otherwise returns "(non-utf-8 bytes) $bytes".
   */
  private def bytesToDebugString(bytes: ByteSequence): String = {
    try {
      EtcdKeyValueMapper.keyBytesToDebugString(bytes)
    } catch {
      case _: IllegalArgumentException =>
        s"(non-utf-8 bytes) ${Bytes.toString(ByteString.copyFrom(bytes.getBytes))}"
    }
  }

  /**
   * Converts a [[WriteResponse]] to a canonical [[Code]] to be used as a label for a write latency
   * observation.
   *
   * @param response The response to convert to a canonical code.
   * @return         The canonical code to be used as a label for a write latency observation.
   */
  private def computeOperationResultFromWriteResponse(
      response: Try[WriteResponse]): OperationResult.Value = {
    response match {
      case scala.util.Success(writeResponse: WriteResponse) =>
        writeResponse match {
          case WriteResponse.Committed(_) =>
            OperationResult.SUCCESS
          case WriteResponse.OccFailure(keyState: KeyState) =>
            keyState match {
              case KeyState.Present(_, _) =>
                OperationResult.WRITE_OCC_FAILURE_KEY_PRESENT
              case KeyState.Absent(_) =>
                OperationResult.WRITE_OCC_FAILURE_KEY_ABSENT
            }
        }
      case scala.util.Failure(_) =>
        OperationResult.FAILURE
    }
  }

  /** An application `key` scoped to a particular `namespace`. */
  private[util] case class ScopedKey(namespace: KeyNamespace, key: String) {

    /** Returns debug string representing this scoped key. */
    override def toString: String = {
      s"{namespace=${namespace.value}, key=$key}"
    }
  }

  /**
   * A shared pool on which to schedule timeout events for slow etcd requests.
   *
   * Timeout commands are cheap and total outgoing QPS is low, so we only allocate a single thread.
   */
  private val timeoutEcPool: SequentialExecutionContextPool =
    SequentialExecutionContextPool.create("etcd-client-timeout", numThreads = 1)

  private[util] object forTest {

    /** Constructs [[EtcdClient]] from [[JetcdWrapper]]. */
    def apply(jetcd: JetcdWrapper, config: Config): EtcdClient = {
      new EtcdClient(jetcd, config)
    }
  }
}

package com.databricks.caching.util

import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.CompletionException
import scala.collection.{SortedMap, mutable}
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import io.grpc.{Status, StatusException}
import io.prometheus.client.CollectorRegistry
import io.etcd.jetcd
import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.api.RangeResponse
import io.etcd.jetcd.kv.GetResponse
import io.grpc.{StatusRuntimeException, Status => JetcdStatus}
import com.databricks.caching.util.MetricUtils.ChangeTracker
import com.databricks.caching.util.TestUtils.assertThrow
import com.google.protobuf.ByteString
import com.databricks.caching.util.EtcdClient.WriteResponse.KeyState
import com.databricks.caching.util.EtcdClient.{
  GlobalLogEntry,
  JetcdWrapper,
  JetcdWrapperImpl,
  ReadResponse,
  Version,
  VersionedValue,
  WatchArgs,
  WatchEvent,
  WriteResponse
}
import com.databricks.rpc.DatabricksServerWrapper
import com.databricks.testing.DatabricksTest

class EtcdClientSuite extends DatabricksTest {
  private val ARBITRARY_NON_NEGATIVE_VALUE: Long = 3

  private val NAMESPACE = EtcdClient.KeyNamespace("test-namespace")

  private val etcd = EtcdTestEnvironment.create()

  private val jetcdClient: jetcd.Client =
    EtcdClient.createJetcdClient(Seq(etcd.endpoint), None)

  private val sec = SequentialExecutionContext.createWithDedicatedPool("EtcdClientSuite")

  override def beforeEach(): Unit = {
    etcd.deleteAll()
  }

  override def afterAll(): Unit = {
    etcd.close()
  }

  /** Converts the given string to its UTF-8 byte encoding. */
  private def bytes(s: String): ByteString = ByteString.copyFromUtf8(s)

  /** Converts the given string to its UTF-8 ByteSequence encoding. */
  private def byteSequence(s: String): ByteSequence = ByteSequence.from(s, UTF_8)

  /**
   * Calls `client.write(key, version, value, previousVersion, isIncremental)` and awaits successful
   * completion of the write. The write may succeed in one of two ways:
   *  1. Directly! `client.write()` may yield [[WriteResponse.Committed]].
   *  2. The attempt may yield an OCC failure due to internal retries, where the first attempt
   *     actually succeeded but where the jetcd client believes it failed. When jetcd retries the
   *     write, it looks like an OCC failure because the current version of the object in the store
   *     is `version` instead of `previousVersion`. If the test sees
   *     `OccFailure(KeyState.Present(version))`, it verifies by reading directly from etcd that the
   *     write plausibly succeeded by inspecting the rows between `key/previousVersion` and
   *     `key/version` inclusive. For example, if we see `OccFailure(KeyState.Present(20))` when
   *     writing
   *
   *        key="a", version=1/20, value="foo", previousVersion=1/10, isIncremental=true
   *
   *     we expect to see the following rows in `[a/{storeInc}/1/10, a/{storeInc}/1/20]`:
   *
   *        a/{storeInc}/1/10 -> * // payload can be anything
   *        a/{storeInc}/1/20 -> value
   *
   *     for non-incremental writes, we expect to see only the latter row.
   */
  private def awaitSuccessfulWrite(
      client: EtcdClient,
      key: String,
      version: Version,
      value: String,
      previousVersionOpt: Option[Version],
      isIncremental: Boolean,
      globalLogEntryOpt: Option[GlobalLogEntry] = None): Unit = {
    val future: Future[WriteResponse] =
      client.write(key, version, bytes(value), previousVersionOpt, isIncremental, globalLogEntryOpt)
    val writeResponse: WriteResponse = Await.result(future, Duration.Inf)
    val possibleSpuriousOccFailure: Boolean = writeResponse match {
      case WriteResponse.Committed(_) =>
        false
      case WriteResponse.OccFailure(WriteResponse.KeyState.Present(actualVersion: Version, _))
          if actualVersion == version =>
        // It's likely this is a spurious OCC failure due to a retry after a false error. We will
        // read the affected rows to figure out if the write actually succeeded below.
        logger.info("Encountered spurious OCC failure!")
        true
      case _ =>
        fail(
          s"Unexpected response to " +
          s"write($key, $version, $previousVersionOpt): $writeResponse"
        )
    }

    // Lazily formatted string for debugging including all etcd contents.
    object LazyDebugString {
      override def toString: String = {
        val builder = new StringBuilder
        builder.append(s"etcd contents, ")
        builder.append(s"possibleSpuriousOccFailure=$possibleSpuriousOccFailure):\n")
        for (tuple <- etcd.getAll()) {
          val (key, value): (String, String) = tuple
          builder.append(s"  $key -> $value\n")
        }
        builder.toString()
      }
    }

    // Read directly from etcd to validate that the expected contents were written. We do this
    // even when the client claims the write committed successfully because this code path is
    // extremely unlikely to be hit otherwise (less than 1/1000 writes encounter a spurious
    // OCC failure).
    val rangeReadLowInclusiveVersion = previousVersionOpt match {
      case Some(previousVersion) => previousVersion
      // If there was no existing key, then read all versions in the store in order to verify
      // that there really is only 1 version now presently in the store.
      case _ => Version.MIN
    }
    val rangeReadHighExclusiveVersion = version.copy(lowBits = version.lowBits.value + 1)
    val previousVersionedKey: String =
      getVersionedKey(client.config.keyNamespace, key, rangeReadLowInclusiveVersion)
    val versionedKey: String = getVersionedKey(client.config.keyNamespace, key, version)
    val highExclusiveVersionedKey: String =
      getVersionedKey(client.config.keyNamespace, key, rangeReadHighExclusiveVersion)
    val keyValues: SortedMap[String, String] =
      etcd.getRange(previousVersionedKey, highExclusiveVersionedKey)
    if (previousVersionOpt.isDefined && isIncremental) {
      // For incremental writes with a predecessor (None indicates no predecessor), we expect
      // both the previous version and latest written version to be present.
      assert(keyValues.size == 2, LazyDebugString)
      assert(keyValues.firstKey == previousVersionedKey, LazyDebugString)
    } else if (previousVersionOpt.isEmpty || !isIncremental) {
      // For non-incremental writes, or where there was no predecessor, only the latest written
      // version should be present.
      assert(
        keyValues.size == 1,
        f"rangeReadLow: $previousVersionedKey, high: $highExclusiveVersionedKey, $LazyDebugString"
      )
    }
    assert(keyValues.last == ((versionedKey, value)), LazyDebugString)

    globalLogEntryOpt match {
      case Some(globalLogEntry: GlobalLogEntry) =>
        val previousVersionedKey: String =
          getVersionedKey(
            client.config.keyNamespace,
            globalLogEntry.logName,
            rangeReadLowInclusiveVersion,
            tagOpt = Some(key)
          )
        val versionedKey: String =
          getVersionedKey(
            client.config.keyNamespace,
            globalLogEntry.logName,
            version,
            tagOpt = Some(key)
          )
        val highExclusiveVersionedKey: String =
          getVersionedKey(
            client.config.keyNamespace,
            globalLogEntry.logName,
            rangeReadHighExclusiveVersion
          )
        val keyValues: SortedMap[String, String] =
          etcd.getRange(previousVersionedKey, highExclusiveVersionedKey)
        val kvValue: Option[String] = keyValues.get(versionedKey)
        assert(kvValue.isDefined, LazyDebugString)
        assert(kvValue.get == globalLogEntry.entry.toStringUtf8, LazyDebugString)
      case None => ()
    }
  }

  /**
   * Encoded characters to replace reserved characters in etcd keys.
   *
   * [[EtcdClient]] reserves the ':', '/', and '%' characters for internal use. If these characters
   * * are present in the etcd key, the test needs to encode them before reading from etcd.
   */
  private val KEY_ENCODING_MAP = Map[Char, String]('/' -> "%2F", ':' -> "%3A", '%' -> "%25")

  /**
   * Encodes the given string to replace reserved characters in etcd keys. This is used when reading
   * from etcd to ensure that etcd keys are correctly encoded.
   */
  private def encodeString(string: String): String = {
    string.flatMap { char =>
      KEY_ENCODING_MAP.getOrElse(char, char.toString)
    }
  }

  private def getVersionedKey(
      namespace: EtcdClient.KeyNamespace,
      key: String,
      version: Version,
      tagOpt: Option[String] = None): String = {
    val formattedVersion = f"${version.highBits}%016X/${version.lowBits.value}%016X"
    val encodedKey = encodeString(key)
    val encodedNamespace = encodeString(namespace.value)
    tagOpt match {
      case Some(tag) =>
        val encodedTag = encodeString(tag)
        f"$encodedNamespace/$encodedKey/$formattedVersion/$encodedTag"
      case None => f"$encodedNamespace/$encodedKey/$formattedVersion"
    }
  }

  /** Awaits an OCC failure write response and returns the current key state. */
  private def awaitOccFailure(future: Future[WriteResponse]): KeyState = {
    val writeResponse: WriteResponse = Await.result(future, Duration.Inf)
    writeResponse match {
      case WriteResponse.OccFailure(keyState) => keyState
      case _ => fail(s"Unexpected write response: $writeResponse")
    }
  }

  /** Returns the version from `keyState`. Returns None if the key is absent. */
  private def getVersionFromKeyState(keyState: KeyState): Option[Version] = {
    keyState match {
      case KeyState.Present(version, _) => Some(version)
      case KeyState.Absent(_) => None
    }
  }

  /** Returns the current histogram count recorded in "dicer_etcd_client_op_latency". */
  private def getDicerEtcdClientOpLatencyHistogramCount(
      operation: String,
      keyNamespace: EtcdClient.KeyNamespace,
      status: String,
      operationResult: OperationResult.Value): Int = {
    MetricUtils.getHistogramCount(
      CollectorRegistry.defaultRegistry,
      "dicer_etcd_client_op_latency",
      Map(
        "operation" -> operation,
        "status" -> status,
        "operationResult" -> operationResult.toString,
        "keyNamespace" -> keyNamespace.value
      )
    )
  }

  /**
   * Returns a sequence of malformed versioned keys in tuples of of the form (key: String,
   * malformedVersionedKeyBytes: ByteSequence). If `isGlobalLogKey` is true, then the versioned key
   * has "/tag" appended to the end (otherwise it does not).
   */
  private def generateMalformedVersionedKeys(
      namespace: EtcdClient.KeyNamespace,
      isGlobalLogKey: Boolean): Seq[(String, ByteSequence)] = {
    for (tuple <- generateMalformedVersions(isGlobalLogKey).zipWithIndex)
      yield {
        val (bytes, i): (ByteSequence, Int) = tuple
        (s"k$i", ByteSequence.from(s"$namespace/k$i/", UTF_8).concat(bytes))
      }
  }

  /**
   * Returns a sequence of malformed versions in [[ByteSequence]] form. If `isGlobalLogKey` is true,
   * then the versioned key has "/tag" appended to the end (otherwise it does not).
   * */
  private def generateMalformedVersions(isGlobalLogKey: Boolean): Seq[ByteSequence] = {
    // Add a tag suffix if this is for testing global log keys
    val suffix = if (isGlobalLogKey) "/tag" else ""
    val validVersionPiece: String = f"$ARBITRARY_NON_NEGATIVE_VALUE%016X"
    // Note that the first byte here is 48, or 0x30, which is the ASCII code for '0'. This is needed
    // so that it falls into the range of the read which starts from '0'
    val nonUtf8Example =
      Array[Byte](48, -1, -2, -3, -4, -5, -6, -7, -8, -9, -10, -11, -12, -13, -14, -15)
    assert(!ByteString.copyFrom(nonUtf8Example).isValidUtf8)
    Seq(
      // Invalid UTF-8
      ByteSequence.from(s"$validVersionPiece/", UTF_8).concat(ByteSequence.from(nonUtf8Example)),
      // Doesn't match pattern: high bits 1 digit too short
      ByteSequence.from(s"00AB18C82CB0BDE/$validVersionPiece" + suffix, UTF_8),
      // Doesn't match pattern: high bits 1 digit too long
      ByteSequence.from(s"00AB0018C82CB0BDE/$validVersionPiece" + suffix, UTF_8),
      // Doesn't match pattern: low bits 1 digit too short
      ByteSequence.from(s"$validVersionPiece/000018C82CB0BDE" + suffix, UTF_8),
      // Doesn't match pattern: low bits 1 digit too long
      ByteSequence.from(s"$validVersionPiece/00000018C82CB0BDE" + suffix, UTF_8),
      // Doesn't match pattern: "/" separator between high bits and low bits
      ByteSequence.from(s"$validVersionPiece-$validVersionPiece" + suffix, UTF_8),
      // Doesn't match pattern: Invalid radix 16 long: high bits invalid radix 16
      ByteSequence.from(s"1BAE018C82CB0BDG/$validVersionPiece" + suffix, UTF_8),
      // Doesn't match pattern: Invalid radix 16 long: low bits invalid radix 16
      ByteSequence.from(s"$validVersionPiece/0000018C82CB0BDG" + suffix, UTF_8),
      // High bits are invalid (negative)
      ByteSequence.from(s"80AB018C82CB0BDF/$validVersionPiece" + suffix, UTF_8),
      // Low bits are invalid (negative)
      ByteSequence.from(s"$validVersionPiece/8000018C82CB0BDE" + suffix, UTF_8)
    )
  }

  /** Returns 0.0 if the watch is not lagging, 1.0 if it is. */
  private def getWatchLaggingGaugeValue(key: String): Double = {
    MetricUtils.getMetricValue(
      CollectorRegistry.defaultRegistry,
      metric = "dicer_etcd_client_watch_lagging",
      Map("key" -> key)
    )
  }

  /**
   * Reads the current value of the version high watermark from `etcd`. Will throw if absent.
   */
  private def readVersionHighWatermark(namespace: EtcdClient.KeyNamespace): String = {
    etcd.getKey(f"${namespace.value}/__version_high_watermark").get
  }

  test("KeyNamespace validation") {
    // Test plan: Verify that EtcdClient.KeyNamespace construct throws IllegalArgumentException when
    // given invalid namespace values.
    assertThrow[IllegalArgumentException]("KeyNamespace must be non-empty") {
      EtcdClient.KeyNamespace("")
    }

    assertThrow[IllegalArgumentException]("KeyNamespace __key contains illegal characters") {
      EtcdClient.KeyNamespace("__key")
    }

    EtcdClient.KeyNamespace("valid-namespace")
  }

  test("WatchArgs validation") {
    // Test plan: Verify that the WatchArgs constructor throws an IllegalArgumentException
    // when given a variety of invalid values.

    val validKey: String = "some-key"
    val validPageLimit: Int = 10
    val validBackupPollingInterval: FiniteDuration = 30.seconds
    val validWatchDuration: Duration = Duration.Inf

    for (invalidPageLimit: Int <- Seq(Integer.MIN_VALUE, -2, -1, 0)) {
      assertThrow[IllegalArgumentException]("pageLimit") {
        WatchArgs(
          validKey,
          invalidPageLimit,
          validBackupPollingInterval,
          validWatchDuration
        )
      }
    }

    for (invalidBackupPollingInterval: FiniteDuration <- Seq(
        -1.second,
        0.seconds,
        0.99.seconds
      )) {
      assertThrow[IllegalArgumentException]("Backup polling interval") {
        WatchArgs(
          validKey,
          validPageLimit,
          invalidBackupPollingInterval,
          validWatchDuration
        )
      }
    }

    for (invalidWatchDuration: FiniteDuration <- Seq(
        -1.second,
        1.second,
        14.seconds
      )) {
      assertThrow[IllegalArgumentException]("Watch duration") {
        WatchArgs(validKey, validPageLimit, validBackupPollingInterval, invalidWatchDuration)
      }
    }
  }

  test("Write (update) successfully writes incremental and non-incremental values") {
    // Test plan: Verify that `write` writes the versioned value for the given key to the store, and
    // that incremental writes result in an added row while non-incremental writes result in a
    // single row for the key. Verify this by writing a sequence of incremental and non-incremental
    // writes to the same key and check that the store has the expected contents.
    val client: EtcdClient = etcd.createEtcdClientAndInitializeStore(EtcdClient.Config(NAMESPACE))

    // Write the initial value.
    awaitSuccessfulWrite(
      client,
      "key",
      version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 1),
      "value1",
      previousVersionOpt = None,
      isIncremental = false
    )
    assert(
      etcd.getPrefix(s"$NAMESPACE/key/version") == Map(
        f"$NAMESPACE/key/version" -> f"$ARBITRARY_NON_NEGATIVE_VALUE%016X/0000000000000001"
      )
    )
    // Write an incremental value. The store should contain both the initial value and the
    // incremental value after the write.
    awaitSuccessfulWrite(
      client,
      "key",
      version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 2),
      "value2",
      previousVersionOpt = Some(Version(ARBITRARY_NON_NEGATIVE_VALUE, 1)),
      isIncremental = true
    )
    assert(
      etcd.getPrefix(s"$NAMESPACE/key/version") == Map(
        f"$NAMESPACE/key/version" -> f"$ARBITRARY_NON_NEGATIVE_VALUE%016X/0000000000000002"
      )
    )
    // Write a non-incremental value. The store should contain only the non-incremental value after
    // the write.
    awaitSuccessfulWrite(
      client,
      "key",
      version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 3),
      "value3",
      previousVersionOpt = Some(Version(ARBITRARY_NON_NEGATIVE_VALUE, 2)),
      isIncremental = false
    )
    assert(
      etcd.getPrefix(f"$NAMESPACE/key/version") == Map(
        f"$NAMESPACE/key/version" -> f"$ARBITRARY_NON_NEGATIVE_VALUE%016X/0000000000000003"
      )
    )
    // Write another incremental value. The store should contain both the latest non-incremental
    // value and the new incremental value.
    awaitSuccessfulWrite(
      client,
      "key",
      version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 0xAB),
      "value4",
      previousVersionOpt = Some(Version(ARBITRARY_NON_NEGATIVE_VALUE, 3)),
      isIncremental = true
    )
    assert(
      etcd.getPrefix(f"$NAMESPACE/key/version") == Map(
        f"$NAMESPACE/key/version" -> f"$ARBITRARY_NON_NEGATIVE_VALUE%016X/00000000000000AB"
      )
    )
  }

  test("Write (create) checks the version high watermark") {
    // Test plan: Verify that writes for new keys are successful when they have a sufficiently high
    // key version, and fail with an OccFailure reporting a safe key lower bound to use when
    // they don't. Verify that a write using the version lower bound reported by this OccFailure
    // then succeeds.

    val client: EtcdClient = etcd.createEtcdClientAndInitializeStore(EtcdClient.Config(NAMESPACE))

    // Write an initial key, which should succeed and update the version high watermark.
    awaitSuccessfulWrite(
      client,
      "key1",
      version = Version(4, 1),
      "value1",
      previousVersionOpt = None,
      isIncremental = false
    )

    // Verify that creating a second key with the same version does not succeed, and the response
    // reports the new version bound.
    val newKeyVersionLowerBoundExclusive: Version =
      awaitOccFailure(
        client
          .write(
            "key2",
            version = Version(4, 1),
            bytes("value2"),
            previousVersionOpt = None,
            isIncremental = false
          )
      ) match {
        case KeyState.Absent(newKeyVersionLowerBoundExclusive) => newKeyVersionLowerBoundExclusive
        case KeyState.Present(_, _) => fail("key2 should not exist in the store")
      }
    assert(
      etcd.getPrefix(f"$NAMESPACE/key2") == Map()
    )

    // Now retry the write with a version higher than the lower bound and verify that it succeeds.
    val safeVersion: Version =
      newKeyVersionLowerBoundExclusive.copy(
        lowBits = newKeyVersionLowerBoundExclusive.lowBits.value + 1
      )
    awaitSuccessfulWrite(
      client,
      "key2",
      version = safeVersion,
      "value1",
      previousVersionOpt = None,
      isIncremental = false
    )
  }

  test("Write (create) of existing key fails and returns the current version") {
    // Test plan: Verify that writes for new keys fail if the key already exists in the store and
    // returns an OccFailure reporting the current actual key version in the store.

    val client = etcd.createEtcdClientAndInitializeStore(EtcdClient.Config(NAMESPACE))

    // Write some initial value to the store so that the key exists.
    awaitSuccessfulWrite(
      client,
      "key",
      version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 41),
      "value",
      previousVersionOpt = None,
      isIncremental = false
    )

    // Attempt to write (create) the key with `previousVersionOpt = None`.
    val actualVersionOpt: Option[Version] = getVersionFromKeyState(
      awaitOccFailure(
        client
          .write(
            "key",
            version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 42),
            bytes("value"),
            previousVersionOpt = None,
            isIncremental = false
          )
      )
    )
    assert(
      actualVersionOpt.contains(Version(ARBITRARY_NON_NEGATIVE_VALUE, 41))
    )
  }

  test("Write (update) checks the previous version") {
    // Test plan: Verify that writes to an existing key update the key when the previous version
    // matches, and fail with an OccFailure reporting the current version when it doesn't.

    val client = etcd.createEtcdClientAndInitializeStore(EtcdClient.Config(NAMESPACE))

    // Write "key" with version 41 to the store.
    awaitSuccessfulWrite(
      client,
      "key",
      version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 41),
      "value1",
      previousVersionOpt = None,
      isIncremental = false
    )

    // Update "key" to version 42 based on current version 41.
    awaitSuccessfulWrite(
      client,
      "key",
      version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 42),
      "value2",
      previousVersionOpt = Some(Version(ARBITRARY_NON_NEGATIVE_VALUE, 41)),
      isIncremental = false
    )

    // Attempt to update the key based on stale version 41.
    val actualVersionOpt1: Option[Version] = getVersionFromKeyState(
      awaitOccFailure(
        client
          .write(
            "key",
            version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 43),
            bytes("value3"),
            previousVersionOpt = Some(Version(ARBITRARY_NON_NEGATIVE_VALUE, 41)),
            isIncremental = false
          )
      )
    )
    assert(
      actualVersionOpt1.contains(Version(ARBITRARY_NON_NEGATIVE_VALUE, 42))
    )

    // Attempt to update the key based on bogus version 43.
    val actualVersionOpt2: Option[Version] = getVersionFromKeyState(
      awaitOccFailure(
        client
          .write(
            "key",
            version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 44),
            bytes("value3"),
            previousVersionOpt = Some(Version(ARBITRARY_NON_NEGATIVE_VALUE, 43)),
            isIncremental = false
          )
      )
    )
    assert(
      actualVersionOpt2.contains(Version(ARBITRARY_NON_NEGATIVE_VALUE, 42))
    )
  }

  test("Write (update) to non-existent key returns a safe key version") {
    // Test plan: Verify that attempted writes to an existing key fail when the key doesn't exist
    // and returns an OccFailure reporting a safe version to use to create the key.

    val client: EtcdClient = etcd.createEtcdClientAndInitializeStore(EtcdClient.Config(NAMESPACE))

    // Write an initial key to the store, which should advance the version high watermark.
    val initialVersion = Version(ARBITRARY_NON_NEGATIVE_VALUE, 41)
    awaitSuccessfulWrite(
      client,
      "key1",
      version = initialVersion,
      "value1",
      previousVersionOpt = None,
      isIncremental = false
    )

    // Verify that attempting an update to a key which actually doesn't exist returns an OccFailure,
    // and that the OccFailure reports a safe version lower bound.
    val newKeyVersionLowerBoundExclusive: Version =
      awaitOccFailure(
        client
          .write(
            "key2",
            version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 1),
            bytes("value2"),
            previousVersionOpt = Some(Version(ARBITRARY_NON_NEGATIVE_VALUE, 0)),
            isIncremental = false
          )
      ) match {
        case KeyState.Absent(newKeyVersionLowerBoundExclusive: Version) =>
          newKeyVersionLowerBoundExclusive
        case KeyState.Present(_, _) => fail("key2 should not exist in the store")
      }

    // The exact value of the new watermark/lower bound is an implementation detail, but it must
    // be greater than the version that we wrote.
    assert(newKeyVersionLowerBoundExclusive > initialVersion)
    assert(
      etcd.getPrefix(f"$NAMESPACE/key2") == Map()
    )

    // Now retry the write (this time, a create request no previous version) with higher than the
    // safe version and verify that it succeeds.
    val newVersion = newKeyVersionLowerBoundExclusive.copy(
      lowBits = newKeyVersionLowerBoundExclusive.lowBits.value + 1
    )
    awaitSuccessfulWrite(
      client,
      "key2",
      version = newVersion,
      "value1",
      previousVersionOpt = None,
      isIncremental = false
    )
  }

  test("Write (update) fails with INTERNAL error when version exceeds watermark") {
    // Test plan: verify that updates fail with an INTERNAL error when the proposed version exceeds
    // the current version high watermark (as opposed to doing an internal retry).
    val client: EtcdClient = etcd.createEtcdClientAndInitializeStore(EtcdClient.Config(NAMESPACE))

    awaitSuccessfulWrite(
      client,
      "key1",
      version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 42),
      "value2",
      previousVersionOpt = None,
      isIncremental = false
    )

    val newKeyVersionLowerBoundExclusive: Version = EtcdKeyValueMapper.parseVersionValue(
      ByteSequence.from(readVersionHighWatermark(NAMESPACE), UTF_8)
    )
    val proposedVersion: Version =
      newKeyVersionLowerBoundExclusive.copy(
        lowBits = newKeyVersionLowerBoundExclusive.lowBits.value + 1
      )

    val updateFuture: Future[WriteResponse] =
      client.write(
        "key1",
        proposedVersion,
        bytes("value3"),
        previousVersionOpt = Some(Version(ARBITRARY_NON_NEGATIVE_VALUE, 42)),
        isIncremental = false
      )
    val exception: StatusException =
      assertThrow[StatusException]("previous watermark") {
        Await.result(updateFuture, Duration.Inf)
      }
    assert(exception.getStatus.getCode == Status.INTERNAL.getCode)

    // While the write reports a failure, it should have pushed the watermark forward so that a
    // retry can succeed.
    assert(
      EtcdKeyValueMapper.parseVersionValue(
        ByteSequence.from(readVersionHighWatermark(NAMESPACE), UTF_8)
      ) == proposedVersion.copy(lowBits = proposedVersion.lowBits.value + 1.hour.toMillis)
    )
  }

  test("Write (update) which attempts to increase watermark observes concurrent watermark update") {
    // Test plan: verify that an update which attempts to increase the version high watermark
    // can tolerate an intervening write which updates the watermark first. A store write which
    // also updates the watermark involves two transactions. The test verifies that a concurrent
    // write, which intervienes between these two transactions, is correctly tolerated.
    val (clientUnderTest, interposingWrapperUnderTest): (EtcdClient, InterposingJetcdWrapper) =
      etcd.createEtcdClientWithInterposingJetcdWrapper(
        RealtimeTypedClock,
        EtcdClient.Config(NAMESPACE)
      )
    val interveningClient: EtcdClient =
      etcd.createEtcdClientAndInitializeStore(EtcdClient.Config(NAMESPACE))

    // Write a new key, which will increase the version high watermark beyond the written version.
    val key: String = "key1"
    awaitSuccessfulWrite(
      clientUnderTest,
      key,
      version = Version(4, 1),
      "value1",
      previousVersionOpt = None,
      isIncremental = false
    )

    interposingWrapperUnderTest.setBlockCommits(true)
    val writeResponse: Future[WriteResponse] = clientUnderTest.write(
      key,
      Version(6, 2),
      bytes("does-not-matter"),
      previousVersionOpt = Some(Version(4, 1)),
      isIncremental = false
    )

    // This should observe that the watermark is too low, and should attempt to update the watermark
    // in a new transaction. Wait on the completion of the first transaction before proceeding.
    val commitFuture: Future[Unit] = interposingWrapperUnderTest.runEarliestBlockedCommit()
    Await.ready(commitFuture, Duration.Inf)

    // Before we allow that to happen, we issue an intervening write to update the watermark through
    // the second client. Note that we use a proposed version so that the new watermark is different
    // than the one that would be produced by `clientUnderTest`.
    val interveningWriteVersion = Version(6, 100)
    val expectedWatermarkVersion: Version = interveningWriteVersion.copy(
      lowBits = interveningWriteVersion.lowBits.value + 1.hour.toMillis
    )
    assertThrow[StatusException](s"Watermark was updated to $expectedWatermarkVersion") {
      Await.result(
        interveningClient.write(
          key,
          interveningWriteVersion,
          ByteString.copyFromUtf8("does-not-matter"),
          previousVersionOpt = Some(Version(4, 1)),
          isIncremental = false
        ),
        Duration.Inf
      )
    }
    assert(
      EtcdKeyValueMapper.parseVersionValue(
        ByteSequence.from(readVersionHighWatermark(NAMESPACE), UTF_8)
      ) == expectedWatermarkVersion
    )

    // Now, we allow the first commit to proceed. It should observe the new watermark written by
    // the intervening client, and should not update it again.
    interposingWrapperUnderTest.setBlockCommits(false)
    assertThrow[StatusException](s"Watermark was updated to $expectedWatermarkVersion") {
      Await.result(writeResponse, Duration.Inf)
    }
    assert(
      EtcdKeyValueMapper.parseVersionValue(
        ByteSequence.from(readVersionHighWatermark(NAMESPACE), UTF_8)
      ) == expectedWatermarkVersion
    )
  }

  test("Write and read keys with special characters") {
    // Test plan: Verify that writes succeed when the key contains characters that are reserved for
    // internal use by EtcdClient, and that the key is correctly encoded in the store. Verify this
    // by writing keys with reserved characters '/', ':', and '%' and checking that the store
    // encodes the keys according to percent-encoding.
    val client: EtcdClient = etcd.createEtcdClientAndInitializeStore(EtcdClient.Config(NAMESPACE))

    // Contains '/'.
    val key1 = "ke/y"
    awaitSuccessfulWrite(
      client,
      key1,
      version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 41),
      "value1",
      previousVersionOpt = None,
      isIncremental = false
    )

    // Contains ':'.
    // First attempt a write to get the new key version lower bound.
    val key2 = "ke:y"
    val newKeyVersionLowerBoundExclusive1: Version =
      awaitOccFailure(
        client
          .write(
            key2,
            version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 42),
            bytes("value2"),
            previousVersionOpt = None,
            isIncremental = false
          )
      ) match {
        case KeyState.Absent(newKeyVersionLowerBoundExclusive: Version) =>
          newKeyVersionLowerBoundExclusive
        case KeyState.Present(_, _) => fail(s"$key2 should not exist in the store")
      }
    val newVersion1: Version = newKeyVersionLowerBoundExclusive1.copy(
      lowBits = newKeyVersionLowerBoundExclusive1.lowBits.value + 1
    )
    awaitSuccessfulWrite(
      client,
      key2,
      version = newVersion1,
      "value1",
      previousVersionOpt = None,
      isIncremental = false
    )

    // Contains '%'.
    // First attempt a write to get the new key version lower bound.
    val key3 = "ke%y"
    val newKeyVersionLowerBoundExclusive2: Version =
      awaitOccFailure(
        client
          .write(
            key3,
            version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 42),
            bytes("value2"),
            previousVersionOpt = None,
            isIncremental = false
          )
      ) match {
        case KeyState.Absent(newKeyVersionLowerBoundExclusive: Version) =>
          newKeyVersionLowerBoundExclusive
        case KeyState.Present(_, _) => fail(s"$key3 should not exist in the store")
      }
    val newVersion2: Version = newKeyVersionLowerBoundExclusive2.copy(
      lowBits = newKeyVersionLowerBoundExclusive2.lowBits.value + 1
    )
    awaitSuccessfulWrite(
      client,
      key3,
      version = newVersion2,
      "value1",
      previousVersionOpt = None,
      isIncremental = false
    )

    // Contains '%', '/', and ':'.
    // First attempt a write to get the new key version lower bound.
    val key4 = "ke%y/:"
    val newKeyVersionLowerBoundExclusive3: Version =
      awaitOccFailure(
        client
          .write(
            key4,
            version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 42),
            bytes("value2"),
            previousVersionOpt = None,
            isIncremental = false
          )
      ) match {
        case KeyState.Absent(newKeyVersionLowerBoundExclusive: Version) =>
          newKeyVersionLowerBoundExclusive
        case KeyState.Present(_, _) => fail(s"$key4 should not exist in the store")
      }
    val newVersion3: Version = newKeyVersionLowerBoundExclusive3.copy(
      lowBits = newKeyVersionLowerBoundExclusive3.lowBits.value + 1
    )
    awaitSuccessfulWrite(
      client,
      key4,
      version = newVersion3,
      "value1",
      previousVersionOpt = None,
      isIncremental = false
    )

    // Entirely EtcdClient reserved characters.
    // First attempt a write to get the new key version lower bound.
    val key5 = "//:%::/%/%%://:"
    val newKeyVersionLowerBoundExclusive4: Version =
      awaitOccFailure(
        client
          .write(
            key5,
            version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 42),
            bytes("value2"),
            previousVersionOpt = None,
            isIncremental = false
          )
      ) match {
        case KeyState.Absent(newKeyVersionLowerBoundExclusive: Version) =>
          newKeyVersionLowerBoundExclusive
        case KeyState.Present(_, _) => fail(s"$key5 should not exist in the store")
      }

    val newVersion4: Version = newKeyVersionLowerBoundExclusive4.copy(
      lowBits = newKeyVersionLowerBoundExclusive4.lowBits.value + 1
    )
    awaitSuccessfulWrite(
      client,
      key5,
      version = newVersion4,
      "value1",
      previousVersionOpt = None,
      isIncremental = false
    )

    // Contains a reserved character and its encoding.
    // First attempt a write to get the new key version lower bound.
    val key6 = "%2525%%25%2525%%"
    val newKeyVersionLowerBoundExclusive5: Version =
      awaitOccFailure(
        client
          .write(
            key6,
            version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 42),
            bytes("value2"),
            previousVersionOpt = None,
            isIncremental = false
          )
      ) match {
        case KeyState.Absent(newKeyVersionLowerBoundExclusive: Version) =>
          newKeyVersionLowerBoundExclusive
        case KeyState.Present(_, _) => fail(s"$key6 should not exist in the store")
      }

    val newVersion5: Version = newKeyVersionLowerBoundExclusive5.copy(
      lowBits = newKeyVersionLowerBoundExclusive5.lowBits.value + 1
    )
    awaitSuccessfulWrite(
      client,
      key6,
      version = newVersion5,
      "value1",
      previousVersionOpt = None,
      isIncremental = false
    )
  }

  test("KeyNamespace values are encoded correctly") {
    // Test plan: Verify that the KeyNamespace values are correctly encoded when used in the store.
    // Verify this by writing keys with KeyNamespace values that contain reserved characters and
    // checking that the store contains the expected contents.

    // Write a key with a KeyNamespace value that contains '/'.
    val namespace1 = EtcdClient.KeyNamespace("some/namespace")
    val client1: EtcdClient = etcd.createEtcdClientAndInitializeStore(EtcdClient.Config(namespace1))

    awaitSuccessfulWrite(
      client1,
      "key",
      version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 41),
      "value1",
      previousVersionOpt = None,
      isIncremental = false
    )

    // Write a key with a KeyNamespace value that contains ':'.
    val namespace2 = EtcdClient.KeyNamespace("some:namespace")
    val client2: EtcdClient = etcd.createEtcdClientAndInitializeStore(EtcdClient.Config(namespace2))

    awaitSuccessfulWrite(
      client2,
      "key",
      version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 42),
      "value2",
      previousVersionOpt = None,
      isIncremental = false
    )

    // Write a key with a KeyNamespace value that contains '%'.
    val namespace3 = EtcdClient.KeyNamespace("some%namespace")
    val client3: EtcdClient = etcd.createEtcdClientAndInitializeStore(EtcdClient.Config(namespace3))

    awaitSuccessfulWrite(
      client3,
      "key",
      version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 43),
      "value3",
      previousVersionOpt = None,
      isIncremental = false
    )

    // Write a key with a KeyNamespace value that contains '%', '/', and ':'.
    val namespace4 = EtcdClient.KeyNamespace("some%/:namespace")
    val client4: EtcdClient = etcd.createEtcdClientAndInitializeStore(EtcdClient.Config(namespace4))

    awaitSuccessfulWrite(
      client4,
      "key",
      version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 44),
      "value4",
      previousVersionOpt = None,
      isIncremental = false
    )
  }

  gridTest("Write (create/update) requires that keys are valid")(
    Seq(
      (Some(Version(ARBITRARY_NON_NEGATIVE_VALUE, 41)), Version(ARBITRARY_NON_NEGATIVE_VALUE, 42)),
      (None, Version(ARBITRARY_NON_NEGATIVE_VALUE, 42))
    )
  ) {
    case (previousVersionOpt: Option[Version], version: Version) =>
      // Test plan: verify that writes fail when the key contains illegal characters.
      val client: EtcdClient = etcd.createEtcdClientAndInitializeStore(EtcdClient.Config(NAMESPACE))

      // Starts with '__'.
      assertThrow[IllegalArgumentException]("contains illegal characters") {
        client.write(
          "__key",
          version,
          bytes("value1"),
          previousVersionOpt,
          isIncremental = false
        )
      }
  }

  test("Write (update) requires that the version is greater than the previous version") {
    // Test plan: Verify that writes require versions to be increasing.

    val client: EtcdClient = etcd.createEtcdClientAndInitializeStore(EtcdClient.Config(NAMESPACE))

    assertThrow[IllegalArgumentException]("Versions must increase") {
      client.write(
        "key",
        version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 42),
        bytes("value1"),
        previousVersionOpt = Some(Version(ARBITRARY_NON_NEGATIVE_VALUE, 42)),
        isIncremental = false
      )
    }
    assertThrow[IllegalArgumentException]("Versions must increase") {
      client.write(
        "key",
        version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 41),
        bytes("value1"),
        previousVersionOpt = Some(Version(ARBITRARY_NON_NEGATIVE_VALUE, 42)),
        isIncremental = false
      )
    }
    assertThrow[IllegalArgumentException]("Versions must increase") {
      client.write(
        "key",
        version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 0),
        bytes("value1"),
        previousVersionOpt = Some(Version(ARBITRARY_NON_NEGATIVE_VALUE, 0)),
        isIncremental = false
      )
    }
  }

  test("Write (create/update) returns DATA_LOSS if version high watermark doesn't exist") {
    // Test plan: Verify that writes both for new and existing keys return a DATA_LOSS status
    // exception if the version high watermark is missing from the store. Verify this as well for
    // writes which are updates (include a previous version in the write call) but where the key
    // actually doesn't exist, which also reads the version high watermark but through a
    // different code path. Verify this by explicitly deleting the watermark and attempting writes.

    val client: EtcdClient = etcd.createEtcdClientAndInitializeStore(EtcdClient.Config(NAMESPACE))

    // Delete the version high watermark.
    jetcdClient.getKVClient
      .delete(
        ByteSequence.from(f"$NAMESPACE/__version_high_watermark", UTF_8)
      )
      .get()

    // Verify that attempting to create a new key, or updating a key which doesn't exist, encounters
    // a DATA_LOSS StatusException with the expected message.
    for (previousVersionOpt <- Seq[Option[Version]](
        None,
        Some(Version(ARBITRARY_NON_NEGATIVE_VALUE, 41))
      )) {
      val future =
        client
          .write(
            "key",
            version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 42),
            bytes("value"),
            previousVersionOpt,
            isIncremental = false
          )
      val statusException = assertThrow[StatusException](
        """Data corruption: VersionHighWatermarkKey(path=[""" +
        NAMESPACE + """, __version_high_watermark], stringRep="""" +
        NAMESPACE + """/__version_high_watermark") is missing""".stripMargin
      ) {
        Await.result(future, Duration.Inf)
      }
      assert(statusException.getStatus.getCode == Status.DATA_LOSS.getCode)
      // Verify nothing was written to the store.
      assert(
        etcd.getPrefix(f"$NAMESPACE/key") == Map()
      )
    }
  }

  test("Write (create/update) returns DATA_LOSS if version high watermark is malformed") {
    // Test plan: Verify that writes for new keys return a DATA_LOSS status exception if the
    // version high watermark is corrupted. Verify this as well for writes which are updates
    // (include a previous version in the write call) but where the key actually doesn't exist,
    // which also reads the version high watermark but through a different code path. Verify this
    // by explicitly overwriting the watermark with an invalid value and attempting writes.

    val client: EtcdClient = etcd.createEtcdClientAndInitializeStore(EtcdClient.Config(NAMESPACE))

    // This is a bit white-boxy:
    // The version high watermark is only parsed in the 'else' clause of the write txns, i.e.
    // if the check fails. Any present version key will cause the check to fail for new keys, while
    // a mismatch between the version in the store and the specified predecessor will cause an
    // update to fail. To simulate these cases then, we can prep the store by writing some version
    // and fail to specify it as the predecessor when testing below.
    jetcdClient.getKVClient
      .put(
        ByteSequence.from(f"$NAMESPACE/key/version", UTF_8),
        ByteSequence.from(f"$ARBITRARY_NON_NEGATIVE_VALUE%016X/00000000000000EF", UTF_8)
      )
      .get()

    for (bogusVersionHighWatermark: ByteSequence <- generateMalformedVersions(
        isGlobalLogKey = false
      )) {
      logger.debug(s"$bogusVersionHighWatermark")
      jetcdClient.getKVClient
        .put(
          ByteSequence.from(f"$NAMESPACE/__version_high_watermark", UTF_8),
          bogusVersionHighWatermark
        )
        .get()

      val previousVersionOpts =
        Seq[Option[Version]](Some(Version(ARBITRARY_NON_NEGATIVE_VALUE, 41)), None)
      for (previousVersionOpt <- previousVersionOpts) {
        val future =
          client
            .write(
              "key",
              version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 42),
              bytes("value"),
              previousVersionOpt,
              isIncremental = false
            )
        val statusException = assertThrow[StatusException](
          "Data corruption: malformed version"
        ) {
          Await.result(future, Duration.Inf)
        }
        assert(statusException.getStatus.getCode == Status.DATA_LOSS.getCode)
      }
    }
  }

  test("Write (update) returns DATA_LOSS if version value is malformed") {
    // Test plan: Verify that writes fail when the version is malformed. Verify this by writing
    // malformed versions and attempting writes. Construct a set of malformed versions that exercise
    // different code paths / aspects of the regex pattern (a well-formed version value is a UTF-8
    // string with the format: "{16-digit hex number}/{16-digit hex number}").
    val client = etcd.createEtcdClientAndInitializeStore(EtcdClient.Config(NAMESPACE))
    for (bogusVersionValue: ByteSequence <- generateMalformedVersions(
        isGlobalLogKey = false
      )) {
      logger.debug(s"$bogusVersionValue")
      jetcdClient.getKVClient
        .put(
          ByteSequence.from(f"$NAMESPACE/key1/version", UTF_8),
          bogusVersionValue
        )
        .get()
      val future =
        client
          .write(
            "key1",
            version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 42),
            bytes("value1"),
            previousVersionOpt = None,
            isIncremental = false
          )
      val statusException = assertThrow[StatusException](f"Data corruption: malformed version") {
        Await.result(future, Duration.Inf)
      }
      assert(statusException.getStatus.getCode == Status.DATA_LOSS.getCode)
    }
  }

  test("Version must have non-negative high and low bits") {
    // Test plan: Verify that both components of the 128-bit version must be positive when
    // interpreted individually.
    Version(41, 42)
    assertThrow[IllegalArgumentException](
      "Version high and low bits must be non-negative"
    ) {
      Version(41, -42)
    }
    assertThrow[IllegalArgumentException](
      "Version high and low bits must be non-negative"
    ) {
      Version(-41, 42)
    }
    assertThrow[IllegalArgumentException](
      "Version high and low bits must be non-negative"
    ) {
      Version(-41, -42)
    }
  }

  /**
   * Tests that [[EtcdClient.read()]] returns a 'DATA_LOSS' StatusException on a series of malformed
   * keys. If `isGlobalKeyLey` is true, keys have a tag appended to the end to simulate a global log
   * key, otherwise they do not (see [[generateMalformedVersionedKeys()]]).
   */
  private def testReadMalformedKey(isGlobalLogKey: Boolean): Unit = {
    val client: EtcdClient = etcd.createEtcdClientAndInitializeStore(EtcdClient.Config(NAMESPACE))
    for (tuple <- generateMalformedVersionedKeys(NAMESPACE, isGlobalLogKey)) {
      val (key, bogusVersionedKey): (String, ByteSequence) = tuple
      logger.debug(s"$bogusVersionedKey")
      jetcdClient.getKVClient
        .put(
          bogusVersionedKey,
          byteSequence("opaque value")
        )
        .get()

      val future: Future[ReadResponse] = client.read(key)
      val statusException = assertThrow[StatusException](
        f"Data corruption: malformed key"
      ) {
        Await.result(future, Duration.Inf)
      }
      assert(statusException.getStatus.getCode == Status.DATA_LOSS.getCode)
    }
  }

  test("Read of malformed versioned key") {
    // Test plan: Verify that `read` returns the expected error when it encounters a malformed
    // versioned key.
    testReadMalformedKey(false)
  }

  test("Read of malformed global log key") {
    // Test plan: Verify that `read` returns the expected error when it encounters a malformed
    // global log key.
    testReadMalformedKey(true)
  }

  test("Watch watches values") {
    // Test plan: Verify that a watcher sees the initial values and then sees subsequent values as
    // they are written. Verify this by writing some values to the store and checking that they are
    // observed.

    val client: EtcdClient = etcd.createEtcdClientAndInitializeStore(EtcdClient.Config(NAMESPACE))

    // Write initial values.
    awaitSuccessfulWrite(
      client,
      "key",
      version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 1),
      "value1",
      previousVersionOpt = None,
      isIncremental = false
    )
    awaitSuccessfulWrite(
      client,
      "key",
      version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 2),
      "value2",
      previousVersionOpt = Some(Version(ARBITRARY_NON_NEGATIVE_VALUE, 1)),
      isIncremental = true
    )
    // Start watching.
    val callback = new LoggingStreamCallback[WatchEvent](sec)
    val watchHandle = client.watch(WatchArgs("key"), callback)

    // Verify that the watcher sees the initial values followed by a `Causal` event.
    val expectedElements = new mutable.ArrayBuffer[StatusOr[WatchEvent]]()
    expectedElements += StatusOr.success(
      WatchEvent.VersionedValue(
        Version(ARBITRARY_NON_NEGATIVE_VALUE, 1),
        bytes("value1")
      )
    )
    expectedElements += StatusOr.success(
      WatchEvent.VersionedValue(Version(ARBITRARY_NON_NEGATIVE_VALUE, 2), bytes("value2"))
    )
    expectedElements += StatusOr.success(WatchEvent.Causal)
    AssertionWaiter("Wait for value1, value2").await {
      assert(callback.getLog == expectedElements)
    }

    // Write a new value and verify that the watcher sees it.
    awaitSuccessfulWrite(
      client,
      "key",
      version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 3),
      "value3",
      previousVersionOpt = Some(Version(ARBITRARY_NON_NEGATIVE_VALUE, 2)),
      isIncremental = true
    )
    expectedElements += StatusOr.success(
      WatchEvent.VersionedValue(
        Version(ARBITRARY_NON_NEGATIVE_VALUE, 3),
        bytes("value3")
      )
    )
    AssertionWaiter("Wait for value3").await {
      assert(callback.getLog == expectedElements)
    }

    // Cancel the watch and verify that the watcher sees the cancellation.
    val cancelStatus = Status.CANCELLED.withDescription("my test")
    watchHandle.cancel(cancelStatus)
    expectedElements += StatusOr.error(cancelStatus)

    AssertionWaiter("Wait for cancellation").await {
      assert(callback.getLog == expectedElements)
    }
  }

  test("Watch with paging of initial read") {
    // Test plan: Verify that a watcher sees all values even when there are enough versions that
    // they cannot be returned in a single page. Verify this by writing enough versions for a key
    // that they cannot be returned in a single page, watching the key, and verifying that all
    // values are returned.

    val client: EtcdClient = etcd.createEtcdClientAndInitializeStore(EtcdClient.Config(NAMESPACE))
    val pageLimit: Int = 10

    for (numVersions <- Seq(
        pageLimit - 1,
        pageLimit,
        pageLimit + 1,
        pageLimit * 2,
        pageLimit * 2 + 1,
        pageLimit * 3 - 1,
        pageLimit * 3
      )) {
      val expectedElements = new mutable.ArrayBuffer[StatusOr[WatchEvent]]()
      val key = s"key$numVersions"
      var previousVersionOpt: Option[Version] = None
      for (i <- 0 until numVersions) {
        val version: Version = previousVersionOpt match {
          case None =>
            // Writes for new keys will unconditionally advance the version high watermark, so
            // we must read the next safe version for each new key as it advances.
            val newKeyVersionLowerBoundExclusive: Version = EtcdKeyValueMapper.parseVersionValue(
              ByteSequence.from(readVersionHighWatermark(NAMESPACE), UTF_8)
            )
            val nextLowBits = newKeyVersionLowerBoundExclusive.lowBits.value + 1
            newKeyVersionLowerBoundExclusive.copy(lowBits = nextLowBits)
          case Some(version) => version.copy(lowBits = version.lowBits.value + 1)
        }
        val value = s"value$i"
        EtcdTestUtils.retryOnceOnWatermarkError {
          awaitSuccessfulWrite(
            client,
            key,
            version,
            value,
            previousVersionOpt,
            isIncremental = previousVersionOpt.isDefined
          )
        }
        expectedElements += StatusOr.success(
          WatchEvent.VersionedValue(version, bytes(value))
        )
        previousVersionOpt = Some(version)
      }
      // Verify that `watch` sees all initial versions followed by a `Causal` event.
      val callback = new LoggingStreamCallback[WatchEvent](sec)
      val watchHandle = client.watch(WatchArgs(key, pageLimit = pageLimit), callback)
      expectedElements += StatusOr.success(WatchEvent.Causal)
      AssertionWaiter("Wait for initial versions").await {
        assert(callback.getLog == expectedElements)
      }

      // Verify that subsequent versions are seen by the watcher as well.
      val version = previousVersionOpt.get.copy(lowBits = previousVersionOpt.get.lowBits.value + 1)
      val values = s"bonus value"
      awaitSuccessfulWrite(
        client,
        key,
        version,
        values,
        previousVersionOpt,
        isIncremental = previousVersionOpt.isDefined
      )
      expectedElements += StatusOr.success(
        WatchEvent.VersionedValue(version, bytes(values))
      )
      AssertionWaiter("Wait for subsequent versions").await {
        assert(callback.getLog == expectedElements)
      }
      watchHandle.cancel(Status.CANCELLED)
    }
  }

  test("Watch with empty initial state") {
    // Test plan: Verify that a watcher of a key with no initial state sees a `Causal` event
    // followed by `VersionedValue` events as values are written.

    val client: EtcdClient = etcd.createEtcdClientAndInitializeStore(EtcdClient.Config(NAMESPACE))
    val callback = new LoggingStreamCallback[WatchEvent](sec)
    val watchHandle = client.watch(WatchArgs("key"), callback)
    val expectedElements = new mutable.ArrayBuffer[StatusOr[WatchEvent]]()

    // Wait for the watcher to see the initial `Causal` event.
    expectedElements += StatusOr.success(WatchEvent.Causal)
    AssertionWaiter("Wait for the initial causal event").await {
      assert(callback.getLog == expectedElements)
    }

    // Write a value and verify that the watcher sees it.
    awaitSuccessfulWrite(
      client,
      "key",
      version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 42),
      "value",
      previousVersionOpt = None,
      isIncremental = false
    )
    expectedElements += StatusOr.success(
      WatchEvent.VersionedValue(
        Version(ARBITRARY_NON_NEGATIVE_VALUE, 42),
        bytes("value")
      )
    )
    AssertionWaiter("Wait for the subsequent event").await {
      assert(callback.getLog == expectedElements)
    }

    // Cancel the watcher and verify that it sees the cancellation.
    watchHandle.cancel(Status.CANCELLED)
    expectedElements += StatusOr.error(Status.CANCELLED)
    AssertionWaiter("Wait for the cancellation").await {
      assert(callback.getLog == expectedElements)
    }
  }

  test("Watch with corrupted initial state") {
    // Test plan: Verify that the watcher sees an error when it encounters an existing corrupted
    // key before the watch begins.

    // Write a row with a bogus versioned key.
    val client: EtcdClient = etcd.createEtcdClientAndInitializeStore(EtcdClient.Config(NAMESPACE))
    jetcdClient.getKVClient
      .put(
        byteSequence(f"$NAMESPACE/key/$ARBITRARY_NON_NEGATIVE_VALUE%016X/1_not_a_version"),
        byteSequence("opaque value")
      )
      .get()

    // Start watching.
    val callback = new LoggingStreamCallback[WatchEvent](sec)
    client.watch(WatchArgs("key"), callback)
    AssertionWaiter("Wait for the bogus event").await {
      val log: Vector[StatusOr[WatchEvent]] = callback.getLog
      assert(log.size == 1)
      assert(log.head.status.getCode == Status.DATA_LOSS.getCode)
      assert(
        log.head.status.getDescription == "Data corruption: malformed key: " +
        f"$NAMESPACE/key/$ARBITRARY_NON_NEGATIVE_VALUE%016X/1_not_a_version"
      )
    }
  }

  test("Watch with corrupted update") {
    // Test plan: Verify that the watcher sees an error when it encounters a corrupted key is
    // written after the watch begins.

    // Start watching.
    val client: EtcdClient = etcd.createEtcdClientAndInitializeStore(EtcdClient.Config(NAMESPACE))
    val callback = new LoggingStreamCallback[WatchEvent](sec)
    client.watch(WatchArgs("key"), callback)
    AssertionWaiter("Wait for the initial causal event").await {
      assert(callback.getLog == Vector(StatusOr.success(WatchEvent.Causal)))
    }

    // Write a row with a bogus versioned key.
    jetcdClient.getKVClient
      .put(
        byteSequence(f"$NAMESPACE/key/$ARBITRARY_NON_NEGATIVE_VALUE%016X/1_not_a_version"),
        byteSequence("opaque value")
      )
      .get()

    // Verify that the watcher sees the error.
    AssertionWaiter("Wait for the subsequent bogus event").await {
      val log: Vector[StatusOr[WatchEvent]] = callback.getLog
      assert(log.size == 2)
      assert(log.last.status.getCode == Status.DATA_LOSS.getCode)
      assert(
        log.last.status.getDescription == "Data corruption: malformed key: " +
        f"$NAMESPACE/key/$ARBITRARY_NON_NEGATIVE_VALUE%016X/1_not_a_version"
      )
    }
  }

  test("Watch with failure during initial read") {
    // Test plan: Verify that failures returned from etcd during the initial read for a watch
    // request surface as a `Status` error, converted from the etcd shaded gRPC library type to the
    // version of `Status` used in the rest of Dicer.
    val fakeClock = new FakeTypedClock

    val (client, jetcdWrapper): (EtcdClient, InterposingJetcdWrapper) =
      etcd.createEtcdClientWithInterposingJetcdWrapper(
        fakeClock,
        EtcdClient.Config(NAMESPACE)
      )
    Await.result(
      client.initializeVersionHighWatermarkUnsafe(Version(ARBITRARY_NON_NEGATIVE_VALUE, 0)),
      Duration.Inf
    )
    val error = JetcdStatus.INTERNAL.withDescription("foo")
    jetcdWrapper.startFailingGets(new StatusRuntimeException(error))

    // Start watching.
    val callback = new LoggingStreamCallback[WatchEvent](sec)
    client.watch(WatchArgs("key"), callback)
    AssertionWaiter("Wait for the injected failure to occur").await {
      val log: Vector[StatusOr[WatchEvent]] = callback.getLog
      assert(log.size == 1)
      assert(log.head.status.getCode == Status.Code.INTERNAL)
      assert(log.head.status.getDescription == "foo")
    }
  }

  test("Watch with failure after successful watch") {
    // Test plan: Verify that failures returned from etcd after the watch has been successfully
    // established surface as a `Status` error, converted from the etcd shaded gRPC library type to
    // the version of `Status` used in the rest of Dicer.

    val (client, jetcdWrapper): (EtcdClient, InterposingJetcdWrapper) =
      etcd.createEtcdClientWithInterposingJetcdWrapper(
        new FakeTypedClock,
        EtcdClient.Config(NAMESPACE)
      )
    Await.result(
      client.initializeVersionHighWatermarkUnsafe(
        Version(ARBITRARY_NON_NEGATIVE_VALUE, 0)
      ),
      Duration.Inf
    )
    // Start watching.
    val callback = new LoggingStreamCallback[WatchEvent](sec)
    client.watch(WatchArgs("key"), callback)

    // Wait for causal event
    val expectedLog = mutable.ArrayBuffer[StatusOr[WatchEvent]]()
    expectedLog.append(StatusOr.success(WatchEvent.Causal))
    AssertionWaiter("Wait for watch causal event").await {
      assert(callback.getLog == expectedLog)
    }

    // Perform a write
    val watchValue = "value1"
    awaitSuccessfulWrite(
      client,
      "key",
      version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 41),
      watchValue,
      previousVersionOpt = None,
      isIncremental = false
    )

    expectedLog.append(
      StatusOr.success(
        WatchEvent.VersionedValue(Version(ARBITRARY_NON_NEGATIVE_VALUE, 41), bytes(watchValue))
      )
    )
    AssertionWaiter("Wait for watch value event").await {
      assert(callback.getLog == expectedLog)
    }

    // Fail the established watch.
    val error = JetcdStatus.UNAVAILABLE.withDescription("foo")
    jetcdWrapper.failCurrentWatch(new StatusRuntimeException(error))

    // The watch failure should be visible eventually.
    AssertionWaiter("Wait for watch failure").await {
      assert(callback.getLog.last.status.getCode == Status.UNAVAILABLE.getCode)
      assert(callback.getLog.last.status.getDescription == "foo")
    }
  }

  test("Watch sees across different values for high bits") {
    // Test plan: Verify that a watcher does not treat versions with different high bits specially.
    // Verify this by writing incremental values with different high bits values and asserting
    // that the watcher receives them all.

    val client = etcd.createEtcdClientAndInitializeStore(EtcdClient.Config(NAMESPACE))
    val version0 = Version(4, 1)
    val version1 = Version(6, 1)
    awaitSuccessfulWrite(
      client,
      "key",
      version = version0,
      "value01",
      previousVersionOpt = None,
      isIncremental = false
    )
    EtcdTestUtils.retryOnceOnWatermarkError {
      awaitSuccessfulWrite(
        client,
        "key",
        version = version1,
        "value11",
        previousVersionOpt = Some(version0),
        isIncremental = true
      )
    }
    val callback = new LoggingStreamCallback[WatchEvent](sec)
    val watchHandle = client.watch(WatchArgs("key"), callback)

    // Verify that the watcher sees the initial values followed by a `Causal` event for values
    // across both high bits values.
    val expectedElements = new mutable.ArrayBuffer[StatusOr[WatchEvent]]()
    expectedElements += StatusOr.success(
      WatchEvent.VersionedValue(
        version0,
        bytes("value01")
      )
    )
    expectedElements += StatusOr.success(
      WatchEvent.VersionedValue(
        version1,
        bytes("value11")
      )
    )
    expectedElements += StatusOr.success(WatchEvent.Causal)
    AssertionWaiter("Wait for value01, value11").await {
      assert(callback.getLog == expectedElements)
    }

    // Write a new value with a third store incarnation and verify that the watcher receives it as
    // well.
    val version2 = Version(8, 1)
    EtcdTestUtils.retryOnceOnWatermarkError {
      awaitSuccessfulWrite(
        client,
        "key",
        version = version2,
        "value21",
        previousVersionOpt = Some(version1),
        isIncremental = true
      )
    }
    expectedElements += StatusOr.success(
      WatchEvent.VersionedValue(
        version2,
        bytes("value21")
      )
    )
    AssertionWaiter("Wait for value21").await {
      assert(callback.getLog == expectedElements)
    }

    // Cancel the watch and verify that the watcher sees the cancellation.
    val cancelStatus = Status.CANCELLED.withDescription("my test")
    watchHandle.cancel(cancelStatus)
    expectedElements += StatusOr.error(cancelStatus)

    AssertionWaiter("Wait for cancellation").await {
      assert(callback.getLog == expectedElements)
    }
  }

  test("InterposingJetcdWrapper allows watches to be paused and resumed") {
    // Test plan: Verify that the InterposingJetCdWrapper queues watch events after
    // `pauseCurrentWatch` is called and releases them and resumes normal delivery after
    // `resumeCurrentWatch` is called. Cause gets to fail once the watch is established to ensure
    // that backup polling does not give the state machine an alternate path to discovering updates.
    val sec = FakeSequentialExecutionContext.create("watcher")
    val (client, jetcdWrapper): (EtcdClient, InterposingJetcdWrapper) =
      etcd.createEtcdClientWithInterposingJetcdWrapper(sec.getClock, EtcdClient.Config(NAMESPACE))
    Await.result(client.initializeVersionHighWatermarkUnsafe(Version.MIN), Duration.Inf)

    // Start watching.
    val callback = new LoggingStreamCallback[WatchEvent](sec)
    val watchArgs = WatchArgs("key")
    val watchHandle = client.watch(watchArgs, callback)

    // Verify that the watcher sees the initial `Causal` event.
    val expectedElements = new mutable.ArrayBuffer[StatusOr[WatchEvent]]()
    expectedElements += StatusOr.success(WatchEvent.Causal)

    AssertionWaiter("Wait for Causal event").await {
      assert(callback.getLog == expectedElements)
    }

    // Pause delivery of watch events.
    jetcdWrapper.pauseCurrentWatch()

    // Cause gets to fail so that backup polling can't help.
    jetcdWrapper.startFailingGets(new StatusException(Status.UNAVAILABLE))

    // Write two values that won't be immediately delivered due to the pause.
    awaitSuccessfulWrite(
      client,
      "key",
      version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 1),
      "value1",
      previousVersionOpt = None,
      isIncremental = false
    )
    awaitSuccessfulWrite(
      client,
      "key",
      version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 2),
      "value2",
      previousVersionOpt = Some(Version(ARBITRARY_NON_NEGATIVE_VALUE, 1)),
      isIncremental = true
    )

    // The events supplied to the callback should not change because the watch is paused. Advance
    // the fake clock and sleep a bit to give the events a chance to be delivered, and verify that
    // no new events have been delivered.
    Await.result(sec.call {
      sec.getClock.advanceBy(watchArgs.backupPollingInterval)
    }, Duration.Inf)
    // Note that we generally discourage sleeping in tests, but in this case we're checking for a
    // "non-event". We choose a short duration here (100ms) which should be enough to cause this
    // test to fail with very high probability (except for during very slow test runs).
    Thread.sleep(100)
    assert(callback.getLog == expectedElements)

    // Now unpause delivery of watch events and make sure the queued events are delivered.
    jetcdWrapper.resumeCurrentWatch()

    expectedElements += StatusOr.success(
      WatchEvent.VersionedValue(
        Version(ARBITRARY_NON_NEGATIVE_VALUE, 1),
        bytes("value1")
      )
    )

    expectedElements += StatusOr.success(
      WatchEvent.VersionedValue(
        Version(ARBITRARY_NON_NEGATIVE_VALUE, 2),
        bytes("value2")
      )
    )
    AssertionWaiter("Wait for all values including value2 after watch is unpaused").await {
      assert(callback.getLog == expectedElements)
    }

    // Write one more new value and verify that the watcher sees it.
    awaitSuccessfulWrite(
      client,
      "key",
      version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 3),
      "value3",
      previousVersionOpt = Some(Version(ARBITRARY_NON_NEGATIVE_VALUE, 2)),
      isIncremental = true
    )
    expectedElements += StatusOr.success(
      WatchEvent.VersionedValue(
        Version(ARBITRARY_NON_NEGATIVE_VALUE, 3),
        bytes("value3")
      )
    )
    AssertionWaiter("Wait for value3").await {
      assert(callback.getLog == expectedElements)
    }

    watchHandle.cancel(Status.CANCELLED)
  }

  test("Watcher does backup polling in case underlying etcd watch is laggy.") {
    // Test plan:
    // - Verify that the watcher does backup polling, such that even if the watch is stuck, the
    //   watcher still delivers new watch events after the backup polling interval has passed.
    // - Verify that if the backup polling fails, then it is retried after an appropriate interval.
    // - Verify that the lagging watch gauge starts out at 0, is set to 1 when the watch is paused
    //   and lagging, and resets to 0 when the watch is resumed and allowed to catch up.
    val sec = FakeSequentialExecutionContext.create("watcher")
    val (client, jetcdWrapper): (EtcdClient, InterposingJetcdWrapper) =
      etcd.createEtcdClientWithInterposingJetcdWrapper(sec.getClock, EtcdClient.Config(NAMESPACE))
    Await.result(client.initializeVersionHighWatermarkUnsafe(Version.MIN), Duration.Inf)

    // Start watching.
    val callback = new LoggingStreamCallback[WatchEvent](sec)
    val watchArgs = WatchArgs("key")
    val watchHandle = client.watch(watchArgs, callback)

    // Verify that the watcher sees the initial `Causal` event.
    val expectedElements = new mutable.ArrayBuffer[StatusOr[WatchEvent]]()
    expectedElements += StatusOr.success(WatchEvent.Causal)

    AssertionWaiter("Wait for Causal event").await {
      assert(callback.getLog == expectedElements)
      // Initially the watch should not be lagging
      assert(getWatchLaggingGaugeValue("key") == 0.0)
    }

    // Pause delivery of watch events. Note however that we allow reads to continue to succeed so
    // that backup polling can work.
    jetcdWrapper.pauseCurrentWatch()

    // Write two values, advance time, and verify that the callback receives them, but only
    // after the backup polling interval has passed.
    awaitSuccessfulWrite(
      client,
      "key",
      version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 1),
      "value1",
      previousVersionOpt = None,
      isIncremental = false
    )
    awaitSuccessfulWrite(
      client,
      "key",
      version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 2),
      "value2",
      previousVersionOpt = Some(Version(ARBITRARY_NON_NEGATIVE_VALUE, 1)),
      isIncremental = true
    )

    // Advance to just before the backup polling interview and check to see that we haven't received
    // the new elements yet.
    sec.getClock.advanceBy(watchArgs.backupPollingInterval - 1.nanos)
    // Note that we generally discourage sleeping in tests, but in this case we're checking for a
    // "non-event". We choose a short duration here (100ms) which should be enough to cause this
    // test to fail with very high probability (except for during very slow test runs).
    Thread.sleep(100)
    assert(callback.getLog == expectedElements)

    // Advance the small additional time needed to reach the backup polling interval and verify
    // we now get the new elements.
    sec.getClock.advanceBy(1.nanos)
    expectedElements += StatusOr.success(
      WatchEvent.VersionedValue(
        Version(ARBITRARY_NON_NEGATIVE_VALUE, 1),
        bytes("value1")
      )
    )
    expectedElements += StatusOr.success(
      WatchEvent.VersionedValue(
        Version(ARBITRARY_NON_NEGATIVE_VALUE, 2),
        bytes("value2")
      )
    )

    AssertionWaiter("Wait for all values including value2 even though watch is paused").await {
      assert(callback.getLog == expectedElements)
      // The watch lagging gauge should now be set to 1.0 since the watch is lagging.
      assert(getWatchLaggingGaugeValue("key") == 1.0)
    }

    // Also cause reads to start failing and write another value, causing the callback not to
    // receive the new value from the next backup polling attempt. Allows reads to succeed again
    // and wait another polling interval and verify that the value now succeeds.
    jetcdWrapper.startFailingGets(new StatusException(Status.UNAVAILABLE))

    awaitSuccessfulWrite(
      client,
      "key",
      version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 3),
      "value3",
      previousVersionOpt = Some(Version(ARBITRARY_NON_NEGATIVE_VALUE, 2)),
      isIncremental = true
    )
    sec.getClock.advanceBy(watchArgs.backupPollingInterval)
    // Note that we generally discourage sleeping in tests, but in this case we're checking for a
    // "non-event". We choose a short duration here (100ms) which should be enough to cause this
    // test to fail with very high probability (except for during very slow test runs).
    Thread.sleep(100)
    assert(callback.getLog == expectedElements, "No new value can be received yet")

    // Stop failing reads, allow the backup polling interval to elapse, and verify we now see the
    // value.
    jetcdWrapper.stopFailingGets()
    sec.getClock.advanceBy(watchArgs.backupPollingInterval)
    expectedElements += StatusOr.success(
      WatchEvent.VersionedValue(Version(ARBITRARY_NON_NEGATIVE_VALUE, 3), bytes("value3"))
    )
    AssertionWaiter("Wait for value3 after reads are allowed to succeed again").await {
      assert(callback.getLog == expectedElements)
      // Still lagging!
      assert(getWatchLaggingGaugeValue("key") == 1.0)
    }

    // Unpause the watch and verify that the watch stops lagging.
    jetcdWrapper.resumeCurrentWatch()
    AssertionWaiter("Wait for watch to stop lagging after unpausing it").await {
      assert(getWatchLaggingGaugeValue("key") == 0.0)
    }

    watchHandle.cancel(Status.CANCELLED)
  }

  test("Watcher cancels watch after specified finite watch duration is exceeded.") {
    // Test plan: Verify that the watcher automatically cancels the watch after the configured watch
    // duration is exceeded, but no so sooner than that. Do this by verifying that the callback
    // observes an item written to the store just before the watch expires, and that the callback
    // receives a cancellation event after the watch duration has passed.
    val sec = FakeSequentialExecutionContext.create("watcher")
    val watchDuration: FiniteDuration = 5.minutes
    val (client, _): (EtcdClient, InterposingJetcdWrapper) =
      etcd.createEtcdClientWithInterposingJetcdWrapper(
        sec.getClock,
        EtcdClient.Config(NAMESPACE)
      )
    Await.result(client.initializeVersionHighWatermarkUnsafe(Version.MIN), Duration.Inf)

    // Start watching.
    val callback = new LoggingStreamCallback[WatchEvent](sec)
    client.watch(WatchArgs("key", watchDuration = watchDuration), callback)

    // Verify that the watcher sees the initial `Causal` event.
    val expectedElements = new mutable.ArrayBuffer[StatusOr[WatchEvent]]()
    expectedElements += StatusOr.success(WatchEvent.Causal)

    AssertionWaiter("Wait for Causal event").await {
      assert(callback.getLog == expectedElements)
    }

    // Advance to just before the watch duration interview and ensure that a newly added item is
    // reported to the callback by the watch.
    val justBeforeWatchDuration: FiniteDuration = watchDuration - 1.nano
    sec.getClock.advanceBy(justBeforeWatchDuration)
    awaitSuccessfulWrite(
      client,
      "key",
      version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 1),
      "value1",
      previousVersionOpt = None,
      isIncremental = false
    )
    expectedElements += StatusOr.success(
      WatchEvent.VersionedValue(Version(ARBITRARY_NON_NEGATIVE_VALUE, 1), bytes("value1"))
    )
    AssertionWaiter("Wait for value1 to be seen by callback").await {
      assert(callback.getLog == expectedElements)
    }

    // Advance the small additional time needed to reach the watch duration and verify that the
    // watch is cancelled.
    sec.getClock.advanceBy(watchDuration - justBeforeWatchDuration)
    expectedElements += StatusOr.error(Status.CANCELLED)
    AssertionWaiter("Wait for cancellation").await {
      assert(callback.getLog == expectedElements)
    }
  }

  test("Watcher never cancels watch with default, infinite watch duration.") {
    // Test plan: Verify that the watcher automatically cancels the watch after the configured watch
    // duration is exceeded, but no so sooner than that. Do this by verifying that the callback
    // observes an item written to the store just before the watch expires, and that the callback
    // receives a cancellation event after the watch duration has passed.
    val sec = FakeSequentialExecutionContext.create("watcher")
    val (client, _): (EtcdClient, InterposingJetcdWrapper) =
      etcd.createEtcdClientWithInterposingJetcdWrapper(
        sec.getClock,
        EtcdClient.Config(NAMESPACE)
      )
    Await.result(client.initializeVersionHighWatermarkUnsafe(Version.MIN), Duration.Inf)

    // Start watching.
    val callback = new LoggingStreamCallback[WatchEvent](sec)
    client.watch(WatchArgs("key"), callback)

    // Verify that the watcher sees the initial `Causal` event.
    val expectedElements = new mutable.ArrayBuffer[StatusOr[WatchEvent]]()
    expectedElements += StatusOr.success(WatchEvent.Causal)

    AssertionWaiter("Wait for Causal event").await {
      assert(callback.getLog == expectedElements)
    }

    // Advance the fake clock by a very long time and make sure the watch is not cancelled.
    sec.getClock.advanceBy(10.days)
    expectedElements += StatusOr.error(Status.CANCELLED)
    // Wait a short amount of real time to give asynchronous a cancellation a chance to happen or
    // // not.
    Thread.sleep(100)
    assert(!callback.getLog.contains(StatusOr.error(Status.CANCELLED)))
  }

  test("Write to new high bits requires predecessor with previous high bits value") {
    // Test plan: verify that the first write with a version with a new high bits value still
    // require the correct predecessor to succeed, even though the predecessor version has a lower
    // value in its high bits.

    val client: EtcdClient = etcd.createEtcdClientAndInitializeStore(EtcdClient.Config(NAMESPACE))
    val highBits0 = 10
    val highBits1 = 40

    val initialVersion = Version(highBits0, 1)
    awaitSuccessfulWrite(
      client,
      "key",
      initialVersion,
      "value01",
      previousVersionOpt = None,
      isIncremental = false
    )

    // Attempt to write in `highBits1`, naming previousVersionOpt = None, even though a
    // value exists with a version in `highBits0`, and expect that this fails with an
    // OccFailure.
    val writeResponse: WriteResponse = Await.result(
      client
        .write(
          "key",
          Version(highBits1, 1),
          bytes("value11"),
          previousVersionOpt = None,
          isIncremental = false
        ),
      Duration.Inf
    )
    writeResponse match {
      case WriteResponse.OccFailure(KeyState.Present(`initialVersion`, _)) =>
        ()
      case _ =>
        fail("Unexpected write response: " + writeResponse)
    }

    // The same write should succeed if the correct previous version is named.
    EtcdTestUtils.retryOnceOnWatermarkError {
      awaitSuccessfulWrite(
        client,
        "key",
        version = Version(highBits1, 1),
        "value11",
        previousVersionOpt = Some(initialVersion),
        isIncremental = false
      )
    }
  }

  test("Global log write adds entry to log") {
    // Test plan: Verify that when a global log entry is provided to `write()` that it is written to
    // the global log.

    val client: EtcdClient = etcd.createEtcdClientAndInitializeStore(EtcdClient.Config(NAMESPACE))

    awaitSuccessfulWrite(
      client,
      "target0",
      version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 1),
      "foo",
      previousVersionOpt = None,
      isIncremental = false,
      Some(GlobalLogEntry("__recall", bytes("target0-pod1")))
    )

    assert(
      etcd.getPrefix(f"$NAMESPACE/__recall") == Map(
        f"$NAMESPACE/__recall/$ARBITRARY_NON_NEGATIVE_VALUE%016X/0000000000000001/target0"
        -> "target0-pod1"
      )
    )
  }

  test("Read of global log writes") {
    // Test plan: Verify that `read` returns global log entries. Verify this by writing a series of
    // log entries and reading them back using `read`.

    val client: EtcdClient = etcd.createEtcdClientAndInitializeStore(EtcdClient.Config(NAMESPACE))
    val pageLimit = 10

    val numTargets: Int = 6
    val numVersions: Int = 3

    for (i <- 0 until numVersions) {
      for (j <- 0 until numTargets) {
        val value = s"key$j-0"
        awaitSuccessfulWrite(
          client,
          s"key$j",
          Version(j * 2 + 4, i + 1),
          "value",
          previousVersionOpt = if (i == 0) {
            None
          } else {
            Some(Version(j * 2 + 4, i))
          },
          isIncremental = false,
          Some(GlobalLogEntry("__recall", bytes(value)))
        )
      }
    }

    val future1: Future[ReadResponse] = client.read("__recall")
    val readResponse1: ReadResponse = Await.result(future1, Duration.Inf)
    assert(readResponse1.values.length == pageLimit)
    for (i <- 0 until pageLimit) {
      assert(
        readResponse1
          .values(i)
          .version == Version(
          i % numTargets * 2 + 4,
          i / numTargets + 1
        )
      )
      assert(readResponse1.values(i).globalLogApplicationKeyOpt.get == s"key${i % numTargets}")
      assert(readResponse1.values(i).value == bytes(s"key${i % numTargets}-0"))
    }
    assert(readResponse1.more)

    val remainingWrites = numTargets * numVersions - pageLimit

    val future2: Future[ReadResponse] =
      client.read("__recall", pageLimit, Some(readResponse1.resumeToken))
    val readResponse2: ReadResponse = Await.result(future2, Duration.Inf)
    assert(readResponse2.values.length == remainingWrites)
    for (i <- pageLimit until pageLimit + remainingWrites) {
      assert(
        readResponse2
          .values(i - pageLimit)
          .version == Version(
          i % numTargets * 2 + 4,
          i / numTargets + 1
        )
      )
      assert(
        readResponse2
          .values(i - pageLimit)
          .globalLogApplicationKeyOpt
          .get == s"key${i % numTargets}"
      )
      assert(readResponse2.values(i - pageLimit).value == bytes(s"key${i % numTargets}-0"))
    }
  }

  test("Read of global log writes encodes EtcdClient special characters") {
    // Test plan: Verify that `read` returns global log entries correctly when the namespace, keys,
    // and global log values contain EtcdClient special characters. Verify this by writing a series
    // of log entries with special characters used by EtcdClient and reading them back using `read`.
    val namespace = EtcdClient.KeyNamespace("namespace:/%")
    val client: EtcdClient = etcd.createEtcdClientAndInitializeStore(EtcdClient.Config(namespace))
    val pageLimit = 10

    val numTargets: Int = 6
    val numVersions: Int = 3

    for (i <- 0 until numVersions) {
      for (j <- 0 until numTargets) {
        val logValue = s"key$j-0%/:"
        awaitSuccessfulWrite(
          client,
          s"/%:-key$j",
          Version(j * 2 + 4, i + 1),
          "value",
          previousVersionOpt = if (i == 0) {
            None
          } else {
            Some(Version(j * 2 + 4, i))
          },
          isIncremental = false,
          Some(GlobalLogEntry("__recall", bytes(logValue)))
        )
      }
    }

    val future1: Future[ReadResponse] = client.read("__recall")
    val readResponse1: ReadResponse = Await.result(future1, Duration.Inf)
    assert(readResponse1.values.length == pageLimit)
    for (i <- 0 until pageLimit) {
      assert(
        readResponse1
          .values(i)
          .version == Version(
          i % numTargets * 2 + 4,
          i / numTargets + 1
        )
      )
      assert(readResponse1.values(i).globalLogApplicationKeyOpt.get == s"/%:-key${i % numTargets}")
      assert(readResponse1.values(i).value == bytes(s"key${i % numTargets}-0%/:"))
    }
    assert(readResponse1.more)

    val remainingWrites = numTargets * numVersions - pageLimit

    val future2: Future[ReadResponse] =
      client.read("__recall", pageLimit, Some(readResponse1.resumeToken))
    val readResponse2: ReadResponse = Await.result(future2, Duration.Inf)
    assert(readResponse2.values.length == remainingWrites)
    for (i <- pageLimit until pageLimit + remainingWrites) {
      assert(
        readResponse2
          .values(i - pageLimit)
          .version == Version(
          i % numTargets * 2 + 4,
          i / numTargets + 1
        )
      )
      assert(
        readResponse2
          .values(i - pageLimit)
          .globalLogApplicationKeyOpt
          .get == s"/%:-key${i % numTargets}"
      )
      assert(readResponse2.values(i - pageLimit).value == bytes(s"key${i % numTargets}-0%/:"))
    }
  }

  test("Watch on global log fails") {
    // Test plan: Verify that a watcher raises an exception when trying to watch the global log, as
    // it is not a valid watch target. Verify this by writing a value to a global log and attempting
    // to watch the log.
    val client: EtcdClient = etcd.createEtcdClientAndInitializeStore(EtcdClient.Config(NAMESPACE))

    // Write initial values.
    awaitSuccessfulWrite(
      client,
      "key",
      version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 1),
      "value",
      previousVersionOpt = None,
      isIncremental = false,
      Some(GlobalLogEntry("__recall", bytes("value1")))
    )

    // Start watching.
    val callback = new LoggingStreamCallback[WatchEvent](sec)
    client.watch(WatchArgs("__recall"), callback)

    AssertionWaiter("Wait for error").await {
      val log: Vector[StatusOr[WatchEvent]] = callback.getLog
      assert(log.size == 1)
      assert(log.last.status.getCode == Status.FAILED_PRECONDITION.getCode)
      assert(
        log.last.status.getDescription == "Expected versioned key but got global log versioned " +
        f"key: $NAMESPACE/__recall/$ARBITRARY_NON_NEGATIVE_VALUE%016X/0000000000000001/key"
      )
    }
  }

  test("Metric recorded for successful write operation latency") {
    // Test plan: Verify that metrics are recorded for successful write operations.
    val oldWriteCount = getDicerEtcdClientOpLatencyHistogramCount(
      "create",
      NAMESPACE,
      "success",
      OperationResult.SUCCESS
    )
    val client: EtcdClient = etcd.createEtcdClientAndInitializeStore(EtcdClient.Config(NAMESPACE))

    awaitSuccessfulWrite(
      client,
      "key",
      version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 1),
      "value1",
      previousVersionOpt = None,
      isIncremental = false
    )

    // Wait for the write metric to be recorded.
    AssertionWaiter("Waiting for write metric to be recorded").await {
      assert(
        getDicerEtcdClientOpLatencyHistogramCount(
          "create",
          NAMESPACE,
          "success",
          OperationResult.SUCCESS
        ) >= oldWriteCount + 1
      )
    }
  }

  test("Metric recorded for OCC failure write operation latency") {
    // Test plan: Verify that metrics are recorded for successful write operations.
    val oldWriteCount =
      getDicerEtcdClientOpLatencyHistogramCount(
        "create",
        NAMESPACE,
        "success",
        OperationResult.WRITE_OCC_FAILURE_KEY_PRESENT
      )
    val client: EtcdClient = etcd.createEtcdClientAndInitializeStore(EtcdClient.Config(NAMESPACE))

    // Write some initial value to the store so that the key exists.
    awaitSuccessfulWrite(
      client,
      "key",
      version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 41),
      "value",
      previousVersionOpt = None,
      isIncremental = false
    )

    // Attempt to write (create) the key with `previousVersionOpt = None`.
    awaitOccFailure(
      client
        .write(
          "key",
          version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 42),
          bytes("value"),
          previousVersionOpt = None,
          isIncremental = false
        )
    )

    // Wait for the write metric to be recorded.
    AssertionWaiter("Waiting for write metric to be recorded").await {
      assert(
        getDicerEtcdClientOpLatencyHistogramCount(
          "create",
          NAMESPACE,
          "success",
          OperationResult.WRITE_OCC_FAILURE_KEY_PRESENT
        ) >= oldWriteCount + 1
      )
    }
  }

  test("Metric recorded for failed write operation latency") {
    // Test plan: Verify that metrics are recorded for failed write operations with the expected
    // gRPC code.
    val oldWriteCount =
      getDicerEtcdClientOpLatencyHistogramCount(
        "create",
        NAMESPACE,
        "failure",
        OperationResult.FAILURE
      )
    val client: EtcdClient = etcd.createEtcdClientAndInitializeStore(EtcdClient.Config(NAMESPACE))
    val bogusVersionValue: ByteSequence = ByteSequence.from("nonsensical/version", UTF_8)
    jetcdClient.getKVClient
      .put(
        ByteSequence.from(f"$NAMESPACE/key1/version", UTF_8),
        bogusVersionValue
      )
      .get()
    val future =
      client
        .write(
          "key1",
          version = Version(ARBITRARY_NON_NEGATIVE_VALUE, 42),
          bytes("value1"),
          previousVersionOpt = None,
          isIncremental = false
        )
    assertThrow[StatusException](f"Data corruption: malformed version") {
      Await.result(future, Duration.Inf)
    }

    // Wait for the write metric to be recorded.
    AssertionWaiter("Waiting for write metric to be recorded").await {
      assert(
        getDicerEtcdClientOpLatencyHistogramCount(
          "create",
          NAMESPACE,
          "failure",
          OperationResult.FAILURE
        ) >= oldWriteCount + 1
      )
    }
  }

  test("initializeVersionHighWatermarkUnsafe writes requested version") {
    // Test plan: verify that `initializeVersionHighWatermarkUnsafe()` writes the requested
    // version to etcd if the watermark is absent from the store.
    val client: EtcdClient = etcd.createEtcdClient(EtcdClient.Config(NAMESPACE))
    val watermark = Version(2, 4)

    Await.result(client.initializeVersionHighWatermarkUnsafe(watermark), Duration.Inf)

    assert(
      readVersionHighWatermark(NAMESPACE)
        .contains("0000000000000002/0000000000000004")
    )
  }

  test("initializeVersionHighWatermarkUnsafe fails and does not overwrite existing watermark") {
    // Test plan: verify that `initializeVersionHighWatermarkUnsafe()` fails with ALREADY_EXISTS
    // when a version high watermark is already present and does not overwrite the existing
    // version.
    val client: EtcdClient = etcd.createEtcdClient(EtcdClient.Config(NAMESPACE))
    val watermark = Version(2, 4)
    Await.result(client.initializeVersionHighWatermarkUnsafe(watermark), Duration.Inf)
    assert(
      readVersionHighWatermark(NAMESPACE)
        .contains("0000000000000002/0000000000000004")
    )

    // Try to write another version and assert that a failure is reported.
    val differentWatermark = Version(2, 5)
    assertThrow[StatusException]("ALREADY_EXISTS") {
      Await.result(client.initializeVersionHighWatermarkUnsafe(differentWatermark), Duration.Inf)
    }

    // The original value should still be there.
    assert(
      readVersionHighWatermark(NAMESPACE)
        .contains("0000000000000002/0000000000000004")
    )
  }

  test(
    "initializeVersionHighWatermarkUnsafe fails with DATA_LOSS when the existing " +
    "watermark is corrupted"
  ) {
    // Test plan: verify that `initializeVersionHighWatermarkUnsafe()` fails with DATA_LOSS when
    // a version high watermark is present but is not a valid Version.
    val client: EtcdClient = etcd.createEtcdClient(EtcdClient.Config(NAMESPACE))
    val invalidWatermark: String = "not-a-version"
    jetcdClient.getKVClient
      .put(
        ByteSequence.from(s"$NAMESPACE/__version_high_watermark", UTF_8),
        ByteSequence.from(invalidWatermark, UTF_8)
      )
      .get()

    assertThrow[StatusException]("DATA_LOSS") {
      Await.result(
        client.initializeVersionHighWatermarkUnsafe(
          Version(2, 4)
        ),
        Duration.Inf
      )
    }

    // Should not have touched the original, corrupted value:
    assert(readVersionHighWatermark(NAMESPACE).contains(invalidWatermark))
  }

  test("EtcdWatcher does not request advance and ignores read failures after cancel") {
    // Test plan: verify that EtcdWatcher does not request an advance call if it's already been
    // cancelled, and that it also ignores delayed read failures after cancellation, emitting no
    // actions.
    import EtcdWatcher.EtcdWatcherStateMachine

    val stateMachine =
      new EtcdWatcherStateMachine(WatchArgs("key"), loggerPrefix = "")
    val watcherTestDriver = new TestStateMachineDriver[
      EtcdWatcherStateMachine.Event,
      EtcdWatcherStateMachine.DriverAction](
      stateMachine
    )

    watcherTestDriver.onAdvance(
      sec.getClock.tickerTime(),
      sec.getClock.instant()
    )

    // Get the watcher into a watching state by sending an empty read.
    watcherTestDriver.onEvent(
      sec.getClock.tickerTime(),
      sec.getClock.instant(),
      EtcdWatcherStateMachine.Event.ReadSucceeded(
        new GetResponse(
          RangeResponse.newBuilder().setMore(false).build(),
          ByteSequence.EMPTY
        )
      )
    )

    // Fail the watch, which should put us into a failed state.
    watcherTestDriver.onEvent(
      sec.getClock.tickerTime(),
      sec.getClock.instant(),
      EtcdWatcherStateMachine.Event.EtcdWatchFailed(Status.UNAVAILABLE)
    )

    // Even if we call the state machine again, it shouldn't request any more advances from the
    // driver.
    assert(
      watcherTestDriver
        .onAdvance(
          sec.getClock.tickerTime(),
          sec.getClock.instant()
        )
        .nextTickerTime
      == TickerTime.MAX
    )

    // If a delayed read failure occurs, the state machine ignores it and still does not schedule
    // another advance call.
    assert(
      watcherTestDriver
        .onEvent(
          sec.getClock.tickerTime(),
          sec.getClock.instant(),
          EtcdWatcherStateMachine.Event.ReadFailed(Status.UNAVAILABLE)
        ) ==
      StateMachineOutput[EtcdWatcherStateMachine.DriverAction](TickerTime.MAX, Seq.empty)
    )
  }

  test("Reads and writes scoped to namespace") {
    // Test plan: verify that reads and writes are within the scope of a namespace by writing some
    // data to multiple namespaces and checking that reads only return the data for the correct
    // namespace.
    val client1: EtcdClient =
      etcd.createEtcdClientAndInitializeStore(
        EtcdClient.Config(EtcdClient.KeyNamespace("namespace1"))
      )
    val client2: EtcdClient =
      etcd.createEtcdClientAndInitializeStore(
        EtcdClient.Config(EtcdClient.KeyNamespace("namespace2"))
      )

    val version1 = Version(ARBITRARY_NON_NEGATIVE_VALUE, 2)
    awaitSuccessfulWrite(
      client1,
      "key",
      version1,
      "value1",
      previousVersionOpt = None,
      isIncremental = false,
      globalLogEntryOpt = Some(GlobalLogEntry("__recall", bytes("log-value1")))
    )

    {
      // client1 should observe the data through reads.
      val readResponse: ReadResponse = Await.result(client1.read("key"), Duration.Inf)
      assert(readResponse.values.size == 1)
      assert(readResponse.values.head == VersionedValue(version1, bytes("value1")))

      val globalLogReadResponse: ReadResponse = Await.result(client1.read("__recall"), Duration.Inf)
      assert(globalLogReadResponse.values.size == 1)
      assert(
        globalLogReadResponse.values.head ==
        VersionedValue(version1, bytes("log-value1"), globalLogApplicationKeyOpt = Some("key"))
      )
    }

    {
      // But client2 should not, as it's pointed at a different namespace.
      val readResponse: ReadResponse = Await.result(client2.read("key"), Duration.Inf)
      assert(readResponse.values.isEmpty)

      val globalLogReadResponse: ReadResponse = Await.result(client2.read("__recall"), Duration.Inf)
      assert(globalLogReadResponse.values.isEmpty)
    }

    // Write something to client2's namespace for the same key. There should be no conflict.
    val version2 = Version(ARBITRARY_NON_NEGATIVE_VALUE, 420000000L)
    awaitSuccessfulWrite(
      client2,
      "key",
      version2,
      "value2",
      previousVersionOpt = None,
      isIncremental = false,
      globalLogEntryOpt = Some(GlobalLogEntry("__recall", bytes("log-value2")))
    )

    {
      // client1's reads should not have been affected.
      val readResponse: ReadResponse = Await.result(client1.read("key"), Duration.Inf)
      assert(readResponse.values.size == 1)
      assert(readResponse.values.head == VersionedValue(version1, bytes("value1")))

      val globalLogReadResponse: ReadResponse = Await.result(client1.read("__recall"), Duration.Inf)
      assert(globalLogReadResponse.values.size == 1)
      assert(
        globalLogReadResponse.values.head ==
        VersionedValue(version1, bytes("log-value1"), globalLogApplicationKeyOpt = Some("key"))
      )
    }

    {
      // But client2 should now observe the effects of its write.
      val readResponse: ReadResponse = Await.result(client2.read("key"), Duration.Inf)
      assert(readResponse.values.size == 1)
      assert(readResponse.values.head == VersionedValue(version2, bytes("value2")))

      val globalLogReadResponse: ReadResponse = Await.result(client2.read("__recall"), Duration.Inf)
      assert(globalLogReadResponse.values.size == 1)
      assert(
        globalLogReadResponse.values.head ==
        VersionedValue(version2, bytes("log-value2"), globalLogApplicationKeyOpt = Some("key"))
      )
    }
  }

  test("High version watermark is maintained and checked separately for each namespace") {
    // Verify that EtcdClient checks high version watermark on a per-namespace basic and allows
    // non-monotonic writes across different namespaces. Verify this by writing a value for a key
    // to namespace1 with a higher version, then writing the same key to namespace2 with a lower
    // version. The second write should succeed even if it writes a lower version than the first
    // write, because it happens in a separate namespace.

    val client1: EtcdClient =
      etcd.createEtcdClientAndInitializeStore(
        EtcdClient.Config(EtcdClient.KeyNamespace("namespace1"))
      )
    val client2: EtcdClient =
      etcd.createEtcdClientAndInitializeStore(
        EtcdClient.Config(EtcdClient.KeyNamespace("namespace2"))
      )

    val higherVersion = Version(ARBITRARY_NON_NEGATIVE_VALUE, 4)
    val lowerVersion = Version(ARBITRARY_NON_NEGATIVE_VALUE, 2)

    awaitSuccessfulWrite(
      client1,
      "key",
      higherVersion,
      "value1",
      previousVersionOpt = None,
      isIncremental = false,
      globalLogEntryOpt = None
    )

    awaitSuccessfulWrite(
      client2,
      "key",
      lowerVersion,
      "value2",
      previousVersionOpt = None,
      isIncremental = false,
      globalLogEntryOpt = None
    )
  }

  test("Timeout slow requests") {
    // Test plan: verify that requests to an unresponsive etcd are eventually timed out.

    val unresponsiveServer: DatabricksServerWrapper =
      ServerTestUtils.createUnresponsiveServer(port = 0)

    // Create a client whose timeout clock we can control and is pointed at the local unresponsive
    // server.
    val sec: FakeSequentialExecutionContext = FakeSequentialExecutionContext.create("timeout-test")
    val jetcdWrapper: JetcdWrapper = new JetcdWrapperImpl(
      sec,
      EtcdClient.createJetcdClient(
        etcdEndpoints = Seq(s"https://localhost:${unresponsiveServer.activePort()}"),
        tlsOptionsOpt = None
      )
    )
    val client: EtcdClient = EtcdClient.forTest(jetcdWrapper, EtcdClient.Config(NAMESPACE))

    // Kick off an async op, advance the clock to simulate a delay exceeding the internal timeout,
    // and verify that EtcdClient surfaces an error to the caller for both reads and writes.
    val readResponse: Future[ReadResponse] = client.read("key")
    sec.getClock.advanceBy(10.seconds)
    AssertionWaiter("read timeout").await {
      assertThrow[CompletionException]("DEADLINE_EXCEEDED") {
        Await.result(readResponse, Duration.Inf)
      }
    }

    val writeResponse: Future[WriteResponse] =
      client.write(
        "key",
        Version(ARBITRARY_NON_NEGATIVE_VALUE, 43),
        bytes("value3"),
        previousVersionOpt = Some(Version(ARBITRARY_NON_NEGATIVE_VALUE, 42)),
        isIncremental = true
      )
    sec.getClock.advanceBy(10.seconds)
    AssertionWaiter("write timeout").await {
      assertThrow[CompletionException]("DEADLINE_EXCEEDED") {
        Await.result(writeResponse, Duration.Inf)
      }
    }

    // Because of internal backup polling, unresponsive servers should also cause watches to time
    // out. watch() does not synchronously initiate a read; rather, a read is guaranteed to be
    // enqueued when watch() returns. To ensure that we sequence the clock advance after the read
    // is initiated, we must enqueue the clock advance on `sec` as well.
    val watchCallback: LoggingStreamCallback[WatchEvent] = new LoggingStreamCallback(sec)
    client.watch(WatchArgs("key"), watchCallback)
    sec.run { sec.getClock.advanceBy(10.seconds) }
    AssertionWaiter("watch timeout").await {
      val event: StatusOr[WatchEvent] = watchCallback.getLog.last
      assert(!event.isOk)
      assert(event.status.getDescription.contains("DEADLINE_EXCEEDED"))
    }
  }

  test("Client receives UNEXPECTED failure status for unexpected onComplete callback") {
    // Test plan: verify that if the client unexpectedly triggers an onComplete callback with
    // either a cancellation or failure callback, then this is converted to a watch failure with
    // an unexpected error, and that a CACHING_DEGRADED error is logged with
    // ETCD_CLIENT_UNEXPECTED_WATCH_FAILURE.
    val fakeClock = new FakeTypedClock

    val unexpectedWatchFailureCount: ChangeTracker[Int] = ChangeTracker(
      () =>
        MetricUtils.getPrefixLoggerErrorCount(
          Severity.DEGRADED,
          CachingErrorCode.ETCD_CLIENT_UNEXPECTED_WATCH_FAILURE,
          prefix = s"{namespace=$NAMESPACE, key=key}"
        )
    )

    val (client, jetcdWrapper): (EtcdClient, InterposingJetcdWrapper) =
      etcd.createEtcdClientWithInterposingJetcdWrapper(
        fakeClock,
        EtcdClient.Config(NAMESPACE)
      )
    Await.result(
      client.initializeVersionHighWatermarkUnsafe(Version(ARBITRARY_NON_NEGATIVE_VALUE, 0)),
      Duration.Inf
    )
    jetcdWrapper.startUnexpectedlyCompletingWatches()

    // Start watching.
    val callback = new LoggingStreamCallback[WatchEvent](sec)
    client.watch(WatchArgs("key"), callback)
    AssertionWaiter("Wait for the UNKNOWN failure to be reported").await {
      assert(callback.getLog.last.status.getCode == Status.Code.UNKNOWN)
    }
    assert(unexpectedWatchFailureCount.totalChange() == 1)
  }
}

package com.databricks.caching.util

import com.google.protobuf.ByteString
import com.databricks.caching.util.EtcdClient.{KeyNamespace, ScopedKey, Version}

import io.etcd.jetcd.ByteSequence

import java.nio.charset.StandardCharsets.UTF_8
import scala.util.matching.Regex

/**
 * Collection of methods that define the format of keys used in etcd, as well as some values
 * stored in etcd which are managed by the client.
 */
object EtcdKeyValueMapper {

  /**
   * The etcd key identifying the current "version high watermark" for a given EtcdClient namespace.
   */
  private val VERSION_HIGH_WATERMARK_KEY_STRING: String = "__version_high_watermark"

  /** Regular expressions for capturing components of etcd keys / values. */
  private val VERSION_VALUE_PATTERN: Vector[Regex] =
    Vector("^[0-9A-F]{16}$".r, "^[0-9A-F]{16}$".r)
  private val VERSIONED_KEY_PATTERN: Vector[Regex] =
    Vector("^.+?$".r, "^.+?$".r, "^[0-9A-F]{16}$".r, "^[0-9A-F]{16}$".r)
  private val GLOBAL_LOG_VERSIONED_KEY_PATTERN: Vector[Regex] =
    Vector("^.+?$".r, "^.+?$".r, "^[0-9A-F]{16}$".r, "^[0-9A-F]{16}$".r, "^.+?$".r)

  /**
   * Special characters which need to be encoded in etcd keys. The encoding uses standard
   * percent-encoding (https://en.wikipedia.org/wiki/Percent-encoding).
   *
   * Key namespaces and versions are separated in etcd keys using '/', so it is disallowed in
   * application keys to avoid ambiguous parsing.
   *
   * ':' has no special use now, but is reserved just in case we need it later.
   *
   * '%' is used to encode special characters.
   */
  private val KEY_ENCODING_MAP = Map[Char, String]('/' -> "%2F", ':' -> "%3A", '%' -> "%25")

  /**
   * Validates `applicationKey`, returning false if it starts with a double underscore.
   */
  private[util] def isAllowedInEtcdKey(applicationKey: String): Boolean = {
    !applicationKey.startsWith("__")
  }

  /**
   * Returns a fixed-width, order-preserving, and human-readable string representation of `value`
   * to be used as part of etcd keys.
   *
   * This formats the value using its hex representation, and padded with leading zeros to fit the
   * fixed width.
   */
  private def toFixedWidthString(value: Long): String = {
    f"$value%016X"
  }

  /** Returns the etcd key path used to store the version high watermark. */
  private def getVersionHighWatermarkKeyPath(namespace: KeyNamespace): KeyPath = {
    val scopedKey = ScopedKey(namespace, VERSION_HIGH_WATERMARK_KEY_STRING)
    KeyPath(Vector(scopedKey.namespace.value, scopedKey.key))
  }

  /** Returns the etcd key byte sequence used to store the version high watermark. */
  private[util] def getVersionHighWatermarkKeyBytes(namespace: KeyNamespace): ByteSequence = {
    getVersionHighWatermarkKeyPath(namespace).toEtcdKeyByteSequence
  }

  /** Returns a human-readable version of the etcd key path of the version high watermark. */
  private[util] def getVersionHighWatermarkKeyDebugString(namespace: KeyNamespace): String = {
    val keyPath = getVersionHighWatermarkKeyPath(namespace)
    s"""VersionHighWatermarkKey(path=${keyPath.toString}, stringRep="${keyPath.toKeyString}")"""
  }

  /**
   * Returns the etcd key path to use for the key-value pair storing the current version of the
   * given application key.
   *
   * For example, for a key foo, yields: KeyPath(Vector("target1", "foo", "version"))
   */
  private def getKeyVersionKeyPath(scopedKey: ScopedKey): KeyPath = {
    KeyPath(Vector(scopedKey.namespace.value, scopedKey.key, "version"))
  }

  /**
   * Returns the etcd key byte sequence to use for the key-value pair storing the current version
   * of the given application key.
   *
   * For example, for a key foo, yields: KeyPath(Vector("target1", "foo", "version"))
   */
  private[util] def getKeyVersionKeyBytes(scopedKey: ScopedKey): ByteSequence = {
    getKeyVersionKeyPath(scopedKey).toEtcdKeyByteSequence
  }

  /**
   * Returns the given `version` as a string to store in an etcd key-value pair.
   *
   * For example, given `Version(high=0xB1, low=0x18C82CB0BDE)`, yields:
   * "00000000000000B1/0000018C82CB0BDE"
   */
  private def getVersionValueString(version: Version): String = {
    val highBitsString = toFixedWidthString(version.highBits)
    val lowBitsString = toFixedWidthString(version.lowBits.value)
    s"$highBitsString/$lowBitsString"
  }

  /** Returns the given `version` as a byte sequence to store in an etcd key-value pair. */
  private[util] def getVersionValueBytes(version: Version): ByteSequence = {
    stringToBytes(getVersionValueString(version))
  }

  /**
   * Returns the etcd key path to use for the key-value pair storing the value of the
   * application key at the given `version`.
   *
   * For example for a key with `Version(high=0x2, low=0x18C82CB0BDE)`, yields:
   * KeyPath(Vector("target1", "foo", "0000000000000002", "0000018C82CB0BDE"))
   */
  private def getVersionedKeyKeyPath(scopedKey: ScopedKey, version: Version): KeyPath = {
    val highBitsString = toFixedWidthString(version.highBits)
    val lowBitsString = toFixedWidthString(version.lowBits.value)
    KeyPath(Vector(scopedKey.namespace.value, scopedKey.key, highBitsString, lowBitsString))
  }

  /**
   * Returns the etcd key byte sequence to use for the key-value pair storing the value of the
   * application key at the given `version`.
   */
  private[util] def getVersionedKeyKeyBytes(
      scopedKey: ScopedKey,
      version: Version): ByteSequence = {
    getVersionedKeyKeyPath(scopedKey, version).toEtcdKeyByteSequence
  }

  /**
   * Returns an etcd key (in bytes) that sorts after any valid versioned `scopedKey` (see
   * [[getVersionedKeyKeyPath()]])
   *
   * For example, for a scoped key `ns/target`, yields:
   * "ns/target/FFFFFFFFFFFFFFFF/FFFFFFFFFFFFFFFF"
   */
  private[util] def toVersionedKeyExclusiveLimitBytes(scopedKey: ScopedKey): ByteSequence = {
    val keyPath = KeyPath(Vector(scopedKey.namespace.value, scopedKey.key, "F" * 16, "F" * 16))
    keyPath.toEtcdKeyByteSequence
  }

  /**
   * Returns the etcd key (in bytes) to use for the key-value pair storing the value of the global
   * log entry in `logName` with the given `version` and `scopedKey`.
   *
   * For example for a log "__recall_log", scoped key "ns/target-foo", and key version
   * Version(high=0x2,0x18C82CB0BDE), yields:
   * "ns/__recall_log/0000000000000002/0000018C82CB0BDE/target-foo"
   */
  private[util] def getGlobalLogVersionedKeyBytes(
      logName: String,
      version: Version,
      scopedKey: ScopedKey): ByteSequence = {
    val highBitsString = toFixedWidthString(version.highBits)
    val lowBitsString = toFixedWidthString(version.lowBits.value)
    val keyPath =
      KeyPath(
        Vector(scopedKey.namespace.value, logName, highBitsString, lowBitsString, scopedKey.key)
      )
    keyPath.toEtcdKeyByteSequence
  }

  /**
   * Attempts inverse of [[getVersionValueBytes()]].
   *
   * @throws IllegalArgumentException if parsing failed.
   */
  @throws[IllegalArgumentException]
  private[util] def parseVersionValue(bytes: ByteSequence): Version = {
    val versionValuePath: Vector[String] = KeyPath.fromEtcdKeyByteSequence(bytes).components

    // The version value path should have 2 components: high bits, low bits. Check that the path
    // has the expected format and number of components before attempting to parse it.
    {
      if (versionValuePath.size != VERSION_VALUE_PATTERN.size) {
        // The version value path has the wrong number of components.
        None
      } else {
        // Check that each component matches the expected pattern.
        if (VERSION_VALUE_PATTERN.zip(versionValuePath).forall {
            case (pattern, value) =>
              pattern.pattern.matcher(value).matches()
          }) {
          Some(versionValuePath)
        } else {
          None
        }
      }
    } match {
      case Some(Vector(highBits, lowBits)) =>
        val highBitsLong: Long = parseVersionPieceHexString(highBits)
        val lowBitsLong: Long = parseVersionPieceHexString(lowBits)
        Version(highBitsLong, lowBitsLong)
      case _ =>
        throw new IllegalArgumentException(
          s"version value did not have the expected format: $versionValuePath"
        )
    }
  }

  /** Represents the parts of a parsed versioned key. See [[getVersionedKeyKeyBytes()]]. */
  private[util] case class ParsedVersionedKey(scopedKey: ScopedKey, version: Version)

  /**
   * Attempts inverse of [[getVersionedKeyKeyBytes()]].
   *
   * @throws IllegalArgumentException if parsing failed.
   */
  @throws[IllegalArgumentException]
  private[util] def parseVersionedKey(bytes: ByteSequence): ParsedVersionedKey = {
    val versionedKeyPath: Vector[String] = KeyPath.fromEtcdKeyByteSequence(bytes).components

    // The versioned key path should have 4 components: namespace, key, high bits, low bits. Check
    // that the key path has the expected format and number of components before attempting to
    // parse it.
    {
      if (versionedKeyPath.size != VERSIONED_KEY_PATTERN.size) {
        // The versioned key path has the wrong number of components.
        None
      } else {
        // Check that each component matches the expected pattern.
        if (VERSIONED_KEY_PATTERN.zip(versionedKeyPath).forall {
            case (pattern, value) => pattern.pattern.matcher(value).matches()
          }) {
          Some(versionedKeyPath)
        } else {
          None
        }
      }
    } match {
      case Some(Vector(namespace, key, highBitsString, lowBitsString)) =>
        val highBits: Long = parseVersionPieceHexString(highBitsString)
        val lowBits: Long = parseVersionPieceHexString(lowBitsString)
        ParsedVersionedKey(
          ScopedKey(EtcdClient.KeyNamespace(namespace), key),
          Version(highBits, lowBits)
        )
      case _ =>
        throw new IllegalArgumentException(
          s"versioned key did not have the expected format: $versionedKeyPath"
        )
    }
  }

  /**
   * Represents the parts of a parsed global log versioned key. See
   * [[getGlobalLogVersionedKeyBytes()]].
   */
  private[util] case class ParsedGlobalLogVersionedKey(
      logName: String,
      version: Version,
      applicationKey: ScopedKey)

  /**
   * Attempts inverse of [[getGlobalLogVersionedKeyBytes()]].
   *
   * @throws IllegalArgumentException if parsing failed.
   */
  @throws[IllegalArgumentException]
  private[util] def parseGlobalLogVersionedKey(bytes: ByteSequence): ParsedGlobalLogVersionedKey = {
    val globalLogVersionedKeyPath: Vector[String] =
      KeyPath.fromEtcdKeyByteSequence(bytes).components

    // The global log versioned key path should have 5 components: namespace, log name, high bits,
    // low bits, key. Check that the key path has the expected format and number of components
    // before attempting to parse it.
    {
      if (globalLogVersionedKeyPath.size != GLOBAL_LOG_VERSIONED_KEY_PATTERN.size) {
        // The global log versioned key path has the wrong number of components.
        None
      } else {
        // Check that each component matches the expected pattern.
        if (GLOBAL_LOG_VERSIONED_KEY_PATTERN.zip(globalLogVersionedKeyPath).forall {
            case (pattern, value) => pattern.pattern.matcher(value).matches()
          }) {
          Some(globalLogVersionedKeyPath)
        } else {
          None
        }
      }
    } match {
      case Some(Vector(namespace, logName, highBitsString, lowBitsString, key)) =>
        val highBits: Long = parseVersionPieceHexString(highBitsString)
        val lowBits: Long = parseVersionPieceHexString(lowBitsString)
        ParsedGlobalLogVersionedKey(
          logName,
          Version(highBits, lowBits),
          ScopedKey(EtcdClient.KeyNamespace(namespace), key)
        )
      case _ =>
        throw new IllegalArgumentException(
          s"global log versioned key did not have the expected format: $globalLogVersionedKeyPath"
        )
    }
  }

  /**
   * Converts the given UTF-8 bytes to string and decodes it to generate human-readable keys from
   * etcd keys.
   *
   * @throws IllegalArgumentException if bytes is not a valid UTF-8 string.
   */
  @throws[IllegalArgumentException]
  private[util] def keyBytesToDebugString(bytes: ByteSequence): String = {
    s"Etcd key: ${KeyPath.fromEtcdKeyByteSequence(bytes)}"
  }

  /**
   * Converts the given string to a UTF-8 encoded byte sequence. Note that the UTF-8 encoding
   * preserves ordering (i.e. the resulting byte sequences will always order in the same way as
   * the strings they encode).
   */
  private def stringToBytes(string: String): ByteSequence = {
    ByteSequence.from(string, UTF_8)
  }

  /**
   * Attempts inverse of [[toFixedWidthString()]].
   *
   * @throws IllegalArgumentException if parsing failed.
   */
  @throws[IllegalArgumentException]
  private def parseVersionPieceHexString(versionPieceString: String): Long = {
    try {
      java.lang.Long.parseUnsignedLong(versionPieceString, 16)
    } catch {
      case ex: NumberFormatException =>
        throw new IllegalArgumentException(
          s"Version was not a valid hexadecimal number: $versionPieceString",
          ex
        )
    }
  }

  /**
   * Represents a key path in etcd. The key path is a sequence of components, where each component
   * is a string. The key path is used to represent the hierarchy of keys in etcd.
   */
  private case class KeyPath(components: Vector[String]) {

    /** Returns a human-readable string representation of the key path. */
    override def toString: String = s"[${components.mkString(", ")}]"

    /**
     * Generates a byte sequence from the given key path. The path components are joined with '/'
     * and are encoded using [[toKeyString()]] to replace special characters in EtcdClient key
     * formats.
     *
     * Key path byte sequences must be created using this method to ensure that all keys are
     * encoded.
     */
    def toEtcdKeyByteSequence: ByteSequence = {
      stringToBytes(this.toKeyString)
    }

    /**
     * Encodes the key path to be used as an etcd key. The key path components are joined with '/'
     * and are encoded using percent-encoding to replace special characters reserved in etcd keys.
     *
     * This is an intermediate step to generate a byte sequence from the key path. Ideally, this
     * method should be private to the [[KeyPath]] and only used internally to generate byte
     * sequences. However, it is currently used in the [[ForTest]] object for testing purposes.
     */
    def toKeyString: String = {
      components
        .map { component: String =>
          component.flatMap { character: Char =>
            KEY_ENCODING_MAP.getOrElse(character, character.toString)
          }
        }
        .mkString("/")
    }
  }

  private object KeyPath {

    /**
     * Generates a key path from the given byte sequence. The given UTF-8 byte sequence is first
     * converted to a string, with the bytes being validated as UTF-8. The string is then decoded
     * using [[fromKeyString()]].
     *
     * @throws IllegalArgumentException if byteSequence is not a valid UTF-8 string.
     */
    @throws[IllegalArgumentException]
    def fromEtcdKeyByteSequence(byteSequence: ByteSequence): KeyPath = {
      // Use ByteString to validate that the bytes are valid UTF-8.
      val byteString = ByteString.copyFrom(byteSequence.getBytes)
      val keyString = if (byteString.isValidUtf8) {
        byteString.toStringUtf8
      } else {
        throw new IllegalArgumentException(
          s"bytes are not valid UTF-8: ${Bytes.toString(byteString)}"
        )
      }
      KeyPath.fromKeyString(keyString)
    }

    /**
     * Inverse the encoding done by [[KeyPath.toKeyString()]]. Decodes the given `key` to generate
     * human-readable keys from etcd keys.
     */
    private def fromKeyString(keyString: String): KeyPath = {
      val keyDecodingMap: Map[String, Char] = KEY_ENCODING_MAP.map(_.swap)

      // Split the key into the path components, and decode each component.
      val components: Vector[String] = keyString
        .split("/")
        .map { component: String =>
          val decodedComponent = new StringBuilder(component)
          var i = 0

          // Replace encoded characters with their decoded values.
          while (i < decodedComponent.length) {
            if (decodedComponent(i) == '%') {
              val encodedChar: String = decodedComponent.substring(i, i + 3)
              val decodedChar: String =
                keyDecodingMap.get(encodedChar).map((_: Char).toString).getOrElse(encodedChar)
              decodedComponent.replace(i, i + 3, decodedChar)
            }
            i += 1
          }
          decodedComponent.toString
        }
        .toVector
      KeyPath(components)
    }
  }

  object ForTest {

    // Methods below are exposed for testing to allow callers outside of //dicer/util to avoid
    // depending on the exact format of the keys and values that we store in etcd (which is an
    // implementation detail of EtcdClient) while still allowing direct access to what is stored.
    // This is unfortunately not perfect as the presence of these methods themselves already leaks
    // implementation details, but directing callers through these methods prevents us having to
    // update all tests if any of the formats were to change.

    /** See [[EtcdKeyValueMapper.getVersionHighWatermarkKeyPath]]. */
    def getVersionHighWatermarkKeyString(namespace: KeyNamespace): String =
      EtcdKeyValueMapper.getVersionHighWatermarkKeyPath(namespace).toKeyString

    /** See [[EtcdKeyValueMapper.getVersionValueString]]. */
    def toVersionValueString(version: Version): String =
      EtcdKeyValueMapper.getVersionValueString(version)

    /** See [[EtcdKeyValueMapper.getKeyVersionKeyPath]] */
    def toVersionKeyString(namespace: KeyNamespace, key: String): String =
      EtcdKeyValueMapper.getKeyVersionKeyPath(ScopedKey(namespace, key)).toKeyString

    /** See [[EtcdKeyValueMapper.getVersionedKeyKeyPath]]. */
    def toVersionedKeyString(namespace: KeyNamespace, key: String, version: Version): String = {
      EtcdKeyValueMapper.getVersionedKeyKeyPath(ScopedKey(namespace, key), version).toKeyString
    }
  }
}

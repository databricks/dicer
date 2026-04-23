package com.databricks.dicer.friend

import com.databricks.api.proto.dicer.friend.SliceP
import com.google.protobuf.ByteString
import com.databricks.dicer.common.SliceHelper
import com.databricks.dicer.common.SliceHelper._
import com.databricks.dicer.external.{Slice, SliceKey}

/*
 * Accessors for `Slice` and `SliceKey` that help with the conversion between [[Slice]],
 * [[SliceKey]] and their corresponding proto messages. These are exposed for Caching team use
 * (i.e. Softstore), with access restricted using Bazel visibility.
 */
object SliceAccessor {

  private val ZERO_BYTE = ByteString.copyFrom(Array[Byte](0))

  /** Converts a [[Slice]] to a proto message [[SliceP]]. */
  def toProto(slice: Slice): SliceP = {
    slice.toProto
  }

  /** Converts a [[SliceP]] to a [[Slice]] instance. */
  def fromProto(proto: SliceP): Slice = SliceHelper.fromProto(proto)

  /** Returns a [[Slice]] containing only the given `key`. */
  def createSingleKeySlice(key: SliceKey): Slice = {
    // The lowest-sorting key that is greater than any given key is that key with a trailing zero
    // byte appended.
    val successorKey: SliceKey = SliceKey.fromRawBytes(key.toRawBytes.concat(ZERO_BYTE))
    Slice(key, successorKey)
  }
}

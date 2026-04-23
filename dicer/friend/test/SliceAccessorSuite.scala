package com.databricks.dicer.friend

import com.databricks.dicer.external.{Slice, SliceKey}
import com.databricks.testing.DatabricksTest
import com.google.protobuf.ByteString

class SliceAccessorSuite extends DatabricksTest {
  test("createSingleKeySlice") {
    // Test plan: Verify that `createSingleKeySlice` returns a slice from the given key (inclusive)
    // to the minimum representable greater key (exclusive).
    val key = SliceKey.fromRawBytes(ByteString.copyFrom(Array[Byte](0x11, 0x22, 0x33, 0x44)))
    val singleKeySlice: Slice = SliceAccessor.createSingleKeySlice(key)

    assert(singleKeySlice.lowInclusive == key)
    assert(singleKeySlice.highExclusive.isFinite)
    assert(
      singleKeySlice.highExclusive.asFinite ==
      SliceKey.fromRawBytes(ByteString.copyFrom(Array[Byte](0x11, 0x22, 0x33, 0x44, 0x00)))
    )
  }
}

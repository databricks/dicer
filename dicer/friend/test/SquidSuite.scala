package com.databricks.dicer.friend

import com.databricks.api.proto.dicer.friend.SquidP
import com.databricks.dicer.external.ResourceAddress
import com.databricks.caching.util.TestUtils.{
  assertThrow,
  checkComparisons,
  checkEquality,
  loadTestData
}
import com.databricks.testing.DatabricksTest

import java.net.URI
import java.time.Instant
import java.util.UUID
import com.databricks.caching.util.{RealtimeTypedClock, TickerTime, TypedClock}
import com.databricks.dicer.common.test.SquidTestDataP
import com.databricks.dicer.common.test.SquidTestDataP.{EqualityGroupP, InvalidSquidTestCaseP}

class SquidSuite extends DatabricksTest {

  private val TEST_DATA: SquidTestDataP =
    loadTestData[SquidTestDataP]("dicer/common/test/data/squid_test_data.textproto")

  /** Some sample SQUIDs in expected sort order. */
  private val ORDERED_SQUIDS: Vector[Squid] = TEST_DATA.orderedSquids.map(Squid.fromProto).toVector

  test("Squid accessors") {
    // Test plan: verify that the resource address, UUID and creation time Instant supplied to the
    // Squid constructor are returned by accessors.
    val resourceAddress = ResourceAddress(URI.create("c"))
    val creationTime = RealtimeTypedClock.instant()
    val resourceUuid = UUID.randomUUID()
    val squid =
      Squid(resourceAddress, creationTime.toEpochMilli, resourceUuid)
    assert(squid.resourceAddress == resourceAddress)
    assert(squid.creationTimeMillis == creationTime.toEpochMilli)
    assert(squid.creationTime == creationTime)
    assert(squid.resourceUuid == resourceUuid)
  }

  test("Squid proto roundtrip") {
    // Test plan: verify that Squid instances roundtrip via toProto and fromProto.
    for (squid: Squid <- ORDERED_SQUIDS) {
      val proto: SquidP = squid.toProto
      val roundtrip: Squid = Squid.fromProto(proto)
      assert(squid == roundtrip)
      assert(squid.resourceAddress == roundtrip.resourceAddress)
      assert(squid.resourceUuid == roundtrip.resourceUuid)
    }
  }

  test("Squid proto roundtrip with nanosecond precision from clock") {
    // Test plan: verify that Squid instances roundtrip correctly even when the creation time
    // indicated by the clock has a non-zero sub-millisecond portion. (The creation time should be
    // truncated to millisecond precision to ensure this.)
    val clock = new TypedClock {
      override def instant(): Instant = Instant.ofEpochSecond(1234567890, 123456789)
      override def tickerTime(): TickerTime = TickerTime(1234567890123L)
    }
    val squid =
      Squid.createForNewIncarnation(clock, ResourceAddress(URI.create("c")), UUID.randomUUID())
    val proto: SquidP = squid.toProto
    val roundtrip: Squid = Squid.fromProto(proto)
    assert(squid == roundtrip)
    assert(squid.creationTimeMillis == roundtrip.creationTimeMillis)
  }

  test("Squid invalid proto") {
    // Test plan: verify the expected exceptions are thrown when deserializing invalid SquidP
    // protos.
    for (testCase: InvalidSquidTestCaseP <- TEST_DATA.invalidSquidTestCases) {
      assertThrow[IllegalArgumentException](testCase.getExpectedError) {
        Squid.fromProto(testCase.getSquid)
      }
    }
  }

  test("Squid compare") {
    // Test plan: verify that comparison operations work as expected for Squid instances.
    checkComparisons(ORDERED_SQUIDS)
  }

  test("Squid equality") {
    // Test plan: define groups of equivalent resources and verify that equals and hashCode work as
    // expected.
    checkEquality(
      TEST_DATA.squidEqualityGroups.map { group: EqualityGroupP =>
        group.squids.map(Squid.fromProto)
      }
    )
  }
}

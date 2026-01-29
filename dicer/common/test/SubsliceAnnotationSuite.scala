package com.databricks.dicer.common

import com.databricks.api.proto.dicer.common.DiffAssignmentP.SubsliceAnnotationP
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.friend.Squid
import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.caching.util.UnixTimeVersion
import com.databricks.testing.DatabricksTest

import java.time.Instant

class SubsliceAnnotationSuite extends DatabricksTest {
  test("SubsliceAnnotation proto validity and round-tripping") {
    // Test plan: Verify that a SubsliceAnnotation instance can be converted to proto and back, and
    // also verify SubsliceAnnotation.fromProto throws proper exceptions for invalid protos.

    // Create a valid SubsliceAnnotation instance and proto.
    val subsliceAnnotation =
      SubsliceAnnotation("Fili" -- "Kili", Instant.now().toEpochMilli, Some(Transfer(42, "Pod1")))
    val ownerResource: Squid = "Pod0"
    val resourceBuilder = new Assignment.ResourceProtoBuilder
    val subsliceAnnotationP: SubsliceAnnotationP =
      subsliceAnnotation.toProto(ownerResource, resourceBuilder)
    val resourceMap = Assignment.ResourceMap.fromProtos(resourceBuilder.toProtos)

    // Verify round-tripping.
    val (annotationAfterRoundTrip, ownerResourceAfterRoundTrip): (SubsliceAnnotation, Squid) =
      SubsliceAnnotation.fromProto(subsliceAnnotationP, resourceMap)
    assert(annotationAfterRoundTrip == subsliceAnnotation)
    assert(ownerResourceAfterRoundTrip == ownerResource)
    assert(
      annotationAfterRoundTrip.toProto(ownerResourceAfterRoundTrip, resourceBuilder) ==
      subsliceAnnotationP
    )

    // Tweak the valid SubsliceAnnotationP and verify exceptions.
    assertThrow[IllegalArgumentException]("Resource index 2 out of range.") {
      SubsliceAnnotation.fromProto(subsliceAnnotationP.withResourceId(2), resourceMap)
    }
  }

  test("SubsliceAnnotation generationToTime") {
    // Test plan: Verify that SubsliceAnnotation.generationToTime returns the value of the
    // generation number as an [[Instant]], assuming that the generation number tracks the number of
    // millis since the Unix epoch.

    val continuousGenerationInstant: Instant = Instant.now()
    val subsliceAnnotation =
      SubsliceAnnotation(
        "Fili" -- "Kili",
        continuousGenerationInstant.toEpochMilli,
        stateTransferOpt = None
      )
    assert(
      subsliceAnnotation.generationToTime.toEpochMilli == continuousGenerationInstant.toEpochMilli
    )
  }

  test("SubsliceAnnotation toString") {
    // Test plan: Verify the toString method of SubsliceAnnotation. Verify this by creating a
    // SubsliceAnnotation instance with and without a state transfer, and verifying the result.
    val slice = "" -- "Kili"
    val timeVersion = UnixTimeVersion(42)
    assertResult(s"""$slice:$timeVersion""")(SubsliceAnnotation(slice, timeVersion, None).toString)

    val transfer: Transfer = Transfer(13, "Pod1")
    assertResult(s"""$slice:$timeVersion, state provider: ${transfer.fromResource}""")(
      SubsliceAnnotation(slice, timeVersion, Some(transfer)).toString
    )
  }
}

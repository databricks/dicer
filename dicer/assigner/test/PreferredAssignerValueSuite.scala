package com.databricks.dicer.assigner

import com.databricks.testing.DatabricksTest
import com.databricks.api.proto.dicer.assigner.{
  AssignerInfoP,
  HeartbeatRequestP,
  HeartbeatResponseP,
  PreferredAssignerSpecP,
  PreferredAssignerValueP
}
import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.dicer.common.{Generation, Incarnation, Redirect}

import java.util.UUID
import java.net.URI

class PreferredAssignerValueSuite extends DatabricksTest {

  test("PreferredAssigner.toProto and fromProto round-tripping") {
    // Test plan: Verify `PreferredAssigner.toProto` and `PreferredAssigner.fromProto` are
    // inverses of each other. To do this, we generate a random `PreferredAssigner` instance,
    // convert it to a proto via `toProto` and then use the `fromProto` to convert it back to an
    // `PreferredAssigner` instance and verify that the two instances should be equivalent.
    // We also test that None preferred assigner can be converted to proto and back.
    val assignerInfo =
      AssignerInfo(java.util.UUID.randomUUID(), new java.net.URI("http://localhost:8080"))
    val generation = Generation(Incarnation(42), 39)

    for (value: PreferredAssignerValue <- Seq(
        PreferredAssignerValue.SomeAssigner(assignerInfo, generation),
        PreferredAssignerValue.NoAssigner(generation),
        PreferredAssignerValue.ModeDisabled(generation)
      )) {
      assert(value == PreferredAssignerValue.fromProto(value.toProto))

      // Also test toSpecProto and the fromProto method that takes the spec and generation.
      val specP: PreferredAssignerSpecP = value.toSpecProto
      assert(value == PreferredAssignerValue.fromProto(specP, value.generation))
    }
  }

  test("AssignerInfo.fromProto throws with invalid proto") {
    // Test plan: verify that `AssignerInfo.fromProto` throws exceptions when given protos with
    // invalid fields.

    // Construct a valid assigner infoP and then permute individual fields to make them invalid.
    val uuid = java.util.UUID.randomUUID()
    val uri = new java.net.URI("http://localhost:8080")
    val validAssignerInfoP = AssignerInfoP(
      uuidHigh = Some(uuid.getMostSignificantBits),
      uuidLow = Some(uuid.getLeastSignificantBits),
      uri = Some(uri.toString)
    )

    for (invalidAssignerInfoP: AssignerInfoP <- Seq(
        validAssignerInfoP.clearUuidHigh.clearUuidLow,
        validAssignerInfoP.clearUri,
        validAssignerInfoP.withUuidLow(0).withUuidHigh(0),
        validAssignerInfoP.withUri(""),
        validAssignerInfoP.withUri("not a URI")
      )) {
      assertThrows[IllegalArgumentException] {
        logger.info("Testing invalid assigner info: " + invalidAssignerInfoP)
        AssignerInfo.fromProto(invalidAssignerInfoP)
      }
    }
  }

  test("AssignerInfo.toString") {
    // Test plan: verify that AssignerInfo.toString returns a readable string which matches the
    // expectation.
    val uuid = java.util.UUID.randomUUID()
    val uri = new java.net.URI("http://localhost:8080")
    val assignerInfo = AssignerInfo(uuid, uri)
    assert(assignerInfo.toString == s"(uuid: $uuid, uri: $uri)")
  }

  test("Default PreferredAssignerValueP is valid") {
    // Test plan: verify that the default `PreferredAssignerValueP` is valid, and it's equivalent
    // to `PreferredAssignerValue.ModeDisabled` with `Generation.Empty`.
    val defaultPreferredAssignerValueP = PreferredAssignerValueP()
    val expectedPreferredAssignerValue = PreferredAssignerValue.ModeDisabled(Generation.EMPTY)
    assert(
      PreferredAssignerValue.fromProto(defaultPreferredAssignerValueP) ==
      expectedPreferredAssignerValue
    )
  }

  test("PreferredAssignerValue.fromProto throws with invalid proto") {
    // Test plan: verify that `PreferredAssignerValue.fromProto` throws exceptions when given protos
    // with invalid fields.
    val generation = Generation(Incarnation(42), 39)
    val uuid = java.util.UUID.randomUUID()
    val uri = new java.net.URI("http://localhost:8080")
    val validAssignerInfoP = AssignerInfoP(
      uuidHigh = Some(uuid.getMostSignificantBits),
      uuidLow = Some(uuid.getLeastSignificantBits),
      uri = Some(uri.toString)
    )
    val validSpecP = PreferredAssignerSpecP(
      value = PreferredAssignerSpecP.Value.AssignerInfo(validAssignerInfoP)
    )
    val validValueP = PreferredAssignerValueP(
      Some(validSpecP),
      generation = Some(generation.toProto)
    )
    for (invalidValueP: PreferredAssignerValueP <- Seq(
        // Bad uri in the nested assigner info
        validValueP.withPreferredAssigner(
          validValueP.getPreferredAssigner.withAssignerInfo(
            validValueP.getPreferredAssigner.getAssignerInfo.withUri("bad uri")
          )
        ),
        // Bad UUID in the nested assigner info
        validValueP.withPreferredAssigner(
          validValueP.getPreferredAssigner.withAssignerInfo(
            validValueP.getPreferredAssigner.getAssignerInfo.withUuidHigh(0).withUuidLow(0)
          )
        )
      )) {
      assertThrows[IllegalArgumentException] {
        PreferredAssignerValue.fromProto(invalidValueP)
      }
    }
  }

  test("HeartbeatRequest.fromProto and toProto") {
    // Test plan: verify that `HeartbeatRequest.fromProto` validates the request proto and the
    // toProto method generates the correct request proto. Additionally, verify that two methods
    // round-trip correctly.
    val opId: Long = 45L
    val assignerInfo =
      AssignerInfo(java.util.UUID.randomUUID(), new java.net.URI("http://localhost:8080"))
    val generation = Generation(Incarnation(42), 39)

    val somePreferredAssigner = PreferredAssignerValue.SomeAssigner(assignerInfo, generation)
    val noAssigner = PreferredAssignerValue.NoAssigner(generation)
    val modeDisabled = PreferredAssignerValue.ModeDisabled(generation)

    // Verify the `fromProto` throws with an invalid request proto - no or negative opId.
    assertThrows[IllegalArgumentException] {
      HeartbeatRequest.fromProto(HeartbeatRequestP())
    }
    assertThrows[IllegalArgumentException] {
      HeartbeatRequest.fromProto(HeartbeatRequestP().withOpId(-1))
    }

    // Verify that empty preferredAssignerValue proto is not allowed, because it is required
    // to have a [[PreferredAssignerValue.SomeAssigner]] but empty proto implies ModeDisabled.
    assertThrow[IllegalArgumentException]("must be `SomeAssigner`") {
      HeartbeatRequest.fromProto(HeartbeatRequestP().withOpId(18))
    }

    // Verify that `HeartbeatRequest.fromProto` requires the preferred assigner value to be
    // `PreferredAssignerValue.SomeAssigner`.
    for (preferredAssignerValue: PreferredAssignerValue <- Seq(noAssigner, modeDisabled)) {
      assertThrow[IllegalArgumentException]("must be `SomeAssigner`") {
        HeartbeatRequest.fromProto(
          HeartbeatRequestP()
            .withOpId(18)
            .withPreferredAssignerValue(preferredAssignerValue.toProto)
        )
      }
    }

    // Construct a valid request proto and verify the fields and round-trip.
    val request = HeartbeatRequest(opId, somePreferredAssigner)
    assert(request == HeartbeatRequest.fromProto(request.toProto))
    assert(request.toProto.opId.get == opId)
    assert(request.toProto.preferredAssignerValue.get == somePreferredAssigner.toProto)

    assert(HeartbeatRequest.fromProto(request.toProto) == request)
  }

  test("HeartbeatResponse.fromRequestProto and HeartbeatResponse.toProto") {
    // Test plan: verify that `HeartbeatResponse.Proto` validates the heartbeat response proto and
    // the toProto method generates the correct response proto. Additionally, verify that two
    // methods round-trip correctly.
    val preferredAssignerModeDisabled = PreferredAssignerValue.ModeDisabled(
      Generation(Incarnation(42), 39)
    )
    val noPreferredAssigner = PreferredAssignerValue.NoAssigner(
      Generation(Incarnation(42), 39)
    )
    val somePreferredAssigner = PreferredAssignerValue.SomeAssigner(
      AssignerInfo(java.util.UUID.randomUUID(), new java.net.URI("http://localhost:8080")),
      Generation(Incarnation(42), 39)
    )

    // Verify the `fromRequestProto` throws with an invalid request proto - no opId specified.
    assertThrows[IllegalArgumentException] {
      HeartbeatResponse.fromProto(HeartbeatResponseP())
    }

    // Verify the `fromRequestProto` throws with an invalid request proto - negative opId.
    assertThrows[IllegalArgumentException] {
      HeartbeatResponse.fromProto(HeartbeatResponseP(opId = Some(-1)))
    }

    // Verify that empty preferredAssignerValue proto in response is allowed, and it's equivalent
    // to PreferredAssignerValue.ModeDisabled.
    val parsedRequest = HeartbeatResponse.fromProto(HeartbeatResponseP().withOpId(18))
    assert(
      parsedRequest == HeartbeatResponse(18, PreferredAssignerValue.ModeDisabled(Generation.EMPTY))
    )

    // Construct some valid response protos and verify the result is as expected.
    for (preferredAssignerValue: PreferredAssignerValue <- Seq(
        preferredAssignerModeDisabled,
        noPreferredAssigner,
        somePreferredAssigner
      )) {
      val opId: Long = 42
      val responseP = HeartbeatResponseP(
        opId = Some(opId),
        preferredAssignerValue = Some(
          preferredAssignerValue.toProto
        )
      )
      val expectedResponse = HeartbeatResponse(opId, preferredAssignerValue)
      assert(expectedResponse == HeartbeatResponse.fromProto(responseP))
      // Verify the round-trip.
      assert(expectedResponse.toProto == responseP)
    }

  }

  test("PreferredAssignerConfig.create") {
    // Test plan: verify that `PreferredAssignerConfig.create` returns an expected instance.

    val thisAssignerInfo = AssignerInfo(UUID.randomUUID(), new URI("http://localhost:1234"))
    val otherAssignerInfo = AssignerInfo(UUID.randomUUID(), new URI("http://localhost:5678"))

    val nonLooseIncarnation = Incarnation(42) // An arbitrary non-loose incarnation.
    val looseIncarnation = Incarnation(41) // An arbitrary loose incarnation.

    // List of possible cases for the `PreferredAssignerValue`.
    val modeDisabledValue = PreferredAssignerValue.ModeDisabled(Generation.EMPTY)
    val modeDisabledValue2 =
      PreferredAssignerValue.ModeDisabled(Generation(looseIncarnation, number = 123))
    val noAssignerValue = PreferredAssignerValue.NoAssigner(Generation.EMPTY)
    val noAssignerValue2 =
      PreferredAssignerValue.NoAssigner(Generation(nonLooseIncarnation, number = 123))
    val thisAssignerPreferredValue =
      PreferredAssignerValue.SomeAssigner(thisAssignerInfo, Generation(nonLooseIncarnation, 123))
    val otherAssignerPreferredValue =
      PreferredAssignerValue.SomeAssigner(otherAssignerInfo, Generation(nonLooseIncarnation, 123))

    // If PA is disabled, we should assume the role of preferred.
    for (value: PreferredAssignerValue <- Seq(modeDisabledValue, modeDisabledValue2)) {
      val config = PreferredAssignerConfig.create(
        value,
        thisAssignerInfo
      )
      assert(config.role == AssignerRole.Preferred)
      assert(config.redirect == Redirect.EMPTY)
    }

    // - For `PreferredAssignerValue.NoAssigner`, always not preferred and redirect to empty.
    for (value: PreferredAssignerValue <- Seq(noAssignerValue, noAssignerValue2)) {
      val config = PreferredAssignerConfig.create(
        value,
        thisAssignerInfo
      )
      assert(config.role == AssignerRole.Standby)
      assert(config.redirect == Redirect.EMPTY)
    }

    // - For `PreferredAssignerValue.SomeAssigner`, should be preferred if the assigner has the
    // same `AssignerInfo` as the current assigner, and redirect to the assigner's URI.

    val config1 = PreferredAssignerConfig.create(
      thisAssignerPreferredValue,
      thisAssignerInfo
    )
    assert(config1.role == AssignerRole.Preferred)
    assert(config1.redirect == Redirect(Some(thisAssignerInfo.uri)))

    val config2 = PreferredAssignerConfig.create(
      otherAssignerPreferredValue,
      thisAssignerInfo
    )
    assert(config2.role == AssignerRole.Standby)
    assert(config2.redirect == Redirect(Some(otherAssignerInfo.uri)))

    // Special case: value is a `PreferredAssignerValue.SomeAssigner` where the assigner info has
    // the same URI but different UUID. This should be considered as not preferred.
    val sameUriDifferentUuidPreferredValue =
      PreferredAssignerValue.SomeAssigner(
        AssignerInfo(UUID.randomUUID(), thisAssignerInfo.uri),
        Generation(nonLooseIncarnation, 123)
      )
    val config3 = PreferredAssignerConfig.create(
      sameUriDifferentUuidPreferredValue,
      thisAssignerInfo
    )
    assert(config3.role == AssignerRole.Standby)
    assert(config3.redirect == Redirect(Some(thisAssignerInfo.uri)))
  }

}

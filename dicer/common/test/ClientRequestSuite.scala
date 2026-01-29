package com.databricks.dicer.common

import scala.concurrent.duration._

import java.net.{URI, URISyntaxException}
import java.time.Instant
import com.databricks.api.proto.dicer.common.ClientRequestP.SliceletDataP.SliceLoadP
import com.databricks.api.proto.dicer.common.ClientRequestP.{
  ClientFeatureSupportP,
  SliceletDataP,
  SubscriberDataP
}
import com.databricks.api.proto.dicer.common.{
  ClientRequestP,
  ClientResponseP,
  GenerationP,
  SyncAssignmentStateP,
  TargetP
}
import com.databricks.caching.util.TestUtils.{assertThrow, loadTestData}
import com.google.protobuf.ByteString
import com.databricks.dicer.common.SliceletData.{KeyLoad, SliceLoad}
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.common.test.{
  ClientRequestTestDataP,
  ClientResponseTestDataP,
  SliceLoadTestDataP,
  SliceletDataTestDataP
}
import com.databricks.dicer.common.test.ClientRequestTestDataP.InvalidClientRequestTestCaseP
import com.databricks.dicer.common.test.ClientResponseTestDataP.{
  InvalidClientResponseTestCaseP,
  RoundTrippingTestCaseP
}
import com.databricks.dicer.common.test.SliceLoadTestDataP.ProtoValidityTestCaseP
import com.databricks.dicer.external.{Slice, Target}
import com.databricks.testing.DatabricksTest
import com.google.protobuf.InvalidProtocolBufferException

import scala.concurrent.duration._
import scala.util.control.NonFatal

class ClientRequestSuite extends DatabricksTest {

  private val CLIENT_REQUEST_TEST_DATA: ClientRequestTestDataP =
    loadTestData[ClientRequestTestDataP](
      "dicer/common/test/data/client_request_test_data.textproto"
    )

  private val CLIENT_RESPONSE_TEST_DATA: ClientResponseTestDataP =
    loadTestData[ClientResponseTestDataP](
      "dicer/common/test/data/client_response_test_data.textproto"
    )

  private val CLUSTER_URI1 = URI.create("kubernetes-cluster:test-env/cloud1/public/region8/clustertype2/01")
  private val CLUSTER_URI2 = URI.create("kubernetes-cluster:test-env/cloud1/public/region1/clustertype3/01")

  test("Invalid ClientRequest") {
    // Test plan: Verify that ClientRequest validation fails with the right exception and the
    // relevant error string (or at least a snippet of it) when given an invalid proto.
    val testCases: Seq[InvalidClientRequestTestCaseP] = CLIENT_REQUEST_TEST_DATA.invalidCases

    for (testCase <- testCases) {
      val testName: String = testCase.caseName.getOrElse(fail("expect a name for each test case"))
      val expectedErrorMsg: String =
        testCase.expectedErrorMessage.getOrElse(fail("expect a non-empty error message"))
      val unmarshaller = TargetUnmarshaller.CLIENT_UNMARSHALLER
      assertThrow[IllegalArgumentException](expectedErrorMsg) {
        val proto: ClientRequestP = testCase.getClientRequest
        ClientRequest.fromProto(unmarshaller, proto)
        fail(s"Expect $testName to throw an exception, but it succeeded.")
      }
    }
  }

  test("ClientRequest roundtripping") {
    // Test plan: Ensure that the client request class and the proto roundtrip for valid objects.
    val testCases: Seq[ClientRequestP] = CLIENT_REQUEST_TEST_DATA.roundTrippingCases

    for (clusterUri <- Seq(CLUSTER_URI1, CLUSTER_URI2)) {
      // Add the cluster URI to the proto for the test cases that do not have it.
      for (requestProto <- testCases) {
        // Determine the appropriate unmarshaller based on the request type
        val unmarshaller = requestProto.subscriberDataP match {
          case SubscriberDataP.ClerkFields(_) =>
            // Clerk requests will be unmarshalled by the client unmarshaller.
            TargetUnmarshaller.CLIENT_UNMARSHALLER
          case SubscriberDataP.SliceletFields(_) =>
            // Slicelet requests will be unmarshalled by an assigner unmarshaller.
            TargetUnmarshaller.createAssignerUnmarshaller(clusterUri)
          case SubscriberDataP.Empty =>
            fail("Unexpected empty SubscriberDataP")
        }

        val request: ClientRequest = ClientRequest.fromProto(unmarshaller, requestProto)
        val roundtrip: ClientRequestP = request.toProto
        val finalRoundtrip: ClientRequest = ClientRequest.fromProto(unmarshaller, roundtrip)
        assert(request == finalRoundtrip)
      }
    }
  }

  test("ClientRequest supportsSerializedAssignment defaults to false") {
    // Test plan: Verify that supportsSerializedAssignment defaults to false when:
    // 1. client_feature_support is omitted entirely
    // 2. client_feature_support is present but empty (defaultInstance)
    val protoWithoutClientFeatureSupport = ClientRequestP(
      target = Some(TargetP(name = Some("softstore"))),
      syncAssignmentState = Some(
        new SyncAssignmentStateP(
          state = SyncAssignmentStateP.State.KnownGeneration(
            new GenerationP(incarnation = Some(10L), number = Some(42L))
          )
        )
      ),
      subscriberDebugName = Some("subscriber"),
      chosenRpcTimeoutMillis = Some(600000L),
      subscriberDataP = ClientRequestP.SubscriberDataP.ClerkFields(new ClientRequestP.ClerkDataP)
    )

    val request1 = ClientRequest.fromProto(
      TargetUnmarshaller.CLIENT_UNMARSHALLER,
      protoWithoutClientFeatureSupport
    )
    assert(!request1.supportsSerializedAssignment)

    val protoWithEmptyClientFeatureSupport = protoWithoutClientFeatureSupport.copy(
      clientFeatureSupport = Some(ClientFeatureSupportP.defaultInstance)
    )
    val request2 = ClientRequest.fromProto(
      TargetUnmarshaller.CLIENT_UNMARSHALLER,
      protoWithEmptyClientFeatureSupport
    )
    assert(!request2.supportsSerializedAssignment)
  }

  test("ClientRequest supportsSerializedAssignment roundtrips correctly") {
    // Test plan: Verify that supportsSerializedAssignment roundtrips correctly for both true and
    // false values.
    //
    // Note: This test is separate from the "ClientRequest roundtripping" test because
    // supportsSerializedAssignment is only implemented in Scala (Rust ignores this field),
    // whereas the "ClientRequest roundtripping" test covers behavior that is identical
    // in both Scala and Rust.
    val target = Target("test-target")
    val syncAssignmentState = SyncAssignmentState.KnownGeneration(Generation.EMPTY)
    val timeout: FiniteDuration = 600.seconds

    for (supportsSerializedAssignment: Boolean <- Seq(true, false)) {
      val request = ClientRequest(
        target = target,
        syncAssignmentState = syncAssignmentState,
        subscriberDebugName = "test-subscriber",
        timeout = timeout,
        subscriberData = ClerkData,
        supportsSerializedAssignment = supportsSerializedAssignment
      )

      // Convert to proto and back.
      val proto: ClientRequestP = request.toProto
      val roundtrippedRequest: ClientRequest =
        ClientRequest.fromProto(TargetUnmarshaller.CLIENT_UNMARSHALLER, proto)
      assertResult(request)(roundtrippedRequest)
    }
  }

  test("SliceletData roundtripping") {
    val testData: Seq[SliceletDataP] =
      loadTestData[SliceletDataTestDataP](
        "dicer/common/test/data/slicelet_data_test_data.textproto"
      ).roundTrippingCases

    // Test plan: Ensure that the SliceletData class and the proto roundtrip for a valid object.
    for (data <- testData) {
      assert(SliceletData.fromProto(data).toProto == data)
    }
  }

  private val SLICE_LOAD_TEST_DATA: SliceLoadTestDataP =
    loadTestData[SliceLoadTestDataP]("dicer/common/test/data/slice_load_test_data.textproto")

  test("Invalid SliceLoad proto") {
    // Test plan: verify that the `SliceLoad.fromProto` method throws an exception when
    // given an invalid proto.
    val invalidCases: Seq[ProtoValidityTestCaseP] = SLICE_LOAD_TEST_DATA.invalidCases

    for (caseP: ProtoValidityTestCaseP <- invalidCases) {
      val sliceLoadProto = caseP.sliceLoad.getOrElse(fail("expect non empty SliceLoadP"))
      val expectedErrorMsg = caseP.expectedError.getOrElse(fail("expect non empty error message"))
      val debugCaseName = caseP.debugName.getOrElse(fail("expect non empty debug name"))

      try {
        SliceLoad.fromProto(sliceLoadProto)
        fail(s"Expected exception for case: $debugCaseName")
      } catch {
        case exception: IllegalArgumentException =>
          assert(
            exception.getMessage.contains(expectedErrorMsg),
            s"Case $debugCaseName Expected error message '$expectedErrorMsg', " +
            s"but got: ${exception.getMessage}"
          )
      }
    }
  }

  test("SliceLoad.slice default value") {
    // Test plan: Verify that when `slice` field is missing in the SliceLoadP proto message,
    // the SliceLoad can still be successfully parsed from it and the value of `slice` will
    // be a full slice.
    val emptySliceCase: SliceLoadP = SLICE_LOAD_TEST_DATA.getEmptySliceCase

    assert(emptySliceCase.slice.isEmpty)
    assert(SliceLoad.fromProto(emptySliceCase).slice == Slice.FULL)
  }

  test("SliceLoad.numReplicas default value") {
    // Test plan: Verify that when `numReplicas` field is missing in the SliceLoadP proto message,
    // the SliceLoad can still be successfully parsed from it and the value of `numReplicas` will
    // be 1.
    val protoWithoutNumReplicas: SliceLoadP = SLICE_LOAD_TEST_DATA.getEmptyNumReplicasCase

    assert(protoWithoutNumReplicas.numReplicas.isEmpty)
    assert(SliceLoad.fromProto(protoWithoutNumReplicas).numReplicas == 1)
  }

  test("SliceLoad roundtripping") {
    // Test plan: Verify that SliceLoad.fromProto does not throw an exception when given a
    // valid proto, and that the SliceLoad class and the proto round-trip correctly.
    // Note: Strictly speaking, SliceLoad.fromProto and SliceLoad.toProto are not fully
    // symmetric, since an empty slice is represented as a full slice in the proto, and the
    // unspecified numReplicas field defaults to 1. This test is intended to ensure that
    // round-tripping works for the case where the slice is not empty and numReplicas is specified.
    // See `SliceLoad.slice default value` and `SliceLoad.numReplicas default value` for
    // the special cases.
    val validCases: Seq[SliceLoadP] = SLICE_LOAD_TEST_DATA.roundTrippingCases

    for (sliceLoadP: SliceLoadP <- validCases) {
      val sliceLoad = SliceLoad.fromProto(sliceLoadP)
      val sliceLoadProto: SliceLoadP = sliceLoad.toProto

      assert(sliceLoadProto == sliceLoadP)
      // Also test that the `toProto` method returns a valid proto.
      assert(SliceLoad.fromProto(sliceLoad.toProto) == sliceLoad)
    }

    // Test combinations of various valid parameters.
    for (primaryRateLoad: Double <- Seq(820.0, 750.0, 430.0, 1024.0, 2048.0);
      topKeys: Seq[KeyLoad] <- Seq(Seq(KeyLoad("apple", 50), KeyLoad("banana", 30)), Seq());
      numReplicas: Int <- Seq(1, 2, 10, 100)) {
      // Get current Instant, but with seconds precision.
      val now = Instant.ofEpochSecond(Instant.now().getEpochSecond)
      val sliceLoad = SliceLoad(
        primaryRateLoad,
        windowLowInclusive = now.minusSeconds(1),
        windowHighExclusive = now,
        slice = "a" -- "c",
        topKeys,
        numReplicas
      )
      assert(sliceLoad == SliceLoad.fromProto(sliceLoad.toProto))
    }
  }

  test("Invalid ClientResponse") {
    // Test plan: Verify that ClientResponse validation fails with the right exception and the
    // relevant error string (or at least a snippet of it) when given an invalid proto.
    val testCases: Seq[InvalidClientResponseTestCaseP] = CLIENT_RESPONSE_TEST_DATA.invalidCases

    for (testCase <- testCases) {
      val testName: String = testCase.caseName.getOrElse(fail("expect a name for each test case"))
      val expectedErrorMsg: String =
        testCase.expectedErrorMessage.getOrElse(fail("expect a non-empty error message"))
      try {
        ClientResponse.fromProto(testCase.getClientResponse)
        fail(s"Expected exception for case: $testName")
      } catch {
        case exception: IllegalArgumentException =>
          assert(
            exception.getMessage.contains(expectedErrorMsg),
            s"Case $testName Expected error message '$expectedErrorMsg', " +
            s"but got: ${exception.getMessage}"
          )
        case exception: URISyntaxException =>
          assert(
            testName.contains("Invalid redirect URI"), // The specific case that can throw this
            s"Case $testName: unexpected exception thrown for case, $exception"
          )
        case NonFatal(e) =>
          fail(s"Case $testName: unexpected exception thrown for case, $e")
      }
    }
  }

  test("ClientResponse roundtripping") {
    // Test plan: Ensure that the response class and the proto roundtrip for a valid object.
    val testCases: Seq[RoundTrippingTestCaseP] = CLIENT_RESPONSE_TEST_DATA.roundTrippingCases

    for (testCase <- testCases) {
      val caseName = testCase.caseName.getOrElse(fail("expect a name for each test case"))
      val responseProto = testCase.getClientResponse
      val response: ClientResponse = ClientResponse.fromProto(responseProto)
      val roundtrip: ClientResponseP = response.toProto
      val finalRoundtrip: ClientResponse = ClientResponse.fromProto(roundtrip)
      assert(
        response == finalRoundtrip,
        s"Roundtrip failed for case: $caseName. " +
        s"Original proto: $responseProto, Roundtrip proto: $roundtrip"
      )
    }
  }

  test("ClientResponse with valid serialized assignment") {
    // Test plan: Ensure that ClientResponse with serialized_known_assignment can be parsed
    // correctly and that the parsed assignment is consistent with the initial one.

    // Create a random assignment and convert to a ClientResponseP.
    val generation: Generation = 43
    val sliceAssignment: SliceAssignment = TestSliceUtils.createRandomSliceAssignment(
      slice = Slice.FULL,
      subslices = Vector.empty,
      generation = generation,
      rng = new scala.util.Random(2)
    )
    val assignment: Assignment = TestSliceUtils.createAssignment(
      generation = generation,
      consistencyMode = AssignmentConsistencyMode.Affinity,
      entries = Vector(sliceAssignment)
    )
    val diffAssignment: DiffAssignment = assignment.toDiff(Generation.EMPTY)
    val syncAssignmentStateP: SyncAssignmentStateP =
      SyncAssignmentState.createWithDiffAssignmentSerialized(diffAssignment)
    val responseP: ClientResponseP =
      ClientResponse.createProtoWithSyncStateP(syncAssignmentStateP, 10.seconds, Redirect.EMPTY)

    // Parse the response that contains the serialized assignment.
    val response: ClientResponse = ClientResponse.fromProto(responseP)
    response.syncState match {
      case SyncAssignmentState.KnownAssignment(roundTripDiff: DiffAssignment) =>
        assertResult(diffAssignment)(roundTripDiff)
      case SyncAssignmentState.KnownGeneration(_: Generation) =>
        fail("This should be an assignment")
    }
  }

  test("ClientResponse with malformed serialized assignment") {
    // Test plan: Ensure that ClientResponse with malformed serialized_known_assignment throws
    // the appropriate exception when being parsed.
    val serializedAssignment: com.google.protobuf.ByteString =
      ByteString.copyFrom(Array[Byte]('h', 'e', 'l', 'l', 'o'))
    val syncAssignmentStateP: SyncAssignmentStateP = new SyncAssignmentStateP(
      state = SyncAssignmentStateP.State.KnownSerializedAssignment(serializedAssignment)
    )
    val responseP: ClientResponseP =
      ClientResponse.createProtoWithSyncStateP(syncAssignmentStateP, 10.seconds, Redirect.EMPTY)
    assertThrow[InvalidProtocolBufferException]("Protocol message tag had invalid wire type") {
      ClientResponse.fromProto(responseP)
    }
  }

  test("ClientRequest.getClientType") {
    // Test plan: Verify that getClientType returns ClientType.Clerk for a ClientRequest with
    // ClerkData subscriber data and returns ClientType.Slicelet for a ClientRequest with
    // SliceletData subscriber data.
    val clerkRequest = ClientRequest(
      target = Target("test-target"),
      syncAssignmentState = SyncAssignmentState.KnownGeneration(Generation.EMPTY),
      subscriberDebugName = "test-clerk",
      timeout = 10.seconds,
      subscriberData = ClerkData,
      supportsSerializedAssignment = false
    )

    assert(clerkRequest.getClientType == ClientType.Clerk)

    val sliceletData = SliceletData(
      squid = createTestSquid(CLUSTER_URI1.toString),
      state = SliceletDataP.State.RUNNING,
      kubernetesNamespace = "test-namespace",
      attributedLoads = Vector.empty,
      unattributedLoadOpt = None
    )
    val sliceletRequest = ClientRequest(
      target = Target("test-target"),
      syncAssignmentState = SyncAssignmentState.KnownGeneration(Generation.EMPTY),
      subscriberDebugName = "test-slicelet",
      timeout = 10.seconds,
      subscriberData = sliceletData,
      supportsSerializedAssignment = false
    )

    assert(sliceletRequest.getClientType == ClientType.Slicelet)
  }
}

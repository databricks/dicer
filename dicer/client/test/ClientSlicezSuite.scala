package com.databricks.dicer.client

import java.net.URI

import scala.concurrent.Future
import scala.concurrent.duration._

import org.apache.commons.text.StringEscapeUtils

import com.databricks.caching.util.AssertionWaiter
import com.databricks.caching.util.SequentialExecutionContext
import com.databricks.caching.util.TestUtils
import com.databricks.caching.util.TestUtils.TestName
import com.databricks.dicer.client.ClientSlicezTestHelper._
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.common.{Assignment, ClerkData, ClientType}
import com.databricks.dicer.external.{Slice, Target}
import com.databricks.testing.DatabricksTest
import TestClientUtils.TEST_CLIENT_UUID

class ClientSlicezSuite extends DatabricksTest with TestName {

  private val sec = SequentialExecutionContext.createWithDedicatedPool("client-slicez-suite")

  test("Check ClientTargetSlicezData data table HTML contents") {
    // Test plan: Directly create ClientTargetSlicezData and use golden snippets to verify
    // elements are correctly rendered.
    val assignment: Assignment = ClientSlicezTestHelper.createAssignment
    val unattributedLoadBySliceOpt: Option[Map[Slice, Double]] = Some(
      Map(
        ("" -- "Kili", 0.5),
        ("Kili" -- ∞, 0.75)
      )
    )

    val clientTargetSlicezData1: ClientTargetSlicezData = createClientTargetSlicezData(TARGET)

    val clientTargetSlicezData2: ClientTargetSlicezData =
      createClientTargetSlicezData(
        TARGET,
        clientClusterOpt = None,
        subscriberDebugName = SUBSCRIBER_DEBUG_NAMES(1),
        assignmentOpt = Option(assignment),
        unattributedLoadBySliceOpt = unattributedLoadBySliceOpt
      )

    val clientTableHtml1: String = clientTargetSlicezData1.getHtml.render
    val clientTableHtml2: String = clientTargetSlicezData2.getHtml.render

    val expectedStrings1: Array[String] = Array(
      "<tr style=\"background-color: PaleTurquoise;\">", // background color
      "<td>softstore-storelet</td>", // target name
      "<td>S0-softstore-storelet-localhost</td>", // subscriber debug name
      "<td>https://localhost:12345</td>", // watch address
      "<td>1970-01-01T00:00:00Z</td>", // Instant.EPOCH
      // Unattributed load table
      "┌───────────┬────────────┬───────────────────┐",
      "│ Slice.low │ Slice.high │ Unattributed Load │",
      "├───────────┼────────────┼───────────────────┤",
      "│ N/A       │ N/A        │ N/A               │",
      "└───────────┴────────────┴───────────────────┘"
    )

    val expectedStrings2: Array[String] = Array(
      "<tr style=\"background-color: PaleTurquoise;\">", // Background color
      "<td>softstore-storelet</td>", // Target name
      "<td>S1-softstore-storelet-localhost</td>", // Subscriber debug name
      "<td>https://localhost:12345</td>", // Watch address
      "<td>1970-01-01T00:00:00Z</td>", // Instant.EPOCH
      // Unattributed load table
      "┌───────────┬────────────┬───────────────────┐",
      "│ Slice.low │ Slice.high │ Unattributed Load │",
      "├───────────┼────────────┼───────────────────┤",
      // The start of the slice is "", which must be escaped. We do not test for
      // escaped contents in this test case.
      "│ Kili       │ 0.5               │",
      "│ Kili      │ ∞          │ 0.75              │",
      "└───────────┴────────────┴───────────────────┘"
    )

    for (expectedString <- expectedStrings1) {
      assert(clientTableHtml1.contains(expectedString))
    }
    for (expectedString <- expectedStrings2) {
      assert(clientTableHtml2.contains(expectedString))
    }
  }

  test("Check escaping contents for ClientTargetSlicezData") {
    // Test plan: Create ClientTargetSlicezData with escaping contents and test if they get handled.
    val nonsenseName = "<button>&nonsense name</button>"

    // Calculate expected escaped contents using `StringEscapeUtils.escapeHtml4`.
    val nonsenseNameEscaped = StringEscapeUtils.escapeHtml4(nonsenseName)

    val clientTargetSlicezData: ClientTargetSlicezData =
      createClientTargetSlicezData(
        Target("target-with-nonsense-debug-name"),
        subscriberDebugName = nonsenseName
      )

    val renderedData = clientTargetSlicezData.getHtml.render

    // Verify the rendered HTML should contain snippets with escaped contents rather than raw ones.
    assert(!renderedData.contains(nonsenseName))
    assert(renderedData.contains(nonsenseNameEscaped))
  }

  test("ClientTargetSlicezDataExporter uses reference equality") {
    // Test plan: Create multiple exporter instances and verify that equals() compares memory
    // addresses rather than object contents. Test both equal cases (same reference) and unequal
    // cases (different instances, different ref types, null, value types). Also verify that
    // hashCode() uses identity hash code for consistency with equals().

    // Setup: Create a test implementation that always returns a dummy ClientTargetSlicezData.
    class TestExporter extends ClientTargetSlicezDataExporter {
      override def getSlicezData: Future[ClientTargetSlicezData] =
        Future.successful(createClientTargetSlicezData(TARGET))
    }

    // Setup: Create two distinct instances with identical data, and one alias to the first
    // instance.
    val exporter1: TestExporter = new TestExporter
    val exporter2: TestExporter = new TestExporter
    val exporter3: TestExporter = exporter1

    // Verify: Two different object instances should not be equal, even with identical data.
    assert(exporter1 != exporter2)

    // Verify: Same object instance should be equal to itself and to its alias.
    assert(exporter1 == exporter1)
    assert(exporter1 == exporter3)

    // Verify: Same object instance should produce the same hashCode consistently.
    assert(exporter1.hashCode() == exporter1.hashCode())
    assert(exporter1.hashCode() == exporter3.hashCode())

    // Verify: Different object instances should produce different hashCodes.
    assert(exporter1.hashCode() != exporter2.hashCode())

    // Verify: Comparison with different ref types, value types and null should return false.
    assert(exporter1 != TestUtils.awaitResult(exporter1.getSlicezData, Duration.Inf))
    assert(exporter1 != 42)
    assert(exporter1 != "string")
    assert(exporter1 != null)
    // Verify: Hash code comparison with different ref types and value types should return false.
    assert(
      exporter1
        .hashCode() != TestUtils.awaitResult(exporter1.getSlicezData, Duration.Inf).hashCode()
    )
    assert(exporter1.hashCode() != 42.hashCode())
    assert(exporter1.hashCode() != "string".hashCode())
  }

  test("getHtml - same cluster shows no indicator in target cell") {
    // Test plan: Verify that no connection type indicator appears in the Target column when the
    // target and client are in the same cluster. Construct a ClientTargetSlicezData with a
    // KubernetesTarget carrying a cluster URI and the same URI as the client cluster, render it,
    // and verify the target cell contains only the bare target description.
    val cluster: URI = URI.create("kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01")
    val target: Target = Target.createKubernetesTarget(cluster, "softstore-storelet")
    val renderedHtml: String =
      createClientTargetSlicezData(target, clientClusterOpt = Some(cluster)).getHtml.render

    assert(renderedHtml.contains(s"<td>${target.toParseableDescription}</td>"))
    assert(!renderedHtml.contains("[Cross-cluster]"))
    assert(!renderedHtml.contains("[Cross-region]"))
  }

  test("getHtml - different cluster same region shows [Cross-cluster] in target cell") {
    // Test plan: Verify that a bolded [Cross-cluster] indicator appears in the Target column when
    // the target and client are in different clusters within the same region. Construct a
    // ClientTargetSlicezData with a KubernetesTarget in gc/01 and gc/02 as the client cluster
    // (same region, different cluster code), render it, and verify the target cell contains the
    // target description followed by a bold [Cross-cluster] tag.
    val targetCluster: URI = URI.create("kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01")
    val clientCluster: URI = URI.create("kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/02")
    val target: Target = Target.createKubernetesTarget(targetCluster, "softstore-storelet")
    val renderedHtml: String =
      createClientTargetSlicezData(target, clientClusterOpt = Some(clientCluster)).getHtml.render

    assert(
      renderedHtml
        .contains(s"<td>${target.toParseableDescription} <strong>[Cross-cluster]</strong></td>")
    )
    assert(!renderedHtml.contains("[Cross-region]"))
  }

  test("getHtml - different region shows [Cross-region] in target cell") {
    // Test plan: Verify that a bolded [Cross-region] indicator appears in the Target column when
    // the target and client are in different regions. Construct a ClientTargetSlicezData with a
    // KubernetesTarget in region1 and region8 as the client cluster, render it, and verify
    // the target cell contains the target description followed by a bold [Cross-region] tag.
    val targetCluster: URI = URI.create("kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01")
    val clientCluster: URI = URI.create("kubernetes-cluster:test-env/cloud1/public/region8/clustertype2/01")
    val target: Target = Target.createKubernetesTarget(targetCluster, "softstore-storelet")
    val renderedHtml: String =
      createClientTargetSlicezData(target, clientClusterOpt = Some(clientCluster)).getHtml.render

    assert(
      renderedHtml
        .contains(s"<td>${target.toParseableDescription} <strong>[Cross-region]</strong></td>")
    )
    assert(!renderedHtml.contains("[Cross-cluster]"))
  }

  test("getHtml - target with no cluster info shows no indicator in target cell") {
    // Test plan: Verify that no indicator appears when the target has no cluster information
    // (a KubernetesTarget created via Target.apply, which sets clusterOpt = None). The target
    // cell should contain only the bare target description even when the client has cluster info.
    val target: Target = Target("softstore-storelet")
    val clientCluster: URI = URI.create("kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01")
    val renderedHtml: String =
      createClientTargetSlicezData(target, clientClusterOpt = Some(clientCluster)).getHtml.render

    assert(renderedHtml.contains(s"<td>${target.toParseableDescription}</td>"))
    assert(!renderedHtml.contains("[Cross-cluster]"))
    assert(!renderedHtml.contains("[Cross-region]"))
  }

  test("getHtml - client with no cluster info shows no indicator in target cell") {
    // Test plan: Verify that no indicator appears when the client's cluster information is
    // unavailable (clientClusterOpt = None). Even with a KubernetesTarget carrying a cluster URI,
    // the target cell should contain only the bare target description.
    val targetCluster: URI = URI.create("kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01")
    val target: Target = Target.createKubernetesTarget(targetCluster, "softstore-storelet")
    val renderedHtml: String =
      createClientTargetSlicezData(target, clientClusterOpt = None).getHtml.render

    assert(renderedHtml.contains(s"<td>${target.toParseableDescription}</td>"))
    assert(!renderedHtml.contains("[Cross-cluster]"))
    assert(!renderedHtml.contains("[Cross-region]"))
  }

  test("ClientSlicez register and unregister") {
    // Test plan: Verify that ClientSlicez correctly handles exporter registration and
    // unregistration by checking that `ClientSlicez.getData` includes the subscriberDebugName when
    // the exporter is registered, and excludes it after it is unregistered. Note that the lookup
    // will fail to connect to the non-existent server in the background, but this test only
    // verifies the registration/unregistration behavior.
    val subscriberDebugName: String = getSafeName

    // Setup: Create an exporter (SliceLookup) with the `subscriberDebugName`.
    val config: InternalClientConfig =
      InternalClientConfig(
        SliceLookupConfig(
          ClientType.Clerk,
          watchAddress = WATCH_ADDRESS,
          tlsOptionsOpt = None,
          TARGET,
          clientIdOpt = Some(TEST_CLIENT_UUID),
          watchStubCacheTime = 20.seconds,
          watchFromDataPlane = false,
          enableRateLimiting = false
        )
      )
    val protoLogger: DicerClientProtoLogger = DicerClientProtoLogger.create(
      clientType = ClientType.Clerk,
      conf = TestClientUtils.createTestProtoLoggerConf(sampleFraction = 0.0),
      ownerName = subscriberDebugName
    )
    val exporter: SliceLookup =
      SliceLookup.createUnstarted(
        sec,
        config.sliceLookupConfig,
        subscriberDebugName,
        protoLogger,
        serviceBuilderOpt = None
      )
    // Setup: Start the lookup and register it to the ClientSlicez.
    exporter.start(() => ClerkData)

    // Verify: Wait for the lookup to be registered in ClientSlicez.
    AssertionWaiter("Wait for the lookup to be registered").await {
      val clientSlicezData1: Seq[ClientTargetSlicezData] =
        TestUtils.awaitResult(ClientSlicez.forTest.getData, Duration.Inf)
      assert(clientSlicezData1.exists { targetSlicezData: ClientTargetSlicezData =>
        targetSlicezData.subscriberDebugName == subscriberDebugName
      })
    }
    // Setup: Cancel the lookup to stop it and unregister it from the ClientSlicez.
    exporter.cancel()
    // Verify: Wait for the lookup to be unregistered from ClientSlicez.
    AssertionWaiter("Wait for the lookup to be unregistered").await {
      val clientSlicezData2: Seq[ClientTargetSlicezData] =
        TestUtils.awaitResult(ClientSlicez.forTest.getData, Duration.Inf)
      assert(!clientSlicezData2.exists { targetSlicezData: ClientTargetSlicezData =>
        targetSlicezData.subscriberDebugName == subscriberDebugName
      })
    }
  }
}

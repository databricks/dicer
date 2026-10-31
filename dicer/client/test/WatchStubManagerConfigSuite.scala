package com.databricks.dicer.client

import java.net.{InetAddress, URI}

import com.databricks.dicer.common.ClientType
import com.databricks.rpc.testing.TestTLSOptions
import com.databricks.rpc.tls.TLSOptions
import com.databricks.testing.DatabricksTest

class WatchStubManagerConfigSuite extends DatabricksTest {

  /** The watch address the base config connects to. */
  private val TEST_WATCH_ADDRESS: URI = URI.create("https://localhost:8080")

  /**
   * A second watch address, for the variant that differs from the base config in
   * `watchAddress`.
   */
  private val TEST_OTHER_WATCH_ADDRESS: URI = URI.create("https://localhost:9090")

  /**
   * Builds a config for `targetName`, with each field overridable to derive one-field variants.
   */
  private def createConfig(
      targetName: String,
      clientType: ClientType = ClientType.Clerk,
      watchAddress: URI = TEST_WATCH_ADDRESS,
      tlsOptionsOpt: Option[TLSOptions] = None,
      watchFromDataPlane: Boolean = false,
      sourceIpOpt: Option[InetAddress] = None): WatchStubManagerConfig =
    WatchStubManagerConfig(
      clientType = clientType,
      targetName = targetName,
      watchAddress = watchAddress,
      tlsOptionsOpt = tlsOptionsOpt,
      watchFromDataPlane = watchFromDataPlane,
      sourceIpOpt = sourceIpOpt
    )

  test("the client name is derived from the client type and target name") {
    // Test plan: Verify that clientName is derived from the client type and target name, for an
    // instance `copy` produces as much as for a directly built one, so it can never drift from the
    // lookup identity it describes. Verify this by checking a directly built config, then copying
    // the identity fields, then copying a connection field the name does not depend on.

    // Setup: Build a Clerk config to copy from.
    val clerkConfig: WatchStubManagerConfig = createConfig("derive-test")

    // Verify: The directly built config derives the name from its own parameters.
    assertResult("dicer-clerk-derive-test")(clerkConfig.clientName)

    // Verify: Copying the identity fields re-derives the name from them.
    val sliceletConfig: WatchStubManagerConfig =
      clerkConfig.copy(clientType = ClientType.Slicelet, targetName = "derive-test-other")
    assertResult("dicer-slicelet-derive-test-other")(sliceletConfig.clientName)

    // Verify: Copying only the watch address carries the name over, since the name is not derived
    // from the address.
    val readdressedConfig: WatchStubManagerConfig =
      clerkConfig.copy(watchAddress = TEST_OTHER_WATCH_ADDRESS)
    assertResult("dicer-clerk-derive-test")(readdressedConfig.clientName)
    assertResult(TEST_OTHER_WATCH_ADDRESS)(readdressedConfig.watchAddress)
  }

  test("configs differing only in the source IP are distinct") {
    // Test plan: Verify that `sourceIpOpt` reaches `clientName` and participates in equality, so a
    // source-bound connection's channels and metrics stay distinct from an unbound one's. Verify
    // this by building three configs differing only in `sourceIpOpt`, then checking their names
    // and inequality.

    // Setup: One unbound config and two bound to different local addresses.
    val unboundConfig: WatchStubManagerConfig = createConfig("source-ip-test")
    val firstNicConfig: WatchStubManagerConfig =
      createConfig("source-ip-test", sourceIpOpt = Some(InetAddress.getByName("10.4.5.6")))
    val secondNicConfig: WatchStubManagerConfig =
      createConfig("source-ip-test", sourceIpOpt = Some(InetAddress.getByName("10.7.8.9")))

    // Verify: A bound config's name carries its address, so metrics and channels stay distinct.
    assertResult("dicer-clerk-source-ip-test")(unboundConfig.clientName)
    assertResult("dicer-clerk-source-ip-test-src-10.4.5.6")(firstNicConfig.clientName)
    assertResult("dicer-clerk-source-ip-test-src-10.7.8.9")(secondNicConfig.clientName)

    // Verify: They are pairwise unequal.
    assert(unboundConfig != firstNicConfig)
    assert(unboundConfig != secondNicConfig)
    assert(firstNicConfig != secondNicConfig)
  }

  test("a source-bound client name formats the source IP like getHostAddress") {
    // Test plan: Verify that a source-bound clientName writes the source address the way
    // `InetAddress.getHostAddress` does, which the Rust client mirrors so both languages name an
    // IPv6-bound connection identically. Verify this by building configs bound to an unscoped IPv6
    // address and to the address `getByName` returns for IPv4-mapped IPv6 text, then checking their
    // names. `getByName` already converts that address to an `Inet4Address`, so the second case
    // pins the JDK conversion the Rust client mirrors.

    // Setup: One config bound to an unscoped IPv6 address and one bound to the `Inet4Address` that
    // `getByName` returns for `::ffff:10.4.5.6`.
    val ipv6BoundConfig: WatchStubManagerConfig =
      createConfig("ip-format-test", sourceIpOpt = Some(InetAddress.getByName("2001:db8::1")))
    val convertedIpv4BoundConfig: WatchStubManagerConfig =
      createConfig("ip-format-test", sourceIpOpt = Some(InetAddress.getByName("::ffff:10.4.5.6")))

    // Verify: The IPv6 address is written uncompressed, and the converted address as IPv4.
    assertResult("dicer-clerk-ip-format-test-src-2001:db8:0:0:0:0:0:1")(ipv6BoundConfig.clientName)
    assertResult("dicer-clerk-ip-format-test-src-10.4.5.6")(convertedIpv4BoundConfig.clientName)
  }

  test("equals distinguishes configs that differ in any single field") {
    // Test plan: Verify that every field participates in equals, so no two configs describing
    // different watch connections are conflated as one map key. Verify this by building a base
    // config and, for each field, a variant differing only in that field, then asserting each
    // variant is unequal to the base. This also pins that each field stays a constructor
    // parameter: a field moved into the class body, as `clientName` is, would drop out of equals.

    // Setup: Build a base config to derive one-field variants from.
    val targetName: String = "differing-fields-test"
    val baseConfig: WatchStubManagerConfig = createConfig(targetName)

    // Setup: Derive one variant per field, each differing from `baseConfig` in exactly that field.
    val variedConfigs: List[(String, WatchStubManagerConfig)] = List(
      "clientType" -> createConfig(targetName, clientType = ClientType.Slicelet),
      "targetName" -> createConfig("differing-fields-test-other"),
      "watchAddress" -> createConfig(targetName, watchAddress = TEST_OTHER_WATCH_ADDRESS),
      "tlsOptionsOpt" ->
      createConfig(targetName, tlsOptionsOpt = TestTLSOptions.clientTlsOptionsOpt),
      "watchFromDataPlane" -> createConfig(targetName, watchFromDataPlane = true)
    )

    // Verify: Every variant is unequal to the base config.
    variedConfigs.foreach {
      case (fieldName: String, variedConfig: WatchStubManagerConfig) =>
        withClue(s"configs differing in $fieldName should be unequal: ") {
          assert(baseConfig != variedConfig)
        }
    }
  }

  test("equals and hashCode treat configs with identical fields as the same value") {
    // Test plan: Verify that two separately built configs whose fields all match are equal and hash
    // equal, which is what [[SliceLookupCache]] relies on to key a lookup by its watch connection.
    // Verify this by building the same config twice and comparing the two instances. Inequality of
    // configs that differ is covered by the per-field test above.

    // Setup: Build the same config twice, so the two instances are distinct but field-identical.
    val targetName: String = "equal-configs-test"
    val config: WatchStubManagerConfig = createConfig(targetName)
    val identicalConfig: WatchStubManagerConfig = createConfig(targetName)

    // Verify: The two configs are equal and hash equal.
    assertResult(config)(identicalConfig)
    assertResult(config.hashCode())(identicalConfig.hashCode())
  }
}

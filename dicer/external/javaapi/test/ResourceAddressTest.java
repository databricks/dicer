package com.databricks.dicer.external.javaapi;

import static com.databricks.caching.util.javaapi.TestUtils.checkComparisons;
import static com.databricks.caching.util.javaapi.TestUtils.checkEquality;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.databricks.caching.util.javaapi.TestUtils;
import com.databricks.dicer.common.test.TestData.ResourceAddressTestDataP;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResourceAddressTest {

  /** Test data loaded from textproto. */
  private static final ResourceAddressTestDataP TEST_DATA = loadTestData();

  /** Loads test data from textproto file. */
  private static ResourceAddressTestDataP loadTestData() {
    ResourceAddressTestDataP.Builder builder = ResourceAddressTestDataP.newBuilder();
    try {
      TestUtils.loadTestData(
          "dicer/common/test/data/resource_address_test_data.textproto", builder);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return builder.build();
  }

  /** Helper creating a resource from the string representation of its URI. */
  private ResourceAddress resourceAddressFromString(String uri) {
    return new ResourceAddress(URI.create(uri));
  }

  @Test
  void testResourceAddressCompare() {
    // Test plan: verify that comparison operations work as expected for ResourceAddress instances.
    List<ResourceAddress> orderedResources = new ArrayList<>();
    for (String uri : TEST_DATA.getOrderedUrisList()) {
      orderedResources.add(resourceAddressFromString(uri));
    }
    checkComparisons(orderedResources);
  }

  @Test
  void testResourceAddressEquality() {
    // Test plan: define groups of equivalent resources and verify that equals and hashCode work as
    // expected.
    List<List<ResourceAddress>> equalityGroups = new ArrayList<>();
    for (ResourceAddressTestDataP.EqualityGroupP group : TEST_DATA.getEqualityGroupsList()) {
      List<ResourceAddress> resourceGroup = new ArrayList<>();
      for (String uri : group.getUrisList()) {
        resourceGroup.add(resourceAddressFromString(uri));
      }
      equalityGroups.add(resourceGroup);
    }
    checkEquality(equalityGroups);
  }

  @Test
  void testResourceAddressUnsupportedUri() {
    // Test plan: verify that an IllegalArgumentException is thrown when constructing a
    // ResourceAddress from unsupported URIs.
    for (ResourceAddressTestDataP.UnsupportedResourceAddressUriP unsupportedCase :
        TEST_DATA.getUnsupportedUriCasesList()) {
      URI uri = URI.create(unsupportedCase.getUri()); // URI itself is valid, but is not supported.
      assertThatThrownBy(() -> new ResourceAddress(uri))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(unsupportedCase.getExpectedErrorMessage());
    }
  }

  @Test
  void testResourceAddressToString() {
    // Test plan: verify that toString() returns the expected string representation.
    ResourceAddress resource = resourceAddressFromString("http://foo.bar");
    assertThat(resource.toString()).isEqualTo("http://foo.bar");
  }

  @Test
  void testResourceAddressFromScalaResourceAddress() {
    // Test plan: verify that the package-private constructor correctly wraps a Scala
    // ResourceAddress and produces a Java ResourceAddress equivalent to the one constructed from
    // the same URI.
    URI uri = URI.create("http://example.com/resource");
    com.databricks.dicer.external.ResourceAddress scalaAddress =
        com.databricks.dicer.external.ResourceAddress.apply(uri);
    ResourceAddress javaAddress = new ResourceAddress(scalaAddress);

    assertThat(javaAddress).isEqualTo(new ResourceAddress(uri));
  }
}

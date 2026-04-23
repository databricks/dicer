package com.databricks.dicer.external.javaapi;

import static org.assertj.core.api.Assertions.*;

import com.databricks.caching.util.javaapi.TestUtils;
import com.databricks.dicer.common.test.TestData.TargetTestDataP;
import java.util.List;
import org.junit.jupiter.api.Test;

public class TargetTest {
  private static final TargetTestDataP TEST_DATA = loadTargetTestData();

  /** Reads a TargetTestDataP instance from a textproto file. */
  private static TargetTestDataP loadTargetTestData() {
    TargetTestDataP.Builder builder = TargetTestDataP.newBuilder();
    try {
      TestUtils.loadTestData("dicer/common/test/data/target_test_data.textproto", builder);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return builder.build();
  }

  @Test
  public void testValidNames() {
    // Test plan: verify that we're able to construct Target using valid names (i.e. names that are
    // valid DNS labels).
    List<String> validNames = TEST_DATA.getValidTargetNamesList();
    for (String validName : validNames) {
      Target target = new Target(validName);
      assertThat(target.toString()).isEqualTo(validName);
    }
  }

  @Test
  public void testInvalidNames() {
    // Test plan: verify that `Target(invalidName)` throws an `IllegalArgumentException`.
    List<String> invalidNames = TEST_DATA.getInvalidTargetNamesList();
    for (String invalidName : invalidNames) {
      assertThatThrownBy(() -> new Target(invalidName))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Target name must match regex");
    }
  }

  @Test
  public void testToString() {
    // Test plan: verify that the string representation of the target is of the form `name`.
    Target target = new Target("test-service");
    assertThat(target.toString()).isEqualTo("test-service");
  }

  @Test
  public void testEqualsAndHashCode() {
    // Test plan: construct equality groups of Target objects and verify that `==` and `hashCode`
    // behave correctly using TestUtils.checkEquality.
    List<List<Target>> javaGroups =
        List.of(
            List.of(new Target("a"), new Target("a")),
            List.of(new Target("softstore-storelet")),
            List.of(new Target("0a-1b"), new Target("0a-1b")));
    TestUtils.checkEquality(javaGroups);
  }
}

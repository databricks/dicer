package com.databricks.caching.util.javaapi;

import static org.assertj.core.api.Assertions.*;

import com.databricks.caching.util.javaapi.test.TestMessage.*;
import java.util.List;
import org.junit.jupiter.api.Test;

public class TestUtilsTest {

  @Test
  public void testCheckEquality() {
    // Test plan: verify that checkEquality completes successfully when all elements within each
    // group are equal, and throws an exception when any group contains unequal elements.
    List<List<String>> groups = List.of(List.of("a", "a"), List.of("b"), List.of("c", "c"));
    assertThatNoException().isThrownBy(() -> TestUtils.checkEquality(groups));

    List<List<String>> unequalGroups =
        List.of(List.of("a", "a"), List.of("b", "c"), List.of("c", "c"));
    assertThatThrownBy(() -> TestUtils.checkEquality(unequalGroups))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  public void testLoadTestDataFileNotFound() {
    // Test plan: verify that loadTestData throws RuntimeException when the file does not exist.
    assertThatThrownBy(
            () ->
                TestUtils.loadTestData(
                    "path/to/nonexistent/file.textproto", StringTestMessageP.newBuilder()))
        .isInstanceOf(java.io.IOException.class);
  }

  @Test
  public void testLoadTestDataInvalidFormat() {
    // Test plan: verify that loadTestData throws a RuntimeException when the textproto file is
    // malformed or when the message type does not match the builder.
    assertThatThrownBy(
            () ->
                TestUtils.loadTestData(
                    "caching/util/javaapi/test/data/invalid_textproto.textproto",
                    StringTestMessageP.newBuilder()))
        .isInstanceOf(com.google.protobuf.TextFormat.ParseException.class);

    assertThatThrownBy(
            () ->
                TestUtils.loadTestData(
                    "caching/util/javaapi/test/data/valid_textproto.textproto",
                    IntTestMessageP.newBuilder()))
        .isInstanceOf(com.google.protobuf.TextFormat.ParseException.class);
  }

  @Test
  public void testLoadTestDataSuccess() {
    // Test plan: verify that loadTestData successfully loads a valid textproto file.
    StringTestMessageP.Builder builder = StringTestMessageP.newBuilder();
    try {
      TestUtils.loadTestData("caching/util/javaapi/test/data/valid_textproto.textproto", builder);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    StringTestMessageP testData = builder.build();
    assertThat(testData.getStringsList()).containsExactly("foo", "bar", "baz");
  }

  @Test
  public void testCheckComparisons() {
    // Test plan: verify that checkComparisons completes successfully when given a list of
    // strictly increasing, distinct elements, and throws an AssertionError when the input violates
    // preconditions (duplicates or unsorted order), or when compareTo is not correctly implemented.

    // Comparable that always returns 0 from compareTo, violating the expected ordering.
    class AlwaysEqualComparable implements Comparable<AlwaysEqualComparable> {
      final int value;

      AlwaysEqualComparable(int value) {
        this.value = value;
      }

      @Override
      public int compareTo(AlwaysEqualComparable that) {
        // Broken: claim all elements are equal regardless of value.
        return 0;
      }
    }

    List<Integer> sorted = List.of(1, 2, 3, 4, 10);
    assertThatNoException().isThrownBy(() -> TestUtils.checkComparisons(sorted));

    List<Integer> invalid1 = List.of(1, 1, 2);
    assertThatThrownBy(() -> TestUtils.checkComparisons(invalid1))
        .isInstanceOf(AssertionError.class);

    List<Integer> invalid2 = List.of(1, 3, 2);
    assertThatThrownBy(() -> TestUtils.checkComparisons(invalid2))
        .isInstanceOf(AssertionError.class);

    List<AlwaysEqualComparable> invalid3 =
        List.of(
            new AlwaysEqualComparable(1),
            new AlwaysEqualComparable(2),
            new AlwaysEqualComparable(3));
    assertThatThrownBy(() -> TestUtils.checkComparisons(invalid3))
        .isInstanceOf(AssertionError.class);
  }
}

package com.databricks.caching.util.javaapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Message;
import com.google.protobuf.TextFormat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import scala.jdk.javaapi.CollectionConverters;

/** Utility methods for Java-based tests in the caching module. */
public class TestUtils {
  private TestUtils() {}

  /**
   * Reads test data from a textproto into the provided builder. Callers should invoke {@code
   * builder.build()} after this returns to obtain the message.
   *
   * @param universeRelativePath The path relative to the "universe" directory.
   * @param builder A builder instance for the message type to populate.
   * @throws java.io.IOException if the file cannot be read.
   * @throws com.google.protobuf.TextFormat.ParseException if the textproto cannot be
   *     parsed.
   */
  public static void loadTestData(String universeRelativePath, Message.Builder builder)
      throws IOException {
    Path path =
        Paths.get(
            System.getenv("TEST_SRCDIR"), System.getenv("TEST_WORKSPACE"), universeRelativePath);
    String contents = String.join("\n", Files.readAllLines(path));
    TextFormat.merge(contents, builder);
  }

  /**
   * @see com.databricks.caching.util.TestUtils#checkEquality
   */
  public static void checkEquality(List<? extends List<?>> groups) {
    List<scala.collection.Seq<?>> scalaGroups = new ArrayList<>();
    for (List<?> group : groups) {
      scalaGroups.add(CollectionConverters.asScalaBuffer(group).toSeq());
    }
    com.databricks.caching.util.TestUtils.checkEquality(
        CollectionConverters.asScalaBuffer(scalaGroups).toSeq());
  }

  /**
   * Compares all elements in the given sorted list with distinct elements, in ascending order.
   * Verify that `compareTo` returns the expected result for each pair of elements.
   */
  public static <T extends Comparable<? super T>> void checkComparisons(List<T> sorted) {
    for (int i = 0; i < sorted.size(); i++) {
      T iElement = sorted.get(i);

      // Self comparison is not done here because Java treats it as a bug. See
      // https://errorprone.info/bugpattern/SelfComparison.

      // Compare with all following elements.
      for (int j = i + 1; j < sorted.size(); j++) {
        T jElement = sorted.get(j);

        // i is strictly less than j.
        assertThat(iElement).as(iElement + " != " + jElement).isNotEqualTo(jElement);
        assertThat(iElement).as(iElement + " < " + jElement).isLessThan(jElement);
        assertThat(iElement).as(iElement + " <= " + jElement).isLessThanOrEqualTo(jElement);

        // j is strictly greater than i.
        assertThat(jElement).as(jElement + " != " + iElement).isNotEqualTo(iElement);
        assertThat(jElement).as(jElement + " > " + iElement).isGreaterThan(iElement);
        assertThat(jElement).as(jElement + " >= " + iElement).isGreaterThanOrEqualTo(iElement);
      }
    }
  }
}

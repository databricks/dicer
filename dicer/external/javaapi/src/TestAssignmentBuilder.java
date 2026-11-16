package com.databricks.dicer.external.javaapi;

import com.databricks.dicer.external.DicerTestEnvironment;
import com.databricks.dicer.external.TestAssignmentBuilderInterop;

/**
 * A builder for creating test assignments using Java types. This builder internally converts Java
 * types to Scala types and delegates to the Scala TestAssignmentBuilder. It exposes a limited
 * subset of the Scala builder's functionality; more methods can be added in the future as needed.
 *
 * @see DicerTestEnvironment.TestAssignmentBuilder
 */
public final class TestAssignmentBuilder {

  /** The underlying Scala TestAssignmentBuilder. */
  private final DicerTestEnvironment.TestAssignmentBuilder scalaBuilder;

  /** Creates a new TestAssignmentBuilder. */
  public TestAssignmentBuilder() {
    this.scalaBuilder = TestAssignmentBuilderInterop.createTestAssignmentBuilder();
  }

  /**
   * @see DicerTestEnvironment.TestAssignmentBuilder#add
   */
  public TestAssignmentBuilder add(Slice slice, Slicelet slicelet) {
    com.databricks.dicer.external.Slice scalaSlice = slice.toScala();
    com.databricks.dicer.external.Slicelet scalaSlicelet = slicelet.toScala();
    scalaBuilder.add(scalaSlice, scalaSlicelet);
    return this;
  }

  /**
   * @see DicerTestEnvironment.TestAssignmentBuilder#build
   */
  public DicerTestEnvironment.TestAssignment build() {
    return scalaBuilder.build();
  }
}

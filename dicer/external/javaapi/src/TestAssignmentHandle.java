package com.databricks.dicer.external.javaapi;

/**
 * @see com.databricks.dicer.external.DicerTestEnvironment.AssignmentHandle
 */
public final class TestAssignmentHandle {

  /** The underlying Scala AssignmentHandle. */
  private final com.databricks.dicer.external.DicerTestEnvironment.AssignmentHandle scalaHandle;

  /**
   * Package-private constructor. `TestAssignmentHandle` can only be created by
   * `DicerTestEnvironment`.
   */
  TestAssignmentHandle(
      com.databricks.dicer.external.DicerTestEnvironment.AssignmentHandle scalaHandle) {
    this.scalaHandle = scalaHandle;
  }

  /** Package-private accessor for the underlying Scala AssignmentHandle. */
  com.databricks.dicer.external.DicerTestEnvironment.AssignmentHandle toScala() {
    return scalaHandle;
  }

  @Override
  public String toString() {
    return scalaHandle.toString();
  }
}

package com.databricks.dicer.external

/**
 * Java interop helper that provides access to the TestAssignmentBuilder class for Java code.
 * This is needed because Databricks enforces that Scala's package-private members should not be
 * accessed from Java classes, so this wrapper exposes the functionality publicly. Access to this
 * interop is controlled by Bazel visibility rules, restricting it to dicer internal code only.
 */
object TestAssignmentBuilderInterop {

  /** Creates a new TestAssignmentBuilder instance for Java. */
  def createTestAssignmentBuilder(): DicerTestEnvironment.TestAssignmentBuilder =
    new DicerTestEnvironment.TestAssignmentBuilder()
}

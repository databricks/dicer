// LINT.IfChange
package com.databricks.dicer.external.javaapi;

/**
 * @see com.databricks.dicer.external.SliceletListener
 */
public interface SliceletListener {

  /**
   * @see com.databricks.dicer.external.SliceletListener#onAssignmentUpdated()
   */
  void onAssignmentUpdated();
}
// LINT.ThenChange(dicer/external/src/SliceletListener.scala)
// LINT.ThenChange(dicer/rust/external/slicelet/src/slicelet.rs)

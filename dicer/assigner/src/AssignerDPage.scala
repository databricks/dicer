package com.databricks.dicer.assigner

// TODO(<internal bug>): Support DPages in the Dicer OSS build.
/**
 * No-op implementation of AssignerDPage for the open-source build. The DPage debug endpoint
 * depends on internal Databricks infrastructure that is not available in OSS.
 */
private[assigner] object AssignerDPage {

  /**
   * No-op in the OSS build: DPage registration requires internal infrastructure.
   *
   * @param slicezExporter provides the live [[AssignerSlicezData]] on demand
   * @param dpageNamespace the namespace for DPage registration
   */
  def setup(slicezExporter: AssignerSlicezDataExporter, dpageNamespace: String): Unit = {}
}

package com.databricks.dicer.assigner

class AssignmentGeneratorWithoutStatusMaskingSuite
    extends AssignmentGeneratorSuiteBase(
      observeSliceletReadiness = true,
      permitRunningToNotReady = false
    )

package com.databricks.dicer.assigner

class AssignmentGeneratorWithStatusMaskingSuite
    extends AssignmentGeneratorSuiteBase(
      observeSliceletReadiness = false,
      permitRunningToNotReady = false
    )

package com.databricks.dicer.assigner

class AssignmentGeneratorWithPermitRunningToNotReadySuite
    extends AssignmentGeneratorSuiteBase(
      observeSliceletReadiness = true,
      permitRunningToNotReady = true
    )

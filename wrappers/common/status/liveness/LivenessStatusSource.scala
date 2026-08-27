package com.databricks.common.status.liveness

import com.databricks.common.status.ProbeStatusSource

/**
 * LivenessStatusSource defines the interface liveness probe status sources should implement.
 */
trait LivenessStatusSource extends ProbeStatusSource

package com.databricks.dicer.common

object MetricMeasurement {

  /**
   * REQUIRES: `value` is non-negative, finite number.
   *
   * Throws [[IllegalArgumentException]] if the measured value of a metric (e.g. load or size) is
   * invalid.
   */
  def requireValidMeasurement(value: Double): Unit = {
    require(!value.isNaN, s"$value must be a number")
    require(value.signum >= 0, s"$value must be non-negative")
    require(!value.isPosInfinity, s"$value must be finite")
  }
}

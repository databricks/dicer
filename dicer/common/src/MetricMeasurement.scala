package com.databricks.dicer.common

object MetricMeasurement {

  /**
   * The largest value a Slicelet may report for any metric (e.g. the load of a Slice replica or
   * key). It is far above any real value, and small enough that the Assigner can multiply reported
   * values by replica counts and add them up without reaching infinity.
   */
  val MAX_REPORTED_MEASUREMENT: Double = 1e100

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

  /**
   * Checks a metric value reported by a Slicelet: it must be a valid measurement (see
   * [[requireValidMeasurement]]) and no larger than [[MAX_REPORTED_MEASUREMENT]].
   */
  @throws[IllegalArgumentException]("if `value` is invalid or larger than MAX_REPORTED_MEASUREMENT")
  def requireValidReportedMeasurement(value: Double): Unit = {
    requireValidMeasurement(value)
    require(value <= MAX_REPORTED_MEASUREMENT, s"$value must be at most $MAX_REPORTED_MEASUREMENT")
  }
}

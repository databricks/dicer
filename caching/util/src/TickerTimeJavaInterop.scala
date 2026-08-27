package com.databricks.caching.util

import scala.concurrent.duration.FiniteDuration

/** Java interop helpers for the symbolic arithmetic operators on [[TickerTime]]. */
object TickerTimeJavaInterop {

  /** Adds `d` to `t`. Saturated. */
  def add(t: TickerTime, d: FiniteDuration): TickerTime = t + d

  /** Subtracts `other` from `t`. Saturated. */
  def elapsedSince(t: TickerTime, other: TickerTime): FiniteDuration = t - other

  /** Subtracts `d` from `t`. Saturated. */
  def subtract(t: TickerTime, d: FiniteDuration): TickerTime = t - d
}

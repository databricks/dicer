package com.databricks.testing

import org.scalatest.Assertions
import org.scalatest.concurrent.{Eventually, PatienceConfiguration}
import org.scalatest.enablers.Retrying
import org.scalatest.time.{Nanoseconds, Span}

import scala.concurrent.duration._

/** Provides methods for testing asynchronous or multi-threaded code. */
trait AsyncTestHelpers extends Assertions {

  /**
   * Invokes the specified function repetitively until it doesn't throw any exceptions up to the
   * specified `timeout` at the specified `pollInterval` and returns the last value returned by
   * the function.
   */
  protected final def eventually[T](timeout: Duration, pollInterval: Duration)(
      fun: => T
  )(implicit pos: org.scalactic.source.Position): T = {
    Eventually.eventually(
      PatienceConfiguration.Timeout(Span(timeout.toNanos, Nanoseconds)),
      PatienceConfiguration.Interval(Span(pollInterval.toNanos, Nanoseconds))
    )(fun)(Retrying.retryingNatureOfT, pos)
  }
}

package com.databricks.featureflag.client.utils

import com.google.common.base.Supplier

/**
 * A minimal trait representing a flag-like object that can provide a value. OSS wrapper that
 * mirrors the internal API surface so callers compile unchanged.
 */
trait FlagValueProvider[T] extends NamedFlag {

  /** The name of the flag, used for debugging and metrics. */
  def flagName: String

  /** The supplier that returns the current flag value. */
  def valueSupplier: Supplier[T]

  /** Returns the current value of the flag. */
  def getCurrentValue(): T = valueSupplier.get()
}

object FlagValueProvider {

  /** Creates a [[FlagValueProvider]] from a flag name and a value supplier. */
  def apply[T](name: String, supplier: Supplier[T]): FlagValueProvider[T] = {
    new FlagValueProvider[T] {
      override def flagName: String = name
      override def valueSupplier: Supplier[T] = supplier
    }
  }
}

package com.databricks.dicer.common

import com.databricks.dicer.external.Target

/**
 * A strongly-typed, validated wrapper around a target name. Used when a verified target name is
 * needed, e.g., as a key in config maps, since configurations in Dicer are scoped to target names.
 * Similar to [[Target]], but unlike [[Target]] it is not scoped to a cluster.
 *
 * @param value the target name string, which must be conformant with RFC 1123. See [[Target.name]].
 * @throws IllegalArgumentException if `value` is not a valid target name conformant with RFC 1123.
 */
case class TargetName(value: String) {
  Target.validateName(value)

  /** Returns whether the given target has this name. */
  def matches(target: Target): Boolean = value == target.name

  override def toString: String = value
}

/** Factory method for [[TargetName]]. */
object TargetName {

  /**
   * Creates the target name for the given target. Never throws, since [[Target.name]] is
   * guaranteed to be a valid target name by [[Target]]'s own validation.
   */
  def forTarget(target: Target): TargetName = TargetName(target.name)
}

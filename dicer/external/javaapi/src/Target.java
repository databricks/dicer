package com.databricks.dicer.external.javaapi;

/**
 * @see com.databricks.dicer.external.Target
 */
public final class Target {

  private final com.databricks.dicer.external.Target scalaTarget;

  /**
   * @see com.databricks.dicer.external.Target#apply(String)
   */
  public Target(String name) {
    // Delegate to the Scala implementation which enforces validations.
    scalaTarget = com.databricks.dicer.external.Target.apply(name);
  }

  @Override
  public String toString() {
    return scalaTarget.toString();
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (obj == null || getClass() != obj.getClass()) return false;
    Target target = (Target) obj;
    return scalaTarget.equals(target.scalaTarget);
  }

  @Override
  public int hashCode() {
    return scalaTarget.hashCode();
  }

  /** Returns the underlying Scala Target instance. */
  com.databricks.dicer.external.Target toScala() {
    return scalaTarget;
  }
}

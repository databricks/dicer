package com.databricks.dicer.external.javaapi;

import java.net.URI;

/**
 * @see com.databricks.dicer.external.ResourceAddress
 */
public class ResourceAddress implements Comparable<ResourceAddress> {

  /** The underlying Scala ResourceAddress that this Java wrapper delegates to. */
  private final com.databricks.dicer.external.ResourceAddress scalaResourceAddress;

  /**
   * @see com.databricks.dicer.external.ResourceAddress()
   */
  public ResourceAddress(URI uri) throws IllegalArgumentException {
    this.scalaResourceAddress = com.databricks.dicer.external.ResourceAddress.apply(uri);
  }

  public URI uri() {
    return scalaResourceAddress.uri();
  }

  @Override
  public String toString() {
    return scalaResourceAddress.toString();
  }

  @Override
  public int compareTo(ResourceAddress that) {
    return this.scalaResourceAddress.compareTo(that.scalaResourceAddress);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (obj == null || getClass() != obj.getClass()) return false;
    ResourceAddress that = (ResourceAddress) obj;
    return this.scalaResourceAddress.equals(that.scalaResourceAddress);
  }

  @Override
  public int hashCode() {
    return scalaResourceAddress.hashCode();
  }

  /** Package-private constructor that wraps an existing Scala ResourceAddress. */
  ResourceAddress(com.databricks.dicer.external.ResourceAddress scalaResourceAddress) {
    this.scalaResourceAddress = scalaResourceAddress;
  }
}

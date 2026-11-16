package com.databricks.dicer.client

/** Framework-specific address classification. Service discovery is not supported in open source. */
private[client] object WatchAddressHelper {

  /**
   * Whether `address` is a service discovery address which has no port. Open-source addresses
   * always require an explicit port.
   */
  def isServiceDiscoveryAddress(address: String): Boolean = false
}

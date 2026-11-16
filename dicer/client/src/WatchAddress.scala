package com.databricks.dicer.client

/**
 * Address of the watch server the client should connect to.
 *
 * @param address The address passed to the networking layer.
 */
private[dicer] final class WatchAddress private (val address: String) {

  override def equals(obj: Any): Boolean = obj match {
    case that: WatchAddress => address == that.address
    case _ => false
  }

  override def hashCode(): Int = address.hashCode

  override def toString: String = address
}

private[dicer] object WatchAddress {

  /**
   * Builds a [[WatchAddress]] for connecting to a watch server
   * (Assigner or Slicelet) from a full address string.
   */
  def fromAddress(address: String): WatchAddress = new WatchAddress(address)

  /**
   * Builds a [[WatchAddress]] for connecting to a watch server
   * (Assigner or Slicelet) from a hostname, port, and optional scheme.
   * A service discovery address `hostname` is used unchanged, without the scheme or port.
   */
  def fromHostPort(hostname: String, port: Int, schemeOpt: Option[String] = None): WatchAddress = {
    val address: String = if (WatchAddressHelper.isServiceDiscoveryAddress(hostname)) {
      hostname
    } else {
      val schemePrefix: String = schemeOpt.fold("")((scheme: String) => s"$scheme://")
      s"$schemePrefix$hostname:$port"
    }
    fromAddress(address)
  }
}

package com.databricks.dicer.assigner

import java.net.URI

/**
 * A host-port pair that represents the URI of an Assigner.
 *
 * @param host The hostname or IP address.
 * @param port The port number.
 */
private[assigner] case class AssignerUri(host: String, port: Int) {

  /** The URI as `https://host:port`. */
  val toUri: URI = URI.create(s"https://$host:$port")
}

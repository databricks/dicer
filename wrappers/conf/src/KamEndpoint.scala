package com.databricks.backend.k8sauthmanagerclient

/**
 * OSS stub for [[KamEndpoint]].
 */
sealed trait KamEndpoint

object KamEndpoint {
  // Unused in OSS, kept for compatibility with copybara'd callers that construct it.
  final case class Dbns(dbnsIdentifier: String, destinationClusterUri: String) extends KamEndpoint
}

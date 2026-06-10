package com.databricks.featureflag.client.utils

/** Marker trait for any flag-like object that has a name. */
trait NamedFlag {
  def flagName: String
}

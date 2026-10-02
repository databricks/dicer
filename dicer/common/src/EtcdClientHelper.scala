package com.databricks.dicer.common

import com.databricks.caching.util.EtcdClient.{Version => EtcdClientVersion}

/** Utility methods for converting between [[EtcdClientVersion]] and [[Generation]]. */
object EtcdClientHelper {

  /** Returns the [[EtcdClientVersion]] used to represent `generation` in etcd. */
  def getVersionFromGeneration(generation: Generation): EtcdClientVersion = {
    new EtcdClientVersion(generation.incarnation.value, generation.number.value)
  }

  /** Returns the [[Generation]] represented by `version`. */
  def createGenerationFromVersion(version: EtcdClientVersion): Generation = {
    Generation(Incarnation(version.highBits), version.lowBits)
  }
}

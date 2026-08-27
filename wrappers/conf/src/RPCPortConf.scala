package com.databricks.conf.trusted

import com.databricks.conf.DbConf

/**
 * Contains the port numbers used by different services in production.
 */
trait RPCPortConf extends DbConf {
  val dicerEtcdRpcPort = configure("databricks.diceretcd.rpc.port", 24501)
  val dicerAssignerRpcPort = configure("databricks.dicer.assigner.rpc.port", 24500)

  /** The port on which the Slicelet library listens for watch RPCs from Clerks. */
  val dicerSliceletRpcPort = configure("databricks.dicer.slicelet.rpc.port", 24510)
}

package com.databricks.backend.k8sauthmanagerclient

import com.databricks.rpc.SslArguments

/**
 * OSS stub for [[KamClientConfig]]. The parameters are unused, just kept for compatibility with
 * internal code.
 */
case class KamClientConfig(
    endpoint: KamEndpoint,
    sslArgs: SslArguments,
    callerServiceName: String,
    env: String,
    cloud: String)

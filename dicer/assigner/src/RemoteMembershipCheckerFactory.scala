package com.databricks.dicer.assigner

import scala.concurrent.duration.FiniteDuration

import com.databricks.backend.k8sauthmanagerclient.KamClientConfig

/**
 * OSS stub for the Kubernetes remote checker. The internal version relies on a separate
 * authentication service. So here, `create` just returns a no-op factory.
 */
object RemoteMembershipCheckerFactory {

  def create(
      kamClientConfig: KamClientConfig,
      kubeContext: String,
      kubeApiUrl: String,
      caCertBytes: Array[Byte],
      namespace: String,
      appName: String,
      pollingInterval: FiniteDuration,
      rpcPort: Int): KubernetesMembershipChecker.Factory =
    new KubernetesMembershipChecker.Factory {
      override def create(
          assignerInfo: AssignerInfo,
          assignerProtoLogger: AssignerProtoLogger): Option[KubernetesMembershipChecker] = None
    }
}

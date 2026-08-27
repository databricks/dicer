package com.databricks.dicer.assigner

import com.databricks.dicer.assigner.conf.DicerAssignerConf

/**
 * OSS stub for the Kubernetes remote checker. The internal version relies on a separate
 * authentication service. So here, `tryCreate` just returns None.
 */
object RemoteMembershipCheckerFactory {

  def tryCreate(
      assignerConf: DicerAssignerConf,
      namespace: String,
      appName: String): Option[KubernetesMembershipChecker.Factory] = None
}

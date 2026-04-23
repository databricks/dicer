package com.databricks.conf.trusted

import com.databricks.conf.DbConfSingletonImpl

/** Test utilities for LocationConf. */
object LocationConfTestUtils {

  /**
   * Creates a LocationConf based on the given environment variables. The LOCATION key can be set to
   * a JSON string containing the fields of a [[KubernetesLocation]] to override the default value.
   */
  def newTestLocationConfig(envMap: Map[String, String]): LocationConf = {
    new DbConfSingletonImpl with LocationConf {
      override def sysEnv: Map[String, String] = envMap
      // Internal tests specify some extra fields that are not implemented in open source, allow
      // them to be ignored.
      override def shouldFailOnUnknownProperties(): Boolean = false
    }
  }

  /**
   * Returns a default test [[LocationConf]] with the cluster URI
   * "kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01" and the region URI
   * "region:dev/cloud1/public/region1".
   */
  def newTestLocationConfig(): LocationConf = {
    new DbConfSingletonImpl with LocationConf {
      override val location: KubernetesLocation =
        KubernetesLocation(
          kubernetesClusterUri =
            Some("kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01"),
          regionUri = Some("region:dev/cloud1/public/region1")
        )
    }
  }
}

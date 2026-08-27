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

  /**
   * Returns a materialized [[LocationConf]] with the given cluster and region URIs. Only the fields
   * the open source [[KubernetesLocation]] models are honored; the remaining parameters exist to
   * match the internal signature so shared tests compile against both. An empty URI string maps to
   * `None`.
   */
  def newTestLocationConf(
      confStr: String = "",
      cloudProvider: String = "AWS",
      cloudProviderRegion: String = "AWS_US_WEST_2",
      environment: String = "DEV",
      kubernetesClusterType: String = "GENERAL_CLASSIC",
      kubernetesClusterUri: String =
        "kubernetes-cluster:test-env/cloud1/public/region1/clustertype2/01",
      kubernetesClusterShortName: String = "reg1gc01",
      regionUri: String = "region:dev/cloud1/public/region1",
      regionShortName: String = "reg1",
      regulatoryDomain: String = "PUBLIC"): LocationConf = {
    new DbConfSingletonImpl with LocationConf {
      override val location: KubernetesLocation =
        KubernetesLocation(
          kubernetesClusterUri = Option(kubernetesClusterUri).filter(_.nonEmpty),
          regionUri = Option(regionUri).filter(_.nonEmpty)
        )
    }
  }
}

package com.databricks.dicer.assigner

import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.util.ClientBuilder

/** Produces clients for a Kubernetes API server. */
private[assigner] trait KubernetesApiClientFactory {

  /** Creates a new Kubernetes API client. */
  def create(): ApiClient
}

/** Constructors for [[KubernetesApiClientFactory]]. */
private[assigner] object KubernetesApiClientFactory {

  /** Returns a factory that produces clients for the Kubernetes API server in the local cluster. */
  def localCluster(): KubernetesApiClientFactory =
    new KubernetesApiClientFactory {
      override def create(): ApiClient = ClientBuilder.cluster().build()
    }
}

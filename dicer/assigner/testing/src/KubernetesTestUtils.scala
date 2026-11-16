package com.databricks.dicer.assigner.testing

import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters._
import scala.concurrent.duration._

import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.{V1ObjectMeta, V1Pod, V1PodCondition, V1PodStatus}
import okhttp3.OkHttpClient

/** Shared utilities for tests that use a [[FakeKubernetesServer]]. */
private[assigner] object KubernetesTestUtils {

  /** Default connect timeout for fake-server clients. */
  val DEFAULT_CONNECT_TIMEOUT: FiniteDuration = 5.seconds

  /** Default read timeout for fake-server clients. */
  val DEFAULT_READ_TIMEOUT: FiniteDuration = 10.seconds

  /** Builds an [[ApiClient]] backed by the given [[FakeKubernetesServer]]. */
  def buildApiClient(
      server: FakeKubernetesServer,
      connectTimeout: FiniteDuration = DEFAULT_CONNECT_TIMEOUT,
      readTimeout: FiniteDuration = DEFAULT_READ_TIMEOUT): ApiClient = {
    val client: ApiClient = new ApiClient()
    client.setBasePath(s"http://localhost:${server.port}")
    val okHttpClient: OkHttpClient = client.getHttpClient
      .newBuilder()
      .connectTimeout(connectTimeout.toMillis, TimeUnit.MILLISECONDS)
      .readTimeout(readTimeout.toMillis, TimeUnit.MILLISECONDS)
      .build()
    client.setHttpClient(okHttpClient)
    client
  }

  /** Builds a [[CoreV1Api]] backed by the given [[FakeKubernetesServer]]. */
  def buildCoreV1Api(
      server: FakeKubernetesServer,
      connectTimeout: FiniteDuration = DEFAULT_CONNECT_TIMEOUT,
      readTimeout: FiniteDuration = DEFAULT_READ_TIMEOUT): CoreV1Api =
    new CoreV1Api(buildApiClient(server, connectTimeout, readTimeout))

  /** Builds a `Ready` [[V1Pod]] with the given UID and pod IP. */
  def buildReadyPod(uid: String, podIP: String): V1Pod = {
    val readyCondition: V1PodCondition = new V1PodCondition()
    readyCondition.setType("Ready")
    readyCondition.setStatus("True")
    new V1Pod()
      .metadata(new V1ObjectMeta().uid(uid))
      .status(new V1PodStatus().podIP(podIP).conditions(List(readyCondition).asJava))
  }
}

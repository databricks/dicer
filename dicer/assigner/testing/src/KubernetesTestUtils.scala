package com.databricks.dicer.assigner.testing

import java.util.UUID
import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters._
import scala.concurrent.duration._

import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.{V1ObjectMeta, V1Pod, V1PodCondition, V1PodStatus}
import okhttp3.OkHttpClient

import com.databricks.caching.util.AlertOwnerTeam
import com.databricks.caching.util.SequentialExecutionContext
import com.databricks.dicer.assigner.KubernetesMembershipChecker

/** Shared utilities for tests that use a [[FakeKubernetesServer]]. */
private[assigner] object KubernetesTestUtils {

  /** Connect timeout matching [[KubernetesMembershipChecker.create]]. */
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

  /**
   * A [[KubernetesMembershipChecker.Factory]] that builds an inert checker over a bare
   * [[CoreV1Api]] (no server). The checker satisfies the Assigner's required dependency but never
   * polls: the one-hour interval schedules the first poll well past any test. Use when a test needs
   * the Assigner to start but does not exercise membership.
   */
  val inertMembershipCheckerFactory: KubernetesMembershipChecker.Factory =
    new KubernetesMembershipChecker.Factory {
      override def create(assignerUuid: UUID): KubernetesMembershipChecker = {
        val checkerSec: SequentialExecutionContext =
          SequentialExecutionContext.createWithDedicatedPool(
            name = "membership-checker-inert",
            alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
          )
        new KubernetesMembershipChecker(
          checkerSec,
          new CoreV1Api(new ApiClient()),
          assignerUuid,
          namespace = "test-namespace",
          appName = "test-app",
          pollingInterval = 1.hour,
          rpcPort = 1,
          kubeContextLabelOpt = None
        )
      }
    }
}

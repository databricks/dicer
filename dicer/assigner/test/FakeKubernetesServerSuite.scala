package com.databricks.dicer.assigner

import java.util.UUID

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration.Duration

import io.kubernetes.client.informer.{
  ResourceEventHandler,
  SharedIndexInformer,
  SharedInformerFactory
}
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.{V1Pod, V1PodList}
import io.kubernetes.client.util.CallGeneratorParams

import com.databricks.caching.util.{AlertOwnerTeam, AssertionWaiter, SequentialExecutionContext}
import com.databricks.caching.util.TestUtils.TestName
import com.databricks.testing.DatabricksTest

/** Tests [[FakeKubernetesServer]] through the Kubernetes Java API client. */
class FakeKubernetesServerSuite extends DatabricksTest with TestName {

  private val sec: SequentialExecutionContext =
    SequentialExecutionContext.createWithDedicatedPool(
      name = "fake-kubernetes-server-suite",
      alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
    )
  private val fakeServer: FakeKubernetesServer = FakeKubernetesServer.createAndStart(sec)
  private val coreV1Api: CoreV1Api =
    FakeKubernetesTestSupport.buildCoreV1Api(fakeServer, readTimeout = Duration.Zero)

  /**
   * Wraps a k8s [[SharedIndexInformer]] and records its observed pod additions, modifications,
   * and deletions. Also records any errors reported through the exception handler.
   */
  private class LoggingPodInformer(
      sec: SequentialExecutionContext,
      namespace: String,
      appName: String) {
    private val informerFactory: SharedInformerFactory =
      new SharedInformerFactory(coreV1Api.getApiClient)

    private val added: mutable.ArrayBuffer[V1Pod] = mutable.ArrayBuffer.empty

    private val modified: mutable.ArrayBuffer[V1Pod] = mutable.ArrayBuffer.empty

    private val deleted: mutable.ArrayBuffer[V1Pod] = mutable.ArrayBuffer.empty

    private val errors: mutable.ArrayBuffer[Throwable] = mutable.ArrayBuffer.empty

    private val informer: SharedIndexInformer[V1Pod] = informerFactory.sharedIndexInformerFor(
      (params: CallGeneratorParams) => {
        coreV1Api.listNamespacedPodCall(
          /* namespace */ namespace,
          /* pretty */ null,
          /* allowWatchBookmarks */ null,
          /* _continue */ null,
          /* fieldSelector */ null,
          /* labelSelector */ s"app=$appName",
          /* limit */ null,
          /* resourceVersion */ params.resourceVersion,
          /* resourceVersionMatch */ null,
          /* sendInitialEvents */ null,
          /* timeoutSeconds */ params.timeoutSeconds,
          /* watch */ params.watch,
          /* callback */ null
        )
      },
      classOf[V1Pod],
      classOf[V1PodList],
      0L,
      (resourceClass: Class[V1Pod], error: Throwable) => sec.run { errors += error }
    )

    /** Starts this informer and captures its events. */
    def start(): Unit = {
      informer.addEventHandler(new ResourceEventHandler[V1Pod] {
        override def onAdd(pod: V1Pod): Unit = sec.run { added += pod }

        override def onUpdate(oldPod: V1Pod, newPod: V1Pod): Unit =
          sec.run { modified += newPod }

        override def onDelete(pod: V1Pod, deletedFinalStateUnknown: Boolean): Unit =
          sec.run { deleted += pod }
      })
      informer.run()
    }

    /** Returns the pod additions observed by this informer. */
    def getAddedPods(): Vector[V1Pod] = {
      Await.result(sec.call { added.toVector }, Duration.Inf)
    }

    /** Returns the pod modifications observed by this informer. */
    def getModifiedPods(): Vector[V1Pod] = {
      Await.result(sec.call { modified.toVector }, Duration.Inf)
    }

    /** Returns the pod deletions observed by this informer. */
    def getDeletedPods(): Vector[V1Pod] = {
      Await.result(sec.call { deleted.toVector }, Duration.Inf)
    }

    /** Returns the errors observed by this informer. */
    def getErrors(): Vector[Throwable] = {
      Await.result(sec.call { errors.toVector }, Duration.Inf)
    }
  }

  /** Helps accumulates expected logs and verify them against an informer as a test progresses. */
  private case class LogVerifier(
      expectedAdditions: Vector[V1Pod] = Vector.empty,
      expectedModifications: Vector[V1Pod] = Vector.empty,
      expectedDeletions: Vector[V1Pod] = Vector.empty,
      expectedErrorStatusCodes: Vector[Int] = Vector.empty
  ) {

    /** Returns a new verifier which adds `pods` to its expected pod additions. */
    def withExpectedAdditions(pods: V1Pod*): LogVerifier = {
      copy(expectedAdditions = expectedAdditions ++ pods.toVector)
    }

    /** Returns a new verifier which adds `pods` to its expected pod modifications. */
    def withExpectedModifications(pods: V1Pod*): LogVerifier = {
      copy(expectedModifications = expectedModifications ++ pods.toVector)
    }

    /** Returns a new verifier which adds `pods` to its expected pod deletions. */
    def withExpectedDeletions(pods: V1Pod*): LogVerifier = {
      copy(expectedDeletions = expectedDeletions ++ pods.toVector)
    }

    /** Returns a new verifier which adds `statusCodes` to its expected API errors. */
    def withExpectedErrors(statusCodes: Int*): LogVerifier = {
      copy(expectedErrorStatusCodes = expectedErrorStatusCodes ++ statusCodes.toVector)
    }

    /** Waits until `loggingInformer`'s logs match the expected logs. */
    def awaitLogs(loggingInformer: LoggingPodInformer): Unit = {
      AssertionWaiter("informer logs").await {
        assertResult(expectedAdditions)(loggingInformer.getAddedPods())
        assertResult(expectedModifications)(loggingInformer.getModifiedPods())
        assertResult(expectedDeletions)(loggingInformer.getDeletedPods())
        assertResult(expectedErrorStatusCodes)(
          loggingInformer.getErrors().map {
            case error: ApiException => error.getCode
            case error: Throwable => fail(s"Expected ApiException, got $error")
          }
        )
      }
    }
  }

  override def afterAll(): Unit = {
    fakeServer.stop()
    super.afterAll()
  }

  /** Calls the fake's list-pods endpoint through the generated Kubernetes API. */
  private def listPods(namespace: String, labelSelector: String): V1PodList = {
    coreV1Api.listNamespacedPod(
      /* namespace */ namespace,
      /* pretty */ null,
      /* allowWatchBookmarks */ null,
      /* _continue */ null,
      /* fieldSelector */ null,
      /* labelSelector */ labelSelector,
      /* limit */ null,
      /* resourceVersion */ null,
      /* resourceVersionMatch */ null,
      /* sendInitialEvents */ null,
      /* timeoutSeconds */ null,
      /* watch */ null
    )
  }

  /** Returns a pod with a unique Kubernetes UID. */
  private def buildPod(): V1Pod = {
    val uid: String = UUID.randomUUID().toString
    val pod: V1Pod = FakeKubernetesTestSupport.buildReadyPod(uid, podIP = "127.0.0.1")
    pod.getMetadata.setName(uid)
    pod
  }

  /** Extracts pod UIDs in response order. */
  private def podUids(podList: V1PodList): List[String] = {
    podList.getItems.asScala.map(_.getMetadata.getUid).toList
  }

  test("listNamespacedPod returns scoped pods and increasing resource versions") {
    // Test plan: verify that polling returns pods for the requested scope and advances the resource
    // version after a pod update.
    val firstNamespace: String = s"first-ns-$getSafeName"
    val firstAppName: String = s"first-app-$getSafeName"
    val secondNamespace: String = s"second-ns-$getSafeName"
    val secondAppName: String = s"second-app-$getSafeName"
    val firstPod: V1Pod = buildPod()
    val secondPod: V1Pod = buildPod()

    fakeServer.setPods(firstNamespace, firstAppName, Some(List(firstPod)))
    fakeServer.setPods(secondNamespace, secondAppName, Some(List(secondPod)))

    val firstResponse: V1PodList = listPods(firstNamespace, s"app=$firstAppName")
    val secondResponse: V1PodList = listPods(secondNamespace, s"app=$secondAppName")
    assertResult(List(firstPod.getMetadata.getUid))(podUids(firstResponse))
    assertResult(List(secondPod.getMetadata.getUid))(podUids(secondResponse))

    val initialResourceVersion: Long = firstResponse.getMetadata.getResourceVersion.toLong
    val replacementPod: V1Pod = buildPod()
    fakeServer.setPods(firstNamespace, firstAppName, Some(List(replacementPod)))

    val updatedResponse: V1PodList = listPods(firstNamespace, s"app=$firstAppName")
    assertResult(List(replacementPod.getMetadata.getUid))(podUids(updatedResponse))
    assert(updatedResponse.getMetadata.getResourceVersion.toLong > initialResourceVersion)
  }

  test("listNamespacedPod returns an empty items collection") {
    // Test plan: verify that polling a configured empty scope returns an empty items collection.
    val namespace: String = s"ns-$getSafeName"
    val appName: String = s"app-$getSafeName"
    fakeServer.setPods(namespace, appName, Some(List.empty))

    assert(listPods(namespace, s"app=$appName").getItems.isEmpty)
  }

  test("listNamespacedPod returns an empty list when items is omitted") {
    // Test plan: verify that the Kubernetes client normalizes a response with no items field to a
    // non-null empty list.
    val namespace: String = s"ns-$getSafeName"
    val appName: String = s"app-$getSafeName"
    fakeServer.setPods(namespace, appName, None)

    val podList: V1PodList = listPods(namespace, s"app=$appName")
    assert(podList.getItems.isEmpty)
  }

  test("listNamespacedPod returns Kubernetes API errors") {
    // Test plan: verify that polling surfaces a configured Kubernetes API error and response body.
    val namespace: String = s"ns-$getSafeName"
    val appName: String = s"app-$getSafeName"
    fakeServer.setErrorResponse(namespace, appName, statusCode = 503)

    val error: ApiException = intercept[ApiException] {
      listPods(namespace, s"app=$appName")
    }
    assertResult(503)(error.getCode)
    assert(error.getResponseBody.contains("fake error"))
  }

  test("listNamespacedPod validates selectors and records authorization") {
    // Test plan: verify that polling rejects an invalid selector and records authorization from a
    // valid request.
    val namespace: String = s"ns-$getSafeName"
    val appName: String = s"app-$getSafeName"
    fakeServer.setPods(namespace, appName, Some(List.empty))

    val error: ApiException = intercept[ApiException] {
      listPods(namespace, s"component=$appName")
    }
    assertResult(400)(error.getCode)

    val authorization: String = "Bearer fake-kubernetes-server-suite"
    coreV1Api.getApiClient.addDefaultHeader("Authorization", authorization)
    listPods(namespace, s"app=$appName")
    assertResult(Some(authorization))(
      Await.result(fakeServer.getLastAuthorizationHeader(namespace, appName), Duration.Inf)
    )
  }

  test("informer reports pod additions, modifications, and deletions") {
    // Test plan: verify that the informer reports additions, modifications, and deletions after
    // successive pod list updates.
    val namespace: String = s"ns-$getSafeName"
    val appName: String = s"app-$getSafeName"
    val initialPod: V1Pod = buildPod()
    val addedPod: V1Pod = buildPod()
    fakeServer.setPods(namespace, appName, Some(List(initialPod)))

    val loggingInformer: LoggingPodInformer =
      new LoggingPodInformer(sec, namespace, appName)
    loggingInformer.start()
    val initialLogs: LogVerifier = LogVerifier().withExpectedAdditions(initialPod)
    initialLogs.awaitLogs(loggingInformer)

    fakeServer.setPods(namespace, appName, Some(List(initialPod, addedPod)))
    val updatedLogs: LogVerifier = initialLogs
      .withExpectedAdditions(addedPod)
      .withExpectedModifications(initialPod)
      .withExpectedErrors(410)
    // Even though `initialPod` is the same as before, the client will still report it.
    updatedLogs.awaitLogs(loggingInformer)

    fakeServer.setPods(namespace, appName, Some(List(addedPod)))
    val finalLogs: LogVerifier = updatedLogs
      .withExpectedModifications(addedPod)
      .withExpectedDeletions(initialPod)
      .withExpectedErrors(410)
    finalLogs.awaitLogs(loggingInformer)
  }

  test("informer recovers from an error after observing pods") {
    // Test plan: verify that an informer observes an error after its initial pods and then reports
    // pod updates after the server recovers.
    val namespace: String = s"ns-$getSafeName"
    val appName: String = s"app-$getSafeName"
    val initialPod: V1Pod = buildPod()
    val recoveredPod: V1Pod = buildPod()
    fakeServer.setPods(namespace, appName, Some(List(initialPod)))

    val loggingInformer: LoggingPodInformer =
      new LoggingPodInformer(sec, namespace, appName)
    loggingInformer.start()
    val initialLogs: LogVerifier = LogVerifier().withExpectedAdditions(initialPod)
    initialLogs.awaitLogs(loggingInformer)

    fakeServer.setErrorResponse(namespace, appName, statusCode = 503)
    val errorLogs: LogVerifier = initialLogs.withExpectedErrors(503)
    errorLogs.awaitLogs(loggingInformer)

    fakeServer.setPods(namespace, appName, Some(List(recoveredPod)))
    val recoveredLogs: LogVerifier = errorLogs
      .withExpectedAdditions(recoveredPod)
      .withExpectedDeletions(initialPod)
    recoveredLogs.awaitLogs(loggingInformer)
  }

  test("informer recovers when its initial request returns an error") {
    // Test plan: verify that an informer observes an initial error and reports pods after the
    // server recovers.
    val namespace: String = s"ns-$getSafeName"
    val appName: String = s"app-$getSafeName"
    val recoveredPod: V1Pod = buildPod()
    fakeServer.setErrorResponse(namespace, appName, statusCode = 503)

    val loggingInformer: LoggingPodInformer =
      new LoggingPodInformer(sec, namespace, appName)
    loggingInformer.start()
    val errorLogs: LogVerifier = LogVerifier().withExpectedErrors(503)
    errorLogs.awaitLogs(loggingInformer)

    fakeServer.setPods(namespace, appName, Some(List(recoveredPod)))
    val recoveredLogs: LogVerifier = errorLogs.withExpectedAdditions(recoveredPod)
    recoveredLogs.awaitLogs(loggingInformer)
  }

}

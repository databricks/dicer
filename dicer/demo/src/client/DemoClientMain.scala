package com.databricks.dicer.demo.client

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.Random
import scala.util.control.NonFatal

import io.grpc.{Grpc, InsecureChannelCredentials, ManagedChannelBuilder}

import com.databricks.DatabricksMain
import com.databricks.api.proto.dicer.demo.{
  DemoServiceGrpc,
  DeleteValueRequestP,
  DeleteValueResponseP,
  GetValueRequestP,
  GetValueResponseP,
  PutValueRequestP,
  PutValueResponseP
}
import com.databricks.backend.common.util.Project
import com.databricks.caching.util.{PrefixLogger, ServerConf}
import com.databricks.conf.Config
import com.databricks.conf.trusted.ProjectConf
import com.databricks.dicer.demo.common.DemoCommon
import com.databricks.dicer.external.{Clerk, ClerkConf, ResourceAddress, SliceKey, Target}
import com.databricks.rpc.tls.{TLSOptions, TLSOptionsMigration}

/** Configuration for the demo client. */
class DemoClientConf(project: Project.Project, rawConfig: Config)
    extends ProjectConf(project, rawConfig)
    with ClerkConf
    with ServerConf {

  // SSL configuration is read from databricks.rpc.cert/key/truststore via ServerConf.
  // When those config keys are set, sslArgs will be populated and SSL will be enabled.
  override def dicerTlsOptions: Option[TLSOptions] = TLSOptionsMigration.convert(sslArgs)
}

/**
 * DemoClient demonstrates a Dicer-aware client.
 *
 * This client:
 * - Uses the Clerk to discover which server owns each key
 * - Continuously sends random operations (GET, PUT, DELETE) for random keys (0-9999)
 * - Operation distribution: 60% GET, 30% PUT, 10% DELETE
 * - Load shifts every 60 seconds to simulate changing access patterns
 * - Logs operation type, latency, and response information
 */
object DemoClientMain extends DatabricksMain(Project.DemoClient) {
  private val prefixLogger = PrefixLogger.create(getClass, "")

  /** Total number of keys that we will send requests for (0 to NUM_KEYS-1). */
  private val NUM_KEYS: Int = 10000

  /** Maximum queries per second the client will send across all servers. */
  private val MAX_QPS: Int = 1000

  /** Minimum delay between requests to stay under the max QPS. */
  private val MIN_DELAY: FiniteDuration = 1.second / MAX_QPS

  /**
   * Simulate changing access patterns over time. The client is single-threaded so we don't need to
   * lock this state.
   */
  private val loadShifter = new LoadShifter(numKeys = NUM_KEYS)

  override def wrappedMain(args: Array[String]): Unit = {
    val conf = new DemoClientConf(Project.DemoClient, rawConfig)
    val target = Target(DemoCommon.TARGET_NAME)

    // Get the Slicelet hostname from environment variable.
    // In Kubernetes, this would be the ClusterIP service name for the DemoServer.
    val sliceletHostName: String = Option(System.getenv("SLICELET_HOST"))
      .getOrElse(throw new IllegalStateException("SLICELET_HOST environment variable must be set"))

    logger.info(s"Creating Clerk for ${DemoCommon.TARGET_NAME}, connecting to $sliceletHostName")

    // The stub factory creates a gRPC stub to each server URI.
    // When TLS is configured, use secure channel; otherwise use plaintext.
    //
    // For TLS connections, we use overrideAuthority() because:
    // - Slicelets register using pod IP addresses (e.g., https://10.244.0.9:8080)
    // - Server certificates contain DNS SANs (demo-server.demo-server.svc.cluster.local)
    // - overrideAuthority() tells gRPC to verify against the DNS name instead of the IP
    val stubFactory: ResourceAddress => DemoServiceGrpc.DemoServiceStub = { resourceAddress =>
      val uri = resourceAddress.uri
      val channelBuilder: ManagedChannelBuilder[_] = conf.dicerTlsOptions match {
        case Some(tlsOptions) =>
          Grpc
            .newChannelBuilderForAddress(uri.getHost, uri.getPort, tlsOptions.channelCredentials())
            .overrideAuthority("demo-server.demo-server.svc.cluster.local")
        case None =>
          Grpc.newChannelBuilderForAddress(
            uri.getHost,
            uri.getPort,
            InsecureChannelCredentials.create()
          )
      }
      DemoServiceGrpc.stub(channelBuilder.build())
    }

    // Create the Clerk which will connect to Slicelets to learn about assignments.
    val clerk: Clerk[DemoServiceGrpc.DemoServiceStub] =
      Clerk.create(conf, target, sliceletHostName, stubFactory)

    logger.info("Waiting for Clerk to be ready...")
    // Wait for up to 60 seconds for the initial assignment to be received, throws if this times
    // out. For this use case we take a hard dependency on being able to route requests to the
    // demo-server, if that is undesirable then this can be skipped and instead handle the case of
    // `Clerk.getStubForKey` returning `None`.
    Await.result(clerk.ready, 60.seconds)
    logger.info("Clerk is ready, starting request loop")

    val random: Random = new Random()
    var requestCounter: Long = 0L

    // Continuous loop sending requests to random keys.
    while (true) {
      val startTime: Long = System.currentTimeMillis()
      requestCounter += 1
      val uniform: Double = random.nextDouble()
      // Bias the keys towards lower values (simulating a non-uniform key distribution).
      val baseKey: Int = (NUM_KEYS * math.pow(uniform, 4.0)).toInt
      val key: Int = loadShifter.shiftKey(baseKey)
      val sliceKey: SliceKey = DemoCommon.toSliceKey(key)

      // Randomly select operation: 60% GET, 30% PUT, 10% DELETE.
      val opSelection: Double = random.nextDouble()
      val operation: Operation =
        if (opSelection < 0.60) Operation.GET
        else if (opSelection < 0.90) Operation.PUT
        else Operation.DELETE

      // Use Clerk to find the server for this key.
      clerk.getStubForKey(sliceKey) match {
        case Some(stub) =>
          try {
            val logDetails: String = operation match {
              case Operation.GET =>
                val request: GetValueRequestP = GetValueRequestP(key = Some(key))
                val responseFuture: Future[GetValueResponseP] = stub.getValue(request)
                val response: GetValueResponseP = Await.result(responseFuture, 5.seconds)
                f"Key=$key%4d, Value=${response.value}"

              case Operation.PUT =>
                val value: String = s"Value-$key-${System.currentTimeMillis()}"
                val request: PutValueRequestP =
                  PutValueRequestP(key = Some(key), value = Some(value))
                val responseFuture: Future[PutValueResponseP] = stub.putValue(request)
                Await.result(responseFuture, 5.seconds)
                f"Key=$key%4d, Value=$value"

              case Operation.DELETE =>
                val request: DeleteValueRequestP = DeleteValueRequestP(key = Some(key))
                val responseFuture: Future[DeleteValueResponseP] = stub.deleteValue(request)
                Await.result(responseFuture, 5.seconds)
                f"Key=$key%4d"
            }

            val latencyMs: Long = System.currentTimeMillis() - startTime

            prefixLogger.info(
              f"Request #$requestCounter: Op=$operation%6s, $logDetails, Latency=${latencyMs}ms",
              every = 1.second
            )
          } catch {
            case NonFatal(e) =>
              val latencyMs: Long = System.currentTimeMillis() - startTime
              prefixLogger.error(
                f"Request #$requestCounter: Op=$operation%6s, Key=$key%4d, " +
                s"ERROR: ${e.getMessage}, Latency=${latencyMs}ms"
              )
          }

        case None =>
          prefixLogger.error(
            f"Request #$requestCounter: Op=$operation%6s, Key=$key%4d. " +
            s"Unexpected: could not get stub for key even though Clerk is ready",
            every = 1.second
          )
      }

      // Rate limiting: sleep to maintain target QPS.
      val iterationDurationMillis: Long = System.currentTimeMillis() - startTime
      if (iterationDurationMillis < MIN_DELAY.toMillis) {
        Thread.sleep(MIN_DELAY.toMillis - iterationDurationMillis)
      }
    }
  }
}

/** Enum for operations on the demo server. */
sealed trait Operation
object Operation {
  case object GET extends Operation
  case object PUT extends Operation
  case object DELETE extends Operation
}

/**
 * LoadShifter applies a shifting offset to keys to simulate changing access patterns over time.
 * The offset changes periodically (every 60 seconds) to simulate how real-world
 * workloads may shift their access patterns over time. NOT thread-safe.
 *
 * @param numKeys Total number of keys in the keyspace
 */
class LoadShifter(numKeys: Int) {

  /** How often to shift the load pattern, in milliseconds (60 seconds). */
  private val SHIFT_INTERVAL_MS: Long = 60000

  private val prefixLogger = PrefixLogger.create(getClass, "")

  /** The last time the load was shifted. */
  private var lastShiftTime: Long = System.currentTimeMillis()

  /** Current offset added to keys to simulate shifting access patterns. */
  private var keyOffset: Int = 0

  /**
   * Applies a shifting offset to the base key. The offset changes periodically
   * to simulate changing access patterns over time.
   *
   * @param baseKey The original key to shift
   * @return The shifted key, wrapped around to stay within [0, numKeys)
   */
  def shiftKey(baseKey: Int): Int = {
    val currentTime: Long = System.currentTimeMillis()
    if (currentTime - lastShiftTime >= SHIFT_INTERVAL_MS) {
      keyOffset = currentTime.hashCode().abs % numKeys
      lastShiftTime = currentTime
      prefixLogger.info(s"Load shifted: new offset = $keyOffset")
    }
    (baseKey + keyOffset) % numKeys
  }
}

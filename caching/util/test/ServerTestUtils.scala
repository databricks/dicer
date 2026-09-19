package com.databricks.caching.util

import java.util.concurrent.{ExecutorService, Executors}
import scala.util.DynamicVariable

import com.databricks.rpc.DatabricksServerWrapper
import io.grpc.ServerBuilder

object ServerTestUtils {

  /**
   * A simple trait for testing attribution context propagation.
   * Uses DynamicVariable to enable context propagation across execution contexts.
   */
  trait AttributionContextPropagationTester {

    /**
     * The current attribution context. Use DynamicVariable which gives us the desired behavior for
     * context propagation: the current value can be updated within a specific block of code and
     * automatically restored after the block completes. Each thread maintains its own stack; new
     * threads receive a copy of the bindings from the parent thread and further modifications are
     * independent.
     */
    private val attributionContext = new DynamicVariable[String](null)

    /**
     * Executes a block of code with a specific attribution context ID.
     * The context is automatically restored after the block completes.
     *
     * @param id The attribution context ID to set
     * @param block The code block to execute
     * @return The result of executing the block
     */
    def withAttributionContextId[T](id: String)(block: => T): T = {
      attributionContext.withValue(id)(block)
    }

    /**
     * Gets the current attribution context ID.
     *
     * @return The current attribution context ID, or null if none is set
     */
    def getAttributionContextId: String = {
      attributionContext.value
    }
  }

  /**
   * Creates a generic server which binds to `port` and ignores all incoming requests. Useful for
   * testing that outgoing network requests have a deadline set.
   *
   * @param port The port to bind to. Supplying `port = 0` will choose a random port.
   */
  def createUnresponsiveServer(port: Int): DatabricksServerWrapper = {
    val serverBuilder = ServerBuilder.forPort(port)
    val executor: ExecutorService = Executors.newFixedThreadPool(1)
    val server = new DatabricksServerWrapper(serverBuilder.executor(executor).build, executor)
    server.start()
    server
  }
}

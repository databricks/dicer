package com.databricks.caching.util

import java.util.concurrent.CompletableFuture
import io.etcd.jetcd.Watch.Listener

/**
 * Minimal OSS implementation of [[JetcdStatusUtils]]. In OSS, jetcd isn't relocated, so its status
 * exceptions already use the same gRPC classes as the rest of the code and need no conversion.
 */
private[util] object JetcdStatusUtils {

  /** Runs `body`, letting its exceptions through as is. */
  def toGrpcShadedExceptions[T](body: => T): T = body

  /** Returns `future` itself, since its failures need no conversion. */
  def toGrpcShadedFailures[T](future: CompletableFuture[T]): CompletableFuture[T] = future

  /** Returns `listener` itself, since its errors need no conversion. */
  def toGrpcShadedErrors(listener: Listener): Listener = listener
}

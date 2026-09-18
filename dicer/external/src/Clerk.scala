package com.databricks.dicer.external

import java.net.URI
import javax.annotation.concurrent.ThreadSafe

import scala.concurrent.Future

import com.databricks.dicer.client.ClerkImpl

/**
 * The class that allows a caller to route requests to the right resource (e.g., pod) in the same
 * cluster as the caller.
 *
 * Cross-cluster routing is _not_ supported via this API as of May 2025. Please reach out to
 * the maintainers if you have a cross-cluster use case.
 *
 * If a Clerk is being used for forwarding (e.g., an RPC is first sent to a random pod, which
 * then sends it to an assigned peer pod in the same service), there is a risk of forwarding loops,
 * e.g., where two servers believe the other is assigned a particular key and ping pong a
 * request. To avoid this, we recommend using a separate logical RPC for the initial unaffinitized
 * request and the subsequent affinitized request. Note that forwarding in general should be avoided
 * when possible: https://sre.google/sre-book/addressing-cascading-failures/. Instead, consider
 * proxying, redirects, etc...
 *
 * The Clerk is thread-safe, and all calls on the Clerk are synchronous and non-blocking.
 */
@ThreadSafe
final class Clerk[Stub <: AnyRef] private[dicer] (private[dicer] val impl: ClerkImpl[Stub]) {

  /**
   * For the given `key`, returns the stub for the resource affinitized to that key. If there are
   * multiple resources affinitized to the key, a random resource will be chosen. If no resource is
   * known, `None` will be returned. A resource will not be known until the Clerk has connected to
   * Dicer and has an initial assignment. You can determine when the Clerk has received an initial
   * assignment by checking the value of the [[ready()]] future.
   */
  def getStubForKey(key: SliceKey): Option[Stub] = impl.getStubForKey(key)

  /**
   * A future that succeeds when this Clerk has received an initial resource mapping and is ready to
   * route requests.
   *
   * If the future fails, it means that there is a permanent error preventing this Clerk from
   * starting (for example, a configuration error for the [[Target]]).
   */
  def ready: Future[Unit] = impl.ready

  object forTest {

    /**
     * Stop the clerk in test environment. No method should be called on the Clerk after it has
     * been stopped.
     */
    def stop(): Unit = impl.stop()
  }
}

/** Companion object for [[Clerk]]. */
object Clerk {

  /**
   * REQUIRES: Only one `Clerk` may be created per `target` per process, except in tests.
   *
   * For a given [[Target]], this class allows the caller to determine the Stub for
   * the resource(s) that are handling a particular [[SliceKey]].
   *
   * For generality, Dicer maps the slice key to a [[ResourceAddress]], then calls an
   * application-defined stub factory to map the address to an application-specific Stub type.
   *
   * The stub is typically an RPC stub, but can be any type, even just the raw resource address
   * if the application needs total control.
   *
   * Dicer caches stubs to avoid creating a stub on every request.
   *
   * The clerk will not immediately be ready to route as network communication is needed to obtain
   * the initial resource mapping. Applications may observe completion of the [[Clerk.ready]] future
   * to learn when the Clerk has received an initial resource assignment.
   *
   * The mapping of SliceKeys to resources is updated asynchronously, so there is no guarantee that
   * the Slicelet to which a request is routed by the Clerk will still own the key when it receives
   * that request. As a result, the application should check for ownership/affinity on the Slicelet
   * side using the Slicelet's API when handling a request.
   *
   * @param sliceletHostName This is the domain name address of the Slicelet service that serves
   *                         assignments, usually backed by clusterIP.
   */
  // TODO(<internal bug>): Enforce this REQUIRES clause.
  def create[Stub <: AnyRef](
      clerkConf: ClerkConf,
      target: Target,
      sliceletHostName: String,
      stubFactory: ResourceAddress => Stub): Clerk[Stub] = {
    val watchAddress: URI = clerkConf.getSliceletURI(sliceletHostName)
    val clerkImpl =
      ClerkImpl.create[Stub](clerkConf, target, watchAddress, stubFactory, sourceIpOpt = None)
    new Clerk[Stub](clerkImpl)
  }
}

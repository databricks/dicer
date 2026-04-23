package com.databricks.dicer.external

import javax.annotation.concurrent.ThreadSafe

import com.databricks.dicer.client.{SliceKeyHandleImpl, SliceletImpl}

/**
 * The class that allows a server to be assigned keys by Dicer. Clerks route requests to it.
 */
@ThreadSafe
final class Slicelet private (private[dicer] val impl: SliceletImpl) {

  /**
   * REQUIRES: Must not be called more than once.
   *
   * Starts the Slicelet:
   *
   *  - If a listener is given, it will be called whenever the Slicelet learns about a new
   *    assignment.
   *  - Connects to the Dicer service to learn about assignments as they evolve and to inform Dicer
   *    that this server is available to handle RPCs (and may be included in future assignments).
   *  - Starts the Slicelet RPC service that is used internally by Dicer Clerks to learn about
   *    assignments and for other internal protocols.
   *
   * Note that other methods may be called even before the Slicelet is started. In that case, they
   * will behave as if the Slicelet has no keys assigned to it.
   *
   * @param selfPort the port on the local server to which Clerk requests will be routed. Used in
   *                 conjunction with the pod's IP address.
   * @param listenerOpt optional listener that is called when the assignment changes.
   */
  def start(selfPort: Int, listenerOpt: Option[SliceletListener]): this.type = {
    impl.start(selfPort, listenerOpt)
    this
  }

  /**
   * Create a handle that can be used to track ownership of a key during an in-progress operation.
   * The caller MUST call [[SliceKeyHandle.close]] once the operation is complete (e.g. at the end
   * of the RPC handler). [[SliceKeyHandle.isAssignedContinuously]] can be checked before
   * externalizing any results. See the comment for [[SliceKeyHandle]] for more details.
   *
   * The caller should call [[SliceKeyHandle.incrementLoadBy]] to report load to Dicer for ''every''
   * request received by this Slicelet, even requests which have been load shed or for a key which
   * is not owned by this Slicelet.
   */
  def createHandle(key: SliceKey): SliceKeyHandle = {
    new SliceKeyHandle(impl.createHandle(key))
  }

  /**
   * Returns the Slices that are assigned to this server. See remarks on
   * [[SliceKeyHandle.isAssignedContinuously]].
   *
   * Note that the type is `Seq[Slice]`, unfortunately `assignedSlices.contains(key: SliceKey)`
   * compiles but will always be false. If you want to check whether a key is currently assigned,
   * use [[SliceKeyHandle.isAssignedContinuously]].
   */
  def assignedSlices: Seq[Slice] = impl.assignedSlicesSet.slices

  override def toString: String = impl.toString

  object forTest {

    /**
     * If [[start]] has been called, returns the resource address for this Slicelet, i.e., the
     * address on which the application is listening. Please contact the project maintainers for non-test
     * use-case.
     */
    // TODO(<internal bug>): Replace usages of Slicelet.forTest.resourceAddress with
    //  SliceletAccessor.resourceAddress
    @throws[IllegalStateException]("if start() has not been called")
    def resourceAddress: ResourceAddress = impl.squid.resourceAddress

    /**
     * Stop the slicelet in the test environment. Please contact the project maintainers for non-test
     * use-case.
     */
    def stop(): Unit = impl.forTest.stop()
  }
}

/** Companion object for [[Slicelet]]. */
object Slicelet {

  /**
   * REQUIRES: Only one Slicelet instance may be created per process, except in tests. See
   * DicerTestEnvironment for how to create multiple Slicelets in tests.
   *
   * Creates a Slicelet. The Slicelet MUST be started using [[Slicelet.start()]] before it is used.
   *
   * @param sliceletConf configuration for the Slicelet.
   * @param target identifies the set of resources sharded by Dicer. This server is one such
   *               resource: once the Slicelet has been started, Dicer will assign keys to it.
   */
  @throws[IllegalStateException](
    "if this is a data plane Slicelet ([[SliceletConf.watchFromDataPlane]] is true) but the " +
    "required WhereAmI information is not available in the binary. See " +
    "<internal link>). This should typically be considered a fatal error for most Dicer " +
    "sharded applications."
  )
  def apply(sliceletConf: SliceletConf, target: Target): Slicelet = {
    new Slicelet(SliceletImpl.createForExternal(sliceletConf, target))
  }

  private[external] object forTestStatic {

    /**
     * Creates a Slicelet whose implementation is the given `sliceletImpl`. See [[apply]]
     * for requirements and behavior.
     */
    def createFromImpl(sliceletImpl: SliceletImpl): Slicelet = {
      new Slicelet(sliceletImpl)
    }
  }
}

/**
 * A handle represents an in-progress operation where the Slicelet wants to track ownership of a
 * key. It can call [[isAssignedContinuously]] before externalizing any results. [[close]] must be
 * called once the operation is complete (e.g. at the end of the RPC handler). It is recommended to
 * use [[scala.util.Using]] for this (you might need to add a bazel dep on
 * "{parent}/org.scala-lang.modules/scala-collection-compat_{scala}").
 *
 * Example:
 * {{{
 *   Using.resource(slicelet.createHandle(key)) { handle: SliceKeyHandle =>
 *     if (handle.isAssignedContinuously) {
 *       processRequest(handle, req)
 *     } else {
 *       // Create appropriate exception.
 *       throw DatabricksServiceException(ErrorCode.INTERNAL_ERROR,
 *         s"$key does not seem to be owned by this pod")
 *     }
 *   }
 *
 *   def processRequest(handle: SliceKeyHandle, req: Request): Unit = {
 *     val state = readFromDb()
 *     ...
 *     if (handle.isAssignedContinuously) {
 *       writeToDb(newState)
 *     }
 *   }
 * }}}
 *
 * If the operation is asynchronous and returns [[scala.concurrent.Future]], use
 * [[scala.concurrent.Future.andThen]] to close the handle at the end of the operation, taking care
 * to also handle any exceptions created by synchronous code:
 * {{{
 *   val handle: SliceKeyHandle = slicelet.createHandle(key)
 *   try {
 *     if (handle.isAssignedContinuously) {
 *       processRequest(handle, req)
 *         .andThen { case _ => handle.close() }
 *     } else {
 *       // Create appropriate exception.
 *       throw DatabricksServiceException(ErrorCode.INTERNAL_ERROR,
 *         s"$key does not seem to be owned by this pod")
 *     }
 *   } catch {
 *     case t: Throwable =>
 *       // Handle exceptions from else case or thrown directly (i.e. synchronously) by
 *       // processRequest.
 *       handle.close()
 *       throw t;
 *   }
 * }}}
 *
 */
@ThreadSafe
final class SliceKeyHandle private[external] (impl: SliceKeyHandleImpl) extends AutoCloseable {

  /**
   * Returns whether we believe `key` has been continuously assigned to this server since the handle
   * was created. When true, it is *very likely* that the key was assigned to the current server
   * during handle creation, and has not been unassigned from the current server at any point
   * afterward, but it is not guaranteed. This is because this check is performed locally against
   * the latest assignment known to this Slicelet and different Slicelets will not learn about
   * assignment changes at the same instant.
   *
   * For example,
   *
   * {{{
   *   // Assume the example code is executed on pod0.
   *
   *   // `key` is assigned on pod0.
   *   val handle: SliceKeyHandle = slicelet.createHandle(key)
   *   assert(handle.isAssignedContinuously == true)
   *
   *   // `key` is still assigned on pod0 in a newer assignment.
   *   assert(handle.isAssignedContinuously == true)
   *
   *   // `key` is re-assigned to pod1.
   *   assert(handle.isAssignedContinuously == false)
   *
   *   // `key` is assigned back on pod0.
   *   assert(handle.isAssignedContinuously == false)
   *
   *   handle.close()
   * }}}
   */
  def isAssignedContinuously: Boolean = impl.isAssignedContinuously

  /**
   * Accumulates load for `key` by `value`. This is used by Dicer to make load balancing decisions
   * in its assignments. `value` should be positive: negative or zero values are ignored.
   */
  def incrementLoadBy(value: Int): Unit = impl.incrementLoadBy(value)

  /** The [[SliceKey]] associated with this handle. */
  def key: SliceKey = impl.key

  /** Marks the end of the pending operation represented by this handle. */
  override def close(): Unit = impl.close()
}

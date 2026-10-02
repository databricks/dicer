package com.databricks.dicer.assigner

import java.util.UUID

import scala.concurrent.Future

import com.databricks.dicer.assigner.ResourceFetcher.ResourceFetchResult

/**
 * Fetches a set of resources, which are routable units capable of performing work.
 *
 * A fetcher is not closeable and may consume shared resources (like network connections)
 * for the lifetime of the process after it is created.
 *
 * The fetch result is a snapshot of the current resources that is at least as fresh as when
 * fetch() was called.
 */
private[assigner] trait ResourceFetcher {

  /**
   * Fetches the set of resources that the fetcher is responsible for and returns a future that
   * completes with a [[ResourceFetchResult]]. If the fetch cannot be performed, this method
   * returns a future that fails with a [[io.grpc.StatusException]] indicating the
   * error.
   */
  def fetch(): Future[ResourceFetchResult]
}

private[assigner] object ResourceFetcher {

  /**
   * The set of resources from a successful fetch.
   *
   * @param resources a set of resources, which may be empty.
   */
  final case class ResourceFetchResult(resources: Seq[Resource])
}

/**
 * A status that indicates whether the resource is capable of performing work.
 */
private[assigner] sealed trait ResourceReadiness

private[assigner] object ResourceReadiness {

  /** No readiness reported, or unrecognized value. */
  case object Unspecified extends ResourceReadiness

  /** Resource is not ready to receive work. */
  case object NotReady extends ResourceReadiness

  /** Resource is ready to receive work. */
  case object Ready extends ResourceReadiness

  /** Resource is terminating and should not be given new work. */
  case object Terminating extends ResourceReadiness
}

/**
 * A routable unit capable of performing work.
 *
 * @param nameOpt the human-readable identifier, or `None` if not available at the time
 *                that the resource was fetched.
 * @param uuidOpt the unique identifier, or `None` if not available at the time that the
 *                resource was fetched.
 * @param ipOpt the IP address, or `None` if not available at the time that the resource
 *              was fetched.
 * @param readiness the readiness status.
 */
private[assigner] final case class Resource(
    nameOpt: Option[String],
    uuidOpt: Option[UUID],
    ipOpt: Option[String],
    readiness: ResourceReadiness)

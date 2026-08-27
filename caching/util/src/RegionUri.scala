package com.databricks.caching.util

import com.databricks.infra.lib.{InfraDataModel, InfraResource, ResourcePath}

/**
 * A validated region IDM URI, e.g. "region:prod/cloud1/public/region1" (see <internal link>).
 *
 * An instance can only be obtained through [[RegionUri.fromUri]], which resolves the URI against
 * the [[InfraDataModel]] owned by the Infra team, so holding one is a proof that the URI named a
 * real region in the model.
 */
final class RegionUri private (val uri: String) {

  override def toString: String = uri

  override def hashCode(): Int = uri.hashCode()

  override def equals(obj: Any): Boolean = obj match {
    case that: RegionUri => this.uri == that.uri
    case _ => false
  }
}

object RegionUri {

  private val logger: PrefixLogger = PrefixLogger.create(getClass, "")

  /**
   * Parses `uri` as a region IDM URI, resolving it against the embedded [[InfraDataModel]]. Returns
   * `None` when `uri` is not a `region:` URI or names a region absent from the model (see
   * [[ResourcePath.getFromUri]]).
   *
   * The embedded model is co-versioned with the binary, so a region turned up after the binary was
   * built resolves to `None`.
   */
  def fromUri(uri: String): Option[RegionUri] = {
    val regionOpt: Option[InfraResource.RegionResource] =
      ResourcePath.getFromUri(InfraDataModel.fromEmbedded, uri).collect {
        case region: InfraResource.RegionResource => region
      }
    val resultOpt: Option[RegionUri] = regionOpt.flatMap(_.uri).map(new RegionUri(_))
    IdmUriParseMetrics.recordParse(
      IdmUriParseMetrics.UriType.Region,
      succeeded = resultOpt.isDefined
    )
    if (resultOpt.isEmpty) {
      logger.warn(s"Failed to parse region IDM URI: $uri")
    }
    resultOpt
  }
}

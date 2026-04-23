package com.databricks.dicer.client

import scala.concurrent.Future

/**
 * No-op implementation of ClientDPage for the open-source build. The DPage debug endpoint
 * depends on internal Databricks infrastructure that is not available in OSS.
 */
// TODO(<internal bug>): Support DPages in the Dicer OSS build.
private[client] object ClientDPage {

  /**
   * No-op in the OSS build: DPage registration requires internal infrastructure.
   *
   * @param getSlicezJsonFut unused in OSS
   * @param dpageNamespace unused in OSS
   */
  def setup(getSlicezJsonFut: () => Future[String], dpageNamespace: String): Unit = {}
}

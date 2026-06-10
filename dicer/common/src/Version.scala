package com.databricks.dicer.common

import com.databricks.caching.util.{BuildInfo, PrefixLogger}
import com.databricks.dicer.external.Target
import io.prometheus.client.Gauge
import com.databricks.dicer.common.TargetHelper.TargetOps

object Version {

  private[this] val logger = PrefixLogger.create(this.getClass, "")

  // Used to report the version of the code that the client is running. Allows us to figure out when
  // changes are rolled out and get a sense for when we can make backwards-incompatible changes on
  // the server. Do note it is possible that the client service ends up rolling back some time
  // later, so we should always leave a sufficient buffer of time. The version number represents
  // seconds since the epoch (`date +%s`) on the creation date.
  //
  // Version changelog
  //
  // 1761600610 (2025-10-27):
  // - Added support for receiving serialized assignments from the server in the watch requests.
  //   Clients with this version or later will indicate the support through
  //   `supports_serialized_assignment` to the server in their watch requests, enabling the server
  //   to respond with serialized assignment.
  //
  // 1712123631 (2024-04-02):
  // - Added generalized support for syncing token maps in the client rather than individual tokens.
  //   This allows arbitrary sets of versioned values to be synced between assigners via clients.
  //
  // 1711357000 (2024-03-25):
  // - Changed ContinuousAssignments to allow less than or equal generation number as the
  //   SliceAssignment (previously it was just less than). This will be used for state transfer.
  //
  // 1710541787 (2024-03-15):
  // - Eliminate usage of hazzers for redirect protos so that even the empty proto results in the
  //   desired redirect being sent. See <internal link>.
  //   (As of that PR, the Assigner server never sends a non-empty redirect proto, so even older
  //   clients will still do the right thing as long as the Assigner server is sufficiently up to
  //   date.)
  //
  // 1702512000 (2023-12-14):
  // - First version that we track.
  // - Has support for handling redirects sent by the server.
  val UNKNOWN_VERSION: Long = 0
  val LATEST_VERSION: Long = 1761600610

  private val versionGauge = {
    val gauge =
      Gauge.build().name("dicer_client_version").help("Version of the Dicer client code").register()
    gauge.set(LATEST_VERSION)
    gauge
  }

  // -------------------------
  // Dicer Build/Commit Timestamp Metrics
  // -------------------------
  private[this] val clientBuildInfoGauge = Gauge
    .build()
    .name("dicer_client_build_info")
    .labelNames(
      "targetCluster",
      "targetName",
      "targetInstanceId",
      "source",
      "commitTimestamp",
      "language"
    )
    .help(
      "The build info of the service running the Dicer client. " +
      "The commit timestamp is recorded in the label. " +
      "The value is always set to 1."
    )
    .register()
  private[this] val clientCommitTimestampGauge = Gauge
    .build()
    .name("dicer_client_commit_timestamp")
    .labelNames("targetCluster", "targetName", "targetInstanceId", "source")
    .help(
      "The latest commit timestamp of the service running the Dicer client. " +
      "The commit timestamp is recorded in epoch milliseconds as value. " +
      "It's more convenient for aggregations."
    )
    .register()

  /**
   * Records the client version based on the given `branch` string and `source` for `target`. Also
   * records the static version of the source code defined by `LATEST_VERSION`.
   *
   * @param target The [[Target]] service whose client version is being recorded.
   * @param source The source of the metric.
   * @param branch The client build version string. This value can be obtained from
   *               [[LocationConf.branch]] and is expected to be in this format:
   *               `dicer_customer_2024-09-04_14.57.01Z_master_164f18b3_1957847387`.
   */
  def recordClientVersion(target: Target, source: AssignmentMetricsSource, branch: String): Unit = {
    val buildInfo: BuildInfo = BuildInfo.parseClientVersion(branch)
    logger.info(
      s"Dicer $source instance in this binary belonging to Dicer target $target was " +
      s"built from branch ${buildInfo.getBranchNameLabelValue} at commit " +
      s"${buildInfo.getCommitHashLabelValue} with commit time " +
      s"${buildInfo.getCommitTimeLabelValue}, library version ${versionGauge.get()}"
    )
    clientCommitTimestampGauge
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        source.toString
      )
      .set(buildInfo.getCommitTimeEpochMillis)
    clientBuildInfoGauge
      .labels(
        target.getTargetClusterLabel,
        target.getTargetNameLabel,
        target.getTargetInstanceIdLabel,
        source.toString,
        buildInfo.getCommitTimeLabelValue,
        "Scala"
      )
      .set(1)
  }
}

/**
 * Identifies which client component (Clerk or Slicelet) is reporting an assignment-related metric.
 */
sealed trait AssignmentMetricsSource

object AssignmentMetricsSource {
  case object Clerk extends AssignmentMetricsSource
  case object Slicelet extends AssignmentMetricsSource
}

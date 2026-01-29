package com.databricks.caching.util

import com.databricks.caching.util.BuildInfo.DATE_TIME_FORMATTER
import java.time.{Instant, ZoneOffset, ZonedDateTime}
import java.time.format.DateTimeFormatter

import scala.util.control.NonFatal
import scala.util.matching.Regex

/**
 * Stores information parsed from a client build version string.
 *
 * @param branchNameOpt The branch name of the build, or None if the branch name is unknown.
 * @param commitTimeOpt The commit time of the build as an [[ZonedDateTime]], or None if the commit
 *                      time is unknown.
 * @param commitHashOpt The commit hash (first 8 hex digits) of the build, or None if the commit
 *                      hash is unknown.
 */
class BuildInfo private (
    branchNameOpt: Option[String],
    commitTimeOpt: Option[Instant],
    commitHashOpt: Option[String]) {

  /** Returns the branch name, or None if the branch name is unknown. */
  def getBranchNameOpt: Option[String] = branchNameOpt

  /**
   * Returns a string suitable for a metric label representing the branch name. If there is no
   * branch name, this method returns "unknown".
   */
  def getBranchNameLabelValue: String = branchNameOpt.getOrElse("unknown")

  /**
   * Returns a string suitable for a metric label representing the commit time. If there is no
   * commit time, this method returns "unknown".
   */
  def getCommitTimeLabelValue: String =
    commitTimeOpt
      .map { commitTime =>
        val zonedDateTime: ZonedDateTime = commitTime.atZone(ZoneOffset.UTC)
        DATE_TIME_FORMATTER.format(zonedDateTime)
      }
      .getOrElse("unknown")

  /**
   * Returns the commit time of the build as the number of milliseconds since the Unix epoch, or 0
   * if the commit time is unknown.
   */
  def getCommitTimeEpochMillis: Long = commitTimeOpt.map(_.toEpochMilli).getOrElse(0L)

  /**
   * Returns a string suitable for a metric label representing the commit hash (first 8 hex digits).
   * If there is no commit hash, this method returns "unknown".
   */
  def getCommitHashLabelValue: String = commitHashOpt.getOrElse("unknown")

}

/** Companion object for [[BuildInfo]]. */
object BuildInfo {

  private[this] val logger: PrefixLogger = PrefixLogger.create(getClass, "")

  /** A regex that matches a valid client build version string. */
  private[this] val CLIENT_VERSION_REGEX =
    """(\d{4}-\d{2}-\d{2}_\d{2}\.\d{2}\.\d{2}Z)_(.*?)_([a-z0-9]{8})_\d+$""".r

  private val DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ssX")

  /**
   * Parses a client build version string into constituent commit time, branch name, and commit hash
   * components and returns a [[BuildInfo]] with the result. If the input string is malformed, any
   * components that failed to be parsed will be considered unknown.
   *
   * @param branch The client build version string. This value can be obtained from
   *               [[LocationConf.branch]] and is expected to be in this format:
   *               `service_2024-09-04_14.57.01Z_master_164f18b3_1957847387`,
   * @return       A [[BuildInfo]] object containing the parsed commit time, branch name, and commit
   *               hash (first 8 hex digits).
   */
  def parseClientVersion(branch: String): BuildInfo = {
    // This is extracted from [[LocationConf.branch]]. It depends on the assumption that
    // the timestamp from the branch name is the commit timestamp of the PR of branch
    // being built on. Unfortunately, there is no commit hash in the LocationConf.
    // <internal link> has been filed to do this properly.

    // Parse the commit timestamp and git branch out of the branch string.
    val regexMatch: Option[Regex.Match] = CLIENT_VERSION_REGEX.findFirstMatchIn(branch)
    val commitTimeStringOpt: Option[String] = regexMatch.map(_.group(1))
    val branchNameOpt: Option[String] = regexMatch.map(_.group(2))
    val commitHashOpt: Option[String] = regexMatch.map(_.group(3))

    val commitTimeOpt: Option[Instant] = commitTimeStringOpt.flatMap { commitTimeString =>
      try {
        val zonedDateTime = ZonedDateTime.parse(commitTimeString, DATE_TIME_FORMATTER)
        Some(Instant.from(zonedDateTime))
      } catch {
        case NonFatal(_: Throwable) =>
          logger.error(s"Failed to parse the commit timestamp $commitTimeString")
          None
      }
    }
    new BuildInfo(branchNameOpt, commitTimeOpt, commitHashOpt)
  }

}

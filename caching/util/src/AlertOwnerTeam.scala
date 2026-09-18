package com.databricks.caching.util

/**
 * Teams responsible for handling errors from client services. These values must match the
 * team names in eng-team-info.json, which is used to automatically route alerts.
 */
sealed trait AlertOwnerTeam

object AlertOwnerTeam {
  case object CachingTeam extends AlertOwnerTeam {
    override def toString: String = "platform-team"
  }

  /** The alert routing name for [[CachingTeam]], as a plain string. */
  val CACHING_TEAM_NAME: String = CachingTeam.toString

  /**
   * An [[AlertOwnerTeam]] for teams not represented by a named case object.
   * Pass the team's alert routing name directly (e.g. "eng-my-team").
   */
  case class Custom(teamName: String) extends AlertOwnerTeam {
    override def toString: String = teamName
  }

  /**
   * Returns the [[AlertOwnerTeam]] for the given team name. Returns the named case object
   * if the name matches a known team (e.g. [[CachingTeam]]), otherwise returns [[Custom]].
   */
  def createFromString(teamName: String): AlertOwnerTeam = teamName match {
    case t if t == CachingTeam.toString => CachingTeam
    case _ => Custom(teamName)
  }
}

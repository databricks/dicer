package com.databricks.dicer.assigner

import com.databricks.dicer.external.Target
import java.net.URI

/**
 * The verdict returned by [[TargetMigrationRouter.getVerdict]], indicating how the assigner should
 * handle a given [[Target]].
 */
sealed trait Verdict

object Verdict {

  /** The assigner should handle the target locally. */
  case object Handle extends Verdict

  /**
   * The assigner should redirect requests for the target to the specified assigner URI.
   *
   * @param handlingAssigner The URI of the assigner that will handle the target.
   */
  case class Redirect(handlingAssigner: URI) extends Verdict
}

/**
 * Router for target migration that determines, for each [[Target]], whether the assigner
 * should handle the target locally ([[Verdict.Handle]]) or redirect to another assigner
 * ([[Verdict.Redirect]]).
 *
 * Use [[TargetMigrationRouter.ALWAYS_HANDLE]] for a router that handles all targets locally.
 */
class TargetMigrationRouter private (private val verdictFn: Target => Verdict) {

  /**
   * Returns the [[Verdict]] for the given target, indicating whether this assigner should handle
   * the target or redirect to another assigner.
   *
   * @param target The target to evaluate.
   */
  def getVerdict(target: Target): Verdict = verdictFn(target)
}

object TargetMigrationRouter {

  /**
   * A [[TargetMigrationRouter]] that handles all targets locally, never redirecting to another
   * assigner.
   */
  val ALWAYS_HANDLE: TargetMigrationRouter =
    new TargetMigrationRouter(_ => Verdict.Handle)
}

package com.databricks.dicer.assigner.config

/**
 * The churn config defines a [[maxPenaltyRatio]] that is applied to the load of a resource when it
 * is reassigned to a new Slice. By making keys more expensive to reassign, the churn penalty
 * discourages constant refinements (and potential oscillations) of the assignment. For example, a
 * given Slice is less likely to bounce between resources if there are small changes in relative
 * load over time, because small improvements in load balancing are offset by the overhead imposed
 * by the penalty.
 *
 * @param maxPenaltyRatio Churn penalty ratio that is applied when a Slice is reassigned.
 */
case class ChurnConfig(maxPenaltyRatio: Double) {
  require(maxPenaltyRatio >= 0.0, s"maxPenaltyRatio must be non-negative: $maxPenaltyRatio")
}

object ChurnConfig {

  /** Default churn configuration. */
  val DEFAULT: ChurnConfig = ChurnConfig(maxPenaltyRatio = 0.25)

  /**
   * Churn configuration in which there is no penalty for churn. Used when load balancing is not
   * enabled.
   */
  val ZERO_PENALTY: ChurnConfig = ChurnConfig(maxPenaltyRatio = 0.0)
}

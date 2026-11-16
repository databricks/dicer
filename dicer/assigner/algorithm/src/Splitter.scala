package com.databricks.dicer.assigner.algorithm

import scala.collection.mutable

import com.databricks.dicer.assigner.algorithm.Algorithm.{
  Config,
  MIN_AVG_SLICE_REPLICAS,
  SIZE_SPLIT_THRESHOLD_RATIO
}

/** Implements the split phases of the algorithm. */
private[assigner] object Splitter {

  /**
   * Splits slices with too much load. This phase repeatedly splits the Slice with the greatest raw
   * load per replica, until all are below the threshold or no more splits are possible. All else
   * being equal, splitting is preferred over replication to maintain affinity, so this phase runs
   * before replication. Size plays no role in this phase.
   */
  def splitHotLoadSlices(config: Config, assignment: MutableAssignment): Unit = {
    splitInternal(assignment)(
      minSliceReplicas = Int.MaxValue, // No minimum number of replicas to split.
      measure = { sliceAssignment: assignment.MutableSliceAssignment =>
        sliceAssignment.rawLoadPerReplica
      },
      chooseMetric = { _: assignment.MutableSliceAssignment =>
        // Size limits are not yet enabled, and splits here target load alone.
        MutableAssignment.Metric.Load
      },
      splitThreshold = config.desiredLoadRange.splitThreshold
    )
  }

  /**
   * Splits Slices by size until each Slice's size is at or below [[SIZE_SPLIT_THRESHOLD_RATIO]] of
   * the maximum desired size per resource, or no more splits are possible. Does nothing unless size
   * limits are enabled on `assignment`.
   */
  def splitHotSizeSlices(assignment: MutableAssignment): Unit = {
    for (maxDesiredSize: Double <- assignment.getMaxDesiredSizeOpt) {
      splitInternal(assignment)(
        minSliceReplicas = Int.MaxValue,
        measure = { sliceAssignment: assignment.MutableSliceAssignment =>
          sliceAssignment.rawSize
        },
        chooseMetric = { _: assignment.MutableSliceAssignment =>
          // Splitting by load would leave most of the size in one half, yielding more Slices of
          // uneven size.
          MutableAssignment.Metric.Size
        },
        splitThreshold = maxDesiredSize * SIZE_SPLIT_THRESHOLD_RATIO
      )
    }
  }

  /**
   * Performs zero-churn splits to ensure total replica count is at least MIN_AVG_SLICE_REPLICAS per
   * resource. This phase runs after de-replication and merging because those steps may have reduced
   * the count below the minimum.
   *
   * Zero-churn means splits don't change resource assignments - both child slices inherit the
   * parent's resources. Despite this, we still prioritize splitting hot slices (by raw heat per
   * replica, splitting each by its dominant metric) to benefit the migration phase.
   */
  def ensureMinTotalSliceReplicas(resources: Resources, assignment: MutableAssignment): Unit = {
    splitInternal(assignment)(
      minSliceReplicas = MIN_AVG_SLICE_REPLICAS * resources.availableResources.size,
      measure = { sliceAssignment: assignment.MutableSliceAssignment =>
        sliceAssignment.rawHeatPerReplica
      },
      chooseMetric = { sliceAssignment: assignment.MutableSliceAssignment =>
        // Split by the dominant metric (load or size) in this phase.
        sliceAssignment.dominantMetric
      },
      splitThreshold = -1.0 // Negative threshold means split regardless of heat.
    )
  }

  /**
   * Continue splitting the Slice that is hottest by `measure` (ties broken by Slice), using the
   * metric chosen by `chooseMetric`, until one of the following:
   *
   *  - There are no more Slices to split
   *  - There are at least `minSliceReplicas` Slice replicas in the assignment
   *  - The hottest splittable Slice has `measure` at or below `splitThreshold`
   */
  private def splitInternal(assignment: MutableAssignment)(
      minSliceReplicas: Int,
      measure: assignment.MutableSliceAssignment => Double,
      chooseMetric: assignment.MutableSliceAssignment => MutableAssignment.Metric,
      splitThreshold: Double): Unit = {
    if (minSliceReplicas <= assignment.currentNumTotalSliceReplicas) {
      // Short-circuit before creating a PQ of all Slices if we're already at or above
      // `minSliceReplicas`.
      return
    }
    // Dequeue the hottest Slice first; ties are broken by Slice for determinism.
    val ordering = Ordering.by { sliceAssignment: assignment.MutableSliceAssignment =>
      (measure(sliceAssignment), sliceAssignment.slice)
    }

    // Maintain all (remaining) slice assignments in a priority queue so that we can easily find the
    // hottest Slice at each step. Note that the Slices in this queue are not necessarily splittable
    // (e.g. when they are already single-keyed in original assignment).
    val remainingCandidateSlices =
      mutable.PriorityQueue.empty[assignment.MutableSliceAssignment](ordering)
    remainingCandidateSlices ++= assignment.sliceAssignmentsIterator

    while (remainingCandidateSlices.nonEmpty &&
      assignment.currentNumTotalSliceReplicas < minSliceReplicas) {
      val sliceAssignment: assignment.MutableSliceAssignment = remainingCandidateSlices.dequeue()
      if (measure(sliceAssignment) <= splitThreshold) {
        // All remaining slices are at or below the split threshold, so stop splitting.
        return
      }
      sliceAssignment.split(chooseMetric(sliceAssignment)) match {
        case Some((left, right)) =>
          remainingCandidateSlices.enqueue(left)
          remainingCandidateSlices.enqueue(right)
        case None =>
        // Unsplittable Slice; skip it!
      }
    }
  }
}

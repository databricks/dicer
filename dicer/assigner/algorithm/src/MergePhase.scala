package com.databricks.dicer.assigner.algorithm

import com.databricks.caching.util.AssertMacros.iassert
import com.databricks.caching.util.{
  CachingErrorCode,
  IntrusiveMinHeap,
  IntrusiveMinHeapElement,
  PrefixLogger
}
import com.databricks.dicer.assigner.algorithm.Algorithm.{Config, MAX_AVG_SLICE_REPLICAS}

/**
 * Merges adjacent cold slices to ensure total replica count stays below MAX_AVG_SLICE_REPLICAS per
 * resource. This phase repeatedly merges the coldest adjacent slice pairs until the total replica
 * count is within bounds.
 *
 * Guarantee: Merged slices always have the minimum number of replicas (relied upon by
 * calculateDesiredLoadRange). This is reasonable because we only merge the coldest pairs.
 */
private[assigner] object MergePhase {
  def merge(config: Config, resources: Resources, assignment: MutableAssignment): Unit = {
    new Merger(config, resources, assignment).run()
  }

  /**
   * Merges the coldest slice pairs until the total number of slice replicas is less than or equal
   * to (MAX_AVG_SLICE_REPLICAS * numResources).
   *
   * When a pair of slices is merged, the resulting slice will be unconditionally configured to have
   * the minimum number of replicas. This is because the slice pairs that we merge are always the
   * coldest adjacent pairs in the assignment, and if we only merge when there are more than
   * [[MAX_AVG_SLICE_REPLICAS]] * numResources slice replicas, then the load on the coldest pair
   * post-merge should also be fairly low.
   *
   * In addition, the per-replica load after merge is possible to be greater than split threshold,
   * but this scenario should also be rare because we are merging the coldest pairs.
   */
  private class Merger(config: Config, resources: Resources, assignment: MutableAssignment) {

    private val logger = PrefixLogger.create(getClass, config.target.name)

    /**
     * A candidate is a pair of adjacent slices that may be merged. While the [[Merger]] is running,
     * it repeatedly finds the pair of adjacent slices with the lowest total load and merges them.
     * To motivate the data structures used in this implementation, consider the assignment with the
     * following Slices and corresponding load values:
     *
     *     ["","b")->10.0, ["b","c")->5.0, ["c","d")->5.0, ["d",∞)->10.0
     *
     * In this example, there are three candidates for merging: we can choose to merge at "b", "c",
     * or "d". Each [[CandidateSlicePair]] is an [[IntrusiveMinHeapElement]] instance in which the
     * priority is the total load of the adjacent slices, which allows us to efficiently find the
     * best candidate (with the lowest total load) at each step in an [[IntrusiveMinHeap]], and to
     * update the priorities of candidates as their neighbors are merged. The min-heap for the
     * example above contains:
     *
     *     Candidate(left=["b","c")->5.0, right=["c","d")->5.0), priority=10.0)
     *     Candidate(left=["","b")->10.0, right=["b","c")->5.0), priority=15.0)
     *     Candidate(left=["c","d")->5.0, right=["d",∞)->10.0), priority=15.0)
     *
     * The heap is intrusive because after merging a particular candidate, the expected total load
     * for the surrounding candidates is also modified. For example, if we merge at "c" (since the
     * corresponding candidate has the lowest total load), then the heap becomes:
     *
     *    Candidate(left=["","b")->10.0, right=["b","d")->10.0), priority=20.0)
     *    Candidate(left=["b","d")->10.0, right=["d",∞)->10.0), priority=20.0)
     *
     * The original candidate is removed from the heap, and the two surrounding candidates are
     * updated to reflect the new (potentially) affected Slices and the new total load. In addition,
     * we maintain `successorOpt` and `predecessorOpt` links for each candidate so that we can
     * efficiently find the candidates that need to be fixed up after a merge.
     */
    private class CandidateSlicePair(
        private var left: assignment.MutableSliceAssignment,
        private var right: assignment.MutableSliceAssignment,
        private var predecessorOpt: Option[CandidateSlicePair])
        extends IntrusiveMinHeapElement[Double] {

      private var successorOpt: Option[CandidateSlicePair] = None

      // Fix up reverse link.
      if (predecessorOpt.isDefined) {
        predecessorOpt.get.successorOpt = Some(this)
      }

      // Initialize the total load of the candidate pair by summing the current `left` and `right`
      // load values.
      updatePriority()

      /**
       * REQUIRES: this candidate has been removed from [[candidates]].
       *
       * Merges [[left]] and [[right]]. Updates the priority of the predecessor and successor
       * candidates (if any) to reflect the new total load they would have if they were merged and
       * fixes up the successor and predecessor links to reflect the merge.
       *
       * For example, suppose we start in this state:
       *
       * <pre>
       *   predecessor   candidate    successor
       *   [a,b)-[b,c)   [b,c)-[c,d)  [c,d)-[d,e)
       * </pre>
       *
       * After merging [b,c) with [c,d) to produce [b,d), we fix up the predecessor and successor
       * slice pairs and links as follows:
       *
       * <pre>
       *   predecessor     successor
       *   [a, b)-[b,d)    [b,d)-[d,e)
       * </pre>
       */
      def merge(): Unit = {
        iassert(!attached, "this candidate must be detached from the heap before merging")
        // See the doc of `Merger` for why `minReplicas` is used.
        val merged: assignment.MutableSliceAssignment =
          left.mergeWithSuccessor(config.resourceAdjustedKeyReplicationConfig.minReplicas)

        if (predecessorOpt.isDefined) {
          val predecessor: CandidateSlicePair = predecessorOpt.get
          predecessor.right = merged
          predecessor.updatePriority()
          predecessor.successorOpt = successorOpt
        }
        if (successorOpt.isDefined) {
          val successor: CandidateSlicePair = successorOpt.get
          successor.left = merged
          successor.updatePriority()
          successor.predecessorOpt = predecessorOpt
        }
      }

      /**
       * Updates this candidate's priority (which is the total load for the pair of Slices). The
       * priority determines the candidate's position in the [[candidates]] min-heap. This method
       * should be called when the candidate is initialized and when the candidate's `left` or
       * `right` values have been modified (due to the merging of adjacent Slices).
       */
      private def updatePriority(): Unit = {
        setPriority(left.rawLoad + right.rawLoad)
      }
    }

    private val candidates = new IntrusiveMinHeap[Double, CandidateSlicePair]()

    /** See the main doc of [[Merger]]. */
    def run(): Unit = {
      val maxSliceReplicas: Int = MAX_AVG_SLICE_REPLICAS * resources.availableResources.size
      if (assignment.currentNumTotalSliceReplicas <= maxSliceReplicas) {
        // Early return if the max total replica count is already satisfied, to avoid the overhead
        // of populating candidates below.
        return
      }
      populateCandidates()
      while (assignment.currentNumTotalSliceReplicas > maxSliceReplicas && candidates.nonEmpty) {
        val candidate: CandidateSlicePair = candidates.pop()
        candidate.merge()
      }
    }

    /** Populates all merge candidates (all adjacent Slices in [[assignment]]). */
    private def populateCandidates(): Unit = {
      // Iterate over the assignment Slices in order to create candidates for each adjacent
      // left-right pair.
      val it = assignment.sliceAssignmentsIterator
      var left: assignment.MutableSliceAssignment = it.next()
      var predecessorOpt: Option[CandidateSlicePair] = None
      while (it.hasNext) {
        val right: assignment.MutableSliceAssignment = it.next()
        val candidate = new CandidateSlicePair(left, right, predecessorOpt)
        candidates.push(candidate)
        left = right
        predecessorOpt = Some(candidate)
      }
    }
  }
}

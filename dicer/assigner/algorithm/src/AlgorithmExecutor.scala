package com.databricks.dicer.assigner.algorithm

import com.databricks.dicer.assigner.algorithm.Algorithm.Config

/**
 * Orchestrates the execution of the algorithm phases.
 *
 * <h2>Concepts and Precedents</h2>
 *
 * Dicer performs load-driven splits and merges, a partitioning strategy pioneered by Bigtable (OSDI
 * 2006 https://doi.org/10.1145/1365815.1365816) to automatically respond to data hot spots. Dicer's
 * "Slices" are analogous to Bigtable's "Tablets". We reject alternative strategies like manual
 * repartitioning (which involves massive toil and is prone to human error), or strategies assuming
 * uniform data distributions like consistent hashing.
 *
 * As with Slicer (OSDI 2016 https://www.usenix.org/system/files/conference/osdi16/osdi16-adya.pdf),
 * Dicer supports load-driven asymmetric key redundancy, in which hot Slices can have more replicas
 * than cold Slices. Splitting, merging, replicating, and de-replicating Slices are centrally
 * coordinated by the Assigner in both Dicer and Slicer. In contrast, Bigtable's tablet servers are
 * responsible for splits; only merges are centrally coordinated. (And in typical storage systems,
 * replication tends to be statically configured, rather than dynamically adjusted based on load.)
 *
 * NOTE: This implementation differs from the algorithm used in our internal production systems.
 * The production version integrates tightly with Databricks-specific infrastructure, frameworks,
 * assumptions and operational tooling, which makes it unsuitable for direct open-source release.
 *
 * The implementation here preserves the core algorithmic behavior and is fully covered by tests,
 * though it is likely to differ in behavior. Users are advised to ensure it works well for their
 * application before going to production.
 */
private[assigner] object AlgorithmExecutor {

  /**
   * Executes the algorithm phases to generate a balanced slice assignment (for references to Slicer
   * algorithm, see https://www.usenix.org/system/files/conference/osdi16/osdi16-adya.pdf):
   *
   * 1. Deallocation phase: Deallocate slice replicas assigned to unhealthy resources. This is like
   *    step 1 of the Slicer sharding algorithm.
   * 2. ConstraintPhase: Clamp replica counts to configured min/max. This is like step 2 of the
   *    Slicer algorithm.
   * 3. Split hot: Split hot slices to reduce per-replica load below split threshold. This is like
   *    step 5 (a) of the Slicer algorithm.
   * 4. MergePhase: Merge adjacent cold slices to stay below max total replicas. This is like step
   *    3 (a) of the Slicer algorithm (other conditions are not implemented).
   * 5. Split for min replicas: Zero-churn splits to ensure minimum total replicas.
   * 6. Placement phase: Iteratively reassign, replicate and dereplicate slices to various resources
   *    to greedily minimize the objective function, which tries to reduce load imbalance while
   *    having minimal churn. This is conceptually similar to step 4 of the Slicer algorithm.
   * 7. MergePhase: Run the merge phase again to ensure we are below the max total replicas after
   *    the placement phase.
   *
   * @param config Configuration for the algorithm.
   * @param resources Resources available for the algorithm.
   * @param assignment Mutable assignment that will be updated by each phase.
   */
  def run(config: Config, resources: Resources, assignment: MutableAssignment): Unit = {
    DeallocationPhase.deallocateUnhealthyResources(assignment)

    // Clamp replica counts to configured range. This happens now so that:
    // - Later steps can assume replicas are in range and won't be clamped again
    // - Split/merge decisions are made with correct per-replica loads
    ConstraintPhase.clampReplicas(config, assignment)
    Splitter.splitHotLoadSlices(config, assignment)
    MergePhase.merge(config, resources, assignment)

    // Ensure minimum total replicas. Done now because de-replication/merge may have reduced the
    // count below the minimum.
    Splitter.ensureMinTotalSliceReplicas(resources, assignment)
    PlacementPhase.place(config, assignment)
    // The placement phase can replicate slices, causing the total number of slice replicas to
    // increase beyond `num_resources * MAX_AVG_SLICE_REPLICAS`. To handle this we just run the
    // merge phase again (although in most cases it will be a no-op).
    //
    // Note that the placement phase can also dereplicate slices, but it ensures that the slice
    // replicas don't go below `num_resources * MIN_AVG_SLICE_REPLICAS` (see the `PlacementPhase`
    // comment), so we don't need to rerun the split phase.
    MergePhase.merge(config, resources, assignment)
  }
}

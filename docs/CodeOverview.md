# Inside Dicer: Anatomy of Databricks' Auto-Sharder

*A comprehensive code analysis for rapid onboarding*

**Target Audience**: Engineers ramping up on the Dicer codebase 

**Prerequisites**: Familiarity with the Slicer paper and "Fast Key-Value Stores: An Idea Whose Time Has Come and Gone"

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Mental Model: The Big Picture](#2-mental-model-the-big-picture)
3. [Core Concepts](#3-core-concepts)
4. [System Architecture](#4-system-architecture)
5. [Deep Dive: The Sharding Algorithm](#5-deep-dive-the-sharding-algorithm)
6. [Deep Dive: The Assigner](#6-deep-dive-the-assigner)
7. [Deep Dive: Client Libraries](#7-deep-dive-client-libraries)
8. [Data Structures](#8-data-structures)
9. [Request Flow Analysis](#9-request-flow-analysis)
10. [Key Design Decisions](#10-key-design-decisions)
11. [Testing Strategy](#11-testing-strategy)
12. [Code Navigation Guide](#12-code-navigation-guide)
13. [References](#13-references)

---

## 1. Executive Summary

**Dicer** is Databricks' production auto-sharder - a foundational infrastructure system that enables services to colocate in-memory state with computation. It's inspired by Google's Slicer (OSDI 2016) but adapted for Kubernetes environments with several Databricks-specific enhancements.

### Why Dicer Exists

Traditional stateless services pay a heavy tax:
- **Network latency**: Every request hits a cache/database
- **CPU overhead**: Serialization/deserialization cycles waste compute
- **The Overread Problem**: Fetching entire objects to use small fractions

Dicer solves this by intelligently sharding key ranges to pods, allowing services to maintain in-memory state with guaranteed request affinity.

### Key Production Impact

- **Unity Catalog**: 10x reduction in database load
- **SQL Query Orchestration**: 2 nines availability improvement
- **Softstore**: Near-zero cache hit rate degradation during rolling restarts

### Tech Stack Summary

| Component | Technology |
|-----------|------------|
| Language | Scala 2.12.20 |
| Build | Bazel |
| RPC | gRPC |
| Coordination | etcd |
| Orchestration | Kubernetes |
| Metrics | Prometheus |

---

## 2. Mental Model: The Big Picture

### The Inverse Pyramid Approach

Start with the simplest mental model, then layer in complexity:

```
Level 1 (Simplest):
  Dicer assigns KEY RANGES to PODS

Level 2:
  Assigner GENERATES assignments based on SIGNALS (health, load, terminations)
  Slicelets RECEIVE assignments and REPORT load
  Clerks ROUTE requests to the right pod

Level 3:
  Algorithm has PHASES: Deallocation -> Constraints -> Split -> Merge -> Placement
  Assignments are VERSIONED with generations and incarnations
  Watch protocol enables ASYNC, DIFF-BASED updates
```

### The Core Loop

```
                    +-----------+
                    |  Assigner |
                    +-----+-----+
                          |
          +---------------+---------------+
          |               |               |
     [Health]        [Load]         [K8s Events]
     Heartbeats      Reports        Terminations
          |               |               |
          +-------+-------+---------------+
                  |
                  v
          +-------+--------+
          |   Algorithm    |
          | (5-phase)      |
          +-------+--------+
                  |
                  v
          +-------+--------+
          |  Assignment    |
          |  (versioned)   |
          +-------+--------+
                  |
      +-----------+-----------+
      |                       |
      v                       v
+-----+-----+           +-----+-----+
|  Slicelet |           |   Clerk   |
|  (server) |           |  (client) |
+-----------+           +-----------+
```

---

## 3. Core Concepts

### 3.1 SliceKey: The Universal Address

A `SliceKey` is an 8-byte fingerprint of your application key. **Why fingerprints?**

```scala
// dicer/external/src/SliceKey.scala:115-138
trait SliceKeyFunction {
  def apply(applicationKey: Array[Byte]): Array[Byte]
}

// Recommended: Use FarmHash Fingerprint64
object Fingerprint extends SliceKeyFunction {
  override def apply(applicationKey: Array[Byte]): Array[Byte] = {
    Hashing.farmHashFingerprint64().hashBytes(applicationKey).asBytes
  }
}
```

**Key insight**: Fingerprinting ensures uniform distribution across the key space, which is critical for balanced sharding. The 8-byte size enables efficient comparison via `Long` operations:

```scala
// dicer/external/src/SliceKey.scala:30-36
private val bytesPrefix: Long = {
  // Convert first 8 bytes to unsigned long for fast comparison
  byteAt(0) << 56 | byteAt(1) << 48 | ... | byteAt(7)
}

def compare(that: SliceKey): Int = {
  // Fast path: compare prefixes as unsigned longs
  val comparePrefixResult = java.lang.Long.compareUnsigned(this.bytesPrefix, that.bytesPrefix)
  if (comparePrefixResult != 0) return comparePrefixResult
  // Slow path: byte-by-byte comparison for keys > 8 bytes
  ...
}
```

### 3.2 Slice: A Contiguous Key Range

A `Slice` is a half-open interval `[lowInclusive, highExclusive)`:

```scala
// dicer/external/src/Slice.scala
case class Slice(lowInclusive: SliceKey, highExclusive: HighSliceKey) {
  def contains(key: SliceKey): Boolean
  def intersection(other: Slice): Option[Slice]
}

// Special sentinel for unbounded ranges
object InfinitySliceKey extends HighSliceKey
```

**Important invariant**: Slices in an assignment must be **disjoint** and **cover the entire key space** from `""` to `∞`.

### 3.3 Assignment: The Complete Mapping

An `Assignment` captures the full state of key-to-pod mappings:

```scala
// dicer/common/src/Assignment.scala:57-61
case class Assignment(
    isFrozen: Boolean,              // Pause assignment updates
    consistencyMode: AssignmentConsistencyMode,
    generation: Generation,          // Version number
    sliceMap: SliceMap[SliceAssignment]  // The actual mapping
)
```

**Generation** = `(Incarnation, Number)`:
- **Incarnation**: Bumped on disasters/breaking changes
- **Number**: Monotonically increasing within incarnation

### 3.4 Squid: Resource Identity

A `Squid` (Slice-Unique ID) identifies a specific pod incarnation:

```scala
// Contains: podUID + resourceAddress (IP:port)
// Used for Strong consistency mode: exact SQUID match required
// In Affinity mode: only address match required
```

---

## 4. System Architecture

### 4.1 Component Overview

```
+------------------+     Watch RPC      +------------------+
|                  |<-------------------|                  |
|    Assigner      |    Heartbeats      |    Slicelet      |
|   (Control Plane)|<-------------------|   (Data Plane)   |
|                  |    Load Reports    |                  |
|                  |------------------->|                  |
|                  |   Diff Assignment  |                  |
+--------+---------+                    +--------+---------+
         |                                       |
         |  Watch RPC                            | Watch RPC
         |                                       |
         v                                       v
+------------------+                    +------------------+
|                  |                    |                  |
|      etcd        |                    |     Clerk        |
|  (Persistence)   |                    |   (Routing)      |
|                  |                    |                  |
+------------------+                    +------------------+
```

### 4.2 Directory Structure

```
dicer/
├── assigner/           # Control plane
│   ├── algorithm/      # 5-phase sharding algorithm
│   ├── config/         # Target configuration
│   └── src/            # Assigner service
├── client/             # Data plane libraries
│   ├── ClerkImpl       # Request routing
│   ├── SliceletImpl    # Assignment handling
│   └── SliceLookup     # Watch client
├── common/             # Shared code
│   ├── Assignment      # Core data structures
│   └── Generation      # Versioning
├── external/           # Public API
│   ├── Clerk           # Client facade
│   ├── Slicelet        # Server facade
│   └── SliceKey        # Key representation
├── friend/             # Internal API
│   └── SliceMap        # Efficient range map
└── demo/               # Example application
```

---

## 5. Deep Dive: The Sharding Algorithm

### 5.1 Algorithm Overview

The algorithm runs in the Assigner whenever signals change (health, load, K8s events). It's **stateless** - given the previous assignment and current signals, it produces a new assignment.

```scala
// dicer/assigner/algorithm/src/Algorithm.scala:68-121
def generateAssignment(
    instant: Instant,
    target: Target,
    targetConfig: InternalTargetConfig,
    resources: Resources,
    baseAssignmentSliceMap: SliceMap[SliceAssignment],
    loadMap: LoadMap): SliceMap[ProposedSliceAssignment]
```

### 5.2 The Seven Phases

The algorithm runs 7 phases in sequence. This is based on the Slicer paper but adapted for Kubernetes:

```scala
// dicer/assigner/algorithm/src/AlgorithmExecutor.scala:56-78
def run(config: Config, resources: Resources, assignment: MutableAssignment): Unit = {
  DeallocationPhase.deallocateUnhealthyResources(assignment)  // Phase 1
  ConstraintPhase.clampReplicas(config, assignment)            // Phase 2
  Splitter.splitHotSlices(config, assignment)                  // Phase 3
  MergePhase.merge(config, resources, assignment)              // Phase 4
  Splitter.ensureMinTotalSliceReplicas(resources, assignment)  // Phase 5
  PlacementPhase.place(config, assignment)                     // Phase 6
  MergePhase.merge(config, resources, assignment)              // Phase 7
}
```

**Phase Summary Table**:

| Phase | Name | Purpose |
|-------|------|---------|
| 1 | Deallocation | Remove slices from unhealthy pods |
| 2 | Constraint | Clamp replica counts to min/max |
| 3 | Split Hot | Split slices exceeding load threshold |
| 4 | Merge | Merge cold adjacent slices |
| 5 | Split Min | Ensure minimum total slice replicas |
| 6 | Placement | Greedy local search for balance |
| 7 | Final Merge | Clean up after placement replication |

### 5.3 Phase 1: Deallocation

The simplest phase - remove all slices from unhealthy resources:

```scala
// dicer/assigner/algorithm/src/DeallocationPhase.scala:10-22
def deallocateUnhealthyResources(assignment: MutableAssignment): Unit = {
  for (unhealthyResource <- assignment.getUnhealthyResourceStates) {
    // Capture to Vector to avoid iterator invalidation during modification
    val assignedSlices = unhealthyResource.getAssignedSlices.toVector
    for (sliceAssignment <- assignedSlices) {
      sliceAssignment.deallocateResource(unhealthyResource)
    }
  }
}
```

**Key insight**: This phase runs first to ensure subsequent phases don't consider unhealthy resources.

### 5.4 Phase 2: Constraint

Clamp replica counts to configured bounds before split/merge decisions:

```scala
// dicer/assigner/algorithm/src/ConstraintPhase.scala:11-23
def clampReplicas(config: Config, assignment: MutableAssignment): Unit = {
  val minReplicas = config.resourceAdjustedKeyReplicationConfig.minReplicas
  val maxReplicas = config.resourceAdjustedKeyReplicationConfig.maxReplicas

  for (sliceAssignment <- assignment.sliceAssignmentsIterator) {
    val current = sliceAssignment.currentNumReplicas
    val clamped = current.max(minReplicas).min(maxReplicas)
    sliceAssignment.adjustReplicas(clamped)
  }
}
```

**Why early?** Split/merge phases need accurate per-replica load values. Clamping first ensures they compute correctly.

### 5.5 Phase 3: Split Hot Slices

Split slices whose per-replica load exceeds the split threshold:

```scala
// dicer/assigner/algorithm/src/Splitter.scala:16-22
def splitHotSlices(config: Config, assignment: MutableAssignment): Unit = {
  splitInternal(
    assignment,
    minSliceReplicas = Int.MaxValue,  // No minimum target
    splitThreshold = config.desiredLoadRange.splitThreshold
  )
}
```

**The Split Loop**:
```scala
// dicer/assigner/algorithm/src/Splitter.scala:48-82
private def splitInternal(...): Unit = {
  // Priority queue ordered by per-replica load (hottest first)
  val remainingCandidates = PriorityQueue.empty(sliceAsnOrderingByRawLoadPerReplica)
  remainingCandidates ++= assignment.sliceAssignmentsIterator

  while (remainingCandidates.nonEmpty &&
         assignment.currentNumTotalSliceReplicas < minSliceReplicas) {
    val hottest = remainingCandidates.dequeue()

    if (hottest.rawLoadPerReplica <= splitThreshold) {
      return  // All remaining are below threshold
    }

    hottest.split() match {
      case Some((left, right)) =>
        // Both children inherit parent's resources (zero churn)
        remainingCandidates.enqueue(left, right)
      case None =>
        // Unsplittable (single-keyed slice)
    }
  }
}
```

**Why split before replicate?** Splitting maintains affinity (same pod handles same keys), while replication spreads keys across pods. Prefer affinity when possible.

### 5.6 Phase 4: Merge Cold Slices

Merge adjacent cold slices to keep total replicas below the maximum:

```scala
// dicer/assigner/algorithm/src/MergePhase.scala:21-23
def merge(config: Config, resources: Resources, assignment: MutableAssignment): Unit = {
  new Merger(config, resources, assignment).run()
}
```

**The Merge Algorithm** uses an intrusive min-heap for efficiency:

```scala
// dicer/assigner/algorithm/src/MergePhase.scala:147-159
def run(): Unit = {
  val maxSliceReplicas = MAX_AVG_SLICE_REPLICAS * resources.availableResources.size

  if (assignment.currentNumTotalSliceReplicas <= maxSliceReplicas) {
    return  // Already within bounds
  }

  populateCandidates()  // All adjacent slice pairs

  while (assignment.currentNumTotalSliceReplicas > maxSliceReplicas &&
         candidates.nonEmpty) {
    val coldestPair = candidates.pop()  // Lowest combined load
    coldestPair.merge()
  }
}
```

**Key data structure**: `CandidateSlicePair` maintains predecessor/successor links so that after merging `[b,c) + [c,d)` into `[b,d)`, the adjacent candidates `[a,b)-[b,d)` and `[b,d)-[d,e)` can be efficiently updated.

**Invariant**: Merged slices always get minimum replicas (cold pairs don't need more).

### 5.7 Phase 5: Ensure Minimum Slice Replicas

Zero-churn splits to meet the minimum total replica count:

```scala
// dicer/assigner/algorithm/src/Splitter.scala:33-39
def ensureMinTotalSliceReplicas(resources: Resources, assignment: MutableAssignment): Unit = {
  splitInternal(
    assignment,
    minSliceReplicas = MIN_AVG_SLICE_REPLICAS * resources.availableResources.size,
    splitThreshold = -1.0  // Negative = split regardless of load
  )
}
```

**Why needed?** Phases 2-4 (constraint clamping, merging) may have reduced replicas below the minimum. This phase restores the invariant.

**Zero-churn**: Both child slices inherit parent's resource assignments. Hot slices are still prioritized for splits (benefits placement phase).

### 5.8 Phase 6: Placement (The Heart of the Algorithm)

Placement uses **greedy local search** to minimize an objective function:

```scala
// dicer/assigner/algorithm/src/PlacementPhase.scala:159-214
// Objective = churn_penalty + undershoot_penalty + overshoot_penalty +
//             empty_resource_penalty + replication_penalty
```

**Penalty Breakdown**:

| Penalty | Formula | Coefficient | Purpose |
|---------|---------|-------------|---------|
| Churn | `maxPenaltyRatio * slice_per_replica_load` | ~0.25 | Discourage movement |
| Undershoot | `(1 + (min - load)/min)² * (min - load)` | 4.0 | Fill underloaded pods |
| Overshoot | `(load/max)² * (load - max)` | 16.0 | Prevent overload |
| Empty | `max_desired_load * 100` | 100.0 | Never leave pods empty |
| Replication | `current_per_replica_load` | 1.0 | Discourage over-replication |

**Why these coefficients?**
- Overshoot (16.0) > Undershoot (4.0): Overload is more dangerous than underutilization
- Empty (100.0) dominates: Guarantees no pod is left without slices
- Churn (~0.25) is low: Willing to move for balance, but not excessively

**Three Operations**:

| Operation | Effect | When Used |
|-----------|--------|-----------|
| Reassign | Move slice from hot to cold pod | Most common |
| Replicate | Add replica on cold pod | Hot slice can't be split further |
| Dereplicate | Remove replica from hot pod | Above minimum replicas |

**The Greedy Loop**:

```scala
// dicer/assigner/algorithm/src/PlacementPhase.scala:95-153
def run(): Unit = {
  val deadline = PLACEMENT_TIMEOUT.fromNow  // 30 seconds max

  while (assignment.eligibleResourcesRemain && deadline.hasTimeLeft()) {
    val hottestResource = assignment.hottestResourceState
    val candidateOps = generateCandidateOperations(hottestResource)

    var bestOp: Option[Operation] = None
    var bestDelta: Double = 0.0  // Negative = improvement

    for (op <- candidateOps) {
      val delta = calculateOperationDelta(op, resourceStates)
      if (delta < bestDelta) {
        bestDelta = delta
        bestOp = Some(op)
      }
    }

    if (bestOp.isDefined && bestDelta < -1e-10) {
      applyOperation(bestOp.get)
    } else {
      hottestResource.exclude()  // Can't improve, try next hottest
    }
  }
}
```

**Termination conditions**:
1. No eligible resources remain (all excluded)
2. 30-second timeout reached
3. No improving operation found for any resource

### 5.9 Phase 7: Final Merge

The placement phase may replicate slices, pushing total replicas above the maximum. This final merge pass cleans up:

```scala
// Same as Phase 4, but typically a no-op
MergePhase.merge(config, resources, assignment)
```

### 5.10 Slice Count Invariants

The algorithm maintains these bounds:

```scala
// dicer/assigner/algorithm/src/Algorithm.scala:30-35
val MIN_AVG_SLICE_REPLICAS: Int = 32   // Per resource
val MAX_AVG_SLICE_REPLICAS: Int = 64   // Per resource

// Total slice replicas must be in:
// [numResources * 32, numResources * 64]
```

**Why these bounds?**
- **Lower bound**: Ensures sufficient granularity for load balancing
- **Upper bound**: Limits memory overhead and assignment size

---

## 6. Deep Dive: The Assigner

### 6.1 Assigner Architecture

```scala
// dicer/assigner/src/Assigner.scala:81-94
@ThreadSafe
class Assigner private (
    assignerSecPool: SequentialExecutionContextPool,  // Thread pool
    sec: SequentialExecutionContext,                  // Main SEC
    conf: DicerAssignerConf,
    preferredAssignerDriver: PreferredAssignerDriver, // HA
    store: Store,                                     // etcd/InMemory
    kubernetesTargetWatcherFactory: KubernetesTargetWatcher.Factory,
    healthWatcherFactory: HealthWatcher.Factory,
    configProvider: StaticTargetConfigProvider,
    ...
)
```

### 6.2 Sequential Execution Context (SEC)

SECs are Dicer's answer to concurrency control:

```scala
// One SEC per target for isolation
// Operations are serialized within an SEC
// Multiple SECs can run concurrently across the pool

val assignerSecPool = SequentialExecutionContextPool.create("Assigner", numThreads = 8)
val generatorSec = assignerSecPool.createExecutionContext(s"generation-$target")
```

**Key insight**: SECs prevent race conditions on assignment state without coarse-grained locks.

### 6.3 Watch Protocol

The watch protocol enables efficient assignment distribution:

```scala
// dicer/assigner/src/Assigner.scala:187-229
def handleWatch(rpcContext: RPCContext, req: ClientRequestP): Future[ClientResponseP] = {
  sec.flatCall {
    val request = ClientRequest.fromProto(targetUnmarshaller, req)

    preferredAssignerConfig.role match {
      case AssignerRole.Preferred =>
        // Handle locally
        val generator = lookupGenerator(request.target)
        subscriberManager.handleWatchRequest(
          rpcContext, request, generator.getGeneratorCell, redirect
        )

      case AssignerRole.Standby =>
        // Redirect to preferred assigner
        Future.successful(ClientResponse(
          syncState = KnownGeneration(Generation.EMPTY),
          redirect = preferredAssignerConfig.redirect
        ).toProto)
    }
  }
}
```

### 6.4 Preferred Assigner (HA)

Dicer uses leader election for high availability:

```scala
// Only preferred assigner generates assignments
// Standby assigners redirect requests
// On termination, preferred abdicates

ShutdownHookManager.addShutdownHook(TERMINATION_SHUTDOWN_HOOK_PRIORITY) {
  preferredAssignerDriver.sendTerminationNotice()
}
```

### 6.5 Generator Lifecycle

Each target has its own `AssignmentGeneratorDriver`:

```scala
// dicer/assigner/src/Assigner.scala:420-438
private def lookupGenerator(target: Target): Option[AssignmentGeneratorHandle] = {
  configProvider.getLatestTargetConfigMap.get(targetName).map { targetConfig =>
    generatorMap.getOrElseUpdate(target, {
      // Create new generator on first watch for this target
      val generator = createGenerator(target, targetConfig)
      new AssignmentGeneratorHandle(generator, sec.getClock.tickerTime())
    })
  }
}
```

Generators are cleaned up after inactivity:

```scala
// dicer/assigner/src/Assigner.scala:682-711
private def cleanupInactiveGenerators(): Unit = {
  val inactiveEntries = generatorMap.filter { case (_, handle) =>
    (currentTime - handle.getLastWatchTime) >= conf.generatorInactivityDeadline
  }
  for ((target, handle) <- inactiveEntries) {
    handle.getGeneratorDriver.shutdown()
    generatorMap.remove(target)
  }
}
```

---

## 7. Deep Dive: Client Libraries

### 7.1 Clerk: The Router

```scala
// dicer/external/src/Clerk.scala:27-56
@ThreadSafe
final class Clerk[Stub <: AnyRef] private (impl: ClerkImpl[Stub]) {

  // O(log n) lookup in SliceMap
  def getStubForKey(key: SliceKey): Option[Stub] = impl.getStubForKey(key)

  // Resolves when initial assignment received
  def ready: Future[Unit] = impl.ready
}
```

**Internal Structure**:

```scala
// dicer/client/src/ClerkImpl.scala:48-68
class ClerkImpl[Stub <: AnyRef] private (
    sec: SequentialExecutionContext,
    target: Target,
    lookup: SliceLookup,           // Watches for assignments
    subscriberDebugName: String,
    stubFactory: ResourceAddress => Stub  // Creates RPC stubs
) {
  // Cache stubs for efficiency
  private val resourceRouter = new ResourceRouter[Stub](
    lookup.cellConsumer,
    stubFactory,
    stubCacheLifetime = 1.hour
  )
}
```

### 7.2 Slicelet: The Assignment Handler

```scala
// dicer/external/src/Slicelet.scala:10-81
@ThreadSafe
final class Slicelet private (impl: SliceletImpl) {

  // Register with Dicer, start accepting assignments
  def start(selfPort: Int, listenerOpt: Option[SliceletListener]): this.type

  // Create handle for request processing
  def createHandle(key: SliceKey): SliceKeyHandle

  // Query current assignments
  def assignedSlices: Seq[Slice]
}
```

### 7.3 SliceKeyHandle: Ownership Tracking

The handle pattern is critical for correctness:

```scala
// dicer/external/src/Slicelet.scala:107-204
@ThreadSafe
final class SliceKeyHandle private[external] (impl: SliceKeyHandleImpl) extends AutoCloseable {

  // Check continuous ownership since handle creation
  def isAssignedContinuously: Boolean = impl.isAssignedContinuously

  // Report load for this key
  def incrementLoadBy(value: Int): Unit = impl.incrementLoadBy(value)

  // MUST be called when done
  override def close(): Unit = impl.close()
}
```

**Usage Pattern**:

```scala
// From demo server: dicer/demo/src/server/DemoServerMain.scala:157-180
Using.resource(slicelet.createHandle(sliceKey)) { handle =>
  if (!handle.isAssignedContinuously) {
    // Log warning but still serve (availability over consistency)
    logger.warn(s"Key $key may not be assigned to this server")
  }

  // Always report load, even for unassigned keys
  handle.incrementLoadBy(100)

  // Process request
  cache.getIfPresent(key)
}
```

---

## 8. Data Structures

### 8.1 SliceMap: Efficient Range Map

```scala
// dicer/friend/src/SliceMap.scala:28-51
class SliceMap[T](
    val entries: immutable.Vector[T],
    val getSlice: T => Slice
) {
  // O(log n) lookup using binary search
  def lookUp(key: SliceKey): T =
    entries(SliceMap.findIndexInOrderedDisjointEntries(entries, key, getSlice))
}
```

**Binary Search Implementation**:

```scala
// dicer/friend/src/SliceMap.scala:248-289
@tailrec
def findIndexInOrderedDisjointEntries[T](
    entries: Seq[T], key: SliceKey, getSlice: T => Slice
): Int = {
  def search(begin: Int, end: Int): Int = {
    if (begin >= end) return -1
    val mid = (begin + end) >>> 1  // Overflow-safe
    val midSlice = getSlice(entries(mid))

    if (key < midSlice.lowInclusive) search(begin, mid)
    else midSlice.highExclusive match {
      case high: SliceKey if key >= high => search(mid + 1, end)
      case _ => mid  // Found!
    }
  }
  search(0, entries.size)
}
```

### 8.2 LoadMap: Per-Key Load Tracking

```scala
// Used in algorithm for load-aware decisions
val adjustedLoadMap: LoadMap = computeAdjustedLoadMap(
  targetConfig.loadBalancingConfig,
  availableResourceCount,
  loadMap
)

// Includes "uniform reservation" - predicted future load
def withAddedUniformLoad(uniformReservedLoad: Double): LoadMap
```

### 8.3 MutableAssignment: Algorithm Working State

```scala
// dicer/assigner/algorithm/src/MutableAssignment.scala
class MutableAssignment(
    instant: Instant,
    baseAssignment: SliceMap[SliceAssignment],
    resources: Resources,
    loadMap: LoadMap,
    churnConfig: ChurnConfig
) {
  // Mutable slice assignments
  class MutableSliceAssignment {
    def allocateResource(resource: ResourceState): Unit
    def deallocateResource(resource: ResourceState): Unit
    def reassignResource(from: ResourceState, to: ResourceState): Unit
    def rawLoadPerReplica: Double
  }

  // Resource state tracking
  class ResourceState {
    def getTotalLoad: Double
    def getAssignedSlices: Set[MutableSliceAssignment]
    def exclude(): Unit  // Mark as non-improvable
  }
}
```

---

## 9. Request Flow Analysis

### 9.1 End-to-End Request Flow

```
1. Client Application
   |
   | SliceKey.apply(userKey, FarmHashFunction)
   v
2. Clerk.getStubForKey(sliceKey)
   |
   | SliceMap.lookUp(key) -> SliceAssignment
   | ResourceRouter.getStub(resourceAddress)
   v
3. gRPC Call to Server
   |
   v
4. DemoServiceImpl.getValue(request)
   |
   | slicelet.createHandle(sliceKey)
   | handle.isAssignedContinuously  // Check ownership
   | handle.incrementLoadBy(100)    // Report load
   | cache.getIfPresent(key)        // Process request
   | handle.close()
   v
5. Response to Client
```

### 9.2 Assignment Update Flow

```
1. Signal Change (health/load/K8s event)
   |
   v
2. AssignmentGenerator triggered
   |
   | Algorithm.generateAssignment()
   |   - DeallocationPhase
   |   - ConstraintPhase
   |   - Splitter (hot/min)
   |   - MergePhase
   |   - PlacementPhase
   v
3. New Assignment stored in etcd
   |
   v
4. DiffAssignment sent via Watch RPC
   |
   | toDiff(clientGeneration) -> partial diff
   v
5. Clerk/Slicelet applies diff
   |
   | Assignment.fromDiff(known, diff)
   v
6. Local SliceMap updated
```

### 9.3 Graceful Shutdown Flow

```
1. SIGTERM received
   |
   v
2. Shutdown hook triggered (30s delay)
   |
   | Thread.sleep(30000)  // Keep accepting requests
   v
3. Slicelet notifies Assigner
   |
   v
4. Assigner marks pod unhealthy
   |
   | DeallocationPhase removes slices
   v
5. New assignment propagated
   |
   v
6. Clerks route away from terminating pod
   |
   v
7. Server stops accepting requests
   |
   | server.shutdown()
   v
8. Drain in-flight RPCs (15s grace)
```

---

## 10. Key Design Decisions

### 10.1 Why Fingerprinted Keys?

**Problem**: Natural keys have skewed distributions (hot users, popular items)

**Solution**: 8-byte fingerprints via FarmHash
- Uniform distribution across key space
- Efficient comparison via `Long` operations
- Fixed size reduces memory overhead

### 10.2 Why Diff-Based Assignment Updates?

**Problem**: Assignments can be large (N slices * M resources)

**Solution**: Delta compression
- Only send changed slices
- Clients reconstruct full assignment
- Reduces bandwidth and processing

```scala
// dicer/common/src/Assignment.scala:93-120
def toDiff(diffGeneration: Generation): DiffAssignment = {
  val fullRequired = diffGeneration.incarnation != generation.incarnation
  if (fullRequired) {
    DiffAssignmentSliceMap.Full(sliceMap)
  } else {
    // Only slices with generation > diffGeneration
    val changed = sliceMap.entries.filter(_.generation > diffGeneration)
    DiffAssignmentSliceMap.Partial(diffGeneration, changed)
  }
}
```

### 10.3 Why Greedy Local Search for Placement?

**Problem**: Optimal placement is NP-hard

**Solution**: Greedy with timeouts
- Generate candidate operations from hottest resource
- Evaluate objective function delta
- Apply best improving operation
- 30-second timeout ensures termination

**Trade-off**: May not find global optimum, but:
- Fast enough for online use
- Good enough in practice
- Bounded latency

### 10.4 Why Sequential Execution Contexts?

**Problem**: Concurrent access to assignment state

**Solution**: Single-threaded async execution per component
- No locks needed
- Deterministic behavior
- Easy reasoning about state

**Trade-off**: Potential throughput bottleneck, mitigated by:
- Per-target isolation
- Thread pool sharing
- Non-blocking operations

### 10.5 Why Affinity vs Strong Consistency?

**Affinity Mode** (default):
- Only checks resource address match
- Pod restarts don't invalidate assignments
- Higher availability

**Strong Mode**:
- Requires exact SQUID match
- At most one owner per key guaranteed
- Lower availability during restarts

---

## 11. Testing Strategy

### 11.1 Test Categories

| Category | Location | Purpose |
|----------|----------|---------|
| Unit Tests | `*/test/*Suite.scala` | Component isolation |
| Integration | `*IntegrationSuite.scala` | Cross-component |
| Algorithm | `algorithm/test/` | Sharding correctness |
| End-to-End | `DicerTestEnvironment` | Full system |

### 11.2 DicerTestEnvironment

```scala
// dicer/external/src/DicerTestEnvironment.scala
// Creates in-process Dicer for testing
class DicerTestEnvironment {
  def createSlicelet(target: Target): Slicelet
  def createClerk[Stub](target: Target, stubFactory: ...): Clerk[Stub]
  def setAssignment(target: Target, assignment: ...): Unit
}
```

### 11.3 Fake Implementations

Key fakes for testing:
- `InMemoryStore`: No etcd dependency
- `FakeKubernetesTargetWatcher`: Simulate K8s events
- `TestAssigner`: Controllable assignment generation

---

## 12. Code Navigation Guide

### 12.1 Start Here (Recommended Order)

1. **Public API** (`dicer/external/src/`)
   - `Clerk.scala` - Client routing
   - `Slicelet.scala` - Server assignment
   - `SliceKey.scala` - Key representation

2. **Demo** (`dicer/demo/src/`)
   - `DemoServerMain.scala` - Complete example
   - `DemoClientMain.scala` - Client usage

3. **Algorithm** (`dicer/assigner/algorithm/src/`)
   - `Algorithm.scala` - Entry point
   - `AlgorithmExecutor.scala` - Phase orchestration
   - `PlacementPhase.scala` - Core logic

4. **Assigner** (`dicer/assigner/src/`)
   - `Assigner.scala` - Service orchestration
   - `AssignmentGenerator.scala` - Assignment production

5. **Data Structures** (`dicer/common/src/`, `dicer/friend/src/`)
   - `Assignment.scala` - Core structure
   - `SliceMap.scala` - Range map

### 12.2 Key Files by Topic

| Topic | Key Files |
|-------|-----------|
| Sharding Algorithm | `Algorithm.scala`, `PlacementPhase.scala`, `AlgorithmExecutor.scala` |
| Assignment Distribution | `Assigner.scala`, `SubscriberManager.scala` |
| Client Routing | `ClerkImpl.scala`, `ResourceRouter.scala`, `SliceLookup.scala` |
| Server Integration | `SliceletImpl.scala`, `SliceKeyHandleImpl.scala` |
| Watch Protocol | `WatchServerHelper.scala`, `AssignmentSyncStateMachine.scala` |
| Configuration | `target.proto`, `InternalTargetConfig.scala` |
| Persistence | `EtcdStore.scala`, `InMemoryStore.scala` |

### 12.3 Important Invariants

1. **SliceMap completeness**: Slices are disjoint and cover `["", ∞)`
2. **Slice replica bounds**: Between `N*32` and `N*64` total replicas
3. **Generation monotonicity**: Always increasing within incarnation
4. **Resource health**: Only healthy resources receive slices

---

## 13. References

### Academic Papers

1. **Slicer** (OSDI 2016) - Dicer's primary inspiration
   - Adya et al., "Slicer: Auto-Sharding for Datacenter Applications"
   - [Paper PDF](https://www.usenix.org/system/files/conference/osdi16/osdi16-adya.pdf)

2. **Fast Key-Value Stores** (HotOS 2019) - Motivation for in-memory colocation
   - Adya et al., "Fast key-value stores: An idea whose time has come and gone"
   - [Paper PDF](https://dl.acm.org/doi/10.1145/3317550.3321434)

3. **Centrifuge** (NSDI 2010) - Lease management background
   - Adya et al., "Centrifuge: Integrated Lease Management and Partitioning"

4. **Shard Manager** (SOSP 2021) - Facebook's approach
   - Lee et al., "Shard Manager: A Generic Shard Management Framework"

5. **Bigtable** (OSDI 2006) - Load-driven splits origin
   - Chang et al., "Bigtable: A Distributed Storage System for Structured Data"

### Internal Resources

- [Databricks Blog: Open Sourcing Dicer](https://www.databricks.com/blog/open-sourcing-dicer-databricks-auto-sharder)
- `docs/UserGuide.md` - Integration guide
- `docs/BestPractices.md` - Operational guidance
- `docs/FAQ.md` - Common questions

---

## Appendix: Quick Reference

### A. Configuration Checklist

```protobuf
# dicer/external/config/<env>/<target>.textproto
owner_team_name: "your-team"
default_config {
  primary_rate_metric_config {
    max_load_hint: 50000           # Pod capacity
    imbalance_tolerance_hint: DEFAULT
  }
}
```

### B. Environment Variables

| Variable | Required | Purpose |
|----------|----------|---------|
| `POD_IP` | Yes | Pod network address |
| `POD_UID` | Yes | Kubernetes pod UID |
| `LOCATION` | Yes | Cluster URI |
| `NAMESPACE` | No | Kubernetes namespace |

### C. Build Commands

```bash
# Build everything
bazel build //...

# Run all tests
bazel test //...

# Build specific module
bazel build //dicer/assigner/...

# Run demo
cd dicer/demo && ./scripts/setup.sh
```

### D. Metrics to Monitor

| Metric | Description |
|--------|-------------|
| `dicer_slice_key_byte_size` | Key size distribution |
| `dicer_assignment_generation` | Current assignment version |
| `dicer_misdirected_requests` | Routing accuracy |
| `dicer_load_per_resource` | Load distribution |

---

*Last updated: January 2026*

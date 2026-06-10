package com.databricks.dicer.assigner

import javax.annotation.concurrent.ThreadSafe

import com.databricks.caching.util.{Cancellable, ValueStreamCallback, WatchValueCell}

/**
 * A fake implementation of [[TargetMigrator]]. Tests can call [[setSnapshot]] to drive runtime
 * snapshot changes.
 */
@ThreadSafe
private[dicer] class FakeTargetMigrator(initialSnapshot: TargetMigrationSnapshot)
    extends TargetMigrator {

  /** The current [[TargetOwnershipResolver]], initialized to `initialSnapshot`. */
  private val cell: WatchValueCell[TargetOwnershipResolver] = {
    val c = new WatchValueCell[TargetOwnershipResolver]
    c.setValue(new TargetOwnershipResolver(initialSnapshot))
    c
  }

  override def getLatestResolver: TargetOwnershipResolver = {
    // Safe to call `get`, the cell is seeded directly in the constructor.
    cell.getLatestValueOpt.get
  }

  override def watch(callback: ValueStreamCallback[TargetOwnershipResolver]): Cancellable =
    cell.watch(callback)

  /** Builds a new [[TargetOwnershipResolver]] from `snapshot` and publishes it to watchers. */
  def setSnapshot(snapshot: TargetMigrationSnapshot): Unit = {
    cell.setValue(new TargetOwnershipResolver(snapshot))
  }
}

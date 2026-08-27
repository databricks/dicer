package com.databricks.dicer.assigner

import java.util.UUID
import javax.annotation.concurrent.ThreadSafe

import scala.concurrent.Future

import com.databricks.caching.util.{Cancellable, ValueStreamCallback, WatchValueCell}
import com.databricks.dicer.assigner.config.TargetMigrationConfig

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
    c.setValue(new TargetOwnershipResolver(FakeTargetMigrator.FAKE_ASSIGNER_UUID, initialSnapshot))
    c
  }

  override def getLatestResolver: TargetOwnershipResolver = {
    // Safe to call `get`, the cell is seeded directly in the constructor.
    cell.getLatestValueOpt.get
  }

  override def watch(callback: ValueStreamCallback[TargetOwnershipResolver]): Cancellable =
    cell.watch(callback)

  /** No-op: this fake is not used to exercise gossip, so it never gossips a value back. */
  override def handleGossipRequest(
      peerConfigOpt: Option[TargetMigrationConfig]): Future[Option[TargetMigrationConfig]] =
    Future.successful(None)

  /** Builds a new [[TargetOwnershipResolver]] from `snapshot` and publishes it to watchers. */
  def setSnapshot(snapshot: TargetMigrationSnapshot): Unit = {
    cell.setValue(new TargetOwnershipResolver(FakeTargetMigrator.FAKE_ASSIGNER_UUID, snapshot))
  }
}

private[dicer] object FakeTargetMigrator {

  /** An arbitrary fixed Assigner UUID used to build the fake's resolvers. */
  private val FAKE_ASSIGNER_UUID = new UUID(128, 256)
}

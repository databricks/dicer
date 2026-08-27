package com.databricks.dicer.client

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

import scala.concurrent.duration.FiniteDuration

import com.databricks.caching.util.Lock.withLock
import com.databricks.caching.util.{HyperLogLog, SlidingHyperLogLog, TickerTime, TypedClock}
import com.databricks.dicer.external.SliceKey
import javax.annotation.concurrent.{GuardedBy, ThreadSafe}

/**
 * SliceletKeyCardinalityEstimator is used for keeping running estimate of the cardinality of the
 * keyspace of a user of Dicer for the purposes of reporting metrics. Dicer is only capable of
 * rebalancing effectively when |keys| >> |slicelets|, so this is important information to
 * understand usage of Dicer.
 *
 * This class is thread-safe, since [[add()]] needs to be called via [[SliceHandle]]s.
 */
@ThreadSafe
private[dicer] final class SliceletKeyCardinalityEstimator(clock: TypedClock) {

  /**
   * Because [[add()]] is called from the Slicelet's [[createHandle]]/[[incrementLoadBy]],
   * throughput is quite important. The natural implementation in here would be to just use a
   * [single mutex][1], but it's important for this to be as cheap as possible.
   *
   * Fortunately, HyperLogLogs stripe trivially because they support [[merge()]]. Writes can go to
   * any of a set of HyperLogLogs, and then results can be recovered by merging all of them.
   *
   * [1]: <internal link>.md#preemptive-isolation-domain
   */
  private val stripes =
    new Array[SliceletKeyCardinalityEstimator.Stripe](SliceletKeyCardinalityEstimator.N_STRIPES)

  for (i <- 0 until stripes.length) {
    stripes(i) = new SliceletKeyCardinalityEstimator.Stripe()
  }

  /** Add the given key to the cardinality estimate. */
  def add(key: SliceKey): Unit = {
    // Any choice of stripe is legal here and will produce the same results. Thread ID is readily
    // available and cheap, random choice or key hash would also work fine.
    stripes((Thread.currentThread().getId() % stripes.length).toInt).add(clock.tickerTime(), key)
  }

  /**
   * Returns a HyperLogLog representing the estimate of the cardinality of recently observed keys.
   * Returned as a HyperLogLog rather than the raw estimate so that it can be merged with estimates
   * from other Slicelets without double-counting.
   */
  def recent(): HyperLogLog = {
    val now = clock.tickerTime()
    val merged = new HyperLogLog()
    for (stripe: SliceletKeyCardinalityEstimator.Stripe <- stripes) {
      merged.merge(stripe.recent(now))
    }
    merged
  }
}

private object SliceletKeyCardinalityEstimator {

  /**
   * The number of stripes. Each stripe is a mutex, so more stripes reduces contention in [[add()]]
   * at the cost of more space (each SlidingHyperLogLog costs ~1KB) and a slower [[recent()]]. A
   * choice on the order of number of expected cores should do fine - it's likely the process is
   * doing work other than just [[createHandle]]/[[incrementLoadBy]].
   */
  private val N_STRIPES: Int = 32

  /** The retention window for the Slicelet's definition of "recent." */
  private val RETENTION: FiniteDuration = new FiniteDuration(60, TimeUnit.SECONDS)

  private final class Stripe() {

    private val lock = new ReentrantLock()

    @GuardedBy("lock")
    private val hll = new SlidingHyperLogLog(SliceletKeyCardinalityEstimator.RETENTION)

    /** Add the given key to the cardinality estimate. */
    def add(now: TickerTime, key: SliceKey): Unit = withLock(lock) {
      hll.add(now, key.toRawBytes)
    }

    /**
     * Returns a HyperLogLog representing the estimate of the cardinality of recently observed keys.
     * Returned as a HyperLogLog rather than the raw estimate so that it can be merged with
     * estimates from other Slicelets without double-counting.
     */
    def recent(now: TickerTime): HyperLogLog = withLock(lock) {
      hll.recent(now)
    }
  }
}

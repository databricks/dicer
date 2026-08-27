package com.databricks.dicer.client

import com.databricks.caching.util.{FakeTypedClock, TestUtils}
import com.google.protobuf.ByteString
import com.databricks.dicer.external.SliceKey
import com.databricks.testing.DatabricksTest

class SliceletKeyCardinalityEstimatorSuite extends DatabricksTest {
  test("thread safety") {
    // Test plan: verify that interacting with the SliceletKeyCardinalityEstimator from multiple
    // threads doesn't cause anything bad to happen.

    val clock = new FakeTypedClock()
    val estimator = new SliceletKeyCardinalityEstimator(clock)

    val N_THREADS = 8
    val N_KEYS_PER_THREAD = 10000
    val N_KEYS = N_THREADS * N_KEYS_PER_THREAD
    val keys: Array[Array[SliceKey]] = (0 until N_THREADS).iterator
      .map(threadIdx => {
        (0 until N_KEYS_PER_THREAD).iterator
          .map(i => {
            val id = (threadIdx.toLong << 48) | i
            SliceKey.fromRawBytes(ByteString.copyFrom(BigInt(id.toLong).toByteArray))
          })
          .toArray
      })
      .toArray

    val threads: Vector[Thread] = (0 until N_THREADS).toVector.map { threadIdx =>
      val thread = new Thread(() => {
        val threadKeys = keys(threadIdx)
        for (key: SliceKey <- threadKeys) {
          estimator.add(key)
        }
      })
      thread.start()
      thread
    }

    for (thread <- threads) {
      thread.join()
    }

    val actual: Long = estimator.recent().estimate()
    TestUtils.assertApproxEqual(
      actual.toDouble,
      N_KEYS.toDouble,
      N_KEYS.toDouble * 0.15
    )
  }
}

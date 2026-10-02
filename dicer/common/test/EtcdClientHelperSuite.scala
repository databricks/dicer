package com.databricks.dicer.common

import com.databricks.testing.DatabricksTest
import com.databricks.caching.util.EtcdClient.{Version => EtcdClientVersion}

class EtcdClientHelperSuite extends DatabricksTest {

  test(
    "createGenerationFromVersion returns the expected values and roundtrips with " +
    "getVersionFromGeneration"
  ) {
    // Test plan: Verify that createGenerationFromVersion returns the expected Generation for
    // various versions and store incarnations, and roundtrips with getVersionFromGeneration.

    for (storeIncarnation <- Seq[Long](0, 2, 3, 42, 5L << 48 - 1)) {
      for (generationNumber <- Seq(0, 1, 42, Long.MaxValue)) {
        val version = EtcdClientVersion(
          storeIncarnation,
          generationNumber
        )
        val generation: Generation = EtcdClientHelper.createGenerationFromVersion(version)
        assert(generation.incarnation.value >= 0)
        assert(generation.incarnation.value == version.highBits)
        assert(generation.number.value == version.lowBits.value)
        assert(EtcdClientHelper.getVersionFromGeneration(generation) == version)
      }
    }
  }
}

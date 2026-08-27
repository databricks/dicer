package com.databricks.dicer.common
import scala.concurrent.duration.Duration

import com.databricks.caching.util.AlertOwnerTeam
import com.databricks.caching.util.TestUtils
import com.databricks.caching.util.SequentialExecutionContext
import com.databricks.dicer.common.SlicezKeyTracker.SlicezKeyTrackable
import com.databricks.dicer.common.TestSliceUtils.{encryptedFp, fp, identityKey}
import org.apache.commons.lang3.StringUtils
import com.databricks.testing.DatabricksTest
import scalatags.Text.all._
import com.databricks.dicer.external.{SliceKey, Target}

class SlicezKeyTrackerSuite extends DatabricksTest {

  /** The sequential execution context the `slicezKeyTracker` runs on. */
  private val sec = SequentialExecutionContext.createWithDedicatedPool(
    name = "SlicezKeyTrackerSuite",
    alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
  )

  private val slicezKeyTracker = new SlicezKeyTracker("Test", sec)

  private val TARGET1 = Target("target1")
  private val TARGET2 = Target("target2")

  /**
   * Simulates real [[SlicezKeyTrackable]] associated with a target.
   *
   * @param target the target to be tracked.
   * @param existingKeys keys that have associated information with the target.
   */
  private class SlicezKeyTrackableForTest(target: Target, existingKeys: Seq[SliceKey])
      extends SlicezKeyTrackable {
    override def getTarget: Target = target

    /** Returns associated information for `key`. */
    override def searchBySliceKey(key: SliceKey): Option[String] = {
      if (existingKeys.contains(key)) {
        Some(s"${target.name}-$key")
      } else {
        None
      }
    }
  }

  /** Clears the states in tracker before each test. */
  override def beforeEach(): Unit = {
    slicezKeyTracker.forTest.reset()
  }

  test("Check if the track, and remove actions set the tracker state correctly.") {
    // Test plan: get the action callbacks registered in [[DebugStringServletRegistry]], test them
    // against different cases of user input. Check that the tracker state is correct for valid
    // inputs.

    // Valid inputs.
    // Some of them are targets and keys that exist in the fake data, some are not,
    // but it should not affect the behaviors of the tracker.
    val goodTargetAndKey: Seq[(String, String)] = Seq(
      ("hello", "world"),
      ("non-exist-target", "nonExistKey"),
      (TARGET1.toString, "hello"),
      (TARGET1.toString, "world"),
      (TARGET1.toString, "any"),
      (TARGET2.toString, "key"),
      (TARGET2.toString, "should be OK")
    )

    // New target-key pairs are correctly added to the tracker.
    for (tuple <- goodTargetAndKey) {
      val (targetDescription, key): (String, String) = tuple
      assert(
        !TestUtils
          .awaitResult(slicezKeyTracker.forTest.getAllTrackedTargetAndKey, Duration.Inf)
          .contains((targetDescription, key))
      )
      TestUtils.awaitResult(
        slicezKeyTracker.forTest.trackNewTargetKeyPair(targetDescription, key),
        Duration.Inf
      )
      assert(
        TestUtils
          .awaitResult(slicezKeyTracker.forTest.getAllTrackedTargetAndKey, Duration.Inf)
          .contains((targetDescription, key))
      )
    }

    // Target-key pairs are correctly removed from the tracker.
    for (tuple <- goodTargetAndKey) {
      val (targetDescription, key): (String, String) = tuple
      assert(
        TestUtils
          .awaitResult(slicezKeyTracker.forTest.getAllTrackedTargetAndKey, Duration.Inf)
          .contains((targetDescription, key))
      )
      TestUtils.awaitResult(
        slicezKeyTracker.forTest.removeTargetKeyPair(targetDescription, key),
        Duration.Inf
      )
      assert(
        !TestUtils
          .awaitResult(slicezKeyTracker.forTest.getAllTrackedTargetAndKey, Duration.Inf)
          .contains((targetDescription, key))
      )
    }
  }

  test("Check HTML is rendered correctly based on Trackables.") {
    // Test plan: feed some data (instances of `Trackable`) to the SlicezKeyTracker, and verify
    // that the HTML is rendered correctly.

    val trackableForTestSeq: Seq[SlicezKeyTrackableForTest] = Seq(
      new SlicezKeyTrackableForTest(
        TARGET1,
        Seq(
          identityKey("key0"),
          fp("key0"),
          encryptedFp("key0"),
          identityKey("key1"),
          fp("key1"),
          encryptedFp("key1"),
          identityKey("key2"),
          fp("key2"),
          encryptedFp("key2")
        )
      ),
      // No information in the Trackable for target2.
      new SlicezKeyTrackableForTest(TARGET2, existingKeys = Seq.empty)
    )

    // Verify HTML content when key tracker contains no tracked targets and keys.
    assert(
      TestUtils
        .awaitResult(slicezKeyTracker.getHtml(trackableForTestSeq), Duration.Inf)
        .render
        .contains("There is no tracked target and key currently.")
    )

    // Verify HTML content when non-existing targets are passed.
    val nonExistTargetAndKeys: Seq[(String, String)] = Seq(
      ("", ""), // empty target and keys
      ("non_existing_target", "non_existing_key")
    )

    for (tuple <- nonExistTargetAndKeys) {
      val (targetDescription, key): (String, String) = tuple
      TestUtils.awaitResult(
        slicezKeyTracker.forTest.trackNewTargetKeyPair(targetDescription, key),
        Duration.Inf
      )

      val targetNotFoundHTML: String =
        TestUtils.awaitResult(slicezKeyTracker.getHtml(trackableForTestSeq), Duration.Inf).render
      assert(
        targetNotFoundHTML.contains(
          s"Tracked information for target ${strong(targetDescription)} and key ${strong(key)}: "
        )
      )
      assert(targetNotFoundHTML.contains(s"The target $targetDescription is not found."))
    }

    // Verify HTML content when there is corresponding information exists in the target.
    val validTargetAndKeyWithInfo: Seq[(Target, String)] = Seq(
      (TARGET1, "key0"),
      (TARGET1, "key1"),
      (TARGET1, "key2")
    )
    slicezKeyTracker.forTest.reset()
    for (tuple <- validTargetAndKeyWithInfo) {
      val (target, key): (Target, String) = tuple
      val targetDescription: String = target.toParseableDescription
      TestUtils.awaitResult(
        slicezKeyTracker.forTest.trackNewTargetKeyPair(targetDescription, key),
        Duration.Inf
      )
      val infoFoundHtml: String =
        TestUtils.awaitResult(slicezKeyTracker.getHtml(trackableForTestSeq), Duration.Inf).render

      assert(
        infoFoundHtml
          .contains(
            s"Tracked information for target ${strong(targetDescription)} and key ${strong(key)}: "
          )
      )
      // Only the `Identity key` is present for the inputs "key0", "key1", "key2".
      assert(infoFoundHtml.contains("Identity key"))
      assert(infoFoundHtml.contains(s"${TARGET1.name}-${identityKey(key)}"))
      assert(infoFoundHtml.contains("FarmHashed key"))
      assert(infoFoundHtml.contains(s"${TARGET1.name}-${fp(key)}"))
      assert(infoFoundHtml.contains("SHA-256 hashed then FarmHashed key"))
      assert(infoFoundHtml.contains(s"${TARGET1.name}-${encryptedFp(key)}"))
    }

    // Verify HTML content when there is no corresponding information exists for given target.
    val validTargetAndKeyWithoutInfo: Seq[(Target, String)] = Seq(
      (TARGET2, "key0"),
      (TARGET2, "key1")
    )
    slicezKeyTracker.forTest.reset()

    for (tuple <- validTargetAndKeyWithoutInfo.zipWithIndex) {
      val ((target, key), index): ((Target, String), Int) = tuple
      val targetDescription: String = target.toParseableDescription
      TestUtils.awaitResult(
        slicezKeyTracker.forTest.trackNewTargetKeyPair(targetDescription, key),
        Duration.Inf
      )
      val infoNotFoundHtml: String =
        TestUtils.awaitResult(slicezKeyTracker.getHtml(trackableForTestSeq), Duration.Inf).render
      assert(
        infoNotFoundHtml.contains(
          s"Tracked information for target ${strong(targetDescription)} and key ${strong(key)}: "
        )
      )
      assert(
        StringUtils.countMatches(
          infoNotFoundHtml,
          s"There is no information found for target $targetDescription."
        ) == index + 1
      )
    }
  }

  test("Different tracker instances do not affect each other") {
    // Test plan: DebugStringServletRegistry is a singleton. If actions have the same name, the
    // last one registered will overwrite the others. This test verifies that with different
    // identifier, the trackers do not affect each other. Verify this by tracking different targets
    // and keys in different trackers, and check that the trackers do not contain each other's
    // target-key pairs.
    val secFoo =
      SequentialExecutionContext.createWithDedicatedPool(
        name = "Foo",
        alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
      )
    val secBar =
      SequentialExecutionContext.createWithDedicatedPool(
        name = "Bar",
        alertOwnerTeam = AlertOwnerTeam.CACHING_TEAM_NAME
      )
    val slicezKeyTrackerFoo = new SlicezKeyTracker("foo", secFoo)
    val slicezKeyTrackerBar = new SlicezKeyTracker("bar", secBar)

    val targetAndKeyFoo: Seq[(String, String)] =
      Seq((TARGET1.toString, "key1"), (TARGET1.toString, "key2"))
    val targetAndKeyBar: Seq[(String, String)] =
      Seq((TARGET1.toString, "key3"), (TARGET1.toString, "key4"))

    assert(
      !TestUtils
        .awaitResult(slicezKeyTrackerFoo.forTest.getAllTrackedTargetAndKey, Duration.Inf)
        .contains((TARGET1.toString, "key1"))
    )
    for (tuples <- targetAndKeyFoo zip targetAndKeyBar) {
      val (tupleFoo, tupleBar): ((String, String), (String, String)) = tuples
      val (targetDescriptionFoo, keyFoo): (String, String) = tupleFoo
      val (targetDescriptionBar, keyBar): (String, String) = tupleBar
      assert(
        !TestUtils
          .awaitResult(slicezKeyTrackerFoo.forTest.getAllTrackedTargetAndKey, Duration.Inf)
          .contains((targetDescriptionFoo, keyFoo))
      )
      assert(
        !TestUtils
          .awaitResult(slicezKeyTrackerBar.forTest.getAllTrackedTargetAndKey, Duration.Inf)
          .contains((targetDescriptionBar, keyBar))
      )
      // Track new key in the foo tracker and bar tracker respectively.
      TestUtils.awaitResult(
        slicezKeyTrackerFoo.forTest.trackNewTargetKeyPair(targetDescriptionFoo, keyFoo),
        Duration.Inf
      )
      TestUtils.awaitResult(
        slicezKeyTrackerBar.forTest.trackNewTargetKeyPair(targetDescriptionBar, keyBar),
        Duration.Inf
      )
      assert(
        TestUtils
          .awaitResult(slicezKeyTrackerFoo.forTest.getAllTrackedTargetAndKey, Duration.Inf)
          .contains((targetDescriptionFoo, keyFoo))
      )
      assert(
        !TestUtils
          .awaitResult(slicezKeyTrackerFoo.forTest.getAllTrackedTargetAndKey, Duration.Inf)
          .contains((targetDescriptionBar, keyBar))
      )
      assert(
        TestUtils
          .awaitResult(slicezKeyTrackerBar.forTest.getAllTrackedTargetAndKey, Duration.Inf)
          .contains((targetDescriptionBar, keyBar))
      )
      assert(
        !TestUtils
          .awaitResult(slicezKeyTrackerBar.forTest.getAllTrackedTargetAndKey, Duration.Inf)
          .contains((targetDescriptionFoo, keyFoo))
      )
    }
  }
}

package com.databricks.dicer.common
import java.nio.charset.StandardCharsets

import scala.collection.mutable
import scala.concurrent.Future

import scalatags.Text.TypedTag
import scalatags.Text.all._
import com.databricks.caching.util.SequentialExecutionContext
import com.google.protobuf.ByteString
import com.databricks.dicer.common.SlicezKeyTracker.{KEY_VARIANTS, SlicezKeyTrackable}
import com.databricks.dicer.external.{SliceKey, Target}
import com.databricks.instrumentation.DebugStringServlet
import com.databricks.instrumentation.DebugStringServletRegistry
import com.databricks.instrumentation.DebugStringServletRegistry.ActionKey

import javax.annotation.concurrent.ThreadSafe

/**
 * REQUIRES: `trackerName` must be unique across this entire process.
 *
 * The class that tracks the target-key pairs that are subscribed by the users in the debug page.
 * Additionally, it generates the HTML component that displays, adds and removes tracked target-key
 * pairs.
 *
 * @param trackerName an identifier that indicates the type of information tracked, such as
 *                    "Assignment" or "State Transfer". This MUST be unique per process, to ensure
 *                    that each action registered to the global [[DebugStringServletRegistry]] is
 *                    unique.
 * @param sec the sequential execution context that protects the internal state.
 *
 */
@ThreadSafe
class SlicezKeyTracker(trackerName: String, sec: SequentialExecutionContext) {

  /** A mutable set that stores all the target-key pairs tracked. */
  private val trackedTargetAndKey: mutable.Set[(String, String)] = mutable.Set.empty

  /** Name of the action to add new tracked keys, also the prompts to the users. */
  private val TRACK_NEW_TARGET_AND_KEY_LABEL = s"Track New Key for $trackerName"

  /** Name of the action to remove an existed tracked key */
  private val REMOVE_TRACKED_TARGET_AND_KEY_LABEL = s"Remove Key for $trackerName"

  // Register an action to respond to the user requests to track a new target-key pair.
  DebugStringServletRegistry.registerAction(
    TRACK_NEW_TARGET_AND_KEY_LABEL,
    (actionMap: Map[String, String]) => {
      insertTargetAndKey(actionMap("targetDescription"), actionMap("userInput"))
    },
    method = "GET"
  )

  // Register an action to respond to the user requests to remove a tracked target-key pair.
  // Note that the removal action reads different fields from action map from insertion action.
  DebugStringServletRegistry.registerAction(
    REMOVE_TRACKED_TARGET_AND_KEY_LABEL,
    (actionMap: Map[String, String]) => {
      removeTargetAndKey(actionMap("targetDescription"), actionMap("key"))
    },
    method = "GET"
  )

  /** Returns a Future of HTML components based on the sequence of [[SlicezKeyTrackable]]s. */
  def getHtml(trackables: Seq[SlicezKeyTrackable]): Future[TypedTag[String]] = {
    sec.call {
      buildHtml(trackables)
    }
  }

  /**
   * Generates the HTML representation of the tracked information for different variants of `key`
   * (see [[SlicezKeyTracker.KEY_VARIANTS]]) associated with the Dicer Target identified by
   * `targetDescription`.
   *
   * @param trackables A sequence of [[SlicezKeyTrackable]]s used to search for the tracked
   *                   information.
   * @param targetDescription The debug string representation of the target name.
   * @param key A [[String]] of a key in UTF-8 representation.
   * @return an HTML representation of the tracked information, or an error prompt if no associated
   *         information exists for the specified target-key pair.
   */
  private def createSearchResultDiv(
      trackables: Seq[SlicezKeyTrackable],
      targetDescription: String,
      key: String): TypedTag[String] = {
    sec.assertCurrentContext()
    val targetTrackableOpt: Option[SlicezKeyTrackable] = trackables.find(
      (trackable: SlicezKeyTrackable) =>
        trackable.getTarget.toParseableDescription == targetDescription
    )
    val trackedInfoHTML: TypedTag[String] = targetTrackableOpt match {
      case Some(trackable: SlicezKeyTrackable) =>
        // For each key variant, search for the tracked information.
        val searchResults: Map[String, String] = KEY_VARIANTS
          .map {
            case (variant: String, sliceKeyFunction: (Array[Byte] => SliceKey)) =>
              val keyInBytes: Array[Byte] = key.getBytes(StandardCharsets.UTF_8)
              val sliceKey: SliceKey = sliceKeyFunction(keyInBytes)
              variant -> trackable.searchBySliceKey(sliceKey)
          }
          .collect {
            case (variant: String, Some(trackedInfo: String)) => variant -> trackedInfo
          }
        if (searchResults.isEmpty) {
          ul(s"There is no information found for target $targetDescription.")
        } else {
          ul(style := "margin: 0;")(
            searchResults.map {
              case (variant: String, trackedInfo: String) =>
                li(pre(s"""$variant: \n$trackedInfo"""))
            }.toSeq
          )
        }
      // error: target not found
      case None =>
        ul(style := "line-height: 0;")(li(s"The target $targetDescription is not found."))
    }

    div(
      RawFrag(
        s"Tracked information for target ${strong(targetDescription)} and key ${strong(key)}: "
      ),
      trackedInfoHTML
    )
  }

  /**
   * Builds the HTML for the tracker in the debug page based on a sequence of
   * [[SlicezKeyTrackable]]s.
   * The HTML contains input fields for adding new keys, buttons for removing tracked keys, and
   * sections containing the tracked information.
   *
   * @param trackables A sequence of [[SlicezKeyTrackable]]s used to search for the tracked
   *                   information.
   * @return A string of HTML that will be shown as the Zpages's tracker part.
   */
  private def buildHtml(trackables: Seq[SlicezKeyTrackable]): TypedTag[String] = {
    sec.assertCurrentContext()
    // Get all the targets we currently have, then show the input boxes to insert/remove tracked
    // items per target.
    val allTargetDescriptions: Seq[String] = trackables.map { data: SlicezKeyTrackable =>
      data.getTarget.toParseableDescription
    }.distinct

    val allInputBoxes: TypedTag[String] =
      ul(style := "line-height: 0;")(allTargetDescriptions.map { targetDescription: String =>
        // Add the prompt, insertion input box for each target name
        val perTargetPrompt: String = s"For target ${strong(targetDescription)}: "
        val trackerTrackNewInput: String =
          DebugStringServlet.makeActionInput(
            TRACK_NEW_TARGET_AND_KEY_LABEL,
            Map("targetDescription" -> targetDescription),
            useGet = true // use GET instead of the default POST
          )
        li(
          span(
            span(display.`inline-block`)(RawFrag(perTargetPrompt)),
            span(display.`inline-block`)(RawFrag(trackerTrackNewInput))
          )
        )
      })

    // Some prompts about the search results and errors.
    val emptyResultPrompt: String = if (trackedTargetAndKey.isEmpty) {
      "There is no tracked target and key currently."
    } else {
      "The information for the tracked target(s) and key(s) is(are):"
    }
    val trackerResultPrompt: TypedTag[String] = div(emptyResultPrompt)

    // Retrieve the tracked information for each target-key pair in `trackedTargetAndKey` from
    // `trackables`. A removal link will be added next to each result, to remove the target and key
    // from being tracked.
    val allResults: Seq[TypedTag[String]] = trackedTargetAndKey.map {
      case (targetDescription: String, key: String) =>
        val searchResultDiv: TypedTag[String] =
          createSearchResultDiv(trackables, targetDescription, key)
        val removalLink: String = DebugStringServlet.makeActionLink(
          REMOVE_TRACKED_TARGET_AND_KEY_LABEL,
          Map("targetDescription" -> targetDescription, "key" -> key),
          useGet = true // use GET instead of the default POST
        )
        div(searchResultDiv, RawFrag(removalLink))
    }.toSeq

    div(allInputBoxes, trackerResultPrompt, allResults)
  }

  /**
   * Tries to inserts a new pair of target-key to the tracker. If the target-key pair already
   * exists, it does nothing.
   */
  private def insertTargetAndKey(targetDescription: String, key: String): Unit = sec.run {
    trackedTargetAndKey += ((targetDescription, key))
  }

  /**
   * Tries to remove a tracked pair of target-key to the tracker. If the target-key pair does not
   * exist, it does nothing.
   */
  private def removeTargetAndKey(targetDescription: String, key: String): Unit = sec.run {
    trackedTargetAndKey -= ((targetDescription, key))
  }

  object forTest {

    /** Resets the tracked target-key pairs to empty, reset the error message to empty. */
    private[common] def reset(): Unit = {
      sec.run {
        trackedTargetAndKey.clear()
      }
    }

    /** Returns all the keys that this tracker is tracking. */
    private[common] def getAllTrackedTargetAndKey: Future[Set[(String, String)]] =
      sec.call(trackedTargetAndKey.toSet)

    /** Tracks a new target-key pair in the tracker. */
    private[common] def trackNewTargetKeyPair(
        targetDescription: String,
        key: String): Future[Unit] =
      sec.call {
        val addAction: DebugStringServletRegistry.ActionWithResult =
          DebugStringServletRegistry.actions.get(
            ActionKey(TRACK_NEW_TARGET_AND_KEY_LABEL, "GET")
          )
        addAction(Map("targetDescription" -> targetDescription, "userInput" -> key))
      }

    /** Stops tracking a target-key pair in the tracker. */
    private[common] def removeTargetKeyPair(targetDescription: String, key: String): Future[Unit] =
      sec.call {
        val addAction: DebugStringServletRegistry.ActionWithResult =
          DebugStringServletRegistry.actions.get(
            ActionKey(REMOVE_TRACKED_TARGET_AND_KEY_LABEL, "GET")
          )
        addAction(Map("targetDescription" -> targetDescription, "key" -> key))
      }
  }
}

object SlicezKeyTracker {

  /** A trait for data structures that can be feed into [[SlicezKeyTracker]]. */
  trait SlicezKeyTrackable {

    /** Returns the Dicer [[Target]] that is being tracked. */
    def getTarget: Target

    /** Searches for the tracked information associated with the SliceKey. */
    def searchBySliceKey(sliceKey: SliceKey): Option[String]
  }

  /**
   * The mapping from key variants to functions that generates the SliceKey based on
   * various common hashing approaches.
   */
  private val KEY_VARIANTS: Map[String, Array[Byte] => SliceKey] = Map(
    "Identity key" -> (key => SliceKey.fromRawBytes(ByteString.copyFrom(key))),
    "FarmHashed key" -> (key => SliceKey.newFingerprintBuilder().putBytes(key).build()),
    "SHA-256 hashed then FarmHashed key" -> (key => {
      val messageDigest = java.security.MessageDigest.getInstance("SHA-256")
      SliceKey.newFingerprintBuilder().putBytes(messageDigest.digest(key)).build()
    })
  )
}

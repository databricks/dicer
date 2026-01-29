package com.databricks.dicer.common

import scala.util.Random

import com.databricks.caching.util.TestUtils.assertThrow
import com.databricks.dicer.common.TestSliceUtils._
import com.databricks.dicer.external.Slice
import com.databricks.dicer.friend.{MutableSliceMap, SliceMap, Squid}
import com.databricks.dicer.friend.SliceMap.{IntersectionEntry, GapEntry}
import com.databricks.caching.util.UnixTimeVersion
import com.databricks.testing.DatabricksTest

class ProposedAssignmentSuite extends DatabricksTest {

  /** Asserts that committing `proposal` at `generation` results in the `expected` assignment. */
  private def testAsn(
      description: String,
      proposal: ProposedAssignment,
      generation: Generation,
      expected: Assignment): Unit = {
    val actual: Assignment =
      proposal.commit(
        isFrozen = false,
        AssignmentConsistencyMode.Affinity,
        generation
      )
    assert(actual == expected, s"unexpected commit outcome ($description)")
  }

  test("ProposedAssignment toString") {
    // Test plan: Verify that the output of `ProposedAssignment.toString` has the expected format.

    val proposedAssignment = ProposedAssignment(
      predecessorOpt = None,
      // Slice Assignments with one or multiple assigned resources, and with or without primary
      // rate load.
      createProposal(
        ("" -- "Balin" -> Seq("Pod0", "Pod1")).copy(primaryRateLoadOpt = None),
        ("Balin" -- "Kili" -> Seq("Pod1")).copy(primaryRateLoadOpt = Some(1.0)),
        ("Kili" -- ∞ -> Seq("Pod2", "Pod3", "Pod4")).copy(primaryRateLoadOpt = Some(3.0))
      )
    )
    val toStringLines: Seq[String] =
      proposedAssignment.toString.linesIterator.toSeq
    assert(toStringLines.size == 3)
    assert(
      """^\["" \.\. Balin\) -> \{\[Pod0.*], \[Pod1.*]}$""".r
        .findFirstIn(toStringLines.head)
        .isDefined
    )
    assert(
      """^\[Balin \.\. Kili\) -> \[Pod1.*] load=1\.0$""".r
        .findFirstIn(toStringLines(1))
        .isDefined
    )
    assert(
      """^\[Kili \.\. ∞\) -> \{\[Pod2.*], \[Pod3.*], \[Pod4.*]} load=3\.0$""".r
        .findFirstIn(toStringLines(2))
        .isDefined
    )
  }

  test("ProposedAssignment predecessorGenerationOrEmpty") {
    // Test plan: Verify that ProposedAssignment.predecessorGenerationOrEmpty returns correct
    // results.

    assert(
      ProposedAssignment(
        predecessorOpt = None,
        createProposal(
          ("" -- ∞) -> Seq("Pod0", "Pod1")
        )
      ).predecessorGenerationOrEmpty == Generation.EMPTY
    )

    assert(
      ProposedAssignment(
        predecessorOpt = Some(
          createAssignment(
            2 ## 42,
            AssignmentConsistencyMode.Affinity,
            ("" -- ∞) @@ (2 ## 42) -> Seq("Pod0", "Pod1")
          )
        ),
        createProposal(
          ("" -- ∞) -> Seq("Pod0", "Pod1")
        )
      ).predecessorGenerationOrEmpty == 2 ## 42
    )
  }

  test("ProposedAssignment invalid commit") {
    // Test plan: Verify that committing invalid proposals throws the expected exceptions. Using
    // `AssignmentConsistencyMode.Strong` throws `NotImplementedError`, and trying to commit
    // a proposal with an earlier generation than the predecessor

    assertThrow[NotImplementedError]("Only Affinity is currently supported") {
      ProposedAssignment(
        predecessorOpt = None,
        createProposal(
          ("" -- ∞) -> Seq("Pod0", "Pod1")
        )
      ).commit(
        isFrozen = false,
        AssignmentConsistencyMode.Strong,
        generation = 42
      )
    }

    for (proposalGeneration: Generation <- Seq[Generation](41, 42)) {
      assertThrow[IllegalArgumentException](
        s"Proposal generation $proposalGeneration must be greater than the predecessor"
      ) {
        ProposedAssignment(
          predecessorOpt = Some(
            createAssignment(
              42,
              AssignmentConsistencyMode.Affinity,
              ("" -- ∞) @@ 42 -> Seq("Pod1", "Pod2")
            )
          ),
          createProposal(
            ("" -- ∞) -> Seq("Pod0", "Pod1")
          )
        ).commit(
          isFrozen = false,
          AssignmentConsistencyMode.Affinity,
          proposalGeneration
        )
      }
    }
  }

  test("ProposedAssignment slice generation advanced correctly") {
    // Test plan: verify that committing proposals sets the generation for new mappings and when
    // committing based on a predecessor, preserves the existing generation for existing mappings
    // when the Slice satisfies the following conditions relative to the predecessor (and otherwise
    // does not):
    // 1. has the same slice boundaries (has not been split or merged),
    // 2. has the same set of assigned resources,
    // 3. has roughly the same load (within the provided threshold, will be verified in separate
    //    test case, see "ProposedAssignment commit generation advanced by load
    //    changes exhaustive"),
    // 4. has the same subslice annotations (in particular, subslice annotations can change even
    //    when all of the above holds when state transfer is disabled and needs to clear any
    //    existing state transfer subslice annotations),
    // 5. is in a non-loose assignment incarnation.

    testAsn(
      "previous generation preserved",
      proposal = ProposedAssignment(
        predecessorOpt = Some(
          createAssignment(
            2 ## 42,
            AssignmentConsistencyMode.Affinity,
            ("" -- "Balin") @@ (2 ## 42) -> Seq("Pod0", "Pod1"),
            ("Balin" -- ∞) @@ (2 ## 42) -> Seq("Pod2") | Map(
              "Pod2" -> Seq(
                SubsliceAnnotation("Fili" -- "Kili", 30, stateTransferOpt = None)
              )
            )
          )
        ),
        createProposal(
          ("" -- "Balin") -> Seq("Pod0", "Pod1"),
          ("Balin" -- ∞) -> Seq("Pod2")
        )
      ),
      generation = 2 ## 47,
      expected = createAssignment(
        2 ## 47,
        AssignmentConsistencyMode.Affinity,
        // All the conditions for carrying forward Slice Assignment are satisfied. Slice Assignments
        // should not change.
        ("" -- "Balin") @@ (2 ## 42) -> Seq("Pod0", "Pod1"),
        ("Balin" -- ∞) @@ (2 ## 42) -> Seq("Pod2") | Map(
          // Subslice Annotation is also carried forward.
          "Pod2" -> Seq(
            SubsliceAnnotation("Fili" -- "Kili", 30, stateTransferOpt = None)
          )
        )
      )
    )

    testAsn(
      "Slice generation advances when Slice boundaries change",
      proposal = ProposedAssignment(
        predecessorOpt = Some(
          createAssignment(
            2 ## 42,
            AssignmentConsistencyMode.Affinity,
            ("" -- "Fili") @@ (2 ## 42) -> Seq("Pod0", "Pod1"),
            ("Fili" -- ∞) @@ (2 ## 42) -> Seq("Pod1")
          )
        ),
        createProposal(
          // The high boundary of the first Slice changed from Fili to Kili.
          ("" -- "Kili") -> Seq("Pod0", "Pod1"),
          ("Kili" -- ∞) -> Seq("Pod1")
        )
      ),
      generation = 2 ## 47,
      expected = createAssignment(
        2 ## 47,
        // Both Slice Assignments should have new generation.
        AssignmentConsistencyMode.Affinity,
        (("" -- "Kili") @@ (2 ## 47) -> Seq("Pod0", "Pod1")) | Map(
          "Pod0" -> Seq(
            SubsliceAnnotation("" -- "Fili", 42, stateTransferOpt = None),
            SubsliceAnnotation("Fili" -- "Kili", 47, Some(Transfer(id = 0, fromResource = "Pod1")))
          ),
          // Note Pod1 is continuously assigned on both ["", Fili) and [Fili, Kili).
          "Pod1" -> Seq(
            SubsliceAnnotation("" -- "Kili", 42, stateTransferOpt = None)
          )
        ),
        (("Kili" -- ∞) @@ (2 ## 47) -> Seq("Pod1")) | Map(
          "Pod1" -> Seq(
            SubsliceAnnotation("Kili" -- ∞, 42, stateTransferOpt = None)
          )
        )
      )
    )

    testAsn(
      "Slice generation advanced when assigned resources change",
      proposal = ProposedAssignment(
        predecessorOpt = Some(
          createAssignment(
            2 ## 42,
            AssignmentConsistencyMode.Affinity,
            ("" -- "Fili") @@ (2 ## 42) -> Seq("Pod1", "Pod2"),
            ("Fili" -- "Kili") @@ (2 ## 42) -> Seq("Pod2"),
            ("Kili" -- ∞) @@ (2 ## 42) -> Seq("Pod2")
          )
        ),
        createProposal(
          ("" -- "Fili") -> Seq("Pod1"), // Pod2 removed.
          ("Fili" -- "Kili") -> Seq("Pod2", "Pod3"), // Pod3 added.
          ("Kili" -- ∞) -> Seq("Pod3") // Changed from Pod2 to Pod3.
        )
      ),
      generation = 2 ## 47,
      expected = createAssignment(
        2 ## 47,
        AssignmentConsistencyMode.Affinity,
        // All Slice generations are advanced.
        ("" -- "Fili") @@ (2 ## 47) -> Seq("Pod1") | Map(
          "Pod1" -> Seq(
            SubsliceAnnotation("" -- "Fili", 42, stateTransferOpt = None)
          )
        ),
        ("Fili" -- "Kili") @@ (2 ## 47) -> Seq("Pod2", "Pod3") | Map(
          "Pod2" -> Seq(
            SubsliceAnnotation("Fili" -- "Kili", 42, stateTransferOpt = None)
          ),
          "Pod3" -> Seq(
            SubsliceAnnotation("Fili" -- "Kili", 47, Some(Transfer(id = 0, fromResource = "Pod2")))
          )
        ),
        ("Kili" -- ∞) @@ (2 ## 47) -> Seq("Pod3") | Map(
          "Pod3" -> Seq(
            SubsliceAnnotation("Kili" -- ∞, 47, Some(Transfer(id = 1, fromResource = "Pod2")))
          )
        )
      )
    )

    testAsn(
      "Slice generation advances for loose incarnation",
      proposal = ProposedAssignment(
        predecessorOpt = Some(
          createAssignment(
            42,
            AssignmentConsistencyMode.Affinity,
            ("" -- "Balin") @@ 42 -> Seq("Pod0"),
            ("Balin" -- ∞) @@ 42 -> Seq("Pod1")
          )
        ),
        // Assigned exactly as before.
        createProposal(
          ("" -- "Balin") -> Seq("Pod0"),
          ("Balin" -- ∞) -> Seq("Pod1")
        )
      ),
      generation = 47,
      expected = createAssignment(
        47,
        AssignmentConsistencyMode.Affinity,
        // Slice generation updated.
        ("" -- "Balin") @@ 47 -> Seq("Pod0") | Map(
          "Pod0" -> Seq(
            SubsliceAnnotation("" -- "Balin", 42, stateTransferOpt = None)
          )
        ),
        ("Balin" -- ∞) @@ 47 -> Seq("Pod1") | Map(
          "Pod1" -> Seq(
            SubsliceAnnotation("Balin" -- ∞, 42, stateTransferOpt = None)
          )
        )
      )
    )
  }

  test("ProposedAssignment commit generation advanced by load changes exhaustive") {
    // Test plan: verify that for various permutations of previous load, proposed load, and
    // change threshold ratios that a committed Slice assignment has the expected generation: if
    // the primary rate load change is within the threshold, the generation is preserved and
    // previous load is preserved; otherwise, the generation is incremented and the proposed load
    // is used. When the Slice is new, the generation should increase but the subsliceAnnotations
    // should indicate continuity of the assignment.
    //
    // The behavior is different in the "loose" incarnation, where the generation is always updated
    // when there is any change in the primary rate load. We automatically adjust the declared test
    // cases for the loose incarnation variants to reflect this behavior.

    case class TestCase(
        previousLoadOpt: Option[Double],
        proposedLoadOpt: Option[Double],
        threshold: Double,
        expectUpdate: Boolean) {
      def toLooseIncarnationTestCase: TestCase = {
        // In the loose incarnation, the diffGeneration is always incremented.
        TestCase(
          previousLoadOpt,
          proposedLoadOpt,
          threshold,
          expectUpdate = true
        )
      }
    }
    val testCases = Seq(
      TestCase(None, None, 0.1, expectUpdate = false),
      TestCase(None, Some(0.0), 0.1, expectUpdate = true),
      TestCase(None, Some(10.0), 0.1, expectUpdate = true),
      TestCase(Some(0.0), None, 0.1, expectUpdate = true),
      TestCase(Some(10.0), None, 0.1, expectUpdate = true),
      TestCase(Some(0.0), Some(0.0), 0.1, expectUpdate = false),
      TestCase(Some(0.0), Some(10.0), 0.1, expectUpdate = true),
      TestCase(Some(10.0), Some(10.0), 0.1, expectUpdate = false),
      TestCase(Some(10.0), Some(11.0), 0.1, expectUpdate = false),
      TestCase(Some(10.0), Some(12.0), 0.1, expectUpdate = true),
      TestCase(Some(10.0), Some(9.0), 0.1, expectUpdate = false),
      TestCase(Some(10.0), Some(8.0), 0.1, expectUpdate = true)
    )
    for (incarnation <- Seq(
        0, // loose
        1, // loose
        2, // non-loose
        128 // non-loose
      )) {
      val adjustedTestCases = if (Incarnation(incarnation).isLoose) {
        testCases.map { testCase =>
          testCase.toLooseIncarnationTestCase
        }
      } else {
        testCases
      }
      for (testCase: TestCase <- adjustedTestCases) {
        // Create an predecessor assignment and a proposed assignment with a stably assigned Slice
        // including the previous load and proposed load, respectively.
        val predecessorAssignment: Assignment = createAssignment(
          generation = incarnation ## 42,
          AssignmentConsistencyMode.Affinity,
          (("" -- "Balin") @@ (incarnation ## 42) -> Seq("Pod0", "Pod1"))
            .copy(primaryRateLoadOpt = testCase.previousLoadOpt),
          ("Balin" -- ∞) @@ (incarnation ## 42) -> Seq("Pod1")
        )
        val proposedAssignment: ProposedAssignment = ProposedAssignment(
          predecessorOpt = Some(predecessorAssignment),
          createProposal(
            (("" -- "Balin") -> Seq("Pod0", "Pod1"))
              .copy(primaryRateLoadOpt = testCase.proposedLoadOpt),
            ("Balin" -- ∞) -> Seq("Pod2")
          )
        )
        val committedAssignment: Assignment = proposedAssignment
          .commit(
            isFrozen = false,
            AssignmentConsistencyMode.Affinity,
            generation = incarnation ## 47,
            primaryRateLoadThreshold = testCase.threshold
          )
        // Build expected Slice assignment (and overall assignment).
        val expectedSlice: SliceAssignment = if (testCase.expectUpdate) {
          // Although the Slice generation has changed, the subsliceAnnotation collection must
          // still indicate continuity of the assignment.
          val subsliceAnnotation =
            SubsliceAnnotation("" -- "Balin", 42, stateTransferOpt = None)
          (("" -- "Balin") @@ (incarnation ## 47) -> Seq("Pod0", "Pod1"))
            .copy(primaryRateLoadOpt = testCase.proposedLoadOpt) | Map(
            // Both resources are continuously assigned.
            "Pod0" -> Seq(subsliceAnnotation),
            "Pod1" -> Seq(subsliceAnnotation)
          )
        } else {
          (("" -- "Balin") @@ (incarnation ## 42) -> Seq("Pod0", "Pod1"))
            .copy(primaryRateLoadOpt = testCase.previousLoadOpt)
        }
        val expectedCommittedAssignment: Assignment = createAssignment(
          generation = incarnation ## 47,
          AssignmentConsistencyMode.Affinity,
          expectedSlice,
          ("Balin" -- ∞) @@ (incarnation ## 47) -> Seq("Pod2") | Map(
            "Pod2" -> Seq(
              SubsliceAnnotation(
                "Balin" -- ∞,
                continuousGenerationNumber = 47,
                Some(Transfer(id = 0, fromResource = "Pod1"))
              )
            )
          )
        )
        assert(committedAssignment.sliceMap.entries(0) == expectedSlice, s"testCase=$testCase")
        assert(committedAssignment == expectedCommittedAssignment, s"testCase=$testCase")
      }
    }
  }

  test("Proposal commit across incarnations") {
    // Test plan: verify that ProposedAssignment.commit() with a predecessor from a
    // lower incarnation succeeds but makes no claims about continuous assignment (even if some
    // slices and sub-slices are assigned to the same resources).
    val initialGeneration = Generation(Incarnation(10), 1)
    val newGeneration = Generation(Incarnation(11), 1)
    val initialAssignment: Assignment = createAssignment(
      initialGeneration,
      AssignmentConsistencyMode.Affinity,
      ("" -- "Balin") @@ initialGeneration -> Seq("pod0"),
      ("Balin" -- "Fili") @@ initialGeneration -> Seq("pod1"),
      ("Fili" -- ∞) @@ initialGeneration -> Seq("pod2")
    )

    val proposal: SliceMap[ProposedSliceAssignment] = createProposal(
      ("" -- "Balin") -> Seq("pod0"),
      ("Balin" -- "Kili") -> Seq("pod1"),
      ("Kili" -- ∞) -> Seq("pod2")
    )
    val newAssignment: Assignment =
      ProposedAssignment(predecessorOpt = Some(initialAssignment), proposal)
        .commit(
          isFrozen = false,
          AssignmentConsistencyMode.Affinity,
          newGeneration
        )

    assert(proposal.entries.size == newAssignment.sliceMap.entries.size)
    for (tuple <- proposal.entries.zip(newAssignment.sliceMap.entries)) {
      val (proposed, committed): (
          ProposedSliceAssignment,
          SliceAssignment
      ) = tuple
      assert(committed.slice == proposed.slice)
      assert(committed.resources == proposed.resources)
      assert(committed.generation == newGeneration)
      assert(
        committed.subsliceAnnotationsByResource.values.forall((_: Seq[SubsliceAnnotation]).isEmpty)
      )
    }
  }

  test("Continuous generation number created or carried forward correctly") {
    // Test plan: Verify that SubsliceAnnotations are created or carried forward from predecessor
    // with correct continuous generation number as expected with manual assignment changes. See
    // description fields of the sub-cases below for details.

    testAsn(
      "Subslice Annotations carried forward when generation is not advanced",
      proposal = ProposedAssignment(
        predecessorOpt = Some(
          createAssignment(
            2 ## 42,
            AssignmentConsistencyMode.Affinity,
            ("" -- "Balin") @@ (2 ## 42) -> Seq("Pod0") | Map(
              "Pod0" -> Seq(
                SubsliceAnnotation("" -- "Akka", 30, stateTransferOpt = None),
                SubsliceAnnotation("Akka" -- "Allen", 20, stateTransferOpt = None)
              )
            ),
            ("Balin" -- ∞) @@ (2 ## 42) -> Seq("Pod1", "Pod2") | Map(
              "Pod2" -> Seq(
                SubsliceAnnotation("Fili" -- "Kili", 30, stateTransferOpt = None)
              )
            )
          )
        ),
        createProposal(
          ("" -- "Balin") -> Seq("Pod0"), // Same as in predecessor.
          ("Balin" -- ∞) -> Seq("Pod1", "Pod2") // Same as in predecessor.
        )
      ),
      generation = 2 ## 47,
      // Both Slice Assignments are carried forward from previous and they should contain the same
      // Subslice Annotations as before.
      expected = createAssignment(
        2 ## 47,
        AssignmentConsistencyMode.Affinity,
        ("" -- "Balin") @@ (2 ## 42) -> Seq("Pod0") | Map(
          "Pod0" -> Seq(
            SubsliceAnnotation("" -- "Akka", 30, stateTransferOpt = None),
            SubsliceAnnotation("Akka" -- "Allen", 20, stateTransferOpt = None)
          )
        ),
        ("Balin" -- ∞) @@ (2 ## 42) -> Seq("Pod1", "Pod2") | Map(
          "Pod2" -> Seq(
            SubsliceAnnotation("Fili" -- "Kili", 30, stateTransferOpt = None)
          )
        )
      )
    )

    testAsn(
      "Subslice Annotations carried forward and created when generation advanced",
      proposal = ProposedAssignment(
        predecessorOpt = Some(
          createAssignment(
            2 ## 42,
            AssignmentConsistencyMode.Affinity,
            ("" -- "Balin") @@ (2 ## 42) -> Seq("Pod0") | Map(
              "Pod0" -> Seq(
                SubsliceAnnotation("" -- "Akka", 30, stateTransferOpt = None),
                SubsliceAnnotation("Akka" -- "Allen", 20, stateTransferOpt = None)
              )
            ),
            ("Balin" -- ∞) @@ (2 ## 42) -> Seq("Pod1", "Pod2") | Map(
              "Pod2" -> Seq(
                SubsliceAnnotation("Fili" -- "Kili", 30, stateTransferOpt = None)
              )
            )
          )
        ),
        createProposal(
          (("" -- "Balin") -> Seq("Pod0")).copy(
            // New primary rate load so generation will be advanced
            primaryRateLoadOpt = Some(1000.0)
          ),
          (("Balin" -- ∞) -> Seq("Pod1", "Pod2"))
            .copy(
              // New primary rate load so generation will be advanced
              primaryRateLoadOpt = Some(1000.0)
            )
        )
      ),
      generation = 2 ## 47,
      expected = createAssignment(
        2 ## 47,
        // Both Slice Assignments should have new generation, with Subslice Annotations either
        // newly created or carried forward from predecessor.
        AssignmentConsistencyMode.Affinity,
        (("" -- "Balin") @@ (2 ## 47) -> Seq("Pod0")).withPrimaryRateLoad(1000.0) | Map(
          "Pod0" -> Seq(
            SubsliceAnnotation("" -- "Akka", 30, stateTransferOpt = None), // Carried forward.
            SubsliceAnnotation("Akka" -- "Allen", 20, stateTransferOpt = None), // Carried forward.
            SubsliceAnnotation("Allen" -- "Balin", 42, stateTransferOpt = None) // Created.
          )
        ),
        (("Balin" -- ∞) @@ (2 ## 47) -> Seq("Pod1", "Pod2")).withPrimaryRateLoad(1000.0) | Map(
          "Pod2" -> Seq(
            SubsliceAnnotation("Balin" -- "Fili", 42, stateTransferOpt = None), // Created.
            SubsliceAnnotation("Fili" -- "Kili", 30, stateTransferOpt = None), // Carried forward.
            SubsliceAnnotation("Kili" -- ∞, 42, stateTransferOpt = None) // Created.
          ),
          "Pod1" -> Seq(
            SubsliceAnnotation("Balin" -- ∞, 42, stateTransferOpt = None) // Created.
          )
        )
      )
    )

    testAsn(
      "Subslice Annotations correctly carried forward with expected new subslice boundaries when " +
      "Slice Assignment's boundaries changed",
      proposal = ProposedAssignment(
        predecessorOpt = Some(
          createAssignment(
            2 ## 42,
            AssignmentConsistencyMode.Affinity,
            ("" -- "Fili") @@ (2 ## 42) -> Seq("Pod0") | Map(
              "Pod0" -> Seq(
                SubsliceAnnotation("Akka" -- "Fili", 20, stateTransferOpt = None)
              )
            ),
            ("Fili" -- ∞) @@ (2 ## 42) -> Seq("Pod1") | Map(
              "Pod1" -> Seq(
                SubsliceAnnotation("Fili" -- "Nori", 30, stateTransferOpt = None)
              )
            )
          )
        ),
        createProposal(
          // The high boundary of the first Slice become Kili from Fili.
          ("" -- "Kili") -> Seq("Pod0"),
          ("Kili" -- ∞) -> Seq("Pod1")
        )
      ),
      generation = 2 ## 47,
      expected = createAssignment(
        2 ## 47,
        // Both Slice Assignments should have new generation, with Subslice Annotations either
        // newly created or carried forward from predecessor.
        AssignmentConsistencyMode.Affinity,
        (("" -- "Kili") @@ (2 ## 47) -> Seq("Pod0")) | Map(
          "Pod0" -> Seq(
            SubsliceAnnotation("" -- "Akka", 42, stateTransferOpt = None), // Newly created.
            SubsliceAnnotation("Akka" -- "Fili", 20, stateTransferOpt = None), // Carried forward.
            // Note Pod0 is newly assigned on [Fili, Kili) thus has latest continuous assigned
            // number.
            SubsliceAnnotation("Fili" -- "Kili", 47, Some(Transfer(id = 0, fromResource = "Pod1")))
          )
        ),
        (("Kili" -- ∞) @@ (2 ## 47) -> Seq("Pod1")) | Map(
          "Pod1" -> Seq(
            // Carried forward with subslice updated.
            SubsliceAnnotation("Kili" -- "Nori", 30, stateTransferOpt = None),
            SubsliceAnnotation("Nori" -- ∞, 42, stateTransferOpt = None) // Newly created.
          )
        )
      )
    )

    testAsn(
      "Annotations preserved for assigned resources and removed for unassigned ones",
      proposal = ProposedAssignment(
        predecessorOpt = Some(
          createAssignment(
            2 ## 42,
            AssignmentConsistencyMode.Affinity,
            ("" -- ∞) @@ (2 ## 42) -> Seq("Pod0", "Pod1") | Map(
              "Pod0" -> Seq(SubsliceAnnotation("Fili" -- "Kili", 20, stateTransferOpt = None)),
              "Pod1" -> Seq(SubsliceAnnotation("Fili" -- "Kili", 20, stateTransferOpt = None))
            )
          )
        ),
        createProposal(
          // Pod0 remains assigned, Pod1 unassigned, Pod2 newly assigned.
          ("" -- ∞) -> Seq("Pod0", "Pod2")
        )
      ),
      generation = 2 ## 47,
      expected = createAssignment(
        2 ## 47,
        AssignmentConsistencyMode.Affinity,
        (("" -- ∞) @@ (2 ## 47) -> Seq("Pod0", "Pod2")) | Map(
          "Pod0" -> Seq(
            SubsliceAnnotation("" -- "Fili", 42, stateTransferOpt = None), // Newly created.
            SubsliceAnnotation("Fili" -- "Kili", 20, stateTransferOpt = None), // Preserved.
            SubsliceAnnotation("Kili" -- ∞, 42, stateTransferOpt = None) // Newly created.
          ),
          "Pod2" -> Seq(
            SubsliceAnnotation("" -- ∞, 47, Some(Transfer(id = 0, fromResource = "Pod0")))
          )
          // Pod1 is unassigned. No Subslice Annotations for it.
          // Pod2 is newly assigned. No Subslice Annotations for it.
        )
      )
    )
  }

  test("Continuous generation number randomized") {
    // Test plan: Verify that `ProposedAssignment.commit` creates correct continuous generation
    // numbers in subslice annotations with randomly generated assignments. Do this by first
    // creating a random assignment. Then, in each iteration, create a new assignment with random
    // boundaries, which, with high probability, keeps the same resource as the previous assignment,
    // for a random point in each slice. Track the expected continuous generation number in a
    // MutableSliceMap upon generating each random assignment. Finally, verify correctness of the
    // continuous generation numbers in the resulting assignment by comparing them against the
    // expected MutableSliceMap for each assigned resource.

    val numRuns = 100 // Repeatedly run the test plan 100 times to cover more possible cases.
    val numResources = 20
    val numSlices = 50
    val numAssignmentChanges = 10
    val numMaxReplicas = 5

    val seed: Long = Random.nextLong()
    logger.info(s"Using seed $seed")
    val rng = new Random(seed)

    for (_ <- 0 until numRuns) {
      val resources: IndexedSeq[Squid] =
        (0 until numResources).map((resourceIdx: Int) => createTestSquid(s"pod$resourceIdx"))
      // Mapping Slice Replica (a pair of resource and Slice) to an optional continuous generation
      // number - the expected earliest generation number that the Slice has been continuously
      // assigned to this resource. The Option being empty means the resource is not assigned to the
      // Slice.
      val expectedContGenBySliceReplica: Map[Squid, MutableSliceMap[Option[UnixTimeVersion]]] =
        resources.map { resource: Squid =>
          val mutableSliceMap = new MutableSliceMap[Option[UnixTimeVersion]]
          // Initially, all Slices are unassigned to any resource.
          mutableSliceMap.put(Slice.FULL, value = None)
          resource -> mutableSliceMap
        }.toMap
      // Merge function for each MutableSliceMap in `expectedContGenBySliceReplica`.
      val mergeFn: (Option[UnixTimeVersion], Option[UnixTimeVersion]) => Option[UnixTimeVersion] = {
        case (Some(oldGen: UnixTimeVersion), Some(newGen: UnixTimeVersion)) =>
          // Keep the old generation number as the slice is continuously assigned to the resource.
          Some(oldGen)
        case (None, Some(newGen: UnixTimeVersion)) =>
          // Record the new generation number for newly assigned slice replicas.
          Some(newGen)
        case _ =>
          // Slice replica not assigned.
          None
      }

      var predecessorOpt: Option[Assignment] = None

      for (i: Int <- 0 until numAssignmentChanges) {
        val proposal: SliceMap[ProposedSliceAssignment] = predecessorOpt match {
          case Some(predecessor: Assignment) =>
            createBiasedProposal(numSlices, predecessor, resources, numMaxReplicas, rng)
          case None => createRandomProposal(numSlices, resources, numMaxReplicas, rng)
        }
        val assignment: Assignment =
          ProposedAssignment(predecessorOpt, proposal)
            .commit(
              isFrozen = false,
              AssignmentConsistencyMode.Affinity,
              i * 10 + 1
            )

        // Update `expectedContGenBySliceReplica` for each possible pair of (Resource, Slice) based
        // on the new assignment.
        for (asn: SliceAssignment <- assignment.sliceMap.entries;
          entry <- expectedContGenBySliceReplica) {
          val (resource, mutableSliceMap): (Squid, MutableSliceMap[Option[UnixTimeVersion]]) = entry
          if (asn.resources.contains(resource)) {
            mutableSliceMap.merge(asn.slice, value = Some(asn.generation.number), mergeFn)
          } else {
            mutableSliceMap.merge(asn.slice, value = None, mergeFn)
          }
        }
        predecessorOpt = Some(assignment)
      }

      val finalAssignment: Assignment = predecessorOpt.get

      // Record the actual continuous generation number for each slice replica of the final
      // assignment in MutableSliceMaps for convenient comparison with the expected values.
      //
      // See `expectedContGenBySliceReplica` for how to interpret the MutableSliceMap.
      val actualContGenBySliceReplica: Map[Squid, MutableSliceMap[Option[UnixTimeVersion]]] =
        resources.map { resource: Squid =>
          val mutableSliceMap = new MutableSliceMap[Option[UnixTimeVersion]]
          // Initially, all Slices are unassigned to any resource.
          mutableSliceMap.put(Slice.FULL, value = None)
          resource -> mutableSliceMap
        }.toMap
      for (asn: SliceAssignment <- finalAssignment.sliceAssignments;
        resource: Squid <- asn.resources) {
        actualContGenBySliceReplica(resource).put(asn.slice, Some(asn.generation.number))
        for (subsliceAnnotation: SubsliceAnnotation <- asn.subsliceAnnotationsByResource.getOrElse(
            resource,
            Vector.empty
          )) {
          actualContGenBySliceReplica(resource)
            .put(subsliceAnnotation.subslice, Some(subsliceAnnotation.continuousGenerationNumber))
        }
      }

      // Verify the actual continuous generation numbers against the expected ones.
      for (resource: Squid <- resources) {
        assert(
          actualContGenBySliceReplica(resource).toVector ==
          expectedContGenBySliceReplica(resource).toVector
        )
      }
    }
  }

  test("State transfer preserved after merge") {
    // Test plan: Verify that state transfer annotations are created and carried over as expected
    // with manual assignment changes. These changes include splits, merges, and reassignments
    // within a single assignment change.

    val initialAssignment: Assignment = createAssignment(
      generation = 10,
      AssignmentConsistencyMode.Affinity,
      ("" -- "Balin") @@ 10 -> Seq("pod0"),
      ("Balin" -- "Bofur") @@ 10 -> Seq("pod1"),
      ("Bofur" -- "Fili") @@ 10 -> Seq("pod2"),
      ("Fili" -- "Kili") @@ 10 -> Seq("pod3"),
      ("Kili" -- ∞) @@ 10 -> Seq("pod4")
    )
    // Reassign second and third slice to pod0, and last slice to pod2.
    val proposal1: SliceMap[ProposedSliceAssignment] = createProposal(
      ("" -- "Balin") -> Seq("pod0"),
      ("Balin" -- "Bofur") -> Seq("pod0"),
      ("Bofur" -- "Fili") -> Seq("pod0"),
      ("Fili" -- "Kili") -> Seq("pod3"),
      ("Kili" -- ∞) -> Seq("pod2")
    )
    val assignment1: Assignment =
      ProposedAssignment(predecessorOpt = Some(initialAssignment), proposal1)
        .commit(
          isFrozen = false,
          AssignmentConsistencyMode.Affinity,
          20
        )
    assert(
      assignment1 == createAssignment(
        generation = 20,
        AssignmentConsistencyMode.Affinity,
        ("" -- "Balin") @@ 20 -> Seq("pod0") | Map(
          "pod0" -> Seq(SubsliceAnnotation("" -- "Balin", 10, stateTransferOpt = None))
        ),
        ("Balin" -- "Bofur") @@ 20 -> Seq("pod0") | Map(
          "pod0" -> Seq(
            SubsliceAnnotation("Balin" -- "Bofur", 20, stateTransferOpt = Some(Transfer(0, "pod1")))
          )
        ),
        ("Bofur" -- "Fili") @@ 20 -> Seq("pod0") | Map(
          "pod0" -> Seq(
            SubsliceAnnotation("Bofur" -- "Fili", 20, stateTransferOpt = Some(Transfer(1, "pod2")))
          )
        ),
        ("Fili" -- "Kili") @@ 20 -> Seq("pod3") | Map(
          "pod3" -> Seq(SubsliceAnnotation("Fili" -- "Kili", 10, stateTransferOpt = None))
        ),
        ("Kili" -- ∞) @@ 20 -> Seq("pod2") | Map(
          "pod2" -> Seq(
            SubsliceAnnotation("Kili" -- ∞, 20, stateTransferOpt = Some(Transfer(2, "pod4")))
          )
        )
      )
    )
    // Split third slice, then reassign first three slices to pod2 and also merge them. Reassign
    // second part of split slice to pod3 and merge with next slice.
    val proposal2: SliceMap[ProposedSliceAssignment] = createProposal(
      ("" -- "Dwalin") -> Seq("pod2"),
      ("Dwalin" -- "Kili") -> Seq("pod3"),
      ("Kili" -- ∞) -> Seq("pod2")
    )
    val assignment2: Assignment =
      ProposedAssignment(predecessorOpt = Some(assignment1), proposal2)
        .commit(
          isFrozen = false,
          AssignmentConsistencyMode.Affinity,
          30
        )
    assert(
      assignment2 == createAssignment(
        generation = 30,
        AssignmentConsistencyMode.Affinity,
        ("" -- "Dwalin") @@ 30 -> Seq("pod2") | Map(
          "pod2" -> Seq(
            SubsliceAnnotation("" -- "Balin", 30, stateTransferOpt = Some(Transfer(0, "pod0"))),
            SubsliceAnnotation(
              "Balin" -- "Bofur",
              30,
              stateTransferOpt = Some(Transfer(1, "pod0"))
            ),
            SubsliceAnnotation(
              "Bofur" -- "Dwalin",
              30,
              stateTransferOpt = Some(Transfer(2, "pod0"))
            )
          )
        ),
        ("Dwalin" -- "Kili") @@ 30 -> Seq("pod3") | Map(
          "pod3" -> Seq(
            SubsliceAnnotation(
              "Dwalin" -- "Fili",
              30,
              stateTransferOpt = Some(Transfer(3, "pod0"))
            ),
            SubsliceAnnotation("Fili" -- "Kili", 10, stateTransferOpt = None)
          )
        ),
        ("Kili" -- ∞) @@ 30 -> Seq("pod2") | Map(
          // Transfer id is preserved, it's unique within the transfer generation.
          "pod2" -> Seq(
            SubsliceAnnotation("Kili" -- ∞, 20, stateTransferOpt = Some(Transfer(2, "pod4")))
          )
        )
      )
    )
  }

  test("State transfer information populated even if there are multiple state providers") {
    // Test plan: Verify that `ProposedAssignment.commit` creates state transfer information in
    // subslice annotations for a reassigned Slice with multiple possible state providers, where
    // the commonly assigned resources are preferred to be state provider candidates and the
    // state provider candidates are assigned to the acquirers in a round-robin manner.

    val initialAssignment: Assignment = createAssignment(
      generation = 10,
      AssignmentConsistencyMode.Affinity,
      ("" -- "Fili") @@ 10 -> Seq("pod0", "pod1", "pod2"),
      ("Fili" -- ∞) @@ 10 -> Seq("pod2", "pod3", "pod4")
    )
    val proposal: SliceMap[ProposedSliceAssignment] = createProposal(
      ("" -- "Fili") -> Seq("pod3", "pod4", "pod5", "pod6", "pod7"),
      ("Fili" -- ∞) -> Seq("pod3", "pod4", "pod5", "pod6", "pod7", "pod8")
    )
    val committedAssignment: Assignment =
      ProposedAssignment(predecessorOpt = Some(initialAssignment), proposal)
        .commit(
          isFrozen = false,
          AssignmentConsistencyMode.Affinity,
          20
        )

    assert(committedAssignment.sliceAssignments.size == 2)

    // The first slice assignment: all resources are newly assigned, and they contain state
    // providers from the previously assigned resources in a round-robin order.
    val expectedSliceAssignment0: SliceAssignment =
      ("" -- "Fili") @@ 20 -> Seq("pod3", "pod4", "pod5", "pod6", "pod7") | Map(
        "pod3" -> Seq(
          SubsliceAnnotation("" -- "Fili", 20, stateTransferOpt = Some(Transfer(0, "pod0")))
        ),
        "pod4" -> Seq(
          SubsliceAnnotation("" -- "Fili", 20, stateTransferOpt = Some(Transfer(1, "pod1")))
        ),
        "pod5" -> Seq(
          SubsliceAnnotation("" -- "Fili", 20, stateTransferOpt = Some(Transfer(2, "pod2")))
        ),
        "pod6" -> Seq(
          SubsliceAnnotation("" -- "Fili", 20, stateTransferOpt = Some(Transfer(3, "pod0")))
        ),
        "pod7" -> Seq(
          SubsliceAnnotation("" -- "Fili", 20, stateTransferOpt = Some(Transfer(4, "pod1")))
        )
      )
    assert(committedAssignment.sliceAssignments.head == expectedSliceAssignment0)

    // The second slice assignment. pod3 and pod4 are not newly assigned so they don't have state
    // providers. pod3 and pod4 themselves should serve as state provider (for pod5, pod6, pod7,
    // pod8, in a round-robin manner) as they are commonly assigned in both previous and new
    // assignment. pod2 is not assigned in the new assignment and given the presence of pod3 and
    // pod4, pod2 is not considered as a state provider.
    val expectedSliceAssignment1: SliceAssignment =
      ("Fili" -- ∞) @@ 20 -> Seq("pod3", "pod4", "pod5", "pod6", "pod7", "pod8") | Map(
        "pod3" -> Seq(
          SubsliceAnnotation("Fili" -- ∞, 10, stateTransferOpt = None)
        ),
        "pod4" -> Seq(
          SubsliceAnnotation("Fili" -- ∞, 10, stateTransferOpt = None)
        ),
        "pod5" -> Seq(
          SubsliceAnnotation("Fili" -- ∞, 20, stateTransferOpt = Some(Transfer(5, "pod3")))
        ),
        "pod6" -> Seq(
          SubsliceAnnotation("Fili" -- ∞, 20, stateTransferOpt = Some(Transfer(6, "pod4")))
        ),
        "pod7" -> Seq(
          SubsliceAnnotation("Fili" -- ∞, 20, stateTransferOpt = Some(Transfer(7, "pod3")))
        ),
        "pod8" -> Seq(
          SubsliceAnnotation("Fili" -- ∞, 20, stateTransferOpt = Some(Transfer(8, "pod4")))
        )
      )
    assert(committedAssignment.sliceAssignments(1) == expectedSliceAssignment1)
  }

  test("State transfer randomized") {
    // Test plan: Verify that `ProposedAssignment.commit` creates correct
    // `SubsliceAnnotation.stateTransferOpt`, with randomly generated assignments. Do this by first
    // creating a random assignment. Then, in each iteration, create a new assignment with random
    // boundaries. Track the most recent assignment of slices, and verify that any reassignment
    // results in fetching state from the correct previous owner.

    val numResources = 10
    val numSlices = 20
    val numAssignmentChanges = 10
    val numMaxReplicas = 5

    val seed: Long = Random.nextLong()
    logger.info(s"seed=$seed")
    val rng = new Random(seed)

    // All resources that will appear in the random assignments.
    val resources: IndexedSeq[Squid] = (0 until numResources).map(i => createTestSquid(s"pod$i"))
    // For each state acquirer, map (sub)slice to the expected _previous_ resources that the
    // acquirer should acquirer state from (i.e. (Acquirer, SubSlice) -> Provider). The
    // (Acquirer, SubSlice) pairs that have not seen re-assignments (no possible state provider) are
    // holes. A state transfer for a subslice must come from the previous resource indicated in this
    // map.
    val expectedProviderMapByAcquirer: Map[Squid, MutableSliceMap[Squid]] =
      resources.map { resource: Squid =>
        resource -> new MutableSliceMap[Squid]
      }.toMap

    // Initial assignment.
    var assignment: Assignment =
      ProposedAssignment(
        predecessorOpt = None,
        createRandomProposal(numSlices, resources, numMaxReplicas, rng)
      ).commit(
        isFrozen = false,
        AssignmentConsistencyMode.Affinity,
        generation = 1
      )

    for (assignmentChangeIndex: Int <- 1 until numAssignmentChanges) {
      // Create random proposal and commit.
      val previousSliceMap: SliceMap[SliceAssignment] = assignment.sliceMap
      val proposal: SliceMap[ProposedSliceAssignment] =
        createBiasedProposal(numSlices, assignment, resources, numMaxReplicas, rng)
      assignment = ProposedAssignment(predecessorOpt = Some(assignment), proposal)
        .commit(
          isFrozen = false,
          AssignmentConsistencyMode.Affinity,
          generation = assignmentChangeIndex * 5 + 1
        )

      // Based on the diff between the previous and the new assignment, keep track of the expected
      // state provider for each newly assigned resources into `expectedProviderMapByAcquirer`.
      val intersectionEntries: Vector[IntersectionEntry[SliceAssignment, SliceAssignment]] =
        SliceMap.intersectSlices(previousSliceMap, assignment.sliceMap).entries
      for (entry <- intersectionEntries) {
        val previous: SliceAssignment = entry.leftEntry
        val next: SliceAssignment = entry.rightEntry
        // For each newly assigned resource for the subslice, track the expected state provider
        // from the previous resources.
        //
        // The approach to calculate the expected state providers for the state acquirers is
        // logically equivalent with the approach used in ProposedAssignment.commit(): Always use
        // commonly assigned resource as state provider when possible, and match state providers and
        // state acquirers using round-robin. But the test implementation is slightly tweaked from
        // the production code to make the test more convincing.

        val newlyAssignedResources: Set[Squid] = next.resources -- previous.resources
        val commonlyAssignedResources: Set[Squid] = next.resources.intersect(previous.resources)

        val sortedPreviousResources: Vector[Squid] = previous.resources.toVector.sorted
        val sortedNewlyAssignedResources: Vector[Squid] = newlyAssignedResources.toVector.sorted
        var sortedPreviousResourceIterator: Iterator[Squid] = sortedPreviousResources.iterator

        for (newlyAssignedResource: Squid <- sortedNewlyAssignedResources) {
          // For each (sorted) newly assigned resources, find the next eligible (sorted) state
          // provider.
          var nextEligibleProviderOpt: Option[Squid] = None
          while (nextEligibleProviderOpt.isEmpty) {
            if (!sortedPreviousResourceIterator.hasNext) {
              sortedPreviousResourceIterator = sortedPreviousResources.iterator
            }
            val currentProviderCandidate: Squid = sortedPreviousResourceIterator.next()
            // The provider candidate is considered eligible if it's a commonly-assigned resource,
            // or if there are no commonly assigned resources.
            if (commonlyAssignedResources.isEmpty || commonlyAssignedResources.contains(
                currentProviderCandidate
              )) {
              nextEligibleProviderOpt = Some(currentProviderCandidate)
            }
          }
          expectedProviderMapByAcquirer(newlyAssignedResource)
            .put(entry.slice, nextEligibleProviderOpt.get)
        }
      }

      // Collect all state transfer annotations for each state acquirer.
      val actualProviderMapByAcquirer: Map[Squid, MutableSliceMap[Squid]] = resources.map {
        resource: Squid =>
          resource -> new MutableSliceMap[Squid]
      }.toMap
      for (asn: SliceAssignment <- assignment.sliceMap.entries) {
        for (assignedResource: Squid <- asn.resources) {
          for (annotation: SubsliceAnnotation <- asn.subsliceAnnotationsByResource.getOrElse(
              assignedResource,
              Seq.empty
            )) {
            if (annotation.stateTransferOpt.isDefined) {
              actualProviderMapByAcquirer(assignedResource).put(
                annotation.subslice,
                annotation.stateTransferOpt.get.fromResource
              )
            }
          }
        }
      }

      // Verify that the actual state providers exactly equal what we expect based on
      // `expectedProviderMapByAcquirer`.
      for (entry <- actualProviderMapByAcquirer) {
        val (acquirer, actualProviderMap): (Squid, MutableSliceMap[Squid]) = entry

        // Note that `expectedProviderMapByAcquirer` contains historical provider maps for slices
        // that might not be assigned on the corresponding acquirer in the latest assignment, while
        // the actual provider map only contains assigned slices. We intersect the expected provider
        // map with the latest assigned slices for the acquirer, and only take the assigned portion
        // of the provider map for verification.
        val providerSliceMap: SliceMap[GapEntry[(Slice, Squid)]] =
          SliceMap.createFromOrderedDisjointEntries(
            expectedProviderMapByAcquirer(acquirer).toVector,
            getSlice = { case (slice: Slice, _) => slice }
          )
        val acquirerAssignedSlicesMap: SliceMap[GapEntry[SliceAssignment]] =
          SliceMap.createFromOrderedDisjointEntries(
            assignment.getAssignedSliceAssignments(acquirer),
            getSlice = (_: SliceAssignment).slice
          )
        val intersectionsOnProvidersAndAssignedSlices
            : SliceMap[IntersectionEntry[GapEntry[(Slice, Squid)], GapEntry[SliceAssignment]]] =
          SliceMap
            .intersectSlices(providerSliceMap, acquirerAssignedSlicesMap)

        val expectedProviderMapOnAssignedSlices = new MutableSliceMap[Squid]
        for (entry <- intersectionsOnProvidersAndAssignedSlices.entries) {
          val leftEntry: GapEntry[(Slice, Squid)] = entry.leftEntry
          val rightEntry: GapEntry[SliceAssignment] = entry.rightEntry
          // Take the intersection entries that indicate both an expected state provider and a
          // currently assigned subslice.
          if (leftEntry.isDefined && rightEntry.isDefined) {
            val (_, previousResource): (_, Squid) = entry.leftEntry.get
            expectedProviderMapOnAssignedSlices.put(entry.slice, previousResource)
          }
        }

        assert(actualProviderMap.toVector == expectedProviderMapOnAssignedSlices.toVector)
      }
    }
  }
}

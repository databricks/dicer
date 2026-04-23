package com.databricks.dicer.external.javaapi;

import static com.databricks.dicer.external.javaapi.TestSliceUtils.fp;
import static org.assertj.core.api.Assertions.assertThat;

import com.databricks.backend.common.util.CurrentProject;
import com.databricks.backend.common.util.Project;
import com.databricks.testing.DatabricksJavaTest;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class SliceletTest extends DatabricksJavaTest {

  @BeforeAll
  static void BeforeAll() {
    // Initialize the project context. Required for configuration loading.
    CurrentProject.initializeProject(Project.TestProject());
  }

  /** The target used in tests. */
  private static final Target target = new Target("test-target");

  /** The java test environment. */
  private static final DicerTestEnvironment testEnv = DicerTestEnvironment.create();

  @Test
  public void testToString() {
    // Test plan: Create two Slicelets with the same target and verify their string
    // representations are expected and contain distinct identifiers.
    Slicelet slicelet1 = testEnv.createSlicelet(target);
    assertThat(slicelet1.toString()).contains("S0-test-target-localhost");
    Slicelet slicelet2 = testEnv.createSlicelet(target);
    assertThat(slicelet2.toString()).contains("S1-test-target-localhost");

    // Clean up slicelets.
    testEnv.stopSlicelet(slicelet1);
    testEnv.stopSlicelet(slicelet2);
  }

  @Test
  public void testSliceletOperations() {
    // Test plan: Create two slicelets and set explicit assignments. Verify that each slicelet
    // is assigned the exact expected slices and that isAssignedContinuously() returns correct
    // values based on the assignment.

    // Use AtomicInteger for simplicity.
    AtomicInteger listenerCalledNum = new AtomicInteger(0);

    SliceletListener listener =
        new SliceletListener() {
          @Override
          public void onAssignmentUpdated() {
            listenerCalledNum.incrementAndGet();
          }
        };

    // Create and start slicelet1.
    Slicelet slicelet1 = testEnv.createSlicelet(target);
    slicelet1.start(1111, Optional.of(listener));

    // Create and start slicelet2.
    Slicelet slicelet2 = testEnv.createSlicelet(target);
    slicelet2.start(2222, Optional.of(listener));

    // Create specific slice keys for testing.
    SliceKey fili = fp("Fili");
    Slice sliceMinFili = Slice.create(SliceKey.MIN, fili);
    Slice sliceFiliInf = Slice.create(fili, InfinitySliceKey.INSTANCE);

    // Create initial assignment:
    // - ["", "Fili") -> slicelet1
    // - ["Fili", Inf) -> slicelet2
    TestAssignmentBuilder builder1 = new TestAssignmentBuilder();
    builder1.add(sliceMinFili, slicelet1).add(sliceFiliInf, slicelet2);
    testEnv.setAndFreezeAssignment(target, builder1.build());

    // Wait for assignments to propagate and verify exact slices.
    waitUntilAsserted(() -> assertThat(slicelet1.assignedSlices()).containsExactly(sliceMinFili));
    waitUntilAsserted(() -> assertThat(slicelet2.assignedSlices()).containsExactly(sliceFiliInf));

    // Verify isAssignedContinuously for SliceKey.MIN (assigned to slicelet1).
    try (SliceKeyHandle handle = slicelet1.createHandle(SliceKey.MIN)) {
      assertThat(handle.isAssignedContinuously()).isTrue();
      handle.incrementLoadBy(1);
      assertThat(handle.key()).isEqualTo(SliceKey.MIN);
    }
    try (SliceKeyHandle handle = slicelet2.createHandle(SliceKey.MIN)) {
      assertThat(handle.isAssignedContinuously()).isFalse();
    }

    // Verify isAssignedContinuously for fili (assigned to slicelet2).
    try (SliceKeyHandle handle = slicelet1.createHandle(fili)) {
      assertThat(handle.isAssignedContinuously()).isFalse();
    }
    try (SliceKeyHandle handle = slicelet2.createHandle(fili)) {
      assertThat(handle.isAssignedContinuously()).isTrue();
    }

    // Change the assignment, assign the full range to slicelet2:
    // - ["", Inf) -> slicelet2
    TestAssignmentBuilder builder2 = new TestAssignmentBuilder();
    builder2.add(Slice.FULL, slicelet2);
    testEnv.setAndFreezeAssignment(target, builder2.build());

    // Wait for assignments to propagate and verify exact slices.
    waitUntilAsserted(() -> assertThat(slicelet1.assignedSlices()).isEmpty());
    waitUntilAsserted(() -> assertThat(slicelet2.assignedSlices()).containsExactly(Slice.FULL));

    // Verify isAssignedContinuously for SliceKey.MIN (assigned to slicelet2).
    try (SliceKeyHandle handle = slicelet1.createHandle(SliceKey.MIN)) {
      assertThat(handle.isAssignedContinuously()).isFalse();
    }
    try (SliceKeyHandle handle = slicelet2.createHandle(SliceKey.MIN)) {
      assertThat(handle.isAssignedContinuously()).isTrue();
    }

    assertThat(listenerCalledNum.get())
        .as("Listener should have been called at least once")
        .isGreaterThan(0);

    // Clean up slicelets.
    testEnv.stopSlicelet(slicelet1);
    testEnv.stopSlicelet(slicelet2);
  }

  @AfterAll
  static void AfterAll() {
    testEnv.stop();
  }
}

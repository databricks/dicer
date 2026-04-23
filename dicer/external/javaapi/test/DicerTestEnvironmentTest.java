package com.databricks.dicer.external.javaapi;

import static com.databricks.dicer.external.javaapi.TestSliceUtils.fp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.databricks.backend.common.util.CurrentProject;
import com.databricks.backend.common.util.Project;
import com.databricks.testing.DatabricksJavaTest;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class DicerTestEnvironmentTest extends DatabricksJavaTest {

  /** The java test environment. */
  private static final DicerTestEnvironment testEnv = DicerTestEnvironment.create();

  @BeforeAll
  static void beforeAll() {
    // Initialize the project context. Required for configuration loading.
    CurrentProject.initializeProject(Project.TestProject());
  }

  @AfterAll
  static void afterAll() {
    testEnv.stop();
  }

  @Test
  public void testFreezeUnfreezeAssignment() {
    // Test plan: Verify freeze/unfreeze functionality by freezing an empty assignment (all slices
    // assigned to blackhole), using hasReceivedAtLeast to confirm both slicelets and clerk receive
    // the frozen assignment, verifying slicelets have no assigned slices and clerk routes to
    // blackhole, then unfreezing and confirming a new assignment is generated.
    Target target = new Target("test-freeze-unfreeze-target");

    // Create slicelets.
    int port1 = 1111;
    int port2 = 2222;
    Slicelet slicelet1 = testEnv.createSlicelet(target);
    slicelet1.start(port1, Optional.empty());
    Slicelet slicelet2 = testEnv.createSlicelet(target);
    slicelet2.start(port2, Optional.empty());

    // Create a clerk.
    ClerkConfig clerkConfig =
        testEnv.setConnectionConfigForNewClerk(ClerkConfig.builder(), slicelet1).build();
    Clerk<ResourceAddress> clerk =
        Clerk.create(clerkConfig, target, "localhost", java.util.function.Function.identity());

    // Create and freeze assignment:
    // - ["", "Inf") -> blackhole.
    TestAssignmentBuilder builder = new TestAssignmentBuilder();
    TestAssignmentHandle handle = testEnv.setAndFreezeAssignment(target, builder.build());

    // Wait for clients to receive the assignment.
    waitUntilAsserted(() -> assertThat(testEnv.hasReceivedAtLeast(handle, slicelet1)).isTrue());
    waitUntilAsserted(() -> assertThat(testEnv.hasReceivedAtLeast(handle, slicelet2)).isTrue());
    waitUntilAsserted(() -> assertThat(testEnv.hasReceivedAtLeast(handle, clerk)).isTrue());

    // Verify that clients received the assignment.
    assertThat(slicelet1.assignedSlices()).isEmpty();
    assertThat(slicelet2.assignedSlices()).isEmpty();
    Optional<ResourceAddress> resourceAddress1Opt = clerk.getStubForKey(SliceKey.MIN);
    assertThat(resourceAddress1Opt).isPresent();
    String address1 = resourceAddress1Opt.get().toString();
    assertThat(address1.contains(String.valueOf(port1)) || address1.contains(String.valueOf(port2)))
        .isFalse();

    // Unfreeze the assignment.
    CompletionStage<Void> unfreezeResult = testEnv.unfreezeAssignment(target);
    waitUntilAsserted(() -> assertThat(unfreezeResult.toCompletableFuture()).isDone());

    // Verify that assignment changed.
    waitUntilAsserted(
        () -> {
          assertThat(slicelet1.assignedSlices()).isNotEmpty();
          assertThat(slicelet2.assignedSlices()).isNotEmpty();
          Optional<ResourceAddress> resourceAddress2Opt = clerk.getStubForKey(SliceKey.MIN);
          assertThat(resourceAddress2Opt).isPresent();
          String address2 = resourceAddress2Opt.get().toString();
          assertThat(
                  address2.contains(String.valueOf(port1))
                      || address2.contains(String.valueOf(port2)))
              .isTrue();
        });

    // Clean up.
    testEnv.stopSlicelet(slicelet1);
    testEnv.stopSlicelet(slicelet2);
    testEnv.stopClerk(clerk);
  }

  @Test
  public void getTotalAttributedLoad() {
    // Test plan: Verify that getTotalAttributedLoad returns the expected result based on the
    // load reported by slicelets. Initially load is 0, then increases as we increment load
    // on assigned keys.
    Target target = new Target("test-total-attributed-load");

    // Before any watch requests, total load should be 0.
    assertThat(testEnv.getTotalAttributedLoad(target)).isEqualTo(0.0);

    // Create a slicelet.
    Slicelet slicelet = testEnv.createSlicelet(target);
    slicelet.start(1111, Optional.empty());
    TestAssignmentBuilder builder = new TestAssignmentBuilder();
    builder.add(Slice.FULL, slicelet);
    TestAssignmentHandle handle = testEnv.setAndFreezeAssignment(target, builder.build());

    // Wait for the slicelet to receive the assignment.
    waitUntilAsserted(() -> assertThat(testEnv.hasReceivedAtLeast(handle, slicelet)).isTrue());

    // Increment load on an assigned key.
    SliceKey fili = fp("Fili");
    try (SliceKeyHandle keyHandle = slicelet.createHandle(fili)) {
      keyHandle.incrementLoadBy(100);
    }

    // Wait for load to be reflected.
    waitUntilAsserted(
        () -> {
          double load = testEnv.getTotalAttributedLoad(target);
          assertThat(load).isGreaterThan(0.0);
        });

    // Clean up.
    testEnv.stopSlicelet(slicelet);
  }

}

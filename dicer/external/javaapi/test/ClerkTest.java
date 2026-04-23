package com.databricks.dicer.external.javaapi;

import static com.databricks.dicer.external.javaapi.TestSliceUtils.fp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.databricks.backend.common.util.CurrentProject;
import com.databricks.backend.common.util.Project;
import com.databricks.testing.DatabricksJavaTest;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class ClerkTest extends DatabricksJavaTest {

  /** The target used in tests. */
  private static final Target target = new Target("test-target");

  /** The java test environment. */
  private static final DicerTestEnvironment testEnv = DicerTestEnvironment.create();

  @BeforeAll
  static void BeforeAll() {
    // Initialize the project context. Required for configuration loading.
    CurrentProject.initializeProject(Project.TestProject());
  }

  @AfterAll
  static void afterAll() {
    testEnv.stop();
  }

  @Test
  public void testClerkGetStubForKey() {
    // Test plan: Verify that Clerk correctly tracks assignment changes and returns appropriate
    // stubs. First, create an assignment distributing slices across two slicelets and verify
    // stubs point to the correct targets. Then change the assignment and verify stubs reflect the
    // change.
    int selfPort1 = 1111;
    int selfPort2 = 2222;
    Slicelet slicelet1 = testEnv.createSlicelet(target);
    slicelet1.start(selfPort1, Optional.empty());
    Slicelet slicelet2 = testEnv.createSlicelet(target);
    slicelet2.start(selfPort2, Optional.empty());

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

    // Connect to a slicelet.
    ClerkConfig clerkConfig =
        testEnv.setConnectionConfigForNewClerk(ClerkConfig.builder(), slicelet1).build();
    Clerk<ResourceAddress> clerk =
        Clerk.create(clerkConfig, target, "localhost", Function.identity());

    // Verify that the stub is created against the correct slicelet.
    waitUntilAsserted(
        () -> {
          Optional<ResourceAddress> resourceAddress1Opt = clerk.getStubForKey(SliceKey.MIN);
          assertThat(resourceAddress1Opt).isPresent();
          assertThat(resourceAddress1Opt.get().toString().contains(selfPort1 + "")).isTrue();
          Optional<ResourceAddress> resourceAddress2Opt = clerk.getStubForKey(fili);
          assertThat(resourceAddress2Opt).isPresent();
          assertThat(resourceAddress2Opt.get().toString().contains(selfPort2 + "")).isTrue();
        });

    // Change the assignment, assign the full range to slicelet2:
    // - ["", Inf) -> slicelet2
    TestAssignmentBuilder builder2 = new TestAssignmentBuilder();
    builder2.add(Slice.FULL, slicelet2);
    testEnv.setAndFreezeAssignment(target, builder2.build());

    // Verify that the stub is created against the new assigned slicelet.
    waitUntilAsserted(
        () -> {
          Optional<ResourceAddress> resourceAddress1Opt = clerk.getStubForKey(SliceKey.MIN);
          assertThat(resourceAddress1Opt).isPresent();
          assertThat(resourceAddress1Opt.get().toString().contains(selfPort2 + "")).isTrue();
          Optional<ResourceAddress> resourceAddress2Opt = clerk.getStubForKey(fili);
          assertThat(resourceAddress2Opt).isPresent();
          assertThat(resourceAddress2Opt.get().toString().contains(selfPort2 + "")).isTrue();
        });

    testEnv.stopSlicelet(slicelet1);
    testEnv.stopSlicelet(slicelet2);
    testEnv.stopClerk(clerk);
  }

  @Test
  public void testClerkReady() {
    // Test plan: Create a slicelet and clerk, call ready() to get the readiness CompletionStage,
    // and verify it completes without throwing an exception.
    Slicelet slicelet = testEnv.createSlicelet(target);
    slicelet.start(1111, Optional.empty());
    ClerkConfig clerkConfig =
        testEnv.setConnectionConfigForNewClerk(ClerkConfig.builder(), slicelet).build();
    Clerk<ResourceAddress> clerk =
        Clerk.create(clerkConfig, target, "localhost", Function.identity());
    CompletionStage<Void> readiness = clerk.ready();
    // Wait until ready completes without exception.
    assertThatNoException().isThrownBy(() -> readiness.toCompletableFuture().get());
    testEnv.stopSlicelet(slicelet);
    testEnv.stopClerk(clerk);
  }
}

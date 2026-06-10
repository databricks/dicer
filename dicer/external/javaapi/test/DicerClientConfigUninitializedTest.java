package com.databricks.dicer.external.javaapi;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.databricks.testing.DatabricksJavaTest;
import org.junit.jupiter.api.Test;

/**
 * Verifies that config builders fail with a clear error when {@link
 * com.databricks.backend.common.util.CurrentProject} has not been initialized.
 *
 * <p>This test must live in its own Bazel target (separate JVM) so that {@code CurrentProject} is
 * guaranteed to be uninitialized when the test runs.
 */
final class DicerClientConfigUninitializedTest extends DatabricksJavaTest {

  @Test
  void testSliceletConfigFailsIfProjectNotInitialized() {
    // Test plan: Verify that SliceletConfig.builder().build() throws an IllegalStateException with
    // a helpful message when CurrentProject has not been initialized. This guards against silent
    // misconfiguration at startup.
    assertThatThrownBy(() -> SliceletConfig.builder().build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CurrentProject not initialized");
  }

  @Test
  void testClerkConfigFailsIfProjectNotInitialized() {
    // Test plan: Verify that ClerkConfig.builder().build() throws an IllegalStateException with a
    // helpful message when CurrentProject has not been initialized. This ensures Clerk config
    // construction fails fast instead of silently using an invalid project context.
    assertThatThrownBy(() -> ClerkConfig.builder().build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CurrentProject not initialized");
  }
}

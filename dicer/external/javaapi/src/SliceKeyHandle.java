package com.databricks.dicer.external.javaapi;

/**
 * See {@link com.databricks.dicer.external.SliceKeyHandle}.
 *
 * <p>Recommended usage example:
 *
 * <pre>{@code
 * try (SliceKeyHandle handle = slicelet.createHandle(key)) {
 *     if (handle.isAssignedContinuously()) {
 *         handle.incrementLoadBy(5);
 *         // Process request with the key
 *         processRequest(handle, request);
 *     } else {
 *         // Key is no longer assigned to this pod; handle accordingly.
 *     }
 *   } // handle.close() is called automatically
 * }</pre>
 */
public final class SliceKeyHandle implements AutoCloseable {

  /** The underlying Scala `SliceKeyHandle` instance. */
  private final com.databricks.dicer.external.SliceKeyHandle scalaSliceKeyHandle;

  /** Package private constructor to prevent external instantiation. */
  SliceKeyHandle(com.databricks.dicer.external.SliceKeyHandle scalaSliceKeyHandle) {
    this.scalaSliceKeyHandle = scalaSliceKeyHandle;
  }

  /**
   * @see com.databricks.dicer.external.SliceKeyHandle#isAssignedContinuously()
   */
  public boolean isAssignedContinuously() {
    return scalaSliceKeyHandle.isAssignedContinuously();
  }

  /**
   * @see com.databricks.dicer.external.SliceKeyHandle#incrementLoadBy(int)
   */
  public void incrementLoadBy(int value) {
    scalaSliceKeyHandle.incrementLoadBy(value);
  }

  /**
   * @see com.databricks.dicer.external.SliceKeyHandle#key()
   */
  public SliceKey key() {
    return new SliceKey(scalaSliceKeyHandle.key());
  }

  @Override
  public void close() {
    scalaSliceKeyHandle.close();
  }
}

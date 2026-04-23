package com.databricks.dicer.external.javaapi;

/**
 * Publicizes package-private members of the Java API for use in internal code. While this class is
 * present in the public API package, it is hidden via bazel visibility rules.
 */
public class ImplFriend {
  /** Converts a Scala SliceKey to a Java SliceKey. */
  public static SliceKey convertToJavaSliceKey(
      com.databricks.dicer.external.SliceKey scalaSliceKey) {
    return new SliceKey(scalaSliceKey);
  }

  /** Converts a Java SliceKey to a Scala SliceKey. */
  public static com.databricks.dicer.external.SliceKey convertToScalaSliceKey(
      SliceKey javaSliceKey) {
    return javaSliceKey.toScala();
  }

  /** Converts a Scala Clerk to a Java Clerk. */
  public static <Stub> Clerk<Stub> convertToJavaClerk(
      com.databricks.dicer.external.Clerk<Stub> scalaClerk) {
    return new Clerk<>(scalaClerk);
  }

  /** Converts a Java Clerk to a Scala Clerk. */
  public static <Stub> com.databricks.dicer.external.Clerk<Stub> convertToScalaClerk(
      Clerk<Stub> javaClerk) {
    return javaClerk.toScala();
  }

  /** Converts a Scala ResourceAddress to a Java ResourceAddress. */
  public static ResourceAddress convertToJavaResourceAddress(
      com.databricks.dicer.external.ResourceAddress scalaResourceAddress) {
    return new ResourceAddress(scalaResourceAddress);
  }

  /** Converts a Scala Slice to a Java Slice. */
  public static Slice convertToJavaSlice(com.databricks.dicer.external.Slice scalaSlice) {
    return new Slice(scalaSlice);
  }

  /** Converts a Java Slice to a Scala Slice. */
  public static com.databricks.dicer.external.Slice convertToScalaSlice(Slice javaSlice) {
    return javaSlice.toScala();
  }

  /**
   * Allows Java shims outside `com.databricks.dicer.external.javaapi` to access Target's `toScala`
   * method.
   */
  public static com.databricks.dicer.external.Target toScalaTarget(Target target) {
    return target.toScala();
  }
}

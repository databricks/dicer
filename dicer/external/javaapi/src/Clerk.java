package com.databricks.dicer.external.javaapi;

import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import scala.Function1;
import scala.compat.java8.FutureConverters;
import scala.compat.java8.OptionConverters;

/**
 * @see com.databricks.dicer.external.Clerk
 */
public class Clerk<Stub> {

  /** The underlying Scala Clerk instance. */
  private final com.databricks.dicer.external.Clerk<Stub> scalaClerk;

  /** Private constructor. Use {@link #create} instead. */
  private Clerk(
      ClerkConfig config,
      Target target,
      String sliceletHostName,
      Function<ResourceAddress, Stub> stubFactory) {
    // Convert Java Function<ResourceAddress, Stub> to Scala Function1.
    Function1<com.databricks.dicer.external.ResourceAddress, Stub> scalaStubFactory =
        address -> stubFactory.apply(new ResourceAddress(address));
    this.scalaClerk =
        com.databricks.dicer.external.Clerk.create(
            config.toScala(), target.toScala(), sliceletHostName, scalaStubFactory);
  }

  /**
   * REQUIRES: Only one `Clerk` may be created per `target` per process, except in tests.
   *
   * <p>See {@link com.databricks.dicer.external.Clerk#create}
   */
  public static <Stub> Clerk<Stub> create(
      ClerkConfig config,
      Target target,
      String sliceletHostName,
      Function<ResourceAddress, Stub> stubFactory) {
    return new Clerk<>(config, target, sliceletHostName, stubFactory);
  }

  /**
   * @see com.databricks.dicer.external.Clerk#getStubForKey
   */
  public Optional<Stub> getStubForKey(SliceKey key) {
    return OptionConverters.toJava(scalaClerk.getStubForKey(key.toScala()));
  }

  /**
   * @see com.databricks.dicer.external.Clerk#ready
   */
  public CompletionStage<Void> ready() {
    return FutureConverters.toJava(scalaClerk.ready())
        .thenApply(
            // Runs on the same thread. This is lightweight and only converts the BoxedUnit to Void.
            ready -> null);
  }

  /**
   * Package-private constructor that wraps an existing Scala Clerk for internal caching Java API
   * usage.
   */
  Clerk(com.databricks.dicer.external.Clerk<Stub> scalaClerk) {
    this.scalaClerk = scalaClerk;
  }

  /** Package-private accessor for the underlying Scala Clerk. */
  com.databricks.dicer.external.Clerk<Stub> toScala() {
    return scalaClerk;
  }
}

package com.databricks.dicer.external.javaapi;

import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Optional;
import javax.annotation.concurrent.ThreadSafe;
import scala.collection.JavaConverters;
import scala.compat.java8.OptionConverters;

/**
 * @see com.databricks.dicer.external.Slicelet
 */
@ThreadSafe
public final class Slicelet {

  /** The underlying Scala `Slicelet` instance. */
  private final com.databricks.dicer.external.Slicelet scalaSlicelet;

  /**
   * Private constructor that prevents external instantiation.
   *
   * @param config the Slicelet configuration
   * @param target identifies the set of resources sharded by Dicer
   */
  private Slicelet(SliceletConfig config, Target target) {
    com.databricks.dicer.external.Slicelet scalaSlicelet =
        com.databricks.dicer.external.Slicelet.apply(config.toScala(), target.toScala());
    this.scalaSlicelet = scalaSlicelet;
  }

  /**
   * @see com.databricks.dicer.external.Slicelet#apply
   */
  public static Slicelet create(SliceletConfig config, Target target) {
    return new Slicelet(config, target);
  }

  /**
   * Creates a {@link Slicelet} with default configuration.
   *
   * @deprecated Use {@link #create(SliceletConfig, Target)} instead, which allows configuring TLS
   *     options and other settings.
   */
  @Deprecated
  public static Slicelet create(Target target) {
    return create(SliceletConfig.builder().build(), target);
  }

  /**
   * @see com.databricks.dicer.external.Slicelet#start
   */
  public Slicelet start(int selfPort, Optional<SliceletListener> listenerOpt) {
    scalaSlicelet.start(
        selfPort,
        OptionConverters.toScala(listenerOpt.map(listener -> listener::onAssignmentUpdated)));
    return this;
  }

  /**
   * @see com.databricks.dicer.external.Slicelet#createHandle
   */
  public SliceKeyHandle createHandle(SliceKey key) {
    com.databricks.dicer.external.SliceKey scalaKey = key.scalaHighSliceKey().asFinite();
    return new SliceKeyHandle(scalaSlicelet.createHandle(scalaKey));
  }

  /**
   * @see com.databricks.dicer.external.Slicelet#assignedSlices
   */
  public List<Slice> assignedSlices() {
    scala.collection.Seq<com.databricks.dicer.external.Slice> scalaSlices =
        scalaSlicelet.assignedSlices();
    return JavaConverters.asJavaCollection(scalaSlices).stream()
        .map(Slice::new)
        .collect(ImmutableList.toImmutableList());
  }

  @Override
  public String toString() {
    return scalaSlicelet.toString();
  }

  /** Package-private accessor for the underlying Scala Slicelet. */
  com.databricks.dicer.external.Slicelet toScala() {
    return this.scalaSlicelet;
  }
}

package com.databricks.dicer.client.javaapi

import com.databricks.conf.RawConfigSingleton
import com.databricks.backend.common.util.CurrentProject
import com.databricks.conf.Config
import com.databricks.conf.trusted.ProjectConf
import com.databricks.dicer.external.SliceletConf

/**
 * A concrete implementation of [[SliceletConf]].
 *
 * The cake pattern with mixins (used in DB-CONF in Scala) is not supported in Java.
 * This class provides a concrete implementation of [[SliceletConf]] that the Java shim can
 * instantiate and pass to the Scala Slicelet.
 *
 * Intended for Java usage only; do not use directly from Scala code.
 *
 * See [[DicerClientConfImplCommon]] for TLS configuration requirements.
 *
 * @param baseConf base config used to construct this [[SliceletConfImpl]] instance.
 *
 * @throws IllegalStateException if [[CurrentProject]] is not initialized.
 */
final class SliceletConfImpl private (baseConf: Config)
    extends ProjectConf(
      CurrentProject.projectOpt.getOrElse(
        throw new IllegalStateException(
          "CurrentProject not initialized. Ensure DatabricksMain has started or call " +
          "CurrentProject.initializeProject() in tests."
        )
      ),
      baseConf
    )
    with SliceletConf
    with DicerClientConfImplCommon

object SliceletConfImpl {

  /** The singleton production instance of [[SliceletConfImpl]]. */
  val INSTANCE = new SliceletConfImpl(RawConfigSingleton.conf)

  private[dicer] object forTest {

    /** Creates a test instance from the provided base config (not singleton). */
    def create(baseConf: Config): SliceletConfImpl = new SliceletConfImpl(baseConf)
  }
}

package com.databricks.caching.util

/**
 * An object that encapsulates constants and functions for both the config tool and the specific
 * services (Dicer & Softstore) to use.
 */
object SafeConfigUtil {

  /** The common prefix of SAFE flag names for Softstore. */
  val SOFTSTORE_CONFIG_FLAGS_NAME_PREFIX: String = "databricks.softstore.config."

  /** The common prefix of SAFE flag names for Dicer target configs. */
  val DICER_TARGET_CONFIG_FLAGS_NAME_PREFIX: String = "databricks.dicer.assigner.targetConfig."

  /** The name of the SAFE flag used for the Dicer target migration dynamic configs. */
  val DICER_TARGET_MIGRATION_CONFIG_FLAG_NAME: String =
    "databricks.dicer.assigner.targetMigrationConfig"

  /** The relative directory, from the workspace root, where we define the SAFE flags. */
  val SAFE_FLAG_DIR = "feature-flag/configs/caching/"

  val DEV_MODE_SHORT_NAME: String = "dev"

  val STAGING_MODE_SHORT_NAME: String = "staging"

  val PROD_MODE_SHORT_NAME: String = "prod"

  /** Gets the name of the Softstore namespace from the config flag name. */
  def getNamespaceFromConfigFlagName(configFlagName: String): String = {
    getSuffix(configFlagName, SOFTSTORE_CONFIG_FLAGS_NAME_PREFIX)
  }

  /** Gets the name of the Dicer target from the config flag name. */
  def getTargetNameFromConfigFlagName(configFlagName: String): String = {
    getSuffix(configFlagName, DICER_TARGET_CONFIG_FLAGS_NAME_PREFIX)
  }

  /** Stripes flagNamePrefix from configFlagName.  */
  private def getSuffix(configFlagName: String, flagNamePrefix: String): String = {
    require(
      configFlagName.startsWith(flagNamePrefix),
      s"The config flag name $configFlagName does not start with $flagNamePrefix"
    )
    configFlagName.substring(flagNamePrefix.length)
  }
}

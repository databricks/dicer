package com.databricks.api.proto.infra

/**
 * Tells the process which "environment" it is running in. "Environment" is separated based on their
 * use in the software development process. Here the environments  include "dev", "staging", and
 * "prod", following the common practice in the industry.
 */
object Environment {

  /** Kinds of environments. */
  sealed trait Kind

  object Kind {

    /** The environment is unspecified. */
    case object KIND_UNSPECIFIED extends Kind

    /** The environment used for development purpose and testing ad-hoc changes. */
    case object DEV extends Kind

    /**
     * The environment to stage and finally verify a code version before it deploys to the
     * production.
     */
    case object STAGING extends Kind

    /** The production environment facing real customer traffic. */
    case object PROD extends Kind
  }
}

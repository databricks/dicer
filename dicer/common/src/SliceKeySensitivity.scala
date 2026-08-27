package com.databricks.dicer.common

/**
 * A customer's attestation of whether a target's SliceKeys (and the slice ranges derived from them)
 * may contain sensitive data.
 *
 * The attestation governs whether Slices and SliceKeys appear in Central Logfood in
 * prod: only non-sensitive Slices and SliceKeys do.
 */
private[dicer] sealed trait SliceKeySensitivity

private[dicer] object SliceKeySensitivity {

  /** The customer has attested that the target's SliceKeys contain no sensitive data. */
  case object NonSensitive extends SliceKeySensitivity

  /** The customer has attested that the target's SliceKeys may contain sensitive data. */
  case object Sensitive extends SliceKeySensitivity

  /** The customer has not made an explicit attestation. SliceKeys are treated as sensitive. */
  case object Unspecified extends SliceKeySensitivity
}

//! Utilities for deterministic hash-based item sampling.

use farmhash::fingerprint64;
use tonic::Status;

/// The modulus used to map hash values to the range [0, HASH_MODULUS). This serves two purposes:
///   1. It avoids floating point precision issues: operating directly on full 64-bit hash values
///      (e.g. values close to 2^64 from fingerprint64) can compromise calculation precision.
///      Reducing to a small integer range makes the threshold comparison exact.
///   2. It defines the sampling resolution: with HASH_MODULUS = 10^4 = 10000, the threshold
///      comparison `restricted_hash_value < (sample_fraction * HASH_MODULUS) as u64` has exactly
///      4 decimal places of resolution, matching the stated precision limit.
const HASH_MODULUS: u64 = 10000;

/// Returns whether `item` is deterministically sampled at rate `sample_fraction` for
/// `sample_namespace`.
///
/// The sampling is deterministic: the same (`item`, `sample_namespace`) pair always yields the
/// same result, even across different processes and across the Scala and Rust implementations.
/// Different namespaces act as independent salts, enabling independent sampling decisions for the
/// same item across different features.
///
/// NOTE: `sample_fraction` should be at most 4 decimal digits precise (e.g., 0.1234). Precision
/// beyond 4 decimal places is silently ignored.
///
/// * `item` - The string representation of the item to sample.
/// * `sample_namespace` - The sampling namespace; acts as a salt to ensure independence across
///   features/usages.
/// * `sample_fraction` - The fraction of items to sample, must be in [0.0, 1.0].
///
/// Returns `true` if the item is sampled, `false` otherwise.
///
/// Returns [`Status::invalid_argument`] if `sample_fraction` is not in [0.0, 1.0].
pub fn is_sampled(
    item: &str,
    sample_namespace: &str,
    sample_fraction: f64,
) -> Result<bool, Status> {
    if !(0.0..=1.0).contains(&sample_fraction) {
        return Err(Status::invalid_argument(format!(
            "sampleFraction must be in [0.0, 1.0], got {sample_fraction:?}"
        )));
    }
    // Two consecutive string writes feed raw bytes with no delimiter, so different (item,
    // sample_namespace) pairs can produce the same byte stream: ("hello","world") and
    // ("he","lloworld") both yield the bytes [h,e,l,l,o,w,o,r,l,d]. Prefixing the item length as a
    // 4-byte int encodes the boundary explicitly, making those two inputs distinct.
    let item_length: u32 = item.encode_utf16().count() as u32;
    let mut bytes: Vec<u8> = Vec::new();
    bytes.extend_from_slice(&item_length.to_le_bytes());
    bytes.extend_from_slice(item.as_bytes());
    bytes.extend_from_slice(sample_namespace.as_bytes());
    let fingerprint64_hash_value: u64 = fingerprint64(&bytes);
    let restricted_hash_value: u64 = fingerprint64_hash_value % HASH_MODULUS;
    Ok(restricted_hash_value < (sample_fraction * HASH_MODULUS as f64) as u64)
}

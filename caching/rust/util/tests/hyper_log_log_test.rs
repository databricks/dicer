use std::ops::Range;

use prost::Message as _;
use caching_rust_util::hyper_log_log::HyperLogLog;
use caching_util_proto_hyper_log_log_proto::databricks::caching::util::HyperLogLogP;

// Returns a HyperLogLog containing keys created from ints in the given range.
fn hll_of_range(range: Range<usize>) -> HyperLogLog {
    let mut hll = HyperLogLog::new();
    for i in range {
        hll.add(&key_of(i)[..]);
    }
    hll
}

fn key_of(x: usize) -> [u8; 8] {
    (x as u64).to_be_bytes()
}

fn assert_approximate(actual: u64, expected: u64) {
    const THRESHOLD: f64 = 0.18;

    let error = ((expected as f64) - (actual as f64)).abs() / (expected as f64);
    assert!(
        error < THRESHOLD,
        "actual={}, expected={}, error {:.2}% is over threshold {:.2}%",
        actual,
        expected,
        error * 100.0,
        THRESHOLD * 100.0,
    )
}

#[test]
fn empty_zero_estimate() {
    // Test plan: verify that a HyperLogLog with no keys estimates 0.
    let hll = HyperLogLog::new();
    assert_eq!(hll.estimate(), 0);
}

#[test]
fn low_cardinality_estimate() {
    // Test plan: verify that low-cardinality estimates are within an error margin of the true
    // cardinality.
    //
    // Cardinalities under ~5m/2 use the linearcounting so they're measured differently from high
    // cardinalities.
    let mut hll = HyperLogLog::new();

    for i in 0..15000 {
        hll.add(&key_of(i)[..]);
        hll.add(&key_of(i)[..]);

        assert_approximate(hll.estimate(), (i + 1) as u64);
    }
}

#[test]
fn high_cardinality_estimate() {
    // Test plan: verify that high-cardinality estimates are within an error margin of the true
    // cardinality.
    //
    // Cardinalities over ~5m/2 use the hyperloglog's estimate, so they're measured differently from
    // low cardinalities.
    let mut hll = HyperLogLog::new();

    for i in 0..1_000_000 {
        hll.add(&key_of(i)[..]);
        hll.add(&key_of(i)[..]);

        // Only check occasionally to keep the test cheap.
        if i % 1000 == 0 {
            assert_approximate(hll.estimate(), (i + 1) as u64);
        }
    }
}

#[test]
fn merge_non_overlapping() {
    // Test plan: verify that merging non-overlapping sets produces an estimate that's approximately
    // the sum of the two sizes.
    let mut a = hll_of_range(0..1000);
    let b = hll_of_range(1000..2000);

    a.merge_from(&b);

    assert_approximate(a.estimate(), 2000u64)
}

#[test]
fn merge_overlapping() {
    // Test plan: verify that merging overlapping sets does not double-count the shared keys.
    let mut a = hll_of_range(0..1000);
    let b = hll_of_range(500..1500);

    a.merge_from(&b);

    assert_approximate(a.estimate(), 1500);
}

#[test]
fn proto_roundtrip() {
    // Test plan: verify that HyperLogLog can roundtrip to and from proto.
    let a = hll_of_range(50000..52000);
    let a_proto = a.to_proto();
    let b = HyperLogLog::from_proto(&a_proto).unwrap();

    assert_eq!(a.estimate(), b.estimate());
}

#[test]
fn from_proto_compat() {
    // Test plan: verify that fromProto is backwards compatible, continuing to be able to correctly
    // parse an old encoded proto.

    // This is copy-pasted from the equivalent Scala test, see instructions there for generating
    // new ones as the format evolves.

    // Here as a literal to ensure that the library continues to be able to deserialize this format.
    let encoded: Vec<u8> = [
        0x0A, 0x80, 0x02, 0x0B, 0x0F, 0x0D, 0x0D, 0x0C, 0x0D, 0x0B, 0x0E, 0x0C, 0x0B, 0x11, 0x10,
        0x0F, 0x0E, 0x0E, 0x0E, 0x0D, 0x0C, 0x0E, 0x0E, 0x0C, 0x0C, 0x11, 0x0C, 0x0E, 0x0B, 0x0E,
        0x0D, 0x0C, 0x0B, 0x0C, 0x0C, 0x11, 0x0B, 0x10, 0x0C, 0x0C, 0x0D, 0x0D, 0x0E, 0x0F, 0x0D,
        0x0D, 0x0D, 0x0B, 0x0E, 0x0F, 0x0C, 0x0D, 0x0F, 0x0E, 0x0B, 0x10, 0x0C, 0x0C, 0x0E, 0x0C,
        0x0C, 0x0F, 0x0D, 0x0B, 0x0B, 0x0E, 0x0D, 0x0C, 0x0E, 0x0C, 0x0C, 0x0E, 0x0B, 0x0B, 0x0C,
        0x0B, 0x0B, 0x0C, 0x0D, 0x0F, 0x10, 0x11, 0x0E, 0x0C, 0x0E, 0x0F, 0x0D, 0x0C, 0x0D, 0x0C,
        0x0B, 0x10, 0x0E, 0x0B, 0x10, 0x0C, 0x0B, 0x0D, 0x0F, 0x0E, 0x12, 0x0E, 0x0F, 0x0F, 0x0E,
        0x0D, 0x0E, 0x0D, 0x0F, 0x14, 0x0F, 0x0E, 0x0F, 0x0F, 0x0D, 0x0D, 0x0C, 0x0A, 0x0C, 0x0C,
        0x0C, 0x0C, 0x0C, 0x0D, 0x11, 0x0C, 0x0D, 0x0C, 0x0E, 0x10, 0x0C, 0x0E, 0x0E, 0x0E, 0x0E,
        0x0D, 0x0B, 0x10, 0x0D, 0x0E, 0x0C, 0x11, 0x0A, 0x11, 0x0E, 0x0B, 0x0F, 0x0D, 0x0D, 0x14,
        0x0D, 0x0C, 0x0B, 0x0F, 0x0D, 0x0E, 0x0D, 0x0E, 0x0D, 0x0E, 0x0C, 0x0D, 0x0B, 0x0F, 0x0B,
        0x0D, 0x0F, 0x0D, 0x0C, 0x0E, 0x0D, 0x0C, 0x0B, 0x0D, 0x0A, 0x0D, 0x0B, 0x0E, 0x0C, 0x0E,
        0x10, 0x10, 0x0C, 0x0D, 0x0E, 0x10, 0x0C, 0x0E, 0x0C, 0x0D, 0x11, 0x0F, 0x0E, 0x0B, 0x0F,
        0x0D, 0x0E, 0x0C, 0x16, 0x0E, 0x0D, 0x0C, 0x14, 0x0B, 0x0D, 0x0C, 0x0D, 0x0D, 0x0C, 0x0D,
        0x0E, 0x0E, 0x0E, 0x0E, 0x0E, 0x0F, 0x0F, 0x10, 0x0E, 0x0B, 0x0D, 0x0D, 0x0C, 0x0B, 0x0D,
        0x0C, 0x0D, 0x0F, 0x0D, 0x0E, 0x0C, 0x0B, 0x12, 0x0D, 0x14, 0x0D, 0x0D, 0x0E, 0x0D, 0x0D,
        0x0B, 0x0E, 0x0C, 0x0D, 0x0D, 0x0E, 0x0E, 0x0C, 0x0E, 0x11, 0x0C, 0x0C, 0x0B, 0x0D, 0x0C,
        0x0C, 0x0F, 0x12, 0x0D,
    ]
    .to_vec();

    let pb = HyperLogLogP::decode(&encoded[..]).unwrap();
    let b = HyperLogLog::from_proto(&pb).unwrap();

    assert_eq!(b.estimate(), 1089507);
}

#[test]
fn reject_invalid_proto() {
    // Test plan: verify that HyperLogLog::try_from(&HyperLogLogP) rejects malformed protos.

    fn inner(registers: Option<Vec<u8>>) {
        assert!(HyperLogLog::from_proto(&HyperLogLogP { inner: registers }).is_err());
    }

    // missing registers
    inner(None);
    // too few registers
    inner(Some([0u8; 255].to_vec()));
    // too many registers
    inner(Some([0u8; 257].to_vec()));
    // large register value
    inner({
        let mut registers = [0u8; 256];
        registers[0] = 64 - 8 + 2;
        Some(registers.to_vec())
    });
}

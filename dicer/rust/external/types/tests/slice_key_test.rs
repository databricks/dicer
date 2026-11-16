use caching_rust_util_testing::test_utils::check_rel_ops;
use dicer_rust_external_types::{
    HighSliceKey,
    HighSliceKeyRef,
    NextFingerprint,
    SliceKey,
    SliceKeyBuilder,
    SliceKeyError,
    SliceKeySegmentedFingerprintBuilder,
};
use dicer_rust_testing::data_helpers::check_equality;
use dicer_rust_testing::test_data::SLICE_KEY_TEST_DATA;
use dicer_rust_testing::test_slice_utils::{
    high_slice_key_from_proto,
    identity_bytes,
    slice_key_from_proto,
    successor,
};

/// Returns a copy of the ordered HighSliceKey values from shared test data.
fn ordered_high_slice_keys() -> Vec<HighSliceKey> {
    SLICE_KEY_TEST_DATA
        .ordered_high_slice_keys
        .iter()
        .map(high_slice_key_from_proto)
        .collect()
}

#[test]
fn test_ordering() {
    // Test plan: Perform generic tests of ordering operators given a manually constructed sequence
    // of SliceKey values in the expected sort order from the shared test data.
    let ordered_slice_keys: Vec<HighSliceKey> = ordered_high_slice_keys();
    check_rel_ops(&ordered_slice_keys);
}

#[test]
fn test_equality() {
    // Test plan: Perform generic tests of equality and hash-code logic given a manually constructed
    // set of equality groups, and using our sample ordered slice keys.

    // Create equality groups from the ordered slice keys.
    let ordered_slice_keys: Vec<HighSliceKey> = ordered_high_slice_keys();
    let equality_groups: Vec<Vec<HighSliceKey>> = ordered_slice_keys
        .into_iter()
        .map(|key: HighSliceKey| {
            // Create three references: two clones and the original key
            vec![key.clone(), key.clone(), key]
        })
        .collect();

    check_equality(&equality_groups);
}

#[test]
fn test_slice_key_debug() {
    // Test plan: format! SliceKey values from shared proto test data.
    // Verify that the string representations match the expected values
    // specified in the test data.

    for test_case in &SLICE_KEY_TEST_DATA.to_string_test_cases {
        let slice_key_proto = test_case.slice_key.as_ref().unwrap();
        let slice_key: SliceKey = slice_key_from_proto(slice_key_proto);
        let expected_string: &str = test_case.expected_string.as_ref().unwrap();

        if test_case.is_successor() {
            // Test the successor representation (append a null byte).
            let successor_key: SliceKey = successor(&slice_key);
            assert_eq!(
                format!("{:?}", successor_key),
                expected_string,
                "Successor of slice key should match expected string"
            );
        } else {
            // Test the regular representation
            assert_eq!(
                format!("{:?}", slice_key),
                expected_string,
                "Slice key should match expected string"
            );
        }
    }
}

#[test]
fn test_min() {
    // Test plan: Verify that the minimum key is the empty key.
    assert_eq!(SliceKey::MIN, identity_bytes(b""));
}

#[test]
#[allow(deprecated)]
fn test_farmhash_fingerprint64() {
    // Test plan: Verify that the farmhash fingerprint64 function produces the expected results
    // for known inputs (testing deprecated API for backward compatibility).

    // Test "Hello" -> 0x87206b321e7cc8d5
    let hello_key: SliceKey = SliceKey::new_farmhash_fingerprint64(b"Hello");
    assert_eq!(format!("{:?}", hello_key), "0x87206b321e7cc8d5");

    // Test "Foo" -> 0x19f3e313e51cd78a
    let foo_key: SliceKey = SliceKey::new_farmhash_fingerprint64(b"Foo");
    assert_eq!(format!("{:?}", foo_key), "0x19f3e313e51cd78a");

    // Test "Fili" -> 0x590de492bef412fc
    let fili_key: SliceKey = SliceKey::new_farmhash_fingerprint64(b"Fili");
    assert_eq!(format!("{:?}", fili_key), "0x590de492bef412fc");
}

#[test]
fn test_high_slice_key_as_ref() {
    // Test plan: Verify that `as_ref` returns an equivalent high slice key ref.
    let key: SliceKey = identity_bytes(&[0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF]);
    assert_eq!(HighSliceKey::Finite(key.clone()).as_ref(), HighSliceKeyRef::Finite(&key));
    assert_eq!(HighSliceKey::Infinity.as_ref(), HighSliceKeyRef::Infinity);
}

#[test]
fn test_high_slice_key_ref_to_owned() {
    // Test plan: Verify that `to_owned` returns an equivalent high slice key.
    let key: SliceKey = identity_bytes(&[0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF]);
    assert_eq!(HighSliceKeyRef::Finite(&key).to_owned(), HighSliceKey::Finite(key));
    assert_eq!(HighSliceKeyRef::Infinity.to_owned(), HighSliceKey::Infinity);
}

#[test]
fn test_high_slice_key_cross_type_equality() {
    // Test plan: Verify that comparison between `HighSliceKey` and `HighSliceKeyRef`
    // yields the expected results.
    let ordered_high_slice_keys: Vec<HighSliceKey> = ordered_high_slice_keys();
    for (i, key1) in ordered_high_slice_keys.iter().enumerate() {
        for (j, key2) in ordered_high_slice_keys.iter().enumerate() {
            assert_eq!(*key1 == key2.as_ref(), i == j);
            assert_eq!(key1.as_ref() == *key2, i == j);
        }
    }
}

#[test]
fn test_fingerprint_builder() {
    // Test plan: Verify that the farmhash fingerprint64 builder produces the expected results
    // using test cases from shared proto test data.

    use dicer_rust_testing::test_data::slice_key_test_data_p::farmhash_builder_test_case_p::builder_operation_p::Operation;

    for test_case in &SLICE_KEY_TEST_DATA.farmhash_builder_test_cases {
        let description: &str = test_case.description.as_ref().unwrap();
        let expected_raw_bytes: Vec<u8> = test_case
            .expected_raw_bytes
            .iter()
            .map(|&b| b as u8)
            .collect();

        // Build the key by applying operations
        let mut builder = SliceKey::fingerprint_builder();
        for op in &test_case.operations {
            match &op.operation {
                Some(Operation::PutLong(value)) => {
                    builder = builder.put_long(*value);
                }
                Some(Operation::PutString(value)) => {
                    builder = builder.put_string(value);
                }
                Some(Operation::PutBytes(value)) => {
                    builder = builder.put_bytes(value);
                }
                None => {}
            }
        }
        let key: SliceKey = builder.build();

        assert_eq!(
            key.to_raw_bytes(),
            expected_raw_bytes.as_slice(),
            "Builder test case '{}' should produce expected bytes",
            description
        );
    }

    // Additional verification: empty builder should not equal MIN
    let empty_key: SliceKey = SliceKey::fingerprint_builder().build();
    assert_ne!(empty_key, SliceKey::MIN);
}

#[test]
fn test_segment_builder_multiple_segments() {
    // Test plan: Verify that a SliceKeySegmentedFingerprintBuilder with `next_fingerprint` calls
    // produces the concatenation of the fingerprints of each segment, in order. Do this by building
    // a three-segment key and comparing it against the concatenated raw bytes of single-segment
    // keys built from the same key parts.
    let segment1: SliceKey = SliceKey::fingerprint_builder()
        .put_bytes(b"Balin")
        .put_long(42)
        .build();
    let segment2: SliceKey = SliceKey::fingerprint_builder().put_string("Dwalin").build();
    let segment3: SliceKey = SliceKey::fingerprint_builder()
        .put_long(-7)
        .put_string("Fili")
        .build();

    let key: SliceKey = SliceKey::fingerprint_builder()
        .put_bytes(b"Balin")
        .put_long(42)
        .next_fingerprint()
        .put_string("Dwalin")
        .next_fingerprint()
        .put_long(-7)
        .put_string("Fili")
        .build();

    let expected_raw_bytes: Vec<u8> = [segment1, segment2, segment3]
        .iter()
        .flat_map(|segment: &SliceKey| segment.to_raw_bytes().to_vec())
        .collect();
    assert_eq!(key.to_raw_bytes(), expected_raw_bytes.as_slice());
    assert_eq!(key.to_raw_bytes().len(), 24);
}

#[test]
fn test_segment_builder_segment_boundaries_affect_key() {
    // Test plan: Verify that the same key parts split differently across segments produce different
    // SliceKeys. Do this by building keys from an identical sequence of key parts with boundaries
    // in different positions, and verifying that the resulting SliceKeys are different.
    let unsplit: SliceKey = SliceKey::fingerprint_builder()
        .put_string("Kili")
        .put_string("Oin")
        .build();
    let split_after_kili: SliceKey = SliceKey::fingerprint_builder()
        .put_string("Kili")
        .next_fingerprint()
        .put_string("Oin")
        .build();
    let split_after_ki: SliceKey = SliceKey::fingerprint_builder()
        .put_string("Ki")
        .next_fingerprint()
        .put_string("liOin")
        .build();

    assert_ne!(unsplit, split_after_kili);
    assert_ne!(split_after_kili, split_after_ki);
}

#[test]
fn test_segment_builder_empty_segments() {
    // Test plan: Verify that `next_fingerprint` closes the current segment and appends a new
    // fingerprint value to the SliceKey even if no bytes were put for the current segment.
    let test_key: SliceKey = SliceKey::fingerprint_builder()
        .put_string("Gloin")
        .next_fingerprint()
        // Closes an empty segment.
        .next_fingerprint()
        .put_string("Gloin")
        .build();

    let key_from_empty: SliceKey = SliceKey::fingerprint_builder().build();
    let key_from_gloin: SliceKey = SliceKey::fingerprint_builder().put_string("Gloin").build();
    let expected_raw_bytes: Vec<u8> = [
        key_from_gloin.to_raw_bytes(),
        key_from_empty.to_raw_bytes(),
        key_from_gloin.to_raw_bytes(),
    ]
    .concat();
    assert_eq!(test_key.to_raw_bytes(), expected_raw_bytes.as_slice());
}

#[test]
fn test_segment_builder_builds_exactly_n_segments() {
    // Test plan: Verify that a SliceKeySegmentedFingerprintBuilder<N> builds a SliceKey with
    // exactly N 8-byte segments, including a trailing empty segment, up to the maximum of 8
    // segments. Do this by annotating the builder type after each call and checking the built key's
    // length and segments, and by building a key with 8 segments and checking that it is 64 bytes
    // long.

    // Verify N == 2.
    let builder: SliceKeySegmentedFingerprintBuilder<1> =
        SliceKey::fingerprint_builder().put_string("Nori");
    let builder: SliceKeySegmentedFingerprintBuilder<2> = builder.next_fingerprint();
    let key: SliceKey = builder.build();
    // The trailing empty segment is included as the fingerprint of the empty input.
    let expected_raw_bytes: Vec<u8> = [
        SliceKey::fingerprint_builder()
            .put_string("Nori")
            .build()
            .to_raw_bytes(),
        SliceKey::fingerprint_builder().build().to_raw_bytes(),
    ]
    .concat();
    assert_eq!(key.to_raw_bytes(), expected_raw_bytes.as_slice());

    // Verify N == 3.
    let builder: SliceKeySegmentedFingerprintBuilder<3> = SliceKey::fingerprint_builder()
        .put_long(1)
        .next_fingerprint()
        .next_fingerprint();
    assert_eq!(builder.build().to_raw_bytes().len(), 3 * 8);

    // Verify N == 8.
    let builder: SliceKeySegmentedFingerprintBuilder<8> = SliceKey::fingerprint_builder()
        .next_fingerprint()
        .next_fingerprint()
        .next_fingerprint()
        .next_fingerprint()
        .next_fingerprint()
        .next_fingerprint()
        // Maximum number of segments. Calling this one more time is a compile error.
        .next_fingerprint();
    assert_eq!(builder.build().to_raw_bytes().len(), 64);
}

#[test]
#[allow(deprecated)]
fn test_farmhash_builder_vs_deprecated() {
    // Test plan: Verify that the builder produces the same result as the deprecated method for
    // single-bytes inputs using test cases from shared proto test data.

    for test_case in &SLICE_KEY_TEST_DATA.farmhash_builder_vs_deprecated_test_cases {
        let description: &str = test_case.description.as_ref().unwrap();
        let input_bytes: Vec<u8> = test_case.input_bytes.iter().map(|&b| b as u8).collect();

        // Verify: builder and deprecated method produce same result.
        let key_from_deprecated: SliceKey = SliceKey::new_farmhash_fingerprint64(&input_bytes);
        let key_from_builder: SliceKey = SliceKey::fingerprint_builder()
            .put_bytes(&input_bytes)
            .build();

        assert_eq!(
            key_from_builder, key_from_deprecated,
            "Builder test case '{}' should produce same result as deprecated method",
            description
        );

        // Verify: the raw bytes match expected.
        let expected_raw_bytes: Vec<u8> = test_case
            .expected_raw_bytes
            .iter()
            .map(|&b| b as u8)
            .collect();
        assert_eq!(
            key_from_builder.to_raw_bytes(),
            expected_raw_bytes.as_slice(),
            "Builder test case '{}' should produce expected raw bytes",
            description
        );
    }
}

#[test]
fn test_from_trusted_fingerprint() {
    // Test plan: Verify that from_trusted_fingerprint correctly creates SliceKeys for valid
    // inputs (bytes <= 32) and round-trips correctly, using ordered slice keys from shared test data.
    for high_slice_key in &ordered_high_slice_keys() {
        if let HighSliceKey::Finite(original) = high_slice_key {
            let bytes: &[u8] = original.to_raw_bytes();
            if bytes.len() <= 32 {
                let roundtrip: SliceKey =
                    SliceKey::from_trusted_fingerprint(bytes).expect("valid bytes");
                assert_eq!(&roundtrip, original, "Round trip should succeed for {:?}", original);
                assert_eq!(
                    roundtrip.to_raw_bytes(),
                    bytes,
                    "Bytes should match for {:?}",
                    original
                );
            }
        }
    }
}

#[test]
fn test_from_trusted_fingerprint_validation() {
    // Test plan: Verify that from_trusted_fingerprint correctly rejects invalid inputs (bytes > 32)
    // using test cases from shared proto test data.
    for test_case in &SLICE_KEY_TEST_DATA.from_trusted_fingerprint_error_test_cases {
        let raw_bytes: Vec<u8> = test_case.raw_bytes.iter().map(|&b| b as u8).collect();
        let expected_error: &str = test_case
            .expected_error
            .as_deref()
            .expect("test case must set expected_error");

        let result: Result<SliceKey, SliceKeyError> =
            SliceKey::from_trusted_fingerprint(&raw_bytes);
        assert!(result.is_err(), "Should reject {} bytes", raw_bytes.len());
        let err: SliceKeyError = result.unwrap_err();
        assert!(
            err.to_string().contains(expected_error),
            "Error message should contain '{}'",
            expected_error
        );
    }
}

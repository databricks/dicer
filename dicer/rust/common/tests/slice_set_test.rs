use dicer_rust_common::{slice_from_proto, SliceSet};
use dicer_rust_external_types::Slice;
use dicer_rust_testing::test_data::SLICE_SET_TEST_DATA;
#[test]
fn test_canonicalization() {
    // Test plan: create `SliceSet`s with overlapping, disordered, contiguous, and repeating input
    // Slices. Verify that the constructed SliceSet has the expected "canonical" Slices (ordered and
    // coalesced).
    for test_case in &SLICE_SET_TEST_DATA.canonicalization_test_cases {
        let input_slices: Vec<Slice> = test_case
            .input_slices
            .iter()
            .map(|s| slice_from_proto(s).unwrap())
            .collect();
        let expected_output_slices: Vec<Slice> = test_case
            .expected_output_slices
            .iter()
            .map(|s| slice_from_proto(s).unwrap())
            .collect();
        let set = SliceSet::from_iter(input_slices);

        assert_eq!(
            set.into_iter().collect::<Vec<_>>(),
            expected_output_slices,
            "Unexpected output for {:?}",
            test_case.description
        );
    }
}

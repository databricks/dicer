use dicer_common_proto_common_proto::dicer::client_request_p::SliceletDataP;
use dicer_rust_common::SliceletData;
use dicer_rust_testing::test_data::SLICELET_DATA_TEST_DATA;
#[test]
fn test_subscriber_data_roundtrip() {
    // Test plan: Ensure that the SliceletData class and the proto roundtrip for a valid object.

    for test_case in &SLICELET_DATA_TEST_DATA.round_tripping_cases {
        // Create a SliceletData object from the test case.
        let data: SliceletData = SliceletData::from_proto(test_case).unwrap();

        // Convert it back to proto and then back to SliceletData.
        let proto: SliceletDataP = data.to_proto();
        let round_tripped_data: SliceletData = SliceletData::from_proto(&proto).unwrap();

        // Assert that the original and round-tripped data are equal.
        assert_eq!(data, round_tripped_data);
    }
}

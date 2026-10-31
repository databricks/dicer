use caching_rust_util::region_uri::RegionUri;
use caching_rust_util_testing::test_data::REGION_URI_TEST_DATA;

#[test]
fn test_from_uri_accepts_valid() {
    // Test plan: Verify that from_uri accepts every region URI in the shared test data (present in
    // the embedded InfraDataModel) and round-trips it back to the same string.
    for uri in &REGION_URI_TEST_DATA.valid_uris {
        let parsed = RegionUri::from_uri(uri.clone())
            .unwrap_or_else(|err| panic!("{uri:?} should parse, but got {err:?}"));
        assert_eq!(parsed.to_string(), *uri);
        assert_eq!(format!("{parsed:?}"), format!("{uri:?}"));
    }
}

#[test]
fn test_from_uri_rejects_invalid() {
    // Test plan: Verify that from_uri rejects every value in the shared invalid set (wrong scheme,
    // malformed, or absent from the embedded InfraDataModel).
    for uri in &REGION_URI_TEST_DATA.invalid_uris {
        RegionUri::from_uri(uri.clone())
            .expect_err(&format!("{uri:?} should be rejected, but parsed"));
    }
}

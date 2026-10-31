use caching_rust_util::rfc1123;
use caching_rust_util_testing::test_data::RFC1123_TEST_DATA;

#[test]
fn test_lengths() {
    // Test plan: Sanity check that the boundary size strings have the expected lengths.
    assert_eq!(
        RFC1123_TEST_DATA
            .longest_valid_string
            .as_ref()
            .unwrap()
            .len(),
        63
    );
    assert_eq!(RFC1123_TEST_DATA.too_long_string.as_ref().unwrap().len(), 64);
}

#[test]
fn test_valid_strings() {
    // Test plan: Verify that the expected strings are considered valid.
    for s in &RFC1123_TEST_DATA.valid_strings {
        assert!(rfc1123::is_valid(s), "{s:?} is not valid, but should be")
    }
}

#[test]
fn test_invalid_strings() {
    // Test plan: Verify that the expected strings are considered invalid.
    for s in &RFC1123_TEST_DATA.invalid_strings {
        assert!(!rfc1123::is_valid(s), "{s:?} is valid, but should not be")
    }
}

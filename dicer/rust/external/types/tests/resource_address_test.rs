use tonic::Code;
use caching_rust_util_testing::test_utils::check_rel_ops;
use dicer_rust_external_types::ResourceAddress;
use dicer_rust_testing::data_helpers::check_equality;
use dicer_rust_testing::test_data::RESOURCE_ADDRESS_TEST_DATA;
#[test]
fn test_ordering() {
    // Test plan: Verify that comparison operations work as expected for ResourceAddress instances
    // using the ordered URIs from shared test data.
    let ordered_resources: Vec<ResourceAddress> = RESOURCE_ADDRESS_TEST_DATA
        .ordered_uris
        .iter()
        .map(|uri: &String| ResourceAddress::new(uri).unwrap())
        .collect();
    assert!(!ordered_resources.is_empty(), "ordered_uris is empty");
    check_rel_ops(&ordered_resources);
}

#[test]
fn test_equality() {
    // Test plan: Define groups of equivalent resources and verify that equality works as expected.
    let equality_groups: Vec<Vec<ResourceAddress>> = RESOURCE_ADDRESS_TEST_DATA
        .equality_groups
        .iter()
        .map(|group| {
            group
                .uris
                .iter()
                .map(|uri: &String| ResourceAddress::new(uri).unwrap())
                .collect()
        })
        .collect();
    assert!(!equality_groups.is_empty(), "equality_groups is empty");
    check_equality(&equality_groups);
}

#[test]
fn test_as_str() {
    // Test plan: Verify that a ResourceAddress displays as exactly the string it was created from,
    // including URIs that `Uri` would display differently (e.g. by adding a "/" path).
    let raw_uris = RESOURCE_ADDRESS_TEST_DATA.ordered_uris.iter().chain(
        RESOURCE_ADDRESS_TEST_DATA
            .equality_groups
            .iter()
            .flat_map(|group| group.uris.iter()),
    );
    for raw_uri in raw_uris {
        let resource: ResourceAddress = ResourceAddress::new(raw_uri).unwrap();
        assert_eq!(resource.as_str(), raw_uri);
        assert_eq!(&resource.to_string(), raw_uri);
    }
}

#[test]
fn test_unsupported_uri() {
    // Test plan: Verify that an error is returned when constructing a ResourceAddress from
    // unsupported URIs.
    assert!(
        !RESOURCE_ADDRESS_TEST_DATA.unsupported_uri_cases.is_empty(),
        "unsupported_uri_cases is empty"
    );
    for unsupported_case in &RESOURCE_ADDRESS_TEST_DATA.unsupported_uri_cases {
        let uri: &str = unsupported_case.uri.as_ref().unwrap();
        let expected_error_message: &str =
            unsupported_case.expected_error_message.as_ref().unwrap();

        let error = ResourceAddress::new(uri).unwrap_err();
        assert_eq!(error.code(), Code::InvalidArgument);
        assert!(
            error.message().contains(expected_error_message),
            "Expected error message containing {:?}, but was {:?}",
            expected_error_message,
            error.message()
        );
    }
}

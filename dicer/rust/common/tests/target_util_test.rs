use dicer_common_proto_common_proto::dicer::target_p::Type;
use dicer_common_proto_common_proto::dicer::TargetP;
use dicer_rust_common::{
    should_serve_request_target,
    target_from_parseable_description,
    target_from_proto,
    target_to_parseable_description,
    target_to_proto,
};
use dicer_rust_external_types::Target;
use dicer_rust_testing::test_data::{
    SHOULD_SERVE_REQUEST_TARGET_TEST_DATA,
    TARGET_TEST_DATA,
};
/// Example of valid Kubernetes cluster URIs.
const VALID_CLUSTER_URIS: [Option<&str>; 3] = [
    Some("kubernetes-cluster:prod/cloud1/public/region1/clustertype2/01"), // homegrown CP cluster
    Some("kubernetes-cluster:prod/cloud1/public/region1/clustertype3/01"), // general cluster
    None,                                                       // empty cluster URI is valid
];

#[test]
fn test_target_from_proto_for_invalid_input() {
    // Test plan: Verify that `target_from_proto` returns an error if the proto is invalid.
    // Possible combinations of invalid inputs:
    // 1. Valid name, but invalid cluster URI.
    // 2. Valid or empty cluster URI, but invalid name.
    // 3. Invalid name and invalid cluster URI.

    // Case 1: Valid name, but invalid cluster URI.
    for name in &TARGET_TEST_DATA.valid_target_names {
        for invalid_cluster_uri_case in &TARGET_TEST_DATA.invalid_uri_test_cases {
            let cluster_uri = invalid_cluster_uri_case.cluster_uri.as_ref().unwrap();
            let proto = TargetP {
                name: Some(name.to_string()),
                kubernetes_cluster_uri: Some(cluster_uri.to_string()),
                target_type: Some(Type::Kubernetes.into()),
                instance_id: None,
            };
            let result = target_from_proto(&proto);
            assert!(result.is_err(), "Expected error for invalid cluster URI: {cluster_uri:?}");
        }
    }

    // Case 2: Valid or empty cluster URI, but invalid name.
    for cluster_uri in &VALID_CLUSTER_URIS {
        for name in &TARGET_TEST_DATA.invalid_target_names {
            let proto = TargetP {
                name: Some(name.to_string()),
                kubernetes_cluster_uri: cluster_uri.map(|uri| uri.to_string()),
                target_type: Some(Type::Kubernetes.into()),
                instance_id: None,
            };
            let result = target_from_proto(&proto);
            assert!(
                result.is_err(),
                "Expected error for invalid name: {name:?}, cluster URI: {cluster_uri:?}"
            );
        }
    }

    // Case 3: Invalid name and invalid cluster URI.
    for invalid_cluster_uri_case in &TARGET_TEST_DATA.invalid_uri_test_cases {
        let cluster_uri = invalid_cluster_uri_case.cluster_uri.as_ref().unwrap();
        for name in &TARGET_TEST_DATA.invalid_target_names {
            let proto = TargetP {
                name: Some(name.to_string()),
                kubernetes_cluster_uri: Some(cluster_uri.to_string()),
                target_type: Some(Type::Kubernetes.into()),
                instance_id: None,
            };
            let result = target_from_proto(&proto);
            assert!(
                result.is_err(),
                "Expected error for invalid name: {name:?}, invalid cluster URI: \
                 {cluster_uri:?}"
            );
        }
    }
}

#[test]
fn test_target_from_proto_to_proto_roundtrip_kubernetes() {
    // Test plan: Verify that `target_from_proto` and `target_to_proto` can roundtrip valid
    // Kubernetes targets.
    for name in &TARGET_TEST_DATA.valid_target_names {
        for cluster_uri in &VALID_CLUSTER_URIS {
            let proto = TargetP {
                name: Some(name.to_string()),
                kubernetes_cluster_uri: cluster_uri.map(|uri| uri.to_string()),
                target_type: Some(Type::Kubernetes.into()),
                instance_id: None,
            };
            let target = target_from_proto(&proto).unwrap();
            let round_tripped_proto = target_to_proto(&target);
            // Verify that the round-tripped proto matches the original proto.
            assert_eq!(round_tripped_proto, proto);
        }
    }
}

#[test]
fn test_target_parseable_description_roundtrip_kubernetes() {
    // Test plan: Verify `target_to_parseable_description` and `target_from_parseable_description`
    // can roundtrip valid Kubernetes targets.
    for name in &TARGET_TEST_DATA.valid_target_names {
        for cluster_uri in &VALID_CLUSTER_URIS {
            let proto: TargetP = TargetP {
                name: Some(name.to_string()),
                kubernetes_cluster_uri: cluster_uri.map(|uri| uri.to_string()),
                target_type: Some(Type::Kubernetes.into()),
                instance_id: None,
            };
            let target: Target = target_from_proto(&proto).unwrap();
            let description: String = target_to_parseable_description(&target);
            let round_tripped_target: Target =
                target_from_parseable_description(&description).unwrap();
            assert_eq!(description, format!("{target:?}"));
            assert_eq!(round_tripped_target, target);
        }
    }
}

#[test]
fn test_target_parseable_description_invalid_cluster_uri() {
    // Test plan: Verify that `target_from_parseable_description` returns an error for invalid
    // Kubernetes target descriptions with invalid cluster URIs.
    for invalid_cluster_uri_case in &TARGET_TEST_DATA.invalid_uri_test_cases {
        let cluster_uri: &str = invalid_cluster_uri_case.cluster_uri.as_ref().unwrap();
        let description: String = format!("{cluster_uri}:valid-name");
        assert!(target_from_parseable_description(&description).is_err());
    }
}

#[test]
fn test_target_from_proto_with_unspecified_type() {
    // Test plan: Verify that `target_from_proto` correctly parses a proto with an
    // unspecified type as a Kubernetes target.
    for name in &TARGET_TEST_DATA.valid_target_names {
        for cluster_uri in &VALID_CLUSTER_URIS {
            let proto = TargetP {
                name: Some(name.to_string()),
                kubernetes_cluster_uri: cluster_uri.map(|uri| uri.to_string()),
                target_type: Some(Type::Unspecified.into()),
                instance_id: None,
            };
            let expected_proto = TargetP {
                name: Some(name.to_string()),
                kubernetes_cluster_uri: cluster_uri.map(|uri| uri.to_string()),
                target_type: Some(Type::Kubernetes.into()),
                instance_id: None,
            };
            let target = target_from_proto(&proto).unwrap();
            let round_tripped_proto = target_to_proto(&target);
            // Verify that the round-tripped proto matches the expected proto with
            // `Type::Kubernetes`.
            assert_eq!(round_tripped_proto, expected_proto);
        }
    }
}

#[test]
fn test_target_from_proto_to_proto_roundtrip_app() {
    // Test plan: Verify that `target_from_proto` and `target_to_proto` can roundtrip valid
    // AppTargets.
    for name in &TARGET_TEST_DATA.valid_target_names {
        let instance_ids = vec!["instance-1", "instance-2", "my-app-123"];
        for instance_id in instance_ids {
            let proto = TargetP {
                name: Some(name.to_string()),
                kubernetes_cluster_uri: None,
                target_type: Some(Type::App.into()),
                instance_id: Some(instance_id.to_string()),
            };
            let target = target_from_proto(&proto).unwrap();
            let round_tripped_proto = target_to_proto(&target);
            // Verify that the round-tripped proto matches the original proto.
            assert_eq!(round_tripped_proto, proto);
        }
    }
}

#[test]
fn test_target_parseable_description_roundtrip_app() {
    // Test plan: Verify `target_to_parseable_description` and `target_from_parseable_description`
    // can roundtrip valid AppTargets.
    for name in &TARGET_TEST_DATA.valid_target_names {
        for instance_id in &TARGET_TEST_DATA.valid_instance_ids {
            let proto: TargetP = TargetP {
                name: Some(name.to_string()),
                kubernetes_cluster_uri: None,
                target_type: Some(Type::App.into()),
                instance_id: Some(instance_id.to_string()),
            };
            let target: Target = target_from_proto(&proto).unwrap();

            let description: String = target_to_parseable_description(&target);
            assert_eq!(description, format!("appTarget:{name}:{instance_id}"));
            assert_eq!(target_from_parseable_description(&description).unwrap(), target);
        }
    }
}

#[test]
fn test_target_parseable_description_invalid_names() {
    // Test plan: Verify that `target_from_parseable_description` returns an error for AppTarget
    // descriptions with invalid names.
    for invalid_name in &TARGET_TEST_DATA.invalid_target_names {
        if !invalid_name.contains(':') {
            // Names containing ':' are skipped because ':' is the parseable-description delimiter.
            let description: String = format!("appTarget:{invalid_name}:a123");
            assert!(target_from_parseable_description(&description).is_err());
        }
    }
}

#[test]
fn test_target_parseable_description_invalid_instance_ids() {
    // Test plan: Verify that `target_from_parseable_description` returns an error for AppTarget
    // descriptions with invalid instance IDs.
    for invalid_instance_id in &TARGET_TEST_DATA.invalid_instance_ids {
        let description: String = format!("appTarget:name:{invalid_instance_id}");
        assert!(target_from_parseable_description(&description).is_err());
    }
}

#[test]
fn test_app_target_requires_instance_id() {
    // Test plan: Verify that AppTarget creation fails if instance_id is missing or empty.
    let proto_missing_instance_id = TargetP {
        name: Some("my-app".to_string()),
        kubernetes_cluster_uri: None,
        target_type: Some(Type::App.into()),
        instance_id: None,
    };
    assert!(target_from_proto(&proto_missing_instance_id).is_err());

    let proto_empty_instance_id = TargetP {
        name: Some("my-app".to_string()),
        kubernetes_cluster_uri: None,
        target_type: Some(Type::App.into()),
        instance_id: Some("".to_string()),
    };
    assert!(target_from_proto(&proto_empty_instance_id).is_err());
}

#[test]
fn test_should_serve_request_target() {
    // Test plan: Verify that should_serve_request_target produces the expected result for all
    // test cases defined in `SHOULD_SERVE_REQUEST_TARGET_TEST_DATA`.

    // Cases that should not be served.
    for test_case in &SHOULD_SERVE_REQUEST_TARGET_TEST_DATA.should_not_serve_test_cases {
        let local_target: Target = target_from_proto(
            test_case
                .local_target
                .as_ref()
                .expect("local target should be defined"),
        )
        .expect("local target should be valid");
        let request_target: Target = target_from_proto(
            test_case
                .request_target
                .as_ref()
                .expect("request target should be defined"),
        )
        .expect("request target should be valid");

        let description: &str = test_case
            .description
            .as_ref()
            .expect("description should be defined")
            .as_str();

        assert!(
            !should_serve_request_target(&local_target, &request_target),
            "Expected not served for test case: {}",
            description
        );
    }

    // Cases that should be served.
    for test_case in &SHOULD_SERVE_REQUEST_TARGET_TEST_DATA.should_serve_test_cases {
        let local_target: Target = target_from_proto(
            test_case
                .local_target
                .as_ref()
                .expect("local target should be defined"),
        )
        .expect("local target should be valid");
        let request_target: Target = target_from_proto(
            test_case
                .request_target
                .as_ref()
                .expect("request target should be defined"),
        )
        .expect("request target should be valid");

        let description: &str = test_case
            .description
            .as_ref()
            .expect("description should be defined")
            .as_str();

        assert!(
            should_serve_request_target(&local_target, &request_target),
            "Expected served for test case: {}",
            description
        );
    }
}

use caching_rust_util::kubernetes_cluster_uri::KubernetesClusterUri;
use caching_rust_util_testing::one_location::EXAMPLE_DATA_PLANE_CLUSTER_URI;
use dicer_rust_external_types::target::{
    get_instance_id,
    get_target_cluster,
    new_app_target,
    new_kubernetes_target,
};
use dicer_rust_external_types::Target;
use dicer_rust_testing::test_data::TARGET_TEST_DATA;

#[test]
fn test_target_new() {
    // Test plan: Verify that we're able to create valid targets through the
    // `Target::new` constructor.
    for name in &TARGET_TEST_DATA.valid_target_names {
        let target = Target::new(name).unwrap();

        assert_eq!(get_target_cluster(&target), None);
        assert_eq!(target.name(), name);
        assert_eq!(&format!("{target:?}"), name);
    }
}

#[test]
fn test_in_explicit_cluster() {
    // Test plan: Verify that we're able to create valid targets through the
    // `new_kubernetes_target` constructor with an explicit cluster URI.
    for name in &TARGET_TEST_DATA.valid_target_names {
        let target = new_kubernetes_target(EXAMPLE_DATA_PLANE_CLUSTER_URI, name).unwrap();

        assert_eq!(
            get_target_cluster(&target),
            Some(
                &KubernetesClusterUri::from_uri(EXAMPLE_DATA_PLANE_CLUSTER_URI.to_string())
                    .unwrap()
            )
        );
        assert_eq!(target.name(), name);
        assert_eq!(format!("{target:?}"), format!("{EXAMPLE_DATA_PLANE_CLUSTER_URI}:{name}"));
    }
}

#[test]
fn test_in_explicit_cluster_with_invalid_cluster_uri() {
    // Test plan: Verify that `new_kubernetes_target` returns an error if the cluster URI is
    // invalid.
    for test_case in &TARGET_TEST_DATA.invalid_uri_test_cases {
        println!("{}", test_case.description.as_ref().unwrap());
        let e =
            new_kubernetes_target(test_case.cluster_uri.as_ref().unwrap(), "softstore-storelet")
                .unwrap_err();

        let expected_error_message = test_case.expected_error_message.as_ref().unwrap();
        assert!(
            e.to_string().contains(expected_error_message),
            "Expected error message containing {expected_error_message:?}, but was {:?}",
            e.to_string()
        );
    }
}

#[test]
fn test_invalid_name() {
    // Test plan: Verify that target creation fails if the target name is invalid.
    for invalid_name in &TARGET_TEST_DATA.invalid_target_names {
        Target::new(invalid_name).unwrap_err();
        new_kubernetes_target(EXAMPLE_DATA_PLANE_CLUSTER_URI, invalid_name).unwrap_err();
    }
}

#[test]
fn test_app_target_creation() {
    // Test plan: Verify that we can create Target instances with AppTarget variant.
    for name in &TARGET_TEST_DATA.valid_target_names {
        for instance_id in &TARGET_TEST_DATA.valid_instance_ids {
            let target = new_app_target(name, instance_id)
                .expect("valid RFC 1123 name and instance ID should create an AppTarget");

            assert_eq!(target.name(), name);
            assert_eq!(get_instance_id(&target), Some(instance_id.as_str()));
            assert_eq!(format!("{target:?}"), format!("appTarget:{name}:{instance_id}"));
        }
    }
}

#[test]
fn test_get_instance_id() {
    // Test plan: Verify that get_instance_id returns None for Kubernetes target variants
    // and Some for AppTarget variants.
    for name in &TARGET_TEST_DATA.valid_target_names {
        let k8s_target = Target::new(name).unwrap();
        assert_eq!(get_instance_id(&k8s_target), None);
    }

    for name in &TARGET_TEST_DATA.valid_target_names {
        for instance_id in &TARGET_TEST_DATA.valid_instance_ids {
            let app_target = new_app_target(name, instance_id)
                .expect("valid RFC 1123 name and instance ID should create an AppTarget");
            assert_eq!(get_instance_id(&app_target), Some(instance_id.as_str()));
        }
    }
}

#[test]
fn test_get_target_cluster_returns_none_for_app_target() {
    // Test plan: Verify that get_target_cluster returns None for AppTarget variants.
    for name in &TARGET_TEST_DATA.valid_target_names {
        for instance_id in &TARGET_TEST_DATA.valid_instance_ids {
            let app_target = new_app_target(name, instance_id)
                .expect("valid RFC 1123 name and instance ID should create an AppTarget");
            assert_eq!(get_target_cluster(&app_target), None);
        }
    }
}

#[test]
fn test_invalid_app_target() {
    // Test plan: Verify that new_app_target rejects names and instance IDs that are
    // not valid RFC 1123 labels. Do this by asserting an error for each invalid name (paired with a
    // valid instance ID) and for each invalid instance ID (paired with a valid name).
    for invalid_name in &TARGET_TEST_DATA.invalid_target_names {
        new_app_target(invalid_name, "instance-1").unwrap_err();
    }
    for invalid_instance_id in &TARGET_TEST_DATA.invalid_instance_ids {
        new_app_target("name", invalid_instance_id).unwrap_err();
    }
}

#[test]
fn test_kubernetes_target_structure() {
    // Test plan: Verify that KubernetesTarget properly stores name and cluster info

    let target = Target::new("test-service").unwrap();
    assert_eq!(target.name(), "test-service");
    assert!(get_target_cluster(&target).is_none());
    assert!(get_instance_id(&target).is_none());

    let target2 =
        new_kubernetes_target("kubernetes-cluster:test-env/cloud2/public/region4/s1/test", "test-service")
            .unwrap();
    assert_eq!(target2.name(), "test-service");
    assert!(get_target_cluster(&target2).is_some());
}

#[test]
fn test_app_target_name_method() {
    // Test plan: Verify that AppTarget::name() returns the correct app name

    let target = new_app_target("my-app", "my-instance").unwrap();

    assert_eq!(target.name(), "my-app");
}

#[test]
fn test_display_canonical_format() {
    // Test plan: Pin the canonical `Display` strings for each Target variant. These strings are
    // used as Prometheus label values and must match the Scala `Target.toString` byte-for-byte
    // (see `target.rs`'s Display impl). Do this by formatting one example of each variant and
    // asserting the exact expected output, and confirming Debug emits the same canonical string.
    let k8s_no_cluster: Target = Target::new("my-target").unwrap();
    assert_eq!(format!("{k8s_no_cluster}"), "my-target");
    assert_eq!(format!("{k8s_no_cluster:?}"), "my-target");

    let k8s_with_cluster: Target =
        new_kubernetes_target(EXAMPLE_DATA_PLANE_CLUSTER_URI, "my-target").unwrap();
    let expected_k8s_with_cluster: String = format!("{EXAMPLE_DATA_PLANE_CLUSTER_URI}:my-target");
    assert_eq!(format!("{k8s_with_cluster}"), expected_k8s_with_cluster);
    assert_eq!(format!("{k8s_with_cluster:?}"), expected_k8s_with_cluster);

    let app_target: Target = new_app_target("my-app", "my-instance").unwrap();
    assert_eq!(format!("{app_target}"), "appTarget:my-app:my-instance");
    assert_eq!(format!("{app_target:?}"), "appTarget:my-app:my-instance");
}

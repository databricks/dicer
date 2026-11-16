use tonic::Code;
use dicer_rust_common::TargetName;
use dicer_rust_external_types::target::{new_app_target, new_kubernetes_target};
use dicer_rust_external_types::Target;
use dicer_rust_testing::test_data::TARGET_TEST_DATA;

/// A Kubernetes cluster URI used to construct cluster-scoped targets.
const CLUSTER_URI: &str = "kubernetes-cluster:prod/cloud1/public/region1/clustertype2/01";

/// Returns targets of every variant with the given `name`.
fn targets_with_name(name: &str) -> Vec<Target> {
    vec![
        Target::new(name).unwrap(),
        new_kubernetes_target(CLUSTER_URI, name).unwrap(),
        new_app_target(name, "a123").unwrap(),
        new_app_target(name, "b456").unwrap(),
    ]
}

#[test]
fn test_valid_names() {
    // Test plan: Verify that `TargetName` accepts every valid target name from the shared
    // textproto. Do this by constructing a `TargetName` from each name and asserting that its
    // `Display` output is the name.
    for name in &TARGET_TEST_DATA.valid_target_names {
        let target_name = TargetName::new(name.as_str())
            .unwrap_or_else(|status| panic!("Expected {name:?} to be valid: {status:?}"));
        assert_eq!(target_name.to_string(), *name);
    }
}

#[test]
fn test_invalid_names() {
    // Test plan: Verify that `TargetName` rejects every invalid target name from the shared
    // textproto. Do this by asserting that constructing a `TargetName` from each name returns an
    // `InvalidArgument` status with the expected message.
    for name in &TARGET_TEST_DATA.invalid_target_names {
        let Err(status) = TargetName::new(name.as_str()) else {
            panic!("Expected {name:?} to be invalid");
        };
        assert_eq!(status.code(), Code::InvalidArgument, "{name:?}");
        assert!(status.message().contains("Target name must match regex"), "{name:?}: {status:?}");
    }
}

#[test]
fn test_for_target() {
    // Test plan: Verify that `for_target` returns the target's name regardless of the target
    // variant or cluster. Do this by calling `for_target` on targets of every variant with the same
    // name and asserting the result equals a `TargetName` constructed directly from that name.
    let expected = TargetName::new("softstore-storelet").unwrap();
    for target in targets_with_name("softstore-storelet") {
        assert_eq!(TargetName::for_target(&target), expected, "{target:?}");
    }
}

#[test]
fn test_matches() {
    // Test plan: Verify that `matches` compares only the target name, ignoring the target
    // variant, cluster, and instance ID. Do this by asserting that a `TargetName` matches targets
    // of every variant with the same name, and matches none with a different name.
    let target_name = TargetName::new("softstore-storelet").unwrap();
    for target in targets_with_name("softstore-storelet") {
        assert!(target_name.matches(&target), "{target_name} should match {target:?}");
    }
    for target in targets_with_name("softstore-storelet-2") {
        assert!(!target_name.matches(&target), "{target_name} should not match {target:?}");
    }
}

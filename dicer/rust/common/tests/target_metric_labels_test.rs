use dicer_rust_common::TargetMetricLabels;
use dicer_rust_external_types::target::new_kubernetes_target;

#[test]
fn test_target_metric_labels() {
    // Test plan: Verify that target metric labels include the target name and Kubernetes cluster,
    // and leave the instance ID empty for Kubernetes targets.
    let target = new_kubernetes_target(
        "kubernetes-cluster:prod/cloud1/public/region1/clustertype2/01",
        "target-foo-test-only",
    )
    .unwrap();
    let labels = TargetMetricLabels::from_target(&target);
    assert_eq!(labels.targetName, "target-foo-test-only");
    assert_eq!(labels.targetCluster, "kubernetes-cluster:prod/cloud1/public/region1/clustertype2/01",);
    assert_eq!(labels.targetInstanceId, "");
}

use http::StatusCode;
use raf_info_service_tracker::ReadinessProbeTracker;

#[test]
fn current_pod_status_is_ok() {
    // Test plan: Verify that the readiness tracker reports the pod as ready. Validate this by
    // checking that `ReadinessProbeTracker::current_pod_status` returns `StatusCode::OK`.
    assert_eq!(ReadinessProbeTracker::current_pod_status(), StatusCode::OK);
}

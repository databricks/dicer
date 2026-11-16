//! Readiness status of the current pod.

use http::StatusCode;

/// Reports the current pod's readiness status.
pub struct ReadinessProbeTracker;

impl ReadinessProbeTracker {
    /// Always returns `StatusCode::OK`, meaning the pod is ready.
    pub fn current_pod_status() -> StatusCode {
        StatusCode::OK
    }
}

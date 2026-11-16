//! OSS placeholder for the internal `slicelet_internal_test.rs`, whose cases cover internal
//! Kubernetes cluster types that OSS does not model.

use common_conf_rust_whereami::WhereAmI;
use dicer_rust_client::slicelet_internal;
use tonic::Status;

#[test]
fn test_verify_slicelet_support_in_current_cluster_accepts_every_cluster() {
    // Test plan: Verify that the OSS cluster check accepts the Slicelet regardless of
    // data plane watch flag and of whether the location is available. Validate this by
    // calling it with both flag values, for both an empty location and an unavailable one.
    let whereami: WhereAmI = WhereAmI::default();
    for watch_from_data_plane in [false, true] {
        assert!(slicelet_internal::verify_slicelet_support_in_current_cluster(
            watch_from_data_plane,
            &Ok(&whereami)
        )
        .is_ok());
        assert!(slicelet_internal::verify_slicelet_support_in_current_cluster(
            watch_from_data_plane,
            &Err(Status::failed_precondition("location unavailable"))
        )
        .is_ok());
    }
}

//! No-op implementation of internal Slicelet utilities.

use common_conf_rust_whereami::WhereAmI;
use tonic::Status;

/// Accepts the Slicelet in every cluster.
pub fn verify_slicelet_support_in_current_cluster(
    _watch_from_data_plane: bool,
    _whereami: &Result<&WhereAmI, Status>,
) -> Result<(), Status> {
    Ok(())
}

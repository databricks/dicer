//! Registers the Dicer client's debug pages, which expose live client state as JSON.

// TODO(<internal bug>): Register the client debug pages.

use std::future::Future;

/// Registers the client's debug pages. No-op as DPage registration requires internal
/// infrastructure.
///
/// # Arguments
/// * `_get_slicez_json_fut` - unused in OSS.
pub(crate) fn setup<F, Fut>(_get_slicez_json_fut: F) -> Result<(), anyhow::Error>
where
    F: Fn() -> Fut + Send + Sync + 'static,
    Fut: Future<Output = String> + Send,
{
    Ok(())
}

/// Sets up the dPage wiring for testing in an isolated registry. No-op as DPage registration
/// requires internal infrastructure.
///
/// # Arguments
/// * `_get_slicez_json_fut` - unused in OSS.
/// * `_test_dpage_namespace` - unused in OSS.
pub fn setup_for_test<F, Fut>(
    _get_slicez_json_fut: F,
    _test_dpage_namespace: &'static str,
) -> Result<(), anyhow::Error>
where
    F: Fn() -> Fut + Send + Sync + 'static,
    Fut: Future<Output = String> + Send,
{
    Ok(())
}

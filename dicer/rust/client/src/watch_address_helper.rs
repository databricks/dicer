//! Framework-specific address classification. Service discovery is not supported in open source.

/// Whether `address` is a service discovery address which has no port. Open-source addresses always
/// require an explicit port.
pub(crate) fn is_service_discovery_address(_address: &str) -> bool {
    false
}

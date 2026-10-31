use std::sync::{LazyLock, Mutex};

use prometheus_client::registry::Registry;

/// A global Prometheus registry for metrics.
///
/// # Example
/// ```
/// // Lock the registry for registration.
/// let mut registry: MutexGuard<'_, Registry> = PROMETHEUS_REGISTRY.lock().unwrap();
/// registry.register(
///     "metric_name",
///     "description of the metric",
///     metric.clone(),
/// );
/// // Scrapes and other registrations block on this mutex, so drop to avoid lock contention.
/// drop(registry);
/// ```
pub static PROMETHEUS_REGISTRY: LazyLock<Mutex<Registry>> =
    LazyLock::new(|| Mutex::new(Registry::default()));

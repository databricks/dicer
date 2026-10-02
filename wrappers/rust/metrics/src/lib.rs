//! OSS metrics facade for Caching and Dicer Rust clients.

use std::sync::{LazyLock, Mutex, MutexGuard};

use prometheus_client::registry::{Metric, Registry};

/// Registry shared by every metric declared through this crate's macros.
static PROMETHEUS_REGISTRY: LazyLock<Mutex<Registry>> =
    LazyLock::new(|| Mutex::new(Registry::default()));

#[doc(hidden)]
/// Implementation details required by macros expanded in dependent crates.
pub mod internal {
    use std::fmt;

    use prometheus_client::encoding::text::encode;
    pub use prometheus_client::metrics::counter::Counter;
    pub use prometheus_client::metrics::family::Family;
    pub use prometheus_client::metrics::gauge::Gauge;
    pub use prometheus_client::metrics::histogram::Histogram;

    use super::{Metric, MutexGuard, Registry, PROMETHEUS_REGISTRY};

    /// Returns the process-wide registry in Prometheus text exposition format for tests.
    pub fn encode_metrics_for_test() -> Result<String, fmt::Error> {
        let mut output = String::new();
        let registry: MutexGuard<'_, Registry> = PROMETHEUS_REGISTRY
            .lock()
            .expect("metrics registry lock is poisoned");
        encode(&mut output, &registry)?;
        Ok(output)
    }

    /// Registers `metric` in the process-wide registry and returns a clone that records into it.
    pub fn register_metric<T, N, H>(metric: T, name: N, help: H) -> T
    where
        T: Metric + Clone,
        N: Into<String>,
        H: Into<String>,
    {
        let mut registry: MutexGuard<'_, Registry> = PROMETHEUS_REGISTRY
            .lock()
            .expect("metrics registry lock is poisoned");
        registry.register(name, help, metric.clone());
        metric
    }

    /// Normalizes a counter name before registering it with `prometheus-client`.
    ///
    /// Counter identifiers may include the conventional `_TOTAL` suffix. Macro expansion
    /// lowercases the identifier, while `prometheus-client` appends `_total` to the encoded sample
    /// name. Removing one trailing `_total` therefore preserves both accepted naming forms without
    /// emitting `_total_total`; names without a trailing `_total` are returned unchanged.
    pub fn strip_total_suffix<N>(counter_name: N) -> String
    where
        N: Into<String>,
    {
        let name: String = counter_name.into();
        name.strip_suffix("_total")
            .map(str::to_owned)
            .unwrap_or(name)
    }
}

/// Defines a static Prometheus counter that is registered on first use.
///
/// Counters use `u64` values by default and optionally support labels or `f64` values. The metric
/// name is the lowercase static name. A trailing `_total` is removed during registration because
/// `prometheus-client` restores it when encoding the counter.
///
/// # Examples
///
/// ```
/// counter!(RPC_REQUESTS_TOTAL, "Total number of RPC requests");
/// RPC_REQUESTS_TOTAL.inc();
/// ```
///
/// ```
/// #[derive(Clone, Debug, Hash, PartialEq, Eq, EncodeLabelSet)]
/// struct RpcLabels { method: String, status: String }
/// counter!(pub RPC_REQUESTS_TOTAL, "Total number of RPC requests", RpcLabels);
/// RPC_REQUESTS_TOTAL
///     .get_or_create_owned(&RpcLabels {
///         method: "get".to_string(),
///         status: "OK".to_string(),
///     })
///     .inc();
/// ```
///
/// ```
/// counter!(CPU_TIME_SECONDS_TOTAL, "Total CPU time in seconds", f64);
/// CPU_TIME_SECONDS_TOTAL.inc_by(1.25);
/// ```
///
/// ```
/// #[derive(Clone, Debug, Hash, PartialEq, Eq, EncodeLabelSet)]
/// struct ServiceLabels { service: String, region: String }
/// counter!(pub REVENUE_TOTAL, "Total revenue in dollars", ServiceLabels, f64);
/// REVENUE_TOTAL
///     .get_or_create_owned(&ServiceLabels {
///         service: "api".to_string(),
///         region: "region1".to_string(),
///     })
///     .inc_by(125.99);
/// ```
#[macro_export]
macro_rules! counter {
    ($visibility:vis $var_name:ident, $metric_help:expr) => {
        $crate::metric!(
            $visibility $var_name,
            $crate::internal::Counter,
            default(),
            $crate::internal::strip_total_suffix(stringify!($var_name).to_lowercase()),
            $metric_help
        );
    };

    ($visibility:vis $var_name:ident, $metric_help:expr, f64) => {
        $crate::metric!(
            $visibility $var_name,
            $crate::internal::Counter::<f64>,
            default(),
            $crate::internal::strip_total_suffix(stringify!($var_name).to_lowercase()),
            $metric_help
        );
    };

    ($visibility:vis $var_name:ident, $metric_help:expr, $label_type:ty) => {
        $crate::metric!(
            $visibility $var_name,
            $crate::internal::Family::<$label_type, $crate::internal::Counter>,
            default(),
            $crate::internal::strip_total_suffix(stringify!($var_name).to_lowercase()),
            $metric_help
        );
    };

    ($visibility:vis $var_name:ident, $metric_help:expr, $label_type:ty, f64) => {
        $crate::metric!(
            $visibility $var_name,
            $crate::internal::Family::<$label_type, $crate::internal::Counter::<f64>>,
            default(),
            $crate::internal::strip_total_suffix(stringify!($var_name).to_lowercase()),
            $metric_help
        );
    };
}

/// Defines a static Prometheus gauge that is registered on first use.
///
/// Gauges use `i64` values by default and optionally support labels or `f64` values. The metric
/// name is the lowercase static name.
///
/// # Examples
///
/// ```
/// gauge!(ACTIVE_CONNECTIONS, "Number of active connections");
/// ACTIVE_CONNECTIONS.set(42);
/// ```
///
/// ```
/// #[derive(Clone, Debug, Hash, PartialEq, Eq, EncodeLabelSet)]
/// struct RpcLabels { method: String, status: String }
/// gauge!(pub ACTIVE_CONNECTIONS, "Number of active connections", RpcLabels);
/// ACTIVE_CONNECTIONS
///     .get_or_create_owned(&RpcLabels {
///         method: "get".to_string(),
///         status: "OK".to_string(),
///     })
///     .set(42);
/// ```
///
/// ```
/// gauge!(CPU_TEMPERATURE, "Current CPU temperature in Celsius", f64);
/// CPU_TEMPERATURE.set(65.5);
/// ```
///
/// ```
/// #[derive(Clone, Debug, Hash, PartialEq, Eq, EncodeLabelSet)]
/// struct CpuLabels { core: String }
/// gauge!(pub CPU_TEMPERATURE, "Current CPU temperature in Celsius", CpuLabels, f64);
/// CPU_TEMPERATURE
///     .get_or_create_owned(&CpuLabels { core: "0".to_string() })
///     .set(65.5);
/// ```
#[macro_export]
macro_rules! gauge {
    ($visibility:vis $var_name:ident, $metric_help:expr) => {
        $crate::metric!(
            $visibility $var_name,
            $crate::internal::Gauge,
            default(),
            stringify!($var_name).to_lowercase(),
            $metric_help
        );
    };

    ($visibility:vis $var_name:ident, $metric_help:expr, f64) => {
        $crate::metric!(
            $visibility $var_name,
            $crate::internal::Gauge::<f64, ::std::sync::atomic::AtomicU64>,
            default(),
            stringify!($var_name).to_lowercase(),
            $metric_help
        );
    };

    ($visibility:vis $var_name:ident, $metric_help:expr, $label_type:ty) => {
        $crate::metric!(
            $visibility $var_name,
            $crate::internal::Family::<$label_type, $crate::internal::Gauge>,
            default(),
            stringify!($var_name).to_lowercase(),
            $metric_help
        );
    };

    ($visibility:vis $var_name:ident, $metric_help:expr, $label_type:ty, f64) => {
        $crate::metric!(
            $visibility $var_name,
            $crate::internal::Family::<
                $label_type,
                $crate::internal::Gauge::<f64, ::std::sync::atomic::AtomicU64>,
            >,
            default(),
            stringify!($var_name).to_lowercase(),
            $metric_help
        );
    };
}

/// Defines a static Prometheus histogram that is registered on first use.
///
/// The metric name is the lowercase static name, and `$buckets` supplies its upper bounds.
/// Supplying a label type creates a family keyed by that label set.
///
/// # Examples
///
/// ```
/// histogram!(
///     REQUEST_LATENCY,
///     "Latency of requests in milliseconds",
///     [10.0, 100.0, 1000.0]
/// );
/// REQUEST_LATENCY.observe(15.0);
/// ```
///
/// ```
/// #[derive(Clone, Debug, Hash, PartialEq, Eq, EncodeLabelSet)]
/// struct RpcLabels { method: String, status: String }
/// histogram!(
///     pub REQUEST_LATENCY,
///     "Latency of requests in milliseconds",
///     [10.0, 100.0, 1000.0],
///     RpcLabels
/// );
/// REQUEST_LATENCY
///     .get_or_create_owned(&RpcLabels {
///         method: "get".to_string(),
///         status: "OK".to_string(),
///     })
///     .observe(15.0);
/// ```
#[macro_export]
macro_rules! histogram {
    ($visibility:vis $var_name:ident, $metric_help:expr, $buckets:expr) => {
        $crate::metric!(
            $visibility $var_name,
            $crate::internal::Histogram,
            new($buckets),
            stringify!($var_name).to_lowercase(),
            $metric_help
        );
    };

    ($visibility:vis $var_name:ident, $metric_help:expr, $buckets:expr, $label_type:ty) => {
        $crate::metric!(
            $visibility $var_name,
            $crate::internal::Family::<$label_type, $crate::internal::Histogram>,
            new_with_constructor(|| $crate::internal::Histogram::new($buckets)),
            stringify!($var_name).to_lowercase(),
            $metric_help
        );
    };
}

/// Defines and lazily registers a static Prometheus metric.
///
/// This macro supports the implementation of type-specific [`counter!`], [`gauge!`], and
/// [`histogram!`] macros and should not be invoked directly.
#[macro_export]
macro_rules! metric {
    ($visibility:vis $var_name:ident, $metric_type:ty, $metric_init:ident ($($args:expr),*), $metric_name:expr, $metric_help:expr) => {
        $visibility static $var_name: ::std::sync::LazyLock<$metric_type> =
            ::std::sync::LazyLock::new(|| {
                $crate::internal::register_metric(
                    <$metric_type>::$metric_init($($args),*),
                    $metric_name,
                    $metric_help,
                )
            });
    };
}

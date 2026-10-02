//! Test helpers for the OSS metrics facade.

use universe_raf_metrics::internal::encode_metrics_for_test as encode_registered_metrics_for_test;

/// Encodes the process-wide registry for assertions in tests.
pub fn encode_metrics_for_test() -> String {
    encode_registered_metrics_for_test().expect("metrics should encode successfully")
}

/// Verifies an encoded counter's metadata, labels, and value.
pub fn verify_encoded_counter(name: &str, help: &str, labels: &[(&str, &str)], value: u64) {
    let base_name: &str = name.strip_suffix("_total").unwrap_or(name);
    let encoded_registry: String = encoded_registry();
    assert_contains(&encoded_registry, &format!("# HELP {base_name} {help}."));
    assert_contains(&encoded_registry, &format!("# TYPE {base_name} counter"));
    assert_contains(
        &encoded_registry,
        &format!("{base_name}_total{} {value}", format_labels(labels)),
    );
}

/// Verifies an encoded gauge's metadata, labels, and value.
pub fn verify_encoded_gauge(name: &str, help: &str, labels: &[(&str, &str)], value: i64) {
    let encoded_registry: String = encoded_registry();
    assert_contains(&encoded_registry, &format!("# HELP {name} {help}."));
    assert_contains(&encoded_registry, &format!("# TYPE {name} gauge"));
    assert_contains(&encoded_registry, &format!("{name}{} {value}", format_labels(labels)));
}

/// Verifies an encoded histogram's metadata, labels, sum, count, and buckets.
pub fn verify_encoded_histogram(
    name: &str,
    help: &str,
    labels: &[(&str, &str)],
    sum: f64,
    count: u64,
    buckets: &[(f64, u64)],
) {
    let encoded_registry: String = encoded_registry();
    assert_contains(&encoded_registry, &format!("# HELP {name} {help}."));
    assert_contains(&encoded_registry, &format!("# TYPE {name} histogram"));
    assert_contains(&encoded_registry, &format!("{name}_sum{} {sum}", format_labels(labels)));
    assert_contains(&encoded_registry, &format!("{name}_count{} {count}", format_labels(labels)));

    for &(upper_bound, bucket_count) in buckets {
        let encoded_bound: String = format_f64(upper_bound);
        let bucket_labels: Vec<(&str, &str)> =
            [&[("le", encoded_bound.as_str())][..], labels].concat();
        assert_contains(
            &encoded_registry,
            &format!("{name}_bucket{} {bucket_count}", format_labels(&bucket_labels)),
        );
    }
}

/// Returns the value of an encoded counter.
pub fn get_counter_value(name: &str, labels: &[(&str, &str)]) -> Option<u64> {
    let encoded_prefix: String = format!("{name}{} ", format_labels(labels));
    encoded_value(&encoded_registry(), &encoded_prefix)
}

/// Returns the count for a histogram bucket with the requested upper bound.
pub fn get_histogram_bucket(name: &str, labels: &[(&str, &str)], upper_bound: f64) -> Option<u64> {
    let encoded_bound: String = format_f64(upper_bound);
    let bucket_labels: Vec<(&str, &str)> = [&[("le", encoded_bound.as_str())][..], labels].concat();
    let encoded_prefix: String = format!("{name}_bucket{} ", format_labels(&bucket_labels));
    encoded_value(&encoded_registry(), &encoded_prefix)
}

/// Returns the sum of the observations recorded in an encoded histogram.
pub fn get_histogram_sum(name: &str, labels: &[(&str, &str)]) -> Option<f64> {
    let encoded_prefix: String = format!("{name}_sum{} ", format_labels(labels));
    encoded_value(&encoded_registry(), &encoded_prefix)
}

/// Returns the number of observations recorded in an encoded histogram.
pub fn get_histogram_count(name: &str, labels: &[(&str, &str)]) -> Option<u64> {
    let encoded_prefix: String = format!("{name}_count{} ", format_labels(labels));
    encoded_value(&encoded_registry(), &encoded_prefix)
}

/// Encodes the process-wide registry or fails the current test.
fn encoded_registry() -> String {
    encode_metrics_for_test()
}

/// Returns the parsed value from the first encoded metric matching `prefix`.
fn encoded_value<T>(encoded_registry: &str, prefix: &str) -> Option<T>
where
    T: std::str::FromStr,
    T::Err: std::fmt::Debug,
{
    encoded_registry.lines().find_map(|line: &str| {
        line.strip_prefix(prefix).map(|value: &str| {
            value
                .trim()
                .parse::<T>()
                .expect("encoded metric value should be a number")
        })
    })
}

/// Fails the current test unless `expected` occurs in `encoded_registry`.
fn assert_contains(encoded_registry: &str, expected: &str) {
    assert!(
        encoded_registry.contains(expected),
        "expected:\n{expected}\nactual:\n{encoded_registry}"
    );
}

/// Formats metric labels in Prometheus text exposition syntax.
fn format_labels(labels: &[(&str, &str)]) -> String {
    if labels.is_empty() {
        String::new()
    } else {
        let encoded_labels: String = labels
            .iter()
            .map(|(key, value)| format!(r#"{key}="{value}""#))
            .collect::<Vec<_>>()
            .join(",");
        format!("{{{encoded_labels}}}")
    }
}

/// Formats a histogram boundary as it appears in Prometheus text exposition output.
fn format_f64(value: f64) -> String {
    match value {
        f64::INFINITY => "+Inf".to_owned(),
        value if value.fract() == 0.0 => format!("{value:.1}"),
        _ => value.to_string(),
    }
}

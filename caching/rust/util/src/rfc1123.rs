use std::sync::LazyLock;

use regex::Regex;

/// The regex for RFC 1123 conformant strings.
pub static REGEX: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^[0-9a-z]([0-9-a-z]{0,61}[0-9a-z])?$").unwrap());

/// Returns whether the given string conforms to RFC 1123.
pub fn is_valid(s: &str) -> bool {
    REGEX.is_match(s)
}

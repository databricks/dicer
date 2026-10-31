use std::sync::LazyLock;

use chrono::{DateTime, NaiveDateTime, TimeZone, Utc};
use regex::Regex;

/// The regex that matches a valid client build version string.
static REGEX: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(\d{4}-\d{2}-\d{2}_\d{2}\.\d{2}\.\d{2})Z_(.*?)_([a-z0-9]{8})_\d+$").unwrap()
});

/// The format of the commit time in the client build version string.
/// Caveat: this format does not include timezone information, as `DateTime::parse_from_str`
/// does not support the "Z" suffix as a timezone. However, since the regex explicitly requires
/// the "Z" suffix, we know the commit time is in UTC.
const COMMIT_UTC_TIME_FORMAT: &str = "%Y-%m-%d_%H.%M.%S";

/// Stores information parsed from a client build version string.
#[derive(Debug, Clone, Default)]
pub struct BuildInfo {
    /// The branch name of the build, or None if the branch name is unknown.
    branch_name_opt: Option<String>,

    /// The commit time of the build as a UTC datetime, or None if the commit time is unknown.
    /// Storing the commit time as a `DateTime<Utc>` allows for easy formatting and avoids parsing
    /// the string representation every time the accessors are called.
    commit_time_opt: Option<DateTime<Utc>>,

    /// The commit hash (first 8 hex digits) of the build, or None if the commit hash is unknown.
    commit_hash_opt: Option<String>,
}

impl BuildInfo {
    /// Parses a client build version string into a `BuildInfo`.
    /// If parsing fails, returns a `BuildInfo` with all fields set to `None`.
    ///
    /// # Arguments
    /// * `branch` - The client build version string.
    pub fn from_client_version(branch: &str) -> Self {
        let captures = match REGEX.captures(branch) {
            Some(c) => c,
            None => return Self::default(),
        };

        let commit_time_opt: Option<DateTime<Utc>> =
            NaiveDateTime::parse_from_str(&captures[1], COMMIT_UTC_TIME_FORMAT)
                .ok()
                .map(|naive_dt| Utc.from_utc_datetime(&naive_dt));

        let branch_name_opt: Option<String> = captures.get(2).map(|m| m.as_str().to_string());
        let commit_hash_opt: Option<String> = captures.get(3).map(|m| m.as_str().to_string());

        Self {
            branch_name_opt,
            commit_time_opt,
            commit_hash_opt,
        }
    }

    /// Returns the branch name, or None if the branch name is unknown
    pub fn get_branch_name_opt(&self) -> Option<&str> {
        self.branch_name_opt.as_deref()
    }

    /// Returns a string suitable for a metric label representing the branch name.
    /// If there is no branch name, this method returns "unknown"
    pub fn get_branch_name_label_value(&self) -> &str {
        self.branch_name_opt.as_deref().unwrap_or("unknown")
    }

    /// Returns a string suitable for a metric label representing the commit time.
    /// If there is no commit time, this method returns "unknown"
    pub fn get_commit_time_label_value(&self) -> String {
        self.commit_time_opt
            .map(|time| time.format(COMMIT_UTC_TIME_FORMAT).to_string() + "Z") // Z suffix for UTC
            .unwrap_or_else(|| "unknown".to_string())
    }

    /// Returns the commit time of the build as the number of milliseconds since the Unix epoch,
    /// or 0 if the commit time is unknown
    pub fn get_commit_time_epoch_millis(&self) -> i64 {
        self.commit_time_opt
            .map(|time| time.timestamp_millis())
            .unwrap_or(0)
    }

    /// Returns a string suitable for a metric label representing the commit hash (first 8 hex
    /// digits). If there is no commit hash, this method returns "unknown"
    pub fn get_commit_hash_label_value(&self) -> &str {
        self.commit_hash_opt.as_deref().unwrap_or("unknown")
    }
}

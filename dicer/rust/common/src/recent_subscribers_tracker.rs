use std::collections::HashMap;
use std::time::{Duration, Instant};

use caching_rust_util::Clock;

/// Subscriber information, for metrics purpose.
#[derive(Clone, Debug, PartialEq, Hash, Eq)]
pub enum SubscriberInfo {
    /// Track the client code version of Clerks.
    ClerkInfo { version: i64 },

    /// Track the client code version and address of Slicelets.
    SliceletInfo { version: i64, address: String },
}

/// The subscriber's debug name, aliased to improve readability.
pub type SubscriberDebugName = String;

/// The subscriber info along with the last time it was active.
#[derive(Debug)]
pub struct LastSeenSubscriberRecord {
    pub subscriber_info: SubscriberInfo,
    pub last_active: Instant,
}

/// Tracks the labels for subscriber metrics.
#[derive(Clone, Debug, Hash, PartialEq, Eq)]
pub struct MetricsKey {
    pub type_label: &'static str, // Either "Clerk" or "Slicelet".
    pub version: i64,             // The client code version.
}

impl MetricsKey {
    pub fn new(subscriber_info: &SubscriberInfo) -> Self {
        match subscriber_info {
            SubscriberInfo::ClerkInfo { version } => MetricsKey {
                type_label: "Clerk",
                version: *version,
            },
            SubscriberInfo::SliceletInfo { version, .. } => MetricsKey {
                type_label: "Slicelet",
                version: *version,
            },
        }
    }
}

/// Tracks recent subscribers for monitoring purposes. Subscribers that haven’t sent any watch
/// requests within `expire_duration` are removed each each time the `get_active_subscribers` are
/// called.
///
/// # Note
///
/// The internal implementation uses a simple hash map. We chose this over a cache for it's simple
/// and easy to reason about. This also means that `get_active_subscribers` can be more expensive,
/// especially when dealing with a very large number of subscribers. This is acceptable here
/// because this method is only called for metrics reporting, which is very infrequent (e.g. once
/// every 10 minutes), and the number of active subscribers is expected to be within a reasonable
/// limit (e.g. less than 10,000). In contrast, [`record_watch_request`] is very cheap, it only
/// performs a hash map insertion.
///
/// Other potential implementations include using
///  - DashMap, a concurrent hash map that may offer better read performance.
///  - Using a third-party cache crate with TTL support, such as moka or mini-moka so that eviction
///    is more efficient.
///  - Implement our own cache with TTL (if in the future we implement a cache for Linkstore, we
///    can use the same underlying cache here).
pub struct RecentSubscribersTracker {
    /// Clock for time-related operations.
    clock: Clock,

    /// A map from subscriber debug name to its info and last seen time.
    subscribers: HashMap<SubscriberDebugName, LastSeenSubscriberRecord>,

    /// Duration after which a subscriber is considered expired if no watch requests are received.
    expire_duration: Duration,
}

impl RecentSubscribersTracker {
    pub fn new(clock: Clock, expire_duration: Duration) -> Self {
        Self {
            clock,
            subscribers: HashMap::new(),
            expire_duration,
        }
    }

    /// Records a subscriber's info with the current time when it sends a watch request, and
    /// returns the previous info if the subscriber was already present, or None otherwise.
    pub fn record_watch_request(
        &mut self,
        subscriber_debug_name: SubscriberDebugName,
        info: SubscriberInfo,
    ) -> Option<SubscriberInfo> {
        let record = LastSeenSubscriberRecord {
            subscriber_info: info,
            last_active: self.clock.instant(),
        };

        let previous_value: Option<LastSeenSubscriberRecord> =
            self.subscribers.insert(subscriber_debug_name, record);
        previous_value.map(|record| record.subscriber_info) // Ignore the previous timestamp.
    }

    /// Removes subscribers that have expired and returns a cloned vector of active subscribers.
    /// This method is relatively expensive and **MUST** be called infrequently.
    pub fn get_active_subscribers(
        &mut self,
    ) -> &HashMap<SubscriberDebugName, LastSeenSubscriberRecord> {
        let now = self.clock.instant();

        // Remove inactive subscribers.
        self.subscribers
            .retain(|_, record| now.duration_since(record.last_active) < self.expire_duration);

        &self.subscribers
    }
}

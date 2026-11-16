use std::collections::{HashMap, HashSet};
use std::fmt::{self, Write};
use std::sync::Arc;
use std::time::{Duration, Instant};

use derive_more::Display;
use prometheus_client::encoding::{EncodeLabelSet, EncodeLabelValue, LabelValueEncoder};
use prometheus_client::metrics::counter::Counter;
use prometheus_client::metrics::histogram::Histogram;
use prost::Message;
use rand::rngs::StdRng;
use rand::{Rng, SeedableRng};
use tonic::{Request, Response, Status};
use caching_rust_util::prefix_logger::PrefixLogger;
use caching_rust_util::sequential_executor::SequentialExecutor;
use caching_rust_util::watch_cell::{self, WatchCell};
use caching_rust_util::{
    caching_error_code,
    prefix_critical_alert,
    prefix_degraded_alert,
    prefix_info,
    Clock,
};
use dicer_common_proto_common_proto::dicer::assignment_service_server::AssignmentService;
use dicer_common_proto_common_proto::dicer::{
    ClientRequestP,
    ClientResponseP,
    SyncAssignmentStateP,
};
use dicer_rust_external_types::target::{
    get_target_cluster,
    target_variant,
    TargetVariant,
};
use dicer_rust_external_types::Target;
use raf_metrics::{counter, gauge, histogram};

use crate::common_slicez::{ClerkSubscriberSlicezData, SliceletSubscriberSlicezData};
use crate::recent_subscribers_tracker::{LastSeenSubscriberRecord, SubscriberDebugName};
use crate::watch_server_util::{
    get_default_watch_rpc_timeout,
    MAX_WATCH_REQUEST_CONTENT_LENGTH_BYTES,
};
use crate::{
    should_serve_request_target,
    Assignment,
    AssignmentCellValue,
    ClientRequest,
    ClientResponse,
    ClientType,
    Generation,
    MetricsKey,
    RecentSubscribersTracker,
    Redirect,
    SubscriberData,
    SubscriberInfo,
    SyncAssignmentState,
    TargetMetricLabels,
};

/// Minimum time after a subscriber last watched that it remains in the active Clerks or Slicelets
/// collection. We expect subscriber entries to be cleared up within twice this interval in the
/// general case.
const SUBSCRIBERS_EXPIRE_AFTER: Duration = Duration::from_mins(10);

/// Threshold (80% of the max watch message content length) at or above which we alert that a watch
/// response is near the limit clients enforce.
const WATCH_RESPONSE_SIZE_ALERT_THRESHOLD_BYTES: usize =
    MAX_WATCH_REQUEST_CONTENT_LENGTH_BYTES * 4 / 5;

/// The `senderClusterUri` label value used when a watch request did not carry a sender cluster URI.
/// Non-empty so that queries can tell it apart from a handler running a binary that predates the
/// label, whose series carry no `senderClusterUri` at all. Must match the Scala value.
const UNKNOWN_SENDER_CLUSTER_URI: &str = "unknown";

counter!(
    DICER_WATCH_REQUESTS_TOTAL,
    "The number of watch requests received by the SubscriberHandler",
    WatchRequestMetricLabels
);

gauge!(
    DICER_SUBSCRIBER_NUM_SUBSCRIBERS,
    "The number of Clerks/Slicelets connected with the SubscriberHandler in the last few minutes, \
    along with their client code version and the location of the handler",
    SubscriberMetricLabels
);

counter!(
    DICER_WATCH_REQUESTS_LOAD_SHED_TOTAL,
    "The number of watch requests that took too long to process on the server and were load shed",
    TargetMetricLabels
);

/// Histogram buckets for serialized assignment and response sizes in bytes. These histograms are
/// labeled per target, so the bucket count is kept small: boundaries grow 4x from 1 KiB to 1 MiB,
/// which covers nearly all responses, then mark 2 MiB, 3 MiB and the gRPC limit (currently 4 MiB,
/// see [`crate::watch_server_util::MAX_WATCH_REQUEST_CONTENT_LENGTH_BYTES`]), with one boundary
/// beyond it to capture outliers.
const SERIALIZED_ASSIGNMENT_SIZE_BUCKETS: [f64; 10] = {
    const KIB: f64 = 1024.0;
    const MIB: f64 = 1024.0 * KIB;
    [
        KIB,
        4.0 * KIB,
        16.0 * KIB,
        64.0 * KIB,
        256.0 * KIB,
        MIB,
        2.0 * MIB,
        3.0 * MIB,
        4.0 * MIB,
        8.0 * MIB,
    ]
};

histogram!(
    DICER_SERIALIZED_ASSIGNMENT_SIZE_BYTES_HISTOGRAM,
    "The size of serialized assignment state protos distributed by SubscriberHandler in bytes",
    SERIALIZED_ASSIGNMENT_SIZE_BUCKETS,
    SerializedAssignmentSizeMetricLabels
);

histogram!(
    DICER_CLIENT_RESPONSE_SIZE_BYTES_HISTOGRAM,
    "Size of ClientResponseP protos distributed by SubscriberHandler in bytes",
    SERIALIZED_ASSIGNMENT_SIZE_BUCKETS,
    SerializedAssignmentSizeMetricLabels
);

/// Identifies the location of the handler.
#[derive(Clone, Copy, Debug, Display, Eq, PartialEq, Hash)]
pub enum HandlerLocation {
    #[display("assigner")]
    Assigner,

    #[display("slicelet")]
    Slicelet,

    #[display("clerk")]
    Clerk,
}

impl EncodeLabelValue for HandlerLocation {
    fn encode(&self, encoder: &mut LabelValueEncoder) -> Result<(), fmt::Error> {
        let value: &str = match self {
            HandlerLocation::Assigner => "Assigner",
            HandlerLocation::Slicelet => "Slicelet",
            HandlerLocation::Clerk => "Clerk",
        };
        encoder.write_str(value)
    }
}

// Ensure that `SubscriberHandler` is `Send` and `Sync`.
static_assertions::assert_impl_all!(SubscriberHandler: Send, Sync);

/// Manages all the subscribers for a given sharded target. It informs the pending RPC callers
/// when the assignment changes, or when the the suggested RPC timeout is reached.
pub struct SubscriberHandler {
    /// Sequential executor that guards mutable, exclusive state.
    executor: SequentialExecutor<Inner>,

    /// Cached metric child for the load-shed counter to avoid recreation on every increment.
    load_shed_counter: Counter,

    /// Cached metric child for client response size histogram.
    client_response_size_histogram: Histogram,

    /// Cached metric child for serialized assignment size histogram.
    serialized_assignment_size_histogram: Histogram,

    /// The target this handler serves.
    target: Target,

    /// Logger for alert metrics and structured log output.
    logger: PrefixLogger,
}

/// Total jitter range relative to the chosen watch request processing timeout. A value of
/// 0.4 (40%) varies each request's timeout by up to 20% in either direction (+/-), which helps
/// spread out watch request cancellations for a target.
const PROCESSING_TIMEOUT_JITTER_RATIO: f64 = 0.4;

impl SubscriberHandler {
    /// Creates a handler with a fresh RNG for adding jitter to watch processing timeouts.
    pub fn new(clock: Clock, handler_location: HandlerLocation, target: Target) -> Self {
        Self::new_with_random(clock, handler_location, target, StdRng::from_os_rng())
    }

    /// Creates a handler with the supplied RNG for adding jitter to watch processing timeouts,
    /// allowing tests to reproduce random values using a logged seed.
    pub fn new_with_random_for_test(
        clock: Clock,
        handler_location: HandlerLocation,
        target: Target,
        random: StdRng,
    ) -> Self {
        Self::new_with_random(clock, handler_location, target, random)
    }

    /// Initializes the handler and its executor-owned state with the supplied RNG.
    fn new_with_random(
        clock: Clock,
        handler_location: HandlerLocation,
        target: Target,
        random: StdRng,
    ) -> Self {
        let target_labels = TargetMetricLabels::from_target(&target);
        let size_labels = SerializedAssignmentSizeMetricLabels::new(handler_location, &target);

        let load_shed_counter: Counter =
            DICER_WATCH_REQUESTS_LOAD_SHED_TOTAL.get_or_create_owned(&target_labels);
        let client_response_size_histogram: Histogram =
            DICER_CLIENT_RESPONSE_SIZE_BYTES_HISTOGRAM.get_or_create_owned(&size_labels);
        let serialized_assignment_size_histogram: Histogram =
            DICER_SERIALIZED_ASSIGNMENT_SIZE_BYTES_HISTOGRAM.get_or_create_owned(&size_labels);

        let logger = PrefixLogger::new("SubscriberHandler", format!("{target:?}"));
        let inner = Inner {
            handler_location,
            target: target.clone(),
            recent_subscribers_tracker: RecentSubscribersTracker::new(
                clock.clone(),
                SUBSCRIBERS_EXPIRE_AFTER,
            ),
            last_published_metrics: HashSet::new(),
            cached_sync_assignment_state: None,
            logger: logger.clone(),
            random,
        };

        let executor = SequentialExecutor::new(inner, clock);

        Self {
            executor,
            load_shed_counter,
            client_response_size_histogram,
            serialized_assignment_size_histogram,
            target,
            logger,
        }
    }

    /// Starts the subscriber handler so it can start processing watch requests, also starts
    /// a background task periodically updating the metrics.
    pub fn start(&self, handle: &tokio::runtime::Handle) {
        let started: bool = self.executor.start(handle);
        if started {
            /// Schedules the next periodic metrics update. The scheduled task will reschedule itself
            /// to create a repeating pattern until the executor is dropped.
            fn schedule_metrics_update(executor: &SequentialExecutor<Inner>) {
                executor.schedule(
                    |executor, inner| {
                        inner.update_subscriber_metrics();
                        // Reschedule for next update.
                        schedule_metrics_update(executor);
                    },
                    SUBSCRIBERS_EXPIRE_AFTER,
                );
            }
            schedule_metrics_update(&self.executor);
        }
    }

    /// Handles a watch request by returning either
    ///  - a new assignment diff, if the newer assignment becomes available, or
    ///  - the current known generation, if timeout occurs first.
    pub async fn handle_watch<A: AssignmentCellValue>(
        &self,
        request: ClientRequest,
        assignment_cell: WatchCell<A>,
    ) -> Result<ClientResponseP, Status> {
        // Explicitly capture start time before calling into the sequential executor, because the
        // executor may have a queue built up and we want to account for its scheduling delay.
        let start_time: Instant = self.executor.clock().instant();

        // Track this client in recent-subscribers collection and update metrics.
        let (request, processing_timeout): (ClientRequest, Duration) = self
            .executor
            .call(|_executor, inner| {
                inner.update_watch_metrics(&request);

                // Return NOT_FOUND if the assignment should not be served to this request.
                if !should_serve_request_target(&inner.target, request.target()) {
                    return Err(Status::not_found(format!(
                        "Request for target {:?} misrouted to handler for target {:?}. \
                         See 'Configuration: Clients' in <internal link>.",
                        request.target(),
                        inner.target,
                    )));
                }

                // Unlike Scala, where replyFromCell calculates the watch processing timeout within the SEC,
                // Rust's reply_from_cell sets up the timeout outside the executor call. Calculate the watch
                // processing timeout here because it now adds jitter, which requires mutable access to the
                // RNG in the executor's inner state. Passing the calculated timeout to reply_from_cell avoids
                // another executor call to access the RNG.
                let processing_timeout: Duration =
                    Self::get_watch_processing_timeout(request.timeout(), &mut inner.random);
                Ok((request, processing_timeout))
            })
            .await?;
        let response_proto = self
            .reply_from_cell(start_time, request, assignment_cell, processing_timeout)
            .await?;

        // Record response size metrics using the cached histogram children.
        self.record_response_size(&response_proto);

        Ok(response_proto)
    }

    /// Returns the information about the connect Clerks and Slicelets for the slicez debugging.
    pub async fn get_slicez_data(
        &self,
    ) -> (Vec<SliceletSubscriberSlicezData>, Vec<ClerkSubscriberSlicezData>) {
        self.executor
            .call(|_executor, inner| {
                let subscribers: &HashMap<SubscriberDebugName, LastSeenSubscriberRecord> =
                    inner.recent_subscribers_tracker.get_active_subscribers();
                let mut slicelet_data: Vec<SliceletSubscriberSlicezData> = Vec::new();
                let mut clerk_data: Vec<ClerkSubscriberSlicezData> = Vec::new();
                for (debug_name, record) in subscribers.iter() {
                    match &record.subscriber_info {
                        SubscriberInfo::SliceletInfo { address, .. } => {
                            slicelet_data.push(SliceletSubscriberSlicezData {
                                debug_name: debug_name.clone(),
                                address: address.clone(),
                            });
                        }
                        SubscriberInfo::ClerkInfo { .. } => {
                            clerk_data.push(ClerkSubscriberSlicezData {
                                debug_name: debug_name.clone(),
                            });
                        }
                    }
                }
                (slicelet_data, clerk_data)
            })
            .await
    }

    /// The timeout on the server side so that the Watch RPC is "returned" rather than timed out. We
    /// want to return the RPC with an empty message (no assignment) rather than let it time out since
    /// a DEADLINE_EXCEEDED shows up as an error and can be confusing. Also, this model allows us to
    /// return the known generation number for sync purposes.
    fn get_watch_processing_timeout(
        client_watch_rpc_timeout: Duration,
        random: &mut StdRng,
    ) -> Duration {
        // Set the server-side processing timeout as follows:
        // - If the client's watch RPC timeout is between 0 and 10 seconds, use
        //   50% of the client's watch RPC timeout. At most, this will be 5 seconds.
        // - If the client's watch RPC timeout exceeds 10 seconds, use the client's watch
        //   RPC timeout minus 5 seconds (this will be constant).
        //
        // These values are heuristic choices intended to leave enough time to successfully return
        // the RPC despite potential delays (e.g. networking delays).
        let max_client_watch_rpc_timeout_offset: Duration = Duration::from_secs(5);
        let client_watch_rpc_timeout_offset: Duration =
            (client_watch_rpc_timeout / 2).min(max_client_watch_rpc_timeout_offset);

        let processing_timeout_without_jitter: Duration =
            client_watch_rpc_timeout - client_watch_rpc_timeout_offset;

        // We add jitter to spread the periodic watch request cancellations across more CPU quota
        // periods to try to avoid processing most or all of them in synchronized bursts. Our load
        // tests have shown that this reduces CPU throttling in the Assigner, especially when
        // dealing with watch request cancellations for larger targets (i.e., 200+ Slicelets).
        //
        // NOTE: Cap the total jitter range at 5 seconds, so the jitter we apply will be at most
        // +/-2.5 seconds from the unjittered processing timeout calculated above. This cap ensures
        // that the server-side timeout, even with the maximum applicable jitter, is still guaranteed
        // to be earlier than the client's watch RPC timeout.
        //
        // Without this cap, for clients with sufficiently large watch RPC timeouts, applying jitter
        // could produce server-side timeouts that exceed the client's deadline, contrary to our
        // intent to return a response before that deadline.
        //
        // For example, with a jitter ratio of 40% (+/-20%) and a client watch RPC timeout of 35s:
        //
        //                            Server-side processing timeout
        // Without jitter:            30s
        // With jitter, without cap:   [24s, 36s]     <-- can exceed the client's 35s deadline
        // With jitter, with cap:      [27.5s, 32.5s] <-- stays below the client's 35s deadline
        let jitter_range: f64 = (PROCESSING_TIMEOUT_JITTER_RATIO
            * processing_timeout_without_jitter.as_secs_f64())
        .min(max_client_watch_rpc_timeout_offset.as_secs_f64());

        // Sample jitter from the uniform distribution U[-jitter_range/2, jitter_range/2).
        let jitter: f64 = (random.random::<f64>() - 0.5) * jitter_range;
        let processing_timeout: f64 = processing_timeout_without_jitter.as_secs_f64() + jitter;

        // Although we expect the result to be representable, defensively fall back to the
        // unjittered timeout if multiplication overflows.
        Duration::try_from_secs_f64(processing_timeout).unwrap_or(processing_timeout_without_jitter)
    }

    /// Waits for a new assignment or timeout, then constructs the appropriate response proto.
    async fn reply_from_cell<A: AssignmentCellValue>(
        &self,
        start_time: Instant,
        request: ClientRequest,
        assignment_cell: WatchCell<A>,
        processing_timeout: Duration,
    ) -> Result<ClientResponseP, Status> {
        let clock = self.executor.clock().clone();

        // Schedule a timeout to race against the assignment's change. Ensure we apply the timeout
        // from `start_time` rather than the current time, since the sequential executor may have a
        // queue built up and delay the execution of the handler. But we still want to try to
        // respond to the client in time.
        let elapsed: Duration = clock.instant().saturating_duration_since(start_time);
        let remaining_timeout: Duration = processing_timeout.saturating_sub(elapsed);
        let remaining_processing_timeout_future = clock.sleep_with_reason(
            remaining_timeout,
            format!("subscriber_handler_processing_timeout_{:?}", request.target()),
        );

        let remote_server_known_generation: Generation =
            request.sync_assignment_state().known_generation();
        let supports_serialized_assignment: bool = request.supports_serialized_assignment();
        let load_shed_counter = self.load_shed_counter.clone();
        let serialized_assignment_size_histogram =
            self.serialized_assignment_size_histogram.clone();

        tokio::select! {
            biased; // Prefer the completed future over the sleep.

            _ = assignment_cell.changed_since(remote_server_known_generation) => {
                // Assignment changed within timeout. Return the updated assignment.
                let assignment_value: watch_cell::Ref<A> = assignment_cell.get();

                let sync_state_p: SyncAssignmentStateP = self.executor.call(
                    move |_executor, inner| -> Result<SyncAssignmentStateP, Status> {
                        let elapsed: Duration =
                            clock.instant().saturating_duration_since(start_time);
                        if elapsed > request.timeout() {
                            // If the request timeout has elapsed, it is possible that the client
                            // has already timed out its request. Note that we don't expect to reach
                            // here typically because remaining_processing_timeout_future races
                            // against changed_since and would win if no assignment arrives in time.
                            // But if an assignment arrives just before the timeout, the biased
                            // select prefers the assignment branch, and the subsequent executor
                            // call may be delayed if the executor queue is backlogged, by which
                            // point the request timeout may have elapsed.

                            // In this case, we load shed the request and simply return the known
                            // generation. We don't return the cached assignment because it adds
                            // more complexity and still introduces more overhead for the rpc layer.
                            prefix_info!(
                                inner.logger,
                                every_n_sec = 30,
                                "Processing an assignment after the request timeout, \
                                 this may indicate the sequential executor is overloaded. \
                                 Returning just the known generation."
                            );
                            load_shed_counter.inc();
                            return Ok(SyncAssignmentState::KnownGeneration(
                                assignment_value.generation(),
                            ).to_proto());
                        }

                        match assignment_value.assignment_opt() {
                            Some(assignment)
                                if assignment.generation() > remote_server_known_generation =>
                            {
                                let cached_state: &CachedSyncAssignmentState =
                                    inner.get_or_update_cached_sync_assignment_state(assignment)?;
                                if cached_state.generation > assignment.generation() {
                                    // The cache is ahead of the watched assignment; reply with
                                    // only its generation.
                                    return Ok(SyncAssignmentState::KnownGeneration(
                                        cached_state.generation,
                                    ).to_proto());
                                }
                                let full_state_p: SyncAssignmentStateP =
                                    if supports_serialized_assignment {
                                        cached_state.sync_serialized_assignment_state_p.clone()
                                    } else {
                                        cached_state.sync_assignment_state_p.clone()
                                    };
                                serialized_assignment_size_histogram
                                    .observe(full_state_p.encoded_len() as f64);
                                Ok(full_state_p)
                            }
                            _ => {
                                // Unreachable: `changed_since` should return an assignment with a
                                // higher generation. If this ever happens, we log a critical error
                                // and return the known generation so a future retry has a chance to
                                // succeed. We prefer this to panicking.
                                prefix_critical_alert!(
                                    inner.logger,
                                    caching_error_code::RUST_SUBSCRIBER_HANDLER_INTERNAL_ERROR,
                                    log_every_n_sec = 30,
                                    "changed_since returned unexpected assignment",
                                );
                                Ok(SyncAssignmentState::KnownGeneration(
                                    assignment_value.generation(),
                                ).to_proto())
                            }
                        }
                    },
                ).await?;

                Ok(ClientResponse::create_proto_with_sync_state_p(
                    sync_state_p,
                    get_default_watch_rpc_timeout(),
                    // No redirects between Slicelets and Clerks.
                    &Redirect::EMPTY,
                ))
            }

            _ = remaining_processing_timeout_future => {
                // Timeout occurred - return current known generation.
                let assignment_value: watch_cell::Ref<A> = assignment_cell.get();
                let current_generation: Generation = assignment_value.generation();
                let sync_state_p: SyncAssignmentStateP =
                    SyncAssignmentState::KnownGeneration(current_generation).to_proto();
                Ok(ClientResponse::create_proto_with_sync_state_p(
                    sync_state_p,
                    get_default_watch_rpc_timeout(),
                    // No redirects between Slicelets and Clerks.
                    &Redirect::EMPTY,
                ))
            }
        }
    }

    /// Records the size of a watch response being distributed to a client, and alerts if the
    /// response is near or exceeding the content length limit clients enforce.
    fn record_response_size(&self, response_proto: &ClientResponseP) {
        let response_size_bytes = response_proto.encoded_len();
        self.client_response_size_histogram
            .observe(response_size_bytes as f64);

        if response_size_bytes >= WATCH_RESPONSE_SIZE_ALERT_THRESHOLD_BYTES {
            prefix_degraded_alert!(
                self.logger,
                caching_error_code::SUBSCRIBER_HANDLER_WATCH_RESPONSE_NEAR_OR_EXCEEDING_LIMIT,
                log_every_n_sec = 60,
                "Watch response for target {:?} is {} bytes, at or above {} bytes (80% of the {} \
                 byte limit). Responses exceeding the limit cause clients to receive \
                 RESOURCE_EXHAUSTED errors and fail to receive assignments.",
                self.target,
                response_size_bytes,
                WATCH_RESPONSE_SIZE_ALERT_THRESHOLD_BYTES,
                MAX_WATCH_REQUEST_CONTENT_LENGTH_BYTES,
            );
        }
    }
}

/// Stores the proto representation of [`SyncAssignmentState`] along with its generation. Used to
/// avoid redundant proto conversions of the same assignment when handling watch requests from
/// different clients.
struct CachedSyncAssignmentState {
    /// The proto containing a structured `KnownAssignment`, used when the client doesn't support
    /// serialized assignments.
    sync_assignment_state_p: SyncAssignmentStateP,

    /// The proto containing serialized bytes of `KnownSerializedAssignment`, used when the client
    /// supports serialized assignments.
    sync_serialized_assignment_state_p: SyncAssignmentStateP,

    /// The generation of the cached assignment.
    generation: Generation,
}

impl CachedSyncAssignmentState {
    /// Creates a [`CachedSyncAssignmentState`] from the given full `assignment`.
    ///
    /// Note: This method takes an [`Assignment`] rather than a [`DiffAssignment`] because we only
    /// cache full assignments (diffed from [`Generation::EMPTY`]), not partial diffs. Revisit this
    /// if the cache logic needs to support partial assignments.
    fn from_assignment(assignment: &Assignment) -> Result<Self, Status> {
        let diff_assignment = assignment.to_diff(Generation::EMPTY)?;
        let (sync_assignment_state_p, sync_serialized_assignment_state_p) =
            SyncAssignmentState::known_assignment_to_cached_protos(diff_assignment);
        Ok(Self {
            sync_assignment_state_p,
            sync_serialized_assignment_state_p,
            generation: assignment.generation(),
        })
    }
}

/// Inner state for SubscriberHandler guarded by the [`SequentialExecutor`].
struct Inner {
    /// The location of this handler.
    handler_location: HandlerLocation,

    /// The target which this handler is serving.
    target: Target,

    /// Tracks the active subscribers.
    recent_subscribers_tracker: RecentSubscribersTracker,

    /// Tracks the metric labels that we published previously, for this target. We need to track
    /// this to stop publishing metrics for the keys that get removed.
    last_published_metrics: HashSet<MetricsKey>,

    /// Stores the assignment state proto and generation for the latest full assignment processed
    /// from the assignment cell. It is reused to avoid redundant proto conversions. The state is
    /// updated whenever a newer full assignment is received with a higher generation number.
    cached_sync_assignment_state: Option<CachedSyncAssignmentState>,

    /// Logger for alert metrics and structured log output.
    logger: PrefixLogger,

    /// Random number generator for watch processing timeout jitter.
    random: StdRng,
}

impl Inner {
    /// Updates various metrics based on receiving the watch request.
    fn update_watch_metrics(&mut self, request: &ClientRequest) {
        // First, update the watch requests counter.
        DICER_WATCH_REQUESTS_TOTAL
            .get_or_create_owned(&WatchRequestMetricLabels::new(
                self.handler_location,
                &self.target,
                request,
            ))
            .inc();

        // Extract subscriber info from the request.
        let subscriber_info = match request.subscriber_data() {
            SubscriberData::Slicelet(slicelet_data) => SubscriberInfo::SliceletInfo {
                version: request.version(),
                address: slicelet_data
                    .squid()
                    .resource_address()
                    .as_str()
                    .to_string(),
            },
            SubscriberData::Clerk => SubscriberInfo::ClerkInfo {
                version: request.version(),
            },
        };

        // Record the subscriber in the tracker.
        let previous_subscriber_info_opt: Option<SubscriberInfo> =
            self.recent_subscribers_tracker.record_watch_request(
                request.subscriber_debug_name().to_string(),
                subscriber_info.clone(),
            );

        if previous_subscriber_info_opt.is_none() {
            // If the subscriber is new, eagerly update the number of subscribers metric.
            let metrics_key = MetricsKey::new(&subscriber_info);
            let labels =
                SubscriberMetricLabels::new(self.handler_location, request.target(), &metrics_key);
            DICER_SUBSCRIBER_NUM_SUBSCRIBERS
                .get_or_create_owned(&labels)
                .inc();

            // Track this metrics key.
            self.last_published_metrics.insert(metrics_key);
        }
    }

    /// Returns the [`CachedSyncAssignmentState`] for the given full `assignment`, updating
    /// `cached_sync_assignment_state` if `assignment` is newer than the cached state. The returned
    /// state's generation is never less than `assignment.generation()`; it is greater only if the
    /// cache is ahead of `assignment` (unexpected, and alerted).
    fn get_or_update_cached_sync_assignment_state(
        &mut self,
        assignment: &Assignment,
    ) -> Result<&CachedSyncAssignmentState, Status> {
        // When a newer assignment is received, update the cached state as needed. Reuse it whenever
        // possible to avoid redundant proto conversions. This always assumes full assignments.
        // In the future, if we implement durable assignments, we could optimize by distributing
        // partial diffs instead of full assignments.
        //
        // The cached state is taken out and reinserted, rather than borrowed in place, because the
        // borrow checker rejects returning a reference that is only conditionally borrowed.
        let current_state: CachedSyncAssignmentState =
            match self.cached_sync_assignment_state.take() {
                Some(cached) if cached.generation >= assignment.generation() => {
                    // Cache is up-to-date (or ahead), no update needed.
                    cached
                }
                previous_opt => match CachedSyncAssignmentState::from_assignment(assignment) {
                    Ok(fresh) => fresh,
                    Err(status) => {
                        // Leave the previous cached state in place on failure.
                        self.cached_sync_assignment_state = previous_opt;
                        return Err(status);
                    }
                },
            };
        let cached_state: &CachedSyncAssignmentState =
            self.cached_sync_assignment_state.insert(current_state);

        // Defensive: we don't expect the cache to be ahead of the source given rust watch cell only
        // return the assignment with higher generation than the known generation, but we alert to
        // be safe.
        if cached_state.generation > assignment.generation() {
            prefix_degraded_alert!(
                self.logger,
                caching_error_code::SUBSCRIBER_HANDLER_ASSIGNMENT_CACHE_AHEAD_OF_SOURCE,
                log_every_n_sec = 30,
                "Cached assignment generation ahead of source",
            );
        }
        Ok(cached_state)
    }

    /// Updates the gauge metrics tracking recent Clerks and Slicelets for this target. Cleans up
    /// subscribers that have not been recently seen from the `recent_subscribers_tracker`, since
    /// the configured expiry time is otherwise only enforced lazily when `get_active_subscribers`
    /// is called.
    ///
    /// Note: This is an expensive operation and should not be called too often.
    fn update_subscriber_metrics(&mut self) {
        // Get active subscribers (this also cleans up expired entries)
        let active_subscribers: &HashMap<SubscriberDebugName, LastSeenSubscriberRecord> =
            self.recent_subscribers_tracker.get_active_subscribers();

        // Count by (Clerk or Slicelet, version) - create metrics keys for counting
        let mut counts: HashMap<MetricsKey, i64> = HashMap::new();
        for record in active_subscribers.values() {
            let metrics_key = MetricsKey::new(&record.subscriber_info);
            *counts.entry(metrics_key).or_insert(0) += 1;
        }

        // Update metrics based on count
        for (key, count) in &counts {
            DICER_SUBSCRIBER_NUM_SUBSCRIBERS
                .get_or_create_owned(&SubscriberMetricLabels::new(
                    self.handler_location,
                    &self.target,
                    key,
                ))
                .set(*count);
        }

        // Stop publishing metrics for labels that are no longer relevant
        let current_metrics: HashSet<MetricsKey> = counts.keys().cloned().collect();

        // Collect the keys to remove and update stored metrics using swap
        let metrics_to_remove: Vec<MetricsKey> = self
            .last_published_metrics
            .difference(&current_metrics)
            .cloned()
            .collect();

        // Use swap to replace the old set with the new one without cloning
        let mut new_metrics = current_metrics;
        std::mem::swap(&mut self.last_published_metrics, &mut new_metrics);

        // Iterate over keys to remove their gauges
        for key in metrics_to_remove {
            // Remove the gauge for metrics that are no longer relevant.
            DICER_SUBSCRIBER_NUM_SUBSCRIBERS.remove(&SubscriberMetricLabels::new(
                self.handler_location,
                &self.target,
                &key,
            ));
        }
    }
}

impl Drop for Inner {
    fn drop(&mut self) {
        // Get the recent subscribers and remove them from metrics.
        let recent_subscribers: &HashMap<SubscriberDebugName, LastSeenSubscriberRecord> =
            self.recent_subscribers_tracker.get_active_subscribers();

        for record in recent_subscribers.values() {
            let metrics_key = MetricsKey::new(&record.subscriber_info);
            let labels =
                SubscriberMetricLabels::new(self.handler_location, &self.target, &metrics_key);
            DICER_SUBSCRIBER_NUM_SUBSCRIBERS.remove(&labels);
        }
    }
}

/// The implementation of `AssignmentService` for Slicelets.
///
/// This server handles watch requests from Clerks, allowing them to discover the latest
/// assignment information from this Slicelet.
pub struct SliceletAssignmentServer<A: AssignmentCellValue> {
    subscriber_handler: Arc<SubscriberHandler>,
    assignment_cell: WatchCell<A>,
}

impl<A: AssignmentCellValue> SliceletAssignmentServer<A> {
    pub fn new(subscriber_handler: Arc<SubscriberHandler>, assignment_cell: WatchCell<A>) -> Self {
        Self {
            subscriber_handler,
            assignment_cell,
        }
    }
}

#[async_trait::async_trait]
impl<A: AssignmentCellValue> AssignmentService for SliceletAssignmentServer<A> {
    async fn watch(
        &self,
        request: Request<ClientRequestP>,
    ) -> Result<Response<ClientResponseP>, Status> {
        // Convert the proto request to our internal ClientRequest type.
        let proto_request: ClientRequestP = request.into_inner();
        let client_request: ClientRequest = ClientRequest::from_proto(&proto_request)?;

        // Delegate to SubscriberHandler's business logic.
        let response_proto: ClientResponseP = self
            .subscriber_handler
            .handle_watch(client_request, self.assignment_cell.clone())
            .await?;

        Ok(Response::new(response_proto))
    }
}
/// Prometheus labels for metrics that are broken down by watch request attributes.
#[derive(Clone, Debug, Hash, PartialEq, Eq, EncodeLabelSet)]
#[allow(non_snake_case)]
struct WatchRequestMetricLabels {
    // Fields below need to be camel case to be consistent with the label names used in Scala.
    handlerTargetCluster: String, // The target cluster of the handler.
    handlerTargetName: String,    // The target name of the handler.
    handlerTargetInstanceId: String, // The target instance ID of the handler.
    requestTargetCluster: String, // The target cluster of the request.
    requestTargetName: String,    // The target name of the request.
    requestTargetInstanceId: String, // The target instance ID of the request.
    callerService: &'static str,  // The service name of the caller.
    r#type: ClientType, // The client type of the caller. Use `r#` since type is a Rust keyword.
    version: String,    // The client code version of the caller.
    handlerLocation: HandlerLocation, // The location of the handler.
    matchedName: &'static str, // Whether the target name matches, used to simplify Prom query.
    matchedCluster: &'static str, // Whether the target cluster matches.
    matchedInstanceId: &'static str, // Whether the target instance ID matches.
    // The name of the request's alternativeTarget, empty when no alternativeTarget is provided.
    alternativeTargetName: String,
    // The instance ID of the request's alternativeTarget, empty when no alternativeTarget provided.
    alternativeTargetInstanceId: String,
    // The Kubernetes cluster URI of the sender pod, `UNKNOWN_SENDER_CLUSTER_URI` when the request
    // did not populate it.
    senderClusterUri: String,
}

impl WatchRequestMetricLabels {
    fn new(
        handler_location: HandlerLocation,
        handler_target: &Target,
        client_request: &ClientRequest,
    ) -> Self {
        let request_target: &Target = client_request.target();
        let handler_target_label = TargetMetricLabels::from_target(handler_target);
        let request_target_label = TargetMetricLabels::from_target(request_target);
        let matched_name_label_value = if handler_target.name() == request_target.name() {
            "true"
        } else {
            "false"
        };
        let matched_cluster_label_value =
            match (target_variant(handler_target), target_variant(request_target)) {
                (TargetVariant::Kubernetes(_), TargetVariant::Kubernetes(_)) => {
                    // For KubernetesTargets, compare just the cluster field (not the entire struct)
                    match (get_target_cluster(handler_target), get_target_cluster(request_target)) {
                        (Some(h_cluster), Some(r_cluster)) if h_cluster == r_cluster => "true",
                        _ => "false",
                    }
                }
                (TargetVariant::App(_), TargetVariant::App(_)) => {
                    // If both Targets are AppTargets and they are equal, set the "matchedCluster" label to
                    // "true" even though there is no cluster for either target. This is to remain consistent
                    // with the property that: `handlerTarget` = `requestTarget` iff `matchedCluster` = "true".
                    if handler_target == request_target {
                        "true"
                    } else {
                        "false"
                    }
                }
                _ => "false",
            };
        let matched_instance_id_label_value =
            match (target_variant(handler_target), target_variant(request_target)) {
                (TargetVariant::Kubernetes(_), TargetVariant::Kubernetes(_)) => {
                    // If both Targets are KubernetesTargets and they are equal, set the "matchedInstanceId"
                    // label to "true" even though there is no instance ID for either target. This is to
                    // remain consistent with the property that: `handlerTarget` = `requestTarget` iff
                    // `matchedInstanceId` = "true".
                    if handler_target == request_target {
                        "true"
                    } else {
                        "false"
                    }
                }
                (TargetVariant::App(h), TargetVariant::App(r))
                    if h.instance_id() == r.instance_id() =>
                {
                    "true"
                }
                (TargetVariant::App(_), TargetVariant::App(_)) => "false",
                _ => "false",
            };

        let client_type: ClientType = match client_request.subscriber_data() {
            SubscriberData::Slicelet(_) => ClientType::Slicelet,
            SubscriberData::Clerk => ClientType::Clerk,
        };

        let (alternative_target_name, alternative_target_instance_id): (String, String) =
            match client_request.alternative_target_opt() {
                Some(app_target) => {
                    (app_target.name().to_string(), app_target.instance_id().to_string())
                }
                None => (String::new(), String::new()),
            };

        let sender_cluster_uri: String = client_request
            .cluster_uri_opt()
            .map(|cluster_uri| cluster_uri.to_string())
            .unwrap_or_else(|| UNKNOWN_SENDER_CLUSTER_URI.to_string());

        WatchRequestMetricLabels {
            handlerTargetCluster: handler_target_label.targetCluster,
            handlerTargetName: handler_target_label.targetName,
            handlerTargetInstanceId: handler_target_label.targetInstanceId,
            requestTargetCluster: request_target_label.targetCluster,
            requestTargetName: request_target_label.targetName,
            requestTargetInstanceId: request_target_label.targetInstanceId,
            callerService: "unknown", // There's no service identity context in Rust.
            r#type: client_type,
            version: client_request.version().to_string(),
            handlerLocation: handler_location,
            matchedName: matched_name_label_value,
            matchedCluster: matched_cluster_label_value,
            matchedInstanceId: matched_instance_id_label_value,
            alternativeTargetName: alternative_target_name,
            alternativeTargetInstanceId: alternative_target_instance_id,
            senderClusterUri: sender_cluster_uri,
        }
    }
}

#[derive(Clone, Debug, Hash, PartialEq, Eq, EncodeLabelSet)]
#[allow(non_snake_case)]
/// Prometheus labels for subscriber metrics.
pub struct SubscriberMetricLabels {
    // Fields below need to be camel case to be consistent with the label names used in Scala.
    targetCluster: String,
    targetName: String,
    targetInstanceId: String,
    r#type: &'static str, // Use `r#` since type is a Rust keyword.
    version: String,
    handlerLocation: HandlerLocation,
}

impl SubscriberMetricLabels {
    pub fn new(
        handler_location: HandlerLocation,
        target: &Target,
        metrics_key: &MetricsKey,
    ) -> Self {
        let target_label = TargetMetricLabels::from_target(target);
        Self {
            targetCluster: target_label.targetCluster,
            targetName: target_label.targetName,
            targetInstanceId: target_label.targetInstanceId,
            r#type: metrics_key.type_label,
            version: metrics_key.version.to_string(),
            handlerLocation: handler_location,
        }
    }
}

#[derive(Clone, Debug, Hash, PartialEq, Eq, EncodeLabelSet)]
#[allow(non_snake_case)]
/// Prometheus labels for serialized assignment size metrics.
pub struct SerializedAssignmentSizeMetricLabels {
    // Note: The fields below need to be camel case to be consistent with the label names used in
    // Scala.
    targetCluster: String,
    targetName: String,
    targetInstanceId: String,
    handlerLocation: HandlerLocation,
}

impl SerializedAssignmentSizeMetricLabels {
    pub fn new(handler_location: HandlerLocation, target: &Target) -> Self {
        let target_label = TargetMetricLabels::from_target(target);
        Self {
            targetCluster: target_label.targetCluster,
            targetName: target_label.targetName,
            targetInstanceId: target_label.targetInstanceId,
            handlerLocation: handler_location,
        }
    }
}

/// TEST-ONLY: A handle that blocks the [`SubscriberHandler`]'s executor until explicitly
/// unblocked. Used to simulate executor scheduling delay in tests. The blocker task is enqueued
/// via [`SequentialExecutor::execute`] with zero delay, so FIFO ordering guarantees that all
/// subsequent executor tasks are queued behind it.
pub struct ExecutorBlockerForTest {
    /// The sender to unblock the executor.
    unblock_tx: std::sync::mpsc::SyncSender<()>,
}

impl ExecutorBlockerForTest {
    /// Unblocks the executor, allowing queued tasks to proceed.
    pub fn unblock(self) {
        let _ = self.unblock_tx.send(());
    }
}

impl SubscriberHandler {
    /// TEST-ONLY: Schedules a task on the executor that blocks until explicitly unblocked.
    pub fn block_executor_for_test(&self) -> ExecutorBlockerForTest {
        let (unblock_tx, unblock_rx) = std::sync::mpsc::sync_channel::<()>(0);

        self.executor.execute(move |_executor, _inner| {
            let _ = unblock_rx.recv();
        });

        ExecutorBlockerForTest { unblock_tx }
    }
}

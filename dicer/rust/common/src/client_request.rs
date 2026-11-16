use std::sync::LazyLock;
use std::time::Duration;

use chrono::{DateTime, Utc};
use tonic::Status;
use caching_rust_util::kubernetes_cluster_uri::KubernetesClusterUri;
use caching_rust_util::prefix_logger::PrefixLogger;
use caching_rust_util::prefix_warn;
use caching_rust_util::hyper_log_log::HyperLogLog;
use caching_rust_util::protocol_duration::ProtocolDuration;
use caching_rust_util::region_uri::RegionUri;
use caching_rust_util::unwrap_ref_or_default::UnwrapRefOrDefault;
use dicer_common_proto_common_proto::dicer::client_request_p::slicelet_data_p::{
    load_distribution_p::CdfPointP,
    State as StateP,
    *,
};
use dicer_common_proto_common_proto::dicer::client_request_p::{
    ClerkDataP,
    ClientFeatureSupportP,
    SliceletDataP,
    SubscriberDataP,
};
use dicer_common_proto_common_proto::dicer::{ClientRequestP, TargetP};
use dicer_friend_proto_friend_proto_library::dicer::friend::{SliceP, SquidP};
use dicer_rust_external_types::target::{target_variant, AppTarget, TargetVariant};
use dicer_rust_external_types::{Slice, SliceKey, Target};
use dicer_rust_friend::Squid;
use dicer_rust_friend_external_slice_key_accessor::SliceKeyAccessor;

use crate::metric_measurement::validate_reported_measurement;
use crate::sync_assignment_state::SyncAssignmentState;
use crate::target_util::{app_target_to_proto, target_from_proto, target_to_proto};
use crate::version::{LATEST_VERSION, UNKNOWN_VERSION};
use crate::{slice_from_proto, slice_to_proto, Generation};

static LOGGER: LazyLock<PrefixLogger> = LazyLock::new(|| PrefixLogger::new("ClientRequest", ""));

/// The parsed and validated representation of [`ClientRequestP`].
#[derive(Debug, PartialEq)]
pub struct ClientRequest {
    target: Target,
    sync_assignment_state: SyncAssignmentState,
    subscriber_debug_name: String,
    timeout: ProtocolDuration,
    subscriber_data: SubscriberData,
    /// Indicates whether the client supports parsing serialized assignments. If true, the server
    /// may return a serialized assignment in response to this watch request.
    supports_serialized_assignment: bool,
    version: i64,
    /// See [`ClientRequestP::alternative_target`] in common.proto.
    alternative_target_opt: Option<AppTarget>,
    /// Sender pod's Kubernetes cluster IDM URI, if available from WhereAmI; region consistency
    /// with `region_uri_opt` is not enforced.
    // TODO(<internal bug>): Remove once DBNS replaces the Kubernetes API server termination-signal watch
    // and once cluster_uri_opt is no longer used to determine the cluster type of the sender for
    // the process of rejecting new Serverless Platform customers that use KubernetesTarget.
    cluster_uri_opt: Option<KubernetesClusterUri>,
    /// Sender pod's region IDM URI, if available from WhereAmI.
    region_uri_opt: Option<RegionUri>,
}

impl ClientRequest {
    /// Creates a new `ClientRequest` instance. [`LATEST_VERSION`] is used if `version_opt` is
    /// `None`.
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        target: Target,
        sync_assignment_state: SyncAssignmentState,
        subscriber_debug_name: &str,
        timeout: Duration,
        subscriber_data: SubscriberData,
        supports_serialized_assignment: bool,
        version_opt: Option<i64>,
        alternative_target_opt: Option<AppTarget>,
        cluster_uri_opt: Option<KubernetesClusterUri>,
        region_uri_opt: Option<RegionUri>,
    ) -> Result<Self, Status> {
        if timeout.as_millis() == 0 {
            return Err(Status::invalid_argument("Positive timeout value needed"));
        }
        let scala_compatible_timeout = ProtocolDuration::try_from(timeout)?;

        let subscriber_debug_name: String = subscriber_debug_name.to_string();
        if subscriber_debug_name.is_empty() {
            return Err(Status::invalid_argument("Subscriber debug name must not be empty"));
        }

        Ok(Self {
            target,
            sync_assignment_state,
            subscriber_debug_name,
            timeout: scala_compatible_timeout,
            subscriber_data,
            supports_serialized_assignment,
            version: version_opt.unwrap_or(LATEST_VERSION),
            alternative_target_opt,
            cluster_uri_opt,
            region_uri_opt,
        })
    }

    /// Converts to corresponding proto representation.
    pub fn to_proto(&self) -> ClientRequestP {
        let subscriber_data_proto: SubscriberDataP = match &self.subscriber_data {
            SubscriberData::Clerk => SubscriberDataP::ClerkFields(ClerkDataP {}),
            SubscriberData::Slicelet(slicelet_data) => {
                SubscriberDataP::SliceletFields(slicelet_data.to_proto())
            }
        };

        let timeout_millis: u128 = self.timeout.as_millis();
        let timeout_millis: i64 = timeout_millis.try_into().unwrap_or(i64::MAX);

        ClientRequestP {
            target: Some(target_to_proto(&self.target)),
            sync_assignment_state: Some(self.sync_assignment_state.to_proto()),
            subscriber_debug_name: Some(self.subscriber_debug_name.clone()),
            chosen_rpc_timeout_millis: Some(timeout_millis),
            version: Some(self.version),
            subscriber_data_p: Some(subscriber_data_proto),
            client_feature_support: Some(ClientFeatureSupportP {
                supports_serialized_assignment: Some(self.supports_serialized_assignment),
            }),
            redirect_token: None,
            alternative_target: self
                .alternative_target_opt
                .as_ref()
                .map(app_target_to_proto),
            cluster_uri: self.cluster_uri_opt.as_ref().map(|uri| uri.to_string()),
            region_uri: self.region_uri_opt.as_ref().map(|uri| uri.to_string()),
        }
    }

    /// Parses and validates the given client request proto.
    ///
    /// Returns an `INVALID_ARGUMENT` status if the proto is invalid, including if
    /// `alternative_target` is set but is not a valid `AppTarget`.
    pub fn from_proto(proto: &ClientRequestP) -> Result<ClientRequest, Status> {
        // Create the subscriber data depending on whether it is a Clerk or a Slicelet request.
        let subscriber_data: SubscriberData = match &proto.subscriber_data_p {
            Some(SubscriberDataP::ClerkFields(_)) => SubscriberData::Clerk,
            Some(SubscriberDataP::SliceletFields(proto)) => {
                SubscriberData::Slicelet(SliceletData::from_proto(proto)?)
            }
            None => {
                return Err(Status::invalid_argument(
                    "One of ClerkDataP or SliceletDataP must be defined",
                ))
            }
        };

        // Parse the target.
        let target_proto: &TargetP = proto
            .target
            .as_ref()
            .ok_or_else(|| Status::invalid_argument("Target must be defined in ClientRequestP"))?;
        let target: Target = target_from_proto(target_proto)?;

        // Parse the sync assignment state.
        let sync_assignment_state =
            SyncAssignmentState::from_proto(proto.sync_assignment_state.unwrap_ref_or_default())?;

        // Parse the timeout.
        let timeout_millis: i64 = proto.chosen_rpc_timeout_millis();
        let timeout_millis: u64 = timeout_millis
            .try_into()
            .map_err(|_| Status::invalid_argument("Positive timeout value needed"))?;

        // Zero duration will be validated in ClientRequest::new.
        let timeout = Duration::from_millis(timeout_millis);

        let supports_serialized_assignment: bool = proto
            .client_feature_support
            .as_ref()
            .and_then(|cfs| cfs.supports_serialized_assignment)
            .unwrap_or(false);

        let version: i64 = proto.version.unwrap_or(UNKNOWN_VERSION);

        let alternative_target_opt: Option<AppTarget> = proto
            .alternative_target
            .as_ref()
            .map(|alt_proto| {
                let alt_target: Target = target_from_proto(alt_proto).map_err(|e| {
                    Status::invalid_argument(format!(
                        "alternativeTarget is ill-formed: {}",
                        e.message()
                    ))
                })?;
                match target_variant(&alt_target) {
                    TargetVariant::App(app_target) => Ok(app_target.clone()),
                    TargetVariant::Kubernetes(k8s_target) => Err(Status::invalid_argument(format!(
                        "alternativeTarget must be an AppTarget; got a KubernetesTarget: {k8s_target:?}"
                    ))),
                }
            })
            .transpose()?;
        // An unrecognized cluster_uri / region_uri is dropped to None rather than rejecting the
        // request: these fields are advisory sender metadata, so an unrecognized value must not
        // fail an otherwise-valid watch.
        let cluster_uri_opt: Option<KubernetesClusterUri> =
            proto.cluster_uri.as_ref().and_then(|uri| {
                match KubernetesClusterUri::from_uri(uri.clone()) {
                    Ok(parsed) => Some(parsed),
                    Err(_) => {
                        prefix_warn!(
                            *LOGGER,
                            "Unrecognized clusterUri {uri} in request for {target} from subscriber \
                             {}.",
                            proto.subscriber_debug_name()
                        );
                        None
                    }
                }
            });
        let region_uri_opt: Option<RegionUri> =
            proto
                .region_uri
                .as_ref()
                .and_then(|uri| match RegionUri::from_uri(uri.clone()) {
                    Ok(parsed) => Some(parsed),
                    Err(_) => {
                        prefix_warn!(
                            *LOGGER,
                            "Unrecognized regionUri {uri} in request for {target} from subscriber \
                             {}.",
                            proto.subscriber_debug_name()
                        );
                        None
                    }
                });

        ClientRequest::new(
            target,
            sync_assignment_state,
            proto.subscriber_debug_name(),
            timeout,
            subscriber_data,
            supports_serialized_assignment,
            Some(version),
            alternative_target_opt,
            cluster_uri_opt,
            region_uri_opt,
        )
    }

    pub fn target(&self) -> &Target {
        &self.target
    }

    pub fn sync_assignment_state(&self) -> &SyncAssignmentState {
        &self.sync_assignment_state
    }

    pub fn subscriber_debug_name(&self) -> &str {
        &self.subscriber_debug_name
    }

    pub fn timeout(&self) -> Duration {
        self.timeout.into_duration()
    }

    pub fn subscriber_data(&self) -> &SubscriberData {
        &self.subscriber_data
    }

    pub fn supports_serialized_assignment(&self) -> bool {
        self.supports_serialized_assignment
    }

    pub fn version(&self) -> i64 {
        self.version
    }

    /// Returns the alternative target identifier associated with this request, if one was set.
    pub fn alternative_target_opt(&self) -> Option<&AppTarget> {
        self.alternative_target_opt.as_ref()
    }

    pub fn cluster_uri_opt(&self) -> Option<&KubernetesClusterUri> {
        self.cluster_uri_opt.as_ref()
    }

    pub fn region_uri_opt(&self) -> Option<&RegionUri> {
        self.region_uri_opt.as_ref()
    }

    pub fn known_generation(&self) -> Generation {
        self.sync_assignment_state.known_generation()
    }
}

/// An enum to represent either Clerk or Slicelet data.
#[allow(clippy::large_enum_variant)]
#[derive(Clone, Debug, PartialEq)]
pub enum SubscriberData {
    Clerk,
    Slicelet(SliceletData),
}

/// Extra Slicelet data sent in a client request.
#[derive(Clone, Debug, PartialEq)]
pub struct SliceletData {
    squid: Squid,
    state: StateP,
    kubernetes_namespace: String,
    attributed_loads: Vec<SliceLoad>,
    unattributed_load_opt: Option<SliceLoad>,
    key_cardinality_estimate_opt: Option<HyperLogLog>,
}

impl SliceletData {
    /// Creates a new `SliceletData` instance.
    pub fn new(
        squid: Squid,
        state: StateP,
        kubernetes_namespace: String,
        attributed_loads: Vec<SliceLoad>,
        unattributed_load_opt: Option<SliceLoad>,
        key_cardinality_estimate_opt: Option<HyperLogLog>,
    ) -> Self {
        Self {
            squid,
            state,
            kubernetes_namespace,
            attributed_loads,
            unattributed_load_opt,
            key_cardinality_estimate_opt,
        }
    }

    /// Converts to corresponding proto representation.
    pub fn to_proto(&self) -> SliceletDataP {
        let attributed_loads_proto: Vec<SliceLoadP> = self
            .attributed_loads
            .iter()
            .map(|load: &SliceLoad| load.to_proto())
            .collect();
        let unattributed_load_proto: Option<SliceLoadP> = self
            .unattributed_load_opt
            .as_ref()
            .map(|load: &SliceLoad| load.to_proto());

        SliceletDataP {
            nonce: None, // Deprecated field.
            uri: None,   // Deprecated field.
            state: Some(self.state as i32),
            squid: Some(self.squid.to_proto()),
            attributed_loads: attributed_loads_proto,
            unattributed_load: unattributed_load_proto,
            kubernetes_namespace: Some(self.kubernetes_namespace.clone()),
            surrenders: vec![],
            key_cardinality_estimate: self
                .key_cardinality_estimate_opt
                .as_ref()
                .map(HyperLogLog::to_proto),
        }
    }

    /// Parses and validates the given slicelet data proto.
    pub fn from_proto(proto: &SliceletDataP) -> Result<SliceletData, Status> {
        let squid_proto: &SquidP = proto
            .squid
            .as_ref()
            .ok_or_else(|| Status::invalid_argument("SliceletDataP must contain a squid field."))?;
        let squid: Squid = Squid::from_proto(squid_proto)?;

        let kubernetes_namespace: String = proto.kubernetes_namespace.clone().unwrap_or_default();

        // Parse attributed loads
        let attributed_loads: Vec<SliceLoad> = proto
            .attributed_loads
            .iter()
            .map(SliceLoad::from_proto)
            .collect::<Result<Vec<_>, _>>()?;

        // Parse unattributed load if present
        let unattributed_load_opt: Option<SliceLoad> = proto
            .unattributed_load
            .as_ref()
            .map(SliceLoad::from_proto)
            .transpose()?;

        Ok(SliceletData {
            squid,
            state: proto.state(),
            kubernetes_namespace,
            attributed_loads,
            unattributed_load_opt,
            key_cardinality_estimate_opt: proto
                .key_cardinality_estimate
                .as_ref()
                .and_then(|hll_pb| HyperLogLog::from_proto(hll_pb).ok()),
        })
    }

    pub fn squid(&self) -> &Squid {
        &self.squid
    }

    pub fn attributed_loads(&self) -> &[SliceLoad] {
        &self.attributed_loads
    }

    pub fn unattributed_load_opt(&self) -> Option<&SliceLoad> {
        self.unattributed_load_opt.as_ref()
    }
}

/// Aggregate load measurements over some time window and Slice.
#[derive(Clone, Debug, PartialEq)]
pub struct SliceLoad {
    primary_rate_load: f64,
    window_low_inclusive: DateTime<Utc>,
    window_high_exclusive: DateTime<Utc>,
    slice: Slice,
    top_keys: Vec<KeyLoad>,
    num_replicas: i32,
    load_distribution_opt: Option<LoadDistribution>,
}

impl SliceLoad {
    /// The earliest and latest allowed window timestamps, in seconds since the Unix epoch (1970
    /// through 2106). They are close enough together that the gap between any two window
    /// timestamps, or between one and the Assigner's clock, fits in a duration counted in
    /// nanoseconds.
    pub const MIN_WINDOW_EPOCH_SECONDS: i64 = 0;
    pub const MAX_WINDOW_EPOCH_SECONDS: i64 = 1 << 32;

    /// Creates a new SliceLoad instance, see [`SliceLoad::validate`] for the requirements on
    /// the parameters.
    pub fn new(
        primary_rate_load: f64,
        window_low_inclusive: DateTime<Utc>,
        window_high_exclusive: DateTime<Utc>,
        slice: Slice,
        top_keys: Vec<KeyLoad>,
        num_replicas: i32,
        load_distribution_opt: Option<LoadDistribution>,
    ) -> Result<Self, Status> {
        Self::validate(
            primary_rate_load,
            &top_keys,
            window_low_inclusive,
            window_high_exclusive,
            &slice,
            num_replicas,
            &load_distribution_opt,
        )?;

        Ok(Self {
            primary_rate_load,
            window_low_inclusive,
            window_high_exclusive,
            slice,
            top_keys,
            num_replicas,
            load_distribution_opt,
        })
    }

    /// Converts to corresponding proto representation.
    fn to_proto(&self) -> SliceLoadP {
        SliceLoadP {
            primary_rate_load: Some(self.primary_rate_load),
            window_low_inclusive_seconds: Some(self.window_low_inclusive.timestamp()),
            window_high_exclusive_seconds: Some(self.window_high_exclusive.timestamp()),
            slice: Some(slice_to_proto(self.slice.low_inclusive(), self.slice.high_exclusive())),
            top_keys: self.top_keys.iter().map(KeyLoad::to_proto).collect(),
            num_replicas: Some(self.num_replicas),
            load_distribution: self
                .load_distribution_opt
                .as_ref()
                .map(LoadDistribution::to_proto),
        }
    }

    // Filters to only keep the top keys that are above the threshold.
    pub fn filter_keys_above_threshold(&mut self, threshold: f64) {
        self.top_keys
            .retain(|key_load| key_load.underestimated_primary_rate_load >= threshold);
    }

    /// Parses and validates the given slice load proto.
    fn from_proto(proto: &SliceLoadP) -> Result<SliceLoad, Status> {
        let primary_rate_load: f64 = proto.primary_rate_load.unwrap_or(0.0);

        let window_low_inclusive_seconds: i64 = proto.window_low_inclusive_seconds.unwrap_or(0);
        let window_high_exclusive_seconds: i64 = proto.window_high_exclusive_seconds.unwrap_or(0);
        // Check the seconds before creating `DateTime`s, which can't hold very large values.
        Self::validate_window_timestamp(window_low_inclusive_seconds)?;
        Self::validate_window_timestamp(window_high_exclusive_seconds)?;
        let window_low_inclusive: DateTime<Utc> =
            DateTime::from_timestamp(window_low_inclusive_seconds, 0)
                .ok_or_else(|| Status::invalid_argument("Invalid window_low_inclusive_seconds"))?;
        let window_high_exclusive: DateTime<Utc> =
            DateTime::from_timestamp(window_high_exclusive_seconds, 0)
                .ok_or_else(|| Status::invalid_argument("Invalid window_high_exclusive_seconds"))?;

        let slice: Slice = match &proto.slice {
            Some(slice_proto) => slice_from_proto(slice_proto)?,
            None => slice_from_proto(&SliceP::default())?,
        };

        let top_keys: Vec<KeyLoad> = proto
            .top_keys
            .iter()
            .map(KeyLoad::from_proto)
            .collect::<Result<_, _>>()?;

        // For backward compatibility, if the `num_replicas` field is not defined in the SliceLoadP
        // (e.g. when the SliceLoadP is reported by some Slicelets in stale versions), we set the
        // value of this field to 1 by default in the returned `SliceLoad` struct, rather than
        // failing the from_proto() method.
        let num_replicas: i32 = proto.num_replicas.unwrap_or(1);

        let load_distribution_opt: Option<LoadDistribution> = proto
            .load_distribution
            .as_ref()
            .map(LoadDistribution::from_proto)
            .transpose()?;

        SliceLoad::new(
            primary_rate_load,
            window_low_inclusive,
            window_high_exclusive,
            slice,
            top_keys,
            num_replicas,
            load_distribution_opt,
        )
    }

    /// Validates the parameters of the `SliceLoad`.
    fn validate(
        primary_rate_load: f64,
        top_keys: &Vec<KeyLoad>,
        window_low_inclusive: DateTime<Utc>,
        window_high_exclusive: DateTime<Utc>,
        slice: &Slice,
        num_replicas: i32,
        load_distribution_opt: &Option<LoadDistribution>,
    ) -> Result<(), Status> {
        // Ensure that the primary rate load is valid.
        validate_reported_measurement(primary_rate_load)?;

        // Ensure that the window timestamps are between the allowed earliest and latest values.
        Self::validate_window_timestamp(window_low_inclusive.timestamp())?;
        Self::validate_window_timestamp(window_high_exclusive.timestamp())?;

        // Ensure that the window high is not less than the window low.
        if window_high_exclusive < window_low_inclusive {
            return Err(Status::invalid_argument(format!(
                "High exclusive time must be >= low inclusive time: {} < {}.",
                window_high_exclusive, window_low_inclusive
            )));
        }

        // Ensure that every top key is within the slice.
        for key_load in top_keys {
            if !slice.contains_key(&key_load.key) {
                return Err(Status::invalid_argument(format!(
                    "Top key {:?} must be contained in slice {:?}.",
                    key_load.key, slice
                )));
            }
        }

        // Ensure that the load distribution's point keys are within the slice. Points are strictly
        // ascending by key, so checking the first and last suffices.
        if let Some(distribution) = load_distribution_opt
            && !distribution.points.is_empty()
        {
            let first = distribution.points.first().unwrap();
            let last = distribution.points.last().unwrap();
            if !slice.contains_key(first.key()) || !slice.contains_key(last.key()) {
                return Err(Status::invalid_argument(format!(
                    "Load distribution keys {:?} and {:?} must be contained in slice {:?}.",
                    first.key(),
                    last.key(),
                    slice
                )));
            }
        }

        // Ensure that the number of replicas is positive.
        if num_replicas <= 0 {
            return Err(Status::invalid_argument(format!(
                "Number of replicas must be positive: {}",
                num_replicas
            )));
        }
        Ok(())
    }

    /// Checks that `epoch_seconds` is between [`SliceLoad::MIN_WINDOW_EPOCH_SECONDS`] and
    /// [`SliceLoad::MAX_WINDOW_EPOCH_SECONDS`].
    fn validate_window_timestamp(epoch_seconds: i64) -> Result<(), Status> {
        if !(Self::MIN_WINDOW_EPOCH_SECONDS..=Self::MAX_WINDOW_EPOCH_SECONDS)
            .contains(&epoch_seconds)
        {
            return Err(Status::invalid_argument(format!(
                "Window timestamp must be within [{}, {}] seconds since the epoch: {}.",
                Self::MIN_WINDOW_EPOCH_SECONDS,
                Self::MAX_WINDOW_EPOCH_SECONDS,
                epoch_seconds
            )));
        }
        Ok(())
    }

    /// Gets the primary rate load (e.g., time-weighted mean of QPS). "Primary" to distinguish
    /// from the (as yet unsupported) other load measurements. "Rate" to distinguish from
    /// (as yet unsupported) gauge measurements (e.g., memory usage) which must be provided
    /// through synthesized incremental rate updates (e.g., incrementing memory usage by 1MiB
    /// every second to represent a 1MiB memory footprint). This includes the load from `top_keys`.
    pub fn primary_rate_load(&self) -> f64 {
        self.primary_rate_load
    }

    /// Gets the inclusive start of the time window over which the load measurement are aggregated.
    pub fn window_low_inclusive(&self) -> DateTime<Utc> {
        self.window_low_inclusive
    }

    /// Gets the exclusive end of the time window over which the load measurement are aggregated.
    pub fn window_high_exclusive(&self) -> DateTime<Utc> {
        self.window_high_exclusive
    }

    /// Gets the range of keys to which the load measurement applies.
    pub fn slice(&self) -> &Slice {
        &self.slice
    }

    /// Gets the keys within this slice that have the highest estimate load.
    pub fn top_keys(&self) -> &[KeyLoad] {
        &self.top_keys
    }

    /// Gets the number of replicas of `slice` known by the Slicelet when generating this slice
    /// load.
    pub fn num_replicas(&self) -> i32 {
        self.num_replicas
    }

    /// Gets the approximate distribution of load across keys within `slice`, or `None` when the
    /// Slicelet did not report one.
    pub fn load_distribution_opt(&self) -> Option<&LoadDistribution> {
        self.load_distribution_opt.as_ref()
    }
}

/// Load measurement for a single key.
#[derive(Clone, Debug, PartialEq)]
pub struct KeyLoad {
    /// The key for which this load measurement applies.
    key: SliceKey,

    /// Estimated primary load for this particular key. Note this should be an underestimate,
    /// i.e. the real time-weighted load is greater than or equal to this value. Must be a
    /// non-negative number no larger than [`MAX_REPORTED_MEASUREMENT`](crate::MAX_REPORTED_MEASUREMENT).
    underestimated_primary_rate_load: f64,
}

impl KeyLoad {
    /// Creates a new KeyLoad instance with the given key and load measurement.
    pub fn new(key: SliceKey, underestimated_primary_rate_load: f64) -> Result<Self, Status> {
        validate_reported_measurement(underestimated_primary_rate_load)?;
        Ok(KeyLoad {
            key,
            underestimated_primary_rate_load,
        })
    }

    /// Converts to corresponding proto representation.
    fn to_proto(&self) -> KeyLoadP {
        KeyLoadP {
            slice_key: Some(self.key.to_raw_bytes().to_vec()),
            underestimated_primary_rate_load: Some(self.underestimated_primary_rate_load),
        }
    }

    /// Parses and validates the given key load proto.
    fn from_proto(proto: &KeyLoadP) -> Result<KeyLoad, Status> {
        let key_bytes = proto
            .slice_key
            .as_ref()
            .ok_or_else(|| Status::invalid_argument("KeyLoadP must have a slice key field."))?;
        let slice_key: SliceKey = SliceKey::from_raw_bytes(key_bytes);
        let load: f64 = proto.underestimated_primary_rate_load.unwrap_or(0.0);

        KeyLoad::new(slice_key, load)
    }

    pub fn key(&self) -> &SliceKey {
        &self.key
    }

    pub fn underestimated_primary_rate_load(&self) -> f64 {
        self.underestimated_primary_rate_load
    }
}

/// Approximate distribution of load across keys within a Slice.
#[derive(Clone, Debug, PartialEq)]
pub struct LoadDistribution {
    /// Samples of the load CDF, ordered by `key` ascending. Empty when no distribution has been
    /// reported for the Slice; consumers may assume that the load is evenly distributed across the
    /// Slice.
    points: Vec<CdfPoint>,

    /// The maximum error in any point's `cumulative_load_fraction`, as a fraction of the Slice's
    /// total load.
    max_error_fraction: f64,
}

impl LoadDistribution {
    /// Creates a new LoadDistribution instance, see [`LoadDistribution::validate`] for the
    /// requirements on the parameters.
    pub fn new(points: Vec<CdfPoint>, max_error_fraction: f64) -> Result<Self, Status> {
        Self::validate(&points, max_error_fraction)?;
        Ok(Self {
            points,
            max_error_fraction,
        })
    }

    /// Converts to corresponding proto representation.
    fn to_proto(&self) -> LoadDistributionP {
        LoadDistributionP {
            points: self.points.iter().map(CdfPoint::to_proto).collect(),
            max_error_fraction: Some(self.max_error_fraction),
        }
    }

    /// Parses and validates the given load distribution proto.
    fn from_proto(proto: &LoadDistributionP) -> Result<LoadDistribution, Status> {
        let max_error_fraction: f64 = proto.max_error_fraction.ok_or_else(|| {
            Status::invalid_argument("LoadDistributionP must have a max_error_fraction")
        })?;
        let points: Vec<CdfPoint> = proto
            .points
            .iter()
            .map(CdfPoint::from_proto)
            .collect::<Result<Vec<CdfPoint>, Status>>()?;

        LoadDistribution::new(points, max_error_fraction)
    }

    /// Validates the parameters of the `LoadDistribution`:
    /// 1. `max_error_fraction` must be in the half-open interval [0, 1).
    /// 2. `points` must be strictly ascending by `key` (ordered, with unique keys) and
    ///    `cumulative_load_fraction` must be non-decreasing.
    fn validate(points: &[CdfPoint], max_error_fraction: f64) -> Result<(), Status> {
        if !(0.0..1.0).contains(&max_error_fraction) {
            return Err(Status::invalid_argument(format!(
                "max_error_fraction must be in [0, 1): {}",
                max_error_fraction
            )));
        }

        for pair in points.windows(2) {
            let prev: &CdfPoint = &pair[0];
            let next: &CdfPoint = &pair[1];
            if prev.key >= next.key {
                return Err(Status::invalid_argument(
                    "points must be strictly ascending by key (ordered, with unique keys)",
                ));
            }
            if prev.cumulative_load_fraction > next.cumulative_load_fraction {
                return Err(Status::invalid_argument(
                    "cumulative_load_fraction must be non-decreasing across points",
                ));
            }
        }
        Ok(())
    }

    /// Gets the samples of the load CDF, ordered by `key` ascending.
    pub fn points(&self) -> &[CdfPoint] {
        &self.points
    }

    /// Gets the maximum error in any point's `cumulative_load_fraction`, as a fraction of the
    /// Slice's total load.
    pub fn max_error_fraction(&self) -> f64 {
        self.max_error_fraction
    }
}

/// A single point on the load CDF: `cumulative_load_fraction` (the quantile) is the fraction of
/// the Slice's total load attributed to keys at or below `key`.
#[derive(Clone, Debug, PartialEq)]
pub struct CdfPoint {
    /// A key within the Slice's key space.
    key: SliceKey,

    /// The cumulative fraction of the Slice's load attributed to keys at or below `key`, in
    /// [0, 1].
    cumulative_load_fraction: f64,
}

impl CdfPoint {
    /// Creates a new CdfPoint instance.
    /// Requires `cumulative_load_fraction` to be in the closed interval [0, 1].
    pub fn new(key: SliceKey, cumulative_load_fraction: f64) -> Result<Self, Status> {
        if !(0.0..=1.0).contains(&cumulative_load_fraction) {
            return Err(Status::invalid_argument(format!(
                "cumulative_load_fraction must be in [0, 1]: {}",
                cumulative_load_fraction
            )));
        }
        Ok(Self {
            key,
            cumulative_load_fraction,
        })
    }

    /// Converts to corresponding proto representation.
    fn to_proto(&self) -> CdfPointP {
        CdfPointP {
            key: Some(self.key.to_raw_bytes().to_vec()),
            cumulative_load_fraction: Some(self.cumulative_load_fraction),
        }
    }

    /// Parses and validates the given point proto.
    fn from_proto(proto: &CdfPointP) -> Result<CdfPoint, Status> {
        let key_bytes = proto
            .key
            .as_ref()
            .ok_or_else(|| Status::invalid_argument("CdfPointP must have a key"))?;
        let key: SliceKey = SliceKey::from_raw_bytes(key_bytes);
        let cumulative_load_fraction: f64 = proto.cumulative_load_fraction.ok_or_else(|| {
            Status::invalid_argument("CdfPointP must have a cumulative_load_fraction")
        })?;

        CdfPoint::new(key, cumulative_load_fraction)
    }

    /// Gets the key within the Slice's key space.
    pub fn key(&self) -> &SliceKey {
        &self.key
    }

    /// Gets the cumulative fraction of the Slice's load attributed to keys at or below `key`.
    pub fn cumulative_load_fraction(&self) -> f64 {
        self.cumulative_load_fraction
    }
}

/// Test-only function to create a [`SliceLoad`] from proto.
pub fn slice_load_from_proto_for_test(proto: &SliceLoadP) -> Result<SliceLoad, Status> {
    SliceLoad::from_proto(proto)
}

/// Test-only function to convert a [`SliceLoad`] to proto.
pub fn slice_load_to_proto_for_test(slice_load: &SliceLoad) -> SliceLoadP {
    slice_load.to_proto()
}

/// Test-only function to create a [`KeyLoad`] from proto.
pub fn key_load_from_proto_for_test(proto: &KeyLoadP) -> Result<KeyLoad, Status> {
    KeyLoad::from_proto(proto)
}

/// Test-only function to convert a [`KeyLoad`] to proto.
pub fn key_load_to_proto_for_test(key_load: &KeyLoad) -> KeyLoadP {
    key_load.to_proto()
}

/// Test-only function to create a [`LoadDistribution`] from proto.
pub fn load_distribution_from_proto_for_test(
    proto: &LoadDistributionP,
) -> Result<LoadDistribution, Status> {
    LoadDistribution::from_proto(proto)
}

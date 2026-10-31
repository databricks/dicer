/// Teams responsible for handling errors from client services. Values must match the team names
/// used to generate alerts.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Hash)]
pub enum AlertOwnerTeam {
    CachingTeam,
    /// For teams not represented by a named variant. Pass the team's alert routing name directly
    /// (e.g. `"eng-my-team"`).
    Custom(&'static str),
}

impl AlertOwnerTeam {
    pub const fn as_str(self) -> &'static str {
        match self {
            AlertOwnerTeam::CachingTeam => "platform-team",
            AlertOwnerTeam::Custom(name) => name,
        }
    }
}

/// An error code with an associated owner team. The `const fn` constructor accepts only
/// `&'static str`, which strongly encourages construction with string literals and compile-time
/// constants, preventing high-cardinality metrics from dynamic strings.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Hash)]
pub struct CachingErrorCode {
    error_code: &'static str,
    owner_team: AlertOwnerTeam,
}

impl CachingErrorCode {
    pub const fn new(error_code: &'static str, owner_team: AlertOwnerTeam) -> Self {
        Self {
            error_code,
            owner_team,
        }
    }

    pub const fn error_code(&self) -> &'static str {
        self.error_code
    }

    pub const fn owner_team(&self) -> AlertOwnerTeam {
        self.owner_team
    }
}

/// Severity of the error. Firing [`prefix_degraded_alert!`] or [`prefix_critical_alert!`]
/// increments a metric for which we have defined alerts in Caching team services.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Hash)]
pub enum Severity {
    /// System will continue to work albeit in degraded state. Pages SEV1 (business hours only).
    Degraded,

    /// An invariant is violated and the system may not be working correctly. Pages SEV0 (all
    /// hours).
    Critical,
}

// ---------------------------------------------------------------------------
// Error codes.
//
// Fixed-team codes are `const`. Dynamic-team codes (where the owning team is
// determined at construction time) are factory functions.
// ---------------------------------------------------------------------------

/// A panic caught during a `WatchValueCellPollAdapter` poll iteration.
pub const WATCH_VALUE_CELL_POLL_ADAPTER_PANIC: CachingErrorCode =
    CachingErrorCode::new("WATCH_VALUE_CELL_POLL_ADAPTER_PANIC", AlertOwnerTeam::CachingTeam);

/// A `QuantileSummaries` had to break its accuracy invariant to stay under the memory constraint.
/// When this happens, we need to check with the customer on their load pattern, adjust the memory
/// constraint or relative error of `QuantileSummaries`.
///
/// This condition is typically triggered by some pathological SliceKey access pattern. After the
/// pattern ends, the alert should be able to recover by its own.
pub const QUANTILE_SUMMARIES_ACCURACY_INVARIANT_BROKEN: CachingErrorCode = CachingErrorCode::new(
    "quantile_summaries_accuracy_invariant_broken",
    AlertOwnerTeam::CachingTeam,
);

// -- Dicer client errors --

/// Unexpected internal error during assignment syncing in the Rust `AssignmentSyncStateMachine`.
pub const RUST_ASSIGNMENT_SYNC_STATE_MACHINE_UNEXPECTED_ERROR: CachingErrorCode =
    CachingErrorCode::new(
        "RUST_ASSIGNMENT_SYNC_STATE_MACHINE_UNEXPECTED_ERROR",
        AlertOwnerTeam::CachingTeam,
    );

/// Indicates an internal invariant violation in
/// [com.databricks.dicer.client.AssignmentSyncStateMachine.ReadScheduler]. This may cause watch
/// requests to be sent more or less frequently than expected.
pub const DICER_CLIENT_READ_SCHEDULER_ERROR: CachingErrorCode =
    CachingErrorCode::new("DICER_CLIENT_READ_SCHEDULER_ERROR", AlertOwnerTeam::CachingTeam);

/// Unexpected internal error in the Rust `SliceLookup`.
pub const RUST_SLICE_LOOKUP_UNEXPECTED_ERROR: CachingErrorCode =
    CachingErrorCode::new("RUST_SLICE_LOOKUP_UNEXPECTED_ERROR", AlertOwnerTeam::CachingTeam);

/// The `SubscriberHandler` received an assignment that is older than its cached assignment.
/// It will continue using the newer cached assignment, but this indicates an issue with
/// assignment distribution.
pub const SUBSCRIBER_HANDLER_ASSIGNMENT_CACHE_AHEAD_OF_SOURCE: CachingErrorCode =
    CachingErrorCode::new(
        "SUBSCRIBER_HANDLER_ASSIGNMENT_CACHE_AHEAD_OF_SOURCE",
        AlertOwnerTeam::CachingTeam,
    );

/// An unexpected internal error in the `SubscriberHandler` that does not correspond to a
/// specific known condition.
pub const RUST_SUBSCRIBER_HANDLER_INTERNAL_ERROR: CachingErrorCode =
    CachingErrorCode::new("RUST_SUBSCRIBER_HANDLER_INTERNAL_ERROR", AlertOwnerTeam::CachingTeam);

/// A watch response distributed by the `SubscriberHandler` is near or exceeding the maximum content
/// length limit clients enforce. Responses exceeding the limit cause clients to receive
/// RESOURCE_EXHAUSTED errors and fail to receive assignments.
pub const SUBSCRIBER_HANDLER_WATCH_RESPONSE_NEAR_OR_EXCEEDING_LIMIT: CachingErrorCode =
    CachingErrorCode::new(
        "SUBSCRIBER_HANDLER_WATCH_RESPONSE_NEAR_OR_EXCEEDING_LIMIT",
        AlertOwnerTeam::CachingTeam,
    );

/// Unexpected internal error during load reporting in the Rust `SliceletLoadAccumulator`.
pub const RUST_SLICELET_LOAD_ACCUMULATOR_UNEXPECTED_ERROR: CachingErrorCode = CachingErrorCode::new(
    "RUST_SLICELET_LOAD_ACCUMULATOR_UNEXPECTED_ERROR",
    AlertOwnerTeam::CachingTeam,
);

/// A key exceeding `max_key_length_bytes` was reported to the Rust `SliceletLoadAccumulator`. The
/// key's load still counts toward its Slice's total load, but the key is omitted from top keys and
/// the load distribution, which may degrade Dicer's split and load balancing decisions.
pub const RUST_SLICELET_LOAD_ACCUMULATOR_KEY_TOO_LONG: CachingErrorCode = CachingErrorCode::new(
    "RUST_SLICELET_LOAD_ACCUMULATOR_KEY_TOO_LONG",
    AlertOwnerTeam::CachingTeam,
);

/// Unexpected dpage registration error in Dicer client. Clerk/Slicelet functionality continues,
/// but the dpage may not be available for debugging. Should be investigated by checking the logs.
pub const RUST_DICER_CLIENT_DPAGE_REGISTRATION_ERROR: CachingErrorCode = CachingErrorCode::new(
    "RUST_DICER_CLIENT_DPAGE_REGISTRATION_ERROR",
    AlertOwnerTeam::CachingTeam,
);

/// `DicerClientFeatureRolloutFlag` could not resolve the current region URI from WhereAmI at
/// process initialization, so every `is_enabled` call will return false for the lifetime of the
/// process.
pub const DICER_CLIENT_FEATURE_ROLLOUT_REGION_URI_UNAVAILABLE: CachingErrorCode =
    CachingErrorCode::new(
        "DICER_CLIENT_FEATURE_ROLLOUT_REGION_URI_UNAVAILABLE",
        AlertOwnerTeam::CachingTeam,
    );

/// `DicerClientFeatureRolloutFlag` could not resolve the deployment environment (dev/staging/prod)
/// from WhereAmI at process initialization, so no configurations are loaded and every
/// `is_enabled` call will return false for the lifetime of the process.
pub const DICER_CLIENT_FEATURE_ROLLOUT_ENV_UNAVAILABLE: CachingErrorCode = CachingErrorCode::new(
    "DICER_CLIENT_FEATURE_ROLLOUT_ENV_UNAVAILABLE",
    AlertOwnerTeam::CachingTeam,
);

/// `DicerClientFeatureRolloutFlag::is_enabled` was called with a feature name that does not match
/// any feature loaded for the current deployment environment, indicating either a typo at the call
/// site or a missing config file.
pub const DICER_CLIENT_FEATURE_ROLLOUT_FLAG_NOT_FOUND: CachingErrorCode = CachingErrorCode::new(
    "DICER_CLIENT_FEATURE_ROLLOUT_FLAG_NOT_FOUND",
    AlertOwnerTeam::CachingTeam,
);

// -- State machine / SEC pool errors (dynamic team) --

/// An uncaught panic in `StateMachine::advance`.
pub const fn state_machine_advance_panic(owner_team: AlertOwnerTeam) -> CachingErrorCode {
    CachingErrorCode::new("STATE_MACHINE_ADVANCE_PANIC", owner_team)
}

/// An uncaught panic in the action handler of a `StateMachineDriver`.
pub const fn state_machine_action_handler_panic(owner_team: AlertOwnerTeam) -> CachingErrorCode {
    CachingErrorCode::new("STATE_MACHINE_ACTION_HANDLER_PANIC", owner_team)
}

use std::time::Duration;

use tonic::Status;
use caching_rust_util::protocol_duration::ProtocolDuration;
use caching_rust_util::unwrap_ref_or_default::UnwrapRefOrDefault;
use dicer_common_proto_common_proto::dicer::{ClientResponseP, SyncAssignmentStateP};

use crate::redirect::Redirect;
use crate::sync_assignment_state::SyncAssignmentState;
use crate::MIN_WATCH_RPC_TIMEOUT;

/// The parsed and validated representation of [`ClientResponseP`].
#[derive(Debug, PartialEq)]
pub struct ClientResponse {
    sync_state: SyncAssignmentState,
    suggested_rpc_timeout: ProtocolDuration,
    redirect: Redirect,
}

impl ClientResponse {
    pub fn new(
        sync_state: SyncAssignmentState,
        suggested_rpc_timeout: ProtocolDuration,
        redirect: Redirect,
    ) -> Self {
        Self {
            sync_state,
            suggested_rpc_timeout,
            redirect,
        }
    }

    /// See [`ClientResponseP::sync_assignment_state`].
    pub fn sync_state(&self) -> &SyncAssignmentState {
        &self.sync_state
    }

    /// Consumes the response and returns the sync state by ownership.
    pub fn into_sync_state(self) -> SyncAssignmentState {
        self.sync_state
    }

    /// See [`ClientResponseP::suggested_rpc_timeout_millis`].
    pub fn suggested_rpc_timeout(&self) -> Duration {
        self.suggested_rpc_timeout.into_duration()
    }

    /// See [`ClientResponseP::redirect`].
    pub fn redirect(&self) -> &Redirect {
        &self.redirect
    }

    /// Parses and validates the given client response proto.
    pub fn from_proto(proto: &ClientResponseP) -> Result<ClientResponse, Status> {
        // Parse the sync state.
        let sync_state_proto: &SyncAssignmentStateP =
            proto.sync_assignment_state.unwrap_ref_or_default();
        let sync_state = SyncAssignmentState::from_proto(sync_state_proto)?;

        // Parse the suggested RPC timeout.
        let timeout_millis: i64 = proto.suggested_rpc_timeout_millis.unwrap_or_default();
        let timeout_millis: u64 = timeout_millis.try_into().map_err(|_| {
            Status::invalid_argument(format!(
                "suggested_rpc_timeout_millis outside of supported range: {timeout_millis}"
            ))
        })?;
        let suggested_rpc_timeout = Duration::from_millis(timeout_millis);
        if suggested_rpc_timeout < MIN_WATCH_RPC_TIMEOUT {
            return Err(Status::invalid_argument(format!(
                "Watch RPC timeout must be at least {MIN_WATCH_RPC_TIMEOUT:?}: \
                 {suggested_rpc_timeout:?}"
            )));
        }
        let suggested_rpc_timeout = ProtocolDuration::try_from(suggested_rpc_timeout)?;

        // Parse the redirect.
        let redirect: Redirect = Redirect::from_proto(proto.redirect.unwrap_ref_or_default())?;

        Ok(ClientResponse {
            sync_state,
            suggested_rpc_timeout,
            redirect,
        })
    }

    /// Converts to corresponding proto representation.
    pub fn to_proto(&self) -> ClientResponseP {
        let sync_state_proto: SyncAssignmentStateP = self.sync_state.to_proto();
        Self::create_proto_with_sync_state_p(
            sync_state_proto,
            self.suggested_rpc_timeout,
            &self.redirect,
        )
    }

    /// Creates a [`ClientResponseP`] using the given pre-built [`SyncAssignmentStateP`].
    pub fn create_proto_with_sync_state_p(
        sync_state_proto: SyncAssignmentStateP,
        suggested_rpc_timeout: ProtocolDuration,
        redirect: &Redirect,
    ) -> ClientResponseP {
        let timeout_millis: u128 = suggested_rpc_timeout.as_millis();
        let suggested_rpc_timeout_millis: i64 = timeout_millis
            .try_into()
            .unwrap_or(ProtocolDuration::max_duration_millis());

        ClientResponseP {
            sync_assignment_state: Some(sync_state_proto),
            suggested_rpc_timeout_millis: Some(suggested_rpc_timeout_millis),
            redirect: Some(redirect.to_proto()),
        }
    }
}

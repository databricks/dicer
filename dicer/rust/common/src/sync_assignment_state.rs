use prost::Message;
use tonic::Status;
use dicer_common_proto_common_proto::dicer::sync_assignment_state_p::State;
use dicer_common_proto_common_proto::dicer::{DiffAssignmentP, SyncAssignmentStateP};

use crate::diff_assignment::DiffAssignment;
use crate::Generation;

/// The parsed and validated representation of [`SyncAssignmentStateP`].
#[derive(Debug, PartialEq)]
pub enum SyncAssignmentState {
    /// Generation of the latest assignment known to the sender. This case is used when the sender
    /// believes the remote server knows of an assignment with a higher generation or may learn of
    /// one before the sender.
    KnownGeneration(Generation),

    /// The latest assignment known to the sender. This case is used when the sender believes the
    /// remote server has no assignment or an assignment with a generation that is less than this
    /// diff assignment's generation.
    KnownAssignment(DiffAssignment),
}

impl SyncAssignmentState {
    /// Returns the known generation specified in this sync state, which may come either from the
    /// `known_assignment` field or the `known_generation` field.
    pub fn known_generation(&self) -> Generation {
        match self {
            SyncAssignmentState::KnownGeneration(generation) => *generation,
            SyncAssignmentState::KnownAssignment(assignment) => assignment.generation(),
        }
    }

    /// Parses and validates the given sync state proto.
    pub(crate) fn from_proto(proto: &SyncAssignmentStateP) -> Result<SyncAssignmentState, Status> {
        match &proto.state {
            Some(State::KnownAssignment(diff_assignment_proto)) => {
                let diff_assignment = DiffAssignment::from_proto(diff_assignment_proto)?;
                Ok(SyncAssignmentState::KnownAssignment(diff_assignment))
            }
            Some(State::KnownGeneration(generation_proto)) => {
                let generation = Generation::from_proto(generation_proto)?;
                Ok(SyncAssignmentState::KnownGeneration(generation))
            }
            Some(State::KnownSerializedAssignment(serialized_assignment)) => {
                let diff_assignment_proto: DiffAssignmentP =
                    DiffAssignmentP::decode(serialized_assignment.as_slice()).map_err(|e| {
                        Status::invalid_argument(format!(
                            "Failed to parse DiffAssignmentP from KnownSerializedAssignment: {e}"
                        ))
                    })?;
                let diff_assignment = DiffAssignment::from_proto(&diff_assignment_proto)?;
                Ok(SyncAssignmentState::KnownAssignment(diff_assignment))
            }
            None => Err(Status::invalid_argument("SyncAssignmentStateP state must not be empty")),
        }
    }

    /// Returns the proto representation of this sync state.
    pub fn to_proto(&self) -> SyncAssignmentStateP {
        match self {
            SyncAssignmentState::KnownGeneration(generation) => SyncAssignmentStateP {
                state: Some(State::KnownGeneration(generation.to_proto())),
            },
            SyncAssignmentState::KnownAssignment(diff_assignment) => SyncAssignmentStateP {
                state: Some(State::KnownAssignment(diff_assignment.to_proto())),
            },
        }
    }

    /// Returns both the structured [`SyncAssignmentStateP::State::KnownAssignment`] and serialized
    /// [`SyncAssignmentStateP::State::KnownSerializedAssignment`] [`SyncAssignmentStateP`] proto
    /// representations of the given [`DiffAssignment`], so that they can be cached and reused for
    /// multiple clients.
    pub fn known_assignment_to_cached_protos(
        diff_assignment: DiffAssignment,
    ) -> (SyncAssignmentStateP, SyncAssignmentStateP) {
        let diff_proto: DiffAssignmentP = diff_assignment.to_proto();
        let serialized_bytes: Vec<u8> = diff_proto.encode_to_vec();
        let structured = SyncAssignmentStateP {
            state: Some(State::KnownAssignment(diff_proto)),
        };
        let serialized = SyncAssignmentStateP {
            state: Some(State::KnownSerializedAssignment(serialized_bytes)),
        };
        (structured, serialized)
    }
}

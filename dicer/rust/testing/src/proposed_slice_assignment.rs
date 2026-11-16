use tonic::Status;
use dicer_rust_common::validate_measurement;
use dicer_rust_external_types::Slice;
use dicer_rust_friend::Squid;

/// A proposed slice assignment for building test assignments.
///
/// This type can be used by test utilities (like create_random_proposal) that need to
/// construct assignments programmatically. For building assignments from Slicelets,
/// use [`TestAssignmentBuilder`](crate::test_assignment_builder::TestAssignmentBuilder) instead.
#[derive(Debug, Clone)]
pub struct ProposedSliceAssignment {
    /// The slice (key range) being assigned.
    slice: Slice,

    /// The SQUIDs of resources (Slicelets) to which this slice is assigned.
    /// Must contain at least one resource. Multiple resources support replication.
    resources: Vec<Squid>,

    /// OPTIONAL: Primary rate load for this slice assignment.
    /// Corresponds to the load reported by Slicelets for this slice.
    primary_rate_load: Option<f64>,
}

impl ProposedSliceAssignment {
    /// Creates a new ProposedSliceAssignment.
    ///
    /// # Arguments
    ///
    /// * `slice` - The key range being assigned
    /// * `resources` - The Slicelet SQUIDs to which this slice is assigned (at least one required)
    /// * `primary_rate_load` - Optional load measurement for this slice
    ///
    /// # Errors
    ///
    /// Returns an error if:
    /// - `resources` is empty
    /// - `primary_rate_load` is negative or infinite
    pub fn new(
        slice: Slice,
        resources: Vec<Squid>,
        primary_rate_load: Option<f64>,
    ) -> Result<Self, Status> {
        if resources.is_empty() {
            return Err(Status::invalid_argument(
                "ProposedSliceAssignment must have at least one resource",
            ));
        }

        if let Some(load) = primary_rate_load {
            validate_measurement(load)?;
        }

        Ok(Self {
            slice,
            resources,
            primary_rate_load,
        })
    }

    /// Returns a reference to the slice.
    pub fn slice(&self) -> &Slice {
        &self.slice
    }

    /// Consumes this assignment and returns its components.
    pub fn into_parts(self) -> (Slice, Vec<Squid>, Option<f64>) {
        (self.slice, self.resources, self.primary_rate_load)
    }
}

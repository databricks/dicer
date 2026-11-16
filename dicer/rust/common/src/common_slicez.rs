/// Tracks the Slicez-relevant data for a Slicelet.
#[derive(Debug, Clone, PartialEq)]
pub struct SliceletSubscriberSlicezData {
    pub debug_name: String,
    pub address: String,
}

/// Tracks the Slicez-relevant data for a Clerk.
#[derive(Debug, Clone, PartialEq)]
pub struct ClerkSubscriberSlicezData {
    pub debug_name: String,
}

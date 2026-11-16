mod resource_address;
mod slice;
pub mod slice_key;
mod slice_key_builder;
// The target module is public so internal dicer code can import helper functions like
// target_variant(), get_target_cluster(), etc. via dicer_rust_external_types::target::.
// However, we only re-export the Target struct below to keep this crate's root namespace clean
// and avoid polluting it with internal types (TargetVariant, TargetCluster, AppTarget) and helper
// functions. Note: what customers see is controlled by //dicer/rust/external:external_lib's
// explicit re-exports, not by what we re-export here.
pub mod target;

pub use resource_address::ResourceAddress;
pub use slice::*;
pub use slice_key::{HighSliceKey, HighSliceKeyRef, SliceKey, SliceKeyError};
pub use slice_key_builder::*;
// Only re-export the customer-facing Target struct to keep the root namespace clean.
// Internal dicer code can still access helpers via dicer_rust_external_types::target::.
pub use target::Target;

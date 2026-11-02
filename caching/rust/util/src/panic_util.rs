//! Utilities for handling panic payloads caught by [`std::panic::catch_unwind`].

use std::any::Any;

/// Extracts a human-readable message from a panic payload.
#[allow(clippy::borrowed_box)]
pub fn extract_panic_message(payload: &Box<dyn Any + Send>) -> String {
    if let Some(s) = payload.downcast_ref::<&str>() {
        (*s).to_string()
    } else if let Some(s) = payload.downcast_ref::<String>() {
        s.clone()
    } else {
        "<non-string panic payload>".to_string()
    }
}

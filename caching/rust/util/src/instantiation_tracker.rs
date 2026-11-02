use std::collections::HashMap;
use std::fmt::Debug;
use std::hash::Hash;
use std::sync::{Mutex, MutexGuard};

use tonic::Status;

/// A utility to enforce uniqueness of objects within the process. `T`'s equality semantics
/// define the level at which uniqueness is enforced. In particular, for a given instance
/// `t1`, `enforce_and_record(t1)` will return an error if, within this process, there was a
/// previous invocation `enforce_and_record(t0)` such that `t0 == t1`.
///
/// Thread-safe.
#[derive(Default)]
pub struct InstantiationTracker<T: Eq + Hash> {
    /// The past instantiations by key. We store a stack trace of the original instantiation for
    /// debugging.
    instantiations: Mutex<HashMap<T, String>>,
}

impl<T: Eq + Hash + Debug> InstantiationTracker<T> {
    /// Returns a new [`InstantiationTracker`] for uniqueness key type `T`.
    pub fn new() -> Self {
        Self {
            instantiations: Mutex::new(HashMap::new()),
        }
    }

    /// Records an instantiation associated with `key`. Returns an error if a previous call to
    /// [`enforce_and_record()`] was made with an equivalent `key`.
    pub fn enforce_and_record(&self, key: T) -> Result<(), Status> {
        let mut instantiations: MutexGuard<HashMap<T, String>> =
            self.instantiations.lock().unwrap();

        if let Some(previously_created_location) = instantiations.get(&key) {
            return Err(Status::failed_precondition(format!(
                "Instance for {:?} already exists. Previously created at {}",
                key, previously_created_location
            )));
        }

        // Capture the current stack trace for debugging.
        // Format it similarly to Scala's getStackTrace output.
        let stack_trace = format!("{:?}", std::backtrace::Backtrace::capture());

        instantiations.insert(key, stack_trace);
        Ok(())
    }
}

/// A convenience marker type which specifies that the tracked type should be
/// instantiated at most once per-process.
///
/// All instances will be equal and have the same hash.
/// Therefore, the hash table of the tracker can have at most one entry.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct PerProcessSingleton;

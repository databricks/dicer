use std::fmt;
use std::ops::Deref;
use std::sync::Arc;

use tokio::sync::watch;

/// A cell storing a versioned value of type `Value`.
///
/// Writers can call `set()` to update the value, and observers can either call
/// `get()` to access the latest value, or, if they want to be notified when the
/// value changes, they can use `changed_since()` with the last known version
/// to wait until the cell contains a value with a newer version.
///
/// Note that observers are guaranteed to learn about the latest value of the
/// cell but must not assume they will observe every intermediate value, because
/// multiple changes may occur between successive observations.
///
/// # Examples
///
/// ```
/// use caching_rust_util::watch_cell::{Versioned, WatchCell};
///
/// #[derive(Debug, Clone, PartialEq, Eq)]
/// struct VersionedString {
///     version: u64,
///     value: String,
/// }
///
/// impl Versioned for VersionedString {
///     type Version = u64;
///
///     fn version(&self) -> Self::Version {
///         self.version
///     }
/// }
///
/// #[tokio::main]
/// async fn main() {
///     let cell = WatchCell::new(VersionedString {
///         version: 1,
///         value: "initial value".to_string(),
///     });
///     assert_eq!(cell.get().value, "initial value");
///     assert_eq!(cell.get().version, 1);
///     
///     // Set a new value with higher version.
///     cell.set(VersionedString {
///         version: 2,
///         value: "new value".to_string(),
///     });
///     
///     // Set yet another value with even higher version.
///     cell.set(VersionedString {
///         version: 3,
///         value: "newer value".to_string(),
///     });
///     
///     // Wait for a version higher than 1.
///     cell.changed_since(1).await;
///     let updated = cell.get();
///     
///     // We'll get the latest value, skipping the intermediate version 2.
///     assert_eq!(updated.value, "newer value");
///     assert_eq!(updated.version, 3); // latest version
/// }
/// ```
///
/// # Remarks
///
/// This implementation wraps the `tokio::sync::watch` crate to reduce surface
/// and complexity:
///
/// - The value is stored in a `Ref` wrapper which allows for cheap cloning
///   (via an `Arc` internally) rather than blocking the underlying `watch`
///   sender as Tokio's `watch::Ref` abstraction does. This avoids potential
///   deadlocks.
/// - The versioning system ensures that observers can efficiently request
///   only values newer than what they've already seen, avoiding redundant
///   notifications. In addition, the contract is relative to the version of the
///   value, not the time it was set, resulting in cleaner semantics and greater
///   resilience to timing issues in applications.
pub struct WatchCell<Value: Versioned> {
    sender: watch::Sender<Ref<Value>>,
    watcher: Watcher<Value>,
}

/// A trait for types that have monotonically increasing versions.
pub trait Versioned: Send + Sync + 'static {
    /// The type of the version.
    type Version: Copy + Ord + Send;

    /// Returns the version of the value.
    fn version(&self) -> Self::Version;
}

impl<Value: Versioned> WatchCell<Value> {
    /// Creates a new `WatchCell` with the specified initial value.
    pub fn new(initial_value: Value) -> Self {
        let value = Arc::new(initial_value);
        let (sender, receiver) = watch::channel(Ref { value });
        let watcher = Watcher { receiver };
        Self { sender, watcher }
    }

    /// Stores the given value in the cell if the provided version is greater
    /// than the current version. This is a non-blocking call which will not
    /// wait for receivers to observe the change.
    ///
    /// Returns true if the value was updated (i.e., if the supplied value's
    /// version was greater than the current version), or false otherwise.
    pub fn set(&self, value: Value) -> bool {
        self.sender
            .send_if_modified(move |current: &mut Ref<Value>| {
                if current.version() < value.version() {
                    let value = Arc::new(value);
                    *current = Ref { value };
                    true
                } else {
                    false
                }
            })
    }

    /// See [`Watcher::get`].
    pub fn get(&self) -> Ref<Value> {
        self.watcher.get()
    }

    /// See [`Watcher::changed_since`]. Does not return an error because the
    /// cell cannot be dropped while the future is being awaited.
    pub async fn changed_since(&self, known_version: Value::Version) {
        self.watcher
            .changed_since(known_version)
            .await
            .expect("the cell has been dropped?");
    }

    /// Returns a reference to a watcher for the cell.
    pub fn watcher(&self) -> &Watcher<Value> {
        &self.watcher
    }
}

/// A watcher for a `WatchCell` that can be used to wait for changes to the
/// cell.
pub struct Watcher<Value: Versioned> {
    receiver: watch::Receiver<Ref<Value>>,
}

impl<Value: Versioned> Watcher<Value> {
    /// Returns a `Ref` to the current value stored in the cell.
    ///
    /// This is a non-blocking call (returns immediately).
    pub fn get(&self) -> Ref<Value> {
        self.receiver.borrow().clone()
    }

    /// Waits for a value with a version greater than the given `known_version`
    /// to become available.
    ///
    /// If a value with a greater version is already available, this method
    /// returns immediately. Otherwise, it waits for the cell to be updated with
    /// a value that has a greater version.
    ///
    /// The watch cell may be dropped while the future is being awaited, in
    /// which case the future will complete immediately with a
    /// `WatchCellError::CellDropped` error.
    pub async fn changed_since(&self, known_version: Value::Version) -> Result<(), WatchCellError> {
        // Clone the receiver to avoid changing the version of the original
        // receiver (which happens when we call `borrow_and_update` or
        // `changed`).
        let mut receiver = self.receiver.clone();
        loop {
            {
                // We use `borrow_and_update` to record that we've seen the
                // current value so that `changed` doesn't return
                // immediately if we need to call it below. See
                // https://docs.rs/tokio/latest/tokio/sync/watch/index.html#borrow_and_update-versus-borrow
                // for details.
                let current: watch::Ref<Ref<Value>> = receiver.borrow_and_update();
                if current.version() > known_version {
                    return Ok(());
                }
            } // Release mutable borrow of `receiver` before waiting.
            if receiver.changed().await.is_err() {
                // The sender (i.e., the cell) has been dropped, so we exit the loop.
                return Err(WatchCellError::CellDropped);
            }
        }
    }
}

// Manually implemented since `Value` may not implement `Clone`.
impl<Value: Versioned> Clone for Watcher<Value> {
    fn clone(&self) -> Self {
        Self {
            receiver: self.receiver.clone(),
        }
    }
}

/// An error that can occur when waiting for a value to change in a `WatchCell`
/// using [`Watcher::changed_since`].
#[derive(Debug, PartialEq, Eq, Hash)]
pub enum WatchCellError {
    CellDropped,
}

impl fmt::Display for WatchCellError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            WatchCellError::CellDropped => write!(f, "The cell has been dropped"),
        }
    }
}

/// Implemented rather than derived because `Value` may not implement `Clone`.
impl<Value: Versioned> Clone for WatchCell<Value> {
    fn clone(&self) -> Self {
        Self {
            sender: self.sender.clone(),
            watcher: self.watcher.clone(),
        }
    }
}

/// A reference to a value stored in a `WatchCell`, along with its associated
/// version. Returned by `WatchCell::get()`.
///
/// This reference is not responsive to changes in the cell - it is a snapshot
/// of the value and version at the time it was retrieved. It uses an `Arc`
/// internally to allow for cheap cloning without copying the underlying value.
///
/// The `Ref` implements `Deref` to allow direct access to the underlying value
/// and provides a `version()` method to access the associated version.
pub struct Ref<Value: Versioned> {
    value: Arc<Value>,
}

impl<Value: Versioned> Ref<Value> {
    /// Returns the version of the referenced value.
    pub fn version(&self) -> Value::Version {
        self.value.version()
    }
}

/// Implemented rather than derived because `Value` may not implement `Clone`.
impl<Value: Versioned> Clone for Ref<Value> {
    fn clone(&self) -> Self {
        Self {
            value: Arc::clone(&self.value),
        }
    }
}

impl<Value: Versioned> Deref for Ref<Value> {
    type Target = Value;

    fn deref(&self) -> &Self::Target {
        self.value.deref()
    }
}

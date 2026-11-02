use caching_rust_util::watch_cell::{Ref, Versioned, WatchCell, WatchCellError};

/// A versioned value.
#[derive(Debug, Clone, PartialEq, Eq)]
struct VersionedValue {
    version: u64,
    value: String,
}

impl VersionedValue {
    fn new(version: u64, value: impl Into<String>) -> Self {
        Self {
            version,
            value: value.into(),
        }
    }
}

impl Versioned for VersionedValue {
    type Version = u64;

    fn version(&self) -> Self::Version {
        self.version
    }
}

#[test]
fn initial_value() {
    // Test plan: verify that the initial value is returned by `get`.
    let cell = WatchCell::new(VersionedValue::new(42, "init value"));
    let cell_ref: Ref<VersionedValue> = cell.get();
    assert_eq!(*cell_ref, VersionedValue::new(42, "init value"));
    assert_eq!(cell_ref.version(), 42);
}

#[test]
fn set() {
    // Test plan: verify that values specified with increasing version numbers
    // via `set` are returned by `get`.
    let cell = WatchCell::new(VersionedValue::new(42, "init value"));
    assert!(cell.set(VersionedValue::new(100, "new value")));
    let cell_ref: Ref<VersionedValue> = cell.get();
    assert_eq!(*cell_ref, VersionedValue::new(100, "new value"));
    assert_eq!(cell_ref.version(), 100);

    // Overwrite the initial value.
    assert!(cell.set(VersionedValue::new(200, "newer value")));
    let cell_ref: Ref<VersionedValue> = cell.get();
    assert_eq!(*cell_ref, VersionedValue::new(200, "newer value"));
    assert_eq!(cell_ref.version(), 200);
}

#[test]
fn set_older_version_does_not_overwrite() {
    // Test plan: verify that setting a value with an older version
    // does not overwrite the current value.
    let cell = WatchCell::new(VersionedValue::new(100, "version 100 value"));
    let cell_ref: Ref<VersionedValue> = cell.get();
    assert_eq!(*cell_ref, VersionedValue::new(100, "version 100 value"));
    assert_eq!(cell_ref.version(), 100);

    // Try to set a value with an older version (50 < 100).
    assert!(!cell.set(VersionedValue::new(50, "version 50 value")));
    let cell_ref: Ref<VersionedValue> = cell.get();
    assert_eq!(*cell_ref, VersionedValue::new(100, "version 100 value"));
    assert_eq!(cell_ref.version(), 100);
}

#[test]
fn set_same_version_does_not_overwrite() {
    // Test plan: verify that setting a value with the same version
    // does not overwrite the current value.
    let cell = WatchCell::new(VersionedValue::new(100, "original value"));
    let cell_ref: Ref<VersionedValue> = cell.get();
    assert_eq!(*cell_ref, VersionedValue::new(100, "original value"));
    assert_eq!(cell_ref.version(), 100);

    // Try to set a different value with the same version.
    // The set operation should return false indicating it didn't update.
    assert!(!cell.set(VersionedValue::new(100, "new value with same version")));

    // The cell should still have the original value.
    let cell_ref: Ref<VersionedValue> = cell.get();
    assert_eq!(*cell_ref, VersionedValue::new(100, "original value"));
    assert_eq!(cell_ref.version(), 100);

    // Now set a value with a newer version, which should work.
    assert!(cell.set(VersionedValue::new(101, "newer version value")));
    let cell_ref: Ref<VersionedValue> = cell.get();
    assert_eq!(*cell_ref, VersionedValue::new(101, "newer version value"));
    assert_eq!(cell_ref.version(), 101);
}

#[tokio::test]
async fn broadcast() {
    // Test plan: verify that multiple clones of the cell can observe the same
    // value.
    let cell = WatchCell::new(VersionedValue::new(42, "init value"));
    let cell_clone = cell.clone();

    // Should see initial value.
    assert_eq!(*cell.get(), VersionedValue::new(42, "init value"));
    assert_eq!(*cell_clone.get(), VersionedValue::new(42, "init value"));

    // Update the value. Both cells should see the new value.
    assert!(cell.set(VersionedValue::new(100, "new value")));

    // Use on_changed_since to wait for changes.
    cell.changed_since(42).await;
    cell_clone.changed_since(42).await;

    let updated = cell.get();
    let updated_clone = cell_clone.get();

    assert_eq!(*updated, VersionedValue::new(100, "new value"));
    assert_eq!(*updated_clone, VersionedValue::new(100, "new value"));
}

#[test]
fn drop_cell() {
    // Test plan: verify that a watch cell can be dropped and that clones can
    // still observe the latest value.
    let cell = WatchCell::new(VersionedValue::new(42, "init value"));
    let cell_clone = cell.clone();
    drop(cell);
    assert_eq!(*cell_clone.get(), VersionedValue::new(42, "init value"));
}

#[tokio::test]
async fn on_changed_since_returns_latest_value() {
    // Test plan: verify that `on_changed_since()` waits for the latest value.
    let cell = WatchCell::new(VersionedValue::new(42, "init value"));
    assert_eq!(*cell.get(), VersionedValue::new(42, "init value"));

    assert!(cell.set(VersionedValue::new(100, "new value")));

    // Wait for change
    cell.changed_since(42).await;
    let updated = cell.get();
    assert_eq!(*updated, VersionedValue::new(100, "new value"));
}

#[tokio::test]
async fn on_changed_since_version_check() {
    // Test plan: verify that `on_changed_since()` properly checks versions
    let cell = WatchCell::new(VersionedValue::new(42, "init value"));
    assert_eq!(*cell.get(), VersionedValue::new(42, "init value"));

    // Set a new value with a higher version.
    assert!(cell.set(VersionedValue::new(100, "new value")));

    // Wait for changes since version 42.
    cell.changed_since(42).await;
    let updated1 = cell.get();
    assert_eq!(*updated1, VersionedValue::new(100, "new value"));

    // Set another value with an even higher version.
    assert!(cell.set(VersionedValue::new(200, "newest value")));

    // Wait for changes since version 100.
    cell.changed_since(100).await;
    let updated2 = cell.get();
    assert_eq!(*updated2, VersionedValue::new(200, "newest value"));

    // This should immediately return since 50 < 200.
    cell.changed_since(50).await;
    let updated3 = cell.get();
    assert_eq!(*updated3, VersionedValue::new(200, "newest value"));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn multi_threaded() {
    // Test plan: verify that multiple tasks can observe the same cell. In this
    // test, we use a payload type that is not `Copy` or `Clone` to validate
    // that the `WatchCell` implementation does not require these traits.
    #[derive(Debug)]
    struct NonCopyClone {
        value: i32,
    }

    impl Versioned for NonCopyClone {
        type Version = i64;

        fn version(&self) -> Self::Version {
            self.value as i64
        }
    }

    let cell = WatchCell::new(NonCopyClone { value: 0 });

    // Spawn a task that will set the value repeatedly.
    let cell_clone = cell.clone();
    let set_task = tokio::spawn(async move {
        for i in 1..=10 {
            // We expect all sets to succeed since versions are monotonically
            // increasing
            assert!(cell_clone.set(NonCopyClone { value: i }));
            tokio::time::sleep(std::time::Duration::from_millis(1)).await;
        }
    });

    // Spawn tasks that will observe the values.
    let mut observe_tasks = Vec::new();
    for _ in 0..10 {
        let observe_cell = cell.clone();
        observe_tasks.push(tokio::spawn(async move {
            // We do not expect to see every value, but we do expect to see
            // values with monotonically increasing versions, and eventually
            // reach the final value.
            let mut current_version = 0;
            let mut value = observe_cell.get().value;

            while value < 10 {
                observe_cell.changed_since(current_version).await;
                let ref_val = observe_cell.get();
                let next_version = ref_val.version();
                let next_value = ref_val.value;

                // With the new versioned API, we only assert that versions
                // increase which guarantees the value should match the version
                assert!(next_version > current_version);

                current_version = next_version;
                value = next_value;
            }
        }));
    }

    // Join the tasks.
    for task in observe_tasks {
        task.await.unwrap();
    }
    set_task.await.unwrap();
}

#[tokio::test]
async fn cell_can_be_dropped_while_waiting() {
    // Test plan: verify that the cell can be dropped while a future is waiting,
    // and the future completes successfully when the version was already updated.
    let cell = WatchCell::new(VersionedValue::new(1, "initial"));

    // Update the value
    cell.set(VersionedValue::new(2, "updated"));

    // Create a future that waits for a change
    let watcher = cell.watcher().clone();
    let wait_future = watcher.changed_since(1);

    // Drop the cell (and thus the sender)
    drop(cell);

    // The future should still complete because version 2 > 1
    wait_future.await.unwrap();
}

#[tokio::test]
async fn cell_dropped_while_pending() {
    // Test plan: verify that when the cell is dropped while a future is
    // waiting for a version that doesn't exist yet, the future completes
    // immediately.
    let cell = WatchCell::new(VersionedValue::new(1, "initial"));

    // Create a future that waits for a version that doesn't exist yet
    let watcher = cell.watcher().clone();
    let wait_future = watcher.changed_since(5);

    // Drop the cell - no version 6 will ever arrive
    drop(cell);

    // The future should complete immediately when cell is dropped with
    // a `WatchCellError::CellDropped` error.
    assert_eq!(wait_future.await.unwrap_err(), WatchCellError::CellDropped);
}

use std::sync::Arc;
use std::thread;

use tonic::{Code, Status};
use caching_rust_util::instantiation_tracker::{
    InstantiationTracker,
    PerProcessSingleton,
};

#[test]
fn test_enforce_and_record_allows_first_instantiation() {
    // Test plan: Verify that the first call to enforce_and_record succeeds.
    //
    // Setup: Create a new tracker.
    // Verify: First call succeeds.

    let tracker = InstantiationTracker::new();
    let result: Result<(), Status> = tracker.enforce_and_record("key1");
    assert!(result.is_ok(), "First instantiation should succeed");
}

#[test]
fn test_enforce_and_record_rejects_duplicate() {
    // Test plan: Verify that duplicate calls to enforce_and_record fail with appropriate error.
    //
    // Setup: Create a tracker and record first instantiation.
    // Verify: Second call with same key fails with "already exists" error and
    // FailedPrecondition code.

    let tracker = InstantiationTracker::new();
    tracker
        .enforce_and_record("key1")
        .expect("First instantiation should succeed");

    let result = tracker.enforce_and_record("key1");
    assert!(result.is_err(), "Duplicate instantiation should fail, but got: {:?}", result);

    let error: Status = result.unwrap_err();
    assert_eq!(error.code(), Code::FailedPrecondition, "Error should have FailedPrecondition code");
    assert!(
        error.message().contains("already exists"),
        "Error message should mention 'already exists', got: {}",
        error.message()
    );
    assert!(
        error.message().contains("key1"),
        "Error message should mention the key, got: {}",
        error.message()
    );
    assert!(
        error.message().contains("Backtrace"),
        "Error message should mention 'Backtrace', got: {}",
        error.message()
    );
}

#[test]
fn test_enforce_and_record_allows_different_keys() {
    // Test plan: Verify that different keys can be recorded independently.
    //
    // Setup: Create a tracker and record first key.
    // Verify: Second call with different key succeeds.

    let tracker = InstantiationTracker::new();
    tracker
        .enforce_and_record("key1")
        .expect("First instantiation should succeed");
    tracker
        .enforce_and_record("key2")
        .expect("Second instantiation with different key should succeed");
}

#[test]
fn test_per_process_singleton_marker() {
    // Test plan: Verify PerProcessSingleton marker works with InstantiationTracker.
    //
    // Setup: Create a tracker and record singleton marker.
    // Verify: Second call with same marker fails with FailedPrecondition code.

    let tracker = InstantiationTracker::new();
    tracker
        .enforce_and_record(PerProcessSingleton)
        .expect("First instantiation should succeed");

    let result: Result<(), Status> = tracker.enforce_and_record(PerProcessSingleton);
    assert!(result.is_err(), "Duplicate singleton instantiation should fail");
    assert_eq!(
        result.unwrap_err().code(),
        Code::FailedPrecondition,
        "Error should have FailedPrecondition code"
    );
}

#[test]
fn test_per_process_singleton_enforces_single_instance() {
    // Test plan: Verify that PerProcessSingleton enforces exactly one instance per tracker.
    //
    // Setup: Create a tracker specifically for PerProcessSingleton.
    // Verify: First call succeeds, second call fails with proper error.

    let tracker: InstantiationTracker<PerProcessSingleton> = InstantiationTracker::new();

    // First instance should succeed.
    let result1: Result<(), Status> = tracker.enforce_and_record(PerProcessSingleton);
    assert!(result1.is_ok(), "First PerProcessSingleton instantiation should succeed");

    // Second instance should fail.
    let result2 = tracker.enforce_and_record(PerProcessSingleton);
    assert!(result2.is_err(), "Second PerProcessSingleton instantiation should fail");

    let error: Status = result2.unwrap_err();
    assert_eq!(error.code(), Code::FailedPrecondition, "Error should have FailedPrecondition code");
    assert!(
        error.message().contains("PerProcessSingleton"),
        "Error should mention PerProcessSingleton, got: {}",
        error.message()
    );
}

#[test]
fn test_per_process_singleton_multiple_trackers_independent() {
    // Test plan: Verify that different tracker instances are independent.
    //
    // Setup: Create two separate trackers for PerProcessSingleton.
    // Verify: Each tracker allows its own PerProcessSingleton instance independently.

    let tracker1: InstantiationTracker<PerProcessSingleton> = InstantiationTracker::new();
    let tracker2: InstantiationTracker<PerProcessSingleton> = InstantiationTracker::new();

    // Both trackers should allow their own PerProcessSingleton.
    let result1: Result<(), Status> = tracker1.enforce_and_record(PerProcessSingleton);
    assert!(result1.is_ok(), "First tracker should allow PerProcessSingleton");

    let result2: Result<(), Status> = tracker2.enforce_and_record(PerProcessSingleton);
    assert!(result2.is_ok(), "Second tracker should allow PerProcessSingleton independently");

    // But each tracker should still enforce its own singleton.
    let result1_duplicate: Result<(), Status> = tracker1.enforce_and_record(PerProcessSingleton);
    assert!(
        result1_duplicate.is_err(),
        "First tracker should reject duplicate PerProcessSingleton"
    );

    let result2_duplicate: Result<(), Status> = tracker2.enforce_and_record(PerProcessSingleton);
    assert!(
        result2_duplicate.is_err(),
        "Second tracker should reject duplicate PerProcessSingleton"
    );
}

#[test]
fn test_with_custom_struct() {
    // Test plan: Verify InstantiationTracker works with custom structs.
    //
    // Setup: Create a tracker and a custom struct implementing Hash + Eq.
    // Verify: Equal structs are detected as duplicates with FailedPrecondition code.

    #[derive(Debug, Clone, PartialEq, Eq, Hash)]
    struct ServiceConfig {
        name: String,
        port: u16,
    }

    let tracker = InstantiationTracker::new();
    let config1 = ServiceConfig {
        name: "service1".to_string(),
        port: 8080,
    };
    let config2 = ServiceConfig {
        name: "service1".to_string(),
        port: 8080,
    };

    tracker
        .enforce_and_record(config1)
        .expect("First instantiation should succeed");

    let result: Result<(), Status> = tracker.enforce_and_record(config2);
    assert!(result.is_err(), "Duplicate config instantiation should fail");
    assert_eq!(
        result.unwrap_err().code(),
        Code::FailedPrecondition,
        "Error should have FailedPrecondition code"
    );
}

#[test]
fn test_with_integer_keys() {
    // Test plan: Verify InstantiationTracker works with integer keys.
    //
    // Setup: Create a tracker and use integer keys.
    // Verify: Same integers are detected as duplicates, different ones are allowed.

    let tracker = InstantiationTracker::new();
    tracker
        .enforce_and_record(42)
        .expect("First instantiation should succeed");
    tracker
        .enforce_and_record(100)
        .expect("Different integer should succeed");

    let result: Result<(), Status> = tracker.enforce_and_record(42);
    assert!(result.is_err(), "Duplicate integer instantiation should fail");
    assert_eq!(
        result.unwrap_err().code(),
        Code::FailedPrecondition,
        "Error should have FailedPrecondition code"
    );
}

#[test]
fn test_error_contains_stack_trace() {
    // Test plan: Verify that error messages contain stack trace information.
    //
    // Setup: Create a tracker, record a key, then try to record it again.
    // Verify: Error message contains stack trace (though exact format may vary)
    // and FailedPrecondition code.

    let tracker = InstantiationTracker::new();
    tracker
        .enforce_and_record("traced_key")
        .expect("First instantiation should succeed");

    let result: Result<(), Status> = tracker.enforce_and_record("traced_key");
    assert!(result.is_err(), "Duplicate should fail");

    let error: Status = result.unwrap_err();
    assert_eq!(error.code(), Code::FailedPrecondition, "Error should have FailedPrecondition code");
    assert!(
        !error.message().is_empty(),
        "Error message should not be empty, got: {}",
        error.message()
    );
    assert!(
        error.message().contains("test_error_contains_stack_trace"),
        "Error message should contain stack trace with test_error_contains_stack_trace in trace, {}",
        error.message()
    );
}

#[test]
fn test_concurrent_instantiations() {
    // Test plan: Verify that concurrent calls to enforce_and_record with the same key results
    // in exactly one call succeeding and the rest failing. If the implementation has a
    // concurrency bug (due to incorrect lock usage, for example), this test would be flaky.
    //
    // Setup: Create a tracker and spawn 10 threads all trying to enforce_and_record the same key.
    // Verify: Exactly one thread succeeds, and 9 threads fail with FailedPrecondition code.

    let tracker = Arc::new(InstantiationTracker::new());
    let num_threads: usize = 10;
    let mut handles: Vec<thread::JoinHandle<Result<(), tonic::Status>>> = Vec::new();

    for _ in 0..num_threads {
        let tracker_clone = Arc::clone(&tracker);
        let handle = thread::spawn(move || tracker_clone.enforce_and_record(PerProcessSingleton));
        handles.push(handle);
    }

    // Wait for all threads to complete and collect results.
    let results: Vec<Result<(), Status>> = handles
        .into_iter()
        .map(|handle| handle.join().expect("Thread panicked"))
        .collect();

    // Exactly one should have succeeded, and the other 9 should have failed.
    let num_errors: usize = results.iter().filter(|r| r.is_err()).count();
    assert_eq!(
        num_errors,
        9,
        "Expected exactly 9 threads to fail, but {} failed. Results: {:?}",
        num_errors,
        results
            .iter()
            .enumerate()
            .map(|(i, r)| {
                if r.is_err() {
                    format!("Thread {} failed", i)
                } else {
                    format!("Thread {} succeeded", i)
                }
            })
            .collect::<Vec<_>>()
    );

    // Verify all errors have FailedPrecondition code.
    for result in results.iter().filter(|r| r.is_err()) {
        assert_eq!(
            result.as_ref().unwrap_err().code(),
            Code::FailedPrecondition,
            "All errors should have FailedPrecondition code"
        );
    }
}

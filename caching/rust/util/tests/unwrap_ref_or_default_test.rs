use std::panic::catch_unwind;
use std::ptr::addr_of;
use std::sync::atomic::{AtomicI32, Ordering};
use std::sync::Barrier;
use std::thread::{self, Scope};

use caching_rust_util::unwrap_ref_or_default::UnwrapRefOrDefault;

#[test]
fn test_some() {
    // Test Plan: Verify that for `Some` instances, a reference to the actual contained value is
    // returned.
    let str1: String = "hello1".to_string();
    let opt_of_ref: Option<&String> = Some(&str1);
    let ref_of_opt: &Option<String> = &Some("hello2".to_string());

    let ref1: &String = opt_of_ref.unwrap_ref_or_default();
    let ref2: &String = ref_of_opt.unwrap_ref_or_default();
    assert_eq!(ref1, "hello1");
    assert_eq!(ref2, "hello2");
}

#[test]
fn test_none() {
    // Test Plan: Verify that for `None` instances, a reference to the default value for the type
    // is returned.
    let opt_of_ref: Option<&String> = None;
    let ref_of_opt: &Option<String> = &None;

    let ref1: &String = opt_of_ref.unwrap_ref_or_default();
    let ref2: &String = ref_of_opt.unwrap_ref_or_default();
    assert_eq!(ref1, "");
    assert_eq!(ref2, "");

    // Verify that since both `Option`s had the same underlying type, there should only be a single
    // default instance in existence.
    assert_eq!(addr_of!(*ref1), addr_of!(*ref2));
}

#[test]
fn test_multiple_different_types() {
    // Test Plan: Verify that we can operate on multiple different underlying types without issue.
    let opt1: Option<&String> = None;
    let opt2: Option<&i32> = None;
    let opt3: Option<&Vec<String>> = None;

    let ref1: &String = opt1.unwrap_ref_or_default();
    let ref2: &i32 = opt2.unwrap_ref_or_default();
    let ref3: &Vec<String> = opt3.unwrap_ref_or_default();
    assert_eq!(ref1, "");
    assert_eq!(*ref2, 0);
    assert_eq!(*ref3, Vec::<String>::new());
}

#[test]
fn test_panic_in_default_initializer() {
    // Test Plan: Verify that if the `default` method for the underlying type panics, then
    // `unwrap_ref_or_default` will panic but will still be usable for other types afterward.
    #[derive(Debug)]
    struct TypeWithPanicingDefault;

    impl Default for TypeWithPanicingDefault {
        fn default() -> TypeWithPanicingDefault {
            panic!()
        }
    }

    // Verify that the panic is propagated.
    let opt: Option<&TypeWithPanicingDefault> = None;
    catch_unwind(|| opt.unwrap_ref_or_default()).expect_err("Did not panic");

    // Verify that `unwrap_ref_or_default` is still usable for other types.
    let opt_string: Option<&String> = None;
    let string: &String = opt_string.unwrap_ref_or_default();
    assert_eq!(string, "");
}

#[test]
fn test_reentrant_call_in_default_initializer() {
    // Test Plan: Verify that it's supported for the `default` method for the underlying type to
    // reentrantly call `unwrap_ref_or_default` on a different type.
    struct TypeWithReentrantDefault(i32);

    impl Default for TypeWithReentrantDefault {
        fn default() -> TypeWithReentrantDefault {
            let opt_string: Option<&String> = None;
            let string: &String = opt_string.unwrap_ref_or_default();
            assert_eq!(string, "");
            TypeWithReentrantDefault(42)
        }
    }

    let opt: Option<&TypeWithReentrantDefault> = None;
    let val: &TypeWithReentrantDefault = opt.unwrap_ref_or_default();
    assert_eq!(val.0, 42);
}

#[test]
fn test_concurrent_calls_for_same_type() {
    // Test Plan: Verify that if there are concurrent calls for the same underlying type, even
    // though the `default` method will be called multiple times, only one of the calls will win and
    // the others will be dropped.
    struct Dummy;

    // Use a barrier in the `default` method to force it to run concurrently with itself.
    const NUM_CONCURRENT_CALLS: usize = 3;
    static BARRIER: Barrier = Barrier::new(NUM_CONCURRENT_CALLS);
    static CREATION_COUNT: AtomicI32 = AtomicI32::new(0);

    impl Default for Dummy {
        fn default() -> Dummy {
            BARRIER.wait();
            CREATION_COUNT.fetch_add(1, Ordering::SeqCst);
            Dummy
        }
    }

    static DROP_COUNT: AtomicI32 = AtomicI32::new(0);
    impl Drop for Dummy {
        fn drop(&mut self) {
            DROP_COUNT.fetch_add(1, Ordering::SeqCst);
        }
    }

    thread::scope(|scope: &Scope| {
        for _ in 0..NUM_CONCURRENT_CALLS {
            scope.spawn(|| None::<Dummy>.unwrap_ref_or_default());
        }
    });

    // Verify that while `default` was called three times, all but one of the resulting instances
    // were dropped when the race was detected.
    assert_eq!(CREATION_COUNT.load(Ordering::SeqCst), 3);
    assert_eq!(DROP_COUNT.load(Ordering::SeqCst), 2);
}

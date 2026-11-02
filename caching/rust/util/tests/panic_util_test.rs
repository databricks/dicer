use std::any::Any;

use caching_rust_util::panic_util::extract_panic_message;

#[test]
fn extracts_static_str_payload() {
    // Test plan: Verify that a panic payload carrying a `&'static str` (the form
    // `panic!("literal")` produces) round-trips through `extract_panic_message`. Verify this by
    // boxing a string literal as `Box<dyn Any + Send>` and asserting the function returns the
    // same string.

    // Setup: Box a `&'static str` as the type catch_unwind would hand back.
    let payload: Box<dyn Any + Send> = Box::new("literal panic message");

    // Verify: the function returns the literal's contents unchanged.
    assert_eq!(extract_panic_message(&payload), "literal panic message");
}

#[test]
fn extracts_string_payload() {
    // Test plan: Verify that a panic payload carrying a `String` (the form
    // `panic!("fmt {}", x)` produces) round-trips through `extract_panic_message`. Verify this
    // by boxing an owned `String` as `Box<dyn Any + Send>` and asserting the function returns
    // the same string.

    // Setup: Box an owned `String` as the type catch_unwind would hand back.
    let payload: Box<dyn Any + Send> = Box::new(String::from("formatted panic message"));

    // Verify: the function returns the String's contents unchanged.
    assert_eq!(extract_panic_message(&payload), "formatted panic message");
}

#[test]
fn falls_back_to_placeholder_for_non_string_payload() {
    // Test plan: Verify that a panic payload whose concrete type is neither `&str` nor
    // `String` (the form `panic_any(value)` with a custom type produces) returns the fallback
    // placeholder rather than panicking or returning empty output. Verify this by boxing an
    // `i32` and asserting the function returns `<non-string panic payload>`.

    // Setup: Box a primitive numeric type as the type a `panic_any` caller might hand back.
    let payload: Box<dyn Any + Send> = Box::new(42i32);

    // Verify: the function returns the placeholder string for non-`&str`, non-`String`
    // payloads.
    assert_eq!(extract_panic_message(&payload), "<non-string panic payload>");
}

#[test]
fn extracts_message_from_real_caught_panic() {
    // Test plan: Verify end-to-end that the boxed payload `std::panic::catch_unwind` hands
    // back for a `panic!("...")` call has the shape `extract_panic_message` expects, locking
    // in the assumption that `panic!` with a literal produces a `&str` payload. Verify this by
    // catching a real panic and asserting the function recovers the panic message.

    // Setup: Trigger and catch a real `panic!("real panic")`; unwrap the boxed payload from
    // the Err arm.
    let result = std::panic::catch_unwind(|| panic!("real panic"));
    let payload = result.expect_err("the closure was expected to panic");

    // Verify: the function recovers the original panic message from the boxed payload.
    assert_eq!(extract_panic_message(&payload), "real panic");
}

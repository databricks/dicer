load(
    "@rules_rust//rust:defs.bzl",
    _rust_binary = "rust_binary",
    _rust_library = "rust_library",
    _rust_test = "rust_test",
)
load(
    "@rules_rust_prost//:defs.bzl",
    _rust_prost_library = "rust_prost_library",
)
load(
    "//bazel/rules/rust:buffa.bzl",
    _rust_buffa_library = "rust_buffa_library_rule",
)

# Adds file and line numbers to compiled outputs.
_DEFAULT_DEBUGINFO_FLAGS = ["-Cdebuginfo=1"]

def _prepend_rustc_flags(additional_flags, kwargs):
    user_defined_flags = kwargs.get("rustc_flags", [])
    kwargs["rustc_flags"] = additional_flags + user_defined_flags
    return kwargs

def rust_library(name, **kwargs):
    _rust_library(
        name = name,
        **_prepend_rustc_flags(_DEFAULT_DEBUGINFO_FLAGS, kwargs)
    )

def rust_binary(name, **kwargs):
    _rust_binary(
        name = name,
        **_prepend_rustc_flags(_DEFAULT_DEBUGINFO_FLAGS, kwargs)
    )

rust_prost_library = _rust_prost_library

def rust_test(name, **kwargs):
    """Declares a `rust_test` whose cases run serially.

    Args:
        name: Name of the test target.
        **kwargs: Attributes forwarded to rules_rust's `rust_test`.
    """

    # Set RUST_TEST_THREADS to 1 to ensure that tests run in serial, matching internal behavior.
    # NOTE: This guarantees that only one test is running at a time, but does not guarantee that
    # - tests run in a specific order (typically run alphabetically, test_a before test_b, but Rust
    #   provides no guarantee.)
    # - tests run in the same thread
    env = kwargs.get("env", {})
    env["RUST_TEST_THREADS"] = "1"
    kwargs["env"] = env
    _rust_test(
        name = name,
        **_prepend_rustc_flags(_DEFAULT_DEBUGINFO_FLAGS, kwargs)
    )

def rust_buffa_library(
        name,
        text = False,
        **kwargs):
    """Compile a `proto_library` into a Rust crate with buffa.

    Delegates to the underlying rule from `bazel/rules/rust/buffa.bzl`. The `_buffa` suffix
    namespaces buffa crates apart from a prost-generated crate for the same proto.

    Per-target codegen flags (1:1 with `buffa_codegen` CLI):
      - `text`: emits `impl buffa::text::TextFormat`. Supported — the buffa
        crate's `text` feature is enabled, so the generated impls link.
    """

    _rust_buffa_library(
        name = name,
        text = text,
        **kwargs
    )

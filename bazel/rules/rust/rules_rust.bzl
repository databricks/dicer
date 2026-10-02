load(
    "@rules_rust//rust:defs.bzl",
    _rust_binary = "rust_binary",
    _rust_library = "rust_library",
    _rust_test = "rust_test",
)
load(
    "//bazel/rules/rust:buffa.bzl",
    _rust_buffa_library = "rust_buffa_library_rule",
)

rust_library = _rust_library
rust_binary = _rust_binary
rust_test = _rust_test

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

"""Rules for compiling `proto_library` targets into Rust crates using buffa.

The user-facing macro `rust_buffa_library` lives in
`bazel/rules/rust/rules_rust.bzl` for symmetry with `rust_prost_library`; it
delegates to `_rust_buffa_library` from this file. The aspect + low-level rule
live here.

End-to-end shape:

1. `buffa_aspect` traverses a `proto_library`'s `deps` and, for each
   `proto_library` target, runs `buffa_codegen --single-file lib.rs ...` and
   then compiles that lib.rs via rules_rust's `rustc_compile_action`.
2. Each step propagates a `BuffaProtoInfo` provider with the generated
   `dep_variant_info` and a `package_info` file mapping the generated crate
   name to the proto packages it covers.
3. Downstream proto_library aspects read the deps' `package_info` files to
   build an `--extern-path` map so dep types are referenced via
   `::<dep_crate>::<pkg>` instead of being regenerated.
4. `_rust_buffa_library` is a thin rule that re-exports the aspect's
   `BuffaProtoInfo` providers for its `proto` attribute, making the
   generated crate consumable as a normal `rust_library` dep.

Mirrors `rules_rust_prost`'s shape; the cross-crate stitching protocol uses
the same package_info / deps_info pattern.
"""

load("@rules_rust//rust:defs.bzl", "rust_common")
load("@rules_rust//rust/private:rust.bzl", "RUSTC_ATTRS")
load("@rules_rust//rust/private:rustc.bzl", "rustc_compile_action")

visibility(["public"])

BUFFA_RUST_EDITION = "2024"

BuffaProtoInfo = provider(
    doc = "Per-proto_library buffa codegen + compile output.",
    fields = {
        "dep_variant_info": "rust_common.dep_variant_info wrapping the generated rlib.",
        "package_info": "File mapping this crate name to its proto packages.",
        "transitive_package_infos": "depset[File] of package_info for this crate and all transitive proto deps.",
    },
)

def _proto_virtual_import_path(file, proto_info):
    """Returns the path that protoc records as FileDescriptorProto.getName().

    Bazel File objects expose two path coordinate systems: `file.path`
    (exec-root-relative) and `file.short_path` (workspace-relative); they diverge
    for generated files.
    """

    # proto_info.proto_source_root is exec-root-relative. It is None or "." when
    # no strip_import_prefix is set, or an exec-root directory otherwise.
    proto_source_root = proto_info.proto_source_root or "."
    if proto_source_root == ".":
        return file.short_path
    exec_root_prefix = proto_source_root + "/"
    if file.path.startswith(exec_root_prefix):
        return file.path.removeprefix(exec_root_prefix)
    return file.path

# Buffa's shared WKT crates own the `google.protobuf` package. Reject unknown
# files under its canonical import prefix before they generate a competing
# crate that can shadow that package mapping.
_WKT_PROTO_PREFIX = "google/protobuf/"

# The well-known-type files the vendored crates provide, so buffa never
# generates them: `google.protobuf.*` come from buffa-types, and
# `descriptor.proto` / `compiler/plugin.proto` from buffa-descriptor.
_COVERED_WKT_PROTOS = [
    "google/protobuf/any.proto",
    "google/protobuf/api.proto",
    "google/protobuf/compiler/plugin.proto",
    "google/protobuf/descriptor.proto",
    "google/protobuf/duration.proto",
    "google/protobuf/empty.proto",
    "google/protobuf/field_mask.proto",
    "google/protobuf/source_context.proto",
    "google/protobuf/struct.proto",
    "google/protobuf/timestamp.proto",
    "google/protobuf/type.proto",
    "google/protobuf/wrappers.proto",
]

def _is_wkt_only(proto_info):
    """Whether every direct source of `proto_info` is a well-known type.

    Such proto_libraries are provided by the vendored buffa-types /
    buffa-descriptor crates and must not be regenerated into their own crate;
    the aspect skips them and downstream leaves resolve `google.protobuf.*`
    references via the extern path.
    """
    import_paths = [
        _proto_virtual_import_path(src, proto_info)
        for src in proto_info.direct_sources
    ]
    return bool(import_paths) and all(
        [p in _COVERED_WKT_PROTOS for p in import_paths],
    )

def _check_wkt_coverage(label, proto_info):
    """Reject well-known types not provided by buffa's shared WKT crates."""
    for source in proto_info.direct_sources:
        import_path = _proto_virtual_import_path(source, proto_info)
        if (import_path.startswith(_WKT_PROTO_PREFIX) and
            import_path not in _COVERED_WKT_PROTOS):
            fail("{} uses {}, which buffa-types and buffa-descriptor do not provide"
                .format(label, import_path))

def _crate_name_from_label(label):
    """Derive the buffa crate name for a proto_library label.

    Bazel package converted by replacing `/` and `-` with `_`, joined with the
    target name (also `-` → `_`), then `_buffa`-suffixed. The `_buffa` suffix
    distinguishes the buffa crate from a prost-generated crate for the same proto,
    so the two coexist in one binary instead of colliding at link (e.g. proto
    `//caching/util/test/proto:test_data_proto` →
    `caching_util_test_proto_test_data_proto_buffa`).
    """
    pkg = label.package.replace("-", "_").replace("/", "_")
    name = label.name.replace("-", "_")
    return pkg + "_" + name + "_buffa"

def _run_buffa_codegen(
        *,
        ctx,
        crate_name,
        proto_info,
        dep_buffa_infos):
    """Invoke buffa_codegen for the current proto_library target.

    Returns a struct with `lib_rs`, `package_info`, and
    `transitive_package_infos`. File names use `ctx.label.name` so multiple
    rules wrapping the same proto don't collide on declared output paths. The
    on-disk `crate_name` is still the canonical one — only the file basename
    differs.
    """
    prefix = "{}.{}".format(ctx.label.name, crate_name)
    lib_rs = ctx.actions.declare_file("{}.buffa.lib.rs".format(prefix))
    package_info = ctx.actions.declare_file("{}.buffa_package_info".format(prefix))

    # deps_info contains one package_info path per transitive proto dependency.
    transitive_pkg_infos = depset(
        direct = [d.package_info for d in dep_buffa_infos],
        transitive = [d.transitive_package_infos for d in dep_buffa_infos],
    )
    deps_info = ctx.actions.declare_file("{}.buffa_deps_info".format(prefix))
    deps_info_content = ctx.actions.args()
    deps_info_content.set_param_file_format("multiline")
    deps_info_content.add_all(transitive_pkg_infos)
    ctx.actions.write(output = deps_info, content = deps_info_content)

    outputs = [lib_rs, package_info]

    args = ctx.actions.args()
    args.add("--single-file", lib_rs)
    args.add("--crate-name", crate_name)
    args.add("--package-info-output", package_info)
    args.add("--deps-info", deps_info)

    # Never generate the well-known types: buffa_codegen auto-injects the
    # `.google.protobuf = ::buffa_types::google::protobuf` extern path instead
    # (see `_COVERED_WKT_PROTOS`). Exclude the exact files, NOT the whole
    # `google/protobuf/` prefix — a non-WKT proto under that prefix must stay in
    # its own generation set rather than be dropped as "nothing to generate".
    for wkt in _COVERED_WKT_PROTOS:
        args.add("--exclude", wkt)

    if ctx.attr.text:
        args.add("--text")
    args.add_all(proto_info.transitive_descriptor_sets)

    inputs = depset(
        direct = [deps_info],
        transitive = [
            proto_info.transitive_descriptor_sets,
            transitive_pkg_infos,
        ],
    )

    ctx.actions.run(
        executable = ctx.executable._codegen,
        arguments = [args],
        inputs = inputs,
        outputs = outputs,
        mnemonic = "BuffaCodegen",
        progress_message = "Generating buffa Rust source for %{label}",
    )

    return struct(
        lib_rs = lib_rs,
        package_info = package_info,
        transitive_package_infos = transitive_pkg_infos,
    )

def _compile_generated_crate(*, ctx, crate_name, lib_rs, dep_buffa_infos):
    """Drive `rustc_compile_action` to compile the generated lib.rs into an rlib.

    Uses the same shape rules_rust_prost uses (see its `_compile_rust`).
    """

    # lib_rs.path embeds ctx.label.name, so wrappers of the same proto do not
    # collide on the rlib output path.
    output_hash = repr(hash(lib_rs.path + ".buffa"))
    rlib = ctx.actions.declare_file("lib{}-{}.rlib".format(crate_name, output_hash))
    rmeta = ctx.actions.declare_file("lib{}-{}.rmeta".format(crate_name, output_hash))

    # `rustc_rmeta_output` is rules_rust's *rendered-diagnostics* sink for the
    # metadata action, NOT the metadata file. It must be a distinct output —
    # passing `rmeta` for both overwrites the binary .rmeta with diagnostic text.
    rmeta_diagnostics = ctx.actions.declare_file("lib{}-{}.rmeta.rustc-out".format(crate_name, output_hash))

    # WKT and descriptor crates are always linked so buffa's built-in extern
    # paths resolve even when the proto does not import them directly.
    rust_deps = [d.dep_variant_info for d in dep_buffa_infos]
    for implicit in (ctx.attr._runtime, ctx.attr._wkt, ctx.attr._descriptor):
        rust_deps.append(rust_common.dep_variant_info(
            crate_info = implicit[rust_common.crate_info],
            dep_info = implicit[rust_common.dep_info],
            cc_info = implicit[CcInfo] if CcInfo in implicit else None,
            build_info = None,
        ))

    rust_toolchain = ctx.toolchains["@rules_rust//rust:toolchain_type"]

    providers = rustc_compile_action(
        ctx = ctx,
        attr = ctx.rule.attr,
        toolchain = rust_toolchain,
        # Public proto imports can generate paths into transitive crates.
        force_all_deps_direct = True,
        crate_info_dict = dict(
            name = crate_name,
            type = "rlib",
            root = lib_rs,
            # rustc_compile_action wraps these three fields in depset() and
            # concatenates srcs with compile_data, so they must remain lists.
            srcs = [lib_rs],
            deps = rust_deps,
            proc_macro_deps = [],
            aliases = {},
            output = rlib,
            metadata = rmeta,
            metadata_supports_pipelining = False,
            rustc_rmeta_output = rmeta_diagnostics,
            edition = BUFFA_RUST_EDITION,
            is_test = False,
            rustc_env = {},
            rustc_env_files = [],
            compile_data = depset([]),
            compile_data_targets = depset([]),
            owner = ctx.label,
            wrapped_crate_type = None,
        ),
        output_hash = output_hash,
        include_coverage = False,
    )

    crate_info = None
    dep_info = None
    cc_info = None
    for p in providers:
        if hasattr(p, "name") and hasattr(p, "root"):
            crate_info = p
        elif hasattr(p, "direct_crates") and hasattr(p, "transitive_crates"):
            dep_info = p
        elif hasattr(p, "linking_context"):
            cc_info = p

    return rust_common.dep_variant_info(
        crate_info = crate_info,
        dep_info = dep_info,
        cc_info = cc_info,
        build_info = None,
    )

def _buffa_aspect_impl(target, ctx):
    _check_wkt_coverage(target.label, target[ProtoInfo])

    # Well-known-type proto_libraries are provided by buffa-types /
    # buffa-descriptor — never regenerate them.
    if _is_wkt_only(target[ProtoInfo]):
        return []

    crate_name = _crate_name_from_label(target.label)

    dep_buffa_infos = [
        d[BuffaProtoInfo]
        for d in ctx.rule.attr.deps
        if BuffaProtoInfo in d
    ]

    codegen = _run_buffa_codegen(
        ctx = ctx,
        crate_name = crate_name,
        proto_info = target[ProtoInfo],
        dep_buffa_infos = dep_buffa_infos,
    )

    dep_variant_info = _compile_generated_crate(
        ctx = ctx,
        crate_name = crate_name,
        lib_rs = codegen.lib_rs,
        dep_buffa_infos = dep_buffa_infos,
    )

    own_transitive = depset(
        direct = [codegen.package_info],
        transitive = [codegen.transitive_package_infos],
    )

    return [
        BuffaProtoInfo(
            dep_variant_info = dep_variant_info,
            package_info = codegen.package_info,
            transitive_package_infos = own_transitive,
        ),
        OutputGroupInfo(rust_generated_srcs = depset([codegen.lib_rs])),
    ]

buffa_aspect = aspect(
    implementation = _buffa_aspect_impl,
    attr_aspects = ["deps"],
    required_providers = [[ProtoInfo]],
    attrs = {
        "text": attr.bool(
            default = False,
            doc = "Emit buffa text-format implementations.",
        ),
        "_codegen": attr.label(
            default = Label("//bazel/rules/rust/buffa:buffa_codegen"),
            executable = True,
            cfg = "exec",
        ),
        "_runtime": attr.label(
            default = Label("@crates//:buffa"),
            providers = [[rust_common.crate_info]],
        ),
        "_wkt": attr.label(
            default = Label("@crates//:buffa-types"),
            providers = [[rust_common.crate_info]],
        ),
        "_descriptor": attr.label(
            default = Label("@crates//:buffa-descriptor"),
            providers = [[rust_common.crate_info]],
        ),
    } | RUSTC_ATTRS,
    toolchains = [
        "@rules_rust//rust:toolchain_type",
        "@bazel_tools//tools/cpp:toolchain_type",
    ],
    fragments = ["cpp"],
)

def _rust_buffa_library_impl(ctx):
    info = ctx.attr.proto[BuffaProtoInfo]
    crate_info = info.dep_variant_info.crate_info
    if ctx.attr.crate_name != crate_info.name:
        fail("crate_name '{}' does not match generated crate name '{}'".format(
            ctx.attr.crate_name,
            crate_info.name,
        ))
    return [
        crate_info,
        info.dep_variant_info.dep_info,
        DefaultInfo(files = depset([crate_info.output])),
        OutputGroupInfo(rust_generated_srcs = crate_info.srcs),
        info,
    ]

rust_buffa_library_rule = rule(
    implementation = _rust_buffa_library_impl,
    doc = """Compile a `proto_library` into a Rust crate using buffa.

Do not call this rule directly: use the `rust_buffa_library` macro from
`//bazel/rules/rust:rules_rust.bzl`, which accepts user-friendly attributes.
""",
    attrs = {
        "crate_name": attr.string(mandatory = True),
        "proto": attr.label(
            mandatory = True,
            providers = [ProtoInfo],
            aspects = [buffa_aspect],
        ),
        "text": attr.bool(
            default = False,
            doc = "Emit buffa text-format implementations.",
        ),
    } | RUSTC_ATTRS,
    toolchains = [
        "@rules_rust//rust:toolchain_type",
        "@bazel_tools//tools/cpp:toolchain_type",
    ],
    fragments = ["cpp"],
)

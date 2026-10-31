//! The `package_info` manifests that connect already generated Buffa crates.
//!
//! Each codegen run writes one manifest for the crate it generates, listing the proto files that
//! crate owns and the Rust path of every message and enum declared in them. A downstream run reads
//! its dependencies' manifests (listed in `--deps-info`).
//!
//! Ownership is recorded per file and per type, not per proto package, because separate
//! `proto_library` targets can share a package or nest packages inside each other.
//!
//! # Format
//!
//! JSON, serialized from [`PackageInfo`] with serde. Type paths are relative to the crate root.
//! For example:
//!
//! ```json
//! {
//!   "crate_name": "dicer_common_test_proto_test_data_proto_buffa",
//!   "files": [
//!     {
//!       "name": "dicer/common/test/proto/test_data.proto",
//!       "types": [
//!         {
//!           "proto_fqn": ".dicer.test.GenerationTestDataP",
//!           "rust_path": "dicer::test::GenerationTestDataP"
//!         },
//!         {
//!           "proto_fqn": ".dicer.test.SliceMapTestDataP",
//!           "rust_path": "dicer::test::SliceMapTestDataP"
//!         }
//!       ]
//!     }
//!   ]
//! }
//! ```

use anyhow::{bail, Context, Result};
use buffa_codegen::context::CodeGenContext;
use buffa_codegen::generated::descriptor::{
    DescriptorProto,
    EnumDescriptorProto,
    FileDescriptorProto,
};
use buffa_codegen::idents::escape_mod_ident;
use buffa_codegen::CodeGenConfig;
use serde::{Deserialize, Serialize};

/// One generated crate's manifest: the proto files it owns and where it emitted their types.
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct PackageInfo {
    /// Name of the crate that generated `files`, as passed to `--crate-name`.
    crate_name: String,
    /// The owned files, in the order this crate was asked to generate them.
    files: Vec<OwnedProtoFile>,
}

/// A proto file owned by a generated crate, with the types it declares.
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct OwnedProtoFile {
    /// [`FileDescriptorProto::name`], e.g. `dicer/common/test/proto/test_data.proto`. Downstream
    /// crates compare it with their input descriptors' names to check that every file has an owner.
    name: String,
    /// Every message and enum declared in the file, nested types included.
    types: Vec<OwnedProtoType>,
}

/// A message or enum, and where its owning crate emitted it.
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct OwnedProtoType {
    /// Fully-qualified proto name with a leading dot, e.g. `.dicer.test.GenerationTestDataP`.
    proto_fqn: String,
    /// Path from the owning crate's root, e.g. `dicer::test::GenerationTestDataP`, in the form
    /// [`rewrite_crate_relative_type_path`] produces.
    rust_path: String,
}

impl PackageInfo {
    /// Proto file names this crate owns, in the order this crate was asked to generate them.
    pub(crate) fn owned_file_names(&self) -> impl Iterator<Item = &str> + '_ {
        self.files
            .iter()
            .map(|file: &OwnedProtoFile| file.name.as_str())
    }

    /// Maps every type this crate owns to its absolute Rust path, for
    /// [`buffa_codegen::CodeGenConfig::extern_paths`].
    ///
    /// Each entry names one exact type so it cannot capture types that other crates declare in
    /// the same or nested packages.
    ///
    /// # Returns
    ///
    /// `(proto_fqn, ::<crate_name>::<rust_path>)` pairs, in manifest order.
    pub(crate) fn extern_paths(&self) -> impl Iterator<Item = (String, String)> + '_ {
        self.files
            .iter()
            .flat_map(|file: &OwnedProtoFile| &file.types)
            .map(|owned_type: &OwnedProtoType| {
                (
                    owned_type.proto_fqn.clone(),
                    format!("::{}::{}", self.crate_name, owned_type.rust_path),
                )
            })
    }
}

/// Builds this crate's manifest, where each generated type's proto FQN mapped to its
///  crate-relative Rust path from [`CodeGenContext::rust_type`].
///
/// # Arguments
///
/// * `crate_name` - Name of the crate being generated, from `--crate-name`.
/// * `file_descriptors` - Every merged input descriptor.
/// * `generated_files` - The files this crate generated.
/// * `config` - The config used to generate resolved crate.
///
/// # Returns
///
/// A manifest with one owned file per entry in `generated_files`, in that order.
///
/// # Errors
///
/// Returns an error if a generated file has no descriptor, if a declared type has no Rust path,
/// or if a type resolves outside this crate (e.g. to [`buffa_types`] through buffa's automatic
/// `.google.protobuf` mapping).
pub(crate) fn package_info_for_generated_files(
    crate_name: &str,
    file_descriptors: &[FileDescriptorProto],
    generated_files: &[String],
    config: &CodeGenConfig,
) -> Result<PackageInfo> {
    let context: CodeGenContext<'_> =
        CodeGenContext::for_generate(file_descriptors, generated_files, config);
    let mut package_info: PackageInfo = PackageInfo {
        crate_name: crate_name.to_string(),
        files: Vec::new(),
    };
    for name in generated_files {
        let fd: &FileDescriptorProto = file_descriptors
            .iter()
            .find(|fd: &&FileDescriptorProto| fd.name.as_deref() == Some(name.as_str()))
            .with_context(|| {
                format!("{name} has no file descriptor in the input descriptor sets")
            })?;
        let package: &str = fd.package.as_deref().unwrap_or("");
        let prefix: String = if package.is_empty() {
            String::new()
        } else {
            format!(".{package}")
        };
        let mut proto_fqns: Vec<String> = Vec::new();
        collect_type_names(&prefix, &fd.message_type, &fd.enum_type, &mut proto_fqns)?;
        let mut types: Vec<OwnedProtoType> = Vec::new();
        for proto_fqn in proto_fqns {
            let rust_path: &str = context
                .rust_type(&proto_fqn)
                .with_context(|| format!("missing generated Rust type for {proto_fqn}"))?;
            // An absolute path means buffa mapped this crate's own type to another
            // crate (e.g. its automatic `.google.protobuf` mapping), so references
            // to the recorded path would not resolve.
            if rust_path.starts_with("::") || rust_path.starts_with("crate::") {
                bail!("{proto_fqn} in {name} resolves outside this crate, to {rust_path}");
            }
            types.push(OwnedProtoType {
                rust_path: rewrite_crate_relative_type_path(rust_path),
                proto_fqn,
            });
        }
        package_info.files.push(OwnedProtoFile {
            name: name.clone(),
            types,
        });
    }
    Ok(package_info)
}

/// Appends the fully-qualified name of every message and enum declared in a scope, recursing
/// into nested types.
///
/// Synthetic map-entry messages are skipped. protoc emits one per `map<>` field, and no other
/// crate refers to them. Within each scope enums come first, and a message's nested types come
/// before the message itself.
///
/// For `GenerationTestDataP` in `dicer/common/test/proto/test_data.proto`:
/// `.dicer.test.GenerationTestDataP.InvalidGenerationTestCaseP`
/// `.dicer.test.GenerationTestDataP.CanonicalGenerationTestCaseP`
/// `.dicer.test.GenerationTestDataP.ToStringTestCaseP`
/// `.dicer.test.GenerationTestDataP`
///
/// # Arguments
///
/// * `prefix` - Fully-qualified name of the enclosing scope with a leading dot (`.dicer.test`),
///   or empty for a file without a package.
/// * `messages` - Messages declared directly in that scope.
/// * `enums` - Enums declared directly in that scope.
/// * `names` - Output list the names are appended to.
///
/// # Errors
///
/// Returns an error if a message or enum descriptor has no name.
fn collect_type_names(
    prefix: &str,
    messages: &[DescriptorProto],
    enums: &[EnumDescriptorProto],
    names: &mut Vec<String>,
) -> Result<()> {
    for enum_type in enums {
        let name: &str = enum_type.name.as_deref().context("enum without a name")?;
        names.push(format!("{prefix}.{name}"));
    }
    for message in messages {
        if message.options.map_entry.unwrap_or(false) {
            continue;
        }
        let name: &str = message.name.as_deref().context("message without a name")?;
        let name: String = format!("{prefix}.{name}");
        collect_type_names(&name, &message.nested_type, &message.enum_type, names)?;
        names.push(name);
    }
    Ok(())
}

/// Suffixes `self`, `super`, `crate`, and `Self` module segments with `_` so the path matches
/// the modules buffa emits. Other segments are left as [`CodeGenContext::rust_type`] returned them.
///
/// # Arguments
///
/// * `rust_path` - A crate-relative path from [`CodeGenContext::rust_type`], e.g.
///   `dicer::test::GenerationTestDataP`.
///
/// # Returns
///
/// The same path, with those four keywords suffixed if they appear as module segments.
/// `foo::self::Bar` becomes `foo::self_::Bar`.
fn rewrite_crate_relative_type_path(rust_path: &str) -> String {
    let (modules, name): (&str, &str) = rust_path.rsplit_once("::").unwrap_or(("", rust_path));
    let mut segments: Vec<String> = modules
        .split("::")
        .filter(|module: &&str| !module.is_empty())
        .map(|module: &str| {
            let escaped: String = escape_mod_ident(module);
            if escaped.starts_with("r#") {
                module.to_string()
            } else {
                escaped
            }
        })
        .collect();
    segments.push(name.to_string());
    segments.join("::")
}

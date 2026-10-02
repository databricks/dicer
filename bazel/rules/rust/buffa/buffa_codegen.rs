//! Bazel driver for `buffa_codegen`.
//!
//! Reads one or more `FileDescriptorSet` protos (produced by Bazel's
//! `proto_library` via `protoc`), feeds them to `buffa_codegen::generate`,
//! and writes the resulting Rust source into a single Bazel-declared file.
//!
//! Replaces what `buffa-build` does in a Cargo `build.rs` workflow. With
//! Bazel the `FileDescriptorSet` is already available as a build output, so
//! the `protoc`-invocation half of `buffa-build` is unneeded.
//!
use std::collections::{BTreeMap, HashSet};
use std::path::PathBuf;

use anyhow::{bail, Context, Result};
use buffa::Message;
use buffa_codegen::generated::descriptor::{FileDescriptorProto, FileDescriptorSet};
use buffa_codegen::idents::escape_mod_ident;
use buffa_codegen::{generate, CodeGenConfig, GeneratedFile, GeneratedFileKind, ALLOW_LINTS};
use clap::Parser;

#[derive(Parser)]
#[command(about = "Generate Rust source from FileDescriptorSet using buffa-codegen.")]
struct Args {
    /// Emit a single self-contained `.rs` file with all generated modules
    /// inlined. This is the Bazel-declared crate root.
    #[arg(long)]
    single_file: PathBuf,

    /// Rust crate name for the generated code. Used when writing
    /// `--package-info-output` so downstream crates know which crate covers
    /// which proto packages.
    #[arg(long)]
    crate_name: String,

    /// Write a tab-separated manifest of this crate's proto packages to PATH.
    /// One line per package: `<crate_name>\t<proto_package>`.
    #[arg(long)]
    package_info_output: PathBuf,

    /// Read a newline-separated list of dep crates' `package_info` files
    /// from PATH and synthesize `--extern-path` mappings so types from
    /// dependency crates are referenced as `::<dep_crate>::<pkg_path>`
    /// instead of being regenerated in this crate.
    #[arg(long)]
    deps_info: PathBuf,

    /// Skip generation for proto file paths starting with PREFIX (repeatable).
    #[arg(long)]
    exclude: Vec<String>,

    /// Emit `impl buffa::text::TextFormat` on generated message structs.
    #[arg(long)]
    text: bool,

    /// `FileDescriptorSet` files (binpb). Multiple are merged by proto file name.
    #[arg(required = true)]
    fds: Vec<PathBuf>,
}

/// Read a `--deps-info` manifest (one path per line, blank lines and
/// `#`-comments allowed) and load every referenced package_info file. Each
/// package_info line is `<crate_name>\t<proto_package>`. Returns a flat list
/// of `(crate_name, proto_package)` pairs across all deps.
fn load_deps_info(path: &PathBuf) -> Result<Vec<(String, String)>> {
    let content =
        std::fs::read_to_string(path).with_context(|| format!("read {}", path.display()))?;
    let mut out = Vec::new();
    for dep_path in content.lines() {
        let dep_path = dep_path.trim();
        if dep_path.is_empty() || dep_path.starts_with('#') {
            continue;
        }
        let dep = std::fs::read_to_string(dep_path)
            .with_context(|| format!("read dep package_info {dep_path}"))?;
        for (lineno, line) in dep.lines().enumerate() {
            if line.is_empty() {
                continue;
            }
            let (crate_name, pkg) = line.split_once('\t').with_context(|| {
                format!("malformed line {} in {dep_path}: `{line}`", lineno + 1)
            })?;
            out.push((crate_name.to_string(), pkg.to_string()));
        }
    }
    Ok(out)
}

/// Convert a dotted proto package to its Rust module path, escaping each
/// segment exactly as buffa's own codegen does (`escape_mod_ident`): a keyword
/// segment like `type` becomes `r#type`, `self`/`super`/`crate`/`Self` gain a
/// `_` suffix. Without this the extern path (`::crate::type::…`) would not match
/// the module buffa actually emits (`::crate::r#type::…`) → E0433. An empty
/// package yields an empty string; callers skip those.
fn package_to_rust_module_path(pkg: &str) -> String {
    pkg.split('.')
        .map(escape_mod_ident)
        .collect::<Vec<_>>()
        .join("::")
}

/// Convert deps-info pairs into buffa `--extern-path` entries.
///
/// Each `(crate_name, proto_pkg)` pair becomes
/// `(".<proto_pkg>", "::<crate_name>::<pkg_as_rust_path>")` where
/// `pkg_as_rust_path` maps dots to `::` with per-segment keyword escaping. The
/// leading `.` on the proto prefix matches buffa's fully-qualified-name
/// convention.
fn deps_to_extern_paths(deps: Vec<(String, String)>) -> Vec<(String, String)> {
    deps.into_iter()
        // A dependency proto with no `package` cannot be externed by package
        // prefix: an empty package yields proto-prefix ".", which buffa's
        // resolver treats as a catch-all matching every fully-qualified name and
        // reroutes all local types to the dep crate (E0433/E0412). Skip them.
        .filter(|(_, pkg)| !pkg.is_empty())
        .map(|(crate_name, pkg)| {
            let proto_prefix = format!(".{pkg}");
            let rust_path = format!("::{crate_name}::{}", package_to_rust_module_path(&pkg));
            (proto_prefix, rust_path)
        })
        .collect()
}

/// Collect the set of proto packages covered by `files_to_generate`.
fn covered_packages<'a>(
    descriptors: &'a [FileDescriptorProto],
    files_to_generate: &[String],
) -> Vec<&'a str> {
    use std::collections::BTreeSet;
    let to_gen: HashSet<&str> = files_to_generate.iter().map(String::as_str).collect();
    let mut pkgs = BTreeSet::new();
    for fd in descriptors {
        if let Some(name) = fd.name.as_deref()
            && to_gen.contains(name)
        {
            pkgs.insert(fd.package.as_deref().unwrap_or(""));
        }
    }
    pkgs.into_iter().collect()
}

/// Read and decode a binary `FileDescriptorSet`.
fn read_fds(path: &PathBuf) -> Result<FileDescriptorSet> {
    let data = std::fs::read(path).with_context(|| format!("read {}", path.display()))?;
    FileDescriptorSet::decode_from_slice(&data)
        .with_context(|| format!("decode FileDescriptorSet from {}", path.display()))
}

/// Assemble a single self-contained `.rs` source from the generated files.
///
/// With `file_per_package = true`, every `PackageMod` already contains its
/// package's complete source. Nest those bodies according to their proto
/// packages and drop their redundant generated-file headers.
fn assemble_single_file(generated: &[GeneratedFile]) -> String {
    let package_mods: Vec<(&str, &str)> = generated
        .iter()
        .filter(|f| f.kind == GeneratedFileKind::PackageMod)
        .map(|f| (f.package.as_str(), strip_generated_header(&f.content)))
        .collect();

    emit_inlined_module_tree(&package_mods)
}

/// Nest package bodies under the `pub mod` tree implied by their dotted names.
/// Package-less files stay at the crate root, and packages are ordered
/// deterministically.
fn emit_inlined_module_tree(package_mods: &[(&str, &str)]) -> String {
    #[derive(Default)]
    struct ModNode<'a> {
        contents: Vec<&'a str>,
        children: BTreeMap<&'a str, ModNode<'a>>,
    }

    let mut root = ModNode::default();
    for (package, content) in package_mods {
        let mut node = &mut root;
        if !package.is_empty() {
            for segment in package.split('.') {
                node = node.children.entry(segment).or_default();
            }
        }
        node.contents.push(content);
    }

    let lints = ALLOW_LINTS.join(", ");

    /// Render one module-tree node and its descendants at the requested depth.
    fn emit(out: &mut String, node: &ModNode<'_>, depth: usize, lints: &str) {
        let indent = "    ".repeat(depth);
        for content in &node.contents {
            for line in content.lines() {
                if line.is_empty() {
                    out.push('\n');
                } else {
                    out.push_str(&indent);
                    out.push_str(line);
                    out.push('\n');
                }
            }
        }
        for (segment, child) in &node.children {
            let escaped = escape_mod_ident(segment);
            out.push_str(&format!("{indent}#[allow({lints})]\n"));
            out.push_str(&format!("{indent}pub mod {escaped} {{\n"));
            out.push_str(&format!("{indent}    use super::*;\n"));
            emit(out, child, depth + 1, lints);
            out.push_str(&format!("{indent}}}\n"));
        }
    }

    let mut out = String::new();
    out.push_str("// @generated by buffa-codegen. DO NOT EDIT.\n");
    out.push_str(&format!("#![allow({lints})]\n\n"));
    emit(&mut out, &root, 0, &lints);
    out
}

/// Strip a package body's generated-file header and its optional blank line.
/// Leave unexpected content unchanged.
fn strip_generated_header(content: &str) -> &str {
    const HEADER: &str = "// @generated by buffa-codegen. DO NOT EDIT.\n";
    match content.strip_prefix(HEADER) {
        Some(rest) => rest.strip_prefix('\n').unwrap_or(rest),
        None => content,
    }
}

/// Merge descriptor sets by proto file name, preserving the first occurrence.
fn merge_fds(paths: &[PathBuf]) -> Result<Vec<FileDescriptorProto>> {
    let mut out = Vec::new();
    let mut seen = HashSet::new();
    for path in paths {
        let fds = read_fds(path)?;
        for file in fds.file {
            let name = file.name.clone().with_context(|| {
                format!("FileDescriptorProto without name in {}", path.display())
            })?;
            if seen.insert(name) {
                out.push(file);
            }
        }
    }
    Ok(out)
}

/// Generate one Rust crate root and its package manifest from descriptor sets.
fn main() -> Result<()> {
    let args = Args::parse();

    let file_descriptors = merge_fds(&args.fds)?;

    // Dependency packages must be excluded from the generation set. Otherwise,
    // buffa emits owned-view impls for external types, violating the orphan
    // rule (E0117).
    let deps_pairs = load_deps_info(&args.deps_info)?;
    // Skip package-less deps: an empty package here would match the empty
    // package of every package-less leaf file in `should_skip` below and wrongly
    // exclude them all from generation.
    let dep_packages: HashSet<String> = deps_pairs
        .iter()
        .map(|(_, pkg)| pkg.clone())
        .filter(|pkg| !pkg.is_empty())
        .collect();

    let extern_paths = deps_to_extern_paths(deps_pairs);

    let should_skip = |fd: &FileDescriptorProto| {
        let name = fd.name.as_deref().unwrap_or("");
        let package = fd.package.as_deref().unwrap_or("");
        dep_packages.contains(package) || args.exclude.iter().any(|p| name.starts_with(p.as_str()))
    };

    let files_to_generate: Vec<String> = file_descriptors
        .iter()
        .filter(|fd| !should_skip(fd))
        .filter_map(|fd| fd.name.clone())
        .collect();

    if files_to_generate.is_empty() {
        bail!("nothing to generate: all input files filtered by --deps-info / --exclude");
    }

    let mut config = CodeGenConfig::default();
    config.generate_text = args.text;
    config.extern_paths = extern_paths;
    // Single-file output needs each package's generated content fully inlined.
    config.file_per_package = true;

    let generated = generate(&file_descriptors, &files_to_generate, &config)
        .context("buffa_codegen::generate")?;

    let bundle = assemble_single_file(&generated);
    if let Some(parent) = args.single_file.parent() {
        std::fs::create_dir_all(parent)
            .with_context(|| format!("create_dir_all {} (for --single-file)", parent.display()))?;
    }
    std::fs::write(&args.single_file, bundle)
        .with_context(|| format!("write {}", args.single_file.display()))?;

    let mut content = String::new();
    for pkg in covered_packages(&file_descriptors, &files_to_generate) {
        content.push_str(&args.crate_name);
        content.push('\t');
        content.push_str(pkg);
        content.push('\n');
    }
    if let Some(parent) = args.package_info_output.parent() {
        std::fs::create_dir_all(parent).with_context(|| {
            format!("create_dir_all {} (for --package-info-output)", parent.display())
        })?;
    }
    std::fs::write(&args.package_info_output, content)
        .with_context(|| format!("write {}", args.package_info_output.display()))?;

    Ok(())
}

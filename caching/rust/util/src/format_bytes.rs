//! Utilities for rendering byte slices as human-readable strings.

use std::fmt::{self, Debug, Formatter};

/// Produces a human-readable representation of a byte slice via [`Debug`].
///
/// Bytes for which the `is_printable` predicate returns `true` are emitted as
/// their corresponding `char`; all other bytes are written as a two-digit
/// uppercase hex escape (for example, `\x1F`).
///
/// An empty byte slice and a truncation length of zero both render as `""`.
/// When a truncation length is set and `bytes.len()` exceeds it, only the
/// first `truncate_len` bytes are rendered and the output is suffixed with
/// `...`.
pub struct FormatBytes<'a, F> {
    bytes: &'a [u8],

    /// If present, the maximum number of bytes from `bytes` to include in the
    /// rendered output. If `bytes.len()` exceeds this, the output is suffixed
    /// with `...`.
    truncate_len_opt: Option<usize>,

    /// Decides whether a given byte is emitted as a `char` (`true`) or as a
    /// hex escape (`false`).
    is_printable: &'a F,
}

impl<'a> FormatBytes<'a, fn(u8) -> bool> {
    /// Returns a [`FormatBytes`] that renders `bytes` in full, treating ASCII
    /// codepoints 32-126 as printable.
    pub fn printable_ascii(bytes: &'a [u8]) -> Self {
        Self {
            bytes,
            truncate_len_opt: None,
            is_printable: &(is_printable_ascii as fn(u8) -> bool),
        }
    }

    /// Returns a [`FormatBytes`] that renders at most `truncate_len` bytes
    /// from `bytes`, treating ASCII codepoints 32-126 as printable.
    pub fn printable_ascii_truncating(bytes: &'a [u8], truncate_len: usize) -> Self {
        Self {
            bytes,
            truncate_len_opt: Some(truncate_len),
            is_printable: &(is_printable_ascii as fn(u8) -> bool),
        }
    }
}

impl<'a, F> FormatBytes<'a, F>
where
    F: Fn(u8) -> bool,
{
    /// Returns a [`FormatBytes`] that renders at most `truncate_len_opt` bytes
    /// from `bytes` (or all of `bytes` if `None`), using `is_printable` to
    /// decide which bytes are emitted as `char`s rather than as hex escapes.
    pub fn truncating_with_predicate(
        bytes: &'a [u8],
        truncate_len_opt: Option<usize>,
        is_printable: &'a F,
    ) -> Self {
        Self {
            bytes,
            truncate_len_opt,
            is_printable,
        }
    }
}

impl<'a, F> Debug for FormatBytes<'a, F>
where
    F: Fn(u8) -> bool,
{
    fn fmt(&self, f: &mut Formatter<'_>) -> fmt::Result {
        if self.bytes.is_empty() || self.truncate_len_opt.is_some_and(|len| len == 0) {
            // Empty string representation.
            write!(f, "\"\"")?;
            return Ok(());
        }
        let limit = self.truncate_len_opt.unwrap_or(usize::MAX);
        for b in self.bytes.iter().take(limit) {
            if (self.is_printable)(*b) {
                write!(f, "{}", *b as char)?;
            } else {
                write!(f, "\\x{:02X}", *b)?;
            }
        }

        if limit < self.bytes.len() {
            write!(f, "...")?;
        }
        Ok(())
    }
}

/// Returns whether the character represented by `b` is printable ASCII.
fn is_printable_ascii(b: u8) -> bool {
    (32..=126).contains(&b)
}

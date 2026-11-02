use caching_rust_util::format_bytes::FormatBytes;

#[test]
fn to_string() {
    // Test plan: run the Debug rendering on the end keys, some printable and some non-printable
    // keys. Ensure that the human-readable strings are as expected for byte arrays.
    let min: &[u8] = &[];
    let printable: &[u8] = b"Hello";
    let unprintable: &[u8] = &[0, 20, 31, 127, 221, 255, 128];
    let partial: &[u8] = &[0, 20, 65, 31, 127, 97, 221, 255, 99, 128];
    let eight_byte_printable: &[u8] = b"longword";

    assert_eq!(format!("{:?}", FormatBytes::printable_ascii(min)), "\"\"");
    assert_eq!(format!("{:?}", FormatBytes::printable_ascii(printable)), "Hello");
    assert_eq!(
        format!("{:?}", FormatBytes::printable_ascii(unprintable)),
        r"\x00\x14\x1F\x7F\xDD\xFF\x80"
    );
    assert_eq!(
        format!("{:?}", FormatBytes::printable_ascii(partial)),
        r"\x00\x14A\x1F\x7Fa\xDD\xFFc\x80"
    );
    assert_eq!(format!("{:?}", FormatBytes::printable_ascii(eight_byte_printable)), "longword");
}

#[test]
fn to_string_truncated() {
    // Test plan: check that the Debug rendering truncates long strings correctly.
    let bytes: &[u8] = b"The quick brown fox jumps over the lazy dog";
    assert_eq!(
        format!("{:?}", FormatBytes::printable_ascii_truncating(bytes, 16)),
        "The quick brown ..."
    );

    // Boundary cases.
    assert_eq!(format!("{:?}", FormatBytes::printable_ascii_truncating(bytes, 0)), "\"\"");
    assert_eq!(
        format!("{:?}", FormatBytes::printable_ascii_truncating(bytes, bytes.len() - 1)),
        "The quick brown fox jumps over the lazy do..."
    );
    assert_eq!(
        format!("{:?}", FormatBytes::printable_ascii_truncating(bytes, bytes.len())),
        "The quick brown fox jumps over the lazy dog"
    );
    assert_eq!(
        format!("{:?}", FormatBytes::printable_ascii_truncating(bytes, bytes.len() + 1)),
        "The quick brown fox jumps over the lazy dog"
    );
}

#[test]
fn override_is_printable() {
    // Test plan: Verify that FormatBytes respects custom is_printable predicates when converting
    // bytes to human-readable format. The default is_printable considers ASCII chars 32-126 as
    // printable, but this test overrides it to only consider letters as printable. Digits "123"
    // should be hex-escaped as \x31\x32\x33 while letters "ABC" remain as-is.
    let bytes: &[u8] = b"ABC123";
    let is_letter = |b: u8| (b as char).is_alphabetic();
    assert_eq!(
        format!("{:?}", FormatBytes::truncating_with_predicate(bytes, None, &is_letter)),
        r"ABC\x31\x32\x33"
    );
}

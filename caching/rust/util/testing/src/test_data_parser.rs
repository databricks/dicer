use buffa::{Message as BuffaMessage, MessageName};
use prost::{Message as ProstMessage, Name};

/// Parses the contents of the file at `path`, which is expected to contain a proto of type `B` in
/// text proto format, and converts it to the Prost type `P` for the same protobuf message.
///
/// # Arguments
///
/// * `path` - The path to the textproto file to parse.
///
/// # Returns
///
/// The parsed proto message of type `P`.
///
/// # Panics
///
/// This function supports test code only, and will panic if:
/// - The file cannot be read.
/// - The file cannot be parsed as the specified proto type.
/// - `B` and `P` are not generated from the same protobuf message.
pub fn parse_test_data<
    B: buffa::text::TextFormat + BuffaMessage + MessageName + Default,
    P: ProstMessage + Name + Default,
>(
    path: &str,
) -> P {
    assert_eq!(B::FULL_NAME, P::full_name(), "expected the same protobuf message");
    let buffa_message: B = parse_buffa_test_data(path);

    // Buffa and Prost types generated from the same message share a wire format, so round-trip
    // through bytes.
    P::decode(buffa_message.encode_to_vec().as_slice())
        .expect("Buffa message must decode with the same Prost schema")
}

/// Parses the contents of the file at `path`, which is expected to contain a proto of type `T` in
/// text proto format, where `T` is a buffa-generated message.
///
/// Requires the `text` codegen flag on `T`'s `rust_buffa_library` target.
///
/// # Arguments
///
/// * `path` - The path to the textproto file to parse.
///
/// # Returns
///
/// The parsed proto message of type `T`.
///
/// # Panics
///
/// This function supports test code only, and will panic if:
/// - The file cannot be read.
/// - The file cannot be parsed as the specified proto type.
pub fn parse_buffa_test_data<T: buffa::text::TextFormat + Default>(path: &str) -> T {
    let contents = std::fs::read_to_string(path)
        .unwrap_or_else(|err| panic!("failed to read contents of {:?}: {:?}", path, err));

    buffa::text::decode_from_str(&contents)
        .unwrap_or_else(|err| panic!("unable to parse {:?} as textproto: {:?}", path, err))
}

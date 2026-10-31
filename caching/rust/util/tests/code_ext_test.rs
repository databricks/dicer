use tonic::Code;
use caching_rust_util::code_ext::CodeExt;

#[test]
fn code_strings_match_patched_tonic() {
    // Test plan: Verify every tonic code has the expected metric label and error code,
    // ensuring that the internal patch and OSS implemenation are in sync. Validate this
    // by iterating through each `tonic::Code` and verifying that calling `as_upper_str`
    // and `error_code` returns the expected string.
    let test_cases = [
        (Code::Ok, "OK", "OK"),
        (Code::Cancelled, "CANCELLED", "CANCELLED"),
        (Code::Unknown, "UNKNOWN", "UNKNOWN"),
        (Code::InvalidArgument, "INVALID_ARGUMENT", "INVALID_PARAMETER_VALUE"),
        (Code::DeadlineExceeded, "DEADLINE_EXCEEDED", "DEADLINE_EXCEEDED"),
        (Code::NotFound, "NOT_FOUND", "NOT_FOUND"),
        (Code::AlreadyExists, "ALREADY_EXISTS", "ALREADY_EXISTS"),
        (Code::PermissionDenied, "PERMISSION_DENIED", "PERMISSION_DENIED"),
        (Code::ResourceExhausted, "RESOURCE_EXHAUSTED", "RESOURCE_EXHAUSTED"),
        (Code::FailedPrecondition, "FAILED_PRECONDITION", "BAD_REQUEST"),
        (Code::Aborted, "ABORTED", "ABORTED"),
        (Code::OutOfRange, "OUT_OF_RANGE", "OUT_OF_RANGE"),
        (Code::Unimplemented, "UNIMPLEMENTED", "NOT_IMPLEMENTED"),
        (Code::Internal, "INTERNAL", "INTERNAL_ERROR"),
        (Code::Unavailable, "UNAVAILABLE", "UNAVAILABLE"),
        (Code::DataLoss, "DATA_LOSS", "DATA_LOSS"),
        (Code::Unauthenticated, "UNAUTHENTICATED", "UNAUTHENTICATED"),
    ];

    for (code, expected_upper, expected_error_code) in test_cases {
        assert_eq!(code.as_upper_str(), expected_upper, "{code:?}");
        assert_eq!(code.error_code(), expected_error_code, "{code:?}");
    }
}

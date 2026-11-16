use std::sync::LazyLock;

use caching_rust_util_testing::parse_test_data;
// Re-export the slice_key_test_data_p module for use in tests.
pub use dicer_common_test_proto_test_data_proto::dicer::test::slice_key_test_data_p;
use dicer_common_test_proto_test_data_proto::dicer::test::{
    AssignerServiceInfoTestDataP,
    AssignmentTestDataP,
    ClientRequestTestDataP,
    ClientResponseTestDataP,
    DiffAssignmentTestDataP,
    DpageViewHelpersTestDataP,
    GenerationTestDataP,
    ResourceAddressTestDataP,
    ShouldServeRequestTargetTestDataP,
    SliceAssignmentTestDataP,
    SliceKeyTestDataP,
    SliceLoadTestDataP,
    SliceMapTestDataP,
    SliceSetTestDataP,
    SliceletDataTestDataP,
    SliceletLoadAccumulatorTestDataP,
    SliceletTestDataP,
    SquidTestDataP,
    TargetTestDataP,
    TransferTestDataP,
};
use dicer_common_test_proto_test_data_proto_buffa::dicer::test as buffa_test_data;
use dicer_friend_external_test_proto_test_data_proto::dicer::friend::external::test::TwoLevelShardingHeadersTestDataP;
use dicer_friend_external_test_proto_test_data_proto_buffa::dicer::friend::external::test as buffa_friend_test_data;

/// Test data for `AssignerServiceInfo` suites.
pub static ASSIGNER_SERVICE_INFO_TEST_DATA: LazyLock<AssignerServiceInfoTestDataP> =
    LazyLock::new(|| {
        parse_test_data::<buffa_test_data::AssignerServiceInfoTestDataP, _>(
            "dicer/common/test/data/assigner_service_info_test_data.textproto",
        )
    });

/// Test data for `Assignment` suites.
pub static ASSIGNMENT_TEST_DATA: LazyLock<AssignmentTestDataP> = LazyLock::new(|| {
    parse_test_data::<buffa_test_data::AssignmentTestDataP, _>(
        "dicer/common/test/data/assignment_test_data.textproto",
    )
});

/// Test data for `ClientRequest` suites.
pub static CLIENT_REQUEST_TEST_DATA: LazyLock<ClientRequestTestDataP> = LazyLock::new(|| {
    parse_test_data::<buffa_test_data::ClientRequestTestDataP, _>(
        "dicer/common/test/data/client_request_test_data.textproto",
    )
});

/// Test data for `ClientResponse` suites.
pub static CLIENT_RESPONSE_TEST_DATA: LazyLock<ClientResponseTestDataP> = LazyLock::new(|| {
    parse_test_data::<buffa_test_data::ClientResponseTestDataP, _>(
        "dicer/common/test/data/client_response_test_data.textproto",
    )
});

/// Test data for `DiffAssignment` suites.
pub static DIFF_ASSIGNMENT_TEST_DATA: LazyLock<DiffAssignmentTestDataP> = LazyLock::new(|| {
    parse_test_data::<buffa_test_data::DiffAssignmentTestDataP, _>(
        "dicer/common/test/data/diff_assignment_test_data.textproto",
    )
});

/// Test data for DPage view helpers.
pub static DPAGE_VIEW_HELPERS_TEST_DATA: LazyLock<DpageViewHelpersTestDataP> =
    LazyLock::new(|| {
        parse_test_data::<buffa_test_data::DpageViewHelpersTestDataP, _>(
            "dicer/common/test/data/dpage_view_helpers_test_data.textproto",
        )
    });

/// Test data for `should_serve_request_target` tests.
pub static SHOULD_SERVE_REQUEST_TARGET_TEST_DATA: LazyLock<ShouldServeRequestTargetTestDataP> =
    LazyLock::new(|| {
        parse_test_data::<buffa_test_data::ShouldServeRequestTargetTestDataP, _>(
            "dicer/common/test/data/should_serve_request_target_test_data.textproto",
        )
    });

/// Test data for generation suites.
pub static GENERATION_TEST_DATA: LazyLock<GenerationTestDataP> = LazyLock::new(|| {
    parse_test_data::<buffa_test_data::GenerationTestDataP, _>(
        "dicer/common/test/data/generation_test_data.textproto",
    )
});

/// Test data for `SliceAssignment` suites.
pub static SLICE_ASSIGNMENT_TEST_DATA: LazyLock<SliceAssignmentTestDataP> = LazyLock::new(|| {
    parse_test_data::<buffa_test_data::SliceAssignmentTestDataP, _>(
        "dicer/common/test/data/slice_assignment_test_data.textproto",
    )
});

/// Test data for `SliceMap` suites.
pub static SLICE_MAP_TEST_DATA: LazyLock<SliceMapTestDataP> = LazyLock::new(|| {
    parse_test_data::<buffa_test_data::SliceMapTestDataP, _>(
        "dicer/common/test/data/slice_map_test_data.textproto",
    )
});

/// Test data for `SliceSet` suites.
pub static SLICE_SET_TEST_DATA: LazyLock<SliceSetTestDataP> = LazyLock::new(|| {
    parse_test_data::<buffa_test_data::SliceSetTestDataP, _>(
        "dicer/common/test/data/slice_set_test_data.textproto",
    )
});

/// Test data for Slicelet suites.
pub static SLICELET_TEST_DATA: LazyLock<SliceletTestDataP> = LazyLock::new(|| {
    parse_test_data::<buffa_test_data::SliceletTestDataP, _>(
        "dicer/common/test/data/slicelet_test_data.textproto",
    )
});

/// Test data for SQUID suites.
pub static SQUID_TEST_DATA: LazyLock<SquidTestDataP> = LazyLock::new(|| {
    parse_test_data::<buffa_test_data::SquidTestDataP, _>(
        "dicer/common/test/data/squid_test_data.textproto",
    )
});

/// Test data for target suites.
pub static TARGET_TEST_DATA: LazyLock<TargetTestDataP> = LazyLock::new(|| {
    parse_test_data::<buffa_test_data::TargetTestDataP, _>(
        "dicer/common/test/data/target_test_data.textproto",
    )
});

/// Test data for `Transfer` suites.
pub static TRANSFER_TEST_DATA: LazyLock<TransferTestDataP> = LazyLock::new(|| {
    parse_test_data::<buffa_test_data::TransferTestDataP, _>(
        "dicer/common/test/data/transfer_test_data.textproto",
    )
});

/// Test data for SliceLoad suites.
pub static SLICE_LOAD_TEST_DATA: LazyLock<SliceLoadTestDataP> = LazyLock::new(|| {
    parse_test_data::<buffa_test_data::SliceLoadTestDataP, _>(
        "dicer/common/test/data/slice_load_test_data.textproto",
    )
});

/// Test data for SliceletData suites.
pub static SLICELET_DATA_TEST_DATA: LazyLock<SliceletDataTestDataP> = LazyLock::new(|| {
    parse_test_data::<buffa_test_data::SliceletDataTestDataP, _>(
        "dicer/common/test/data/slicelet_data_test_data.textproto",
    )
});

/// Test data for SliceletLoadAccumulator suites.
pub static SLICELET_LOAD_ACCUMULATOR_TEST_DATA: LazyLock<SliceletLoadAccumulatorTestDataP> =
    LazyLock::new(|| {
        parse_test_data::<buffa_test_data::SliceletLoadAccumulatorTestDataP, _>(
            "dicer/common/test/data/slicelet_load_accumulator_test_data.textproto",
        )
    });

/// Test data for per-Slice load-distribution reporting. Separated from
/// SLICELET_LOAD_ACCUMULATOR_TEST_DATA because Scala SliceletLoadAccumulator doesn't support load
/// distribution reporting yet, so we can run them only against Rust SliceletLoadAccumulator.
pub static SLICELET_LOAD_ACCUMULATOR_DISTRIBUTION_TEST_DATA: LazyLock<
    SliceletLoadAccumulatorTestDataP,
> = LazyLock::new(|| {
    parse_test_data::<buffa_test_data::SliceletLoadAccumulatorTestDataP, _>(
        "dicer/common/test/data/slicelet_load_accumulator_distribution_test_data.textproto",
    )
});

/// Test data for SliceKey suites.
pub static SLICE_KEY_TEST_DATA: LazyLock<SliceKeyTestDataP> = LazyLock::new(|| {
    parse_test_data::<buffa_test_data::SliceKeyTestDataP, _>(
        "dicer/common/test/data/slice_key_test_data.textproto",
    )
});

/// Test data for ResourceAddress suites.
pub static RESOURCE_ADDRESS_TEST_DATA: LazyLock<ResourceAddressTestDataP> = LazyLock::new(|| {
    parse_test_data::<buffa_test_data::ResourceAddressTestDataP, _>(
        "dicer/common/test/data/resource_address_test_data.textproto",
    )
});

/// Test data for TwoLevelShardingHeaders suites.
pub static TWO_LEVEL_SHARDING_HEADERS_TEST_DATA: LazyLock<TwoLevelShardingHeadersTestDataP> =
    LazyLock::new(|| {
        parse_test_data::<buffa_friend_test_data::TwoLevelShardingHeadersTestDataP, _>(
            "dicer/friend/external/test/data/two_level_sharding_headers_test_data.textproto",
        )
    });

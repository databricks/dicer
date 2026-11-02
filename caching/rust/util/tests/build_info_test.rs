use caching_rust_util::build_info::BuildInfo;
use caching_rust_util_testing::test_data::BUILD_INFO_TEST_DATA;
use caching_util_test_proto_test_data_proto_buffa::databricks::caching::test::build_info_test_data_p::BuildInfoP;

#[test]
fn test_build_info_parsing() {
    // Test plan: Verify that all test cases are parsed correctly according to the test data

    for test_case in &BUILD_INFO_TEST_DATA.test_cases {
        // Get the input branch name from the test case.
        let input_branch_name: &str = test_case
            .input_branch_name
            .as_deref()
            .expect("input_branch_name should not be None");

        let build_info = BuildInfo::from_client_version(input_branch_name);

        // Get the expected output.
        let expected_output: &BuildInfoP = &test_case.expected_build_info;

        // Verify that the get_branch_name_opt returns the expected branch name.
        let branch_name_opt: Option<&str> = build_info.get_branch_name_opt();
        let expected_branch_name_opt: Option<&str> = expected_output.branch_name.as_deref();
        assert_eq!(branch_name_opt, expected_branch_name_opt);

        // Verify that the get_branch_name_label_value returns the expected branch name label value.
        let branch_name_label_value: &str = build_info.get_branch_name_label_value();
        let expected_branch_name_label_value: Option<&str> =
            expected_output.branch_name_label_value.as_deref();
        assert_eq!(Some(branch_name_label_value), expected_branch_name_label_value);

        // Verify that the get_commit_time_label_value returns the expected commit time label value.
        let commit_time_label_value: String = build_info.get_commit_time_label_value();
        let expected_commit_time_label_value: Option<&str> =
            expected_output.commit_time_label_value.as_deref();
        assert_eq!(Some(commit_time_label_value.as_str()), expected_commit_time_label_value);

        // Verify that the get_commit_time_epoch_millis returns the expected commit time epoch
        // millis.
        let commit_time_epoch_millis: i64 = build_info.get_commit_time_epoch_millis();
        let expected_commit_time_epoch_millis: Option<i64> =
            expected_output.commit_time_epoch_millis;
        assert_eq!(Some(commit_time_epoch_millis), expected_commit_time_epoch_millis);

        // Verify that the get_commit_hash_label_value returns the expected commit hash label value.
        let commit_hash_label_value: &str = build_info.get_commit_hash_label_value();
        let expected_commit_hash_label_value: Option<&str> =
            expected_output.commit_hash_label_value.as_deref();
        assert_eq!(Some(commit_hash_label_value), expected_commit_hash_label_value);
    }
}

use dicer_rust_common::{get_default_watch_rpc_timeout, MIN_WATCH_RPC_TIMEOUT};

#[test]
fn default_watch_rpc_timeout() {
    // Test plan: verify that the default watch rpc timeout can safely be converted to a
    // ProtocolDuration, and is greater than the minimum required watch rpc timeout.
    let default_timeout = get_default_watch_rpc_timeout();
    assert!(default_timeout.into_duration() > MIN_WATCH_RPC_TIMEOUT);
}

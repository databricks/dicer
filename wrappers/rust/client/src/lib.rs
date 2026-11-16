//! gRPC client channel type.

/// A gRPC client channel. Clones are cheap and share the underlying connection.
pub type GrpcChannel = tonic::transport::Channel;

//! Batch processing module with factory/builder patterns and DRY implementation

pub mod common;
pub mod factory;
pub mod tests;

// Re-export key types and functions for convenience
pub use common::*;
pub use factory::*;
pub use tests::*;
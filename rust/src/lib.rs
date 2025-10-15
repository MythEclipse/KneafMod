pub mod parallelism;
pub mod performance;
pub mod simd;
pub mod spatial;
pub mod jni;
pub mod memory;
pub mod entities;
pub mod errors;
pub mod logging;
pub mod traits;
pub mod types;
pub mod binary;
pub mod compression;
pub mod batch;
pub mod storage;

// Export all public modules for use outside the crate
pub use parallelism::*;
pub use performance::*;
pub use simd::*;
pub use spatial::*;
pub use jni::*;
pub use memory::*;
pub use entities::*;
pub use errors::*;
pub use logging::*;
pub use traits::*;
pub use types::*;
pub use binary::*;
pub use compression::*;
pub use batch::*;
pub use storage::*;

// Export verification test for manual execution
#[cfg(test)]
pub mod refactoring_verification_test;

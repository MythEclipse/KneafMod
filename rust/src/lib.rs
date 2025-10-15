pub mod parallelism;
pub mod performance_monitoring;
pub mod simd;
pub mod simd_standardized;
pub mod errors;
pub mod logging;
pub mod traits;
pub mod types;

// Export verification test for manual execution
#[cfg(test)]
pub mod refactoring_verification_test;

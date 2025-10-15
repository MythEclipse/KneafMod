//! SIMD (Single Instruction, Multiple Data) module containing all SIMD-related functionality

// Re-export public items from submodules
pub mod base;
pub mod enhanced;
pub mod standardized;

// Re-export key types and functions for convenience
pub use base::{
    SimdError, SimdResult, SimdLevel, SimdConfig, SimdFeatures, SimdProcessor, get_simd_manager,
    vector_ops,
};
pub use enhanced::{SimdCapability, detect_simd_capability, set_simd_capability, get_simd_capability};
pub use standardized::{
    SimdStandardOps, StandardSimdOps, STANDARD_SIMD_OPS, get_standard_simd_ops,
};
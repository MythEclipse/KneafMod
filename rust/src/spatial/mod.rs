//! Spatial operations module

// Re-export all items from both base and optimized modules
pub mod base;
pub mod optimized;
pub use base::*;
pub use optimized::*;
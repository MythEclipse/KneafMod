//! Villager entity processing module

pub mod types;
pub mod processing;
pub mod config;
pub mod bindings;
pub mod pathfinding;
pub mod spatial;

// Re-export common entity types for convenience
pub use crate::entities::common::types::*;
pub use crate::entities::common::factory::*;

// Re-export villager-specific types
pub use types::*;
pub use processing::*;
pub use config::*;
pub use bindings::*;
pub use pathfinding::*;
pub use spatial::*;
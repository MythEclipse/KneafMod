pub mod config;
pub mod spatial;
pub mod components;
pub mod resources;
pub mod events;
pub mod systems;
pub mod app;

// Re-export existing types for integration
pub use crate::entity::{Config as EntityConfig, Input as EntityInput, ProcessResult as EntityProcessResult};




// Re-exports
pub use config::*;
pub use spatial::*;
pub use components::*;
pub use resources::*;
pub use events::*;
pub use systems::*;
pub use app::*;
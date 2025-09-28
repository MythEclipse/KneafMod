pub mod config;
pub mod components;
pub mod resources;
pub mod events;
pub mod systems;
pub mod app;

// Re-export existing types for integration
pub use crate::entity::config::Config as EntityConfig;
pub use crate::entity::types::Input as EntityInput;
pub use crate::entity::types::ProcessResult as EntityProcessResult;




// Re-exports
pub use config::*;
pub use components::*;
pub use resources::*;
pub use events::*;
pub use systems::*;
pub use app::*;
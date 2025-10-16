pub mod entity_state;
pub mod thread_safe_state_manager;

// Re-export commonly used types
pub use entity_state::{EntityState, StateManager};
pub use thread_safe_state_manager::{ThreadSafeEntityState, ThreadSafeStateManager};
// Re-export memory management components
pub mod allocator;
pub mod arena;
pub mod pressure_config;
pub mod zero_copy;

// Re-export memory monitoring components
pub mod monitoring;

// Re-export memory pool components
pub mod pool;

// Re-export background cleanup components
pub mod cleanup;

// Public API re-exports for convenience
pub use allocator::*;
pub use arena::*;
pub use pressure_config::*;
pub use zero_copy::*;
pub use monitoring::*;
pub use pool::*;
pub use cleanup::*;
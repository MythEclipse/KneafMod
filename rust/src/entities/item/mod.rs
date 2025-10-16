pub mod types;
pub mod processing;
pub mod config;
pub mod bindings;

// Re-export main types
pub use types::{ItemData, ItemStack, ItemType};
pub use processing::process_items;
pub use types::{ItemInput, ItemProcessResult};
pub use config::ItemConfig;
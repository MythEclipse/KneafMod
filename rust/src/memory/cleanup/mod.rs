// Memory cleanup module
pub mod background_cleanup;
pub mod examples;
pub mod integration_example;
pub mod tests;

// Re-export cleanup functionality
pub use background_cleanup::*;
pub use examples::*;
pub use integration_example::*;
pub use tests::*;
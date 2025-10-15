use crate::errors::{RustError, Result};
/// Trait for components that need initialization checks
pub trait Initializable {
    /// Check if the component is initialized
    /// Returns Ok(()) if initialized, Err(RustError) if not
    fn check_initialized(&self) -> Result<()>;
    
    /// Get a human-readable name for the component (used in error messages)
    fn component_name(&self) -> &str;
}

/// Macro to implement Initializable for types with an initialized flag
#[macro_export]
macro_rules! impl_initializable {
    ($type:ty, $initialized_field:ident, $component_name:expr) => {
        impl Initializable for $type {
            fn check_initialized(&self) -> std::result::Result<(), crate::errors::RustError> {
                if !self.$initialized_field.load(std::sync::atomic::Ordering::Relaxed) {
                    return Err(crate::errors::RustError::NotInitializedError(
                        format!("{} not initialized", $component_name)
                    ));
                }
                Ok(())
            }
            
            fn component_name(&self) -> &str {
                $component_name
            }
        }
    };
}

/// Helper function to create a not initialized error for a component
pub fn not_initialized_error(component_name: &str) -> RustError {
    RustError::NotInitializedError(format!("{} not initialized", component_name))
}
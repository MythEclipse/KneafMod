use crate::errors::{RustError, Result};
use crate::types::{Aabb, EntityData, EntityType};
use std::sync::atomic::Ordering;

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
                if !self.$initialized_field.load(Ordering::Relaxed) {
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

/// Unified Entity trait with associated types for different entity implementations
pub trait Entity: Send + Sync {
    /// Associated type for entity data
    type Data: EntityDataTrait;
    
    /// Associated type for entity configuration
    type Config: EntityConfigTrait;
    
    /// Get the entity ID
    fn id(&self) -> u64;
    
    /// Get the entity type
    fn entity_type(&self) -> &EntityType;
    
    /// Get the entity position
    fn position(&self) -> (f64, f64, f64);
    
    /// Check if the entity is within bounds
    fn is_within_bounds(&self, bounds: &Aabb) -> bool;
    
    /// Create a new entity instance
    fn new(data: Self::Data, config: &Self::Config) -> Result<Self> where Self: Sized;
}

/// Trait for entity data types
pub trait EntityDataTrait {
    /// Get the entity ID from the data
    fn id(&self) -> u64;
    
    /// Get the entity type from the data
    fn entity_type(&self) -> &EntityType;
    
    /// Get the entity position from the data
    fn position(&self) -> (f64, f64, f64);
}

/// Trait for entity configuration types
use std::fmt::Debug;

pub trait EntityConfigTrait: Debug + Send + Sync {
    /// Get the close radius from the configuration
    fn close_radius(&self) -> f32;
    
    /// Get the medium radius from the configuration
    fn medium_radius(&self) -> f32;
    
    /// Get the close rate from the configuration
    fn close_rate(&self) -> f32;
    
    /// Get the medium rate from the configuration
    fn medium_rate(&self) -> f32;
    
    /// Check if spatial partitioning is enabled
    fn use_spatial_partitioning(&self) -> bool;
}

/// Default entity configuration struct
#[derive(Debug, Clone)]
pub struct EntityConfigImpl {
    pub close_radius: f32,
    pub medium_radius: f32,
    pub close_rate: f32,
    pub medium_rate: f32,
    pub use_spatial_partitioning: bool,
}

// Implement EntityDataTrait for EntityData
impl EntityDataTrait for EntityData {
    fn id(&self) -> u64 {
        self.entity_id.parse::<u64>().unwrap_or(0)
    }
    
    fn entity_type(&self) -> &EntityType {
        &self.entity_type
    }
    
    fn position(&self) -> (f64, f64, f64) {
        (self.x as f64, self.y as f64, self.z as f64)
    }
}

// Implement EntityConfigTrait for EntityConfigImpl
impl EntityConfigTrait for EntityConfigImpl {
    fn close_radius(&self) -> f32 {
        self.close_radius
    }
    
    fn medium_radius(&self) -> f32 {
        self.medium_radius
    }
    
    fn close_rate(&self) -> f32 {
        self.close_rate
    }
    
    fn medium_rate(&self) -> f32 {
        self.medium_rate
    }
    
    fn use_spatial_partitioning(&self) -> bool {
        self.use_spatial_partitioning
    }
}
use std::sync::Arc;

/// Trait defining the configuration for any entity type
pub trait EntityConfig: Send + Sync {
    /// Get the entity type identifier
    fn get_entity_type(&self) -> &str;
    
    /// Get a clone of this configuration
    fn clone_box(&self) -> Box<dyn EntityConfig>;
}

/// Default implementation for entity configuration
#[derive(Debug, Default, Clone)]
pub struct DefaultEntityConfig {
    pub entity_type: String,
}

impl EntityConfig for DefaultEntityConfig {
    fn get_entity_type(&self) -> &str {
        &self.entity_type
    }
    
    fn clone_box(&self) -> Box<dyn EntityConfig> {
        Box::new(self.clone())
    }
}

/// Type alias for Arc-wrapped EntityConfig
pub type ArcEntityConfig = Arc<dyn EntityConfig>;

/// Trait defining the interface for entity processors
pub trait EntityProcessor: Send + Sync {
    /// Process entities
    fn process(&self) -> Result<(), String>;
}
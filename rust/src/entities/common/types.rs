use std::sync::Arc;

/// Trait defining the configuration for any entity type
pub trait EntityConfig: Send + Sync {
    /// Get the entity type identifier
    fn get_entity_type(&self) -> &str;
    
    /// Get a clone of this configuration
    fn clone_box(&self) -> Box<dyn EntityConfig>;

    /// Get disable AI distance
    fn get_disable_ai_distance(&self) -> f32 { 32.0 }

    /// Get simplify AI distance
    fn get_simplify_ai_distance(&self) -> f32 { 64.0 }

    /// Get reduce pathfinding distance
    fn get_reduce_pathfinding_distance(&self) -> Option<f32> { Some(128.0) }

    /// Get max entities per group
    fn get_max_entities_per_group(&self) -> usize { 10 }

    /// Get spatial grid size
    fn get_spatial_grid_size(&self) -> f32 { 100.0 }
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
    
    /// Process entities with input
    fn process_entities(&self, input: crate::EntityProcessingInput) -> crate::EntityProcessingResult;
    
    /// Update configuration
    fn update_config(&self, config: ArcEntityConfig);
    
    /// Process entities in batch
    fn process_entities_batch(&self, inputs: Vec<crate::EntityProcessingInput>) -> Vec<crate::EntityProcessingResult>;
    
    /// Get current configuration
    fn get_config(&self) -> ArcEntityConfig;
}
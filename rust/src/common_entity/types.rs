use serde::{Deserialize, Serialize};
use std::sync::Arc;

/// Common entity type enum
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum EntityType {
    /// Mob entity type
    Mob,
    /// Villager entity type
    Villager,
    /// Other entity types can be added here
    Other(String),
}

/// Common entity data structure
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EntityData {
    pub id: u64,
    pub entity_type: EntityType,
    pub position: (f32, f32, f32),
    pub distance: f32,
    pub metadata: Option<serde_json::Value>,
}

/// Common input structure for entity processing
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EntityProcessingInput {
    pub tick_count: u64,
    pub entities: Vec<EntityData>,
    pub players: Option<Vec<PlayerData>>,
}

/// Common result structure for entity processing
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EntityProcessingResult {
    pub entities_to_disable_ai: Vec<u64>,
    pub entities_to_simplify_ai: Vec<u64>,
    pub entities_to_reduce_pathfinding: Option<Vec<u64>>,
    pub groups: Option<Vec<EntityGroup>>,
    pub processing_stats: ProcessingStats,
}

/// Player data structure
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PlayerData {
    pub id: u64,
    pub position: (f32, f32, f32),
}

/// Entity group structure
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EntityGroup {
    pub group_id: u32,
    pub center_position: (f32, f32, f32),
    pub member_ids: Vec<u64>,
    pub group_type: String,
    pub metadata: Option<serde_json::Value>,
}

/// Processing statistics structure
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct ProcessingStats {
    pub total_entities_processed: usize,
    pub ai_disabled_count: usize,
    pub ai_simplified_count: usize,
    pub groups_formed: usize,
    pub simd_operations_used: usize,
    pub memory_allocations: usize,
    pub processing_time_ms: u64,
}

/// Entity configuration trait
pub trait EntityConfig: Send + Sync + Clone + Serialize + Deserialize<'static> {
    /// Get the distance threshold for disabling AI
    fn get_disable_ai_distance(&self) -> f32;
    
    /// Get the distance threshold for simplifying AI
    fn get_simplify_ai_distance(&self) -> f32;
    
    /// Get the distance threshold for reducing pathfinding
    fn get_reduce_pathfinding_distance(&self) -> Option<f32>;
}

/// Entity processor trait
pub trait EntityProcessor: Send + Sync {
    /// Process entities with the given input
    fn process_entities(&mut self, input: EntityProcessingInput) -> EntityProcessingResult;
    
    /// Process entities in batch mode
    fn process_entities_batch(&mut self, inputs: Vec<EntityProcessingInput>) -> Vec<EntityProcessingResult>;
    
    /// Get the configuration for this processor
    fn get_config(&self) -> Arc<dyn EntityConfig>;
    
    /// Update the configuration for this processor
    fn update_config(&mut self, new_config: Arc<dyn EntityConfig>);
}

/// Entity processor factory trait
pub trait EntityProcessorFactory {
    /// Create a new entity processor for the given entity type
    fn create_processor(&self, entity_type: EntityType) -> Arc<dyn EntityProcessor>;
}
use std::fmt::Debug;
use std::sync::Arc;
use std::collections::HashMap;
use serde::{Deserialize, Serialize};

/// Spatial groups for entity organization
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SpatialGroups {
    pub group_type: GroupType,
    pub entity_ids: Vec<u64>,
    pub center: (f32, f32, f32),
    pub radius: f32,
}

/// Processing statistics for entity operations
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct ProcessingStats {
    pub operation_time: f64,
    pub memory_used: usize,
    pub entities_processed: u64,
    pub success_rate: f32,
}

/// Execution strategy for entity processing
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ExecutionStrategy {
    /// Default execution strategy
    Default,
    /// Priority-based execution
    Priority,
    /// Batch processing
    Batch,
    /// Real-time processing
    RealTime,
}

/// Error type for execution failures
#[derive(Debug, Clone)]
pub struct ExecutionError {
    pub message: String,
    pub error_type: ExecutionErrorType,
}

/// Type of execution error
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ExecutionErrorType {
    /// Invalid configuration
    InvalidConfig,
    /// Resource exhaustion
    ResourceExhaustion,
    /// Timeout
    Timeout,
    /// Unknown error
    Unknown,
}

/// Configuration for entities
pub trait EntityConfig: Debug + Send + Sync {
    fn clone_box(&self) -> Box<dyn EntityConfig>;
    
    /// Gets the entity type
    fn entity_type(&self) -> EntityType;
    
    /// Checks if the entity is active
    fn is_active(&self) -> bool;
    
    /// Gets the update interval
    fn update_interval(&self) -> u64;
    
    /// Execute with priority
    fn execute_with_priority(&self, priority: u8) -> Result<(), ExecutionError>;
    
    /// Get execution strategy
    fn get_execution_strategy(&self) -> ExecutionStrategy;
}


pub type BoxedEntityConfig = Box<dyn EntityConfig>;

impl Clone for BoxedEntityConfig {
    fn clone(&self) -> Self {
        self.clone_box()
    }
}

/// Default entity configuration implementation
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DefaultEntityConfig {
    pub entity_type: EntityType,
    pub is_active: bool,
    pub update_interval: u64,
    pub properties: HashMap<String, String>,
}


impl EntityConfig for DefaultEntityConfig {
    fn entity_type(&self) -> EntityType {
        self.entity_type
    }
    
    fn is_active(&self) -> bool {
        self.is_active
    }
    
    fn update_interval(&self) -> u64 {
        self.update_interval
    }
    
    fn clone_box(&self) -> Box<dyn EntityConfig> {
        Box::new(self.clone())
    }
    
    fn execute_with_priority(&self, _priority: u8) -> Result<(), ExecutionError> {
        Ok(())
    }
    
    fn get_execution_strategy(&self) -> ExecutionStrategy {
        ExecutionStrategy::Default
    }
}

impl Default for DefaultEntityConfig {
    fn default() -> Self {
        Self {
            entity_type: EntityType::Generic,
            is_active: true,
            update_interval: 1000,
            properties: HashMap::new(),
        }
    }
}

/// Type of entity
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum EntityType {
    /// Player entity
    Player,
    /// Mob entity
    Mob,
    /// Villager entity
    Villager,
    /// Block entity
    Block,
    /// Item entity
    Item,
    /// Projectile entity
    Projectile,
    /// Generic entity
    Generic,
}

/// Data representing a player
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PlayerData {
    pub uuid: String,
    pub username: String,
    pub x: f32,
    pub y: f32,
    pub z: f32,
    pub health: f32,
    pub max_health: f32,
    pub level: u32,
    pub experience: u32,
    pub inventory: Vec<String>,
}

/// Data representing an entity
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EntityData {
    pub entity_id: String,
    pub entity_type: EntityType,
    pub x: f32,
    pub y: f32,
    pub z: f32,
    pub health: f32,
    pub max_health: f32,
    pub velocity_x: f32,
    pub velocity_y: f32,
    pub velocity_z: f32,
    pub rotation: f32,
    pub pitch: f32,
    pub yaw: f32,
    pub properties: HashMap<String, String>,
}

/// Input for entity processing
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EntityProcessingInput {
    pub entity_id: String,
    pub entity_type: EntityType,
    pub data: EntityData,
    pub delta_time: f32,
    pub simulation_distance: u32,
}

/// Result of entity processing
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EntityProcessingResult {
    pub entity_id: String,
    pub success: bool,
    pub error_message: Option<String>,
    pub new_position: Option<(f32, f32, f32)>,
    pub new_velocity: Option<(f32, f32, f32)>,
    pub health_changed: Option<f32>,
    pub metadata_changed: Option<HashMap<String, String>>,
    pub entities_to_disable_ai: Vec<u64>,
    pub entities_to_simplify_ai: Vec<u64>,
    pub entities_to_reduce_pathfinding: Option<Vec<u64>>,
    pub groups: Option<SpatialGroups>,
    pub processing_stats: Option<ProcessingStats>,
}

/// Configuration for binary conversion
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BinaryConverterConfig {
    pub use_compression: bool,
    pub compression_level: u32,
    pub use_crc_checksum: bool,
    pub max_batch_size: usize,
    pub buffer_alignment: usize,
    pub validate_input: bool,
    pub compress_large_data: bool,
    pub compression_threshold: usize,
}

impl Default for BinaryConverterConfig {
    fn default() -> Self {
        Self {
            use_compression: true,
            compression_level: 6,
            use_crc_checksum: true,
            max_batch_size: 1024 * 1024, // 1MB
            buffer_alignment: 8,
            validate_input: true,
            compress_large_data: false,
            compression_threshold: 1024,
        }
    }
}

/// AABB (Axis-Aligned Bounding Box) for spatial calculations
#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
pub struct Aabb {
    pub min_x: f32,
    pub min_y: f32,
    pub min_z: f32,
    pub max_x: f32,
    pub max_y: f32,
    pub max_z: f32,
}

impl Aabb {
    /// Creates a new AABB
    pub fn new(min_x: f32, min_y: f32, min_z: f32, max_x: f32, max_y: f32, max_z: f32) -> Self {
        Self {
            min_x, min_y, min_z, max_x, max_y, max_z
        }
    }
    
    /// Checks if this AABB intersects with another
    pub fn intersects(&self, other: &Self) -> bool {
        self.min_x <= other.max_x && self.max_x >= other.min_x &&
        self.min_y <= other.max_y && self.max_y >= other.min_y &&
        self.min_z <= other.max_z && self.max_z >= other.min_z
    }
    
    /// Expands the AABB to include a point
    pub fn expand(&mut self, x: f32, y: f32, z: f32) {
        self.min_x = self.min_x.min(x);
        self.min_y = self.min_y.min(y);
        self.min_z = self.min_z.min(z);
        self.max_x = self.max_x.max(x);
        self.max_y = self.max_y.max(y);
        self.max_z = self.max_z.max(z);
    }
}

/// Statistics for pathfinding cache
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PathfindingCacheStats {
    pub hits: u64,
    pub misses: u64,
    pub evictions: u64,
    pub total_queries: u64,
    pub average_query_time: f64,
    pub cache_size: usize,
    pub max_cache_size: usize,
}

impl Default for PathfindingCacheStats {
    fn default() -> Self {
        Self {
            hits: 0,
            misses: 0,
            evictions: 0,
            total_queries: 0,
            average_query_time: 0.0,
            cache_size: 0,
            max_cache_size: 1000,
        }
    }
}

/// Group type for entities
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum GroupType {
    /// Villager group
    VillagerGroup,
    /// Passive herd
    PassiveHerd,
    /// Mixed crowd
    MixedCrowd,
    /// Player party
    PlayerParty,
    /// Monster pack
    MonsterPack,
}

// Re-export for public use
pub use self::EntityConfig as EntityConfigTrait;
pub use self::DefaultEntityConfig as DefaultEntityConfigTrait;
pub use self::EntityType as EntityTypeTrait;
pub use self::PlayerData as PlayerDataTrait;
pub use self::EntityData as EntityDataTrait;
pub use self::EntityProcessingInput as EntityProcessingInputTrait;
pub use self::EntityProcessingResult as EntityProcessingResultTrait;
pub use self::BinaryConverterConfig as BinaryConverterConfigTrait;
pub use self::Aabb as AabbTrait;
pub use self::PathfindingCacheStats as PathfindingCacheStatsTrait;
pub use self::GroupType as GroupTypeTrait;

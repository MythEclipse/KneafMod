use crate::errors::{RustError, Result};
use crate::traits::Entity;
// Remove duplicate import of RustError and Result
use glam::Vec3A;
use serde::{Deserialize, Serialize};
use std::sync::RwLock;

/// Error type for Java-Rust performance integration
#[derive(Debug, Clone, PartialEq)]
pub enum RustPerformanceError {
    /// Native library not loaded
    LibraryNotLoaded(String),
    
    /// Rust performance system not initialized
    NotInitialized(String),
    
    /// Failed to load Rust performance native library
    LibraryLoadFailed(String),
    
    /// Rust performance initialization failed
    InitializationFailed(String),
    
    /// Ultra performance initialization failed
    UltimateInitFailed(String),
    
    /// Invalid arguments provided
    InvalidArguments(String),
    
    /// Semaphore timeout
    SemaphoreTimeout(String),
    
    /// Native call failed
    NativeCallFailed(String),
    
    /// Memory optimization failed
    MemoryOptimizationFailed(String),
    
    /// Chunk optimization failed
    ChunkOptimizationFailed(String),
    
    /// Error during performance monitoring shutdown
    ShutdownError(String),
    
    /// Error during counter reset
    CounterResetError(String),
    
    /// Error retrieving CPU stats
    CpuStatsError(String),
    
    /// Error retrieving memory stats
    MemoryStatsError(String),
    
    /// Error retrieving TPS
    TpsError(String),
    
    /// Error retrieving entity count
    EntityCountError(String),
    
    /// Error retrieving mob count
    MobCountError(String),
    
    /// Error retrieving block count
    BlockCountError(String),
    
    /// Error retrieving merged count
    MergedCountError(String),
    
    /// Error retrieving despawned count
    DespawnedCountError(String),
    
    /// Error during batch processing
    BatchProcessingError(String),
    
    /// Error during zero-copy operation
    ZeroCopyError(String),
    
    /// Error retrieving metrics
    MetricsError(String),
    
    /// Error logging startup info
    LogStartupError(String),
    
    /// Generic error with custom message
    GenericError(String),
}

impl RustPerformanceError {
    /// Create a new RustPerformanceError with a custom message
    pub fn with_code(message: String, code: i32) -> Self {
        match code {
            1 => Self::LibraryNotLoaded(message),
            2 => Self::NotInitialized(message),
            3 => Self::LibraryLoadFailed(message),
            4 => Self::InitializationFailed(message),
            5 => Self::UltimateInitFailed(message),
            6 => Self::InvalidArguments(message),
            7 => Self::SemaphoreTimeout(message),
            8 => Self::NativeCallFailed(message),
            9 => Self::MemoryOptimizationFailed(message),
            10 => Self::ChunkOptimizationFailed(message),
            11 => Self::ShutdownError(message),
            12 => Self::CounterResetError(message),
            13 => Self::CpuStatsError(message),
            14 => Self::MemoryStatsError(message),
            15 => Self::TpsError(message),
            16 => Self::EntityCountError(message),
            17 => Self::MobCountError(message),
            18 => Self::BlockCountError(message),
            19 => Self::MergedCountError(message),
            20 => Self::DespawnedCountError(message),
            21 => Self::BatchProcessingError(message),
            22 => Self::ZeroCopyError(message),
            23 => Self::MetricsError(message),
            24 => Self::LogStartupError(message),
            _ => Self::GenericError(message),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize, Default)]
pub struct Aabb {
    pub min: Vec3A,
    pub max: Vec3A,
}

impl Aabb {
    pub fn new(min_x: f32, min_y: f32, min_z: f32, max_x: f32, max_y: f32, max_z: f32) -> Self {
        Self {
            min: Vec3A::new(min_x, min_y, min_z),
            max: Vec3A::new(max_x, max_y, max_z),
        }
    }

    pub fn contains_point(&self, point: &Vec3A) -> bool {
        point.x >= self.min.x
            && point.x <= self.max.x
            && point.y >= self.min.y
            && point.y <= self.max.y
            && point.z >= self.min.z
            && point.z <= self.max.z
    }

    pub fn intersects(&self, other: &Aabb) -> bool {
        self.min.x <= other.max.x
            && self.max.x >= other.min.x
            && self.min.y <= other.max.y
            && self.max.y >= other.min.y
            && self.min.z <= other.max.z
            && self.max.z >= other.min.z
    }
}

/// Re-export centralized error types for consistency across the codebase
// RustError and Result are already imported at the top of the file

/// Convert legacy RustPerformanceError to centralized RustError
impl From<crate::errors::RustError> for RustPerformanceError {
    fn from(error: crate::errors::RustError) -> Self {
        Self::with_code(error.to_string(), match error {
            crate::errors::RustError::IoError(_) => 1,
            crate::errors::RustError::DatabaseError(_) => 2,
            crate::errors::RustError::ChunkNotFound(_) => 3,
            crate::errors::RustError::CompressionError(_) => 4,
            crate::errors::RustError::ChecksumError(_) => 5,
            crate::errors::RustError::JniError(_) => 6,
            crate::errors::RustError::TimeoutError(_) => 7,
            crate::errors::RustError::OperationFailed(_) => 8,
            crate::errors::RustError::SerializationError(_) => 9,
            crate::errors::RustError::DeserializationError(_) => 10,
            crate::errors::RustError::ValidationError(_) => 11,
            crate::errors::RustError::ResourceExhaustionError(_) => 12,
            crate::errors::RustError::InternalError(_) => 13,
            crate::errors::RustError::NotInitializedError(_) => 14,
            crate::errors::RustError::InvalidInputError(_) => 15,
            crate::errors::RustError::InvalidArgumentError(_) => 16,
            crate::errors::RustError::BufferError(_) => 17,
            crate::errors::RustError::ParseError(_) => 18,
            crate::errors::RustError::ConversionError(_) => 19,
            crate::errors::RustError::InvalidOperationType { .. } => 20,
            crate::errors::RustError::EmptyOperationSet => 21,
            crate::errors::RustError::AsyncTaskSendFailed { .. } => 22,
            crate::errors::RustError::AsyncResultReceiveFailed { .. } => 23,
            crate::errors::RustError::SharedBufferLockFailed => 24,
        })
    }
}

/// Result type alias for Rust performance operations (using centralized error type)
pub type PerformanceResult<T> = Result<T>;

// Entity-related types and implementations

/// Entity type identifier
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum EntityType {
    Player,
    Mob,
    Block,
    Villager,
    Item,
    Other(String),
}

impl EntityType {
    /// Convert from string to EntityType
    pub fn from_string(s: &str) -> Self {
        match s.to_lowercase().as_str() {
            "player" => Self::Player,
            "mob" => Self::Mob,
            "block" => Self::Block,
            "villager" => Self::Villager,
            "item" => Self::Item,
            other => Self::Other(other.to_string()),
        }
    }
}

/// Core entity data structure
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EntityData {
    pub id: u64,
    pub entity_type: EntityType,
    pub x: f64,
    pub y: f64,
    pub z: f64,
    pub distance: f32,
    pub is_block_entity: bool,
}

/// Player-specific entity data
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PlayerData {
    pub id: u64,
    pub x: f64,
    pub y: f64,
    pub z: f64,
}

/// Input structure for entity processing
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EntityInput {
    pub tick_count: u64,
    pub entities: Vec<EntityData>,
    pub players: Vec<PlayerData>,
    pub config: EntityConfig,
}

/// Processing result structure
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EntityProcessResult {
    pub entities_to_tick: Vec<u64>,
}

/// Entity configuration structure
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct EntityConfig {
    pub close_radius: f32,
    pub medium_radius: f32,
    pub close_rate: f32,
    pub medium_rate: f32,
    pub far_rate: f64,
    pub use_spatial_partitioning: bool,
    pub world_bounds: Aabb,
    pub quadtree_max_entities: usize,
    pub quadtree_max_depth: usize,
}

/// Factory for creating different types of entities
pub struct EntityTypeFactory;

impl EntityTypeFactory {
    /// Create a new entity of the specified type
    pub fn create_entity(
        entity_type: EntityType,
        id: u64,
        x: f64,
        y: f64,
        z: f64,
        config: &EntityConfig,
    ) -> Result<Box<dyn Entity<Data = EntityData, Config = EntityConfig>>> {
        match entity_type {
            EntityType::Player => Ok(Box::new(PlayerEntity::new(
                EntityData {
                    id,
                    entity_type: EntityType::Player,
                    x,
                    y,
                    z,
                    distance: 0.0,
                    is_block_entity: false,
                },
                config.clone(),
            )?)),
            EntityType::Mob => Ok(Box::new(MobEntity::new(
                EntityData {
                    id,
                    entity_type: EntityType::Mob,
                    x,
                    y,
                    z,
                    distance: 0.0,
                    is_block_entity: false,
                },
                config.clone(),
            )?)),
            EntityType::Block => Ok(Box::new(BlockEntity::new(
                EntityData {
                    id,
                    entity_type: EntityType::Block,
                    x,
                    y,
                    z,
                    distance: 0.0,
                    is_block_entity: true,
                },
                config.clone(),
            )?)),
            EntityType::Villager => Ok(Box::new(VillagerEntity::new(
                EntityData {
                    id,
                    entity_type: EntityType::Villager,
                    x,
                    y,
                    z,
                    distance: 0.0,
                    is_block_entity: false,
                },
                config.clone(),
            )?)),
            EntityType::Item => Ok(Box::new(ItemEntity::new(
                EntityData {
                    id,
                    entity_type: EntityType::Item,
                    x,
                    y,
                    z,
                    distance: 0.0,
                    is_block_entity: false,
                },
                config.clone(),
            )?)),
            EntityType::Other(_) => Err(RustError::InvalidArgumentError(
                "Unsupported entity type".to_string(),
            )),
        }
    }
}

/// Builder for entity configuration
#[derive(Debug, Default)]
pub struct EntityConfigBuilder {
    config: EntityConfig,
}

impl EntityConfigBuilder {
    /// Create a new EntityConfigBuilder
    pub fn new() -> Self {
        Self::default()
    }

    /// Set the close radius
    pub fn close_radius(mut self, close_radius: f32) -> Self {
        self.config.close_radius = close_radius;
        self
    }

    /// Set the medium radius
    pub fn medium_radius(mut self, medium_radius: f32) -> Self {
        self.config.medium_radius = medium_radius;
        self
    }

    /// Set the close rate
    pub fn close_rate(mut self, close_rate: f32) -> Self {
        self.config.close_rate = close_rate;
        self
    }

    /// Set the medium rate
    pub fn medium_rate(mut self, medium_rate: f32) -> Self {
        self.config.medium_rate = medium_rate;
        self
    }

    /// Set the far rate
    pub fn far_rate(mut self, far_rate: f64) -> Self {
        self.config.far_rate = far_rate;
        self
    }

    /// Set whether to use spatial partitioning
    pub fn use_spatial_partitioning(mut self, use_spatial_partitioning: bool) -> Self {
        self.config.use_spatial_partitioning = use_spatial_partitioning;
        self
    }

    /// Set the world bounds
    pub fn world_bounds(mut self, world_bounds: Aabb) -> Self {
        self.config.world_bounds = world_bounds;
        self
    }

    /// Set the maximum number of entities per quadtree node
    pub fn quadtree_max_entities(mut self, quadtree_max_entities: usize) -> Self {
        self.config.quadtree_max_entities = quadtree_max_entities;
        self
    }

    /// Set the maximum depth of the quadtree
    pub fn quadtree_max_depth(mut self, quadtree_max_depth: usize) -> Self {
        self.config.quadtree_max_depth = quadtree_max_depth;
        self
    }

    /// Build the EntityConfig
    pub fn build(self) -> EntityConfig {
        self.config
    }
}

// Concrete entity implementations

/// Player entity implementation
#[derive(Debug, Clone)]
pub struct PlayerEntity {
    data: EntityData,
    config: EntityConfig,
}

impl PlayerEntity {
    /// Create a new PlayerEntity
    pub fn new(data: EntityData, config: EntityConfig) -> Result<Self> {
        Ok(Self { data, config })
    }
}

/// Mob entity implementation
#[derive(Debug, Clone)]
pub struct MobEntity {
    data: EntityData,
    config: EntityConfig,
}

impl MobEntity {
    /// Create a new MobEntity
    pub fn new(data: EntityData, config: EntityConfig) -> Result<Self> {
        Ok(Self { data, config })
    }
}

/// Block entity implementation
#[derive(Debug, Clone)]
pub struct BlockEntity {
    data: EntityData,
    config: EntityConfig,
}

impl BlockEntity {
    /// Create a new BlockEntity
    pub fn new(data: EntityData, config: EntityConfig) -> Result<Self> {
        Ok(Self { data, config })
    }
}

/// Villager entity implementation
#[derive(Debug, Clone)]
pub struct VillagerEntity {
    data: EntityData,
    config: EntityConfig,
}

impl VillagerEntity {
    /// Create a new VillagerEntity
    pub fn new(data: EntityData, config: EntityConfig) -> Result<Self> {
        Ok(Self { data, config })
    }
}

/// Item entity implementation
#[derive(Debug, Clone)]
pub struct ItemEntity {
    data: EntityData,
    config: EntityConfig,
}

impl ItemEntity {
    /// Create a new ItemEntity
    pub fn new(data: EntityData, config: EntityConfig) -> Result<Self> {
        Ok(Self { data, config })
    }
}

// Implement Entity trait for concrete entity types

impl Entity for PlayerEntity {
    type Data = EntityData;
    type Config = EntityConfig;

    fn id(&self) -> u64 {
        self.data.id
    }

    fn entity_type(&self) -> &EntityType {
        &self.data.entity_type
    }

    fn position(&self) -> (f64, f64, f64) {
        (self.data.x, self.data.y, self.data.z)
    }

    fn is_within_bounds(&self, bounds: &Aabb) -> bool {
        let pos = Vec3A::new(self.data.x as f32, self.data.y as f32, self.data.z as f32);
        bounds.contains_point(&pos)
    }

    fn new(data: Self::Data, config: &Self::Config) -> Result<Self> {
        Ok(Self {
            data,
            config: config.clone(),
        })
    }
}

impl Entity for MobEntity {
    type Data = EntityData;
    type Config = EntityConfig;

    fn id(&self) -> u64 {
        self.data.id
    }

    fn entity_type(&self) -> &EntityType {
        &self.data.entity_type
    }

    fn position(&self) -> (f64, f64, f64) {
        (self.data.x, self.data.y, self.data.z)
    }

    fn is_within_bounds(&self, bounds: &Aabb) -> bool {
        let pos = Vec3A::new(self.data.x as f32, self.data.y as f32, self.data.z as f32);
        bounds.contains_point(&pos)
    }

    fn new(data: Self::Data, config: &Self::Config) -> Result<Self> {
        Ok(Self {
            data,
            config: config.clone(),
        })
    }
}

impl Entity for BlockEntity {
    type Data = EntityData;
    type Config = EntityConfig;

    fn id(&self) -> u64 {
        self.data.id
    }

    fn entity_type(&self) -> &EntityType {
        &self.data.entity_type
    }

    fn position(&self) -> (f64, f64, f64) {
        (self.data.x, self.data.y, self.data.z)
    }

    fn is_within_bounds(&self, bounds: &Aabb) -> bool {
        let pos = Vec3A::new(self.data.x as f32, self.data.y as f32, self.data.z as f32);
        bounds.contains_point(&pos)
    }

    fn new(data: Self::Data, config: &Self::Config) -> Result<Self> {
        Ok(Self {
            data,
            config: config.clone(),
        })
    }
}

impl Entity for VillagerEntity {
    type Data = EntityData;
    type Config = EntityConfig;

    fn id(&self) -> u64 {
        self.data.id
    }

    fn entity_type(&self) -> &EntityType {
        &self.data.entity_type
    }

    fn position(&self) -> (f64, f64, f64) {
        (self.data.x, self.data.y, self.data.z)
    }

    fn is_within_bounds(&self, bounds: &Aabb) -> bool {
        let pos = Vec3A::new(self.data.x as f32, self.data.y as f32, self.data.z as f32);
        bounds.contains_point(&pos)
    }

    fn new(data: Self::Data, config: &Self::Config) -> Result<Self> {
        Ok(Self {
            data,
            config: config.clone(),
        })
    }
}

impl Entity for ItemEntity {
    type Data = EntityData;
    type Config = EntityConfig;

    fn id(&self) -> u64 {
        self.data.id
    }

    fn entity_type(&self) -> &EntityType {
        &self.data.entity_type
    }

    fn position(&self) -> (f64, f64, f64) {
        (self.data.x, self.data.y, self.data.z)
    }

    fn is_within_bounds(&self, bounds: &Aabb) -> bool {
        let pos = Vec3A::new(self.data.x as f32, self.data.y as f32, self.data.z as f32);
        bounds.contains_point(&pos)
    }

    fn new(data: Self::Data, config: &Self::Config) -> Result<Self> {
        Ok(Self {
            data,
            config: config.clone(),
        })
    }
}

// Global configuration for entities
lazy_static::lazy_static! {
    pub static ref GLOBAL_ENTITY_CONFIG: RwLock<EntityConfig> = RwLock::new(EntityConfig {
        close_radius: 96.0, // 6 chunks * 16 blocks
        medium_radius: 192.0, // 12 chunks * 16 blocks
        close_rate: 1.0,
        medium_rate: 0.5,
        far_rate: 0.1,
        use_spatial_partitioning: true,
        world_bounds: Aabb::new(-10000.0, -64.0, -10000.0, 10000.0, 320.0, 10000.0),
        quadtree_max_entities: 16,
        quadtree_max_depth: 8,
    });
}

use valence::prelude::*;
use std::sync::Arc;
use parking_lot::RwLock;
use std::collections::HashMap;
use crate::spatial::{QuadTree, ChunkData, ChunkCoord};
use crate::{EntityConfig, BlockConfig, AiConfig, ExceptionsConfig};

// Valence-specific resources
#[derive(Resource)]
pub struct SpatialPartition {
    pub player_quadtree: QuadTree,
    pub entity_quadtree: QuadTree,
}

#[derive(Resource)]
pub struct PerformanceConfig {
    pub entity_config: Arc<RwLock<EntityConfig>>,
    pub block_config: Arc<RwLock<BlockConfig>>,
    pub mob_config: Arc<RwLock<AiConfig>>,
    pub exceptions: Arc<RwLock<ExceptionsConfig>>,
}

#[derive(Resource)]
pub struct ChunkManager {
    pub loaded_chunks: HashMap<ChunkCoord, ChunkData>,
    pub view_distance: i32, // in chunks
    pub unload_distance: i32, // chunks beyond view_distance to unload
}

#[derive(Resource)]
pub struct TickCounter(pub u64);

// New resources
#[derive(Resource)]
pub struct Weather {
    pub raining: bool,
    pub thunder: bool,
    pub time: u64,
}

#[derive(Resource)]
pub struct GameTime {
    pub time: u64,
    pub day_length: u64,
}
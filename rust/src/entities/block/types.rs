use serde::{Deserialize, Serialize};

/// Block position data
#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct BlockPosition {
    pub x: i32,
    pub y: i32,
    pub z: i32,
}

/// Block state data
#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct BlockState {
    pub block_type: String,
    pub properties: std::collections::HashMap<String, String>,
}

/// Block data with position and state
#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct BlockData {
    pub position: BlockPosition,
    pub state: BlockState,
    pub entity_id: u64,
}

#[derive(Serialize, Deserialize)]
pub struct BlockEntityData {
    pub id: u64,
    pub block_type: String,
    pub distance: f32,
    pub x: i32,
    pub y: i32,
    pub z: i32,
}

#[derive(Serialize, Deserialize)]
pub struct BlockInput {
    pub tick_count: u64,
    pub block_entities: Vec<BlockEntityData>,
}

#[derive(Serialize, Deserialize)]
pub struct BlockProcessResult {
    pub block_entities_to_tick: Vec<u64>,
}

#[derive(Serialize, Deserialize, Default)]
pub struct BlockConfig {
    pub close_radius: f32,
    pub medium_radius: f32,
    pub close_rate: f32,
    pub medium_rate: f32,
    pub far_rate: f32,
}

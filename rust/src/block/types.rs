use serde::{Deserialize, Serialize};

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

#[derive(Serialize, Deserialize)]
pub struct BlockConfig {
    pub close_radius: f32,
    pub medium_radius: f32,
    pub close_rate: f32,
    pub medium_rate: f32,
    pub far_rate: f32,
}
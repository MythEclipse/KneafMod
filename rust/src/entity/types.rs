use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize)]
pub struct EntityData {
    pub id: u64,
    pub entity_type: String,
    pub distance: f32,
    pub is_block_entity: bool,
}

#[derive(Serialize, Deserialize)]
pub struct Input {
    pub tick_count: u64,
    pub entities: Vec<EntityData>,
}

#[derive(Serialize, Deserialize)]
pub struct ProcessResult {
    pub entities_to_tick: Vec<u64>,
}

#[derive(Serialize, Deserialize)]
pub struct Config {
    pub close_radius: f32,
    pub medium_radius: f32,
    pub close_rate: f32,
    pub medium_rate: f32,
    pub far_rate: f32,
}
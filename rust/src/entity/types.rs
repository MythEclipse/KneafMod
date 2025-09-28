use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EntityData {
    pub id: u64,
    pub entity_type: String,
    pub x: f64,
    pub y: f64,
    pub z: f64,
    pub distance: f32,
    pub is_block_entity: bool,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PlayerData {
    pub id: u64,
    pub x: f64,
    pub y: f64,
    pub z: f64,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Input {
    pub tick_count: u64,
    pub entities: Vec<EntityData>,
    pub players: Vec<PlayerData>,
    pub entity_config: super::config::Config,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProcessResult {
    pub entities_to_tick: Vec<u64>,
}

#[derive(Serialize, Deserialize, Default)]
pub struct Config {
    pub close_radius: f32,
    pub medium_radius: f32,
    pub close_rate: f32,
    pub medium_rate: f32,
    pub far_rate: f32,
}
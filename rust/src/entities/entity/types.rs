use serde::{Deserialize, Serialize};
use crate::types::{EntityConfigTrait as EntityConfig, EntityDataTrait as EntityData, EntityTypeTrait as EntityType, PlayerDataTrait as PlayerData};

/// Entity position data
#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct EntityPosition {
    pub x: f64,
    pub y: f64,
    pub z: f64,
    pub yaw: f32,
    pub pitch: f32,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Input {
    pub tick_count: u64,
    pub entities: Vec<EntityData>,
    pub players: Vec<PlayerData>,
    pub entity_config: String, // Changed from Box<dyn EntityConfig> to String for serialization
}

#[derive(Serialize, Deserialize)]
pub struct ProcessResult {
    pub entities_to_tick: Vec<u64>,
}

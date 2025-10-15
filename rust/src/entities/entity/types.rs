use serde::{Deserialize, Serialize};
use crate::types::{EntityConfigTrait as EntityConfig, EntityDataTrait as EntityData, EntityTypeTrait as EntityType, PlayerDataTrait as PlayerData};

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Input {
    pub tick_count: u64,
    pub entities: Vec<EntityData>,
    pub players: Vec<PlayerData>,
    pub entity_config: EntityConfig,
}

#[derive(Serialize, Deserialize)]
pub struct ProcessResult {
    pub entities_to_tick: Vec<u64>,
}

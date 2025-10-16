use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use crate::entities::common::types::*;
use crate::EntityProcessingInput;
use crate::EntityProcessingResult;
use crate::EntityType;
use crate::EntityData;
use crate::types::PlayerDataTrait as GlobalPlayerData;

/// Villager-specific data extending the common EntityData
#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct VillagerData {
    pub id: u64,
    pub entity_type: EntityType,
    pub position: (f32, f32, f32),
    pub distance: f32,
    pub profession: String,
    pub level: u8,
    pub has_workstation: bool,
    pub is_resting: bool,
    pub is_breeding: bool,
    pub last_pathfind_tick: u64,
    pub pathfind_frequency: u8,
    pub ai_complexity: u8,
}

/// Villager input extending the common EntityProcessingInput
#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct VillagerInput {
    pub tick_count: u64,
    pub villagers: Vec<VillagerData>,
    pub players: Vec<PlayerData>,
}

/// Villager process result extending the common EntityProcessingResult
#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct VillagerProcessResult {
    pub villagers_to_disable_ai: Vec<u64>,
    pub villagers_to_simplify_ai: Vec<u64>,
    pub villagers_to_reduce_pathfinding: Vec<u64>,
    pub villager_groups: Vec<VillagerGroup>,
}

/// Convert VillagerData to EntityData for use with the common processor
impl From<VillagerData> for EntityData {
    fn from(villager_data: VillagerData) -> Self {
        EntityData {
            entity_id: villager_data.id.to_string(),
            entity_type: villager_data.entity_type,
            x: villager_data.position.0,
            y: villager_data.position.1,
            z: villager_data.position.2,
            health: 20.0, // Default health
            max_health: 20.0,
            velocity_x: 0.0,
            velocity_y: 0.0,
            velocity_z: 0.0,
            rotation: 0.0,
            pitch: 0.0,
            yaw: 0.0,
            properties: {
                let mut map = std::collections::HashMap::new();
                map.insert("profession".to_string(), villager_data.profession);
                map.insert("level".to_string(), villager_data.level.to_string());
                map.insert("has_workstation".to_string(), villager_data.has_workstation.to_string());
                map.insert("is_resting".to_string(), villager_data.is_resting.to_string());
                map.insert("is_breeding".to_string(), villager_data.is_breeding.to_string());
                map.insert("last_pathfind_tick".to_string(), villager_data.last_pathfind_tick.to_string());
                map.insert("pathfind_frequency".to_string(), villager_data.pathfind_frequency.to_string());
                map.insert("ai_complexity".to_string(), villager_data.ai_complexity.to_string());
                map.insert("distance".to_string(), villager_data.distance.to_string());
                map
            },
        }
    }
}

/// Convert VillagerInput to EntityProcessingInput for use with the common processor
impl From<VillagerInput> for EntityProcessingInput {
    fn from(villager_input: VillagerInput) -> Self {
        // For batch processing, we'll create a single input for the first villager
        if let Some(first_villager) = villager_input.villagers.first() {
            EntityProcessingInput {
                entity_id: first_villager.id.to_string(),
                entity_type: first_villager.entity_type,
                data: first_villager.clone().into(),
                delta_time: 0.05, // Default 50ms tick
                simulation_distance: 128,
            }
        } else {
            // Default input if no villagers
            EntityProcessingInput {
                entity_id: "0".to_string(),
                entity_type: EntityType::Villager,
                data: EntityData {
                    entity_id: "0".to_string(),
                    entity_type: EntityType::Villager,
                    x: 0.0,
                    y: 0.0,
                    z: 0.0,
                    health: 20.0,
                    max_health: 20.0,
                    velocity_x: 0.0,
                    velocity_y: 0.0,
                    velocity_z: 0.0,
                    rotation: 0.0,
                    pitch: 0.0,
                    yaw: 0.0,
                    properties: HashMap::new(),
                },
                delta_time: 0.05,
                simulation_distance: 128,
            }
        }
    }
}

/// Convert EntityProcessingResult to VillagerProcessResult
impl From<EntityProcessingResult> for VillagerProcessResult {
    fn from(result: EntityProcessingResult) -> Self {
        // Parse entity_id to get villager ID
        let villager_id = result.entity_id.parse::<u64>().unwrap_or(0);
        
        VillagerProcessResult {
            villagers_to_disable_ai: if result.success && result.metadata_changed.is_some() {
                vec![villager_id]
            } else {
                Vec::new()
            },
            villagers_to_simplify_ai: if !result.success {
                vec![villager_id]
            } else {
                Vec::new()
            },
            villagers_to_reduce_pathfinding: Vec::new(), // Will be populated based on distance logic
            villager_groups: Vec::new(), // Will be populated by spatial processing
        }
    }
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct VillagerGroup {
    pub group_id: u32,
    pub center_x: f32,
    pub center_y: f32,
    pub center_z: f32,
    pub villager_ids: Vec<u64>,
    pub group_type: String,
    pub ai_tick_rate: u8,
}

#[derive(Serialize, Deserialize, Debug, Default, Clone)]
pub struct VillagerConfig {
    pub disable_ai_distance: f32,
    pub simplify_ai_distance: f32,
    pub reduce_pathfinding_distance: f32,
    pub village_radius: f32,
    pub max_villagers_per_group: usize,
    pub pathfinding_tick_interval: u8,
    pub simple_ai_tick_interval: u8,
    pub complex_ai_tick_interval: u8,
    pub workstation_search_radius: f32,
    pub breeding_cooldown_ticks: u64,
    pub rest_tick_interval: u8,
}

/// Player data for villager processing
#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct PlayerData {
    pub id: u64,
    pub x: f64,
    pub y: f64,
    pub z: f64,
}

#[derive(Serialize, Deserialize, Clone)]
pub struct SpatialGroup {
    pub chunk_x: i32,
    pub chunk_z: i32,
    pub villagers: Vec<VillagerData>,
    pub group_center: (f32, f32, f32),
    pub estimated_player_distance: f32,
}

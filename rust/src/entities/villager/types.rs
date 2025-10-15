use serde::{Deserialize, Serialize};
use crate::entities::common::types::*;

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
            id: villager_data.id,
            entity_type: villager_data.entity_type,
            position: villager_data.position,
            distance: villager_data.distance,
            metadata: Some(serde_json::json!({
                "profession": villager_data.profession,
                "level": villager_data.level,
                "has_workstation": villager_data.has_workstation,
                "is_resting": villager_data.is_resting,
                "is_breeding": villager_data.is_breeding,
                "last_pathfind_tick": villager_data.last_pathfind_tick,
                "pathfind_frequency": villager_data.pathfind_frequency,
                "ai_complexity": villager_data.ai_complexity,
            })),
        }
    }
}

/// Convert VillagerInput to EntityProcessingInput for use with the common processor
impl From<VillagerInput> for EntityProcessingInput {
    fn from(villager_input: VillagerInput) -> Self {
        EntityProcessingInput {
            tick_count: villager_input.tick_count,
            entities: villager_input.villagers.into_iter().map(|v| v.into()).collect(),
            players: Some(villager_input.players),
        }
    }
}

/// Convert EntityProcessingResult to VillagerProcessResult
impl From<EntityProcessingResult> for VillagerProcessResult {
    fn from(result: EntityProcessingResult) -> Self {
        let mut villager_groups = Vec::new();
        
        // Convert any spatial groups to villager groups if needed
        if let Some(spatial_groups) = result.groups {
            for group in spatial_groups {
                let villager_group = VillagerGroup {
                    group_id: group.group_id,
                    center_x: group.center_position.0,
                    center_y: group.center_position.1,
                    center_z: group.center_position.2,
                    villager_ids: group.member_ids,
                    group_type: "generic".to_string(), // Default type, would be more specific in real implementation
                    ai_tick_rate: group.ai_tick_rate.unwrap_or(1),
                };
                villager_groups.push(villager_group);
            }
        }

        VillagerProcessResult {
            villagers_to_disable_ai: result.entities_to_disable_ai,
            villagers_to_simplify_ai: result.entities_to_simplify_ai,
            villagers_to_reduce_pathfinding: result.entities_to_reduce_pathfinding.unwrap_or_default(),
            villager_groups,
        }
    }
}

#[derive(Serialize, Deserialize, Clone)]
pub struct VillagerGroup {
    pub group_id: u32,
    pub center_x: f32,
    pub center_y: f32,
    pub center_z: f32,
    pub villager_ids: Vec<u64>,
    pub group_type: String,
    pub ai_tick_rate: u8,
}

#[derive(Serialize, Deserialize, Default, Clone)]
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

#[derive(Serialize, Deserialize, Clone)]
pub struct SpatialGroup {
    pub chunk_x: i32,
    pub chunk_z: i32,
    pub villagers: Vec<VillagerData>,
    pub group_center: (f32, f32, f32),
    pub estimated_player_distance: f32,
}

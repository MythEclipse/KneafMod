use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct VillagerData {
    pub id: u64,
    pub x: f32,
    pub y: f32,
    pub z: f32,
    pub distance: f32,
    pub profession: String,
    pub level: u8,
    pub has_workstation: bool,
    pub is_resting: bool,
    pub is_breeding: bool,
    pub last_pathfind_tick: u64,
    pub pathfind_frequency: u8, // How often to pathfind (1=every tick, 2=every 2nd tick, etc.)
    pub ai_complexity: u8,      // 0=simple, 1=normal, 2=complex
}

#[derive(Serialize, Deserialize)]
pub struct VillagerInput {
    pub tick_count: u64,
    pub villagers: Vec<VillagerData>,
    pub players: Vec<PlayerData>,
}

#[derive(Serialize, Deserialize)]
pub struct PlayerData {
    pub id: u64,
    pub x: f32,
    pub y: f32,
    pub z: f32,
}

#[derive(Serialize, Deserialize, Clone)]
pub struct VillagerProcessResult {
    pub villagers_to_disable_ai: Vec<u64>,
    pub villagers_to_simplify_ai: Vec<u64>,
    pub villagers_to_reduce_pathfinding: Vec<u64>,
    pub villager_groups: Vec<VillagerGroup>,
}

#[derive(Serialize, Deserialize, Clone)]
pub struct VillagerGroup {
    pub group_id: u32,
    pub center_x: f32,
    pub center_y: f32,
    pub center_z: f32,
    pub villager_ids: Vec<u64>,
    pub group_type: String, // "village", "trading", "wandering", "working"
    pub ai_tick_rate: u8,   // How often to update AI for this group
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

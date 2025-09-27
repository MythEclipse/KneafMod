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
pub struct ItemEntityData {
    pub id: u64,
    pub item_type: String,
    pub count: u32,
    pub age_seconds: u32,
    pub chunk_x: i32,
    pub chunk_z: i32,
}

#[derive(Serialize, Deserialize)]
pub struct ItemInput {
    pub items: Vec<ItemEntityData>,
}

#[derive(Serialize, Deserialize)]
pub struct ItemUpdate {
    pub id: u64,
    pub new_count: u32,
}

#[derive(Serialize, Deserialize)]
pub struct ItemProcessResult {
    pub items_to_remove: Vec<u64>,
    pub merged_count: u64,
    pub despawned_count: u64,
    pub item_updates: Vec<ItemUpdate>,
}

#[derive(Serialize, Deserialize)]
pub struct MobData {
    pub id: u64,
    pub entity_type: String,
    pub distance: f32,
    pub is_passive: bool,
}

#[derive(Serialize, Deserialize)]
pub struct MobInput {
    pub tick_count: u64,
    pub mobs: Vec<MobData>,
}

#[derive(Serialize, Deserialize)]
pub struct MobProcessResult {
    pub mobs_to_disable_ai: Vec<u64>,
    pub mobs_to_simplify_ai: Vec<u64>,
}

#[derive(Serialize, Deserialize)]
pub struct Config {
    pub close_radius: f32,
    pub medium_radius: f32,
    pub close_rate: f32,
    pub medium_rate: f32,
    pub far_rate: f32,
}

#[derive(Serialize, Deserialize)]
pub struct ItemConfig {
    pub merge_enabled: bool,
    pub max_items_per_chunk: usize,
    pub despawn_time_seconds: u32,
}

#[derive(Serialize, Deserialize)]
pub struct AiConfig {
    pub passive_disable_distance: f32,
    pub hostile_simplify_distance: f32,
    pub ai_tick_rate_far: f32,
}

#[derive(Serialize, Deserialize)]
pub struct ExceptionsConfig {
    pub critical_entity_types: Vec<String>,
}
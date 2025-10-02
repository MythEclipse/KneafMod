use serde::{Deserialize, Serialize};

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
pub struct ItemConfig {
    pub merge_enabled: bool,
    pub max_items_per_chunk: usize,
    pub despawn_time_seconds: u32,
}
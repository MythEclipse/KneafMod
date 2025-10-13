use serde::{Deserialize, Serialize};

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

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct AiConfig {
    pub passive_disable_distance: f32,
    pub hostile_simplify_distance: f32,
    pub ai_tick_rate_far: f32,
}

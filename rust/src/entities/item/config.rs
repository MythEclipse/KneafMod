use serde::{Deserialize, Serialize};

/// Item configuration
#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct ItemConfig {
    pub despawn_distance: f32,
    pub merge_distance: f32,
    pub max_stack_size: u8,
    pub despawn_tick_interval: u64,
    pub merge_tick_interval: u64,
    pub cleanup_interval: u64,
}

impl Default for ItemConfig {
    fn default() -> Self {
        Self {
            despawn_distance: 128.0,
            merge_distance: 2.0,
            max_stack_size: 64,
            despawn_tick_interval: 6000, // 5 minutes at 20 TPS
            merge_tick_interval: 20, // 1 second at 20 TPS
            cleanup_interval: 100, // 5 seconds at 20 TPS
        }
    }
}
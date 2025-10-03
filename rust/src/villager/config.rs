use super::types::*;
use std::sync::RwLock;

lazy_static::lazy_static! {
    pub static ref VILLAGER_CONFIG: RwLock<VillagerConfig> = RwLock::new(VillagerConfig {
        disable_ai_distance: 150.0,
        simplify_ai_distance: 80.0,
        reduce_pathfinding_distance: 40.0,
        village_radius: 64.0,
        max_villagers_per_group: 16,
        pathfinding_tick_interval: 5, // Pathfind every 5 ticks for far villagers
        simple_ai_tick_interval: 3,   // Simple AI every 3 ticks
        complex_ai_tick_interval: 1,  // Complex AI every tick for close villagers
        workstation_search_radius: 32.0,
        breeding_cooldown_ticks: 6000, // 5 minutes
        rest_tick_interval: 10,        // Rest AI every 10 ticks
    });
}

pub fn update_villager_config(new_config: VillagerConfig) -> Result<(), String> {
    let mut config = VILLAGER_CONFIG.write().map_err(|e| format!("Failed to acquire config lock: {}", e))?;
    *config = new_config;
    Ok(())
}

pub fn get_villager_config() -> VillagerConfig {
    VILLAGER_CONFIG.read().unwrap().clone()
}
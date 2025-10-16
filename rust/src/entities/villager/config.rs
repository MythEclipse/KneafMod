use super::types::*;
use std::sync::RwLock;
use crate::entities::common::types::EntityConfig;

lazy_static::lazy_static! {
    pub static ref VILLAGER_CONFIG: RwLock<VillagerConfig> = RwLock::new(VillagerConfig {
        disable_ai_distance: 150.0,
        simplify_ai_distance: 80.0,
        reduce_pathfinding_distance: 40.0,
        village_radius: 64.0,
        max_villagers_per_group: 16,
        pathfinding_tick_interval: 5,
        simple_ai_tick_interval: 3,
        complex_ai_tick_interval: 1,
        workstation_search_radius: 32.0,
        breeding_cooldown_ticks: 6000,
        rest_tick_interval: 10,
    });
}

pub fn update_villager_config(new_config: VillagerConfig) -> Result<(), String> {
    let mut config = VILLAGER_CONFIG
        .write()
        .map_err(|e| format!("Failed to acquire config lock: {}", e))?;
    *config = new_config;
    Ok(())
}

pub fn get_villager_config() -> VillagerConfig {
    VILLAGER_CONFIG.read().unwrap().clone()
}

// Implement EntityConfig trait for VillagerConfig
impl EntityConfig for VillagerConfig {
    fn get_disable_ai_distance(&self) -> f32 {
        self.disable_ai_distance
    }
    
    fn get_simplify_ai_distance(&self) -> f32 {
        self.simplify_ai_distance
    }
    
    fn get_reduce_pathfinding_distance(&self) -> Option<f32> {
        Some(self.reduce_pathfinding_distance)
    }
    
    fn get_max_entities_per_group(&self) -> usize {
        self.max_villagers_per_group
    }
    
    fn get_spatial_grid_size(&self) -> f32 {
        self.village_radius * 2.0 // Use village radius as base for spatial grid
    }
}

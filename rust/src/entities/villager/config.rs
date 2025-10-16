use super::types::*;
use std::sync::RwLock;
use crate::types::EntityConfigTrait as EntityConfig;

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
    fn entity_type(&self) -> crate::types::EntityType {
        crate::types::EntityType::Villager
    }
    
    fn is_active(&self) -> bool {
        true
    }
    
    fn update_interval(&self) -> u64 {
        self.simple_ai_tick_interval as u64
    }
    
    fn execute_with_priority(&self, priority: u8) -> Result<(), crate::errors::ExecutionError> {
        // Implement priority-based execution logic
        match priority {
            0..=2 => Ok(()), // Low priority - basic updates
            3..=5 => Ok(()), // Medium priority - standard updates
            6..=8 => Ok(()), // High priority - urgent updates
            _ => Err(crate::errors::ExecutionError::InvalidPriority(priority)),
        }
    }
    
    fn get_execution_strategy(&self) -> crate::types::ExecutionStrategy {
        crate::types::ExecutionStrategy::PriorityBased
    }
    
    fn get_entity_type(&self) -> &str {
        "villager"
    }
    
    fn clone_box(&self) -> Box<dyn EntityConfig> {
        Box::new(self.clone())
    }
    
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

use super::types::*;
use std::collections::HashMap;
use rayon::prelude::*;
use serde::{Serialize, Deserialize};

#[derive(Clone, Debug)]
pub struct PathfindingCache {
    pub villager_id: u64,
    pub last_pathfind_tick: u64,
    pub target_position: (f32, f32, f32),
    pub path_success_rate: f32, // 0.0 to 1.0, higher means more successful paths
    pub consecutive_failures: u8,
    pub cached_path_validity: u8, // How many ticks the current path is still valid
}

impl PathfindingCache {
    pub fn new(villager_id: u64) -> Self {
        Self {
            villager_id,
            last_pathfind_tick: 0,
            target_position: (0.0, 0.0, 0.0),
            path_success_rate: 1.0,
            consecutive_failures: 0,
            cached_path_validity: 0,
        }
    }

    pub fn should_pathfind(&self, current_tick: u64, config: &VillagerConfig) -> bool {
        let ticks_since_last = current_tick.saturating_sub(self.last_pathfind_tick);
        
        // Don't pathfind if we have a valid cached path
        if self.cached_path_validity > 0 {
            return false;
        }

        // Reduce pathfinding frequency based on success rate
        let pathfind_interval = if self.path_success_rate > 0.8 {
            config.pathfinding_tick_interval
        } else if self.path_success_rate > 0.5 {
            config.pathfinding_tick_interval * 2
        } else {
            config.pathfinding_tick_interval * 3
        };

        ticks_since_last >= pathfind_interval as u64
    }

    pub fn update_pathfind_result(&mut self, current_tick: u64, success: bool, target_position: (f32, f32, f32)) {
        self.last_pathfind_tick = current_tick;
        self.target_position = target_position;
        
        if success {
            self.consecutive_failures = 0;
            self.path_success_rate = (self.path_success_rate * 0.9 + 1.0 * 0.1).min(1.0);
            self.cached_path_validity = 10; // Path is valid for 10 ticks on success
        } else {
            self.consecutive_failures += 1;
            self.path_success_rate = (self.path_success_rate * 0.9 + 0.0 * 0.1).max(0.0);
            self.cached_path_validity = 0;
        }
    }

    pub fn decrement_cache_validity(&mut self) {
        if self.cached_path_validity > 0 {
            self.cached_path_validity -= 1;
        }
    }
}

pub struct PathfindingOptimizer {
    cache: HashMap<u64, PathfindingCache>,
    global_pathfind_budget: u32, // Maximum pathfinding operations per tick
    current_tick: u64,
}

impl PathfindingOptimizer {
    pub fn new() -> Self {
        Self {
            cache: HashMap::new(),
            global_pathfind_budget: 50, // Limit to 50 pathfinding operations per tick
            current_tick: 0,
        }
    }

    pub fn update_tick(&mut self, current_tick: u64) {
        self.current_tick = current_tick;
        
        // Decrement cache validity for all villagers
        for cache in self.cache.values_mut() {
            cache.decrement_cache_validity();
        }
    }

    pub fn optimize_villager_pathfinding(&mut self, villagers: &mut [VillagerData], config: &VillagerConfig) -> Vec<u64> {
        let mut villagers_to_reduce_pathfinding: Vec<u64> = Vec::new();

        // First pass: determine which villagers should have reduced pathfinding
        let villager_ids_to_reduce: Vec<u64> = villagers.par_iter()
            .filter_map(|villager| {
                if villager.distance > config.reduce_pathfinding_distance {
                    Some(villager.id)
                } else {
                    None
                }
            })
            .collect();

        // Second pass: update cache and villager frequencies
        for villager in villagers.iter_mut() {
            let cache = self.cache.entry(villager.id).or_insert_with(|| PathfindingCache::new(villager.id));
            
            if villager.distance > config.reduce_pathfinding_distance {
                if !cache.should_pathfind(self.current_tick, config) {
                    villager.pathfind_frequency = 15; // Reduce frequency
                } else {
                    // Allow pathfinding but update cache
                    cache.update_pathfind_result(
                        self.current_tick,
                        true, // Assume success for now
                        (villager.x, villager.y, villager.z)
                    );
                    villager.pathfind_frequency = config.pathfinding_tick_interval;
                }
            } else {
                // Close villagers get normal pathfinding
                villager.pathfind_frequency = 1;
                cache.update_pathfind_result(
                    self.current_tick,
                    true,
                    (villager.x, villager.y, villager.z)
                );
            }
        }

        villager_ids_to_reduce
    }

    pub fn batch_optimize_pathfinding(&mut self, groups: &[VillagerGroup], config: &VillagerConfig) -> Vec<u64> {
        let mut all_villagers_to_reduce = Vec::new();

        // Process groups based on their AI tick rate
        for group in groups {
            // Skip groups that don't need pathfinding this tick
            if self.current_tick % group.ai_tick_rate as u64 != 0 {
                continue;
            }

            // Process villagers in this group
            for &villager_id in &group.villager_ids {
                if let Some(cache) = self.cache.get_mut(&villager_id) {
                    if !cache.should_pathfind(self.current_tick, config) {
                        all_villagers_to_reduce.push(villager_id);
                    }
                }
            }
        }

        all_villagers_to_reduce
    }

    pub fn cleanup_old_cache(&mut self, active_villager_ids: &[u64]) {
        let active_set: std::collections::HashSet<_> = active_villager_ids.iter().collect();
        self.cache.retain(|id, _| active_set.contains(id));
    }

    pub fn get_cache_stats(&self) -> PathfindingCacheStats {
        let total_villagers = self.cache.len();
        let mut high_success_rate = 0;
        let mut low_success_rate = 0;
        let mut average_pathfind_interval = 0.0;

        for cache in self.cache.values() {
            if cache.path_success_rate > 0.7 {
                high_success_rate += 1;
            } else if cache.path_success_rate < 0.3 {
                low_success_rate += 1;
            }
        }

        if total_villagers > 0 {
            average_pathfind_interval = self.cache.values()
                .map(|cache| cache.last_pathfind_tick)
                .sum::<u64>() as f32 / total_villagers as f32;
        }

        PathfindingCacheStats {
            total_villagers,
            high_success_rate,
            low_success_rate,
            average_pathfind_interval,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PathfindingCacheStats {
    pub total_villagers: usize,
    pub high_success_rate: usize,
    pub low_success_rate: usize,
    pub average_pathfind_interval: f32,
}

// Advanced pathfinding optimizations for large villager populations
pub struct AdvancedPathfindingOptimizer {
    base_optimizer: PathfindingOptimizer,
    group_pathfind_cache: HashMap<u32, GroupPathfindingCache>,
}

impl AdvancedPathfindingOptimizer {
    pub fn new() -> Self {
        Self {
            base_optimizer: PathfindingOptimizer::new(),
            group_pathfind_cache: HashMap::new(),
        }
    }

    pub fn optimize_large_villager_groups(&mut self, groups: &mut [VillagerGroup], config: &VillagerConfig) -> Vec<u64> {
        let mut villagers_to_reduce = Vec::new();

        // Process groups sequentially to avoid closure borrowing issues
        for group in groups.iter_mut() {
            // Check if group needs pathfinding this tick
            if self.base_optimizer.current_tick % group.ai_tick_rate as u64 != 0 {
                continue;
            }

            // Get or create group cache
            let group_cache = self.group_pathfind_cache.entry(group.group_id)
                .or_insert_with(|| GroupPathfindingCache::new(group.group_id));

            // Optimize pathfinding for the entire group
            let should_reduce = group_cache.optimize_group_pathfinding(group, config, self.base_optimizer.current_tick);
            
            if should_reduce {
                villagers_to_reduce.extend(&group.villager_ids);
            }
        }

        villagers_to_reduce
    }
}

#[derive(Clone, Debug)]
struct GroupPathfindingCache {
    group_id: u32,
    last_group_pathfind_tick: u64,
    group_path_success_rate: f32,
    shared_target_position: (f32, f32, f32),
}

impl GroupPathfindingCache {
    fn new(group_id: u32) -> Self {
        Self {
            group_id,
            last_group_pathfind_tick: 0,
            group_path_success_rate: 1.0,
            shared_target_position: (0.0, 0.0, 0.0),
        }
    }

    fn optimize_group_pathfinding(&mut self, group: &VillagerGroup, config: &VillagerConfig, current_tick: u64) -> bool {
        // For large groups, we can optimize by having them share pathfinding results
        if group.villager_ids.len() >= 8 {
            let ticks_since_last = current_tick.saturating_sub(self.last_group_pathfind_tick);
            
            if ticks_since_last >= (config.pathfinding_tick_interval * 2) as u64 {
                // Update group pathfinding cache
                self.last_group_pathfind_tick = current_tick;
                self.shared_target_position = (group.center_x, group.center_y, group.center_z);
                self.group_path_success_rate = (self.group_path_success_rate * 0.9 + 1.0 * 0.1).min(1.0);
                
                // Return true to indicate we should reduce individual villager pathfinding
                return true;
            }
        }
        
        false
    }
}
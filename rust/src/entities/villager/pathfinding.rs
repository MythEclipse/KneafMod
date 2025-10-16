use super::types::*;
use jni::{objects::JClass, JNIEnv};
use rayon::prelude::*;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

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

    pub fn update_pathfind_result(
        &mut self,
        current_tick: u64,
        success: bool,
        target_position: (f32, f32, f32),
    ) {
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
    current_tick: u64,
}

impl PathfindingOptimizer {
    pub fn new() -> Self {
        Self {
            cache: HashMap::new(),
            current_tick: 0,
        }
    }
}

impl Default for PathfindingOptimizer {
    fn default() -> Self {
        Self::new()
    }
}

impl PathfindingOptimizer {
    pub fn update_tick(&mut self, current_tick: u64) {
        self.current_tick = current_tick;

        // Decrement cache validity for all villagers
        for cache in self.cache.values_mut() {
            cache.decrement_cache_validity();
        }
    }

    pub fn optimize_villager_pathfinding(
        &mut self,
        villagers: &mut [VillagerData],
        config: &VillagerConfig,
    ) -> Vec<u64> {
        // First pass: determine which villagers should have reduced pathfinding
        let villager_ids_to_reduce: Vec<u64> = villagers
            .par_iter()
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
            let cache = self
                .cache
                .entry(villager.id)
                .or_insert_with(|| PathfindingCache::new(villager.id));

            if villager.distance > config.reduce_pathfinding_distance {
                if !cache.should_pathfind(self.current_tick, config) {
                    villager.pathfind_frequency = 15; // Reduce frequency
                } else {
                    // Allow pathfinding but update cache
                    cache.update_pathfind_result(
                        self.current_tick,
                        true, // Assume success for now
                        villager.position,
                    );
                    villager.pathfind_frequency = config.pathfinding_tick_interval;
                }
            } else {
                // Close villagers get normal pathfinding
                villager.pathfind_frequency = 1;
                cache.update_pathfind_result(
                    self.current_tick,
                    true,
                    villager.position,
                );
            }
        }

        villager_ids_to_reduce
    }

    pub fn batch_optimize_pathfinding(
        &mut self,
        groups: &[VillagerGroup],
        config: &VillagerConfig,
    ) -> Vec<u64> {
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
            average_pathfind_interval = self
                .cache
                .values()
                .map(|cache| cache.last_pathfind_tick)
                .sum::<u64>() as f32
                / total_villagers as f32;
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

    pub fn optimize_large_villager_groups(
        &mut self,
        groups: &mut [VillagerGroup],
        config: &VillagerConfig,
    ) -> Vec<u64> {
        let mut villagers_to_reduce = Vec::new();

        // Process groups sequentially to avoid closure borrowing issues
        for group in groups.iter_mut() {
            // Check if group needs pathfinding this tick
            if self.base_optimizer.current_tick % group.ai_tick_rate as u64 != 0 {
                continue;
            }

            // Get or create group cache
            let group_cache = self
                .group_pathfind_cache
                .entry(group.group_id)
                .or_insert_with(|| GroupPathfindingCache::new(group.group_id));

            // Optimize pathfinding for the entire group
            let should_reduce = group_cache.optimize_group_pathfinding(
                group,
                config,
                self.base_optimizer.current_tick,
            );

            if should_reduce {
                villagers_to_reduce.extend(&group.villager_ids);
            }
        }

        villagers_to_reduce
    }
}

impl Default for AdvancedPathfindingOptimizer {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Clone, Debug)]
struct GroupPathfindingCache {
    last_group_pathfind_tick: u64,
    group_path_success_rate: f32,
    shared_target_position: (f32, f32, f32),
}

impl GroupPathfindingCache {
    fn new(_group_id: u32) -> Self {
        Self {
            last_group_pathfind_tick: 0,
            group_path_success_rate: 1.0,
            shared_target_position: (0.0, 0.0, 0.0),
        }
    }

    fn optimize_group_pathfinding(
        &mut self,
        group: &VillagerGroup,
        config: &VillagerConfig,
        current_tick: u64,
    ) -> bool {
        // For large groups, we can optimize by having them share pathfinding results
        if group.villager_ids.len() >= 8 {
            let ticks_since_last = current_tick.saturating_sub(self.last_group_pathfind_tick);

            if ticks_since_last >= (config.pathfinding_tick_interval * 2) as u64 {
                // Update group pathfinding cache
                self.last_group_pathfind_tick = current_tick;
                self.shared_target_position = (group.center_x, group.center_y, group.center_z);
                self.group_path_success_rate =
                    (self.group_path_success_rate * 0.9 + 1.0 * 0.1).min(1.0);

                // Return true to indicate we should reduce individual villager pathfinding
                return true;
            }
        }

        false
    }
}
/// SIMD-accelerated heuristic distance calculation for A* pathfinding
/// Calculates Manhattan distance heuristic for multiple target positions
pub fn calculate_heuristic_distances_simd(
    start: (f32, f32, f32),
    targets: &[(f32, f32, f32)],
) -> Vec<f32> {
    #[cfg(target_feature = "avx2")]
    {
        use std::arch::x86_64::*;

        let start_x = _mm256_set1_ps(start.0);
        let start_y = _mm256_set1_ps(start.1);
        let start_z = _mm256_set1_ps(start.2);

        targets
            .par_chunks(8)
            .flat_map(|chunk| {
                let mut distances = [0.0f32; 8];

                let target_x = _mm256_set_ps(
                    chunk.get(7).map(|p| p.0).unwrap_or(0.0),
                    chunk.get(6).map(|p| p.0).unwrap_or(0.0),
                    chunk.get(5).map(|p| p.0).unwrap_or(0.0),
                    chunk.get(4).map(|p| p.0).unwrap_or(0.0),
                    chunk.get(3).map(|p| p.0).unwrap_or(0.0),
                    chunk.get(2).map(|p| p.0).unwrap_or(0.0),
                    chunk.get(1).map(|p| p.0).unwrap_or(0.0),
                    chunk.get(0).map(|p| p.0).unwrap_or(0.0),
                );
                let target_y = _mm256_set_ps(
                    chunk.get(7).map(|p| p.1).unwrap_or(0.0),
                    chunk.get(6).map(|p| p.1).unwrap_or(0.0),
                    chunk.get(5).map(|p| p.1).unwrap_or(0.0),
                    chunk.get(4).map(|p| p.1).unwrap_or(0.0),
                    chunk.get(3).map(|p| p.1).unwrap_or(0.0),
                    chunk.get(2).map(|p| p.1).unwrap_or(0.0),
                    chunk.get(1).map(|p| p.1).unwrap_or(0.0),
                    chunk.get(0).map(|p| p.1).unwrap_or(0.0),
                );
                let target_z = _mm256_set_ps(
                    chunk.get(7).map(|p| p.2).unwrap_or(0.0),
                    chunk.get(6).map(|p| p.2).unwrap_or(0.0),
                    chunk.get(5).map(|p| p.2).unwrap_or(0.0),
                    chunk.get(4).map(|p| p.2).unwrap_or(0.0),
                    chunk.get(3).map(|p| p.2).unwrap_or(0.0),
                    chunk.get(2).map(|p| p.2).unwrap_or(0.0),
                    chunk.get(1).map(|p| p.2).unwrap_or(0.0),
                    chunk.get(0).map(|p| p.2).unwrap_or(0.0),
                );

                let dx = _mm256_sub_ps(target_x, start_x);
                let dy = _mm256_sub_ps(target_y, start_y);
                let dz = _mm256_sub_ps(target_z, start_z);

                // Manhattan distance: |dx| + |dy| + |dz|
                let abs_dx = _mm256_andnot_ps(_mm256_set1_ps(-0.0), dx);
                let abs_dy = _mm256_andnot_ps(_mm256_set1_ps(-0.0), dy);
                let abs_dz = _mm256_andnot_ps(_mm256_set1_ps(-0.0), dz);

                let sum = _mm256_add_ps(_mm256_add_ps(abs_dx, abs_dy), abs_dz);

                _mm256_storeu_ps(distances.as_mut_ptr(), sum);
                distances.into_iter().take(chunk.len()).collect::<Vec<_>>()
            })
            .collect()
    }
    #[cfg(not(target_feature = "avx2"))]
    {
        targets
            .iter()
            .map(|target| {
                (target.0 - start.0).abs() + (target.1 - start.1).abs() + (target.2 - start.2).abs()
            })
            .collect()
    }
}

/// SIMD-accelerated Euclidean distance calculation for pathfinding
pub fn calculate_euclidean_distances_simd(
    start: (f32, f32, f32),
    targets: &[(f32, f32, f32)],
) -> Vec<f32> {
    #[cfg(target_feature = "avx2")]
    {
        use std::arch::x86_64::*;

        let start_x = _mm256_set1_ps(start.0);
        let start_y = _mm256_set1_ps(start.1);
        let start_z = _mm256_set1_ps(start.2);

        targets
            .par_chunks(8)
            .flat_map(|chunk| {
                let mut distances = [0.0f32; 8];

                let target_x = _mm256_set_ps(
                    chunk.get(7).map(|p| p.0).unwrap_or(0.0),
                    chunk.get(6).map(|p| p.0).unwrap_or(0.0),
                    chunk.get(5).map(|p| p.0).unwrap_or(0.0),
                    chunk.get(4).map(|p| p.0).unwrap_or(0.0),
                    chunk.get(3).map(|p| p.0).unwrap_or(0.0),
                    chunk.get(2).map(|p| p.0).unwrap_or(0.0),
                    chunk.get(1).map(|p| p.0).unwrap_or(0.0),
                    chunk.get(0).map(|p| p.0).unwrap_or(0.0),
                );
                let target_y = _mm256_set_ps(
                    chunk.get(7).map(|p| p.1).unwrap_or(0.0),
                    chunk.get(6).map(|p| p.1).unwrap_or(0.0),
                    chunk.get(5).map(|p| p.1).unwrap_or(0.0),
                    chunk.get(4).map(|p| p.1).unwrap_or(0.0),
                    chunk.get(3).map(|p| p.1).unwrap_or(0.0),
                    chunk.get(2).map(|p| p.1).unwrap_or(0.0),
                    chunk.get(1).map(|p| p.1).unwrap_or(0.0),
                    chunk.get(0).map(|p| p.1).unwrap_or(0.0),
                );
                let target_z = _mm256_set_ps(
                    chunk.get(7).map(|p| p.2).unwrap_or(0.0),
                    chunk.get(6).map(|p| p.2).unwrap_or(0.0),
                    chunk.get(5).map(|p| p.2).unwrap_or(0.0),
                    chunk.get(4).map(|p| p.2).unwrap_or(0.0),
                    chunk.get(3).map(|p| p.2).unwrap_or(0.0),
                    chunk.get(2).map(|p| p.2).unwrap_or(0.0),
                    chunk.get(1).map(|p| p.1).unwrap_or(0.0),
                    chunk.get(0).map(|p| p.0).unwrap_or(0.0),
                );

                let dx = _mm256_sub_ps(target_x, start_x);
                let dy = _mm256_sub_ps(target_y, start_y);
                let dz = _mm256_sub_ps(target_z, start_z);

                let dx2 = _mm256_mul_ps(dx, dx);
                let dy2 = _mm256_mul_ps(dy, dy);
                let dz2 = _mm256_mul_ps(dz, dz);

                let sum = _mm256_add_ps(_mm256_add_ps(dx2, dy2), dz2);
                let dist = _mm256_sqrt_ps(sum);

                _mm256_storeu_ps(distances.as_mut_ptr(), dist);
                distances.into_iter().take(chunk.len()).collect::<Vec<_>>()
            })
            .collect()
    }
    #[cfg(not(target_feature = "avx2"))]
    {
        targets
            .iter()
            .map(|target| {
                let dx = target.0 - start.0;
                let dy = target.1 - start.1;
                let dz = target.2 - start.2;
                (dx * dx + dy * dy + dz * dz).sqrt()
            })
            .collect()
    }
}

/// SIMD-accelerated collision checking for pathfinding waypoints
/// Checks if multiple positions are blocked by obstacles
pub fn check_path_obstacles_simd(
    positions: &[(f32, f32, f32)],
    obstacles: &[crate::spatial::Aabb],
) -> Vec<bool> {
    #[cfg(target_feature = "avx2")]
    {
        use std::arch::x86_64::*;

        positions
            .par_iter()
            .map(|pos| {
                let pos_vec = glam::Vec3::new(pos.0, pos.1, pos.2);

                // Check against all obstacles
                for obstacle in obstacles {
                    if obstacle.contains(pos_vec) {
                        return true; // Position is blocked
                    }
                }
                false // Position is clear
            })
            .collect()
    }
    #[cfg(not(target_feature = "avx2"))]
    {
        positions
            .iter()
            .map(|pos| {
                let pos_vec = glam::Vec3::new(pos.0, pos.1, pos.2);

                // Check against all obstacles
                for obstacle in obstacles {
                    if obstacle.contains(pos_vec.into()) {
                        return true; // Position is blocked
                    }
                }
                false // Position is clear
            })
            .collect()
    }
}

/// SIMD-accelerated path smoothing - removes unnecessary waypoints
pub fn smooth_path_simd(
    waypoints: &[(f32, f32, f32)],
    obstacles: &[crate::spatial::Aabb],
) -> Vec<(f32, f32, f32)> {
    if waypoints.len() <= 2 {
        return waypoints.to_vec();
    }

    let mut smoothed = vec![waypoints[0]]; // Always include start
    let mut current_index = 0;

    while current_index < waypoints.len() - 2 {
        let mut furthest_reachable = current_index + 1;

        // Find the furthest waypoint we can reach directly
        for i in (current_index + 1)..waypoints.len() {
            // Check if line segment from current to i is clear
            if is_line_clear_simd(waypoints[current_index], waypoints[i], obstacles) {
                furthest_reachable = i;
            } else {
                break;
            }
        }

        // Add the furthest reachable waypoint
        smoothed.push(waypoints[furthest_reachable]);
        current_index = furthest_reachable;
    }

    // Always include the end
    if *smoothed.last().unwrap() != waypoints[waypoints.len() - 1] {
        smoothed.push(waypoints[waypoints.len() - 1]);
    }

    smoothed
}

/// Helper function to check if line segment between two points is clear
fn is_line_clear_simd(
    start: (f32, f32, f32),
    end: (f32, f32, f32),
    obstacles: &[crate::spatial::Aabb],
) -> bool {
    let direction = (end.0 - start.0, end.1 - start.1, end.2 - start.2);

    // Check ray intersection with all obstacles
    for obstacle in obstacles {
        let ray_origin = glam::Vec3::new(start.0, start.1, start.2);
        let ray_dir = glam::Vec3::new(direction.0, direction.1, direction.2);

        if let Some(_) = crate::spatial::ray_aabb_intersect_simd(ray_origin, ray_dir, obstacle) {
            return false; // Line is blocked
        }
    }

    true // Line is clear
}
/// JNI function for SIMD-accelerated heuristic distance calculation
#[no_mangle]
pub unsafe extern "C" fn Java_com_kneaf_core_performance_RustPerformance_calculateHeuristicDistancesSimd(
    _env: JNIEnv,
    _class: JClass,
    start_x: f32,
    start_y: f32,
    start_z: f32,
    targets_ptr: *const (f32, f32, f32),
    targets_len: usize,
) -> *mut f32 {
    let start = (start_x, start_y, start_z);
    let targets = unsafe { std::slice::from_raw_parts(targets_ptr, targets_len) };
    let distances = calculate_heuristic_distances_simd(start, targets);

    // Return as raw pointer (caller responsible for freeing)
    let mut boxed = distances.into_boxed_slice();
    let ptr = boxed.as_mut_ptr();
    std::mem::forget(boxed);
    ptr
}

/// JNI function for SIMD-accelerated Euclidean distance calculation
#[no_mangle]
pub unsafe extern "C" fn Java_com_kneaf_core_performance_RustPerformance_calculateEuclideanDistancesSimd(
    _env: JNIEnv,
    _class: JClass,
    start_x: f32,
    start_y: f32,
    start_z: f32,
    targets_ptr: *const (f32, f32, f32),
    targets_len: usize,
) -> *mut f32 {
    let start = (start_x, start_y, start_z);
    let targets = unsafe { std::slice::from_raw_parts(targets_ptr, targets_len) };
    let distances = calculate_euclidean_distances_simd(start, targets);

    // Return as raw pointer (caller responsible for freeing)
    let mut boxed = distances.into_boxed_slice();
    let ptr = boxed.as_mut_ptr();
    std::mem::forget(boxed);
    ptr
}

/// JNI function for SIMD-accelerated path obstacle checking
#[no_mangle]
pub unsafe extern "C" fn Java_com_kneaf_core_performance_RustPerformance_checkPathObstaclesSimd(
    _env: JNIEnv,
    _class: JClass,
    positions_ptr: *const (f32, f32, f32),
    positions_len: usize,
    obstacles_ptr: *const crate::spatial::Aabb,
    obstacles_len: usize,
) -> *mut bool {
    let positions = unsafe { std::slice::from_raw_parts(positions_ptr, positions_len) };
    let obstacles = unsafe { std::slice::from_raw_parts(obstacles_ptr, obstacles_len) };
    let results = check_path_obstacles_simd(positions, obstacles);

    // Return as raw pointer (caller responsible for freeing)
    let mut boxed = results.into_boxed_slice();
    let ptr = boxed.as_mut_ptr();
    std::mem::forget(boxed);
    ptr
}

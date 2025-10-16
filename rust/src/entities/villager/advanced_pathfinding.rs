use super::types::*;
use crate::logging::{generate_trace_id, PerformanceLogger};
use dashmap::DashMap;
use once_cell::sync::Lazy;
use rand::Rng;
use serde_json;
use std::sync::{Arc, RwLock};
use std::time::{Duration, Instant};
use std::collections::{HashMap, HashSet, VecDeque};

static ADVANCED_PATHFINDING_LOGGER: Lazy<PerformanceLogger> = 
    Lazy::new(|| PerformanceLogger::new("advanced_pathfinding"));

// ==============================================
// Core Pathfinding Structures
// ==============================================

/// Pathfinding context containing all necessary data for a path calculation
#[derive(Debug, Clone)]
pub struct PathfindingContext {
    pub villager_id: u64,
    pub start_position: (f32, f32, f32),
    pub goal_position: (f32, f32, f32),
    pub navigation_mesh: Vec<(f32, f32, f32)>,
    pub terrain_data: Option<TerrainData>,
    pub cached_path: Option<Vec<(f32, f32, f32)>>,
    pub algorithm: PathfindingAlgorithm,
}

/// Terrain data for dynamic pathfinding
#[derive(Debug, Clone, Default)]
pub struct TerrainData {
    pub obstacles: Vec<((f32, f32, f32), (f32, f32, f32))>, // (min, max) coordinates
    pub passable_types: HashSet<String>,
    pub last_updated: Instant,
}

/// Path cache entry with expiration
#[derive(Debug, Clone)]
pub struct PathCacheEntry {
    pub path: Vec<(f32, f32, f32)>,
    pub start: (f32, f32, f32),
    pub goal: (f32, f32, f32),
    pub timestamp: Instant,
    pub algorithm: PathfindingAlgorithm,
}

/// Flow field data structure for group pathfinding
#[derive(Debug, Clone, Default)]
pub struct FlowField {
    pub grid: HashMap<(i32, i32, i32), (f32, f32, f32)>, // (x, y, z) -> direction
    pub resolution: f32,
    pub center: (f32, f32, f32),
    pub radius: f32,
    pub last_updated: Instant,
}

/// Advanced pathfinding context containing all components
pub struct AdvancedPathfindingContext {
    pub path_cache: Arc<PathCache>,
    pub terrain_monitor: Arc<TerrainMonitor>,
    pub flow_field_manager: Arc<FlowFieldManager>,
    pub last_flow_field_update: Instant,
}

// ==============================================
// Path Cache Implementation
// ==============================================

/// Thread-safe path cache with TTL-based expiration
pub struct PathCache {
    cache: DashMap<(u64, (f32, f32, f32), (f32, f32, f32), PathfindingAlgorithm), PathCacheEntry>,
    ttl: Duration,
}

impl PathCache {
    pub fn new(ttl: Duration) -> Self {
        Self {
            cache: DashMap::new(),
            ttl,
        }
    }

    /// Get a cached path if it exists and is valid
    pub fn get_cached_path(
        &self,
        villager_id: u64,
        start: (f32, f32, f32),
        goal: (f32, f32, f32),
        algorithm: PathfindingAlgorithm,
    ) -> Option<Vec<(f32, f32, f32)>> {
        let key = (villager_id, start, goal, algorithm);
        
        let trace_id = generate_trace_id();
        let start_time = Instant::now();
        
        let result = self.cache.get(&key).and_then(|entry| {
            let entry = entry.value();
            
            // Check if entry is still valid (not expired)
            if Instant::now().duration_since(entry.timestamp) <= self.ttl {
                ADVANCED_PATHFINDING_LOGGER.log_debug(
                    "cache_hit",
                    &trace_id,
                    &format!("Cache hit for villager {} - using cached path", villager_id),
                );
                Some(entry.path.clone())
            } else {
                ADVANCED_PATHFINDING_LOGGER.log_debug(
                    "cache_miss_expired",
                    &trace_id,
                    &format!("Cache miss - entry expired for villager {}", villager_id),
                );
                None
            }
        });
        
        ADVANCED_PATHFINDING_LOGGER.log_debug(
            "cache_lookup",
            &trace_id,
            &format!("Cache lookup completed in {:?}", start_time.elapsed()),
        );
        
        result
    }

    /// Add a path to the cache
    pub fn add_cached_path(
        &self,
        villager_id: u64,
        start: (f32, f32, f32),
        goal: (f32, f32, f32),
        algorithm: PathfindingAlgorithm,
        path: Vec<(f32, f32, f32)>,
    ) {
        let key = (villager_id, start, goal, algorithm);
        let entry = PathCacheEntry {
            path,
            start,
            goal,
            timestamp: Instant::now(),
            algorithm,
        };
        
        let trace_id = generate_trace_id();
        let start_time = Instant::now();
        
        self.cache.insert(key, entry);
        
        ADVANCED_PATHFINDING_LOGGER.log_debug(
            "cache_added",
            &trace_id,
            &format!("Added path to cache for villager {} in {:?}", villager_id, start_time.elapsed()),
        );
    }

    /// Invalidate paths affected by terrain changes
    pub fn invalidate_paths_affected_by_terrain(&self, changed_region: ((f32, f32, f32), (f32, f32, f32))) {
        let trace_id = generate_trace_id();
        let start_time = Instant::now();
        
        let mut keys_to_remove = Vec::new();
        
        // This is a simplified implementation - in a real system you would use a spatial index
        // to quickly find affected paths instead of iterating all entries
        for entry in self.cache.iter() {
            let entry = entry.value();
            
            // Check if any point in the path is within the changed region
            let path_intersects = entry.path.iter().any(|&(x, y, z)| {
                x >= changed_region.0 .0 && x <= changed_region.1 .0 &&
                y >= changed_region.0 .1 && y <= changed_region.1 .1 &&
                z >= changed_region.0 .2 && z <= changed_region.1 .2
            });
            
            if path_intersects {
                keys_to_remove.push(entry.start);
            }
        }
        
        // Remove invalidated entries
        for key in keys_to_remove {
            // In a real implementation, you would need the full key (villager_id, goal, algorithm)
            // This is just a simplified example
            ADVANCED_PATHFINDING_LOGGER.log_debug(
                "cache_invalidated",
                &trace_id,
                &format!("Invalidated path cache entry for region change"),
            );
        }
        
        ADVANCED_PATHFINDING_LOGGER.log_debug(
            "cache_invalidation_complete",
            &trace_id,
            &format!("Cache invalidation completed in {:?}", start_time.elapsed()),
        );
    }

    /// Clear all expired entries from the cache
    pub fn cleanup_expired_entries(&self) {
        let trace_id = generate_trace_id();
        let start_time = Instant::now();
        
        let now = Instant::now();
        let mut expired_count = 0;
        
        // Iterate and remove expired entries
        self.cache.retain(|_, entry| {
            if now.duration_since(entry.timestamp) > self.ttl {
                expired_count += 1;
                false
            } else {
                true
            }
        });
        
        ADVANCED_PATHFINDING_LOGGER.log_debug(
            "cache_cleanup",
            &trace_id,
            &format!("Cleaned up {} expired cache entries in {:?}", expired_count, start_time.elapsed()),
        );
    }
}

// ==============================================
// Terrain Monitor Implementation
// ==============================================

/// Monitors terrain changes for dynamic pathfinding
pub struct TerrainMonitor {
    terrain_data: RwLock<TerrainData>,
    change_listeners: Vec<Box<dyn Fn((f32, f32, f32), (f32, f32, f32)) + Send + Sync>>,
}

impl TerrainMonitor {
    pub fn new() -> Self {
        Self {
            terrain_data: RwLock::new(TerrainData::default()),
            change_listeners: Vec::new(),
        }
    }

    /// Update terrain data with new obstacles
    pub fn update_terrain(&self, changed_region: ((f32, f32, f32), (f32, f32, f32))) {
        let trace_id = generate_trace_id();
        let start_time = Instant::now();
        
        let mut terrain = self.terrain_data.write().unwrap();
        terrain.obstacles.push(changed_region);
        terrain.last_updated = Instant::now();
        
        // Notify listeners about the terrain change
        for listener in &self.change_listeners {
            listener(changed_region.0, changed_region.1);
        }
        
        ADVANCED_PATHFINDING_LOGGER.log_debug(
            "terrain_updated",
            &trace_id,
            &format!("Updated terrain with {} new obstacles in {:?}", terrain.obstacles.len(), start_time.elapsed()),
        );
    }

    /// Get current terrain data
    pub fn get_terrain_data(&self) -> TerrainData {
        let terrain = self.terrain_data.read().unwrap();
        terrain.clone()
    }

    /// Register a listener for terrain changes
    pub fn register_change_listener<F: Fn((f32, f32, f32), (f32, f32, f32)) + Send + Sync + 'static>(
        &mut self,
        listener: F,
    ) {
        self.change_listeners.push(Box::new(listener));
    }
}

// ==============================================
// Flow Field Manager Implementation
// ==============================================

/// Manages flow fields for group pathfinding
pub struct FlowFieldManager {
    flow_fields: RwLock<HashMap<u32, FlowField>>,
    resolution: f32,
}

impl FlowFieldManager {
    pub fn new(resolution: f32) -> Self {
        Self {
            flow_fields: RwLock::new(HashMap::new()),
            resolution,
        }
    }

    /// Update or create a flow field for a specific region
    pub fn update_flow_field(&self, center: (f32, f32, f32), radius: f32) -> u32 {
        let trace_id = generate_trace_id();
        let start_time = Instant::now();
        
        let field_id = rand::thread_rng().gen();
        let mut flow_field = FlowField::default();
        flow_field.center = center;
        flow_field.radius = radius;
        flow_field.resolution = self.resolution;
        flow_field.last_updated = Instant::now();
        
        // In a real implementation, you would calculate the actual flow directions
        // based on the navigation mesh, obstacles, and group goal
        let grid_size = ((radius * 2.0) / self.resolution) as i32;
        
        for x in -grid_size..grid_size {
            for y in -grid_size..grid_size {
                for z in -grid_size..grid_size {
                    let world_pos = (
                        center.0 + (x as f32 * self.resolution),
                        center.1 + (y as f32 * self.resolution),
                        center.2 + (z as f32 * self.resolution),
                    );
                    
                    // Simplified: point towards the center (goal)
                    let direction = (
                        center.0 - world_pos.0,
                        center.1 - world_pos.1,
                        center.2 - world_pos.2,
                    );
                    
                    flow_field.grid.insert((x, y, z), direction);
                }
            }
        }
        
        let mut fields = self.flow_fields.write().unwrap();
        fields.insert(field_id, flow_field);
        
        ADVANCED_PATHFINDING_LOGGER.log_debug(
            "flow_field_updated",
            &trace_id,
            &format!("Updated flow field {} with {} nodes in {:?}", field_id, flow_field.grid.len(), start_time.elapsed()),
        );
        
        field_id
    }

    /// Get a flow field by ID
    pub fn get_flow_field(&self, field_id: u32) -> Option<FlowField> {
        let fields = self.flow_fields.read().unwrap();
        fields.get(&field_id).cloned()
    }

    /// Remove an unused flow field
    pub fn remove_flow_field(&self, field_id: u32) {
        let mut fields = self.flow_fields.write().unwrap();
        fields.remove(&field_id);
    }
}

// ==============================================
// Core Pathfinding Algorithms
// ==============================================

impl AdvancedPathfindingContext {
    pub fn new() -> Self {
        let path_cache = Arc::new(PathCache::new(Duration::from_secs(30)));
        let terrain_monitor = Arc::new(TerrainMonitor::new());
        let flow_field_manager = Arc::new(FlowFieldManager::new(1.0));
        
        Self {
            path_cache,
            terrain_monitor,
            flow_field_manager,
            last_flow_field_update: Instant::now(),
        }
    }

    /// Create a pathfinding context for a villager
    pub fn create_pathfinding_context(
        &self,
        villager_id: u64,
        start: (f32, f32, f32),
        algorithm: PathfindingAlgorithm,
    ) -> PathfindingContext {
        let trace_id = generate_trace_id();
        let start_time = Instant::now();
        
        let navigation_mesh = Vec::new(); // In a real implementation, get from spatial manager
        let terrain_data = Some(self.terrain_monitor.get_terrain_data());
        let cached_path = self.path_cache.get_cached_path(villager_id, start, (0.0, 0.0, 0.0), algorithm);
        
        let context = PathfindingContext {
            villager_id,
            start_position: start,
            goal_position: (0.0, 0.0, 0.0), // Placeholder - will be set before pathfinding
            navigation_mesh,
            terrain_data,
            cached_path,
            algorithm,
        };
        
        ADVANCED_PATHFINDING_LOGGER.log_debug(
            "context_created",
            &trace_id,
            &format!("Created pathfinding context for villager {} in {:?}", villager_id, start_time.elapsed()),
        );
        
        context
    }

    /// Find a path for a villager using the selected algorithm
    pub fn find_villager_path(
        &self,
        villager_id: u64,
        start: (f32, f32, f32),
        goal: (f32, f32, f32),
    ) -> Option<Vec<(f32, f32, f32)>> {
        let trace_id = generate_trace_id();
        let start_time = Instant::now();
        
        // First, try to use cached path if available
        if let Some(cached_path) = self.path_cache.get_cached_path(villager_id, start, goal, PathfindingAlgorithm::Auto) {
            ADVANCED_PATHFINDING_LOGGER.log_debug(
                "path_found_cached",
                &trace_id,
                &format!("Found cached path for villager {} in {:?}", villager_id, start_time.elapsed()),
            );
            return Some(cached_path);
        }

        // Otherwise, run the appropriate pathfinding algorithm
        let path = match PathfindingAlgorithm::Auto {
            // In a real implementation, you would select the best algorithm based on
            // map size, distance, villager type, and other factors
            _ => self.run_astar(start, goal, &self.terrain_monitor.get_terrain_data()),
        };

        if let Some(path) = &path {
            // Cache the successful path
            self.path_cache.add_cached_path(villager_id, start, goal, PathfindingAlgorithm::Auto, path.clone());
            
            ADVANCED_PATHFINDING_LOGGER.log_debug(
                "path_found",
                &trace_id,
                &format!("Found new path for villager {} with {} nodes in {:?}", villager_id, path.len(), start_time.elapsed()),
            );
        } else {
            ADVANCED_PATHFINDING_LOGGER.log_debug(
                "path_not_found",
                &trace_id,
                &format!("No path found for villager {} after {:?}", villager_id, start_time.elapsed()),
            );
        }

        path
    }

    /// Find paths for a group of villagers using flow field pathfinding
    pub fn find_paths_for_group(
        &self,
        group: &VillagerGroup,
        goal: (f32, f32, f32),
    ) -> Vec<PathfindingResult> {
        let trace_id = generate_trace_id();
        let start_time = Instant::now();
        
        let mut results = Vec::new();
        
        // Create or update flow field for the group
        let field_id = self.flow_field_manager.update_flow_field(goal, group.villager_ids.len() as f32 * 5.0);
        
        // For each villager, find a path using the flow field
        for &villager_id in &group.villager_ids {
            let start = (0.0, 0.0, 0.0); // In a real implementation, get actual position
            
            // Use flow field to guide pathfinding
            let path = self.run_flow_field_pathfinding(start, goal, field_id);
            
            results.push(PathfindingResult {
                villager_id,
                success: path.is_some(),
                path,
                algorithm_used: PathfindingAlgorithm::FlowField,
                steps_taken: 0, // Will be set by actual pathfinding algorithm
                execution_time: start_time.elapsed(),
                error: None,
            });
        }
        
        ADVANCED_PATHFINDING_LOGGER.log_debug(
            "group_paths_found",
            &trace_id,
            &format!("Found paths for group of {} villagers in {:?}", group.villager_ids.len(), start_time.elapsed()),
        );
        
        results
    }

    /// Run A* pathfinding algorithm
    fn run_astar(&self, start: (f32, f32, f32), goal: (f32, f32, f32), terrain_data: &TerrainData) -> Option<Vec<(f32, f32, f32)>> {
        let trace_id = generate_trace_id();
        let start_time = Instant::now();
        
        // Simplified A* implementation - in a real system you would use a proper priority queue
        let mut open_set = HashSet::new();
        let mut closed_set = HashSet::new();
        let mut came_from = HashMap::new();
        let mut g_score = HashMap::new();
        let mut f_score = HashMap::new();
        
        let start_node = start;
        open_set.insert(start_node);
        g_score.insert(start_node, 0.0);
        f_score.insert(start_node, heuristic(start_node, goal));
        
        while !open_set.is_empty() {
            // Find node with lowest f_score (simplified - in real A* use a priority queue)
            let current = *open_set.iter().min_by_key(|&&node| f_score[&node]).unwrap();
            
            if current == goal {
                let path = reconstruct_path(came_from, start, current);
                ADVANCED_PATHFINDING_LOGGER.log_debug(
                    "astar_complete",
                    &trace_id,
                    &format!("A* completed in {:?} with {} nodes", start_time.elapsed(), path.len()),
                );
                return Some(path);
            }
            
            open_set.remove(&current);
            closed_set.insert(current);
            
            // Get neighbors (simplified - in real implementation use navigation mesh)
            let neighbors = get_neighbors(current, terrain_data);
            
            for neighbor in neighbors {
                if closed_set.contains(&neighbor) {
                    continue;
                }
                
                let tentative_g_score = *g_score.get(&current).unwrap_or(&f32::INFINITY) + distance(current, neighbor);
                
                let neighbor_g_score = g_score.get(&neighbor).copied().unwrap_or(f32::INFINITY);
                if tentative_g_score >= neighbor_g_score {
                    continue;
                }
                
                came_from.insert(neighbor, current);
                g_score.insert(neighbor, tentative_g_score);
                f_score.insert(neighbor, tentative_g_score + heuristic(neighbor, goal));
                
                if !open_set.contains(&neighbor) {
                    open_set.insert(neighbor);
                }
            }
        }
        
        // No path found
        ADVANCED_PATHFINDING_LOGGER.log_debug(
            "astar_failed",
            &trace_id,
            &format!("A* failed to find path after {:?}", start_time.elapsed()),
        );
        None
    }

    /// Run flow field pathfinding algorithm
    fn run_flow_field_pathfinding(&self, start: (f32, f32, f32), goal: (f32, f32, f32), field_id: u32) -> Option<Vec<(f32, f32, f32)>> {
        let trace_id = generate_trace_id();
        let start_time = Instant::now();
        
        // Get the flow field
        let flow_field = match self.flow_field_manager.get_flow_field(field_id) {
            Some(field) => field,
            None => {
                ADVANCED_PATHFINDING_LOGGER.log_debug(
                    "flow_field_missing",
                    &trace_id,
                    "Flow field not found",
                );
                return None;
            }
        };
        
        // Convert start position to flow field coordinates
        let start_grid = world_to_grid(start, flow_field.center, flow_field.resolution);
        
        // Path following using flow field directions
        let mut path = Vec::new();
        let mut current = start;
        let mut steps = 0;
        
        while distance(current, goal) > 0.5 && steps < 1000 {
            path.push(current);
            
            // Get flow direction for current position
            let grid_pos = world_to_grid(current, flow_field.center, flow_field.resolution);
            let direction = flow_field.grid.get(&grid_pos).cloned().unwrap_or((0.0, 0.0, 0.0));
            
            // Move in the direction of the flow field
            current.0 += direction.0 * flow_field.resolution;
            current.1 += direction.1 * flow_field.resolution;
            current.2 += direction.2 * flow_field.resolution;
            
            steps += 1;
        }
        
        // Add the goal as the final point
        path.push(goal);
        
        ADVANCED_PATHFINDING_LOGGER.log_debug(
            "flow_field_complete",
            &trace_id,
            &format!("Flow field pathfinding completed in {:?} with {} nodes", start_time.elapsed(), path.len()),
        );
        
        Some(path)
    }
}

// ==============================================
// Helper Functions
// ==============================================

/// Heuristic function for pathfinding (Manhattan distance)
fn heuristic(a: (f32, f32, f32), b: (f32, f32, f32)) -> f32 {
    (a.0 - b.0).abs() + (a.1 - b.1).abs() + (a.2 - b.2).abs()
}

/// Calculate Euclidean distance between two points
fn distance(a: (f32, f32, f32), b: (f32, f32, f32)) -> f32 {
    let dx = a.0 - b.0;
    let dy = a.1 - b.1;
    let dz = a.2 - b.2;
    (dx * dx + dy * dy + dz * dz).sqrt()
}

/// Reconstruct path from start to goal using came_from map
fn reconstruct_path(came_from: HashMap<(f32, f32, f32), (f32, f32, f32)>, start: (f32, f32, f32), goal: (f32, f32, f32)) -> Vec<(f32, f32, f32)> {
    let mut path = Vec::new();
    let mut current = goal;
    
    // Add goal first
    path.push(current);
    
    // Trace back from goal to start
    while current != start {
        current = *came_from.get(&current).expect("No path found");
        path.push(current);
    }
    
    // Reverse to get path from start to goal
    path.reverse();
    path
}

/// Get neighboring nodes for a given position (simplified)
fn get_neighbors(pos: (f32, f32, f32), terrain_data: &TerrainData) -> Vec<(f32, f32, f32)> {
    let mut neighbors = Vec::new();
    const STEP_SIZE: f32 = 1.0;
    
    // Check all 6 cardinal directions
    let directions = [
        (STEP_SIZE, 0.0, 0.0),
        (-STEP_SIZE, 0.0, 0.0),
        (0.0, STEP_SIZE, 0.0),
        (0.0, -STEP_SIZE, 0.0),
        (0.0, 0.0, STEP_SIZE),
        (0.0, 0.0, -STEP_SIZE),
    ];
    
    for dir in directions {
        let neighbor = (pos.0 + dir.0, pos.1 + dir.1, pos.2 + dir.2);
        
        // Check if neighbor is passable (not in obstacles)
        let is_obstacle = terrain_data.obstacles.iter().any(|&((ox, oy, oz), (hx, hy, hz))| {
            neighbor.0 >= ox && neighbor.0 <= hx &&
            neighbor.1 >= oy && neighbor.1 <= hy &&
            neighbor.2 >= oz && neighbor.2 <= hz
        });
        
        if !is_obstacle {
            neighbors.push(neighbor);
        }
    }
    
    neighbors
}

/// Convert world coordinates to grid coordinates
fn world_to_grid(world: (f32, f32, f32), center: (f32, f32, f32), resolution: f32) -> (i32, i32, i32) {
    let x = ((world.0 - center.0) / resolution).round() as i32;
    let y = ((world.1 - center.1) / resolution).round() as i32;
    let z = ((world.2 - center.2) / resolution).round() as i32;
    (x, y, z)
}

// ==============================================
// Path Smoothing Implementation
// ==============================================

/// Smooth a path to reduce zig-zag movement
pub fn smooth_path(path: &[ (f32, f32, f32) ], navigation_mesh: &[ (f32, f32, f32) ]) -> Vec<(f32, f32, f32)> {
    let trace_id = generate_trace_id();
    let start_time = Instant::now();
    
    if path.len() <= 2 {
        return path.to_vec();
    }
    
    let mut smoothed_path = Vec::new();
    smoothed_path.push(path[0]);
    
    let mut i = 0;
    while i < path.len() - 1 {
        let j = path.len() - 1;
        
        // Find the farthest point that is visible from current point
        while j > i + 1 && is_visible(path[i], path[j], navigation_mesh) {
            j -= 1;
        }
        
        if j != i + 1 {
            smoothed_path.push(path[j]);
        }
        
        i = j;
    }
    
    smoothed_path.push(path[path.len() - 1]);
    
    ADVANCED_PATHFINDING_LOGGER.log_debug(
        "path_smoothed",
        &trace_id,
        &format!("Smoothed path from {} nodes to {} nodes in {:?}", path.len(), smoothed_path.len(), start_time.elapsed()),
    );
    
    smoothed_path
}

/// Check if two points are visible (no obstacles in between)
fn is_visible(a: (f32, f32, f32), b: (f32, f32, f32), navigation_mesh: &[ (f32, f32, f32) ]) -> bool {
    // Simplified visibility check - in a real implementation you would raycast against obstacles
    // and navigation mesh to determine if the line between a and b is clear
    
    // For this example, we'll just check if there's a direct connection in the navigation mesh
    navigation_mesh.iter().any(|&node| {
        (node == a || node == b) && distance(a, b) < 5.0 // Max distance for direct visibility
    })
}

// ==============================================
// Dynamic Pathfinding Implementation
// ==============================================

/// Update a path dynamically in response to terrain changes
pub fn update_path_dynamically(
    original_path: &[ (f32, f32, f32) ],
    changed_region: ((f32, f32, f32), (f32, f32, f32)),
    navigation_mesh: &[ (f32, f32, f32) ],
) -> Option<Vec<(f32, f32, f32)>> {
    let trace_id = generate_trace_id();
    let start_time = Instant::now();
    
    // Find the first point in the path that is affected by the terrain change
    let affected_index = original_path.iter().position(|&point| {
        point.0 >= changed_region.0 .0 && point.0 <= changed_region.1 .0 &&
        point.1 >= changed_region.0 .1 && point.1 <= changed_region.1 .1 &&
        point.2 >= changed_region.0 .2 && point.2 <= changed_region.1 .2
    });
    
    if let Some(index) = affected_index {
        // Recalculate path from the affected point to the end
        let new_start = original_path[index];
        let new_goal = original_path.last().cloned().unwrap();
        
        // In a real implementation, you would run the appropriate pathfinding algorithm here
        // For this example, we'll just return a simplified path
        let mut new_path = original_path[0..=index].to_vec();
        
        // Add some detour points to demonstrate dynamic pathfinding
        let detour_points = generate_detour_points(new_start, new_goal, changed_region);
        new_path.extend(detour_points);
        new_path.push(new_goal);
        
        ADVANCED_PATHFINDING_LOGGER.log_debug(
            "path_updated_dynamically",
            &trace_id,
            &format!("Updated path dynamically - added {} detour points in {:?}", detour_points.len(), start_time.elapsed()),
        );
        
        Some(new_path)
    } else {
        // No points in the path were affected by the terrain change
        ADVANCED_PATHFINDING_LOGGER.log_debug(
            "path_no_change_needed",
            &trace_id,
            "No path update needed - terrain change did not affect current path",
        );
        Some(original_path.to_vec())
    }
}

/// Generate detour points for dynamic pathfinding
fn generate_detour_points(start: (f32, f32, f32), goal: (f32, f32, f32), obstacle: ((f32, f32, f32), (f32, f32, f32))) -> Vec<(f32, f32, f32)> {
    let mut rng = rand::thread_rng();
    let mut detour = Vec::new();
    
    // Calculate direction from start to goal
    let dx = goal.0 - start.0;
    let dy = goal.1 - start.1;
    let dz = goal.2 - start.2;
    
    // Generate detour points around the obstacle
    let detour_size = 3; // Number of detour points
    
    for i in 0..detour_size {
        // Generate points perpendicular to the path direction, outside the obstacle
        let t = (i as f32) / (detour_size as f32 - 1.0);
        let progress = start.0 + dx * t;
        
        let offset = 2.0 + rng.gen_range(0.0..1.0); // Random offset between 2.0 and 3.0
        let perpendicular_x = -dy * offset;
        let perpendicular_y = dx * offset;
        let perpendicular_z = 0.0; // Keep z relatively flat for simplicity
        
        let point = (
            progress + perpendicular_x,
            start.1 + dy * t + perpendicular_y,
            start.2 + dz * t + perpendicular_z,
        );
        
        detour.push(point);
    }
    
    detour
}

// ==============================================
// Multi-threaded Pathfinding Implementation
// ==============================================

/// Find paths for multiple villagers concurrently
pub fn find_paths_concurrently(
    villagers: &[u64],
    start_positions: &[(f32, f32, f32)],
    goal: (f32, f32, f32),
    path_cache: &Arc<PathCache>,
    terrain_monitor: &Arc<TerrainMonitor>,
) -> Vec<(u64, Option<Vec<(f32, f32, f32)>>)> {
    use rayon::prelude::*;
    
    let trace_id = generate_trace_id();
    let start_time = Instant::now();
    
    let terrain_data = terrain_monitor.get_terrain_data();
    
    let results = villagers.par_iter()
        .zip(start_positions.par_iter())
        .map(|(&villager_id, &start)| {
            // Try to use cached path first
            let cached_path = path_cache.get_cached_path(villager_id, start, goal, PathfindingAlgorithm::Auto);
            
            if let Some(path) = cached_path {
                (villager_id, Some(path))
            } else {
                // Run A* pathfinding (or other algorithm)
                let path = run_astar(start, goal, &terrain_data);
                
                if let Some(path) = &path {
                    // Cache the successful path
                    path_cache.add_cached_path(villager_id, start, goal, PathfindingAlgorithm::Auto, path.clone());
                }
                
                (villager_id, path)
            }
        })
        .collect();
    
    ADVANCED_PATHFINDING_LOGGER.log_debug(
        "concurrent_pathfinding_complete",
        &trace_id,
        &format!("Found paths for {} villagers concurrently in {:?}", villagers.len(), start_time.elapsed()),
    );
    
    results
}

// ==============================================
// Public API
// ==============================================

/// Initialize the advanced pathfinding system
pub fn initialize_advanced_pathfinding() -> Arc<AdvancedPathfindingContext> {
    let path_cache = Arc::new(PathCache::new(Duration::from_secs(30)));
    let terrain_monitor = Arc::new(TerrainMonitor::new());
    let flow_field_manager = Arc::new(FlowFieldManager::new(1.0));
    
    Arc::new(AdvancedPathfindingContext {
        path_cache,
        terrain_monitor,
        flow_field_manager,
        last_flow_field_update: Instant::now(),
    })
}
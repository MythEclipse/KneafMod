//! Native A* Pathfinding Implementation
//!
//! This module provides a high-performance, native Rust implementation of the A* pathfinding algorithm
//! with async support, multi-threading capabilities, and integration with the entity system.
//! Replaces the Java implementation in KneafCore.java with better performance and memory safety.

use crate::entity_registry::EntityId;
use crate::performance_monitor::PerformanceMonitor;
use glam::Vec3;
use log::{debug, info, warn};
use rayon::prelude::*;
use serde::{Deserialize, Serialize};
use std::collections::{BinaryHeap, HashMap};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::task::JoinHandle;

/// Configuration for pathfinding behavior
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PathfindingConfig {
    pub max_search_distance: f32,
    pub heuristic_weight: f32,
    pub timeout_ms: u64,
    pub allow_diagonal_movement: bool,
    pub path_smoothing_enabled: bool,
    pub async_thread_pool_size: usize,
}

impl Default for PathfindingConfig {
    fn default() -> Self {
        Self {
            max_search_distance: 100.0,
            heuristic_weight: 1.0,
            timeout_ms: 1000,
            allow_diagonal_movement: false,
            path_smoothing_enabled: true,
            async_thread_pool_size: 4,
        }
    }
}

/// Represents a 2D grid for pathfinding
#[derive(Debug, Clone)]
pub struct PathfindingGrid {
    pub width: usize,
    pub height: usize,
    pub obstacles: Vec<bool>, // true = obstacle, false = walkable
}

impl PathfindingGrid {
    pub fn new(width: usize, height: usize) -> Self {
        Self {
            width,
            height,
            obstacles: vec![false; width * height],
        }
    }

    pub fn set_obstacle(&mut self, x: usize, y: usize, is_obstacle: bool) {
        if x < self.width && y < self.height {
            self.obstacles[y * self.width + x] = is_obstacle;
        }
    }

    pub fn is_walkable(&self, x: usize, y: usize) -> bool {
        x < self.width && y < self.height && !self.obstacles[y * self.width + x]
    }

    pub fn is_valid_coordinate(&self, x: usize, y: usize) -> bool {
        x < self.width && y < self.height
    }
}

/// Represents a node in the A* search
#[derive(Debug, Clone, PartialEq)]
struct PathNode {
    position: (usize, usize),
    g_cost: f32, // Cost from start to this node
    h_cost: f32, // Heuristic cost from this node to goal
    f_cost: f32, // Total cost (g + h)
    parent: Option<(usize, usize)>,
}

impl PathNode {
    fn new(
        position: (usize, usize),
        g_cost: f32,
        h_cost: f32,
        parent: Option<(usize, usize)>,
    ) -> Self {
        Self {
            position,
            g_cost,
            h_cost,
            f_cost: g_cost + h_cost,
            parent,
        }
    }
}

// Manual Eq implementation for PathNode
impl Eq for PathNode {}

// Reverse ordering for BinaryHeap (min-heap)
impl Ord for PathNode {
    fn cmp(&self, other: &Self) -> std::cmp::Ordering {
        other
            .f_cost
            .partial_cmp(&self.f_cost)
            .unwrap_or(std::cmp::Ordering::Equal)
            .then_with(|| other.position.cmp(&self.position))
    }
}

impl PartialOrd for PathNode {
    fn partial_cmp(&self, other: &Self) -> Option<std::cmp::Ordering> {
        Some(self.cmp(other))
    }
}

/// Result of a pathfinding operation
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PathfindingResult {
    pub success: bool,
    pub path: Vec<(usize, usize)>,
    pub path_length: f32,
    pub nodes_explored: usize,
    pub execution_time_ms: u64,
    pub error_message: Option<String>,
}

/// Type alias for complex pathfinding request type
type PathfindingRequest = (PathfindingGrid, (usize, usize), (usize, usize));

/// Main pathfinding system
pub struct PathfindingSystem {
    config: PathfindingConfig,
    performance_monitor: Arc<PerformanceMonitor>,
}

impl PathfindingSystem {
    pub fn new(config: PathfindingConfig, performance_monitor: Arc<PerformanceMonitor>) -> Self {
        info!("Initializing PathfindingSystem with config: {:?}", config);
        Self {
            config,
            performance_monitor,
        }
    }

    /// Perform A* pathfinding on a 2D grid
    pub fn find_path(
        &self,
        grid: &PathfindingGrid,
        start: (usize, usize),
        goal: (usize, usize),
    ) -> PathfindingResult {
        let start_time = Instant::now();

        debug!("Starting A* pathfinding from {:?} to {:?}", start, goal);

        // Validate inputs
        if !grid.is_valid_coordinate(start.0, start.1) || !grid.is_valid_coordinate(goal.0, goal.1)
        {
            return PathfindingResult {
                success: false,
                path: vec![],
                path_length: 0.0,
                nodes_explored: 0,
                execution_time_ms: start_time.elapsed().as_millis() as u64,
                error_message: Some("Start or goal position outside grid bounds".to_string()),
            };
        }

        if !grid.is_walkable(start.0, start.1) || !grid.is_walkable(goal.0, goal.1) {
            return PathfindingResult {
                success: false,
                path: vec![],
                path_length: 0.0,
                nodes_explored: 0,
                execution_time_ms: start_time.elapsed().as_millis() as u64,
                error_message: Some("Start or goal position is not walkable".to_string()),
            };
        }

        if start == goal {
            return PathfindingResult {
                success: true,
                path: vec![start],
                path_length: 0.0,
                nodes_explored: 1,
                execution_time_ms: start_time.elapsed().as_millis() as u64,
                error_message: None,
            };
        }

        // A* algorithm implementation
        let mut open_set = BinaryHeap::new();
        let mut closed_set = HashMap::new();
        let mut came_from = HashMap::new();
        let mut g_score = HashMap::new();

        let start_node = PathNode::new(start, 0.0, self.heuristic(start, goal), None);
        open_set.push(start_node);
        g_score.insert(start, 0.0);

        let mut nodes_explored = 0;
        let timeout_duration = Duration::from_millis(self.config.timeout_ms);

        while let Some(current) = open_set.pop() {
            nodes_explored += 1;

            // Check timeout
            if start_time.elapsed() > timeout_duration {
                warn!(
                    "A* pathfinding timed out after {}ms",
                    self.config.timeout_ms
                );
                return PathfindingResult {
                    success: false,
                    path: vec![],
                    path_length: 0.0,
                    nodes_explored,
                    execution_time_ms: start_time.elapsed().as_millis() as u64,
                    error_message: Some("Pathfinding timed out".to_string()),
                };
            }

            let current_pos = current.position;

            // Check if we reached the goal
            if current_pos == goal {
                let path = self.reconstruct_path(&came_from, current_pos);
                let path_length = self.calculate_path_length(&path);

                debug!(
                    "Pathfinding successful: {} nodes, length: {:.2}",
                    path.len(),
                    path_length
                );

                return PathfindingResult {
                    success: true,
                    path,
                    path_length,
                    nodes_explored,
                    execution_time_ms: start_time.elapsed().as_millis() as u64,
                    error_message: None,
                };
            }

            // Skip if already processed
            if closed_set.contains_key(&current_pos) {
                continue;
            }

            closed_set.insert(current_pos, current.clone());

            // Explore neighbors
            let neighbors = self.get_neighbors(&current_pos, grid);

            for neighbor_pos in neighbors {
                if closed_set.contains_key(&neighbor_pos) {
                    continue;
                }

                let tentative_g = current.g_cost + self.distance(current_pos, neighbor_pos);

                if !g_score.contains_key(&neighbor_pos) || tentative_g < g_score[&neighbor_pos] {
                    came_from.insert(neighbor_pos, current_pos);
                    g_score.insert(neighbor_pos, tentative_g);

                    let h_cost = self.heuristic(neighbor_pos, goal) * self.config.heuristic_weight;
                    let neighbor_node =
                        PathNode::new(neighbor_pos, tentative_g, h_cost, Some(current_pos));

                    open_set.push(neighbor_node);
                }
            }
        }

        // No path found
        debug!("No path found from {:?} to {:?}", start, goal);

        PathfindingResult {
            success: false,
            path: vec![],
            path_length: 0.0,
            nodes_explored,
            execution_time_ms: start_time.elapsed().as_millis() as u64,
            error_message: Some("No path found".to_string()),
        }
    }

    /// Perform A* pathfinding asynchronously
    pub async fn find_path_async(
        &self,
        grid: PathfindingGrid,
        start: (usize, usize),
        goal: (usize, usize),
    ) -> PathfindingResult {
        let start_time = Instant::now();

        // Use tokio spawn for async execution
        let config_clone = self.config.clone();
        let performance_monitor_clone = self.performance_monitor.clone();
        let grid_clone = grid.clone();

        let handle: JoinHandle<PathfindingResult> = tokio::spawn(async move {
            // Create a new pathfinding system for the async task
            let temp_system = PathfindingSystem::new(config_clone, performance_monitor_clone);
            temp_system.find_path(&grid_clone, start, goal)
        });

        match handle.await {
            Ok(result) => {
                let total_time = start_time.elapsed().as_millis() as u64;
                debug!("Async pathfinding completed in {}ms", total_time);
                result
            }
            Err(e) => {
                warn!("Async pathfinding failed: {}", e);
                PathfindingResult {
                    success: false,
                    path: vec![],
                    path_length: 0.0,
                    nodes_explored: 0,
                    execution_time_ms: start_time.elapsed().as_millis() as u64,
                    error_message: Some(format!("Async execution failed: {}", e)),
                }
            }
        }
    }

    /// Perform parallel A* pathfinding for multiple requests
    pub fn find_paths_parallel(&self, requests: Vec<PathfindingRequest>) -> Vec<PathfindingResult> {
        let start_time = Instant::now();

        debug!(
            "Processing {} pathfinding requests in parallel",
            requests.len()
        );

        let results: Vec<PathfindingResult> = requests
            .into_par_iter()
            .map(|(grid, start, goal)| self.find_path(&grid, start, goal))
            .collect();

        let total_time = start_time.elapsed().as_millis() as u64;
        debug!(
            "Parallel pathfinding completed {} requests in {}ms",
            results.len(),
            total_time
        );

        results
    }

    /// Convert world coordinates to grid coordinates
    pub fn world_to_grid(world_pos: Vec3, grid_size: f32) -> (usize, usize) {
        let x = (world_pos.x / grid_size).max(0.0) as usize;
        let y = (world_pos.z / grid_size).max(0.0) as usize;
        (x, y)
    }

    /// Convert grid coordinates to world coordinates
    pub fn grid_to_world(grid_pos: (usize, usize), grid_size: f32) -> Vec3 {
        Vec3::new(
            grid_pos.0 as f32 * grid_size,
            0.0,
            grid_pos.1 as f32 * grid_size,
        )
    }

    /// Smooth a path using line of sight optimization
    pub fn smooth_path(
        &self,
        path: &[(usize, usize)],
        grid: &PathfindingGrid,
    ) -> Vec<(usize, usize)> {
        if path.len() <= 2 {
            return path.to_vec();
        }

        let mut smoothed = vec![path[0]];
        let mut current_index = 0;

        while current_index < path.len() - 1 {
            let mut farthest_visible = current_index + 1;

            // Find the farthest node that is visible from the current node
            for i in (current_index + 2)..path.len() {
                if self.has_line_of_sight(path[current_index], path[i], grid) {
                    farthest_visible = i;
                } else {
                    break;
                }
            }

            smoothed.push(path[farthest_visible]);
            current_index = farthest_visible;
        }

        smoothed
    }

    // Private helper methods

    fn heuristic(&self, pos: (usize, usize), goal: (usize, usize)) -> f32 {
        // Manhattan distance
        let dx = (pos.0 as f32 - goal.0 as f32).abs();
        let dy = (pos.1 as f32 - goal.1 as f32).abs();

        if self.config.allow_diagonal_movement {
            // Diagonal distance
            let min_dist = dx.min(dy);
            let max_dist = dx.max(dy);
            min_dist * 1.414 + (max_dist - min_dist)
        } else {
            dx + dy
        }
    }

    fn distance(&self, a: (usize, usize), b: (usize, usize)) -> f32 {
        let dx = (a.0 as f32 - b.0 as f32).abs();
        let dy = (a.1 as f32 - b.1 as f32).abs();

        if self.config.allow_diagonal_movement {
            let min_dist = dx.min(dy);
            let max_dist = dx.max(dy);
            min_dist * 1.414 + (max_dist - min_dist)
        } else {
            dx + dy
        }
    }

    fn get_neighbors(&self, pos: &(usize, usize), grid: &PathfindingGrid) -> Vec<(usize, usize)> {
        let mut neighbors = Vec::new();
        let (x, y) = *pos;

        // 4-connected or 8-connected grid
        let directions = if self.config.allow_diagonal_movement {
            vec![
                (1, 0),
                (-1, 0),
                (0, 1),
                (0, -1), // Cardinal directions
                (1, 1),
                (-1, 1),
                (1, -1),
                (-1, -1), // Diagonal directions
            ]
        } else {
            vec![(1, 0), (-1, 0), (0, 1), (0, -1)]
        };

        for (dx, dy) in directions {
            let nx = x as isize + dx;
            let ny = y as isize + dy;

            if nx >= 0 && ny >= 0 {
                let nx = nx as usize;
                let ny = ny as usize;

                if grid.is_walkable(nx, ny) {
                    neighbors.push((nx, ny));
                }
            }
        }

        neighbors
    }

    fn reconstruct_path(
        &self,
        came_from: &HashMap<(usize, usize), (usize, usize)>,
        goal: (usize, usize),
    ) -> Vec<(usize, usize)> {
        let mut path = vec![goal];
        let mut current = goal;

        while let Some(&parent) = came_from.get(&current) {
            path.push(parent);
            current = parent;
        }

        path.reverse();
        path
    }

    fn calculate_path_length(&self, path: &[(usize, usize)]) -> f32 {
        if path.len() < 2 {
            return 0.0;
        }

        let mut length = 0.0;
        for i in 1..path.len() {
            length += self.distance(path[i - 1], path[i]);
        }
        length
    }

    fn has_line_of_sight(
        &self,
        start: (usize, usize),
        end: (usize, usize),
        grid: &PathfindingGrid,
    ) -> bool {
        // Bresenham's line algorithm for line of sight
        let mut x = start.0 as isize;
        let mut y = start.1 as isize;
        let x2 = end.0 as isize;
        let y2 = end.1 as isize;

        let dx = (x2 - x).abs();
        let dy = (y2 - y).abs();
        let sx = if x < x2 { 1 } else { -1 };
        let sy = if y < y2 { 1 } else { -1 };
        let mut err = dx - dy;

        while x != x2 || y != y2 {
            if !grid.is_walkable(x as usize, y as usize) {
                return false;
            }

            let e2 = 2 * err;
            if e2 > -dy {
                err -= dy;
                x += sx;
            }
            if e2 < dx {
                err += dx;
                y += sy;
            }
        }

        true
    }
}

/// Integration with AI component for entity pathfinding
pub struct EntityPathfindingSystem {
    pathfinding_system: PathfindingSystem,
    entity_paths: HashMap<EntityId, Vec<(usize, usize)>>,
    current_path_index: HashMap<EntityId, usize>,
}

impl EntityPathfindingSystem {
    pub fn new(config: PathfindingConfig, performance_monitor: Arc<PerformanceMonitor>) -> Self {
        let pathfinding_system = PathfindingSystem::new(config, performance_monitor);

        Self {
            pathfinding_system,
            entity_paths: HashMap::new(),
            current_path_index: HashMap::new(),
        }
    }

    /// Find path for an entity to a target position
    pub fn find_entity_path(
        &mut self,
        entity_id: EntityId,
        start_pos: Vec3,
        target_pos: Vec3,
        grid: &PathfindingGrid,
        grid_size: f32,
    ) -> Option<PathfindingResult> {
        let start_grid = PathfindingSystem::world_to_grid(start_pos, grid_size);
        let target_grid = PathfindingSystem::world_to_grid(target_pos, grid_size);

        let result = self
            .pathfinding_system
            .find_path(grid, start_grid, target_grid);

        if result.success {
            self.entity_paths.insert(entity_id, result.path.clone());
            self.current_path_index.insert(entity_id, 0);
        }

        Some(result)
    }

    /// Get the next position in the entity's path
    pub fn get_next_path_position(&mut self, entity_id: EntityId, grid_size: f32) -> Option<Vec3> {
        if let Some(path) = self.entity_paths.get(&entity_id) {
            if let Some(&current_index) = self.current_path_index.get(&entity_id) {
                if current_index < path.len() {
                    let next_grid_pos = path[current_index];
                    let world_pos = PathfindingSystem::grid_to_world(next_grid_pos, grid_size);

                    // Move to next position
                    self.current_path_index.insert(entity_id, current_index + 1);

                    Some(world_pos)
                } else {
                    // Path completed
                    self.entity_paths.remove(&entity_id);
                    self.current_path_index.remove(&entity_id);
                    None
                }
            } else {
                None
            }
        } else {
            None
        }
    }

    /// Check if entity has a path
    pub fn has_path(&self, entity_id: EntityId) -> bool {
        self.entity_paths.contains_key(&entity_id)
    }

    /// Clear entity's path
    pub fn clear_path(&mut self, entity_id: EntityId) {
        self.entity_paths.remove(&entity_id);
        self.current_path_index.remove(&entity_id);
    }

    /// Get pathfinding statistics
    pub fn get_statistics(&self) -> PathfindingStatistics {
        PathfindingStatistics {
            active_paths: self.entity_paths.len(),
            total_paths_processed: self.entity_paths.len(),
        }
    }
}

/// Pathfinding statistics
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PathfindingStatistics {
    pub active_paths: usize,
    pub total_paths_processed: usize,
}

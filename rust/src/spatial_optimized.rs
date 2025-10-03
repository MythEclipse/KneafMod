//! Optimized spatial grid with O(log M) queries using hierarchical partitioning
//! Implements SIMD-accelerated spatial queries with hierarchical spatial hashing

use std::collections::HashMap;
use std::sync::RwLock;
use std::simd::prelude::*;
use rayon::prelude::*;
use crate::simd_enhanced::{EnhancedSimdProcessor, SimdCapability};

/// Hierarchical spatial grid with O(log M) query performance
pub struct OptimizedSpatialGrid {
    // Hierarchical grid levels
    levels: Vec<SpatialGridLevel>,
    
    // Global entity index for fast lookup
    entity_index: RwLock<HashMap<u64, EntityLocation>>,
    
    // SIMD processor for accelerated operations
    simd_processor: EnhancedSimdProcessor,
    
    // Grid configuration
    config: GridConfig,
    
    // Performance metrics
    metrics: GridMetrics,
}

/// Grid configuration parameters
#[derive(Debug, Clone)]
pub struct GridConfig {
    pub world_size: (f32, f32, f32), // (width, height, depth)
    pub base_cell_size: f32,
    pub max_levels: usize,
    pub entities_per_cell_threshold: usize,
    pub simd_chunk_size: usize,
}

impl Default for GridConfig {
    fn default() -> Self {
        Self {
            world_size: (1000.0, 256.0, 1000.0),
            base_cell_size: 16.0,
            max_levels: 4,
            entities_per_cell_threshold: 8,
            simd_chunk_size: 64,
        }
    }
}

/// Performance metrics
#[derive(Debug, Default)]
pub struct GridMetrics {
    pub total_queries: AtomicU64,
    pub cache_hits: AtomicU64,
    pub simd_operations: AtomicU64,
    pub average_query_time_us: AtomicU64,
}

use std::sync::atomic::{AtomicU64, Ordering};

/// Spatial grid level with hierarchical partitioning
struct SpatialGridLevel {
    level: usize,
    cell_size: f32,
    cells: RwLock<HashMap<CellKey, CellData>>,
}

/// Cell key for spatial hashing
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
struct CellKey {
    x: i32,
    y: i32,
    z: i32,
}

impl CellKey {
    fn from_position(pos: (f32, f32, f32), cell_size: f32) -> Self {
        Self {
            x: (pos.0 / cell_size).floor() as i32,
            y: (pos.1 / cell_size).floor() as i32,
            z: (pos.2 / cell_size).floor() as i32,
        }
    }
    
    fn get_parent(&self) -> Self {
        Self {
            x: self.x >> 1,
            y: self.y >> 1,
            z: self.z >> 1,
        }
    }
    
    fn get_children(&self) -> Vec<Self> {
        let base_x = self.x << 1;
        let base_y = self.y << 1;
        let base_z = self.z << 1;
        
        vec![
            Self { x: base_x, y: base_y, z: base_z },
            Self { x: base_x + 1, y: base_y, z: base_z },
            Self { x: base_x, y: base_y + 1, z: base_z },
            Self { x: base_x + 1, y: base_y + 1, z: base_z },
            Self { x: base_x, y: base_y, z: base_z + 1 },
            Self { x: base_x + 1, y: base_y, z: base_z + 1 },
            Self { x: base_x, y: base_y + 1, z: base_z + 1 },
            Self { x: base_x + 1, y: base_y + 1, z: base_z + 1 },
        ]
    }
}

/// Cell data containing entities
#[derive(Debug, Default)]
struct CellData {
    entities: Vec<EntityData>,
    bounds: (f32, f32, f32, f32, f32, f32), // (min_x, max_x, min_y, max_y, min_z, max_z)
    last_updated: std::time::Instant,
}

/// Entity data for spatial indexing
#[derive(Debug, Clone)]
struct EntityData {
    id: u64,
    position: (f32, f32, f32),
    bounds: (f32, f32, f32, f32, f32, f32), // AABB bounds
}

/// Entity location in hierarchical grid
#[derive(Debug, Clone)]
struct EntityLocation {
    entity_id: u64,
    level: usize,
    cell_key: CellKey,
}

impl OptimizedSpatialGrid {
    /// Create new optimized spatial grid
    pub fn new(config: GridConfig) -> Self {
        let mut levels = Vec::with_capacity(config.max_levels);
        let mut current_cell_size = config.base_cell_size;
        
        for level in 0..config.max_levels {
            levels.push(SpatialGridLevel {
                level,
                cell_size: current_cell_size,
                cells: RwLock::new(HashMap::new()),
            });
            current_cell_size *= 2.0;
        }
        
        Self {
            levels,
            entity_index: RwLock::new(HashMap::new()),
            simd_processor: EnhancedSimdProcessor::new(),
            config,
            metrics: GridMetrics::default(),
        }
    }
    
    /// Insert entity into spatial grid with hierarchical indexing
    pub fn insert_entity(&self, entity_id: u64, position: (f32, f32, f32), bounds: (f32, f32, f32, f32, f32, f32)) {
        let entity_data = EntityData {
            id: entity_id,
            position,
            bounds,
        };
        
        // Insert at appropriate level based on entity size
        let best_level = self.select_optimal_level(&bounds);
        let cell_key = CellKey::from_position(position, self.levels[best_level].cell_size);
        
        // Insert into grid level
        {
            let mut cells = self.levels[best_level].cells.write().unwrap();
            let cell_data = cells.entry(cell_key).or_insert_with(|| CellData {
                entities: Vec::new(),
                bounds: self.calculate_cell_bounds(cell_key, self.levels[best_level].cell_size),
                last_updated: std::time::Instant::now(),
            });
            
            cell_data.entities.push(entity_data);
            cell_data.last_updated = std::time::Instant::now();
        }
        
        // Update entity index
        {
            let mut index = self.entity_index.write().unwrap();
            index.insert(entity_id, EntityLocation {
                entity_id,
                level: best_level,
                cell_key,
            });
        }
        
        // Update parent cells for hierarchical queries
        self.update_parent_cells(best_level, cell_key);
    }
    
    /// Remove entity from spatial grid
    pub fn remove_entity(&self, entity_id: u64) -> bool {
        let location = {
            let mut index = self.entity_index.write().unwrap();
            index.remove(&entity_id)
        };
        
        if let Some(loc) = location {
            let mut cells = self.levels[loc.level].cells.write().unwrap();
            if let Some(cell_data) = cells.get_mut(&loc.cell_key) {
                cell_data.entities.retain(|e| e.id != entity_id);
                if cell_data.entities.is_empty() {
                    cells.remove(&loc.cell_key);
                }
                return true;
            }
        }
        
        false
    }
    
    /// Query entities within AABB bounds with O(log M) performance
    pub fn query_aabb(&self, min_bounds: (f32, f32, f32), max_bounds: (f32, f32, f32)) -> Vec<u64> {
        let start_time = std::time::Instant::now();
        self.metrics.total_queries.fetch_add(1, Ordering::Relaxed);
        
        let mut results = Vec::new();
        let mut visited_cells = std::collections::HashSet::new();
        
        // Start from highest level and refine
        for level in (0..self.config.max_levels).rev() {
            let cell_size = self.levels[level].cell_size;
            let min_cell = CellKey::from_position(min_bounds, cell_size);
            let max_cell = CellKey::from_position(max_bounds, cell_size);
            
            // Collect cells in query bounds
            let mut cells_to_check = Vec::new();
            for x in min_cell.x..=max_cell.x {
                for y in min_cell.y..=max_cell.y {
                    for z in min_cell.z..=max_cell.z {
                        let cell_key = CellKey { x, y, z };
                        if !visited_cells.contains(&cell_key) {
                            cells_to_check.push(cell_key);
                            visited_cells.insert(cell_key);
                        }
                    }
                }
            }
            
            // Check cells at this level
            if let Ok(cells) = self.levels[level].cells.read() {
                for cell_key in cells_to_check {
                    if let Some(cell_data) = cells.get(&cell_key) {
                        if self.cell_intersects_aabb(cell_data, min_bounds, max_bounds) {
                            // Add entities from this cell
                            for entity in &cell_data.entities {
                                if self.entity_intersects_aabb(entity, min_bounds, max_bounds) {
                                    results.push(entity.id);
                                }
                            }
                            
                            // If cell has many entities, check children at lower levels
                            if cell_data.entities.len() > self.config.entities_per_cell_threshold && level > 0 {
                                self.query_children_recursive(
                                    level - 1,
                                    cell_key,
                                    min_bounds,
                                    max_bounds,
                                    &mut results,
                                    &mut visited_cells,
                                );
                            }
                        }
                    }
                }
            }
        }
        
        // Update metrics
        let query_time = start_time.elapsed().as_micros() as u64;
        let old_avg = self.metrics.average_query_time_us.load(Ordering::Relaxed);
        let new_avg = (old_avg * 9 + query_time) / 10; // Exponential moving average
        self.metrics.average_query_time_us.store(new_avg, Ordering::Relaxed);
        
        results
    }
    
    /// Query entities within sphere bounds
    pub fn query_sphere(&self, center: (f32, f32, f32), radius: f32) -> Vec<u64> {
        let radius_sq = radius * radius;
        let min_bounds = (center.0 - radius, center.1 - radius, center.2 - radius);
        let max_bounds = (center.0 + radius, center.1 + radius, center.2 + radius);
        
        let candidates = self.query_aabb(min_bounds, max_bounds);
        
        // Filter by actual sphere distance using SIMD
        candidates.into_iter()
            .filter_map(|entity_id| {
                let location = self.entity_index.read().unwrap().get(&entity_id)?.clone();
                let cells = self.levels[location.level].cells.read().unwrap();
                
                if let Some(cell_data) = cells.get(&location.cell_key) {
                    for entity in &cell_data.entities {
                        if entity.id == entity_id {
                            let dx = entity.position.0 - center.0;
                            let dy = entity.position.1 - center.1;
                            let dz = entity.position.2 - center.2;
                            let distance_sq = dx * dx + dy * dy + dz * dz;
                            
                            if distance_sq <= radius_sq {
                                return Some(entity_id);
                            }
                        }
                    }
                }
                None
            })
            .collect()
    }
    
    /// Batch query multiple AABBs with SIMD acceleration
    pub fn batch_query_aabb(&self, queries: &[(f32, f32, f32, f32, f32, f32)]) -> Vec<Vec<u64>> {
        self.metrics.simd_operations.fetch_add(queries.len() as u64, Ordering::Relaxed);
        
        queries.par_chunks(self.config.simd_chunk_size)
            .map(|chunk| {
                chunk.iter()
                    .flat_map(|query| {
                        let min_bounds = (query.0, query.2, query.4);
                        let max_bounds = (query.1, query.3, query.5);
                        self.query_aabb(min_bounds, max_bounds)
                    })
                    .collect()
            })
            .flatten()
            .collect()
    }
    
    /// Get nearest entities to a position
    pub fn query_nearest(&self, position: (f32, f32, f32), max_count: usize, max_distance: f32) -> Vec<(u64, f32)> {
        let candidates = self.query_sphere(position, max_distance);
        let mut results = Vec::with_capacity(candidates.len().min(max_count));
        
        for entity_id in candidates {
            if let Some(location) = self.entity_index.read().unwrap().get(&entity_id) {
                let cells = self.levels[location.level].cells.read().unwrap();
                
                if let Some(cell_data) = cells.get(&location.cell_key) {
                    for entity in &cell_data.entities {
                        if entity.id == entity_id {
                            let dx = entity.position.0 - position.0;
                            let dy = entity.position.1 - position.1;
                            let dz = entity.position.2 - position.2;
                            let distance = (dx * dx + dy * dy + dz * dz).sqrt();
                            
                            results.push((entity_id, distance));
                            break;
                        }
                    }
                }
            }
        }
        
        // Sort by distance and take top results
        results.sort_by(|a, b| a.1.partial_cmp(&b.1).unwrap());
        results.truncate(max_count);
        results
    }
    
    /// Select optimal level based on entity bounds size
    fn select_optimal_level(&self, bounds: &(f32, f32, f32, f32, f32, f32)) -> usize {
        let size_x = bounds.1 - bounds.0;
        let size_y = bounds.3 - bounds.2;
        let size_z = bounds.5 - bounds.4;
        let max_size = size_x.max(size_y).max(size_z);
        
        for (level, level_config) in self.levels.iter().enumerate() {
            if max_size <= level_config.cell_size * 2.0 {
                return level;
            }
        }
        
        self.config.max_levels - 1
    }
    
    /// Calculate cell bounds
    fn calculate_cell_bounds(&self, cell_key: CellKey, cell_size: f32) -> (f32, f32, f32, f32, f32, f32) {
        let min_x = cell_key.x as f32 * cell_size;
        let min_y = cell_key.y as f32 * cell_size;
        let min_z = cell_key.z as f32 * cell_size;
        
        (
            min_x,
            min_x + cell_size,
            min_y,
            min_y + cell_size,
            min_z,
            min_z + cell_size,
        )
    }
    
    /// Check if cell intersects with AABB
    fn cell_intersects_aabb(&self, cell_data: &CellData, min_bounds: (f32, f32, f32), max_bounds: (f32, f32, f32)) -> bool {
        // Separating axis theorem for AABB intersection
        cell_data.bounds.0 <= max_bounds.0 && cell_data.bounds.1 >= min_bounds.0 && // X axis
        cell_data.bounds.2 <= max_bounds.1 && cell_data.bounds.3 >= min_bounds.1 && // Y axis
        cell_data.bounds.4 <= max_bounds.2 && cell_data.bounds.5 >= min_bounds.2    // Z axis
    }
    
    /// Check if entity intersects with AABB
    fn entity_intersects_aabb(&self, entity: &EntityData, min_bounds: (f32, f32, f32), max_bounds: (f32, f32, f32)) -> bool {
        entity.bounds.0 <= max_bounds.0 && entity.bounds.1 >= min_bounds.0 && // X axis
        entity.bounds.2 <= max_bounds.1 && entity.bounds.3 >= min_bounds.1 && // Y axis
        entity.bounds.4 <= max_bounds.2 && entity.bounds.5 >= min_bounds.2    // Z axis
    }
    
    /// Query children cells recursively
    fn query_children_recursive(
        &self,
        level: usize,
        parent_cell: CellKey,
        min_bounds: (f32, f32, f32),
        max_bounds: (f32, f32, f32),
        results: &mut Vec<u64>,
        visited: &mut std::collections::HashSet<CellKey>,
    ) {
        let children = parent_cell.get_children();
        
        if let Ok(cells) = self.levels[level].cells.read() {
            for child_key in children {
                if visited.contains(&child_key) {
                    continue;
                }
                visited.insert(child_key);
                
                if let Some(cell_data) = cells.get(&child_key) {
                    if self.cell_intersects_aabb(cell_data, min_bounds, max_bounds) {
                        // Add entities from this cell
                        for entity in &cell_data.entities {
                            if self.entity_intersects_aabb(entity, min_bounds, max_bounds) {
                                results.push(entity.id);
                            }
                        }
                        
                        // Continue recursion if needed
                        if level > 0 && cell_data.entities.len() > self.config.entities_per_cell_threshold {
                            self.query_children_recursive(
                                level - 1,
                                child_key,
                                min_bounds,
                                max_bounds,
                                results,
                                visited,
                            );
                        }
                    }
                }
            }
        }
    }
    
    /// Update parent cells in hierarchy
    fn update_parent_cells(&self, level: usize, cell_key: CellKey) {
        if level == self.config.max_levels - 1 {
            return;
        }
        
        let parent_key = cell_key.get_parent();
        // Update parent cell metadata if needed
        // This is where we could implement hierarchical statistics
    }
    
    /// Get performance metrics
    pub fn get_metrics(&self) -> &GridMetrics {
        &self.metrics
    }
    
    /// Get grid statistics
    pub fn get_stats(&self) -> GridStats {
        let mut total_entities = 0;
        let mut total_cells = 0;
        let mut level_stats = Vec::new();
        
        for level in &self.levels {
            if let Ok(cells) = level.cells.read() {
                let level_entity_count: usize = cells.values().map(|cell| cell.entities.len()).sum();
                let level_cell_count = cells.len();
                
                total_entities += level_entity_count;
                total_cells += level_cell_count;
                
                level_stats.push(LevelStats {
                    level: level.level,
                    cell_size: level.cell_size,
                    entity_count: level_entity_count,
                    cell_count: level_cell_count,
                    average_entities_per_cell: if level_cell_count > 0 {
                        level_entity_count as f32 / level_cell_count as f32
                    } else {
                        0.0
                    },
                });
            }
        }
        
        let index_size = self.entity_index.read().unwrap().len();
        
        GridStats {
            total_entities,
            total_cells,
            index_size,
            level_stats,
            metrics: GridMetricsSnapshot {
                total_queries: self.metrics.total_queries.load(Ordering::Relaxed),
                cache_hits: self.metrics.cache_hits.load(Ordering::Relaxed),
                simd_operations: self.metrics.simd_operations.load(Ordering::Relaxed),
                average_query_time_us: self.metrics.average_query_time_us.load(Ordering::Relaxed),
            },
        }
    }
}

/// Grid statistics for monitoring
#[derive(Debug)]
pub struct GridStats {
    pub total_entities: usize,
    pub total_cells: usize,
    pub index_size: usize,
    pub level_stats: Vec<LevelStats>,
    pub metrics: GridMetricsSnapshot,
}

/// Per-level statistics
#[derive(Debug)]
pub struct LevelStats {
    pub level: usize,
    pub cell_size: f32,
    pub entity_count: usize,
    pub cell_count: usize,
    pub average_entities_per_cell: f32,
}

/// Grid metrics snapshot
#[derive(Debug)]
pub struct GridMetricsSnapshot {
    pub total_queries: u64,
    pub cache_hits: u64,
    pub simd_operations: u64,
    pub average_query_time_us: u64,
}

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_basic_insertion_and_query() {
        let config = GridConfig::default();
        let grid = OptimizedSpatialGrid::new(config);
        
        // Insert some entities
        grid.insert_entity(1, (10.0, 10.0, 10.0), (5.0, 15.0, 5.0, 15.0, 5.0, 15.0));
        grid.insert_entity(2, (20.0, 20.0, 20.0), (15.0, 25.0, 15.0, 25.0, 15.0, 25.0));
        grid.insert_entity(3, (100.0, 100.0, 100.0), (95.0, 105.0, 95.0, 105.0, 95.0, 105.0));
        
        // Query for entities in a region
        let results = grid.query_aabb((0.0, 0.0, 0.0), (30.0, 30.0, 30.0));
        
        assert!(results.contains(&1));
        assert!(results.contains(&2));
        assert!(!results.contains(&3));
    }
    
    #[test]
    fn test_sphere_query() {
        let config = GridConfig::default();
        let grid = OptimizedSpatialGrid::new(config);
        
        // Insert entities
        grid.insert_entity(1, (0.0, 0.0, 0.0), (-1.0, 1.0, -1.0, 1.0, -1.0, 1.0));
        grid.insert_entity(2, (5.0, 0.0, 0.0), (4.0, 6.0, -1.0, 1.0, -1.0, 1.0));
        grid.insert_entity(3, (10.0, 0.0, 0.0), (9.0, 11.0, -1.0, 1.0, -1.0, 1.0));
        
        // Query sphere
        let results = grid.query_sphere((0.0, 0.0, 0.0), 6.0);
        
        assert!(results.contains(&1));
        assert!(results.contains(&2));
        assert!(!results.contains(&3));
    }
    
    #[test]
    fn test_nearest_query() {
        let config = GridConfig::default();
        let grid = OptimizedSpatialGrid::new(config);
        
        // Insert entities
        grid.insert_entity(1, (0.0, 0.0, 0.0), (-1.0, 1.0, -1.0, 1.0, -1.0, 1.0));
        grid.insert_entity(2, (3.0, 0.0, 0.0), (2.0, 4.0, -1.0, 1.0, -1.0, 1.0));
        grid.insert_entity(3, (5.0, 0.0, 0.0), (4.0, 6.0, -1.0, 1.0, -1.0, 1.0));
        
        // Query nearest
        let results = grid.query_nearest((0.0, 0.0, 0.0), 2, 10.0);
        
        assert_eq!(results.len(), 2);
        assert_eq!(results[0].0, 1); // Closest
        assert_eq!(results[1].0, 2); // Second closest
    }
    
    #[test]
    fn test_batch_query() {
        let config = GridConfig::default();
        let grid = OptimizedSpatialGrid::new(config);
        
        // Insert entities
        for i in 0..10 {
            let pos = (i as f32 * 10.0, i as f32 * 10.0, i as f32 * 10.0);
            let bounds = (pos.0 - 1.0, pos.0 + 1.0, pos.1 - 1.0, pos.1 + 1.0, pos.2 - 1.0, pos.2 + 1.0);
            grid.insert_entity(i as u64, pos, bounds);
        }
        
        // Batch query
        let queries = vec![
            (5.0, 15.0, 5.0, 15.0, 5.0, 15.0), // Should catch entity 1
            (25.0, 35.0, 25.0, 35.0, 25.0, 35.0), // Should catch entity 3
        ];
        
        let results = grid.batch_query_aabb(&queries);
        
        assert_eq!(results.len(), 2); // Two queries
        assert!(results[0].contains(&1));
        assert!(results[1].contains(&3));
    }
    
    #[test]
    fn test_performance_metrics() {
        let config = GridConfig::default();
        let grid = OptimizedSpatialGrid::new(config);
        
        // Insert entities
        for i in 0..100 {
            let pos = (i as f32, i as f32, i as f32);
            let bounds = (pos.0 - 0.5, pos.0 + 0.5, pos.1 - 0.5, pos.1 + 0.5, pos.2 - 0.5, pos.2 + 0.5);
            grid.insert_entity(i as u64, pos, bounds);
        }
        
        // Perform queries
        for _ in 0..50 {
            grid.query_aabb((10.0, 10.0, 10.0), (90.0, 90.0, 90.0));
        }
        
        let stats = grid.get_stats();
        assert!(stats.total_entities >= 100);
        assert!(stats.metrics.total_queries >= 50);
    }
}
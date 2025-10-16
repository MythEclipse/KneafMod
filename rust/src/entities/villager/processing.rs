use super::types::*;
use super::advanced_pathfinding::*;
use super::pathfinding::AdvancedPathfindingOptimizer;
use crate::entities::entity::processing::*;
use crate::entities::entity::types::*;
use crate::logging::{generate_trace_id, PerformanceLogger};
use crate::memory::pool::object_pool::ObjectPool;
use crate::parallelism::WorkStealingScheduler;
use crate::EntityData;
use once_cell::sync::Lazy;
use rayon::prelude::*;
use serde_json;
use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::time::Instant;

static VILLAGER_PROCESSOR_LOGGER: Lazy<PerformanceLogger> =
    Lazy::new(|| PerformanceLogger::new("villager_processor"));

// Global advanced pathfinding optimizer for all villager processing
static ADVANCED_PATHFINDING_OPTIMIZER: Lazy<Arc<AdvancedPathfindingOptimizer>> =
    Lazy::new(|| {
        let optimizer = AdvancedPathfindingOptimizer::new();
        // In a real implementation, you would initialize with actual navigation mesh data
        Arc::new(optimizer)
    });

/// Efficient pathfinding for villagers using the advanced pathfinding system
pub fn find_villager_path(
    villager_id: u64,
    start: (f32, f32, f32),
    goal: (f32, f32, f32),
) -> Option<Vec<(f32, f32, f32)>> {
    let trace_id = generate_trace_id();
    VILLAGER_PROCESSOR_LOGGER.log_debug(
        "pathfind_start",
        &trace_id,
        &format!(
            "Finding path for villager {} from {:?} to {:?}",
            villager_id, start, goal
        ),
    );

    let start_time = Instant::now();
    
    // Use the advanced pathfinding optimizer
    let path = ADVANCED_PATHFINDING_OPTIMIZER.find_villager_path(villager_id, start, goal);
    
    if path.is_some() {
        VILLAGER_PROCESSOR_LOGGER.log_debug(
            "pathfind_success",
            &trace_id,
            &format!("Path found for villager {}", villager_id),
        );
    } else {
        VILLAGER_PROCESSOR_LOGGER.log_debug(
            "pathfind_failed",
            &trace_id,
            &format!("No path found for villager {} after {:?}", villager_id, start_time.elapsed()),
        );
    }
    
    path
}

/// Find paths for a group of villagers using the advanced pathfinding system
pub fn find_paths_for_villager_group(
    group: &VillagerGroup,
    goal: (f32, f32, f32),
) -> Vec<PathfindingResult> {
    let trace_id = generate_trace_id();
    VILLAGER_PROCESSOR_LOGGER.log_debug(
        "group_pathfind_start",
        &trace_id,
        &format!(
            "Finding paths for group {} ({} villagers) to {:?}",
            group.group_id, group.villager_ids.len(), goal
        ),
    );

    let start_time = Instant::now();
    
    // Use the advanced pathfinding optimizer for group pathfinding
    let results = ADVANCED_PATHFINDING_OPTIMIZER.find_paths_for_group(group, goal);
    
    VILLAGER_PROCESSOR_LOGGER.log_debug(
        "group_pathfind_complete",
        &trace_id,
        &format!(
            "Pathfinding completed for group {} in {:?}, successful paths: {}",
            group.group_id,
            start_time.elapsed(),
            results.iter().filter(|r| r.success).count()
        ),
    );
    
    results
}

/// Heuristic cost estimate (Manhattan distance for 3D space)
fn heuristic_cost_estimate(a: (f32, f32, f32), b: (f32, f32, f32)) -> f32 {
    (a.0 - b.0).abs() + (a.1 - b.1).abs() + (a.2 - b.2).abs()
}

/// Calculate Euclidean distance between two points
fn distance_between(a: (f32, f32, f32), b: (f32, f32, f32)) -> f32 {
    let dx = a.0 - b.0;
    let dy = a.1 - b.1;
    let dz = a.2 - b.2;
    (dx * dx + dy * dy + dz * dz).sqrt()
}

/// Get neighboring nodes from navigation mesh
fn get_navigation_neighbors(
    node: (f32, f32, f32),
    navigation_mesh: &Vec<(f32, f32, f32)>,
) -> Vec<(f32, f32, f32)> {
    // In a real implementation, this would use spatial partitioning (octree, grid, etc.)
    // For simplicity, we'll return all nodes within a certain distance
    const NEIGHBOR_DISTANCE: f32 = 5.0;
    
    navigation_mesh
        .iter()
        .filter(|&&n| distance_between(node, n) < NEIGHBOR_DISTANCE)
        .map(|&n| n)
        .collect()
}

/// Reconstruct path from start to goal using came_from map
fn reconstruct_path(
    came_from: HashMap<(f32, f32, f32), (f32, f32, f32)>,
    start: (f32, f32, f32),
    current: (f32, f32, f32),
) -> Option<Vec<(f32, f32, f32)>> {
    let mut path = Vec::new();
    let mut current_node = current;

    // Add current node to path
    path.push(current_node);

    // Trace back through came_from map
    while current_node != start {
        if let Some(&prev_node) = came_from.get(&current_node) {
            path.push(prev_node);
            current_node = prev_node;
        } else {
            // No path found (shouldn't happen if A* completed properly)
            return None;
        }
    }

    // Path is built in reverse, so reverse it
    path.reverse();
    
    Some(path)
}

/// Process villager entities with spatial optimization and advanced pathfinding
pub fn process_villagers(input: VillagerInput) -> VillagerProcessResult {
    let trace_id = generate_trace_id();
    VILLAGER_PROCESSOR_LOGGER.log_info(
        "process_start",
        &trace_id,
        &format!(
            "process_villagers called with {} villagers",
            input.villagers.len()
        ),
    );
    let start_time = Instant::now();

    // Validate input
    if input.villagers.is_empty() {
        VILLAGER_PROCESSOR_LOGGER.log_debug(
            "empty_input",
            &trace_id,
            "No villagers to process, returning empty result",
        );
        return VillagerProcessResult {
            villagers_to_disable_ai: Vec::new(),
            villagers_to_simplify_ai: Vec::new(),
            villagers_to_reduce_pathfinding: Vec::new(),
            villager_groups: Vec::new(),
        };
    }

    // Get player position (assuming first player is the one we care about)
    let player_position = input.players.first().map(|p| (p.x as f32, p.y as f32, p.z as f32)).unwrap_or((0.0, 0.0, 0.0));

    // Group villagers spatially first
    let mut villager_groups = group_villagers_by_spatial_proximity(&input.villagers);
    
    // Process groups with advanced pathfinding
    let mut pathfinding_results = Vec::new();
    
    for group in &villager_groups {
        // Only pathfind for groups near the player
        if group.center_x > 256.0 {
            continue;
        }
        
        // Use advanced pathfinding for the group
        let results = find_paths_for_villager_group(group, player_position);
        pathfinding_results.extend(results);
    }

    // Use work-stealing scheduler for optimal thread distribution
    let scheduler = WorkStealingScheduler::new();
    
    // Convert to common entity format for processing
    let entity_inputs: Vec<EntityProcessingInput> = input
        .villagers
        .iter()
        .map(|villager| EntityProcessingInput {
            entity_id: villager.id.to_string(),
            entity_type: villager.entity_type,
            data: villager.clone().into(),
            delta_time: 0.05, // 50ms tick
            simulation_distance: 128,
        })
        .collect();

    // Process entities in parallel
    let results = scheduler.execute(entity_inputs, |input| {
        process_villager_entity(input, player_position)
    });

    // Process results to determine optimization needs
    let mut villagers_to_disable_ai = Vec::new();
    let mut villagers_to_simplify_ai = Vec::new();
    let mut villagers_to_reduce_pathfinding = Vec::new();

    for result in results {
        let villager_id = result.entity_id.parse::<u64>().unwrap_or(0);
        
        // Find the corresponding villager data to check distance
        if let Some(villager) = input.villagers.iter().find(|v| v.id == villager_id) {
            // AI optimization based on distance from player
            if villager.distance > 384.0 {
                villagers_to_disable_ai.push(villager_id);
            } else if villager.distance > 256.0 {
                villagers_to_simplify_ai.push(villager_id);
            } else if villager.distance > 128.0 {
                villagers_to_reduce_pathfinding.push(villager_id);
            }

            // Update group AI tick rate based on group size
            if let Some(group) = villager_groups.iter_mut().find(|g| g.villager_ids.contains(&villager_id)) {
                if group.villager_ids.len() > 50 {
                    group.ai_tick_rate = 3; // Reduce tick rate for large groups
                } else if group.villager_ids.len() > 20 {
                    group.ai_tick_rate = 2; // Medium tick rate for medium groups
                } else {
                    group.ai_tick_rate = 1; // Full tick rate for small groups
                }
            }
        }
    }

    let elapsed = start_time.elapsed();
    VILLAGER_PROCESSOR_LOGGER.log_info(
        "processing_complete",
        &trace_id,
        &format!(
            "Villager processing completed in {:?}. Disabled AI for {} villagers, simplified AI for {} villagers, reduced pathfinding for {} villagers. Formed {} villager groups",
            elapsed,
            villagers_to_disable_ai.len(),
            villagers_to_simplify_ai.len(),
            villagers_to_reduce_pathfinding.len(),
            villager_groups.len()
        ),
    );

    VillagerProcessResult {
        villagers_to_disable_ai,
        villagers_to_simplify_ai,
        villagers_to_reduce_pathfinding,
        villager_groups,
    }
}

/// Process a single villager entity with memory pooling and advanced pathfinding optimization
fn process_villager_entity(input: EntityProcessingInput, player_position: (f32, f32, f32)) -> EntityProcessingResult {
    let trace_id = generate_trace_id();
    let start_time = Instant::now();

    // Get thread-local memory pool for efficient object reuse
    let pool = ObjectPool::<VillagerData>::new(Default::default());
    
    // Use memory pool to get or create villager data
    let mut villager_data = pool.get();
    
    // Update villager state with new input
    if let Ok(parsed_id) = input.entity_id.parse::<u64>() {
        villager_data.id = parsed_id;
        villager_data.position = (input.data.x, input.data.y, input.data.z);
        
        // Calculate distance from player for AI optimization
        let distance = distance_between(villager_data.position, player_position);
        villager_data.distance = distance;

        // Update profession and other villager-specific properties
        if let Some(profession) = input.data.properties.get("profession") {
            villager_data.profession = profession.clone();
        }
        if let Some(level_str) = input.data.properties.get("level") {
            if let Ok(level) = level_str.parse::<u8>() {
                villager_data.level = level;
            }
        }
    }

    // Advanced pathfinding using our new system
    let path = if villager_data.distance < 64.0 {
        // Only pathfind for villagers close to player
        find_villager_path(villager_data.id, villager_data.position, player_position)
    } else {
        None
    };

    let result = EntityProcessingResult {
        entity_id: input.entity_id,
        success: true,
        distance: villager_data.distance,
        metadata_changed: Some(serde_json::json!({
            "position": villager_data.position,
            "distance": villager_data.distance,
            "profession": villager_data.profession,
            "level": villager_data.level,
            "path": path.map(|p| p.iter().map(|&(x, y, z)| vec![x, y, z]).collect::<Vec<_>>()),
        })),
    };

    let elapsed = start_time.elapsed();
    VILLAGER_PROCESSOR_LOGGER.log_debug(
        "villager_processed",
        &trace_id,
        &format!(
            "Processed villager {} in {:?}, distance: {:.2}, profession: {}",
            input.entity_id, elapsed, villager_data.distance, villager_data.profession
        ),
    );

    result
}

/// Group villagers by spatial proximity for efficient rendering and AI management
fn group_villagers_by_spatial_proximity(villagers: &[VillagerData]) -> Vec<VillagerGroup> {
    let trace_id = generate_trace_id();
    VILLAGER_PROCESSOR_LOGGER.log_debug(
        "group_start",
        &trace_id,
        &format!("Grouping {} villagers by spatial proximity", villagers.len()),
    );

    // Use chunk-based spatial partitioning (simplified)
    const CHUNK_SIZE: f32 = 16.0; // 16-block chunks
    let mut groups_by_chunk: HashMap<(i32, i32, i32), Vec<&VillagerData>> = HashMap::new();

    // Assign villagers to chunks
    for villager in villagers {
        let chunk_x = (villager.position.0 / CHUNK_SIZE).floor() as i32;
        let chunk_y = (villager.position.1 / CHUNK_SIZE).floor() as i32;
        let chunk_z = (villager.position.2 / CHUNK_SIZE).floor() as i32;
        
        let chunk_key = (chunk_x, chunk_y, chunk_z);
        groups_by_chunk.entry(chunk_key).or_insert_with(Vec::new).push(villager);
    }

    // Create villager groups from chunks
    let mut villager_groups = Vec::new();
    let mut group_id_counter = 0;

    for (chunk_key, villagers_in_chunk) in groups_by_chunk {
        if villagers_in_chunk.len() < 3 {
            // Skip small groups for better performance
            continue;
        }

        // Calculate group center
        let mut center_x = 0.0;
        let mut center_y = 0.0;
        let mut center_z = 0.0;
        
        for villager in villagers_in_chunk {
            center_x += villager.position.0;
            center_y += villager.position.1;
            center_z += villager.position.2;
        }
        
        let group_size = villagers_in_chunk.len() as f32;
        center_x /= group_size;
        center_y /= group_size;
        center_z /= group_size;

        // Create villager group
        let mut villager_ids = Vec::new();
        for villager in villagers_in_chunk {
            villager_ids.push(villager.id);
        }

        let group = VillagerGroup {
            group_id: group_id_counter,
            center_x,
            center_y,
            center_z,
            villager_ids,
            group_type: format!("village_group_{}", group_id_counter),
            ai_tick_rate: 1, // Default tick rate
        };

        villager_groups.push(group);
        group_id_counter += 1;
    }

    VILLAGER_PROCESSOR_LOGGER.log_debug(
        "group_complete",
        &trace_id,
        &format!("Created {} villager groups", villager_groups.len()),
    );

    villager_groups
}

/// Get default navigation mesh (in real implementation, this would be loaded from game assets)
fn get_default_navigation_mesh() -> Vec<(f32, f32, f32)> {
    // Simplified navigation mesh with common game world features
    vec![
        (0.0, 0.0, 0.0),
        (16.0, 0.0, 0.0),
        (32.0, 0.0, 0.0),
        (0.0, 0.0, 16.0),
        (16.0, 0.0, 16.0),
        (32.0, 0.0, 16.0),
        (0.0, 0.0, 32.0),
        (16.0, 0.0, 32.0),
        (32.0, 0.0, 32.0),
        // Add more points as needed for real-world usage
    ]
}

/// Batch process multiple villager collections in parallel
pub fn process_villagers_batch(inputs: Vec<VillagerInput>) -> Vec<VillagerProcessResult> {
    inputs.into_par_iter().map(process_villagers).collect()
}

/// Process villagers from JSON input and return JSON result
pub fn process_villagers_json(json_input: &str) -> Result<String, String> {
    let trace_id = generate_trace_id();
    VILLAGER_PROCESSOR_LOGGER.log_info(
        "json_process_start",
        &trace_id,
        &format!(
            "process_villagers_json called with input length: {}",
            json_input.len()
        ),
    );

    let input: VillagerInput = serde_json::from_str(json_input).map_err(|e| {
        VILLAGER_PROCESSOR_LOGGER.log_error(
            "json_parse_error",
            &trace_id,
            &format!("ERROR: Failed to parse JSON input: {}", e),
            "VILLAGER_PROCESSING",
        );
        format!("Failed to parse JSON input: {}", e)
    })?;

    let result = process_villagers(input);

    let json_result = serde_json::to_string(&result).map_err(|e| {
        VILLAGER_PROCESSOR_LOGGER.log_error(
            "json_serialize_error",
            &trace_id,
            &format!("ERROR: Failed to serialize result to JSON: {}", e),
            "VILLAGER_PROCESSING",
        );
        format!("Failed to serialize result to JSON: {}", e)
    })?;

    VILLAGER_PROCESSOR_LOGGER.log_info(
        "json_process_complete",
        &trace_id,
        &format!(
            "Villager processing completed, result length: {}",
            json_result.len()
        ),
    );

    Ok(json_result)
}

/// Process villager AI in JSON format for JNI compatibility
pub fn process_villager_ai_json(input_json: &str) -> Result<String, String> {
    process_villagers_json(input_json)
}

/// Process villager AI in binary batch format for JNI compatibility
pub fn process_villager_ai_binary_batch(input_bytes: &[u8]) -> Result<String, String> {
    let trace_id = generate_trace_id();
    VILLAGER_PROCESSOR_LOGGER.log_info(
        "binary_batch_process_start",
        &trace_id,
        &format!(
            "process_villager_ai_binary_batch called with {} bytes",
            input_bytes.len()
        ),
    );

    // For now, convert binary to JSON and process (in real implementation, use proper binary format)
    let input_str = String::from_utf8_lossy(input_bytes);
    
    // Try to parse as JSON first
    if let Ok(input) = serde_json::from_str::<VillagerInput>(&input_str) {
        let result = process_villagers(input);
        
        let json_result = serde_json::to_string(&result).map_err(|e| {
            VILLAGER_PROCESSOR_LOGGER.log_error(
                "binary_serialize_error",
                &trace_id,
                &format!("ERROR: Failed to serialize result to JSON: {}", e),
                "VILLAGER_PROCESSING",
            );
            format!("Failed to serialize result to JSON: {}", e)
        })?;

        VILLAGER_PROCESSOR_LOGGER.log_info(
            "binary_batch_process_complete",
            &trace_id,
            &format!(
                "Villager binary batch processing completed, result length: {}",
                json_result.len()
            ),
        );

        Ok(json_result)
    } else {
        // If not valid JSON, return error
        Err("Invalid binary input format - expected JSON string".to_string())
    }
}

/// Thread-safe villager state management with spatial awareness
#[derive(Debug, Clone)]
pub struct VillagerStateManager {
    pub active_villagers: Arc<std::sync::RwLock<HashMap<u64, VillagerData>>>,
    pub villager_pools: Arc<std::sync::RwLock<HashMap<EntityType, ObjectPool<VillagerData>>>>,
    pub spatial_groups: Arc<std::sync::RwLock<HashMap<(i32, i32, i32), Vec<u64>>>>,
    logger: PerformanceLogger,
}

impl VillagerStateManager {
    pub fn new() -> Self {
        Self {
            active_villagers: Arc::new(std::sync::RwLock::new(HashMap::new())),
            villager_pools: Arc::new(std::sync::RwLock::new(HashMap::new())),
            spatial_groups: Arc::new(std::sync::RwLock::new(HashMap::new())),
            logger: PerformanceLogger::new("villager_state_manager"),
        }
    }

    /// Update villager state with spatial grouping optimization
    pub fn update_villager_state(&self, villager_id: u64, new_state: VillagerData) -> Result<(), String> {
        let trace_id = generate_trace_id();
        
        let mut active_villagers = self.active_villagers.write().unwrap();
        let mut spatial_groups = self.spatial_groups.write().unwrap();
        
        if let Some(existing) = active_villagers.get_mut(&villager_id) {
            // Remove from old spatial group
            let old_chunk = self.get_chunk_coordinates(existing.position);
            if let Some(group) = spatial_groups.get_mut(&old_chunk) {
                if let Some(index) = group.iter().position(|&id| id == villager_id) {
                    group.remove(index);
                    
                    // Remove empty groups
                    if group.is_empty() {
                        spatial_groups.remove(&old_chunk);
                    }
                }
            }

            // Update villager state
            *existing = new_state.clone();

            // Add to new spatial group
            let new_chunk = self.get_chunk_coordinates(new_state.position);
            spatial_groups.entry(new_chunk).or_insert_with(Vec::new).push(villager_id);

            self.logger.log_debug(
                "villager_state_updated",
                &trace_id,
                &format!("Updated state for villager {}: {:?}", villager_id, existing),
            );
            
            Ok(())
        } else {
            // Create new entry
            active_villagers.insert(villager_id, new_state.clone());
            
            // Add to spatial group
            let chunk = self.get_chunk_coordinates(new_state.position);
            spatial_groups.entry(chunk).or_insert_with(Vec::new).push(villager_id);

            self.logger.log_debug(
                "villager_state_created",
                &trace_id,
                &format!("Created new villager state for villager {}: {:?}", villager_id, new_state),
            );
            
            Ok(())
        }
    }

    /// Get chunk coordinates for spatial partitioning
    fn get_chunk_coordinates(&self, position: (f32, f32, f32)) -> (i32, i32, i32) {
        const CHUNK_SIZE: f32 = 16.0;
        let x = (position.0 / CHUNK_SIZE).floor() as i32;
        let y = (position.1 / CHUNK_SIZE).floor() as i32;
        let z = (position.2 / CHUNK_SIZE).floor() as i32;
        (x, y, z)
    }

    /// Get all villagers in a specific spatial chunk
    pub fn get_villagers_in_chunk(&self, chunk: (i32, i32, i32)) -> Vec<u64> {
        let spatial_groups = self.spatial_groups.read().unwrap();
        spatial_groups.get(&chunk).cloned().unwrap_or(Vec::new())
    }

    /// Get villagers within a radius using spatial partitioning for efficiency
    pub fn get_villagers_in_radius(&self, center: (f32, f32, f32), radius: f32) -> Vec<u64> {
        let spatial_groups = self.spatial_groups.read().unwrap();
        let center_chunk = self.get_chunk_coordinates(center);
        let radius_chunks = (radius / 16.0).ceil() as i32;

        let mut result = Vec::new();

        // Check neighboring chunks within radius
        for x in center_chunk.0 - radius_chunks..=center_chunk.0 + radius_chunks {
            for y in center_chunk.1 - radius_chunks..=center_chunk.1 + radius_chunks {
                for z in center_chunk.2 - radius_chunks..=center_chunk.2 + radius_chunks {
                    let chunk = (x, y, z);
                    if let Some(villagers) = spatial_groups.get(&chunk) {
                        // In real implementation, we would check actual distance here
                        // For simplicity, we'll return all villagers in nearby chunks
                        result.extend_from_slice(villagers);
                    }
                }
            }
        }

        result
    }
}

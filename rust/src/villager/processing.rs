use super::types::*;
use super::config::*;
use super::spatial::*;
use super::pathfinding::*;
use rayon::prelude::*;
use std::sync::Mutex;
use once_cell::sync::Lazy;
use serde::{Deserialize, Serialize};

// Global pathfinding optimizer instance
static PATHFINDING_OPTIMIZER: Lazy<Mutex<PathfindingOptimizer>> = 
    Lazy::new(|| Mutex::new(PathfindingOptimizer::new()));

static ADVANCED_PATHFINDING_OPTIMIZER: Lazy<Mutex<AdvancedPathfindingOptimizer>> = 
    Lazy::new(|| Mutex::new(AdvancedPathfindingOptimizer::new()));

pub fn process_villager_ai(input: VillagerInput) -> VillagerProcessResult {
    let config = get_villager_config();
    
    // Update pathfinding optimizer tick
    {
        let mut optimizer = PATHFINDING_OPTIMIZER.lock().unwrap();
        optimizer.update_tick(input.tick_count);
    }

    // Step 1: Spatial grouping - group villagers by proximity
    let spatial_groups = group_villagers_by_proximity(&input.villagers, &input.players);
    
    // Step 2: Optimize villager groups based on characteristics and player distance
    let villager_groups = optimize_villager_groups(spatial_groups, &config);
    
    // Step 3: Process AI optimizations in parallel by group
    let group_results: Vec<_> = villager_groups.par_iter()
        .map(|group| process_villager_group(group, &input, &config))
        .collect();

    // Step 4: Optimize pathfinding for all villagers
    let pathfinding_optimization = {
        let mut optimizer = PATHFINDING_OPTIMIZER.lock().unwrap();
        let mut villagers_copy = input.villagers.clone();
        let result = optimizer.optimize_villager_pathfinding(&mut villagers_copy, &config);
        
        // Also apply advanced group-based pathfinding optimization
        let mut advanced_optimizer = ADVANCED_PATHFINDING_OPTIMIZER.lock().unwrap();
        let advanced_result = advanced_optimizer.optimize_large_villager_groups(&mut villager_groups.clone(), &config);
        
        // Combine results
        let mut combined_result = result;
            combined_result.extend(advanced_result);
        combined_result
    };

    // Step 5: Combine all results
    let mut villagers_to_disable_ai = Vec::new();
    let mut villagers_to_simplify_ai = Vec::new();
    let mut villagers_to_reduce_pathfinding = pathfinding_optimization;

    for group_result in group_results {
        villagers_to_disable_ai.extend(group_result.villagers_to_disable_ai);
        villagers_to_simplify_ai.extend(group_result.villagers_to_simplify_ai);
        villagers_to_reduce_pathfinding.extend(group_result.villagers_to_reduce_pathfinding);
    }

    // Clean up old cache entries
    {
        let mut optimizer = PATHFINDING_OPTIMIZER.lock().unwrap();
        let active_ids: Vec<u64> = input.villagers.iter().map(|v| v.id).collect();
        optimizer.cleanup_old_cache(&active_ids);
    }

    VillagerProcessResult {
        villagers_to_disable_ai,
        villagers_to_simplify_ai,
        villagers_to_reduce_pathfinding,
        villager_groups,
    }
}

fn process_villager_group(group: &VillagerGroup, input: &VillagerInput, config: &VillagerConfig) -> VillagerGroupResult {
    let mut villagers_to_disable_ai = Vec::new();
    let mut villagers_to_simplify_ai = Vec::new();
    let mut villagers_to_reduce_pathfinding = Vec::new();

    // Determine processing strategy based on group characteristics
    match group.group_type.as_str() {
        "village" => process_village_group(group, input, config, &mut villagers_to_disable_ai, &mut villagers_to_simplify_ai),
        "working" => process_working_group(group, input, config, &mut villagers_to_disable_ai, &mut villagers_to_simplify_ai),
        "breeding" => process_breeding_group(group, input, config, &mut villagers_to_disable_ai, &mut villagers_to_simplify_ai),
        "resting" => process_resting_group(group, input, config, &mut villagers_to_disable_ai, &mut villagers_to_simplify_ai),
        "wandering" => process_wandering_group(group, input, config, &mut villagers_to_disable_ai, &mut villagers_to_simplify_ai),
        _ => process_generic_group(group, input, config, &mut villagers_to_disable_ai, &mut villagers_to_simplify_ai),
    }

    // Apply distance-based optimizations
    apply_distance_optimizations(group, input, config, &mut villagers_to_disable_ai, &mut villagers_to_simplify_ai, &mut villagers_to_reduce_pathfinding);

    VillagerGroupResult {
        villagers_to_disable_ai,
        villagers_to_simplify_ai,
        villagers_to_reduce_pathfinding,
    }
}

fn process_village_group(
    group: &VillagerGroup,
    input: &VillagerInput,
    config: &VillagerConfig,
    disable_ai: &mut Vec<u64>,
    simplify_ai: &mut Vec<u64>,
) {
    // Village groups get special treatment based on player proximity
    let group_distance = calculate_group_distance_to_players(group, &input.players);

    if group_distance > config.disable_ai_distance {
        // Far villages - disable AI for most villagers, keep a few active
        let villagers_to_keep = std::cmp::max(1, group.villager_ids.len() / 8);
        for (i, &villager_id) in group.villager_ids.iter().enumerate() {
            if i >= villagers_to_keep {
                disable_ai.push(villager_id);
            }
        }
    } else if group_distance > config.simplify_ai_distance {
        // Medium distance villages - simplify AI for most villagers
        let villagers_to_keep_complex = std::cmp::max(2, group.villager_ids.len() / 4);
        for (i, &villager_id) in group.villager_ids.iter().enumerate() {
            if i >= villagers_to_keep_complex {
                simplify_ai.push(villager_id);
            }
        }
    }
    // Close villages keep full AI
}

fn process_working_group(
    group: &VillagerGroup,
    input: &VillagerInput,
    config: &VillagerConfig,
    disable_ai: &mut Vec<u64>,
    simplify_ai: &mut Vec<u64>,
) {
    // Working villagers need workstation proximity checks
    let group_distance = calculate_group_distance_to_players(group, &input.players);

    if group_distance > config.disable_ai_distance {
        // Disable AI for far working villagers
        disable_ai.extend(&group.villager_ids);
    } else if group_distance > config.simplify_ai_distance {
        // Simplify AI for medium-distance working villagers
        simplify_ai.extend(&group.villager_ids);
    }
}

fn process_breeding_group(
    group: &VillagerGroup,
    input: &VillagerInput,
    config: &VillagerConfig,
    disable_ai: &mut Vec<u64>,
    simplify_ai: &mut Vec<u64>,
) {
    // Breeding groups need special handling - keep some activity for population management
    let group_distance = calculate_group_distance_to_players(group, &input.players);

    if group_distance > config.disable_ai_distance * 1.5 {
        // Very far breeding groups - disable most AI
        let villagers_to_keep = std::cmp::max(1, group.villager_ids.len() / 6);
        for (i, &villager_id) in group.villager_ids.iter().enumerate() {
            if i >= villagers_to_keep {
                disable_ai.push(villager_id);
            }
        }
    } else if group_distance > config.simplify_ai_distance {
        // Medium distance - simplify AI
        simplify_ai.extend(&group.villager_ids);
    }
    // Close breeding groups keep full AI for player interaction
}

fn process_resting_group(
    group: &VillagerGroup,
    input: &VillagerInput,
    config: &VillagerConfig,
    disable_ai: &mut Vec<u64>,
    simplify_ai: &mut Vec<u64>,
) {
    // Resting villagers can have heavily reduced AI
    let group_distance = calculate_group_distance_to_players(group, &input.players);

    if group_distance > config.simplify_ai_distance {
        // Simplify AI for resting villagers when players are not very close
        simplify_ai.extend(&group.villager_ids);
    }
}

fn process_wandering_group(
    group: &VillagerGroup,
    input: &VillagerInput,
    config: &VillagerConfig,
    disable_ai: &mut Vec<u64>,
    simplify_ai: &mut Vec<u64>,
) {
    // Wandering villagers can have reduced AI based on distance
    let group_distance = calculate_group_distance_to_players(group, &input.players);

    if group_distance > config.disable_ai_distance {
        disable_ai.extend(&group.villager_ids);
    } else if group_distance > config.simplify_ai_distance {
        simplify_ai.extend(&group.villager_ids);
    }
}

fn process_generic_group(
    group: &VillagerGroup,
    input: &VillagerInput,
    config: &VillagerConfig,
    disable_ai: &mut Vec<u64>,
    simplify_ai: &mut Vec<u64>,
) {
    // Generic processing based purely on distance
    let group_distance = calculate_group_distance_to_players(group, &input.players);

    if group_distance > config.disable_ai_distance {
        disable_ai.extend(&group.villager_ids);
    } else if group_distance > config.simplify_ai_distance {
        simplify_ai.extend(&group.villager_ids);
    }
}

fn apply_distance_optimizations(
    group: &VillagerGroup,
    input: &VillagerInput,
    config: &VillagerConfig,
    disable_ai: &mut Vec<u64>,
    simplify_ai: &mut Vec<u64>,
    reduce_pathfinding: &mut Vec<u64>,
) {
    // Additional distance-based optimizations
    let group_distance = calculate_group_distance_to_players(group, &input.players);

    if group_distance > config.reduce_pathfinding_distance {
        // Reduce pathfinding frequency for distant groups
        reduce_pathfinding.extend(&group.villager_ids);
    }
}

fn calculate_group_distance_to_players(group: &VillagerGroup, players: &[PlayerData]) -> f32 {
    if players.is_empty() {
        return f32::MAX;
    }

    // Calculate distance from group center to nearest player
    let group_center = (group.center_x, group.center_y, group.center_z);
    
    players.par_iter()
        .map(|player| {
            let dx = player.x - group_center.0;
            let dy = player.y - group_center.1;
            let dz = player.z - group_center.2;
            (dx * dx + dy * dy + dz * dz).sqrt()
        })
        .min_by(|a, b| a.partial_cmp(b).unwrap())
        .unwrap_or(f32::MAX)
}

#[derive(Default)]
struct VillagerGroupResult {
    villagers_to_disable_ai: Vec<u64>,
    villagers_to_simplify_ai: Vec<u64>,
    villagers_to_reduce_pathfinding: Vec<u64>,
}

/// Batch process multiple villager collections for better performance
pub fn process_villager_ai_batch(inputs: Vec<VillagerInput>) -> Vec<VillagerProcessResult> {
    inputs.into_par_iter()
        .map(|input| process_villager_ai(input))
        .collect()
}

/// Process villager AI from JSON input and return JSON result
pub fn process_villager_ai_json(json_input: &str) -> Result<String, String> {
    let input: VillagerInput = serde_json::from_str(json_input)
        .map_err(|e| format!("Failed to parse JSON input: {}", e))?;
    
    let result = process_villager_ai(input);
    
    serde_json::to_string(&result)
        .map_err(|e| format!("Failed to serialize result to JSON: {}", e))
}

/// Process villager AI from binary input for better JNI performance
pub fn process_villager_ai_binary_batch(data: &[u8]) -> Result<Vec<u8>, String> {
    if data.is_empty() {
        return Ok(Vec::new());
    }

    // Deserialize manual villager input, process, and serialize result
    let input = crate::binary::conversions::deserialize_villager_input(data)
        .map_err(|e| format!("Failed to deserialize villager input: {}", e))?;
    let result = process_villager_ai(input);
    let out = crate::binary::conversions::serialize_villager_result(&result)
        .map_err(|e| format!("Failed to serialize villager result: {}", e))?;
    Ok(out)
}

/// Get performance statistics for villager processing
pub fn get_villager_processing_stats() -> Result<VillagerProcessingStats, String> {
    let pathfinding_stats = {
        let optimizer = PATHFINDING_OPTIMIZER.lock().map_err(|e| format!("Failed to acquire pathfinding optimizer lock: {}", e))?;
        optimizer.get_cache_stats()
    };

    Ok(VillagerProcessingStats {
        pathfinding_cache_stats: pathfinding_stats,
        active_groups: 0, // This would be calculated from current processing
        total_villagers_processed: 0, // This would be tracked over time
    })
}

#[derive(Serialize, Deserialize)]
pub struct VillagerProcessingStats {
    pub pathfinding_cache_stats: PathfindingCacheStats,
    pub active_groups: usize,
    pub total_villagers_processed: usize,
}
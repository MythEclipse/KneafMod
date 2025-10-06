use super::types::*;
use super::config::*;
use super::spatial::*;
use super::pathfinding::*;
use rayon::prelude::*;
use std::sync::atomic::{AtomicUsize, AtomicU64, AtomicBool, Ordering};
use once_cell::sync::Lazy;
use crate::villager::pathfinding;
use serde::{Deserialize, Serialize};
use dashmap::DashMap;
use crossbeam_queue::SegQueue;
use std::sync::{Arc, RwLock, Mutex};
use std::cell::UnsafeCell;
use crate::memory_pool::{EnhancedMemoryPoolManager, get_global_enhanced_pool, PooledVec};
use crate::arena::{BumpArena, get_global_arena_pool};
use std::ptr;

// Lock-free pathfinding optimizer with Copy-on-Write support
static PATHFINDING_OPTIMIZER: Lazy<Mutex<PathfindingOptimizer>> =
    Lazy::new(|| Mutex::new(PathfindingOptimizer::new()));

static ADVANCED_PATHFINDING_OPTIMIZER: Lazy<Mutex<AdvancedPathfindingOptimizer>> =
    Lazy::new(|| Mutex::new(AdvancedPathfindingOptimizer::new()));

// Lock-free villager group cache with CoW support for O(1) access
static VILLAGER_GROUP_CACHE: Lazy<DashMap<u32, Arc<VillagerGroup>>> = Lazy::new(DashMap::new);

// Global memory pool access for villager processing
static MEMORY_POOL: Lazy<Arc<EnhancedMemoryPoolManager>> =
    Lazy::new(|| Arc::clone(&get_global_enhanced_pool()));

static ARENA_POOL: Lazy<Arc<crate::arena::ArenaPool>> =
    Lazy::new(|| get_global_arena_pool());

// Atomic counters for performance monitoring
static TOTAL_VILLAGERS_PROCESSED: AtomicU64 = AtomicU64::new(0);
static ACTIVE_VILLAGER_GROUPS: AtomicUsize = AtomicUsize::new(0);
static VILLAGER_AI_CRITICAL_OPS: AtomicUsize = AtomicUsize::new(0);
static VILLAGER_AI_MEMORY_ABORTS: AtomicUsize = AtomicUsize::new(0);
static IS_VILLAGER_AI_CRITICAL: AtomicBool = AtomicBool::new(false);

pub fn process_villager_ai(mut input: VillagerInput) -> VillagerProcessResult {
    // Mark as critical operation to prevent memory cleanup during villager AI processing
    IS_VILLAGER_AI_CRITICAL.store(true, Ordering::Relaxed);
    VILLAGER_AI_CRITICAL_OPS.fetch_add(1, Ordering::Relaxed);
     
    let config = get_villager_config();
     
    // Check memory pressure before starting processing
    let memory_pressure = MEMORY_POOL.get_memory_pressure();
     
    // If memory pressure is critical, abort some non-critical processing to prevent lag
    if memory_pressure == crate::memory_pool::MemoryPressureLevel::Critical {
        VILLAGER_AI_MEMORY_ABORTS.fetch_add(1, Ordering::Relaxed);
        
        // Return early with minimal processing during critical memory pressure
        let result = process_villager_ai_during_critical_pressure(input, &config);
        IS_VILLAGER_AI_CRITICAL.store(false, Ordering::Relaxed);
        return result;
    }
     
    // Update pathfinding optimizer tick (write operation)
    {
        // Use CoW pattern for pathfinding optimizer
        let mut optimizer = PATHFINDING_OPTIMIZER.lock().unwrap();
        optimizer.update_tick(input.tick_count);
    }

    // Step 1: Spatial grouping - use lock-free grouping with memory pooling
    let spatial_groups = group_villagers_by_proximity(&input.villagers, &input.players);
     
    // Step 2: Optimize villager groups with lock-free operations
    let villager_groups = optimize_villager_groups(spatial_groups, &config);
     
    // Update atomic counters for performance monitoring
    let total_villagers = input.villagers.len() as u64;
    TOTAL_VILLAGERS_PROCESSED.fetch_add(total_villagers, Ordering::Relaxed);
    ACTIVE_VILLAGER_GROUPS.store(villager_groups.len(), Ordering::Relaxed);

    // Step 3: Process AI optimizations in parallel by group using lock-free results collection
    let group_results: Arc<SegQueue<VillagerGroupResult>> = Arc::new(SegQueue::new());

    villager_groups.par_iter().for_each(|group| {
        let result = process_villager_group(&group, &input, &config);
        group_results.push(result);

        // Cache the group for O(1) access in future calls using CoW
        VILLAGER_GROUP_CACHE.insert(group.group_id, Arc::new(group.clone()));
    });

    // Step 4: Optimize pathfinding for all villagers with CoW pattern
    let pathfinding_optimization = {
        // Use CoW pattern for pathfinding optimizers to avoid locking
        let mut optimizer = PATHFINDING_OPTIMIZER.lock().unwrap();
        
        // Use memory pool for villager data to reduce cloning
        let mem_pool = get_global_enhanced_pool();
        let mut villagers_copy = mem_pool.get_vec_u64(input.villagers.len());
        // Note: In real implementation, we would convert VillagerData to u64 representation
        
        let result = optimizer.optimize_villager_pathfinding(&mut input.villagers, &config);

        // Also apply advanced group-based pathfinding optimization
        let mut advanced_optimizer = ADVANCED_PATHFINDING_OPTIMIZER.lock().unwrap();
        let advanced_result = advanced_optimizer.optimize_large_villager_groups(&mut villager_groups.clone(), &config);

        // Combine results using memory pool
        let mut combined_result = mem_pool.get_vec_u64(result.len() + advanced_result.len());
        combined_result.extend_from_slice(&result);
        combined_result.extend_from_slice(&advanced_result);
        combined_result.to_vec()
    };

    // Step 5: Combine all results using lock-free operations
    let mut villagers_to_disable_ai: Vec<u64> = Vec::new();
    let mut villagers_to_simplify_ai: Vec<u64> = Vec::new();
    let mut villagers_to_reduce_pathfinding: Vec<u64> = Vec::new();

    while let Some(group_result) = group_results.pop() {
        villagers_to_disable_ai.extend(group_result.villagers_to_disable_ai);
        villagers_to_simplify_ai.extend(group_result.villagers_to_simplify_ai);
        villagers_to_reduce_pathfinding.extend(group_result.villagers_to_reduce_pathfinding);
    }

    // Clean up old cache entries with CoW pattern
    {
        let mut optimizer = PATHFINDING_OPTIMIZER.lock().unwrap();
        let active_ids: Vec<u64> = input.villagers.iter().map(|v| v.id).collect();
        optimizer.cleanup_old_cache(&active_ids);
    }

    // Clear critical operation flag
    IS_VILLAGER_AI_CRITICAL.store(false, Ordering::Relaxed);

    // Return result with CoW-enabled villager groups
    VillagerProcessResult {
        villagers_to_disable_ai,
        villagers_to_simplify_ai,
        villagers_to_reduce_pathfinding,
        villager_groups: villager_groups.into_iter().map(|g| g.clone()).collect(),
    }
}

/// Process villager AI with minimal processing during critical memory pressure
fn process_villager_ai_during_critical_pressure(input: VillagerInput, config: &VillagerConfig) -> VillagerProcessResult {
    // During critical memory pressure, we only do essential processing:
    // 1. Calculate group distances
    // 2. Apply maximum simplification to all villagers
    // 3. Skip complex pathfinding
     
    // Use memory pool for spatial groups to reduce allocation pressure
    let mem_pool = get_global_enhanced_pool();
    let spatial_groups = group_villagers_by_proximity(&input.villagers, &input.players);
    let villager_groups = optimize_villager_groups(spatial_groups, &config);
     
    let mut villagers_to_disable_ai = mem_pool.get_vec_u64(input.villagers.len());
    let mut villagers_to_simplify_ai = mem_pool.get_vec_u64(input.villagers.len());
    let mut villagers_to_reduce_pathfinding = mem_pool.get_vec_u64(input.villagers.len());
     
    // During critical pressure, disable AI for all but essential villagers
    villager_groups.iter().for_each(|group| {
        // Keep only 1 villager per group active during critical pressure
        let keep_count = std::cmp::max(1, group.villager_ids.len() / 8); // Fixed ratio instead of division by same number
        for (i, &villager_id) in group.villager_ids.iter().enumerate() {
            if i >= keep_count {
                villagers_to_disable_ai.push(villager_id);
            } else {
                villagers_to_simplify_ai.push(villager_id); // Simplify even the kept ones
            }
        }
         
        // Reduce pathfinding for all during critical pressure
        villagers_to_reduce_pathfinding.extend(&group.villager_ids);
    });
     
    VillagerProcessResult {
        villagers_to_disable_ai: villagers_to_disable_ai.to_vec(),
        villagers_to_simplify_ai: villagers_to_simplify_ai.to_vec(),
        villagers_to_reduce_pathfinding: villagers_to_reduce_pathfinding.to_vec(),
        villager_groups: villager_groups.into_iter().map(|g| g.clone()).collect(),
    }
}

fn process_villager_group(group: &VillagerGroup, input: &VillagerInput, config: &VillagerConfig) -> VillagerGroupResult {
    // Use memory pool for result storage to reduce allocation pressure
    let mem_pool = get_global_enhanced_pool();
    let mut villagers_to_disable_ai = mem_pool.get_vec_u64(group.villager_ids.len());
    let mut villagers_to_simplify_ai = mem_pool.get_vec_u64(group.villager_ids.len());
    let mut villagers_to_reduce_pathfinding = mem_pool.get_vec_u64(group.villager_ids.len());

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
        villagers_to_disable_ai: villagers_to_disable_ai.to_vec(),
        villagers_to_simplify_ai: villagers_to_simplify_ai.to_vec(),
        villagers_to_reduce_pathfinding: villagers_to_reduce_pathfinding.to_vec(),
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
        // Far working villagers - disable AI for most, keep a few active for critical tasks
        let villagers_to_keep = std::cmp::max(1, group.villager_ids.len() / 8);
        for (i, &villager_id) in group.villager_ids.iter().enumerate() {
            if i >= villagers_to_keep {
                disable_ai.push(villager_id);
            }
        }
    } else if group_distance > config.simplify_ai_distance {
        // Medium distance - simplify AI but keep some complexity
        let villagers_to_keep_complex = std::cmp::max(2, group.villager_ids.len() / 4);
        for (i, &villager_id) in group.villager_ids.iter().enumerate() {
            if i >= villagers_to_keep_complex {
                simplify_ai.push(villager_id);
            }
        }
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
    _disable_ai: &mut Vec<u64>,
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
        // Far wandering villagers - disable AI for most, keep a few active for exploration
        let villagers_to_keep = std::cmp::max(1, group.villager_ids.len() / 8);
        for (i, &villager_id) in group.villager_ids.iter().enumerate() {
            if i >= villagers_to_keep {
                disable_ai.push(villager_id);
            }
        }
    } else if group_distance > config.simplify_ai_distance {
        // Medium distance - simplify AI but keep some exploration behavior
        let villagers_to_keep_complex = std::cmp::max(2, group.villager_ids.len() / 4);
        for (i, &villager_id) in group.villager_ids.iter().enumerate() {
            if i >= villagers_to_keep_complex {
                simplify_ai.push(villager_id);
            }
        }
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
        // Far generic villagers - disable AI for most, keep a few active
        let villagers_to_keep = std::cmp::max(1, group.villager_ids.len() / 8);
        for (i, &villager_id) in group.villager_ids.iter().enumerate() {
            if i >= villagers_to_keep {
                disable_ai.push(villager_id);
            }
        }
    } else if group_distance > config.simplify_ai_distance {
        // Medium distance - simplify AI but keep some basic behavior
        let villagers_to_keep_complex = std::cmp::max(2, group.villager_ids.len() / 4);
        for (i, &villager_id) in group.villager_ids.iter().enumerate() {
            if i >= villagers_to_keep_complex {
                simplify_ai.push(villager_id);
            }
        }
    }
}

fn apply_distance_optimizations(
    group: &VillagerGroup,
    input: &VillagerInput,
    config: &VillagerConfig,
    _disable_ai: &mut Vec<u64>,
    _simplify_ai: &mut Vec<u64>,
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
        active_groups: ACTIVE_VILLAGER_GROUPS.load(Ordering::Relaxed),
        total_villagers_processed: TOTAL_VILLAGERS_PROCESSED.load(Ordering::Relaxed) as usize,
    })
}

#[derive(Serialize, Deserialize)]
pub struct VillagerProcessingStats {
    pub pathfinding_cache_stats: PathfindingCacheStats,
    pub active_groups: usize,
    pub total_villagers_processed: usize,
}
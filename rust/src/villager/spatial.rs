use super::types::*;
use rayon::prelude::*;
use once_cell::sync::Lazy;
use std::collections::HashMap;
use std::cell::UnsafeCell;
use std::sync::atomic::{AtomicPtr, Ordering};
use std::ptr;
use dashmap::DashMap;
use std::sync::{Arc, Mutex};

const CHUNK_SIZE: f32 = 16.0;
const MAX_GROUP_RADIUS: f32 = 32.0;

// Global lock-free spatial grouping cache
static VILLAGER_SPATIAL_CACHE: Lazy<DashMap<(i32, i32), Vec<SpatialGroup>>> = Lazy::new(DashMap::new);

pub fn group_villagers_by_proximity(villagers: &[VillagerData], players: &[PlayerData]) -> Vec<SpatialGroup> {
    if villagers.is_empty() {
        return Vec::new();
    }

    // Use DashMap for chunk-based spatial partitioning (thread-safe)
    let villager_chunks: DashMap<(i32, i32), Vec<VillagerData>> = DashMap::new();

    for villager in villagers {
        let chunk_x = (villager.x / CHUNK_SIZE).floor() as i32;
        let chunk_z = (villager.z / CHUNK_SIZE).floor() as i32;
        let key = (chunk_x, chunk_z);

        villager_chunks.entry(key).or_insert_with(Vec::new).push(villager.clone());
    }

    // Process chunks in parallel with lock-free operations
    let results: Arc<Mutex<Vec<SpatialGroup>>> = Arc::new(Mutex::new(Vec::new()));
    villager_chunks.into_iter().par_bridge().for_each(|((chunk_x, chunk_z), mut chunk_villagers)| {
        if chunk_villagers.is_empty() {
            return;
        }

        // Calculate approximate player distance for this chunk
        let chunk_center_x = (chunk_x as f32 + 0.5) * CHUNK_SIZE;
        let chunk_center_z = (chunk_z as f32 + 0.5) * CHUNK_SIZE;
        let estimated_player_distance = calculate_min_distance_to_players(chunk_center_x, 64.0, chunk_center_z, players);

        // Group villagers within the chunk based on proximity
        let groups = group_villagers_in_chunk(&mut chunk_villagers, chunk_x, chunk_z, estimated_player_distance);
        
        // Use atomic operations to update cache without locking
        VILLAGER_SPATIAL_CACHE.insert((chunk_x, chunk_z), groups.clone());
        
        // Append results into shared vector
        let mut guard = results.lock().unwrap();
        guard.extend(groups);
    });

    let guard = results.lock().unwrap();
    guard.clone()
}

fn group_villagers_in_chunk(villagers: &mut Vec<VillagerData>, chunk_x: i32, chunk_z: i32, estimated_player_distance: f32) -> Vec<SpatialGroup> {
    if villagers.is_empty() {
        return Vec::new();
    }

    let mut groups: Vec<SpatialGroup> = Vec::new();
    let mut processed = vec![false; villagers.len()];

    // Sort by distance to optimize grouping
    villagers.sort_by(|a, b| a.distance.partial_cmp(&b.distance).unwrap());

    for i in 0..villagers.len() {
        if processed[i] {
            continue;
        }

        // Start a new group with this villager
        let mut group_villagers = vec![villagers[i].clone()];
        processed[i] = true;
        
        let mut group_center = (villagers[i].x, villagers[i].y, villagers[i].z);
        let mut total_weight = 1.0;

        // Find nearby villagers to add to this group
        for j in (i + 1)..villagers.len() {
            if processed[j] {
                continue;
            }

            let distance = calculate_distance(
                villagers[i].x, villagers[i].y, villagers[i].z,
                villagers[j].x, villagers[j].y, villagers[j].z
            );

            if distance <= MAX_GROUP_RADIUS {
                group_villagers.push(villagers[j].clone());
                processed[j] = true;

                // Update group center (weighted average)
                let weight = 1.0 / (1.0 + distance * 0.1); // Closer villagers have more weight
                group_center.0 += villagers[j].x * weight;
                group_center.1 += villagers[j].y * weight;
                group_center.2 += villagers[j].z * weight;
                total_weight += weight;
            }

            // Limit group size to prevent oversized groups
            if group_villagers.len() >= 16 {
                break;
            }
        }

        // Normalize group center
        if total_weight > 0.0 {
            group_center.0 /= total_weight;
            group_center.1 /= total_weight;
            group_center.2 /= total_weight;
        }

        groups.push(SpatialGroup {
            chunk_x,
            chunk_z,
            villagers: group_villagers,
            group_center,
            estimated_player_distance,
        });
    }

    groups
}

pub fn calculate_min_distance_to_players(x: f32, y: f32, z: f32, players: &[PlayerData]) -> f32 {
    if players.is_empty() {
        return f32::MAX;
    }

    players.par_iter()
        .map(|player| calculate_distance(x, y, z, player.x, player.y, player.z))
        .min_by(|a, b| a.partial_cmp(b).unwrap())
        .unwrap_or(f32::MAX)
}

#[inline]
fn calculate_distance(x1: f32, y1: f32, z1: f32, x2: f32, y2: f32, z2: f32) -> f32 {
    let dx = x2 - x1;
    let dy = y2 - y1;
    let dz = z2 - z1;
    (dx * dx + dy * dy + dz * dz).sqrt()
}

pub fn optimize_villager_groups(groups: Vec<SpatialGroup>, config: &VillagerConfig) -> Vec<VillagerGroup> {
    groups.into_par_iter()
        .filter_map(|group| {
            if group.villagers.is_empty() {
                return None;
            }

            // Determine group type based on villager characteristics
            let group_type = determine_group_type(&group.villagers);
            
            // Calculate AI tick rate based on player distance and group characteristics
            let ai_tick_rate = calculate_ai_tick_rate(group.estimated_player_distance, &group_type, config);
            
            // Create villager group
            Some(VillagerGroup {
                group_id: ((group.chunk_x as u32) << 16) | (group.chunk_z as u32 & 0xFFFF),
                center_x: group.group_center.0,
                center_y: group.group_center.1,
                center_z: group.group_center.2,
                villager_ids: group.villagers.iter().map(|v| v.id).collect(),
                group_type,
                ai_tick_rate,
            })
        })
        .collect()
}

fn determine_group_type(villagers: &[VillagerData]) -> String {
    let has_workstation = villagers.iter().any(|v| v.has_workstation);
    let is_resting = villagers.iter().any(|v| v.is_resting);
    let is_breeding = villagers.iter().any(|v| v.is_breeding);
    
    if is_breeding {
        "breeding".to_string()
    } else if is_resting {
        "resting".to_string()
    } else if has_workstation {
        "working".to_string()
    } else {
        // Check if villagers are clustered (potential village)
        let avg_distance = calculate_average_villager_distance(villagers);
        if avg_distance < 20.0 && villagers.len() > 3 {
            "village".to_string()
        } else {
            "wandering".to_string()
        }
    }
}

fn calculate_ai_tick_rate(player_distance: f32, group_type: &str, config: &VillagerConfig) -> u8 {
    match group_type {
        "village" => {
            if player_distance < config.reduce_pathfinding_distance {
                config.complex_ai_tick_interval
            } else if player_distance < config.simplify_ai_distance {
                config.simple_ai_tick_interval
            } else {
                config.pathfinding_tick_interval
            }
        },
        "working" => {
            if player_distance < config.simplify_ai_distance {
                config.simple_ai_tick_interval
            } else {
                config.pathfinding_tick_interval
            }
        },
        "breeding" => {
            // Breeding villagers need more frequent updates when players are nearby
            if player_distance < config.simplify_ai_distance {
                config.simple_ai_tick_interval
            } else {
                config.pathfinding_tick_interval
            }
        },
        "resting" => config.rest_tick_interval,
        "wandering" => config.pathfinding_tick_interval,
        _ => config.pathfinding_tick_interval,
    }
}

fn calculate_average_villager_distance(villagers: &[VillagerData]) -> f32 {
    if villagers.len() < 2 {
        return 0.0;
    }

    let mut total_distance = 0.0;
    let mut count = 0;

    for i in 0..villagers.len() {
        for j in (i + 1)..villagers.len() {
            let distance = calculate_distance(
                villagers[i].x, villagers[i].y, villagers[i].z,
                villagers[j].x, villagers[j].y, villagers[j].z
            );
            total_distance += distance;
            count += 1;
        }
    }

    if count > 0 {
        total_distance / count as f32
    } else {
        0.0
    }
}
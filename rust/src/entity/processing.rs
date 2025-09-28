use super::types::*;
use super::config::*;
use crate::EXCEPTIONS_CONFIG;
use crate::spatial::SpatialPartition;
use rayon::prelude::*;
use std::sync::RwLock;

lazy_static::lazy_static! {
    static ref ENTITY_SPATIAL_PARTITION: RwLock<Option<SpatialPartition<u64>>> = RwLock::new(None);
}

pub fn process_entities(input: Input) -> ProcessResult {
    let config = CONFIG.read().unwrap();
    let exceptions = EXCEPTIONS_CONFIG.read().unwrap();

    if config.use_spatial_partitioning {
        // Update spatial partition
        let mut partition = ENTITY_SPATIAL_PARTITION.write().unwrap();
        if partition.is_none() {
            *partition = Some(SpatialPartition::new(config.world_bounds.clone(), config.quadtree_max_entities, config.quadtree_max_depth));
        }
        if let Some(ref mut spatial) = *partition {
            // Insert players into spatial partition
            for player in &input.players {
                spatial.insert_or_update(player.id, [player.x, player.y, player.z]);
            }

            // Insert entities
            for entity in &input.entities {
                spatial.insert_or_update(entity.id, [entity.x, entity.y, entity.z]);
            }

            spatial.rebuild();

            // Process entities using spatial queries
            let entities_to_tick: Vec<u64> = input.entities.par_iter().filter_map(|entity| {
                if entity.is_block_entity || exceptions.critical_entity_types.contains(&entity.entity_type) {
                    Some(entity.id)
                } else {
                    // Query nearby players
                    let nearby_players = spatial.query_nearby([entity.x, entity.y, entity.z], config.medium_radius as f64);
                    if !nearby_players.is_empty() {
                        // Calculate distance to nearest player
                        let mut min_distance = f64::INFINITY;
                        for (_, player_pos) in nearby_players {
                            let dx = entity.x - player_pos[0];
                            let dy = entity.y - player_pos[1];
                            let dz = entity.z - player_pos[2];
                            let distance = (dx * dx + dy * dy + dz * dz).sqrt();
                            if distance < min_distance {
                                min_distance = distance;
                            }
                        }

                        let rate = if min_distance <= config.close_radius as f64 {
                            config.close_rate as f32
                        } else if min_distance <= config.medium_radius as f64 {
                            config.medium_rate as f32
                        } else {
                            config.far_rate as f32
                        };
                        let period = (1.0 / rate) as u64;
                        if period == 0 || (input.tick_count + entity.id as u64) % period == 0 {
                            Some(entity.id)
                        } else {
                            None
                        }
                    } else {
                        // No players nearby, use far rate
                        let period = (1.0 / config.far_rate) as u64;
                        if period == 0 || (input.tick_count + entity.id as u64) % period == 0 {
                            Some(entity.id)
                        } else {
                            None
                        }
                    }
                }
            }).collect();

            ProcessResult { entities_to_tick }
        } else {
            // Fallback to original logic
            fallback_process_entities(&input, &input.entity_config, &exceptions)
        }
    } else {
        fallback_process_entities(&input, &input.entity_config, &exceptions)
    }
}

fn fallback_process_entities(input: &Input, config: &super::config::Config, exceptions: &crate::ExceptionsConfig) -> ProcessResult {
    let entities_to_tick: Vec<u64> = input.entities.par_iter().filter_map(|entity| {
        if entity.is_block_entity || exceptions.critical_entity_types.contains(&entity.entity_type) {
            Some(entity.id)
        } else {
            let rate = if entity.distance <= config.close_radius as f32 {
                config.close_rate
            } else if entity.distance <= config.medium_radius as f32 {
                config.medium_rate as f32
            } else {
                config.far_rate as f32
            };
            let period = (1.0 / rate) as u64;
            if period == 0 || (input.tick_count + entity.id as u64) % period == 0 {
                Some(entity.id)
            } else {
                None
            }
        }
    }).collect();
    ProcessResult { entities_to_tick }
}
use crate::types::*;
use crate::config::*;
use std::collections::HashMap;
use std::fs;
use std::fs::OpenOptions;
use std::io::Write;
use std::time::{Duration, Instant};

pub fn process_entities(input: Input) -> ProcessResult {
    let config = CONFIG.read().unwrap();
    let exceptions = EXCEPTIONS_CONFIG.read().unwrap();
    let mut entities_to_tick = Vec::new();
    for entity in input.entities {
        if entity.is_block_entity || exceptions.critical_entity_types.contains(&entity.entity_type) {
            entities_to_tick.push(entity.id);
            continue;
        }
        let rate = if entity.distance <= config.close_radius {
            config.close_rate
        } else if entity.distance <= config.medium_radius {
            config.medium_rate
        } else {
            config.far_rate
        };
        let period = (1.0 / rate) as u64;
        if period == 0 || (input.tick_count + entity.id as u64) % period == 0 {
            entities_to_tick.push(entity.id);
        }
    }
    ProcessResult { entities_to_tick }
}

pub fn process_item_entities(input: ItemInput) -> ItemProcessResult {
    let config = ITEM_CONFIG.read().unwrap();
    let mut items_to_remove = Vec::new();
    let mut item_updates = Vec::new();
    let mut local_merged = 0u64;
    let mut local_despawned = 0u64;

    let mut chunk_map: HashMap<(i32, i32), Vec<&ItemEntityData>> = HashMap::new();
    for item in &input.items {
        chunk_map.entry((item.chunk_x, item.chunk_z)).or_insert(Vec::new()).push(item);
    }

    for (_chunk, items) in chunk_map {
        // Merge stacks
        if config.merge_enabled {
            let mut type_map: HashMap<&str, Vec<&ItemEntityData>> = HashMap::new();
            for item in &items {
                type_map.entry(&item.item_type).or_insert(Vec::new()).push(item);
            }
            for (_type, type_items) in type_map {
                if type_items.len() > 1 {
                    let mut total_count = 0u32;
                    let mut keep_id = None;
                    for item in &type_items {
                        total_count += item.count;
                        if keep_id.is_none() {
                            keep_id = Some(item.id);
                        }
                    }
                    if let Some(keep_id) = keep_id {
                        item_updates.push(ItemUpdate { id: keep_id, new_count: total_count });
                        for item in &type_items {
                            if item.id != keep_id {
                                items_to_remove.push(item.id);
                                local_merged += 1;
                            }
                        }
                    }
                }
            }
        }

        // Enforce max per chunk
        let mut sorted_items: Vec<&ItemEntityData> = items.iter().filter(|i| !items_to_remove.contains(&i.id)).cloned().collect();
        sorted_items.sort_by_key(|i| i.age_seconds);
        if sorted_items.len() > config.max_items_per_chunk {
            let excess = sorted_items.len() - config.max_items_per_chunk;
            for i in 0..excess {
                items_to_remove.push(sorted_items[i].id);
                local_despawned += 1;
            }
        }

        // Despawn old items
        for item in &items {
            if item.age_seconds > config.despawn_time_seconds && !items_to_remove.contains(&item.id) {
                items_to_remove.push(item.id);
                local_despawned += 1;
            }
        }
    }

    // Update global counters
    *MERGED_COUNT.write().unwrap() += local_merged;
    *DESPAWNED_COUNT.write().unwrap() += local_despawned;

    // Log every minute
    let mut last_time = LAST_LOG_TIME.write().unwrap();
    if last_time.elapsed() > Duration::from_secs(60) {
        let merged = *MERGED_COUNT.read().unwrap();
        let despawned = *DESPAWNED_COUNT.read().unwrap();
        let log_msg = format!("Item optimization: {} merged, {} despawned\n", merged, despawned);
        if let Err(e) = fs::create_dir_all("logs") {
            eprintln!("Failed to create logs dir: {}", e);
        } else if let Ok(mut file) = OpenOptions::new().create(true).append(true).open("logs/rustperf.log") {
            if let Err(e) = write!(file, "{}", log_msg) {
                eprintln!("Failed to write log: {}", e);
            }
        }
        *last_time = Instant::now();
        *MERGED_COUNT.write().unwrap() = 0;
        *DESPAWNED_COUNT.write().unwrap() = 0;
    }

    ItemProcessResult { items_to_remove, merged_count: local_merged, despawned_count: local_despawned, item_updates }
}

pub fn process_mob_ai(input: MobInput) -> MobProcessResult {
    let config = AI_CONFIG.read().unwrap();
    let exceptions = EXCEPTIONS_CONFIG.read().unwrap();
    let mut mobs_to_disable_ai = Vec::new();
    let mut mobs_to_simplify_ai = Vec::new();
    for mob in input.mobs {
        if exceptions.critical_entity_types.contains(&mob.entity_type) {
            continue;
        }
        if mob.is_passive {
            if mob.distance > config.passive_disable_distance {
                mobs_to_disable_ai.push(mob.id);
            }
        } else {
            if mob.distance > config.hostile_simplify_distance {
                let period = (1.0 / config.ai_tick_rate_far) as u64;
                if period == 0 || (input.tick_count + mob.id as u64) % period == 0 {
                    mobs_to_simplify_ai.push(mob.id);
                }
            }
        }
    }
    MobProcessResult { mobs_to_disable_ai, mobs_to_simplify_ai }
}
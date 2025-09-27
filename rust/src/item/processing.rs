use super::types::*;
use super::config::*;
use std::collections::HashMap;
use std::fs;
use std::fs::OpenOptions;
use std::io::Write;
use std::time::{Duration, Instant};
use rayon::prelude::*;

pub fn process_item_entities(input: ItemInput) -> ItemProcessResult {
    let config = ITEM_CONFIG.read().unwrap();

    // Build chunk_map in parallel
    let chunk_map: HashMap<(i32, i32), Vec<&ItemEntityData>> = input.items.par_iter().fold(HashMap::new, |mut acc, item| {
        acc.entry((item.chunk_x, item.chunk_z)).or_insert(Vec::new()).push(item);
        acc
    }).reduce(HashMap::new, |mut acc, map| {
        for (key, value) in map {
            acc.entry(key).or_insert(Vec::new()).extend(value);
        }
        acc
    });

    // Process each chunk in parallel
    let (items_to_remove, item_updates, local_merged, local_despawned): (Vec<u64>, Vec<ItemUpdate>, u64, u64) = chunk_map.par_iter().map(|(_chunk, items)| {
        let mut local_items_to_remove = Vec::new();
        let mut local_item_updates = Vec::new();
        let mut local_merged = 0u64;
        let mut local_despawned = 0u64;

        // Merge stacks
        if config.merge_enabled {
            let type_map: HashMap<&str, Vec<&ItemEntityData>> = items.par_iter().fold(HashMap::new, |mut acc, item| {
                acc.entry(item.item_type.as_str()).or_insert(Vec::new()).push(*item);
                acc
            }).reduce(HashMap::new, |mut acc, map| {
                for (key, value) in map {
                    acc.entry(key).or_insert(Vec::new()).extend(value);
                }
                acc
            });

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
                        local_item_updates.push(ItemUpdate { id: keep_id, new_count: total_count });
                        for item in &type_items {
                            if item.id != keep_id {
                                local_items_to_remove.push(item.id);
                                local_merged += 1;
                            }
                        }
                    }
                }
            }
        }

        // Enforce max per chunk
        let filtered_items: Vec<&ItemEntityData> = items.iter().filter(|i| !local_items_to_remove.contains(&i.id)).cloned().collect();
        let mut sorted_items: Vec<&ItemEntityData> = filtered_items;
        sorted_items.par_sort_by_key(|i| i.age_seconds);
        if sorted_items.len() > config.max_items_per_chunk {
            let excess = sorted_items.len() - config.max_items_per_chunk;
            for i in 0..excess {
                local_items_to_remove.push(sorted_items[i].id);
                local_despawned += 1;
            }
        }

        // Despawn old items
        for item in items {
            if item.age_seconds > config.despawn_time_seconds && !local_items_to_remove.contains(&item.id) {
                local_items_to_remove.push(item.id);
                local_despawned += 1;
            }
        }

        (local_items_to_remove, local_item_updates, local_merged, local_despawned)
    }).reduce(|| (Vec::new(), Vec::new(), 0, 0), |(mut acc_remove, mut acc_updates, mut acc_merged, mut acc_despawned), (remove, updates, merged, despawned)| {
        acc_remove.extend(remove);
        acc_updates.extend(updates);
        acc_merged += merged;
        acc_despawned += despawned;
        (acc_remove, acc_updates, acc_merged, acc_despawned)
    });

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
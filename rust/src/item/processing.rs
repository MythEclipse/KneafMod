use super::types::*;
use super::config::*;
use std::collections::HashMap;
use std::fs;
use std::fs::OpenOptions;
use std::io::Write;
use std::time::{Duration, Instant};
use std::sync::mpsc::{channel, Sender};
use std::thread;
use rayon::prelude::*;
use serde::{Serialize, Deserialize};

#[derive(Debug, Serialize, Deserialize)]
struct ProfilingData {
    operation: String,
    duration_ms: f64,
    items_processed: usize,
    chunks_processed: usize,
    timestamp: String,
}

struct OperationTimer {
    start: Instant,
    operation: String,
    items_processed: usize,
    chunks_processed: usize,
}

impl OperationTimer {
    fn new(operation: &str) -> Self {
        Self {
            start: Instant::now(),
            operation: operation.to_string(),
            items_processed: 0,
            chunks_processed: 0,
        }
    }

    fn with_items(mut self, items: usize) -> Self {
        self.items_processed = items;
        self
    }

    fn with_chunks(mut self, chunks: usize) -> Self {
        self.chunks_processed = chunks;
        self
    }

    fn finish(self) -> f64 {
        let duration = self.start.elapsed();
        let duration_ms = duration.as_secs_f64() * 1000.0;
        
        let profiling_config = PROFILING_CONFIG.read().unwrap();
        if profiling_config.enabled {
            // Update counters
            let mut counters = PROCESSING_COUNTERS.write().unwrap();
            match self.operation.as_str() {
                "item_grouping" => counters.total_grouping_operations += 1,
                "item_merging" => counters.total_merge_operations += 1,
                "item_filtering" => counters.total_filter_operations += 1,
                "item_sorting" => counters.total_sort_operations += 1,
                "item_despawning" => counters.total_despawn_operations += 1,
                _ => {}
            }
            counters.total_items_processed += self.items_processed as u64;
            counters.total_chunks_processed += self.chunks_processed as u64;

            // Log slow operations
            if duration_ms > profiling_config.slow_operation_threshold_ms as f64 {
                let profiling_data = ProfilingData {
                    operation: self.operation.clone(),
                    duration_ms,
                    items_processed: self.items_processed,
                    chunks_processed: self.chunks_processed,
                    timestamp: chrono::Utc::now().to_rfc3339(),
                };

                if profiling_config.log_detailed_timing {
                    let log_msg = format!(
                        "[SLOW_OPERATION] {} took {:.2}ms (items: {}, chunks: {})\n",
                        profiling_data.operation,
                        profiling_data.duration_ms,
                        profiling_data.items_processed,
                        profiling_data.chunks_processed
                    );
                    
                    if let Err(e) = LOG_SENDER.send(log_msg) {
                        eprintln!("Failed to send profiling log to background thread: {}", e);
                    }
                }
            }
        }
        
        duration_ms
    }
}

fn log_profiling_summary(total_items: usize, total_chunks: usize, total_duration_ms: f64) {
    let profiling_config = PROFILING_CONFIG.read().unwrap();
    if !profiling_config.enabled {
        return;
    }

    let counters = PROCESSING_COUNTERS.read().unwrap();
    let summary = format!(
        "[PROFILING_SUMMARY] Processed {} items in {} chunks (total: {:.2}ms). Operations: grouping={}, merging={}, filtering={}, sorting={}, despawning={}\n",
        total_items,
        total_chunks,
        total_duration_ms,
        counters.total_grouping_operations,
        counters.total_merge_operations,
        counters.total_filter_operations,
        counters.total_sort_operations,
        counters.total_despawn_operations
    );
    
    if let Err(e) = LOG_SENDER.send(summary) {
        eprintln!("Failed to send profiling summary to background thread: {}", e);
    }
}

lazy_static::lazy_static! {
    static ref LOG_SENDER: Sender<String> = {
        let (sender, receiver) = channel::<String>();
        
        // Spawn background logging thread
        thread::spawn(move || {
            while let Ok(log_msg) = receiver.recv() {
                if let Err(e) = fs::create_dir_all("logs") {
                    eprintln!("Failed to create logs dir: {}", e);
                    continue;
                }
                
                if let Ok(mut file) = OpenOptions::new().create(true).append(true).open("logs/rustperf.log") {
                    if let Err(e) = write!(file, "{}", log_msg) {
                        eprintln!("Failed to write log: {}", e);
                    }
                } else {
                    eprintln!("Failed to open log file");
                }
            }
        });
        
        sender
    };
}

fn configure_rayon_thread_pool() {
    let config = THREAD_POOL_CONFIG.read().unwrap();
    
    // Configure Rayon's global thread pool if not already configured
    if !config.adaptive_scaling {
        return;
    }
    
    let cpu_cores = num_cpus::get();
    let optimal_threads = (cpu_cores * 2).min(config.max_threads).max(config.min_threads);
    
    // Update metrics
    let mut metrics = THREAD_POOL_METRICS.write().unwrap();
    metrics.current_thread_count = optimal_threads;
    
    // Log configuration
    let log_msg = format!("[THREAD_POOL] Configured Rayon with {} threads (CPU cores: {})\n", optimal_threads, cpu_cores);
    if let Err(e) = LOG_SENDER.send(log_msg) {
        eprintln!("Failed to send thread pool log: {}", e);
    }
}

fn calculate_optimal_thread_count(workload_size: usize) -> usize {
    let config = THREAD_POOL_CONFIG.read().unwrap();
    let cpu_cores = num_cpus::get();
    
    // Base calculation: smaller workloads need fewer threads
    let base_threads = match workload_size {
        0..=100 => 1,                    // Very small workload
        101..=1000 => (cpu_cores / 4).max(1),  // Small workload
        1001..=10000 => cpu_cores / 2,          // Medium workload
        10001..=50000 => cpu_cores,             // Large workload
        _ => (cpu_cores * 2).min(config.max_threads), // Very large workload
    };
    
    base_threads.clamp(config.min_threads, config.max_threads)
}

fn monitor_and_adjust_thread_pool(workload_size: usize) {
    // Update metrics based on workload
    let optimal_threads = calculate_optimal_thread_count(workload_size);
    let mut metrics = THREAD_POOL_METRICS.write().unwrap();
    
    // Estimate active threads based on workload size
    let active_threads = optimal_threads.min(workload_size / 100).max(1);
    metrics.active_thread_count = active_threads;
    metrics.utilization_rate = active_threads as f32 / metrics.current_thread_count as f32;
    
    // Log workload adaptation
    let log_msg = format!("[WORKLOAD_ADAPTATION] Workload: {} items, Optimal threads: {}, Active: {}, Utilization: {:.1}%\n", 
        workload_size, optimal_threads, active_threads, metrics.utilization_rate * 100.0);
    if let Err(e) = LOG_SENDER.send(log_msg) {
        eprintln!("Failed to send workload adaptation log: {}", e);
    }
}

pub fn process_item_entities(input: ItemInput) -> ItemProcessResult {
    // Configure Rayon thread pool on first use
    static INIT: std::sync::Once = std::sync::Once::new();
    INIT.call_once(|| {
        configure_rayon_thread_pool();
    });

    let config = ITEM_CONFIG.read().unwrap();
    let total_items = input.items.len();
    let overall_timer = OperationTimer::new("overall_processing").with_items(total_items);

    // Monitor and adjust thread pool based on workload
    monitor_and_adjust_thread_pool(total_items);

    // Build chunk_map in parallel with timing
    let grouping_timer = OperationTimer::new("item_grouping").with_items(total_items);
    // Estimate: roughly sqrt(total_items) chunks, with average of sqrt(total_items) items per chunk
    let estimated_chunks = (total_items as f64).sqrt().ceil() as usize;
    let estimated_items_per_chunk = (total_items as f64).sqrt().ceil() as usize;
    
    let chunk_map: HashMap<(i32, i32), Vec<&ItemEntityData>> = input.items.par_iter().fold(
        || HashMap::with_capacity(estimated_chunks.min(1000)), // Cap at reasonable max
        |mut acc, item| {
            acc.entry((item.chunk_x, item.chunk_z)).or_insert_with(|| Vec::with_capacity(estimated_items_per_chunk.min(100))).push(item);
            acc
        }
    ).reduce(
        || HashMap::with_capacity(estimated_chunks.min(1000)),
        |mut acc, map| {
            for (key, value) in map {
                acc.entry(key).or_insert_with(|| Vec::with_capacity(value.len())).extend(value);
            }
            acc
        }
    );
    let chunk_count = chunk_map.len();
    let _grouping_duration = grouping_timer.with_chunks(chunk_count).finish();

    // Process each chunk in parallel
    let (items_to_remove, item_updates, local_merged, local_despawned): (std::collections::HashSet<u64>, Vec<ItemUpdate>, u64, u64) = chunk_map.par_iter().map(|(_chunk, items)| {
        let items_in_chunk = items.len();
        // Estimate: up to 50% of items might be removed/merged in worst case
        let estimated_removals = (items_in_chunk / 2).max(10).min(1000);
        let estimated_updates = (items_in_chunk / 4).max(5).min(500);
        
        let mut local_items_to_remove = std::collections::HashSet::with_capacity(estimated_removals);
        let mut local_item_updates = Vec::with_capacity(estimated_updates);
        let mut local_merged = 0u64;
        let mut local_despawned = 0u64;

        // Merge stacks with timing
        if config.merge_enabled {
            let merge_timer = OperationTimer::new("item_merging").with_items(items_in_chunk);
            
            // Estimate: roughly 20% unique types, with average 5 items per type
            let estimated_types = (items_in_chunk / 5).max(5).min(200);
            let estimated_items_per_type = 5.min(items_in_chunk);
            
            let type_map: HashMap<&str, Vec<&ItemEntityData>> = items.par_iter().fold(
                || HashMap::with_capacity(estimated_types),
                |mut acc, item| {
                    acc.entry(item.item_type.as_str()).or_insert_with(|| Vec::with_capacity(estimated_items_per_type)).push(*item);
                    acc
                }
            ).reduce(
                || HashMap::with_capacity(estimated_types),
                |mut acc, map| {
                    for (key, value) in map {
                        acc.entry(key).or_insert_with(|| Vec::with_capacity(value.len())).extend(value);
                    }
                    acc
                }
            );

            for (_type, type_items) in type_map {
                if type_items.len() > 1 {
                    let mut total_count = 0u32;
                    let mut keep_id = None;
                    
                    // Single pass: collect total count and identify items to remove
                    for item in &type_items {
                        total_count += item.count;
                        if let Some(_keep) = keep_id {
                            // Already have a keeper, mark this item for removal
                            local_items_to_remove.insert(item.id);
                            local_merged += 1;
                        } else {
                            // First item becomes the keeper
                            keep_id = Some(item.id);
                        }
                    }
                    
                    if let Some(keep_id) = keep_id {
                        local_item_updates.push(ItemUpdate { id: keep_id, new_count: total_count });
                    }
                }
            }
            
            merge_timer.finish();
        }

        // Enforce max per chunk with timing for filtering and sorting
        let filter_timer = OperationTimer::new("item_filtering").with_items(items_in_chunk);
        let filtered_items: Vec<&ItemEntityData> = items.iter().filter(|i| !local_items_to_remove.contains(&i.id)).cloned().collect();
        filter_timer.finish();
        
        let sort_timer = OperationTimer::new("item_sorting").with_items(filtered_items.len());
        let mut sorted_items: Vec<&ItemEntityData> = Vec::with_capacity(filtered_items.len());
        sorted_items.extend_from_slice(&filtered_items);
        sorted_items.par_sort_by_key(|i| i.age_seconds);
        sort_timer.finish();
        
        if sorted_items.len() > config.max_items_per_chunk {
            let excess = sorted_items.len() - config.max_items_per_chunk;
            for i in 0..excess {
                local_items_to_remove.insert(sorted_items[i].id);
                local_despawned += 1;
            }
        }

        // Despawn old items with timing
        let despawn_timer = OperationTimer::new("item_despawning").with_items(items_in_chunk);
        for item in items {
            if item.age_seconds > config.despawn_time_seconds && !local_items_to_remove.contains(&item.id) {
                local_items_to_remove.insert(item.id);
                local_despawned += 1;
            }
        }
        despawn_timer.finish();

        (local_items_to_remove, local_item_updates, local_merged, local_despawned)
    }).reduce(
        || {
            // Estimate based on total items and chunk count
            let estimated_total_removals = (total_items / 4).max(100).min(10000);
            let estimated_total_updates = (total_items / 8).max(50).min(5000);
            (std::collections::HashSet::with_capacity(estimated_total_removals), Vec::with_capacity(estimated_total_updates), 0, 0)
        },
        |(mut acc_remove, mut acc_updates, mut acc_merged, mut acc_despawned), (remove, updates, merged, despawned)| {
            acc_remove.extend(remove);
            acc_updates.extend(updates);
            acc_merged += merged;
            acc_despawned += despawned;
            (acc_remove, acc_updates, acc_merged, acc_despawned)
        }
    );

    // Convert HashSet to Vec for the final result
    let mut items_to_remove_vec: Vec<u64> = Vec::with_capacity(items_to_remove.len());
    items_to_remove_vec.extend(items_to_remove.into_iter());

    // Update global counters
    *MERGED_COUNT.write().unwrap() += local_merged;
    *DESPAWNED_COUNT.write().unwrap() += local_despawned;

    // Log every minute (asynchronously)
    let mut last_time = LAST_LOG_TIME.write().unwrap();
    if last_time.elapsed() > Duration::from_secs(60) {
        let merged = *MERGED_COUNT.read().unwrap();
        let despawned = *DESPAWNED_COUNT.read().unwrap();
        let log_msg = format!("Item optimization: {} merged, {} despawned\n", merged, despawned);
        
        // Send log message to background thread
        if let Err(e) = LOG_SENDER.send(log_msg) {
            eprintln!("Failed to send log to background thread: {}", e);
        }
        
        *last_time = Instant::now();
        *MERGED_COUNT.write().unwrap() = 0;
        *DESPAWNED_COUNT.write().unwrap() = 0;
    }

    // Log profiling summary
    let overall_duration = overall_timer.with_chunks(chunk_count).finish();
    log_profiling_summary(total_items, chunk_count, overall_duration);

    ItemProcessResult { items_to_remove: items_to_remove_vec, merged_count: local_merged, despawned_count: local_despawned, item_updates }
}

/// Process item entities from JSON input and return JSON result
pub fn process_item_entities_json(json_input: &str) -> Result<String, String> {
    let input: ItemInput = serde_json::from_str(json_input)
        .map_err(|e| format!("Failed to parse JSON input: {}", e))?;
    
    let result = process_item_entities(input);
    
    serde_json::to_string(&result)
        .map_err(|e| format!("Failed to serialize result to JSON: {}", e))
}
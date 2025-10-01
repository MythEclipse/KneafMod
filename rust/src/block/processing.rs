use super::types::*;
use super::config::*;
use rayon::prelude::*;
use serde_json;
use crate::parallelism::{get_adaptive_pool, monitoring, WorkStealingScheduler};
use std::time::Instant;

pub fn process_block_entities(input: BlockInput) -> BlockProcessResult {
    let start_time = Instant::now();
    let config = BLOCK_CONFIG.read().unwrap();

    // Filter block entities based on distance and activity heuristic using adaptive parallelism
    // Active entities: close radius (always tick), medium radius (reduced rate), far radius (minimal rate)
    let pool = get_adaptive_pool();
    let block_entities_to_tick: Vec<u64> = pool.execute(|| {
        input.block_entities
            .par_iter()
            .filter_map(|be| {
                // Simple heuristic: closer entities are more likely to be active
                // Use distance-based filtering with tick rate consideration
                let should_tick = if be.distance <= config.close_radius {
                    // Close entities: always tick
                    true
                } else if be.distance <= config.medium_radius {
                    // Medium distance: tick at reduced rate based on tick count
                    input.tick_count % (1.0 / config.medium_rate) as u64 == 0
                } else {
                    // Far entities: tick at minimal rate
                    input.tick_count % (1.0 / config.far_rate) as u64 == 0
                };

                if should_tick {
                    Some(be.id)
                } else {
                    None
                }
            })
            .collect()
    });

    // Record performance metrics
    crate::performance_monitoring::record_operation(start_time, input.block_entities.len(), pool.current_thread_count());

    BlockProcessResult { block_entities_to_tick }
}

/// Batch process multiple block entity collections in parallel with work-stealing
pub fn process_block_entities_batch(inputs: Vec<BlockInput>) -> Vec<BlockProcessResult> {
    let scheduler = WorkStealingScheduler::new(inputs);
    scheduler.execute(|input| process_block_entities(input))
}

/// Process block entities from JSON input and return JSON result
pub fn process_block_entities_json(json_input: &str) -> Result<String, String> {
    let input: BlockInput = serde_json::from_str(json_input)
        .map_err(|e| format!("Failed to parse JSON input: {}", e))?;
    
    let result = process_block_entities(input);
    
    serde_json::to_string(&result)
        .map_err(|e| format!("Failed to serialize result to JSON: {}", e))
}

/// Process block entities from binary input in batches for better JNI performance
pub fn process_block_entities_binary_batch(data: &[u8]) -> Result<Vec<u8>, String> {
    if data.is_empty() {
        return Ok(Vec::new());
    }

    let input = crate::binary::conversions::deserialize_block_input(data)
        .map_err(|e| format!("Failed to deserialize block input: {}", e))?;
    let result = process_block_entities(input);
    let out = crate::binary::conversions::serialize_block_result(&result)
        .map_err(|e| format!("Failed to serialize block result: {}", e))?;
    Ok(out)
}
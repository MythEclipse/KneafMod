use super::types::*;
use super::config::*;
use rayon::prelude::*;
use serde_json;

pub fn process_block_entities(input: BlockInput) -> BlockProcessResult {
    let config = BLOCK_CONFIG.read().unwrap();
    
    // Filter block entities based on distance and activity heuristic
    // Active entities: close radius (always tick), medium radius (reduced rate), far radius (minimal rate)
    let block_entities_to_tick: Vec<u64> = input.block_entities
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
        .collect();
    
    BlockProcessResult { block_entities_to_tick }
}

/// Batch process multiple block entity collections in parallel
pub fn process_block_entities_batch(inputs: Vec<BlockInput>) -> Vec<BlockProcessResult> {
    inputs.into_par_iter().map(|input| process_block_entities(input)).collect()
}

/// Process block entities from JSON input and return JSON result
pub fn process_block_entities_json(json_input: &str) -> Result<String, String> {
    let input: BlockInput = serde_json::from_str(json_input)
        .map_err(|e| format!("Failed to parse JSON input: {}", e))?;
    
    let result = process_block_entities(input);
    
    serde_json::to_string(&result)
        .map_err(|e| format!("Failed to serialize result to JSON: {}", e))
}
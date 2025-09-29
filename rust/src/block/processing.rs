use super::types::*;
use rayon::prelude::*;
use serde_json;

pub fn process_block_entities(input: BlockInput) -> BlockProcessResult {
    // Always tick all block entities to prevent functional issues
    // Use parallel processing for better performance with large numbers
    let block_entities_to_tick: Vec<u64> = input.block_entities.par_iter().map(|be| be.id).collect();
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
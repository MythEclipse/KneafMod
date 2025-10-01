use super::types::*;
use super::config::*;
use rayon::prelude::*;
use serde_json;

pub fn process_mob_ai(input: MobInput) -> MobProcessResult {
    let config = AI_CONFIG.read().unwrap();
    
    // Filter mobs based on distance and activity level using parallel processing
    let results: Vec<(Option<u64>, Option<u64>)> = input.mobs
        .par_iter()
        .map(|mob| {
            // Passive mobs: disable AI if far away
            if mob.is_passive && mob.distance > config.passive_disable_distance {
                (Some(mob.id), None)
            }
            // Hostile mobs: simplify AI if medium distance
            else if !mob.is_passive && mob.distance > config.hostile_simplify_distance {
                (None, Some(mob.id))
            }
            // Active mobs: keep full AI
            else {
                (None, None)
            }
        })
        .collect();

    // Separate the results into two vectors
    let mut mobs_to_disable_ai = Vec::new();
    let mut mobs_to_simplify_ai = Vec::new();
    
    for (disable_id, simplify_id) in results {
        if let Some(id) = disable_id {
            mobs_to_disable_ai.push(id);
        }
        if let Some(id) = simplify_id {
            mobs_to_simplify_ai.push(id);
        }
    }

    MobProcessResult {
        mobs_to_disable_ai,
        mobs_to_simplify_ai
    }
}

/// Batch process multiple mob collections in parallel
pub fn process_mob_ai_batch(inputs: Vec<MobInput>) -> Vec<MobProcessResult> {
    inputs.into_par_iter().map(|input| process_mob_ai(input)).collect()
}

/// Process mob AI from JSON input and return JSON result
pub fn process_mob_ai_json(json_input: &str) -> Result<String, String> {
    let input: MobInput = serde_json::from_str(json_input)
        .map_err(|e| format!("Failed to parse JSON input: {}", e))?;
    
    let result = process_mob_ai(input);
    
    serde_json::to_string(&result)
        .map_err(|e| format!("Failed to serialize result to JSON: {}", e))
}

/// Process mob AI from binary input in batches for better JNI performance
pub fn process_mob_ai_binary_batch(data: &[u8]) -> Result<Vec<u8>, String> {
    if data.is_empty() {
        return Ok(Vec::new());
    }

    // Deserialize manual mob input, process, and serialize result
    let input = crate::binary::conversions::deserialize_mob_input(data)
        .map_err(|e| format!("Failed to deserialize mob input: {}", e))?;
    let result = process_mob_ai(input);
    let out = crate::binary::conversions::serialize_mob_result(&result)
        .map_err(|e| format!("Failed to serialize mob result: {}", e))?;
    Ok(out)
}
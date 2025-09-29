use super::types::*;
use rayon::prelude::*;
use std::collections::HashMap;
use serde_json;

pub fn process_entities(input: Input) -> ProcessResult {
    // Group entities by type for more efficient processing
    let mut entities_by_type: HashMap<String, Vec<&EntityData>> = HashMap::new();

    for entity in &input.entities {
        entities_by_type.entry(entity.entity_type.clone())
            .or_insert_with(Vec::new)
            .push(entity);
    }

    // Process each group in parallel
    let entities_to_tick: Vec<u64> = entities_by_type.into_par_iter()
        .flat_map(|(_entity_type, entities)| {
            entities.into_par_iter().map(|entity| entity.id)
        })
        .collect();

    ProcessResult { entities_to_tick }
}

/// Batch process multiple entity collections in parallel
pub fn process_entities_batch(inputs: Vec<Input>) -> Vec<ProcessResult> {
    inputs.into_par_iter().map(|input| process_entities(input)).collect()
}

/// Process entities from JSON input and return JSON result
pub fn process_entities_json(json_input: &str) -> Result<String, String> {
    let input: Input = serde_json::from_str(json_input)
        .map_err(|e| format!("Failed to parse JSON input: {}", e))?;
    
    let result = process_entities(input);
    
    serde_json::to_string(&result)
        .map_err(|e| format!("Failed to serialize result to JSON: {}", e))
}
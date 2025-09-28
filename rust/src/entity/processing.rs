use super::types::*;
use rayon::prelude::*;
use std::collections::HashMap;

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
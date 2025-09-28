use super::types::*;
use rayon::prelude::*;

pub fn process_entities(input: Input) -> ProcessResult {
    // Use parallel processing for better performance with large numbers of entities
    let entities_to_tick: Vec<u64> = input.entities.par_iter().map(|entity| entity.id).collect();
    ProcessResult { entities_to_tick }
}

/// Batch process multiple entity collections in parallel
pub fn process_entities_batch(inputs: Vec<Input>) -> Vec<ProcessResult> {
    inputs.into_par_iter().map(|input| process_entities(input)).collect()
}
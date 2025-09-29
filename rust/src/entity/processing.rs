use super::types::*;
use rayon::prelude::*;
use std::collections::HashMap;
use serde_json;
use crate::memory_pool::{get_thread_local_pool, PooledVec};
use crate::parallelism::WorkStealingScheduler;
use std::time::Instant;

pub fn process_entities(input: Input) -> ProcessResult {
    let start_time = Instant::now();

    // Better estimate: use entity diversity analysis for more accurate memory allocation
    let entity_count = input.entities.len();
    let estimated_types = if entity_count > 500 {
        (entity_count / 15).max(5).min(80) // More conservative estimate for large datasets
    } else if entity_count > 100 {
        (entity_count / 12).max(8).min(50) // Balanced estimate for medium datasets
    } else {
        (entity_count / 8).max(3).min(25) // More generous estimate for small datasets
    };
    let mut entities_by_type: HashMap<String, Vec<&EntityData>> = HashMap::with_capacity(estimated_types);

    // Pre-allocate vectors with better size estimates
    for entity in &input.entities {
        entities_by_type.entry(entity.entity_type.clone())
            .or_insert_with(|| Vec::with_capacity(entity_count / estimated_types + 5))
            .push(entity);
    }

    // Process each group in parallel using optimized batching
    let pool_manager = get_thread_local_pool().expect("Memory pool not initialized");
    
    // Better estimate: only entities that actually need processing
    let estimated_active_entities = if entity_count > 1000 {
        (entity_count * 3 / 4).max(100) // Assume 75% need processing in large datasets
    } else {
        entity_count // Assume all need processing in small datasets
    };
    let mut entities_to_tick = pool_manager.get_vec_u64(estimated_active_entities);

    // Collect into a temporary Vec using rayon then move into pooled vec
    let temp: Vec<u64> = entities_by_type.into_par_iter()
        .flat_map(|(_entity_type, entities)| {
            entities.into_par_iter().map(|entity| entity.id)
        })
        .collect();

    // Move collected ids into pooled vector
    entities_to_tick.as_mut().extend_from_slice(&temp);

    // Record performance metrics (simplified)
    let _elapsed = start_time.elapsed();

    ProcessResult { entities_to_tick: entities_to_tick.take() }
}

/// Batch process multiple entity collections in parallel with work-stealing
pub fn process_entities_batch(inputs: Vec<Input>) -> Vec<ProcessResult> {
    let scheduler = WorkStealingScheduler::new(inputs);
    scheduler.execute(|input| process_entities(input))
}

/// Process entities from JSON input and return JSON result
pub fn process_entities_json(json_input: &str) -> Result<String, String> {
    let input: Input = serde_json::from_str(json_input)
        .map_err(|e| format!("Failed to parse JSON input: {}", e))?;
    
    let result = process_entities(input);
    
    serde_json::to_string(&result)
        .map_err(|e| format!("Failed to serialize result to JSON: {}", e))
}
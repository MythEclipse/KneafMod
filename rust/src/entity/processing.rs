use super::types::*;
use rayon::prelude::*;
use std::collections::HashMap;
use serde_json;
use crate::memory_pool::get_thread_local_pool;
use crate::parallelism::WorkStealingScheduler;
use std::time::Instant;
use crate::{log_error, logging::{PerformanceLogger, generate_trace_id}};
use crate::arena::{get_global_arena_pool, ScopedArena, ArenaVec};
use once_cell::sync::Lazy;

static ENTITY_PROCESSOR_LOGGER: Lazy<PerformanceLogger> = Lazy::new(|| PerformanceLogger::new("entity_processor"));

pub fn process_entities(input: Input) -> ProcessResult {
    let trace_id = generate_trace_id();
    ENTITY_PROCESSOR_LOGGER.log_info("process_start", &trace_id, &format!("process_entities called with {} entities", input.entities.len()));
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
    ENTITY_PROCESSOR_LOGGER.log_debug("entity_types_estimate", &trace_id, &format!("Estimated entity types: {}", estimated_types));
    
    let mut entities_by_type: HashMap<String, Vec<&EntityData>> = HashMap::with_capacity(estimated_types);

    // Pre-allocate vectors with better size estimates
    // Use scoped arena for temporary allocations during entity grouping
    let arena = ScopedArena::new(get_global_arena_pool());
    
    for entity in &input.entities {
        entities_by_type.entry(entity.entity_type.clone())
            .or_insert_with(|| Vec::with_capacity(entity_count / estimated_types + 5))
            .push(entity);
    }
    
    ENTITY_PROCESSOR_LOGGER.log_debug("entity_grouping", &trace_id, &format!("Grouped entities by type: {} groups", entities_by_type.len()));

    // Process each group in parallel using optimized batching
    let pool_manager = match get_thread_local_pool() {
        Some(pool) => {
            ENTITY_PROCESSOR_LOGGER.log_debug("memory_pool", &trace_id, "Successfully got thread local memory pool");
            pool
        },
        None => {
            ENTITY_PROCESSOR_LOGGER.log_error("memory_pool_error", &trace_id, "ERROR: Memory pool not initialized", "ENTITY_PROCESSING");
            panic!("Memory pool not initialized");
        }
    };
    
    // Better estimate: only entities that actually need processing
    let estimated_active_entities = if entity_count > 1000 {
        (entity_count * 3 / 4).max(100) // Assume 75% need processing in large datasets
    } else {
        entity_count // Assume all need processing in small datasets
    };
    ENTITY_PROCESSOR_LOGGER.log_debug("active_entities_estimate", &trace_id, &format!("Estimated active entities: {}", estimated_active_entities));
    
    
    let mut entities_to_tick = pool_manager.get_vec_u64(estimated_active_entities);

    // Use fold to avoid intermediate Vec allocation
    ENTITY_PROCESSOR_LOGGER.log_info("parallel_processing_start", &trace_id, "Starting parallel processing");
    let temp: Vec<u64> = entities_by_type.into_par_iter()
        .flat_map(|(_entity_type, entities)| {
            entities.into_par_iter().map(|entity| entity.id)
        })
        .fold(Vec::new, |mut acc, id| {
            acc.push(id);
            acc
        })
        .reduce(Vec::new, |mut acc, mut chunk| {
            acc.append(&mut chunk);
            acc
        });

    ENTITY_PROCESSOR_LOGGER.log_debug("parallel_processing_complete", &trace_id, &format!("Parallel processing completed, collected {} entities", temp.len()));

    // Move collected ids into pooled vector
    entities_to_tick.as_mut().extend_from_slice(&temp);
    // Record performance metrics (simplified)
    let elapsed = start_time.elapsed();
    ENTITY_PROCESSOR_LOGGER.log_info("processing_complete", &trace_id, &format!("Processing completed in {:?}", elapsed));

    let result = ProcessResult { entities_to_tick: entities_to_tick.take() };
    ENTITY_PROCESSOR_LOGGER.log_info("result_return", &trace_id, &format!("Returning result with {} entities_to_tick", result.entities_to_tick.len()));
    result
}

/// Batch process multiple entity collections in parallel with work-stealing
pub fn process_entities_batch(inputs: Vec<Input>) -> Vec<ProcessResult> {
    let scheduler = WorkStealingScheduler::new(inputs);
    scheduler.execute(|input| process_entities(input))
}

/// Process entities from JSON input and return JSON result
pub fn process_entities_json(json_input: &str) -> Result<String, String> {
    let trace_id = generate_trace_id();
    ENTITY_PROCESSOR_LOGGER.log_info("json_process_start", &trace_id, &format!("process_entities_json called with input length: {}", json_input.len()));
    
    // Log input preview for debugging
    let preview_len = json_input.len().min(100);
    ENTITY_PROCESSOR_LOGGER.log_debug("json_input_preview", &trace_id, &format!("Input preview: {}", &json_input[..preview_len]));
    
    let input: Input = serde_json::from_str(json_input)
        .map_err(|e| {
            ENTITY_PROCESSOR_LOGGER.log_error("json_parse_error", &trace_id, &format!("ERROR: Failed to parse JSON input: {}", e), "ENTITY_PROCESSING");
            format!("Failed to parse JSON input: {}", e)
        })?;
    
    ENTITY_PROCESSOR_LOGGER.log_info("json_parse_success", &trace_id, &format!("Successfully parsed JSON input, entities count: {}", input.entities.len()));
    
    let result = process_entities(input);
    
    ENTITY_PROCESSOR_LOGGER.log_info("json_process_complete", &trace_id, &format!("process_entities completed, entities_to_tick count: {}", result.entities_to_tick.len()));
    
    let json_result = serde_json::to_string(&result)
        .map_err(|e| {
            ENTITY_PROCESSOR_LOGGER.log_error("json_serialize_error", &trace_id, &format!("ERROR: Failed to serialize result to JSON: {}", e), "ENTITY_PROCESSING");
            format!("Failed to serialize result to JSON: {}", e)
        })?;
    
    ENTITY_PROCESSOR_LOGGER.log_info("json_serialize_success", &trace_id, &format!("Successfully serialized result, output length: {}", json_result.len()));
    Ok(json_result)
}
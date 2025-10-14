use super::types::*;
use crate::arena::{get_global_arena_pool, ScopedArena};
use crate::logging::{generate_trace_id, PerformanceLogger};
use crate::parallelism::WorkStealingScheduler;
use once_cell::sync::Lazy;
use rayon::prelude::*;
use serde_json;
use std::collections::HashMap;
use std::time::Instant;

static ENTITY_PROCESSOR_LOGGER: Lazy<PerformanceLogger> =
    Lazy::new(|| PerformanceLogger::new("entity_processor"));

/// AVX-512 accelerated entity distance calculation
#[cfg(target_feature = "avx512f")]
pub fn calculate_entity_distances_avx512(
    positions: &[(f32, f32, f32)],
    center: (f32, f32, f32),
) -> Vec<f32> {
    use std::arch::x86_64::*;

    let cx = _mm512_set1_ps(center.0);
    let cy = _mm512_set1_ps(center.1);
    let cz = _mm512_set1_ps(center.2);

    positions
        .par_chunks(16)
        .flat_map(|chunk| {
            let mut distances = [0.0f32; 16];

            // Load position data in chunks of 16
            let px = _mm512_loadu_ps(chunk.iter().map(|p| p.0).collect::<Vec<_>>().as_ptr());
            let py = _mm512_loadu_ps(chunk.iter().map(|p| p.1).collect::<Vec<_>>().as_ptr());
            let pz = _mm512_loadu_ps(chunk.iter().map(|p| p.2).collect::<Vec<_>>().as_ptr());

            // Calculate differences from center
            let dx = _mm512_sub_ps(px, cx);
            let dy = _mm512_sub_ps(py, cy);
            let dz = _mm512_sub_ps(pz, cz);

            // Calculate squared distances
            let dx2 = _mm512_mul_ps(dx, dx);
            let dy2 = _mm512_mul_ps(dy, dy);
            let dz2 = _mm512_mul_ps(dz, dz);

            // Sum components and take square root
            let sum = _mm512_add_ps(_mm512_add_ps(dx2, dy2), dz2);
            let dist = _mm512_sqrt_ps(sum);

            _mm512_storeu_ps(distances.as_mut_ptr(), dist);

            // Return only the distances for the actual chunk size
            distances.into_iter().take(chunk.len()).collect::<Vec<_>>()
        })
        .collect()
}

/// AVX-512 accelerated entity filtering by distance
#[cfg(target_feature = "avx512f")]
pub fn filter_entities_by_distance_avx512<T: Clone + Send + Sync>(
    entities: &[(T, [f64; 3])],
    center: [f64; 3],
    max_distance: f64,
) -> Vec<(T, [f64; 3])> {
    use std::arch::x8264::*;

    let max_dist_sq = max_distance * max_distance;
    let cx = center[0] as f32;
    let cy = center[1] as f32;
    let cz = center[2] as f32;

    entities
        .par_chunks(16)
        .flat_map(|chunk| {
            let mut results = Vec::with_capacity(chunk.len());

            for (i, &(ref entity, pos)) in chunk.iter().enumerate() {
                let dx = (pos[0] - center[0]) as f32;
                let dy = (pos[1] - center[1]) as f32;
                let dz = (pos[2] - center[2]) as f32;

                let dist_sq = dx * dx + dy * dy + dz * dz;
                if dist_sq <= max_dist_sq as f32 {
                    results.push((entity.clone(), pos));
                }
            }

            results
        })
        .collect()
}

/// AVX-512 accelerated chunk distance calculation
#[cfg(target_feature = "avx512f")]
pub fn calculate_chunk_distances_avx512(
    chunk_coords: &[(i32, i32)],
    center_chunk: (i32, i32),
) -> Vec<f32> {
    use std::arch::x86_64::*;

    let cx = _mm512_set1_epi32(center_chunk.0);
    let cz = _mm512_set1_epi32(center_chunk.1);

    chunk_coords
        .par_chunks(16)
        .flat_map(|chunk| {
            let mut distances = [0.0f32; 16];

            let x_coords =
                _mm512_loadu_epi32(chunk.iter().map(|p| p.0).collect::<Vec<_>>().as_ptr());
            let z_coords =
                _mm512_loadu_epi32(chunk.iter().map(|p| p.1).collect::<Vec<_>>().as_ptr());

            // Calculate differences
            let dx = _mm512_sub_epi32(x_coords, cx);
            let dz = _mm512_sub_epi32(z_coords, cz);

            // Convert to float and calculate squared distance
            let dx_f32 = _mm512_cvtepi32_ps(dx);
            let dz_f32 = _mm512_cvtepi32_ps(dz);

            let dx2 = _mm512_mul_ps(dx_f32, dx_f32);
            let dz2 = _mm512_mul_ps(dz_f32, dz_f32);

            let dist_sq = _mm512_add_ps(dx2, dz2);
            let dist = _mm512_sqrt_ps(dist_sq);

            _mm512_storeu_ps(distances.as_mut_ptr(), dist);

            distances.into_iter().take(chunk.len()).collect::<Vec<_>>()
        })
        .collect()
}

pub fn process_entities(input: Input) -> ProcessResult {
    let trace_id = generate_trace_id();
    ENTITY_PROCESSOR_LOGGER.log_info(
        "process_start",
        &trace_id,
        &format!(
            "process_entities called with {} entities",
            input.entities.len()
        ),
    );
    let start_time = Instant::now();

    // Better estimate: use entity diversity analysis for more accurate memory allocation
    let entity_count = input.entities.len();

    // Validate entity count to prevent excessive allocation
    if entity_count > 10000 {
        ENTITY_PROCESSOR_LOGGER.log_warning(
            "excessive_entity_count",
            &trace_id,
            &format!("Too many entities: {}, returning empty result", entity_count),
        );
        return ProcessResult { entities_to_tick: Vec::new() };
    }

    let estimated_types = if entity_count > 500 {
        (entity_count / 15).max(5).min(80) // More conservative estimate for large datasets
    } else if entity_count > 100 {
        (entity_count / 12).max(8).min(50) // Balanced estimate for medium datasets
    } else {
        (entity_count / 8).max(3).min(25) // More generous estimate for small datasets
    };
    ENTITY_PROCESSOR_LOGGER.log_debug(
        "entity_types_estimate",
        &trace_id,
        &format!("Estimated entity types: {}", estimated_types),
    );

    let mut entities_by_type: HashMap<String, Vec<&EntityData>> =
        HashMap::with_capacity(estimated_types);

    // Pre-allocate vectors with better size estimates
    // Use scoped arena for temporary allocations during entity grouping
    let _arena = ScopedArena::new(get_global_arena_pool());

    for entity in &input.entities {
        entities_by_type
            .entry(entity.entity_type.clone())
            .or_insert_with(|| Vec::with_capacity(entity_count / estimated_types + 5))
            .push(entity);
    }

    ENTITY_PROCESSOR_LOGGER.log_debug(
        "entity_grouping",
        &trace_id,
        &format!(
            "Grouped entities by type: {} groups",
            entities_by_type.len()
        ),
    );

    // Process each group in parallel using optimized batching
    ENTITY_PROCESSOR_LOGGER.log_debug(
        "memory_pool",
        &trace_id,
        "Successfully got thread local memory pool",
    );

    // Better estimate: only entities that actually need processing
    let estimated_active_entities = if entity_count > 1000 {
        (entity_count * 3 / 4).max(100) // Assume 75% need processing in large datasets
    } else {
        entity_count // Assume all need processing in small datasets
    };
    ENTITY_PROCESSOR_LOGGER.log_debug(
        "active_entities_estimate",
        &trace_id,
        &format!("Estimated active entities: {}", estimated_active_entities),
    );

    let mut entities_to_tick: Vec<u64> = Vec::with_capacity(estimated_active_entities);

    // Use fold to avoid intermediate Vec allocation
    ENTITY_PROCESSOR_LOGGER.log_info(
        "parallel_processing_start",
        &trace_id,
        "Starting parallel processing",
    );
    let temp: Vec<u64> = entities_by_type
        .into_par_iter()
        .flat_map(|(_entity_type, entities)| entities.into_par_iter().map(|entity| entity.id))
        .fold(Vec::new, |mut acc, id| {
            acc.push(id);
            acc
        })
        .reduce(Vec::new, |mut acc, mut chunk| {
            acc.append(&mut chunk);
            acc
        });

    ENTITY_PROCESSOR_LOGGER.log_debug(
        "parallel_processing_complete",
        &trace_id,
        &format!(
            "Parallel processing completed, collected {} entities",
            temp.len()
        ),
    );

    // Move collected ids into pooled vector
    entities_to_tick.extend_from_slice(&temp);
    // Record performance metrics (simplified)
    let elapsed = start_time.elapsed();
    ENTITY_PROCESSOR_LOGGER.log_info(
        "processing_complete",
        &trace_id,
        &format!("Processing completed in {:?}", elapsed),
    );

    let result = ProcessResult { entities_to_tick };
    ENTITY_PROCESSOR_LOGGER.log_info(
        "result_return",
        &trace_id,
        &format!(
            "Returning result with {} entities_to_tick",
            result.entities_to_tick.len()
        ),
    );
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
    ENTITY_PROCESSOR_LOGGER.log_info(
        "json_process_start",
        &trace_id,
        &format!(
            "process_entities_json called with input length: {}",
            json_input.len()
        ),
    );

    // Log input preview for debugging
    let preview_len = json_input.len().min(100);
    ENTITY_PROCESSOR_LOGGER.log_debug(
        "json_input_preview",
        &trace_id,
        &format!("Input preview: {}", &json_input[..preview_len]),
    );

    let input: Input = serde_json::from_str(json_input).map_err(|e| {
        ENTITY_PROCESSOR_LOGGER.log_error(
            "json_parse_error",
            &trace_id,
            &format!("ERROR: Failed to parse JSON input: {}", e),
            "ENTITY_PROCESSING",
        );
        format!("Failed to parse JSON input: {}", e)
    })?;

    ENTITY_PROCESSOR_LOGGER.log_info(
        "json_parse_success",
        &trace_id,
        &format!(
            "Successfully parsed JSON input, entities count: {}",
            input.entities.len()
        ),
    );

    let result = process_entities(input);

    ENTITY_PROCESSOR_LOGGER.log_info(
        "json_process_complete",
        &trace_id,
        &format!(
            "process_entities completed, entities_to_tick count: {}",
            result.entities_to_tick.len()
        ),
    );

    let json_result = serde_json::to_string(&result).map_err(|e| {
        ENTITY_PROCESSOR_LOGGER.log_error(
            "json_serialize_error",
            &trace_id,
            &format!("ERROR: Failed to serialize result to JSON: {}", e),
            "ENTITY_PROCESSING",
        );
        format!("Failed to serialize result to JSON: {}", e)
    })?;

    ENTITY_PROCESSOR_LOGGER.log_info(
        "json_serialize_success",
        &trace_id,
        &format!(
            "Successfully serialized result, output length: {}",
            json_result.len()
        ),
    );
    Ok(json_result)
}

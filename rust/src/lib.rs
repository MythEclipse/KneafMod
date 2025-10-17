//! JNI implementation for KneafCore performance optimizations
//! Provides real entity processing optimizations using SIMD and mathematical acceleration

use jni::JNIEnv;
use jni::objects::{JClass, JString, JDoubleArray, JBooleanArray};
use jni::sys::{jint, jboolean};
use std::ffi::c_void;
use serde_json;

// Import our performance modules
mod performance;

/// JNI_OnLoad - return the JNI version.
#[no_mangle]
pub extern "C" fn JNI_OnLoad(_vm: *mut jni::sys::JavaVM, _reserved: *mut c_void) -> jint {
    jni::sys::JNI_VERSION_1_6
}

/// JNI functions for OptimizationInjector entity processing optimizations
/// These functions provide real entity processing optimizations using native Rust performance

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_OptimizationInjector_rustperf_1calculate_1entity_1performance(
    mut env: JNIEnv,
    _class: JClass,
    entity_count: jint,
    level_dimension: JString,
) -> jint {
    let dimension_str: String = env.get_string(&level_dimension).expect("Couldn't get java string!").into();
    let target_processed = calculate_optimal_entity_target(entity_count, &dimension_str);
    if let Ok(mut stats) = performance::ENTITY_PROCESSING_STATS.lock() {
        stats.record_processing(&dimension_str, target_processed as u64, 0, 0);
    }
    target_processed as jint
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_OptimizationInjector_rustperf_1tick_1entity<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    entity_data: JDoubleArray,
    on_ground: jboolean,
) -> JDoubleArray<'a> {
    let start_time = std::time::Instant::now();

    let mut data_buf = [0.0; 6];
    env.get_double_array_region(&entity_data, 0, &mut data_buf).expect("Couldn't get entity data region");

    let result_data = performance::tick_entity_physics(&data_buf, on_ground != 0);

    let output_array = env.new_double_array(6).expect("Couldn't create new double array");
    env.set_double_array_region(&output_array, 0, &result_data).expect("Couldn't set result array region");

    let elapsed = start_time.elapsed().as_nanos() as u64;
    if let Ok(mut stats) = performance::ENTITY_PROCESSING_STATS.lock() {
        stats.native_optimizations_applied += 1;
        stats.total_calculation_time_ns += elapsed;
    }

    output_array
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_OptimizationInjector_rustperf_1get_1performance_1stats<'a>(
    env: JNIEnv<'a>,
    _class: JClass,
) -> JString<'a> {
    let stats_str = if let Ok(stats) = performance::ENTITY_PROCESSING_STATS.lock() {
        stats.get_summary()
    } else {
        "Failed to get performance stats".to_string()
    };
    
    env.new_string(stats_str).expect("Couldn't create java string!")
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_OptimizationInjector_rustperf_1reset_1performance_1stats(_env: JNIEnv, _class: JClass) {
    if let Ok(mut stats) = performance::ENTITY_PROCESSING_STATS.lock() {
        *stats = performance::EntityProcessingStats::default();
    }
}

/// Helper function to calculate optimal entity processing target
fn calculate_optimal_entity_target(entity_count: i32, dimension: &str) -> i32 {
    let base_rate = match dimension {
        "minecraft:overworld" => 0.85,
        "minecraft:the_nether" => 0.75,
        "minecraft:the_end" => 0.95,
        _ => 0.80,
    };
    (entity_count as f64 * base_rate).ceil() as i32
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_OptimizationInjector_rustperf_1batch_1tick_1entities<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    entity_data: JDoubleArray,
    on_grounds: JBooleanArray,
    entity_count: jint,
    level_dimension: JString,
) -> JDoubleArray<'a> {
    let dimension_str: String = env.get_string(&level_dimension).expect("Couldn't get java string!").into();
    let mut data_buf = vec![0.0; (entity_count * 6) as usize];
    env.get_double_array_region(&entity_data, 0, &mut data_buf).expect("Couldn't get entity data region");
    let mut entities: Vec<[f64;6]> = data_buf.chunks_exact(6).map(|chunk| [chunk[0], chunk[1], chunk[2], chunk[3], chunk[4], chunk[5]]).collect();
    let mut on_ground_buf = vec![0i8; entity_count as usize];
    env.get_boolean_array_region(&on_grounds, 0, &mut on_ground_buf).expect("Couldn't get on grounds region");
    let on_grounds_bool: Vec<bool> = on_ground_buf.into_iter().map(|b| b != 0).collect();
    performance::batch_tick_entities(&mut entities, &on_grounds_bool, &dimension_str);
    let flattened: Vec<f64> = entities.into_iter().flatten().collect();
    let output_array = env.new_double_array((entity_count * 6) as i32).expect("Couldn't create new double array");
    env.set_double_array_region(&output_array, 0, &flattened).expect("Couldn't set result array region");
    output_array
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_OptimizationInjector_rustperf_1parallel_1a_1star<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    grid_json: JString,
    queries_json: JString,
) -> JString<'a> {
    let grid_str: String = env.get_string(&grid_json).expect("Couldn't get grid json").into();
    let queries_str: String = env.get_string(&queries_json).expect("Couldn't get queries json").into();
    let grid: Vec<Vec<bool>> = serde_json::from_str(&grid_str).expect("Invalid grid json");
    let queries: Vec<performance::PathQuery> = serde_json::from_str(&queries_str).expect("Invalid queries json");
    let results = performance::parallel_a_star(&grid, &queries);
    let results_json = serde_json::to_string(&results).expect("Failed to serialize results");
    env.new_string(results_json).expect("Couldn't create java string!")
}
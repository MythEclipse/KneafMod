//! JNI implementation for KneafCore performance optimizations
//! Provides real entity processing optimizations using SIMD and mathematical acceleration

use jni::JNIEnv;
use jni::objects::{JClass, JString, JDoubleArray};
use jni::sys::{jint, jboolean};
use std::ffi::c_void;

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
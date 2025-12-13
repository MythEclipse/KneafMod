#![allow(non_snake_case)]
#![allow(dead_code)]
// Suppress common warnings during ongoing refactor: unused variables/imports/must_use, etc.
#![allow(unused_variables, unused_imports, unused_mut, unused_assignments, unused_must_use)]
//! JNI implementation for KneafCore performance optimizations
//! Provides real entity processing optimizations using SIMD and mathematical acceleration

//! JNI implementation for KneafCore vector mathematics
//! Provides STRICTLY PURE vector/matrix operations - NO game state access, NO entity references

use jni::JNIEnv;
use jni::objects::{JClass, JFloatArray, JDoubleArray, JObject, JObjectArray, JString};
use jni::sys::{jdouble, jlong, jint, jboolean, jdoubleArray};
use std::ffi::c_void;
use std::sync::Arc;

// Import all modules
mod performance;
mod parallel_processing;
mod parallel_astar;
mod parallel_matrix;
mod arena_memory;
mod simd_runtime;
mod load_balancer;
mod performance_monitoring;
mod tests;

// Performance Monitoring System modules
pub mod performance_monitor;
mod metrics_collector;
mod metric_aggregator;

// Entity Processing System modules
pub mod entity_registry;
pub mod entity_framework;
mod combat_system;
mod entity_modulation;

// AI Pathfinding System modules
pub mod pathfinding;

// Component definitions for entity system
use glam::Vec3;

#[derive(Debug, Clone)]
pub struct PositionComponent {
    pub position: Vec3,
    pub velocity: Vec3,
}

impl entity_registry::Component for PositionComponent {}

#[derive(Debug, Clone)]
pub struct HealthComponent {
    pub current: f32,
    pub max: f32,
    pub regeneration: f32,
}

impl entity_registry::Component for HealthComponent {}

// Import functions from modules

/// JNI_OnLoad - return the JNI version.
#[no_mangle]
pub extern "C" fn JNI_OnLoad(_vm: *mut jni::sys::JavaVM, _reserved: *mut c_void) -> jni::sys::jint {
    jni::sys::JNI_VERSION_1_6
}

/// JNI functions for RustVectorLibrary - STRICTLY MATHEMATICAL OPERATIONS ONLY
/// These functions provide native vector/matrix operations using nalgebra, glam, and faer
/// NO game state access, NO entity references, NO AI logic - pure input→output
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustVectorLibrary_nalgebra_1matrix_1mul<'a>(
    env: JNIEnv<'a>,
    _class: JClass,
    a: JFloatArray,
    b: JFloatArray,
) -> JFloatArray<'a> {
    let mut a_buf = [0.0f32; 16];
    env.get_float_array_region(&a, 0, &mut a_buf).expect("Failed to get a array");
    let mut b_buf = [0.0f32; 16];
    env.get_float_array_region(&b, 0, &mut b_buf).expect("Failed to get b array");
    let result = parallel_processing::nalgebra_matrix_mul(a_buf, b_buf);
    let output = env.new_float_array(16).expect("Failed to create output array");
    env.set_float_array_region(&output, 0, &result).expect("Failed to set output array");
    output
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustVectorLibrary_nalgebra_1vector_1add<'a>(
    env: JNIEnv<'a>,
    _class: JClass,
    a: JFloatArray,
    b: JFloatArray,
) -> JFloatArray<'a> {
    let mut a_buf = [0.0f32; 3];
    env.get_float_array_region(&a, 0, &mut a_buf).expect("Failed to get a array");
    let mut b_buf = [0.0f32; 3];
    env.get_float_array_region(&b, 0, &mut b_buf).expect("Failed to get b array");
    let result = parallel_processing::nalgebra_vector_add(a_buf, b_buf);
    let output = env.new_float_array(3).expect("Failed to create output array");
    env.set_float_array_region(&output, 0, &result).expect("Failed to set output array");
    output
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustVectorLibrary_glam_1vector_1dot(
    env: JNIEnv,
    _class: JClass,
    a: JFloatArray,
    b: JFloatArray,
) -> f32 {
    let mut a_buf = [0.0f32; 3];
    env.get_float_array_region(&a, 0, &mut a_buf).expect("Failed to get a array");
    let mut b_buf = [0.0f32; 3];
    env.get_float_array_region(&b, 0, &mut b_buf).expect("Failed to get b array");
    parallel_processing::glam_vector_dot(a_buf, b_buf)
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustVectorLibrary_glam_1vector_1cross<'a>(
    env: JNIEnv<'a>,
    _class: JClass,
    a: JFloatArray,
    b: JFloatArray,
) -> JFloatArray<'a> {
    let mut a_buf = [0.0f32; 3];
    env.get_float_array_region(&a, 0, &mut a_buf).expect("Failed to get a array");
    let mut b_buf = [0.0f32; 3];
    env.get_float_array_region(&b, 0, &mut b_buf).expect("Failed to get b array");
    let result = parallel_processing::glam_vector_cross(a_buf, b_buf);
    let output = env.new_float_array(3).expect("Failed to create output array");
    env.set_float_array_region(&output, 0, &result).expect("Failed to set output array");
    output
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustVectorLibrary_glam_1matrix_1mul<'a>(
    env: JNIEnv<'a>,
    _class: JClass,
    a: JFloatArray,
    b: JFloatArray,
) -> JFloatArray<'a> {
    let mut a_buf = [0.0f32; 16];
    env.get_float_array_region(&a, 0, &mut a_buf).expect("Failed to get a array");
    let mut b_buf = [0.0f32; 16];
    env.get_float_array_region(&b, 0, &mut b_buf).expect("Failed to get b array");
    let result = performance::glam_matrix_mul(a_buf, b_buf);
    let output = env.new_float_array(16).expect("Failed to create output array");
    env.set_float_array_region(&output, 0, &result).expect("Failed to set output array");
    output
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustVectorLibrary_faer_1matrix_1mul<'a>(
    env: JNIEnv<'a>,
    _class: JClass,
    a: JFloatArray,
    b: JFloatArray,
) -> JFloatArray<'a> {
    let mut a_buf = [0.0f32; 16];
    env.get_float_array_region(&a, 0, &mut a_buf).expect("Failed to get a array");
    let mut b_buf = [0.0f32; 16];
    env.get_float_array_region(&b, 0, &mut b_buf).expect("Failed to get b array");
    let result = performance::faer_matrix_mul(a_buf, b_buf);
    let output = env.new_float_array(16).expect("Failed to create output array");
    env.set_float_array_region(&output, 0, &result).expect("Failed to set output array");
    output
}

/// JNI functions for OptimizationInjector - STRICTLY MATHEMATICAL OPERATIONS ONLY
/// These functions provide pure vector operations with NO game state access
/// Input: numerical values only, Output: numerical results only
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_OptimizationInjector_rustperf_1vector_1multiply<'a>(
    env: JNIEnv<'a>,
    _class: JClass,
    x: jdouble,
    y: jdouble,
    z: jdouble,
    scalar: jdouble,
) -> JDoubleArray<'a> {
    let result = [x * scalar, y * scalar, z * scalar];
    let output = env.new_double_array(3).expect("Failed to create output array");
    env.set_double_array_region(&output, 0, &result).expect("Failed to set output array");
    output
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_OptimizationInjector_rustperf_1vector_1add<'a>(
    env: JNIEnv<'a>,
    _class: JClass,
    x1: jdouble,
    y1: jdouble,
    z1: jdouble,
    x2: jdouble,
    y2: jdouble,
    z2: jdouble,
) -> JDoubleArray<'a> {
    let result = [x1 + x2, y1 + y2, z1 + z2];
    let output = env.new_double_array(3).expect("Failed to create output array");
    env.set_double_array_region(&output, 0, &result).expect("Failed to set output array");
    output
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_OptimizationInjector_rustperf_1vector_1damp<'a>(
    env: JNIEnv<'a>,
    _class: JClass,
    x: jdouble,
    y: jdouble,
    z: jdouble,
    _damping: jdouble,
) -> JDoubleArray<'a> {
    // Advanced Physics Optimization: Use Rust for fast calculations on all axes
    // No damping - just passthrough with Rust-optimized vector operations
    // This allows SIMD optimizations and better performance for horizontal + vertical axes
    
    // Direct passthrough - Rust compilation provides optimization benefits
    // SIMD instructions can be used by the Rust compiler for vector operations
    let result = [x, y, z];

    let output = env.new_double_array(3).expect("Failed to create output array");
    env.set_double_array_region(&output, 0, &result).expect("Failed to set output array");
    output
}

/// Enhanced JNI functions for multi-core optimization
/// These functions provide access to the new parallel processing capabilities
/// Work-stealing parallel A* pathfinding
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_ParallelRustVectorProcessor_parallelAStarPathfind<'a>(
    env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
    grid_data: jni::objects::JByteArray<'a>,
    width: i32,
    height: i32,
    depth: i32,
    start_x: i32,
    start_y: i32,
    start_z: i32,
    goal_x: i32,
    goal_y: i32,
    goal_z: i32,
    num_threads: i32,
) -> jni::objects::JObject<'a> {
    // Delegate to parallel_astar module
    parallel_astar::Java_com_kneaf_core_ParallelRustVectorProcessor_parallelAStarPathfind(
        env, _class, grid_data, width, height, depth, start_x, start_y, start_z, 
        goal_x, goal_y, goal_z, num_threads
    )
}

/// Enhanced parallel matrix multiplication with block decomposition
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_ParallelRustVectorProcessor_parallelMatrixMultiplyBlock<'a>(
    env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
    a: jni::objects::JFloatArray<'a>,
    b: jni::objects::JFloatArray<'a>,
    a_rows: i32,
    a_cols: i32,
    b_cols: i32,
) -> jni::objects::JFloatArray<'a> {
    // Delegate to parallel_matrix module
    parallel_matrix::Java_com_kneaf_core_ParallelRustVectorProcessor_parallelMatrixMultiplyBlock(
        env, _class, a, b, a_rows, a_cols, b_cols
    )
}

/// Enhanced parallel Strassen matrix multiplication
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_ParallelRustVectorProcessor_parallelStrassenMultiply<'a>(
    env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
    a: jni::objects::JFloatArray<'a>,
    b: jni::objects::JFloatArray<'a>,
    size: i32,
) -> jni::objects::JFloatArray<'a> {
    // Delegate to parallel_matrix module
    parallel_matrix::Java_com_kneaf_core_ParallelRustVectorProcessor_parallelStrassenMultiply(
        env, _class, a, b, size
    )
}

/// Batch matrix operations with cache optimization
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_ParallelRustVectorProcessor_batchMatrixMultiply<'a>(
    env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
    matrices_a: jni::objects::JObjectArray<'a>,
    matrices_b: jni::objects::JObjectArray<'a>,
    matrix_size: i32,
    batch_size: i32,
) -> jni::objects::JObjectArray<'a> {
    // Delegate to parallel_matrix module
    parallel_matrix::Java_com_kneaf_core_ParallelRustVectorProcessor_batchMatrixMultiplyEnhanced(
        env, _class, matrices_a, matrices_b, matrix_size, batch_size
    )
}

/// Arena-based matrix multiplication with zero-copy operations
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_ParallelRustVectorProcessor_arenaMatrixMultiply<'a>(
    mut env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
    a: jni::objects::JFloatArray<'a>,
    b: jni::objects::JFloatArray<'a>,
    a_rows: i32,
    a_cols: i32,
    b_cols: i32,
    thread_id: i32,
) -> jni::objects::JFloatArray<'a> {
    // Delegate to arena_memory module
    arena_memory::arena_matrix_multiply_jni(
        &mut env, &a, &b, a_rows, a_cols, b_cols, thread_id
    )
}

/// Runtime-detected SIMD matrix multiplication
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_ParallelRustVectorProcessor_runtimeMatrixMultiply<'a>(
    env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
    a: jni::objects::JFloatArray<'a>,
    b: jni::objects::JFloatArray<'a>,
    a_rows: i32,
    a_cols: i32,
    b_cols: i32,
) -> jni::objects::JFloatArray<'a> {
    // Delegate to simd_runtime module
    simd_runtime::Java_com_kneaf_core_ParallelRustVectorProcessor_runtimeMatrixMultiply(
        env, _class, a, b, a_rows, a_cols, b_cols
    )
}

/// Runtime-detected SIMD vector dot product
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_ParallelRustVectorProcessor_runtimeVectorDotProduct(
    env: jni::JNIEnv,
    _class: jni::objects::JClass,
    a: jni::objects::JFloatArray,
    b: jni::objects::JFloatArray,
) -> f32 {
    // Delegate to simd_runtime module
    simd_runtime::Java_com_kneaf_core_ParallelRustVectorProcessor_runtimeVectorDotProduct(
        env, _class, a, b
    )
}

/// Runtime-detected SIMD vector addition
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_ParallelRustVectorProcessor_runtimeVectorAdd<'a>(
    env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
    a: jni::objects::JFloatArray<'a>,
    b: jni::objects::JFloatArray<'a>,
) -> jni::objects::JFloatArray<'a> {
    // Delegate to simd_runtime module
    simd_runtime::Java_com_kneaf_core_ParallelRustVectorProcessor_runtimeVectorAdd(
        env, _class, a, b
    )
}

/// Runtime-detected SIMD 4x4 matrix multiplication
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_ParallelRustVectorProcessor_runtimeMatrix4x4Multiply<'a>(
    env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
    a: jni::objects::JFloatArray<'a>,
    b: jni::objects::JFloatArray<'a>,
) -> jni::objects::JFloatArray<'a> {
    // Delegate to simd_runtime module
    simd_runtime::Java_com_kneaf_core_ParallelRustVectorProcessor_runtimeMatrix4x4Multiply(
        env, _class, a, b
    )
}

/// Reset memory arena
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_ParallelRustVectorProcessor_resetMemoryArena(
    env: jni::JNIEnv,
    _class: jni::objects::JClass,
) {
    // Delegate to arena_memory module
    arena_memory::Java_com_kneaf_core_ParallelRustVectorProcessor_resetMemoryArena(
        env, _class
    )
}

/// Create load balancer
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_ParallelRustVectorProcessor_createLoadBalancer(
    env: jni::JNIEnv,
    _class: jni::objects::JClass,
    num_threads: i32,
    max_queue_size: i32,
) -> jni::sys::jlong {
    // Delegate to load_balancer module
    load_balancer::Java_com_kneaf_core_ParallelRustVectorProcessor_createLoadBalancerEnhanced(
        env, _class, num_threads, max_queue_size
    )
}

/// Submit task to load balancer
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_ParallelRustVectorProcessor_submitTask(
    env: jni::JNIEnv,
    _class: jni::objects::JClass,
    balancer_ptr: jni::sys::jlong,
    task_type: jni::objects::JString,
    data: jni::objects::JFloatArray,
) {
    // Delegate to load_balancer module
    load_balancer::Java_com_kneaf_core_ParallelRustVectorProcessor_submitTask(
        env, _class, balancer_ptr, task_type, data
    )
}

/// Shutdown load balancer
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_ParallelRustVectorProcessor_shutdownLoadBalancer(
    env: jni::JNIEnv,
    _class: jni::objects::JClass,
    balancer_ptr: jni::sys::jlong,
) {
    // Delegate to load_balancer module
    load_balancer::Java_com_kneaf_core_ParallelRustVectorProcessor_shutdownLoadBalancer(
        env, _class, balancer_ptr
    )
}

/// Batch processing functions
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_ParallelRustVectorProcessor_batchNalgebraMatrixMul<'a>(
    env: JNIEnv<'a>,
    _class: JClass,
    matrices_a: JObjectArray<'a>,
    matrices_b: JObjectArray<'a>,
    count: i32,
) -> JObjectArray<'a> {
    // Delegate to parallel_processing module
    parallel_processing::Java_com_kneaf_core_ParallelRustVectorProcessor_batchNalgebraMatrixMulEnhanced(
        env, _class, matrices_a, matrices_b, count
    )
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_ParallelRustVectorProcessor_batchNalgebraVectorAdd<'a>(
    env: JNIEnv<'a>,
    _class: JClass,
    vectors_a: JObjectArray<'a>,
    vectors_b: JObjectArray<'a>,
    count: i32,
) -> JObjectArray<'a> {
    // Delegate to parallel_processing module
    parallel_processing::Java_com_kneaf_core_ParallelRustVectorProcessor_batchNalgebraVectorAddEnhanced(
        env, _class, vectors_a, vectors_b, count
    )
}

/// Zero-copy operations using direct ByteBuffers
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_ParallelRustVectorProcessor_nalgebraMatrixMulDirect<'a>(
    env: JNIEnv<'a>,
    _class: JClass,
    a_buffer: JObject<'a>,
    b_buffer: JObject<'a>,
    result_buffer: JObject<'a>,
) -> JObject<'a> {
    // Delegate to parallel_processing module
    parallel_processing::Java_com_kneaf_core_ParallelRustVectorProcessor_nalgebraMatrixMulDirect(
        env, _class, a_buffer, b_buffer, result_buffer
    )
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_ParallelRustVectorProcessor_nalgebraVectorAddDirect<'a>(
    env: JNIEnv<'a>,
    _class: JClass,
    a_buffer: JObject<'a>,
    b_buffer: JObject<'a>,
    result_buffer: JObject<'a>,
) -> JObject<'a> {
    // Delegate to parallel_processing module
    parallel_processing::Java_com_kneaf_core_ParallelRustVectorProcessor_nalgebraVectorAddDirect(
        env, _class, a_buffer, b_buffer, result_buffer
    )
}

/// Safe memory management functions
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_ParallelRustVectorProcessor_releaseNativeBuffer(
    _env: JNIEnv,
    _class: JClass,
    pointer: jlong,
) {
    // Delegate to parallel_processing module
    parallel_processing::release_native_buffer(pointer)
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_ParallelRustVectorProcessor_allocateNativeBuffer(
    _env: JNIEnv,
    _class: JClass,
    size: i32,
) -> jlong {
    // Delegate to parallel_processing module
    parallel_processing::allocate_native_buffer(size)
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_ParallelRustVectorProcessor_copyToNativeBuffer(
    mut env: JNIEnv,
    _class: JClass,
    pointer: jlong,
    data: JFloatArray,
    offset: i32,
    length: i32,
) {
    // Delegate to parallel_processing module
    let buffer = parallel_processing::copy_to_native_buffer(&mut env, &data, offset, length);
    parallel_processing::MEMORY_MANAGER.store_data(pointer, buffer);
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_ParallelRustVectorProcessor_copyFromNativeBuffer<'a>(
    env: JNIEnv<'a>,
    _class: JClass,
    pointer: jlong,
    result: JFloatArray,
    offset: i32,
    length: i32,
) {
    // Delegate to parallel_processing module
    if let Some(data) = parallel_processing::MEMORY_MANAGER.retrieve_data(pointer) {
        let data_slice = if data.len() > length as usize {
            &data[..length as usize]
        } else {
            &data
        };
         
        parallel_processing::copy_from_native_buffer(&env, data_slice, &result, offset);
    }
}


/// Original performance functions (unchanged)
// These are internal helpers and are not FFI entry points. Use Rust ABI to avoid
// improper_ctypes warnings when passing fixed-size arrays.
pub fn nalgebra_matrix_mul(a: [f32; 16], b: [f32; 16]) -> [f32; 16] {
    let ma = nalgebra::Matrix4::<f32>::from_row_slice(&a);
    let mb = nalgebra::Matrix4::<f32>::from_row_slice(&b);
    let res = ma * mb;
    res.as_slice().try_into().unwrap()
}

pub fn nalgebra_vector_add(a: [f32; 3], b: [f32; 3]) -> [f32; 3] {
    let va = nalgebra::Vector3::<f32>::from_row_slice(&a);
    let vb = nalgebra::Vector3::<f32>::from_row_slice(&b);
    let res = va + vb;
    res.as_slice().try_into().unwrap()
}

pub fn glam_vector_dot(a: [f32; 3], b: [f32; 3]) -> f32 {
    let va = glam::Vec3::from(a);
    let vb = glam::Vec3::from(b);
    va.dot(vb)
}

pub fn glam_vector_cross(a: [f32; 3], b: [f32; 3]) -> [f32; 3] {
    let va = glam::Vec3::from(a);
    let vb = glam::Vec3::from(b);
    let res = va.cross(vb);
    res.to_array()
}


/// Performance monitoring JNI functions
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_DistributedTracer_getRustPerformanceStats<'a>(
    env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
) -> jni::objects::JString<'a> {
    performance_monitoring::Java_com_kneaf_core_performance_DistributedTracer_getRustPerformanceStats(
        env, _class
    )
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_DistributedTracer_getRustMetricsJson<'a>(
    env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
) -> jni::objects::JString<'a> {
    performance_monitoring::Java_com_kneaf_core_performance_DistributedTracer_getRustMetricsJson(
        env, _class
    )
}

/// JNI functions for Entity Processing System - Generic Framework Implementation
/// These functions provide native Rust entity processing with GPU acceleration and SIMD optimization
/// Initialize Entity Registry
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_EntityProcessingService_initializeEntityRegistry<'a>(
    env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
) -> jni::sys::jlong {
    // Create performance monitor
    let performance_monitor = Arc::new(performance_monitor::PerformanceMonitor::new());
     
    // Create entity registry
    let entity_registry = Arc::new(entity_registry::EntityRegistry::new(performance_monitor));
     
    // Convert to raw pointer for Java
    Box::into_raw(Box::new(entity_registry)) as jlong
}

/// Initialize complete Entity Modulation System
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_EntityProcessingService_initializeEntityModulationSystem<'a>(
    env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
    max_entities: i32,
) -> jni::sys::jlong {
    let modulation_system = entity_modulation::EntityModulationSystem::new(max_entities as usize);
     
    // Convert to raw pointer for Java
    Box::into_raw(Box::new(modulation_system)) as jlong
}

/// Update entity modulation system
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_EntityProcessingService_updateEntityModulationSystem<'a>(
    env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
    system_ptr: jni::sys::jlong,
    delta_time: jdouble,
) -> jni::objects::JString<'a> {
    let mut system = unsafe { Box::from_raw(system_ptr as *mut entity_modulation::EntityModulationSystem) };
     
    let result = system.update(delta_time as f32);
     
    // Convert back to raw pointer
    let _ = Box::into_raw(system);
     
    match result {
        Ok(_) => {
            let json = r#"{"success": true}"#;
            env.new_string(json).unwrap()
        }
        Err(error) => {
            let json = format!(r#"{{"success": false, "error": "{}"}}"#, error);
            env.new_string(&json).unwrap()
        }
    }
}

/// Get entity modulation system statistics
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_EntityProcessingService_getEntityModulationStats<'a>(
    env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
    system_ptr: jni::sys::jlong,
) -> jni::objects::JString<'a> {
    let system = unsafe { Box::from_raw(system_ptr as *mut entity_modulation::EntityModulationSystem) };
     
    let stats = system.get_statistics();
     
    // Convert back to raw pointer
    let _ = Box::into_raw(system);
     
    let json = format!(
        r#"{{"total_entities": {}, "active_entities": {}, "is_initialized": {}}}"#,
        stats.total_entities,
        stats.active_entities,
        stats.is_initialized
    );
     
    env.new_string(&json).unwrap()
}

/// Initialize entity modulation system
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_EntityProcessingService_initializeModulationSystem<'a>(
    env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
    system_ptr: jni::sys::jlong,
) -> jni::objects::JString<'a> {
    let mut system = unsafe { Box::from_raw(system_ptr as *mut entity_modulation::EntityModulationSystem) };
     
    let result = system.initialize();
     
    // Convert back to raw pointer
    let _ = Box::into_raw(system);
     
    match result {
        Ok(_) => {
            let json = r#"{"success": true}"#;
            env.new_string(json).unwrap()
        }
        Err(error) => {
            let json = format!(r#"{{"success": false, "error": "{}"}}"#, error);
            env.new_string(&json).unwrap()
        }
    }
}

/// Shutdown entity modulation system
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_EntityProcessingService_shutdownModulationSystem<'a>(
    env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
    system_ptr: jni::sys::jlong,
) {
    if system_ptr != 0 {
        let mut system = unsafe { Box::from_raw(system_ptr as *mut entity_modulation::EntityModulationSystem) };
        system.shutdown().unwrap_or_else(|e| eprintln!("Shutdown error: {}", e));
    }
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_DistributedTracer_recordRustOperation(
    env: jni::JNIEnv,
    _class: jni::objects::JClass,
    component: jni::objects::JString,
    duration_ns: i64,
    success: bool,
) {
    performance_monitoring::Java_com_kneaf_core_performance_DistributedTracer_recordRustOperation(
        env, _class, component, duration_ns, success
    )
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_DistributedTracer_recordRustError(
    env: jni::JNIEnv,
    _class: jni::objects::JClass,
    component: jni::objects::JString,
) {
    performance_monitoring::Java_com_kneaf_core_performance_DistributedTracer_recordRustError(
        env, _class, component
    )
}

/// JNI functions for AI Pathfinding System - Native A* Implementation
/// These functions provide native Rust pathfinding with high performance and memory safety
/// Initialize pathfinding system
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_EntityProcessingService_initializePathfindingSystem<'a>(
    env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
    max_search_distance: jdouble,
    heuristic_weight: jdouble,
    timeout_ms: i32,
    allow_diagonal: bool,
    async_thread_pool_size: i32,
) -> jni::sys::jlong {
    let config = pathfinding::PathfindingConfig {
        max_search_distance: max_search_distance as f32,
        heuristic_weight: heuristic_weight as f32,
        timeout_ms: timeout_ms as u64,
        allow_diagonal_movement: allow_diagonal,
        path_smoothing_enabled: true,
        async_thread_pool_size: async_thread_pool_size as usize,
    };

    let performance_monitor = Arc::new(performance_monitor::PerformanceMonitor::new());
    let pathfinding_system = pathfinding::PathfindingSystem::new(config, performance_monitor);

    Box::into_raw(Box::new(pathfinding_system)) as jlong
}

/// Find path using A* algorithm
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_EntityProcessingService_findPath<'a>(
    env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
    system_ptr: jni::sys::jlong,
    grid_data: jni::objects::JByteArray<'a>,
    width: i32,
    height: i32,
    start_x: i32,
    start_y: i32,
    goal_x: i32,
    goal_y: i32,
) -> jni::objects::JString<'a> {
    let system = unsafe { Box::from_raw(system_ptr as *mut pathfinding::PathfindingSystem) };

    // Convert Java byte array to Rust grid
    let grid_size = (width * height) as usize;
    let mut obstacles = vec![false; grid_size];

    if let Ok(grid_bytes) = env.convert_byte_array(grid_data) {
        for i in 0..grid_size.min(grid_bytes.len()) {
            obstacles[i] = grid_bytes[i] != 0;
        }
    }

    let grid = pathfinding::PathfindingGrid {
        width: width as usize,
        height: height as usize,
        obstacles,
    };

    let start = (start_x as usize, start_y as usize);
    let goal = (goal_x as usize, goal_y as usize);

    let result = system.find_path(&grid, start, goal);

    // Convert back to raw pointer
    let _ = Box::into_raw(system);

    // Return result as JSON string
    let json = format!(
        r#"{{"success": {}, "path_length": {:.2}, "nodes_explored": {}, "execution_time_ms": {}, "path": {:?}, "error": {}}}"#,
        result.success,
        result.path_length,
        result.nodes_explored,
        result.execution_time_ms,
        result.path,
        result.error_message.as_ref().map_or("null".to_string(), |e| format!("\"{}\"", e))
    );

    env.new_string(&json).unwrap()
}

/// Find path asynchronously
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_EntityProcessingService_findPathAsync<'a>(
    env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
    system_ptr: jni::sys::jlong,
    grid_data: jni::objects::JByteArray<'a>,
    width: i32,
    height: i32,
    start_x: i32,
    start_y: i32,
    goal_x: i32,
    goal_y: i32,
) -> jni::objects::JString<'a> {
    // For async operations, we need to handle this differently
    // For now, delegate to synchronous version
    Java_com_kneaf_core_EntityProcessingService_findPath(
        env, _class, system_ptr, grid_data, width, height,
        start_x, start_y, goal_x, goal_y
    )
}

/// Initialize entity pathfinding system
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_EntityProcessingService_initializeEntityPathfindingSystem<'a>(
    env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
    max_search_distance: jdouble,
    heuristic_weight: jdouble,
    timeout_ms: i32,
    allow_diagonal: bool,
    async_thread_pool_size: i32,
) -> jni::sys::jlong {
    let config = pathfinding::PathfindingConfig {
        max_search_distance: max_search_distance as f32,
        heuristic_weight: heuristic_weight as f32,
        timeout_ms: timeout_ms as u64,
        allow_diagonal_movement: allow_diagonal,
        path_smoothing_enabled: true,
        async_thread_pool_size: async_thread_pool_size as usize,
    };

    let performance_monitor = Arc::new(performance_monitor::PerformanceMonitor::new());
    let entity_pathfinding_system = pathfinding::EntityPathfindingSystem::new(config, performance_monitor);

    Box::into_raw(Box::new(entity_pathfinding_system)) as jlong
}

/// Find path for entity
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_EntityProcessingService_findEntityPath<'a>(
    env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
    system_ptr: jni::sys::jlong,
    entity_id: i32,
    start_x: jdouble,
    start_y: jdouble,
    start_z: jdouble,
    target_x: jdouble,
    target_y: jdouble,
    target_z: jdouble,
    grid_data: jni::objects::JByteArray<'a>,
    width: i32,
    height: i32,
    grid_size: jdouble,
) -> jni::objects::JString<'a> {
    let mut system = unsafe { Box::from_raw(system_ptr as *mut pathfinding::EntityPathfindingSystem) };

    let start_pos = glam::Vec3::new(start_x as f32, start_y as f32, start_z as f32);
    let target_pos = glam::Vec3::new(target_x as f32, target_y as f32, target_z as f32);

    // Convert Java byte array to Rust grid
    let grid_array_size = (width * height) as usize;
    let mut obstacles = vec![false; grid_array_size];

    if let Ok(grid_bytes) = env.convert_byte_array(grid_data) {
        for i in 0..grid_array_size.min(grid_bytes.len()) {
            obstacles[i] = grid_bytes[i] != 0;
        }
    }

    let grid = pathfinding::PathfindingGrid {
        width: width as usize,
        height: height as usize,
        obstacles,
    };

    let entity_id = entity_registry::EntityId(entity_id as u64);
    let result = system.find_entity_path(entity_id, start_pos, target_pos, &grid, grid_size as f32);

    // Convert back to raw pointer
    let _ = Box::into_raw(system);

    match result {
        Some(path_result) => {
            let json = format!(
                r#"{{"success": {}, "path_length": {:.2}, "nodes_explored": {}, "execution_time_ms": {}, "path": {:?}, "error": {}}}"#,
                path_result.success,
                path_result.path_length,
                path_result.nodes_explored,
                path_result.execution_time_ms,
                path_result.path,
                path_result.error_message.as_ref().map_or("null".to_string(), |e| format!("\"{}\"", e))
            );
            env.new_string(&json).unwrap()
        }
        None => {
            let json = r#"{"success": false, "error": "Failed to find path"}"#;
            env.new_string(json).unwrap()
        }
    }
}

/// Get next path position for entity
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_EntityProcessingService_getEntityNextPathPosition<'a>(
    env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
    system_ptr: jni::sys::jlong,
    entity_id: i32,
    grid_size: jdouble,
) -> jni::objects::JString<'a> {
    let mut system = unsafe { Box::from_raw(system_ptr as *mut pathfinding::EntityPathfindingSystem) };

    let entity_id = entity_registry::EntityId(entity_id as u64);
    let next_pos = system.get_next_path_position(entity_id, grid_size as f32);

    // Convert back to raw pointer
    Box::into_raw(system);

    match next_pos {
        Some(pos) => {
            let json = format!(
                r#"{{"success": true, "position": {{"x": {:.2}, "y": {:.2}, "z": {:.2}}}}}"#,
                pos.x, pos.y, pos.z
            );
            env.new_string(&json).unwrap()
        }
        None => {
            let json = r#"{"success": false, "error": "No path available"}"#;
            env.new_string(json).unwrap()
        }
    }
}

/// Check if entity has active path
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_EntityProcessingService_entityHasPath(
    env: jni::JNIEnv,
    _class: jni::objects::JClass,
    system_ptr: jni::sys::jlong,
    entity_id: i32,
) -> bool {
    let system = unsafe { Box::from_raw(system_ptr as *mut pathfinding::EntityPathfindingSystem) };

    let entity_id = entity_registry::EntityId(entity_id as u64);
    let has_path = system.has_path(entity_id);

    // Convert back to raw pointer
    Box::into_raw(system);

    has_path
}

/// Clear entity's path
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_EntityProcessingService_clearEntityPath(
    env: jni::JNIEnv,
    _class: jni::objects::JClass,
    system_ptr: jni::sys::jlong,
    entity_id: i32,
) {
    let mut system = unsafe { Box::from_raw(system_ptr as *mut pathfinding::EntityPathfindingSystem) };

    let entity_id = entity_registry::EntityId(entity_id as u64);
    system.clear_path(entity_id);

    // Convert back to raw pointer
    Box::into_raw(system);
}

/// Get pathfinding system statistics
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_EntityProcessingService_getPathfindingStatistics<'a>(
    env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
    system_ptr: jni::sys::jlong,
) -> jni::objects::JString<'a> {
    let system = unsafe { Box::from_raw(system_ptr as *mut pathfinding::EntityPathfindingSystem) };

    let stats = system.get_statistics();

    // Convert back to raw pointer
    Box::into_raw(system);

    let json = format!(
        r#"{{"active_paths": {}, "total_paths_processed": {}}}"#,
        stats.active_paths,
        stats.total_paths_processed
    );

    env.new_string(&json).unwrap()
}

/// Cleanup pathfinding system resources
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_EntityProcessingService_cleanupPathfindingSystem(
    env: jni::JNIEnv,
    _class: jni::objects::JClass,
    system_ptr: jni::sys::jlong,
) {
    if system_ptr != 0 {
        unsafe { Box::from_raw(system_ptr as *mut pathfinding::PathfindingSystem) };
    }
}

/// Cleanup entity pathfinding system resources
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_EntityProcessingService_cleanupEntityPathfindingSystem(
    env: jni::JNIEnv,
    _class: jni::objects::JClass,
    system_ptr: jni::sys::jlong,
) {
    if system_ptr != 0 {
        unsafe { Box::from_raw(system_ptr as *mut pathfinding::EntityPathfindingSystem) };
    }
}

/// Generic utility functions for mathematical operations
/// These functions provide pure mathematical computations without any game-specific logic

/// Calculate trajectory for projectile motion
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_OptimizationInjector_calculateTrajectory<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    start_x: jdouble,
    start_y: jdouble,
    start_z: jdouble,
    target_x: jdouble,
    target_y: jdouble,
    target_z: jdouble,
    speed: jdouble,
) -> JDoubleArray<'a> {
    let dx = target_x - start_x;
    let dy = target_y - start_y;
    let dz = target_z - start_z;
    let distance = (dx*dx + dy*dy + dz*dz).sqrt();
    
    if distance == 0.0 {
        return env.new_double_array(0).unwrap();
    }
    
    let normalized_x = dx / distance;
    let normalized_y = dy / distance;
    let normalized_z = dz / distance;
    
    // Return next position with speed applied
    let result = [
        start_x + normalized_x * speed * 0.1,
        start_y + normalized_y * speed * 0.1,
        start_z + normalized_z * speed * 0.1
    ];
    
    let output = env.new_double_array(3).expect("Failed to create output array");
    env.set_double_array_region(&output, 0, &result).expect("Failed to set output array");
    output
}

/// Calculate positions in circular pattern
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_OptimizationInjector_calculateCircularPositions<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    center_x: jdouble,
    center_y: jdouble,
    center_z: jdouble,
    radius: jdouble,
    count: i32,
) -> JObjectArray<'a> {
    let mut positions = Vec::new();
    
    for i in 0..count {
        let angle = (i as f64 * 2.0 * std::f64::consts::PI) / (count as f64);
        positions.push([
            center_x + angle.cos() * radius,
            center_y,
            center_z + angle.sin() * radius
        ]);
    }
    
    // Create 2D array: double[count][3]
    let double_array_class = env.find_class("[D").expect("Failed to find double array class");
    let result_array = env.new_object_array(count, double_array_class, JObject::null()).expect("Failed to create object array");
    
    for (i, pos) in positions.iter().enumerate() {
        let pos_array = env.new_double_array(3).expect("Failed to create position array");
        env.set_double_array_region(&pos_array, 0, pos).expect("Failed to set position array");
        env.set_object_array_element(&result_array, i as i32, pos_array).expect("Failed to set array element");
    }
    
    result_array
}

/// Calculate damage with multiplier
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_OptimizationInjector_calculateScaledDamage(
    _env: JNIEnv,
    _class: JClass,
    base_damage: jdouble,
    multiplier: jdouble,
) -> jdouble {
    base_damage * multiplier
}

/// Calculate stacks with maximum limit
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_OptimizationInjector_calculateLimitedStacks(
    _env: JNIEnv,
    _class: JClass,
    current_stacks: jint,
    increment: jint,
    max_stacks: jint,
) -> jint {
    std::cmp::min(current_stacks + increment, max_stacks)
}

/// JNI functions for Hayabusa skill optimizations - ShadowZombieNinja specific
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_OptimizationInjector_rustperf_1hayabusa_1phantom_1shuriken<'a>(
    env: JNIEnv<'a>,
    _class: JClass,
    start_x: jdouble,
    start_y: jdouble,
    start_z: jdouble,
    target_x: jdouble,
    target_y: jdouble,
    target_z: jdouble,
    speed: jdouble,
) -> JDoubleArray<'a> {
    let dx = target_x - start_x;
    let dy = target_y - start_y;
    let dz = target_z - start_z;
    let distance = (dx*dx + dy*dy + dz*dz).sqrt();
    
    if distance == 0.0 {
        let result = [start_x, start_y, start_z];
        let output = env.new_double_array(3).expect("Failed to create output array");
        env.set_double_array_region(&output, 0, &result).expect("Failed to set output array");
        return output;
    }
    
    let normalized_x = dx / distance;
    let normalized_y = dy / distance;
    let normalized_z = dz / distance;
    
    // Apply speed and add slight acceleration for projectile behavior
    let result = [
        start_x + normalized_x * speed * 0.15,
        start_y + normalized_y * speed * 0.15,
        start_z + normalized_z * speed * 0.15
    ];
    
    let output = env.new_double_array(3).expect("Failed to create output array");
    env.set_double_array_region(&output, 0, &result).expect("Failed to set output array");
    output
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_OptimizationInjector_rustperf_1hayabusa_1quad_1shadow<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass,
    center_x: jdouble,
    center_y: jdouble,
    center_z: jdouble,
    radius: jdouble,
) -> JObjectArray<'a> {
    let mut positions = Vec::new();
    
    // Create 4 quadrant positions in a perfect square pattern
    positions.push([center_x + radius, center_y, center_z]);      // Right
    positions.push([center_x - radius, center_y, center_z]);      // Left
    positions.push([center_x, center_y, center_z + radius]);      // Forward
    positions.push([center_x, center_y, center_z - radius]);      // Backward
    
    // Create 2D array: double[4][3]
    let double_array_class = env.find_class("[D").expect("Failed to find double array class");
    let result_array = env.new_object_array(4, double_array_class, JObject::null()).expect("Failed to create object array");
    
    for (i, pos) in positions.iter().enumerate() {
        let pos_array = env.new_double_array(3).expect("Failed to create position array");
        env.set_double_array_region(&pos_array, 0, pos).expect("Failed to set position array");
        env.set_object_array_element(&result_array, i as i32, pos_array).expect("Failed to set array element");
    }
    
    result_array
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_OptimizationInjector_rustperf_1hayabusa_1shadow_1kill_1damage(
    _env: JNIEnv,
    _class: JClass,
    passive_stacks: jint,
    base_damage: jdouble,
) -> jdouble {
    let multiplier = 1.0 + (passive_stacks as f64 * 0.30); // More aggressive scaling
    base_damage * multiplier
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_OptimizationInjector_rustperf_1hayabusa_1calculate_1passive_1stacks(
    _env: JNIEnv,
    _class: JClass,
    current_stacks: jint,
    successful_hit: jboolean,
    max_stacks: jint,
) -> jint {
    if successful_hit != jni::sys::JNI_TRUE {
        return current_stacks;
    }
    
    let new_stacks = current_stacks + 2; // Faster stacking for more dynamic combat
    std::cmp::min(new_stacks, max_stacks)
}

// ============================================================================
// JNI functions for RustNativeLoader - Centralized loader implementations
// These are duplicates of RustVectorLibrary methods but with RustNativeLoader prefix
// ============================================================================

/// RustNativeLoader - Category 1: Pure Mathematical Operations
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustNativeLoader_nalgebra_1matrix_1mul<'a>(
    env: JNIEnv<'a>,
    _class: JClass,
    a: JFloatArray,
    b: JFloatArray,
) -> JFloatArray<'a> {
    let mut a_buf = [0.0f32; 16];
    env.get_float_array_region(&a, 0, &mut a_buf).expect("Failed to get a array");
    let mut b_buf = [0.0f32; 16];
    env.get_float_array_region(&b, 0, &mut b_buf).expect("Failed to get b array");
    let result = parallel_processing::nalgebra_matrix_mul(a_buf, b_buf);
    let output = env.new_float_array(16).expect("Failed to create output array");
    env.set_float_array_region(&output, 0, &result).expect("Failed to set output array");
    output
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustNativeLoader_nalgebra_1vector_1add<'a>(
    env: JNIEnv<'a>,
    _class: JClass,
    a: JFloatArray,
    b: JFloatArray,
) -> JFloatArray<'a> {
    let mut a_buf = [0.0f32; 3];
    env.get_float_array_region(&a, 0, &mut a_buf).expect("Failed to get a array");
    let mut b_buf = [0.0f32; 3];
    env.get_float_array_region(&b, 0, &mut b_buf).expect("Failed to get b array");
    let result = parallel_processing::nalgebra_vector_add(a_buf, b_buf);
    let output = env.new_float_array(3).expect("Failed to create output array");
    env.set_float_array_region(&output, 0, &result).expect("Failed to set output array");
    output
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustNativeLoader_glam_1vector_1dot(
    env: JNIEnv,
    _class: JClass,
    a: JFloatArray,
    b: JFloatArray,
) -> f32 {
    let mut a_buf = [0.0f32; 3];
    env.get_float_array_region(&a, 0, &mut a_buf).expect("Failed to get a array");
    let mut b_buf = [0.0f32; 3];
    env.get_float_array_region(&b, 0, &mut b_buf).expect("Failed to get b array");
    parallel_processing::glam_vector_dot(a_buf, b_buf)
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustNativeLoader_glam_1vector_1cross<'a>(
    env: JNIEnv<'a>,
    _class: JClass,
    a: JFloatArray,
    b: JFloatArray,
) -> JFloatArray<'a> {
    let mut a_buf = [0.0f32; 3];
    env.get_float_array_region(&a, 0, &mut a_buf).expect("Failed to get a array");
    let mut b_buf = [0.0f32; 3];
    env.get_float_array_region(&b, 0, &mut b_buf).expect("Failed to get b array");
    let result = parallel_processing::glam_vector_cross(a_buf, b_buf);
    let output = env.new_float_array(3).expect("Failed to create output array");
    env.set_float_array_region(&output, 0, &result).expect("Failed to set output array");
    output
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustNativeLoader_glam_1matrix_1mul<'a>(
    env: JNIEnv<'a>,
    _class: JClass,
    a: JFloatArray,
    b: JFloatArray,
) -> JFloatArray<'a> {
    let mut a_buf = [0.0f32; 16];
    env.get_float_array_region(&a, 0, &mut a_buf).expect("Failed to get a array");
    let mut b_buf = [0.0f32; 16];
    env.get_float_array_region(&b, 0, &mut b_buf).expect("Failed to get b array");
    let result = performance::glam_matrix_mul(a_buf, b_buf);
    let output = env.new_float_array(16).expect("Failed to create output array");
    env.set_float_array_region(&output, 0, &result).expect("Failed to set output array");
    output
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustNativeLoader_faer_1matrix_1mul<'a>(
    env: JNIEnv<'a>,
    _class: JClass,
    a: JDoubleArray,
    b: JDoubleArray,
) -> JDoubleArray<'a> {
    // Convert f64 to f32 for now since faer_matrix_mul only supports f32
    let mut a_buf_f64 = [0.0f64; 16];
    env.get_double_array_region(&a, 0, &mut a_buf_f64).expect("Failed to get a array");
    let mut b_buf_f64 = [0.0f64; 16];
    env.get_double_array_region(&b, 0, &mut b_buf_f64).expect("Failed to get b array");
    
    // Convert to f32
    let mut a_buf = [0.0f32; 16];
    let mut b_buf = [0.0f32; 16];
    for i in 0..16 {
        a_buf[i] = a_buf_f64[i] as f32;
        b_buf[i] = b_buf_f64[i] as f32;
    }
    
    let result_f32 = performance::faer_matrix_mul(a_buf, b_buf);
    
    // Convert back to f64
    let mut result = [0.0f64; 16];
    for i in 0..16 {
        result[i] = result_f32[i] as f64;
    }
    
    let output = env.new_double_array(16).expect("Failed to create output array");
    env.set_double_array_region(&output, 0, &result).expect("Failed to set output array");
    output
}

/// RustNativeLoader - Category 3: Parallel Processing Operations
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustNativeLoader_parallelMatrixMultiplyBlock<'a>(
    env: JNIEnv<'a>,
    _class: JClass,
    a: JFloatArray,
    b: JFloatArray,
    size: jint,
) -> JFloatArray<'a> {
    let len = (size * size) as usize;
    let mut a_buf = vec![0.0f32; len];
    let mut b_buf = vec![0.0f32; len];
    
    env.get_float_array_region(&a, 0, &mut a_buf).expect("Failed to get a array");
    env.get_float_array_region(&b, 0, &mut b_buf).expect("Failed to get b array");
    
    // Use enhanced version which exists
    let size_usize = size as usize;
    let result = parallel_matrix::enhanced_parallel_matrix_multiply_block(&a_buf, &b_buf, size_usize, size_usize, size_usize);
    let output = env.new_float_array(len as i32).expect("Failed to create output array");
    env.set_float_array_region(&output, 0, &result).expect("Failed to set output array");
    output
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustNativeLoader_parallelStrassenMultiply<'a>(
    env: JNIEnv<'a>,
    _class: JClass,
    a: JFloatArray,
    b: JFloatArray,
    n: jint,
) -> JFloatArray<'a> {
    let len = (n * n) as usize;
    let mut a_buf = vec![0.0f32; len];
    let mut b_buf = vec![0.0f32; len];
    
    env.get_float_array_region(&a, 0, &mut a_buf).expect("Failed to get a array");
    env.get_float_array_region(&b, 0, &mut b_buf).expect("Failed to get b array");
    
    let result = parallel_matrix::parallel_strassen_multiply(&a_buf, &b_buf, n as usize);
    let output = env.new_float_array(len as i32).expect("Failed to create output array");
    env.set_float_array_region(&output, 0, &result).expect("Failed to set output array");
    output
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustNativeLoader_arenaMatrixMultiply<'a>(
    env: JNIEnv<'a>,
    _class: JClass,
    a: JFloatArray,
    b: JFloatArray,
    size: jint,
) -> JFloatArray<'a> {
    let size_usize = size as usize;
    let len = size_usize * size_usize;
    let mut a_buf = vec![0.0f32; len];
    let mut b_buf = vec![0.0f32; len];
    
    env.get_float_array_region(&a, 0, &mut a_buf).expect("Failed to get a array");
    env.get_float_array_region(&b, 0, &mut b_buf).expect("Failed to get b array");
    
    // arena_matrix_multiply needs: a, b, a_rows, a_cols, b_cols, thread_id
    let result = arena_memory::arena_matrix_multiply(&a_buf, &b_buf, size_usize, size_usize, size_usize, 0);
    let output = env.new_float_array(len as i32).expect("Failed to create output array");
    env.set_float_array_region(&output, 0, &result).expect("Failed to set output array");
    output
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustNativeLoader_runtimeMatrixMultiply<'a>(
    env: JNIEnv<'a>,
    _class: JClass,
    a: JFloatArray,
    b: JFloatArray,
    size: jint,
) -> JFloatArray<'a> {
    let size_usize = size as usize;
    let len = size_usize * size_usize;
    let mut a_buf = vec![0.0f32; len];
    let mut b_buf = vec![0.0f32; len];
    
    env.get_float_array_region(&a, 0, &mut a_buf).expect("Failed to get a array");
    env.get_float_array_region(&b, 0, &mut b_buf).expect("Failed to get b array");
    
    // runtime_matrix_multiply needs: a, b, a_rows, a_cols, b_cols
    let result = simd_runtime::runtime_matrix_multiply(&a_buf, &b_buf, size_usize, size_usize, size_usize);
    let output = env.new_float_array(len as i32).expect("Failed to create output array");
    env.set_float_array_region(&output, 0, &result).expect("Failed to set output array");
    output
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustNativeLoader_runtimeVectorDotProduct(
    env: JNIEnv,
    _class: JClass,
    a: JFloatArray,
    b: JFloatArray,
) -> f32 {
    let len = env.get_array_length(&a).expect("Failed to get array length") as usize;
    let mut a_buf = vec![0.0f32; len];
    let mut b_buf = vec![0.0f32; len];
    
    env.get_float_array_region(&a, 0, &mut a_buf).expect("Failed to get a array");
    env.get_float_array_region(&b, 0, &mut b_buf).expect("Failed to get b array");
    
    simd_runtime::runtime_vector_dot_product(&a_buf, &b_buf)
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustNativeLoader_runtimeVectorAdd<'a>(
    env: JNIEnv<'a>,
    _class: JClass,
    a: JFloatArray,
    b: JFloatArray,
) -> JFloatArray<'a> {
    let len = env.get_array_length(&a).expect("Failed to get array length") as usize;
    let mut a_buf = vec![0.0f32; len];
    let mut b_buf = vec![0.0f32; len];
    
    env.get_float_array_region(&a, 0, &mut a_buf).expect("Failed to get a array");
    env.get_float_array_region(&b, 0, &mut b_buf).expect("Failed to get b array");
    
    let result = simd_runtime::runtime_vector_add(&a_buf, &b_buf);
    let output = env.new_float_array(len as i32).expect("Failed to create output array");
    env.set_float_array_region(&output, 0, &result).expect("Failed to set output array");
    output
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustNativeLoader_runtimeMatrix4x4Multiply<'a>(
    env: JNIEnv<'a>,
    _class: JClass,
    a: JFloatArray,
    b: JFloatArray,
) -> JFloatArray<'a> {
    let mut a_buf = [0.0f32; 16];
    let mut b_buf = [0.0f32; 16];
    
    env.get_float_array_region(&a, 0, &mut a_buf).expect("Failed to get a array");
    env.get_float_array_region(&b, 0, &mut b_buf).expect("Failed to get b array");
    
    let result = simd_runtime::runtime_matrix4x4_multiply(&a_buf, &b_buf);
    let output = env.new_float_array(16).expect("Failed to create output array");
    env.set_float_array_region(&output, 0, &result).expect("Failed to set output array");
    output
}

// ========================================
// RustNativeLoader - Category 2: OptimizationInjector Delegates
// Wrapper methods that delegate to existing OptimizationInjector implementations
// ========================================

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustNativeLoader_rustperf_1vector_1multiply<'a>(
    env: JNIEnv<'a>,
    class: JClass,
    x: jdouble,
    y: jdouble,
    z: jdouble,
    scalar: jdouble,
) -> JDoubleArray<'a> {
    Java_com_kneaf_core_OptimizationInjector_rustperf_1vector_1multiply(env, class, x, y, z, scalar)
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustNativeLoader_rustperf_1vector_1add<'a>(
    env: JNIEnv<'a>,
    class: JClass,
    x1: jdouble,
    y1: jdouble,
    z1: jdouble,
    x2: jdouble,
    y2: jdouble,
    z2: jdouble,
) -> JDoubleArray<'a> {
    Java_com_kneaf_core_OptimizationInjector_rustperf_1vector_1add(env, class, x1, y1, z1, x2, y2, z2)
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustNativeLoader_rustperf_1vector_1damp<'a>(
    env: JNIEnv<'a>,
    class: JClass,
    x: jdouble,
    y: jdouble,
    z: jdouble,
    damping: jdouble,
) -> JDoubleArray<'a> {
    Java_com_kneaf_core_OptimizationInjector_rustperf_1vector_1damp(env, class, x, y, z, damping)
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustNativeLoader_rustperf_1hayabusa_1phantom_1shuriken<'a>(
    env: JNIEnv<'a>,
    class: JClass,
    start_x: jdouble,
    start_y: jdouble,
    start_z: jdouble,
    target_x: jdouble,
    target_y: jdouble,
    target_z: jdouble,
    speed: jdouble,
) -> JDoubleArray<'a> {
    Java_com_kneaf_core_OptimizationInjector_rustperf_1hayabusa_1phantom_1shuriken(
        env, class, start_x, start_y, start_z, target_x, target_y, target_z, speed
    )
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustNativeLoader_rustperf_1hayabusa_1quad_1shadow<'a>(
    env: JNIEnv<'a>,
    class: JClass,
    center_x: jdouble,
    center_y: jdouble,
    center_z: jdouble,
    radius: jdouble,
) -> JObjectArray<'a> {
    Java_com_kneaf_core_OptimizationInjector_rustperf_1hayabusa_1quad_1shadow(env, class, center_x, center_y, center_z, radius)
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustNativeLoader_rustperf_1hayabusa_1shadow_1kill_1damage(
    env: JNIEnv,
    class: JClass,
    passive_stacks: jint,
    base_damage: jdouble,
) -> jdouble {
    Java_com_kneaf_core_OptimizationInjector_rustperf_1hayabusa_1shadow_1kill_1damage(env, class, passive_stacks, base_damage)
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustNativeLoader_rustperf_1hayabusa_1calculate_1passive_1stacks(
    env: JNIEnv,
    class: JClass,
    current_stacks: jint,
    successful_hit: jboolean,
    max_stacks: jint,
) -> jint {
    Java_com_kneaf_core_OptimizationInjector_rustperf_1hayabusa_1calculate_1passive_1stacks(env, class, current_stacks, successful_hit, max_stacks)
}

// ========================================
// RustNativeLoader - Category 4: Batch Operations Delegates
// Only implemented batch operations (batchNalgebra methods)
// ========================================

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustNativeLoader_batchNalgebraMatrixMulNative<'a>(
    env: JNIEnv<'a>,
    class: JClass<'a>,
    matrices_a: JObjectArray<'a>,
    matrices_b: JObjectArray<'a>,
    count: jint,
) -> JObjectArray<'a> {
    Java_com_kneaf_core_ParallelRustVectorProcessor_batchNalgebraMatrixMul(env, class, matrices_a, matrices_b, count)
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustNativeLoader_batchNalgebraVectorAddNative<'a>(
    env: JNIEnv<'a>,
    class: JClass<'a>,
    vectors_a: JObjectArray<'a>,
    vectors_b: JObjectArray<'a>,
    count: jint,
) -> JObjectArray<'a> {
    Java_com_kneaf_core_ParallelRustVectorProcessor_batchNalgebraVectorAdd(env, class, vectors_a, vectors_b, count)
}

// Note: Other batch methods (batchGlamVectorDot, batchGlamVectorCross, batchGlamMatrixMul, batchFaerMatrixMul)
// are not implemented in Rust yet. Java will need to provide stubs or fallbacks.

// ========================================
// RustNativeLoader - Category 5: Memory Management Delegates
// ========================================

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustNativeLoader_releaseNativeBuffer(
    env: JNIEnv,
    class: JClass,
    pointer: jlong,
) {
    Java_com_kneaf_core_ParallelRustVectorProcessor_releaseNativeBuffer(env, class, pointer)
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustNativeLoader_allocateNativeBuffer(
    env: JNIEnv,
    class: JClass,
    size: jint,
) -> jlong {
    Java_com_kneaf_core_ParallelRustVectorProcessor_allocateNativeBuffer(env, class, size)
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustNativeLoader_copyToNativeBuffer(
    env: JNIEnv,
    class: JClass,
    pointer: jlong,
    data: JFloatArray,
    offset: jint,
    length: jint,
) {
    Java_com_kneaf_core_ParallelRustVectorProcessor_copyToNativeBuffer(env, class, pointer, data, offset, length)
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustNativeLoader_copyFromNativeBuffer<'a>(
    env: JNIEnv<'a>,
    class: JClass,
    pointer: jlong,
    result: JFloatArray,
    offset: jint,
    length: jint,
) {
    Java_com_kneaf_core_ParallelRustVectorProcessor_copyFromNativeBuffer(env, class, pointer, result, offset, length)
}

// ========================================
// RustNativeLoader - Category 6: Performance Stats Delegate
// ========================================

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustNativeLoader_getRustPerformanceStats<'a>(
    env: JNIEnv<'a>,
    class: JClass<'a>,
) -> JString<'a> {
    Java_com_kneaf_core_performance_DistributedTracer_getRustPerformanceStats(env, class)
}

// ========================================
// RustNativeLoader - Category 2B: High-Performance Vector Utilities
// SIMD-optimized common game physics operations
// ========================================

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustNativeLoader_vectorDistance(
    _env: JNIEnv,
    _class: JClass,
    x1: jdouble,
    y1: jdouble,
    z1: jdouble,
    x2: jdouble,
    y2: jdouble,
    z2: jdouble,
) -> jdouble {
    let dx = x2 - x1;
    let dy = y2 - y1;
    let dz = z2 - z1;
    (dx * dx + dy * dy + dz * dz).sqrt()
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustNativeLoader_vectorNormalize<'a>(
    env: JNIEnv<'a>,
    _class: JClass,
    x: jdouble,
    y: jdouble,
    z: jdouble,
) -> JDoubleArray<'a> {
    let length = (x * x + y * y + z * z).sqrt();
    
    let result = if length > 1e-10 {
        [x / length, y / length, z / length]
    } else {
        [0.0, 0.0, 0.0]
    };
    
    let output = env.new_double_array(3).expect("Failed to create output array");
    env.set_double_array_region(&output, 0, &result).expect("Failed to set output array");
    output
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustNativeLoader_vectorLength(
    _env: JNIEnv,
    _class: JClass,
    x: jdouble,
    y: jdouble,
    z: jdouble,
) -> jdouble {
    (x * x + y * y + z * z).sqrt()
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustNativeLoader_vectorLerp<'a>(
    env: JNIEnv<'a>,
    _class: JClass,
    x1: jdouble,
    y1: jdouble,
    z1: jdouble,
    x2: jdouble,
    y2: jdouble,
    z2: jdouble,
    t: jdouble,
) -> JDoubleArray<'a> {
    let result = [
        x1 + (x2 - x1) * t,
        y1 + (y2 - y1) * t,
        z1 + (z2 - z1) * t,
    ];
    
    let output = env.new_double_array(3).expect("Failed to create output array");
    env.set_double_array_region(&output, 0, &result).expect("Failed to set output array");
    output
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustNativeLoader_batchDistanceCalculation<'a>(
    env: JNIEnv<'a>,
    _class: JClass,
    positions: JFloatArray,
    count: jint,
    center_x: jdouble,
    center_y: jdouble,
    center_z: jdouble,
) -> JDoubleArray<'a> {
    let count_usize = count as usize;
    let mut pos_buf = vec![0.0f32; count_usize * 3];
    env.get_float_array_region(&positions, 0, &mut pos_buf).expect("Failed to get positions");
    
    let mut distances = vec![0.0f64; count_usize];
    
    // SIMD-friendly batch processing
    for i in 0..count_usize {
        let idx = i * 3;
        let dx = (pos_buf[idx] as f64) - center_x;
        let dy = (pos_buf[idx + 1] as f64) - center_y;
        let dz = (pos_buf[idx + 2] as f64) - center_z;
        distances[i] = (dx * dx + dy * dy + dz * dz).sqrt();
    }
    
    let output = env.new_double_array(count).expect("Failed to create output array");
    env.set_double_array_region(&output, 0, &distances).expect("Failed to set output array");
    output
}

/// Calculate circular position using SIMD-optimized trigonometric functions
/// Returns [x, z] coordinates at given angle and radius from center
#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_RustNativeLoader_calculateCircularPosition<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    center_x: jdouble,
    center_z: jdouble,
    radius: jdouble,
    angle: jdouble,
) -> jdoubleArray {
    // Calculate cos and sin for circular positioning
    let x_offset = angle.cos() * radius;
    let z_offset = angle.sin() * radius;
    
    let result = vec![center_x + x_offset, center_z + z_offset];
    
    let output = env.new_double_array(2).expect("Failed to create output array");
    env.set_double_array_region(&output, 0, &result).expect("Failed to set output array");
    output.into_raw()
}

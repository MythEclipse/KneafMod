#![allow(non_snake_case)]
#![allow(dead_code)]
// Suppress common warnings during ongoing refactor: unused variables/imports/must_use, etc.
#![allow(unused_variables, unused_imports, unused_mut, unused_assignments, unused_must_use)]
//! JNI implementation for KneafCore performance optimizations
//! Provides real entity processing optimizations using SIMD and mathematical acceleration

//! JNI implementation for KneafCore vector mathematics
//! Provides STRICTLY PURE vector/matrix operations - NO game state access, NO entity references

use jni::JNIEnv;
use jni::objects::{JClass, JFloatArray, JDoubleArray, JObject, JObjectArray};
use jni::sys::{jdouble, jlong};
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
mod dashboard;

// Entity Processing System modules
pub mod entity_registry;
mod shadow_zombie_ninja;
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
    // Vanilla pass-through - return input values unchanged (no damping applied)
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

/// JNI functions for Entity Processing System - Native ECS Implementation
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

/// Create ShadowZombieNinja entity
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_EntityProcessingService_createShadowZombieNinja<'a>(
    env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
    registry_ptr: jni::sys::jlong,
    x: jdouble,
    y: jdouble,
    z: jdouble,
) -> jni::sys::jlong {
    let registry = unsafe { Arc::from_raw(registry_ptr as *const entity_registry::EntityRegistry) };
     
    let position = glam::Vec3::new(x as f32, y as f32, z as f32);
    let entity_id = registry.create_entity(entity_registry::EntityType::ShadowZombieNinja);
     
    // Create ShadowZombieNinja with factory
    let performance_monitor = Arc::new(performance_monitor::PerformanceMonitor::new());
    let factory = shadow_zombie_ninja::ShadowZombieNinjaFactory::new(performance_monitor);
    let ninja = factory.create_entity(entity_id, position);
     
    // Convert to raw pointer for Java
    Box::into_raw(Box::new(ninja)) as jlong
}

/// Update entity position
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_EntityProcessingService_updateEntityPosition(
    env: jni::JNIEnv,
    _class: jni::objects::JClass,
    entity_ptr: jni::sys::jlong,
    x: jdouble,
    y: jdouble,
    z: jdouble,
) {
    let mut ninja = unsafe { Box::from_raw(entity_ptr as *mut shadow_zombie_ninja::ShadowZombieNinja) };
     
    let new_position = glam::Vec3::new(x as f32, y as f32, z as f32);
    ninja.transform.position = new_position;
     
    // Don't drop the box, convert back to raw pointer
    let _ = Box::into_raw(ninja); // Retain ownership of the box
}

/// Process entity combat action
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_EntityProcessingService_processEntityCombat<'a>(
    env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
    combat_system_ptr: jni::sys::jlong,
    attacker_ptr: jni::sys::jlong,
    target_ptr: jni::sys::jlong,
) -> jni::objects::JString<'a> {
    let combat_system = unsafe { Arc::from_raw(combat_system_ptr as *const combat_system::CombatSystem) };
    let attacker = unsafe { Box::from_raw(attacker_ptr as *mut shadow_zombie_ninja::ShadowZombieNinja) };
    let target = unsafe { Box::from_raw(target_ptr as *mut shadow_zombie_ninja::ShadowZombieNinja) };
     
    let attacker_pos = attacker.transform.position;
    let target_pos = target.transform.position;
     
    // Process combat
    let result = combat_system.process_attack(attacker.entity_id, target.entity_id, target_pos);
     
    // Convert results back to raw pointers
    let _ = Box::into_raw(attacker);
    let _ = Box::into_raw(target);
     
    // Return result as JSON string
    match result {
        Ok(event) => {
            let json = format!(
                r#"{{"success": true, "damage": {}, "critical": {}, "event_type": {:?}}}"#,
                event.damage_amount,
                event.is_critical,
                event.event_type
            );
            env.new_string(&json).unwrap()
        }
        Err(error) => {
            let json = format!(r#"{{"success": false, "error": "{}"}}"#, error);
            env.new_string(&json).unwrap()
        }
    }
}

/// Initialize combat system
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_EntityProcessingService_initializeCombatSystem<'a>(
    env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
    registry_ptr: jni::sys::jlong,
) -> jni::sys::jlong {
    let registry = unsafe { Arc::from_raw(registry_ptr as *const entity_registry::EntityRegistry) };
    let performance_monitor = Arc::new(performance_monitor::PerformanceMonitor::new());
     
    let combat_system = Arc::new(combat_system::CombatSystem::new(registry, performance_monitor));
     
    Box::into_raw(Box::new(combat_system)) as jlong
}

/// Clean up entity system resources
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_EntityProcessingService_cleanupEntitySystem(
    env: jni::JNIEnv,
    _class: jni::objects::JClass,
    registry_ptr: jni::sys::jlong,
    combat_system_ptr: jni::sys::jlong,
) {
    // Clean up resources
    if registry_ptr != 0 {
        unsafe { Box::from_raw(registry_ptr as *mut entity_registry::EntityRegistry) };
    }

    if combat_system_ptr != 0 {
        unsafe { Box::from_raw(combat_system_ptr as *mut combat_system::CombatSystem) };
    }
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

/// Spawn ShadowZombieNinja using modulation system
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_EntityProcessingService_spawnShadowZombieNinjaModulation<'a>(
    env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
    system_ptr: jni::sys::jlong,
    x: jdouble,
    y: jdouble,
    z: jdouble,
) -> jni::objects::JString<'a> {
    let system = unsafe { Box::from_raw(system_ptr as *mut entity_modulation::EntityModulationSystem) };
     
    let position = glam::Vec3::new(x as f32, y as f32, z as f32);
    let result = system.spawn_shadow_zombie_ninja(position);
     
    // Convert back to raw pointer
    let _ = Box::into_raw(system);
     
    match result {
        Ok(entity_id) => {
            let json = format!(r#"{{"success": true, "entity_id": {}}}"#, entity_id.0);
            env.new_string(&json).unwrap()
        }
        Err(error) => {
            let json = format!(r#"{{"success": false, "error": "{}"}}"#, error);
            env.new_string(&json).unwrap()
        }
    }
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
        r#"{{"total_entities": {}, "active_entities": {}, "shadow_zombie_ninjas": {}, "is_initialized": {}}}"#,
        stats.total_entities,
        stats.active_entities,
        stats.shadow_zombie_ninjas,
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

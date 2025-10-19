//! JNI implementation for KneafCore performance optimizations
//! Provides real entity processing optimizations using SIMD and mathematical acceleration

//! JNI implementation for KneafCore vector mathematics
//! Provides STRICTLY PURE vector/matrix operations - NO game state access, NO entity references

use jni::JNIEnv;
use jni::objects::{JClass, JFloatArray, JDoubleArray, JObject, JObjectArray};
use jni::sys::{jdouble, jlong};
use std::ffi::c_void;

// Import all modules
mod performance;
mod parallel_processing;
mod parallel_astar;
mod parallel_matrix;
mod arena_memory;
mod simd_runtime;
mod load_balancer;
mod performance_monitoring;
mod zero_copy_buffer;
mod tests;

// Import functions from modules
use parallel_processing::*;
use parallel_astar::*;
use parallel_matrix::*;
use arena_memory::*;
use simd_runtime::*;
use load_balancer::*;
use performance_monitoring::*;
use zero_copy_buffer::*;

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
    damping: jdouble,
) -> JDoubleArray<'a> {
    let omega = 2.0 / damping.max(0.001);
    let x_calc = omega * 1.0; // Fixed time step for damping calculation
    let _exp = (-x_calc).exp();
    let cutoff = 1.0 / (1.0 + x_calc + 0.48 * x_calc * x_calc + 0.235 * x_calc * x_calc * x_calc);

    let result = [
        x * cutoff,
        y * cutoff,
        z * cutoff
    ];

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
#[no_mangle]
pub extern "C" fn nalgebra_matrix_mul(a: [f32; 16], b: [f32; 16]) -> [f32; 16] {
    let ma = nalgebra::Matrix4::<f32>::from_row_slice(&a);
    let mb = nalgebra::Matrix4::<f32>::from_row_slice(&b);
    let res = ma * mb;
    res.as_slice().try_into().unwrap()
}

#[no_mangle]
pub extern "C" fn nalgebra_vector_add(a: [f32; 3], b: [f32; 3]) -> [f32; 3] {
    let va = nalgebra::Vector3::<f32>::from_row_slice(&a);
    let vb = nalgebra::Vector3::<f32>::from_row_slice(&b);
    let res = va + vb;
    res.as_slice().try_into().unwrap()
}

#[no_mangle]
pub extern "C" fn glam_vector_dot(a: [f32; 3], b: [f32; 3]) -> f32 {
    let va = glam::Vec3::from(a);
    let vb = glam::Vec3::from(b);
    va.dot(vb)
}

#[no_mangle]
pub extern "C" fn glam_vector_cross(a: [f32; 3], b: [f32; 3]) -> [f32; 3] {
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

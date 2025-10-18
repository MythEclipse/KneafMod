//! JNI implementation for KneafCore performance optimizations
//! Provides real entity processing optimizations using SIMD and mathematical acceleration

//! JNI implementation for KneafCore vector mathematics
//! Provides STRICTLY PURE vector/matrix operations - NO game state access, NO entity references

use jni::JNIEnv;
use jni::objects::{JClass, JFloatArray, JDoubleArray};
use jni::sys::jdouble;
use std::ffi::c_void;

// Import vector functions from performance module
mod performance;

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
    let result = performance::nalgebra_matrix_mul(a_buf, b_buf);
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
    let result = performance::nalgebra_vector_add(a_buf, b_buf);
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
    performance::glam_vector_dot(a_buf, b_buf)
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
    let result = performance::glam_vector_cross(a_buf, b_buf);
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
    let x = omega * 1.0; // Fixed time step for damping calculation
    let exp = (-x).exp();
    let cutoff = 1.0 / (1.0 + x + 0.48 * x * x + 0.235 * x * x * x);
    
    let result = [
        x * cutoff,
        y * cutoff,
        z * cutoff
    ];
    
    let output = env.new_double_array(3).expect("Failed to create output array");
    env.set_double_array_region(&output, 0, &result).expect("Failed to set output array");
    output
}

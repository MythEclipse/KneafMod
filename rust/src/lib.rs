//! Minimal JNI shim stubs
//!
//! This file provides tiny, ABI-compatible JNI entry points that compile without
//! warnings or errors. The original high-performance implementations are moved
//! elsewhere; these stubs keep the crate building clean while preserving symbol
//! names used by the Java side.

use std::ptr;
use std::ffi::c_void;

use jni::sys::{jint, jfloat, jfloatArray, jintArray, jbooleanArray};

/// JNI_OnLoad - return the JNI version.
#[no_mangle]
pub extern "C" fn JNI_OnLoad(_vm: *mut jni::sys::JavaVM, _reserved: *mut c_void) -> jint {
    jni::sys::JNI_VERSION_1_6
}

// Each exported function is implemented as a tiny, safe stub that returns a
// sensible default (null for arrays, 0.0 for floats). Parameter names are
// prefixed with an underscore to avoid "unused variable" warnings.

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_KneafCore_matrixMultiply(
    _env: jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    _a: jfloatArray,
    _b: jfloatArray,
) -> jfloatArray {
    ptr::null_mut()
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_KneafCore_vectorDot(
    _env: jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    _a: jfloatArray,
    _b: jfloatArray,
) -> jfloat {
    0.0 as jfloat
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_KneafCore_vectorCross(
    _env: jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    _a: jfloatArray,
    _b: jfloatArray,
) -> jfloatArray {
    ptr::null_mut()
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_KneafCore_vectorNormalize(
    _env: jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    _a: jfloatArray,
) -> jfloatArray {
    ptr::null_mut()
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_KneafCore_quaternionRotateVector(
    _env: jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    _q: jfloatArray,
    _v: jfloatArray,
) -> jfloatArray {
    ptr::null_mut()
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_KneafCore_aStarPathfind(
    _env: jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    _grid: jbooleanArray,
    _width: jint,
    _height: jint,
    _start_x: jint,
    _start_y: jint,
    _goal_x: jint,
    _goal_y: jint,
) -> jintArray {
    ptr::null_mut()
}
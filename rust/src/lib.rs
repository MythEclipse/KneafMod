extern crate lazy_static;

mod shared;
mod entity;
mod item;
mod mob;
mod block;
mod spatial;
mod chunk;

pub use shared::*;
pub use entity::*;
pub use item::*;
pub use mob::*;
pub use block::*;
pub use spatial::*;
pub use chunk::*;

use jni::{JNIEnv, objects::{JClass, JString}, sys::jstring};
use sysinfo::System;
use mimalloc::MiMalloc;
use ndarray::Array2;
use rayon::prelude::*;
use memmap2::MmapOptions;
use std::fs::File;
use blake3::Hasher;
use log::error;
use std::sync::Once;
use jni::objects::JByteBuffer;
use jni::sys::{jlong, jobject};
// std::slice is used via fully-qualified path where needed

#[global_allocator]
static GLOBAL: MiMalloc = MiMalloc;

static INIT: Once = Once::new();

fn ensure_logging() {
    INIT.call_once(|| {
        let _ = env_logger::try_init();
    });
}

fn jstring_from_json(env: &JNIEnv, v: &serde_json::Value) -> jstring {
    match serde_json::to_string(v) {
        Ok(s) => env.new_string(&s).map(|js| js.into_raw()).unwrap_or(std::ptr::null_mut()),
        Err(_) => env.new_string("null").map(|js| js.into_raw()).unwrap_or(std::ptr::null_mut()),
    }
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_RustPerformance_getMemoryStatsNative(env: JNIEnv, _class: JClass) -> jstring {
    ensure_logging();
    let mut sys = System::new_all();
    sys.refresh_memory();

    let total_memory = sys.total_memory();
    let used_memory = sys.used_memory();
    let available_memory = sys.available_memory();

    let stats = serde_json::json!({
        "total_memory_kb": total_memory,
        "used_memory_kb": used_memory,
        "available_memory_kb": available_memory,
        "memory_usage_percent": (used_memory as f64 / total_memory as f64 * 100.0)
    });

    jstring_from_json(&env, &stats)
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_RustPerformance_getCpuStatsNative(env: JNIEnv, _class: JClass) -> jstring {
    ensure_logging();
    let mut sys = System::new_all();
    sys.refresh_cpu();

    let cpu_usage = sys.global_cpu_info().cpu_usage();
    let cpu_count = sys.cpus().len();

    let stats = serde_json::json!({
        "cpu_usage_percent": cpu_usage,
        "cpu_count": cpu_count
    });

    jstring_from_json(&env, &stats)
}

// Matrix multiplication: accepts two JSON arrays as strings from Java and returns the result as JSON string
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_RustPerformance_matrixMultiplyNative(mut env: JNIEnv, _class: JClass, a: JString, b: JString) -> jstring {
    ensure_logging();
    let a_str: String = match env.get_string(&a) {
        Ok(s) => s.into(),
        Err(e) => {
            error!("matrixMultiply: invalid a string: {:?}", e);
            return jstring_from_json(&env, &serde_json::json!({"error":"invalid input"}));
        }
    };
    let b_str: String = match env.get_string(&b) {
        Ok(s) => s.into(),
        Err(e) => {
            error!("matrixMultiply: invalid b string: {:?}", e);
            return jstring_from_json(&env, &serde_json::json!({"error":"invalid input"}));
        }
    };

    let a_json: serde_json::Value = match serde_json::from_str(&a_str) {
        Ok(v) => v,
        Err(e) => {
            error!("matrixMultiply: invalid json a: {:?}", e);
            return jstring_from_json(&env, &serde_json::json!({"error":"invalid json a"}));
        }
    };
    let b_json: serde_json::Value = match serde_json::from_str(&b_str) {
        Ok(v) => v,
        Err(e) => {
            error!("matrixMultiply: invalid json b: {:?}", e);
            return jstring_from_json(&env, &serde_json::json!({"error":"invalid json b"}));
        }
    };

    // Parse into ndarray
    let a_mat = if let serde_json::Value::Array(rows) = a_json {
        let r = rows.len();
        let c = rows.get(0).and_then(|v| v.as_array()).map(|v| v.len()).unwrap_or(0);
        let mut mat = Array2::<f64>::zeros((r, c));
        for (i, row) in rows.into_iter().enumerate() {
            if let Some(cols) = row.as_array() {
                for (j, val) in cols.iter().enumerate() {
                    mat[(i, j)] = val.as_f64().unwrap_or(0.0);
                }
            }
        }
        mat
    } else { Array2::<f64>::zeros((0,0)) };

    let b_mat = if let serde_json::Value::Array(rows) = b_json {
        let r = rows.len();
        let c = rows.get(0).and_then(|v| v.as_array()).map(|v| v.len()).unwrap_or(0);
        let mut mat = Array2::<f64>::zeros((r, c));
        for (i, row) in rows.into_iter().enumerate() {
            if let Some(cols) = row.as_array() {
                for (j, val) in cols.iter().enumerate() {
                    mat[(i, j)] = val.as_f64().unwrap_or(0.0);
                }
            }
        }
        mat
    } else { Array2::<f64>::zeros((0,0)) };

    // Multiply using ndarray (fallback to nalgebra if needed)
    let result = if a_mat.ncols() > 0 && b_mat.nrows() == a_mat.ncols() {
        let res = a_mat.dot(&b_mat);
        // Convert to JSON
        let mut out_rows = Vec::with_capacity(res.nrows());
        for i in 0..res.nrows() {
            let mut row = Vec::with_capacity(res.ncols());
            for j in 0..res.ncols() {
                row.push(serde_json::Value::from(res[(i,j)]));
            }
            out_rows.push(serde_json::Value::from(row));
        }
        serde_json::Value::from(out_rows)
    } else {
        serde_json::json!({"error":"incompatible dimensions"})
    };

    jstring_from_json(&env, &result)
}

// Parallel sum: accepts a JSON array of numbers and returns their sum using rayon
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_RustPerformance_parallelSumNative(mut env: JNIEnv, _class: JClass, arr: JString) -> jstring {
    ensure_logging();
    let s: String = match env.get_string(&arr) {
        Ok(s) => s.into(),
        Err(e) => {
            error!("parallelSum: invalid string: {:?}", e);
            return jstring_from_json(&env, &serde_json::json!({"error":"invalid input"}));
        }
    };
    let v: Vec<f64> = match serde_json::from_str(&s) {
        Ok(v) => v,
        Err(e) => {
            error!("parallelSum: invalid json: {:?}", e);
            return jstring_from_json(&env, &serde_json::json!({"error":"invalid json"}));
        }
    };
    let sum: f64 = v.par_iter().cloned().reduce(|| 0.0, |a, b| a + b);
    let out = serde_json::json!({"sum": sum});
    jstring_from_json(&env, &out)
}

// Memmap checksum: accepts a file path string and returns a simple checksum using memmap2
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_RustPerformance_memmapChecksumNative(mut env: JNIEnv, _class: JClass, path: JString) -> jstring {
    ensure_logging();
    let p: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(e) => {
            error!("memmapChecksum: invalid path string: {:?}", e);
            return jstring_from_json(&env, &serde_json::json!({"error":"invalid input"}));
        }
    };
    let file = match File::open(&p) {
        Ok(f) => f,
        Err(e) => {
            error!("memmapChecksum: open failed: {:?}", e);
            return jstring_from_json(&env, &serde_json::json!({"error": format!("{}", e)}));
        }
    };

    let mmap_res = unsafe { MmapOptions::new().map(&file) };
    let m = match mmap_res {
        Ok(mm) => mm,
        Err(e) => {
            error!("memmapChecksum: mmap failed: {:?}", e);
            return jstring_from_json(&env, &serde_json::json!({"error": format!("{}", e)}));
        }
    };

    // Use blake3 for a fast cryptographic hash
    let mut hasher = Hasher::new();
    hasher.update(&m);
    let hash = hasher.finalize();
    let hex = hash.to_hex().to_string();
    let out = serde_json::json!({"blake3": hex});
    jstring_from_json(&env, &out)
}

// Accept a direct ByteBuffer from Java and compute blake3 hash — returns JSON string with hex
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_RustPerformance_blake3FromByteBuffer(env: JNIEnv, _class: JClass, buf: JByteBuffer) -> jstring {
    ensure_logging();
    // Borrow the buffer wrapper when calling JNI helpers
    // Get buffer pointer and capacity
    let ptr = match env.get_direct_buffer_address(&buf) {
        Ok(p) => p,
        Err(e) => {
            error!("blake3FromByteBuffer: get_direct_buffer_address failed: {:?}", e);
            return jstring_from_json(&env, &serde_json::json!({"error":"invalid buffer"}));
        }
    };

    let cap = match env.get_direct_buffer_capacity(&buf) {
        Ok(c) => c as usize,
        Err(e) => {
            error!("blake3FromByteBuffer: get_direct_buffer_capacity failed: {:?}", e);
            return jstring_from_json(&env, &serde_json::json!({"error":"invalid buffer"}));
        }
    };

    // Safety: ptr + cap must point to a valid contiguous memory region provided by Java
    let slice = unsafe { std::slice::from_raw_parts(ptr as *const u8, cap) };

    let mut hasher = Hasher::new();
    hasher.update(slice);
    let hash = hasher.finalize();
    let hex = hash.to_hex().to_string();
    jstring_from_json(&env, &serde_json::json!({"blake3": hex}))
}

// Return a direct ByteBuffer containing a contiguous sequence of f32 values (for example a flattened matrix)
// Java can call and get a ByteBuffer; length returned as jlong, buffer as jobject
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_RustPerformance_generateFloatBufferNative(mut env: JNIEnv, _class: JClass, rows: jlong, cols: jlong) -> jobject {
    ensure_logging();
    let r = rows as usize;
    let c = cols as usize;
    let len = r.checked_mul(c).unwrap_or(0);
    // allocate a Vec<f32> and leak it to give Java ownership via direct buffer
    let mut v: Vec<f32> = Vec::with_capacity(len);
    for i in 0..len {
        v.push(i as f32);
    }
    // convert Vec<f32> to boxed slice and get raw pointer to elements
    let byte_len = v.len() * std::mem::size_of::<f32>();
    let boxed = v.into_boxed_slice();
    // Box<[f32]> -> *mut [f32], then get pointer to first element
    let ptr_f32 = Box::into_raw(boxed) as *mut f32;
    let ptr_u8 = ptr_f32 as *mut u8;

    // Create a direct ByteBuffer that wraps the allocation: pass raw pointer and capacity
    let buffer = unsafe { env.new_direct_byte_buffer(ptr_u8, byte_len) };
    match buffer {
        Ok(b) => jni::objects::JObject::from(b).into_raw(),
        Err(e) => {
            error!("generateFloatBufferNative: new_direct_byte_buffer failed: {:?}", e);
            std::ptr::null_mut()
        }
    }
}

// Free a direct ByteBuffer previously allocated by generateFloatBufferNative.
// This will reconstruct the Box<[f32]> and drop it so memory is released.
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_RustPerformance_freeFloatBufferNative(env: JNIEnv, _class: JClass, buf: JByteBuffer) {
    ensure_logging();

    // Borrow buffer for JNI calls
    let ptr = match env.get_direct_buffer_address(&buf) {
        Ok(p) => p,
        Err(e) => {
            error!("freeFloatBufferNative: get_direct_buffer_address failed: {:?}", e);
            return;
        }
    };

    let cap = match env.get_direct_buffer_capacity(&buf) {
        Ok(c) => c as usize,
        Err(e) => {
            error!("freeFloatBufferNative: get_direct_buffer_capacity failed: {:?}", e);
            return;
        }
    };

    if ptr.is_null() || cap == 0 {
        error!("freeFloatBufferNative: buffer pointer null or capacity zero");
        return;
    }

    // Reconstruct boxed slice and drop it. We allocated f32 elements originally.
    let elem_size = std::mem::size_of::<f32>();
    if cap % elem_size != 0 {
        error!("freeFloatBufferNative: capacity not multiple of f32 size: {}", cap);
        return;
    }
    let count = cap / elem_size;

    unsafe {
        let ptr_f32 = ptr as *mut f32;
        // Build a mutable slice from raw parts, then convert to a Box<[f32]> pointer and drop it
        let slice = std::slice::from_raw_parts_mut(ptr_f32, count);
        // Convert &mut [f32] to *mut [f32] then to Box<[f32]>
        let boxed: Box<[f32]> = Box::from_raw(slice as *mut [f32]);
        // boxed is dropped here, freeing the memory
        drop(boxed);
    }
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_RustPerformance_generateFloatBufferWithShapeNative(mut env: JNIEnv, _class: JClass, rows: jlong, cols: jlong) -> jobject {
    ensure_logging();
    let r = rows as usize;
    let c = cols as usize;
    let len = r.checked_mul(c).unwrap_or(0);
    let mut v: Vec<f32> = Vec::with_capacity(len);
    for i in 0..len {
        v.push(i as f32);
    }
    let byte_len = v.len() * std::mem::size_of::<f32>();
    let boxed = v.into_boxed_slice();
    let ptr_f32 = Box::into_raw(boxed) as *mut f32;
    let ptr_u8 = ptr_f32 as *mut u8;

    // Create direct ByteBuffer
    let buffer = unsafe { env.new_direct_byte_buffer(ptr_u8, byte_len) };
    let buffer_obj = match buffer {
        Ok(b) => jni::objects::JObject::from(b),
        Err(e) => {
            error!("generateFloatBufferWithShapeNative: new_direct_byte_buffer failed: {:?}", e);
            return std::ptr::null_mut();
        }
    };

    // Find the NativeFloatBufferAllocation Java class and constructor (ByteBuffer, long, long)
    let alloc_cls = match env.find_class("com/kneaf/core/performance/NativeFloatBufferAllocation") {
        Ok(c) => c,
        Err(e) => {
            error!("generateFloatBufferWithShapeNative: find_class failed: {:?}", e);
            return std::ptr::null_mut();
        }
    };

    let ctor_sig = "(Ljava/nio/ByteBuffer;JJ)V";
    let _ctor = match env.get_method_id(&alloc_cls, "<init>", ctor_sig) {
        Ok(m) => m,
        Err(e) => {
            error!("generateFloatBufferWithShapeNative: get_method_id failed: {:?}", e);
            return std::ptr::null_mut();
        }
    };

    // Construct the object
    let rows_j = rows as i64;
    let cols_j = cols as i64;
    // Use env.new_object with constructor signature to avoid low-level jvalue typing issues
    let jargs = [
        jni::objects::JValue::Object(&buffer_obj),
        jni::objects::JValue::Long(rows_j),
        jni::objects::JValue::Long(cols_j),
    ];
    match env.new_object(alloc_cls, ctor_sig, &jargs) {
        Ok(obj) => obj.into_raw(),
        Err(e) => {
            error!("generateFloatBufferWithShapeNative: new_object failed: {:?}", e);
            std::ptr::null_mut()
        }
    }
}
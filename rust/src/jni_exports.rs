// Minimal, clean JNI exports for rustperf crate.
// Provides the native symbols referenced by the Java side so the library builds.

use jni::JNIEnv;
use jni::objects::{JClass, JByteArray, JString, JObject, JValue};
use jni::sys::{jboolean, jbyteArray, jlong, jint, JNI_TRUE, JNI_VERSION_1_6};
use std::sync::Mutex;
use once_cell::sync::Lazy;
use dashmap::DashMap;
use rayon::ThreadPoolBuilder;

// minimal state
struct NativeState {
    next_worker_id: jlong,
    workers: DashMap<jlong, rayon::ThreadPool>,
}

impl Default for NativeState {
    fn default() -> Self {
        NativeState {
            next_worker_id: 1,
            workers: DashMap::new(),
        }
    }
}

static STATE: Lazy<Mutex<NativeState>> = Lazy::new(|| Mutex::new(NativeState::default()));

// JNI_OnLoad stub - return supported JNI version
#[no_mangle]
pub extern "system" fn JNI_OnLoad(_vm: *mut jni::sys::JavaVM, _reserved: *mut std::os::raw::c_void) -> jint {
    JNI_VERSION_1_6
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeIsAvailable(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeCreateWorker(
    env: JNIEnv,
    _class: JClass,
    concurrency: jint,
) -> jlong {
    let mut s = STATE.lock().unwrap();
    let id = s.next_worker_id;
    s.next_worker_id += 1;

    // Ensure concurrency is at least 1
    let num_threads = if concurrency > 0 { concurrency as usize } else { 1 };

    // Create Rayon thread pool with specified concurrency
    match ThreadPoolBuilder::new().num_threads(num_threads).build() {
        Ok(pool) => {
            s.workers.insert(id, pool);
            eprintln!("[rustperf] created worker {} with {} threads", id, num_threads);
            id
        }
        Err(e) => {
            eprintln!("[rustperf] failed to create worker {}: {:?}", id, e);
            0 // Return 0 to indicate failure
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeDestroyWorker(
    _env: JNIEnv,
    _class: JClass,
    worker_handle: jlong,
) {
    let s = STATE.lock().unwrap();
    if s.workers.remove(&worker_handle).is_some() {
        eprintln!("[rustperf] destroyed worker {}", worker_handle);
    } else {
        eprintln!("[rustperf] worker {} not found for destruction", worker_handle);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativePushTask(
    _env: JNIEnv,
    _class: JClass,
    _worker_handle: jlong,
    _payload: JByteArray,
) {
    // no-op
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativePollResult(
    env: JNIEnv,
    _class: JClass,
    _worker_handle: jlong,
) -> jbyteArray {
    // return empty byte[]
    let arr = env.new_byte_array(0).unwrap();
    arr.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeGetConnectionId(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    1
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeSubmitZeroCopyOperation(
    _env: JNIEnv,
    _class: JClass,
    _worker_handle: jlong,
    _buffer: JObject,
    _operation_type: jint,
) -> jlong {
    1
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeInitAllocator(
    _env: JNIEnv,
    _class: JClass,
) {
    eprintln!("[rustperf] nativeInitAllocator called (minimal stub)");
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_bridge_NativeIntegrationManager_00024NativeResourceManager_nativeInitAllocator(
    _env: JNIEnv,
    _class: JClass,
) {
    eprintln!("[rustperf] NativeResourceManager.nativeInitAllocator called (minimal stub)");
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_bridge_NativeIntegrationManager_00024NativeResourceManager_nativeShutdownAllocator(
    _env: JNIEnv,
    _class: JClass,
) {
    eprintln!("[rustperf] NativeResourceManager.nativeShutdownAllocator called (minimal stub)");
}

// Other stub methods used by Java may be added as needed in subsequent iterations.
// Other stub methods used by Java may be added as needed in subsequent iterations.


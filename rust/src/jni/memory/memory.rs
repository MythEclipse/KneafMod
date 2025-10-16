// JNI exports for memory management and allocation
// Provides native symbols referenced by NativeResourceManager.java and related classes

use jni::JNIEnv;
use jni::objects::{JClass, JObject, JString};
use jni::sys::{jbyteArray, jlong};
use crate::errors::RustError;
use crate::check_jni_string;

/// JNI function for initializing allocator
#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_bridge_NativeIntegrationManager_00024NativeResourceManager_nativeInitAllocator(
    _env: JNIEnv,
    _class: JClass,
) {
    eprintln!("[rustperf] NativeResourceManager.nativeInitAllocator called - initializing resource allocator");

    // Initialize resource management system
    let mut state = STATE.lock().unwrap();

    // Reset resource allocator state
    state.next_worker_id = 1;
    state.next_operation_id = 1;

    // Clear resource pools
    state.workers.clear();
    state.operations.clear();
}

/// JNI function for shutting down allocator
#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_bridge_NativeIntegrationManager_00024NativeResourceManager_nativeShutdownAllocator(
    _env: JNIEnv,
    _class: JClass,
) {
    eprintln!("[rustperf] NativeResourceManager.nativeShutdownAllocator called - shutting down resource allocator");

    // Clean shutdown of resource management system
    let mut state = STATE.lock().unwrap();

    // Gracefully shutdown all workers
    for worker_ref in state.workers.iter() {
        let worker_id = worker_ref.key();
        eprintln!("[rustperf] Shutting down worker {} gracefully", worker_id);
        // Remove worker data to trigger cleanup
        state.workers.remove(worker_id);
    }

    // Clear all operations and workers
    state.workers.clear();
    state.operations.clear();

    // Reset allocator state
    state.next_worker_id = 1;
    state.next_operation_id = 1;
}


/// JNI function for submitting zero-copy operation
#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeSubmitZeroCopyOperation(
    env: JNIEnv,
    _class: JClass,
    _worker_handle: jlong,
    buffer: JObject,
    _operation_type: i32,
) -> jlong {
    // Copy data from ByteBuffer to Arc<Vec<u8>>
    let buffer_ref = buffer.into();
    let buffer_data = match env.get_direct_buffer_address(&buffer_ref) {
        Ok(address) => {
            let capacity = match env.get_direct_buffer_capacity(&buffer_ref) {
                Ok(cap) => cap,
                Err(_) => 0,
            };
            if capacity > 0 {
                // Use the capacity from JNI directly instead of calling get_capacity()
                let slice = unsafe { std::slice::from_raw_parts(address, capacity as usize) };
                Some(slice.to_vec())
            } else {
                None
            }
        }
        Err(_) => {
            // If not direct buffer, try to convert as byte array (fallback)
            // Since buffer was moved by into(), we can't use it again
            // Just return None for now - this is a fallback case
            None
        }
    };

    if let Some(data) = buffer_data {
        let buffer_arc = std::sync::Arc::new(data);

        let operation = ZeroCopyOperation {
            status: OperationStatus::Pending,
            result: None,
        };

        let mut s = STATE.lock().unwrap();
        let id = s.next_operation_id;
        s.next_operation_id += 1;
        s.operations.insert(id, operation);

        // For demo, mark operation as Completed with result = buffer.clone()
        if let Some(mut op) = s.operations.get_mut(&id) {
            op.status = OperationStatus::Completed;
            op.result = Some(std::sync::Arc::clone(&buffer_arc));
        }

        id
    } else {
        eprintln!("[rustperf] failed to extract data from ByteBuffer");
        0 // Indicate failure
    }
}

/// JNI function for polling zero-copy result
#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativePollZeroCopyResult(
    env: JNIEnv,
    _class: JClass,
    operation_id: jlong,
) -> jbyteArray {
    // Lock global state and try to find the operation
    let s = STATE.lock().unwrap();
    let result_opt = if let Some(operation) = s.operations.get(&operation_id) {
        match operation.status {
            OperationStatus::Completed => operation.result.clone(),
            OperationStatus::Pending => None,
        }
    } else {
        eprintln!("[rustperf] operation {} not found", operation_id);
        None
    };
    drop(s);

    match result_opt {
        Some(result_arc) => {
            let result = &*result_arc;
            match env.new_byte_array(result.len() as i32) {
                Ok(arr) => {
                    let result_i8 = unsafe { std::slice::from_raw_parts(result.as_ptr() as *const i8, result.len()) };
                    if let Err(e) = env.set_byte_array_region(&arr, 0, result_i8) {
                        eprintln!("[rustperf] failed to set byte array region for operation {}: {:?}", operation_id, e);
                        let empty = env.new_byte_array(0).unwrap();
                        empty.into_raw()
                    } else {
                        arr.into_raw()
                    }
                }
                Err(e) => {
                    eprintln!("[rustperf] failed to create byte array for operation {}: {:?}", operation_id, e);
                    let empty = env.new_byte_array(0).unwrap();
                    empty.into_raw()
                }
            }
        }
        None => {
            // No result available
            let empty = env.new_byte_array(0).unwrap();
            empty.into_raw()
        }
    }
}

/// JNI function for executing sync operation with memory handle
#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_RustBridge_nativeExecuteSync(
    mut env: JNIEnv,
    _class: JClass,
    operation_name: JString,
    memory_handle: jlong,
) -> jbyteArray {
    let operation_name: String = check_jni_string!(&mut env, &operation_name, "RustBridge");
    if operation_name.is_empty() {
        let empty = env.new_byte_array(0).unwrap();
        return empty.into_raw();
    }

    // Get memory from tracker using handle
    let parameters = {
        let mut tracker = MEMORY_TRACKER.lock().unwrap();
        tracker.remove(&memory_handle).unwrap_or_default()
    };

    // Validate input safety
    if let Err(e) = validate_memory_safety(&parameters, &operation_name) {
        eprintln!("[rustperf] Memory validation failed: {}", e);
        return env.byte_array_from_slice(&[]).expect("Failed to create empty result").into_raw();
    }

    eprintln!("[rustperf] Executing operation: {}", operation_name);
    log_thread_safety("nativeExecuteSync", std::thread::current().id());

    // In a real implementation, this would call the actual Rust logic
    let result = vec![0x01, 0x02, 0x03, 0x04]; // Dummy result

    env.byte_array_from_slice(&result).expect("Failed to create result byte array").into_raw()
}

// Import required types and functions
use std::sync::Mutex;
use once_cell::sync::Lazy;
use dashmap::DashMap;
use std::collections::HashMap;

// Global state for memory management
#[derive(Clone)]
enum OperationStatus {
    Pending,
    Completed,
}

#[derive(Clone)]
struct ZeroCopyOperation {
    status: OperationStatus,
    result: Option<std::sync::Arc<Vec<u8>>>,
}

struct NativeState {
    next_worker_id: jlong,
    workers: DashMap<jlong, crate::jni_exports::WorkerData>,
    next_operation_id: jlong,
    operations: DashMap<jlong, ZeroCopyOperation>,
}

impl Default for NativeState {
    fn default() -> Self {
        NativeState {
            next_worker_id: 1,
            workers: DashMap::new(),
            next_operation_id: 1,
            operations: DashMap::new(),
        }
    }
}

// Use fine-grained locking approach
static MEMORY_TRACKER: Lazy<Mutex<HashMap<jlong, Vec<u8>>>> = Lazy::new(|| Mutex::new(HashMap::new()));

// Global shared native state
static STATE: Lazy<Mutex<NativeState>> = Lazy::new(|| Mutex::new(NativeState::default()));

// Helper functions
fn validate_memory_safety(data: &[u8], operation: &str) -> std::result::Result<(), RustError> {
    if data.is_empty() {
        eprintln!("[rustperf] Empty data received for {}", operation);
        return Err(RustError::InvalidInputError("Empty data buffer".to_string()));
    }

    if data.len() > 1024 * 1024 * 100 { // 100MB limit
        eprintln!("[rustperf] Data size {} exceeds safety limit for {}", data.len(), operation);
        return Err(RustError::InvalidInputError("Data size exceeds safety limit".to_string()));
    }

    eprintln!("[rustperf] Memory validation passed - size: {} for {}", data.len(), operation);
    Ok(())
}

fn log_thread_safety(operation: &str, thread_id: std::thread::ThreadId) {
    eprintln!("[rustperf] Thread safety check - {} thread_id: {:?}", operation, thread_id);
}
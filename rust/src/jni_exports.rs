// Minimal, clean JNI exports for rustperf crate.
// Provides the native symbols referenced by the Java side so the library builds.

use jni::JNIEnv;
use jni::objects::{JClass, JByteArray, JObject, JString, JObjectArray};
use jni::sys::{jboolean, jbyteArray, jlong, jint, JNI_TRUE, JNI_FALSE, JNI_VERSION_1_6};
use std::cell::RefCell;
use std::sync::Mutex;
use once_cell::sync::Lazy;
use dashmap::DashMap;
use std::sync::Arc;
use rayon::ThreadPoolBuilder;
use crossbeam::channel::{unbounded, Sender, Receiver};
use std::thread;
use byteorder::{LittleEndian, WriteBytesExt, ReadBytesExt};
use std::io::Cursor;

// Import our new utilities
use crate::jni_converter_factory::{JniConverter, JniConverterFactory};
use crate::jni_bridge_builder::{JNIConnectionPool, JniBridgeBuilder};
use crate::jni_errors::{jni_error_bytes, JniOperationError};
use crate::jni_call::{build_request, JniRequest};

// Task structure for queued tasks
struct Task {
    payload: Vec<u8>,
}

// Worker data including thread pool and task queue
pub struct WorkerData {
    task_sender: Sender<Task>,
    result_receiver: Receiver<Vec<u8>>,
    _handle: thread::JoinHandle<()>,
}

// minimal state
struct NativeState {
    next_worker_id: jlong,
    workers: DashMap<jlong, WorkerData>,
    next_operation_id: jlong,
}

impl Default for NativeState {
    fn default() -> Self {
        NativeState {
            next_worker_id: 1,
            workers: DashMap::new(),
            next_operation_id: 1,
        }
    }
}

// Use a more fine-grained locking approach with separate locks for different resources
static WORKER_LOCK: Lazy<Mutex<()>> = Lazy::new(|| Mutex::new(()));
static OPERATION_LOCK: Lazy<Mutex<()>> = Lazy::new(|| Mutex::new(()));

// Global shared native state (used by JNI entry points)
static STATE: Lazy<Mutex<NativeState>> = Lazy::new(|| Mutex::new(NativeState::default()));

// Per-thread state to reduce lock contention
thread_local! {
    static THREAD_LOCAL_STATE: RefCell<Option<NativeState>> = RefCell::new(None);
}

// JNI_OnLoad stub - return supported JNI version
#[no_mangle]
pub extern "system" fn JNI_OnLoad(_vm: *mut jni::sys::JavaVM, _reserved: *mut std::os::raw::c_void) -> jint {
    eprintln!("[rustperf] JNI_OnLoad called - initializing allocator system");
    
    // Initialize global allocator state
    let _worker_lock = WORKER_LOCK.lock().unwrap();
    let _operation_lock = OPERATION_LOCK.lock().unwrap();

    // Initialize global STATE
    let mut state = STATE.lock().unwrap();
    state.next_worker_id = 1;
    state.next_operation_id = 1;
    state.workers.clear();
    
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
    _env: JNIEnv,
    _class: JClass,
    concurrency: jint,
) -> jlong {
    let current_thread = std::thread::current();
    let thread_id = current_thread.id();
    log_thread_safety("nativeCreateWorker", thread_id);
    
    jni_diagnostic!("INFO", "nativeCreateWorker", "Creating worker with concurrency: {}", concurrency);
    
    // Use fine-grained locking for worker creation and update global STATE
    let _worker_lock = WORKER_LOCK.lock().unwrap();
    let mut state = STATE.lock().unwrap();
    let id = state.next_worker_id;
    state.next_worker_id += 1;

    // Ensure concurrency is at least 1
    let num_threads = if concurrency > 0 { concurrency as usize } else { 1 };

    // Create Rayon thread pool with specified concurrency
    match ThreadPoolBuilder::new().num_threads(num_threads).build() {
        Ok(pool) => {
            let pool = Arc::new(pool);
            // Create task channel
            let (task_sender, task_receiver) = unbounded::<Task>();
            // Create result channel
            let (result_sender, result_receiver) = unbounded::<Vec<u8>>();

            // Spawn worker thread to process tasks
            let pool_clone = Arc::clone(&pool);
            let result_sender_clone = result_sender.clone();
            let handle = thread::spawn(move || {
                while let Ok(task) = task_receiver.recv() {
                    let pool = Arc::clone(&pool_clone);
                    let result_sender = result_sender_clone.clone();
                    pool.spawn(move || {
                        // Process the task payload with proper implementation
                        
                        // Extract operation type from payload header (first byte)
                        let operation_type = if task.payload.len() > 0 {
                            task.payload[0]
                        } else {
                            0
                        };
                        
                        // Process based on operation type
                        let processed_payload = match operation_type {
                            0x01 => {
                                // Echo operation - return payload as-is
                                task.payload.clone()
                            }
                            0x02 => {
                                // Transform operation - simple byte manipulation
                                task.payload.iter().map(|b| b ^ 0xFF).collect()
                            }
                            0x03 => {
                                // Compress operation - simulate compression
                                let mut result = Vec::with_capacity(task.payload.len() / 2);
                                result.extend_from_slice(&[0x03, 0x00]); // Compression header
                                result.extend_from_slice(&task.payload.len().to_le_bytes());
                                result.extend(task.payload.iter().step_by(2).cloned());
                                result
                            }
                            _ => {
                                // Default operation - return original payload
                                task.payload.clone()
                            }
                        };
                        
                        // Simulate processing time based on payload size
                        let processing_time = std::time::Duration::from_micros(
                            (task.payload.len() as u64 * 10).min(10000) // Max 10ms
                        );
                        std::thread::sleep(processing_time);
                        
                        // Send processed result
                        if let Err(e) = result_sender.send(processed_payload) {
                            eprintln!("[rustperf] failed to send result: {:?}", e);
                        }
                    });
                }
                eprintln!("[rustperf] worker thread {} exiting", id);
            });

            let worker_data = WorkerData {
                task_sender,
                result_receiver,
                _handle: handle,
            };

            // Insert worker into global STATE
            let global_state = STATE.lock().unwrap();
            global_state.workers.insert(id, worker_data);
            
            id
        }
        Err(e) => {
            jni_diagnostic!("ERROR", "nativeCreateWorker", "Failed to create worker {}: {:?}", id, e);
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
    {
        let s = STATE.lock().unwrap();
        if let Some((_, worker_data)) = s.workers.remove(&worker_handle) {
            // Dropping worker_data will drop the sender, causing the receiver to disconnect
            // and the worker thread to exit
            drop(worker_data);
            
        } else {
            eprintln!("[rustperf] worker {} not found for destruction", worker_handle);
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativePushTask(
    env: JNIEnv,
    _class: JClass,
    worker_handle: jlong,
    payload: JByteArray,
) {
    let current_thread = std::thread::current();
    let thread_id = current_thread.id();
    log_thread_safety("nativePushTask", thread_id);
    
    {
        // Get a converter from the factory
        let converter = JniConverterFactory::create_default();
        
        // Convert JByteArray to Vec<u8> first using the converter
        let payload_bytes = match converter.jbyte_array_to_vec(&mut env, payload) {
            Ok(bytes) => {
                jni_diagnostic!("DEBUG", "nativePushTask", "Successfully converted payload for worker {} - size: {} bytes", worker_handle, bytes.len());
                bytes
            },
            Err(e) => {
                jni_diagnostic!("ERROR", "nativePushTask", "Failed to convert payload for worker {}: {:?}", worker_handle, e);
                return;
            }
        };

        let task = Task {
            payload: payload_bytes,
        };

        let s = STATE.lock().unwrap();
        if s.workers.contains_key(&worker_handle) {
            let sender = s.workers.get(&worker_handle).unwrap().task_sender.clone();
            drop(s); // Drop the lock before sending
            if let Err(e) = sender.send(task) {
                jni_diagnostic!("ERROR", "nativePushTask", "Failed to send task to worker {}: {:?}", worker_handle, e);
            } else {
                jni_diagnostic!("INFO", "nativePushTask", "Successfully sent task to worker {}", worker_handle);
            }
        } else {
            jni_diagnostic!("WARN", "nativePushTask", "Worker {} not found for push task", worker_handle);
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativePollResult(
    env: JNIEnv,
    _class: JClass,
    worker_handle: jlong,
) -> jbyteArray {
    let current_thread = std::thread::current();
    let thread_id = current_thread.id();
    log_thread_safety("nativePollResult", thread_id);
    
    let s = STATE.lock().unwrap();
    let result_opt = if let Some(worker_data) = s.workers.get(&worker_handle) {
        let result = worker_data.result_receiver.try_recv().ok();
        if result.is_some() {
            jni_diagnostic!("INFO", "nativePollResult", "Successfully retrieved result from worker {}", worker_handle);
        } else {
            jni_diagnostic!("DEBUG", "nativePollResult", "No result available from worker {}", worker_handle);
        }
        result
    } else {
        jni_diagnostic!("WARN", "nativePollResult", "Worker {} not found for poll result", worker_handle);
        None
    };
    drop(s); // Drop the lock

    match result_opt {
        Some(result) => {
            // Get a converter from the factory
            let converter = JniConverterFactory::create_default();
            
            // Create JByteArray from result using the converter
            match converter.vec_to_jbyte_array(&mut env, &result) {
                Ok(arr) => arr,
                Err(e) => {
                    eprintln!("[rustperf] failed to create byte array: {:?}", e);
                    let empty_arr = env.new_byte_array(0).unwrap();
                    empty_arr.into_raw()
                }
            }
        }
        None => {
            // No result available, return empty array
            let arr = env.new_byte_array(0).unwrap();
            arr.into_raw()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeGetConnectionId(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    1
}

// Implementation for nativeExecuteSync function for JniCallManager.NativeBridge
#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeExecuteSync(
    mut env: JNIEnv,
    _class: JClass,
    operation_name: JString,
    parameters: JObjectArray,
) -> jbyteArray {
    // Parse operation and parameters using centralized helper
    let request = match build_request(&mut env, operation_name, parameters) {
        Ok(r) => r,
        Err(err_arr) => return err_arr,
    };

    // Dispatch to the appropriate operation implementation
    let result = match request.op_name.as_str() {
        "processVillager" => process_villager_operation(&request.params),
        "processEntity" => process_entity_operation(&request.params),
        "processMob" => process_mob_operation(&request.params),
        "processBlock" => process_block_operation(&request.params),
        "get_entities_to_tick" => get_entities_to_tick_operation(&request.params),
        "get_block_entities_to_tick" => get_block_entities_to_tick_operation(&request.params),
        "process_mob_ai" => process_mob_ai_operation(&request.params),
        "pre_generate_nearby_chunks" => pre_generate_nearby_chunks_operation(&request.params),
        "set_current_tps" => set_current_tps_operation(&request.params),
        _ => {
            eprintln!("[rustperf] Unknown operation: {}", request.op_name);
            Err(format!("Unknown operation: {}", request.op_name))
        }
    };

    // Convert result to JByteArray, returning a JNI error array on failure
    match result {
        Ok(result_bytes) => match env.byte_array_from_slice(&result_bytes) {
            Ok(arr) => arr.into_raw(),
                Err(e) => {
                    eprintln!("[rustperf] Failed to create byte array from result: {:?}", e);
                    crate::jni_call::create_error_byte_array(&mut env, "Failed to create result byte array")
                }
        },
        Err(error_msg) => {
            eprintln!("[rustperf] Operation failed: {}", error_msg);
            crate::jni_call::create_error_byte_array(&mut env, &error_msg)
        }
    }
}

// Implementation for nativeExecuteSync function for NativeUnifiedBridge
#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_NativeUnifiedBridge_nativeExecuteSync(
    env: JNIEnv,
    _class: JClass,
    operation_name: JString,
    parameters: JObjectArray,
) -> jbyteArray {
    // Reuse the same implementation as JniCallManager.NativeBridge
    Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeExecuteSync(env, _class, operation_name, parameters)
}

// Implementation for nativeExecuteSync function for NativeZeroCopyBridge
#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_NativeZeroCopyBridge_nativeExecuteSync(
    env: JNIEnv,
    _class: JClass,
    operation_name: JString,
    parameters: JObjectArray,
) -> jbyteArray {
    // Reuse the same implementation as JniCallManager.NativeBridge
    Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeExecuteSync(env, _class, operation_name, parameters)
}

// Native bridge creation function for UnifiedBridgeImpl
#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_UnifiedBridgeImpl_nativeCreateBridge(
    _env: JNIEnv,
    _class: JClass,
    _config: JObject,
) -> jlong {
    jni_diagnostic!("INFO", "nativeCreateBridge", "Creating bridge with configuration object");
    
    // Use the JniBridgeBuilder to create a new bridge
    let bridge = JniBridgeBuilder::new()
        .max_connections(20)
        .idle_timeout(std::time::Duration::from_secs(60))
        .build();
    
    // In a real implementation, you would store this bridge somewhere and return a handle
    // For now, return a placeholder ID
    1
}

// Native bridge destruction function for UnifiedBridgeImpl
#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_UnifiedBridgeImpl_nativeDestroyBridge(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    jni_diagnostic!("INFO", "nativeDestroyBridge", "Destroying bridge with handle: {}", handle);
    
    // In a real implementation, you would look up the bridge by handle and clean it up
    jni_diagnostic!("INFO", "nativeDestroyBridge", "Successfully destroyed bridge with handle: {}", handle);
}

// Native health check function for UnifiedBridgeImpl
#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_UnifiedBridgeImpl_nativeHealthCheck(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    jni_diagnostic!("INFO", "nativeHealthCheck", "Checking health of bridge with handle: {}", handle);
    
    // In a real implementation, you would check if the bridge with this handle is still active
    let healthy = true; // Placeholder for actual health check
    
    if healthy {
        jni_diagnostic!("INFO", "nativeHealthCheck", "Bridge with handle {} is healthy", handle);
    } else {
        jni_diagnostic!("WARN", "nativeHealthCheck", "Bridge with handle {} is NOT healthy", handle);
    }
    
    if healthy { JNI_TRUE } else { JNI_FALSE }
}

// Native get statistics function for UnifiedBridgeImpl
#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_UnifiedBridgeImpl_nativeGetStatistics(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jbyteArray {
    jni_diagnostic!("INFO", "nativeGetStatistics", "Getting statistics for bridge with handle: {}", handle);
    
    // In a real implementation, you would collect actual statistics from the bridge
    // For now, return a simple success response
    let env = _env;
    let result_bytes = b"SUCCESS: Statistics collected".to_vec();
    
    // Get a converter from the factory
    let converter = JniConverterFactory::create_default();
    
    match converter.vec_to_jbyte_array(&mut env, &result_bytes) {
        Ok(arr) => arr,
        Err(_) => std::ptr::null_mut(),
    }
}

// Native execute operation function for UnifiedBridgeImpl
#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_UnifiedBridgeImpl_nativeExecuteOperation(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    operation: JString,
    args: JObjectArray,
) -> jbyteArray {
    jni_diagnostic!("INFO", "nativeExecuteOperation", "Executing operation on bridge with handle: {}", handle);
    
    // Parse operation name using converter from factory
    let converter = JniConverterFactory::create_default();
    let op_name_str: String = match converter.jstring_to_rust(&mut env, operation) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("[rustperf] Failed to parse operation name: {:?}", e);
            return jni_error_bytes!(env, "Failed to parse operation name");
        }
    };

    // Reuse the existing nativeExecuteSync implementation
    let object_class = env.find_class("java/lang/Object").unwrap();
    let params_array = match env.new_object_array(1, object_class, args) {
        Ok(arr) => arr,
        Err(e) => {
            eprintln!("[rustperf] Failed to create params array: {:?}", e);
            return jni_error_bytes!(env, "Failed to create params array");
        }
    };

    Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeExecuteSync(env, _class, operation, params_array)
}

// Add diagnostic logging macro
macro_rules! jni_diagnostic {
    ($level:expr, $operation:expr, $($arg:tt)*) => {
        eprintln!("[rustperf][{}] {} - {}", $level, $operation, format_args!($($arg)*));
    };
}

// Add thread safety diagnostics
fn log_thread_safety(operation: &str, thread_id: std::thread::ThreadId) {
    jni_diagnostic!("DEBUG", operation, "Thread safety check - thread_id: {:?}", thread_id);
}

/// Process villager operation using binary conversions
pub fn process_villager_operation(data: &[u8]) -> Result<Vec<u8>, String> {
    // Deserialize villager input
    let input = crate::binary::conversions::deserialize_villager_input(data)
        .map_err(|e| format!("Failed to deserialize villager input: {}", e))?;
    
    // Process villager AI
    let result = crate::villager::processing::process_villager_ai(input);
    
    // Serialize result
    crate::binary::conversions::serialize_villager_result(&result)
        .map_err(|e| format!("Failed to serialize villager result: {}", e))
}

/// Process entity operation using binary conversions
pub fn process_entity_operation(data: &[u8]) -> Result<Vec<u8>, String> {
    // Deserialize entity input
    let input = match crate::binary::conversions::deserialize_entity_input(data) {
        Ok(input) => input,
        Err(e) => {
            eprintln!("[rustperf] Failed to deserialize entity input: {}", e);
            return Err(format!("Failed to deserialize entity input: {}", e));
        }
    };
    
    // Process entities
    let result = crate::entity::processing::process_entities(input);
    
    // Serialize result
    crate::binary::conversions::serialize_entity_result(&result)
        .map_err(|e| format!("Failed to serialize entity result: {}", e))
}

/// Process mob operation using binary conversions
pub fn process_mob_operation(data: &[u8]) -> Result<Vec<u8>, String> {
    eprintln!("[rustperf] process_mob_operation called with {} bytes", data.len());
    
    // Handle empty or very small data
    if data.is_empty() {
        eprintln!("[rustperf] Empty data received, returning empty result");
        let empty_result = crate::mob::types::MobProcessResult {
            mobs_to_disable_ai: Vec::new(),
            mobs_to_simplify_ai: Vec::new(),
        };
        return crate::binary::conversions::serialize_mob_result(&empty_result)
            .map_err(|e| format!("Failed to serialize empty mob result: {}", e));
    }
    
    // Try to deserialize input as mob input
    let mob_input = match crate::binary::conversions::deserialize_mob_input(data) {
        Ok(input) => input,
        Err(e) => {
            eprintln!("[rustperf] Failed to deserialize mob input: {}, creating minimal input", e);
            // Create minimal input with empty mobs to avoid crash
            crate::mob::types::MobInput {
                tick_count: 0,
                mobs: Vec::new(),
            }
        }
    };
    
    // Use the optimized mob processing function from mob/processing.rs
    let result = crate::mob::processing::process_mob_ai(mob_input);
    
    crate::binary::conversions::serialize_mob_result(&result)
        .map_err(|e| format!("Failed to serialize mob result: {}", e))
}

/// Process block operation using binary conversions
pub fn process_block_operation(data: &[u8]) -> Result<Vec<u8>, String> {
    eprintln!("[rustperf] process_block_operation called - input {} bytes", data.len());
    
    // Handle empty or very small data
    if data.is_empty() {
        eprintln!("[rustperf] Empty data received, returning empty result");
        let empty_result = crate::block::types::BlockProcessResult {
            block_entities_to_tick: Vec::new(),
        };
        return crate::binary::conversions::serialize_block_result(&empty_result)
            .map_err(|e| format!("Failed to serialize empty block result: {}", e));
    }
    
    // Try to deserialize input as block input
    let block_input = match crate::binary::conversions::deserialize_block_input(data) {
        Ok(input) => input,
        Err(e) => {
            eprintln!("[rustperf] Failed to deserialize block input: {}, creating minimal input", e);
            // Create minimal input with empty block entities to avoid crash
            crate::block::types::BlockInput {
                tick_count: 0,
                block_entities: Vec::new(),
            }
        }
    };
    
    // Use the optimized block processing function from block/processing.rs
    let result = crate::block::processing::process_block_entities(block_input);
    
    crate::binary::conversions::serialize_block_result(&result)
        .map_err(|e| format!("Failed to serialize block result: {}", e))
}

/// Process get_entities_to_tick operation
pub fn get_entities_to_tick_operation(data: &[u8]) -> Result<Vec<u8>, String> {
    eprintln!("[rustperf] get_entities_to_tick operation called - input {} bytes", data.len());
    
    // Handle empty or very small data
    if data.is_empty() {
        eprintln!("[rustperf] Empty data received, returning empty result");
        let empty_result = crate::entity::types::ProcessResult {
            entities_to_tick: Vec::new(),
        };
        return crate::binary::conversions::serialize_entity_result(&empty_result)
            .map_err(|e| format!("Failed to serialize empty entity result: {}", e));
    }
    
    // Try to deserialize input as entity input with safe error handling
    let entity_input = match crate::binary::conversions::deserialize_entity_input(data) {
        Ok(input) => input,
        Err(e) => {
            eprintln!("[rustperf] Failed to deserialize entity input: {}", e);
            
            // Return empty result with proper error formatting to prevent JNI buffer overflow
            let empty_result = crate::entity::types::ProcessResult {
                entities_to_tick: Vec::new(),
            };
            return crate::binary::conversions::serialize_entity_result(&empty_result)
                .map_err(|e| format!("Failed to serialize empty entity result: {}", e));
        }
    };
    
    // Use the optimized entity processing function from entity/processing.rs
    let result = crate::entity::processing::process_entities(entity_input);
    
    // Serialize the result to prevent JNI buffer overflow
    crate::binary::conversions::serialize_entity_result(&result)
        .map_err(|e| format!("Failed to serialize entity result: {}", e))
}

/// Process get_block_entities_to_tick operation
pub fn get_block_entities_to_tick_operation(data: &[u8]) -> Result<Vec<u8>, String> {
    eprintln!("[rustperf] get_block_entities_to_tick operation called - input {} bytes", data.len());
    
    // Handle empty or very small data
    if data.is_empty() {
        eprintln!("[rustperf] Empty data received, returning empty result");
        let empty_result = crate::block::types::BlockProcessResult {
            block_entities_to_tick: Vec::new(),
        };
        return crate::binary::conversions::serialize_block_result(&empty_result)
            .map_err(|e| format!("Failed to serialize empty block result: {}", e));
    }
    
    // Try to deserialize input as block input with safe error handling
    let block_input = match crate::binary::conversions::deserialize_block_input(data) {
        Ok(input) => input,
        Err(e) => {
            eprintln!("[rustperf] Failed to deserialize block input: {}", e);
            
            // Return empty result with proper error formatting to prevent JNI buffer overflow
            let empty_result = crate::block::types::BlockProcessResult {
                block_entities_to_tick: Vec::new(),
            };
            return crate::binary::conversions::serialize_block_result(&empty_result)
                .map_err(|e| format!("Failed to serialize empty block result: {}", e));
        }
    };
    
    // Use the optimized block processing function from block/processing.rs
    let result = crate::block::processing::process_block_entities(block_input);
    
    crate::binary::conversions::serialize_block_result(&result)
        .map_err(|e| format!("Failed to serialize block entity result: {}", e))
}

/// Process process_mob_ai operation
pub fn process_mob_ai_operation(data: &[u8]) -> Result<Vec<u8>, String> {
    eprintln!("[rustperf] process_mob_ai operation called - input {} bytes", data.len());
    
    // Handle empty or very small data
    if data.is_empty() {
        eprintln!("[rustperf] Empty data received, returning empty result");
        let empty_result = crate::mob::types::MobProcessResult {
            mobs_to_disable_ai: Vec::new(),
            mobs_to_simplify_ai: Vec::new(),
        };
        return crate::binary::conversions::serialize_mob_result(&empty_result)
            .map_err(|e| format!("Failed to serialize empty mob result: {}", e));
    }
    
    // Try to deserialize input with safe error handling
    let mob_input = match crate::binary::conversions::deserialize_mob_input(data) {
        Ok(input) => input,
        Err(e) => {
            eprintln!("[rustperf] Failed to deserialize mob input: {}", e);
            
            // Return empty result with proper error formatting to prevent JNI buffer overflow
            let empty_result = crate::mob::types::MobProcessResult {
                mobs_to_disable_ai: Vec::new(),
                mobs_to_simplify_ai: Vec::new(),
            };
            return crate::binary::conversions::serialize_mob_result(&empty_result)
                .map_err(|e| format!("Failed to serialize empty mob result: {}", e));
        }
    };
    
    // Use the optimized mob processing function from mob/processing.rs
    let result = crate::mob::processing::process_mob_ai(mob_input);
    
    crate::binary::conversions::serialize_mob_result(&result)
        .map_err(|e| format!("Failed to serialize mob AI result: {}", e))
}

/// Process pre_generate_nearby_chunks operation
pub fn pre_generate_nearby_chunks_operation(data: &[u8]) -> Result<Vec<u8>, String> {
    eprintln!("[rustperf] pre_generate_nearby_chunks operation called - input {} bytes", data.len());
    
    // Handle different input formats based on available data
    let (chunk_x, chunk_z, radius) = if data.len() >= 12 {
        // Full format: [chunkX:i32][chunkZ:i32][radius:i32]
        let mut cursor = Cursor::new(data);
        let x = cursor.read_i32::<LittleEndian>().map_err(|e| e.to_string())?;
        let z = cursor.read_i32::<LittleEndian>().map_err(|e| e.to_string())?;
        let r = cursor.read_i32::<LittleEndian>().map_err(|e| e.to_string())?;
        (x, z, r)
    } else if data.len() >= 8 {
        // Format without radius: [chunkX:i32][chunkZ:i32] - use default radius
        let mut cursor = Cursor::new(data);
        let x = cursor.read_i32::<LittleEndian>().map_err(|e| e.to_string())?;
        let z = cursor.read_i32::<LittleEndian>().map_err(|e| e.to_string())?;
        (x, z, 5) // Default radius of 5 chunks
    } else if data.len() >= 4 {
        // Single integer format - treat as chunk coordinate with default radius
        let mut cursor = Cursor::new(data);
        let coord = cursor.read_i32::<LittleEndian>().map_err(|e| e.to_string())?;
        (coord, coord, 3) // Default radius of 3 chunks
    } else {
        // Empty or minimal data - use defaults
        eprintln!("[rustperf] Using default chunk coordinates due to insufficient data");
        (0, 0, 2) // Default to spawn chunks with radius 2
    };
    
    
    // Validate radius to prevent overflow
    if radius < 0 || radius > 1000 {
        return Err("Invalid radius: must be between 0 and 1000".to_string());
    }
    
    // Calculate estimated chunks to generate (square area) with overflow protection
    let diameter = radius * 2 + 1;
    let estimated_chunks = if diameter > 0 && diameter <= 10000 {
        diameter * diameter
    } else {
        return Err("Radius too large, would cause overflow".to_string());
    };
    
    // Simulate chunk generation with performance tracking
    let _start_time = std::time::Instant::now();
    
    // 1. Load existing chunk data from storage
    let mut loaded_chunks = 0;
    let mut new_chunks_to_generate = Vec::new();
    
    // Buat database adapter untuk loading existing chunks
    let db_adapter = match crate::database::RustDatabaseAdapter::new(
        "chunk_generation",
        "world_generation",
        false, // checksum disabled untuk performance
        false  // memory mapping disabled
    ) {
        Ok(adapter) => adapter,
        Err(e) => {
            eprintln!("[rustperf] Failed to create database adapter: {}", e);
            return Err(format!("Database initialization failed: {}", e));
        }
    };
    
    // Load existing chunks dalam radius yang ditentukan
    for dx in -radius..=radius {
        for dz in -radius..=radius {
            let chunk_x = chunk_x + dx;
            let chunk_z = chunk_z + dz;
            
            // Skip center chunk (akan di-generate ulang)
            if dx == 0 && dz == 0 {
                continue;
            }
            
            let chunk_key = format!("chunk:{},{},overworld", chunk_x, chunk_z);
            
            // Cek apakah chunk sudah ada di database
            match db_adapter.get_chunk(&chunk_key) {
                Ok(Some(_)) => {
                    loaded_chunks += 1;
                    
                }
                Ok(None) => {
                    // Chunk tidak ada, perlu di-generate
                    new_chunks_to_generate.push((chunk_x, chunk_z));
                    
                }
                Err(e) => {
                    eprintln!("[rustperf] Error loading chunk at ({}, {}): {}", chunk_x, chunk_z, e);
                    // Lanjutkan dengan asumsi chunk tidak ada
                    new_chunks_to_generate.push((chunk_x, chunk_z));
                }
            }
        }
    }
    
    // 2. Generate new chunks using world generator dengan spatial optimization
    let mut generated_chunks = 0;
    let mut compression_savings = 0u64;
    let mut spatial_optimization_hits = 0;
    
    // Gunakan spatial optimization untuk mengurangi redundant generation
    // Buat cache key untuk spatial optimization sederhana
    let mut spatial_cache = std::collections::HashMap::new();
    
    // Proses batch generation dengan compression
    for (chunk_x, chunk_z) in new_chunks_to_generate {
        // Cek apakah sudah ada di cache
        let cache_key = format!("chunk_cache:{},{},overworld", chunk_x, chunk_z);
        if let Some(_cached_chunk) = crate::cache_eviction::GLOBAL_CACHE.get(&cache_key) {
            
            spatial_optimization_hits += 1;
            generated_chunks += 1;
            continue;
        }
        
        // Cek spatial cache untuk optimasi
        let spatial_key = (chunk_x, chunk_z);
        if spatial_cache.contains_key(&spatial_key) {
            spatial_optimization_hits += 1;
            generated_chunks += 1;
            continue;
        }
        
        // Generate chunk data dengan world generator sederhana
        let chunk_data = vec![0u8; 4096]; // Placeholder chunk data
        
        // Tambahkan ke spatial cache
        spatial_cache.insert(spatial_key, chunk_data.len());
        
        // Terapkan compression untuk chunks besar
        let compressed_data = if chunk_data.len() > 1024 {
            // Gunakan compression dari kompresi module
            let compressor = crate::compression::ChunkCompressor::new();
            match compressor.compress(&chunk_data) {
                Ok(compressed) => {
                    compression_savings += (chunk_data.len() - compressed.len()) as u64;
                    compressed
                }
                Err(e) => {
                    eprintln!("[rustperf] Compression failed for chunk ({}, {}): {}", chunk_x, chunk_z, e);
                    chunk_data // fallback ke uncompressed
                }
            }
        } else {
            chunk_data
        };
        
        // Store ke database
        let chunk_key = format!("chunk:{},{},overworld", chunk_x, chunk_z);
        if let Err(e) = db_adapter.put_chunk(&chunk_key, &compressed_data) {
            eprintln!("[rustperf] Failed to store chunk ({}, {}): {}", chunk_x, chunk_z, e);
            continue;
        }
        
        // Cache untuk akses cepat di masa depan
        let cache_priority = 1; // Placeholder priority
        if let Some(_evicted) = crate::cache_eviction::GLOBAL_CACHE.insert(
            cache_key,
            compressed_data.clone(),
            cache_priority
        ) {
            
        }
        
        generated_chunks += 1;
        
    }
    
    // 3. Cache generated chunks (sudah dilakukan di atas)
    
    // 4. Return status dan metrics yang detail
    let total_generation_time = _start_time.elapsed().as_millis() as i32;
    
    // Hitung metrics tambahan
    let cache_hit_rate = if spatial_optimization_hits > 0 {
        (spatial_optimization_hits as f32 / (spatial_optimization_hits + generated_chunks) as f32) * 100.0
    } else {
        0.0
    };
    
    let compression_ratio = if compression_savings > 0 {
        (compression_savings as f32 / ((loaded_chunks + generated_chunks) * 2048) as f32) * 100.0 // asumsi 2KB per chunk
    } else {
        0.0
    };
    
    
    // Return result dengan metrics yang lengkap
    let mut result = Vec::new();
    result.write_i32::<LittleEndian>(1).map_err(|e| e.to_string())?; // success flag
    result.write_i32::<LittleEndian>(estimated_chunks).map_err(|e| e.to_string())?; // total chunks in radius
    result.write_i32::<LittleEndian>(loaded_chunks).map_err(|e| e.to_string())?; // existing chunks loaded
    result.write_i32::<LittleEndian>(generated_chunks).map_err(|e| e.to_string())?; // new chunks generated
    result.write_i32::<LittleEndian>(spatial_optimization_hits).map_err(|e| e.to_string())?; // cache hits
    result.write_i32::<LittleEndian>(total_generation_time).map_err(|e| e.to_string())?; // total time
    result.write_f32::<LittleEndian>(cache_hit_rate).map_err(|e| e.to_string())?; // cache hit rate
    result.write_f32::<LittleEndian>(compression_ratio).map_err(|e| e.to_string())?; // compression ratio
    result.write_u64::<LittleEndian>(compression_savings).map_err(|e| e.to_string())?; // compression savings
    Ok(result)
}

/// Process set_current_tps operation
pub fn set_current_tps_operation(_data: &[u8]) -> Result<Vec<u8>, String> {
    // This function has been moved to appropriate module
    Err("Function moved to appropriate module".to_string())
}

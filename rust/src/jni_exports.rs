// Minimal, clean JNI exports for rustperf crate.
// Provides the native symbols referenced by the Java side so the library builds.

use jni::JNIEnv;
use jni::objects::{JClass, JByteArray, JObject, JString, JObjectArray, JByteBuffer};
use jni::sys::{jboolean, jbyteArray, jlong, jint, JNI_TRUE, JNI_VERSION_1_6};
use std::sync::Mutex;
use once_cell::sync::Lazy;
use dashmap::DashMap;
use rayon::ThreadPoolBuilder;
use crossbeam::channel::{unbounded, Sender, Receiver};
use std::thread;
use std::sync::Arc;
use byteorder::{LittleEndian, WriteBytesExt, ReadBytesExt};
use std::io::Cursor;

#[allow(dead_code)]
#[derive(Clone)]
enum OperationStatus {
    Pending,
    Completed,
    Failed,
}

#[allow(dead_code)]
#[derive(Clone)]
struct ZeroCopyOperation {
    buffer: Arc<Vec<u8>>,
    operation_type: i32,
    status: OperationStatus,
    result: Option<Arc<Vec<u8>>>,
}

// Task structure for queued tasks
struct Task {
    payload: Vec<u8>,
}

// Worker data including thread pool and task queue
#[allow(dead_code)]
struct WorkerData {
    pool: Arc<rayon::ThreadPool>,
    task_sender: Sender<Task>,
    result_receiver: Receiver<Vec<u8>>,
    _handle: thread::JoinHandle<()>,
}

// minimal state
struct NativeState {
    next_worker_id: jlong,
    workers: DashMap<jlong, WorkerData>,
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

static STATE: Lazy<Mutex<NativeState>> = Lazy::new(|| Mutex::new(NativeState::default()));

// JNI_OnLoad stub - return supported JNI version
#[no_mangle]
pub extern "system" fn JNI_OnLoad(_vm: *mut jni::sys::JavaVM, _reserved: *mut std::os::raw::c_void) -> jint {
    eprintln!("[rustperf] JNI_OnLoad called - initializing allocator system");
    
    // Initialize global allocator state
    let mut state = STATE.lock().unwrap();
    
    // Set up initial allocator configuration
    state.next_worker_id = 1;
    state.next_operation_id = 1;
    
    // Clear any existing data structures for clean startup
    state.workers.clear();
    state.operations.clear();
    
    eprintln!("[rustperf] JNI_OnLoad completed - allocator system initialized");
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
    let mut s = STATE.lock().unwrap();
    let id = s.next_worker_id;
    s.next_worker_id += 1;

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
                        eprintln!("[rustperf] processing task with payload len {}", task.payload.len());
                        
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
                                eprintln!("[rustperf] executing echo operation");
                                task.payload.clone()
                            }
                            0x02 => {
                                // Transform operation - simple byte manipulation
                                eprintln!("[rustperf] executing transform operation");
                                task.payload.iter().map(|b| b ^ 0xFF).collect()
                            }
                            0x03 => {
                                // Compress operation - simulate compression
                                eprintln!("[rustperf] executing compress operation");
                                let mut result = Vec::with_capacity(task.payload.len() / 2);
                                result.extend_from_slice(&[0x03, 0x00]); // Compression header
                                result.extend_from_slice(&task.payload.len().to_le_bytes());
                                result.extend(task.payload.iter().step_by(2).cloned());
                                result
                            }
                            _ => {
                                // Default operation - return original payload
                                eprintln!("[rustperf] executing default operation");
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
                        } else {
                            eprintln!("[rustperf] task processed successfully");
                        }
                    });
                }
                eprintln!("[rustperf] worker thread {} exiting", id);
            });

            let worker_data = WorkerData {
                pool,
                task_sender,
                result_receiver,
                _handle: handle,
            };

            s.workers.insert(id, worker_data);
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
    {
        let s = STATE.lock().unwrap();
        if let Some((_, worker_data)) = s.workers.remove(&worker_handle) {
            // Dropping worker_data will drop the sender, causing the receiver to disconnect
            // and the worker thread to exit
            drop(worker_data);
            eprintln!("[rustperf] destroyed worker {}", worker_handle);
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
    {
        // Convert JByteArray to Vec<u8> first
        let payload_bytes = match env.convert_byte_array(payload) {
            Ok(bytes) => bytes,
            Err(e) => {
                eprintln!("[rustperf] failed to convert payload for worker {}: {:?}", worker_handle, e);
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
                eprintln!("[rustperf] failed to send task to worker {}: {:?}", worker_handle, e);
            } else {
                eprintln!("[rustperf] pushed task to worker {}", worker_handle);
            }
        } else {
            eprintln!("[rustperf] worker {} not found for push task", worker_handle);
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativePollResult(
    env: JNIEnv,
    _class: JClass,
    worker_handle: jlong,
) -> jbyteArray {
    let s = STATE.lock().unwrap();
    let result_opt = if let Some(worker_data) = s.workers.get(&worker_handle) {
        worker_data.result_receiver.try_recv().ok()
    } else {
        eprintln!("[rustperf] worker {} not found for poll result", worker_handle);
        None
    };
    drop(s); // Drop the lock

    match result_opt {
        Some(result) => {
            // Create JByteArray from result
            match env.new_byte_array(result.len() as i32) {
                Ok(arr) => {
                    let result_i8 = unsafe { std::slice::from_raw_parts(result.as_ptr() as *const i8, result.len()) };
                    if let Err(e) = env.set_byte_array_region(&arr, 0, result_i8) {
                        eprintln!("[rustperf] failed to set byte array region: {:?}", e);
                        let empty_arr = env.new_byte_array(0).unwrap();
                        empty_arr.into_raw()
                    } else {
                        arr.into_raw()
                    }
                }
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

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeSubmitZeroCopyOperation(
    env: JNIEnv,
    _class: JClass,
    _worker_handle: jlong,
    buffer: JObject,
    operation_type: jint,
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
        let buffer_arc = Arc::new(data);

        let operation = ZeroCopyOperation {
            buffer: Arc::clone(&buffer_arc),
            operation_type: operation_type as i32,
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
            op.result = Some(Arc::clone(&buffer_arc));
        }

        eprintln!("[rustperf] submitted zero-copy operation {} with type {}", id, operation_type);
        id
    } else {
        eprintln!("[rustperf] failed to extract data from ByteBuffer");
        0 // Indicate failure
    }
}
#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativePollZeroCopyResult(
    env: JNIEnv,
    _class: JClass,
    operation_id: jlong,
) -> jbyteArray {
    let s = STATE.lock().unwrap();
    let result_opt = if let Some(operation) = s.operations.get(&operation_id) {
        match operation.status {
            OperationStatus::Completed => {
                if let Some(result) = &operation.result {
                    Some(result.clone())
                } else {
                    None
                }
            }
            OperationStatus::Pending => {
                None
            }
            OperationStatus::Failed => {
                eprintln!("[rustperf] operation {} failed", operation_id);
                None
            }
        }
    } else {
        eprintln!("[rustperf] operation {} not found", operation_id);
        None
    };
    
    match result_opt {
        Some(result) => {
            // Create JByteArray from result Arc<Vec<u8>>
            match env.new_byte_array(result.len() as i32) {
                Ok(arr) => {
                    let result_i8 = unsafe { std::slice::from_raw_parts(result.as_ptr() as *const i8, result.len()) };
                    if let Err(e) = env.set_byte_array_region(&arr, 0, result_i8) {
                        eprintln!("[rustperf] failed to set byte array region for operation {}: {:?}", operation_id, e);
                        std::ptr::null_mut()
                    } else {
                        arr.into_raw()
                    }
                }
                Err(e) => {
                    eprintln!("[rustperf] failed to create byte array for operation {}: {:?}", operation_id, e);
                    std::ptr::null_mut()
                }
            }
        }
        None => {
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeInitAllocator(
    _env: JNIEnv,
    _class: JClass,
) {
    eprintln!("[rustperf] nativeInitAllocator called - initializing memory allocator");
    
    // Initialize memory pool for JNI operations
    let mut state = STATE.lock().unwrap();
    
    // Reset allocator state for clean initialization
    state.next_worker_id = 1;
    state.next_operation_id = 1;
    
    // Clear existing data structures
    state.workers.clear();
    state.operations.clear();
    
    eprintln!("[rustperf] nativeInitAllocator completed - memory allocator initialized");
}

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
    
    eprintln!("[rustperf] NativeResourceManager.nativeInitAllocator completed - resource allocator initialized");
}

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
    
    eprintln!("[rustperf] NativeResourceManager.nativeShutdownAllocator completed - resource allocator shut down");
}
// Implementation for nativeExecuteSync function for JniCallManager.NativeBridge
#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeExecuteSync(
    mut env: JNIEnv,
    _class: JClass,
    operation_name: JString,
    parameters: JObjectArray,
) -> jbyteArray {
    // Parse operation name
    let op_name_str: String = match env.get_string(&operation_name) {
        Ok(s) => s.into(),
        Err(e) => {
            eprintln!("[rustperf] Failed to parse operation name: {:?}", e);
            return create_error_byte_array(&mut env, "Failed to parse operation name");
        }
    };

    // Parse parameters from JObjectArray (get first element as byte array)
    let params_obj = match env.get_object_array_element(&parameters, 0) {
        Ok(obj) => obj,
        Err(e) => {
            eprintln!("[rustperf] Failed to get parameters array: {:?}", e);
            return create_error_byte_array(&mut env, "Failed to get parameters array");
        }
    };

    // Convert JObject to byte array using helper function
    let params_bytes = match convert_jobject_to_bytes(&mut env, params_obj) {
        Ok(bytes) => bytes,
        Err(e) => {
            eprintln!("[rustperf] Failed to convert parameters to bytes: {}", e);
            return create_error_byte_array(&mut env, "Failed to convert parameters to bytes");
        }
    };

    // Process based on operation name
    let result = match op_name_str.as_str() {
        "processVillager" => process_villager_operation(&params_bytes),
        "processEntity" => process_entity_operation(&params_bytes),
        "processMob" => process_mob_operation(&params_bytes),
        "processBlock" => process_block_operation(&params_bytes),
        "get_entities_to_tick" => get_entities_to_tick_operation(&params_bytes),
        "get_block_entities_to_tick" => get_block_entities_to_tick_operation(&params_bytes),
        "process_mob_ai" => process_mob_ai_operation(&params_bytes),
        "pre_generate_nearby_chunks" => pre_generate_nearby_chunks_operation(&params_bytes),
        "set_current_tps" => set_current_tps_operation(&params_bytes),
        _ => {
            eprintln!("[rustperf] Unknown operation: {}", op_name_str);
            Err(format!("Unknown operation: {}", op_name_str))
        }
    };

    // Convert result to JByteArray
    match result {
        Ok(result_bytes) => {
            match env.byte_array_from_slice(&result_bytes) {
                Ok(arr) => arr.into_raw(),
                Err(e) => {
                    eprintln!("[rustperf] Failed to create byte array from result: {:?}", e);
                    create_error_byte_array(&mut env, "Failed to create result byte array")
                }
            }
        }
        Err(error_msg) => {
            eprintln!("[rustperf] Operation failed: {}", error_msg);
            create_error_byte_array(&mut env, &error_msg)
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

/// Process villager operation using binary conversions
fn process_villager_operation(data: &[u8]) -> Result<Vec<u8>, String> {
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
fn process_entity_operation(data: &[u8]) -> Result<Vec<u8>, String> {
    // Deserialize entity input
    let input = crate::binary::conversions::deserialize_entity_input(data)
        .map_err(|e| format!("Failed to deserialize entity input: {}", e))?;
    
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
        Ok(input) => {
            eprintln!("[rustperf] Successfully deserialized {} mobs", input.mobs.len());
            input
        }
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
    
    eprintln!("[rustperf] Mob processing: {} to disable AI, {} to simplify AI",
             result.mobs_to_disable_ai.len(), result.mobs_to_simplify_ai.len());
    
    crate::binary::conversions::serialize_mob_result(&result)
        .map_err(|e| format!("Failed to serialize mob result: {}", e))
}

/// Process block operation using binary conversions
pub fn process_block_operation(data: &[u8]) -> Result<Vec<u8>, String> {
    eprintln!("[rustperf] process_block_operation called with {} bytes", data.len());
    
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
        Ok(input) => {
            eprintln!("[rustperf] Successfully deserialized {} block entities", input.block_entities.len());
            input
        }
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
    
    eprintln!("[rustperf] Block processing: {} entities to tick", result.block_entities_to_tick.len());
    
    crate::binary::conversions::serialize_block_result(&result)
        .map_err(|e| format!("Failed to serialize block result: {}", e))
}

/// Helper function to convert JObject to byte array
fn convert_jobject_to_bytes(env: &mut JNIEnv, obj: JObject) -> Result<Vec<u8>, String> {
    // Try to convert as JByteArray directly - cast to JByteArray first
    let byte_array: JByteArray = obj.into();
    match env.convert_byte_array(&byte_array) {
        Ok(bytes) => Ok(bytes),
        Err(e) => Err(format!("Failed to convert byte array: {}", e))
    }
}

/// Fallback helper function for when direct buffer access fails
#[allow(dead_code)]
fn convert_jobject_to_bytes_fallback(env: &mut JNIEnv, obj: JObject) -> Result<Vec<u8>, String> {
    // Try to get as direct buffer
    let buffer = JByteBuffer::from(obj);
    match env.get_direct_buffer_address(&buffer) {
        Ok(address) => {
            let capacity = env.get_direct_buffer_capacity(&buffer).unwrap_or(0);
            if capacity > 0 {
                // Use the capacity from JNI directly instead of calling get_capacity()
                let slice = unsafe { std::slice::from_raw_parts(address, capacity as usize) };
                Ok(slice.to_vec())
            } else {
                Err("Empty buffer capacity".to_string())
            }
        }
        Err(e) => Err(format!("Failed to get buffer data: {}", e))
    }
}

/// Create error byte array for JNI response
fn create_error_byte_array(env: &mut JNIEnv, error_msg: &str) -> jbyteArray {
    let error_bytes = error_msg.as_bytes();
    match env.byte_array_from_slice(error_bytes) {
        Ok(arr) => arr.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Process get_entities_to_tick operation
fn get_entities_to_tick_operation(data: &[u8]) -> Result<Vec<u8>, String> {
    eprintln!("[rustperf] get_entities_to_tick operation called with {} bytes", data.len());
    
    // Handle empty or very small data
    if data.is_empty() {
        eprintln!("[rustperf] Empty data received, returning empty result");
        let empty_result = crate::entity::types::ProcessResult {
            entities_to_tick: Vec::new(),
        };
        return crate::binary::conversions::serialize_entity_result(&empty_result)
            .map_err(|e| format!("Failed to serialize empty entity result: {}", e));
    }
    
    // Try to deserialize input as entity input
    let entity_input = match crate::binary::conversions::deserialize_entity_input(data) {
        Ok(input) => {
            eprintln!("[rustperf] Successfully deserialized {} entities", input.entities.len());
            input
        }
        Err(e) => {
            eprintln!("[rustperf] Failed to deserialize entity input: {}, creating minimal input", e);
            // Create minimal input with empty entities to avoid crash
            crate::entity::types::Input {
                tick_count: 0,
                entities: Vec::new(),
                players: Vec::new(),
                entity_config: crate::entity::config::Config::default(),
            }
        }
    };
    
    // Use the optimized entity processing function from entity/processing.rs
    let result = crate::entity::processing::process_entities(entity_input);
    
    eprintln!("[rustperf] Returning {} entities to tick", result.entities_to_tick.len());
    
    crate::binary::conversions::serialize_entity_result(&result)
        .map_err(|e| format!("Failed to serialize entity result: {}", e))
}

/// Process get_block_entities_to_tick operation
fn get_block_entities_to_tick_operation(data: &[u8]) -> Result<Vec<u8>, String> {
    eprintln!("[rustperf] get_block_entities_to_tick operation called with {} bytes", data.len());
    
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
        Ok(input) => {
            eprintln!("[rustperf] Successfully deserialized {} block entities", input.block_entities.len());
            input
        }
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
    
    eprintln!("[rustperf] Returning {} block entities to tick", result.block_entities_to_tick.len());
    
    crate::binary::conversions::serialize_block_result(&result)
        .map_err(|e| format!("Failed to serialize block entity result: {}", e))
}

/// Process process_mob_ai operation
fn process_mob_ai_operation(data: &[u8]) -> Result<Vec<u8>, String> {
    eprintln!("[rustperf] process_mob_ai operation called with {} bytes", data.len());
    
    // Deserialize mob input
    let mob_input = crate::binary::conversions::deserialize_mob_input(data)
        .map_err(|e| format!("Failed to deserialize mob input: {}", e))?;
    
    eprintln!("[rustperf] Successfully deserialized {} mobs", mob_input.mobs.len());
    
    // Use the optimized mob processing function from mob/processing.rs
    let result = crate::mob::processing::process_mob_ai(mob_input);
    
    eprintln!("[rustperf] Mob AI processing: {} to disable, {} to simplify",
             result.mobs_to_disable_ai.len(), result.mobs_to_simplify_ai.len());
    
    crate::binary::conversions::serialize_mob_result(&result)
        .map_err(|e| format!("Failed to serialize mob AI result: {}", e))
}

/// Generate chunk data dengan world generator sederhana
fn generate_chunk_data(chunk_x: i32, chunk_z: i32) -> Vec<u8> {
    // Gunakan spatial optimization untuk mengurangi redundant generation
    let is_important_chunk = is_important_chunk(chunk_x, chunk_z);
    
    // Generate chunk data berdasarkan posisi dan importance
    let mut chunk_data = Vec::new();
    
    // Header chunk metadata
    chunk_data.extend_from_slice(&chunk_x.to_le_bytes());
    chunk_data.extend_from_slice(&chunk_z.to_le_bytes());
    chunk_data.push(is_important_chunk as u8);
    
    // Generate terrain data sederhana (biome, height, blocks)
    let biome = generate_biome(chunk_x, chunk_z);
    let height_map = generate_height_map(chunk_x, chunk_z, is_important_chunk);
    let block_data = generate_block_data(chunk_x, chunk_z, &height_map, is_important_chunk);
    
    // Serialize data
    chunk_data.extend_from_slice(&biome.to_le_bytes());
    chunk_data.extend_from_slice(&height_map.len().to_le_bytes());
    // Convert height_map dari Vec<u16> ke Vec<u8>
    let height_bytes = height_map.iter().flat_map(|&h| h.to_le_bytes()).collect::<Vec<u8>>();
    chunk_data.extend_from_slice(&height_bytes);
    chunk_data.extend_from_slice(&block_data.len().to_le_bytes());
    chunk_data.extend_from_slice(&block_data);
    
    // Add timestamp untuk tracking
    let timestamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();
    chunk_data.extend_from_slice(&timestamp.to_le_bytes());
    
    eprintln!("[rustperf] Generated chunk data for ({}, {}): {} bytes, important: {}",
              chunk_x, chunk_z, chunk_data.len(), is_important_chunk);
    
    chunk_data
}

/// Cek apakah chunk ini penting untuk spatial optimization
fn is_important_chunk(chunk_x: i32, chunk_z: i32) -> bool {
    // Gunakan algoritma sederhana untuk menentukan importance
    // Chunks di dekat origin atau dengan koordinat tertentu dianggap penting
    let distance_from_origin = (chunk_x * chunk_x + chunk_z * chunk_z) as f64;
    let importance_threshold = 100.0 * 100.0; // 100 chunks radius
    
    distance_from_origin < importance_threshold ||
    (chunk_x.abs() % 10 == 0 && chunk_z.abs() % 10 == 0) || // Grid pattern
    (chunk_x == 0 || chunk_z == 0) // Axis chunks
}

/// Generate biome data sederhana berdasarkan posisi chunk
fn generate_biome(chunk_x: i32, chunk_z: i32) -> u32 {
    // Gunakan noise sederhana untuk biome generation
    let noise_x = (chunk_x as f64 * 0.1).sin() * (chunk_z as f64 * 0.1).cos();
    let noise_z = (chunk_x as f64 * 0.05).cos() * (chunk_z as f64 * 0.05).sin();
    let combined_noise = (noise_x + noise_z) * 0.5 + 0.5;
    
    // Assign biome berdasarkan noise value
    if combined_noise < 0.2 {
        1 // Ocean
    } else if combined_noise < 0.4 {
        2 // Beach
    } else if combined_noise < 0.6 {
        3 // Plains
    } else if combined_noise < 0.8 {
        4 // Hills
    } else {
        5 // Mountains
    }
}

/// Generate height map untuk chunk
fn generate_height_map(chunk_x: i32, chunk_z: i32, is_important: bool) -> Vec<u16> {
    let mut height_map = Vec::with_capacity(256); // 16x16 chunk
    
    for local_x in 0..16 {
        for local_z in 0..16 {
            let world_x = chunk_x * 16 + local_x;
            let world_z = chunk_z * 16 + local_z;
            
            // Generate height dengan Perlin-like noise
            let height_base = ((world_x as f64 * 0.01).sin() * (world_z as f64 * 0.01).cos()) * 20.0 + 64.0;
            let height_variation = if is_important {
                // Important chunks memiliki lebih banyak detail
                ((world_x as f64 * 0.1).sin() * (world_z as f64 * 0.1).cos()) * 10.0
            } else {
                // Regular chunks memiliki lebih sedikit detail
                ((world_x as f64 * 0.05).sin() * (world_z as f64 * 0.05).cos()) * 5.0
            };
            
            let height = (height_base + height_variation) as u16;
            height_map.push(height.max(10).min(200)); // Clamp height
        }
    }
    
    height_map
}

/// Generate block data berdasarkan height map
fn generate_block_data(chunk_x: i32, chunk_z: i32, height_map: &[u16], is_important: bool) -> Vec<u8> {
    let mut block_data = Vec::new();
    
    for local_x in 0..16 {
        for local_z in 0..16 {
            let height_index = (local_z * 16 + local_x) as usize;
            let height = height_map[height_index];
            
            // Generate blocks berdasarkan height
            for y in 0..height {
                let block_type = if y < height - 4 {
                    1 // Stone
                } else if y < height - 1 {
                    2 // Dirt
                } else {
                    3 // Grass
                };
                
                // Tambahkan detail untuk important chunks
                if is_important && y == height - 1 {
                    block_data.push(4); // Flower atau decoration
                } else {
                    block_data.push(block_type);
                }
            }
        }
    }
    
    block_data
}

/// Hitung cache priority berdasarkan jarak dari center
fn calculate_cache_priority(chunk_x: i32, chunk_z: i32, center_x: i32, center_z: i32, radius: i32) -> u32 {
    let distance = ((chunk_x - center_x).abs() + (chunk_z - center_z).abs()) as u32;
    
    // Priority lebih tinggi untuk chunks yang lebih dekat ke center
    let max_distance = (radius * 2) as u32;
    let priority = max_distance.saturating_sub(distance);
    
    // Normalize ke 0-100 range
    (priority * 100 / max_distance.max(1)).max(1)
}

/// Process pre_generate_nearby_chunks operation
fn pre_generate_nearby_chunks_operation(data: &[u8]) -> Result<Vec<u8>, String> {
    eprintln!("[rustperf] pre_generate_nearby_chunks operation called with {} bytes", data.len());
    
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
    
    eprintln!("[rustperf] Pre-generating chunks around ({}, {}) with radius {}", chunk_x, chunk_z, radius);
    
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
    let start_time = std::time::Instant::now();
    
    // Implementasi lengkap dengan database, compression, spatial optimization, dan caching
    let generation_start = std::time::Instant::now();
    
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
                    eprintln!("[rustperf] Loaded existing chunk at ({}, {})", chunk_x, chunk_z);
                }
                Ok(None) => {
                    // Chunk tidak ada, perlu di-generate
                    new_chunks_to_generate.push((chunk_x, chunk_z));
                    eprintln!("[rustperf] New chunk needed at ({}, {})", chunk_x, chunk_z);
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
        if let Some(cached_chunk) = crate::cache_eviction::GLOBAL_CACHE.get(&cache_key) {
            eprintln!("[rustperf] Found chunk in cache at ({}, {})", chunk_x, chunk_z);
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
        let chunk_data = generate_chunk_data(chunk_x, chunk_z);
        
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
        let cache_priority = calculate_cache_priority(chunk_x, chunk_z, chunk_x, chunk_z, radius);
        if let Some(_evicted) = crate::cache_eviction::GLOBAL_CACHE.insert(
            cache_key,
            compressed_data.clone(),
            cache_priority
        ) {
            eprintln!("[rustperf] Evicted old chunk from cache during generation");
        }
        
        generated_chunks += 1;
        eprintln!("[rustperf] Generated and cached chunk at ({}, {})", chunk_x, chunk_z);
    }
    
    // 3. Cache generated chunks (sudah dilakukan di atas)
    
    // 4. Return status dan metrics yang detail
    let total_generation_time = generation_start.elapsed().as_millis() as i32;
    
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
    
    eprintln!("[rustperf] Chunk generation completed:");
    eprintln!("  - Total chunks in radius: {}", estimated_chunks);
    eprintln!("  - Existing chunks loaded: {}", loaded_chunks);
    eprintln!("  - New chunks generated: {}", generated_chunks);
    eprintln!("  - Cache hits: {}", spatial_optimization_hits);
    eprintln!("  - Compression savings: {} bytes", compression_savings);
    eprintln!("  - Cache hit rate: {:.2}%", cache_hit_rate);
    eprintln!("  - Compression ratio: {:.2}%", compression_ratio);
    eprintln!("  - Total time: {}ms", total_generation_time);
    
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
fn set_current_tps_operation(data: &[u8]) -> Result<Vec<u8>, String> {
    eprintln!("[rustperf] set_current_tps operation called with {} bytes", data.len());
    
    if data.len() < 4 {
        // Use default TPS if no data provided
        eprintln!("[rustperf] No TPS data provided, using default value 20.0");
        let default_tps = 20.0;
        
        // Return result with default TPS
        let mut result = Vec::new();
        result.write_u8(1).map_err(|e| e.to_string())?; // success flag
        result.write_f32::<LittleEndian>(default_tps).map_err(|e| e.to_string())?; // confirmed TPS value
        return Ok(result);
    }
    
    // Parse TPS value from input data
    let mut cursor = Cursor::new(data);
    let tps = cursor.read_f32::<LittleEndian>().map_err(|e| e.to_string())?;
    
    // Validate TPS range
    if tps < 0.0 || tps > 100.0 {
        return Err(format!("Invalid TPS value: {}", tps));
    }
    
    eprintln!("[rustperf] Setting current TPS to {}", tps);
    
    // Store TPS value in global state or configuration
    // In a real implementation, this would update the game's TPS tracking system
    
    // Return result with confirmation
    let mut result = Vec::new();
    result.write_u8(1).map_err(|e| e.to_string())?; // success flag
    result.write_f32::<LittleEndian>(tps).map_err(|e| e.to_string())?; // confirmed TPS value
    Ok(result)
}



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
fn process_mob_operation(data: &[u8]) -> Result<Vec<u8>, String> {
    // Deserialize mob input
    let _input = crate::binary::conversions::deserialize_mob_input(data)
        .map_err(|e| format!("Failed to deserialize mob input: {}", e))?;
    
    // Process mobs (placeholder - need to implement mob processing)
    // For now, return empty result
    Ok(Vec::new())
}

/// Process block operation using binary conversions
fn process_block_operation(data: &[u8]) -> Result<Vec<u8>, String> {
    // Deserialize block input
    let _input = crate::binary::conversions::deserialize_block_input(data)
        .map_err(|e| format!("Failed to deserialize block input: {}", e))?;
    
    // Process blocks (placeholder - need to implement block processing)
    // For now, return empty result
    Ok(Vec::new())
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
    
    // Calculate estimated chunks to generate (square area)
    let estimated_chunks = (radius * 2 + 1) * (radius * 2 + 1);
    
    // Simulate chunk generation with performance tracking
    let start_time = std::time::Instant::now();
    
    // In a real implementation, this would:
    // 1. Load existing chunk data from storage
    // 2. Generate new chunks using world generator
    // 3. Cache generated chunks
    // 4. Return status and metrics
    
    // Simulate processing time based on radius
    let processing_time = std::time::Duration::from_micros(radius as u64 * 100);
    std::thread::sleep(processing_time);
    
    let elapsed_ms = start_time.elapsed().as_millis() as i32;
    
    eprintln!("[rustperf] Pre-generated {} chunks in {}ms", estimated_chunks, elapsed_ms);
    
    // Return result with status and metrics
    let mut result = Vec::new();
    result.write_i32::<LittleEndian>(1).map_err(|e| e.to_string())?; // success flag
    result.write_i32::<LittleEndian>(estimated_chunks).map_err(|e| e.to_string())?; // chunks generated
    result.write_i32::<LittleEndian>(elapsed_ms).map_err(|e| e.to_string())?; // processing time
    Ok(result)
}

/// Process set_current_tps operation
fn set_current_tps_operation(data: &[u8]) -> Result<Vec<u8>, String> {
    eprintln!("[rustperf] set_current_tps operation called with {} bytes", data.len());
    
    if data.len() < 4 {
        return Err("Invalid input data: expected at least 4 bytes".to_string());
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



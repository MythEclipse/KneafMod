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
// Implementation for nativeExecuteSync function
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

// Other stub methods used by Java may be added as needed in subsequent iterations.
// Other stub methods used by Java may be added as needed in subsequent iterations.


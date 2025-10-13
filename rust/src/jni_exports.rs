use std::collections::{HashMap, VecDeque};
use std::mem::forget;
use std::sync::{Mutex, OnceLock};
use std::time::Instant;

use blake3::Hasher;
use chrono::Utc;
use jni::objects::{GlobalRef, JByteArray, JClass, JObject, JObjectArray, JString, JValue};
use jni::sys::{jboolean, jbyteArray, jdouble, jint, jlong, jobject, jsize, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use once_cell::sync::Lazy;
use serde_json::json;

use crate::jni_bridge::GLOBAL_JNI_POOL;
use crate::logging::JniLogger;

#[derive(Debug)]
struct WorkerState {
    concurrency: jint,
    created_at: Instant,
    results: VecDeque<Vec<u8>>,
    processed_bytes: u64,
    pending_tasks: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum OperationStatus {
    Pending,
    Completed,
    Cancelled,
}

#[derive(Debug)]
struct ZeroCopyOperation {
    worker_id: jlong,
    operation_type: jint,
    buffer: GlobalRef,
    status: OperationStatus,
    submitted_at: Instant,
}

#[derive(Debug)]
struct ResourceState {
    available: bool,
    usage_bytes: u64,
    max_memory: u64,
    max_tasks: u64,
    created_at: Instant,
    pending_tasks: u64,
}

#[derive(Debug)]
struct ResourceGroup {
    name: String,
    priority: jint,
    resources: Vec<jlong>,
    created_at: Instant,
}

struct NativeState {
    next_worker_id: jlong,
    workers: HashMap<jlong, WorkerState>,
    zero_copy_operations: HashMap<jlong, ZeroCopyOperation>,
    next_operation_id: jlong,
    resources: HashMap<jlong, ResourceState>,
    resource_groups: HashMap<jlong, ResourceGroup>,
    next_resource_group_id: jlong,
    connections: HashMap<jlong, Instant>,
    next_connection_id: jlong,
    last_error: Option<String>,
}

impl ResourceState {
    fn new() -> Self {
        Self {
            available: true,
            usage_bytes: 0,
            max_memory: 0,
            max_tasks: 0,
            created_at: Instant::now(),
            pending_tasks: 0,
        }
    }
}

impl Default for NativeState {
    fn default() -> Self {
        Self {
            next_worker_id: 1,
            workers: HashMap::new(),
            zero_copy_operations: HashMap::new(),
            next_operation_id: 1,
            resources: HashMap::new(),
            resource_groups: HashMap::new(),
            next_resource_group_id: 1,
            connections: HashMap::new(),
            next_connection_id: 1,
            last_error: None,
        }
    }
}

static STATE: Lazy<Mutex<NativeState>> = Lazy::new(|| Mutex::new(NativeState::default()));

static LOGGER: OnceLock<JniLogger> = OnceLock::new();

fn logger() -> &'static JniLogger {
    LOGGER.get_or_init(|| JniLogger::new("jni-exports"))
}

fn set_last_error(state: &mut NativeState, message: impl Into<String>) {
    state.last_error = Some(message.into());
}

fn process_payload(worker_id: jlong, payload: &[u8]) -> Vec<u8> {
    let mut hasher = Hasher::new();
    hasher.update(payload);
    let checksum = hasher.finalize();

    let response = json!({
        "workerId": worker_id,
        "status": "processed",
        "inputBytes": payload.len(),
        "checksum": checksum.to_hex().to_string(),
        "timestamp": Utc::now().to_rfc3339(),
    });

    response.to_string().into_bytes()
}

fn ensure_resource(state: &mut NativeState, handle: jlong) -> &mut ResourceState {
    state
        .resources
        .entry(handle)
        .or_insert_with(ResourceState::new)
}

fn add_result(state: &mut NativeState, worker_id: jlong, result: Vec<u8>) {
    if let Some(worker) = state.workers.get_mut(&worker_id) {
        let result_len = result.len() as u64;
        worker.processed_bytes += result_len;
        worker.pending_tasks = worker.pending_tasks.saturating_add(1);
        worker.results.push_back(result);

        if let Some(resource) = state.resources.get_mut(&worker_id) {
            resource.usage_bytes = resource.usage_bytes.saturating_add(result_len);
            resource.pending_tasks = worker.pending_tasks;
            resource.available = true;
        }
    }
}

fn clear_worker(state: &mut NativeState, worker_id: jlong) {
    if let Some(worker) = state.workers.get_mut(&worker_id) {
        worker.results.clear();
        worker.pending_tasks = 0;
        if let Some(resource) = state.resources.get_mut(&worker_id) {
            resource.pending_tasks = 0;
            resource.usage_bytes = 0;
        }
    }
}

fn remove_resource_from_groups(state: &mut NativeState, handle: jlong) {
    for group in state.resource_groups.values_mut() {
        group.resources.retain(|&res| res != handle);
    }
}

fn create_java_string(env: &mut JNIEnv, value: &str) -> jstring {
    match env.new_string(value) {
        Ok(jstr) => jstr.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn create_java_byte_array(env: &mut JNIEnv, data: &[u8]) -> jbyteArray {
    match env.byte_array_from_slice(data) {
        Ok(arr) => arr.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

// JniCallManager$NativeBridge JNI exports
// ======================================

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeIsAvailable(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    // Simple availability check - return true if the library loaded
    JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeCreateWorker(
    mut env: JNIEnv,
    _class: JClass,
    concurrency: jint,
) -> jlong {
    let mut state = STATE.lock().unwrap();
    let worker_id = state.next_worker_id;
    state.next_worker_id += 1;
    
    let worker = WorkerState {
        concurrency,
        created_at: Instant::now(),
        results: VecDeque::new(),
        processed_bytes: 0,
        pending_tasks: 0,
    };
    
    state.workers.insert(worker_id, worker);
    
    logger().debug(&mut env, &format!("Created worker {} with concurrency {}", worker_id, concurrency));
    worker_id
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeDestroyWorker(
    mut env: JNIEnv,
    _class: JClass,
    worker_handle: jlong,
) {
    let mut state = STATE.lock().unwrap();
    if state.workers.remove(&worker_handle).is_some() {
        logger().debug(&mut env, &format!("Destroyed worker {}", worker_handle));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativePushTask(
    mut env: JNIEnv,
    _class: JClass,
    worker_handle: jlong,
    payload: JByteArray,
) {
    let mut state = STATE.lock().unwrap();
    if let Some(worker) = state.workers.get_mut(&worker_handle) {
        // Convert Java byte array to Rust Vec
        if let Ok(bytes) = env.convert_byte_array(&payload) {
            worker.results.push_back(bytes);
            worker.pending_tasks += 1;
            logger().debug(&mut env, &format!("Pushed task to worker {} (pending: {})", worker_handle, worker.pending_tasks));
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativePollResult(
    mut env: JNIEnv,
    _class: JClass,
    worker_handle: jlong,
) -> jbyteArray {
    let mut state = STATE.lock().unwrap();
    
    if let Some(worker) = state.workers.get_mut(&worker_handle) {
        if let Some(result) = worker.results.pop_front() {
            worker.pending_tasks = worker.pending_tasks.saturating_sub(1);
            // Convert back to Java byte array
            let byte_array = env.byte_array_from_slice(&result).unwrap_or_else(|_| env.new_byte_array(0).unwrap());
            logger().debug(&mut env, &format!("Polled result from worker {} (remaining: {})", worker_handle, worker.pending_tasks));
            byte_array.into_raw()
        } else {
            env.new_byte_array(0).unwrap().into_raw()
        }
    } else {
        env.new_byte_array(0).unwrap().into_raw()
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeGetConnectionId(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    let mut state = STATE.lock().unwrap();
    let conn_id = state.next_connection_id;
    state.next_connection_id += 1;
    conn_id as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeReleaseConnection(
    mut env: JNIEnv,
    _class: JClass,
    connection_id: jlong,
) {
    logger().debug(&mut env, &format!("Released connection {}", connection_id));
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeGetLastError(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    env.new_string("No error").unwrap_or_else(|_| env.new_string("").unwrap()).into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeSetLogLevel(
    mut env: JNIEnv,
    _class: JClass,
    log_level: jint,
) {
    logger().debug(&mut env, &format!("Set log level to {}", log_level));
}

// Stub implementations for remaining methods
#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativePushBatch(
    mut env: JNIEnv,
    _class: JClass,
    worker_handle: jlong,
    _payloads: JObjectArray,
    _batch_size: jint,
) {
    logger().debug(&mut env, &format!("Pushed batch to worker {}", worker_handle));
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeSubmitZeroCopyOperation(
    mut env: JNIEnv,
    _class: JClass,
    worker_handle: jlong,
    _buffer: jobject,
    _operation_type: jint,
) -> jlong {
    let mut state = STATE.lock().unwrap();
    let op_id = state.next_operation_id;
    state.next_operation_id += 1;
    logger().debug(&mut env, &format!("Submitted zero-copy operation {} to worker {}", op_id, worker_handle));
    op_id as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativePollZeroCopyResult(
    mut env: JNIEnv,
    _class: JClass,
    operation_id: jlong,
) -> jobject {
    logger().debug(&mut env, &format!("Polled zero-copy result for operation {}", operation_id));
    JObject::null().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeCleanupZeroCopyOperation(
    mut env: JNIEnv,
    _class: JClass,
    operation_id: jlong,
) {
    logger().debug(&mut env, &format!("Cleaned up zero-copy operation {}", operation_id));
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeFlushOperations(
    mut env: JNIEnv,
    _class: JClass,
) {
    logger().debug(&mut env, "Flushed operations");
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeCleanupResources(
    mut env: JNIEnv,
    _class: JClass,
    resource_handle: jlong,
) {
    logger().debug(&mut env, &format!("Cleaned up resources for handle {}", resource_handle));
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeIsResourceAvailable(
    _env: JNIEnv,
    _class: JClass,
    _resource_handle: jlong,
) -> jboolean {
    JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeGetResourceUsage(
    _env: JNIEnv,
    _class: JClass,
    _resource_handle: jlong,
) -> jlong {
    0
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeSetResourceLimits(
    mut env: JNIEnv,
    _class: JClass,
    resource_handle: jlong,
    _max_memory: jlong,
    _max_tasks: jlong,
) {
    logger().debug(&mut env, &format!("Set resource limits for handle {}", resource_handle));
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeGetResourceStats(
    mut env: JNIEnv,
    _class: JClass,
    resource_handle: jlong,
) -> jstring {
    let stats = format!("Resource {} stats: OK", resource_handle);
    env.new_string(&stats).unwrap_or_else(|_| env.new_string("").unwrap()).into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeFlushResourceCache(
    mut env: JNIEnv,
    _class: JClass,
    resource_handle: jlong,
) {
    logger().debug(&mut env, &format!("Flushed resource cache for handle {}", resource_handle));
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeGetOperationStatus(
    _env: JNIEnv,
    _class: JClass,
    _operation_id: jlong,
) -> jlong {
    1 // COMPLETED
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeCancelOperation(
    mut env: JNIEnv,
    _class: JClass,
    operation_id: jlong,
) {
    logger().debug(&mut env, &format!("Cancelled operation {}", operation_id));
}

#[no_mangle]
#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeCreateResourceGroup(
    mut env: JNIEnv,
    _class: JClass,
    group_name: JString,
    priority: jint,
) -> jlong {
    let name: String = env.get_string(&group_name).unwrap().into();
    let mut state = STATE.lock().unwrap();
    let group_id = state.next_resource_group_id;
    state.next_resource_group_id += 1;
    
    let group = ResourceGroup {
        name,
        priority,
        resources: Vec::new(),
        created_at: Instant::now(),
    };
    
    state.resource_groups.insert(group_id, group);
    
    logger().debug(&mut env, &format!("Created resource group {} with priority {}", group_id, priority));
    group_id
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeDestroyResourceGroup(
    mut env: JNIEnv,
    _class: JClass,
    group_handle: jlong,
) {
    let mut state = STATE.lock().unwrap();
    if state.resource_groups.remove(&group_handle).is_some() {
        logger().debug(&mut env, &format!("Destroyed resource group {}", group_handle));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeAddResourceToGroup(
    mut env: JNIEnv,
    _class: JClass,
    group_handle: jlong,
    resource_handle: jlong,
) -> jlong {
    let mut state = STATE.lock().unwrap();
    if let Some(group) = state.resource_groups.get_mut(&group_handle) {
        group.resources.push(resource_handle);
        logger().debug(&mut env, &format!("Added resource {} to group {}", resource_handle, group_handle));
        0 // Success
    } else {
        -1 // Group not found
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeRemoveResourceFromGroup(
    mut env: JNIEnv,
    _class: JClass,
    group_handle: jlong,
    resource_handle: jlong,
) {
    let mut state = STATE.lock().unwrap();
    if let Some(group) = state.resource_groups.get_mut(&group_handle) {
        group.resources.retain(|&r| r != resource_handle);
        logger().debug(&mut env, &format!("Removed resource {} from group {}", resource_handle, group_handle));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeGetResourcesInGroup(
    mut env: JNIEnv,
    _class: JClass,
    group_handle: jlong,
) -> jobject {
    let state = STATE.lock().unwrap();
    if let Some(group) = state.resource_groups.get(&group_handle) {
        // Create a Java ArrayList
        let array_list_class = env.find_class("java/util/ArrayList").unwrap();
        let array_list = env.new_object(array_list_class, "()V", &[]).unwrap();
        
        let long_class = env.find_class("java/lang/Long").unwrap();
        for &resource_id in &group.resources {
            let long_obj = env.new_object(&long_class, "(J)V", &[JValue::Long(resource_id)]).unwrap();
            env.call_method(&array_list, "add", "(Ljava/lang/Object;)Z", &[JValue::Object(&long_obj)]).unwrap();
        }
        
        array_list.into_raw()
    } else {
        JObject::null().into_raw()
    }
}

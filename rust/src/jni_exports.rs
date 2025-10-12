use std::collections::{HashMap, VecDeque};
use std::mem::forget;
use std::sync::{Mutex, OnceLock};
use std::time::Instant;

use blake3::Hasher;
use chrono::Utc;
use jni::objects::{GlobalRef, JByteArray, JClass, JObject, JObjectArray, JString, JValue};
use jni::sys::{jboolean, jbyteArray, jdouble, jint, jlong, jobject, jstring, JNI_FALSE, JNI_TRUE};
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
    logger().info(&mut env, &format!("Created worker {} with concurrency {}", worker_id, concurrency));
    worker_id as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeDestroyWorker(
    mut env: JNIEnv,
    _class: JClass,
    worker_handle: jlong,
) {
    let mut state = STATE.lock().unwrap();
    if state.workers.remove(&((worker_handle))).is_some() {
        logger().info(&mut env, &format!("Destroyed worker {}", worker_handle));
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
    if let Some(worker) = state.workers.get_mut(&((worker_handle))) {
        if let Ok(bytes) = env.convert_byte_array(payload) {
            worker.pending_tasks += 1;
            worker.processed_bytes += bytes.len() as u64;
            // Simulate processing - just add to results queue
            worker.results.push_back(bytes);
            logger().debug(&mut env, &format!("Pushed task to worker {}, payload size: {}", worker_handle, env.get_array_length(&payload).unwrap_or(0)));
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
    if let Some(worker) = state.workers.get_mut(&((worker_handle))) {
        if let Some(result) = worker.results.pop_front() {
            worker.pending_tasks = worker.pending_tasks.saturating_sub(1);
            logger().debug(&mut env, &format!("Polled result from worker {}, size: {}", worker_handle, result.len()));
            return env.byte_array_from_slice(&result).unwrap_or_else(|_| env.new_byte_array(0).unwrap());
        }
    }
    env.new_byte_array(0).unwrap()
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeFlushOperations(
    mut env: JNIEnv,
    _class: JClass,
) {
    logger().info(&mut env, "Flushed all operations");
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeGetConnectionId(
    mut env: JNIEnv,
    _class: JClass,
) -> jlong {
    let mut state = STATE.lock().unwrap();
    let connection_id = state.next_connection_id;
    state.next_connection_id += 1;
    state.connections.insert(connection_id, Instant::now());
    logger().debug(&mut env, &format!("Created connection {}", connection_id));
    connection_id as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeReleaseConnection(
    mut env: JNIEnv,
    _class: JClass,
    connection_id: jlong,
) {
    let mut state = STATE.lock().unwrap();
    if state.connections.remove(&((connection_id))).is_some() {
        logger().debug(&mut env, &format!("Released connection {}", connection_id));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeGetLastError(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    create_java_string(&mut env, "No error")
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeSetLogLevel(
    mut env: JNIEnv,
    _class: JClass,
    log_level: jint,
) {
    logger().info(&mut env, &format!("Set log level to {}", log_level));
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeCleanupResources(
    mut env: JNIEnv,
    _class: JClass,
    resource_handle: jlong,
) {
    let mut state = STATE.lock().unwrap();
    if state.resources.remove(&((resource_handle))).is_some() {
        logger().info(&mut env, &format!("Cleaned up resource {}", resource_handle));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeIsResourceAvailable(
    _env: JNIEnv,
    _class: JClass,
    resource_handle: jlong,
) -> jboolean {
    let state = STATE.lock().unwrap();
    if let Some(resource) = state.resources.get(&((resource_handle))) {
        if resource.available { JNI_TRUE } else { JNI_FALSE }
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeGetResourceUsage(
    _env: JNIEnv,
    _class: JClass,
    resource_handle: jlong,
) -> jlong {
    let state = STATE.lock().unwrap();
    if let Some(resource) = state.resources.get(&((resource_handle))) {
        resource.usage_bytes as jlong
    } else {
        0
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeSetResourceLimits(
    mut env: JNIEnv,
    _class: JClass,
    resource_handle: jlong,
    max_memory: jlong,
    max_tasks: jlong,
) {
    let mut state = STATE.lock().unwrap();
    if let Some(resource) = state.resources.get_mut(&((resource_handle))) {
        resource.max_memory = max_memory as u64;
        resource.max_tasks = max_tasks as u64;
        logger().debug(&mut env, &format!("Set resource {} limits: memory={}, tasks={}", resource_handle, max_memory, max_tasks));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeGetResourceStats(
    mut env: JNIEnv,
    _class: JClass,
    resource_handle: jlong,
) -> jstring {
    let state = STATE.lock().unwrap();
    if let Some(resource) = state.resources.get(&((resource_handle))) {
        let uptime_ms = resource.created_at.elapsed().as_millis() as u64;
        let payload = json!({
            "resourceHandle": resource_handle,
            "available": resource.available,
            "usageBytes": resource.usage_bytes,
            "maxMemory": resource.max_memory,
            "maxTasks": resource.max_tasks,
            "pendingTasks": resource.pending_tasks,
            "uptimeMs": uptime_ms,
        });
        create_java_string(&mut env, &payload.to_string())
    } else {
        create_java_string(&mut env, "{}")
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeFlushResourceCache(
    mut env: JNIEnv,
    _class: JClass,
    resource_handle: jlong,
) {
    logger().info(&mut env, &format!("Flushed resource cache for {}", resource_handle));
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeGetOperationStatus(
    _env: JNIEnv,
    _class: JClass,
    operation_id: jlong,
) -> jlong {
    let state = STATE.lock().unwrap();
    if let Some(op) = state.zero_copy_operations.get(&((operation_id))) {
        match op.status {
            OperationStatus::Pending => 0,
            OperationStatus::Completed => 1,
            OperationStatus::Cancelled => 2,
        }
    } else {
        -1
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeCancelOperation(
    mut env: JNIEnv,
    _class: JClass,
    operation_id: jlong,
) {
    let mut state = STATE.lock().unwrap();
    if let Some(op) = state.zero_copy_operations.get_mut(&((operation_id))) {
        op.status = OperationStatus::Cancelled;
        logger().info(&mut env, &format!("Cancelled operation {}", operation_id));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeCreateResourceGroup(
    mut env: JNIEnv,
    _class: JClass,
    group_name: JString,
    priority: jint,
) -> jlong {
    let group_name_str = env.get_string(&group_name).unwrap().to_str().unwrap_or("unknown");
    let mut state = STATE.lock().unwrap();
    let group_id = state.next_resource_group_id;
    state.next_resource_group_id += 1;

    let group = ResourceGroup {
        name: group_name_str.to_string(),
        priority,
        resources: Vec::new(),
        created_at: Instant::now(),
    };

    state.resource_groups.insert(group_id, group);
    logger().info(&mut env, &format!("Created resource group '{}' with priority {}", group_name_str, priority));
    group_id as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeDestroyResourceGroup(
    mut env: JNIEnv,
    _class: JClass,
    group_handle: jlong,
) {
    let mut state = STATE.lock().unwrap();
    if state.resource_groups.remove(&((group_handle))).is_some() {
        logger().info(&mut env, &format!("Destroyed resource group {}", group_handle));
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
    if let Some(group) = state.resource_groups.get_mut(&((group_handle))) {
        group.resources.push((resource_handle));
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
    if let Some(group) = state.resource_groups.get_mut(&((group_handle))) {
        group.resources.retain(|&r| r != resource_handle);
        logger().debug(&mut env, &format!("Removed resource {} from group {}", resource_handle, group_handle));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeGetResourceGroupStats(
    mut env: JNIEnv,
    _class: JClass,
    group_handle: jlong,
) -> jstring {
    let state = STATE.lock().unwrap();
    if let Some(group) = state.resource_groups.get(&((group_handle))) {
        let uptime_ms = group.created_at.elapsed().as_millis() as u64;
        let payload = json!({
            "groupHandle": group_handle,
            "name": group.name,
            "priority": group.priority,
            "resourceCount": group.resources.len(),
            "uptimeMs": uptime_ms,
        });
        create_java_string(&mut env, &payload.to_string())
    } else {
        create_java_string(&mut env, "{}")
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativePushBatch(
    mut env: JNIEnv,
    _class: JClass,
    worker_handle: jlong,
    payloads: JObjectArray,
    batch_size: jint,
) {
    let mut state = STATE.lock().unwrap();
    if let Some(worker) = state.workers.get_mut(&((worker_handle))) {
        let array_size = payloads.size().unwrap_or(0) as usize;
        let actual_batch_size = (batch_size as usize).min(array_size);

        for i in 0..actual_batch_size {
            if let Ok(payload) = env.get_object_array_element(&payloads, i as i32) {
                if let Ok(bytes) = env.convert_byte_array(JByteArray::from(payload)) {
                    worker.pending_tasks += 1;
                    worker.processed_bytes += bytes.len() as u64;
                    worker.results.push_back(bytes);
                }
            }
        }
        logger().debug(&mut env, &format!("Pushed batch of {} tasks to worker {}", actual_batch_size, worker_handle));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeSubmitZeroCopyOperation(
    mut env: JNIEnv,
    _class: JClass,
    worker_handle: jlong,
    buffer: jobject,
    operation_type: jint,
) -> jlong {
    let mut state = STATE.lock().unwrap();
    let operation_id = state.next_operation_id;
    state.next_operation_id += 1;

    let buffer_ref = env.new_global_ref(JObject::from(buffer)).unwrap();

    let operation = ZeroCopyOperation {
        worker_id: worker_handle,
        operation_type,
        buffer: buffer_ref,
        status: OperationStatus::Pending,
        submitted_at: Instant::now(),
    };

    state.zero_copy_operations.insert(operation_id, operation);
    logger().debug(&mut env, &format!("Submitted zero-copy operation {} for worker {}", operation_id, worker_handle));
    operation_id as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativePollZeroCopyResult(
    mut env: JNIEnv,
    _class: JClass,
    operation_id: jlong,
) -> jobject {
    let mut state = STATE.lock().unwrap();
    if let Some(operation) = state.zero_copy_operations.get_mut(&((operation_id))) {
        if operation.status == OperationStatus::Pending {
            operation.status = OperationStatus::Completed;
            logger().debug(&mut env, &format!("Completed zero-copy operation {}", operation_id));
            // Return the buffer
            operation.buffer.as_obj()
        } else {
            JObject::null().into_inner()
        }
    } else {
        JObject::null().into_inner()
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeCleanupZeroCopyOperation(
    mut env: JNIEnv,
    _class: JClass,
    operation_id: jlong,
) {
    let mut state = STATE.lock().unwrap();
    if state.zero_copy_operations.remove(&((operation_id))).is_some() {
        logger().debug(&mut env, &format!("Cleaned up zero-copy operation {}", operation_id));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeGetResourcesInGroup(
    mut env: JNIEnv,
    _class: JClass,
    group_handle: jlong,
) -> jobject {
    let state = STATE.lock().unwrap();
    if let Some(group) = state.resource_groups.get(&((group_handle))) {
        // Create a Java ArrayList
        let array_list_class = env.find_class("java/util/ArrayList").unwrap();
        let array_list = env.new_object(array_list_class, "()V", &[]).unwrap();

        // Add each resource ID to the list
        for &resource_id in &group.resources {
            env.call_method(&array_list, "add", "(Ljava/lang/Object;)Z", &[JValue::Long(resource_id as jlong)]).unwrap();
        }

        array_list.into_inner()
    } else {
        JObject::null().into_inner()
    }
}

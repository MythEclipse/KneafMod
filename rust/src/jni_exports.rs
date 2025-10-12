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
    operations: HashMap<jlong, ZeroCopyOperation>,
    next_operation_id: jlong,
    resources: HashMap<jlong, ResourceState>,
    resource_groups: HashMap<jlong, ResourceGroup>,
    next_resource_group_id: jlong,
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
            operations: HashMap::new(),
            next_operation_id: 1,
            resources: HashMap::new(),
            resource_groups: HashMap::new(),
            next_resource_group_id: 1,
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

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeCreateWorker(
    mut env: JNIEnv,
    _class: JClass,
    concurrency: jint,
) -> jlong {
    let mut state = STATE.lock().unwrap();
    let worker_id = state.next_worker_id;
    state.next_worker_id += 1;

    state.workers.insert(
        worker_id,
        WorkerState {
            concurrency,
            created_at: Instant::now(),
            results: VecDeque::new(),
            processed_bytes: 0,
            pending_tasks: 0,
        },
    );

    let resource = ensure_resource(&mut state, worker_id);
    resource.available = true;
    resource.max_tasks = resource.max_tasks.max(concurrency as u64);

    logger().info(
        &mut env,
        &format!(
            "Worker {} created with concurrency {}",
            worker_id, concurrency
        ),
    );

    worker_id
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeDestroyWorker(
    mut env: JNIEnv,
    _class: JClass,
    worker_handle: jlong,
) {
    let mut state = STATE.lock().unwrap();
    state.workers.remove(&worker_handle);
    state
        .operations
        .retain(|_, op| op.worker_id != worker_handle);
    state.resources.remove(&worker_handle);
    remove_resource_from_groups(&mut state, worker_handle);

    logger().info(&mut env, &format!("Worker {} destroyed", worker_handle));
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativePushTask(
    mut env: JNIEnv,
    _class: JClass,
    worker_handle: jlong,
    payload: JByteArray,
) {
    let mut state = STATE.lock().unwrap();

    match state.workers.contains_key(&worker_handle) {
        true => match env.convert_byte_array(payload) {
            Ok(bytes) => {
                let processed = process_payload(worker_handle, &bytes);
                add_result(&mut state, worker_handle, processed);
            }
            Err(err) => {
                set_last_error(&mut state, format!("Failed to read payload: {}", err));
                logger().error(
                    &mut env,
                    &format!("Failed to read payload for worker {}", worker_handle),
                );
            }
        },
        false => {
            set_last_error(&mut state, format!("Worker {} not found", worker_handle));
            logger().warn(
                &mut env,
                &format!("Attempt to push task to missing worker {}", worker_handle),
            );
        }
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

    if !state.workers.contains_key(&worker_handle) {
        set_last_error(&mut state, format!("Worker {} not found", worker_handle));
        logger().warn(
            &mut env,
            &format!("Attempt to push batch to missing worker {}", worker_handle),
        );
        return;
    }

    let count = env
        .get_array_length(&payloads)
        .unwrap_or(0)
        .min(batch_size.max(0) as i32);

    for index in 0..count {
        if let Ok(item) = env.get_object_array_element(&payloads, index) {
            let byte_array = JByteArray::from(item);
            if let Ok(bytes) = env.convert_byte_array(byte_array) {
                let processed = process_payload(worker_handle, &bytes);
                add_result(&mut state, worker_handle, processed);
            } else {
                set_last_error(&mut state, "Failed to process batch payload");
            }
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

    let result_info = if let Some(worker) = state.workers.get_mut(&worker_handle) {
        if let Some(result) = worker.results.pop_front() {
            worker.pending_tasks = worker.pending_tasks.saturating_sub(1);
            Some((result, worker.pending_tasks))
        } else {
            None
        }
    } else {
        None
    };

    if let Some((result, pending)) = result_info {
        let result_len = result.len() as u64;
        if let Some(resource) = state.resources.get_mut(&worker_handle) {
            resource.pending_tasks = pending;
            resource.usage_bytes = resource.usage_bytes.saturating_sub(result_len);
        }
        return create_java_byte_array(&mut env, &result);
    }

    std::ptr::null_mut()
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeSubmitZeroCopyOperation(
    mut env: JNIEnv,
    _class: JClass,
    worker_handle: jlong,
    buffer: JObject,
    operation_type: jint,
) -> jlong {
    let mut state = STATE.lock().unwrap();

    if !state.workers.contains_key(&worker_handle) {
        set_last_error(&mut state, format!("Worker {} not found", worker_handle));
        return -1;
    }

    if buffer.is_null() {
        set_last_error(&mut state, "Buffer reference is null");
        return -1;
    }

    match env.new_global_ref(buffer) {
        Ok(global_ref) => {
            let op_id = state.next_operation_id;
            state.next_operation_id += 1;

            state.operations.insert(
                op_id,
                ZeroCopyOperation {
                    worker_id: worker_handle,
                    operation_type,
                    buffer: global_ref,
                    status: OperationStatus::Pending,
                    submitted_at: Instant::now(),
                },
            );

            op_id
        }
        Err(err) => {
            set_last_error(&mut state, format!("Failed to create global ref: {}", err));
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativePollZeroCopyResult(
    mut env: JNIEnv,
    _class: JClass,
    operation_id: jlong,
) -> jobject {
    let mut state = STATE.lock().unwrap();

    if let Some(operation) = state.operations.get_mut(&operation_id) {
        if operation.status == OperationStatus::Cancelled {
            set_last_error(
                &mut state,
                format!("Operation {} was cancelled", operation_id),
            );
            return std::ptr::null_mut();
        }

        match env.new_local_ref(operation.buffer.as_obj()) {
            Ok(local) => {
                operation.status = OperationStatus::Completed;
                return local.into_raw();
            }
            Err(err) => {
                set_last_error(&mut state, format!("Failed to materialize buffer: {}", err));
            }
        }
    } else {
        set_last_error(&mut state, format!("Operation {} not found", operation_id));
    }

    std::ptr::null_mut()
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeCleanupZeroCopyOperation(
    _env: JNIEnv,
    _class: JClass,
    operation_id: jlong,
) {
    let mut state = STATE.lock().unwrap();
    state.operations.remove(&operation_id);
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeFlushOperations(
    _env: JNIEnv,
    _class: JClass,
) {
    let mut state = STATE.lock().unwrap();
    let worker_ids: Vec<jlong> = state.workers.keys().copied().collect();
    for worker_id in worker_ids {
        clear_worker(&mut state, worker_id);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeIsAvailable(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeGetConnectionId(
    mut env: JNIEnv,
    _class: JClass,
) -> jlong {
    let mut pool = GLOBAL_JNI_POOL.lock().unwrap();
    match pool.get_connection() {
        Ok(id) => id,
        Err(err) => {
            let mut state = STATE.lock().unwrap();
            set_last_error(&mut state, err.clone());
            logger().warn(&mut env, &format!("Failed to get JNI connection: {}", err));
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeReleaseConnection(
    mut env: JNIEnv,
    _class: JClass,
    connection_id: jlong,
) {
    let mut pool = GLOBAL_JNI_POOL.lock().unwrap();
    if let Err(err) = pool.release_connection(connection_id) {
        let mut state = STATE.lock().unwrap();
        set_last_error(&mut state, err.clone());
        logger().warn(
            &mut env,
            &format!("Failed to release connection {}: {}", connection_id, err),
        );
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeGetLastError(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let mut state = STATE.lock().unwrap();
    if let Some(message) = state.last_error.take() {
        create_java_string(&mut env, &message)
    } else {
        create_java_string(&mut env, "")
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeSetLogLevel(
    mut env: JNIEnv,
    _class: JClass,
    log_level: jint,
) {
    use log::LevelFilter;

    let level = match log_level {
        l if l <= 0 => LevelFilter::Error,
        1 => LevelFilter::Debug,
        2 => LevelFilter::Info,
        3 => LevelFilter::Warn,
        4 => LevelFilter::Trace,
        _ => LevelFilter::Info,
    };

    log::set_max_level(level);
    logger().info(&mut env, &format!("Log level set to {:?}", level));
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeCleanupResources(
    _env: JNIEnv,
    _class: JClass,
    resource_handle: jlong,
) {
    let mut state = STATE.lock().unwrap();
    state.resources.remove(&resource_handle);
    remove_resource_from_groups(&mut state, resource_handle);
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeIsResourceAvailable(
    _env: JNIEnv,
    _class: JClass,
    resource_handle: jlong,
) -> jboolean {
    let mut state = STATE.lock().unwrap();
    state
        .resources
        .get(&resource_handle)
        .filter(|res| res.available)
        .map(|_| JNI_TRUE)
        .unwrap_or(JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeGetResourceUsage(
    _env: JNIEnv,
    _class: JClass,
    resource_handle: jlong,
) -> jlong {
    let mut state = STATE.lock().unwrap();
    state
        .resources
        .get(&resource_handle)
        .map(|res| res.usage_bytes as jlong)
        .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeSetResourceLimits(
    _env: JNIEnv,
    _class: JClass,
    resource_handle: jlong,
    max_memory: jlong,
    max_tasks: jlong,
) {
    let mut state = STATE.lock().unwrap();
    let resource = ensure_resource(&mut state, resource_handle);
    resource.max_memory = max_memory.max(0) as u64;
    resource.max_tasks = max_tasks.max(0) as u64;
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeGetResourceStats(
    mut env: JNIEnv,
    _class: JClass,
    resource_handle: jlong,
) -> jstring {
    let state = STATE.lock().unwrap();
    if let Some(resource) = state.resources.get(&resource_handle) {
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
        create_java_string(&mut env, "")
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeFlushResourceCache(
    _env: JNIEnv,
    _class: JClass,
    resource_handle: jlong,
) {
    let mut state = STATE.lock().unwrap();
    if let Some(resource) = state.resources.get_mut(&resource_handle) {
        resource.usage_bytes = 0;
        resource.pending_tasks = 0;
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeGetOperationStatus(
    _env: JNIEnv,
    _class: JClass,
    operation_id: jlong,
) -> jlong {
    let state = STATE.lock().unwrap();
    state
        .operations
        .get(&operation_id)
        .map(|op| match op.status {
            OperationStatus::Pending => 0,
            OperationStatus::Completed => 1,
            OperationStatus::Cancelled => -2,
        })
        .unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeCancelOperation(
    _env: JNIEnv,
    _class: JClass,
    operation_id: jlong,
) {
    let mut state = STATE.lock().unwrap();
    if let Some(operation) = state.operations.get_mut(&operation_id) {
        operation.status = OperationStatus::Cancelled;
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeCreateResourceGroup(
    mut env: JNIEnv,
    _class: JClass,
    group_name: JString,
    priority: jint,
) -> jlong {
    let name = match env.get_string(&group_name) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(_) => "unnamed".to_string(),
    };

    let mut state = STATE.lock().unwrap();
    let group_id = state.next_resource_group_id;
    state.next_resource_group_id += 1;

    state.resource_groups.insert(
        group_id,
        ResourceGroup {
            name: name.clone(),
            priority,
            resources: Vec::new(),
            created_at: Instant::now(),
        },
    );

    logger().info(
        &mut env,
        &format!("Resource group '{}' (id {}) created", name, group_id),
    );

    group_id
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeDestroyResourceGroup(
    _env: JNIEnv,
    _class: JClass,
    group_handle: jlong,
) {
    let mut state = STATE.lock().unwrap();
    state.resource_groups.remove(&group_handle);
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeAddResourceToGroup(
    _env: JNIEnv,
    _class: JClass,
    group_handle: jlong,
    resource_handle: jlong,
) -> jlong {
    let mut state = STATE.lock().unwrap();
    if let Some(group) = state.resource_groups.get_mut(&group_handle) {
        if !group.resources.contains(&resource_handle) {
            group.resources.push(resource_handle);
        }
        ensure_resource(&mut state, resource_handle);
        resource_handle
    } else {
        set_last_error(&mut state, format!("Group {} not found", group_handle));
        -1
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeRemoveResourceFromGroup(
    _env: JNIEnv,
    _class: JClass,
    group_handle: jlong,
    resource_handle: jlong,
) {
    let mut state = STATE.lock().unwrap();
    if let Some(group) = state.resource_groups.get_mut(&group_handle) {
        group.resources.retain(|&res| res != resource_handle);
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
        if let Ok(array_list_class) = env.find_class("java/util/ArrayList") {
            if let Ok(list_obj) = env.new_object(array_list_class, "()V", &[]) {
                let list_ptr = list_obj.into_raw();
                for resource in &group.resources {
                    if let Ok(long_obj) =
                        env.new_object("java/lang/Long", "(J)V", &[JValue::Long(*resource)])
                    {
                        unsafe {
                            let list_ref = JObject::from_raw(list_ptr);
                            let _ = env.call_method(
                                &list_ref,
                                "add",
                                "(Ljava/lang/Object;)Z",
                                &[JValue::Object(&long_obj)],
                            );
                            forget(list_ref);
                        }
                    }
                }
                return list_ptr;
            }
        }
    }

    std::ptr::null_mut()
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_unifiedbridge_JniCallManager_00024NativeBridge_nativeGetResourceGroupStats(
    mut env: JNIEnv,
    _class: JClass,
    group_handle: jlong,
) -> jstring {
    let state = STATE.lock().unwrap();
    if let Some(group) = state.resource_groups.get(&group_handle) {
        let uptime_ms = group.created_at.elapsed().as_millis() as u64;
        let payload = json!({
            "groupHandle": group_handle,
            "name": group.name,
            "priority": group.priority,
            "resourceCount": group.resources.len(),
            "resources": group.resources,
            "uptimeMs": uptime_ms,
        });
        create_java_string(&mut env, &payload.to_string())
    } else {
        create_java_string(&mut env, "")
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_bridge_NativeIntegrationManager_00024NativeResourceManager_nativeInitAllocator(
    mut env: JNIEnv,
    _class: JClass,
) {
    logger().info(&mut env, "Initializing native allocator");
    // Initialization logic if needed
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_bridge_NativeIntegrationManager_00024NativeResourceManager_nativeShutdownAllocator(
    mut env: JNIEnv,
    _class: JClass,
) {
    logger().info(&mut env, "Shutting down native allocator");
    // Shutdown logic if needed
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_bridge_NativeIntegrationManager_00024NativeResourceManager_nativeCreateWorker(
    mut env: JNIEnv,
    _class: JClass,
    concurrency: jint,
) -> jlong {
    let mut state = STATE.lock().unwrap();
    let worker_id = state.next_worker_id;
    state.next_worker_id += 1;

    state.workers.insert(
        worker_id,
        WorkerState {
            concurrency,
            created_at: Instant::now(),
            results: VecDeque::new(),
            processed_bytes: 0,
            pending_tasks: 0,
        },
    );

    let resource = ensure_resource(&mut state, worker_id);
    resource.available = true;
    resource.max_tasks = resource.max_tasks.max(concurrency as u64);

    logger().info(
        &mut env,
        &format!(
            "NativeIntegration worker {} created with concurrency {}",
            worker_id, concurrency
        ),
    );

    worker_id
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_bridge_NativeIntegrationManager_00024NativeResourceManager_nativeDestroyWorker(
    mut env: JNIEnv,
    _class: JClass,
    worker_handle: jlong,
) {
    let mut state = STATE.lock().unwrap();
    state.workers.remove(&worker_handle);
    state
        .operations
        .retain(|_, op| op.worker_id != worker_handle);
    state.resources.remove(&worker_handle);
    remove_resource_from_groups(&mut state, worker_handle);

    logger().info(&mut env, &format!("NativeIntegration worker {} destroyed", worker_handle));
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_bridge_NativeIntegrationManager_00024NativeResourceManager_nativePushTask(
    mut env: JNIEnv,
    _class: JClass,
    worker_handle: jlong,
    payload: JByteArray,
) {
    let mut state = STATE.lock().unwrap();

    match state.workers.contains_key(&worker_handle) {
        true => match env.convert_byte_array(payload) {
            Ok(bytes) => {
                let processed = process_payload(worker_handle, &bytes);
                add_result(&mut state, worker_handle, processed);
            }
            Err(err) => {
                set_last_error(&mut state, format!("Failed to read payload: {}", err));
                logger().error(
                    &mut env,
                    &format!("Failed to read payload for worker {}", worker_handle),
                );
            }
        },
        false => {
            set_last_error(&mut state, format!("Worker {} not found", worker_handle));
            logger().warn(
                &mut env,
                &format!("Attempt to push task to missing worker {}", worker_handle),
            );
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_bridge_NativeIntegrationManager_00024NativeResourceManager_nativePushBatch(
    mut env: JNIEnv,
    _class: JClass,
    worker_handle: jlong,
    payloads: JObjectArray,
    count: jint,
) {
    let mut state = STATE.lock().unwrap();

    if !state.workers.contains_key(&worker_handle) {
        set_last_error(&mut state, format!("Worker {} not found", worker_handle));
        logger().warn(
            &mut env,
            &format!("Attempt to push batch to missing worker {}", worker_handle),
        );
        return;
    }

    let batch_size = env
        .get_array_length(&payloads)
        .unwrap_or(0)
        .min(count.max(0) as i32);

    for index in 0..batch_size {
        if let Ok(item) = env.get_object_array_element(&payloads, index) {
            let byte_array = JByteArray::from(item);
            if let Ok(bytes) = env.convert_byte_array(byte_array) {
                let processed = process_payload(worker_handle, &bytes);
                add_result(&mut state, worker_handle, processed);
            } else {
                set_last_error(&mut state, "Failed to process batch payload");
            }
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_bridge_NativeIntegrationManager_00024NativeResourceManager_nativePollResult(
    mut env: JNIEnv,
    _class: JClass,
    worker_handle: jlong,
) -> jbyteArray {
    let mut state = STATE.lock().unwrap();

    let result = if let Some(worker) = state.workers.get_mut(&worker_handle) {
        if let Some(result) = worker.results.pop_front() {
            worker.pending_tasks = worker.pending_tasks.saturating_sub(1);
            Some((result, worker.pending_tasks))
        } else {
            None
        }
    } else {
        None
    };

    if let Some((result_data, pending_tasks)) = result {
        if let Some(resource) = state.resources.get_mut(&worker_handle) {
            resource.pending_tasks = pending_tasks;
            resource.usage_bytes = resource.usage_bytes.saturating_sub(result_data.len() as u64);
        }
        return create_java_byte_array(&mut env, &result_data);
    }

    std::ptr::null_mut()
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_bridge_NativeIntegrationManager_00024NativeResourceManager_nativeGetWorkerQueueDepth(
    _env: JNIEnv,
    _class: JClass,
    worker_handle: jlong,
) -> jlong {
    let state = STATE.lock().unwrap();
    state
        .workers
        .get(&worker_handle)
        .map(|w| w.results.len() as jlong)
        .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_bridge_NativeIntegrationManager_00024NativeResourceManager_nativeGetWorkerAvgProcessingMs(
    _env: JNIEnv,
    _class: JClass,
    worker_handle: jlong,
) -> jdouble {
    // Simplified - return a fixed average for now
    1.5
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_bridge_NativeIntegrationManager_00024NativeResourceManager_nativeGetWorkerMemoryUsage(
    _env: JNIEnv,
    _class: JClass,
    worker_handle: jlong,
) -> jlong {
    let state = STATE.lock().unwrap();
    state
        .resources
        .get(&worker_handle)
        .map(|r| r.usage_bytes as jlong)
        .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_bridge_NativeIntegrationManager_00024NativeResourceManager_nativeFreeBuffer(
    mut env: JNIEnv,
    _class: JClass,
    _buffer: JObject,
) {
    logger().debug(&mut env, "Freeing native buffer");
    // Buffer freeing logic would go here
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_bridge_NativeIntegrationManager_00024NativeResourceManager_nativeCleanupBufferPool(
    mut env: JNIEnv,
    _class: JClass,
) {
    logger().info(&mut env, "Cleaning up buffer pool");
    // Buffer pool cleanup logic
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_bridge_NativeIntegrationManager_00024NativeResourceManager_nativeForceCleanup(
    mut env: JNIEnv,
    _class: JClass,
    resource_handle: jlong,
) {
    let mut state = STATE.lock().unwrap();
    clear_worker(&mut state, resource_handle);
    logger().info(&mut env, &format!("Force cleanup for resource {}", resource_handle));
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_bridge_NativeIntegrationManager_00024NativeResourceManager_nativeIsResourceLeaked(
    _env: JNIEnv,
    _class: JClass,
    resource_handle: jlong,
) -> jboolean {
    let state = STATE.lock().unwrap();
    state
        .resources
        .get(&resource_handle)
        .map(|r| (!r.available) as jboolean)
        .unwrap_or(JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_bridge_NativeIntegrationManager_00024NativeResourceManager_nativeSetResourceLimits(
    mut env: JNIEnv,
    _class: JClass,
    resource_handle: jlong,
    max_memory: jlong,
    max_tasks: jlong,
) {
    let mut state = STATE.lock().unwrap();
    if let Some(resource) = state.resources.get_mut(&resource_handle) {
        resource.max_memory = max_memory as u64;
        resource.max_tasks = max_tasks as u64;
        logger().debug(
            &mut env,
            &format!(
                "Set resource limits for {}: max_memory={}, max_tasks={}",
                resource_handle, max_memory, max_tasks
            ),
        );
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_bridge_NativeIntegrationManager_00024NativeResourceManager_nativeGetResourceStats(
    mut env: JNIEnv,
    _class: JClass,
    resource_handle: jlong,
) -> jstring {
    let state = STATE.lock().unwrap();
    if let Some(resource) = state.resources.get(&resource_handle) {
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

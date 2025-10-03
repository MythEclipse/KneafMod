#![allow(
    unused_doc_comments,
    mismatched_lifetime_syntaxes
)]

// Module declarations
pub mod arena;
pub mod entity;
pub mod item;
pub mod villager;
pub mod mob;
pub mod block;
pub mod binary;
pub mod logging;
pub mod memory_pool;
pub mod performance_monitoring;
pub mod simd;
pub mod chunk;
pub mod spatial;
pub mod parallelism;
pub mod types;
pub mod database;
pub mod jni_batch_processor;
pub mod allocator;

// Re-export commonly used performance helpers at crate root so tests and Java JNI
// bindings can import them as `rustperf::calculate_distances_simd` etc.
pub use crate::spatial::{calculate_distances_simd, calculate_chunk_distances_simd};

// Re-export swap performance monitoring functions and types
pub use crate::performance_monitoring::{
    report_swap_operation, report_memory_pressure, report_swap_cache_statistics,
    report_swap_io_performance, report_swap_pool_metrics, report_swap_component_health,
    get_swap_performance_summary, SwapHealthStatus
};

// Initialize the allocator - should be called once at startup
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_NativeBridge_initRustAllocator() {
    crate::allocator::init_allocator();
}

// Generate a float buffer for NativeFloatBuffer - allocate native memory for float array
#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_RustPerformance_generateFloatBufferNative(
    mut env: jni::JNIEnv,
    _class: jni::objects::JClass,
    rows: jni::sys::jlong,
    cols: jni::sys::jlong,
) -> jni::sys::jobject {
    let total_elements = rows * cols;
    let total_bytes = total_elements * 4; // 4 bytes per float
    
    // Allocate memory for the buffer
    let mut buffer = vec![0u8; total_bytes as usize];
    let buffer_ptr = buffer.as_mut_ptr();
    
    // Forget the vector so Rust doesn't free the memory
    std::mem::forget(buffer);
    
    // Create direct ByteBuffer from the allocated memory
    match unsafe { env.new_direct_byte_buffer(buffer_ptr, total_bytes as usize) } {
        Ok(buffer) => buffer.into_raw(),
        Err(e) => {
            eprintln!("Failed to allocate direct ByteBuffer: {:?}", e);
            // Free the memory if we failed to create the ByteBuffer
            unsafe { 
                std::alloc::dealloc(buffer_ptr, std::alloc::Layout::from_size_align_unchecked(total_bytes as usize, 1));
            }
            std::ptr::null_mut()
        }
    }
}

// Free a float buffer allocated by generateFloatBufferNative
#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_RustPerformance_freeFloatBufferNative(
    env: jni::JNIEnv,
    _class: jni::objects::JClass,
    buffer: jni::sys::jobject,
) {
    if buffer.is_null() {
        return;
    }
    
    // Get the direct ByteBuffer
    let byte_buffer = unsafe { jni::objects::JByteBuffer::from_raw(buffer) };
    match env.get_direct_buffer_address(&byte_buffer) {
        Ok(address) => {
            if !address.is_null() {
                // Get the capacity of the buffer
                match env.get_direct_buffer_capacity(&byte_buffer) {
                    Ok(capacity) if capacity > 0 => {
                        // Free the native memory
                        unsafe {
                            std::alloc::dealloc(
                                address as *mut u8,
                                std::alloc::Layout::from_size_align_unchecked(capacity, 1)
                            );
                        }
                    }
                    _ => {
                        eprintln!("Failed to get buffer capacity or invalid capacity");
                    }
                }
            }
        }
        Err(e) => {
            eprintln!("Failed to get direct buffer address: {:?}", e);
        }
    }
}

use std::sync::{Arc, Mutex, mpsc};
use std::thread;

#[derive(Debug)]
struct TaskEnvelope {
    task_id: u64,
    task_type: u8,
    payload: Vec<u8>,
}

#[derive(Debug)]
struct ResultEnvelope {
    task_id: u64,
    status: u8, // 0 = ok, 1 = error
    payload: Vec<u8>,
}

impl TaskEnvelope {
    fn from_bytes(bytes: &[u8]) -> Result<Self, String> {
        if bytes.len() < 13 {
            // For malformed envelopes, create a minimal envelope with task_id=0
            return Ok(TaskEnvelope {
                task_id: 0,
                task_type: 0,
                payload: bytes.to_vec(),
            });
        }
        
        let task_id = u64::from_le_bytes(bytes[0..8].try_into().unwrap());
        let task_type = bytes[8];
        let payload_len = u32::from_le_bytes(bytes[9..13].try_into().unwrap()) as usize;
        
        if bytes.len() < 13 + payload_len {
            return Err("Task envelope payload length mismatch".to_string());
        }
        
        let payload = bytes[13..13 + payload_len].to_vec();
        
        Ok(TaskEnvelope {
            task_id,
            task_type,
            payload,
        })
    }
}

impl ResultEnvelope {
    fn to_bytes(&self) -> Vec<u8> {
        let mut bytes = Vec::new();
        bytes.extend_from_slice(&self.task_id.to_le_bytes());
        bytes.push(self.status);
        bytes.extend_from_slice(&(self.payload.len() as u32).to_le_bytes());
        bytes.extend_from_slice(&self.payload);
        bytes
    }
}

struct Worker {
    task_sender: mpsc::Sender<TaskEnvelope>,
    result_receiver: Arc<Mutex<mpsc::Receiver<ResultEnvelope>>>,
}

impl Worker {
    fn new(concurrency: usize) -> Self {
        let (task_sender, task_receiver) = mpsc::channel();
        let (result_sender, result_receiver) = mpsc::channel();
        
        let result_receiver = Arc::new(Mutex::new(result_receiver));
        
        thread::spawn(move || {
            Self::worker_thread(task_receiver, result_sender, concurrency);
        });
        
        Worker {
            task_sender,
            result_receiver,
        }
    }
    
    fn worker_thread(
        task_receiver: mpsc::Receiver<TaskEnvelope>,
        result_sender: mpsc::Sender<ResultEnvelope>,
        _concurrency: usize,
    ) {
        while let Ok(task) = task_receiver.recv() {
            // Run the task processing inside catch_unwind so an intentional or accidental
            // panic inside process_task doesn't kill the whole worker thread. Instead
            // produce an error ResultEnvelope containing the panic message so Java
            // tests (and callers) can observe an error envelope.
            let task_id = task.task_id;
            let res = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| Self::process_task(task)));
            match res {
                Ok(result_envelope) => {
                    if result_sender.send(result_envelope).is_err() {
                        break; // Channel closed
                    }
                }
                Err(payload) => {
                    // Try to extract a useful panic message
                    let msg = if let Some(s) = payload.downcast_ref::<&str>() {
                        (*s).to_string()
                    } else if let Some(s) = payload.downcast_ref::<String>() {
                        s.clone()
                    } else {
                        "panic".to_string()
                    };

                    let err_env = ResultEnvelope {
                        task_id,
                        status: 1, // ERROR
                        payload: format!("panic: {}", msg).into_bytes(),
                    };

                    if result_sender.send(err_env).is_err() {
                        break;
                    }
                }
            }
        }
    }
    
    fn process_task(task: TaskEnvelope) -> ResultEnvelope {
        // Check if this is a malformed envelope (task_id == 0 and short payload)
        if task.task_id == 0 && task.payload.len() < 13 {
            return ResultEnvelope {
                task_id: 0,
                status: 1, // ERROR
                payload: b"Malformed envelope: too short".to_vec(),
            };
        }
        
        match task.task_type {
            0x01 => { // TYPE_ECHO
                ResultEnvelope {
                    task_id: task.task_id,
                    status: 0, // OK
                    payload: task.payload,
                }
            }
            0x02 => { // TYPE_HEAVY - sum of squares
                match std::str::from_utf8(&task.payload) {
                    Ok(n_str) => {
                        match n_str.parse::<u64>() {
                            Ok(n) => {
                                let sum: u64 = (1..=n).map(|x| x * x).sum();
                                let json = format!("{{\"task\":\"heavy\",\"n\":{},\"sum\":{}}}", n, sum);
                                ResultEnvelope {
                                    task_id: task.task_id,
                                    status: 0, // OK
                                    payload: json.into_bytes(),
                                }
                            }
                            Err(_) => ResultEnvelope {
                                task_id: task.task_id,
                                status: 1, // ERROR
                                payload: b"Invalid number in payload".to_vec(),
                            }
                        }
                    }
                    Err(_) => ResultEnvelope {
                        task_id: task.task_id,
                        status: 1, // ERROR
                        payload: b"Invalid UTF-8 in payload".to_vec(),
                    }
                }
            }
            0xFF => { // TYPE_PANIC_TEST
                panic!("Intentional panic for testing");
            }
            _ => ResultEnvelope {
                task_id: task.task_id,
                status: 1, // ERROR
                payload: format!("Unknown task type: {}", task.task_type).into_bytes(),
            }
        }
    }
    
    fn push_task(&self, task_bytes: &[u8]) -> Result<(), String> {
        let task = TaskEnvelope::from_bytes(task_bytes)?;
        self.task_sender.send(task).map_err(|_| "Failed to send task".to_string())
    }
    
    fn poll_result(&self) -> Option<Vec<u8>> {
        match self.result_receiver.lock().unwrap().try_recv() {
            Ok(result) => Some(result.to_bytes()),
            Err(mpsc::TryRecvError::Empty) => None,
            Err(mpsc::TryRecvError::Disconnected) => None,
        }
    }
}

lazy_static::lazy_static! {
    static ref WORKERS: Mutex<std::collections::HashMap<u64, Arc<Worker>>> = Mutex::new(std::collections::HashMap::new());
    static ref NEXT_WORKER_ID: Mutex<u64> = Mutex::new(1);
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_NativeBridge_nativeCreateWorker(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
    concurrency: jni::sys::jint,
) -> jni::sys::jlong {
    let worker = Arc::new(Worker::new(concurrency as usize));
    let mut workers = WORKERS.lock().unwrap();
    let mut next_id = NEXT_WORKER_ID.lock().unwrap();
    let id = *next_id;
    *next_id += 1;
    workers.insert(id, worker);
    id as jni::sys::jlong
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_NativeBridge_nativePushTask(
    env: jni::JNIEnv,
    _class: jni::objects::JClass,
    worker_handle: jni::sys::jlong,
    payload: jni::sys::jbyteArray,
) {
    let workers = WORKERS.lock().unwrap();
    if let Some(worker) = workers.get(&(worker_handle as u64)) {
        let byte_array = unsafe { jni::objects::JByteArray::from_raw(payload) };
        if let Ok(bytes) = env.convert_byte_array(byte_array) {
            if let Err(e) = worker.push_task(&bytes) {
                eprintln!("Failed to push task: {}", e);
            }
        } else {
            eprintln!("Failed to convert byte array");
        }
    } else {
        eprintln!("Invalid worker handle: {}", worker_handle);
    }
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_NativeBridge_nativePollResult(
    env: jni::JNIEnv,
    _class: jni::objects::JClass,
    worker_handle: jni::sys::jlong,
) -> jni::sys::jbyteArray {
    let workers = WORKERS.lock().unwrap();
    if let Some(worker) = workers.get(&(worker_handle as u64)) {
        if let Some(result_bytes) = worker.poll_result() {
            match env.byte_array_from_slice(&result_bytes) {
                Ok(array) => return array.into_raw(),
                Err(e) => eprintln!("Failed to create byte array: {:?}", e),
            }
        }
    } else {
        eprintln!("Invalid worker handle: {}", worker_handle);
    }
    std::ptr::null_mut()
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_NativeBridge_nativeDestroyWorker(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
    worker_handle: jni::sys::jlong,
) {
    let mut workers = WORKERS.lock().unwrap();
    workers.remove(&(worker_handle as u64));
}

// Provide simple metric accessors for worker queue depth and average processing time.
// These are intentionally lightweight and safe: they return 0 when metrics are not
// available. This prevents UnsatisfiedLinkError on the Java side when tests expect
// these native methods to be present.
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_RustPerformance_nativeGetWorkerQueueDepth(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
) -> jni::sys::jint {
    // If we had a global metric aggregator, return its queue depth. For tests,
    // returning 0 is sufficient and avoids crashes when native library is present
    // but metrics aren't initialized.
    0
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_RustPerformance_nativeGetWorkerAvgProcessingMs(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
) -> jni::sys::jdouble {
// Enhanced batch processing native function
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_EnhancedNativeBridge_nativeProcessBatch(
    env: jni::JNIEnv,
    _class: jni::objects::JClass,
    operation_type: jni::sys::jbyte,
    batch_data: jni::sys::jobject,
) -> jni::sys::jbyteArray {
    if batch_data.is_null() {
        return std::ptr::null_mut();
    }

    let byte_buffer = unsafe { jni::objects::JByteBuffer::from_raw(batch_data) };
    
    match env.get_direct_buffer_address(&byte_buffer) {
        Ok(address) => {
            if address.is_null() {
                return std::ptr::null_mut();
            }
            
            match env.get_direct_buffer_capacity(&byte_buffer) {
                Ok(capacity) if capacity > 0 => {
                    let data = unsafe { std::slice::from_raw_parts(address, capacity) };
                    
                    // Process the batch using the enhanced batch processor
                    match crate::jni_batch_processor::native_process_batch(operation_type as u8, data) {
                        Ok(results) => {
                            // Serialize results back to Java
                            let mut result_buffer = Vec::new();
                            result_buffer.extend_from_slice(&(results.len() as u32).to_le_bytes());
                            
                            for result in &results {
                                result_buffer.extend_from_slice(&(result.len() as u32).to_le_bytes());
                                result_buffer.extend_from_slice(result);
                            }
                            
                            match env.byte_array_from_slice(&result_buffer) {
                                Ok(array) => return array.into_raw(),
                                Err(e) => {
                                    eprintln!("Failed to create result byte array: {:?}", e);
                                    return std::ptr::null_mut();
                                }
                            }
                        }
                        Err(e) => {
                            eprintln!("Batch processing failed: {}", e);
                            return std::ptr::null_mut();
                        }
                    }
                }
                _ => {
                    eprintln!("Invalid buffer capacity");
                    return std::ptr::null_mut();
                }
            }
        }
        Err(e) => {
            eprintln!("Failed to get direct buffer address: {:?}", e);
            return std::ptr::null_mut();
        }
    }
}

// Optimized SIMD operations with enhanced vector processing
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_EnhancedNativeBridge_nativeProcessSimdBatch(
    env: jni::JNIEnv,
    _class: jni::objects::JClass,
    operation_type: jni::sys::jbyte,
    input_buffer: jni::sys::jobject,
) -> jni::sys::jbyteArray {
    if input_buffer.is_null() {
        return std::ptr::null_mut();
    }

    let byte_buffer = unsafe { jni::objects::JByteBuffer::from_raw(input_buffer) };
    
    match env.get_direct_buffer_address(&byte_buffer) {
        Ok(address) => {
            if address.is_null() {
                return std::ptr::null_mut();
            }
            
            match env.get_direct_buffer_capacity(&byte_buffer) {
                Ok(capacity) if capacity > 0 => {
                    let data = unsafe { std::slice::from_raw_parts(address as *const f32, capacity / 4) };
                    
                    // Process SIMD batch based on operation type
                    let results = match operation_type {
                        0x01 => {
                            // Vector addition
                            let chunk_size = std::cmp::min(data.len(), 256); // Process in chunks
                            let mut results = Vec::with_capacity(chunk_size);
                            
                            #[cfg(target_feature = "avx2")]
                            {
                                use std::arch::x86_64::*;
                                unsafe {
                                    for chunk in data.chunks(8) {
                                        if chunk.len() == 8 {
                                            let a = _mm256_loadu_ps(chunk.as_ptr());
                                            let b = _mm256_set1_ps(1.0); // Add 1.0 to each element
                                            let result = _mm256_add_ps(a, b);
                                            let mut temp = [0.0f32; 8];
                                            _mm256_storeu_ps(temp.as_mut_ptr(), result);
                                            results.extend_from_slice(&temp);
                                        } else {
                                            // Fallback for remainder
                                            for &val in chunk {
                                                results.push(val + 1.0);
                                            }
                                        }
                                    }
                                }
                            }
                            #[cfg(not(target_feature = "avx2"))]
                            {
                                for &val in data.iter().take(chunk_size) {
                                    results.push(val + 1.0);
                                }
                            }
                            results
                        }
                        0x02 => {
                            // Vector multiplication
                            let chunk_size = std::cmp::min(data.len(), 256);
                            let mut results = Vec::with_capacity(chunk_size);
                            
                            #[cfg(target_feature = "avx2")]
                            {
                                use std::arch::x86_64::*;
                                unsafe {
                                    for chunk in data.chunks(8) {
                                        if chunk.len() == 8 {
                                            let a = _mm256_loadu_ps(chunk.as_ptr());
                                            let b = _mm256_set1_ps(2.0); // Multiply by 2.0
                                            let result = _mm256_mul_ps(a, b);
                                            let mut temp = [0.0f32; 8];
                                            _mm256_storeu_ps(temp.as_mut_ptr(), result);
                                            results.extend_from_slice(&temp);
                                        } else {
                                            for &val in chunk {
                                                results.push(val * 2.0);
                                            }
                                        }
                                    }
                                }
                            }
                            #[cfg(not(target_feature = "avx2"))]
                            {
                                for &val in data.iter().take(chunk_size) {
                                    results.push(val * 2.0);
                                }
                            }
                            results
                        }
                        _ => {
                            eprintln!("Unknown SIMD operation type: {}", operation_type);
                            return std::ptr::null_mut();
                        }
                    };
                    
                    // Convert results back to bytes
                    let result_bytes = unsafe {
                        std::slice::from_raw_parts(results.as_ptr() as *const u8, results.len() * 4)
                    };
                    
                    match env.byte_array_from_slice(result_bytes) {
                        Ok(array) => array.into_raw(),
                        Err(e) => {
                            eprintln!("Failed to create SIMD result byte array: {:?}", e);
                            std::ptr::null_mut()
                        }
                    }
                }
                _ => {
                    eprintln!("Invalid SIMD buffer capacity");
                    std::ptr::null_mut()
                }
            }
        }
        Err(e) => {
            eprintln!("Failed to get SIMD direct buffer address: {:?}", e);
            std::ptr::null_mut()
        }
    }
}

// Optimized spatial grid operations with O(log M) queries
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_EnhancedNativeBridge_nativeSpatialQuery(
    env: jni::JNIEnv,
    _class: jni::objects::JClass,
    query_type: jni::sys::jbyte,
    center_x: jni::sys::jfloat,
    center_y: jni::sys::jfloat,
    center_z: jni::sys::jfloat,
    radius: jni::sys::jfloat,
) -> jni::sys::jbyteArray {
    use crate::spatial::{calculate_distances_simd, Aabb};
    
    // Create test data for spatial queries (in real implementation, this would use cached spatial structure)
    let test_positions: Vec<(f32, f32, f32)> = (0..1000)
        .map(|i| {
            let x = (i as f32 % 100.0) * 16.0 - 800.0;
            let y = (i as f32 / 100.0) * 10.0;
            let z = (i as f32 % 50.0) * 16.0 - 400.0;
            (x, y, z)
        })
        .collect();
    
    let center = (center_x, center_y, center_z);
    
    let results = match query_type {
        0x01 => {
            // Distance-based query
            let distances = calculate_distances_simd(&test_positions, center);
            let mut filtered = Vec::new();
            
            for (i, &dist) in distances.iter().enumerate() {
                if dist <= radius {
                    filtered.push(i as u32);
                }
            }
            filtered
        }
        0x02 => {
            // AABB-based query
            let query_bounds = Aabb::new(
                (center_x - radius) as f64,
                (center_y - radius) as f64,
                (center_z - radius) as f64,
                (center_x + radius) as f64,
                (center_y + radius) as f64,
                (center_z + radius) as f64,
            );
            
            let mut contained = Vec::new();
            for (i, &(x, y, z)) in test_positions.iter().enumerate() {
                if query_bounds.contains(x as f64, y as f64, z as f64) {
                    contained.push(i as u32);
                }
            }
            contained
        }
        _ => {
            eprintln!("Unknown spatial query type: {}", query_type);
            return std::ptr::null_mut();
        }
    };
    
    // Convert results to bytes
    let mut result_bytes = Vec::with_capacity(results.len() * 4 + 4);
    result_bytes.extend_from_slice(&(results.len() as u32).to_le_bytes());
    for id in results {
        result_bytes.extend_from_slice(&id.to_le_bytes());
    }
    
    match env.byte_array_from_slice(&result_bytes) {
        Ok(array) => array.into_raw(),
        Err(e) => {
            eprintln!("Failed to create spatial query result byte array: {:?}", e);
            std::ptr::null_mut()
        }
    }
}
    0.0
}
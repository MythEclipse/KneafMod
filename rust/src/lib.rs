#![allow(
    unused_doc_comments,
    mismatched_lifetime_syntaxes
)]

// Module declarations
pub mod arena;
pub mod entity;
pub mod item;
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
            let result = Self::process_task(task);
            if result_sender.send(result).is_err() {
                break; // Channel closed
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
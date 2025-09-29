use jni::{JNIEnv, objects::{JClass, JString, JByteBuffer, JObject}, sys::{jstring, jlong, jobject, jint, jbyteArray}};
use std::collections::VecDeque;
use std::sync::{Arc, Mutex, RwLock};
use std::thread;
use std::time::{Duration, Instant};
use crossbeam_channel::{bounded, Sender, Receiver};
use serde_json;
use log::{error, warn, info};
use std::sync::atomic::{AtomicUsize, AtomicU64, Ordering};

/// Configuration for JNI batching
#[derive(Debug, Clone)]
pub struct JniBatchConfig {
    pub max_batch_size: usize,
    pub batch_timeout_ms: u64,
    pub max_queue_size: usize,
    pub worker_threads: usize,
}

impl Default for JniBatchConfig {
    fn default() -> Self {
        Self {
            max_batch_size: 100,
            batch_timeout_ms: 5,
            max_queue_size: 1000,
            worker_threads: 2,
        }
    }
}

/// A batched JNI operation request
#[derive(Debug)]
pub enum JniOperation {
    ProcessEntities {
        input_json: String,
        response_tx: Sender<Result<String, String>>,
    },
    ProcessItems {
        input_json: String,
        response_tx: Sender<Result<String, String>>,
    },
    ProcessMobs {
        input_json: String,
        response_tx: Sender<Result<String, String>>,
    },
    ProcessBlocks {
        input_json: String,
        response_tx: Sender<Result<String, String>>,
    },
    ProcessBinary {
        operation_type: u8,
        input_data: Vec<u8>,
        response_tx: Sender<Result<Vec<u8>, String>>,
    },
}

/// Batch processor for JNI operations
pub struct JniBatchProcessor {
    request_queue: Arc<Mutex<VecDeque<JniOperation>>>,
    config: JniBatchConfig,
    metrics: Arc<JniBatchMetrics>,
    worker_handles: Vec<thread::JoinHandle<()>>,
}

/// Metrics for JNI batching
#[derive(Debug)]
pub struct JniBatchMetrics {
    pub total_operations: AtomicU64,
    pub batched_operations: AtomicU64,
    pub total_batch_time_ns: AtomicU64,
    pub queue_depth: AtomicUsize,
    pub failed_operations: AtomicU64,
}

impl JniBatchMetrics {
    pub fn new() -> Self {
        Self {
            total_operations: AtomicU64::new(0),
            batched_operations: AtomicU64::new(0),
            total_batch_time_ns: AtomicU64::new(0),
            queue_depth: AtomicUsize::new(0),
            failed_operations: AtomicU64::new(0),
        }
    }
}

impl JniBatchProcessor {
    pub fn new(config: JniBatchConfig) -> Self {
        let request_queue = Arc::new(Mutex::new(VecDeque::new()));
        let metrics = Arc::new(JniBatchMetrics::new());
        let mut worker_handles = Vec::new();

        // Spawn worker threads
        for worker_id in 0..config.worker_threads {
            let queue = Arc::clone(&request_queue);
            let metrics = Arc::clone(&metrics);
            let config = config.clone();

            let handle = thread::spawn(move || {
                Self::worker_thread(worker_id, queue, metrics, config);
            });
            worker_handles.push(handle);
        }

        Self {
            request_queue,
            config,
            metrics,
            worker_handles,
        }
    }

    fn worker_thread(
        worker_id: usize,
        queue: Arc<Mutex<VecDeque<JniOperation>>>,
        metrics: Arc<JniBatchMetrics>,
        config: JniBatchConfig,
    ) {
        let mut batch = Vec::new();
        let mut last_batch_time = Instant::now();

        loop {
            // Try to get operations from queue
            {
                let mut queue_guard = queue.lock().unwrap();
                while batch.len() < config.max_batch_size {
                    if let Some(op) = queue_guard.pop_front() {
                        batch.push(op);
                        metrics.queue_depth.fetch_sub(1, Ordering::SeqCst);
                    } else {
                        break;
                    }
                }
            }

            // Process batch if we have enough operations or timeout expired
            let should_process = batch.len() >= config.max_batch_size || 
                (batch.len() > 0 && last_batch_time.elapsed() >= Duration::from_millis(config.batch_timeout_ms));

            if should_process {
                let start_time = Instant::now();
                
                // Process the batch
                Self::process_batch(&batch, worker_id);
                
                let elapsed = start_time.elapsed();
                metrics.total_batch_time_ns.fetch_add(elapsed.as_nanos() as u64, Ordering::SeqCst);
                metrics.batched_operations.fetch_add(batch.len() as u64, Ordering::SeqCst);

                batch.clear();
                last_batch_time = Instant::now();
            }

            // Small sleep to prevent busy waiting
            if batch.is_empty() {
                thread::sleep(Duration::from_millis(1));
            }
        }
    }

    fn process_batch(batch: &[JniOperation], worker_id: usize) {
        // Group operations by type for more efficient processing
        let mut entity_ops = Vec::new();
        let mut item_ops = Vec::new();
        let mut mob_ops = Vec::new();
        let mut block_ops = Vec::new();
        let mut binary_ops = Vec::new();

        for op in batch {
            match op {
                JniOperation::ProcessEntities { input_json, response_tx } => {
                    entity_ops.push((input_json.clone(), response_tx.clone()));
                },
                JniOperation::ProcessItems { input_json, response_tx } => {
                    item_ops.push((input_json.clone(), response_tx.clone()));
                },
                JniOperation::ProcessMobs { input_json, response_tx } => {
                    mob_ops.push((input_json.clone(), response_tx.clone()));
                },
                JniOperation::ProcessBlocks { input_json, response_tx } => {
                    block_ops.push((input_json.clone(), response_tx.clone()));
                },
                JniOperation::ProcessBinary { operation_type, input_data, response_tx } => {
                    binary_ops.push((*operation_type, input_data.clone(), response_tx.clone()));
                },
            }
        }

        // Process each group in parallel
        rayon::join(
            || Self::process_entity_batch(&entity_ops),
            || {
                rayon::join(
                    || Self::process_item_batch(&item_ops),
                    || {
                        rayon::join(
                            || Self::process_mob_batch(&mob_ops),
                            || {
                                rayon::join(
                                    || Self::process_block_batch(&block_ops),
                                    || Self::process_binary_batch(&binary_ops),
                                );
                            },
                        );
                    },
                );
            },
        );
    }

    fn process_entity_batch(ops: &[(String, Sender<Result<String, String>>)]) {
        use crate::entity::processing::process_entities_json;
        
        for (input_json, response_tx) in ops {
            let result = process_entities_json(input_json);
            let _ = response_tx.send(result);
        }
    }

    fn process_item_batch(ops: &[(String, Sender<Result<String, String>>)]) {
        use crate::item::processing::process_item_entities_json;
        
        for (input_json, response_tx) in ops {
            let result = process_item_entities_json(input_json);
            let _ = response_tx.send(result);
        }
    }

    fn process_mob_batch(ops: &[(String, Sender<Result<String, String>>)]) {
        use crate::mob::processing::process_mob_ai_json;
        
        for (input_json, response_tx) in ops {
            let result = process_mob_ai_json(input_json);
            let _ = response_tx.send(result);
        }
    }

    fn process_block_batch(ops: &[(String, Sender<Result<String, String>>)]) {
        use crate::block::processing::process_block_entities_json;
        
        for (input_json, response_tx) in ops {
            let result = process_block_entities_json(input_json);
            let _ = response_tx.send(result);
        }
    }

    fn process_binary_batch(ops: &[(u8, Vec<u8>, Sender<Result<Vec<u8>, String>>)]) {
        use crate::flatbuffers::conversions::{deserialize_entity_input, serialize_entity_result};
        use crate::entity::processing::process_entities;
        
        for (op_type, input_data, response_tx) in ops {
            let result = match op_type {
                1 => {
                    // Entity processing
                    if let Some(input) = deserialize_entity_input(input_data) {
                        let result = process_entities(input);
                        Ok(serialize_entity_result(&result))
                    } else {
                        Err("Failed to deserialize entity input".to_string())
                    }
                },
                _ => Err("Unsupported operation type".to_string()),
            };
            let _ = response_tx.send(result);
        }
    }

    /// Submit an operation to the batch processor
    pub fn submit_operation(&self, operation: JniOperation) -> Result<(), String> {
        let queue_depth = self.metrics.queue_depth.load(Ordering::SeqCst);
        
        if queue_depth >= self.config.max_queue_size {
            return Err("Queue is full".to_string());
        }

        let mut queue = self.request_queue.lock().unwrap();
        queue.push_back(operation);
        self.metrics.queue_depth.fetch_add(1, Ordering::SeqCst);
        self.metrics.total_operations.fetch_add(1, Ordering::SeqCst);

        Ok(())
    }

    /// Get current metrics
    pub fn get_metrics(&self) -> JniBatchMetricsSnapshot {
        let total_ops = self.metrics.total_operations.load(Ordering::SeqCst);
        let batched_ops = self.metrics.batched_operations.load(Ordering::SeqCst);
        let total_time = self.metrics.total_batch_time_ns.load(Ordering::SeqCst);
        let queue_depth = self.metrics.queue_depth.load(Ordering::SeqCst);
        let failed_ops = self.metrics.failed_operations.load(Ordering::SeqCst);

        let avg_batch_time = if batched_ops > 0 {
            total_time as f64 / batched_ops as f64 / 1_000_000.0 // Convert to milliseconds
        } else {
            0.0
        };

        JniBatchMetricsSnapshot {
            total_operations: total_ops,
            batched_operations: batched_ops,
            average_batch_time_ms: avg_batch_time,
            queue_depth,
            failed_operations: failed_ops,
            batching_efficiency: if total_ops > 0 {
                batched_ops as f64 / total_ops as f64
            } else {
                0.0
            },
        }
    }
}

#[derive(Debug)]
pub struct JniBatchMetricsSnapshot {
    pub total_operations: u64,
    pub batched_operations: u64,
    pub average_batch_time_ms: f64,
    pub queue_depth: usize,
    pub failed_operations: u64,
    pub batching_efficiency: f64,
}

// Global batch processor instance
lazy_static::lazy_static! {
    static ref JNI_BATCH_PROCESSOR: Arc<RwLock<Option<Arc<JniBatchProcessor>>>> = Arc::new(RwLock::new(None));
}

/// Initialize the global JNI batch processor
pub fn init_jni_batch_processor(config: JniBatchConfig) -> Result<(), String> {
    let mut processor_guard = JNI_BATCH_PROCESSOR.write().unwrap();
    
    if processor_guard.is_some() {
        return Err("JNI batch processor already initialized".to_string());
    }

    let processor = Arc::new(JniBatchProcessor::new(config));
    *processor_guard = Some(processor);
    
    info!("JNI batch processor initialized with config: {:?}", config);
    Ok(())
}

/// Get the global JNI batch processor
pub fn get_jni_batch_processor() -> Option<Arc<JniBatchProcessor>> {
    JNI_BATCH_PROCESSOR.read().unwrap().clone()
}

/// Convenience function to submit an entity processing operation
pub fn submit_entity_operation(input_json: String) -> Result<Receiver<Result<String, String>>, String> {
    let (tx, rx) = bounded(1);
    let operation = JniOperation::ProcessEntities {
        input_json,
        response_tx: tx,
    };

    if let Some(processor) = get_jni_batch_processor() {
        processor.submit_operation(operation)?;
        Ok(rx)
    } else {
        Err("JNI batch processor not initialized".to_string())
    }
}

/// Convenience function to submit an item processing operation
pub fn submit_item_operation(input_json: String) -> Result<Receiver<Result<String, String>>, String> {
    let (tx, rx) = bounded(1);
    let operation = JniOperation::ProcessItems {
        input_json,
        response_tx: tx,
    };

    if let Some(processor) = get_jni_batch_processor() {
        processor.submit_operation(operation)?;
        Ok(rx)
    } else {
        Err("JNI batch processor not initialized".to_string())
    }
}

/// JNI function to get batch processor metrics
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_RustPerformance_getBatchProcessorMetrics(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    if let Some(processor) = get_jni_batch_processor() {
        let metrics = processor.get_metrics();
        let metrics_json = serde_json::json!({
            "totalOperations": metrics.total_operations,
            "batchedOperations": metrics.batched_operations,
            "averageBatchTimeMs": metrics.average_batch_time_ms,
            "queueDepth": metrics.queue_depth,
            "failedOperations": metrics.failed_operations,
            "batchingEfficiency": metrics.batching_efficiency,
        });

        match env.new_string(&serde_json::to_string(&metrics_json).unwrap_or_default()) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    } else {
        match env.new_string("{\"error\":\"Batch processor not initialized\"}") {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }
}

/// JNI function to initialize batch processor
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_RustPerformance_initBatchProcessor(
    _env: JNIEnv,
    _class: JClass,
    max_batch_size: jint,
    batch_timeout_ms: jlong,
    max_queue_size: jint,
    worker_threads: jint,
) -> jint {
    let config = JniBatchConfig {
        max_batch_size: max_batch_size as usize,
        batch_timeout_ms: batch_timeout_ms as u64,
        max_queue_size: max_queue_size as usize,
        worker_threads: worker_threads as usize,
    };

    match init_jni_batch_processor(config) {
        Ok(_) => 0, // Success
        Err(_) => 1, // Error
    }
}
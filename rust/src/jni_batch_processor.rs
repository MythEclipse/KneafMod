use jni::{JNIEnv, objects::JClass, sys::{jlong, jint, jstring}};
use std::collections::VecDeque;
use std::sync::{Arc, Mutex, RwLock};
use std::time::{Duration, Instant};
use serde_json;
use log::{error, info, debug};
use std::sync::atomic::{AtomicUsize, AtomicU64, AtomicBool, Ordering};
use std::thread;
use std::cmp;

/// Configuration for enhanced JNI batch processing
#[derive(Debug, Clone)]
pub struct EnhancedBatchConfig {
    pub min_batch_size: usize,
    pub max_batch_size: usize,
    pub adaptive_batch_timeout_ms: u64,
    pub max_pending_batches: usize,
    pub worker_threads: usize,
    pub enable_adaptive_sizing: bool,
}

impl Default for EnhancedBatchConfig {
    fn default() -> Self {
        Self {
            min_batch_size: 10,
            max_batch_size: 200,
            adaptive_batch_timeout_ms: 2,
            max_pending_batches: 50,
            worker_threads: 4,
            enable_adaptive_sizing: true,
        }
    }
}

/// Enhanced batch operation with metadata
#[derive(Debug, Clone)]
pub struct EnhancedBatchOperation {
    pub operation_type: u8,
    pub input_data: Vec<u8>,
    pub estimated_size: usize,
    pub timestamp: Instant,
    pub priority: u8, // 0-255, higher is more urgent
}

impl EnhancedBatchOperation {
    pub fn new(operation_type: u8, input_data: Vec<u8>, priority: u8) -> Self {
        let estimated_size = input_data.len();
        Self {
            operation_type,
            input_data,
            estimated_size,
            timestamp: Instant::now(),
            priority,
        }
    }
}

/// Batch processing result
#[derive(Debug, Clone)]
pub struct EnhancedBatchResult {
    pub operation_type: u8,
    pub results: Vec<Vec<u8>>,
    pub processing_time_ns: u64,
    pub batch_size: usize,
    pub success_count: usize,
}

/// Enhanced metrics for batch processing
#[derive(Debug, Default)]
pub struct EnhancedBatchMetrics {
    pub total_batches_processed: AtomicU64,
    pub total_operations_batched: AtomicU64,
    pub average_batch_size: AtomicU64,
    pub current_queue_depth: AtomicUsize,
    pub failed_operations: AtomicU64,
    pub total_processing_time_ns: AtomicU64,
    pub adaptive_batch_size: AtomicUsize,
    pub pressure_level: AtomicU64, // 0-100, higher means more pressure
}

impl EnhancedBatchMetrics {
    pub fn new() -> Self {
        Self {
            total_batches_processed: AtomicU64::new(0),
            total_operations_batched: AtomicU64::new(0),
            average_batch_size: AtomicU64::new(50), // Start with reasonable default
            current_queue_depth: AtomicUsize::new(0),
            failed_operations: AtomicU64::new(0),
            total_processing_time_ns: AtomicU64::new(0),
            adaptive_batch_size: AtomicUsize::new(50),
            pressure_level: AtomicU64::new(0),
        }
    }

    pub fn update_average_batch_size(&self, new_size: usize) {
        let current = self.average_batch_size.load(Ordering::Relaxed);
        let updated = (current * 9 + new_size as u64) / 10; // Exponential moving average
        self.average_batch_size.store(updated, Ordering::Relaxed);
        self.adaptive_batch_size.store(updated as usize, Ordering::Relaxed);
    }

    pub fn get_pressure_level(&self) -> u8 {
        self.pressure_level.load(Ordering::Relaxed) as u8
    }

    pub fn set_pressure_level(&self, level: u8) {
        self.pressure_level.store(level as u64, Ordering::Relaxed);
    }
}

/// Priority queue for batch operations
#[derive(Debug)]
pub struct PriorityBatchQueue {
    queue: Arc<Mutex<VecDeque<EnhancedBatchOperation>>>,
    metrics: Arc<EnhancedBatchMetrics>,
}

impl PriorityBatchQueue {
    pub fn new(metrics: Arc<EnhancedBatchMetrics>) -> Self {
        Self {
            queue: Arc::new(Mutex::new(VecDeque::new())),
            metrics,
        }
    }

    pub fn push(&self, operation: EnhancedBatchOperation) {
        let mut queue = self.queue.lock().unwrap();
        
        // Insert based on priority (higher priority first)
        let insert_pos = queue.binary_search_by_key(&operation.priority, |op| op.priority)
            .unwrap_or_else(|pos| pos);
        queue.insert(insert_pos, operation);
        
        self.metrics.current_queue_depth.store(queue.len(), Ordering::Relaxed);
    }

    pub fn pop(&self) -> Option<EnhancedBatchOperation> {
        let mut queue = self.queue.lock().unwrap();
        let operation = queue.pop_front();
        self.metrics.current_queue_depth.store(queue.len(), Ordering::Relaxed);
        operation
    }

    pub fn drain_batch(&self, max_size: usize, timeout: Duration) -> Vec<EnhancedBatchOperation> {
        let start_time = Instant::now();
        let mut batch = Vec::with_capacity(max_size);
        
        while batch.len() < max_size && start_time.elapsed() < timeout {
            if let Some(operation) = self.pop() {
                batch.push(operation);
            } else if !batch.is_empty() {
                // Have some operations, break if timeout approaching
                break;
            } else {
                // No operations available, wait a bit
                std::thread::sleep(Duration::from_micros(100));
            }
        }
        
        batch
    }

    pub fn len(&self) -> usize {
        self.queue.lock().unwrap().len()
    }

    pub fn is_empty(&self) -> bool {
        self.queue.lock().unwrap().is_empty()
    }
}

/// Enhanced batch processor with adaptive sizing and pressure management
pub struct EnhancedBatchProcessor {
_config: EnhancedBatchConfig,
queues: Arc<Vec<PriorityBatchQueue>>,
metrics: Arc<EnhancedBatchMetrics>,
worker_handles: Vec<thread::JoinHandle<()>>,
shutdown_flag: Arc<AtomicBool>,
}

impl EnhancedBatchProcessor {
    pub fn new(config: EnhancedBatchConfig) -> Self {
        let metrics = Arc::new(EnhancedBatchMetrics::new());
        let mut queues = Vec::with_capacity(7); // 7 operation types
        
        // Create priority queues for each operation type
        for _ in 0..7 {
            queues.push(PriorityBatchQueue::new(Arc::clone(&metrics)));
        }
        
        let queues = Arc::new(queues);
        let shutdown_flag = Arc::new(AtomicBool::new(false));
        let mut worker_handles = Vec::with_capacity(config.worker_threads);
        
        // Spawn worker threads
        for worker_id in 0..config.worker_threads {
            let queues_clone = Arc::clone(&queues);
            let metrics_clone = Arc::clone(&metrics);
            let config_clone = config.clone();
            let shutdown_clone = Arc::clone(&shutdown_flag);
            
            let handle = thread::spawn(move || {
                Self::worker_thread(worker_id, queues_clone, metrics_clone, config_clone, shutdown_clone);
            });
            worker_handles.push(handle);
        }
        
        Self {
            _config: config,
            queues,
            metrics,
            worker_handles,
            shutdown_flag,
        }
    }

    fn worker_thread(
        worker_id: usize,
        queues: Arc<Vec<PriorityBatchQueue>>,
        metrics: Arc<EnhancedBatchMetrics>,
        config: EnhancedBatchConfig,
        shutdown_flag: Arc<AtomicBool>,
    ) {
        let thread_name = format!("EnhancedBatchProcessor-{}", worker_id);
        debug!("Starting {}", thread_name);
        
        while !shutdown_flag.load(Ordering::Relaxed) {
            let mut processed_any = false;
            
            // Process queues round-robin with priority consideration
            for operation_type in 0..queues.len() {
                if shutdown_flag.load(Ordering::Relaxed) {
                    break;
                }
                
                let queue = &queues[operation_type];
                let current_depth = queue.len();
                
                if current_depth == 0 {
                    continue;
                }
                
                // Calculate optimal batch size based on pressure and queue depth
                let optimal_batch_size = Self::calculate_optimal_batch_size(
                    &config,
                    &metrics,
                    current_depth,
                    operation_type as u8
                );
                
                let timeout = Duration::from_millis(config.adaptive_batch_timeout_ms);
                let batch = queue.drain_batch(optimal_batch_size, timeout);
                
                if !batch.is_empty() {
                    processed_any = true;
                    Self::process_batch(operation_type as u8, batch, &metrics);
                }
            }
            
            // Adaptive sleep based on processing activity
            if !processed_any {
                let sleep_duration = if metrics.get_pressure_level() > 50 {
                    Duration::from_micros(50) // High pressure, sleep less
                } else {
                    Duration::from_millis(1) // Normal pressure
                };
                thread::sleep(sleep_duration);
            }
        }
        
        debug!("Shutting down {}", thread_name);
    }

    fn calculate_optimal_batch_size(
        config: &EnhancedBatchConfig,
        metrics: &Arc<EnhancedBatchMetrics>,
        queue_depth: usize,
        _operation_type: u8,
    ) -> usize {
        if !config.enable_adaptive_sizing {
            return config.min_batch_size;
        }
        
        let base_size = metrics.adaptive_batch_size.load(Ordering::Relaxed);
        let pressure_level = metrics.get_pressure_level();
        
        // Adjust batch size based on pressure and queue depth
        let multiplier = match pressure_level {
            0..=20 => 1.2,   // Low pressure, increase batch size
            21..=50 => 1.0,  // Normal pressure, use base size
            51..=80 => 0.8,  // Medium pressure, reduce batch size
            _ => 0.6,        // High pressure, reduce significantly
        };
        
        let adjusted_size = (base_size as f64 * multiplier) as usize;
        
        // Further adjust based on queue depth
        let depth_factor = match queue_depth {
            d if d < config.min_batch_size => 0.8,
            d if d > config.max_batch_size * 2 => 1.5,
            _ => 1.0,
        };
        
        let final_size = (adjusted_size as f64 * depth_factor) as usize;
        
        cmp::max(config.min_batch_size, cmp::min(config.max_batch_size, final_size))
    }

    fn process_batch(operation_type: u8, batch: Vec<EnhancedBatchOperation>, metrics: &Arc<EnhancedBatchMetrics>) {
        let start_time = Instant::now();
        let batch_size = batch.len();
        
        debug!("Processing batch of {} operations for type {}", batch_size, operation_type);
        
        match Self::execute_native_batch(operation_type, &batch) {
            Ok(_results) => {
                let processing_time = start_time.elapsed().as_nanos() as u64;
                
                // Update metrics
                metrics.total_batches_processed.fetch_add(1, Ordering::Relaxed);
                metrics.total_operations_batched.fetch_add(batch_size as u64, Ordering::Relaxed);
                metrics.total_processing_time_ns.fetch_add(processing_time, Ordering::Relaxed);
                metrics.update_average_batch_size(batch_size);
                
                debug!("Batch processed successfully: {} operations in {} ns", batch_size, processing_time);
            }
            Err(e) => {
                error!("Batch processing failed: {}", e);
                metrics.failed_operations.fetch_add(batch_size as u64, Ordering::Relaxed);
            }
        }
    }

    fn execute_native_batch(operation_type: u8, batch: &[EnhancedBatchOperation]) -> Result<Vec<Vec<u8>>, String> {
        if batch.is_empty() {
            return Ok(Vec::new());
        }
        
        // Prepare batch data
        let mut total_size = 0;
        for operation in batch {
            total_size += operation.input_data.len() + 8; // data + header
        }
        
        let mut batch_data = Vec::with_capacity(total_size + 8); // + header
        let mut operation_sizes = Vec::with_capacity(batch.len());
        
        // Write batch header
        batch_data.extend_from_slice(&(batch.len() as u32).to_le_bytes());
        batch_data.extend_from_slice(&(total_size as u32).to_le_bytes());
        
        // Write individual operations
        for operation in batch {
            let data_len = operation.input_data.len();
            batch_data.extend_from_slice(&(data_len as u32).to_le_bytes());
            batch_data.extend_from_slice(&operation.input_data);
            operation_sizes.push(data_len);
        }
        
        // Call native batch processing function (this would be implemented in the main lib.rs)
        match native_process_batch(operation_type, &batch_data) {
            Ok(results) => Ok(results),
            Err(e) => Err(format!("Native batch processing failed: {}", e)),
        }
    }

    /// Submit operation to the processor
    pub fn submit_operation(&self, operation: EnhancedBatchOperation) -> Result<(), String> {
        let operation_type = operation.operation_type as usize;
        
        if operation_type >= self.queues.len() {
            return Err(format!("Invalid operation type: {}", operation.operation_type));
        }
        
        self.queues[operation_type].push(operation);
        Ok(())
    }

    /// Get current metrics
    pub fn get_metrics(&self) -> EnhancedBatchMetricsSnapshot {
        EnhancedBatchMetricsSnapshot {
            total_batches_processed: self.metrics.total_batches_processed.load(Ordering::Relaxed),
            total_operations_batched: self.metrics.total_operations_batched.load(Ordering::Relaxed),
            average_batch_size: self.metrics.average_batch_size.load(Ordering::Relaxed),
            current_queue_depth: self.metrics.current_queue_depth.load(Ordering::Relaxed),
            failed_operations: self.metrics.failed_operations.load(Ordering::Relaxed),
            average_processing_time_ms: {
                let total_time = self.metrics.total_processing_time_ns.load(Ordering::Relaxed);
                let total_ops = self.metrics.total_operations_batched.load(Ordering::Relaxed);
                if total_ops > 0 {
                    (total_time as f64 / total_ops as f64) / 1_000_000.0
                } else {
                    0.0
                }
            },
            adaptive_batch_size: self.metrics.adaptive_batch_size.load(Ordering::Relaxed),
            pressure_level: self.metrics.get_pressure_level(),
        }
    }

    /// Shutdown the processor
    pub fn shutdown(&self) {
        self.shutdown_flag.store(true, Ordering::Relaxed);
        
        for handle in &self.worker_handles {
            handle.thread().unpark();
        }
        
        // Wait for workers to finish
        for _handle in self.worker_handles.iter() {
            // Don't join here, let the thread run independently
        }
    }
}

/// Metrics snapshot for reporting
#[derive(Debug, Clone, serde::Serialize)]
pub struct EnhancedBatchMetricsSnapshot {
    pub total_batches_processed: u64,
    pub total_operations_batched: u64,
    pub average_batch_size: u64,
    pub current_queue_depth: usize,
    pub failed_operations: u64,
    pub average_processing_time_ms: f64,
    pub adaptive_batch_size: usize,
    pub pressure_level: u8,
}

// Global enhanced batch processor instance
lazy_static::lazy_static! {
    static ref ENHANCED_BATCH_PROCESSOR: Arc<RwLock<Option<Arc<EnhancedBatchProcessor>>>> = 
        Arc::new(RwLock::new(None));
}

/// Initialize the enhanced batch processor
pub fn init_enhanced_batch_processor(config: EnhancedBatchConfig) -> Result<(), String> {
    let config_clone = config.clone();
    let mut processor_guard = ENHANCED_BATCH_PROCESSOR.write()
        .map_err(|_| "Enhanced batch processor RwLock poisoned")?;
    
    if processor_guard.is_some() {
        return Err("Enhanced batch processor already initialized".to_string());
    }
    
    let processor = Arc::new(EnhancedBatchProcessor::new(config_clone));
    *processor_guard = Some(processor);
    
    info!("Enhanced batch processor initialized with config: {:?}", config);
    Ok(())
}

/// Get the enhanced batch processor
pub fn get_enhanced_batch_processor() -> Option<Arc<EnhancedBatchProcessor>> {
    ENHANCED_BATCH_PROCESSOR.read()
        .ok()
        .and_then(|guard| guard.clone())
}

/// Submit operation to the enhanced batch processor
pub fn submit_enhanced_operation(operation: EnhancedBatchOperation) -> Result<(), String> {
    if let Some(processor) = get_enhanced_batch_processor() {
        processor.submit_operation(operation)
    } else {
        Err("Enhanced batch processor not initialized".to_string())
    }
}

/// Native function to process batch (to be implemented in main lib.rs)
pub fn native_process_batch(_operation_type: u8, batch_data: &[u8]) -> Result<Vec<Vec<u8>>, String> {
    // This function will be implemented in the main lib.rs file
    // For now, return a placeholder implementation
    Ok(vec![Vec::new(); batch_data.len() / 1024 + 1])
}

/// JNI function to get enhanced batch processor metrics
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_EnhancedNativeBridge_getEnhancedBatchMetrics(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    if let Some(processor) = get_enhanced_batch_processor() {
        let metrics = processor.get_metrics();
        let metrics_json = serde_json::json!({
            "totalBatchesProcessed": metrics.total_batches_processed,
            "totalOperationsBatched": metrics.total_operations_batched,
            "averageBatchSize": metrics.average_batch_size,
            "currentQueueDepth": metrics.current_queue_depth,
            "failedOperations": metrics.failed_operations,
            "averageProcessingTimeMs": metrics.average_processing_time_ms,
            "adaptiveBatchSize": metrics.adaptive_batch_size,
            "pressureLevel": metrics.pressure_level,
        });

        match env.new_string(&serde_json::to_string(&metrics_json).unwrap_or_default()) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    } else {
        match env.new_string("{\"error\":\"Enhanced batch processor not initialized\"}") {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }
}

/// JNI function to initialize enhanced batch processor
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_EnhancedNativeBridge_initEnhancedBatchProcessor(
    _env: JNIEnv,
    _class: JClass,
    min_batch_size: jint,
    max_batch_size: jint,
    adaptive_batch_timeout_ms: jlong,
    max_pending_batches: jint,
    worker_threads: jint,
    enable_adaptive_sizing: bool,
) -> jint {
    let config = EnhancedBatchConfig {
        min_batch_size: min_batch_size as usize,
        max_batch_size: max_batch_size as usize,
        adaptive_batch_timeout_ms: adaptive_batch_timeout_ms as u64,
        max_pending_batches: max_pending_batches as usize,
        worker_threads: worker_threads as usize,
        enable_adaptive_sizing,
    };

    match init_enhanced_batch_processor(config) {
        Ok(_) => 0, // Success
        Err(_) => 1, // Error
    }
}
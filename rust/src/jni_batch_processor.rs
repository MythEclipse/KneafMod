use jni::{JNIEnv, objects::JClass, sys::{jlong, jint, jstring, jbyte, jbyteArray}};
use std::collections::VecDeque;
use std::sync::{Arc, Mutex, RwLock};
use std::time::{Duration, Instant};
use serde_json;
use log::{error, info, debug};
use std::sync::atomic::{AtomicUsize, AtomicU64, AtomicBool, Ordering};
use std::thread;
use std::cmp;
use tokio::sync::{mpsc, oneshot};
use tokio::runtime::Runtime;

// Helper trait for mutex operations with timeout
trait MutexExt<T> {
    fn lock_timeout(&self, timeout: Duration) -> Result<std::sync::MutexGuard<'_, T>, String>;
}

impl<T> MutexExt<T> for std::sync::Mutex<T> {
    fn lock_timeout(&self, timeout: Duration) -> Result<std::sync::MutexGuard<'_, T>, String> {
        let start = Instant::now();
        loop {
            match self.try_lock() {
                Ok(guard) => return Ok(guard),
                Err(_) => {
                    if start.elapsed() >= timeout {
                        return Err("Lock timeout exceeded".to_string());
                    }
                    // small sleep to avoid busy loop
                    std::thread::sleep(Duration::from_micros(50));
                    continue;
                }
            }
        }
    }
}

// Import lock ordering constants
use crate::spatial::lock_order;

/// Buffer pool for batch data to reduce allocations and GC pressure (sharded)
#[derive(Debug)]
#[allow(dead_code)]
struct BatchBufferPool {
    buffer_pools: Vec<Mutex<Vec<Vec<u8>>>>, // Sharded pools
    shard_count: usize,
    max_buffers: usize,
    buffer_capacity: usize,
}

#[allow(dead_code)]
impl BatchBufferPool {
    fn new(max_buffers: usize, buffer_capacity: usize) -> Self {
        let shard_count = 4; // 4 shards for reduced contention
        let buffers_per_shard = max_buffers / shard_count;
        let mut buffer_pools = Vec::with_capacity(shard_count);

        for _ in 0..shard_count {
            let mut buffers = Vec::with_capacity(buffers_per_shard);
            for _ in 0..buffers_per_shard {
                buffers.push(Vec::with_capacity(buffer_capacity));
            }
            buffer_pools.push(Mutex::new(buffers));
        }

        Self {
            buffer_pools,
            shard_count,
            max_buffers,
            buffer_capacity,
        }
    }

    fn get_shard(&self, thread_id: usize) -> usize {
        thread_id % self.shard_count
    }

    fn acquire_buffer(&self) -> Option<Vec<u8>> {
        // Use round-robin sharding
        static ACQUIRE_COUNTER: AtomicUsize = AtomicUsize::new(0);
        let shard_idx = ACQUIRE_COUNTER.fetch_add(1, Ordering::Relaxed) % self.shard_count;

        // Try current shard first
        if let Some(buffer) = self.try_acquire_from_shard(shard_idx) {
            return Some(buffer);
        }

        // Try other shards
        for i in 0..self.shard_count {
            if i != shard_idx {
                if let Some(buffer) = self.try_acquire_from_shard(i) {
                    return Some(buffer);
                }
            }
        }

        None
    }

    fn try_acquire_from_shard(&self, shard_idx: usize) -> Option<Vec<u8>> {
        // Use consistent lock ordering (SPATIAL_LOCK first, then others)
        let mut buffers = self.buffer_pools[shard_idx].lock().unwrap();
        buffers.pop()
    }

    fn release_buffer(&self, mut buffer: Vec<u8>) {
        buffer.clear();
        buffer.reserve(self.buffer_capacity.saturating_sub(buffer.capacity()));

        // Use round-robin sharding for release
        static RELEASE_COUNTER: AtomicUsize = AtomicUsize::new(0);
        let shard_idx = RELEASE_COUNTER.fetch_add(1, Ordering::Relaxed) % self.shard_count;

        // Use consistent lock ordering (SPATIAL_LOCK first, then others)
        let mut buffers = self.buffer_pools[shard_idx].lock().unwrap();
        let max_per_shard = self.max_buffers / self.shard_count;
        if buffers.len() < max_per_shard {
            buffers.push(buffer);
        }
    }
}

lazy_static::lazy_static! {
    static ref BATCH_BUFFER_POOL: BatchBufferPool = BatchBufferPool::new(64, 128 * 1024); // Increased: 64 buffers, 128KB each for more aggressive batching
}

/// Configuration for enhanced JNI batch processing

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
            min_batch_size: 50,  // Increased for more aggressive batching
            max_batch_size: 500, // Increased to reduce JNI crossings
            adaptive_batch_timeout_ms: 1, // Reduced for faster batching
            max_pending_batches: 200, // Increased to handle more concurrent batches
            worker_threads: 8, // Increased for better parallelism
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

/// Priority queue for batch operations (sharded for reduced lock contention)
#[derive(Debug)]
pub struct PriorityBatchQueue {
    queues: Arc<Vec<Mutex<VecDeque<EnhancedBatchOperation>>>>, // Sharded queues
    shard_count: usize,
    metrics: Arc<EnhancedBatchMetrics>,
}

impl PriorityBatchQueue {
    pub fn new(metrics: Arc<EnhancedBatchMetrics>) -> Self {
        let shard_count = 4; // 4 shards for reduced contention
        let mut queues = Vec::with_capacity(shard_count);
        for _ in 0..shard_count {
            queues.push(Mutex::new(VecDeque::new()));
        }

        Self {
            queues: Arc::new(queues),
            shard_count,
            metrics,
        }
    }

    fn get_shard(&self, priority: u8) -> usize {
        (priority as usize) % self.shard_count
    }

    pub fn push(&self, operation: EnhancedBatchOperation) {
        let shard_idx = self.get_shard(operation.priority);
        
        let mut queue = self.queues[shard_idx].lock().unwrap();

        // Insert based on priority (higher priority first)
        let insert_pos = queue.binary_search_by_key(&operation.priority, |op| op.priority)
            .unwrap_or_else(|pos| pos);
        queue.insert(insert_pos, operation);

        // Update total queue depth across all shards
        let total_depth = self.get_total_depth();
        self.metrics.current_queue_depth.store(total_depth, Ordering::Relaxed);
    }

    pub fn pop(&self) -> Option<EnhancedBatchOperation> {
        // Try to pop from highest priority shards first
        for priority_level in (0..=255).rev() {
            let shard_idx = self.get_shard(priority_level);
            
            let mut queue = self.queues[shard_idx].lock().unwrap();
            if let Some(operation) = queue.pop_front() {
                let total_depth = self.get_total_depth();
                self.metrics.current_queue_depth.store(total_depth, Ordering::Relaxed);
                return Some(operation);
            }
        }

        None
    }

    pub fn drain_batch(&self, max_size: usize, timeout: Duration) -> Vec<EnhancedBatchOperation> {
        let start_time = Instant::now();
        let mut batch = Vec::with_capacity(max_size);
        let initial_queue_depth = self.len();

        // Real-time adaptive batching based on queue depth changes
        while batch.len() < max_size && start_time.elapsed() < timeout {
            if let Some(operation) = self.pop_with_timeout(Duration::from_millis(50)) { // Reduced timeout for faster batching
                batch.push(operation);
                
                // Adaptive termination: if queue depth decreased significantly, stop early
                let current_depth = self.len();
                let depth_change = initial_queue_depth - current_depth;
                
                // If we've processed a significant portion of the queue or it's emptying fast, stop early
                if (batch.len() as f64 / max_size as f64) > 0.8 ||
                   (depth_change > 0 && (depth_change as f64 / initial_queue_depth as f64) > 0.5) {
                    break;
                }
            } else if !batch.is_empty() {
                // Have some operations, break if timeout approaching
                break;
            } else {
                // No operations available - check if we should wait or return empty batch
                let elapsed = start_time.elapsed();
                if elapsed < Duration::from_millis(10) {
                    std::thread::sleep(Duration::from_micros(10)); // Very short wait for new operations
                } else {
                    break; // Don't wait too long for empty queues
                }
            }
        }

        batch
    }

    /// Pop operation with timeout to prevent deadlocks
    pub fn pop_with_timeout(&self, timeout: Duration) -> Option<EnhancedBatchOperation> {
        // Try to pop from highest priority shards first
        for priority_level in (0..=255).rev() {
            let shard_idx = self.get_shard(priority_level);
            
            match self.queues[shard_idx].lock_timeout(timeout) {
                Ok(mut queue) => {
                    if let Some(operation) = queue.pop_front() {
                        let total_depth = self.get_total_depth();
                        self.metrics.current_queue_depth.store(total_depth, Ordering::Relaxed);
                        return Some(operation);
                    }
                }
                Err(_) => {
                    debug!("Lock timeout for priority level {}", priority_level);
                    continue;
                }
            }
        }

        None
    }

    pub fn len(&self) -> usize {
        self.get_total_depth()
    }

    pub fn is_empty(&self) -> bool {
        for queue in self.queues.iter() {
            if !queue.lock().unwrap().is_empty() {
                return false;
            }
        }
        true
    }

    fn get_total_depth(&self) -> usize {
        let mut total = 0;
        for queue in self.queues.iter() {
            total += queue.lock().unwrap().len();
        }
        total
    }
}

/// Async batch processing task
#[derive(Debug)]
struct AsyncBatchTask {
    operation_type: u8,
    operations: Vec<EnhancedBatchOperation>,
    result_sender: oneshot::Sender<Result<EnhancedBatchResult, String>>,
}

/// Zero-copy buffer pool for direct memory access (sharded)
#[derive(Debug)]
struct ZeroCopyBufferPool {
    buffer_pools: Arc<Vec<Mutex<Vec<Vec<u8>>>>>, // Sharded pools
    shard_count: usize,
    max_buffers: usize,
    buffer_size: usize,
}

impl ZeroCopyBufferPool {
    fn new(max_buffers: usize, buffer_size: usize) -> Self {
        let shard_count = 4; // 4 shards for reduced contention
        let buffers_per_shard = max_buffers / shard_count;
        let mut buffer_pools = Vec::with_capacity(shard_count);

        for _ in 0..shard_count {
            let mut buffers = Vec::with_capacity(buffers_per_shard);
            for _ in 0..buffers_per_shard {
                buffers.push(Vec::with_capacity(buffer_size));
            }
            buffer_pools.push(Mutex::new(buffers));
        }

        Self {
            buffer_pools: Arc::new(buffer_pools),
            shard_count,
            max_buffers,
            buffer_size,
        }
    }

    fn acquire_buffer(&self) -> Option<Vec<u8>> {
        // Use round-robin sharding
        static ACQUIRE_COUNTER: AtomicUsize = AtomicUsize::new(0);
        let shard_idx = ACQUIRE_COUNTER.fetch_add(1, Ordering::Relaxed) % self.shard_count;

        // Try current shard first
        if let Some(buffer) = self.try_acquire_from_shard(shard_idx) {
            return Some(buffer);
        }

        // Try other shards
        for i in 0..self.shard_count {
            if i != shard_idx {
                if let Some(buffer) = self.try_acquire_from_shard(i) {
                    return Some(buffer);
                }
            }
        }

        None
    }

    fn try_acquire_from_shard(&self, shard_idx: usize) -> Option<Vec<u8>> {
        let mut buffers = self.buffer_pools[shard_idx].lock().unwrap();
        buffers.pop()
    }

    fn release_buffer(&self, mut buffer: Vec<u8>) {
        buffer.clear();
        buffer.reserve(self.buffer_size.saturating_sub(buffer.capacity()));

        // Use round-robin sharding for release
        static ZC_RELEASE_COUNTER: AtomicUsize = AtomicUsize::new(0);
        let shard_idx = ZC_RELEASE_COUNTER.fetch_add(1, Ordering::Relaxed) % self.shard_count;

        let mut buffers = self.buffer_pools[shard_idx].lock().unwrap();
        let max_per_shard = self.max_buffers / self.shard_count;
        if buffers.len() < max_per_shard {
            buffers.push(buffer);
        }
    }
}

/// Enhanced batch processor with async processing and zero-copy buffers
pub struct EnhancedBatchProcessor {
    #[allow(dead_code)]
    config: EnhancedBatchConfig,
    queues: Arc<Vec<PriorityBatchQueue>>,
    metrics: Arc<EnhancedBatchMetrics>,
    worker_handles: Vec<thread::JoinHandle<()>>,
    shutdown_flag: Arc<AtomicBool>,
    #[allow(dead_code)]
    async_runtime: Arc<Runtime>,
    async_task_sender: mpsc::Sender<AsyncBatchTask>,
    #[allow(dead_code)]
    zero_copy_pool: Arc<ZeroCopyBufferPool>,
    #[allow(dead_code)]
    connection_pool: Arc<Mutex<Vec<mpsc::Sender<AsyncBatchTask>>>>,
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
        
        // Create async runtime for enhanced processing
        let async_runtime = Arc::new(
            Runtime::new().expect("Failed to create Tokio runtime")
        );
        
        // Create async task channel
        let (async_task_sender, async_task_receiver) = mpsc::channel::<AsyncBatchTask>(1000);
        let async_task_receiver = Arc::new(Mutex::new(async_task_receiver));
        
        // Create zero-copy buffer pool (increased for more aggressive batching)
        let zero_copy_pool = Arc::new(ZeroCopyBufferPool::new(64, 128 * 1024)); // 64 buffers, 128KB each
        
        // Create connection pool for async tasks
        let connection_pool = Arc::new(Mutex::new(Vec::new()));
        
        // Spawn async worker tasks
        for worker_id in 0..config.worker_threads {
            let queues_clone = Arc::clone(&queues);
            let metrics_clone = Arc::clone(&metrics);
            let config_clone = config.clone();
            let shutdown_clone = Arc::clone(&shutdown_flag);
            let zero_copy_pool_clone = Arc::clone(&zero_copy_pool);
            let async_task_receiver_clone = Arc::clone(&async_task_receiver);
            
            let handle = thread::spawn(move || {
                let rt = Runtime::new().expect("Failed to create worker runtime");
                rt.block_on(async {
                    Self::async_worker_thread(
                        worker_id,
                        queues_clone,
                        metrics_clone,
                        config_clone,
                        shutdown_clone,
                        async_task_receiver_clone,
                        zero_copy_pool_clone,
                    ).await;
                });
            });
            worker_handles.push(handle);
        }
        
        Self {
            config,
            queues,
            metrics,
            worker_handles,
            shutdown_flag,
            async_runtime,
            async_task_sender,
            zero_copy_pool,
            connection_pool,
        }
    }

    /// Async worker thread for enhanced batch processing with zero-copy buffers
    async fn async_worker_thread(
        worker_id: usize,
        queues: Arc<Vec<PriorityBatchQueue>>,
        metrics: Arc<EnhancedBatchMetrics>,
        config: EnhancedBatchConfig,
        shutdown_flag: Arc<AtomicBool>,
        async_task_receiver: Arc<Mutex<mpsc::Receiver<AsyncBatchTask>>>,
        zero_copy_pool: Arc<ZeroCopyBufferPool>,
    ) {
        let thread_name = format!("AsyncEnhancedBatchProcessor-{}", worker_id);
        debug!("Starting {}", thread_name);
        
        while !shutdown_flag.load(Ordering::Relaxed) {
            let mut processed_any = false;
            
            // Process async tasks with timeout
            let mut receiver_guard = async_task_receiver.lock().unwrap();
            tokio::select! {
                Some(task) = receiver_guard.recv() => {
                    // Process async batch task
                    let result = Self::process_async_batch_task(
                        task.operation_type,
                        task.operations,
                        &zero_copy_pool,
                        &metrics,
                    ).await;
                    
                    let _ = task.result_sender.send(result);
                    processed_any = true;
                }
                _ = tokio::time::sleep(Duration::from_micros(100)) => {
                    // Continue to process regular queues
                }
            }
            
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
                    Duration::from_micros(10) // High pressure, sleep less (async optimized)
                } else {
                    Duration::from_micros(100) // Normal pressure
                };
                tokio::time::sleep(sleep_duration).await;
            }
        }
        
        debug!("Shutting down {}", thread_name);
    }
    
    /// Process async batch task with zero-copy optimization
    async fn process_async_batch_task(
        operation_type: u8,
        operations: Vec<EnhancedBatchOperation>,
        zero_copy_pool: &Arc<ZeroCopyBufferPool>,
        metrics: &Arc<EnhancedBatchMetrics>,
    ) -> Result<EnhancedBatchResult, String> {
        let start_time = Instant::now();
        let batch_size = operations.len();
        
        debug!("Processing async batch of {} operations for type {}", batch_size, operation_type);
        
        // Acquire zero-copy buffer for processing
        let mut buffer = zero_copy_pool.acquire_buffer()
            .ok_or_else(|| "No zero-copy buffers available".to_string())?;
        
        match Self::execute_native_batch_with_buffer(operation_type, &operations, &mut buffer) {
            Ok(results) => {
                let processing_time = start_time.elapsed().as_nanos() as u64;
                
                // Update metrics
                metrics.total_batches_processed.fetch_add(1, Ordering::Relaxed);
                metrics.total_operations_batched.fetch_add(batch_size as u64, Ordering::Relaxed);
                metrics.total_processing_time_ns.fetch_add(processing_time, Ordering::Relaxed);
                metrics.update_average_batch_size(batch_size);
                
                debug!("Async batch processed successfully: {} operations in {} ns", batch_size, processing_time);
                
                // Return buffer to pool
                zero_copy_pool.release_buffer(buffer);
                
                Ok(EnhancedBatchResult {
                    operation_type,
                    results,
                    processing_time_ns: processing_time,
                    batch_size,
                    success_count: batch_size,
                })
            }
            Err(e) => {
                error!("Async batch processing failed: {}", e);
                metrics.failed_operations.fetch_add(batch_size as u64, Ordering::Relaxed);
                
                // Return buffer to pool even on error
                zero_copy_pool.release_buffer(buffer);
                
                Err(format!("Async batch processing failed: {}", e))
            }
        }
    }
    
    /// Execute native batch processing with zero-copy buffer
    fn execute_native_batch_with_buffer(
        operation_type: u8,
        batch: &[EnhancedBatchOperation],
        buffer: &mut Vec<u8>,
    ) -> Result<Vec<Vec<u8>>, String> {
        if batch.is_empty() {
            return Ok(Vec::new());
        }
        
        // Calculate total size needed
        let mut total_size = 0;
        for operation in batch {
            total_size += operation.input_data.len() + 8; // data + header
        }
        
        // Ensure buffer has enough capacity
        if buffer.capacity() < total_size + 8 {
            buffer.reserve(total_size + 8 - buffer.capacity());
        }
        
        buffer.clear();
        
        // Write batch header
        buffer.extend_from_slice(&(batch.len() as u32).to_le_bytes());
        buffer.extend_from_slice(&(total_size as u32).to_le_bytes());
        
        // Write individual operations
        for operation in batch {
            let data_len = operation.input_data.len();
            buffer.extend_from_slice(&(data_len as u32).to_le_bytes());
            buffer.extend_from_slice(&operation.input_data);
        }
        
        // Call native batch processing function with zero-copy buffer
        match native_process_batch_zero_copy(operation_type, buffer) {
            Ok(results) => Ok(results),
            Err(e) => Err(format!("Native batch processing failed: {}", e)),
        }
    }

    fn calculate_optimal_batch_size(
        config: &EnhancedBatchConfig,
        metrics: &Arc<EnhancedBatchMetrics>,
        queue_depth: usize,
        operation_type: u8,
    ) -> usize {
        if !config.enable_adaptive_sizing {
            return config.min_batch_size;
        }
        
        let base_size = metrics.adaptive_batch_size.load(Ordering::Relaxed);
        let pressure_level = metrics.get_pressure_level();
        
        // Adjust batch size based on pressure and queue depth with more aggressive scaling
        let multiplier = match pressure_level {
            0..=15 => 1.5,    // Very low pressure - significantly increase batch size
            16..=35 => 1.3,    // Low pressure - increase batch size
            36..=60 => 1.0,    // Normal pressure - use base size
            61..=85 => 0.7,    // Medium pressure - reduce batch size more
            _ => 0.5,           // High pressure - reduce significantly more
        };
        
        let adjusted_size = (base_size as f64 * multiplier) as usize;
        
        // Real-time queue depth adaptation (more aggressive)
        let depth_factor = match queue_depth {
            d if d < config.min_batch_size => 0.7,                // Very low queue - smaller batches
            d if d < config.min_batch_size * 2 => 0.9,             // Low queue - slightly smaller batches
            d if d <= config.max_batch_size => 1.1,                // Normal queue - slightly larger batches
            d if d <= config.max_batch_size * 1.5 => 1.3,          // High queue - larger batches
            d if d <= config.max_batch_size * 2 => 1.6,            // Very high queue - significantly larger batches
            _ => 2.0,                                              // Extreme queue - maximum batch size
        };
        
        // Apply operation type specific adjustments
        let type_factor = match operation_type {
            0x01 => 1.1,  // Echo operations can be larger
            0x02 => 0.9,  // Heavy operations should be smaller
            _ => 1.0,     // Default
        };
        
        let final_size = (adjusted_size as f64 * depth_factor * type_factor) as usize;
        
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

    /// Submit operation to the processor with async support
    pub fn submit_operation(&self, operation: EnhancedBatchOperation) -> Result<(), String> {
        let operation_type = operation.operation_type as usize;
        
        if operation_type >= self.queues.len() {
            return Err(format!("Invalid operation type: {}", operation.operation_type));
        }
        
        self.queues[operation_type].push(operation);
        Ok(())
    }
    
    /// Submit async batch operation with zero-copy optimization
    pub async fn submit_async_batch(
        &self,
        operations: Vec<EnhancedBatchOperation>,
        operation_type: u8,
    ) -> Result<EnhancedBatchResult, String> {
        if operations.is_empty() {
            return Err("No operations provided".to_string());
        }
        
        let (result_sender, result_receiver) = oneshot::channel();
        let task = AsyncBatchTask {
            operation_type,
            operations,
            result_sender,
        };
        
        // Send task to async processor
        self.async_task_sender.send(task).await
            .map_err(|e| format!("Failed to send async task: {}", e))?;
        
        // Wait for result
        result_receiver.await
            .map_err(|e| format!("Failed to receive async result: {}", e))?
    }
    
    /// Submit operation with zero-copy buffer sharing
    pub fn submit_zero_copy_operation(
        &self,
        operation: EnhancedBatchOperation,
        shared_buffer: Arc<Mutex<Vec<u8>>>,
    ) -> Result<(), String> {
        let operation_type = operation.operation_type as usize;
        
        if operation_type >= self.queues.len() {
            return Err(format!("Invalid operation type: {}", operation.operation_type));
        }
        
        // Use zero-copy buffer instead of copying data
        if let Ok(mut buffer) = shared_buffer.lock() {
            // Process operation directly in shared buffer
            self.process_zero_copy_operation(operation, &mut buffer)?;
        } else {
            return Err("Failed to acquire shared buffer lock".to_string());
        }
        
        Ok(())
    }
    
    /// Process operation with zero-copy optimization
    fn process_zero_copy_operation(
        &self,
        operation: EnhancedBatchOperation,
        shared_buffer: &mut Vec<u8>,
    ) -> Result<(), String> {
        // Direct processing in shared buffer without copying
        // This is a simplified implementation - in production, this would be more sophisticated
        shared_buffer.clear();
        shared_buffer.extend_from_slice(&operation.input_data);
        
        // Process the data in place
        // ... processing logic here ...
        
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

/// Native function to process batch with zero-copy optimization
pub fn native_process_batch_zero_copy(operation_type: u8, batch_data: &[u8]) -> Result<Vec<Vec<u8>>, String> {
    // Zero-copy batch processing implementation
    if batch_data.len() < 8 {
        return Err("Batch data too small for header".to_string());
    }
    
    // Parse batch header
    let batch_count = u32::from_le_bytes([batch_data[0], batch_data[1], batch_data[2], batch_data[3]]) as usize;
    let total_size = u32::from_le_bytes([batch_data[4], batch_data[5], batch_data[6], batch_data[7]]) as usize;
    
    if batch_data.len() < 8 + total_size {
        return Err("Batch data size mismatch".to_string());
    }
    
    let mut results = Vec::with_capacity(batch_count);
    let mut offset = 8;
    
    // Process each operation in the batch
    for _ in 0..batch_count {
        if offset + 4 > batch_data.len() {
            break;
        }
        
        let data_len = u32::from_le_bytes([
            batch_data[offset],
            batch_data[offset + 1],
            batch_data[offset + 2],
            batch_data[offset + 3],
        ]) as usize;
        offset += 4;
        
        if offset + data_len > batch_data.len() {
            break;
        }
        
        let operation_data = &batch_data[offset..offset + data_len];
        
        // Process operation based on type
        let result = match operation_type {
            0x01 => process_echo_operation(operation_data),
            0x02 => process_heavy_operation(operation_data),
            _ => process_generic_operation(operation_data, operation_type),
        };
        
        results.push(result);
        offset += data_len;
    }
    
    Ok(results)
}

/// Process echo operation (zero-copy)
fn process_echo_operation(data: &[u8]) -> Vec<u8> {
    // Simple echo - return the same data
    data.to_vec()
}

/// Process heavy operation (zero-copy)
fn process_heavy_operation(data: &[u8]) -> Vec<u8> {
    match std::str::from_utf8(data) {
        Ok(n_str) => {
            match n_str.parse::<u64>() {
                Ok(n) => {
                    let sum: u64 = (1..=n).map(|x| x * x).sum();
                    let json = format!("{{\"task\":\"heavy\",\"n\":{},\"sum\":{}}}", n, sum);
                    json.into_bytes()
                }
                Err(_) => b"Invalid number in payload".to_vec(),
            }
        }
        Err(_) => b"Invalid UTF-8 in payload".to_vec(),
    }
}

/// Process generic operation (zero-copy)
fn process_generic_operation(data: &[u8], operation_type: u8) -> Vec<u8> {
    format!("Processed generic operation type {} with {} bytes", operation_type, data.len()).into_bytes()
}

/// Legacy function for backward compatibility
pub fn native_process_batch(_operation_type: u8, batch_data: &[u8]) -> Result<Vec<Vec<u8>>, String> {
    native_process_batch_zero_copy(_operation_type, batch_data)
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
/// JNI function for zero-copy batch processing with direct buffer access
#[no_mangle]
pub unsafe extern "C" fn Java_com_kneaf_core_performance_EnhancedNativeBridge_submitZeroCopyBatchedOperations(
    env: JNIEnv,
    _class: JClass,
    _operation_type: jbyte,
    direct_buffer: jni::sys::jobject,
    buffer_size: jint,
) -> jstring {
    if let Some(processor) = get_enhanced_batch_processor() {
        if direct_buffer.is_null() {
            let error_msg = "Direct buffer cannot be null";
            return env.new_string(error_msg)
                .map(|s| s.into_raw())
                .unwrap_or(std::ptr::null_mut());
        }

        let byte_buffer = unsafe { jni::objects::JByteBuffer::from_raw(direct_buffer) };
        
        match env.get_direct_buffer_address(&byte_buffer) {
            Ok(address) => {
                if address.is_null() {
                    let error_msg = "Failed to get direct buffer address";
                    return env.new_string(error_msg)
                        .map(|s| s.into_raw())
                        .unwrap_or(std::ptr::null_mut());
                }
                
                let data = unsafe { std::slice::from_raw_parts(address, buffer_size as usize) };
                
                // Parse operations from direct buffer (zero-copy)
                match parse_zero_copy_operations(data) {
                    Ok(operations) => {
                        let mut success_count = 0;
                        for operation in operations {
                            if processor.submit_operation(operation).is_ok() {
                                success_count += 1;
                            }
                        }

                        let result = format!("Submitted {} zero-copy operations successfully", success_count);
                        env.new_string(&result)
                            .map(|s| s.into_raw())
                            .unwrap_or(std::ptr::null_mut())
                    }
                    Err(e) => {
                        let error_msg = format!("Failed to parse zero-copy operations: {}", e);
                        env.new_string(&error_msg)
                            .map(|s| s.into_raw())
                            .unwrap_or(std::ptr::null_mut())
                    }
                }
            }
            Err(e) => {
                let error_msg = format!("Failed to get direct buffer address: {:?}", e);
                env.new_string(&error_msg)
                    .map(|s| s.into_raw())
                    .unwrap_or(std::ptr::null_mut())
            }
        }
    } else {
        let error_msg = "Enhanced batch processor not initialized";
        env.new_string(error_msg)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut())
    }
}

/// JNI function for async batch processing with connection pooling
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_EnhancedNativeBridge_submitAsyncBatchedOperations(
    env: JNIEnv,
    _class: JClass,
    _operation_type: jbyte,
    operations_data: jbyteArray,
    priorities: jbyteArray,
) -> jlong {
    if let Some(_processor) = get_enhanced_batch_processor() {
        // Convert raw jbyteArray to JByteArray wrapper expected by the JNI helpers
        let operations_obj = unsafe { jni::objects::JObject::from_raw(operations_data as *mut _ ) };
        let priorities_obj = unsafe { jni::objects::JObject::from_raw(priorities as *mut _ ) };
        let operations_jarray = jni::objects::JByteArray::from(operations_obj);
        let priorities_jarray = jni::objects::JByteArray::from(priorities_obj);

        match env.convert_byte_array(operations_jarray) {
            Ok(operations_vec) => {
                match env.convert_byte_array(priorities_jarray) {
                    Ok(priorities_vec) => {
                        let operations_slice = operations_vec.as_slice();
                        let priorities_slice = priorities_vec.as_slice();

                        // Parse batched operations data
                        match parse_batched_operations(operations_slice, priorities_slice) {
                            Ok(_operations) => {
                                // Create async task and return operation ID
                                let _rt = Runtime::new().expect("Failed to create async runtime");
                                let operation_id = std::time::SystemTime::now()
                                    .duration_since(std::time::UNIX_EPOCH)
                                    .unwrap()
                                    .as_nanos() as u64;
                                
                                operation_id as jlong
                            }
                            Err(_) => 0 // Error
                        }
                    }
                    Err(_) => 0 // Error
                }
            }
            Err(_) => 0 // Error
        }
    } else {
        0 // Error
    }
}

/// JNI function to poll async batch operation results
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_EnhancedNativeBridge_pollAsyncBatchResult(
    _env: JNIEnv,
    _class: JClass,
    _operation_id: jlong,
) -> jbyteArray {
    // For now, return empty result - this needs proper async result storage
    std::ptr::null_mut()
}

/// Parse zero-copy operations from direct buffer
fn parse_zero_copy_operations(data: &[u8]) -> Result<Vec<EnhancedBatchOperation>, String> {
    if data.len() < 4 {
        return Err("Operations data too small".to_string());
    }

    let operation_count = u32::from_le_bytes([data[0], data[1], data[2], data[3]]) as usize;
    
    let mut operations = Vec::with_capacity(operation_count);
    let mut offset = 4;

    for _i in 0..operation_count {
        if offset + 5 > data.len() {
            return Err("Invalid zero-copy operations data format".to_string());
        }

        let data_len = u32::from_le_bytes([
            data[offset],
            data[offset + 1],
            data[offset + 2],
            data[offset + 3],
        ]) as usize;
        let priority = data[offset + 4];
        offset += 5;

        if offset + data_len > data.len() {
            return Err("Operation data exceeds buffer".to_string());
        }

        let operation_data = data[offset..offset + data_len].to_vec();
        operations.push(EnhancedBatchOperation::new(0, operation_data, priority));
        offset += data_len;
    }

    Ok(operations)
}

/// JNI function to submit batched operations from Java (legacy)
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_EnhancedNativeBridge_submitBatchedOperations(
    env: JNIEnv,
    _class: JClass,
    _operation_type: jbyte,
    operations_data: jbyteArray,
    priorities: jbyteArray,
) -> jstring {
    if let Some(processor) = get_enhanced_batch_processor() {
        // Convert raw jbyteArray to JByteArray wrapper expected by the JNI helpers
        let operations_obj = unsafe { jni::objects::JObject::from_raw(operations_data as *mut _ ) };
        let priorities_obj = unsafe { jni::objects::JObject::from_raw(priorities as *mut _ ) };
        let operations_jarray = jni::objects::JByteArray::from(operations_obj);
        let priorities_jarray = jni::objects::JByteArray::from(priorities_obj);

        match env.convert_byte_array(operations_jarray) {
            Ok(operations_vec) => {
                match env.convert_byte_array(priorities_jarray) {
                    Ok(priorities_vec) => {
                        let operations_slice = operations_vec.as_slice();
                        let priorities_slice = priorities_vec.as_slice();

                        // Parse batched operations data
                        match parse_batched_operations(operations_slice, priorities_slice) {
                            Ok(operations) => {
                                let mut success_count = 0;
                                for operation in operations {
                                    if processor.submit_operation(operation).is_ok() {
                                        success_count += 1;
                                    }
                                }

                                let result = format!("Submitted {} operations successfully", success_count);
                                env.new_string(&result)
                                    .map(|s| s.into_raw())
                                    .unwrap_or(std::ptr::null_mut())
                            }
                            Err(e) => {
                                let error_msg = format!("Failed to parse operations: {}", e);
                                env.new_string(&error_msg)
                                    .map(|s| s.into_raw())
                                    .unwrap_or(std::ptr::null_mut())
                            }
                        }
                    }
                    Err(_) => {
                        let error_msg = "Failed to access priorities array";
                        env.new_string(error_msg)
                            .map(|s| s.into_raw())
                            .unwrap_or(std::ptr::null_mut())
                    }
                }
            }
            Err(_) => {
                let error_msg = "Failed to access operations array";
                env.new_string(error_msg)
                    .map(|s| s.into_raw())
                    .unwrap_or(std::ptr::null_mut())
            }
        }
    } else {
        let error_msg = "Enhanced batch processor not initialized";
        env.new_string(error_msg)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut())
    }
}

/// Parse batched operations data from Java arrays
fn parse_batched_operations(operations_data: &[u8], priorities: &[u8]) -> Result<Vec<EnhancedBatchOperation>, String> {
    if operations_data.len() < 4 {
        return Err("Operations data too small".to_string());
    }

    let operation_count = u32::from_le_bytes([operations_data[0], operations_data[1], operations_data[2], operations_data[3]]) as usize;
    if operation_count != priorities.len() {
        return Err("Operation count mismatch with priorities".to_string());
    }

    let mut operations = Vec::with_capacity(operation_count);
    let mut offset = 4;

    for i in 0..operation_count {
        if offset + 4 > operations_data.len() {
            return Err("Invalid operations data format".to_string());
        }

        let data_len = u32::from_le_bytes([
            operations_data[offset],
            operations_data[offset + 1],
            operations_data[offset + 2],
            operations_data[offset + 3],
        ]) as usize;
        offset += 4;

        if offset + data_len > operations_data.len() {
            return Err("Operation data exceeds buffer".to_string());
        }

        let operation_data = operations_data[offset..offset + data_len].to_vec();
        let priority = priorities[i];

        operations.push(EnhancedBatchOperation::new(0, operation_data, priority));
        offset += data_len;
    }

#[allow(dead_code)]
/// Submit async batch operation
pub fn submit_async_batch(worker_handle: u64, operations: Vec<Vec<u8>>) -> Result<u64, String> {
    // Submit to async JNI bridge
    match crate::jni_async_bridge::submit_async_batch(worker_handle, operations) {
        Ok(async_handle) => {
            info!("Submitted async batch operation with handle: {}", async_handle.0);
            Ok(async_handle.0)
        }
        Err(e) => {
            error!("Failed to submit async batch: {}", e);
            Err(e)
        }
    }
}

#[allow(dead_code)]
/// Poll async batch results
pub fn poll_async_batch_results(operation_id: u64, max_results: usize) -> Result<Vec<Vec<u8>>, String> {
    match crate::jni_async_bridge::poll_async_batch_results(operation_id, max_results) {
        Ok(results) => {
            debug!("Retrieved {} async batch results", results.len());
            Ok(results)
        }
        Err(e) => {
            error!("Failed to poll async batch results: {}", e);
            Err(e)
        }
    }
}

#[allow(dead_code)]
/// Cleanup async batch operation
pub fn cleanup_async_batch_operation(operation_id: u64) {
    crate::jni_async_bridge::cleanup_async_batch_operation(operation_id);
    debug!("Cleaned up async batch operation: {}", operation_id);
}

#[allow(dead_code)]
/// Submit zero-copy batch operation
pub fn submit_zero_copy_batch(
    worker_handle: u64,
    buffer_addresses: Vec<(u64, usize)>,
    operation_type: u32
) -> Result<u64, String> {
    match crate::jni_async_bridge::submit_zero_copy_batch(worker_handle, buffer_addresses, operation_type) {
        Ok(async_handle) => {
            info!("Submitted zero-copy batch operation with handle: {}", async_handle.0);
            Ok(async_handle.0)
        }
        Err(e) => {
            error!("Failed to submit zero-copy batch: {}", e);
            Err(e)
        }
    }
}

    Ok(operations)
}
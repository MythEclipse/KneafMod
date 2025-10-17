use jni::{objects::JClass, sys::{jbyte, jbyteArray, jint, jlong, jstring}, JNIEnv, objects::JByteArray};
use log::{debug, error, info};
use serde_json;
use std::{cmp, collections::VecDeque, sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering}, sync::{Arc, Mutex, RwLock}, thread, time::{Duration, Instant}};
use tokio::{runtime::Runtime, sync::{mpsc, oneshot}};

// Import our new modules
use crate::{
    errors::{RustError, messages},
    BatchError,
    traits::Initializable,
    jni_utils,
};
use crate::jni::utils;
use std::result::Result;
use fastrand::shuffle;
pub use crate::errors::Result as RustResult;
use crate::{jni_error, check_initialized, impl_initializable};

// Helper trait for mutex operations with timeout
// Use a more efficient lock strategy with better backoff
trait LockExt<T> {
    fn try_lock_with_backoff(
        &self,
        max_attempts: usize,
        initial_backoff: Duration,
    ) -> Result<std::sync::MutexGuard<'_, T>, String>;
}

impl<T> LockExt<T> for std::sync::Mutex<T> {
    fn try_lock_with_backoff(
        &self,
        max_attempts: usize,
        initial_backoff: Duration,
    ) -> Result<std::sync::MutexGuard<'_, T>, String> {
        let mut backoff = initial_backoff;
        for attempt in 0..max_attempts {
            match self.try_lock() {
                Ok(guard) => return Ok(guard),
                Err(_) => {
                    if attempt == max_attempts - 1 {
                        return Err("Lock timeout exceeded".to_string());
                    }
                    // Exponential backoff with jitter
                    let jitter = Duration::from_micros(rand::random::<u64>() % 100);
                    std::thread::sleep(backoff + jitter);
                    backoff *= 2;
                }
            }
        }
        Err("Lock acquisition failed".to_string())
    }
}

// Import lock ordering constants

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
        let _shard_idx = ACQUIRE_COUNTER.fetch_add(1, Ordering::Relaxed) % self.shard_count;

        // Use a more efficient shard selection algorithm with local affinity
        let mut attempts = 0;
        let max_attempts = self.shard_count * 2; // Try all shards twice

        while attempts < max_attempts {
            let shard_idx = ACQUIRE_COUNTER.fetch_add(1, Ordering::Relaxed) % self.shard_count;

            if let Some(buffer) = self.try_acquire_from_shard(shard_idx) {
                return Some(buffer);
            }

            attempts += 1;

            // If we've tried all shards once, add some jitter to avoid contention
            if attempts == self.shard_count {
                std::thread::sleep(Duration::from_micros(1));
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
            min_batch_size: 50,           // Increased for more aggressive batching
            max_batch_size: 500,          // Increased to reduce JNI crossings
            adaptive_batch_timeout_ms: 1, // Reduced for faster batching
            max_pending_batches: 200,     // Increased to handle more concurrent batches
            worker_threads: 8,            // Increased for better parallelism
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
        self.adaptive_batch_size
            .store(updated as usize, Ordering::Relaxed);
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
        let insert_pos = queue
            .binary_search_by_key(&operation.priority, |op| op.priority)
            .unwrap_or_else(|pos| pos);
        queue.insert(insert_pos, operation);

        // Update total queue depth across all shards
        let total_depth = self.get_total_depth();
        self.metrics
            .current_queue_depth
            .store(total_depth, Ordering::Relaxed);
    }

    pub fn pop(&self) -> Option<EnhancedBatchOperation> {
        // Try to pop from highest priority shards first
        for priority_level in (0..=255).rev() {
            let shard_idx = self.get_shard(priority_level);

            let mut queue = self.queues[shard_idx].lock().unwrap();
            if let Some(operation) = queue.pop_front() {
                let total_depth = self.get_total_depth();
                self.metrics
                    .current_queue_depth
                    .store(total_depth, Ordering::Relaxed);
                return Some(operation);
            }
        }

        None
    }

    pub fn drain_batch(&self, max_size: usize, timeout: Duration) -> Vec<EnhancedBatchOperation> {
        let start_time = Instant::now();
        let mut batch = Vec::with_capacity(max_size);
        let initial_queue_depth = self.len();

        // Use a more efficient batching algorithm with reduced lock contention
        while batch.len() < max_size && start_time.elapsed() < timeout {
            // Try to get operations from all shards in parallel without holding locks
            let mut operations = Vec::with_capacity(max_size - batch.len());

            // Use more efficient batch gathering with reduced lock contention
            let mut operations = Vec::with_capacity(max_size);
            let mut success_count = 0;
            
            // Process shards in random order to distribute load more evenly
            let mut shard_indices: Vec<usize> = (0..self.shard_count).collect();
            shuffle(&mut shard_indices);
            
            for &shard_idx in &shard_indices {
                if start_time.elapsed() >= timeout {
                    break;
                }
            
                // Use try_lock with shorter timeout for better throughput
                if let Ok(mut queue) = self.queues[shard_idx].try_lock_timeout(Duration::from_millis(50)) {
                    let available = queue.len();
                    if available == 0 {
                        continue;
                    }
            
                    // Calculate batch size with more efficient transfer
                    let take_size = std::cmp::min(max_size - operations.len(), available);
                    let remaining = operations.len() + take_size;
                    
                    // Reserve space upfront to avoid reallocations
                    operations.reserve(take_size);
                    
                    // More efficient bulk transfer using drain
                    operations.extend(queue.drain(0..take_size));
                    success_count += take_size;
                }
            }
            
            // Log performance metrics for optimization
            if success_count > 0 {
                self.batch_metrics.record_batch_gather(success_count, start_time.elapsed());
            }

            // Add all collected operations to the batch
            batch.extend(operations.clone());

            // If we got some operations, check if we should continue
            if operations.is_empty() {
                // No operations available - check if we should wait or return empty batch
                let elapsed = start_time.elapsed();
                if elapsed < Duration::from_millis(10) {
                    std::thread::sleep(Duration::from_micros(1)); // Very short wait for new operations
                } else {
                    break; // Don't wait too long for empty queues
                }
            } else {
                // Check adaptive termination conditions
                let current_depth = self.len();
                let depth_change = initial_queue_depth - current_depth;

                // If we've processed a significant portion of the queue or it's emptying fast, stop early
                if (batch.len() as f64 / max_size as f64) > 0.9 ||  // More aggressive batching
                       (depth_change > 0 && (depth_change as f64 / initial_queue_depth as f64) > 0.7)
                {
                    break;
                }
            }
        }

        batch
    }

    /// Pop operation with timeout to prevent deadlocks
    pub fn pop_with_timeout(&self, timeout: Duration) -> Option<EnhancedBatchOperation> {
        // Use a more efficient approach with try_lock to minimize contention
        let start_time = Instant::now();
        let max_attempts = 5; // Limit number of attempts to avoid long delays

        for attempt in 0..max_attempts {
            // Check if we've exceeded the timeout
            if start_time.elapsed() >= timeout {
                return None;
            }

            // Try to get an operation from any shard
            for priority_level in (0..=255).rev() {
                let shard_idx = self.get_shard(priority_level);

                // Use try_lock with backoff to minimize contention
                match self.queues[shard_idx].try_lock_with_backoff(3, Duration::from_micros(1)) {
                    Ok(mut queue) => {
                        if let Some(operation) = queue.pop_front() {
                            let total_depth = self.get_total_depth();
                            self.metrics
                                .current_queue_depth
                                .store(total_depth, Ordering::Relaxed);
                            return Some(operation);
                        }
                    }
                    Err(_) => continue, // Try next shard if this one is locked
                }
            }

            // Add short delay between attempts to reduce contention
            if attempt < max_attempts - 1 {
                std::thread::sleep(Duration::from_micros(1));
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
    result_sender: oneshot::Sender<std::result::Result<EnhancedBatchResult, String>>,
}

/// Zero-copy buffer pool for direct memory access (sharded with better performance characteristics)
#[derive(Debug)]
struct ZeroCopyBufferPool {
    buffer_pools: Arc<Vec<Mutex<Vec<Vec<u8>>>>>, // Sharded pools
    shard_count: usize,
    max_buffers: usize,
    #[allow(dead_code)]
    high_water_mark: AtomicUsize,
}

impl ZeroCopyBufferPool {
    fn new(max_buffers: usize, buffer_size: usize) -> Self {
        let shard_count = 8; // Increased shards for better parallelism
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
            high_water_mark: AtomicUsize::new(0),
        }
    }

    fn acquire_buffer(&self) -> Option<Vec<u8>> {
        // Use thread-local sharding based on thread ID for better affinity
        let thread_id = rand::random::<usize>();
        let shard_idx = thread_id % self.shard_count;

        // First try with try_lock to avoid blocking
        if let Ok(mut buffers) = self.buffer_pools[shard_idx].try_lock() {
            if let Some(buffer) = buffers.pop() {
                return Some(buffer);
            }
        }

        // If that fails, use a more systematic approach with backoff
        let mut attempts = 0;
        const MAX_ATTEMPTS: usize = 5;
        const BASE_BACKOFF: Duration = Duration::from_micros(1);

        while attempts < MAX_ATTEMPTS {
            for i in 0..self.shard_count {
                if let Ok(mut buffers) = self.buffer_pools[i].try_lock() {
                    if let Some(buffer) = buffers.pop() {
                        return Some(buffer);
                    }
                }
            }

            attempts += 1;
            let backoff = BASE_BACKOFF * 2u32.pow(attempts as u32);
            std::thread::sleep(backoff);
        }

        // Update high water mark for monitoring
        let current_count = self.get_current_buffer_count();
        self.high_water_mark.store(current_count, Ordering::Relaxed);

        None
    }

    #[allow(dead_code)]
    fn try_acquire_from_shard(&self, shard_idx: usize) -> Option<Vec<u8>> {
        self.buffer_pools[shard_idx].lock().unwrap().pop()
    }

    fn release_buffer(&self, mut buffer: Vec<u8>) {
        // Reset buffer contents efficiently
        buffer.clear();
        buffer.shrink_to_fit(); // More aggressive memory return

        // Use thread-local sharding for release
        let thread_id = rand::random::<usize>();
        let shard_idx = thread_id % self.shard_count;

        if let Ok(mut buffers) = self.buffer_pools[shard_idx].try_lock() {
            let max_per_shard = self.max_buffers / self.shard_count;
            if buffers.len() < max_per_shard {
                buffers.push(buffer);
                return;
            }
        }

        // If shard is full, try other shards with backoff
        let mut attempts = 0;
        const MAX_ATTEMPTS: usize = 3;
        const BASE_BACKOFF: Duration = Duration::from_micros(1);

        while attempts < MAX_ATTEMPTS {
            for i in 0..self.shard_count {
                if i == shard_idx {
                    continue;
                }

                if let Ok(mut buffers) = self.buffer_pools[i].try_lock() {
                    let max_per_shard = self.max_buffers / self.shard_count;
                    if buffers.len() < max_per_shard {
                        buffers.push(buffer);
                        return;
                    }
                }
            }

            attempts += 1;
            let backoff = BASE_BACKOFF * 2u32.pow(attempts as u32);
            std::thread::sleep(backoff);
        }

        // If all shards are full, we have to leak the buffer to avoid contention
        // This is a deliberate optimization to prevent blocking on buffer release
        // The buffer will be reallocated when needed
    }

    fn get_current_buffer_count(&self) -> usize {
        let mut count = 0;
        for pool in self.buffer_pools.iter() {
            count += pool.lock().unwrap().len();
        }
        count
    }

    #[allow(dead_code)]
    pub fn get_high_water_mark(&self) -> usize {
        self.high_water_mark.load(Ordering::Relaxed)
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
    is_initialized: AtomicBool,
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
        let async_runtime = Arc::new(Runtime::new().expect("Failed to create Tokio runtime"));

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
                    )
                    .await;
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
            is_initialized: AtomicBool::new(true),
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

                    let _ = task.result_sender.send(result.map_err(|e| format!("{}", e)));
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
                    operation_type as u8,
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

        debug!(
            "Processing async batch of {} operations for type {}",
            batch_size, operation_type
        );

        // Acquire zero-copy buffer for processing
        let mut buffer = zero_copy_pool
            .acquire_buffer()
            .ok_or_else(|| "No zero-copy buffers available".to_string())?;

        match Self::execute_native_batch_with_buffer(operation_type, &operations, &mut buffer) {
            Ok(results) => {
                let processing_time = start_time.elapsed().as_nanos() as u64;

                // Update metrics
                metrics
                    .total_batches_processed
                    .fetch_add(1, Ordering::Relaxed);
                metrics
                    .total_operations_batched
                    .fetch_add(batch_size as u64, Ordering::Relaxed);
                metrics
                    .total_processing_time_ns
                    .fetch_add(processing_time, Ordering::Relaxed);
                metrics.update_average_batch_size(batch_size);

                debug!(
                    "Async batch processed successfully: {} operations in {} ns",
                    batch_size, processing_time
                );

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
                metrics
                    .failed_operations
                    .fetch_add(batch_size as u64, Ordering::Relaxed);

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
        ) -> std::result::Result<Vec<Vec<u8>>, String> {
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
            Err(e) => Err(RustError::OperationFailed(format!("Native batch processing failed: {}", e)).to_string()),
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

        // More granular pressure-based scaling with exponential response
        let multiplier = match pressure_level {
            0..=10 => 2.0,  // Very low pressure - double batch size
            11..=20 => 1.7, // Low pressure - significantly increase batch size
            21..=35 => 1.4, // Moderate low pressure - increase batch size
            36..=50 => 1.1, // Near normal pressure - slightly increase batch size
            51..=65 => 1.0, // Normal pressure - use base size
            66..=75 => 0.8, // Moderate high pressure - slightly reduce batch size
            76..=85 => 0.6, // High pressure - reduce batch size
            86..=95 => 0.4, // Very high pressure - significantly reduce batch size
            _ => 0.2,       // Extreme pressure - minimal batch size to prevent overload
        };

        let adjusted_size = (base_size as f64 * multiplier) as usize;

        // Queue depth adaptation with more precise thresholds
        let depth_factor = match queue_depth {
            d if d == 0 => 0.0,                        // Empty queue - no operations
            d if d < config.min_batch_size => 0.5,     // Very low queue - minimal batch
            d if d < config.min_batch_size * 2 => 0.7, // Low queue - small batch
            d if d < config.min_batch_size * 2 => 0.9, // Below normal - slightly smaller batch
            d if d < config.max_batch_size => 1.0,     // Normal queue - base size
            d if d < config.max_batch_size * 2 => 1.2, // Above normal - slightly larger batch
            d if d < config.max_batch_size => 1.5, // Very high queue - significantly larger batch
            d if d < config.max_batch_size * 2 => 1.5, // Very high queue - significantly larger batch
            _ => 2.0,                                  // Extreme queue - maximum batch size
        };

        // Apply operation type specific adjustments with more granularity
        let type_factor = match operation_type {
            0x01 => 1.2, // Echo operations can be larger
            0x02 => 0.8, // Heavy operations should be smaller
            0x03 => 1.5, // Fast operations can be very large
            0x04 => 0.9, // Medium operations slightly smaller
            _ => 1.0,    // Default
        };

        // Apply system load factor based on historical performance
        let load_factor = if metrics.get_pressure_level() < 50 {
            1.1 // Good performance - push larger batches
        } else if metrics.get_pressure_level() < 80 {
            1.0 // Stable performance - use calculated size
        } else {
            0.9 // Poor performance - use smaller batches to reduce load
        };

        let final_size = (adjusted_size as f64 * depth_factor * type_factor * load_factor) as usize;

        // Ensure we stay within configured bounds but allow for more aggressive sizing
        // when under low pressure to maximize throughput
        if pressure_level < 30 {
            cmp::max(
                config.min_batch_size,
                cmp::min(config.max_batch_size * 2, final_size),
            )
        } else {
            cmp::max(
                config.min_batch_size,
                cmp::min(config.max_batch_size, final_size),
            )
        }
    }

    fn process_batch(
        operation_type: u8,
        batch: Vec<EnhancedBatchOperation>,
        metrics: &Arc<EnhancedBatchMetrics>,
    ) {
        let start_time = Instant::now();
        let batch_size = batch.len();

        debug!(
            "Processing batch of {} operations for type {}",
            batch_size, operation_type
        );

        match Self::execute_native_batch(operation_type, &batch) {
            Ok(_results) => {
                let processing_time = start_time.elapsed().as_nanos() as u64;

                // Update metrics
                metrics
                    .total_batches_processed
                    .fetch_add(1, Ordering::Relaxed);
                metrics
                    .total_operations_batched
                    .fetch_add(batch_size as u64, Ordering::Relaxed);
                metrics
                    .total_processing_time_ns
                    .fetch_add(processing_time, Ordering::Relaxed);
                metrics.update_average_batch_size(batch_size);

                debug!(
                    "Batch processed successfully: {} operations in {} ns",
                    batch_size, processing_time
                );
            }
            Err(e) => {
                error!("Batch processing failed: {}", e);
                metrics
                    .failed_operations
                    .fetch_add(batch_size as u64, Ordering::Relaxed);
            }
        }
    }

    fn execute_native_batch(
            operation_type: u8,
            batch: &[EnhancedBatchOperation],
        ) -> std::result::Result<Vec<Vec<u8>>, String> {
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
        match native_process_batch_zero_copy(operation_type, &batch_data) {
            Ok(results) => Ok(results),
            Err(e) => Err(RustError::OperationFailed(format!("Native batch processing failed: {}", e)).to_string()),
        }
    }

    /// Submit operation to the processor with async support
    pub fn submit_operation(&self, operation: EnhancedBatchOperation) -> Result<(), RustError> {
        check_initialized!(self);
        
        let operation_type = operation.operation_type as usize;

        if operation_type >= self.queues.len() {
            return Err(RustError::InvalidOperationType {
                operation_type: operation.operation_type,
                max_type: self.queues.len() as u8
            });
        }

        self.queues[operation_type].push(operation);
        Ok(())
    }

    /// Submit async batch operation with zero-copy optimization
    pub async fn submit_async_batch(
        &self,
        operations: Vec<EnhancedBatchOperation>,
        operation_type: u8,
    ) -> Result<EnhancedBatchResult, RustError> {
        check_initialized!(self);
        
        if operations.is_empty() {
            return Err(RustError::EmptyOperationSet);
        }

        let (result_sender, result_receiver) = oneshot::channel();
        let task = AsyncBatchTask {
            operation_type,
            operations,
            result_sender,
        };

        // Send task to async processor
        self.async_task_sender
            .send(task)
            .await
            .map_err(|e| RustError::AsyncTaskSendFailed { source: e.to_string() })?;

        // Wait for result
        let result = result_receiver
            .await
            .map_err(|e| RustError::AsyncResultReceiveFailed { source: e.to_string() })?;
        
        result.map_err(|e| RustError::OperationFailed(format!("Async batch processing failed: {}", e)))
    }

    /// Submit operation with zero-copy buffer sharing
    pub fn submit_zero_copy_operation(
        &self,
        operation: EnhancedBatchOperation,
        shared_buffer: Arc<Mutex<Vec<u8>>>,
    ) -> Result<(), RustError> {
        check_initialized!(self);
        
        let operation_type = operation.operation_type as usize;

        if operation_type >= self.queues.len() {
            return Err(RustError::InvalidOperationType {
                operation_type: operation.operation_type,
                max_type: self.queues.len() as u8
            });
        }

        // Use zero-copy buffer instead of copying data
        let mut buffer_guard = shared_buffer.lock()
            .map_err(|_| RustError::SharedBufferLockFailed)?;
        match self.process_zero_copy_operation(operation, &mut *buffer_guard) {
            Ok(_) => {},
            Err(e) => return Err(RustError::OperationFailed(e)),
        };

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
            total_operations_batched: self
                .metrics
                .total_operations_batched
                .load(Ordering::Relaxed),
            average_batch_size: self.metrics.average_batch_size.load(Ordering::Relaxed),
            current_queue_depth: self.metrics.current_queue_depth.load(Ordering::Relaxed),
            failed_operations: self.metrics.failed_operations.load(Ordering::Relaxed),
            average_processing_time_ms: {
                let total_time = self
                    .metrics
                    .total_processing_time_ns
                    .load(Ordering::Relaxed);
                let total_ops = self
                    .metrics
                    .total_operations_batched
                    .load(Ordering::Relaxed);
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
    pub fn shutdown(&self) -> Result<(), RustError> {
        check_initialized!(self);
        
        self.shutdown_flag.store(true, Ordering::Relaxed);

        for handle in &self.worker_handles {
            handle.thread().unpark();
        }

        Ok(())
    }
}

impl_initializable!(EnhancedBatchProcessor, is_initialized, "Enhanced Batch Processor");

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
    let mut processor_guard = ENHANCED_BATCH_PROCESSOR
        .write()
        .map_err(|_| "Enhanced batch processor RwLock poisoned")?;

    if processor_guard.is_some() {
        return Err("Enhanced batch processor already initialized".to_string());
    }

    let processor = Arc::new(EnhancedBatchProcessor::new(config_clone));
    *processor_guard = Some(processor);

    info!(
        "Enhanced batch processor initialized with config: {:?}",
        config
    );
    Ok(())
}

/// Get the enhanced batch processor
pub fn get_enhanced_batch_processor() -> Option<Arc<EnhancedBatchProcessor>> {
    ENHANCED_BATCH_PROCESSOR
        .read()
        .ok()
        .and_then(|guard| guard.clone())
}

/// Submit operation to the enhanced batch processor
pub fn submit_enhanced_operation(operation: EnhancedBatchOperation) -> std::result::Result<(), String> {
    if let Some(processor) = get_enhanced_batch_processor() {
        match processor.submit_operation(operation) {
            Ok(()) => Ok(()),
            Err(e) => Err(e.to_string())
        }
    } else {
        Err("Enhanced batch processor not initialized".to_string())
    }
}

/// Native function to process batch with zero-copy optimization
pub fn native_process_batch_zero_copy(
    operation_type: u8,
    batch_data: &[u8],
) -> Result<Vec<Vec<u8>>, String> {
    // Zero-copy batch processing implementation
    if batch_data.len() < 8 {
        return Err("Batch data too small for header".to_string());
    }

    // Parse batch header
    let batch_count =
        u32::from_le_bytes([batch_data[0], batch_data[1], batch_data[2], batch_data[3]]) as usize;
    let total_size =
        u32::from_le_bytes([batch_data[4], batch_data[5], batch_data[6], batch_data[7]]) as usize;

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
        Ok(n_str) => match n_str.parse::<u64>() {
            Ok(n) => {
                let sum: u64 = (1..=n).map(|x| x * x).sum();
                let json = format!("{{\"task\":\"heavy\",\"n\":{},\"sum\":{}}}", n, sum);
                json.into_bytes()
            }
            Err(_) => b"Invalid number in payload".to_vec(),
        },
        Err(_) => b"Invalid UTF-8 in payload".to_vec(),
    }
}

/// Process generic operation (zero-copy)
fn process_generic_operation(data: &[u8], operation_type: u8) -> Vec<u8> {
    format!(
        "Processed generic operation type {} with {} bytes",
        operation_type,
        data.len()
    )
    .into_bytes()
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
        match env.new_string(messages::PROCESSOR_NOT_INITIALIZED) {
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
        Ok(_) => 0,  // Success
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
    let processor = match get_enhanced_batch_processor() {
        Some(p) => p,
        None => return jni_error!(env, RustError::NotInitializedError(messages::PROCESSOR_NOT_INITIALIZED.to_string()))
    };

    if direct_buffer.is_null() {
        return jni_error!(env, RustError::InvalidArgumentError(messages::NULL_BUFFER_ERROR.to_string()));
    }

    let byte_buffer = unsafe { jni::objects::JByteBuffer::from_raw(direct_buffer) };

    let address = match env.get_direct_buffer_address(&byte_buffer) {
        Ok(addr) if !addr.is_null() => addr,
        Ok(_) => return jni_error!(env, RustError::BufferError(messages::NULL_BUFFER_ADDRESS.to_string())),
        Err(e) => return jni_error!(env, RustError::BufferError(format!("{}: {}", messages::BUFFER_ADDRESS_ERROR, e)))
    };

    let data = unsafe { std::slice::from_raw_parts(address, buffer_size as usize) };

    match parse_zero_copy_operations(data) {
        Ok(operations) => {
            let success_count = operations.iter()
                .filter(|op| processor.submit_operation((*op).clone()).is_ok())
                .count();

            env.new_string(&format!(
                "Submitted {} zero-copy operations successfully",
                success_count
            ))
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut())
        }
        Err(e) => jni_error!(env, RustError::ParseError(format!("{}: {}", messages::PARSE_ERROR, e)))
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
    let processor = match get_enhanced_batch_processor() {
        Some(p) => p,
        None => return 0
    };

    // Convert and validate input arrays
    let operations_vec = match env.convert_byte_array(unsafe { JByteArray::from_raw(operations_data) }) {
        Ok(v) => v,
        Err(_) => return 0
    };

    let priorities_vec = match env.convert_byte_array(unsafe { JByteArray::from_raw(priorities) }) {
        Ok(v) => v,
        Err(_) => return 0
    };

    let operations_slice = &operations_vec[..];
    let priorities_slice = priorities_vec.as_slice();

    match parse_batched_operations(operations_slice, priorities_slice) {
        Ok(operations) => {
            // Create async task and return operation ID
            let operation_id = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_nanos() as u64)
                .unwrap_or(0);

            // Spawn async task
            let processor_clone = Arc::clone(&processor);
            tokio::spawn(async move {
                for operation in operations {
                    let _ = processor_clone.submit_operation(operation);
                }
            });

            operation_id as jlong
        }
        Err(_) => 0,
    }
}

use std::sync::OnceLock;
use std::collections::HashMap;

/// JNI function to poll async batch operation results
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_EnhancedNativeBridge_pollAsyncBatchResult(
    mut env: JNIEnv,
    _class: JClass,
    operation_id: jlong,
) -> jbyteArray {
    // Simple in-memory storage for demonstration - in production use a proper storage mechanism
    static RESULTS: OnceLock<Mutex<HashMap<u64, Vec<u8>>>> = OnceLock::new();
    
    let results_mutex = RESULTS.get_or_init(|| Mutex::new(HashMap::new()));
    let mut results = match results_mutex.lock() {
        Ok(guard) => guard,
        Err(_) => {
            return match utils::create_error_jni_string(&mut env, "Failed to lock results storage") {
                Ok(jstr) => jstr,
                Err(_) => std::ptr::null_mut(),
            };
        }
    };
    
    match results.remove(&(operation_id as u64)) {
        Some(data) => env.byte_array_from_slice(&data).map(|arr| arr.into_raw()).unwrap_or(std::ptr::null_mut()),
        None => {
            let error_msg = "No results found for operation ID";
            env.byte_array_from_slice(error_msg.as_bytes()).map(|arr| arr.into_raw()).unwrap_or(std::ptr::null_mut())
        }
    }
}

/// JNI function to submit batched operations from Java
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_EnhancedNativeBridge_submitBatchedOperations(
    env: JNIEnv,
    _class: JClass,
    _operation_type: jbyte,
    operations_data: jbyteArray,
    priorities: jbyteArray,
) -> jstring {
    let processor = match get_enhanced_batch_processor() {
        Some(p) => p,
        None => return jni_error!(env, RustError::NotInitializedError(messages::PROCESSOR_NOT_INITIALIZED.to_string()))
    };

    // Convert and validate input arrays
    let operations_vec = match env.convert_byte_array(unsafe { JByteArray::from_raw(operations_data) }) {
        Ok(v) => v,
        Err(_) => return jni_error!(env, RustError::ConversionError(messages::ARRAY_CONVERSION_ERROR.to_string()))
    };

    let priorities_vec = match env.convert_byte_array(unsafe { JByteArray::from_raw(priorities) }) {
        Ok(v) => v,
        Err(_) => return jni_error!(env, RustError::ConversionError(messages::ARRAY_CONVERSION_ERROR.to_string()))
    };

    let operations_slice = operations_vec.as_slice();
    let priorities_slice = priorities_vec.as_slice();

    match parse_batched_operations(operations_slice, priorities_slice) {
        Ok(operations) => {
            let success_count = operations.iter()
                .filter(|op| processor.submit_operation((*op).clone()).is_ok())
                .count();

            env.new_string(&format!(
                "Submitted {} operations successfully",
                success_count
            ))
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut())
        }
        Err(e) => jni_error!(env, RustError::ParseError(format!("{}: {}", messages::PARSE_ERROR, e)))
    }
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

/// Parse batched operations data from Java arrays
fn parse_batched_operations(
    operations_data: &[u8],
    priorities: &[u8],
) -> std::result::Result<Vec<EnhancedBatchOperation>, BatchError> {
    if operations_data.len() < 4 {
        return Err(BatchError::ParseError(messages::PARSE_ERROR.to_string()));
    }

    let operation_count = u32::from_le_bytes([
        operations_data[0],
        operations_data[1],
        operations_data[2],
        operations_data[3],
    ]) as usize;
    
    if operation_count != priorities.len() {
        return Err(BatchError::ParseError("Operation count mismatch with priorities".to_string()));
    }

    let mut operations = Vec::with_capacity(operation_count);
    let mut offset = 4;

    for i in 0..operation_count {
        if offset + 4 > operations_data.len() {
            return Err(BatchError::ParseError("Invalid operations data format".to_string()));
        }

        let data_len = u32::from_le_bytes([
            operations_data[offset],
            operations_data[offset + 1],
            operations_data[offset + 2],
            operations_data[offset + 3],
        ]) as usize;
        offset += 4;

        if offset + data_len > operations_data.len() {
            return Err(BatchError::ParseError("Operation data exceeds buffer".to_string()));
        }

        let operation_data = operations_data[offset..offset + data_len].to_vec();
        let priority = priorities[i];

        operations.push(EnhancedBatchOperation::new(0, operation_data, priority));
        offset += data_len;
    }

    Ok(operations)
}

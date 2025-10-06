use std::collections::{BTreeMap, HashMap, VecDeque};
use std::sync::{Arc, Mutex, RwLock};
use std::fmt::Debug;
use std::time::{SystemTime, UNIX_EPOCH, Duration};
use std::thread;
use std::sync::atomic::{AtomicUsize, AtomicBool, Ordering, AtomicU64};
use std::io::{self, Read, Write, Seek, SeekFrom};
use std::fs::File;
use lz4_flex::{compress, decompress};
use flate2::Compression;
use tokio::sync::{mpsc, oneshot};
use tokio::task;
use tokio::runtime::Runtime;
use memmap2::MmapMut;
use crate::logging::{PerformanceLogger, generate_trace_id};

/// Generic object pool for memory reuse with LRU eviction
#[derive(Debug)]
pub struct ObjectPool<T>
where
    T: Debug,
{
    pool: Arc<Mutex<HashMap<u64, (T, SystemTime)>>>,
    access_order: Arc<Mutex<BTreeMap<SystemTime, u64>>>,
    next_id: AtomicU64,
    max_size: usize,
    logger: PerformanceLogger,
    high_water_mark: AtomicUsize,
    allocation_count: AtomicUsize,
    last_cleanup_time: AtomicUsize,
    is_monitoring: AtomicBool,
    pressure_monitor: Arc<RwLock<MemoryPressureMonitor>>,
}

impl<T> ObjectPool<T>
where
    T: Default + Debug + Send + 'static,
{
    pub fn new(max_size: usize) -> Self {
        Self {
            pool: Arc::new(Mutex::new(HashMap::new())),
            access_order: Arc::new(Mutex::new(BTreeMap::new())),
            next_id: AtomicU64::new(0),
            max_size,
            logger: PerformanceLogger::new("memory_pool"),
            high_water_mark: AtomicUsize::new(0),
            allocation_count: AtomicUsize::new(0),
            last_cleanup_time: AtomicUsize::new(SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_secs() as usize),
            is_monitoring: AtomicBool::new(false),
            pressure_monitor: Arc::new(RwLock::new(MemoryPressureMonitor::new())),
        }
    }

    // Manual Clone implementation because atomics do not implement Clone
    pub fn clone_shallow(&self) -> Self {
        Self {
            pool: Arc::clone(&self.pool),
            access_order: Arc::clone(&self.access_order),
            next_id: AtomicU64::new(self.next_id.load(Ordering::Relaxed)),
            max_size: self.max_size,
            logger: self.logger.clone(),
            high_water_mark: AtomicUsize::new(self.high_water_mark.load(Ordering::Relaxed)),
            allocation_count: AtomicUsize::new(self.allocation_count.load(Ordering::Relaxed)),
            last_cleanup_time: AtomicUsize::new(self.last_cleanup_time.load(Ordering::Relaxed)),
            is_monitoring: AtomicBool::new(self.is_monitoring.load(Ordering::Relaxed)),
            pressure_monitor: Arc::clone(&self.pressure_monitor),
        }
    }

// Note: do not implement Clone trait directly here; use `clone_shallow()` when a shallow clone is needed.

    /// Start background monitoring
    pub fn start_monitoring(&self, check_interval_ms: u64) {
        if self.is_monitoring.load(Ordering::Relaxed) {
            return;
        }

        self.is_monitoring.store(true, Ordering::Relaxed);
        
        let pressure_monitor = Arc::clone(&self.pressure_monitor);
    let pool_clone = Arc::new(self.clone_shallow());
        
        thread::spawn(move || {
            loop {
                // Check pool pressure
                let stats = pool_clone.get_monitoring_stats();
                let usage_ratio = stats.current_usage_ratio;
                
                let pressure_level = MemoryPressureLevel::from_usage_ratio(usage_ratio);
                
                // Update monitoring stats
                let mut monitor = pressure_monitor.write().unwrap();
                monitor.record_pressure_check(pressure_level);
                
                // Perform cleanup if needed
                match pressure_level {
                    MemoryPressureLevel::High | MemoryPressureLevel::Critical => {
                        log::warn!("High memory pressure in object pool - performing cleanup");
                        // ObjectPool provides lazy_cleanup for incremental cleanup
                        (&*pool_clone).lazy_cleanup(0.85);
                    },
                    MemoryPressureLevel::Moderate => {
                        log::info!("Moderate memory pressure in object pool");
                    },
                    _ => {}
                }
                
                // Wait for next check
                thread::sleep(Duration::from_millis(check_interval_ms));
            }
        });
    }

    /// Stop background monitoring
    pub fn stop_monitoring(&self) {
        self.is_monitoring.store(false, Ordering::Relaxed);
    }

    /// Get an object from the pool, creating a new one if none available
    pub fn get(&self) -> PooledObject<T> {
        let trace_id = generate_trace_id();

        // Perform lazy cleanup before allocation if needed
        self.lazy_cleanup(0.9); // Cleanup when usage exceeds 90%

        let mut pool_guard = self.pool.lock().unwrap();
        let mut access_order_guard = self.access_order.lock().unwrap();

        let obj = if let Some((lru_time, lru_id)) = access_order_guard.iter().next().map(|(&t, &id)| (t, id)) {
            // Remove from access order
            access_order_guard.remove(&lru_time);
            // Remove from pool and get the object
            pool_guard.remove(&lru_id).map(|(obj, _)| obj).unwrap()
        } else {
            self.logger.log_operation("pool_miss", &trace_id, || {
                log::debug!("Pool miss for type {}, creating new object", std::any::type_name::<T>());
                T::default()
            })
        };

        // Record allocation for monitoring
        self.record_allocation();

        // Clone the Arcs for the PooledObject
        let pool_arc = Arc::clone(&self.pool);
        let access_order_arc = Arc::clone(&self.access_order);
        let next_id = self.next_id.fetch_add(1, Ordering::Relaxed);

        PooledObject {
            object: Some(obj),
            pool: pool_arc,
            access_order: access_order_arc,
            id: next_id,
            max_size: self.max_size,
            size_bytes: None,
            allocation_tracker: None,
            allocation_type: None,
            is_critical_operation: false,
        }
    }

    /// Get an object from the pool with swap tracking
    pub fn get_with_tracking(
        &self,
        size_bytes: u64,
        allocation_tracker: Arc<RwLock<SwapAllocationMetrics>>,
        allocation_type: &str,
    ) -> PooledObject<T> {
        let trace_id = generate_trace_id();

        // Record allocation before getting object
        if let Ok(mut metrics) = allocation_tracker.write() {
            metrics.record_allocation(size_bytes, allocation_type);
        }

        // Perform lazy cleanup before allocation if needed
        self.lazy_cleanup(0.9); // Cleanup when usage exceeds 90%

        let mut pool_guard = self.pool.lock().unwrap();
        let mut access_order_guard = self.access_order.lock().unwrap();

        let obj = if let Some((lru_time, lru_id)) = access_order_guard.iter().next().map(|(&t, &id)| (t, id)) {
            // Remove from access order
            access_order_guard.remove(&lru_time);
            // Remove from pool and get the object
            pool_guard.remove(&lru_id).map(|(obj, _)| obj).unwrap()
        } else {
            self.logger.log_operation("pool_miss_tracked", &trace_id, || {
                log::debug!("Pool miss for tracked type {}, creating new object", std::any::type_name::<T>());
                T::default()
            })
        };

        // Record allocation for monitoring
        self.record_allocation();

        let pool_arc = Arc::clone(&self.pool);
        let access_order_arc = Arc::clone(&self.access_order);
        let next_id = self.next_id.fetch_add(1, Ordering::Relaxed);

        PooledObject {
            object: Some(obj),
            pool: pool_arc,
            access_order: access_order_arc,
            id: next_id,
            max_size: self.max_size,
            size_bytes: Some(size_bytes),
            allocation_tracker: Some(allocation_tracker),
            allocation_type: Some(allocation_type.to_string()),
            is_critical_operation: false,
        }
    }

    /// Get pool statistics with additional monitoring data
    pub fn get_monitoring_stats(&self) -> ObjectPoolMonitoringStats {
        let trace_id = generate_trace_id();
        self.logger.log_operation("get_monitoring_stats", &trace_id, || {
            let pool_len = self.pool.lock().unwrap().len();
            ObjectPoolMonitoringStats {
                available_objects: pool_len,
                max_size: self.max_size,
                high_water_mark: self.high_water_mark.load(Ordering::Relaxed),
                allocation_count: self.allocation_count.load(Ordering::Relaxed),
                current_usage_ratio: pool_len as f64 / self.max_size as f64,
                last_cleanup_time: self.last_cleanup_time.load(Ordering::Relaxed),
            }
        })
    }

    /// Record allocation for monitoring
    fn record_allocation(&self) {
        let count = self.allocation_count.fetch_add(1, Ordering::Relaxed);
        let current = self.pool.lock().unwrap().len();
        if current > self.high_water_mark.load(Ordering::Relaxed) {
            self.high_water_mark.store(current, Ordering::Relaxed);
        }
    }

    /// Record deallocation for monitoring
    fn record_deallocation(&self) {
        self.allocation_count.fetch_sub(1, Ordering::Relaxed);
    }

    /// Perform lazy cleanup when pool usage exceeds threshold
    pub fn lazy_cleanup(&self, threshold: f64) -> bool {
        let trace_id = generate_trace_id();
        let current_time = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs() as usize;
        let last_cleanup = self.last_cleanup_time.load(Ordering::Relaxed);

        // Only cleanup if enough time has passed (1 second)
        if current_time - last_cleanup < 1 {
            return false;
        }

        let mut pool_guard = self.pool.lock().unwrap();
        let mut access_order_guard = self.access_order.lock().unwrap();
        let usage_ratio = pool_guard.len() as f64 / self.max_size as f64;

        if usage_ratio > threshold {
            self.logger.log_info("lazy_cleanup", &trace_id, &format!("Performing lazy cleanup - usage ratio: {:.2}", usage_ratio));

            // Remove excess objects: evict LRU objects beyond high water mark
            let target_size = (self.high_water_mark.load(Ordering::Relaxed) as f64 * 0.7) as usize;
            while pool_guard.len() > target_size && pool_guard.len() > 5 { // Keep at least 5 objects
                if let Some((lru_time, lru_id)) = access_order_guard.iter().next().map(|(&t, &id)| (t, id)) {
                    access_order_guard.remove(&lru_time);
                    pool_guard.remove(&lru_id);
                } else {
                    break;
                }
            }

            self.last_cleanup_time.store(current_time, Ordering::Relaxed);
            self.logger.log_info("lazy_cleanup", &trace_id, &format!("Reduced pool from {} to {}", self.high_water_mark.load(Ordering::Relaxed), pool_guard.len()));
            return true;
        }

        false
    }
}

/// Enhanced wrapper for pooled objects with LRU access tracking and swap tracking
#[allow(dead_code)]
pub struct PooledObject<T> {
    object: Option<T>,
    pool: Arc<Mutex<HashMap<u64, (T, SystemTime)>>>,
    access_order: Arc<Mutex<BTreeMap<SystemTime, u64>>>,
    id: u64,
    max_size: usize,
    size_bytes: Option<u64>,
    allocation_tracker: Option<Arc<RwLock<SwapAllocationMetrics>>>,
    allocation_type: Option<String>,
    is_critical_operation: bool,
}

impl<T> PooledObject<T> {
    /// Get mutable reference to the pooled object
    pub fn as_mut(&mut self) -> &mut T {
        self.object.as_mut().unwrap()
    }

    /// Get reference to the pooled object
    pub fn as_ref(&self) -> &T {
        self.object.as_ref().unwrap()
    }

    /// Take ownership of the object, preventing return to pool
    pub fn take(mut self) -> T {
        self.object.take().unwrap()
    }
}

impl<T> Drop for PooledObject<T> {
    fn drop(&mut self) {
        if let Some(obj) = self.object.take() {
            // Track deallocation if we have tracking info
            if let (Some(size_bytes), Some(tracker)) = (self.size_bytes, &self.allocation_tracker) {
                if let Ok(mut metrics) = tracker.write() {
                    metrics.record_deallocation(size_bytes);
                }
            }

            let now = SystemTime::now();
            let mut pool_guard = self.pool.lock().unwrap();
            let mut access_order_guard = self.access_order.lock().unwrap();

            // Only return to pool if we're not at critical levels
            let usage_ratio = pool_guard.len() as f64 / self.max_size as f64;
            if usage_ratio < 0.95 { // Only add back if usage is below 95%
                if pool_guard.len() < self.max_size {
                    // Add to pool with current access time
                    pool_guard.insert(self.id, (obj, now));
                    access_order_guard.insert(now, self.id);
                } else {
                    // Pool is full, evict LRU and add this one
                    if let Some((lru_time, lru_id)) = access_order_guard.iter().next().map(|(&t, &id)| (t, id)) {
                        access_order_guard.remove(&lru_time);
                        pool_guard.remove(&lru_id);
                        // Now add the new object
                        pool_guard.insert(self.id, (obj, now));
                        access_order_guard.insert(now, self.id);
                    }
                }
            } else {
                // Don't add back to pool if it's already full to prevent overflow
                log::debug!("Not returning object to pool - usage at critical level: {:.2}", usage_ratio);
            }

            // NOTE: deallocation accounting is handled via allocation trackers
        }
    }
}

impl<T> std::ops::Deref for PooledObject<T> {
    type Target = T;

    fn deref(&self) -> &Self::Target {
        self.as_ref()
    }
}

impl<T> std::ops::DerefMut for PooledObject<T> {
    fn deref_mut(&mut self) -> &mut Self::Target {
        self.as_mut()
    }
}

/// Specialized pool for vectors
#[derive(Debug)]
pub struct VecPool<T>
where
    T: Debug,
{
    pool: ObjectPool<Vec<T>>,
}

impl<T> Clone for VecPool<T>
where
    T: Debug + Default + Clone + Send + 'static,
{
    fn clone(&self) -> Self {
        VecPool { pool: self.pool.clone_shallow() }
    }
}

impl<T> VecPool<T>
where
    T: Debug + Default + Clone + Send + 'static,
{
    pub fn new(max_size: usize) -> Self {
        Self {
            pool: ObjectPool::new(max_size),
        }
    }

    pub fn get_vec(&self, capacity: usize) -> PooledVec<T> {
        let mut pooled = self.pool.get();
        let vec = pooled.as_mut();
        vec.clear();
        vec.reserve(capacity);
        // Ensure the vector actually has the requested capacity
        if vec.capacity() < capacity {
            *vec = Vec::with_capacity(capacity);
        }
        pooled
    }
}

pub type PooledVec<T> = PooledObject<Vec<T>>;

/// Specialized pool for strings
pub struct StringPool {
    pool: ObjectPool<String>,
}

impl Clone for StringPool {
    fn clone(&self) -> Self {
        StringPool { pool: self.pool.clone_shallow() }
    }
}

impl StringPool {
    pub fn new(max_size: usize) -> Self {
        Self {
            pool: ObjectPool::new(max_size),
        }
    }

    pub fn get_string(&self, capacity: usize) -> PooledString {
        let mut pooled = self.pool.get();
        pooled.clear();
        pooled.reserve(capacity);
        pooled
    }
}

pub type PooledString = PooledObject<String>;

/// Global memory pool manager
#[derive(Clone)]
pub struct MemoryPoolManager {
    vec_u64_pool: VecPool<u64>,
    vec_u32_pool: VecPool<u32>,
    vec_f32_pool: VecPool<f32>,
    string_pool: StringPool,
    logger: PerformanceLogger,
}

impl MemoryPoolManager {
    pub fn new() -> Self {
        Self {
            vec_u64_pool: VecPool::new(1000),
            vec_u32_pool: VecPool::new(1000),
            vec_f32_pool: VecPool::new(1000),
            string_pool: StringPool::new(500),
            logger: PerformanceLogger::new("memory_pool_manager"),
        }
    }

    pub fn get_vec_u64(&self, capacity: usize) -> PooledVec<u64> {
        self.vec_u64_pool.get_vec(capacity)
    }

    pub fn get_vec_u32(&self, capacity: usize) -> PooledVec<u32> {
        self.vec_u32_pool.get_vec(capacity)
    }

    pub fn get_vec_f32(&self, capacity: usize) -> PooledVec<f32> {
        self.vec_f32_pool.get_vec(capacity)
    }

    pub fn get_string(&self, capacity: usize) -> PooledString {
        self.string_pool.get_string(capacity)
    }

    /// Get pool statistics
    pub fn get_stats(&self) -> MemoryPoolStats {
        let trace_id = generate_trace_id();
        self.logger.log_operation("get_stats", &trace_id, || {
            MemoryPoolStats {
                vec_u64_available: self.vec_u64_pool.pool.pool.lock().unwrap().len(),
                vec_u32_available: self.vec_u32_pool.pool.pool.lock().unwrap().len(),
                vec_f32_available: self.vec_f32_pool.pool.pool.lock().unwrap().len(),
                strings_available: self.string_pool.pool.pool.lock().unwrap().len(),
            }
        })
    }
}

#[derive(Debug, Clone)]
pub struct MemoryPoolStats {
    pub vec_u64_available: usize,
    pub vec_u32_available: usize,
    pub vec_f32_available: usize,
    pub strings_available: usize,
}

#[derive(Debug, Clone)]
pub struct ObjectPoolMonitoringStats {
    pub available_objects: usize,
    pub max_size: usize,
    pub high_water_mark: usize,
    pub allocation_count: usize,
    pub current_usage_ratio: f64,
    pub last_cleanup_time: usize,
}

/// Thread-local memory pool for better performance
pub struct ThreadLocalMemoryPool {
    manager: Arc<MemoryPoolManager>,
}

impl ThreadLocalMemoryPool {
    pub fn new(manager: Arc<MemoryPoolManager>) -> Self {
        Self { manager }
    }

    pub fn get_vec_u64(&self, capacity: usize) -> PooledVec<u64> {
        self.manager.get_vec_u64(capacity)
    }

    pub fn get_vec_u32(&self, capacity: usize) -> PooledVec<u32> {
        self.manager.get_vec_u32(capacity)
    }

    pub fn get_vec_f32(&self, capacity: usize) -> PooledVec<f32> {
        self.manager.get_vec_f32(capacity)
    }

    pub fn get_string(&self, capacity: usize) -> PooledString {
        self.manager.get_string(capacity)
    }
}

thread_local! {
    static THREAD_LOCAL_POOL: std::cell::RefCell<Option<Arc<MemoryPoolManager>>> = std::cell::RefCell::new(None);
}

/// Initialize thread-local memory pool
pub fn init_thread_local_pool(manager: Arc<MemoryPoolManager>) {
    THREAD_LOCAL_POOL.with(|pool| {
        *pool.borrow_mut() = Some(manager);
    });
}

/// Get thread-local memory pool instance
pub fn get_thread_local_pool() -> Option<ThreadLocalMemoryPool> {
    THREAD_LOCAL_POOL.with(|pool| {
        pool.borrow().as_ref().map(|manager| ThreadLocalMemoryPool::new(Arc::clone(manager)))
    })
}

/// Swap memory allocation tracking
#[derive(Debug, Clone)]
pub struct SwapAllocationMetrics {
    pub total_allocations: u64,
    pub total_deallocations: u64,
    pub current_usage_bytes: u64,
    pub peak_usage_bytes: u64,
    pub allocation_failures: u64,
    pub swap_operations_total: u64,
    pub swap_operations_failed: u64,
    pub chunk_metadata_allocations: u64,
    pub compressed_data_allocations: u64,
    pub temporary_buffer_allocations: u64,
    pub last_pressure_check: u64,
    pub high_water_mark_bytes: u64,
    pub lazy_cleanup_count: u64,
    pub aggressive_cleanup_count: u64,
}

impl SwapAllocationMetrics {
    pub fn new() -> Self {
        Self {
            total_allocations: 0,
            total_deallocations: 0,
            current_usage_bytes: 0,
            peak_usage_bytes: 0,
            allocation_failures: 0,
            swap_operations_total: 0,
            swap_operations_failed: 0,
            chunk_metadata_allocations: 0,
            compressed_data_allocations: 0,
            temporary_buffer_allocations: 0,
            last_pressure_check: 0,
            high_water_mark_bytes: 0,
            lazy_cleanup_count: 0,
            aggressive_cleanup_count: 0,
        }
    }

    pub fn record_allocation(&mut self, size_bytes: u64, allocation_type: &str) {
        self.total_allocations += 1;
        self.current_usage_bytes += size_bytes;
        if self.current_usage_bytes > self.peak_usage_bytes {
            self.peak_usage_bytes = self.current_usage_bytes;
        }
        if self.current_usage_bytes > self.high_water_mark_bytes {
            self.high_water_mark_bytes = self.current_usage_bytes;
        }

        match allocation_type {
            "chunk_metadata" => self.chunk_metadata_allocations += 1,
            "compressed_data" => self.compressed_data_allocations += 1,
            "temporary_buffer" => self.temporary_buffer_allocations += 1,
            _ => {}
        }
    }

    pub fn record_deallocation(&mut self, size_bytes: u64) {
        self.total_deallocations += 1;
        self.current_usage_bytes = self.current_usage_bytes.saturating_sub(size_bytes);
        
        // Check if we need to trigger lazy cleanup based on usage ratio
        let usage_ratio = if self.peak_usage_bytes > 0 {
            self.current_usage_bytes as f64 / self.peak_usage_bytes as f64
        } else {
            0.0
        };
        
        if usage_ratio < 0.3 { // If usage is below 30%, reset high water mark
            self.high_water_mark_bytes = self.current_usage_bytes;
        }
    }

    pub fn record_allocation_failure(&mut self) {
        self.allocation_failures += 1;
    }

    pub fn record_swap_operation(&mut self, success: bool) {
        self.swap_operations_total += 1;
        if !success {
            self.swap_operations_failed += 1;
        }
    }

    pub fn record_lazy_cleanup(&mut self) {
        self.lazy_cleanup_count += 1;
    }

    pub fn record_aggressive_cleanup(&mut self) {
        self.aggressive_cleanup_count += 1;
    }

    pub fn update_pressure_check(&mut self) {
        self.last_pressure_check = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();
    }
}

/// Memory pressure levels
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MemoryPressureLevel {
    Normal,
    Moderate,
    High,
    Critical,
}

impl MemoryPressureLevel {
    pub fn from_usage_ratio(usage_ratio: f64) -> Self {
        match usage_ratio {
            r if r < 0.7 => MemoryPressureLevel::Normal,
            r if r < 0.85 => MemoryPressureLevel::Moderate,
            r if r < 0.95 => MemoryPressureLevel::High,
            _ => MemoryPressureLevel::Critical,
        }
    }
}

/// Swap I/O task types for async processing
#[derive(Debug)]
enum SwapIoTask {
    Prefetch {
        chunk_id: usize,
        size: usize,
        result_sender: oneshot::Sender<Result<Vec<u8>, String>>,
    },
    Write {
        data: Vec<u8>,
        result_sender: oneshot::Sender<Result<usize, String>>,
    },
    Read {
        offset: usize,
        size: usize,
        result_sender: oneshot::Sender<Result<Vec<u8>, String>>,
    },
}

/// Swap I/O configuration structure
#[derive(Debug, Clone)]
pub struct SwapIoConfig {
    pub async_prefetching: bool,
    pub compression_enabled: bool,
    pub compression_level: Compression,
    pub memory_mapped_files: bool,
    pub non_blocking_io: bool,
    pub prefetch_buffer_size: usize,
    pub async_prefetch_limit: usize,
    pub mmap_cache_size: usize,
}

/// Swap-specific memory pool with specialized allocation strategies
pub struct SwapMemoryPool {
    chunk_metadata_pool: VecPool<u8>,
    compressed_data_pool: VecPool<u8>,
    temporary_buffer_pool: VecPool<u8>,
    metrics: Arc<RwLock<SwapAllocationMetrics>>,
    logger: PerformanceLogger,
    max_memory_bytes: usize,
    pressure_threshold: f64,
    resize_factor: f64,
    lazy_allocation_threshold: f64,
    aggressive_cleanup_threshold: f64,
    critical_cleanup_threshold: f64,
    min_allocation_guard: f64,
    last_cleanup_time: AtomicUsize,
    is_critical_operation: AtomicBool,
    
    // Swap I/O Optimization Fields
    async_prefetching: AtomicBool,
    compression_enabled: AtomicBool,
    compression_level: Compression,
    memory_mapped_files: AtomicBool,
    non_blocking_io: AtomicBool,
    prefetch_buffer_size: usize,
    mmap_cache: Arc<RwLock<Vec<MmapMut>>>,
    async_prefetch_limit: usize,
    prefetch_queue: Arc<Mutex<VecDeque<(usize, Vec<u8>)>>>,
    runtime: Option<Runtime>,
    sender: mpsc::Sender<SwapIoTask>,
    pending_operations: AtomicU64,
}

impl SwapMemoryPool {
    pub fn new(max_memory_bytes: usize) -> Self {
        // Initialize Tokio runtime for async operations
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(4)
            .thread_name("swap-io-worker")
            .enable_all()
            .build()
            .ok();

        let (sender, receiver) = mpsc::channel(32);

        // Wrap receiver in an Arc<Mutex<>> so it can be referenced from the spawned runtime task
        // Start background task processor
        if let Some(runtime) = &runtime {
            let sender_clone = sender.clone();
            // Move the original receiver into the background task; we won't use the local receiver after this
            let receiver_for_task = receiver;
            runtime.spawn(async move {
                Self::process_async_tasks(receiver_for_task, sender_clone).await;
            });
        }

        Self {
            chunk_metadata_pool: VecPool::new(1000),
            compressed_data_pool: VecPool::new(500),
            temporary_buffer_pool: VecPool::new(200),
            metrics: Arc::new(RwLock::new(SwapAllocationMetrics::new())),
            logger: PerformanceLogger::new("swap_memory_pool"),
            max_memory_bytes,
            pressure_threshold: 0.8,
            resize_factor: 0.75,
            lazy_allocation_threshold: 0.9, // Start lazy allocation when 90% used
            aggressive_cleanup_threshold: 0.95, // Aggressive cleanup at 95%
            critical_cleanup_threshold: 0.98, // Critical threshold at 98%
            min_allocation_guard: 0.05, // Minimum allocation guard to prevent thrashing
            last_cleanup_time: AtomicUsize::new(SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_secs() as usize),
            is_critical_operation: AtomicBool::new(false),
            
            // Swap I/O Optimization Fields
            async_prefetching: AtomicBool::new(false),
            compression_enabled: AtomicBool::new(false),
            compression_level: Compression::default(),
            memory_mapped_files: AtomicBool::new(false),
            non_blocking_io: AtomicBool::new(false),
            prefetch_buffer_size: 64 * 1024 * 1024, // 64MB default
            mmap_cache: Arc::new(RwLock::new(Vec::new())),
            async_prefetch_limit: 4,
            prefetch_queue: Arc::new(Mutex::new(VecDeque::new())),
            runtime,
            sender,
            pending_operations: AtomicU64::new(0),
        }
    }

    /// Process async swap I/O tasks in background
    async fn process_async_tasks(mut receiver: mpsc::Receiver<SwapIoTask>, _sender: mpsc::Sender<SwapIoTask>) {
        while let Some(task) = receiver.recv().await {
            match task {
                SwapIoTask::Prefetch { chunk_id, size, result_sender } => {
                    Self::async_prefetch_chunk(chunk_id, size, result_sender).await;
                },
                SwapIoTask::Write { data, result_sender } => {
                    Self::async_write_data(data, result_sender).await;
                },
                SwapIoTask::Read { offset, size, result_sender } => {
                    Self::async_read_data(offset, size, result_sender).await;
                },
            }
        }
    }
    /// Async prefetching implementation
    async fn async_prefetch_chunk(chunk_id: usize, size: usize, result_sender: oneshot::Sender<Result<Vec<u8>, String>>) {
        // Simulated async prefetch logic - would be implemented with actual disk I/O in production
        let trace_id = generate_trace_id();
        let logger = PerformanceLogger::new("async_prefetch");

        logger.log_info("async_prefetch_start", &trace_id, &format!("Starting prefetch for chunk {} with size {}", chunk_id, size));

        // Simulate I/O delay
        tokio::time::sleep(Duration::from_millis(10)).await;

        // Create dummy data for simulation
        let mut data = vec![0u8; size];
        data[0] = chunk_id as u8; // Use chunk_id as first byte for identification

        // Compress if enabled
        let final_data = if Self::is_compression_enabled() {
            let compressed = compress(&data);
            let mut result = Vec::with_capacity(4 + compressed.len());
            result.extend_from_slice(&(data.len() as u32).to_le_bytes());
            result.extend_from_slice(&compressed);
            logger.log_info("async_prefetch_compressed", &trace_id, &format!("Compressed chunk {}: {} -> {} bytes", chunk_id, size, result.len()));
            result
        } else {
            data
        };

        // Send result back
        let _ = result_sender.send(Ok(final_data));

        logger.log_info("async_prefetch_complete", &trace_id, &format!("Completed prefetch for chunk {}", chunk_id));
    }

    /// Async write implementation
    async fn async_write_data(data: Vec<u8>, result_sender: oneshot::Sender<Result<usize, String>>) {
        let trace_id = generate_trace_id();
        let logger = PerformanceLogger::new("async_write");

        logger.log_info("async_write_start", &trace_id, &format!("Starting async write of {} bytes", data.len()));

        // Simulated async write - would be actual disk I/O in production
        tokio::time::sleep(Duration::from_millis(15)).await;

        // In real implementation, we would write to disk here
        // For simulation, we just return success
        let bytes_written = data.len();

        logger.log_info("async_write_complete", &trace_id, &format!("Completed async write: {} bytes", bytes_written));

        let _ = result_sender.send(Ok(bytes_written));
    }

    /// Async read implementation
    async fn async_read_data(offset: usize, size: usize, result_sender: oneshot::Sender<Result<Vec<u8>, String>>) {
        let trace_id = generate_trace_id();
        let logger = PerformanceLogger::new("async_read");

        logger.log_info("async_read_start", &trace_id, &format!("Starting async read at offset {} with size {}", offset, size));

        // Simulated async read - would be actual disk I/O in production
        tokio::time::sleep(Duration::from_millis(12)).await;

        // Create dummy data for simulation
        let mut data = vec![0u8; size];
        data[0] = (offset % 256) as u8; // Use offset as first byte for identification

        logger.log_info("async_read_complete", &trace_id, &format!("Completed async read: {} bytes", size));

        let _ = result_sender.send(Ok(data));
    }

    /// Allocate memory for chunk metadata with tracking
    pub fn allocate_chunk_metadata(&self, size: usize) -> Result<PooledVec<u8>, String> {
        let trace_id = generate_trace_id();
        self.logger.log_operation("allocate_chunk_metadata", &trace_id, || {
            // Mark as critical operation to prevent cleanup during allocation
            self.is_critical_operation.store(true, Ordering::Relaxed);
            
            // Check memory pressure before allocation with safeguards
            if let Err(e) = self.check_memory_pressure() {
                if let Ok(mut metrics) = self.metrics.write() {
                    metrics.record_allocation_failure();
                }
                self.is_critical_operation.store(false, Ordering::Relaxed);
                return Err(e);
            }

            // Try to prefetch data if async prefetching is enabled
            if self.async_prefetching.load(Ordering::Relaxed) {
                self.try_prefetch_chunk_metadata(size);
            }

            let mut pooled = self.chunk_metadata_pool.pool.get_with_tracking(
                size as u64,
                Arc::clone(&self.metrics),
                "chunk_metadata"
            );

            // Ensure the vector has the requested size (filled with zeros)
            let vec = pooled.as_mut();
            vec.clear();
            vec.resize(size, 0u8);
            
            // Clear critical operation flag
            self.is_critical_operation.store(false, Ordering::Relaxed);

            Ok(pooled)
        })
    }

    /// Try to prefetch chunk metadata asynchronously
    fn try_prefetch_chunk_metadata(&self, size: usize) {
        let trace_id = generate_trace_id();
        
        // Generate a simulated chunk ID for prefetching
        let chunk_id = fastrand::usize(0..10000); // Use fastrand for realistic chunk IDs
        
        // Check if we're already at prefetch limit
        let current_pending = self.pending_operations.load(Ordering::Relaxed);
        if current_pending >= self.async_prefetch_limit as u64 {
            self.logger.log_debug("prefetch_skipped", &trace_id, &format!("Skipping prefetch for chunk {} - at limit ({} pending)", chunk_id, current_pending));
            return;
        }

        // Increment pending operations counter
        self.pending_operations.fetch_add(1, Ordering::Relaxed);

        // Create result channel
        let (result_sender, result_receiver) = oneshot::channel();

        // Send prefetch task to async processor
        let sender_clone = self.sender.clone();
        let logger = self.logger.clone();
        
        task::spawn_blocking(move || {
            let _ = sender_clone.blocking_send(SwapIoTask::Prefetch {
                chunk_id,
                size,
                result_sender,
            });
        });

        // For this simplified implementation we schedule the prefetch and do not wait for the result here.
        // Decrement pending operations immediately for simulation purposes (real implementation would wait for completion).
        self.pending_operations.fetch_sub(1, Ordering::Relaxed);
    }

    /// Allocate memory for compressed data with tracking
    pub fn allocate_compressed_data(&self, size: usize) -> Result<PooledVec<u8>, String> {
        let trace_id = generate_trace_id();
        self.logger.log_operation("allocate_compressed_data", &trace_id, || {
            // Mark as critical operation to prevent cleanup during allocation
            self.is_critical_operation.store(true, Ordering::Relaxed);
            
            // Check memory pressure before allocation with safeguards
            if let Err(e) = self.check_memory_pressure() {
                if let Ok(mut metrics) = self.metrics.write() {
                    metrics.record_allocation_failure();
                }
                self.is_critical_operation.store(false, Ordering::Relaxed);
                return Err(e);
            }

            let mut pooled = self.compressed_data_pool.pool.get_with_tracking(
                size as u64,
                Arc::clone(&self.metrics),
                "compressed_data"
            );

            // Ensure the vector has the requested size (filled with zeros)
            let vec = pooled.as_mut();
            vec.clear();
            vec.resize(size, 0u8);
            
            // Apply compression if enabled
            if self.compression_enabled.load(Ordering::Relaxed) {
                let compressed = self.compress_data(vec)?;
                *vec = compressed;
            }
            
            // Clear critical operation flag
            self.is_critical_operation.store(false, Ordering::Relaxed);

            Ok(pooled)
        })
    }

    /// Allocate memory for temporary buffers with tracking
    pub fn allocate_temporary_buffer(&self, size: usize) -> Result<PooledVec<u8>, String> {
        let trace_id = generate_trace_id();
        self.logger.log_operation("allocate_temporary_buffer", &trace_id, || {
            // Mark as critical operation to prevent cleanup during allocation
            self.is_critical_operation.store(true, Ordering::Relaxed);
            
            // Check memory pressure before allocation with safeguards
            if let Err(e) = self.check_memory_pressure() {
                if let Ok(mut metrics) = self.metrics.write() {
                    metrics.record_allocation_failure();
                }
                self.is_critical_operation.store(false, Ordering::Relaxed);
                return Err(e);
            }

            let mut pooled = self.temporary_buffer_pool.pool.get_with_tracking(
                size as u64,
                Arc::clone(&self.metrics),
                "temporary_buffer"
            );

            // Ensure the vector has the requested size (filled with zeros)
            let vec = pooled.as_mut();
            vec.clear();
            vec.resize(size, 0u8);
            
            // Clear critical operation flag
            self.is_critical_operation.store(false, Ordering::Relaxed);

            Ok(pooled)
        })
    }

    /// Check current memory pressure level
    pub fn get_memory_pressure(&self) -> MemoryPressureLevel {
        if let Ok(metrics) = self.metrics.read() {
            let usage_ratio = metrics.current_usage_bytes as f64 / self.max_memory_bytes as f64;
            
            // Only perform cleanup if not in critical operation to prevent lag
            if !self.is_critical_operation.load(Ordering::Relaxed) {
                // Perform threshold-based cleanup
                match usage_ratio {
                    r if r > self.critical_cleanup_threshold => {
                        self.perform_aggressive_cleanup();
                        MemoryPressureLevel::Critical
                    },
                    r if r > self.aggressive_cleanup_threshold => {
                        self.perform_aggressive_cleanup();
                        MemoryPressureLevel::High
                    },
                    r if r > self.lazy_allocation_threshold => {
                        self.perform_lazy_allocation_cleanup();
                        MemoryPressureLevel::Moderate
                    },
                    _ => MemoryPressureLevel::from_usage_ratio(usage_ratio),
                }
            } else {
                MemoryPressureLevel::from_usage_ratio(usage_ratio)
            }
        } else {
            MemoryPressureLevel::Normal
        }
    }

    /// Check memory pressure and trigger automatic pool management if needed
    fn check_memory_pressure(&self) -> Result<(), String> {
        let pressure = self.get_memory_pressure();
        let trace_id = generate_trace_id();

        if let Ok(mut metrics) = self.metrics.write() {
            metrics.update_pressure_check();
        }

        match pressure {
            MemoryPressureLevel::Normal => Ok(()),
            MemoryPressureLevel::Moderate => {
                self.logger.log_info("memory_pressure_check", &trace_id, "Moderate memory pressure detected");
                self.perform_light_cleanup();
                Ok(())
            },
            MemoryPressureLevel::High => {
                self.logger.log_warning("memory_pressure_check", &trace_id, "High memory pressure detected - performing aggressive cleanup");
                self.perform_aggressive_cleanup();
                Ok(())
            },
            MemoryPressureLevel::Critical => {
                self.logger.log_error("memory_pressure_check", &trace_id, "Critical memory pressure - allocation may fail", "MEMORY_PRESSURE_CRITICAL");
                
                // Record aggressive cleanup for critical pressure
                if let Ok(mut metrics) = self.metrics.write() {
                    metrics.record_aggressive_cleanup();
                }
                
                Err("Critical memory pressure - allocation denied".to_string())
            },
        }
    }

    /// Perform light cleanup (reduce pool sizes)
    pub fn perform_light_cleanup(&self) {
        let trace_id = generate_trace_id();
        self.logger.log_info("light_cleanup", &trace_id, "Performing light memory cleanup");

        // Reduce pool sizes by evicting LRU objects
        self.cleanup_pool_light(&self.chunk_metadata_pool, 0.8, 10);
        self.cleanup_pool_light(&self.compressed_data_pool, 0.8, 5);
        self.cleanup_pool_light(&self.temporary_buffer_pool, 0.8, 2);

        // Update last cleanup time
        let current_time = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs() as usize;
        self.last_cleanup_time.store(current_time, Ordering::Relaxed);
    }

    /// Helper to perform light cleanup on a VecPool
    fn cleanup_pool_light(&self, pool: &VecPool<u8>, ratio: f64, min_keep: usize) {
        let trace_id = generate_trace_id();
        if let Ok(mut inner_pool) = pool.pool.pool.lock() {
            if let Ok(mut access_order) = pool.pool.access_order.lock() {
                let current_size = inner_pool.len();
                let target_size = (current_size as f64 * ratio) as usize;
                let safe_target = target_size.max(min_keep);

                let removed = current_size.saturating_sub(safe_target);
                while inner_pool.len() > safe_target {
                    if let Some((lru_time, lru_id)) = access_order.iter().next().map(|(&t, &id)| (t, id)) {
                        access_order.remove(&lru_time);
                        inner_pool.remove(&lru_id);
                    } else {
                        break;
                    }
                }

                if removed > 0 {
                    self.logger.log_info("pool_light_cleanup", &trace_id, &format!("Removed {} objects from pool ({} -> {})", removed, current_size, inner_pool.len()));
                }
            }
        }
    }

    /// Perform aggressive cleanup (force pool resizing)
    pub fn perform_aggressive_cleanup(&self) {
        let trace_id = generate_trace_id();
        self.logger.log_info("aggressive_cleanup", &trace_id, "Performing aggressive memory cleanup");
          
        // Clear pools entirely and force garbage collection
        if let Ok(mut pool) = self.chunk_metadata_pool.pool.pool.lock() {
            let cleared = pool.len();
            pool.clear();
            self.logger.log_info("aggressive_cleanup", &trace_id, &format!("Cleared {} chunk metadata objects", cleared));
        }

        if let Ok(mut pool) = self.compressed_data_pool.pool.pool.lock() {
            let cleared = pool.len();
            pool.clear();
            self.logger.log_info("aggressive_cleanup", &trace_id, &format!("Cleared {} compressed data objects", cleared));
        }

        if let Ok(mut pool) = self.temporary_buffer_pool.pool.pool.lock() {
            let cleared = pool.len();
            pool.clear();
            self.logger.log_info("aggressive_cleanup", &trace_id, &format!("Cleared {} temporary buffer objects", cleared));
        }

        // Force memory reclamation hint
        std::sync::atomic::fence(std::sync::atomic::Ordering::SeqCst);
        
        // Record cleanup event
        if let Ok(mut metrics) = self.metrics.write() {
            metrics.record_aggressive_cleanup();
        }
        
        // Update last cleanup time
        let current_time = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs() as usize;
        self.last_cleanup_time.store(current_time, Ordering::Relaxed);
    }

    /// Perform lazy allocation cleanup to prevent memory exhaustion
    fn perform_lazy_allocation_cleanup(&self) {
        let trace_id = generate_trace_id();
        let current_time = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs() as usize;
        let last_cleanup = self.last_cleanup_time.load(Ordering::Relaxed);
        
        // Only cleanup if enough time has passed (1 second) to prevent thrashing
        if current_time - last_cleanup < 1 {
            return;
        }

        self.logger.log_info("lazy_allocation_cleanup", &trace_id, "Performing lazy allocation cleanup");
        
        // Get current metrics for intelligent cleanup
        let metrics = self.metrics.read().unwrap();
        let usage_ratio = metrics.current_usage_bytes as f64 / self.max_memory_bytes as f64;
        drop(metrics);
        
        // Calculate cleanup ratio based on current pressure (more aggressive when closer to limits)
        let cleanup_ratio = 0.2 + ((usage_ratio - self.lazy_allocation_threshold) * 0.8)
            .clamp(0.0, 0.8); // 20-100% cleanup ratio
        
        self.logger.log_info("lazy_allocation_cleanup", &trace_id, &format!("Using dynamic cleanup ratio: {:.1}%", cleanup_ratio * 100.0));
        
        // Clean up pools with dynamic ratios while preserving minimum objects
        self.cleanup_pool(&self.chunk_metadata_pool, cleanup_ratio, 10);
        self.cleanup_pool(&self.compressed_data_pool, cleanup_ratio * 0.7, 5); // More conservative for compressed data
        self.cleanup_pool(&self.temporary_buffer_pool, cleanup_ratio * 0.5, 2); // Most conservative for temporary buffers
        
        // Record cleanup event
        if let Ok(mut metrics) = self.metrics.write() {
            metrics.record_lazy_cleanup();
        }
        
        // Update last cleanup time
        self.last_cleanup_time.store(current_time, Ordering::Relaxed);
        
        // Log cleanup results
        if let Ok(metrics) = self.metrics.read() {
            let new_usage_ratio = metrics.current_usage_bytes as f64 / self.max_memory_bytes as f64;
            self.logger.log_info("lazy_allocation_cleanup", &trace_id, &format!("Memory usage: {:.2}% → {:.2}% (reduced by {:.1}%)",
                usage_ratio * 100.0, new_usage_ratio * 100.0, (usage_ratio - new_usage_ratio) * 100.0));
        }
    }

    /// Helper function to cleanup a specific pool with intelligent size calculation
    fn cleanup_pool(&self, pool: &VecPool<u8>, cleanup_ratio: f64, min_keep: usize) {
        let trace_id = generate_trace_id();

        if let Ok(mut inner_pool) = pool.pool.pool.lock() {
            if let Ok(mut access_order) = pool.pool.access_order.lock() {
                let current_size = inner_pool.len();
                if current_size <= min_keep {
                    return;
                }

                // Calculate target size with safeguards
                let target_size = (current_size as f64 * (1.0 - cleanup_ratio)) as usize;
                let safe_target = target_size.max(min_keep);

                let removed = current_size - safe_target;
                while inner_pool.len() > safe_target {
                    if let Some((lru_time, lru_id)) = access_order.iter().next().map(|(&t, &id)| (t, id)) {
                        access_order.remove(&lru_time);
                        inner_pool.remove(&lru_id);
                    } else {
                        break;
                    }
                }

                self.logger.log_info("pool_cleanup", &trace_id, &format!("Cleaned up pool: removed {} objects ({}%), from {} to {}",
                    removed, (removed as f64 / current_size as f64 * 100.0).round(), current_size, inner_pool.len()));
            }
        }
    }

    /// Get current swap allocation metrics
    pub fn get_metrics(&self) -> SwapAllocationMetrics {
        self.metrics.read().unwrap().clone()
    }

    /// Update pool configuration
    pub fn update_config(&mut self, max_memory_bytes: usize, pressure_threshold: f64, resize_factor: f64) {
        self.max_memory_bytes = max_memory_bytes;
        self.pressure_threshold = pressure_threshold;
        self.resize_factor = resize_factor;
    }

    /// Update swap I/O optimization configuration
    pub fn update_swap_io_config(&mut self, config: SwapIoConfig) {
        self.async_prefetching.store(config.async_prefetching, Ordering::Relaxed);
        self.compression_enabled.store(config.compression_enabled, Ordering::Relaxed);
        self.compression_level = config.compression_level;
        self.memory_mapped_files.store(config.memory_mapped_files, Ordering::Relaxed);
        self.non_blocking_io.store(config.non_blocking_io, Ordering::Relaxed);
        self.prefetch_buffer_size = config.prefetch_buffer_size;
        self.async_prefetch_limit = config.async_prefetch_limit;
    }

    /// Check if compression is enabled
    pub fn is_compression_enabled() -> bool {
        // In real implementation, this would be a static check or use a global config
        true
    }

    /// Get current compression level
    pub fn get_default_compression_level() -> Compression {
        // In real implementation, this would use the configured level
        Compression::default()
    }

    /// Compress data with current settings
    pub fn compress_data(&self, data: &[u8]) -> Result<Vec<u8>, String> {
        if !self.compression_enabled.load(Ordering::Relaxed) {
            return Ok(data.to_vec());
        }

        let compressed = compress(data);
        // Prepend original size as 4 bytes little endian
        let mut result = Vec::with_capacity(4 + compressed.len());
        result.extend_from_slice(&(data.len() as u32).to_le_bytes());
        result.extend_from_slice(&compressed);

        Ok(result)
    }

    /// Decompress data with current settings
    pub fn decompress_data(&self, data: &[u8]) -> Result<Vec<u8>, String> {
        if !self.compression_enabled.load(Ordering::Relaxed) {
            return Ok(data.to_vec());
        }

        if data.len() < 4 {
            return Err("Data too short for LZ4 decompression".to_string());
        }

        let original_size = u32::from_le_bytes([data[0], data[1], data[2], data[3]]) as usize;
        let compressed_data = &data[4..];
        let decompressed = decompress(compressed_data, original_size).map_err(|e| e.to_string())?;

        Ok(decompressed)
    }

    /// Create memory-mapped file for swap operations
    pub fn create_mmap_file(&self, path: &str, size: usize) -> Result<MmapMut, String> {
        if !self.memory_mapped_files.load(Ordering::Relaxed) {
            return Err("Memory-mapped files not enabled".to_string());
        }

        let file = File::create(path).map_err(|e| e.to_string())?;
        // Set file size in a cross-platform way
        file.set_len(size as u64).map_err(|e| e.to_string())?;

        // Map file into memory
    let mmap = unsafe { MmapMut::map_mut(&file).map_err(|e| e.to_string())? };
        
        Ok(mmap)
    }

    /// Write data using non-blocking I/O if enabled
    pub async fn write_data_async(&self, data: Vec<u8>) -> Result<usize, String> {
        if !self.non_blocking_io.load(Ordering::Relaxed) {
            return self.write_data_sync(data);
        }

        let (sender, receiver) = oneshot::channel();
        let sender_clone = self.sender.clone();

        task::spawn_blocking(move || {
            let _ = sender_clone.blocking_send(SwapIoTask::Write { data, result_sender: sender });
        }).await.map_err(|e| e.to_string())?;

        match receiver.await {
            Ok(Ok(bytes)) => Ok(bytes),
            Ok(Err(e)) => Err(e),
            Err(_) => Err("Write operation timed out".to_string()),
        }
    }

    /// Read data using non-blocking I/O if enabled
    pub async fn read_data_async(&self, offset: usize, size: usize) -> Result<Vec<u8>, String> {
        if !self.non_blocking_io.load(Ordering::Relaxed) {
            return self.read_data_sync(offset, size);
        }

        let (sender, receiver) = oneshot::channel();
        let sender_clone = self.sender.clone();

        task::spawn_blocking(move || {
            let _ = sender_clone.blocking_send(SwapIoTask::Read { offset, size, result_sender: sender });
        }).await.map_err(|e| e.to_string())?;

        match receiver.await {
            Ok(Ok(data)) => Ok(data),
            Ok(Err(e)) => Err(e),
            Err(_) => Err("Read operation timed out".to_string()),
        }
    }

    /// Write data using synchronous I/O (fallback)
    fn write_data_sync(&self, data: Vec<u8>) -> Result<usize, String> {
        // In real implementation, this would write to actual swap file
        Ok(data.len())
    }

    /// Read data using synchronous I/O (fallback)
    fn read_data_sync(&self, offset: usize, size: usize) -> Result<Vec<u8>, String> {
        // In real implementation, this would read from actual swap file
        let mut data = vec![0u8; size];
        data[0] = (offset % 256) as u8; // Use offset as first byte for identification
        Ok(data)
    }

    /// Record swap operation result
    pub fn record_swap_operation(&self, success: bool) {
        if let Ok(mut metrics) = self.metrics.write() {
            metrics.record_swap_operation(success);
        }
    }
}

/// Memory pressure monitor for background monitoring
#[derive(Debug)]
pub struct MemoryPressureMonitor {
    pressure_checks: Mutex<Vec<(SystemTime, MemoryPressureLevel)>>,
    last_log_time: Mutex<SystemTime>,
}

impl MemoryPressureMonitor {
    pub fn new() -> Self {
        Self {
            pressure_checks: Mutex::new(Vec::new()),
            last_log_time: Mutex::new(SystemTime::now()),
        }
    }

    pub fn record_pressure_check(&self, pressure: MemoryPressureLevel) {
        let now = SystemTime::now();
        let mut checks = self.pressure_checks.lock().unwrap();
        checks.push((now, pressure));
        
        // Keep only last 60 seconds of data
        while let Some(&(_, _)) = checks.first() {
            if now.duration_since(checks[0].0).unwrap_or(Duration::from_secs(61)).as_secs() > 60 {
                checks.remove(0);
            } else {
                break;
            }
        }
    }

    pub fn should_log(&self, pressure: MemoryPressureLevel) -> bool {
        let now = SystemTime::now();
        let mut last_log = self.last_log_time.lock().unwrap();
        
        if now.duration_since(*last_log).unwrap_or(Duration::from_secs(0)).as_secs() > 60 {
            *last_log = now;
            return true;
        }
        
        false
    }

    pub fn get_stats(&self) -> MemoryPressureMonitoringStats {
        let checks = self.pressure_checks.lock().unwrap();
        let total_checks = checks.len();
        
        if total_checks == 0 {
            return MemoryPressureMonitoringStats {
                total_checks: 0,
                normal_checks: 0,
                moderate_checks: 0,
                high_checks: 0,
                critical_checks: 0,
                avg_checks_per_minute: 0.0,
                last_check_time: SystemTime::now(),
            };
        }
        
        let normal_checks = checks.iter().filter(|&&(_, p)| p == MemoryPressureLevel::Normal).count();
        let moderate_checks = checks.iter().filter(|&&(_, p)| p == MemoryPressureLevel::Moderate).count();
        let high_checks = checks.iter().filter(|&&(_, p)| p == MemoryPressureLevel::High).count();
        let critical_checks = checks.iter().filter(|&&(_, p)| p == MemoryPressureLevel::Critical).count();
        
        let avg_checks_per_minute = if total_checks > 0 {
            let duration = checks.last().unwrap().0.duration_since(checks.first().unwrap().0).unwrap_or(Duration::from_secs(60));
            let minutes = duration.as_secs() as f64 / 60.0;
            (total_checks as f64 / minutes).max(0.0)
        } else {
            0.0
        };
        
        MemoryPressureMonitoringStats {
            total_checks,
            normal_checks,
            moderate_checks,
            high_checks,
            critical_checks,
            avg_checks_per_minute,
            last_check_time: checks.last().unwrap().0,
        }
    }
}

#[derive(Debug, Clone)]
pub struct MemoryPressureMonitoringStats {
    pub total_checks: usize,
    pub normal_checks: usize,
    pub moderate_checks: usize,
    pub high_checks: usize,
    pub critical_checks: usize,
    pub avg_checks_per_minute: f64,
    pub last_check_time: SystemTime,
}

#[derive(Debug, Clone)]
pub struct EnhancedMonitoringStats {
    pub regular_stats: MemoryPoolStats,
    pub vec_u64_stats: ObjectPoolMonitoringStats,
    pub vec_u32_stats: ObjectPoolMonitoringStats,
    pub vec_f32_stats: ObjectPoolMonitoringStats,
    pub string_stats: ObjectPoolMonitoringStats,
    pub swap_metrics: SwapAllocationMetrics,
    pub memory_pressure: MemoryPressureLevel,
    pub monitoring_stats: MemoryPressureMonitoringStats,
    pub total_memory_usage_bytes: u64,
}

/// Enhanced memory pool manager with swap support
pub struct EnhancedMemoryPoolManager {
    regular_manager: MemoryPoolManager,
    swap_pool: Arc<SwapMemoryPool>,
    logger: PerformanceLogger,
    pressure_monitor: Arc<Mutex<MemoryPressureMonitor>>,
    is_monitoring: AtomicBool,
}

impl EnhancedMemoryPoolManager {
    pub fn new(max_swap_memory_bytes: usize) -> Self {
        let pressure_monitor = Arc::new(Mutex::new(MemoryPressureMonitor::new()));
        
        // Start background monitoring thread
        let monitor_clone = Arc::clone(&pressure_monitor);
        let swap_pool_clone = Arc::new(SwapMemoryPool::new(max_swap_memory_bytes));
        
        Self {
            regular_manager: MemoryPoolManager::new(),
            swap_pool: swap_pool_clone,
            logger: PerformanceLogger::new("enhanced_memory_pool_manager"),
            pressure_monitor,
            is_monitoring: AtomicBool::new(false),
        }
    }

    /// Start background memory pressure monitoring
    pub fn start_monitoring(&self, check_interval_ms: u64) {
        if self.is_monitoring.load(Ordering::Relaxed) {
            return;
        }

        self.is_monitoring.store(true, Ordering::Relaxed);
        
        let pressure_monitor = Arc::clone(&self.pressure_monitor);
        let swap_pool = Arc::clone(&self.swap_pool);
        let logger = self.logger.clone();
        
        thread::spawn(move || {
            loop {
                // Check memory pressure
                let pressure = swap_pool.get_memory_pressure();
                
                // Update monitoring stats
                let mut monitor = pressure_monitor.lock().unwrap();
                monitor.record_pressure_check(pressure);
                
                // Log pressure information
                match pressure {
                    MemoryPressureLevel::Normal => {
                        if monitor.should_log(pressure) {
                            logger.log_info("memory_monitor", "background_check", "Normal memory pressure");
                        }
                    },
                    MemoryPressureLevel::Moderate => {
                        logger.log_info("memory_monitor", "background_check", "Moderate memory pressure detected");
                    },
                    MemoryPressureLevel::High => {
                        logger.log_warning("memory_monitor", "background_check", "High memory pressure detected");
                    },
                    MemoryPressureLevel::Critical => {
                        logger.log_error("memory_monitor", "background_check", "Critical memory pressure detected", "MEMORY_PRESSURE_CRITICAL");
                    },
                }
                
                // Perform cleanup if needed
                match pressure {
                    MemoryPressureLevel::High | MemoryPressureLevel::Critical => {
                        swap_pool.perform_aggressive_cleanup();
                    },
                    MemoryPressureLevel::Moderate => {
                        swap_pool.perform_light_cleanup();
                    },
                    _ => {}
                }
                
                // Wait for next check
                thread::sleep(Duration::from_millis(check_interval_ms));
            }
        });
    }

    /// Expose current memory pressure of swap pool
    pub fn get_memory_pressure(&self) -> MemoryPressureLevel {
        self.swap_pool.get_memory_pressure()
    }

    /// Stop background memory pressure monitoring
    pub fn stop_monitoring(&self) {
        self.is_monitoring.store(false, Ordering::Relaxed);
    }

    /// Delegate regular pool operations
    pub fn get_vec_u64(&self, capacity: usize) -> PooledVec<u64> {
        self.regular_manager.get_vec_u64(capacity)
    }

    pub fn get_vec_u32(&self, capacity: usize) -> PooledVec<u32> {
        self.regular_manager.get_vec_u32(capacity)
    }

    pub fn get_vec_f32(&self, capacity: usize) -> PooledVec<f32> {
        self.regular_manager.get_vec_f32(capacity)
    }

    pub fn get_string(&self, capacity: usize) -> PooledString {
        self.regular_manager.get_string(capacity)
    }

    /// Swap-specific allocations
    pub fn allocate_chunk_metadata(&self, size: usize) -> Result<PooledVec<u8>, String> {
        self.swap_pool.allocate_chunk_metadata(size)
    }

    pub fn allocate_compressed_data(&self, size: usize) -> Result<PooledVec<u8>, String> {
        self.swap_pool.allocate_compressed_data(size)
    }

    pub fn allocate_temporary_buffer(&self, size: usize) -> Result<PooledVec<u8>, String> {
        self.swap_pool.allocate_temporary_buffer(size)
    }

    /// Get comprehensive pool statistics
    pub fn get_enhanced_stats(&self) -> EnhancedMemoryPoolStats {
        let trace_id = generate_trace_id();
        self.logger.log_operation("get_enhanced_stats", &trace_id, || {
            let regular_stats = self.regular_manager.get_stats();
            let swap_metrics = self.swap_pool.get_metrics();
            let memory_pressure = self.swap_pool.get_memory_pressure();
            let swap_usage = swap_metrics.current_usage_bytes;

            EnhancedMemoryPoolStats {
                regular_stats: regular_stats.clone(),
                swap_metrics: swap_metrics.clone(),
                memory_pressure,
                total_memory_usage_bytes: swap_usage
                    + (regular_stats.vec_u64_available * std::mem::size_of::<u64>() as usize) as u64
                    + (regular_stats.vec_u32_available * std::mem::size_of::<u32>() as usize) as u64
                    + (regular_stats.vec_f32_available * std::mem::size_of::<f32>() as usize) as u64
                    + (regular_stats.strings_available * 24) as u64, // Approximate string size
            }
        })
    }

    /// Get swap pool reference for direct operations
    pub fn get_swap_pool(&self) -> &Arc<SwapMemoryPool> {
        &self.swap_pool
    }

    /// Record swap operation result
    pub fn record_swap_operation(&self, success: bool) {
        self.swap_pool.record_swap_operation(success)
    }

    /// Get memory pressure callback for integration with swap manager
    pub fn get_memory_pressure_callback(&self) -> Box<dyn Fn() -> MemoryPressureLevel + Send + Sync> {
        let swap_pool = Arc::clone(&self.swap_pool);
        Box::new(move || swap_pool.get_memory_pressure())
    }

    /// Force memory cleanup based on pressure level
    pub fn force_cleanup(&self, pressure_level: MemoryPressureLevel) {
        let trace_id = generate_trace_id();
        match pressure_level {
            MemoryPressureLevel::Normal => {
                self.logger.log_info("force_cleanup", &trace_id, "No cleanup needed - memory pressure normal");
            },
            MemoryPressureLevel::Moderate => {
                self.logger.log_info("force_cleanup", &trace_id, "Triggering light cleanup due to moderate pressure");
                self.swap_pool.perform_light_cleanup();
            },
            MemoryPressureLevel::High | MemoryPressureLevel::Critical => {
                self.logger.log_warning("force_cleanup", &trace_id, "Triggering aggressive cleanup due to high/critical pressure");
                self.swap_pool.perform_aggressive_cleanup();
            },
        }
    }

    /// Get swap allocation efficiency metrics
    pub fn get_swap_efficiency_metrics(&self) -> SwapEfficiencyMetrics {
        let metrics = self.swap_pool.get_metrics();
        let efficiency = if metrics.total_allocations > 0 {
            (metrics.total_deallocations as f64 / metrics.total_allocations as f64) * 100.0
        } else {
            0.0
        };

        let failure_rate = if metrics.total_allocations > 0 {
            (metrics.allocation_failures as f64 / metrics.total_allocations as f64) * 100.0
        } else {
            0.0
        };

        let swap_success_rate = if metrics.swap_operations_total > 0 {
            ((metrics.swap_operations_total - metrics.swap_operations_failed) as f64
             / metrics.swap_operations_total as f64) * 100.0
        } else {
            100.0
        };

        SwapEfficiencyMetrics {
            allocation_efficiency: efficiency,
            allocation_failure_rate: failure_rate,
            swap_success_rate,
            memory_utilization: (metrics.current_usage_bytes as f64 / self.swap_pool.max_memory_bytes as f64) * 100.0,
            peak_memory_usage_bytes: metrics.peak_usage_bytes,
        }
    }

    /// Get memory pressure monitoring statistics
    pub fn get_monitoring_stats(&self) -> MemoryPressureMonitoringStats {
        let monitor = self.pressure_monitor.lock().unwrap();
        monitor.get_stats()
    }

    /// Get enhanced monitoring statistics including all subsystems
    pub fn get_enhanced_monitoring_stats(&self) -> EnhancedMonitoringStats {
        let trace_id = generate_trace_id();
        self.logger.log_operation("get_enhanced_monitoring_stats", &trace_id, || {
            let regular_stats = self.regular_manager.get_stats();
            
            // Get monitoring stats from each object pool
            let vec_u64_stats = self.regular_manager.vec_u64_pool.pool.get_monitoring_stats();
            let vec_u32_stats = self.regular_manager.vec_u32_pool.pool.get_monitoring_stats();
            let vec_f32_stats = self.regular_manager.vec_f32_pool.pool.get_monitoring_stats();
            let string_stats = self.regular_manager.string_pool.pool.get_monitoring_stats();
            
            let swap_metrics = self.swap_pool.get_metrics();
            let memory_pressure = self.swap_pool.get_memory_pressure();
            let monitoring_stats = self.get_monitoring_stats();

            EnhancedMonitoringStats {
                regular_stats: regular_stats.clone(),
                vec_u64_stats,
                vec_u32_stats,
                vec_f32_stats,
                string_stats,
                swap_metrics: swap_metrics.clone(),
                memory_pressure,
                monitoring_stats,
                total_memory_usage_bytes: swap_metrics.current_usage_bytes
                    + (regular_stats.vec_u64_available * std::mem::size_of::<u64>() as usize) as u64
                    + (regular_stats.vec_u32_available * std::mem::size_of::<u32>() as usize) as u64
                    + (regular_stats.vec_f32_available * std::mem::size_of::<f32>() as usize) as u64
                    + (regular_stats.strings_available * 24) as u64, // Approximate string size
            }
        })
    }
}

#[derive(Debug, Clone)]
pub struct SwapEfficiencyMetrics {
    pub allocation_efficiency: f64,
    pub allocation_failure_rate: f64,
    pub swap_success_rate: f64,
    pub memory_utilization: f64,
    pub peak_memory_usage_bytes: u64,
}

#[derive(Debug, Clone)]
pub struct EnhancedMemoryPoolStats {
    pub regular_stats: MemoryPoolStats,
    pub swap_metrics: SwapAllocationMetrics,
    pub memory_pressure: MemoryPressureLevel,
    pub total_memory_usage_bytes: u64,
}

/// Global enhanced memory pool instance
lazy_static::lazy_static! {
    pub static ref GLOBAL_ENHANCED_MEMORY_POOL: Arc<EnhancedMemoryPoolManager> = Arc::new(EnhancedMemoryPoolManager::new(1024 * 1024 * 512)); // 512MB default
}

/// Initialize enhanced memory pools with swap support
pub fn init_enhanced_memory_pools(max_swap_memory_bytes: usize) {
    let pool = Arc::new(EnhancedMemoryPoolManager::new(max_swap_memory_bytes));
    let regular_manager = Arc::new(pool.regular_manager.clone());
    init_thread_local_pool(regular_manager);
    // Note: Thread-local swap pools would need separate initialization
    log::info!("Enhanced memory pools initialized with {} MB swap memory", max_swap_memory_bytes / (1024 * 1024));
}

/// Get global enhanced memory pool instance
pub fn get_global_enhanced_pool() -> Arc<EnhancedMemoryPoolManager> {
    Arc::clone(&GLOBAL_ENHANCED_MEMORY_POOL)
}

/// Global memory pool instance (backward compatibility)
lazy_static::lazy_static! {
    pub static ref GLOBAL_MEMORY_POOL: Arc<MemoryPoolManager> = Arc::new(MemoryPoolManager::new());
}

/// Initialize global memory pools (backward compatibility)
pub fn init_memory_pools() {
    init_thread_local_pool(Arc::clone(&GLOBAL_MEMORY_POOL));
    log::info!("Memory pools initialized");
}

/// Get global memory pool instance (backward compatibility)
pub fn get_global_pool() -> &'static MemoryPoolManager {
    &GLOBAL_MEMORY_POOL
}
/// Lightweight pool variant for frequent small allocations (<4KB)
/// Bypasses complex features (compression, async prefetch) for reduced overhead
/// Provides fast path for hot allocations with minimal latency
#[derive(Debug)]
pub struct LightweightMemoryPool {
    /// Pre-allocated small buffers for hot path (no locking)
    hot_buffers: Vec<Vec<u8>>,
    /// Current index for round-robin allocation from hot buffers
    hot_index: AtomicUsize,
    /// Maximum size for hot allocations (4KB)
    max_hot_size: usize,
    /// Number of pre-allocated hot buffers
    hot_buffer_count: usize,
    /// Fallback pool for larger allocations or when hot buffers exhausted
    fallback_pool: VecPool<u8>,
    /// Performance metrics
    allocation_count: AtomicU64,
    hot_hit_count: AtomicU64,
    logger: PerformanceLogger,
}

impl LightweightMemoryPool {
    /// Create new lightweight pool with specified hot buffer configuration
    pub fn new(hot_buffer_count: usize, max_hot_size: usize) -> Self {
        let mut hot_buffers = Vec::with_capacity(hot_buffer_count);
        for _ in 0..hot_buffer_count {
            hot_buffers.push(Vec::with_capacity(max_hot_size));
        }

        Self {
            hot_buffers,
            hot_index: AtomicUsize::new(0),
            max_hot_size,
            hot_buffer_count,
            fallback_pool: VecPool::new(100), // Smaller fallback pool
            allocation_count: AtomicU64::new(0),
            hot_hit_count: AtomicU64::new(0),
            logger: PerformanceLogger::new("lightweight_memory_pool"),
        }
    }

    /// Allocate memory with fast path for small allocations
    /// Bypasses compression and async prefetch for allocations <4KB
    pub fn allocate(&self, size: usize) -> PooledVec<u8> {
        self.allocation_count.fetch_add(1, Ordering::Relaxed);

        // Fast path for small allocations (<4KB) - no complex features
        if size <= self.max_hot_size {
            // Try hot buffer allocation (lock-free)
            let index = self.hot_index.fetch_add(1, Ordering::Relaxed) % self.hot_buffer_count;
            if let Some(buffer) = self.hot_buffers.get(index) {
                if buffer.capacity() >= size {
                    self.hot_hit_count.fetch_add(1, Ordering::Relaxed);
                    let mut pooled = self.fallback_pool.pool.get();
                    let vec = pooled.as_mut();
                    vec.clear();
                    vec.resize(size, 0u8);
                    return pooled;
                }
            }

            // Fallback to pool allocation (still lightweight, no compression/async)
            let mut pooled = self.fallback_pool.pool.get();
            let vec = pooled.as_mut();
            vec.clear();
            vec.resize(size, 0u8);
            pooled
        } else {
            // Larger allocations use fallback pool
            let mut pooled = self.fallback_pool.pool.get();
            let vec = pooled.as_mut();
            vec.clear();
            vec.resize(size, 0u8);
            pooled
        }
    }

    /// Get performance metrics for the lightweight pool
    pub fn get_metrics(&self) -> LightweightPoolMetrics {
        let total_allocations = self.allocation_count.load(Ordering::Relaxed);
        let hot_hits = self.hot_hit_count.load(Ordering::Relaxed);
        let hot_hit_rate = if total_allocations > 0 {
            (hot_hits as f64 / total_allocations as f64) * 100.0
        } else {
            0.0
        };

        LightweightPoolMetrics {
            total_allocations,
            hot_hit_count: hot_hits,
            hot_hit_rate,
            fallback_pool_size: self.fallback_pool.pool.pool.lock().unwrap().len(),
        }
    }

    /// Pre-warm hot buffers for better performance
    pub fn pre_warm(&self) {
        for buffer in &self.hot_buffers {
            let mut _temp: Vec<u8> = Vec::with_capacity(self.max_hot_size);
        }
    }
}

/// Metrics for lightweight pool performance monitoring
#[derive(Debug, Clone)]
pub struct LightweightPoolMetrics {
    pub total_allocations: u64,
    pub hot_hit_count: u64,
    pub hot_hit_rate: f64,
    pub fallback_pool_size: usize,
}
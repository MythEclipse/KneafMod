use std::collections::{BTreeMap, HashMap};
use std::fmt::Debug;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::thread;
use std::time::{Duration, Instant};
use std::time::{SystemTime, UNIX_EPOCH};

use crossbeam_utils::CachePadded;

use crate::logging::{generate_trace_id, PerformanceLogger};
use crate::memory_pool::atomic_state::AtomicPoolState;
use crate::memory_pressure_config::GLOBAL_MEMORY_PRESSURE_CONFIG;

/// Enhanced wrapper for pooled objects with LRU access tracking and swap tracking
#[allow(dead_code)]
#[derive(Debug)]
pub struct PooledObject<T> {
    pub object: Option<T>,
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

            // Use a more robust locking strategy to prevent race conditions
            // Try to acquire both locks with timeout to prevent deadlock
            let pool_lock_result = self.pool.try_lock();
            let access_order_lock_result = self.access_order.try_lock();

            match (pool_lock_result, access_order_lock_result) {
                (Ok(mut pool_guard), Ok(mut access_order_guard)) => {
                    // Both locks acquired successfully - proceed with object return
                    let current_pool_size = pool_guard.len();
                    let usage_ratio = current_pool_size as f64 / self.max_size as f64;

                    // Use a more robust approach to handle pool capacity issues
                    let object_returned = if usage_ratio < 0.80 {
                        // Reduced threshold from 95% to 80%
                        if current_pool_size < self.max_size {
                            // Safe to add directly - no eviction needed
                            pool_guard.insert(self.id, (obj, now));
                            access_order_guard.insert(now, self.id);
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    };

                    if !object_returned {
                        log::debug!("Object not returned to pool due to high memory pressure");
                        
                        // Track potential memory leak
                        static LEAK_COUNTER: AtomicUsize = AtomicUsize::new(0);
                        static LAST_LEAK_CHECK: AtomicUsize = AtomicUsize::new(0);
                        
                        let current_time = SystemTime::now()
                            .duration_since(UNIX_EPOCH)
                            .unwrap_or_default()
                            .as_secs() as usize;
                        
                        // Check for memory leaks every 30 seconds
                        let last_check = LAST_LEAK_CHECK.load(Ordering::Relaxed);
                        if current_time - last_check > 30 {
                            let leak_count = LEAK_COUNTER.load(Ordering::Relaxed);
                            if leak_count > 0 {
                                log::warn!(
                                    "Memory leak detected: {} objects not returned to pool in last 30 seconds",
                                    leak_count
                                );
                                LEAK_COUNTER.store(0, Ordering::Relaxed);
                            }
                            LAST_LEAK_CHECK.store(current_time, Ordering::Relaxed);
                        }
                        
                        LEAK_COUNTER.fetch_add(1, Ordering::Relaxed);
                    }
                }
                (Ok(_), Err(_)) => {
                    // Pool lock acquired but access order lock failed
                    log::warn!("Failed to acquire access order lock - object not returned to pool (race condition prevention)");
                }
                (Err(_), Ok(_)) => {
                    // Access order lock acquired but pool lock failed
                    log::warn!("Failed to acquire pool lock - object not returned to pool (race condition prevention)");
                }
                (Err(_), Err(_)) => {
                    // Both locks failed
                    log::warn!("Failed to acquire both locks - object not returned to pool (race condition prevention)");
                }
            }

            // NOTE: deallocation accounting is handled via allocation trackers
        }
    }
}

// Add background cleanup thread for pool maintenance
use lazy_static::lazy_static;

lazy_static! {
    static ref POOL_CLEANUP_THREAD: Arc<Mutex<Option<thread::JoinHandle<()>>>> =
        Arc::new(Mutex::new(None));
}

pub fn start_pool_cleanup_thread(pool: Arc<ObjectPool<u8>>, interval: Duration) {
    let pool = Arc::clone(&pool);
    let handle = thread::spawn(move || {
        loop {
            // Sleep for the configured interval between cleanup runs
            thread::sleep(interval);

            let now = Instant::now();
            let result = pool.lazy_cleanup(0.9); // Cleanup when usage exceeds 90%
            let elapsed = now.elapsed();

            if result {
                log::info!("Pool cleanup completed in {:?}", elapsed);
            }
        }
    });

    let mut thread_guard = POOL_CLEANUP_THREAD.lock().unwrap();
    *thread_guard = Some(handle);
}

/// Lock-free object pool for memory reuse with LRU eviction using crossbeam::epoch
#[derive(Debug)]
pub struct ObjectPool<T>
where
    T: Debug,
{
    // Thread-safe data structures using Mutex
    pub pool: Arc<Mutex<HashMap<u64, (T, SystemTime)>>>,
    pub access_order: Arc<Mutex<BTreeMap<SystemTime, u64>>>,
    next_id: CachePadded<AtomicU64>,
    max_size: usize,
    logger: PerformanceLogger,
    high_water_mark: CachePadded<AtomicUsize>,
    allocation_count: CachePadded<AtomicUsize>,
    last_cleanup_time: CachePadded<AtomicUsize>,
    is_monitoring: AtomicBool,
    shutdown_flag: Arc<AtomicBool>,
    pressure_monitor: Arc<RwLock<MemoryPressureMonitor>>,
    atomic_state: Arc<AtomicPoolState>, // Thread-safe pool state for race condition prevention
}

impl<T> ObjectPool<T>
where
    T: Default + Debug + Send + 'static,
{
    pub fn new(max_size: usize) -> Self {
        Self {
            pool: Arc::new(Mutex::new(HashMap::new())),
            access_order: Arc::new(Mutex::new(BTreeMap::new())),
            next_id: CachePadded::new(AtomicU64::new(0)),
            max_size,
            logger: PerformanceLogger::new("memory_pool"),
            high_water_mark: CachePadded::new(AtomicUsize::new(0)),
            allocation_count: CachePadded::new(AtomicUsize::new(0)),
            last_cleanup_time: CachePadded::new(AtomicUsize::new(
                SystemTime::now()
                    .duration_since(UNIX_EPOCH)
                    .unwrap_or_default()
                    .as_secs() as usize,
            )),
            is_monitoring: AtomicBool::new(false),
            shutdown_flag: Arc::new(AtomicBool::new(false)),
            pressure_monitor: Arc::new(RwLock::new(MemoryPressureMonitor::new())),
            atomic_state: Arc::new(AtomicPoolState::new(max_size)),
        }
    }

    // Manual Clone implementation because atomics do not implement Clone
    pub fn clone_shallow(&self) -> Self {
        Self {
            pool: Arc::clone(&self.pool),
            access_order: Arc::clone(&self.access_order),
            next_id: CachePadded::new(AtomicU64::new(self.next_id.load(Ordering::Relaxed))),
            max_size: self.max_size,
            logger: self.logger.clone(),
            high_water_mark: CachePadded::new(AtomicUsize::new(
                self.high_water_mark.load(Ordering::Relaxed),
            )),
            allocation_count: CachePadded::new(AtomicUsize::new(
                self.allocation_count.load(Ordering::Relaxed),
            )),
            last_cleanup_time: CachePadded::new(AtomicUsize::new(
                self.last_cleanup_time.load(Ordering::Relaxed),
            )),
            is_monitoring: AtomicBool::new(self.is_monitoring.load(Ordering::Relaxed)),
            shutdown_flag: Arc::clone(&self.shutdown_flag),
            pressure_monitor: Arc::clone(&self.pressure_monitor),
            atomic_state: Arc::clone(&self.atomic_state),
        }
    }

    /// Get an object from the pool using thread-safe operations
    pub fn get(&self) -> PooledObject<T> {
        let trace_id = generate_trace_id();

        // Perform lazy cleanup before allocation if needed
        self.lazy_cleanup(0.9); // Cleanup when usage exceeds 90%

        let obj = self.get_lockfree().unwrap_or_else(|| {
            self.logger.log_operation("pool_miss", &trace_id, || {
                log::debug!(
                    "Pool miss for type {}, creating new object",
                    std::any::type_name::<T>()
                );
                T::default()
            })
        });

        // Record allocation for monitoring
        self.record_allocation();

        let next_id = self.next_id.fetch_add(1, Ordering::Relaxed);

        PooledObject {
            object: Some(obj),
            pool: Arc::clone(&self.pool),
            access_order: Arc::clone(&self.access_order),
            id: next_id,
            max_size: self.max_size,
            size_bytes: None,
            allocation_tracker: None,
            allocation_type: None,
            is_critical_operation: false,
        }
    }

    /// Thread-safe object retrieval using Mutex with deadlock prevention
    fn get_lockfree(&self) -> Option<T> {
        // Use consistent lock ordering to prevent deadlocks: pool first, then access_order
        let mut pool_guard = self.pool.lock().unwrap();
        
        // Get the LRU item from the pool directly to avoid double-locking
        if let Some((lru_time, lru_id)) = {
            let access_order_guard = self.access_order.lock().unwrap();
            access_order_guard.iter().next().map(|(&t, &id)| (t, id))
        } {
            // Remove from access order (separate lock scope)
            {
                let mut access_order_guard = self.access_order.lock().unwrap();
                access_order_guard.remove(&lru_time);
            }
            
            // Remove from pool and get the object
            if let Some((obj, _)) = pool_guard.remove(&lru_id) {
                return Some(obj);
            }
        }
        None
    }

    /// Get an object from the pool with swap tracking and deadlock prevention
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

        // Use consistent lock ordering to prevent deadlocks
        let obj = {
            let mut pool_guard = self.pool.lock().unwrap();
            
            // Get LRU item with separate lock scope to avoid deadlock
            let lru_info = {
                let access_order_guard = self.access_order.lock().unwrap();
                access_order_guard.iter().next().map(|(&t, &id)| (t, id))
            };
            
            if let Some((lru_time, lru_id)) = lru_info {
                // Remove from access order (separate lock scope)
                {
                    let mut access_order_guard = self.access_order.lock().unwrap();
                    access_order_guard.remove(&lru_time);
                }
                
                // Remove from pool and get the object
                pool_guard.remove(&lru_id).map(|(obj, _)| obj).unwrap()
            } else {
                self.logger
                    .log_operation("pool_miss_tracked", &trace_id, || {
                        log::debug!(
                            "Pool miss for tracked type {}, creating new object",
                            std::any::type_name::<T>()
                        );
                        T::default()
                    })
            }
        };

        // Record allocation for monitoring
        self.record_allocation();
        let _count = self.allocation_count.fetch_add(1, Ordering::Relaxed);

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
        self.logger
            .log_operation("get_monitoring_stats", &trace_id, || {
                let pool_len = self.pool.lock().unwrap().len();
                let usage_ratio = self.atomic_state.get_usage_ratio();
                ObjectPoolMonitoringStats {
                    available_objects: pool_len,
                    max_size: self.max_size,
                    high_water_mark: self.high_water_mark.load(Ordering::Relaxed),
                    allocation_count: self.allocation_count.load(Ordering::Relaxed),
                    current_usage_ratio: usage_ratio,
                    last_cleanup_time: self.last_cleanup_time.load(Ordering::Relaxed),
                }
            })
    }

    /// Get current pool size atomically
    pub fn get_current_size(&self) -> usize {
        self.atomic_state.get_current_size()
    }

    /// Get high water mark atomically
    pub fn get_high_water_mark(&self) -> usize {
        self.high_water_mark.load(Ordering::Relaxed)
    }

    /// Get current usage ratio atomically
    pub fn get_usage_ratio(&self) -> f64 {
        self.atomic_state.get_usage_ratio()
    }

    /// Try to increment pool size atomically (thread-safe)
    pub fn try_increment_pool_size(&self) -> Option<usize> {
        self.atomic_state.try_increment_size()
    }

    /// Decrement pool size atomically (thread-safe)
    pub fn decrement_pool_size(&self) {
        self.atomic_state.decrement_size();
    }

    /// Record allocation for monitoring with atomic state updates (thread-safe version)
    fn record_allocation_lockfree(&self) {
        let _count = self.allocation_count.fetch_add(1, Ordering::Relaxed);

        // Get current pool size thread-safely
        let pool_guard = self.pool.lock().unwrap();
        let current = pool_guard.len();

        if current > self.high_water_mark.load(Ordering::Relaxed) {
            self.high_water_mark.store(current, Ordering::Relaxed);
        }
        // Update atomic state to reflect current pool size
        self.atomic_state.update_usage_ratio(current);
    }

    /// Record allocation for monitoring with atomic state updates (thread-safe version)
    fn record_allocation(&self) {
        self.record_allocation_lockfree();
    }

    /// Record deallocation for monitoring with atomic state updates
    #[allow(dead_code)]
    fn record_deallocation(&self) {
        self.allocation_count.fetch_sub(1, Ordering::Relaxed);
        // Update atomic state to reflect current pool size
        let current = self.pool.lock().unwrap().len();
        self.atomic_state.update_usage_ratio(current);
    }

    /// Perform lazy cleanup when pool usage exceeds threshold with deadlock prevention
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

        // Use consistent lock ordering to prevent deadlocks
        let (usage_ratio, initial_pool_size) = {
            let pool_guard = self.pool.lock().unwrap();
            (pool_guard.len() as f64 / self.max_size as f64, pool_guard.len())
        };

        if usage_ratio > threshold {
            self.logger.log_info(
                "lazy_cleanup",
                &trace_id,
                &format!("Performing lazy cleanup - usage ratio: {:.2}", usage_ratio),
            );

            // Remove excess objects: evict LRU objects beyond high water mark
            let target_size = (self.high_water_mark.load(Ordering::Relaxed) as f64 * 0.7) as usize;
            let cleanup_start_time = SystemTime::now();
            const MAX_ITERATIONS: usize = 1000; // Prevent infinite loops
            const MAX_CLEANUP_TIME_MS: u128 = 100; // 100ms timeout
            let mut iterations = 0;
            let mut objects_removed = 0;

            // Use consistent lock ordering: pool first, then access_order
            while {
                let pool_guard = self.pool.lock().unwrap();
                pool_guard.len() > target_size && pool_guard.len() > 5
            } && iterations < MAX_ITERATIONS
            {
                // Keep at least 5 objects
                // Check timeout
                if cleanup_start_time.elapsed().unwrap_or_default().as_millis()
                    > MAX_CLEANUP_TIME_MS
                {
                    self.logger.log_warning(
                        "lazy_cleanup",
                        &trace_id,
                        &format!(
                            "Cleanup timed out after {}ms, removed {} objects",
                            MAX_CLEANUP_TIME_MS, objects_removed
                        ),
                    );
                    break;
                }

                // Get LRU item with separate lock scope
                let lru_info = {
                    let access_order_guard = self.access_order.lock().unwrap();
                    access_order_guard.iter().next().map(|(&t, &id)| (t, id))
                };

                if let Some((lru_time, lru_id)) = lru_info {
                    // Remove from access order (separate lock scope)
                    {
                        let mut access_order_guard = self.access_order.lock().unwrap();
                        access_order_guard.remove(&lru_time);
                    }
                    
                    // Remove from pool (separate lock scope)
                    {
                        let mut pool_guard = self.pool.lock().unwrap();
                        if pool_guard.remove(&lru_id).is_some() {
                            objects_removed += 1;
                        }
                    }
                    iterations += 1;
                } else {
                    // No more LRU items to remove, break to prevent infinite loop
                    break;
                }

                // Safety check: if we've iterated too many times without progress, break
                if iterations > 0 && iterations % 100 == 0 && objects_removed == 0 {
                    self.logger.log_warning(
                        "lazy_cleanup",
                        &trace_id,
                        "No progress made in cleanup loop, breaking to prevent infinite loop",
                    );
                    break;
                }
            }

            self.last_cleanup_time
                .store(current_time, Ordering::Relaxed);
            
            let final_pool_size = {
                let pool_guard = self.pool.lock().unwrap();
                pool_guard.len()
            };
            
            self.logger.log_info(
                "lazy_cleanup",
                &trace_id,
                &format!(
                    "Reduced pool from {} to {} (removed {} objects, {} iterations)",
                    initial_pool_size, final_pool_size, objects_removed, iterations
                ),
            );
            return objects_removed > 0;
        }

        false
    }
}

// (imports consolidated at the top of the file)

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

        if usage_ratio < 0.3 {
            // If usage is below 30%, reset high water mark
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

impl Default for SwapAllocationMetrics {
    fn default() -> Self {
        Self::new()
    }
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MemoryPressureLevel {
    Normal,
    Low,
    Moderate,
    High,
    Critical,
}

impl MemoryPressureLevel {
    pub fn from_usage_ratio(usage_ratio: f64) -> Self {
        if let Ok(config) = GLOBAL_MEMORY_PRESSURE_CONFIG.read() {
            match usage_ratio {
                r if r < config.normal_threshold => MemoryPressureLevel::Normal,
                r if r < config.moderate_threshold => MemoryPressureLevel::Moderate,
                r if r < config.high_threshold => MemoryPressureLevel::High,
                _ => MemoryPressureLevel::Critical,
            }
        } else {
            // Fallback to default values if config is unavailable
            match usage_ratio {
                r if r < 0.7 => MemoryPressureLevel::Normal,
                r if r < 0.85 => MemoryPressureLevel::Moderate,
                r if r < 0.95 => MemoryPressureLevel::High,
                _ => MemoryPressureLevel::Critical,
            }
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
            if now
                .duration_since(checks[0].0)
                .unwrap_or(Duration::from_secs(61))
                .as_secs()
                > 60
            {
                checks.remove(0);
            } else {
                break;
            }
        }
    }

    pub fn should_log(&self, _pressure: MemoryPressureLevel) -> bool {
        let now = SystemTime::now();
        let mut last_log = self.last_log_time.lock().unwrap();

        if now
            .duration_since(*last_log)
            .unwrap_or(Duration::from_secs(0))
            .as_secs()
            > 60
        {
            *last_log = now;
            return true;
        }

        false
    }
}

impl Default for MemoryPressureMonitor {
    fn default() -> Self {
        Self::new()
    }
}
pub struct ObjectPoolMonitoringStats {
    pub available_objects: usize,
    pub max_size: usize,
    pub high_water_mark: usize,
    pub allocation_count: usize,
    pub current_usage_ratio: f64,
    pub last_cleanup_time: usize,
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

// Finalized imports consolidated at top of file.

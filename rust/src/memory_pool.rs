use std::collections::VecDeque;
use std::sync::{Arc, Mutex, RwLock};
use std::fmt::Debug;
use std::time::{SystemTime, UNIX_EPOCH};
use crate::logging::{PerformanceLogger, generate_trace_id};

/// Generic object pool for memory reuse
#[derive(Clone)]
pub struct ObjectPool<T> {
    pool: Arc<Mutex<VecDeque<T>>>,
    max_size: usize,
    logger: PerformanceLogger,
}

impl<T> ObjectPool<T>
where
    T: Default + Debug,
{
    pub fn new(max_size: usize) -> Self {
        Self {
            pool: Arc::new(Mutex::new(VecDeque::with_capacity(max_size))),
            max_size,
            logger: PerformanceLogger::new("memory_pool"),
        }
    }

    /// Get an object from the pool, creating a new one if none available
    pub fn get(&self) -> PooledObject<T> {
        let trace_id = generate_trace_id();
        let mut guard = self.pool.lock().unwrap();
        let obj = guard.pop_front().unwrap_or_else(|| {
            self.logger.log_operation("pool_miss", &trace_id, || {
                log::debug!("Pool miss for type {}, creating new object", std::any::type_name::<T>());
                T::default()
            })
        });

        // Clone the Arc to the pool so the PooledObject can return the object on drop
        let pool_arc = Arc::clone(&self.pool);

        PooledObject {
            object: Some(obj),
            pool: pool_arc,
            max_size: self.max_size,
            size_bytes: None,
            allocation_tracker: None,
            allocation_type: None,
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
        
        let mut guard = self.pool.lock().unwrap();
        let obj = guard.pop_front().unwrap_or_else(|| {
            self.logger.log_operation("pool_miss_tracked", &trace_id, || {
                log::debug!("Pool miss for tracked type {}, creating new object", std::any::type_name::<T>());
                T::default()
            })
        });

        let pool_arc = Arc::clone(&self.pool);

        PooledObject {
            object: Some(obj),
            pool: pool_arc,
            max_size: self.max_size,
            size_bytes: Some(size_bytes),
            allocation_tracker: Some(allocation_tracker),
            allocation_type: Some(allocation_type.to_string()),
        }
    }

}

/// Enhanced wrapper for pooled objects with swap tracking
pub struct PooledObject<T> {
    object: Option<T>,
    pool: Arc<Mutex<VecDeque<T>>>,
    max_size: usize,
    size_bytes: Option<u64>,
    allocation_tracker: Option<Arc<RwLock<SwapAllocationMetrics>>>,
    allocation_type: Option<String>,
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

            let mut pool = self.pool.lock().unwrap();
            if pool.len() < self.max_size {
                pool.push_front(obj);
            }
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
#[derive(Clone)]
pub struct VecPool<T> {
    pool: ObjectPool<Vec<T>>,
}

impl<T: Debug> VecPool<T> {
    pub fn new(max_size: usize) -> Self {
        Self {
            pool: ObjectPool::new(max_size),
        }
    }

    pub fn get_vec(&self, capacity: usize) -> PooledVec<T> {
        let mut pooled = self.pool.get();
        pooled.clear();
        pooled.reserve(capacity);
        // Ensure the vector actually has the requested capacity
        if pooled.capacity() < capacity {
            *pooled = Vec::with_capacity(capacity);
        }
        pooled
    }
}

pub type PooledVec<T> = PooledObject<Vec<T>>;

/// Specialized pool for strings
#[derive(Clone)]
pub struct StringPool {
    pool: ObjectPool<String>,
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
        }
    }

    pub fn record_allocation(&mut self, size_bytes: u64, allocation_type: &str) {
        self.total_allocations += 1;
        self.current_usage_bytes += size_bytes;
        if self.current_usage_bytes > self.peak_usage_bytes {
            self.peak_usage_bytes = self.current_usage_bytes;
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
}

impl SwapMemoryPool {
    pub fn new(max_memory_bytes: usize) -> Self {
        Self {
            chunk_metadata_pool: VecPool::new(1000),
            compressed_data_pool: VecPool::new(500),
            temporary_buffer_pool: VecPool::new(200),
            metrics: Arc::new(RwLock::new(SwapAllocationMetrics::new())),
            logger: PerformanceLogger::new("swap_memory_pool"),
            max_memory_bytes,
            pressure_threshold: 0.8,
            resize_factor: 0.75,
        }
    }

    /// Allocate memory for chunk metadata with tracking
    pub fn allocate_chunk_metadata(&self, size: usize) -> Result<PooledVec<u8>, String> {
        let trace_id = generate_trace_id();
        self.logger.log_operation("allocate_chunk_metadata", &trace_id, || {
            // Check memory pressure before allocation
            if let Err(e) = self.check_memory_pressure() {
                if let Ok(mut metrics) = self.metrics.write() {
                    metrics.record_allocation_failure();
                }
                return Err(e);
            }

            let pooled = self.chunk_metadata_pool.pool.get_with_tracking(
                size as u64,
                Arc::clone(&self.metrics),
                "chunk_metadata"
            );

            Ok(pooled)
        })
    }

    /// Allocate memory for compressed data with tracking
    pub fn allocate_compressed_data(&self, size: usize) -> Result<PooledVec<u8>, String> {
        let trace_id = generate_trace_id();
        self.logger.log_operation("allocate_compressed_data", &trace_id, || {
            // Check memory pressure before allocation
            if let Err(e) = self.check_memory_pressure() {
                if let Ok(mut metrics) = self.metrics.write() {
                    metrics.record_allocation_failure();
                }
                return Err(e);
            }

            let pooled = self.compressed_data_pool.pool.get_with_tracking(
                size as u64,
                Arc::clone(&self.metrics),
                "compressed_data"
            );

            Ok(pooled)
        })
    }

    /// Allocate memory for temporary buffers with tracking
    pub fn allocate_temporary_buffer(&self, size: usize) -> Result<PooledVec<u8>, String> {
        let trace_id = generate_trace_id();
        self.logger.log_operation("allocate_temporary_buffer", &trace_id, || {
            // Check memory pressure before allocation
            if let Err(e) = self.check_memory_pressure() {
                if let Ok(mut metrics) = self.metrics.write() {
                    metrics.record_allocation_failure();
                }
                return Err(e);
            }

            let pooled = self.temporary_buffer_pool.pool.get_with_tracking(
                size as u64,
                Arc::clone(&self.metrics),
                "temporary_buffer"
            );

            Ok(pooled)
        })
    }

    /// Check current memory pressure level
    pub fn get_memory_pressure(&self) -> MemoryPressureLevel {
        if let Ok(metrics) = self.metrics.read() {
            let usage_ratio = metrics.current_usage_bytes as f64 / self.max_memory_bytes as f64;
            MemoryPressureLevel::from_usage_ratio(usage_ratio)
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
                Err("Critical memory pressure - allocation denied".to_string())
            },
        }
    }

    /// Perform light cleanup (reduce pool sizes)
    pub fn perform_light_cleanup(&self) {
        let trace_id = generate_trace_id();
        self.logger.log_info("light_cleanup", &trace_id, "Performing light memory cleanup");
        
        // Reduce pool sizes by removing excess objects
        if let Ok(mut pool) = self.chunk_metadata_pool.pool.pool.lock() {
            let current_size = pool.len();
            let target_size = (current_size as f64 * 0.8) as usize;
            while pool.len() > target_size && pool.len() > 10 {
                pool.pop_back();
            }
            self.logger.log_info("light_cleanup", &trace_id, &format!("Reduced chunk metadata pool from {} to {}", current_size, pool.len()));
        }

        if let Ok(mut pool) = self.compressed_data_pool.pool.pool.lock() {
            let current_size = pool.len();
            let target_size = (current_size as f64 * 0.8) as usize;
            while pool.len() > target_size && pool.len() > 5 {
                pool.pop_back();
            }
            self.logger.log_info("light_cleanup", &trace_id, &format!("Reduced compressed data pool from {} to {}", current_size, pool.len()));
        }

        if let Ok(mut pool) = self.temporary_buffer_pool.pool.pool.lock() {
            let current_size = pool.len();
            let target_size = (current_size as f64 * 0.8) as usize;
            while pool.len() > target_size && pool.len() > 2 {
                pool.pop_back();
            }
            self.logger.log_info("light_cleanup", &trace_id, &format!("Reduced temporary buffer pool from {} to {}", current_size, pool.len()));
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

    /// Record swap operation result
    pub fn record_swap_operation(&self, success: bool) {
        if let Ok(mut metrics) = self.metrics.write() {
            metrics.record_swap_operation(success);
        }
    }
}

/// Enhanced memory pool manager with swap support
pub struct EnhancedMemoryPoolManager {
    regular_manager: MemoryPoolManager,
    swap_pool: Arc<SwapMemoryPool>,
    logger: PerformanceLogger,
}

impl EnhancedMemoryPoolManager {
    pub fn new(max_swap_memory_bytes: usize) -> Self {
        Self {
            regular_manager: MemoryPoolManager::new(),
            swap_pool: Arc::new(SwapMemoryPool::new(max_swap_memory_bytes)),
            logger: PerformanceLogger::new("enhanced_memory_pool_manager"),
        }
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
        self.swap_pool.record_swap_operation(success);
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
pub fn get_global_enhanced_pool() -> &'static EnhancedMemoryPoolManager {
    &GLOBAL_ENHANCED_MEMORY_POOL
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
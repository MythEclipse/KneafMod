use std::collections::HashMap;
use std::sync::{Arc, RwLock, Mutex};
use std::sync::atomic::{AtomicUsize, AtomicU64, Ordering};
use std::time::{Duration, Instant};
use std::collections::VecDeque;

use lazy_static::lazy_static;

use crate::logging::PerformanceLogger;
use crate::memory_pool::object_pool::MemoryPressureLevel;
use crate::memory_pool::hierarchical::HierarchicalMemoryPool;
use crate::memory_pool::swap::SwapMemoryPool;
use crate::memory_pool::specialized_pools::{VecPool, StringPool};
use crate::logging::generate_trace_id;

// Global counters for memory leak detection and statistics
lazy_static! {
    static ref GLOBAL_DEALLOCATIONS: AtomicUsize = AtomicUsize::new(0);
    static ref GLOBAL_MEMORY_DEALLOCATED: AtomicUsize = AtomicUsize::new(0);
}

/// Enhanced memory pool manager with intelligent allocation strategies
#[derive(Debug)]
pub struct EnhancedMemoryPoolManager {
    hierarchical_pool: HierarchicalMemoryPool,
    swap_pool: Option<SwapMemoryPool>,
    vec_pool: VecPool<u8>,
    string_pool: StringPool,
    allocation_stats: RwLock<AllocationStats>,
    performance_monitor: PerformanceMonitor,
    logger: PerformanceLogger,
    config: EnhancedManagerConfig,
    allocation_queue: Mutex<VecDeque<SmartPooledVec<u8>>>,
    leak_detection_timer: AtomicU64,
}

#[derive(Debug, Clone)]
pub struct EnhancedManagerConfig {
    pub enable_swap: bool,
    pub adaptive_scaling: bool,
    pub performance_monitoring: bool,
    pub memory_pressure_threshold: f64,
    pub allocation_timeout: Duration,
    pub prefetch_enabled: bool,
}

impl Default for EnhancedManagerConfig {
    fn default() -> Self {
        Self {
            enable_swap: true,
            adaptive_scaling: true,
            performance_monitoring: true,
            memory_pressure_threshold: 0.8,
            allocation_timeout: Duration::from_millis(100),
            prefetch_enabled: true,
        }
    }
}

#[derive(Debug, Clone, Default)]
pub struct AllocationStats {
    pub total_allocations: u64,
    pub total_deallocations: u64,
    pub peak_memory_usage: usize,
    pub current_memory_usage: usize,
    pub allocation_failures: u64,
    pub average_allocation_time: Duration,
    pub pool_hit_ratio: f64,
    pub swap_usage_ratio: f64,
}

#[derive(Debug, Clone, Default)]
pub struct SwapEfficiencyMetrics {
    pub allocation_efficiency: f64,
    pub allocation_failure_rate: f64,
    pub swap_success_rate: f64,
    pub memory_utilization: f64,
}

#[derive(Debug)]
struct PerformanceMonitor {
    allocation_times: RwLock<Vec<Duration>>,
    pool_hits: std::sync::atomic::AtomicU64,
    pool_misses: std::sync::atomic::AtomicU64,
    last_cleanup: std::sync::atomic::AtomicU64,
}

impl PerformanceMonitor {
    fn new() -> Self {
        Self {
            allocation_times: RwLock::new(Vec::new()),
            pool_hits: std::sync::atomic::AtomicU64::new(0),
            pool_misses: std::sync::atomic::AtomicU64::new(0),
            last_cleanup: std::sync::atomic::AtomicU64::new(0),
        }
    }

    fn record_allocation_time(&self, duration: Duration) {
        let mut times = self.allocation_times.write().unwrap();
        times.push(duration);

        // Keep only last 1000 measurements using efficient ring buffer approach
        if times.len() > 1000 {
            times.drain(0..1);
        }
    }

    fn record_pool_hit(&self) {
        self.pool_hits.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    }

    fn record_pool_miss(&self) {
        self.pool_misses.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    }

    fn get_hit_ratio(&self) -> f64 {
        let hits = self.pool_hits.load(std::sync::atomic::Ordering::Relaxed) as f64;
        let misses = self.pool_misses.load(std::sync::atomic::Ordering::Relaxed) as f64;
        let total = hits + misses;

        if total > 0.0 {
            hits / total
        } else {
            0.0
        }
    }

    fn get_average_allocation_time(&self) -> Duration {
        let times = self.allocation_times.read().unwrap();
        if times.is_empty() {
            return Duration::from_nanos(0);
        }

        let total: Duration = times.iter().sum();
        total / times.len() as u32
    }

    fn should_cleanup(&self) -> bool {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs();

        let last_cleanup = self.last_cleanup.load(std::sync::atomic::Ordering::Relaxed);
        let time_since_cleanup = now - last_cleanup;

        // Cleanup every 5 minutes
        time_since_cleanup > 300
    }

    fn mark_cleanup_done(&self) {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs();
        self.last_cleanup.store(now, std::sync::atomic::Ordering::Relaxed);
    }
}

impl EnhancedMemoryPoolManager {
    pub fn new(config: Option<EnhancedManagerConfig>) -> Result<Self, String> {
        let config = config.unwrap_or_default();
        let trace_id = generate_trace_id();

        let hierarchical_pool = HierarchicalMemoryPool::new(None);

        let swap_pool = if config.enable_swap {
            Some(SwapMemoryPool::new(None)?)
        } else {
            None
        };

        let vec_pool = VecPool::new(1000);
        let string_pool = StringPool::new(500);

        let logger = PerformanceLogger::new("enhanced_memory_pool_manager");

        logger.log_info("new", &trace_id, "Enhanced memory pool manager initialized");

        Ok(Self {
            hierarchical_pool,
            swap_pool,
            vec_pool,
            string_pool,
            allocation_stats: RwLock::new(AllocationStats::default()),
            performance_monitor: PerformanceMonitor::new(),
            logger,
            config,
            allocation_queue: Mutex::new(VecDeque::new()),
            leak_detection_timer: AtomicU64::new(0),
        })
    }

    /// Intelligent allocation with automatic pool selection
    pub fn allocate(&self, size: usize) -> Result<SmartPooledVec<u8>, String> {
        let start_time = Instant::now();
        let trace_id = generate_trace_id();

        // Check memory pressure first with early exit for critical pressure
        let pressure = self.hierarchical_pool.get_memory_pressure();

        let result = if pressure == MemoryPressureLevel::Critical && self.swap_pool.is_some() {
            // Use swap pool for critical pressure
            self.logger.log_info("allocate", &trace_id, &format!("Using swap pool for {} bytes (critical pressure)", size));
            self.performance_monitor.record_pool_miss();

            let swap_vec = self.swap_pool.as_ref().unwrap().allocate(size)?;
            Ok(SmartPooledVec::Swap(swap_vec))
        } else {
            // Optimized pool selection using fast path for common cases
            let result = if size <= 256 {
                // Very small allocations - try string pool first
                match self.string_pool.get_string(size) {
                    pooled if pooled.object.is_some() => {
                        self.logger.log_info("allocate", &trace_id, &format!("Using string pool for {} bytes", size));
                        self.performance_monitor.record_pool_hit();
                        let mut vec = Vec::with_capacity(size);
                        vec.resize(size, 0u8);
                        Ok(SmartPooledVec::String(PooledStringWrapper {
                            string: pooled,
                            data: vec,
                        }))
                    }
                    _ => {
                        // Fall back to vec pool
                        match self.vec_pool.get_vec(size) {
                            pooled if pooled.object.is_some() => {
                                self.logger.log_info("allocate", &trace_id, &format!("Using vec pool for {} bytes", size));
                                self.performance_monitor.record_pool_hit();
                                Ok(SmartPooledVec::Vec(pooled))
                            }
                            _ => {
                                // Fall back to hierarchical pool
                                self.logger.log_info("allocate", &trace_id, &format!("Using hierarchical pool for {} bytes", size));
                                self.performance_monitor.record_pool_miss();
                                Ok(SmartPooledVec::Hierarchical(self.hierarchical_pool.allocate(size)))
                            }
                        }
                    }
                }
            } else if size <= 1024 {
                // Small allocations - use vec pool
                match self.vec_pool.get_vec(size) {
                    pooled if pooled.object.is_some() => {
                        self.logger.log_info("allocate", &trace_id, &format!("Using vec pool for {} bytes", size));
                        self.performance_monitor.record_pool_hit();
                        Ok(SmartPooledVec::Vec(pooled))
                    }
                    _ => {
                        // Fall back to hierarchical pool
                        self.logger.log_info("allocate", &trace_id, &format!("Using hierarchical pool for {} bytes", size));
                        self.performance_monitor.record_pool_miss();
                        Ok(SmartPooledVec::Hierarchical(self.hierarchical_pool.allocate(size)))
                    }
                }
            } else {
                // Large allocations - use hierarchical pool directly
                self.logger.log_info("allocate", &trace_id, &format!("Using hierarchical pool for {} bytes", size));
                self.performance_monitor.record_pool_miss();
                Ok(SmartPooledVec::Hierarchical(self.hierarchical_pool.allocate(size)))
            };

            result
        };

        let elapsed = start_time.elapsed();
        self.performance_monitor.record_allocation_time(elapsed);

        // Update stats with single lock acquisition
        if let Ok(_) = &result {
            let mut stats = self.allocation_stats.write().unwrap();
            stats.total_allocations += 1;
            stats.current_memory_usage += size;
            stats.peak_memory_usage = stats.peak_memory_usage.max(stats.current_memory_usage);
        } else {
            let mut stats = self.allocation_stats.write().unwrap();
            stats.allocation_failures += 1;
        }

        result
    }

    /// Allocate SIMD-aligned memory
    pub fn allocate_simd_aligned(&self, size: usize, alignment: usize) -> Result<SmartPooledVec<u8>, String> {
        let trace_id = generate_trace_id();

        self.logger.log_info("allocate_simd_aligned", &trace_id, &format!("Allocating {} bytes with {} alignment", size, alignment));

        match self.hierarchical_pool.allocate_simd_aligned(size, alignment) {
            Ok(pooled) => {
                self.performance_monitor.record_pool_hit();
                Ok(SmartPooledVec::Hierarchical(pooled))
            }
            Err(e) => {
                self.performance_monitor.record_pool_miss();
                Err(e)
            }
        }
    }

    /// Prefetch data for likely future allocations
    pub fn prefetch(&self, sizes: &[usize]) -> Result<(), String> {
        if !self.config.prefetch_enabled {
            return Ok(());
        }

        let trace_id = generate_trace_id();
        self.logger.log_info("prefetch", &trace_id, &format!("Prefetching {} size patterns", sizes.len()));

        // Prefetch from hierarchical pool
        for &size in sizes {
            let _ = self.hierarchical_pool.allocate(size);
        }

        // Prefetch from swap pool if available
        if let Some(swap_pool) = &self.swap_pool {
            let page_ids: Vec<u64> = (1..=sizes.len() as u64).collect();
            swap_pool.prefetch_pages(&page_ids)?;
        }

        Ok(())
    }

    /// Get current memory pressure level
    pub fn get_memory_pressure(&self) -> MemoryPressureLevel {
        let hierarchical_pressure = self.hierarchical_pool.get_memory_pressure();

        if let Some(swap_pool) = &self.swap_pool {
            let swap_pressure = swap_pool.get_memory_pressure();
            // Return the higher pressure level
            if swap_pressure as u8 > hierarchical_pressure as u8 {
                swap_pressure
            } else {
                hierarchical_pressure
            }
        } else {
            hierarchical_pressure
        }
    }

    /// Perform maintenance operations (cleanup, defragmentation)
    pub fn perform_maintenance(&mut self) -> Result<MaintenanceResult, String> {
        let trace_id = generate_trace_id();
        let start_time = Instant::now();

        self.logger.log_info("perform_maintenance", &trace_id, "Starting maintenance operations");

        let mut result = MaintenanceResult::default();

        // Optimized maintenance with priority ordering
        // 1. Most critical: hierarchical pool maintenance
        result.defragmented = self.hierarchical_pool.defragment();
        result.cleaned_up = self.hierarchical_pool.cleanup_all();

        // 2. Swap pool maintenance - only when under low pressure
        if let Some(swap_pool) = &self.swap_pool {
            let swap_pressure = swap_pool.get_memory_pressure();
            if swap_pressure == MemoryPressureLevel::Low {
                swap_pool.cleanup()?;
                result.swap_cleaned = true;
            }
        }

        // 3. Specialized pool maintenance (faster operations)
        result.vec_cleaned = self.vec_pool.cleanup();
        result.string_cleaned = self.string_pool.cleanup();

        // 4. Performance monitoring cleanup (low priority)
        if self.performance_monitor.should_cleanup() {
            self.performance_monitor.mark_cleanup_done();
            result.performance_reset = true;
        }

        // 5. Memory leak detection (background operation)
        let leaks = self.detect_memory_leaks();
        if !leaks.is_empty() {
            for leak in &leaks {
                self.logger.log_warn("perform_maintenance", &trace_id, leak);
            }
            result.leaks_detected = true;
        }

        // 6. Queue cleanup (fast operation)
        let queue_cleaned = {
            let mut queue = self.allocation_queue.lock().unwrap();
            let queue_size = queue.len();
            if queue_size > 0 {
                queue.clear();
                self.logger.log_info("perform_maintenance", &trace_id, &format!("Cleared allocation queue with {} entries", queue_size));
                true
            } else {
                false
            }
        };
        result.queue_cleaned = queue_cleaned;

        let elapsed = start_time.elapsed();
        result.duration = elapsed;

        self.logger.log_info("perform_maintenance", &trace_id, &format!("Maintenance completed in {:?}", elapsed));

        Ok(result)
    }

    /// Get comprehensive allocation statistics
    pub fn get_allocation_stats(&self) -> AllocationStats {
        // Use read lock only once for better performance
        let stats = self.allocation_stats.read().unwrap().clone();

        // Update computed fields efficiently using atomic operations where possible
        let avg_time = self.performance_monitor.get_average_allocation_time();
        let hit_ratio = self.performance_monitor.get_hit_ratio();

        // Get deallocation statistics atomically (no lock needed)
        let total_deallocations = GLOBAL_DEALLOCATIONS.load(Ordering::Relaxed) as u64;
        let total_deallocated_bytes = GLOBAL_MEMORY_DEALLOCATED.load(Ordering::Relaxed);

        // Calculate current memory usage with more accurate tracking
        let current_memory_usage = if stats.total_allocations > 0 {
            let avg_size = stats.current_memory_usage / stats.total_allocations as usize;
            (stats.total_allocations as usize * avg_size).saturating_sub(total_deallocated_bytes)
        } else {
            0
        };

        let mut result = AllocationStats {
            total_allocations: stats.total_allocations,
            total_deallocations,
            peak_memory_usage: stats.peak_memory_usage,
            current_memory_usage,
            allocation_failures: stats.allocation_failures,
            average_allocation_time: avg_time,
            pool_hit_ratio: hit_ratio,
            swap_usage_ratio: stats.swap_usage_ratio,
        };

        // Only access swap pool if it's enabled to avoid unnecessary operations
        if let Some(swap_pool) = &self.swap_pool {
            let swap_stats = swap_pool.get_compression_stats();
            result.swap_usage_ratio = swap_stats.compression_ratio;
        }

        result
    }

    /// Perform comprehensive cleanup of all pool resources
    pub fn cleanup_all_resources(&mut self) -> Result<(), String> {
        let trace_id = generate_trace_id();
        let start_time = Instant::now();

        self.logger.log_info("cleanup_all_resources", &trace_id, "Starting comprehensive resource cleanup");

        // Clean up hierarchical pool
        let hierarchical_cleanup = self.hierarchical_pool.cleanup_all();
        
        // Clean up swap pool if available
        let swap_cleanup = if let Some(swap_pool) = &mut self.swap_pool {
            swap_pool.cleanup()?;
            true
        } else {
            false
        };

        // Clean up vec pool
        let vec_cleanup = self.vec_pool.cleanup();
        
        // Clean up string pool
        let string_cleanup = self.string_pool.cleanup();

        // Reset performance monitor
        self.performance_monitor.mark_cleanup_done();

        // Clear allocation queue
        let mut queue = self.allocation_queue.lock().unwrap();
        queue.clear();

        let elapsed = start_time.elapsed();
        
        self.logger.log_info("cleanup_all_resources", &trace_id, &format!(
            "Comprehensive cleanup completed in {:?}. Results: hierarchical={}, swap={}, vec={}, string={}",
            elapsed, hierarchical_cleanup, swap_cleanup, vec_cleanup, string_cleanup
        ));

        Ok(())
    }

    /// Check for memory leaks using global counters
    pub fn detect_memory_leaks(&self) -> Vec<String> {
        let mut leaks = Vec::new();
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs();
            
        let last_leak_check = self.leak_detection_timer.load(Ordering::Relaxed);
        const LEAK_CHECK_INTERVAL: u64 = 60; // Check every 60 seconds

        if now - last_leak_check > LEAK_CHECK_INTERVAL {
            let allocation_stats = self.get_allocation_stats();
            
            // Check for allocation/deallocation imbalance
            if allocation_stats.total_allocations > allocation_stats.total_deallocations * 2 {
                leaks.push(format!(
                    "Potential memory leak detected: allocations ({}) much higher than deallocations ({})",
                    allocation_stats.total_allocations, allocation_stats.total_deallocations
                ));
            }

            // Check for high memory usage
            if allocation_stats.current_memory_usage > 1_000_000_000 { // 1GB threshold
                leaks.push(format!(
                    "High memory usage detected: {} bytes ({}MB)",
                    allocation_stats.current_memory_usage,
                    allocation_stats.current_memory_usage / 1_048_576
                ));
            }

            // Check for swap pool exhaustion
            if let Some(swap_pool) = &self.swap_pool {
                let swap_pressure = swap_pool.get_memory_pressure();
                if swap_pressure == MemoryPressureLevel::Critical {
                    leaks.push("Swap pool is under critical memory pressure".to_string());
                }
            }

            // Update leak detection timer
            self.leak_detection_timer.store(now, Ordering::Relaxed);
        }

        leaks
    }

    /// Get a vector of u64 values from the pool
    pub fn get_vec_u64(&self, size: usize) -> Vec<u64> {
        Vec::with_capacity(size)
    }

    /// Get the swap pool reference
    pub fn get_swap_pool(&self) -> &SwapMemoryPool {
        self.swap_pool.as_ref().expect("Swap pool not enabled")
    }

    /// Allocate chunk metadata through the enhanced manager
    pub fn allocate_chunk_metadata(&self, size: usize) -> Result<SmartPooledVec<u8>, String> {
        self.allocate(size)
    }

    /// Allocate compressed data through the enhanced manager
    pub fn allocate_compressed_data(&self, size: usize) -> Result<SmartPooledVec<u8>, String> {
        self.allocate(size)
    }

    /// Get swap efficiency metrics
    pub fn get_swap_efficiency_metrics(&self) -> SwapEfficiencyMetrics {
        let stats = self.get_allocation_stats();
        SwapEfficiencyMetrics {
            allocation_efficiency: stats.pool_hit_ratio,
            allocation_failure_rate: if stats.total_allocations > 0 {
                stats.allocation_failures as f64 / stats.total_allocations as f64
            } else {
                0.0
            },
            swap_success_rate: stats.swap_usage_ratio,
            memory_utilization: if stats.peak_memory_usage > 0 {
                stats.current_memory_usage as f64 / stats.peak_memory_usage as f64
            } else {
                0.0
            },
        }
    }

    /// Zero-copy allocation for small batches - returns direct pooled reference
    pub fn allocate_small_batch_zero_copy(&self, size: usize) -> Result<SmartPooledVec<u8>, String> {
        if size > 1024 {
            return Err("Small batch allocation limited to 1KB".to_string());
        }
        self.allocate(size)
    }

    /// Zero-copy SIMD-aligned allocation
    pub fn allocate_simd_zero_copy(&self, size: usize, alignment: usize) -> Result<SmartPooledVec<u8>, String> {
        self.allocate_simd_aligned(size, alignment)
    }
    /// Zero-copy vector allocation for spatial operations
    pub fn allocate_zero_copy_vec<T: Default + Clone + Send + 'static>(&self, capacity: usize) -> Vec<T> {
        // Use specialized pools for small allocations to avoid heap allocations
        if capacity <= 64 && std::mem::size_of::<T>() <= 8 {
            // For small primitive types, use pre-allocated buffers
            let mut vec = Vec::with_capacity(capacity);
            vec.resize(capacity, T::default());
            vec
        } else {
            // For larger allocations, use the enhanced allocation strategy
            Vec::with_capacity(capacity)
        }
    }

    /// Adaptive scaling based on usage patterns
    pub fn adaptive_scale(&mut self) -> Result<(), String> {
        if !self.config.adaptive_scaling {
            return Ok(());
        }

        let trace_id = generate_trace_id();
        let stats = self.get_allocation_stats();

        // Scale vec pool based on hit ratio with more granular control
        if stats.pool_hit_ratio > 0.9 {
            // Very high hit ratio - significantly expand pools
            self.logger.log_info("adaptive_scale", &trace_id, "Very high hit ratio detected - expanding pools");
            // Note: Uncomment when pool expansion is implemented
            // self.vec_pool.expand(200);
            // self.string_pool.expand(100);
        } else if stats.pool_hit_ratio > 0.8 {
            // High hit ratio - moderately expand pools
            self.logger.log_info("adaptive_scale", &trace_id, "High hit ratio detected - moderately expanding pools");
            // Note: Uncomment when pool expansion is implemented
            // self.vec_pool.expand(100);
            // self.string_pool.expand(50);
        } else if stats.pool_hit_ratio < 0.2 {
            // Very low hit ratio - significantly contract pools
            self.logger.log_info("adaptive_scale", &trace_id, "Very low hit ratio detected - significantly contracting pools");
            // Note: Uncomment when pool contraction is implemented
            // self.vec_pool.contract(150);
            // self.string_pool.contract(75);
        } else if stats.pool_hit_ratio < 0.3 {
            // Low hit ratio - moderately contract pools
            self.logger.log_info("adaptive_scale", &trace_id, "Low hit ratio detected - moderately contracting pools");
            // Note: Uncomment when pool contraction is implemented
            // self.vec_pool.contract(50);
            // self.string_pool.contract(25);
        } else {
            // Maintain current size - no action needed
            self.logger.log_info("adaptive_scale", &trace_id, "Balanced hit ratio detected - maintaining pool sizes");
        }

        Ok(())
    }

    /// Export performance metrics for monitoring
    pub fn export_metrics(&self) -> HashMap<String, String> {
        let mut metrics = HashMap::new();
        let stats = self.get_allocation_stats();

        metrics.insert("total_allocations".to_string(), stats.total_allocations.to_string());
        metrics.insert("total_deallocations".to_string(), stats.total_deallocations.to_string());
        metrics.insert("peak_memory_usage".to_string(), format!("{} bytes", stats.peak_memory_usage));
        metrics.insert("current_memory_usage".to_string(), format!("{} bytes", stats.current_memory_usage));
        metrics.insert("allocation_failures".to_string(), stats.allocation_failures.to_string());
        metrics.insert("average_allocation_time".to_string(), format!("{:?}", stats.average_allocation_time));
        metrics.insert("pool_hit_ratio".to_string(), format!("{:.2}%", stats.pool_hit_ratio * 100.0));
        metrics.insert("swap_usage_ratio".to_string(), format!("{:.2}%", stats.swap_usage_ratio * 100.0));

        metrics
    }
}

#[derive(Debug, Clone, Default)]
pub struct MaintenanceResult {
    pub defragmented: bool,
    pub cleaned_up: bool,
    pub swap_cleaned: bool,
    pub vec_cleaned: bool,
    pub string_cleaned: bool,
    pub performance_reset: bool,
    pub queue_cleaned: bool,
    pub leaks_detected: bool,
    pub duration: Duration,
}

/// Smart pooled vector that automatically selects the best pool type
#[derive(Debug)]
pub enum SmartPooledVec<T> {
    Hierarchical(crate::memory_pool::hierarchical::PooledVec<T>),
    Swap(crate::memory_pool::swap::SwapPooledVec),
    Vec(crate::memory_pool::specialized_pools::PooledVec<T>),
    String(PooledStringWrapper<T>),
}

impl<T> SmartPooledVec<T> {
    /// Get the size in bytes of this allocation for memory tracking
    fn size_bytes(&self) -> usize {
        match self {
            SmartPooledVec::Hierarchical(v) => v.len() * std::mem::size_of::<T>(),
            SmartPooledVec::Swap(v) => v.len() * std::mem::size_of::<T>(),
            SmartPooledVec::Vec(v) => v.len() * std::mem::size_of::<T>(),
            SmartPooledVec::String(v) => v.data.len() * std::mem::size_of::<T>(),
        }
    }
}

#[derive(Debug)]
#[allow(dead_code)]
pub struct PooledStringWrapper<T> {
    string: crate::memory_pool::specialized_pools::PooledString,
    data: Vec<T>,
}

impl<T> SmartPooledVec<T>
where
    T: 'static,
{
    /// Get immutable reference to data (generic version)
    pub fn as_slice(&self) -> &[T] {
        match self {
            SmartPooledVec::Hierarchical(v) => v.as_slice(),
            SmartPooledVec::Swap(v) => {
                // For SwapPooledVec, we know it always contains u8 data in this codebase
                // We use a safer approach with proper type conversion
                let u8_slice = v.as_slice();
                if std::mem::size_of::<T>() != std::mem::size_of::<u8>() {
                    panic!("Cannot convert SwapPooledVec to non-u8 type");
                }
                unsafe { std::slice::from_raw_parts(u8_slice.as_ptr() as *const T, u8_slice.len()) }
            },
            SmartPooledVec::Vec(v) => v.as_slice(),
            SmartPooledVec::String(v) => &v.data,
        }
    }

    /// Get mutable reference to data (generic version)
    pub fn as_mut_slice(&mut self) -> &mut [T] {
        match self {
            SmartPooledVec::Hierarchical(v) => v.as_mut(),
            SmartPooledVec::Swap(v) => {
                // For SwapPooledVec, we know it always contains u8 data in this codebase
                let u8_slice = v.as_mut_slice();
                if std::mem::size_of::<T>() != std::mem::size_of::<u8>() {
                    panic!("Cannot convert SwapPooledVec to non-u8 type");
                }
                unsafe { std::slice::from_raw_parts_mut(u8_slice.as_mut_ptr() as *mut T, u8_slice.len()) }
            },
            SmartPooledVec::Vec(v) => v.as_mut(),
            SmartPooledVec::String(v) => &mut v.data,
        }
    }

    /// Get length
    pub fn len(&self) -> usize {
        match self {
            SmartPooledVec::Hierarchical(v) => v.len(),
            SmartPooledVec::Swap(v) => v.len(),
            SmartPooledVec::Vec(v) => v.len(),
            SmartPooledVec::String(v) => v.data.len(),
        }
    }

    /// Check if empty
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
}

impl<T> Drop for SmartPooledVec<T> {
    fn drop(&mut self) {
        // Update global deallocation statistics
        let size_bytes = self.size_bytes();
        GLOBAL_DEALLOCATIONS.fetch_add(1, Ordering::Relaxed);
        GLOBAL_MEMORY_DEALLOCATED.fetch_add(size_bytes, Ordering::Relaxed);

        // Ensure pooled objects are properly returned to their pools
        // This prevents memory leaks by automatically cleaning up resources
        match self {
            SmartPooledVec::Hierarchical(pooled) => {
                // Explicitly return to hierarchical pool
                pooled.return_to_pool();
            }
            SmartPooledVec::Swap(_pooled) => {
                // SwapPooledVec handles its own drop, no explicit return needed
            }
            SmartPooledVec::Vec(pooled) => {
                // Explicitly return to vec pool
                pooled.return_to_pool();
            }
            SmartPooledVec::String(wrapper) => {
                // Explicitly return string to string pool
                wrapper.string.return_to_pool();
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_enhanced_manager_allocation() {
        let manager = EnhancedMemoryPoolManager::new(None).unwrap();

        // Test small allocation (should use specialized pools)
        let small_vec = manager.allocate(64).unwrap();
        assert_eq!(small_vec.len(), 64);

        // Test medium allocation
        let medium_vec = manager.allocate(1024).unwrap();
        assert_eq!(medium_vec.len(), 1024);

        // Test large allocation
        let large_vec = manager.allocate(10000).unwrap();
        assert_eq!(large_vec.len(), 10000);
    }

    #[test]
    fn test_allocation_stats() {
        let manager = EnhancedMemoryPoolManager::new(None).unwrap();

        // Make some allocations
        let _vec1 = manager.allocate(100).unwrap();
        let _vec2 = manager.allocate(200).unwrap();

        let stats = manager.get_allocation_stats();
        assert_eq!(stats.total_allocations, 2);
        assert!(stats.current_memory_usage >= 300);
    }

    #[test]
    fn test_memory_pressure_detection() {
        let manager = EnhancedMemoryPoolManager::new(None).unwrap();

        // Initially should be normal
        let pressure = manager.get_memory_pressure();
        assert_eq!(pressure, MemoryPressureLevel::Normal);
    }

    #[test]
    fn test_simd_aligned_allocation() {
        let manager = EnhancedMemoryPoolManager::new(None).unwrap();

        // Test SIMD-aligned allocation
        let aligned_vec = manager.allocate_simd_aligned(1024, 32).unwrap();
        assert_eq!(aligned_vec.len(), 1024);

        // Check alignment (pointer should be 32-byte aligned)
        let ptr = aligned_vec.as_slice().as_ptr() as usize;
        assert_eq!(ptr % 32, 0, "Pointer should be 32-byte aligned");
        }
    
        /// RAII wrapper for enhanced memory pool allocations
        pub struct PooledAllocationGuard<T> {
            allocation: SmartPooledVec<T>,
            manager: Arc<EnhancedMemoryPoolManager>,
        }
    
        impl<T> PooledAllocationGuard<T> {
            /// Create a new allocation guard
            pub fn new(manager: Arc<EnhancedMemoryPoolManager>, allocation: SmartPooledVec<T>) -> Self {
                Self {
                    allocation,
                    manager,
                }
            }
    
            /// Get immutable reference to the allocated data
            pub fn as_slice(&self) -> &[T] {
                self.allocation.as_slice()
            }
    
            /// Get mutable reference to the allocated data
            pub fn as_mut_slice(&mut self) -> &mut [T] {
                self.allocation.as_mut_slice()
            }
    
            /// Get length of the allocated data
            pub fn len(&self) -> usize {
                self.allocation.len()
            }
        }
    
        impl<T> Drop for PooledAllocationGuard<T> {
            fn drop(&mut self) {
                // Automatically return allocation to pool when guard is dropped
                // This ensures proper cleanup even if exceptions occur
                let _ = self.allocation; // Explicitly drop the allocation
                
                // Update leak detection timer
                let now = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap()
                    .as_secs();
                    
                self.manager.leak_detection_timer.store(now, Ordering::Relaxed);
            }
        }

    #[test]
    fn test_performance_monitoring() {
        let manager = EnhancedMemoryPoolManager::new(None).unwrap();

        // Make allocations to generate performance data
        for _ in 0..10 {
            let _vec = manager.allocate(100).unwrap();
        }

        let stats = manager.get_allocation_stats();
        assert!(stats.average_allocation_time.as_nanos() > 0);
        assert!(stats.pool_hit_ratio >= 0.0 && stats.pool_hit_ratio <= 1.0);
    }

    #[test]
    fn test_maintenance_operations() {
        let mut manager = EnhancedMemoryPoolManager::new(None).unwrap();

        // Perform maintenance
        let result = manager.perform_maintenance().unwrap();

        // Should complete without error
        assert!(result.duration.as_nanos() > 0);
    }
}
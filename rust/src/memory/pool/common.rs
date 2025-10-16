//! Common types and traits for memory pool implementations

use std::fmt::Debug;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, RwLock};
use crate::{
    memory::pool::object_pool::{ObjectPoolMonitoringStats, SwapAllocationMetrics},
    PooledObject,
};

use crate::errors::{RustError, Result};
use crate::logging::PerformanceLogger;

/// Common trait for all memory pool implementations
pub trait MemoryPool: Debug + Send + Sync {
    /// Create a new memory pool with the given configuration
    fn new(config: MemoryPoolConfig) -> Self where Self: Sized;
    
    /// Allocate memory of the specified size
    fn allocate(&self, size: usize) -> Result<Box<[u8]>>;
     
    /// Deallocate memory
    fn deallocate(&self, ptr: *mut u8, size: usize);
     
    /// Get pool statistics
    fn get_stats(&self) -> MemoryPoolStats;
     
    /// Get current memory pressure level (0.0 to 1.0)
    fn get_memory_pressure(&self) -> f64;
    
    /// Update peak usage statistics
    fn update_peak_usage(&self) {
        // Default implementation - can be overridden by specific implementations
        let current_allocated = self.get_stats().allocated_bytes.load(Ordering::Relaxed);
        let peak_allocated = self.get_stats().peak_allocated_bytes.load(Ordering::Relaxed);
        
        if current_allocated > peak_allocated {
            self.get_stats().peak_allocated_bytes.store(current_allocated, Ordering::Relaxed);
        }
    }
    
    /// Create a shallow clone of the pool
    fn clone_shallow(&self) -> Self where Self: Sized {
        // Default implementation - can be overridden by specific implementations
        unimplemented!("clone_shallow not implemented for this MemoryPool type")
    }
    
    /// Get an object from the pool
    fn get(&self) -> PooledObject<Self> where Self: Sized, Self::Object: Debug + Send + Sync + Default + Clone {
        // Default implementation - can be overridden by specific implementations
        unimplemented!("get not implemented for this MemoryPool type")
    }
    
    /// Get an object from the pool using lock-free operations
    fn get_lockfree(&self) -> Option<Self::Object> where Self: Sized, Self::Object: Debug + Send + Sync + Default + Clone {
        // Default implementation - can be overridden by specific implementations
        None
    }
    
    /// Get an object from the pool with swap tracking
    fn get_with_tracking(
        &self,
        size_bytes: u64,
        allocation_tracker: Arc<RwLock<SwapAllocationMetrics>>,
        allocation_type: &str,
    ) -> PooledObject<Self> where Self: Sized, Self::Object: Debug + Send + Sync + Default + Clone {
        // Default implementation - can be overridden by specific implementations
        unimplemented!("get_with_tracking not implemented for this MemoryPool type")
    }
    
    /// Get pool statistics with additional monitoring data
    fn get_monitoring_stats(&self) -> ObjectPoolMonitoringStats {
        // Default implementation - can be overridden by specific implementations
        ObjectPoolMonitoringStats {
            available_objects: 0,
            max_size: 0,
            high_water_mark: 0,
            allocation_count: 0,
            current_usage_ratio: 0.0,
            last_cleanup_time: 0,
        }
    }
    
    /// Get current pool size atomically
    fn get_current_size(&self) -> usize {
        // Default implementation - can be overridden by specific implementations
        0
    }
    
    /// Get high water mark atomically
    fn get_high_water_mark(&self) -> usize {
        // Default implementation - can be overridden by specific implementations
        0
    }
    
    /// Get current usage ratio atomically
    fn get_usage_ratio(&self) -> f64 {
        // Default implementation - can be overridden by specific implementations
        0.0
    }
    
    /// Try to increment pool size atomically
    fn try_increment_pool_size(&self) -> Option<usize> {
        // Default implementation - can be overridden by specific implementations
        None
    }
    
    /// Decrement pool size atomically
    fn decrement_pool_size(&self) {
        // Default implementation - can be overridden by specific implementations
    }
    
    /// Record allocation for monitoring with atomic state updates
    fn record_allocation_lockfree(&self) {
        // Default implementation - can be overridden by specific implementations
    }
    
    /// Record allocation for monitoring
    fn record_allocation(&self) {
        // Default implementation - can be overridden by specific implementations
        self.record_allocation_lockfree();
    }
    
    /// Record deallocation for monitoring
    fn record_deallocation(&self) {
        // Default implementation - can be overridden by specific implementations
    }
    
    /// Perform lazy cleanup when pool usage exceeds threshold
    fn lazy_cleanup(&self, threshold: f64) -> bool {
        // Default implementation - can be overridden by specific implementations
        false
    }
}

/// Common configuration for memory pools
#[derive(Debug, Clone)]
pub struct MemoryPoolConfig {
    pub max_size: usize,
    pub pre_allocate: bool,
    pub high_water_mark_ratio: f64,
    pub cleanup_threshold: f64,
    pub logger_name: String,
}

impl Default for MemoryPoolConfig {
    fn default() -> Self {
        Self {
            max_size: 1024 * 1024 * 100, // 100MB default
            pre_allocate: true,
            high_water_mark_ratio: 0.9,
            cleanup_threshold: 0.8,
            logger_name: "memory_pool".to_string(),
        }
    }
}

impl Clone for MemoryPoolStats {
    fn clone(&self) -> Self {
        Self {
            allocated_bytes: AtomicUsize::new(self.allocated_bytes.load(Ordering::Relaxed)),
            total_allocations: AtomicUsize::new(self.total_allocations.load(Ordering::Relaxed)),
            total_deallocations: AtomicUsize::new(self.total_deallocations.load(Ordering::Relaxed)),
            peak_allocated_bytes: AtomicUsize::new(self.peak_allocated_bytes.load(Ordering::Relaxed)),
            current_usage_ratio: self.current_usage_ratio,
        }
    }
}
/// Common statistics for memory pools
#[derive(Debug)]
pub struct MemoryPoolStats {
    pub allocated_bytes: AtomicUsize,
    pub total_allocations: AtomicUsize,
    pub total_deallocations: AtomicUsize,
    pub peak_allocated_bytes: AtomicUsize,
    pub current_usage_ratio: f64,
}

impl Default for MemoryPoolStats {
    fn default() -> Self {
        Self {
            allocated_bytes: AtomicUsize::new(0),
            total_allocations: AtomicUsize::new(0),
            total_deallocations: AtomicUsize::new(0),
            peak_allocated_bytes: AtomicUsize::new(0),
            current_usage_ratio: 0.0,
        }
    }
}

/// Builder pattern for memory pool configuration
#[derive(Debug, Default)]
pub struct MemoryPoolBuilder {
    config: MemoryPoolConfig,
}

impl MemoryPoolBuilder {
    /// Create a new builder with default configuration
    pub fn new() -> Self {
        Self::default()
    }
     
    /// Set maximum pool size in bytes
    pub fn max_size(mut self, size: usize) -> Self {
        self.config.max_size = size;
        self
    }
     
    /// Enable/disable pre-allocation
    pub fn pre_allocate(mut self, pre_allocate: bool) -> Self {
        self.config.pre_allocate = pre_allocate;
        self
    }
     
    /// Set high water mark ratio (0.0 to 1.0)
    pub fn high_water_mark_ratio(mut self, ratio: f64) -> Self {
        self.config.high_water_mark_ratio = ratio.clamp(0.0, 1.0);
        self
    }
     
    /// Set cleanup threshold ratio (0.0 to 1.0)
    pub fn cleanup_threshold(mut self, threshold: f64) -> Self {
        self.config.cleanup_threshold = threshold.clamp(0.0, 1.0);
        self
    }
     
    /// Set logger name
    pub fn logger_name(mut self, name: String) -> Self {
        self.config.logger_name = name;
        self
    }
     
    /// Build the configuration
    pub fn build(self) -> MemoryPoolConfig {
        self.config
    }
}

/// Helper trait for safe memory operations
pub trait SafeMemoryOperations {
    /// Allocate memory safely
    fn safe_allocate(&self, size: usize) -> Result<Box<[u8]>>;
     
    /// Deallocate memory safely
    fn safe_deallocate(&self, ptr: *mut u8, size: usize);
}

/// Common implementation for safe memory operations
impl SafeMemoryOperations for MemoryPoolConfig {
    fn safe_allocate(&self, size: usize) -> Result<Box<[u8]>> {
        if size == 0 {
            return Err(RustError::InvalidInput("Allocation size cannot be zero".to_string()));
        }
         
        if size > self.max_size {
            return Err(RustError::ResourceLimitExceeded(
                format!("Allocation size {} exceeds max pool size {}", size, self.max_size)
            ));
        }
         
        Ok(vec![0u8; size].into_boxed_slice())
    }
     
    fn safe_deallocate(&self, _ptr: *mut u8, _size: usize) {
        // In Rust, we typically rely on the drop trait for deallocation
        // This is a placeholder for any additional safety checks needed
    }
}

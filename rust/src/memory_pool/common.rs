//! Common types and traits for memory pool implementations

use std::fmt::Debug;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;

use crate::errors::{RustError, Result};
use crate::logging::PerformanceLogger;

/// Common trait for all memory pool implementations
pub trait MemoryPool: Debug + Send + Sync {
    /// Allocate memory of the specified size
    fn allocate(&self, size: usize) -> Result<Box<[u8]>>;
    
    /// Deallocate memory
    fn deallocate(&self, ptr: *mut u8, size: usize);
    
    /// Get pool statistics
    fn get_stats(&self) -> MemoryPoolStats;
    
    /// Get current memory pressure level
    fn get_memory_pressure(&self) -> f64;
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

/// Common statistics for memory pools
#[derive(Debug, Clone)]
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

/// Common error handling macro for memory pool operations
#[macro_export]
macro_rules! memory_pool_error {
    ($($arg:tt)*) => {
        Err(RustError::MemoryPoolError(format!($($arg)*)))
    };
}

/// Common success result macro for memory pool operations
#[macro_export]
macro_rules! memory_pool_ok {
    ($val:expr) => {
        Ok($val)
    };
}
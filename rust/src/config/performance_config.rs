use crate::parallelism::base::executor_factory::executor_factory::ExecutorType;
use std::sync::Arc;

/// Performance configuration for the runtime
#[derive(Debug, Clone)]
pub struct PerformanceConfig {
    /// Enable work-stealing scheduler
    pub work_stealing_enabled: bool,
    
    /// Maximum queue size for work-stealing scheduler
    pub work_stealing_queue_size: usize,
    
    /// Number of worker threads for thread pool
    pub thread_pool_size: usize,
    
    /// Executor type to use by default
    pub default_executor_type: ExecutorType,
    
    /// Enable SIMD optimizations
    pub simd_enabled: bool,
    
    /// Enable memory pooling
    pub memory_pooling_enabled: bool,
    
    /// Maximum memory pool size (MB)
    pub max_memory_pool_size: usize,
    
    /// Enable zero-copy operations
    pub zero_copy_enabled: bool,
    
    /// Performance monitoring level
    pub monitoring_level: PerformanceMode,
}

/// Performance monitoring modes
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PerformanceMode {
    /// No monitoring
    Off,
    /// Basic monitoring (minimal overhead)
    Basic,
    /// Detailed monitoring (more overhead)
    Detailed,
    /// Development monitoring (max detail)
    Development,
}

impl Default for PerformanceConfig {
    fn default() -> Self {
        Self {
            work_stealing_enabled: true,
            work_stealing_queue_size: 1000,
            thread_pool_size: num_cpus::get(),
            default_executor_type: ExecutorType::WorkStealing,
            simd_enabled: true,
            memory_pooling_enabled: true,
            max_memory_pool_size: 256,
            zero_copy_enabled: true,
            monitoring_level: PerformanceMode::Basic,
        }
    }
}

/// Work-stealing scheduler specific configuration
#[derive(Debug, Clone)]
pub struct WorkStealingConfig {
    /// Is work-stealing enabled
    pub enabled: bool,
    
    /// Maximum queue size before tasks are executed directly
    pub queue_size: usize,
    
    /// Maximum number of concurrent tasks
    pub max_concurrent_tasks: usize,
    
    /// Task steal timeout in milliseconds
    pub steal_timeout: u64,
}

impl Default for WorkStealingConfig {
    fn default() -> Self {
        Self {
            enabled: true,
            queue_size: 1000,
            max_concurrent_tasks: num_cpus::get() * 2,
            steal_timeout: 100,
        }
    }
}

/// Global performance configuration instance
pub static GLOBAL_PERFORMANCE_CONFIG: once_cell::sync::Lazy<Arc<PerformanceConfig>> = 
    once_cell::sync::Lazy::new(|| Arc::new(PerformanceConfig::default()));

/// Get global performance configuration
pub fn get_global_performance_config() -> Arc<PerformanceConfig> {
    GLOBAL_PERFORMANCE_CONFIG.clone()
}

/// Set global performance configuration (thread-safe)
pub fn set_global_performance_config(config: PerformanceConfig) {
    *GLOBAL_PERFORMANCE_CONFIG.get_mut().expect("Failed to get mutable config") = Arc::new(config);
}
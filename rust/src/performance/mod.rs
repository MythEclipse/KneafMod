pub mod cache_eviction;
pub mod checksum_monitor;
pub mod common;
pub mod monitoring;

// Re-export key types and structures for easy access
pub use common::{
    JniCallMetrics, LockWaitMetrics, MemoryMetrics, PerformanceMetrics, PerformanceMonitorTrait
};
pub use monitoring::{
    PerformanceMonitor, PerformanceMonitorBuilder, PerformanceMonitorFactory, 
    PerformanceStats, SwapHealthStatus, SwapPerformanceSummary,
    report_swap_operation, report_memory_pressure, report_swap_cache_statistics,
    report_swap_io_performance, report_swap_pool_metrics, report_swap_component_health,
    get_swap_performance_summary, PERFORMANCE_MONITOR
};
//! Examples of how to use the RealTimeMemoryMonitor

use crate::errors::Result;
use crate::memory::monitoring::{
    RealTimeMemoryMonitor, RealTimeMonitorConfig, MemoryPoolType, start_global_monitoring,
    get_global_memory_metrics, export_global_metrics_to_json, REAL_TIME_MEMORY_MONITOR
};
use crate::memory::pool::lru_eviction::{LRUEvictionMemoryPool, LRUEvictionConfig};
use crate::memory::pool::enhanced_manager::EnhancedMemoryPoolManager;

/// Example: Basic initialization and usage of RealTimeMemoryMonitor
pub fn basic_usage_example() -> Result<()> {
    // Create a custom configuration
    let config = RealTimeMonitorConfig {
        sampling_rate_ms: 500, // Sample metrics every 500ms
        allocation_threshold: 50 * 1024 * 1024, // 50MB threshold
        ..Default::default()
    };

    // Create a new monitor with custom configuration
    let monitor = RealTimeMemoryMonitor::new(Some(config))?;

    // Start monitoring
    monitor.start_monitoring()?;

    // Simulate some memory operations
    for i in 0..10 {
        // Track allocation
        REAL_TIME_MEMORY_MONITOR.track_allocation(
            (i + 1) * 1024 * 1024, // 1MB, 2MB, ..., 10MB
            MemoryPoolType::LRU
        )?;
        
        std::thread::sleep(std::time::Duration::from_millis(100));
    }

    // Get current metrics
    let metrics = REAL_TIME_MEMORY_MONITOR.get_memory_metrics()?;
    println!("Current memory metrics: {:?}", metrics);

    // Export metrics to JSON
    let json = REAL_TIME_MEMORY_MONITOR.export_metrics_to_json()?;
    println!("JSON metrics: {}", json);

    // Stop monitoring
    monitor.stop_monitoring()?;

    Ok(())
}

/// Example: Integration with LRUEvictionMemoryPool
pub fn lru_integration_example() -> Result<()> {
    // Create an LRU eviction pool
    let lru_pool = LRUEvictionMemoryPool::new(Some(LRUEvictionConfig {
        capacity: 100 * 1024 * 1024, // 100MB
        ..Default::default()
    }))?;

    // Start global monitoring
    start_global_monitoring();

    // Perform allocations that will be automatically tracked
    for i in 0..5 {
        let size = (i + 1) * 10 * 1024 * 1024; // 10MB, 20MB, ..., 50MB
        let _buffer = lru_pool.allocate(size)?;
        
        std::thread::sleep(std::time::Duration::from_millis(200));
    }

    // Get global metrics
    if let Some(metrics) = get_global_memory_metrics() {
        println!("Global memory metrics: {:?}", metrics);
    }

    Ok(())
}

/// Example: Integration with EnhancedMemoryPoolManager
pub fn enhanced_manager_integration_example() -> Result<()> {
    // Create an enhanced memory pool manager
    let manager = EnhancedMemoryPoolManager::new(None)?;
    
    // Get the global monitor and integrate with the manager
    REAL_TIME_MEMORY_MONITOR.integrate_with_enhanced_manager(manager)?;

    // Start global monitoring
    start_global_monitoring();

    // The manager will now automatically provide memory pressure information
    // to the real-time monitor

    Ok(())
}

/// Example: Using memory pressure callbacks
pub fn pressure_callback_example() -> Result<()> {
    // Register a callback for memory pressure events
    REAL_TIME_MEMORY_MONITOR.register_pressure_callback(|pressure_level| {
        println!("Memory pressure changed to: {:?}", pressure_level);
        // Here you could implement alerting, logging, or adaptive behavior
    })?;

    // Start global monitoring
    start_global_monitoring();

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_basic_monitor_creation() {
        let monitor = RealTimeMemoryMonitor::new(None).unwrap();
        assert!(!monitor.is_running.load(std::sync::atomic::Ordering::Relaxed));
    }

    #[test]
    fn test_memory_metrics() {
        let monitor = RealTimeMemoryMonitor::new(None).unwrap();
        
        // Track some allocations
        monitor.track_allocation(1024, MemoryPoolType::LRU).unwrap();
        monitor.track_allocation(2048, MemoryPoolType::Hierarchical).unwrap();
        
        // Get metrics
        let metrics = monitor.get_memory_metrics().unwrap();
        
        assert_eq!(metrics.total_memory_usage, 3072);
        assert_eq!(metrics.memory_by_pool_type.len(), 2);
    }
}
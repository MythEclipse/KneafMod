use std::thread;
use std::time::Duration;
use rustperf::memory_pool::ObjectPool;

#[test]
fn test_monitoring_thread_graceful_shutdown() {
    // Create object pool
    let pool = ObjectPool::<String>::new(100);
    
    // Start monitoring with 100ms interval
    pool.start_monitoring(100);
    
    // Verify monitoring is running
    assert!(pool.is_monitoring_active());
    
    // Wait a bit to ensure thread is running
    thread::sleep(Duration::from_millis(150));
    
    // Stop monitoring with timeout
    let stopped = pool.stop_monitoring(Some(500));
    assert!(stopped, "Monitoring thread should stop gracefully");
    
    // Verify monitoring is stopped
    assert!(!pool.is_monitoring_active());
    assert!(pool.is_shutdown_requested());
}

#[test]
fn test_monitoring_thread_force_shutdown() {
    // Create object pool
   let pool = ObjectPool::<String>::new(100);
    
    // Start monitoring with 100ms interval
    pool.start_monitoring(100);
    
    // Verify monitoring is running
    assert!(pool.is_monitoring_active());
    
    // Force stop monitoring
    pool.force_stop_monitoring();
    
    // Verify shutdown flag is set
    assert!(pool.is_shutdown_requested());
}

#[test]
fn test_monitoring_thread_error_handling() {
    // Create object pool
    let pool = ObjectPool::<String>::new(100);
    
    // Start monitoring with very short interval
    pool.start_monitoring(10);
    
    // Verify monitoring is running
    assert!(pool.is_monitoring_active());
    
    // Let it run for a short time to test error handling
    thread::sleep(Duration::from_millis(50));
    
    // Stop monitoring
    let stopped = pool.stop_monitoring(Some(100));
    assert!(stopped, "Monitoring thread should handle errors gracefully");
}
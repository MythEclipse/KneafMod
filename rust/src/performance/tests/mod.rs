use crate::performance::{
    PerformanceMonitor, PerformanceMonitorBuilder, PerformanceMonitorFactory, PerformanceMetrics
};
use std::time::Instant;

#[test]
fn test_performance_monitor_imports() {
    // This test simply verifies that we can import and use the PerformanceMonitor types
    // without running into circular dependency issues
    
    // Create a default performance monitor
    let monitor = PerformanceMonitorFactory::create_default().unwrap();
    
    // Record some sample data
    monitor.record_jni_call("test_call", 10);
    monitor.record_lock_wait("test_lock", 5);
    monitor.record_memory_usage(1024 * 1024 * 100, 1024 * 1024 * 50, 1024 * 1024 * 50);
    monitor.record_gc_event(20);
    
    // Get metrics snapshot
    let metrics: PerformanceMetrics = monitor.get_metrics_snapshot();
    
    // Verify metrics have expected structure
    assert!(metrics.jni_calls.total_calls >= 1);
    assert!(metrics.lock_wait_metrics.total_lock_waits >= 1);
    assert!(metrics.memory_metrics.total_heap_bytes > 0);
    
    println!("Performance monitor test passed!");
}

#[test]
fn test_performance_monitor_builder() {
    // Test that we can use the builder pattern to create a monitor with custom settings
    let monitor = PerformanceMonitorBuilder::new()
        .with_jni_call_threshold(50)
        .with_lock_wait_threshold(25)
        .with_memory_usage_threshold(80)
        .with_gc_duration_threshold(50)
        .build()
        .unwrap();
    
    // Verify the thresholds were set correctly
    assert_eq!(monitor.jni_call_threshold_ms.load(std::sync::atomic::Ordering::Relaxed), 50);
    assert_eq!(monitor.lock_wait_threshold_ms.load(std::sync::atomic::Ordering::Relaxed), 25);
    assert_eq!(monitor.memory_usage_threshold_pct.load(std::sync::atomic::Ordering::Relaxed), 80);
    assert_eq!(monitor.gc_duration_threshold_ms.load(std::sync::atomic::Ordering::Relaxed), 50);
    
    println!("Performance monitor builder test passed!");
}

#[test]
fn test_performance_monitor_trait() {
    // Test that the PerformanceMonitorTrait is implemented correctly
    let monitor = PerformanceMonitorFactory::create_default().unwrap();
    
    // Use the trait methods
    monitor.record_jni_call("trait_test", 10);
    monitor.record_lock_wait("trait_test_lock", 5);
    monitor.record_memory_usage(1024 * 1024 * 100, 1024 * 1024 * 50, 1024 * 1024 * 50);
    monitor.record_gc_event(20);
    
    let metrics = monitor.get_metrics_snapshot();
    
    assert!(metrics.jni_calls.total_calls >= 1);
    
    println!("Performance monitor trait test passed!");
}

#[test]
fn test_operation_recording() {
    // Test the record_operation function
    let start = Instant::now();
    // Simulate some work
    std::thread::sleep(std::time::Duration::from_millis(10));
    
    // This should not panic
    crate::performance::monitoring::record_operation(start, 100, 1);
    
    println!("Operation recording test passed!");
}
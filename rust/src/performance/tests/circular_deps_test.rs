//! Test to verify that circular dependencies have been resolved

// This test simply tries to import and use types from the performance module
// in different combinations to ensure there are no circular dependencies

#[test]
fn test_common_imports() {
    // Test importing from common module
    use crate::performance::common::{
        JniCallMetrics, LockWaitMetrics, MemoryMetrics, PerformanceMetrics, PerformanceMonitorTrait
    };
    
    // Create instances of the types
    let jni_metrics = JniCallMetrics::default();
    let lock_metrics = LockWaitMetrics::default();
    let memory_metrics = MemoryMetrics::default();
    let performance_metrics = PerformanceMetrics::default();
    
    // Verify we can access fields
    assert_eq!(jni_metrics.total_calls, 0);
    assert_eq!(lock_metrics.total_lock_waits, 0);
    assert_eq!(memory_metrics.total_heap_bytes, 0);
    assert_eq!(performance_metrics.jni_calls.total_calls, 0);
    
    println!("✓ Common module imports work correctly");
}

#[test]
fn test_monitoring_imports() {
    // Test importing from monitoring module
    use crate::performance::monitoring::{
        PerformanceMonitor, PerformanceMonitorBuilder, PerformanceMonitorFactory
    };
    
    // Create a monitor using the factory
    let monitor = PerformanceMonitorFactory::create_default().unwrap();
    
    // Record some data
    monitor.record_jni_call("test_call", 10);
    monitor.record_lock_wait("test_lock", 5);
    
    // Get metrics
    let metrics = monitor.get_metrics_snapshot();
    
    // Verify metrics
    assert_eq!(metrics.jni_calls.total_calls, 1);
    assert_eq!(metrics.lock_wait_metrics.total_lock_waits, 1);
    
    println!("✓ Monitoring module imports work correctly");
}

#[test]
fn test_builder_imports() {
    // Test importing and using the builder
    use crate::performance::monitoring::PerformanceMonitorBuilder;
    
    // Create a custom monitor with builder
    let monitor = PerformanceMonitorBuilder::new()
        .with_jni_call_threshold(50)
        .with_lock_wait_threshold(25)
        .build()
        .unwrap();
    
    // Verify thresholds were set
    assert_eq!(monitor.jni_call_threshold_ms.load(std::sync::atomic::Ordering::Relaxed), 50);
    assert_eq!(monitor.lock_wait_threshold_ms.load(std::sync::atomic::Ordering::Relaxed), 25);
    
    println!("✓ Builder imports work correctly");
}

#[test]
fn test_reexported_imports() {
    // Test importing from the main performance module (which re-exports everything)
    use crate::performance::{
        PerformanceMonitor, PerformanceMonitorBuilder, PerformanceMonitorFactory,
        JniCallMetrics, LockWaitMetrics, MemoryMetrics, PerformanceMetrics
    };
    
    // Create a monitor
    let monitor = PerformanceMonitorFactory::create_default().unwrap();
    
    // Record data
    monitor.record_jni_call("test", 10);
    
    // Get metrics
    let metrics = monitor.get_metrics_snapshot();
    
    // Verify
    assert_eq!(metrics.jni_calls.total_calls, 1);
    
    println!("✓ Re-exported imports work correctly");
}

#[test]
fn test_trait_implementation() {
    // Test that PerformanceMonitor implements PerformanceMonitorTrait
    use crate::performance::{
        PerformanceMonitor, PerformanceMonitorFactory, PerformanceMonitorTrait
    };
    
    let monitor = PerformanceMonitorFactory::create_default().unwrap();
    
    // Use the trait methods
    monitor.record_jni_call("trait_test", 10);
    monitor.record_lock_wait("trait_test_lock", 5);
    
    let metrics = monitor.get_metrics_snapshot();
    
    assert_eq!(metrics.jni_calls.total_calls, 1);
    assert_eq!(metrics.lock_wait_metrics.total_lock_waits, 1);
    
    println!("✓ PerformanceMonitor implements PerformanceMonitorTrait correctly");
}
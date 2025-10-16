//! Simple verification script to check for circular dependencies in performance module

fn main() {
    println!("Verifying performance module imports...");
    
    // Test importing and using key types from the performance module
    use rustperf::performance::{
        PerformanceMonitor, 
        PerformanceMonitorBuilder, 
        PerformanceMonitorFactory,
        PerformanceMetrics
    };
    
    println!("✓ Successfully imported performance module types");
    
    // Test creating a default monitor
    let monitor = PerformanceMonitorFactory::create_default().expect("Failed to create default monitor");
    println!("✓ Successfully created default PerformanceMonitor");
    
    // Test recording some data
    monitor.record_jni_call("test_call", 10);
    monitor.record_lock_wait("test_lock", 5);
    monitor.record_memory_usage(1024 * 1024 * 100, 1024 * 1024 * 50, 1024 * 1024 * 50);
    monitor.record_gc_event(20);
    println!("✓ Successfully recorded performance data");
    
    // Test getting metrics
    let metrics: PerformanceMetrics = monitor.get_metrics_snapshot();
    println!("✓ Successfully retrieved performance metrics");
    
    // Print some metrics to verify
    println!(
        "Metrics: JNI calls: {}, Lock waits: {}, Memory usage: {:.1}%",
        metrics.jni_calls.total_calls,
        metrics.lock_wait_metrics.total_lock_waits,
        metrics.memory_metrics.used_heap_percent
    );
    
    // Test builder pattern
    let custom_monitor = PerformanceMonitorBuilder::new()
        .with_jni_call_threshold(50)
        .with_lock_wait_threshold(25)
        .build()
        .expect("Failed to build custom monitor");
    println!("✓ Successfully built custom PerformanceMonitor with builder pattern");
    
    println!("All tests passed! No circular dependencies detected.");
}
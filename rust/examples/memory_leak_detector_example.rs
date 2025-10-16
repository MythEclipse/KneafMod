use std::sync::Arc;
use std::time::Duration;

use rustperf::memory::leak_detector::{
    MemoryLeakDetector, LeakDetectorConfig, LeakDetectionCallback, MemoryLeak, LeakReport,
    export_leak_report_to_json,
};
use rustperf::memory::monitoring::{RealTimeMemoryMonitor, MemoryPoolType};
use rustperf::memory::pool::lru_eviction::LRUEvictionMemoryPool;
use rustperf::memory::pool::enhanced_manager::EnhancedMemoryPoolManager;

/// Example callback implementation for leak detection alerts
struct ExampleLeakCallback;

impl LeakDetectionCallback for ExampleLeakCallback {
    fn on_leak_detected(&self, leak: &MemoryLeak) {
        println!(
            "Leak detected: ID={}, Type={:?}, Confidence={}%, Size={} bytes",
            leak.id, leak.leak_type, leak.confidence, leak.allocation.size
        );
        if let Some(analysis) = &leak.analysis {
            println!("  Analysis: {}", analysis);
        }
    }
    
    fn on_report_generated(&self, report: &LeakReport) {
        println!(
            "Leak report generated: {} leaks detected, current usage: {} bytes",
            report.total_leaks, report.memory_stats.current_usage
        );
    }
    
    fn on_memory_threshold_exceeded(&self, stats: &rustperf::memory::leak_detector::MemoryStats) {
        println!(
            "Memory threshold exceeded: current usage {} bytes, peak usage {} bytes",
            stats.current_usage, stats.peak_usage
        );
    }
}

/// Example demonstrating basic usage of MemoryLeakDetector
fn basic_leak_detection_example() {
    println!("=== Basic Memory Leak Detection Example ===");
    
    // Create configuration with custom settings
    let config = LeakDetectorConfig {
        analysis_interval: 10, // Check for leaks every 10 seconds
        growth_threshold: 2.0, // 2% growth threshold
        max_lifetime: 60,      // 60 second max lifetime for allocations
        sensitivity: 7,        // Higher sensitivity
        min_allocation_size: 512, // Track allocations 512 bytes or larger
        enable_ml_detection: true,
        historical_samples: 5, // Keep last 5 samples for analysis
    };
    
    // Create necessary components
    let memory_monitor = Arc::new(RealTimeMemoryMonitor::new(None).unwrap());
    let lru_pool = Arc::new(LRUEvictionMemoryPool::new(None).unwrap());
    let pool_manager = Arc::new(EnhancedMemoryPoolManager::new(None).unwrap());
    
    // Create leak detector with the components
    let leak_detector = MemoryLeakDetector::new(config, memory_monitor, lru_pool, pool_manager);
    
    // Register callback for leak alerts
    leak_detector.register_callback(Box::new(ExampleLeakCallback));
    
    // Simulate some allocations that might leak
    let allocation_id = leak_detector.track_allocation(
        0x1000,                // Address
        1024,                  // Size
        1,                     // Thread ID
        "example_component".to_string(), // Component
        Some("test allocation".to_string()), // Context
        None,                  // Stack trace
    );
    
    println!("Tracked allocation with ID: {}", allocation_id);
    
    // In a real application, you would let the program run for a while
    // to collect data and detect leaks. For this example, we'll just wait.
    println!("Running leak detection for 30 seconds...");
    std::thread::sleep(Duration::from_secs(30));
    
    // Generate and print a leak report
    let report = leak_detector.get_leak_report();
    println!("Final leak report: {} leaks detected", report.total_leaks);
    
    // Export report to JSON
    if let Ok(json) = export_leak_report_to_json(&report) {
        println!("Leak report JSON:\n{}", json);
    }
}

/// Example demonstrating integration with existing memory systems
fn integrated_leak_detection_example() {
    println!("\n=== Integrated Memory Leak Detection Example ===");
    
    // Create and start the global memory monitor
    let memory_monitor = Arc::new(RealTimeMemoryMonitor::new(None).unwrap());
    memory_monitor.start_monitoring().unwrap();
    
    // Create enhanced memory pool manager
    let pool_manager = Arc::new(EnhancedMemoryPoolManager::new(None).unwrap());
    
    // Integrate with LRUEvictionMemoryPool
    let lru_pool = Arc::new(LRUEvictionMemoryPool::new(None).unwrap());
    
    // Create leak detector with all components
    let leak_detector = MemoryLeakDetector::new(
        LeakDetectorConfig::default(),
        memory_monitor,
        lru_pool,
        pool_manager,
    );
    
    // Register callback
    leak_detector.register_callback(Box::new(ExampleLeakCallback));
    
    // Simulate real-world allocation pattern
    for i in 0..20 {
        // Allocate memory
        let size = 1024 * (i + 1); // Increasing allocation size
        let allocation_id = leak_detector.track_allocation(
            0x1000 + i as usize,     // Unique address for each allocation
            size,                    // Size
            i % 4,                   // Thread ID (rotate through 4 threads)
            format!("component-{}", i % 3), // Component
            Some(format!("allocation-{}", i)), // Context
            None,                    // Stack trace
        );
        
        println!("Allocated {} bytes (ID: {})", size, allocation_id);
        
        // Randomly deallocate some allocations to simulate real behavior
        if i > 5 && i % 3 == 0 {
            let dealloc_id = i - 3; // Deallocate something older
            leak_detector.track_deallocation(dealloc_id as u64);
            println!("Deallocated allocation ID: {}", dealloc_id);
        }
        
        // Small delay to simulate real application behavior
        std::thread::sleep(Duration::from_millis(100));
    }
    
    // Run leak detection for a while
    println!("Running integrated leak detection for 20 seconds...");
    std::thread::sleep(Duration::from_secs(20));
    
    // Get and print final report
    let report = leak_detector.get_leak_report();
    println!("Integrated leak report: {} leaks detected", report.total_leaks);
    
    // In a real application, you would:
    // 1. Periodically check for leaks
    // 2. Take action based on leak severity
    // 3. Log leaks to a monitoring system
    // 4. Notify administrators of critical leaks
}

fn main() {
    // Run both examples
    basic_leak_detection_example();
    integrated_leak_detection_example();
}
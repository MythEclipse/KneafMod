use rustperf::performance_monitoring::{
    report_swap_operation, report_memory_pressure, report_swap_cache_statistics,
    report_swap_io_performance, report_swap_pool_metrics, report_swap_component_health,
    get_swap_performance_summary, SwapHealthStatus
};
use std::thread;
use std::time::{Duration, Instant};

/// Demonstrates comprehensive swap performance monitoring capabilities
fn main() -> Result<(), Box<dyn std::error::Error>> {
    println!("=== Swap Performance Monitoring Example ===");
    
    // Note: Performance monitoring is automatically initialized via lazy_static
    println!("✓ Performance monitoring available (initialized via lazy_static)");
    
    // Simulate various swap operations
    simulate_swap_operations()?;
    
    // Simulate memory pressure scenarios
    simulate_memory_pressure()?;
    
    // Simulate cache hit/miss patterns
    simulate_cache_performance()?;
    
    // Simulate component health monitoring
    simulate_component_health()?;
    
    // Simulate concurrent swap operations
    simulate_concurrent_swap_operations()?;
    
    // Display final metrics
    display_final_metrics()?;
    
    println!("\n=== Swap Performance Monitoring Example Complete ===");
    Ok(())
}

fn simulate_swap_operations() -> Result<(), Box<dyn std::error::Error>> {
    println!("\n--- Simulating Swap Operations ---");
    
    // Simulate swap-in operations
    for i in 0..5 {
        let start = Instant::now();
        thread::sleep(Duration::from_millis(10 + i * 2)); // Simulate I/O delay
        let duration = start.elapsed();
        
        report_swap_operation("in", (64 * 1024) as u64, duration, true);
        println!("✓ Recorded swap-in operation {}: 64KB in {:?})", i + 1, duration);
    }
    
    // Simulate swap-out operations
    for i in 0..3 {
        let start = Instant::now();
        thread::sleep(Duration::from_millis(15 + i * 3)); // Simulate I/O delay
        let duration = start.elapsed();
        
        report_swap_operation("out", (128 * 1024) as u64, duration, true);
        println!("✓ Recorded swap-out operation {}: 128KB in {:?})", i + 1, duration);
    }
    
    // Simulate a swap failure
    report_swap_operation("in", 65536, std::time::Duration::from_millis(100), false);
    println!("⚠ Recorded swap failure: Disk read timeout");
    
    // Simulate I/O performance tracking
    let io_start = Instant::now();
    thread::sleep(Duration::from_millis(25));
    let io_duration = io_start.elapsed();
    report_swap_io_performance((256 * 1024) as u64, io_duration);
    println!("✓ Recorded swap I/O operation: 256KB in {:?})", io_duration);
    
    Ok(())
}

fn simulate_memory_pressure() -> Result<(), Box<dyn std::error::Error>> {
    println!("\n--- Simulating Memory Pressure Scenarios ---");
    
    // Simulate normal pressure
    report_memory_pressure("Normal", false);
    println!("✓ Normal memory pressure recorded");
    
    // Simulate moderate pressure with cleanup
    report_memory_pressure("Moderate", true);
    println!("✓ Moderate memory pressure with cleanup recorded");
    
    // Simulate high pressure
    report_memory_pressure("High", true);
    println!("⚠ High memory pressure recorded");
    
    // Simulate critical pressure
    report_memory_pressure("Critical", true);
    println!("🚨 Critical memory pressure recorded");
    
    // Update pool usage metrics
    report_swap_pool_metrics((512 * 1024 * 1024) as u64, (1024 * 1024 * 1024) as u64); // 512MB used, 1GB capacity
    println!("✓ Swap pool usage updated: 512MB / 1GB (50% efficiency)");
    
    Ok(())
}

fn simulate_cache_performance() -> Result<(), Box<dyn std::error::Error>> {
    println!("\n--- Simulating Cache Performance ---");
    
    // Simulate cache hits and misses
    report_swap_cache_statistics(15, 5);
    println!("✓ Recorded 15 cache hits and 5 cache misses");
    
    Ok(())
}

fn simulate_component_health() -> Result<(), Box<dyn std::error::Error>> {
    println!("\n--- Simulating Component Health Monitoring ---");
    
    // Report healthy components
    report_swap_component_health("SwapManager", SwapHealthStatus::Healthy, None);
    report_swap_component_health("ChunkCache", SwapHealthStatus::Healthy, None);
    println!("✓ Healthy components reported");
    
    // Report degraded component
    report_swap_component_health("MemoryPool", SwapHealthStatus::Degraded, Some("High allocation latency"));
    println!("⚠ Degraded component reported: MemoryPool - High allocation latency");
    
    // Report unhealthy component
    report_swap_component_health("DiskIO", SwapHealthStatus::Unhealthy, Some("Frequent I/O timeouts"));
    println!("🚨 Unhealthy component reported: DiskIO - Frequent I/O timeouts");
    
    Ok(())
}

fn simulate_concurrent_swap_operations() -> Result<(), Box<dyn std::error::Error>> {
    println!("\n--- Simulating Concurrent Swap Operations ---");
    
    let mut handles = vec![];
    
    for thread_id in 0..4 {
        let handle = thread::spawn(move || -> Result<(), String> {
            // Each thread performs different types of swap operations
            for op_id in 0..3 {
                let start = Instant::now();
                thread::sleep(Duration::from_millis(5 + thread_id as u64 * 2));
                let duration = start.elapsed();
                
                // Alternate between swap-in and swap-out
                if op_id % 2 == 0 {
                    report_swap_operation("in", (32 * 1024) as u64, duration, true);
                } else {
                    report_swap_operation("out", (48 * 1024) as u64, duration, true);
                }
                
                // Simulate occasional cache hits
                if op_id % 3 == 0 {
                    report_swap_cache_statistics(1, 0);
                }
            }
            
            println!("✓ Thread {} completed swap operations", thread_id);
            Ok(())
        });
        handles.push(handle);
    }
    
    // Wait for all threads to complete
    for (i, handle) in handles.into_iter().enumerate() {
        handle.join().map_err(|_| format!("Thread {} panicked", i))??;
    }
    
    println!("✓ All concurrent swap operations completed successfully");
    Ok(())
}

fn display_final_metrics() -> Result<(), Box<dyn std::error::Error>> {
    println!("\n--- Final Swap Performance Metrics ---");
    
    // Note: Metrics are updated automatically via the monitoring system
    
    // Get comprehensive swap metrics
    let swap_metrics = get_swap_performance_summary();
    
    println!("\nSwap Operation Statistics:");
    println!("  Swap-in operations: {}", swap_metrics.swap_in_operations);
    println!("  Swap-out operations: {}", swap_metrics.swap_out_operations);
    println!("  Swap failures: {}", swap_metrics.swap_failures);
    println!("  Success rate: {:.1}%", 
        ((swap_metrics.swap_in_operations + swap_metrics.swap_out_operations - swap_metrics.swap_failures) as f64 
         / (swap_metrics.swap_in_operations + swap_metrics.swap_out_operations) as f64) * 100.0);
    
    println!("\nSwap Data Transfer:");
    println!("  Swap-in bytes: {} ({:.1} MB)", swap_metrics.swap_in_bytes, swap_metrics.swap_in_bytes as f64 / (1024.0 * 1024.0));
    println!("  Swap-out bytes: {} ({:.1} MB)", swap_metrics.swap_out_bytes, swap_metrics.swap_out_bytes as f64 / (1024.0 * 1024.0));
    println!("  Total transferred: {} ({:.1} MB)", 
        swap_metrics.swap_in_bytes + swap_metrics.swap_out_bytes,
        (swap_metrics.swap_in_bytes + swap_metrics.swap_out_bytes) as f64 / (1024.0 * 1024.0));
    
    println!("\nSwap Performance Timing:");
    println!("  Average swap-in time: {:.2} ms", swap_metrics.average_swap_in_time_ms);
    println!("  Average swap-out time: {:.2} ms", swap_metrics.average_swap_out_time_ms);
    println!("  Overall swap latency: {:.2} ms", swap_metrics.swap_latency_ms);
    println!("  I/O throughput: {:.2} Mbps", swap_metrics.swap_io_throughput_mbps);
    
    println!("\nCache Performance:");
    println!("  Cache hit rate: {:.1}%", swap_metrics.swap_hit_rate);
    println!("  Cache miss rate: {:.1}%", swap_metrics.swap_miss_rate);
    
    println!("\nMemory Management:");
    println!("  Memory pressure level: {}", swap_metrics.memory_pressure_level);
    println!("  Pressure trigger events: {}", swap_metrics.pressure_trigger_events);
    println!("  Cleanup operations: {}", swap_metrics.swap_cleanup_operations);
    
    // Note: The following fields are not available in the current SwapPerformanceSummary struct:
    // - swap_pool_usage_bytes, swap_pool_capacity_bytes, swap_pool_efficiency
    // - swap_health_status, swap_component_failures
    // These would need to be added to the struct definition if required.
    
    println!("\nSystem Health:");
    println!("  Health monitoring: Available via component health reporting");
    
    // Test integration helpers
    println!("\n--- Testing Integration Helpers ---");
    
    // Test swap operation reporting
    let test_start = Instant::now();
    thread::sleep(Duration::from_millis(5));
    let test_duration = test_start.elapsed();
    report_swap_operation("in", 65536, test_duration, true);
    println!("✓ Integration helper: swap operation reported");
    
    // Test cache statistics reporting
    report_swap_cache_statistics(10, 2);
    println!("✓ Integration helper: cache statistics reported");
    
    // Test I/O performance reporting
    report_swap_io_performance(131072, test_duration);
    println!("✓ Integration helper: I/O performance reported");
    
    // Test pool metrics reporting
    report_swap_pool_metrics(268435456, 536870912); // 256MB / 512MB
    println!("✓ Integration helper: pool metrics reported");
    
    Ok(())
}
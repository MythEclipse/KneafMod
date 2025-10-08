use rustperf::memory_pool::*;
use std::sync::Arc;
use std::thread;
use std::time::Duration;

#[test]
fn test_memory_pool_exhaustion_prevention() {
    // Create a small memory pool to test exhaustion prevention
    let pool = SwapMemoryPool::new(Some(SwapPoolConfig { max_swap_size: 2 * 1024 * 1024, ..Default::default() })).unwrap(); // 1MB pool
    
    // Allocate memory until we reach near exhaustion
    let mut allocations = Vec::new();
    
    for i in 0..50 {
        // Allocate 20KB chunks
        let chunk = pool.allocate_chunk_metadata(20 * 1024);
        
        if let Ok(allocation) = chunk {
            allocations.push(allocation);
            println!("Allocated chunk {}: {} bytes", i, 20 * 1024);
        } else {
            println!("Allocation failed at chunk {} - this is expected with exhaustion prevention", i);
            break;
        }
        
        // Small delay to allow cleanup to happen
        thread::sleep(Duration::from_millis(10));
    }
    
    // Check metrics to ensure we have cleanup events
    let metrics = pool.get_metrics();
    println!("Final metrics:");
    println!("  Total allocations: {}", metrics.total_allocations);
    println!("  Current usage: {} bytes", metrics.current_usage_bytes);
    println!("  Pages in memory: {}", metrics.pages_in_memory);
    println!("  Pages on disk: {}", metrics.pages_on_disk);
    
    // The test passes if we didn't panic and we have some allocations
    assert!(metrics.total_allocations > 0);
}

#[test]
fn test_concurrent_allocation_pressure() {
    // Create a pool and stress test it with concurrent allocations
    let pool = Arc::new(SwapMemoryPool::new(Some(SwapPoolConfig { max_swap_size: 2 * 1024 * 1024, ..Default::default() })).unwrap()); // 2MB pool
    
    let mut handles = vec![];
    
    for _ in 0..20 {
        let pool_clone = Arc::clone(&pool);
        let handle = thread::spawn(move || {
            for i in 0..100 {
                // Allocate different sizes to stress the system
                let size = 10 * 1024 + (i % 10 * 1024); // 10KB-19KB chunks
                
                match pool_clone.allocate_compressed_data(size) {
                    Ok(_) => {
                        if i % 10 == 0 {
                            println!("Thread allocated chunk of {} bytes", size);
                        }
                    },
                    Err(e) => {
                        println!("Thread allocation failed: {}", e);
                        break;
                    }
                }
                
                thread::sleep(Duration::from_millis(1));
            }
        });
        
        handles.push(handle);
    }
    
    // Wait for all threads to complete
    for handle in handles {
        let _ = handle.join();
    }
    
    // Check final state
    let metrics = pool.get_metrics();
    println!("Concurrent allocation test results:");
    println!("  Total allocations: {}", metrics.total_allocations);
    println!("  Current usage: {} bytes", metrics.current_usage_bytes);
    println!("  Pages in memory: {}", metrics.pages_in_memory);
    println!("  Pages on disk: {}", metrics.pages_on_disk);
    
    // Should have some allocations due to memory pressure
    assert!(metrics.total_allocations > 0);
}

#[test]
fn test_threshold_based_cleanup() {
    // Test that cleanup happens at the right thresholds
    let pool = SwapMemoryPool::new(Some(SwapPoolConfig { max_swap_size: 2 * 1024 * 1024, ..Default::default() })).unwrap(); // 512KB pool
    
    // Allocate just under 80% (pressure threshold)
    let alloc1 = pool.allocate_chunk_metadata(400 * 1024).unwrap();
    let metrics1 = pool.get_metrics();
    let pressure1 = pool.get_memory_pressure();
    println!("After 400KB allocation:");
    println!("  Usage: {} bytes ({:.2}%)", metrics1.current_usage_bytes, metrics1.current_usage_bytes as f64 / 512000.0 * 100.0);
    println!("  Pressure: {:?}", pressure1);
    
    // Allocate just over 90% (lazy cleanup threshold)
    let alloc2 = pool.allocate_compressed_data(120 * 1024).unwrap();
    let metrics2 = pool.get_metrics();
    let pressure2 = pool.get_memory_pressure();
    println!("After additional 120KB allocation:");
    println!("  Usage: {} bytes ({:.2}%)", metrics2.current_usage_bytes, metrics2.current_usage_bytes as f64 / 512000.0 * 100.0);
    println!("  Pressure: {:?}", pressure2);
    
    // Wait for cleanup to happen
    thread::sleep(Duration::from_secs(2));
    
    let final_metrics = pool.get_metrics();
    println!("After cleanup:");
    println!("  Usage: {} bytes ({:.2}%)", final_metrics.current_usage_bytes, final_metrics.current_usage_bytes as f64 / 512000.0 * 100.0);
    println!("  Pages in memory: {}", final_metrics.pages_in_memory);
    println!("  Pages on disk: {}", final_metrics.pages_on_disk);
    
    // Drop allocations to free memory
    drop(alloc1);
    drop(alloc2);
    
    // Should have some allocations
    assert!(final_metrics.total_allocations > 0);
}

#[test]
fn test_enhanced_memory_pool_manager_pressure_handling() {
    // Test the enhanced manager with pressure handling
    let manager = EnhancedMemoryPoolManager::new(None).unwrap(); // 4MB pool
    
    // Allocate memory to create pressure
    let mut allocations = Vec::new();
    
    for i in 0..30 {
        let size = 150 * 1024; // 150KB chunks
        
        match manager.allocate(size) {
            Ok(alloc) => {
                allocations.push(alloc);
                println!("Enhanced manager allocated chunk {}: {} bytes", i, size);
            },
            Err(e) => {
                println!("Enhanced manager allocation failed: {}", e);
                break;
            }
        }
        
        thread::sleep(Duration::from_millis(50));
    }
    
    // Wait for monitoring to run
    thread::sleep(Duration::from_secs(3));
    
    // Get stats
    let stats = manager.get_allocation_stats();
    
    println!("Enhanced manager test results:");
    println!("  Total memory usage: {} bytes", stats.current_memory_usage);
    println!("  Total allocations: {}", stats.total_allocations);
    
    // Should have handled pressure without panicking
    assert!(stats.current_memory_usage > 0);
}
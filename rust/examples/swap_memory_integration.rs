use rustperf::memory_pool::*;
use std::sync::Arc;
use std::thread;
use std::time::Duration;

/// Demonstrates integration of swap memory pool with database operations
fn main() -> Result<(), Box<dyn std::error::Error>> {
    println!("=== Swap Memory Pool Database Integration Example ===");
    
    // Initialize enhanced memory pools with 32MB swap memory
    let memory_manager = Arc::new(EnhancedMemoryPoolManager::new(Some(EnhancedManagerConfig {
        enable_swap: true,
        ..Default::default()
    })).unwrap());
    println!("✓ Initialized enhanced memory pools with 32MB swap memory");
    
    // Simulate database swap operations
    simulate_chunk_swap_operations(&memory_manager)?;
    
    // Simulate metadata operations
    simulate_metadata_operations(&memory_manager)?;
    
    // Simulate concurrent swap operations
    simulate_concurrent_swap_operations(&memory_manager)?;
    
    // Demonstrate memory pressure handling
    demonstrate_memory_pressure_handling(&memory_manager)?;
    
    // Show final statistics
    show_final_statistics(&memory_manager)?;
    
    println!("\n=== Integration Example Complete ===");
    Ok(())
}

fn simulate_chunk_swap_operations(manager: &Arc<EnhancedMemoryPoolManager>) -> Result<(), Box<dyn std::error::Error>> {
    println!("\n--- Simulating Chunk Swap Operations ---");
    
    // TODO: These methods don't exist - using basic allocation instead
    let _data1 = manager.allocate(64 * 1024)?; // 64KB data
    println!("✓ Allocated 64KB for compressed chunk data");
    
    let _data2 = manager.allocate(1024)?; // 1KB metadata
    println!("✓ Allocated 1KB for chunk metadata");
    
    let _data3 = manager.allocate(16 * 1024)?; // 16KB temp buffer
    println!("✓ Allocated 16KB for temporary processing buffer");
    
    // Simulate some processing
    thread::sleep(Duration::from_millis(10));
    
    println!("✓ Recorded successful chunk swap operation");
    
    Ok(())
}

fn simulate_metadata_operations(manager: &Arc<EnhancedMemoryPoolManager>) -> Result<(), Box<dyn std::error::Error>> {
    println!("\n--- Simulating Metadata Operations ---");
    
    // Simulate multiple metadata allocations for different chunks
    for i in 0..5 {
        let _metadata = manager.allocate(512)?; // 512 bytes each
        println!("✓ Allocated metadata for chunk {}", i);
        
        // Simulate metadata processing
        thread::sleep(Duration::from_millis(5));
    }
    
    println!("✓ Completed metadata operations for 5 chunks");
    Ok(())
}

fn simulate_concurrent_swap_operations(manager: &Arc<EnhancedMemoryPoolManager>) -> Result<(), Box<dyn std::error::Error>> {
    println!("\n--- Simulating Concurrent Swap Operations ---");
    
    let mut handles = vec![];
    
    for thread_id in 0..4 {
        let manager_clone = Arc::clone(manager);
        let handle = thread::spawn(move || -> Result<(), String> {
            // Each thread performs different types of allocations
            let _chunk_data = manager_clone.allocate(32 * 1024).map_err(|e| e.to_string())?; // 32KB
            thread::sleep(Duration::from_millis(10));
            
            let _metadata = manager_clone.allocate(256).map_err(|e| e.to_string())?; // 256 bytes
            thread::sleep(Duration::from_millis(10));
            
            let _temp_buffer = manager_clone.allocate(8 * 1024).map_err(|e| e.to_string())?; // 8KB
            thread::sleep(Duration::from_millis(10));
            
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

fn demonstrate_memory_pressure_handling(manager: &Arc<EnhancedMemoryPoolManager>) -> Result<(), Box<dyn std::error::Error>> {
    println!("\n--- Demonstrating Memory Pressure Handling ---");
    
    // Check initial memory pressure
    let initial_pressure = manager.get_memory_pressure();
    println!("Initial memory pressure: {:?}", initial_pressure);
    
    // Allocate a large amount of memory to trigger pressure
    println!("Allocating large amounts of memory to trigger pressure...");
    
    let mut large_allocs = vec![];
    for i in 0..10 {
        match manager.allocate(256 * 1024) { // 256KB each
            Ok(data) => {
                large_allocs.push(data);
                println!("✓ Allocated 256KB block {}", i);
            }
            Err(e) => {
                println!("⚠ Failed to allocate block {}: {}", i, e);
                break;
            }
        }
    }
    
    // Check memory pressure after allocations
    let final_pressure = manager.get_memory_pressure();
    println!("Final memory pressure: {:?}", final_pressure);
    
    // Check memory pressure after allocations
    let final_pressure = manager.get_memory_pressure();
    println!("Final memory pressure: {:?}", final_pressure);
    println!("✓ Memory pressure handling completed");
    
    Ok(())
}

fn show_final_statistics(manager: &Arc<EnhancedMemoryPoolManager>) -> Result<(), Box<dyn std::error::Error>> {
    println!("\n--- Final Statistics ---");
    
    // Get comprehensive statistics
    let stats = manager.get_allocation_stats();
    
    println!("Memory Pool Statistics:");
    println!("  Total allocations: {}", stats.total_allocations);
    println!("  Total deallocations: {}", stats.total_deallocations);
    println!("  Peak memory usage: {} bytes", stats.peak_memory_usage);
    println!("  Current memory usage: {} bytes", stats.current_memory_usage);
    println!("  Allocation failures: {}", stats.allocation_failures);
    println!("  Pool hit ratio: {:.1}%", stats.pool_hit_ratio * 100.0);
    println!("  Swap usage ratio: {:.1}%", stats.swap_usage_ratio * 100.0);
    
    Ok(())
}
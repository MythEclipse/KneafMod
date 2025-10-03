use rustperf::memory_pool::*;
use std::sync::Arc;
use std::thread;
use std::time::Duration;

/// Demonstrates integration of swap memory pool with database operations
fn main() -> Result<(), Box<dyn std::error::Error>> {
    println!("=== Swap Memory Pool Database Integration Example ===");
    
    // Initialize enhanced memory pools with 32MB swap memory
    let memory_manager = Arc::new(EnhancedMemoryPoolManager::new(32 * 1024 * 1024));
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
    
    let swap_pool = manager.get_swap_pool();
    
    // Allocate memory for chunk data
    let _chunk_data = swap_pool.allocate_compressed_data(64 * 1024)?; // 64KB chunk
    println!("✓ Allocated 64KB for compressed chunk data");
    
    // Allocate metadata
    let _metadata = swap_pool.allocate_chunk_metadata(1024)?; // 1KB metadata
    println!("✓ Allocated 1KB for chunk metadata");
    
    // Allocate temporary buffer for processing
    let _temp_buffer = swap_pool.allocate_temporary_buffer(16 * 1024)?; // 16KB temp buffer
    println!("✓ Allocated 16KB for temporary processing buffer");
    
    // Simulate some processing
    thread::sleep(Duration::from_millis(10));
    
    // Record successful swap operation
    manager.record_swap_operation(true);
    println!("✓ Recorded successful chunk swap operation");
    
    Ok(())
}

fn simulate_metadata_operations(manager: &Arc<EnhancedMemoryPoolManager>) -> Result<(), Box<dyn std::error::Error>> {
    println!("\n--- Simulating Metadata Operations ---");
    
    let swap_pool = manager.get_swap_pool();
    
    // Simulate multiple metadata allocations for different chunks
    for i in 0..5 {
        let _metadata = swap_pool.allocate_chunk_metadata(512)?; // 512 bytes each
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
            let swap_pool = manager_clone.get_swap_pool();
            
            // Each thread performs different types of allocations
            let _chunk_data = swap_pool.allocate_compressed_data(32 * 1024)?; // 32KB
            thread::sleep(Duration::from_millis(10));
            
            let _metadata = swap_pool.allocate_chunk_metadata(256)?; // 256 bytes
            thread::sleep(Duration::from_millis(10));
            
            let _temp_buffer = swap_pool.allocate_temporary_buffer(8 * 1024)?; // 8KB
            thread::sleep(Duration::from_millis(10));
            
            // Record operation
            manager_clone.record_swap_operation(true);
            
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
    
    let swap_pool = manager.get_swap_pool();
    
    // Check initial memory pressure
    let initial_pressure = swap_pool.get_memory_pressure();
    println!("Initial memory pressure: {:?}", initial_pressure);
    
    // Allocate a large amount of memory to trigger pressure
    println!("Allocating large amounts of memory to trigger pressure...");
    
    let mut large_allocs = vec![];
    for i in 0..10 {
        match swap_pool.allocate_compressed_data(256 * 1024) { // 256KB each
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
    let final_pressure = swap_pool.get_memory_pressure();
    println!("Final memory pressure: {:?}", final_pressure);
    
    // Force cleanup based on pressure level
    manager.force_cleanup(final_pressure);
    println!("✓ Performed cleanup based on memory pressure");
    
    Ok(())
}

fn show_final_statistics(manager: &Arc<EnhancedMemoryPoolManager>) -> Result<(), Box<dyn std::error::Error>> {
    println!("\n--- Final Statistics ---");
    
    // Get comprehensive statistics
    let stats = manager.get_enhanced_stats();
    let efficiency = manager.get_swap_efficiency_metrics();
    
    println!("Memory Pool Statistics:");
    println!("  Total memory usage: {} bytes", stats.total_memory_usage_bytes);
    println!("  Memory pressure: {:?}", stats.memory_pressure);
    println!("  Swap allocations: {}", stats.swap_metrics.total_allocations);
    println!("  Swap deallocations: {}", stats.swap_metrics.total_deallocations);
    
    println!("\nSwap Efficiency Metrics:");
    println!("  Allocation efficiency: {:.1}%", efficiency.allocation_efficiency);
    println!("  Failure rate: {:.1}%", efficiency.allocation_failure_rate);
    println!("  Swap success rate: {:.1}%", efficiency.swap_success_rate);
    println!("  Memory utilization: {:.1}%", efficiency.memory_utilization);
    println!("  Peak memory usage: {} bytes", efficiency.peak_memory_usage_bytes);
    
    println!("  Chunk metadata allocations: {}", stats.swap_metrics.chunk_metadata_allocations);
    println!("  Compressed data allocations: {}", stats.swap_metrics.compressed_data_allocations);
    println!("  Temporary buffer allocations: {}", stats.swap_metrics.temporary_buffer_allocations);
    
    Ok(())
}
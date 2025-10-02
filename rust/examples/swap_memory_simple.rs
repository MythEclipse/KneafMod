//! Simple swap memory pool example demonstrating core functionality
//! 
//! This example shows:
//! - Basic swap memory pool usage
//! - Memory allocation tracking
//! - Memory pressure detection
//! - Performance metrics

use rustperf::memory_pool::*;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    println!("=== Simple Swap Memory Pool Example ===");
    
    // Create a swap memory pool with 16MB capacity
    let swap_pool = SwapMemoryPool::new(16 * 1024 * 1024);
    println!("✓ Created swap memory pool with 16MB capacity");
    
    // Test 1: Basic allocations
    println!("\n--- Test 1: Basic Allocations ---");
    test_basic_allocations(&swap_pool)?;
    
    // Test 2: Memory tracking
    println!("\n--- Test 2: Memory Tracking ---");
    test_memory_tracking(&swap_pool)?;
    
    // Test 3: Memory pressure detection
    println!("\n--- Test 3: Memory Pressure Detection ---");
    test_memory_pressure(&swap_pool)?;
    
    // Test 4: Performance metrics
    println!("\n--- Test 4: Performance Metrics ---");
    test_performance_metrics(&swap_pool)?;
    
    println!("\n=== Simple Example Complete ===");
    Ok(())
}

fn test_basic_allocations(pool: &SwapMemoryPool) -> Result<(), Box<dyn std::error::Error>> {
    // Allocate chunk metadata
    let metadata = pool.allocate_chunk_metadata(1024)?;
    println!("✓ Allocated 1KB for chunk metadata");
    
    // Allocate compressed data
    let compressed = pool.allocate_compressed_data(4096)?;
    println!("✓ Allocated 4KB for compressed data");
    
    // Allocate temporary buffer
    let temp_buffer = pool.allocate_temporary_buffer(2048)?;
    println!("✓ Allocated 2KB for temporary buffer");
    
    // Check metrics
    let metrics = pool.get_metrics();
    println!("  - Total allocations: {}", metrics.total_allocations);
    println!("  - Current usage: {} bytes", metrics.current_usage_bytes);
    
    Ok(())
}

fn test_memory_tracking(pool: &SwapMemoryPool) -> Result<(), Box<dyn std::error::Error>> {
    println!("  - Initial metrics:");
    let initial_metrics = pool.get_metrics();
    println!("    Current usage: {} bytes", initial_metrics.current_usage_bytes);
    
    // Allocate some memory
    {
        let buffer = pool.allocate_chunk_metadata(8192)?;
        println!("✓ Allocated 8KB buffer");
        
        let during_metrics = pool.get_metrics();
        println!("  - During allocation:");
        println!("    Current usage: {} bytes", during_metrics.current_usage_bytes);
        println!("    Peak usage: {} bytes", during_metrics.peak_usage_bytes);
        
        // Buffer will be automatically returned to pool when dropped
    }
    
    // Check metrics after deallocation
    let final_metrics = pool.get_metrics();
    println!("  - After deallocation:");
    println!("    Current usage: {} bytes", final_metrics.current_usage_bytes);
    println!("    Total deallocations: {}", final_metrics.total_deallocations);
    
    Ok(())
}

fn test_memory_pressure(pool: &SwapMemoryPool) -> Result<(), Box<dyn std::error::Error>> {
    // Check initial pressure
    let initial_pressure = pool.get_memory_pressure();
    println!("  - Initial memory pressure: {:?}", initial_pressure);
    
    // Allocate some memory to increase pressure
    let _buffer1 = pool.allocate_chunk_metadata(4 * 1024 * 1024)?; // 4MB
    let pressure1 = pool.get_memory_pressure();
    println!("  - After 4MB allocation: {:?}", pressure1);
    
    let _buffer2 = pool.allocate_chunk_metadata(4 * 1024 * 1024)?; // 4MB more
    let pressure2 = pool.get_memory_pressure();
    println!("  - After 8MB allocation: {:?}", pressure2);
    
    // Try to allocate more (should fail due to memory pressure)
    match pool.allocate_chunk_metadata(8 * 1024 * 1024) {
        Ok(_) => println!("  - Large allocation succeeded (unexpected)"),
        Err(e) => println!("  - Large allocation failed as expected: {}", e),
    }
    
    Ok(())
}

fn test_performance_metrics(pool: &SwapMemoryPool) -> Result<(), Box<dyn std::error::Error>> {
    // Record some swap operations
    pool.record_swap_operation(true);
    pool.record_swap_operation(true);
    pool.record_swap_operation(false);
    pool.record_swap_operation(true);
    
    let metrics = pool.get_metrics();
    println!("  - Swap operation metrics:");
    println!("    Total operations: {}", metrics.swap_operations_total);
    println!("    Failed operations: {}", metrics.swap_operations_failed);
    println!("    Success rate: {:.1}%", 
        ((metrics.swap_operations_total - metrics.swap_operations_failed) as f64 / 
         metrics.swap_operations_total as f64) * 100.0);
    
    // Test cleanup
    println!("  - Testing memory cleanup...");
    pool.perform_light_cleanup();
    println!("✓ Light cleanup completed");
    
    pool.perform_aggressive_cleanup();
    println!("✓ Aggressive cleanup completed");
    
    Ok(())
}
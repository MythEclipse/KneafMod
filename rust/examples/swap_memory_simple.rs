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
    let swap_pool = SwapMemoryPool::new(Some(SwapPoolConfig { max_swap_size: 16 * 1024 * 1024, ..Default::default() }))?;
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
    // TODO: These methods don't exist - using basic allocation instead
    
    // Allocate chunk metadata simulation
    let _metadata = pool.allocate(1024)?;
    println!("✓ Allocated 1KB for chunk metadata");
    
    // Allocate compressed data simulation
    let _compressed = pool.allocate(4096)?;
    println!("✓ Allocated 4KB for compressed data");
    
    // Allocate temporary buffer simulation
    let _temp_buffer = pool.allocate(2048)?;
    println!("✓ Allocated 2KB for temporary buffer");
    
    // Check pressure
    let pressure = pool.get_memory_pressure();
    println!("  - Memory pressure: {:?}", pressure);
    
    Ok(())
}

fn test_memory_tracking(pool: &SwapMemoryPool) -> Result<(), Box<dyn std::error::Error>> {
    println!("  - Initial pressure:");
    let initial_pressure = pool.get_memory_pressure();
    println!("    Memory pressure: {:?}", initial_pressure);

    // Allocate some memory
    {
        let _buffer = pool.allocate(8192)?; // 8KB buffer
        println!("✓ Allocated 8KB buffer");

        let during_pressure = pool.get_memory_pressure();
        println!("  - During allocation:");
        println!("    Memory pressure: {:?}", during_pressure);

        // Buffer will be automatically returned to pool when dropped
    }

    // Check pressure after deallocation
    let final_pressure = pool.get_memory_pressure();
    println!("  - After deallocation:");
    println!("    Memory pressure: {:?}", final_pressure);

    Ok(())
}

fn test_memory_pressure(pool: &SwapMemoryPool) -> Result<(), Box<dyn std::error::Error>> {
    // Check initial pressure
    let initial_pressure = pool.get_memory_pressure();
    println!("  - Initial memory pressure: {:?}", initial_pressure);
    
    // Allocate some memory to increase pressure
    let _buffer1 = pool.allocate(4 * 1024 * 1024)?; // 4MB
    let pressure1 = pool.get_memory_pressure();
    println!("  - After 4MB allocation: {:?}", pressure1);
    
    let _buffer2 = pool.allocate(4 * 1024 * 1024)?; // 4MB more
    let pressure2 = pool.get_memory_pressure();
    println!("  - After 8MB allocation: {:?}", pressure2);
    
    // Try to allocate more (should fail due to memory pressure)
    match pool.allocate(8 * 1024 * 1024) {
        Ok(_) => println!("  - Large allocation succeeded (unexpected)"),
        Err(e) => println!("  - Large allocation failed as expected: {}", e),
    }
    
    Ok(())
}

fn test_performance_metrics(pool: &SwapMemoryPool) -> Result<(), Box<dyn std::error::Error>> {
    // Get compression stats
    println!("  - Compression stats:");
    let compression_stats = pool.get_compression_stats();
    println!("    Total compressed: {} bytes", compression_stats.total_compressed);
    println!("    Total uncompressed: {} bytes", compression_stats.total_uncompressed);
    println!("    Compression ratio: {:.2}", compression_stats.compression_ratio);

    // Test cleanup
    println!("  - Testing memory cleanup...");
    pool.cleanup()?;
    println!("✓ Cleanup completed");

    Ok(())
}
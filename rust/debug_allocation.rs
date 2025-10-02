use rustperf::memory_pool::*;

fn main() {
    println!("=== Debug Allocation Tracking ===");
    
    let pool = SwapMemoryPool::new(1024 * 1024); // 1MB pool
    
    println!("Initial metrics:");
    let initial_metrics = pool.get_metrics();
    println!("  Total allocations: {}", initial_metrics.total_allocations);
    println!("  Current usage: {} bytes", initial_metrics.current_usage_bytes);
    println!("  Memory pressure: {:?}", pool.get_memory_pressure());
    
    println!("\nAllocating 1024 bytes for chunk metadata...");
    let metadata = pool.allocate_chunk_metadata(1024);
    match metadata {
        Ok(vec) => {
            println!("  Allocation successful, vec length: {}", vec.len());
        }
        Err(e) => {
            println!("  Allocation failed: {}", e);
        }
    }
    
    println!("\nAfter allocation metrics:");
    let after_metrics = pool.get_metrics();
    println!("  Total allocations: {}", after_metrics.total_allocations);
    println!("  Current usage: {} bytes", after_metrics.current_usage_bytes);
    println!("  Memory pressure: {:?}", pool.get_memory_pressure());
    
    println!("\nAllocating 4096 bytes for compressed data...");
    let compressed = pool.allocate_compressed_data(4096);
    match compressed {
        Ok(vec) => {
            println!("  Allocation successful, vec length: {}", vec.len());
        }
        Err(e) => {
            println!("  Allocation failed: {}", e);
        }
    }
    
    println!("\nAfter second allocation metrics:");
    let final_metrics = pool.get_metrics();
    println!("  Total allocations: {}", final_metrics.total_allocations);
    println!("  Current usage: {} bytes", final_metrics.current_usage_bytes);
    println!("  Memory pressure: {:?}", pool.get_memory_pressure());
}
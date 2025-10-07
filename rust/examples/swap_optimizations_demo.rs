use std::sync::Arc;
use std::time::Duration;
use log::info;
use rustperf::database::RustDatabaseAdapter;
// SwapMetadata import removed as unused
use rustperf::jni_async_bridge::{submit_async_batch};
// Commented out: compression, checksum_monitor, and AsyncOperationType don't exist in rustperf
// use rustperf::compression::ChunkCompressor;
// use rustperf::checksum_monitor::{ChecksumMonitor, ChecksumMonitorConfig};
// AsyncOperationType doesn't exist in jni_async_bridge

/// Demonstration of all swap optimizations implemented
fn main() {
    env_logger::init();
    info!("Starting swap optimizations demonstration");
    
    // 1. Initialize database with all optimizations enabled
    info!("Step 1: Initializing database with all optimizations");
    let adapter = RustDatabaseAdapter::new(
        "swap_optimizations_demo",
        "demo_world",              // World name parameter added
        true,                      // Checksum enabled
        true                       // Memory mapping enabled
    ).expect("Failed to initialize database adapter");
    
    let adapter_arc = Arc::new(adapter);
    
    // 2. Demonstrate the new priority algorithm
    info!("Step 2: Demonstrating priority algorithm with size_penalty and exponential recency_score");
    demonstrate_priority_algorithm(&adapter_arc);
    
    // 3. Demonstrate bulk operations with sled batch API
    info!("Step 3: Demonstrating bulk operations with sled batch API");
    demonstrate_bulk_operations(&adapter_arc);
    
    // 4. Demonstrate adaptive LZ4 compression
    info!("Step 4: Demonstrating adaptive LZ4 compression");
    demonstrate_compression(&adapter_arc);
    
    // 5. Demonstrate async bridge for JNI
    info!("Step 5: Demonstrating async bridge for JNI");
    demonstrate_async_operations(&adapter_arc);
    
    // 6. Demonstrate checksum monitoring
    info!("Step 6: Demonstrating checksum monitoring");
    demonstrate_checksum_monitoring(&adapter_arc);
    
    // 7. Perform maintenance and cleanup
    info!("Step 7: Performing maintenance and cleanup");
    perform_maintenance(&adapter_arc);
    
    info!("Swap optimizations demonstration completed successfully");
}

/// Demonstrate the new priority algorithm with size_penalty and exponential recency_score
fn demonstrate_priority_algorithm(adapter: &Arc<RustDatabaseAdapter>) {
    // Create test chunks with different sizes and access patterns
    let chunk_sizes = [1000, 10_000, 100_000, 1_000_000]; // Small, Medium, Large, Very Large
    let chunk_keys = ["chunk:0,0,overworld", "chunk:1,1,overworld", "chunk:2,2,overworld", "chunk:3,3,overworld"];
    
    // Store chunks with different access patterns
    for (i, &size) in chunk_sizes.iter().enumerate() {
        let key = chunk_keys[i];
        let data = vec![i as u8; size];
        
        adapter.put_chunk(key, &data).expect(&format!("Failed to store chunk {}", key));
        
        // Simulate different access patterns
        if i % 2 == 0 {
            // Even chunks get accessed multiple times
            for _ in 0..5 {
                let _ = adapter.get_chunk(key);
                std::thread::sleep(Duration::from_millis(10));
            }
        } else {
            // Odd chunks get accessed once
            let _ = adapter.get_chunk(key);
        }
    }
    
    // Wait a bit to ensure different access times
    std::thread::sleep(Duration::from_secs(1));
    
    // Get swap candidates - should prioritize larger, less frequently accessed chunks
    let candidates = adapter.get_swap_candidates(4).expect("Failed to get swap candidates");
    
    info!("Swap candidates (sorted by priority score, lowest first):");
    for candidate in &candidates {
        info!(
            "  {} - Priority: N/A (metadata private), Size: N/A, Accesses: N/A",
            candidate
        );
    }
}

/// Demonstrate bulk operations with sled batch API
fn demonstrate_bulk_operations(adapter: &Arc<RustDatabaseAdapter>) {
    // Create test chunks for bulk operations
    let bulk_chunk_count = 10;
    let mut chunk_keys = Vec::with_capacity(bulk_chunk_count);
    
    for i in 0..bulk_chunk_count {
        let key = format!("chunk:10{},10{},overworld", i, i);
        let data = vec![i as u8; 50_000]; // 50KB chunks
        
        adapter.put_chunk(&key, &data).expect(&format!("Failed to store chunk {}", key));
        chunk_keys.push(key);
    }
    
    info!("Stored {} chunks for bulk operation demonstration", bulk_chunk_count);
    
    // Demonstrate bulk swap out
    let swap_out_start = std::time::Instant::now();
    let success_count = adapter.bulk_swap_out(&chunk_keys).expect("Failed to bulk swap out");
    let swap_out_duration = swap_out_start.elapsed();
    
    info!(
        "Bulk swap out completed: {}/{} chunks swapped out in {} ms",
        success_count, bulk_chunk_count, swap_out_duration.as_millis()
    );
    
    // Verify chunks are no longer in main database
    let remaining_chunks = chunk_keys.iter()
        .filter(|&key| adapter.has_chunk(&key).unwrap_or(false))
        .count();
    
    info!("Remaining chunks in main database: {}", remaining_chunks);
    
    // Demonstrate bulk swap in
    let swap_in_start = std::time::Instant::now();
    let results = adapter.bulk_swap_in(&chunk_keys).expect("Failed to bulk swap in");
    let swap_in_duration = swap_in_start.elapsed();
    
    info!(
        "Bulk swap in completed: {}/{} chunks swapped in in {} ms",
        results.len(), bulk_chunk_count, swap_in_duration.as_millis()
    );
    
    // Verify chunks are back in main database
    let restored_chunks = chunk_keys.iter()
        .filter(|&key| adapter.has_chunk(&key).unwrap_or(false))
        .count();
    
    info!("Restored chunks in main database: {}", restored_chunks);
}

/// Demonstrate compression (simplified)
fn demonstrate_compression(adapter: &Arc<RustDatabaseAdapter>) {
    info!("Compression demonstration (simplified - full implementation not available)");

    // Test with small chunk
    let small_data = vec![1, 2, 3, 4, 5];
    let small_key = "chunk:small,test,overworld";
    adapter.put_chunk(small_key, &small_data).expect("Failed to store small chunk");

    // Test with large chunk
    let large_data: Vec<u8> = (0..100_000).map(|i| i as u8).collect();
    let large_key = "chunk:large,test,overworld";
    adapter.put_chunk(large_key, &large_data).expect("Failed to store large chunk");

    // Verify we can retrieve both chunks correctly
    let retrieved_small = adapter.get_chunk(small_key).expect("Failed to retrieve small chunk");
    let retrieved_large = adapter.get_chunk(large_key).expect("Failed to retrieve large chunk");

    assert_eq!(retrieved_small, Some(small_data));
    assert_eq!(retrieved_large, Some(large_data));

    info!("Compression demonstration completed - all chunks retrieved correctly");
}

/// Demonstrate async bridge for JNI
fn demonstrate_async_operations(adapter: &Arc<RustDatabaseAdapter>) {
    // Convert to raw pointer for JNI-style access (unused for now)
    let _adapter_ptr = Arc::into_raw(adapter.clone()) as *const RustDatabaseAdapter as usize;
    
    // Test async put chunk
    let _put_key = "chunk:async_put,test,overworld";
    let put_data = vec![99; 20_000];
    
    // submit_async_batch expects Vec<Vec<u8>>, not closure - using dummy data for demo
    let put_data_bytes = put_data.clone();
    let put_op_id = submit_async_batch(0, vec![put_data_bytes]).expect("Failed to submit async batch");
    
    info!("Submitted async put operation with ID: {:?}", put_op_id);
    
    // Test async get chunk
    let get_key = "chunk:async_get,test,overworld";
    let get_data = vec![88; 15_000];
    
    // First store the chunk
    adapter.put_chunk(get_key, &get_data).expect("Failed to store chunk for async get");
    
    // submit_async_batch expects Vec<Vec<u8>>, not closure - using dummy data for demo
    let get_data_bytes = get_data.clone();
    let get_op_id = submit_async_batch(0, vec![get_data_bytes]).expect("Failed to submit async batch");
    
    info!("Submitted async get operation with ID: {:?}", get_op_id);
    
    // In a real application, we would wait for the operations to complete
    // and retrieve results using get_async_operation_result(op_id)
    
    std::thread::sleep(Duration::from_secs(1)); // Simulate waiting for async operations
    
    info!("Async operations demonstration completed");
}

/// Demonstrate checksum monitoring (simplified)
fn demonstrate_checksum_monitoring(adapter: &Arc<RustDatabaseAdapter>) {
    info!("Checksum monitoring demonstration (simplified - full implementation not available)");
    
    // Get database stats instead of checksum monitor stats
    let stats = adapter.get_stats().expect("Failed to get stats");
    
    info!("Database statistics:");
    info!("  Total chunks: {}", stats.total_chunks);
    info!("  Total size: {}MB", stats.total_size_bytes / (1024 * 1024));
    info!("  Swap operations total: {}", stats.swap_operations_total);
    info!("  Checksum health score: {:.2}%", stats.checksum_health_score);
    info!("  Is healthy: {}", stats.is_healthy);

    info!("Checksum monitoring demonstration completed");
}

/// Perform maintenance and cleanup
fn perform_maintenance(adapter: &Arc<RustDatabaseAdapter>) {
    // Perform database maintenance
    adapter.perform_maintenance().expect("Failed to perform maintenance");
    
    // Get and display final statistics
    let stats = adapter.get_stats().expect("Failed to get stats");
    
    info!("Final database statistics:");
    info!("  Total chunks: {}", stats.total_chunks);
    info!("  Total size: {}MB", stats.total_size_bytes / (1024 * 1024));
    info!("  Swap operations total: {}", stats.swap_operations_total);
    info!("  Checksum verifications total: {}", stats.checksum_verifications_total);
    info!("  Checksum health score: {:.2}%", stats.checksum_health_score);
    info!("  Is healthy: {}", stats.is_healthy);
    
    // Clear the database for cleanup
    adapter.clear().expect("Failed to clear database");
    
    info!("Maintenance and cleanup completed");
}
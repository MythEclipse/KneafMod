use std::time::Instant;
use crate::jni_batch::{BatchOperation, BatchOperationType, ZeroCopyBufferPool, init_global_buffer_tracker};

fn main() {
    println!("Starting Zero-Copy JNI Performance Benchmark");
    
    // Initialize buffer tracker
    init_global_buffer_tracker(1000).expect("Failed to initialize buffer tracker");
    
    // Get global buffer pool
    let buffer_pool = ZeroCopyBufferPool::global();
    
    // Pre-allocate common buffers
    println!("Pre-allocating common buffer sizes...");
    buffer_pool.pre_allocate_common_buffers();
    println!("Buffer pool pre-allocation complete");
    
    // Test different payload sizes
    let test_sizes = [64, 256, 1024, 4096, 16384, 65536, 131072];
    const TEST_ITERATIONS: usize = 100;
    
    for &size in &test_sizes {
        println!("\nTesting payload size: {} bytes", size);
        
        // Test batch operation creation
        let create_start = Instant::now();
        for _ in 0..TEST_ITERATIONS {
            let payload = vec![0x01; size];
            let _operation = BatchOperation::new(BatchOperationType::Echo, payload);
        }
        let create_time = create_start.elapsed().as_millis();
        println!("  Creation time: {}ms ({} ops/s)", create_time, 
                 (TEST_ITERATIONS as f64 * 1000.0) / create_time as f64);
        
        // Test buffer pool operations
        let pool_start = Instant::now();
        for _ in 0..TEST_ITERATIONS {
            let buffer = buffer_pool.acquire(BatchOperationType::Echo, size);
            buffer_pool.release(buffer);
        }
        let pool_time = pool_start.elapsed().as_millis();
        println!("  Buffer pool time: {}ms ({} ops/s)", pool_time, 
                 (TEST_ITERATIONS as f64 * 1000.0) / pool_time as f64);
        
        // Test zero-copy processing
        let process_start = Instant::now();
        for _ in 0..TEST_ITERATIONS {
            let payload = vec![0x01; size];
            let operation = BatchOperation::new(BatchOperationType::Echo, payload);
            let _result = operation.process_zero_copy().expect("Processing failed");
        }
        let process_time = process_start.elapsed().as_millis();
        println!("  Processing time: {}ms ({} ops/s)", process_time, 
                 (TEST_ITERATIONS as f64 * 1000.0) / process_time as f64);
    }
    
    println!("\nBenchmark complete!");
    println!("Key optimizations implemented:");
    println!("1. Zero-copy JNI calls for hot paths");
    println!("2. Direct ByteBuffer mapping for data transfer");
    println!("3. Pre-allocated buffer pools for common operations");
    println!("4. Memory arena with slab allocation");
    println!("5. Memory consolidation and defragmentation");
    println!("6. Enhanced configuration validation");
}

#[cfg(test)]
mod benchmark_tests {
    use super::*;
    
    #[test]
    fn test_benchmark_performance() {
        // This is a integration test that validates the benchmark runs successfully
        init_global_buffer_tracker(1000).expect("Failed to initialize buffer tracker");
        let buffer_pool = ZeroCopyBufferPool::global();
        
        // Test with small payload (should use zero-copy)
        let small_payload = vec![0x01; 1024];
        let operation = BatchOperation::new(BatchOperationType::Echo, small_payload);
        let result = operation.process_zero_copy().expect("Processing failed");
        
        assert!(result.status == crate::jni_batch::BatchResultStatus::Success);
        assert!(result.payload.len() > 0);
        
        // Test buffer pool operations
        let buffer = buffer_pool.acquire(BatchOperationType::Echo, 1024);
        assert!(buffer.size >= 1024);
        buffer_pool.release(buffer);
    }
}
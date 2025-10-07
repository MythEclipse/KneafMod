use std::time::Instant;
use crate::jni_batch::{BatchOperation, BatchOperationType, ZeroCopyBufferPool, init_global_buffer_tracker};
use jni::objects::JByteBuffer;
use jni::sys::jlong;
use jni::JNIEnv;

#[test]
fn test_zero_copy_buffer_pool_performance() {
    // Initialize buffer tracker before tests
    init_global_buffer_tracker(1000).expect("Failed to initialize buffer tracker");
    
    // Create global buffer pool
    let buffer_pool = ZeroCopyBufferPool::global();
    
    // Test pre-allocation of common buffer sizes
    buffer_pool.pre_allocate_common_buffers();
    
    // Test buffer acquisition and release performance
    let start_time = Instant::now();
    const TEST_ITERATIONS: usize = 1000;
    const TEST_SIZE: usize = 1024;
    
    for i in 0..TEST_ITERATIONS {
        let buffer = buffer_pool.acquire(BatchOperationType::Echo, TEST_SIZE);
        buffer_pool.release(buffer);
        
        // Verify we're getting buffers of sufficient size
        assert!(buffer.size >= TEST_SIZE);
    }
    
    let elapsed = start_time.elapsed().as_millis();
    println!("Buffer pool performance: {} iterations in {}ms", TEST_ITERATIONS, elapsed);
    assert!(elapsed < 500, "Buffer pool operations too slow");
}

#[test]
fn test_zero_copy_operation_creation() {
    // Test zero-copy operation creation with various payload sizes
    let test_sizes = [64, 256, 1024, 4096, 16384, 65536, 131072];
    
    for &size in &test_sizes {
        let payload = vec![0x01; size];
        let operation = BatchOperation::new(BatchOperationType::Echo, payload);
        
        // For smaller sizes, we should get zero-copy operations
        if size <= 131072 {
            // Note: We can't easily assert zero-copy status here without adding a method,
            // but we can verify the operation was created successfully
            assert_eq!(operation.operation_type, BatchOperationType::Echo);
            assert!(operation.payload.len() == 0 || operation.zero_copy_buffer.is_some());
        } else {
            // For larger sizes, we should get regular operations
            assert!(operation.zero_copy_buffer.is_none());
            assert_eq!(operation.payload.len(), size);
        }
    }
}

#[test]
fn test_batch_operation_processing_performance() {
    // Test processing performance with zero-copy operations
    let start_time = Instant::now();
    const TEST_ITERATIONS: usize = 500;
    const PAYLOAD_SIZE: usize = 1024;
    
    for i in 0..TEST_ITERATIONS {
        let payload = vec![0x01; PAYLOAD_SIZE];
        let operation = BatchOperation::new(BatchOperationType::Echo, payload);
        
        // Process the operation
        let result = operation.process_zero_copy();
        assert!(result.is_ok());
        
        let result = result.unwrap();
        assert_eq!(result.status, crate::jni_batch::BatchResultStatus::Success);
        assert!(result.payload.len() > 0);
    }
    
    let elapsed = start_time.elapsed().as_millis();
    println!("Batch processing performance: {} iterations in {}ms", TEST_ITERATIONS, elapsed);
    assert!(elapsed < 1000, "Batch processing too slow");
}

#[test]
fn test_buffer_reuse_efficiency() {
    let buffer_pool = ZeroCopyBufferPool::global();
    const TEST_SIZE: usize = 1024;
    const REUSE_ITERATIONS: usize = 50;
    
    // First, allocate and release buffers to fill the pool
    for _ in 0..10 {
        let buffer = buffer_pool.acquire(BatchOperationType::Echo, TEST_SIZE);
        buffer_pool.release(buffer);
    }
    
    // Now test reuse efficiency
    let start_time = Instant::now();
    
    for i in 0..REUSE_ITERATIONS {
        let buffer = buffer_pool.acquire(BatchOperationType::Echo, TEST_SIZE);
        
        // Verify buffer properties
        assert!(buffer.size >= TEST_SIZE);
        assert_eq!(buffer.operation_type, BatchOperationType::Echo);
        
        buffer_pool.release(buffer);
    }
    
    let elapsed = start_time.elapsed().as_millis();
    println!("Buffer reuse efficiency: {} iterations in {}ms", REUSE_ITERATIONS, elapsed);
    
    // Calculate rough estimate of time per operation
    let avg_time = elapsed as f64 / REUSE_ITERATIONS as f64;
    println!("Average time per buffer operation: {:.2}ms", avg_time);
    
    // Should be reasonably fast (adjust based on expected performance)
    assert!(avg_time < 2.0, "Buffer operations too slow");
}

#[cfg(feature = "java-integration")]
mod java_integration_tests {
    use super::*;
    use jni::JavaVM;
    
    #[test]
    fn test_direct_byte_buffer_integration() {
        // This test requires a running JVM and is intended for integration testing
        let vm = JavaVM::new_default().expect("Failed to create JVM");
        let env = vm.attach_current_thread().expect("Failed to attach thread");
        
        // Create a direct byte buffer
        let buffer = env.new_direct_byte_buffer(vec![0x01, 0x02, 0x03]).expect("Failed to create direct buffer");
        
        // Test conversion to zero-copy buffer reference
        let buffer_ref = BatchOperation::from_direct_byte_buffer(&env, buffer, BatchOperationType::Echo)
            .expect("Failed to create zero-copy operation");
        
        assert!(buffer_ref.zero_copy_buffer.is_some());
        assert_eq!(buffer_ref.operation_type, BatchOperationType::Echo);
    }
}
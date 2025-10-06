use criterion::{criterion_group, criterion_main, Criterion};
use jni_batch_processor::{
    EnhancedBatchConfig, EnhancedBatchOperation, EnhancedBatchProcessor, BatchBufferPool,
    native_process_batch,
};
use std::time::Instant;

fn bench_batch_processing(c: &mut Criterion) {
    let config = EnhancedBatchConfig::default();
    let processor = EnhancedBatchProcessor::new(config);

    // Create test operations
    let mut operations = Vec::with_capacity(1000);
    for i in 0..1000 {
        let op_data = vec![i as u8; 32]; // 32 bytes of test data
        operations.push(EnhancedBatchOperation::new(0, op_data, 1));
    }

    c.bench_function("batch_processing_1000_ops", |b| {
        b.iter(|| {
            // Process in batches of 10
            let mut results = Vec::new();
            for chunk in operations.chunks(10) {
                let batch = chunk.to_vec();
                let result = processor.process_batch(0, batch, &processor.metrics);
                results.push(result);
            }
            results
        })
    });

    c.bench_function("native_batch_processing_direct", |b| {
        b.iter(|| {
            let mut batch_data = Vec::with_capacity(1000 * 36); // 32 bytes data + 4 bytes header
            
            // Write batch header
            batch_data.extend_from_slice(&(1000 as u32).to_le_bytes());
            batch_data.extend_from_slice(&(1000 * 32 as u32).to_le_bytes());
            
            // Write 1000 operations
            for i in 0..1000 {
                let op_data = vec![i as u8; 32];
                batch_data.extend_from_slice(&(op_data.len() as u32).to_le_bytes());
                batch_data.extend_from_slice(&op_data);
            }
            
            native_process_batch(0, &batch_data)
        })
    });

    c.bench_function("buffer_pool_allocation", |b| {
        b.iter(|| {
            let buffer = BATCH_BUFFER_POOL.acquire_buffer();
            if let Some(mut buf) = buffer {
                buf.resize(64 * 1024, 0);
                BATCH_BUFFER_POOL.release_buffer(buf);
            }
        })
    });
}

criterion_group!(benches, bench_batch_processing);
criterion_main!(benches);
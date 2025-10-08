use std::sync::Arc;
use criterion::{criterion_group, criterion_main, Criterion};
use rustperf::memory_pool::*;
use std::time::Duration;
use tokio::runtime::Runtime;

fn benchmark_swap_operations(c: &mut Criterion) {
    let mut group = c.benchmark_group("swap_operations");
    
    // Test with different pool sizes
    for &pool_size in &[1024 * 1024, 1024 * 1024 * 10, 1024 * 1024 * 100] {
        group.bench_function(format!("allocate_chunk_metadata_{}", pool_size / (1024 * 1024)), |b| {
            let pool = SwapMemoryPool::new(Some(SwapPoolConfig { max_swap_size: pool_size, ..Default::default() })).unwrap();
            b.iter(|| {
                let _metadata = pool.allocate_chunk_metadata(4096).unwrap();
            })
        });
        
        group.bench_function(format!("allocate_compressed_data_{}", pool_size / (1024 * 1024)), |b| {
            let pool = SwapMemoryPool::new(Some(SwapPoolConfig { max_swap_size: pool_size, ..Default::default() })).unwrap();
            b.iter(|| {
                let _compressed = pool.allocate_compressed_data(16384).unwrap();
            })
        });
    }
    
    group.finish();
}

fn benchmark_async_operations(c: &mut Criterion) {
    let mut group = c.benchmark_group("async_operations");
    
    // Create a runtime for async operations
    let rt = Runtime::new().unwrap();
    
    group.bench_function("async_write", |b| {
        let pool = SwapMemoryPool::new(Some(SwapPoolConfig { max_swap_size: 1024 * 1024, ..Default::default() })).unwrap();
        let test_data = vec![0x42; 1024 * 1024];
        
        b.iter(|| {
            let result = rt.block_on(pool.write_data_async(test_data.clone()));
            assert!(result.is_ok());
        })
    });
    
    group.bench_function("async_read", |b| {
        let pool = SwapMemoryPool::new(Some(SwapPoolConfig { max_swap_size: 1024 * 1024, ..Default::default() })).unwrap();
        
        b.iter(|| {
            let result = rt.block_on(pool.read_data_async(0, 1024 * 1024));
            assert!(result.is_ok());
        })
    });
    
    group.finish();
}

fn benchmark_compression(c: &mut Criterion) {
    let mut group = c.benchmark_group("compression");
    
    let pool = SwapMemoryPool::new(Some(SwapPoolConfig { max_swap_size: 1024 * 1024 * 10, ..Default::default() })).unwrap();
    
    // Test with different data sizes and patterns
    let test_cases = [
        (vec![0x00; 1024], "all_zeroes"),
        (vec![0x42; 1024], "constant"),
        (vec![0x01; 1024], "repeating"),
        (vec![0; 1024 * 1024], "large_zeroes"),
    ];
    
    for (data, name) in test_cases.iter() {
        group.bench_function(format!("compress_{}", name), |b| {
            b.iter(|| {
                let _compressed = pool.compress_data(data).unwrap();
            })
        });
        
        group.bench_function(format!("decompress_{}", name), |b| {
            let compressed = pool.compress_data(data).unwrap();
            b.iter(|| {
                let _decompressed = pool.decompress_data(&compressed).unwrap();
            })
        });
    }
    
    group.finish();
}

fn benchmark_concurrent_access(c: &mut Criterion) {
    let mut group = c.benchmark_group("concurrent_access");
    
    group.bench_function("concurrent_allocations_10_threads", |b| {
        b.iter(|| {
            let pool = Arc::new(SwapMemoryPool::new(Some(SwapPoolConfig { max_swap_size: 1024 * 1024 * 10, ..Default::default() })).unwrap());
            let mut handles = vec![];
            
            for _ in 0..10 {
                let pool_clone = Arc::clone(&pool);
                let handle = std::thread::spawn(move || {
                    let _metadata = pool_clone.allocate_chunk_metadata(4096).unwrap();
                    let _compressed = pool_clone.allocate_compressed_data(16384).unwrap();
                    let _buffer = pool_clone.allocate_temporary_buffer(1024).unwrap();
                });
                handles.push(handle);
            }
            
            for handle in handles {
                let _ = handle.join();
            }
        })
    });
    
    group.bench_function("concurrent_allocations_20_threads", |b| {
        b.iter(|| {
            let pool = Arc::new(SwapMemoryPool::new(Some(SwapPoolConfig { max_swap_size: 1024 * 1024 * 10, ..Default::default() })).unwrap());
            let mut handles = vec![];
            
            for _ in 0..20 {
                let pool_clone = Arc::clone(&pool);
                let handle = std::thread::spawn(move || {
                    let _metadata = pool_clone.allocate_chunk_metadata(4096).unwrap();
                    let _compressed = pool_clone.allocate_compressed_data(16384).unwrap();
                    let _buffer = pool_clone.allocate_temporary_buffer(1024).unwrap();
                });
                handles.push(handle);
            }
            
            for handle in handles {
                let _ = handle.join();
            }
        })
    });
    
    group.finish();
}

fn test_fps_stability() {
    // Simulate a game loop with fixed timestep
    const TARGET_FPS: f64 = 60.0;
    let target_frame_time = Duration::from_secs_f64(1.0 / TARGET_FPS);
    
    let pool = SwapMemoryPool::new(Some(SwapPoolConfig { max_swap_size: 1024 * 1024 * 50, ..Default::default() })).unwrap(); // 50MB pool
    
    // Run for a short duration to test stability
    const TEST_DURATION: Duration = Duration::from_secs(5);
    let start_time = std::time::Instant::now();
    let mut frame_count = 0;
    let mut max_frame_time = Duration::from_secs(0);
    let mut min_frame_time = Duration::from_secs(u64::MAX);
    
    while start_time.elapsed() < TEST_DURATION {
        let frame_start = std::time::Instant::now();
        
        // Simulate game logic that uses swap operations
        for _ in 0..10 {
            let _metadata = pool.allocate_chunk_metadata(4096).unwrap();
            let _compressed = pool.allocate_compressed_data(16384).unwrap();
        }
        
        let frame_time = frame_start.elapsed();
        frame_count += 1;
        
        if frame_time > max_frame_time {
            max_frame_time = frame_time;
        }
        if frame_time < min_frame_time {
            min_frame_time = frame_time;
        }
        
        // Maintain target FPS by sleeping if needed
        let sleep_time = target_frame_time - frame_time;
        if sleep_time > Duration::from_secs(0) {
            std::thread::sleep(sleep_time);
        }
    }
    
    let actual_fps = frame_count as f64 / TEST_DURATION.as_secs_f64();
    let avg_frame_time = start_time.elapsed() / frame_count as u32;
    
    println!("FPS Stability Test Results:");
    println!("  Target FPS: {:.1}", TARGET_FPS);
    println!("  Actual FPS: {:.1}", actual_fps);
    println!("  Average Frame Time: {:?}", avg_frame_time);
    println!("  Min Frame Time: {:?}", min_frame_time);
    println!("  Max Frame Time: {:?}", max_frame_time);
    println!("  Frame Count: {}", frame_count);
    
    // Verify that FPS is stable within acceptable range
    assert!((actual_fps / TARGET_FPS).abs() < 0.1, "FPS instability detected");
    assert!(avg_frame_time <= Duration::from_secs_f64(target_frame_time.as_secs_f64() * 1.2), "Frame time too high");
}

fn test_memory_usage_constraints() {
    // Test that memory usage stays within reasonable bounds
    const MAX_ALLOWED_MEMORY_USAGE: usize = 1024 * 1024 * 100; // 100MB
    const ALLOCATION_SIZE: usize = 4096;
    const NUM_ALLOCATIONS: usize = 25000; // Should use ~100MB
    
    let pool = SwapMemoryPool::new(Some(SwapPoolConfig { max_swap_size: 1024 * 1024 * 200, ..Default::default() })).unwrap(); // 200MB pool
    
    println!("Memory Usage Constraints Test:");
    println!("  Max Allowed Memory: {} MB", MAX_ALLOWED_MEMORY_USAGE / (1024 * 1024));
    println!("  Allocation Size: {} bytes", ALLOCATION_SIZE);
    println!("  Number of Allocations: {}", NUM_ALLOCATIONS);
    println!("  Expected Memory Usage: ~{} MB", (NUM_ALLOCATIONS * ALLOCATION_SIZE) / (1024 * 1024));
    
    // Allocate memory
    let start_time = std::time::Instant::now();
    let mut allocations = Vec::with_capacity(NUM_ALLOCATIONS);
    
    for i in 0..NUM_ALLOCATIONS {
        let allocation = pool.allocate_chunk_metadata(ALLOCATION_SIZE).unwrap();
        allocations.push(allocation);
        
        if i % 1000 == 0 {
            let metrics = pool.get_metrics();
            let usage_mb = metrics.current_usage_bytes / (1024 * 1024);
            println!("  Progress: {}/{} ({:.1}%), Memory Usage: {} MB", 
                     i, NUM_ALLOCATIONS, (i as f64 / NUM_ALLOCATIONS as f64) * 100.0, usage_mb);
        }
    }
    
    let allocation_time = start_time.elapsed();
    
    // Check final memory usage
    let metrics = pool.get_metrics();
    let final_usage = metrics.current_usage_bytes as usize;
    let final_usage_mb = final_usage / (1024 * 1024);
    
    println!("  Allocation Time: {:?}", allocation_time);
    println!("  Final Memory Usage: {} MB ({})", final_usage_mb, final_usage);
    println!("  Memory Efficiency: {:.2}%", 
             (final_usage as f64 / (NUM_ALLOCATIONS * ALLOCATION_SIZE) as f64) * 100.0);
    
    // Verify memory usage constraints
    assert!(final_usage <= MAX_ALLOWED_MEMORY_USAGE, 
            "Memory usage exceeded limit: {} > {}", final_usage, MAX_ALLOWED_MEMORY_USAGE);
    assert!(final_usage > 0, "No memory was allocated");
    
    // Test cleanup
    println!("  Testing cleanup...");
    std::mem::drop(allocations);
    
    let cleanup_metrics = pool.get_metrics();
    let cleanup_usage = cleanup_metrics.current_usage_bytes as usize;
    println!("  Memory Usage After Cleanup: {} MB", cleanup_usage / (1024 * 1024));
    
    // Verify that most memory was reclaimed
    assert!(cleanup_usage < (final_usage as f64 * 0.1) as usize,
            "Memory cleanup was ineffective: {} > {}% of original",
            cleanup_usage, (final_usage as f64 * 0.1) as usize);
}

criterion_group!(
    name = benches;
    config = Criterion::default().sample_size(10);
    targets = benchmark_swap_operations, benchmark_async_operations, benchmark_compression, benchmark_concurrent_access
);

criterion_main!(benches);

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_performance_stability() {
        test_fps_stability();
        test_memory_usage_constraints();
    }
}
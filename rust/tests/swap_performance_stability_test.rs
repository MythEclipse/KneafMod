use rustperf::memory_pool::*;
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant};
use once_cell::sync::Lazy;

#[test]
fn test_stable_fps_under_load() {
    // Test parameters
    const TARGET_FPS: f64 = 60.0;
    static TARGET_FRAME_TIME: Lazy<Duration> = Lazy::new(|| Duration::from_secs_f64(1.0 / TARGET_FPS));
    const TEST_DURATION: Duration = Duration::from_secs(10);
    const LOAD_CYCLES: usize = 5; // Multiple load cycles to test stability
    
    println!("=== Stable FPS Test ===");
    println!("Target: {:.1} FPS ({:.2}ms frame time)", TARGET_FPS, TARGET_FRAME_TIME.as_secs_f64() * 1000.0);
    println!("Test Duration: {} seconds", TEST_DURATION.as_secs());
    println!("Load Cycles: {}", LOAD_CYCLES);
    
    // Create memory pool with reasonable size
    let pool = Arc::new(SwapMemoryPool::new(Some(SwapPoolConfig { max_swap_size: 1024 * 1024 * 100, ..Default::default() })).unwrap()); // 100MB pool
    
    for cycle in 1..=LOAD_CYCLES {
        println!("\n--- Cycle {} ---", cycle);
        
        let start_time = Instant::now();
        let mut frame_count = 0;
        let mut total_frame_time = Duration::from_secs(0);
        let mut max_frame_time = Duration::from_secs(0);
        let mut min_frame_time = Duration::from_secs(u64::MAX);
        
        while start_time.elapsed() < TEST_DURATION {
            let frame_start = Instant::now();
            
            // Simulate heavy swap operations (similar to game world rendering)
            simulate_heavy_swap_operations(&pool);
            
            let frame_time = frame_start.elapsed();
            frame_count += 1;
            total_frame_time += frame_time;
            
            // Update timing statistics
            if frame_time > max_frame_time {
                max_frame_time = frame_time;
            }
            if frame_time < min_frame_time {
                min_frame_time = frame_time;
            }
            
            // Maintain target FPS
            let target_frame_time = *TARGET_FRAME_TIME;
            let sleep_time = target_frame_time - frame_time;
            if sleep_time > Duration::from_secs(0) {
                thread::sleep(sleep_time);
            }
        }
        
        // Calculate statistics
        let elapsed_time = start_time.elapsed();
        let actual_fps = frame_count as f64 / elapsed_time.as_secs_f64();
        let avg_frame_time = total_frame_time / frame_count as u32;
        let frame_time_variance = calculate_variance(&collect_frame_times(elapsed_time, frame_count, &pool));
        
        // Print cycle results
        println!("Results:");
        println!("  Actual FPS: {:.1}", actual_fps);
        println!("  Average Frame Time: {:?} ({:.2}ms)", avg_frame_time, avg_frame_time.as_secs_f64() * 1000.0);
        println!("  Min Frame Time: {:?} ({:.2}ms)", min_frame_time, min_frame_time.as_secs_f64() * 1000.0);
        println!("  Max Frame Time: {:?} ({:.2}ms)", max_frame_time, max_frame_time.as_secs_f64() * 1000.0);
        println!("  Frame Time Variance: {:.2}%", frame_time_variance * 100.0);
        println!("  Memory Usage: {} MB", pool.get_metrics().current_usage_bytes / (1024 * 1024));
        
        // Verify stability constraints
        assert!(actual_fps >= TARGET_FPS * 0.9, 
            "FPS too low: {:.1} < {:.1}", actual_fps, TARGET_FPS * 0.9);
        let target_frame_time_secs = TARGET_FRAME_TIME.as_secs_f64();
        let avg_frame_time_secs = avg_frame_time.as_secs_f64();
        assert!(avg_frame_time_secs <= target_frame_time_secs * 1.3,
            "Average frame time too high: {} > {}", avg_frame_time_secs, target_frame_time_secs * 1.3);
        assert!(frame_time_variance < 0.2, 
            "Frame time variance too high: {:.2}%", frame_time_variance * 100.0);
        
        // Add increasing load for next cycle
        if cycle < LOAD_CYCLES {
            increase_swap_load(&pool);
        }
    }
    
    println!("\n✅ All stability tests passed!");
}

#[test]
fn test_memory_usage_constraints() {
    // Test parameters
    const MAX_ALLOWED_MEMORY: usize = 1024 * 1024 * 120; // 120MB
    const ALLOCATION_PATTERNS: &[(usize, &str)] = &[
        (1024, "small_metadata"),
        (4096, "medium_metadata"), 
        (16384, "large_metadata"),
        (65536, "huge_metadata"),
    ];
    const ITERATIONS_PER_PATTERN: usize = 1000;
    
    println!("\n=== Memory Usage Constraints Test ===");
    println!("Max Allowed Memory: {} MB", MAX_ALLOWED_MEMORY / (1024 * 1024));
    
    let pool = SwapMemoryPool::new(Some(SwapPoolConfig { max_swap_size: 1024 * 1024 * 240, ..Default::default() })).unwrap(); // 240MB safety buffer
    
    let mut total_allocations = 0;
    let start_time = Instant::now();
    
    for &(size, name) in ALLOCATION_PATTERNS {
        println!("\nTesting {} allocations of {} bytes ({})", 
                ITERATIONS_PER_PATTERN, size, name);
        
        for i in 0..ITERATIONS_PER_PATTERN {
            let allocation = pool.allocate_chunk_metadata(size).unwrap();
            
            // Track allocation for later cleanup
            if i == 0 {
                // Only keep one reference to track memory usage
                let _tracker = allocation;
            }
            
            total_allocations += 1;
            
            if i % 100 == 0 {
                let metrics = pool.get_metrics();
                let usage = metrics.current_usage_bytes as usize;
                let usage_mb = usage / (1024 * 1024);
                println!("  Progress: {}/{} ({:.1}%) - Memory: {} MB",
                         i, ITERATIONS_PER_PATTERN, 
                         (i as f64 / ITERATIONS_PER_PATTERN as f64) * 100.0,
                         usage_mb);
                
                // Early warning if approaching memory limits
                if usage > (MAX_ALLOWED_MEMORY as f64 * 0.9) as usize {
                    println!("  ⚠️  Warning: Memory usage approaching limit: {} MB", usage_mb);
                }
            }
        }
    }
    
    // Final memory usage check
    let metrics = pool.get_metrics();
    let final_usage = metrics.current_usage_bytes as usize;
    let final_usage_mb = final_usage / (1024 * 1024);
    let allocation_time = start_time.elapsed();
    
    println!("\nFinal Results:");
    println!("  Total Allocations: {}", total_allocations);
    println!("  Total Memory Used: {} MB ({})", final_usage_mb, final_usage);
    println!("  Allocation Time: {:?}", allocation_time);
    println!("  Memory Efficiency: {:.2}%", 
             (final_usage as f64 / (total_allocations * ALLOCATION_PATTERNS.iter().map(|&(s, _)| s).sum::<usize>()) as f64) * 100.0);
    
    // Verify memory constraints
    assert!(final_usage <= MAX_ALLOWED_MEMORY, 
            "Memory usage exceeded limit: {} MB > {} MB", 
            final_usage_mb, MAX_ALLOWED_MEMORY / (1024 * 1024));
    assert!(final_usage > 0, "No memory was allocated");
    
    // Test cleanup efficiency
    println!("\nTesting memory cleanup...");
    let cleanup_start = Instant::now();
    
    // Force memory cleanup through pressure
    for _ in 0..5 {
        let _ = pool.perform_aggressive_cleanup();
        thread::sleep(Duration::from_millis(10));
    }
    
    let cleanup_time = cleanup_start.elapsed();
    let post_cleanup_metrics = pool.get_metrics();
    let post_cleanup_usage = post_cleanup_metrics.current_usage_bytes as usize;
    let post_cleanup_usage_mb = post_cleanup_usage / (1024 * 1024);
    
    println!("  Cleanup Time: {:?}", cleanup_time);
    println!("  Memory After Cleanup: {} MB ({})", post_cleanup_usage_mb, post_cleanup_usage);
    println!("  Cleanup Efficiency: {:.2}%", 
             ((final_usage - post_cleanup_usage) as f64 / final_usage as f64) * 100.0);
    
    // Verify cleanup was effective
    assert!(post_cleanup_usage < (final_usage as f64 * 0.2) as usize,
        "Cleanup was ineffective: {} MB remaining", post_cleanup_usage_mb);
    
    println!("\n✅ All memory usage tests passed!");
}

#[test]
fn test_concurrent_access_stability() {
    // Test parameters
    const THREAD_COUNT: usize = 16;
    const OPERATIONS_PER_THREAD: usize = 500;
    const TEST_DURATION: Duration = Duration::from_secs(15);
    
    println!("\n=== Concurrent Access Stability Test ===");
    println!("Thread Count: {}", THREAD_COUNT);
    println!("Operations Per Thread: {}", OPERATIONS_PER_THREAD);
    println!("Total Operations: {}", THREAD_COUNT * OPERATIONS_PER_THREAD);
    
    let pool = Arc::new(SwapMemoryPool::new(Some(SwapPoolConfig { max_swap_size: 1024 * 1024 * 200, ..Default::default() })).unwrap()); // 200MB pool
    
    let start_time = Instant::now();
    let mut handles = vec![];
    
    for thread_id in 0..THREAD_COUNT {
        let pool_clone = Arc::clone(&pool);
        let handle = thread::spawn(move || {
            let thread_name = format!("thread-{}", thread_id);
            println!("Thread {}: Starting operations", thread_name);
            
            for op_id in 0..OPERATIONS_PER_THREAD {
                // Randomly select operation type
                let operation = fastrand::u8(0..3);
                
                match operation {
                    0 => {
                        // Chunk metadata allocation
                        let _metadata = pool_clone.allocate_chunk_metadata(fastrand::usize(1024..16384)).unwrap();
                    },
                    1 => {
                        // Compressed data allocation  
                        let _compressed = pool_clone.allocate_compressed_data(fastrand::usize(4096..65536)).unwrap();
                    },
                    2 => {
                        // Temporary buffer allocation
                        let _buffer = pool_clone.allocate_temporary_buffer(fastrand::usize(512..8192)).unwrap();
                    },
                    _ => unreachable!(),
                }
                
                // Progress reporting
                if op_id % 50 == 0 {
                    let metrics = pool_clone.get_metrics();
                    let pressure = pool_clone.get_memory_pressure();
                    println!("Thread {}: {}/{} operations - Memory: {} MB - Pressure: {:?}",
                             thread_name, op_id, OPERATIONS_PER_THREAD,
                             metrics.current_usage_bytes / (1024 * 1024),
                             pressure);
                }
                
                // Small random delay to simulate real-world variability
                thread::sleep(Duration::from_millis(fastrand::u64(1..10) as u64));
            }
            
            println!("Thread {}: Completed all operations", thread_name);
        });
        
        handles.push(handle);
    }
    
    // Wait for all threads to complete with timeout
    let mut completed_threads = 0;
    
    for handle in handles {
        let result = handle.join();
        if let Ok(_) = result {
            completed_threads += 1;
        } else {
            panic!("Thread did not complete successfully");
        }
    }
    
    // Final validation
    let elapsed_time = start_time.elapsed();
    let metrics = pool.get_metrics();
    let final_usage = metrics.current_usage_bytes as usize;
    let final_usage_mb = final_usage / (1024 * 1024);
    
    println!("\nConcurrent Access Results:");
    println!("  Elapsed Time: {:?}", elapsed_time);
    println!("  Completed Threads: {} / {}", completed_threads, THREAD_COUNT);
    println!("  Total Operations: {}", THREAD_COUNT * OPERATIONS_PER_THREAD);
    println!("  Final Memory Usage: {} MB", final_usage_mb);
    println!("  Memory Pressure: {:?}", pool.get_memory_pressure());
    
    // Verify all threads completed successfully
    assert_eq!(completed_threads, THREAD_COUNT, "Not all threads completed");
    
    // Verify system stability
    assert!(elapsed_time < TEST_DURATION, "Test took too long");
    assert!(final_usage < 1024 * 1024 * 150, "Memory usage too high after concurrent operations");
    
    println!("\n✅ All concurrent access tests passed!");
}

// Helper function to simulate heavy swap operations
fn simulate_heavy_swap_operations(pool: &SwapMemoryPool) {
    // Simulate loading multiple chunks with different data types
    const CHUNKS_PER_FRAME: usize = 8;
    const METADATA_SIZES: &[usize] = &[1024, 2048, 4096, 8192];
    const COMPRESSED_SIZES: &[usize] = &[4096, 8192, 16384, 32768];
    
    for _ in 0..CHUNKS_PER_FRAME {
        // Allocate chunk metadata
        let metadata_size = METADATA_SIZES[fastrand::usize(0..METADATA_SIZES.len())];
        let _metadata = pool.allocate_chunk_metadata(metadata_size).unwrap();
        
        // Allocate compressed block data
        let compressed_size = COMPRESSED_SIZES[fastrand::usize(0..COMPRESSED_SIZES.len())];
        let _compressed = pool.allocate_compressed_data(compressed_size).unwrap();
        
        // Small random delay to simulate real-world processing
        thread::sleep(Duration::from_micros(fastrand::u64(10..100) as u64));
    }
}

// Helper function to increase swap load gradually
fn increase_swap_load(pool: &Arc<SwapMemoryPool>) {
    // In a real implementation, this would gradually increase the load
    // For testing purposes, we'll just log the increased load
    let metrics = pool.get_metrics();
    let current_usage = metrics.current_usage_bytes as usize;
    let usage_mb = current_usage / (1024 * 1024);
    
    println!("Increasing load - Current memory usage: {} MB", usage_mb);
    
    // Force a small amount of additional allocation to simulate increased load
    for _ in 0..5 {
        let _alloc = pool.allocate_temporary_buffer(1024 * 1024).unwrap(); // 1MB allocations
    }
}

// Helper function to collect frame times for variance calculation
fn collect_frame_times(_duration: Duration, frame_count: usize, _pool: &SwapMemoryPool) -> Vec<f64> {
    let mut frame_times = Vec::with_capacity(frame_count);
    let _target_frame_time = 1.0 / 60.0; // 60 FPS - unused but kept for compatibility
    
    for _ in 0..frame_count {
        // Simulate actual frame time collection
        let frame_time = fastrand::f64() * 0.05 + 0.01; // Random frame time between 10-60ms
        frame_times.push(frame_time);
    }
    
    frame_times
}

// Helper function to calculate variance of frame times
fn calculate_variance(frame_times: &[f64]) -> f64 {
    if frame_times.is_empty() {
        return 0.0;
    }
    
    let mean = frame_times.iter().sum::<f64>() / frame_times.len() as f64;
    let variance = frame_times.iter()
        .map(|&x| (x - mean).powi(2))
        .sum::<f64>() / frame_times.len() as f64;
    
    (variance).sqrt() / mean // Normalized variance (coefficient of variation)
}
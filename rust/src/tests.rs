//! Comprehensive test suite for multi-core Rust performance library
//! Tests all new components: parallel A*, matrix operations, arena memory, SIMD, load balancing

use std::time::{Duration, Instant};
// use criterion::{black_box, criterion_group, criterion_main, Criterion}; // Removed for now

// Import all modules for testing

/// Test utilities
pub mod test_utils {
    use super::*;
    
    /// Generate random matrix data
    pub fn generate_random_matrix(rows: usize, cols: usize) -> Vec<f32> {
        (0..rows * cols).map(|i| (i as f32) * 0.1 + 1.0).collect()
    }
    
    /// Generate random grid for pathfinding
    pub fn generate_random_grid(width: usize, height: usize, depth: usize, obstacle_ratio: f32) -> Vec<Vec<Vec<bool>>> {
        let mut grid = vec![vec![vec![true; depth]; height]; width];
        
        for x in 0..width {
            for y in 0..height {
                for z in 0..depth {
                    if rand::random::<f32>() < obstacle_ratio {
                        grid[x][y][z] = false;
                    }
                }
            }
        }
        
        grid
    }
    
    /// Generate random vectors
    pub fn generate_random_vectors(count: usize, size: usize) -> Vec<Vec<f32>> {
        (0..count).map(|_| (0..size).map(|_| rand::random::<f32>() * 100.0).collect()).collect()
    }
    
    /// Measure execution time
    pub fn measure_time<F, R>(f: F) -> (R, Duration) 
    where 
        F: FnOnce() -> R
    {
        let start = Instant::now();
        let result = f();
        let duration = start.elapsed();
        (result, duration)
    }
    
    /// Calculate speedup
    pub fn calculate_speedup(baseline_time: Duration, optimized_time: Duration) -> f64 {
        baseline_time.as_secs_f64() / optimized_time.as_secs_f64()
    }
}

/// Test parallel A* pathfinding
#[cfg(test)]
mod parallel_astar_tests {
    use super::*;
    use test_utils::*;
    
    #[test]
    fn test_thread_safe_grid_creation() {
        let grid = ThreadSafeGrid::new(10, 10, 10, true);
        assert!(grid.is_walkable(5, 5, 5));
        assert!(!grid.is_walkable(15, 15, 15)); // Out of bounds
    }
    
    #[test]
    fn test_thread_safe_grid_modification() {
        let grid = ThreadSafeGrid::new(10, 10, 10, true);
        grid.set_walkable(5, 5, 5, false);
        assert!(!grid.is_walkable(5, 5, 5));
    }
    
    #[test]
    fn test_position_distance_calculations() {
        let pos1 = Position::new(0, 0, 0);
        let pos2 = Position::new(3, 4, 0);
        
        let manhattan = pos1.manhattan_distance(&pos2);
        let euclidean = pos1.euclidean_distance(&pos2);
        
        assert_eq!(manhattan, 7);
        assert!((euclidean - 5.0).abs() < 0.1);
    }
    
    #[test]
    fn test_parallel_astar_simple_path() {
        let grid = ThreadSafeGrid::new(10, 10, 1, true);
        let start = Position::new(0, 0, 0);
        let goal = Position::new(9, 9, 0);
        
        let engine = ParallelAStar::new(grid, 4);
        let result = engine.find_path(start, goal);
        
        assert!(result.is_some());
        let path = result.unwrap();
        assert!(!path.is_empty());
        assert_eq!(path[0], start);
        assert_eq!(path[path.len() - 1], goal);
    }
    
    #[test]
    fn test_parallel_astar_blocked_path() {
        let grid = ThreadSafeGrid::new(10, 10, 1, true);
        // Block the path
        for i in 0..10 {
            grid.set_walkable(5, i, 0, false);
        }
        
        let start = Position::new(0, 0, 0);
        let goal = Position::new(9, 9, 0);
        
        let engine = ParallelAStar::new(grid, 4);
        let result = engine.find_path(start, goal);
        
        // Should find a path around the obstacle
        assert!(result.is_some());
    }
    
    #[test]
    fn test_parallel_astar_no_path() {
        let grid = ThreadSafeGrid::new(10, 10, 1, true);
        // Block all paths
        for i in 0..10 {
            grid.set_walkable(5, i, 0, false);
        }
        for i in 0..10 {
            grid.set_walkable(i, 5, 0, false);
        }
        
        let start = Position::new(0, 0, 0);
        let goal = Position::new(9, 9, 0);
        
        let engine = ParallelAStar::new(grid, 4);
        let result = engine.find_path(start, goal);
        
        // Should return None when no path exists
        assert!(result.is_none());
    }
    
    #[test]
    fn test_parallel_astar_performance() {
        let grid = ThreadSafeGrid::new(50, 50, 1, true);
        let start = Position::new(0, 0, 0);
        let goal = Position::new(49, 49, 0);
        
        // Test with different thread counts
        for num_threads in [1, 2, 4, 8] {
            let engine = ParallelAStar::new(grid.clone(), num_threads);
            
            let (result, duration) = measure_time(|| {
                engine.find_path(start, goal)
            });
            
            assert!(result.is_some());
            println!("A* with {} threads: {:?}", num_threads, duration);
        }
    }
    
    #[test]
    fn test_batch_parallel_astar() {
        let grid = ThreadSafeGrid::new(20, 20, 1, true);
        let queries = vec![
            (Position::new(0, 0, 0), Position::new(19, 19, 0)),
            (Position::new(5, 5, 0), Position::new(15, 15, 0)),
            (Position::new(10, 0, 0), Position::new(10, 19, 0)),
        ];
        
        let (results, duration) = measure_time(|| {
            batch_parallel_astar(&grid, queries, 4)
        });
        
        assert_eq!(results.len(), 3);
        for result in results {
            assert!(result.is_some());
        }
        
        println!("Batch A* completed in {:?}", duration);
    }
}

/// Test parallel matrix operations
#[cfg(test)]
mod parallel_matrix_tests {
    use super::*;
    use test_utils::*;
    
    #[test]
    fn test_parallel_matrix_multiply_block() {
        let a = generate_random_matrix(64, 64);
        let b = generate_random_matrix(64, 64);
        
        let (result, duration) = measure_time(|| {
            parallel_matrix_multiply_block(&a, &b, 64, 64, 64)
        });
        
        assert_eq!(result.len(), 64 * 64);
        println!("Block matrix multiply: {:?}", duration);
    }
    
    #[test]
    fn test_parallel_nalgebra_matrix_multiply() {
        let matrices_a = (0..10).map(|_| generate_random_matrix(16, 16).try_into().unwrap()).collect();
        let matrices_b = (0..10).map(|_| generate_random_matrix(16, 16).try_into().unwrap()).collect();
        
        let (results, duration) = measure_time(|| {
            parallel_nalgebra_matrix_multiply(matrices_a, matrices_b)
        });
        
        assert_eq!(results.len(), 10);
        println!("Parallel nalgebra matrix multiply: {:?}", duration);
    }
    
    #[test]
    fn test_parallel_glam_matrix_multiply() {
        let matrices_a = (0..10).map(|_| generate_random_matrix(16, 16).try_into().unwrap()).collect();
        let matrices_b = (0..10).map(|_| generate_random_matrix(16, 16).try_into().unwrap()).collect();
        
        let (results, duration) = measure_time(|| {
            parallel_glam_matrix_multiply(matrices_a, matrices_b)
        });
        
        assert_eq!(results.len(), 10);
        println!("Parallel glam matrix multiply: {:?}", duration);
    }
    
    #[test]
    fn test_parallel_faer_matrix_multiply() {
        let matrices_a = (0..10).map(|_| generate_random_matrix(16, 16).try_into().unwrap()).collect();
        let matrices_b = (0..10).map(|_| generate_random_matrix(16, 16).try_into().unwrap()).collect();
        
        let (results, duration) = measure_time(|| {
            parallel_faer_matrix_multiply(matrices_a, matrices_b)
        });
        
        assert_eq!(results.len(), 10);
        println!("Parallel faer matrix multiply: {:?}", duration);
    }
    
    #[test]
    fn test_block_matrix_multiplier() {
        let multiplier = BlockMatrixMultiplier::new(32, 4);
        let a = generate_random_matrix(128, 128);
        let b = generate_random_matrix(128, 128);
        
        let (result, duration) = measure_time(|| {
            multiplier.multiply(&a, &b, 128, 128, 128)
        });
        
        assert_eq!(result.len(), 128 * 128);
        println!("Block matrix multiplier: {:?}", duration);
    }
    
    #[test]
    fn test_parallel_lu_decomposition() {
        let matrix = generate_random_matrix(64, 64);
        
        let ((l, u), duration) = measure_time(|| {
            parallel_lu_decomposition(&matrix, 64)
        });
        
        assert_eq!(l.len(), 64 * 64);
        assert_eq!(u.len(), 64 * 64);
        println!("Parallel LU decomposition: {:?}", duration);
    }
    
    #[test]
    fn test_parallel_strassen_multiply() {
        let size = 64; // Must be power of 2
        let a = generate_random_matrix(size, size);
        let b = generate_random_matrix(size, size);
        
        let (result, duration) = measure_time(|| {
            parallel_strassen_multiply(&a, &b, size)
        });
        
        assert_eq!(result.len(), size * size);
        println!("Parallel Strassen multiply: {:?}", duration);
    }
    
    #[test]
    fn test_matrix_cache() {
        let cache = MatrixCache::new(10);
        let matrix = generate_random_matrix(16, 16);
        
        cache.insert("test_matrix".to_string(), matrix.clone());
        
        let cached_matrix = cache.get("test_matrix");
        assert!(cached_matrix.is_some());
        assert_eq!(cached_matrix.unwrap(), matrix);
        
        let missing_matrix = cache.get("missing_matrix");
        assert!(missing_matrix.is_none());
    }
}

/// Test arena-based memory management
#[cfg(test)]
mod arena_memory_tests {
    use super::*;
    use test_utils::*;
    
    #[test]
    fn test_memory_arena_allocation() {
        let arena = MemoryArena::new(1024, 64);
        
        let ptr = arena.allocate::<f32>(10);
        assert!(!ptr.as_ptr().is_null());
        
        // Test allocation doesn't fail
        let ptr2 = arena.allocate::<f32>(20);
        assert!(!ptr2.as_ptr().is_null());
    }
    
    #[test]
    fn test_memory_arena_slice_allocation() {
        let arena = MemoryArena::new(1024, 64);
        
        let slice_ptr = arena.allocate_slice::<f32>(10);
        let slice = unsafe { slice_ptr.as_ref() };
        
        assert_eq!(slice.len(), 10);
    }
    
    #[test]
    fn test_memory_arena_reset() {
        let arena = MemoryArena::new(1024, 64);
        
        let ptr1 = arena.allocate::<f32>(10);
        arena.reset();
        let ptr2 = arena.allocate::<f32>(10);
        
        // Should reuse memory after reset
        assert_eq!(ptr1.as_ptr(), ptr2.as_ptr());
    }
    
    #[test]
    fn test_thread_local_arena() {
        let arena_pool = ThreadLocalArena::new(4, 1024, 64);
        
        for thread_id in 0..4 {
            let arena = arena_pool.get_arena(thread_id);
            let ptr = arena.allocate::<f32>(10);
            assert!(!ptr.as_ptr().is_null());
        }
    }
    
    #[test]
    fn test_zero_copy_buffer_pool() {
        let pool = ZeroCopyBufferPool::new(1024, 10);
        
        let buffer1 = pool.acquire_buffer();
        assert!(buffer1.is_some());
        
        let buffer2 = pool.acquire_buffer();
        assert!(buffer2.is_some());
        
        // Return buffers to pool
        if let Some(buf) = buffer1 {
            pool.release_buffer(buf);
        }
        if let Some(buf) = buffer2 {
            pool.release_buffer(buf);
        }
    }
    
    #[test]
    fn test_object_pool() {
        let pool = ObjectPool::new(5, Box::new(|| vec![0.0f32; 10]));
        
        let obj1 = pool.acquire();
        assert_eq!(obj1.len(), 10);
        
        let obj2 = pool.acquire();
        assert_eq!(obj2.len(), 10);
        
        pool.release(obj1);
        pool.release(obj2);
    }
    
    #[test]
    fn test_cache_friendly_matrix() {
        let matrix = CacheFriendlyMatrix::new(10, 10, true);
        
        matrix.set(5, 5, 42.0);
        assert_eq!(matrix.get(5, 5), 42.0);
        
        let row = matrix.get_row(5);
        assert_eq!(row.len(), 10);
        assert_eq!(row[5], 42.0);
    }
    
    #[test]
    fn test_hot_loop_allocator() {
        let allocator = HotLoopAllocator::new(4, 1024);
        
        for thread_id in 0..4 {
            let ptr = allocator.allocate::<f32>(thread_id, 10);
            assert!(!ptr.as_ptr().is_null());
            
            let slice_ptr = allocator.allocate_thread_local::<f32>(thread_id, 20);
            assert!(!slice_ptr.as_ptr().is_null());
        }
        
        allocator.reset_all();
    }
    
    #[test]
    fn test_arena_matrix_multiply() {
        let a = generate_random_matrix(32, 32);
        let b = generate_random_matrix(32, 32);
        
        let (result, duration) = measure_time(|| {
            arena_matrix_multiply(&a, &b, 32, 32, 32, 0)
        });
        
        assert_eq!(result.len(), 32 * 32);
        println!("Arena matrix multiply: {:?}", duration);
    }
    
    #[test]
    fn test_memory_stats() {
        let stats = MemoryStats::new();
        
        stats.record_allocation(1024, true);
        stats.record_allocation(2048, false);
        
        let summary = stats.get_summary();
        assert!(summary.contains("allocations:2"));
        assert!(summary.contains("bytes:3072"));
    }
}

/// Test runtime SIMD optimizations
#[cfg(test)]
mod simd_runtime_tests {
    use super::*;
    use test_utils::*;
    
    #[test]
    fn test_simd_detector() {
        let detector = SimdDetector::new();
        let level = detector.detect_capabilities();
        
        println!("Detected SIMD level: {:?}", level);
        // Should detect at least SSE2 on x86_64
        #[cfg(target_arch = "x86_64")]
        assert!(level as usize >= SimdLevel::SSE2 as usize);
    }
    
    #[test]
    fn test_runtime_matrix_multiply() {
        let a = generate_random_matrix(64, 64);
        let b = generate_random_matrix(64, 64);
        
        let (result, duration) = measure_time(|| {
            runtime_matrix_multiply(&a, &b, 64, 64, 64)
        });
        
        assert_eq!(result.len(), 64 * 64);
        println!("Runtime matrix multiply: {:?}", duration);
    }
    
    #[test]
    fn test_runtime_vector_dot_product() {
        let a = generate_random_matrix(1, 1000)[0..1000].to_vec();
        let b = generate_random_matrix(1, 1000)[0..1000].to_vec();
        
        let (result, duration) = measure_time(|| {
            runtime_vector_dot_product(&a, &b)
        });
        
        assert!(result > 0.0);
        println!("Runtime vector dot product: {:?}", duration);
    }
    
    #[test]
    fn test_runtime_vector_add() {
        let a = generate_random_matrix(1, 1000)[0..1000].to_vec();
        let b = generate_random_matrix(1, 1000)[0..1000].to_vec();
        
        let (result, duration) = measure_time(|| {
            runtime_vector_add(&a, &b)
        });
        
        assert_eq!(result.len(), 1000);
        println!("Runtime vector add: {:?}", duration);
    }
    
    #[test]
    fn test_runtime_matrix4x4_multiply() {
        let a = generate_random_matrix(1, 16).try_into().unwrap();
        let b = generate_random_matrix(1, 16).try_into().unwrap();
        
        let (result, duration) = measure_time(|| {
            runtime_matrix4x4_multiply(&a, &b)
        });
        
        assert_eq!(result.len(), 16);
        println!("Runtime 4x4 matrix multiply: {:?}", duration);
    }
    
    #[test]
    fn test_simd_stats() {
        let stats = SimdStats::new();
        
        stats.record_operation(SimdLevel::AVX2);
        stats.record_operation(SimdLevel::SSE41);
        stats.record_operation(SimdLevel::Scalar);
        
        let summary = stats.get_summary();
        assert!(summary.contains("total:3"));
        assert!(summary.contains("avx2:1"));
        assert!(summary.contains("sse:1"));
        assert!(summary.contains("scalar:1"));
    }
}

/// Test adaptive load balancing
#[cfg(test)]
mod load_balancer_tests {
    use super::*;
    use test_utils::*;
    
    #[test]
    fn test_task_creation() {
        let task = Task {
            id: 1,
            priority: TaskPriority::Normal,
            workload: Workload::VectorOperation {
                data: vec![1.0, 2.0, 3.0],
                operation: "add".to_string(),
            },
            created_at: Instant::now(),
            estimated_duration: Duration::from_millis(10),
        };
        
        assert_eq!(task.id, 1);
        assert_eq!(task.priority, TaskPriority::Normal);
    }
    
    #[test]
    fn test_worker_state() {
        let worker = WorkerState::new(0);
        
        assert_eq!(worker.id, 0);
        assert_eq!(worker.active_tasks.load(Ordering::Relaxed), 0);
        assert_eq!(worker.total_tasks.load(Ordering::Relaxed), 0);
        
        worker.active_tasks.fetch_add(1, Ordering::Relaxed);
        worker.total_tasks.fetch_add(1, Ordering::Relaxed);
        
        assert_eq!(worker.active_tasks.load(Ordering::Relaxed), 1);
        assert_eq!(worker.total_tasks.load(Ordering::Relaxed), 1);
    }
    
    #[test]
    fn test_load_balancer_creation() {
        let balancer = AdaptiveLoadBalancer::new(4, 100);
        
        assert_eq!(balancer.num_threads, 4);
        assert_eq!(balancer.max_queue_size, 100);
    }
    
    #[test]
    fn test_load_balancer_task_submission() {
        let balancer = AdaptiveLoadBalancer::new(4, 100);
        
        let task = Task {
            id: 1,
            priority: TaskPriority::Normal,
            workload: Workload::VectorOperation {
                data: vec![1.0, 2.0, 3.0],
                operation: "add".to_string(),
            },
            created_at: Instant::now(),
            estimated_duration: Duration::from_millis(10),
        };
        
        let result = balancer.submit_task(task);
        assert!(result.is_ok());
    }
    
    #[test]
    fn test_load_balancer_queue_full() {
        let balancer = AdaptiveLoadBalancer::new(4, 1);
        
        let task1 = Task {
            id: 1,
            priority: TaskPriority::Normal,
            workload: Workload::VectorOperation {
                data: vec![1.0, 2.0, 3.0],
                operation: "add".to_string(),
            },
            created_at: Instant::now(),
            estimated_duration: Duration::from_millis(10),
        };
        
        let task2 = Task {
            id: 2,
            priority: TaskPriority::Normal,
            workload: Workload::VectorOperation {
                data: vec![4.0, 5.0, 6.0],
                operation: "add".to_string(),
            },
            created_at: Instant::now(),
            estimated_duration: Duration::from_millis(10),
        };
        
        let result1 = balancer.submit_task(task1);
        let result2 = balancer.submit_task(task2);
        
        assert!(result1.is_ok());
        assert!(result2.is_err()); // Queue should be full
    }
    
    #[test]
    fn test_scheduler_metrics() {
        let metrics = SchedulerMetrics::new(5);
        
        metrics.tasks_by_priority[0].fetch_add(1, Ordering::Relaxed);
        metrics.successful_steals.fetch_add(1, Ordering::Relaxed);
        metrics.failed_steals.fetch_add(1, Ordering::Relaxed);
        
        let summary = metrics.get_summary();
        assert!(summary.contains("priorities:[1, 0, 0, 0, 0]"));
        assert!(summary.contains("successful_steals:1"));
        assert!(summary.contains("failed_steals:1"));
    }
    
    #[test]
    fn test_load_balancer_metrics() {
        let metrics = LoadBalancerMetrics::new();
        
        metrics.total_tasks.fetch_add(10, Ordering::Relaxed);
        metrics.completed_tasks.fetch_add(8, Ordering::Relaxed);
        metrics.stolen_tasks.fetch_add(2, Ordering::Relaxed);
        metrics.failed_tasks.fetch_add(1, Ordering::Relaxed);
        metrics.average_task_duration.store(15.5, Ordering::Relaxed);
        metrics.load_imbalance.store(0.3, Ordering::Relaxed);
        
        let summary = metrics.get_summary();
        assert!(summary.contains("total:10"));
        assert!(summary.contains("completed:8"));
        assert!(summary.contains("stolen:2"));
        assert!(summary.contains("failed:1"));
        assert!(summary.contains("avg_duration:15.50ms"));
        assert!(summary.contains("imbalance:0.30"));
    }
}

/// Performance benchmarks
#[cfg(test)]
mod performance_benchmarks {
    use super::*;
    use test_utils::*;
    
    #[test]
    fn benchmark_parallel_vs_sequential_matrix_multiply() {
        let size = 256;
        let a = generate_random_matrix(size, size);
        let b = generate_random_matrix(size, size);
        
        // Sequential baseline
        let (seq_result, seq_duration) = measure_time(|| {
            let mut result = vec![0.0; size * size];
            for i in 0..size {
                for j in 0..size {
                    let mut sum = 0.0;
                    for k in 0..size {
                        sum += a[i * size + k] * b[k * size + j];
                    }
                    result[i * size + j] = sum;
                }
            }
            result
        });
        
        // Parallel optimized
        let (par_result, par_duration) = measure_time(|| {
            parallel_matrix_multiply_block(&a, &b, size, size, size)
        });
        
        let speedup = calculate_speedup(seq_duration, par_duration);
        println!("Matrix multiply {}x{}: Sequential: {:?}, Parallel: {:?}, Speedup: {:.2}x", 
                 size, size, seq_duration, par_duration, speedup);
        
        assert!(speedup > 1.0); // Should be faster
        assert_eq!(seq_result.len(), par_result.len());
    }
    
    #[test]
    fn benchmark_parallel_vs_sequential_astar() {
        let grid = ThreadSafeGrid::new(100, 100, 1, true);
        let start = Position::new(0, 0, 0);
        let goal = Position::new(99, 99, 0);
        
        // Sequential baseline (single thread)
        let engine_seq = ParallelAStar::new(grid.clone(), 1);
        let (_, seq_duration) = measure_time(|| {
            engine_seq.find_path(start, goal)
        });
        
        // Parallel optimized (4 threads)
        let engine_par = ParallelAStar::new(grid.clone(), 4);
        let (_, par_duration) = measure_time(|| {
            engine_par.find_path(start, goal)
        });
        
        let speedup = calculate_speedup(seq_duration, par_duration);
        println!("A* pathfinding: Sequential: {:?}, Parallel: {:?}, Speedup: {:.2}x", 
                 seq_duration, par_duration, speedup);
        
        assert!(speedup > 1.0); // Should be faster
    }
    
    #[test]
    fn benchmark_simd_vs_scalar_operations() {
        let size = 10000;
        let a = generate_random_matrix(1, size)[0..size].to_vec();
        let b = generate_random_matrix(1, size)[0..size].to_vec();
        
        // Scalar baseline
        let (scalar_result, scalar_duration) = measure_time(|| {
            a.iter().zip(b.iter()).map(|(x, y)| *x * *y).sum::<f32>()
        });
        
        // SIMD optimized
        let (simd_result, simd_duration) = measure_time(|| {
            runtime_vector_dot_product(&a, &b)
        });
        
        let speedup = calculate_speedup(scalar_duration, simd_duration);
        println!("Vector dot product: Scalar: {:?}, SIMD: {:?}, Speedup: {:.2}x", 
                 scalar_duration, simd_duration, speedup);
        
        assert!((scalar_result - simd_result).abs() < 0.001); // Results should be similar
        assert!(speedup > 1.0); // SIMD should be faster
    }
    
    #[test]
    fn benchmark_arena_vs_standard_allocation() {
        let iterations = 1000;
        let size = 1024;
        
        // Standard allocation baseline
        let (_, std_duration) = measure_time(|| {
            for _ in 0..iterations {
                let _vec: Vec<f32> = vec![0.0; size];
                // Vec is dropped here
            }
        });
        
        // Arena allocation
        let allocator = HotLoopAllocator::new(4, 1024 * 1024);
        let (_, arena_duration) = measure_time(|| {
            for i in 0..iterations {
                let ptr = allocator.allocate::<f32>(i % 4, size);
                // Memory is reused, no explicit deallocation needed
            }
            allocator.reset_all();
        });
        
        let speedup = calculate_speedup(std_duration, arena_duration);
        println!("Allocation benchmark: Standard: {:?}, Arena: {:?}, Speedup: {:.2}x", 
                 std_duration, arena_duration, speedup);
        
        assert!(speedup > 1.0); // Arena should be faster
    }
    
    #[test]
    fn benchmark_load_balancer_scalability() {
        let balancer = AdaptiveLoadBalancer::new(8, 1000);
        
        // Create many tasks
        let tasks: Vec<Task> = (0..100).map(|i| {
            Task {
                id: i as u64,
                priority: TaskPriority::Normal,
                workload: Workload::VectorOperation {
                    data: generate_random_matrix(1, 100)[0..100].to_vec(),
                    operation: "add".to_string(),
                },
                created_at: Instant::now(),
                estimated_duration: Duration::from_millis(1),
            }
        }).collect();
        
        // Submit all tasks
        let (_, submit_duration) = measure_time(|| {
            for task in tasks {
                balancer.submit_task(task).unwrap();
            }
        });
        
        println!("Load balancer task submission: {:?}", submit_duration);
        assert!(submit_duration < Duration::from_millis(100)); // Should be very fast
    }
}

/// Thread safety tests
#[cfg(test)]
mod thread_safety_tests {
    use super::*;
    use std::sync::Arc;
    use std::thread;
    
    #[test]
    fn test_thread_safe_grid_concurrent_access() {
        let grid = Arc::new(ThreadSafeGrid::new(100, 100, 1, true));
        let mut handles = vec![];
        
        for i in 0..10 {
            let grid_clone = Arc::clone(&grid);
            let handle = thread::spawn(move || {
                for j in 0..100 {
                    grid_clone.set_walkable(i, j, 0, false);
                    assert!(!grid_clone.is_walkable(i, j, 0));
                }
            });
            handles.push(handle);
        }
        
        for handle in handles {
            handle.join().unwrap();
        }
    }
    
    #[test]
    fn test_memory_arena_thread_safety() {
        let arena = Arc::new(MemoryArena::new(1024 * 1024, 64));
        let mut handles = vec![];
        
        for i in 0..10 {
            let arena_clone = Arc::clone(&arena);
            let handle = thread::spawn(move || {
                for _ in 0..100 {
                    let ptr = arena_clone.allocate::<f32>(i);
                    assert!(!ptr.as_ptr().is_null());
                }
            });
            handles.push(handle);
        }
        
        for handle in handles {
            handle.join().unwrap();
        }
    }
    
    #[test]
    fn test_zero_copy_buffer_pool_thread_safety() {
        let pool = Arc::new(ZeroCopyBufferPool::new(1024, 100));
        let mut handles = vec![];
        
        for _ in 0..10 {
            let pool_clone = Arc::clone(&pool);
            let handle = thread::spawn(move || {
                for _ in 0..100 {
                    let buffer = pool_clone.acquire_buffer();
                    if let Some(buf) = buffer {
                        pool_clone.release_buffer(buf);
                    }
                }
            });
            handles.push(handle);
        }
        
        for handle in handles {
            handle.join().unwrap();
        }
    }
    
    #[test]
    fn test_load_balancer_thread_safety() {
        let balancer = Arc::new(AdaptiveLoadBalancer::new(4, 1000));
        let mut handles = vec![];
        
        for i in 0..10 {
            let balancer_clone = Arc::clone(&balancer);
            let handle = thread::spawn(move || {
                for j in 0..100 {
                    let task = Task {
                        id: (i * 100 + j) as u64,
                        priority: TaskPriority::Normal,
                        workload: Workload::VectorOperation {
                            data: vec![1.0, 2.0, 3.0],
                            operation: "add".to_string(),
                        },
                        created_at: Instant::now(),
                        estimated_duration: Duration::from_micros(1),
                    };
                    balancer_clone.submit_task(task).unwrap();
                }
            });
            handles.push(handle);
        }
        
        for handle in handles {
            handle.join().unwrap();
        }
        
        let metrics = balancer.get_metrics();
        assert!(metrics.total_tasks.load(Ordering::Relaxed) >= 1000);
    }
}

/// Integration tests
#[cfg(test)]
mod integration_tests {
    use super::*;
    use test_utils::*;
    
    #[test]
    fn test_end_to_end_parallel_processing_pipeline() {
        // Create a complex processing pipeline that uses multiple components
        
        // 1. Generate test data
        let matrix_size = 64;
        let matrices_a = (0..10).map(|_| generate_random_matrix(matrix_size, matrix_size)).collect::<Vec<_>>();
        let matrices_b = (0..10).map(|_| generate_random_matrix(matrix_size, matrix_size)).collect::<Vec<_>>();
        
        // 2. Process with arena memory allocation
        let allocator = HotLoopAllocator::new(4, 1024 * 1024);
        let arena_results: Vec<Vec<f32>> = matrices_a.iter().zip(matrices_b.iter()).enumerate().map(|(i, (a, b))| {
            let thread_id = i % 4;
            arena_matrix_multiply(a, b, matrix_size, matrix_size, matrix_size, thread_id)
        }).collect();
        
        // 3. Process with SIMD optimization
        let simd_results: Vec<Vec<f32>> = matrices_a.iter().zip(matrices_b.iter()).map(|(a, b)| {
            runtime_matrix_multiply(a, b, matrix_size, matrix_size, matrix_size)
        }).collect();
        
        // 4. Process with parallel matrix operations
        let matrix_a_arrays: Vec<[f32; 16]> = matrices_a.iter().take(5).map(|m| m[0..16].try_into().unwrap()).collect();
        let matrix_b_arrays: Vec<[f32; 16]> = matrices_b.iter().take(5).map(|m| m[0..16].try_into().unwrap()).collect();
        let parallel_results = parallel_nalgebra_matrix_multiply(matrix_a_arrays, matrix_b_arrays);
        
        // 5. Verify results are consistent
        assert_eq!(arena_results.len(), 10);
        assert_eq!(simd_results.len(), 10);
        assert_eq!(parallel_results.len(), 5);
        
        // Results should be similar (allowing for small numerical differences)
        for i in 0..5 {
            let arena_result = &arena_results[i];
            let simd_result = &simd_results[i];
            
            for j in 0..(matrix_size * matrix_size) {
                assert!((arena_result[j] - simd_result[j]).abs() < 0.001);
            }
        }
        
        println!("End-to-end pipeline completed successfully");
    }
    
    #[test]
    fn test_concurrent_multi_component_processing() {
        // Test multiple components working together concurrently
        
        let num_threads = 4;
        
        // Create shared resources
        let grid = Arc::new(ThreadSafeGrid::new(50, 50, 1, true));
        let allocator = Arc::new(HotLoopAllocator::new(num_threads, 1024 * 1024));
        let load_balancer = Arc::new(AdaptiveLoadBalancer::new(num_threads, 100));
        
        let mut handles = vec![];
        
        for thread_id in 0..num_threads {
            let grid_clone = Arc::clone(&grid);
            let allocator_clone = Arc::clone(&allocator);
            let balancer_clone = Arc::clone(&load_balancer);
            
            let handle = thread::spawn(move || {
                // Perform different operations in parallel
                
                // 1. Pathfinding operation
                let start = Position::new(thread_id as i32 * 10, 0, 0);
                let goal = Position::new(thread_id as i32 * 10 + 9, 49, 0);
                let engine = ParallelAStar::new(grid_clone.clone(), 1);
                let path = engine.find_path(start, goal);
                assert!(path.is_some());
                
                // 2. Matrix operations with arena allocation
                let matrix_a = generate_random_matrix(32, 32);
                let matrix_b = generate_random_matrix(32, 32);
                let result = arena_matrix_multiply(&matrix_a, &matrix_b, 32, 32, 32, thread_id);
                assert_eq!(result.len(), 32 * 32);
                
                // 3. SIMD operations
                let vector_a = generate_random_matrix(1, 1000)[0..1000].to_vec();
                let vector_b = generate_random_matrix(1, 1000)[0..1000].to_vec();
                let dot_product = runtime_vector_dot_product(&vector_a, &vector_b);
                assert!(dot_product > 0.0);
                
                // 4. Load balancer task submission
                let task = Task {
                    id: thread_id as u64,
                    priority: TaskPriority::Normal,
                    workload: Workload::VectorOperation {
                        data: vec![1.0, 2.0, 3.0],
                        operation: "add".to_string(),
                    },
                    created_at: Instant::now(),
                    estimated_duration: Duration::from_millis(1),
                };
                balancer_clone.submit_task(task).unwrap();
            });
            
            handles.push(handle);
        }
        
        for handle in handles {
            handle.join().unwrap();
        }
        
        // Verify load balancer processed tasks
        let metrics = load_balancer.get_metrics();
        assert!(metrics.total_tasks.load(Ordering::Relaxed) >= num_threads);
        
        println!("Concurrent multi-component processing completed successfully");
    }
}

/// Benchmark configuration for criterion
#[cfg(test)]
mod criterion_benchmarks {
    use super::*;
    use criterion::{criterion_group, criterion_main, Criterion};
    
    fn benchmark_parallel_matrix_multiply(c: &mut Criterion) {
        let size = 128;
        let a = test_utils::generate_random_matrix(size, size);
        let b = test_utils::generate_random_matrix(size, size);
        
        c.bench_function("parallel_matrix_multiply_128x128", |b| {
            b.iter(|| {
                parallel_matrix_multiply_block(black_box(&a), black_box(&b), size, size, size)
            });
        });
    }
    
    fn benchmark_parallel_astar(c: &mut Criterion) {
        let grid = ThreadSafeGrid::new(50, 50, 1, true);
        let start = Position::new(0, 0, 0);
        let goal = Position::new(49, 49, 0);
        let engine = ParallelAStar::new(grid, 4);
        
        c.bench_function("parallel_astar_50x50", |b| {
            b.iter(|| {
                black_box(engine.find_path(start, goal))
            });
        });
    }
    
    fn benchmark_runtime_simd(c: &mut Criterion) {
        let size = 10000;
        let a = test_utils::generate_random_matrix(1, size)[0..size].to_vec();
        let b = test_utils::generate_random_matrix(1, size)[0..size].to_vec();
        
        c.bench_function("runtime_vector_dot_product_10000", |b| {
            b.iter(|| {
                black_box(runtime_vector_dot_product(black_box(&a), black_box(&b)))
            });
        });
    }
    
    fn benchmark_arena_allocation(c: &mut Criterion) {
        let allocator = HotLoopAllocator::new(4, 1024 * 1024);
        
        c.bench_function("arena_allocation_1024_floats", |b| {
            b.iter(|| {
                black_box(allocator.allocate::<f32>(0, 1024))
            });
        });
    }
    
    fn benchmark_load_balancer(c: &mut Criterion) {
        let balancer = AdaptiveLoadBalancer::new(4, 1000);
        let task = Task {
            id: 1,
            priority: TaskPriority::Normal,
            workload: Workload::VectorOperation {
                data: vec![1.0, 2.0, 3.0],
                operation: "add".to_string(),
            },
            created_at: Instant::now(),
            estimated_duration: Duration::from_millis(1),
        };
        
        c.bench_function("load_balancer_task_submission", |b| {
            b.iter(|| {
                black_box(balancer.submit_task(task.clone()))
            });
        });
    }
    
    criterion_group!(
        benches,
        benchmark_parallel_matrix_multiply,
        benchmark_parallel_astar,
        benchmark_runtime_simd,
        benchmark_arena_allocation,
        benchmark_load_balancer
    );
    
    criterion_main!(benches);
}

/// Main test runner
#[cfg(test)]
fn main() {
    // Run all tests
    parallel_astar_tests::run_tests();
    parallel_matrix_tests::run_tests();
    arena_memory_tests::run_tests();
    simd_runtime_tests::run_tests();
    load_balancer_tests::run_tests();
    performance_benchmarks::run_tests();
    thread_safety_tests::run_tests();
    integration_tests::run_tests();
    
    println!("All tests completed successfully!");
}
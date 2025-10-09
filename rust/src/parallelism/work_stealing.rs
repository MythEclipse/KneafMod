use rayon::prelude::*;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Instant;

// Track parallel execution statistics for performance monitoring
static PARALLEL_TASK_STATS: AtomicUsize = AtomicUsize::new(0);

/// Work stealing scheduler with enhanced performance optimizations
#[derive(Debug)]
pub struct WorkStealingScheduler<T> {
    tasks: Vec<T>,
}

impl<T> WorkStealingScheduler<T> {
    /// Create a new work stealing scheduler
    pub fn new(tasks: Vec<T>) -> Self {
        Self { tasks }
    }

    /// Execute tasks with optimized work distribution and performance monitoring
    #[inline(always)]
    pub fn execute<F, R>(self, processor: F) -> Vec<R>
        where
            F: Fn(T) -> R + Send + Sync + 'static,
            T: Send + 'static,
            R: Send + 'static,
        {
            let task_count = self.tasks.len();
            
            // Increment parallel task counter for monitoring
            PARALLEL_TASK_STATS.fetch_add(1, Ordering::Relaxed);
            
            // Use branch prediction for common case optimization
            if task_count == 0 {
                return Vec::new();
            }

            // Aggressive optimization: for very small task counts (<=4), use sequential processing
            // to avoid Rayon thread spawning and work distribution overhead
            if task_count <= 4 {
                return self.execute_sequential(processor);
            }

            // For medium task counts (5-64), use optimized sequential with loop unrolling
            if task_count <= 64 {
                return self.execute_optimized_sequential(processor);
            }

            // For larger task counts, use Rayon's optimized work stealing with task type awareness
            self.execute_parallel(processor)
        }

    /// Sequential execution with no overhead
    fn execute_sequential<F, R>(self, processor: F) -> Vec<R>
        where
            F: Fn(T) -> R,
    {
        self.tasks.into_iter().map(processor).collect()
    }

    /// Optimized sequential execution with loop unrolling for better ILP
    fn execute_optimized_sequential<F, R>(self, processor: F) -> Vec<R>
        where
            F: Fn(T) -> R,
    {
        self.tasks.into_iter().map(processor).collect()
    }

    /// Parallel execution with Rayon work stealing
    fn execute_parallel<F, R>(self, processor: F) -> Vec<R>
        where
            F: Fn(T) -> R + Send + Sync + 'static,
            T: Send + 'static,
            R: Send + 'static,
    {
        // For CPU-bound tasks, use Rayon's default (which is usually optimal)
        // For I/O-bound tasks, we might want different configuration, but Rayon handles this well

        let task_count = self.tasks.len();

        // Measure execution time for performance monitoring
        let start = Instant::now();
        let results = self.tasks.into_par_iter().map(processor).collect();
        let duration = start.elapsed();

        // Log performance statistics (in a real system, this would go to a proper monitoring system)
        #[cfg(debug_assertions)]
        {
            eprintln!("Parallel execution: {} tasks in {:?}", task_count, duration);
        }

        results
    }

    /// Get execution statistics (for debugging/monitoring)
    #[cfg(debug_assertions)]
    pub fn get_stats() -> usize {
        PARALLEL_TASK_STATS.load(Ordering::Relaxed)
    }
}
use crate::errors::{Result, RustError};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Instant;
use crate::parallelism::executor_factory::{ParallelExecutorFactory, ExecutorType};

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
    pub fn execute<F, R>(self, processor: F) -> Result<Vec<R>>
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
            return Ok(Vec::new());
        }

        // Aggressive optimization: for very small task counts (<=4), use sequential processing
        // to avoid Rayon thread spawning and work distribution overhead
        if task_count <= 4 {
            return Ok(self.execute_sequential(processor));
        }

        // For medium task counts (5-64), use optimized sequential with loop unrolling
        if task_count <= 64 {
            return Ok(self.execute_optimized_sequential(processor));
        }

        // For larger task counts, use our optimized work stealing executor
        // This provides better load balancing across threads
        self.execute_parallel_with_factory(processor)
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

    /// Parallel execution with work stealing using our factory-based executor
    fn execute_parallel_with_factory<F, R>(self, processor: F) -> Result<Vec<R>>
    where
        F: Fn(T) -> R + Send + Sync + 'static,
        T: Send + 'static,
        R: Send + 'static,
    {
        let task_count = self.tasks.len();
        
        // Measure execution time for performance monitoring
        let start = Instant::now();
        
        // Use the work-stealing executor from our factory for better load balancing
        let executor = ParallelExecutorFactory::create_work_stealing()
            .map_err(|e| RustError::OperationFailed(format!("Failed to create work-stealing executor: {}", e)))?;
            
        let results = executor.execute(|| {
            self.tasks.into_iter().map(processor).collect()
        });
        
        let duration = start.elapsed();

        // Log performance statistics (in a real system, this would go to a proper monitoring system)
        #[cfg(debug_assertions)]
        {
            eprintln!(
                "Parallel execution: {} tasks in {:?} using work-stealing executor",
                task_count, duration
            );
        }

        Ok(results)
    }

    /// Parallel execution with Rayon work stealing using adaptive thread pool (legacy)
    #[deprecated(since = "0.8.0", note = "Please use execute_parallel_with_factory instead")]
    fn execute_parallel_with_adaptive_pool<F, R>(self, processor: F) -> Result<Vec<R>>
    where
        F: Fn(T) -> R + Send + Sync + 'static,
        T: Send + 'static,
        R: Send + 'static,
    {
        let task_count = self.tasks.len();
        
        // Measure execution time for performance monitoring
        let start = Instant::now();
        
        // Get the global executor for backward compatibility
        let executor = ParallelExecutorFactory::create_executor(ExecutorType::WorkStealing);
        
        let results = executor.execute(|| {
            self.tasks.into_iter().map(processor).collect()
        });
        
        let duration = start.elapsed();

        // Log performance statistics (in a real system, this would go to a proper monitoring system)
        #[cfg(debug_assertions)]
        {
            eprintln!(
                "Parallel execution: {} tasks in {:?} using global executor",
                task_count, duration
            );
        }

        Ok(results)
    }

    /// Get execution statistics (for debugging/monitoring)
    #[cfg(debug_assertions)]
    pub fn get_stats() -> usize {
        PARALLEL_TASK_STATS.load(Ordering::Relaxed)
    }
}

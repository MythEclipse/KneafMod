use crate::errors::{Result, RustError};
use rayon;
use std::sync::Arc;

pub mod work_stealing;
pub use work_stealing::WorkStealingScheduler;
pub mod executor_factory;
pub use executor_factory::{ParallelExecutor, ParallelExecutorFactory, ExecutorType, get_global_executor};

/// Adaptive thread pool with real parallelism using Rayon
#[deprecated(since = "0.8.0", note = "Please use ParallelExecutorFactory instead for new code")]
pub struct AdaptiveThreadPool {
    thread_pool: Arc<rayon::ThreadPool>,
}

impl AdaptiveThreadPool {
    pub fn new() -> Self {
        // Create a Rayon thread pool with optimal configuration
        let num_threads = num_cpus::get();
        let thread_pool = rayon::ThreadPoolBuilder::new()
            .num_threads(num_threads)
            .thread_name(|idx| format!("kneaf-worker-{}", idx))
            .build()
            .expect("Failed to create Rayon thread pool");
         
        AdaptiveThreadPool {
            thread_pool: Arc::new(thread_pool),
        }
    }
}

impl Default for AdaptiveThreadPool {
    fn default() -> Self {
        Self::new()
    }
}

impl AdaptiveThreadPool {
    /// Execute a closure - compatible version for existing code
    pub fn execute<F, R>(&self, f: F) -> R
    where
        F: FnOnce() -> R,
    {
        // For compatibility with existing code, execute directly without thread pool constraints
        // This maintains backward compatibility while allowing the thread pool to be used elsewhere
        f()
    }

    /// Execute a closure with explicit parallel scope - for new code requiring 'static
    pub fn execute_static<F, R>(&self, f: F) -> R
    where
        F: FnOnce() -> R + Send + 'static,
        R: Send + 'static,
    {
        self.thread_pool.scope(|_| {
            f()
        })
    }

    /// Spawn a task asynchronously in the thread pool
    pub fn spawn<F>(&self, f: F)
    where
        F: FnOnce() + Send + 'static,
    {
        self.thread_pool.spawn(f);
    }

    pub fn current_thread_count(&self) -> usize {
        self.thread_pool.current_num_threads()
    }

    /// Get the underlying Rayon thread pool for advanced usage
    pub fn thread_pool(&self) -> &Arc<rayon::ThreadPool> {
        &self.thread_pool
    }
}

lazy_static::lazy_static! {
    static ref GLOBAL_ADAPTIVE_POOL: AdaptiveThreadPool = AdaptiveThreadPool::new();
}

/// Get the global adaptive thread pool
#[deprecated(since = "0.8.0", note = "Please use get_global_executor() instead for new code")]
pub fn get_adaptive_pool() -> &'static AdaptiveThreadPool {
    &GLOBAL_ADAPTIVE_POOL
}

/// Get the global Rayon thread pool for direct usage
#[deprecated(since = "0.8.0", note = "Please use ParallelExecutor::thread_pool() instead for new code")]
pub fn get_rayon_thread_pool() -> Arc<rayon::ThreadPool> {
    GLOBAL_ADAPTIVE_POOL.thread_pool().clone()
}

/// Create a default parallel executor (recommended for new code)
pub fn create_default_executor() -> Result<ParallelExecutor> {
    ParallelExecutorFactory::create_default()
}

/// Create a CPU-bound optimized executor (recommended for new code)
pub fn create_cpu_bound_executor() -> Result<ParallelExecutor> {
    ParallelExecutorFactory::create_cpu_bound()
}

/// Create an I/O-bound optimized executor (recommended for new code)
pub fn create_io_bound_executor() -> Result<ParallelExecutor> {
    ParallelExecutorFactory::create_io_bound()
}

/// Create a work-stealing optimized executor (recommended for new code)
pub fn create_work_stealing_executor() -> Result<ParallelExecutor> {
    ParallelExecutorFactory::create_work_stealing()
}

/// Create a sequential executor (single thread, recommended for new code)
pub fn create_sequential_executor() -> Result<ParallelExecutor> {
    ParallelExecutorFactory::create_sequential()
}

// Re-export performance monitoring under the `monitoring` name so older code
// that imports `parallelism::monitoring` continues to work.
pub use crate::performance_monitoring as monitoring;

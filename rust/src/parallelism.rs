use num_cpus;
use rayon;
use std::sync::Arc;

pub mod work_stealing;
pub use work_stealing::WorkStealingScheduler;

/// Adaptive thread pool with real parallelism using Rayon
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
pub fn get_adaptive_pool() -> &'static AdaptiveThreadPool {
    &GLOBAL_ADAPTIVE_POOL
}

/// Get the global Rayon thread pool for direct usage
pub fn get_rayon_thread_pool() -> Arc<rayon::ThreadPool> {
    GLOBAL_ADAPTIVE_POOL.thread_pool().clone()
}

// Re-export performance monitoring under the `monitoring` name so older code
// that imports `parallelism::monitoring` continues to work.
pub use crate::performance_monitoring as monitoring;

use num_cpus;

pub mod work_stealing;
pub use work_stealing::WorkStealingScheduler;

/// Minimal adaptive pool facade used by other modules. This is intentionally
/// lightweight to avoid coupling with complex scheduler internals while
/// providing a stable API: `get_adaptive_pool()` and `current_thread_count()`.
pub struct AdaptiveThreadPool;

impl AdaptiveThreadPool {
    pub fn new() -> Self {
        AdaptiveThreadPool
    }

    /// Execute a closure in-place (no custom thread pool here). This keeps the
    /// API stable for callers that provide their own Rayon-based parallelism.
    pub fn execute<F, R>(&self, f: F) -> R
    where
        F: FnOnce() -> R,
    {
        f()
    }

    pub fn current_thread_count(&self) -> usize {
        num_cpus::get()
    }
}

lazy_static::lazy_static! {
    static ref GLOBAL_ADAPTIVE_POOL: AdaptiveThreadPool = AdaptiveThreadPool::new();
}

pub fn get_adaptive_pool() -> &'static AdaptiveThreadPool {
    &GLOBAL_ADAPTIVE_POOL
}

// Re-export performance monitoring under the `monitoring` name so older code
// that imports `parallelism::monitoring` continues to work.
pub use crate::performance_monitoring as monitoring;

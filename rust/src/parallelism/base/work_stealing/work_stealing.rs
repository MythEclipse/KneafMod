use crate::parallelism::base::ExecutorType;
use crate::parallelism::base::executor_factory::{ParallelExecutor, ParallelExecutorFactory, get_global_executor};

/// Work-stealing scheduler for parallel task execution
#[derive(Debug, Default)]
pub struct WorkStealingScheduler {
    executor: ParallelExecutor,
}

impl WorkStealingScheduler {
    /// Create a new work-stealing scheduler with the specified executor type
    pub fn new(executor_type: ExecutorType) -> Self {
        let executor = ParallelExecutorFactory::new(executor_type).create_executor();
        Self { executor }
    }

    /// Get a global shared work-stealing scheduler instance
    pub fn get_global_scheduler() -> Self {
        let executor = get_global_executor();
        Self { executor }
    }

    /// Execute a task using the work-stealing scheduler
    pub fn execute<F, R>(&self, f: F) -> R
    where
        F: FnOnce() -> R + Send + 'static,
        R: Send + 'static,
    {
        self.executor.execute(f)
    }
}
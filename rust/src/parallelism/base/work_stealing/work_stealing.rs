use crate::parallelism::base::executor_factory::executor_factory::{ExecutorType, ParallelExecutorEnum, ParallelExecutorFactory};
use crate::ParallelExecutor;
use std::sync::Arc;

/// Work-stealing scheduler for parallel task execution
#[derive(Debug, Default, Clone)]
pub struct WorkStealingScheduler {
    executor: ParallelExecutorEnum,
}

impl WorkStealingScheduler {
    /// Create a new work-stealing scheduler with the specified executor type
    pub fn new(executor_type: ExecutorType) -> Self {
        let executor = ParallelExecutorFactory::create_executor_enum(executor_type);
        Self { executor }
    }

    /// Get a global shared work-stealing scheduler instance
    pub fn get_global_scheduler() -> Self {
        let executor = ParallelExecutorFactory::create_executor_enum(ExecutorType::WorkStealing);
        Self { executor }
    }

    /// Execute a task using the work-stealing scheduler
    pub fn execute<F, R>(&self, f: F) -> R
    where
        F: FnOnce() -> R + Send + 'static,
        R: Send + 'static,
    {
        self.executor.execute_sync(f)
    }
}
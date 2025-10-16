use crate::parallelism::base::executor_factory::ParallelExecutor;
use crate::errors::Result;

pub struct SequentialExecutor;

impl SequentialExecutor {
    pub fn new() -> Self {
        Self
    }
}

impl ParallelExecutor for SequentialExecutor {
    fn execute<F, R>(&self, f: F) -> R
    where
        F: FnOnce() -> R + Send,
        R: Send,
    {
        f()
    }

    fn spawn<F>(&self, f: F)
    where
        F: FnOnce() + Send + 'static,
    {
        f()
    }

    fn current_thread_count(&self) -> usize {
        1
    }

    fn name(&self) -> &str {
        "sequential"
    }
}
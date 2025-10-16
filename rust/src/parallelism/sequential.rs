use crate::ParallelExecutor;

pub struct SequentialExecutor;

impl SequentialExecutor {
    pub fn new() -> Self {
        Self
    }
}

impl ParallelExecutor for SequentialExecutor {
    fn execute_sync<F, R>(&self, f: F) -> R
    where
        F: FnOnce() -> R + Send + 'static,
        R: Send + 'static,
    {
        f()
    }

    fn execute<F, R>(&self, f: F) -> std::pin::Pin<Box<dyn std::future::Future<Output = R> + Send>>
    where
        F: FnOnce() -> R + Send + 'static,
        R: Send + 'static,
    {
        Box::pin(async move { f() })
    }

    fn running_tasks(&self) -> usize {
        0
    }

    fn max_concurrent_tasks(&self) -> usize {
        1
    }
}
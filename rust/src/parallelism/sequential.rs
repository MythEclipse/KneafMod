use crate::ParallelExecutor;
use async_trait::async_trait;

#[derive(Debug)]
pub struct SequentialExecutor;

impl SequentialExecutor {
    pub fn new() -> Self {
        Self
    }
}

#[async_trait]
impl ParallelExecutor for SequentialExecutor {
    fn execute_with_priority<F, R>(&self, f: F, priority: TaskPriority, trace_id: Option<String>) -> R
    where
        F: FnOnce() -> R + Send + 'static,
        R: Send + 'static,
    {
        let task = Box::new(f);
        let result = self.execute(move || task());
        result
    }
    fn execute<F, R>(&self, f: F) -> R
    where
        F: FnOnce() -> R + Send + 'static,
        R: Send + 'static,
    {
        f()
    }

    async fn shutdown(&self) {
    }

    fn running_tasks(&self) -> usize {
        0
    }

    fn max_concurrent_tasks(&self) -> usize {
        1
    }
}
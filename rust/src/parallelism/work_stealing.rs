use rayon::prelude::*;

pub struct WorkStealingScheduler<T> {
    tasks: Vec<T>,
}

impl<T> WorkStealingScheduler<T> {
    pub fn new(tasks: Vec<T>) -> Self {
        Self { tasks }
    }

    pub fn execute<F, R>(self, processor: F) -> Vec<R>
        where
            F: Fn(T) -> R + Send + Sync + 'static,
            T: Send + 'static,
            R: Send + 'static,
        {
            let task_count = self.tasks.len();

            // Aggressive optimization: for small task counts (<=8), use sequential processing
            // to avoid Rayon thread spawning and work distribution overhead
            if task_count <= 8 {
                return self.tasks.into_iter().map(processor).collect();
            }

            // For larger task counts, use Rayon's optimized work stealing
            // Rayon automatically handles work stealing, load balancing, and thread management
            self.tasks.into_par_iter().map(processor).collect()
        }
}
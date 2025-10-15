use num_cpus;
use rayon;
use std::sync::Arc;
use crate::errors::{Result, RustError};

/// Parallel executor types for different use cases
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ExecutorType {
    /// Default executor for general purpose parallel tasks
    Default,
    /// Optimized for CPU-bound tasks with fine-grained parallelism
    CpuBound,
    /// Optimized for I/O-bound tasks with larger thread counts
    IoBound,
    /// Optimized for work-stealing scenarios with dynamic load balancing
    WorkStealing,
    /// Single-threaded executor for sequential tasks
    Sequential,
}

/// Builder pattern for creating configured parallel executors
pub struct ParallelExecutorBuilder {
    executor_type: ExecutorType,
    num_threads: Option<usize>,
    thread_name_prefix: Option<String>,
}

impl Default for ParallelExecutorBuilder {
    fn default() -> Self {
        Self {
            executor_type: ExecutorType::Default,
            num_threads: None,
            thread_name_prefix: None,
        }
    }
}

impl ParallelExecutorBuilder {
    /// Create a new builder with default settings
    pub fn new() -> Self {
        Self::default()
    }

    /// Set the executor type
    pub fn with_type(mut self, executor_type: ExecutorType) -> Self {
        self.executor_type = executor_type;
        self
    }

    /// Set the number of threads (overrides automatic detection)
    pub fn with_threads(mut self, num_threads: usize) -> Self {
        self.num_threads = Some(num_threads);
        self
    }

    /// Set the thread name prefix
    pub fn with_thread_name_prefix(mut self, prefix: String) -> Self {
        self.thread_name_prefix = Some(prefix);
        self
    }

    /// Build the executor with the configured settings
    pub fn build(&self) -> Result<ParallelExecutor> {
        let thread_pool = self.create_thread_pool()?;
        let executor = ParallelExecutor {
            thread_pool: Arc::new(thread_pool),
            executor_type: self.executor_type,
        };
        Ok(executor)
    }

    /// Create the appropriate thread pool based on the executor type
    fn create_thread_pool(&self) -> Result<rayon::ThreadPool> {
        let num_threads = self.num_threads.unwrap_or_else(|| {
            match self.executor_type {
                ExecutorType::Default => num_cpus::get(),
                ExecutorType::CpuBound => num_cpus::get(),
                ExecutorType::IoBound => num_cpus::get() * 2,
                ExecutorType::WorkStealing => num_cpus::get() * 4,
                ExecutorType::Sequential => 1,
            }
        });

        let builder = rayon::ThreadPoolBuilder::new()
            .num_threads(num_threads)
            .thread_name(move |idx| {
                self.thread_name_prefix
                    .as_ref()
                    .map(|prefix| format!("{}-{}", prefix, idx))
                    .unwrap_or_else(|| format!("kneaf-exec-{}", idx))
            });

        builder.build().map_err(|e| RustError::OperationFailed(e.to_string()))
    }
}

/// Factory for creating parallel executors
pub struct ParallelExecutorFactory;

impl ParallelExecutorFactory {
    /// Create a default parallel executor
    pub fn create_default() -> Result<ParallelExecutor> {
        ParallelExecutorBuilder::new().build()
    }

    /// Create a CPU-bound optimized executor
    pub fn create_cpu_bound() -> Result<ParallelExecutor> {
        ParallelExecutorBuilder::new()
            .with_type(ExecutorType::CpuBound)
            .build()
    }

    /// Create an I/O-bound optimized executor
    pub fn create_io_bound() -> Result<ParallelExecutor> {
        ParallelExecutorBuilder::new()
            .with_type(ExecutorType::IoBound)
            .build()
    }

    /// Create a work-stealing optimized executor
    pub fn create_work_stealing() -> Result<ParallelExecutor> {
        ParallelExecutorBuilder::new()
            .with_type(ExecutorType::WorkStealing)
            .build()
    }

    /// Create a sequential executor (single thread)
    pub fn create_sequential() -> Result<ParallelExecutor> {
        ParallelExecutorBuilder::new()
            .with_type(ExecutorType::Sequential)
            .build()
    }

    /// Create a custom executor with specific configuration
    pub fn create_custom<F>(builder_customizer: F) -> Result<ParallelExecutor>
    where
        F: FnOnce(ParallelExecutorBuilder) -> ParallelExecutorBuilder,
    {
        let builder = builder_customizer(ParallelExecutorBuilder::new());
        builder.build()
    }
}

/// Unified parallel executor interface
pub struct ParallelExecutor {
    thread_pool: Arc<rayon::ThreadPool>,
    executor_type: ExecutorType,
}

impl ParallelExecutor {
    /// Execute a closure with the executor
    pub fn execute<F, R>(&self, f: F) -> R
    where
        F: FnOnce() -> R + Send + 'static,
        R: Send + 'static,
    {
        self.thread_pool.install(f)
    }

    /// Execute a closure with static constraints (for compatibility)
    pub fn execute_static<F, R>(&self, f: F) -> R
    where
        F: FnOnce() -> R + Send + 'static,
        R: Send + 'static,
    {
        self.execute(f)
    }

    /// Spawn a task asynchronously
    pub fn spawn<F>(&self, f: F)
    where
        F: FnOnce() + Send + 'static,
    {
        self.thread_pool.spawn(f);
    }

    /// Get the current number of threads in the pool
    pub fn current_thread_count(&self) -> usize {
        self.thread_pool.current_num_threads()
    }

    /// Get the executor type
    pub fn executor_type(&self) -> ExecutorType {
        self.executor_type
    }

    /// Get the underlying thread pool (for advanced usage)
    pub fn thread_pool(&self) -> &Arc<rayon::ThreadPool> {
        &self.thread_pool
    }
}

/// Global executor instance for backward compatibility
lazy_static::lazy_static! {
    static ref GLOBAL_EXECUTOR: ParallelExecutor = ParallelExecutorFactory::create_default()
        .expect("Failed to create global parallel executor");
}

/// Get the global parallel executor
pub fn get_global_executor() -> &'static ParallelExecutor {
    &GLOBAL_EXECUTOR
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_executor_factory() {
        // Test default executor
        let default_executor = ParallelExecutorFactory::create_default().unwrap();
        assert_eq!(default_executor.executor_type(), ExecutorType::Default);

        // Test CPU-bound executor
        let cpu_executor = ParallelExecutorFactory::create_cpu_bound().unwrap();
        assert_eq!(cpu_executor.executor_type(), ExecutorType::CpuBound);

        // Test I/O-bound executor
        let io_executor = ParallelExecutorFactory::create_io_bound().unwrap();
        assert_eq!(io_executor.executor_type(), ExecutorType::IoBound);

        // Test work-stealing executor
        let ws_executor = ParallelExecutorFactory::create_work_stealing().unwrap();
        assert_eq!(ws_executor.executor_type(), ExecutorType::WorkStealing);

        // Test sequential executor
        let seq_executor = ParallelExecutorFactory::create_sequential().unwrap();
        assert_eq!(seq_executor.executor_type(), ExecutorType::Sequential);

        // Test custom executor
        let custom_executor = ParallelExecutorFactory::create_custom(|builder| {
            builder
                .with_type(ExecutorType::CpuBound)
                .with_threads(4)
                .with_thread_name_prefix("custom-test".to_string())
        }).unwrap();
        assert_eq!(custom_executor.executor_type(), ExecutorType::CpuBound);
    }

    #[test]
    fn test_executor_functionality() {
        let executor = ParallelExecutorFactory::create_default().unwrap();
        
        // Test synchronous execution
        let result = executor.execute(|| 42);
        assert_eq!(result, 42);

        // Test asynchronous execution
        let handle = std::thread::spawn(|| {
            executor.spawn(|| {
                // This should just run without panicking
                let x = 1 + 1;
                assert_eq!(x, 2);
            });
        });
        handle.join().unwrap();
    }
}
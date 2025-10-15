use std::sync::{Arc, Mutex};
// Remove conflicting import - we define our own ExecutorType

/// Enum representing different types of parallel executors
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ExecutorType {
    /// Thread pool executor
    ThreadPool,
    /// Work stealing executor
    WorkStealing,
    /// Async executor
    Async,
}

/// Trait defining the interface for parallel executors
/// Trait defining the interface for parallel executors (non-dyn compatible version)
pub trait ParallelExecutor: Send + Sync {
    /// Executes a function synchronously
    fn execute_sync<F, R>(&self, f: F) -> R
    where
        F: FnOnce() -> R + Send + 'static,
        R: Send + 'static;

    /// Shuts down the executor gracefully
    fn shutdown(&self);

    /// Gets the current number of running tasks
    fn running_tasks(&self) -> usize;

    /// Gets the maximum number of concurrent tasks
    fn max_concurrent_tasks(&self) -> usize;
}

/// Async-compatible executor trait for dyn usage
pub trait AsyncParallelExecutor: Send + Sync {
    /// Executes a function asynchronously
    fn execute<F, R>(&self, f: F) -> std::pin::Pin<Box<dyn std::future::Future<Output = R> + Send>>
    where
        F: FnOnce() -> R + Send + 'static,
        R: Send + 'static;

    /// Shuts down the executor gracefully
    async fn shutdown_async(&self);
}

/// Factory for creating different types of parallel executors
pub struct ParallelExecutorFactory;

impl ParallelExecutorFactory {
    /// Creates a new parallel executor of the specified type
    pub fn create_executor(executor_type: ExecutorType) -> Arc<dyn ParallelExecutor> {
        match executor_type {
            ExecutorType::ThreadPool => Arc::new(ThreadPoolExecutor::new()),
            ExecutorType::WorkStealing => Arc::new(WorkStealingExecutor::new()),
            ExecutorType::Async => Arc::new(AsyncExecutor::new()),
        }
    }
}

/// Thread pool executor implementation
struct ThreadPoolExecutor {
    // Simplified implementation for demonstration
    pool: tokio::runtime::Runtime,
}

impl ThreadPoolExecutor {
    fn new() -> Self {
        let pool = tokio::runtime::Runtime::new().expect("Failed to create Tokio runtime");
        Self { pool }
    }
}

#[async_trait::async_trait]
impl ParallelExecutor for ThreadPoolExecutor {
    fn execute<F, R>(&self, f: F) -> std::pin::Pin<Box<dyn std::future::Future<Output = R> + Send>>
    where
        F: FnOnce() -> R + Send + 'static,
        R: Send + 'static,
    {
        Box::pin(self.pool.spawn_blocking(f))
    }

    async fn shutdown(&self) {
        self.pool.shutdown().await;
    }

    fn running_tasks(&self) -> usize {
        0 // Simplified - real implementation would track this
    }

    fn max_concurrent_tasks(&self) -> usize {
        100 // Default value
    }
}

/// Work stealing executor implementation
struct WorkStealingExecutor {
    // Simplified implementation for demonstration
    executor: rayon::ThreadPool,
}

impl WorkStealingExecutor {
    fn new() -> Self {
        let executor = rayon::ThreadPoolBuilder::new()
            .num_threads(num_cpus::get())
            .build()
            .expect("Failed to create Rayon thread pool");
        Self { executor }
    }
}

#[async_trait::async_trait]
impl ParallelExecutor for WorkStealingExecutor {
    fn execute<F, R>(&self, f: F) -> std::pin::Pin<Box<dyn std::future::Future<Output = R> + Send>>
    where
        F: FnOnce() -> R + Send + 'static,
        R: Send + 'static,
    {
        let (sender, receiver) = std::sync::mpsc::channel();
        self.executor.spawn(move || {
            let result = f();
            sender.send(result).expect("Failed to send result");
        });
        Box::pin(async move { receiver.recv().expect("Failed to receive result") })
    }

    async fn shutdown(&self) {
        // Rayon doesn't have a graceful shutdown, so we just return immediately
    }

    fn running_tasks(&self) -> usize {
        0 // Simplified - real implementation would track this
    }

    fn max_concurrent_tasks(&self) -> usize {
        self.executor.current_num_threads()
    }
}

/// Async executor implementation
struct AsyncExecutor {
    // Simplified implementation for demonstration
    runtime: tokio::runtime::Runtime,
}

impl AsyncExecutor {
    fn new() -> Self {
        let runtime = tokio::runtime::Runtime::new().expect("Failed to create Tokio runtime");
        Self { runtime }
    }
}

#[async_trait::async_trait]
impl ParallelExecutor for AsyncExecutor {
    fn execute<F, R>(&self, f: F) -> std::pin::Pin<Box<dyn std::future::Future<Output = R> + Send>>
    where
        F: FnOnce() -> R + Send + 'static,
        R: Send + 'static,
    {
        Box::pin(self.runtime.spawn(f))
    }

    async fn shutdown(&self) {
        self.runtime.shutdown_background();
    }

    fn running_tasks(&self) -> usize {
        0 // Simplified - real implementation would track this
    }

    fn max_concurrent_tasks(&self) -> usize {
        100 // Default value
    }
}

/// Global executor instance for the application
// static GLOBAL_EXECUTOR: Mutex<Option<Arc<Box<dyn ParallelExecutor>>>> = Mutex::new(None);

/// Initializes the global executor with the specified type
// pub fn initialize_global_executor(executor_type: ExecutorType) {
//     let mut global_executor = GLOBAL_EXECUTOR.lock().expect("Failed to lock global executor");
//     *global_executor = Some(ParallelExecutorFactory::create_executor(executor_type));
// }

/// Gets the global executor instance
// pub fn get_global_executor() -> Arc<Box<dyn ParallelExecutor>> {
//     GLOBAL_EXECUTOR.lock()
//         .expect("Failed to lock global executor")
//         .as_ref()
//         .cloned()
//         .expect("Global executor not initialized")
// }

/// Initializes the global executor with a default work-stealing executor
// pub fn initialize_default_executor() {
//     initialize_global_executor(ExecutorType::WorkStealing);
// }

// Initialize the global executor with a default work-stealing executor on first use
#[ctor::ctor]
fn init() {
    initialize_default_executor();
}
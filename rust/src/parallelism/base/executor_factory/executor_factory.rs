use std::sync::{Arc, Mutex};
use async_trait::async_trait;
use crate::config::performance_config::{PerformanceConfig, PerformanceMode};
use crate::parallelism::sequential::SequentialExecutor;
use std::time::Duration;

/// Enum representing different types of parallel executors
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ExecutorType {
    /// Thread pool executor
    ThreadPool,
    /// Work stealing executor
    WorkStealing,
    /// Async executor
    Async,
    /// Sequential executor
    Sequential,
}

#[async_trait]
pub trait ParallelExecutor: Send + Sync {
    /// Executes a function synchronously
    fn execute<F, R>(&self, f: F) -> R
    where
        F: FnOnce() -> R + Send + 'static,
        R: Send + 'static;

    /// Shuts down the executor gracefully
    async fn shutdown(&self);

    /// Gets the current number of running tasks
    fn running_tasks(&self) -> usize;

    /// Gets the maximum number of concurrent tasks
    fn max_concurrent_tasks(&self) -> usize;
}

/// Enum for dynamic dispatch of parallel executors
#[derive(Clone, Debug)]
pub enum ParallelExecutorEnum {
    ThreadPool(Arc<ThreadPoolExecutor>),
    WorkStealing(Arc<WorkStealingExecutor>),
    Async(Arc<AsyncExecutor>),
    Sequential(Arc<SequentialExecutor>),
}

impl ParallelExecutorEnum {
    /// Executes a function synchronously
    pub fn execute<F, R>(&self, f: F) -> R
    where
        F: FnOnce() -> R + Send + 'static,
        R: Send + 'static,
    {
        match self {
            ParallelExecutorEnum::ThreadPool(e) => e.execute(f),
            ParallelExecutorEnum::WorkStealing(e) => e.execute(f),
            ParallelExecutorEnum::Async(e) => e.execute(f),
            ParallelExecutorEnum::Sequential(e) => e.execute(f),
        }
    }

    /// Shuts down the executor gracefully
    pub async fn shutdown(&self) {
        match self {
            ParallelExecutorEnum::ThreadPool(e) => e.shutdown().await,
            ParallelExecutorEnum::WorkStealing(e) => e.shutdown().await,
            ParallelExecutorEnum::Async(e) => e.shutdown().await,
            ParallelExecutorEnum::Sequential(e) => e.shutdown().await,
        }
    }

    /// Gets the current number of running tasks
    pub fn running_tasks(&self) -> usize {
        match self {
            ParallelExecutorEnum::ThreadPool(e) => e.running_tasks(),
            ParallelExecutorEnum::WorkStealing(e) => e.running_tasks(),
            ParallelExecutorEnum::Async(e) => e.running_tasks(),
            ParallelExecutorEnum::Sequential(e) => e.running_tasks(),
        }
    }

    /// Gets the maximum number of concurrent tasks
    pub fn max_concurrent_tasks(&self) -> usize {
        match self {
            ParallelExecutorEnum::ThreadPool(e) => e.max_concurrent_tasks(),
            ParallelExecutorEnum::WorkStealing(e) => e.max_concurrent_tasks(),
            ParallelExecutorEnum::Async(e) => e.max_concurrent_tasks(),
            ParallelExecutorEnum::Sequential(e) => e.max_concurrent_tasks(),
        }
    }
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

/// Configuration for parallel executor performance
#[derive(Debug, Clone, Default)]
pub struct ExecutorConfig {
    pub thread_pool_size: usize,
    pub min_thread_pool_size: usize,
    pub dynamic_thread_scaling: bool,
    pub thread_scale_up_threshold: f64,
    pub thread_scale_down_threshold: f64,
    pub thread_scale_up_delay: Duration,
    pub thread_scale_down_delay: Duration,
    pub work_stealing_config: Option<WorkStealingConfig>,
    pub cpu_aware_thread_sizing: bool,
    pub cpu_load_threshold: f64,
}

/// Configuration for work stealing executor
#[derive(Debug, Clone, Default)]
pub struct WorkStealingConfig {
    pub enabled: bool,
    pub queue_size: usize,
}

/// Factory for creating different types of parallel executors
pub struct ParallelExecutorFactory;

impl ParallelExecutorFactory {
    /// Creates a new parallel executor of the specified type with default configuration
    pub fn create_executor(executor_type: ExecutorType) -> ParallelExecutorEnum {
        match executor_type {
            ExecutorType::ThreadPool => ParallelExecutorEnum::ThreadPool(Arc::new(ThreadPoolExecutor::new(None))),
            ExecutorType::WorkStealing => ParallelExecutorEnum::WorkStealing(Arc::new(WorkStealingExecutor::new(None))),
            ExecutorType::Async => ParallelExecutorEnum::Async(Arc::new(AsyncExecutor::new(None))),
            ExecutorType::Sequential => ParallelExecutorEnum::Sequential(Arc::new(SequentialExecutor::new())),
        }
    }

    /// Creates a new parallel executor enum of the specified type with default configuration
    pub fn create_executor_enum(executor_type: ExecutorType) -> ParallelExecutorEnum {
        match executor_type {
            ExecutorType::ThreadPool => ParallelExecutorEnum::ThreadPool(Arc::new(ThreadPoolExecutor::new(None))),
            ExecutorType::WorkStealing => ParallelExecutorEnum::WorkStealing(Arc::new(WorkStealingExecutor::new(None))),
            ExecutorType::Async => ParallelExecutorEnum::Async(Arc::new(AsyncExecutor::new(None))),
            ExecutorType::Sequential => ParallelExecutorEnum::Sequential(Arc::new(SequentialExecutor::new())),
        }
    }

    /// Creates a new parallel executor with performance configuration
    pub fn create_executor_with_config(executor_type: ExecutorType, config: &ExecutorConfig) -> ParallelExecutorEnum {
        match executor_type {
            ExecutorType::ThreadPool => ParallelExecutorEnum::ThreadPool(Arc::new(ThreadPoolExecutor::new(Some(config)))),
            ExecutorType::WorkStealing => ParallelExecutorEnum::WorkStealing(Arc::new(WorkStealingExecutor::new(Some(config)))),
            ExecutorType::Async => ParallelExecutorEnum::Async(Arc::new(AsyncExecutor::new(Some(config)))),
            ExecutorType::Sequential => ParallelExecutorEnum::Sequential(Arc::new(SequentialExecutor::new())),
        }
    }

    /// Creates a new parallel executor enum with performance configuration
    pub fn create_executor_enum_with_config(executor_type: ExecutorType, config: &ExecutorConfig) -> ParallelExecutorEnum {
        match executor_type {
            ExecutorType::ThreadPool => ParallelExecutorEnum::ThreadPool(Arc::new(ThreadPoolExecutor::new(Some(config)))),
            ExecutorType::WorkStealing => ParallelExecutorEnum::WorkStealing(Arc::new(WorkStealingExecutor::new(Some(config)))),
            ExecutorType::Async => ParallelExecutorEnum::Async(Arc::new(AsyncExecutor::new(Some(config)))),
            ExecutorType::Sequential => ParallelExecutorEnum::Sequential(Arc::new(SequentialExecutor::new())),
        }
    }
}

/// Thread pool executor implementation
#[derive(Debug)]
struct ThreadPoolExecutor {
    pool: tokio::runtime::Runtime,
}

impl ThreadPoolExecutor {
    fn new() -> Self {
        let pool = tokio::runtime::Runtime::new().expect("Failed to create Tokio runtime");
        Self { pool }
    }
}

#[async_trait]
impl ParallelExecutor for ThreadPoolExecutor {
    fn execute<F, R>(&self, f: F) -> R
    where
        F: FnOnce() -> R + Send + 'static,
        R: Send + 'static,
    {
        self.pool.block_on(async { f() })
    }

    async fn shutdown(&self) {
        // Tokio runtime shutdown is not async
    }

    fn running_tasks(&self) -> usize {
        0 
    }

    fn max_concurrent_tasks(&self) -> usize {
        100
    }
}

/// Work stealing executor implementation
#[derive(Debug)]
struct WorkStealingExecutor {
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

#[async_trait]
impl ParallelExecutor for WorkStealingExecutor {
    fn execute<F, R>(&self, f: F) -> R
    where
        F: FnOnce() -> R + Send + 'static,
        R: Send + 'static,
    {
        self.executor.install(f)
    }

    async fn shutdown(&self) {
    }

    fn running_tasks(&self) -> usize {
        0
    }

    fn max_concurrent_tasks(&self) -> usize {
        self.executor.current_num_threads()
    }
}

/// Async executor implementation
#[derive(Debug)]
struct AsyncExecutor {
    runtime: tokio::runtime::Runtime,
}

impl AsyncExecutor {
    fn new() -> Self {
        let runtime = tokio::runtime::Runtime::new().expect("Failed to create Tokio runtime");
        Self { runtime }
    }
}

#[async_trait]
impl ParallelExecutor for AsyncExecutor {
    fn execute<F, R>(&self, f: F) -> R
    where
        F: FnOnce() -> R + Send + 'static,
        R: Send + 'static,
    {
        self.runtime.block_on(async { f() })
    }

    async fn shutdown(&self) {
        // Tokio runtime shutdown is not async
    }

    fn running_tasks(&self) -> usize {
        0
    }

    fn max_concurrent_tasks(&self) -> usize {
        100
    }
}

/// Global executor instance for the application
static GLOBAL_EXECUTOR: Mutex<Option<ParallelExecutorEnum>> = Mutex::new(None);

pub fn initialize_global_executor(executor_type: ExecutorType) {
    let mut global_executor = GLOBAL_EXECUTOR.lock().expect("Failed to lock global executor");
    *global_executor = Some(ParallelExecutorFactory::create_executor_enum(executor_type));
}

pub fn initialize_default_executor() {
    initialize_global_executor(ExecutorType::WorkStealing);
}

#[ctor::ctor]
fn init() {
    initialize_default_executor();
}

impl Default for ParallelExecutorEnum {
    fn default() -> Self {
        // Default to a basic thread pool executor
        ParallelExecutorEnum::ThreadPool(Arc::new(ThreadPoolExecutor::new()))
    }
}
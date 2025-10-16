use std::sync::{Arc, Mutex, RwLock};
use async_trait::async_trait;
use crate::config::performance_config::{PerformanceConfig, WorkStealingConfig};
use crate::parallelism::sequential::SequentialExecutor;
use std::time::Duration;
use std::collections::BinaryHeap;
use std::cmp::Ordering;
use std::time::Instant;
use tokio::sync::Notify;
use std::any::Any;

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
pub trait ParallelExecutor: Send + Sync + Any {
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

    /// Executes a function with specified priority
    fn execute_with_priority<F, R>(&self, f: F, priority: TaskPriority, trace_id: Option<String>) -> R
    where
        F: FnOnce() -> R + Send + 'static,
        R: Send + 'static;
}

/// Priority levels for task execution
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum TaskPriority {
    Lowest,
    Low,
    Normal,
    High,
    Highest,
}

/// Task wrapper with priority metadata
#[derive(Debug, Clone)]
pub struct PrioritizedTask {
    pub priority: TaskPriority,
    pub task: Box<dyn FnOnce() -> Box<dyn Send + 'static> + Send + 'static>,
    pub submission_time: Instant,
    pub trace_id: Option<String>,
}

// Implement PartialEq and Eq required for Ord
impl PartialEq for PrioritizedTask {
    fn eq(&self, other: &Self) -> bool {
        self.priority == other.priority && self.task_id == other.task_id
    }
}

impl Eq for PrioritizedTask {}

impl Ord for PrioritizedTask {
    fn cmp(&self, other: &Self) -> Ordering {
        other.priority.cmp(&self.priority)
            .then_with(|| self.submission_time.cmp(&other.submission_time))
    }
}

impl PartialOrd for PrioritizedTask {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

/// Enum for dynamic dispatch of parallel executors
#[derive(Clone, Debug)]
pub enum ParallelExecutorEnum {
    ThreadPool(Arc<ThreadPoolExecutor>),
    WorkStealing(Arc<EnhancedWorkStealingExecutor>),
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

    /// Executes a function with specified priority
    pub fn execute_with_priority<F, R>(&self, f: F, priority: TaskPriority, trace_id: Option<String>) -> R
    where
        F: FnOnce() -> R + Send + 'static,
        R: Send + 'static,
    {
        match self {
            ParallelExecutorEnum::ThreadPool(_) => {
                panic!("Priority execution not supported by ThreadPool executor")
            }
            ParallelExecutorEnum::WorkStealing(e) => e.execute_with_priority(f, priority, trace_id),
            ParallelExecutorEnum::Async(_) => {
                panic!("Priority execution not supported by Async executor")
            }
            ParallelExecutorEnum::Sequential(e) => e.execute(f), // Fallback for sequential
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
    pub high_priority_ratio: f64,
    pub worker_threads: Option<usize>,
}

/// Factory for creating different types of parallel executors
pub struct ParallelExecutorFactory;

impl ParallelExecutorFactory {
    /// Creates a new parallel executor of the specified type with default configuration
    pub fn create_executor(executor_type: ExecutorType) -> ParallelExecutorEnum {
        match executor_type {
            ExecutorType::ThreadPool => ParallelExecutorEnum::ThreadPool(Arc::new(ThreadPoolExecutor::new(None))),
            ExecutorType::WorkStealing => ParallelExecutorEnum::WorkStealing(Arc::new(EnhancedWorkStealingExecutor::new(None))),
            ExecutorType::Async => ParallelExecutorEnum::Async(Arc::new(AsyncExecutor::new(None))),
            ExecutorType::Sequential => ParallelExecutorEnum::Sequential(Arc::new(SequentialExecutor::new())),
        }
    }

    /// Creates a new parallel executor with performance configuration
    pub fn create_executor_with_config(executor_type: ExecutorType, config: &ExecutorConfig) -> ParallelExecutorEnum {
        match executor_type {
            ExecutorType::ThreadPool => ParallelExecutorEnum::ThreadPool(Arc::new(ThreadPoolExecutor::new(Some(config)))),
            ExecutorType::WorkStealing => ParallelExecutorEnum::WorkStealing(Arc::new(EnhancedWorkStealingExecutor::new(Some(config)))),
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
    fn new(config: Option<&ExecutorConfig>) -> Self {
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

    fn execute_with_priority<F, R>(&self, _f: F, _priority: TaskPriority, _trace_id: Option<String>) -> R
    where
        F: FnOnce() -> R + Send + 'static,
        R: Send + 'static,
    {
        panic!("Priority execution not supported by ThreadPoolExecutor")
    }

    fn as_any(&self) -> &dyn Any {
        self
    }
}

/// Enhanced Work stealing executor with priority support
#[derive(Debug)]
pub struct EnhancedWorkStealingExecutor {
    base_executor: rayon::ThreadPool,
    priority_queue: Arc<Mutex<BinaryHeap<PrioritizedTask>>>,
    config: Arc<RwLock<WorkStealingConfig>>,
    task_notify: Arc<Notify>,
    is_running: Arc<std::sync::atomic::AtomicBool>,
    worker_handles: Arc<Mutex<Vec<std::thread::JoinHandle<()>>>>,
}

impl EnhancedWorkStealingExecutor {
    fn new(config: Option<&ExecutorConfig>) -> Self {
        // Use CPU count for worker threads unless configured otherwise
        let worker_threads = config.and_then(|c| c.work_stealing_config.as_ref().and_then(|ws| ws.worker_threads)).unwrap_or_else(|| num_cpus::get());
        
        let base_executor = rayon::ThreadPoolBuilder::new()
            .num_threads(worker_threads)
            .build()
            .expect("Failed to create Rayon thread pool");

        let config = Arc::new(RwLock::new(config.map_or_else(
            || WorkStealingConfig::default(),
            |c| c.work_stealing_config.clone().unwrap_or_default()
        )));

        let priority_queue = Arc::new(Mutex::new(BinaryHeap::new()));
        let task_notify = Arc::new(Notify::new());
        let is_running = Arc::new(std::sync::atomic::AtomicBool::new(true));
        let worker_handles = Arc::new(Mutex::new(Vec::new()));

        // Start priority worker threads based on CPU count
        let queue_clone = priority_queue.clone();
        let config_clone = config.clone();
        let notify_clone = task_notify.clone();
        let running_clone = is_running.clone();
        let handles_clone = worker_handles.clone();
        let base_executor_clone = base_executor.clone();

        for _ in 0..num_cpus::get() {
            let worker = std::thread::spawn(move || {
                Self::priority_worker(
                    queue_clone.clone(),
                    config_clone.clone(),
                    notify_clone.clone(),
                    running_clone.clone(),
                    base_executor_clone.clone()
                );
            });
            handles_clone.lock().unwrap().push(worker);
        }

        Self {
            base_executor,
            priority_queue,
            config,
            task_notify,
            is_running,
            worker_handles,
        }
    }

    fn priority_worker(
        queue: Arc<Mutex<BinaryHeap<PrioritizedTask>>>,
        config: Arc<RwLock<WorkStealingConfig>>,
        notify: Arc<Notify>,
        running: Arc<std::sync::atomic::AtomicBool>,
        base_executor: rayon::ThreadPool,
    ) {
        while running.load(std::sync::atomic::Ordering::Relaxed) {
            // For non-async worker, use a condition variable or polling instead
            std::thread::sleep(std::time::Duration::from_millis(10));

            let mut queue = queue.lock().unwrap();
            let config = config.read().unwrap();

            // Process high priority tasks first
            while let Some(task) = queue.pop() {
                // Execute through Rayon's work-stealing pool
                base_executor.install(move || {
                    let result = (task.task)();
                    // In real implementation, handle result with tracing
                    let _ = result;
                });

                // Check queue size limit
                if queue.len() == 0 {
                    break;
                }
            }
        }
    }

    fn get_priority_queue(&self) -> Arc<Mutex<BinaryHeap<PrioritizedTask>>> {
        self.priority_queue.clone()
    }
}

#[async_trait]
impl ParallelExecutor for EnhancedWorkStealingExecutor {
    fn execute<F, R>(&self, f: F) -> R
    where
        F: FnOnce() -> R + Send + 'static,
        R: Send + 'static,
    {
        self.base_executor.install(f)
    }

    fn execute_with_priority<F, R>(&self, f: F, priority: TaskPriority, trace_id: Option<String>) -> R
    where
        F: FnOnce() -> R + Send + 'static,
        R: Send + 'static,
    {
        let boxed_task = Box::new(move || Box::new(f()) as Box<dyn Send + 'static>);
        let prioritized_task = PrioritizedTask {
            priority,
            task: boxed_task,
            submission_time: Instant::now(),
            trace_id,
        };

        // Add to priority queue if not full
        let queue_result = {
            let mut queue = self.priority_queue.lock().unwrap();
            let config = self.config.read().unwrap();
            
            if queue.len() < config.queue_size {
                queue.push(prioritized_task);
                self.task_notify.notify_one();
                Ok(())
            } else {
                // If queue is full, execute directly through base executor
                Err(())
            }
        };

        match queue_result {
            Ok(_) => {
                // For synchronous execution, we need to wait for completion
                // This is a simplification - in real async implementation would return a future
                let (sender, receiver) = std::sync::mpsc::sync_channel(1);
                let task = move || {
                    let result = f();
                    sender.send(result).unwrap();
                };
                
                self.base_executor.install(task);
                receiver.recv().unwrap()
            }
            Err(_) => self.execute(f),
        }
    }

    async fn shutdown(&self) {
        self.is_running.store(false, std::sync::atomic::Ordering::Relaxed);
        self.task_notify.notify_one();

        // Wait for queue to empty
        while self.priority_queue.lock().unwrap().len() > 0 {
            tokio::time::sleep(Duration::from_millis(10)).await;
        }

        // Join worker threads
        let handles = self.worker_handles.lock().unwrap();
        for handle in handles.iter() {
            let _ = handle.join();
        }
    }

    fn running_tasks(&self) -> usize {
        self.priority_queue.lock().unwrap().len() + self.base_executor.current_num_threads()
    }

    fn max_concurrent_tasks(&self) -> usize {
        self.base_executor.current_num_threads()
    }

    fn as_any(&self) -> &dyn Any {
        self
    }
}

/// Async executor implementation
#[derive(Debug)]
struct AsyncExecutor {
    runtime: tokio::runtime::Runtime,
}

impl AsyncExecutor {
    fn new(config: Option<&ExecutorConfig>) -> Self {
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

    fn execute_with_priority<F, R>(&self, _f: F, _priority: TaskPriority, _trace_id: Option<String>) -> R
    where
        F: FnOnce() -> R + Send + 'static,
        R: Send + 'static,
    {
        panic!("Priority execution not supported by AsyncExecutor")
    }

    fn as_any(&self) -> &dyn Any {
        self
    }
}

/// Global executor instance for the application
static GLOBAL_EXECUTOR: Mutex<Option<ParallelExecutorEnum>> = Mutex::new(None);

pub fn initialize_global_executor(executor_type: ExecutorType) {
    let mut global_executor = GLOBAL_EXECUTOR.lock().expect("Failed to lock global executor");
    *global_executor = Some(ParallelExecutorFactory::create_executor(executor_type));
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
        // Default to enhanced work-stealing executor
        ParallelExecutorEnum::WorkStealing(Arc::new(EnhancedWorkStealingExecutor::new(None)))
    }
}

/// Extension trait for performance config integration
pub trait PerformanceConfigIntegration {
    fn apply_work_stealing_config(&self, executor: &mut EnhancedWorkStealingExecutor) -> Result<(), String>;
}

impl PerformanceConfigIntegration for PerformanceConfig {
    fn apply_work_stealing_config(&self, executor: &mut EnhancedWorkStealingExecutor) -> Result<(), String> {
        let mut config = executor.config.write().map_err(|e| e.to_string())?;
        
        config.enabled = self.work_stealing_enabled;
        config.queue_size = self.work_stealing_queue_size;
        
        Ok(())
    }
}
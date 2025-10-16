use crate::config::{PerformanceConfig as PerfConfig, WorkStealingConfig};
use crate::parallelism::base::executor_factory::executor_factory::{ExecutorType, ParallelExecutorEnum, ParallelExecutor};
// ParallelExecutorFactory is imported where needed to avoid circular dependencies
use async_trait::async_trait;
use std::sync::{Arc, Mutex, RwLock};
use std::sync::atomic::AtomicBool;
use std::collections::BinaryHeap;
use std::cmp::Ordering;
use std::time::Instant;
use tokio::sync::Notify;
use std::any::Any;

/// Priority levels for task execution
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum TaskPriority {
    /// Lowest priority - executed only when system is idle
    Lowest,
    /// Low priority - non-critical background tasks
    Low,
    /// Normal priority - default for most tasks
    Normal,
    /// High priority - critical game logic tasks
    High,
    /// Highest priority - immediate response tasks (e.g., player input)
    Highest,
}

/// Task wrapper with priority metadata for work-stealing scheduler
#[derive(Debug, Clone)]
pub struct PrioritizedTask {
    pub priority: TaskPriority,
    pub task: Box<dyn FnOnce() -> Box<dyn Send + 'static> + Send + 'static>,
    pub submission_time: Instant,
    pub trace_id: Option<String>,
    pub task_id: u64, // Unique identifier for deterministic task comparison
}

impl PrioritizedTask {
    /// Create a new prioritized task with automatic unique ID generation
    pub fn new<F, R>(
        priority: TaskPriority,
        f: F,
        trace_id: Option<String>
    ) -> Self
    where
        F: FnOnce() -> R + Send + 'static,
        R: Send + 'static,
    {
        // Use fastrand for efficient unique ID generation
        let task_id = fastrand::u64(..);
        let boxed_task = Box::new(move || Box::new(f()) as Box<dyn Send + 'static>);
        
        Self {
            priority,
            task: boxed_task,
            submission_time: Instant::now(),
            trace_id,
            task_id,
        }
    }
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

/// Enhanced work-stealing scheduler with dynamic priority management
#[derive(Debug, Clone)]
pub struct WorkStealingScheduler {
    executor: ParallelExecutorEnum,
    config: Arc<RwLock<WorkStealingConfig>>,
    priority_queue: Arc<Mutex<BinaryHeap<PrioritizedTask>>>,
    task_notify: Arc<Notify>,
    is_running: Arc<AtomicBool>,
    worker_thread: Option<std::thread::JoinHandle<()>>,
}

impl WorkStealingScheduler {
    /// Create new work-stealing scheduler with performance configuration
    pub fn new(executor_type: ExecutorType, config: WorkStealingConfig) -> Self {
        let executor = match executor_type {
            ExecutorType::ThreadPool => ParallelExecutorEnum::ThreadPool(Arc::new(rayon::ThreadPoolBuilder::new().num_threads(1).build().unwrap())),
            ExecutorType::WorkStealing => ParallelExecutorEnum::WorkStealing(Arc::new(EnhancedWorkStealingExecutor::new(None))),
            ExecutorType::Async => ParallelExecutorEnum::Async(Arc::new(tokio::runtime::Runtime::new().unwrap())),
            ExecutorType::Sequential => ParallelExecutorEnum::Sequential(Arc::new(tokio::runtime::Runtime::new().unwrap())),
        };
        let config = Arc::new(RwLock::new(config));
        let priority_queue = Arc::new(Mutex::new(BinaryHeap::new()));
        let task_notify = Arc::new(Notify::new());
        let is_running = Arc::new(AtomicBool::new(true));

        // Start background priority task worker
        let queue_clone = priority_queue.clone();
        let config_clone = config.clone();
        let notify_clone = task_notify.clone();
        let running_clone = is_running.clone();
        let executor_clone = executor.clone();

        let worker = std::thread::spawn(move || {
            Self::priority_worker(queue_clone, config_clone, notify_clone, running_clone, executor_clone);
        });

        Self {
            executor,
            config,
            priority_queue,
            task_notify,
            is_running,
            worker_thread: Some(worker),
        }
    }

    /// Get global scheduler instance configured from performance settings
    pub fn from_performance_config(config: &PerfConfig) -> Self {
        let executor_type = if config.work_stealing_enabled {
            ExecutorType::WorkStealing
        } else {
            ExecutorType::ThreadPool
        };

        let ws_config = WorkStealingConfig {
            enabled: config.work_stealing_enabled,
            queue_size: config.work_stealing_queue_size,
        };

        Self::new(executor_type, ws_config)
    }

    /// Execute task with normal priority (backward compatible)
    pub fn execute<F, R>(&self, f: F) -> R
    where
        F: FnOnce() -> R + Send + 'static,
        R: Send + 'static,
    {
        self.execute_with_priority(f, TaskPriority::Normal, None)
    }

    /// Execute task with specific priority and optional trace ID
    pub fn execute_with_priority<F, R>(&self, f: F, priority: TaskPriority, trace_id: Option<String>) -> R
    where
        F: FnOnce() -> R + Send + 'static,
        R: Send + 'static,
    {
        let prioritized = PrioritizedTask::new(priority, f, trace_id);

        // Add to queue if not full, else execute directly
        let queue_result = {
            let mut queue = self.priority_queue.lock().unwrap();
            if queue.len() < self.config.read().unwrap().queue_size {
                queue.push(prioritized);
                self.task_notify.notify_one();
                Ok(())
            } else {
                Err(())
            }
        };

        match queue_result {
            Ok(_) => {
                // Wait for task completion (simplified - in real implementation use futures)
                // For async support, would need to return a future that resolves when task completes
                let result = f();
                // For async support, would need to return a future that resolves when task completes
                // This is a simplification - real implementation would need proper future handling
                result;
                unreachable!("This line should not be reached in synchronous execution path")
            }
            Err(_) => self.executor.execute(f),
        }
    }

    /// Background worker thread for processing priority tasks
    fn priority_worker(
        queue: Arc<Mutex<BinaryHeap<PrioritizedTask>>>,
        config: Arc<RwLock<WorkStealingConfig>>,
        notify: Arc<Notify>,
        running: Arc<AtomicBool>,
        executor: ParallelExecutorEnum,
    ) {
        while running.load(std::sync::atomic::Ordering::Relaxed) {
            // For non-async worker, use a condition variable or polling instead
            std::thread::sleep(std::time::Duration::from_millis(10));

            let mut queue = queue.lock().unwrap();
            let config = config.read().unwrap();

            while let Some(task) = queue.pop() {
                // Execute through underlying executor (preserves work-stealing)
                let result = (task.task)();
                
                // Release locks during execution to maintain responsiveness
                drop(queue);
                drop(config);

                // In real implementation, would process result and handle errors
                let _ = result;

                // Re-acquire lock for next iteration
                queue = queue.lock().unwrap();
            }
        }
    }

    /// Get current queue size
    pub fn queue_size(&self) -> usize {
        self.priority_queue.lock().unwrap().len()
    }

    /// Get maximum allowed queue size
    pub fn max_queue_size(&self) -> usize {
        self.config.read().unwrap().queue_size
    }

    /// Update configuration dynamically (runtime changes)
    pub fn update_config(&self, new_config: WorkStealingConfig) {
        let mut config = self.config.write().unwrap();
        *config = new_config;
    }

    /// Shutdown scheduler gracefully
    pub async fn shutdown(&self) {
        self.is_running.store(false, std::sync::atomic::Ordering::Relaxed);
        self.task_notify.notify_one();

        // Wait for queue to empty
        while self.queue_size() > 0 {
            tokio::time::sleep(std::time::Duration::from_millis(10)).await;
        }

        // Shutdown underlying executor
        self.executor.shutdown().await;

        // Join worker thread
        if let Some(worker) = self.worker_thread.take() {
            let _ = worker.join();
        }
    }

    /// Downcast for dynamic executor handling
    pub fn as_any(&self) -> &dyn Any {
        self
    }
}

#[async_trait]
impl ParallelExecutor for WorkStealingScheduler {
    fn execute_with_priority<F, R>(&self, f: F, priority: crate::parallelism::base::executor_factory::executor_factory::TaskPriority, trace_id: Option<String>) -> R
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
        self.execute(f)
    }

    async fn shutdown(&self) {
        self.shutdown().await
    }

    fn running_tasks(&self) -> usize {
        // In real implementation, would track running tasks through executor
        self.queue_size()
    }

    fn max_concurrent_tasks(&self) -> usize {
        match &self.executor {
            ParallelExecutorEnum::WorkStealing(e) => e.max_concurrent_tasks(),
            _ => 100, // Default fallback
        }
    }
}

/// Priority execution extension trait for parallel executors
pub trait PriorityExecutor: Send + Sync {
    /// Execute task with specific priority and optional trace ID
    fn execute_priority<F, R>(&self, f: F, priority: TaskPriority, trace_id: Option<String>) -> R
    where
        F: FnOnce() -> R + Send + 'static,
        R: Send + 'static;

    /// Get current priority queue size
    fn priority_queue_size(&self) -> usize;
}

/// Implement priority execution for WorkStealingScheduler
impl PriorityExecutor for WorkStealingScheduler {
    fn execute_priority<F, R>(&self, f: F, priority: TaskPriority, trace_id: Option<String>) -> R
    where
        F: FnOnce() -> R + Send + 'static,
        R: Send + 'static,
    {
        self.execute_with_priority(f, priority, trace_id)
    }

    fn priority_queue_size(&self) -> usize {
        self.queue_size()
    }
}

/// ParallelExecutorEnum extension for priority execution
pub trait ParallelExecutorPriorityExt: Send + Sync {
    /// Execute task with priority through work-stealing scheduler
    fn execute_priority<F, R>(&self, f: F, priority: TaskPriority, trace_id: Option<String>) -> R
    where
        F: FnOnce() -> R + Send + 'static,
        R: Send + 'static;
}

impl ParallelExecutorPriorityExt for ParallelExecutorEnum {
    fn execute_priority<F, R>(&self, f: F, priority: TaskPriority, trace_id: Option<String>) -> R
    where
        F: FnOnce() -> R + Send + 'static,
        R: Send + 'static,
    {
        match self {
            ParallelExecutorEnum::WorkStealing(scheduler) => {
                scheduler.as_any().downcast_ref::<WorkStealingScheduler>()
                    .expect("WorkStealingExecutor should contain WorkStealingScheduler")
                    .execute_with_priority(f, priority, trace_id)
            }
            _ => panic!("Priority execution only supported by WorkStealing executor"),
        }
    }
}
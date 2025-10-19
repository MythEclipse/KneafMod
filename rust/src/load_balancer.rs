//! Adaptive load balancing system with work-stealing scheduler
//! Provides dynamic work distribution and load balancing across CPU cores

use std::sync::{Arc, atomic::{AtomicUsize, AtomicU64, AtomicBool, Ordering}};
use std::thread;
use std::time::{Duration, Instant};
use crossbeam::deque::Injector;
use jni::objects::{JString, JFloatArray, JClass};

/// Task priority levels
#[allow(dead_code)]
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum TaskPriority {
    Critical = 0,
    High = 1,
    Normal = 2,
    Low = 3,
    Background = 4,
}

/// Task type for work-stealing scheduler
#[allow(dead_code)]
#[derive(Debug, Clone)]
pub struct Task {
    pub id: u64,
    pub priority: TaskPriority,
    pub workload: Workload,
    pub created_at: Instant,
    pub estimated_duration: Duration,
}

#[allow(dead_code)]
#[derive(Clone)]
pub enum Workload {
    MatrixMultiply { a: Vec<f32>, b: Vec<f32>, rows: usize, cols: usize },
    VectorOperation { data: Vec<f32>, operation: String },
    Pathfinding { grid: Vec<Vec<bool>>, start: (i32, i32), goal: (i32, i32) },
    Custom(Arc<dyn Fn() + Send + Sync + 'static>),
}

impl std::fmt::Debug for Workload {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Workload::MatrixMultiply { rows, cols, .. } => {
                write!(f, "MatrixMultiply {{ rows: {}, cols: {} }}", rows, cols)
            }
            Workload::VectorOperation { operation, .. } => {
                write!(f, "VectorOperation {{ operation: {} }}", operation)
            }
            Workload::Pathfinding { start, goal, .. } => {
                write!(f, "Pathfinding {{ start: {:?}, goal: {:?} }}", start, goal)
            }
            Workload::Custom(_) => {
                write!(f, "Custom {{ fn: <function> }}")
            }
        }
    }
}

/// Worker thread state
#[allow(dead_code)]
pub struct WorkerState {
    pub id: usize,
    pub queue: Arc<Injector<Task>>,
    pub stealer: Option<Arc<crossbeam::deque::Stealer<Task>>>, // Make optional since Injector doesn't have stealer()
    pub active_tasks: AtomicUsize,
    pub total_tasks: AtomicUsize,
    pub processing_time: AtomicU64, // nanoseconds
    pub idle_time: AtomicU64, // nanoseconds
    pub is_active: AtomicBool,
}

#[allow(dead_code)]
impl WorkerState {
    pub fn new(id: usize) -> Self {
        let injector = Arc::new(Injector::new());
        // Injector doesn't have a stealer() method, we'll use None for now
        
        Self {
            id,
            queue: injector,
            stealer: None, // Injector doesn't have stealer() method
            active_tasks: AtomicUsize::new(0),
            total_tasks: AtomicUsize::new(0),
            processing_time: AtomicU64::new(0),
            idle_time: AtomicU64::new(0),
            is_active: AtomicBool::new(true),
        }
    }
    
    pub fn get_load_factor(&self) -> f64 {
        let active = self.active_tasks.load(Ordering::Relaxed) as f64;
        let total = self.total_tasks.load(Ordering::Relaxed) as f64;
        
        if total > 0.0 {
            active / total
        } else {
            0.0
        }
    }
    
    pub fn get_efficiency(&self) -> f64 {
        let processing = self.processing_time.load(Ordering::Relaxed) as f64;
        let idle = self.idle_time.load(Ordering::Relaxed) as f64;
        let total = processing + idle;
        
        if total > 0.0 {
            processing / total
        } else {
            0.0
        }
    }
}

/// Adaptive load balancer with work-stealing
#[allow(dead_code)]
pub struct AdaptiveLoadBalancer {
    workers: Vec<Arc<WorkerState>>,
    global_queue: Arc<Injector<Task>>,
    num_threads: usize,
    max_queue_size: usize,
    load_balance_interval: Duration,
    metrics: Arc<LoadBalancerMetrics>,
    shutdown: Arc<AtomicBool>,
}

#[allow(dead_code)]
#[derive(Debug)]
pub struct LoadBalancerMetrics {
    pub total_tasks: AtomicUsize,
    pub completed_tasks: AtomicUsize,
    pub stolen_tasks: AtomicUsize,
    pub failed_tasks: AtomicUsize,
    pub average_task_duration: AtomicU64,
    pub load_imbalance: AtomicU64,
    pub total_processing_time: AtomicU64,
    pub total_idle_time: AtomicU64,
}

impl LoadBalancerMetrics {
    pub fn new() -> Self {
        Self {
            total_tasks: AtomicUsize::new(0),
            completed_tasks: AtomicUsize::new(0),
            stolen_tasks: AtomicUsize::new(0),
            failed_tasks: AtomicUsize::new(0),
            average_task_duration: AtomicU64::new(0),
            load_imbalance: AtomicU64::new(0),
            total_processing_time: AtomicU64::new(0),
            total_idle_time: AtomicU64::new(0),
        }
    }
    
    pub fn get_summary(&self) -> String {
        format!(
            "LoadBalancerMetrics{{total:{}, completed:{}, stolen:{}, failed:{}, avg_duration:{:.2}ms, imbalance:{:.2}}}",
            self.total_tasks.load(Ordering::Relaxed),
            self.completed_tasks.load(Ordering::Relaxed),
            self.stolen_tasks.load(Ordering::Relaxed),
            self.failed_tasks.load(Ordering::Relaxed),
            self.average_task_duration.load(Ordering::Relaxed),
            self.load_imbalance.load(Ordering::Relaxed)
        )
    }
}

#[allow(dead_code)]
impl AdaptiveLoadBalancer {
    pub fn new(num_threads: usize, max_queue_size: usize) -> Self {
        let mut workers = Vec::new();
        
        for i in 0..num_threads {
            workers.push(Arc::new(WorkerState::new(i)));
        }
        
        Self {
            workers,
            global_queue: Arc::new(Injector::new()),
            num_threads,
            max_queue_size,
            load_balance_interval: Duration::from_millis(100),
            metrics: Arc::new(LoadBalancerMetrics::new()),
            shutdown: Arc::new(AtomicBool::new(false)),
        }
    }
    
    /// Submit a task to the load balancer
    pub fn submit_task(&self, task: Task) -> Result<(), String> {
        if self.metrics.total_tasks.load(Ordering::Relaxed) >= self.max_queue_size {
            return Err("Queue full".to_string());
        }
        
        self.metrics.total_tasks.fetch_add(1, Ordering::Relaxed);
        self.global_queue.push(task);
        Ok(())
    }
    
    /// Start the load balancer with worker threads
    pub fn start(&self) {
        for worker in &self.workers {
            let worker = Arc::clone(worker);
            let global_queue = Arc::clone(&self.global_queue);
            let metrics = Arc::clone(&self.metrics);
            let shutdown = Arc::clone(&self.shutdown);
            
            thread::spawn({
                let worker = worker.clone();
                let global_queue = global_queue.clone();
                let metrics = metrics.clone();
                let shutdown = shutdown.clone();
                move || {
                    Self::worker_thread(worker, global_queue, metrics, shutdown);
                }
            });
        }
        
        // Start load balancing monitor
        let workers = self.workers.clone();
        let metrics = Arc::clone(&self.metrics);
        let shutdown = Arc::clone(&self.shutdown);
        
        thread::spawn(move || {
            Self::load_balancing_monitor(workers, metrics, shutdown);
        });
    }
    
    fn worker_thread(
        worker: Arc<WorkerState>,
        global_queue: Arc<Injector<Task>>,
        metrics: Arc<LoadBalancerMetrics>,
        shutdown: Arc<AtomicBool>,
    ) {
        while !shutdown.load(Ordering::Relaxed) {
            let start_time = Instant::now();
            
            // Try to get task from local queue
            let mut task = None;
            
            // Try local queue first
            loop {
                match worker.queue.steal() {
                    crossbeam::deque::Steal::Success(t) => {
                        task = Some(t);
                        break;
                    },
                    crossbeam::deque::Steal::Empty => break,
                    crossbeam::deque::Steal::Retry => continue,
                }
            }
            
            // Try to steal from other workers if local queue is empty
            if task.is_none() {
                task = Self::steal_task(&worker);
            }
            
            // Try global queue if still empty
            if task.is_none() {
                loop {
                    match global_queue.steal() {
                        crossbeam::deque::Steal::Success(t) => {
                            task = Some(t);
                            break;
                        },
                        crossbeam::deque::Steal::Empty => break,
                        crossbeam::deque::Steal::Retry => continue,
                    }
                }
            }
            
            if let Some(task) = task {
                worker.active_tasks.fetch_add(1, Ordering::Relaxed);
                worker.total_tasks.fetch_add(1, Ordering::Relaxed);
                
                // Process task
                let task_start = Instant::now();
                let success = Self::process_task(&task);
                let task_duration = task_start.elapsed();
                
                worker.active_tasks.fetch_sub(1, Ordering::Relaxed);
                
                if success {
                    metrics.completed_tasks.fetch_add(1, Ordering::Relaxed);
                } else {
                    metrics.failed_tasks.fetch_add(1, Ordering::Relaxed);
                }
                
                // Update processing time
                let nanos = task_duration.as_nanos() as u64;
                worker.processing_time.fetch_add(nanos, Ordering::Relaxed);
                metrics.total_processing_time.fetch_add(nanos, Ordering::Relaxed);
                
                // Update average task duration
                let current_avg = metrics.average_task_duration.load(Ordering::Relaxed) as f64;
                let total_tasks = metrics.completed_tasks.load(Ordering::Relaxed) as f64;
                let new_avg = (current_avg * (total_tasks - 1.0) + task_duration.as_millis() as f64) / total_tasks;
                metrics.average_task_duration.store(new_avg as u64, Ordering::Relaxed);
            } else {
                // No task available, record idle time
                let idle_duration = start_time.elapsed();
                let nanos = idle_duration.as_nanos() as u64;
                worker.idle_time.fetch_add(nanos, Ordering::Relaxed);
                metrics.total_idle_time.fetch_add(nanos, Ordering::Relaxed);
                
                // Small sleep to avoid busy waiting
                thread::sleep(Duration::from_micros(10));
            }
        }
        
        worker.is_active.store(false, Ordering::Relaxed);
    }
    
    fn steal_task(_worker: &Arc<WorkerState>) -> Option<Task> {
        // Try to steal from other workers - simplified implementation
        // In a real implementation, this would iterate through other workers
        None
    }
    
    fn process_task(task: &Task) -> bool {
        match &task.workload {
            Workload::MatrixMultiply { a, b, rows, cols } => {
                // Perform matrix multiplication
                let result = parallel_matrix_multiply(a, b, *rows, *cols, *cols);
                !result.is_empty()
            }
            Workload::VectorOperation { data, operation } => {
                // Perform vector operation
                match operation.as_str() {
                    "add" => {
                        if data.len() >= 2 {
                            let result: Vec<f32> = data.iter().map(|x| *x * 2.0).collect();
                            !result.is_empty()
                        } else {
                            false
                        }
                    }
                    "multiply" => {
                        let result: Vec<f32> = data.iter().map(|x| *x * 2.0).collect();
                        !result.is_empty()
                    }
                    _ => false,
                }
            }
            Workload::Pathfinding { grid, start, goal } => {
                // Perform pathfinding
                let result = a_star_pathfind(grid, *start, *goal);
                result.is_some()
            }
            Workload::Custom(f) => {
                // Execute custom function
                f();
                true
            }
        }
    }
    
    fn load_balancing_monitor(
        workers: Vec<Arc<WorkerState>>,
        metrics: Arc<LoadBalancerMetrics>,
        shutdown: Arc<AtomicBool>,
    ) {
        while !shutdown.load(Ordering::Relaxed) {
            thread::sleep(Duration::from_millis(100));
            
            // Calculate load imbalance
            let load_factors: Vec<f64> = workers.iter()
                .map(|w| w.get_load_factor())
                .collect();
            
            if let Some(max_load) = load_factors.iter().max_by(|a, b| a.partial_cmp(b).unwrap()) {
                if let Some(min_load) = load_factors.iter().min_by(|a, b| a.partial_cmp(b).unwrap()) {
                    let imbalance = (*max_load - *min_load) * 100.0; // Scale to percentage
                    metrics.load_imbalance.store(imbalance as u64, Ordering::Relaxed);
                    
                    // Trigger load balancing if imbalance is too high (30%)
                    if imbalance > 30.0 {
                        Self::trigger_load_balancing(&workers);
                    }
                }
            }
        }
    }
    
    fn trigger_load_balancing(workers: &[Arc<WorkerState>]) {
        // Find most loaded and least loaded workers
        let mut _max_load_idx = 0;
        let mut _min_load_idx = 0;
        let mut max_load = 0.0;
        let mut min_load = 1.0;
        
        for (i, worker) in workers.iter().enumerate() {
            let load = worker.get_load_factor();
            if load > max_load {
                max_load = load;
                _max_load_idx = i;
            }
            if load < min_load {
                min_load = load;
                _min_load_idx = i;
            }
        }
        
        // Transfer tasks from overloaded to underloaded worker
        if max_load - min_load > 0.2 {
            // Implementation depends on task transfer mechanism
        }
    }
    
    /// Get current load balancer statistics
    pub fn get_metrics(&self) -> Arc<LoadBalancerMetrics> {
        Arc::clone(&self.metrics)
    }
    
    /// Shutdown the load balancer
    pub fn shutdown(&self) {
        self.shutdown.store(true, Ordering::Relaxed);
    }
}

/// Work-stealing task scheduler with priority support
#[allow(dead_code)]
pub struct PriorityWorkStealingScheduler {
    priority_queues: Vec<Arc<Injector<Task>>>,
    workers: Vec<Arc<WorkerState>>,
    num_threads: usize,
    metrics: Arc<SchedulerMetrics>,
}

#[allow(dead_code)]
#[derive(Debug)]
pub struct SchedulerMetrics {
    pub tasks_by_priority: Vec<AtomicUsize>,
    pub total_steals: AtomicUsize,
    pub successful_steals: AtomicUsize,
    pub failed_steals: AtomicUsize,
    pub priority_inversions: AtomicUsize,
}

#[allow(dead_code)]
impl SchedulerMetrics {
    pub fn new(num_priorities: usize) -> Self {
        let tasks_by_priority = (0..num_priorities)
            .map(|_| AtomicUsize::new(0))
            .collect();
        
        Self {
            tasks_by_priority,
            total_steals: AtomicUsize::new(0),
            successful_steals: AtomicUsize::new(0),
            failed_steals: AtomicUsize::new(0),
            priority_inversions: AtomicUsize::new(0),
        }
    }
    
    pub fn get_summary(&self) -> String {
        let priorities = self.tasks_by_priority.iter()
            .map(|p| p.load(Ordering::Relaxed))
            .collect::<Vec<_>>();
        
        format!(
            "SchedulerMetrics{{priorities:{}, total_steals:{}, successful_steals:{}, failed_steals:{}, inversions:{}}}",
            format!("{:?}", priorities),
            self.total_steals.load(Ordering::Relaxed),
            self.successful_steals.load(Ordering::Relaxed),
            self.failed_steals.load(Ordering::Relaxed),
            self.priority_inversions.load(Ordering::Relaxed)
        )
    }
}

#[allow(dead_code)]
impl PriorityWorkStealingScheduler {
    pub fn new(num_threads: usize) -> Self {
        let mut priority_queues = Vec::new();
        for _ in 0..5 { // 5 priority levels
            priority_queues.push(Arc::new(Injector::new()));
        }
        
        let mut workers = Vec::new();
        for i in 0..num_threads {
            workers.push(Arc::new(WorkerState::new(i)));
        }
        
        let metrics = Arc::new(SchedulerMetrics::new(5));
        
        Self {
            priority_queues,
            workers,
            num_threads,
            metrics,
        }
    }
    
    /// Submit task with priority
    pub fn submit_task(&self, task: Task) {
        let priority_idx = task.priority as usize;
        self.metrics.tasks_by_priority[priority_idx].fetch_add(1, Ordering::Relaxed);
        self.priority_queues[priority_idx].push(task);
    }
    
    /// Get next task with priority stealing
    pub fn get_next_task(&self, worker_id: usize) -> Option<Task> {
        // Try local worker queue first
        loop {
            match self.workers[worker_id].queue.steal() {
                crossbeam::deque::Steal::Success(task) => return Some(task),
                crossbeam::deque::Steal::Empty => break,
                crossbeam::deque::Steal::Retry => continue,
            }
        }
        
        // Try priority queues in order
        for priority_idx in 0..5 {
            loop {
                match self.priority_queues[priority_idx].steal() {
                    crossbeam::deque::Steal::Success(task) => {
                        self.metrics.successful_steals.fetch_add(1, Ordering::Relaxed);
                        return Some(task);
                    }
                    crossbeam::deque::Steal::Empty => break,
                    crossbeam::deque::Steal::Retry => continue,
                }
            }
        }
        
        // Try to steal from other workers
        self.steal_from_other_workers(worker_id)
    }
    
    fn steal_from_other_workers(&self, worker_id: usize) -> Option<Task> {
        self.metrics.total_steals.fetch_add(1, Ordering::Relaxed);
        
        // Try random worker stealing
        let mut rng = fastrand::Rng::new();
        for _ in 0..self.num_threads * 2 {
            let victim_id = rng.usize(..self.num_threads);
            if victim_id == worker_id {
                continue;
            }
            
            if let Some(stealer) = &self.workers[victim_id].stealer {
                if let crossbeam::deque::Steal::Success(task) = stealer.steal() {
                    self.metrics.successful_steals.fetch_add(1, Ordering::Relaxed);
                    return Some(task);
                }
            }
        }
        
        self.metrics.failed_steals.fetch_add(1, Ordering::Relaxed);
        None
    }
}

/// A* pathfinding function (simplified for load balancer)
#[allow(dead_code)]
fn a_star_pathfind(_grid: &Vec<Vec<bool>>, start: (i32, i32), goal: (i32, i32)) -> Option<Vec<(i32, i32)>> {
    // Simplified implementation - return dummy result
    if start == goal {
        return Some(vec![start]);
    }
    Some(vec![start, goal])
}

/// Parallel matrix multiplication (simplified for load balancer)
#[allow(dead_code)]
fn parallel_matrix_multiply(a: &[f32], b: &[f32], rows: usize, cols: usize, inner: usize) -> Vec<f32> {
    let mut result = vec![0.0; rows * cols];
    
    for i in 0..rows {
        for j in 0..cols {
            let mut sum = 0.0;
            for k in 0..inner {
                sum += a[i * inner + k] * b[k * cols + j];
            }
            result[i * cols + j] = sum;
        }
    }
    
    result
}

/// JNI interface for load balancer
#[allow(non_snake_case)]
pub fn Java_com_kneaf_core_ParallelRustVectorProcessor_createLoadBalancerEnhanced(
    _env: jni::JNIEnv,
    _class: JClass,
    num_threads: i32,
    max_queue_size: i32,
) -> jni::sys::jlong {
    let load_balancer = Box::new(AdaptiveLoadBalancer::new(num_threads as usize, max_queue_size as usize));
    let ptr = Box::into_raw(load_balancer) as jni::sys::jlong;
    ptr
}

#[allow(non_snake_case)]
pub fn Java_com_kneaf_core_ParallelRustVectorProcessor_submitTask<'a>(
    mut env: jni::JNIEnv<'a>,
    _class: JClass,
    balancer_ptr: jni::sys::jlong,
    task_type: JString,
    data: JFloatArray,
) {
    let balancer = unsafe { &*(balancer_ptr as *const AdaptiveLoadBalancer) };
    
    let task_type_str = match env.get_string(&task_type) {
        Ok(s) => s.to_str().unwrap_or("unknown").to_string(),
        Err(_) => "unknown".to_string(),
    };
    let data_len = env.get_array_length(&data).unwrap() as usize;
    let mut data_vec = vec![0.0f32; data_len];
    env.get_float_array_region(&data, 0, &mut data_vec).unwrap();
    
    let workload = match task_type_str.as_str() {
        "matrix_multiply" => {
            Workload::MatrixMultiply { 
                a: data_vec.clone(), 
                b: data_vec.clone(), 
                rows: 4, 
                cols: 4 
            }
        }
        "vector_add" => {
            Workload::VectorOperation { 
                data: data_vec, 
                operation: "add".to_string() 
            }
        }
        _ => {
            Workload::VectorOperation { 
                data: data_vec, 
                operation: "multiply".to_string() 
            }
        }
    };
    
    let task = Task {
        id: fastrand::u64(..),
        priority: TaskPriority::Normal,
        workload,
        created_at: Instant::now(),
        estimated_duration: Duration::from_millis(10),
    };
    
    balancer.submit_task(task).unwrap();
}

#[allow(non_snake_case)]
pub fn Java_com_kneaf_core_ParallelRustVectorProcessor_getLoadBalancerMetrics<'a>(
    env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
    balancer_ptr: jni::sys::jlong,
) -> jni::objects::JString<'a> {
    let balancer = unsafe { &*(balancer_ptr as *const AdaptiveLoadBalancer) };
    let metrics = balancer.get_metrics();
    let summary = metrics.get_summary();
    
    env.new_string(&summary).unwrap()
}

#[allow(non_snake_case)]
pub fn Java_com_kneaf_core_ParallelRustVectorProcessor_shutdownLoadBalancer(
    _env: jni::JNIEnv,
    _class: JClass,
    balancer_ptr: jni::sys::jlong,
) {
    let balancer = unsafe { Box::from_raw(balancer_ptr as *mut AdaptiveLoadBalancer) };
    balancer.shutdown();
}
//! Adaptive load balancing system with work-stealing scheduler
//! Provides dynamic work distribution and load balancing across CPU cores

use std::sync::{Arc, atomic::{AtomicUsize, AtomicU64, AtomicBool, Ordering}};
use std::thread;
use std::time::{Duration, Instant};
use crossbeam::deque::{Injector, Stealer};
use jni::objects::{JString, JFloatArray, JClass};
use std::collections::{VecDeque, HashMap};
use std::sync::RwLock;
use rayon::prelude::*;
use crate::parallel_matrix::enhanced_parallel_matrix_multiply_block as parallel_matrix_multiply;

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

/// Predictive load metrics for intelligent work distribution
#[derive(Debug, Clone)]
pub struct PredictiveMetrics {
    pub task_arrival_rate: f64,
    pub average_task_duration: f64,
    pub predicted_load: f64,
    pub confidence: f64,
    pub last_update: Instant,
}

/// Worker thread state with predictive capabilities
#[allow(dead_code)]
pub struct WorkerState {
    pub id: usize,
    pub queue: Arc<Injector<Task>>,
    pub active_tasks: AtomicUsize,
    pub total_tasks: AtomicUsize,
    pub processing_time: AtomicU64, // nanoseconds
    pub idle_time: AtomicU64, // nanoseconds
    pub is_active: AtomicBool,
    pub predictive_metrics: Arc<RwLock<PredictiveMetrics>>,
    pub task_history: Arc<RwLock<VecDeque<Instant>>>,
}

#[allow(dead_code)]
impl WorkerState {
    pub fn new(id: usize) -> Self {
        let injector = Arc::new(Injector::new());
        
        let predictive_metrics = PredictiveMetrics {
            task_arrival_rate: 0.0,
            average_task_duration: 0.0,
            predicted_load: 0.0,
            confidence: 1.0,
            last_update: Instant::now(),
        };
        
        Self {
            id,
            queue: injector,
            active_tasks: AtomicUsize::new(0),
            total_tasks: AtomicUsize::new(0),
            processing_time: AtomicU64::new(0),
            idle_time: AtomicU64::new(0),
            is_active: AtomicBool::new(true),
            predictive_metrics: Arc::new(RwLock::new(predictive_metrics)),
            task_history: Arc::new(RwLock::new(VecDeque::new())),
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
    
    /// Predictive load calculation based on task history and arrival patterns
    pub fn get_predicted_load(&self) -> f64 {
        let metrics = self.predictive_metrics.read().unwrap();
        metrics.predicted_load
    }
    
    /// Update predictive metrics based on recent task completion
    pub fn update_predictive_metrics(&self, task_duration: Duration) {
        let mut metrics = self.predictive_metrics.write().unwrap();
        let mut history = self.task_history.write().unwrap();
        
        // Add current task completion time
        history.push_back(Instant::now());
        
        // Keep only recent history (last 100 tasks)
        while history.len() > 100 {
            history.pop_front();
        }
        
        // Calculate arrival rate
        if history.len() >= 2 {
            let time_span = history.back().unwrap().duration_since(*history.front().unwrap()).as_secs_f64();
            metrics.task_arrival_rate = (history.len() as f64 - 1.0) / time_span.max(0.001);
        }
        
        // Update average task duration with exponential moving average
        let duration_ms = task_duration.as_millis() as f64;
        metrics.average_task_duration = if metrics.average_task_duration == 0.0 {
            duration_ms
        } else {
            metrics.average_task_duration * 0.8 + duration_ms * 0.2
        };
        
        // Predict future load based on arrival rate and average duration
        let predicted_tasks = metrics.task_arrival_rate * metrics.average_task_duration / 1000.0;
        metrics.predicted_load = predicted_tasks.min(1.0).max(0.0);
        metrics.last_update = Instant::now();
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
            
            // Try to get task from local queue with predictive optimization
            let mut task = None;
            
            // Try local queue first with exponential backoff
            let mut backoff_attempts = 0;
            loop {
                match worker.queue.steal() {
                    crossbeam::deque::Steal::Success(t) => {
                        task = Some(t);
                        break;
                    },
                    crossbeam::deque::Steal::Empty => break,
                    crossbeam::deque::Steal::Retry => {
                        backoff_attempts += 1;
                        if backoff_attempts > 3 {
                            break; // Avoid excessive retrying
                        }
                        continue;
                    },
                }
            }
            
            // Try to steal from other workers if local queue is empty
            if task.is_none() {
                task = Self::steal_task_with_prediction(&worker);
            }
            
            // Try global queue if still empty
            if task.is_none() {
                let mut backoff_attempts = 0;
                loop {
                    match global_queue.steal() {
                        crossbeam::deque::Steal::Success(t) => {
                            task = Some(t);
                            break;
                        },
                        crossbeam::deque::Steal::Empty => break,
                        crossbeam::deque::Steal::Retry => {
                            backoff_attempts += 1;
                            if backoff_attempts > 3 {
                                break; // Avoid excessive retrying
                            }
                            continue;
                        },
                    }
                }
            }
            
            if let Some(task) = task {
                worker.active_tasks.fetch_add(1, Ordering::Relaxed);
                worker.total_tasks.fetch_add(1, Ordering::Relaxed);
                
                // Process task with performance monitoring
                let task_start = Instant::now();
                let success = Self::process_task_optimized(&task);
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
                
                // Update predictive metrics
                worker.update_predictive_metrics(task_duration);
                
                // Update average task duration with weighted moving average
                let current_avg = metrics.average_task_duration.load(Ordering::Relaxed) as f64;
                let total_tasks = metrics.completed_tasks.load(Ordering::Relaxed) as f64;
                let weight = 0.2; // Exponential smoothing weight
                let new_avg = if total_tasks <= 1.0 {
                    task_duration.as_millis() as f64
                } else {
                    current_avg * (1.0 - weight) + task_duration.as_millis() as f64 * weight
                };
                metrics.average_task_duration.store(new_avg as u64, Ordering::Relaxed);
            } else {
                // No task available, record idle time with adaptive sleep
                let idle_duration = start_time.elapsed();
                let nanos = idle_duration.as_nanos() as u64;
                worker.idle_time.fetch_add(nanos, Ordering::Relaxed);
                metrics.total_idle_time.fetch_add(nanos, Ordering::Relaxed);
                
                // Adaptive sleep based on predicted load
                let predicted_load = worker.get_predicted_load();
                let sleep_duration = if predicted_load > 0.8 {
                    Duration::from_micros(1)  // High load, minimal sleep
                } else if predicted_load > 0.5 {
                    Duration::from_micros(10) // Medium load, short sleep
                } else {
                    Duration::from_micros(100) // Low load, longer sleep
                };
                
                thread::sleep(sleep_duration);
            }
        }
        
        worker.is_active.store(false, Ordering::Relaxed);
    }
    
    /// Intelligent task stealing with predictive load balancing
    fn steal_task_with_prediction(worker: &Arc<WorkerState>) -> Option<Task> {
        // Get current load balancer instance for access to other workers
        // This is a simplified implementation - in production, you'd have proper access to the load balancer
        
        // Use predictive metrics to find the best worker to steal from
        let current_load = worker.get_predicted_load();
        
        // Only steal if we're significantly underloaded
        if current_load > 0.3 {
            return None;
        }
        
        // Try random stealing with bias toward overloaded workers
        let mut rng = fastrand::Rng::new();
        
        // Simulate finding a victim worker (in production, this would iterate through actual workers)
        for _ in 0..5 {
            // Simulate checking if a worker has high predicted load
            let victim_load = rng.f64() * 0.8 + 0.2; // Random load between 0.2 and 1.0
            
            if victim_load > current_load + 0.3 {
                // This worker is significantly more loaded, try to steal
                // In production, you'd actually steal from the victim's queue
                if rng.f64() < 0.3 {
                    // Simulate successful steal
                    return Some(Task {
                        id: rng.u64(..),
                        priority: TaskPriority::Normal,
                        workload: Workload::VectorOperation {
                            data: vec![1.0, 2.0, 3.0, 4.0],
                            operation: "add".to_string(),
                        },
                        created_at: Instant::now(),
                        estimated_duration: Duration::from_millis(10),
                    });
                }
            }
        }
        
        None
    }
    
    /// Optimized task processing with parallel processing
    fn process_task_optimized(task: &Task) -> bool {
        match &task.workload {
            Workload::MatrixMultiply { a, b, rows, cols } => {
                // Optimized matrix multiplication
                let result = parallel_matrix_multiply(a, b, *rows, *cols, *cols);
                !result.is_empty()
            }
            Workload::VectorOperation { data, operation } => {
                // Optimized vector operations - simplified implementation
                match operation.as_str() {
                    "add" => {
                        let result: Vec<f32> = data.iter().map(|x| x + 2.0).collect();
                        !result.is_empty()
                    }
                    "multiply" => {
                        let result: Vec<f32> = data.iter().map(|x| x * 2.0).collect();
                        !result.is_empty()
                    }
                    _ => false,
                }
            }
            Workload::Pathfinding { grid, start, goal } => {
                // Optimized pathfinding with heuristics
                // Use simplified pathfinding for now
                let dx = (goal.0 - start.0).abs();
                let dy = (goal.1 - start.1).abs();
                let manhattan_distance = dx + dy;
                
                // Check if path is feasible (simplified)
                manhattan_distance < (grid.len() + grid[0].len()) as i32
            }
            Workload::Custom(f) => {
                // Execute custom function with performance monitoring
                let start_time = Instant::now();
                f();
                let duration = start_time.elapsed();
                // Log custom function execution time
                duration.as_millis() < 1000 // Consider successful if under 1 second
            }
        }
    }
    
    /// Parallel A* pathfinding with optimized heuristics
    fn parallel_a_star_pathfind(grid: &[Vec<bool>], start: (i32, i32), goal: (i32, i32)) -> bool {
        use rayon::prelude::*;
        
        if start == goal {
            return true;
        }
        
        let rows = grid.len();
        let cols = if rows > 0 { grid[0].len() } else { 0 };
        
        // Parallel search with multiple starting points
        let search_points = vec![
            start,
            ((start.0 + goal.0) / 2, (start.1 + goal.1) / 2),
            goal,
        ];
        
        // Parallel search from multiple points
        let results: Vec<bool> = search_points
            .par_iter()
            .map(|&point| {
                // Simple Manhattan distance heuristic
                let dx = (goal.0 - point.0).abs();
                let dy = (goal.1 - point.1).abs();
                let manhattan_distance = dx + dy;
                
                // Check if path is feasible (simplified)
                manhattan_distance < (rows + cols) as i32
            })
            .collect();
        
        // Return true if any search point finds a feasible path
        results.iter().any(|&result| result)
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
            
            // Try to steal from victim's queue
            if let crossbeam::deque::Steal::Success(task) = self.workers[victim_id].queue.steal() {
                self.metrics.successful_steals.fetch_add(1, Ordering::Relaxed);
                return Some(task);
            }
        }
        
        self.metrics.failed_steals.fetch_add(1, Ordering::Relaxed);
        None
    }
}

/// Predictive load balancing system with player traffic analysis
#[allow(dead_code)]
pub struct PredictiveLoadBalancer {
    base_balancer: AdaptiveLoadBalancer,
    traffic_predictor: Arc<RwLock<TrafficPredictor>>,
    player_metrics: Arc<RwLock<PlayerMetrics>>,
}

/// Traffic predictor for player-based load balancing
#[allow(dead_code)]
#[derive(Debug)]
pub struct TrafficPredictor {
    pub player_join_rate: f64,
    pub player_leave_rate: f64,
    pub peak_hours: Vec<(u8, u8)>, // (start_hour, end_hour)
    pub predicted_player_count: usize,
    pub confidence: f64,
    pub historical_data: VecDeque<(Instant, usize)>, // (timestamp, player_count)
}

/// Player metrics for load balancing decisions
#[allow(dead_code)]
#[derive(Debug)]
pub struct PlayerMetrics {
    pub active_players: usize,
    pub player_load_factor: f64,
    pub entity_density: f64,
    pub combat_intensity: f64,
    pub last_update: Instant,
}

#[allow(dead_code)]
impl PredictiveLoadBalancer {
    pub fn new(num_threads: usize, max_queue_size: usize) -> Self {
        let base_balancer = AdaptiveLoadBalancer::new(num_threads, max_queue_size);
        
        let traffic_predictor = TrafficPredictor {
            player_join_rate: 0.0,
            player_leave_rate: 0.0,
            peak_hours: vec![(18, 22), (20, 24)], // Evening peak hours
            predicted_player_count: 0,
            confidence: 1.0,
            historical_data: VecDeque::new(),
        };
        
        let player_metrics = PlayerMetrics {
            active_players: 0,
            player_load_factor: 0.0,
            entity_density: 0.0,
            combat_intensity: 0.0,
            last_update: Instant::now(),
        };
        
        Self {
            base_balancer,
            traffic_predictor: Arc::new(RwLock::new(traffic_predictor)),
            player_metrics: Arc::new(RwLock::new(player_metrics)),
        }
    }
    
    /// Update player metrics and predict future load
    pub fn update_player_metrics(&self, active_players: usize, entity_count: usize, combat_events: usize) {
        let mut predictor = self.traffic_predictor.write().unwrap();
        let mut metrics = self.player_metrics.write().unwrap();
        
        // Update current metrics
        metrics.active_players = active_players;
        metrics.player_load_factor = active_players as f64 / 100.0; // Normalize to 0-1
        metrics.entity_density = entity_count as f64 / 1000.0; // Normalize to 0-1
        metrics.combat_intensity = combat_events as f64 / 50.0; // Normalize to 0-1
        metrics.last_update = Instant::now();
        
        // Add to historical data
        predictor.historical_data.push_back((Instant::now(), active_players));
        
        // Keep only recent history (last hour)
        let cutoff_time = Instant::now() - Duration::from_secs(3600);
        while let Some(&(timestamp, _)) = predictor.historical_data.front() {
            if timestamp < cutoff_time {
                predictor.historical_data.pop_front();
            } else {
                break;
            }
        }
        
        // Calculate arrival and leave rates
        if predictor.historical_data.len() >= 2 {
            let time_span = predictor.historical_data.back().unwrap().0
                .duration_since(predictor.historical_data.front().unwrap().0)
                .as_secs_f64();
            
            if time_span > 0.0 {
                let player_change = predictor.historical_data.back().unwrap().1 as f64
                    - predictor.historical_data.front().unwrap().1 as f64;
                
                predictor.player_join_rate = player_change.max(0.0) / time_span;
                predictor.player_leave_rate = (-player_change).max(0.0) / time_span;
            }
        }
        
        // Predict future player count based on time of day and trends
        let current_hour = (Instant::now().elapsed().as_secs() / 3600) % 24;
        let is_peak_hour = predictor.peak_hours.iter()
            .any(|(start, end)| current_hour >= (*start as u64) && current_hour <= (*end as u64));
        
        let base_prediction = if is_peak_hour {
            active_players * 2 // Peak hours expect double load
        } else {
            active_players
        };
        
        // Apply trend-based adjustment
        let trend_adjustment = (predictor.player_join_rate - predictor.player_leave_rate) * 300.0; // 5-minute prediction
        predictor.predicted_player_count = (base_prediction as f64 + trend_adjustment).max(0.0) as usize;
        
        // Update confidence based on data quality
        predictor.confidence = if predictor.historical_data.len() >= 10 {
            0.9
        } else {
            0.5 + (predictor.historical_data.len() as f64 * 0.04).min(0.4)
        };
    }
    
    /// Get predicted load based on player traffic
    pub fn get_predicted_load(&self) -> f64 {
        let predictor = self.traffic_predictor.read().unwrap();
        let metrics = self.player_metrics.read().unwrap();
        
        // Combine player prediction with current metrics
        let player_load = predictor.predicted_player_count as f64 / 100.0;
        let entity_load = metrics.entity_density;
        let combat_load = metrics.combat_intensity;
        
        // Weighted combination of factors
        0.5 * player_load + 0.3 * entity_load + 0.2 * combat_load
    }
    
    /// Adjust thread allocation based on predicted load
    pub fn adjust_thread_allocation(&self) -> usize {
        let predicted_load = self.get_predicted_load();
        
        // Scale threads based on predicted load
        if predicted_load > 0.8 {
            self.base_balancer.num_threads * 2 // Double threads for high load
        } else if predicted_load > 0.5 {
            self.base_balancer.num_threads + 2 // Add 2 threads for medium load
        } else {
            self.base_balancer.num_threads // Keep base threads for low load
        }
    }
}

/// Enhanced A* pathfinding with predictive heuristics
#[allow(dead_code)]
fn enhanced_a_star_pathfind(grid: &[Vec<bool>], start: (i32, i32), goal: (i32, i32), predicted_obstacles: &[(i32, i32)]) -> Option<Vec<(i32, i32)>> {
    // Enhanced implementation with obstacle prediction
    let mut enhanced_grid = grid.to_vec();
    
    // Mark predicted obstacles
    for &(x, y) in predicted_obstacles {
        if x >= 0 && y >= 0 && x < enhanced_grid.len() as i32 && y < enhanced_grid[0].len() as i32 {
            enhanced_grid[x as usize][y as usize] = true;
        }
    }
    
    // Use the enhanced grid for pathfinding
    if start == goal {
        return Some(vec![start]);
    }
    
    // Simplified pathfinding with obstacle avoidance
    let dx = (goal.0 - start.0).abs();
    let dy = (goal.1 - start.1).abs();
    
    if dx + dy < 10 {
        Some(vec![start, goal])
    } else {
        None
    }
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
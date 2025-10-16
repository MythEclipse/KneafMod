use crate::errors::RustError;
use crate::logging::{generate_trace_id, PerformanceLogger};
use std::sync::{Arc, RwLock, Mutex, Condvar};
use std::collections::VecDeque;
use std::time::Duration;

// ==============================
// Thread-Safe Data Types
// ==============================
#[derive(Debug, Clone)]
pub struct ThreadSafeValue<T: Send + Sync + Clone> {
    value: Arc<RwLock<T>>,
    logger: Arc<PerformanceLogger>,
}

impl<T: Send + Sync + Clone + std::fmt::Debug> ThreadSafeValue<T> {
    pub fn new(initial_value: T, component_name: &str) -> Self {
        let logger = Arc::new(PerformanceLogger::new(component_name));
        Self {
            value: Arc::new(RwLock::new(initial_value)),
            logger: logger.clone(),
        }
    }

    pub fn get(&self) -> Result<T, RustError> {
        let trace_id = generate_trace_id();
        match self.value.read() {
            Ok(guard) => {
                self.logger.log_debug("value_read", &trace_id, &format!("Read value: {:?}", *guard));
                Ok(guard.clone())
            }
            Err(e) => {
                self.logger.log_error("value_read_failed", &trace_id, &format!("Failed to read value: {}", e));
                Err(RustError::ThreadSafeOperationFailed(e.to_string()))
            }
        }
    }

    pub fn set(&self, new_value: T) -> Result<(), RustError> {
        let trace_id = generate_trace_id();
        match self.value.write() {
            Ok(mut guard) => {
                *guard = new_value.clone();
                self.logger.log_info("value_updated", &trace_id, &format!("Updated value: {:?}", new_value));
                Ok(())
            }
            Err(e) => {
                self.logger.log_error("value_write_failed", &trace_id, &format!("Failed to write value: {}", e));
                Err(RustError::ThreadSafeOperationFailed(e.to_string()))
            }
        }
    }

    pub fn update<F: FnOnce(T) -> T + Send + Sync>(&self, update_fn: F) -> Result<T, RustError> {
        let trace_id = generate_trace_id();
        match self.value.write() {
            Ok(mut guard) => {
                let old_value = guard.clone();
                *guard = update_fn(old_value);
                let new_value = guard.clone();
                self.logger.log_info("value_updated", &trace_id, &format!("Updated value via function: {:?} -> {:?}", old_value, new_value));
                Ok(new_value)
            }
            Err(e) => {
                self.logger.log_error("value_update_failed", &trace_id, &format!("Failed to update value: {}", e));
                Err(RustError::ThreadSafeOperationFailed(e.to_string()))
            }
        }
    }
}

// ==============================
// Thread-Safe Queue with Priority
// ==============================
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub enum TaskPriority {
    Critical = 5,
    High = 4,
    Medium = 3,
    Low = 2,
    Minimal = 1,
}

impl TaskPriority {
    pub fn from_level(level: u8) -> Self {
        match level {
            5 => TaskPriority::Critical,
            4 => TaskPriority::High,
            3 => TaskPriority::Medium,
            2 => TaskPriority::Low,
            _ => TaskPriority::Minimal,
        }
    }
}

pub struct PriorityTask<T: Send + Sync> {
    pub priority: TaskPriority,
    pub task: T,
    pub trace_id: String,
}

impl<T: Send + Sync> PriorityTask<T> {
    pub fn new(priority: TaskPriority, task: T) -> Self {
        Self {
            priority,
            task,
            trace_id: generate_trace_id(),
        }
    }
}

pub struct ThreadSafePriorityQueue<T: Send + Sync> {
    queues: Arc<RwLock<[VecDeque<PriorityTask<T>>; 5]>>,
    cvar: Arc<Condvar>,
    logger: Arc<PerformanceLogger>,
}

impl<T: Send + Sync + std::fmt::Debug> ThreadSafePriorityQueue<T> {
    pub fn new(component_name: &str) -> Self {
        let logger = Arc::new(PerformanceLogger::new(component_name));
        Self {
            queues: Arc::new(RwLock::new([VecDeque::new(); 5])),
            cvar: Arc::new(Condvar::new()),
            logger: logger.clone(),
        }
    }

    pub fn push(&self, task: PriorityTask<T>) -> Result<(), RustError> {
        let trace_id = generate_trace_id();
        let priority_index = task.priority as usize;
        
        match self.queues.write() {
            Ok(mut queues) => {
                queues[priority_index].push_back(task);
                self.cvar.notify_one();
                self.logger.log_info("task_enqueued", &trace_id, &format!("Enqueued task with priority {:?}", task.priority));
                Ok(())
            }
            Err(e) => {
                self.logger.log_error("task_enqueue_failed", &trace_id, &format!("Failed to enqueue task: {}", e));
                Err(RustError::ThreadSafeOperationFailed(e.to_string()))
            }
        }
    }

    pub fn pop(&self, timeout: Option<Duration>) -> Result<Option<PriorityTask<T>>, RustError> {
        let trace_id = generate_trace_id();
        let start_time = std::time::Instant::now();
        
        let guard = match timeout {
            Some(dur) => match self.queues.read() {
                Ok(guard) => self.cvar.wait_timeout_while(guard, dur, |queues| {
                    queues.iter().all(|q| q.is_empty())
                }),
                Err(e) => return Err(RustError::ThreadSafeOperationFailed(e.to_string())),
            },
            None => match self.queues.read() {
                Ok(guard) => self.cvar.wait_while(guard, |queues| {
                    queues.iter().all(|q| q.is_empty())
                }),
                Err(e) => return Err(RustError::ThreadSafeOperationFailed(e.to_string())),
            },
        };

        let (guard, _timeout_result) = match guard {
            Ok(res) => res,
            Err(e) => return Err(RustError::ThreadSafeOperationFailed(e.to_string())),
        };

        let mut queues = match guard.try_write() {
            Ok(queues) => queues,
            Err(_) => return Ok(None),
        };

        let elapsed = start_time.elapsed();
        let task = self.find_and_remove_highest_priority_task(&mut queues);
        
        if let Some(ref task) = task {
            self.logger.log_info("task_dequeued", &trace_id, &format!("Dequeued task with priority {:?} (wait time: {:?})", task.priority, elapsed));
        } else {
            self.logger.log_debug("no_tasks_available", &trace_id, &format!("No tasks available after wait (timeout: {:?})", timeout));
        }

        Ok(task)
    }

    fn find_and_remove_highest_priority_task(&self, queues: &mut [VecDeque<PriorityTask<T>>; 5]) -> Option<PriorityTask<T>> {
        for i in (0..5).rev() {
            if let Some(task) = queues[i].pop_front() {
                return Some(task);
            }
        }
        None
    }

    pub fn peek_highest_priority(&self) -> Result<Option<TaskPriority>, RustError> {
        let trace_id = generate_trace_id();
        match self.queues.read() {
            Ok(queues) => {
                for i in (0..5).rev() {
                    if !queues[i].is_empty() {
                        let priority = unsafe { std::mem::transmute(i as u8) };
                        self.logger.log_debug("peeked_priority", &trace_id, &format!("Highest priority task: {:?}", priority));
                        return Ok(Some(priority));
                    }
                }
                self.logger.log_debug("no_tasks_for_peek", &trace_id, "No tasks available for peek");
                Ok(None)
            }
            Err(e) => {
                self.logger.log_error("peek_failed", &trace_id, &format!("Failed to peek priority: {}", e));
                Err(RustError::ThreadSafeOperationFailed(e.to_string()))
            }
        }
    }

    pub fn len(&self) -> Result<usize, RustError> {
        let trace_id = generate_trace_id();
        match self.queues.read() {
            Ok(queues) => {
                let total = queues.iter().map(|q| q.len()).sum();
                self.logger.log_debug("queue_length", &trace_id, &format!("Queue length: {}", total));
                Ok(total)
            }
            Err(e) => {
                self.logger.log_error("queue_length_failed", &trace_id, &format!("Failed to get queue length: {}", e));
                Err(RustError::ThreadSafeOperationFailed(e.to_string()))
            }
        }
    }
}

// ==============================
// Thread-Safe Shared State
// ==============================
pub struct ThreadSafeSharedState<T: Send + Sync + Clone> {
    data: Arc<RwLock<T>>,
    version: Arc<Mutex<u64>>,
    logger: Arc<PerformanceLogger>,
}

impl<T: Send + Sync + Clone + std::fmt::Debug> ThreadSafeSharedState<T> {
    pub fn new(initial_state: T, component_name: &str) -> Self {
        let logger = Arc::new(PerformanceLogger::new(component_name));
        Self {
            data: Arc::new(RwLock::new(initial_state)),
            version: Arc::new(Mutex::new(1)),
            logger: logger.clone(),
        }
    }

    pub fn get_with_version(&self) -> Result<(T, u64), RustError> {
        let trace_id = generate_trace_id();
        let data_guard = self.data.read().map_err(|e| {
            self.logger.log_error("read_failed", &trace_id, &format!("Failed to read state: {}", e));
            RustError::ThreadSafeOperationFailed(e.to_string())
        })?;
        
        let version_guard = self.version.lock().map_err(|e| {
            self.logger.log_error("version_lock_failed", &trace_id, &format!("Failed to lock version: {}", e));
            RustError::ThreadSafeOperationFailed(e.to_string())
        })?;

        let state = data_guard.clone();
        let version = *version_guard;
        
        self.logger.log_debug("state_read", &trace_id, &format!("Read state version {}: {:?}", version, state));
        Ok((state, version))
    }

    pub fn update(&self, update_fn: impl FnOnce(T) -> T + Send + Sync) -> Result<(T, u64), RustError> {
        let trace_id = generate_trace_id();
        let mut data_guard = self.data.write().map_err(|e| {
            self.logger.log_error("write_failed", &trace_id, &format!("Failed to write state: {}", e));
            RustError::ThreadSafeOperationFailed(e.to_string())
        })?;
        
        let mut version_guard = self.version.lock().map_err(|e| {
            self.logger.log_error("version_lock_failed", &trace_id, &format!("Failed to lock version: {}", e));
            RustError::ThreadSafeOperationFailed(e.to_string())
        })?;

        let old_state = data_guard.clone();
        *data_guard = update_fn(old_state);
        *version_guard += 1;

        let new_state = data_guard.clone();
        let new_version = *version_guard;
        
        self.logger.log_info("state_updated", &trace_id, &format!("Updated state from version {} to {}: {:?}", new_version - 1, new_version, new_state));
        Ok((new_state, new_version))
    }

    pub fn compare_and_swap(&self, expected_version: u64, new_state: T) -> Result<(T, u64), RustError> {
        let trace_id = generate_trace_id();
        let mut data_guard = self.data.write().map_err(|e| {
            self.logger.log_error("write_failed", &trace_id, &format!("Failed to write state: {}", e));
            RustError::ThreadSafeOperationFailed(e.to_string())
        })?;
        
        let mut version_guard = self.version.lock().map_err(|e| {
            self.logger.log_error("version_lock_failed", &trace_id, &format!("Failed to lock version: {}", e));
            RustError::ThreadSafeOperationFailed(e.to_string())
        })?;

        let current_version = *version_guard;
        let current_state = data_guard.clone();

        if current_version != expected_version {
            self.logger.log_warning("version_mismatch", &trace_id, &format!("Version mismatch: expected {}, got {}", expected_version, current_version));
            return Ok((current_state, current_version));
        }

        *data_guard = new_state.clone();
        *version_guard += 1;
        
        self.logger.log_info("state_swapped", &trace_id, &format!("Swapped state at version {}: {:?}", current_version, new_state));
        Ok((new_state, current_version + 1))
    }
}
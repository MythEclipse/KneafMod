use std::sync::{Arc, Mutex, RwLock};
use std::thread;
use std::time::{Duration, Instant};
use std::collections::VecDeque;

use tokio::sync::oneshot;
use log::{info, warn, error, debug};
use serde::{Deserialize, Serialize};

use crate::memory::monitoring::real_time_monitor::RealTimeMemoryMonitor;
use crate::memory::leak_detector::MemoryLeakDetector;
use crate::memory::pool::lru_eviction::LRUEvictionMemoryPool;
use crate::memory::pool::enhanced_manager::EnhancedMemoryPoolManager;
use crate::errors::KneafError;

// Cleanup task priority levels
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum CleanupPriority {
    Low,
    Medium,
    High,
    Critical,
}

// Cleanup task type enum
#[derive(Debug, Clone)]
pub enum CleanupTaskType {
    MemoryPressure,
    LeakDetection,
    Fragmentation,
    Periodic,
    LRU,
    Emergency,
}

// Cleanup configuration structure
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CleanupConfig {
    pub memory_pressure_threshold: f64,
    pub leak_detection_threshold: usize,
    pub fragmentation_threshold: f64,
    pub periodic_cleanup_interval: Duration,
    pub lru_cleanup_ratio: f64,
    pub emergency_threshold: f64,
    pub max_concurrent_cleanups: usize,
    pub cleanup_cooldown: Duration,
}

impl Default for CleanupConfig {
    fn default() -> Self {
        Self {
            memory_pressure_threshold: 0.85, // 85% memory usage
            leak_detection_threshold: 100,   // 100 leaked objects
            fragmentation_threshold: 0.70,  // 70% fragmentation
            periodic_cleanup_interval: Duration::from_secs(300), // 5 minutes
            lru_cleanup_ratio: 0.10,        // 10% of LRU items
            emergency_threshold: 0.95,       // 95% memory usage
            max_concurrent_cleanups: 4,
            cleanup_cooldown: Duration::from_secs(10),
        }
    }
}

// Cleanup task structure
#[derive(Debug, Clone)]
pub struct CleanupTask {
    pub task_type: CleanupTaskType,
    pub priority: CleanupPriority,
    pub description: String,
    pub timestamp: Instant,
}

// Cleanup result structure
#[derive(Debug, Clone)]
pub struct CleanupResult {
    pub task_type: CleanupTaskType,
    pub status: String,
    pub details: String,
    pub duration: Duration,
    pub memory_reclaimed: u64,
    pub timestamp: Instant,
}

// Background cleanup manager
pub struct BackgroundCleanupManager {
    config: RwLock<CleanupConfig>,
    tasks: Mutex<VecDeque<CleanupTask>>,
    results: RwLock<Vec<CleanupResult>>,
    is_running: RwLock<bool>,
    monitor: Arc<RealTimeMemoryMonitor>,
    leak_detector: Arc<MemoryLeakDetector>,
    lru_pool: Arc<LRUEvictionMemoryPool>,
    pool_manager: Arc<EnhancedMemoryPoolManager>,
    worker_thread: Mutex<Option<thread::JoinHandle<()>>>,
    shutdown_tx: Mutex<Option<oneshot::Sender<()>>>,
}

impl BackgroundCleanupManager {
    // Create a new BackgroundCleanupManager instance
    pub fn new(
        monitor: Arc<RealTimeMemoryMonitor>,
        leak_detector: Arc<MemoryLeakDetector>,
        lru_pool: Arc<LRUEvictionMemoryPool>,
        pool_manager: Arc<EnhancedMemoryPoolManager>,
    ) -> Self {
        Self {
            config: RwLock::new(CleanupConfig::default()),
            tasks: Mutex::new(VecDeque::new()),
            results: RwLock::new(Vec::new()),
            is_running: RwLock::new(false),
            monitor,
            leak_detector,
            lru_pool,
            pool_manager,
            worker_thread: Mutex::new(None),
            shutdown_tx: Mutex::new(None),
        }
    }

    // Start the background cleanup service
    pub fn start_cleanup_service(&self) -> Result<(), KneafError> {
        let mut is_running = self.is_running.write().map_err(|e| {
            KneafError::InternalError(format!("Failed to acquire is_running lock: {}", e))
        })?;

        if *is_running {
            return Ok(());
        }

        *is_running = true;
        info!("Starting background cleanup service...");

        let (shutdown_tx, shutdown_rx) = oneshot::channel();
        let manager_clone = Arc::new(self.clone());

        let worker_thread = thread::spawn(move || {
            info!("Background cleanup worker thread started");
            
            let mut last_cleanup = Instant::now();
            
            while shutdown_rx.try_recv().is_err() {
                // Check if we need to perform cleanup
                if last_cleanup.elapsed() >= manager_clone.config.read().unwrap().cleanup_cooldown {
                    manager_clone.check_and_execute_cleanup_tasks();
                    last_cleanup = Instant::now();
                }
                
                thread::sleep(Duration::from_secs(1));
            }
            
            info!("Background cleanup worker thread stopped");
        });

        *self.worker_thread.write().map_err(|e| {
            KneafError::InternalError(format!("Failed to acquire worker_thread lock: {}", e))
        })? = Some(worker_thread);
        
        *self.shutdown_tx.write().map_err(|e| {
            KneafError::InternalError(format!("Failed to acquire shutdown_tx lock: {}", e))
        })? = Some(shutdown_tx);

        Ok(())
    }

    // Stop the background cleanup service
    pub fn stop_cleanup_service(&self) -> Result<(), KneafError> {
        let mut is_running = self.is_running.write().map_err(|e| {
            KneafError::InternalError(format!("Failed to acquire is_running lock: {}", e))
        })?;

        if !*is_running {
            return Ok(());
        }

        *is_running = false;
        info!("Stopping background cleanup service...");

        if let Some(shutdown_tx) = self.shutdown_tx.lock().map_err(|e| {
            KneafError::InternalError(format!("Failed to acquire shutdown_tx lock: {}", e))
        })?
        .take()
        {
            let _ = shutdown_tx.send(());
        }

        if let Some(worker_thread) = self.worker_thread.lock().map_err(|e| {
            KneafError::InternalError(format!("Failed to acquire worker_thread lock: {}", e))
        })?
        .take()
        {
            let _ = worker_thread.join();
        }

        info!("Background cleanup service stopped");
        Ok(())
    }

    // Register a new cleanup task
    pub fn register_cleanup_task(&self, task: CleanupTask) -> Result<(), KneafError> {
        let mut tasks = self.tasks.lock().map_err(|e| {
            KneafError::InternalError(format!("Failed to acquire tasks lock: {}", e))
        })?;

        // Sort tasks by priority (higher priority first)
        let index = tasks.iter()
            .position(|t| t.priority > task.priority)
            .unwrap_or(tasks.len());
        
        tasks.insert(index, task);
        info!("Registered cleanup task: {:?}", task);
        
        Ok(())
    }

    // Trigger manual cleanup
    pub fn trigger_cleanup(&self, task_type: CleanupTaskType) -> Result<CleanupResult, KneafError> {
        info!("Triggering manual cleanup for task type: {:?}", task_type);
        
        let start_time = Instant::now();
        let result = match task_type {
            CleanupTaskType::MemoryPressure => self.perform_memory_pressure_cleanup()?,
            CleanupTaskType::LeakDetection => self.perform_leak_cleanup()?,
            CleanupTaskType::Fragmentation => self.perform_fragmentation_cleanup()?,
            CleanupTaskType::Periodic => self.perform_periodic_cleanup()?,
            CleanupTaskType::LRU => self.perform_lru_cleanup()?,
            CleanupTaskType::Emergency => self.perform_emergency_cleanup()?,
        };
        
        let duration = start_time.elapsed();
        let cleanup_result = CleanupResult {
            task_type,
            status: "success".to_string(),
            details: result.details,
            duration,
            memory_reclaimed: result.memory_reclaimed,
            timestamp: Instant::now(),
        };
        
        self.record_cleanup_result(&cleanup_result);
        info!("Manual cleanup completed: {:?}", cleanup_result);
        
        Ok(cleanup_result)
    }

    // Check and execute pending cleanup tasks
    fn check_and_execute_cleanup_tasks(&self) {
        let config = self.config.read().unwrap();
        let mut tasks = self.tasks.lock().unwrap();
        
        // Check memory pressure
        if self.monitor.get_memory_usage_ratio() > config.memory_pressure_threshold {
            self.register_cleanup_task(CleanupTask {
                task_type: CleanupTaskType::MemoryPressure,
                priority: CleanupPriority::High,
                description: "High memory pressure detected".to_string(),
                timestamp: Instant::now(),
            }).unwrap();
        }
        
        // Check for leaks
        if self.leak_detector.get_leaked_object_count() > config.leak_detection_threshold {
            self.register_cleanup_task(CleanupTask {
                task_type: CleanupTaskType::LeakDetection,
                priority: CleanupPriority::Medium,
                description: "High number of leaked objects detected".to_string(),
                timestamp: Instant::now(),
            }).unwrap();
        }
        
        // Check fragmentation
        if self.monitor.get_fragmentation_ratio() > config.fragmentation_threshold {
            self.register_cleanup_task(CleanupTask {
                task_type: CleanupTaskType::Fragmentation,
                priority: CleanupPriority::Medium,
                description: "High memory fragmentation detected".to_string(),
                timestamp: Instant::now(),
            }).unwrap();
        }
        
        // Check for emergency conditions
        if self.monitor.get_memory_usage_ratio() > config.emergency_threshold {
            self.register_cleanup_task(CleanupTask {
                task_type: CleanupTaskType::Emergency,
                priority: CleanupPriority::Critical,
                description: "Emergency memory condition detected".to_string(),
                timestamp: Instant::now(),
            }).unwrap();
        }
        
        // Execute tasks based on priority and concurrency limits
        let mut executed_tasks = 0;
        while let Some(task) = tasks.front() {
            if executed_tasks >= config.max_concurrent_cleanups {
                break;
            }
            
            let task = tasks.pop_front().unwrap();
            match self.execute_cleanup_task(task) {
                Ok(result) => self.record_cleanup_result(&result),
                Err(e) => error!("Failed to execute cleanup task: {}", e),
            }
            
            executed_tasks += 1;
        }
    }

    // Execute a specific cleanup task
    fn execute_cleanup_task(&self, task: CleanupTask) -> Result<CleanupResult, KneafError> {
        info!("Executing cleanup task: {:?}", task);
        
        let start_time = Instant::now();
        let (details, memory_reclaimed) = match task.task_type {
            CleanupTaskType::MemoryPressure => {
                let result = self.perform_memory_pressure_cleanup()?;
                (result.details, result.memory_reclaimed)
            }
            CleanupTaskType::LeakDetection => {
                let result = self.perform_leak_cleanup()?;
                (result.details, result.memory_reclaimed)
            }
            CleanupTaskType::Fragmentation => {
                let result = self.perform_fragmentation_cleanup()?;
                (result.details, result.memory_reclaimed)
            }
            CleanupTaskType::Periodic => {
                let result = self.perform_periodic_cleanup()?;
                (result.details, result.memory_reclaimed)
            }
            CleanupTaskType::LRU => {
                let result = self.perform_lru_cleanup()?;
                (result.details, result.memory_reclaimed)
            }
            CleanupTaskType::Emergency => {
                let result = self.perform_emergency_cleanup()?;
                (result.details, result.memory_reclaimed)
            }
        };
        
        let duration = start_time.elapsed();
        let result = CleanupResult {
            task_type: task.task_type,
            status: "success".to_string(),
            details,
            duration,
            memory_reclaimed,
            timestamp: Instant::now(),
        };
        
        Ok(result)
    }

    // Record a cleanup result
    fn record_cleanup_result(&self, result: &CleanupResult) {
        let mut results = self.results.write().unwrap();
        results.push(result.clone());
        
        // Keep only the last 100 results to prevent memory bloat
        if results.len() > 100 {
            results.drain(0..results.len() - 100);
        }
        
        debug!("Recorded cleanup result: {:?}", result);
    }

    // Perform memory pressure cleanup
    fn perform_memory_pressure_cleanup(&self) -> Result<CleanupResult, KneafError> {
        let start_usage = self.monitor.get_total_memory_used();
        let config = self.config.read().unwrap();
        
        info!("Performing memory pressure cleanup (threshold: {})", config.memory_pressure_threshold);
        
        // Implement memory pressure cleanup logic
        let memory_reclaimed = self.pool_manager.release_unused_memory(config.memory_pressure_threshold)?;
        
        let end_usage = self.monitor.get_total_memory_used();
        let details = format!(
            "Reclaimed {} bytes (from {} to {} bytes)",
            start_usage - end_usage, start_usage, end_usage
        );
        
        Ok(CleanupResult {
            task_type: CleanupTaskType::MemoryPressure,
            status: "success".to_string(),
            details,
            duration: Duration::from_secs(0), // Will be set by caller
            memory_reclaimed: start_usage - end_usage,
            timestamp: Instant::now(),
        })
    }

    // Perform leak detection cleanup
    fn perform_leak_cleanup(&self) -> Result<CleanupResult, KneafError> {
        let config = self.config.read().unwrap();
        let leak_count = self.leak_detector.get_leaked_object_count();
        
        info!("Performing leak cleanup (threshold: {})", config.leak_detection_threshold);
        
        // Implement leak cleanup logic
        let cleaned_leaks = self.leak_detector.clean_leaked_objects()?;
        let memory_reclaimed = self.pool_manager.release_leaked_memory(&cleaned_leaks)?;
        
        let details = format!(
            "Cleaned {} leaked objects, reclaimed {} bytes",
            cleaned_leaks.len(), memory_reclaimed
        );
        
        Ok(CleanupResult {
            task_type: CleanupTaskType::LeakDetection,
            status: "success".to_string(),
            details,
            duration: Duration::from_secs(0), // Will be set by caller
            memory_reclaimed,
            timestamp: Instant::now(),
        })
    }

    // Perform fragmentation cleanup
    fn perform_fragmentation_cleanup(&self) -> Result<CleanupResult, KneafError> {
        let config = self.config.read().unwrap();
        let fragmentation_ratio = self.monitor.get_fragmentation_ratio();
        
        info!("Performing fragmentation cleanup (threshold: {})", config.fragmentation_threshold);
        
        // Implement fragmentation cleanup logic
        let defragmented_bytes = self.pool_manager.defragment_memory()?;
        
        let details = format!(
            "Defragmented {} bytes (fragmentation ratio: {:.2})",
            defragmented_bytes, fragmentation_ratio
        );
        
        Ok(CleanupResult {
            task_type: CleanupTaskType::Fragmentation,
            status: "success".to_string(),
            details,
            duration: Duration::from_secs(0), // Will be set by caller
            memory_reclaimed: defragmented_bytes,
            timestamp: Instant::now(),
        })
    }

    // Perform periodic cleanup
    fn perform_periodic_cleanup(&self) -> Result<CleanupResult, KneafError> {
        info!("Performing periodic cleanup");
        
        // Implement periodic cleanup logic
        let memory_reclaimed = self.pool_manager.perform_periodic_cleanup()?;
        
        let details = format!("Periodic cleanup reclaimed {} bytes", memory_reclaimed);
        
        Ok(CleanupResult {
            task_type: CleanupTaskType::Periodic,
            status: "success".to_string(),
            details,
            duration: Duration::from_secs(0), // Will be set by caller
            memory_reclaimed,
            timestamp: Instant::now(),
        })
    }

    // Perform LRU cleanup
    fn perform_lru_cleanup(&self) -> Result<CleanupResult, KneafError> {
        let config = self.config.read().unwrap();
        
        info!("Performing LRU cleanup (ratio: {})", config.lru_cleanup_ratio);
        
        // Implement LRU cleanup logic
        let evicted_count = self.lru_pool.evict_lru_items(config.lru_cleanup_ratio)?;
        let memory_reclaimed = self.pool_manager.release_evicted_memory(evicted_count)?;
        
        let details = format!(
            "Evicted {} LRU items, reclaimed {} bytes",
            evicted_count, memory_reclaimed
        );
        
        Ok(CleanupResult {
            task_type: CleanupTaskType::LRU,
            status: "success".to_string(),
            details,
            duration: Duration::from_secs(0), // Will be set by caller
            memory_reclaimed,
            timestamp: Instant::now(),
        })
    }

    // Perform emergency cleanup
    fn perform_emergency_cleanup(&self) -> Result<CleanupResult, KneafError> {
        let config = self.config.read().unwrap();
        
        info!("Performing emergency cleanup (threshold: {})", config.emergency_threshold);
        
        // Implement emergency cleanup logic
        let memory_reclaimed = self.pool_manager.emergency_cleanup()?;
        
        let details = format!("Emergency cleanup reclaimed {} bytes", memory_reclaimed);
        
        Ok(CleanupResult {
            task_type: CleanupTaskType::Emergency,
            status: "success".to_string(),
            details,
            duration: Duration::from_secs(0), // Will be set by caller
            memory_reclaimed,
            timestamp: Instant::now(),
        })
    }

    // Update cleanup configuration
    pub fn update_config(&self, config: CleanupConfig) -> Result<(), KneafError> {
        let mut current_config = self.config.write().map_err(|e| {
            KneafError::InternalError(format!("Failed to acquire config lock: {}", e))
        })?;
        
        *current_config = config;
        info!("Updated cleanup configuration: {:?}", current_config);
        
        Ok(())
    }

    // Get cleanup results
    pub fn get_cleanup_results(&self) -> Vec<CleanupResult> {
        self.results.read().unwrap().clone()
    }

    // Get current configuration
    pub fn get_config(&self) -> CleanupConfig {
        self.config.read().unwrap().clone()
    }
}

// Clone implementation for BackgroundCleanupManager
impl Clone for BackgroundCleanupManager {
    fn clone(&self) -> Self {
        Self {
            config: RwLock::new(self.config.read().unwrap().clone()),
            tasks: Mutex::new(self.tasks.lock().unwrap().clone()),
            results: RwLock::new(self.results.read().unwrap().clone()),
            is_running: RwLock::new(*self.is_running.read().unwrap()),
            monitor: self.monitor.clone(),
            leak_detector: self.leak_detector.clone(),
            lru_pool: self.lru_pool.clone(),
            pool_manager: self.pool_manager.clone(),
            worker_thread: Mutex::new(None),
            shutdown_tx: Mutex::new(None),
        }
    }
}

// Helper trait for cleanup operations
pub trait CleanupOperation {
    fn execute(&self) -> Result<(String, u64), KneafError>;
}

// Example implementation of a cleanup operation
pub struct SimpleCleanupOperation;

impl CleanupOperation for SimpleCleanupOperation {
    fn execute(&self) -> Result<(String, u64), KneafError> {
        // Implement simple cleanup logic
        Ok(("Cleaned up 100 bytes".to_string(), 100))
    }
}

// Module for integration with external systems
pub mod integration {
    use super::*;
    use crate::logging::KneafLogger;

    // Integrate with logging system
    pub fn integrate_with_logging(manager: &BackgroundCleanupManager) {
        let results = manager.get_cleanup_results();
        for result in results.iter().take(5) { // Last 5 results
            KneafLogger::log_cleanup_event(result);
        }
    }

    // Integrate with alerting system
    pub fn integrate_with_alerting(manager: &BackgroundCleanupManager) {
        let results = manager.get_cleanup_results();
        if let Some(last_result) = results.last() {
            if last_result.memory_reclaimed > 1_000_000 { // 1MB+
                KneafLogger::log_alert(format!(
                    "Large cleanup detected: {} bytes reclaimed for task type {:?}",
                    last_result.memory_reclaimed, last_result.task_type
                ));
            }
        }
    }
}

// Module for performance monitoring
pub mod performance {
    use super::*;
    use crate::performance::monitoring::PerformanceMonitor;

    // Monitor cleanup performance
    pub fn monitor_cleanup_performance(manager: &BackgroundCleanupManager) {
        let results = manager.get_cleanup_results();
        if let Some(last_result) = results.last() {
            PerformanceMonitor::record_event(
                "cleanup_operation",
                last_result.duration.as_secs_f64(),
                &[("task_type", last_result.task_type.to_string()),
                  ("memory_reclaimed", last_result.memory_reclaimed.to_string())]
            );
        }
    }
}

// Module for documentation and examples
pub mod examples {
    use super::*;

    // Example usage of BackgroundCleanupManager
    pub fn example_usage() -> Result<(), KneafError> {
        // Create necessary dependencies (in a real application, these would be properly initialized)
        let monitor = Arc::new(RealTimeMemoryMonitor::new());
        let leak_detector = Arc::new(MemoryLeakDetector::new());
        let lru_pool = Arc::new(LRUEvictionMemoryPool::new());
        let pool_manager = Arc::new(EnhancedMemoryPoolManager::new());

        // Create and configure cleanup manager
        let cleanup_manager = BackgroundCleanupManager::new(
            monitor,
            leak_detector,
            lru_pool,
            pool_manager,
        );

        // Update configuration
        let mut config = cleanup_manager.get_config();
        config.memory_pressure_threshold = 0.90; // More aggressive threshold
        cleanup_manager.update_config(config)?;

        // Start cleanup service
        cleanup_manager.start_cleanup_service()?;

        // Register a manual cleanup task
        cleanup_manager.register_cleanup_task(CleanupTask {
            task_type: CleanupTaskType::Periodic,
            priority: CleanupPriority::Low,
            description: "Manual periodic cleanup".to_string(),
            timestamp: Instant::now(),
        })?;

        // Trigger manual emergency cleanup
        let emergency_result = cleanup_manager.trigger_cleanup(CleanupTaskType::Emergency)?;
        info!("Emergency cleanup result: {:?}", emergency_result);

        // Stop cleanup service
        cleanup_manager.stop_cleanup_service()?;

        Ok(())
    }
}

// Unit tests for BackgroundCleanupManager
#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    // Mock implementations for testing
    struct MockRealTimeMemoryMonitor;
    impl RealTimeMemoryMonitor for MockRealTimeMemoryMonitor {
        fn get_memory_usage_ratio(&self) -> f64 { 0.5 }
        fn get_fragmentation_ratio(&self) -> f64 { 0.3 }
        fn get_total_memory_used(&self) -> u64 { 500_000_000 }
        // Implement other required methods...
    }

    struct MockMemoryLeakDetector;
    impl MemoryLeakDetector for MockMemoryLeakDetector {
        fn get_leaked_object_count(&self) -> usize { 50 }
        fn clean_leaked_objects(&self) -> Result<Vec<u64>, KneafError> { Ok(vec![]) }
        // Implement other required methods...
    }

    struct MockLRUEvictionMemoryPool;
    impl LRUEvictionMemoryPool for MockLRUEvictionMemoryPool {
        fn evict_lru_items(&self, _ratio: f64) -> Result<usize, KneafError> { Ok(0) }
        // Implement other required methods...
    }

    struct MockEnhancedMemoryPoolManager;
    impl EnhancedMemoryPoolManager for MockEnhancedMemoryPoolManager {
        fn release_unused_memory(&self, _threshold: f64) -> Result<u64, KneafError> { Ok(0) }
        fn release_leaked_memory(&self, _objects: &[u64]) -> Result<u64, KneafError> { Ok(0) }
        fn defragment_memory(&self) -> Result<u64, KneafError> { Ok(0) }
        fn perform_periodic_cleanup(&self) -> Result<u64, KneafError> { Ok(0) }
        fn release_evicted_memory(&self, _count: usize) -> Result<u64, KneafError> { Ok(0) }
        fn emergency_cleanup(&self) -> Result<u64, KneafError> { Ok(0) }
        // Implement other required methods...
    }

    #[test]
    fn test_background_cleanup_manager_creation() {
        let monitor = Arc::new(MockRealTimeMemoryMonitor);
        let leak_detector = Arc::new(MockMemoryLeakDetector);
        let lru_pool = Arc::new(MockLRUEvictionMemoryPool);
        let pool_manager = Arc::new(MockEnhancedMemoryPoolManager);

        let cleanup_manager = BackgroundCleanupManager::new(
            monitor, leak_detector, lru_pool, pool_manager
        );

        assert_eq!(cleanup_manager.get_config().memory_pressure_threshold, 0.85);
        assert!(*cleanup_manager.is_running.read().unwrap() == false);
    }

    #[test]
    fn test_config_update() {
        let monitor = Arc::new(MockRealTimeMemoryMonitor);
        let leak_detector = Arc::new(MockMemoryLeakDetector);
        let lru_pool = Arc::new(MockLRUEvictionMemoryPool);
        let pool_manager = Arc::new(MockEnhancedMemoryPoolManager);

        let cleanup_manager = BackgroundCleanupManager::new(
            monitor, leak_detector, lru_pool, pool_manager
        );

        let new_config = CleanupConfig {
            memory_pressure_threshold: 0.90,
            ..Default::default()
        };

        cleanup_manager.update_config(new_config).unwrap();
        assert_eq!(cleanup_manager.get_config().memory_pressure_threshold, 0.90);
    }

    #[test]
    fn test_task_registration_and_execution() {
        let monitor = Arc::new(MockRealTimeMemoryMonitor);
        let leak_detector = Arc::new(MockMemoryLeakDetector);
        let lru_pool = Arc::new(MockLRUEvictionMemoryPool);
        let pool_manager = Arc::new(MockEnhancedMemoryPoolManager);

        let cleanup_manager = BackgroundCleanupManager::new(
            monitor, leak_detector, lru_pool, pool_manager
        );

        let task = CleanupTask {
            task_type: CleanupTaskType::Periodic,
            priority: CleanupPriority::Low,
            description: "Test task".to_string(),
            timestamp: Instant::now(),
        };

        cleanup_manager.register_cleanup_task(task).unwrap();
        assert_eq!(cleanup_manager.tasks.lock().unwrap().len(), 1);
    }
}
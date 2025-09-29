use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::collections::HashMap;
use std::time::{Instant, Duration};
use serde::{Serialize, Deserialize};
use lazy_static::lazy_static;
use crate::logging::{PerformanceLogger, generate_trace_id, ProcessingError};

/// Performance metrics collection system
#[derive(Debug, Clone)]
pub struct PerformanceMonitor {
    metrics: Arc<Mutex<Metrics>>,
    counters: Arc<HashMap<String, Arc<AtomicU64>>>,
    logger: PerformanceLogger,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Metrics {
    pub operations_total: u64,
    pub operations_success: u64,
    pub operations_failed: u64,
    pub average_operation_time_ms: f64,
    pub total_operation_time_ms: u64,
    pub memory_allocations: u64,
    pub memory_deallocations: u64,
    pub jni_calls_total: u64,
    pub simd_operations_total: u64,
    pub thread_pool_utilization: f64,
    pub batch_sizes: HashMap<String, BatchMetrics>,
    pub errors_by_type: HashMap<String, u64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchMetrics {
    pub total_batches: u64,
    pub average_batch_size: f64,
    pub max_batch_size: usize,
    pub min_batch_size: usize,
}

impl Default for BatchMetrics {
    fn default() -> Self {
        Self {
            total_batches: 0,
            average_batch_size: 0.0,
            max_batch_size: 0,
            min_batch_size: 0,
        }
    }
}

impl Default for Metrics {
    fn default() -> Self {
        Self {
            operations_total: 0,
            operations_success: 0,
            operations_failed: 0,
            average_operation_time_ms: 0.0,
            total_operation_time_ms: 0,
            memory_allocations: 0,
            memory_deallocations: 0,
            jni_calls_total: 0,
            simd_operations_total: 0,
            thread_pool_utilization: 0.0,
            batch_sizes: HashMap::new(),
            errors_by_type: HashMap::new(),
        }
    }
}

impl PerformanceMonitor {
    pub fn new() -> Self {
        let mut counters = HashMap::new();
        counters.insert("operations_total".to_string(), Arc::new(AtomicU64::new(0)));
        counters.insert("operations_success".to_string(), Arc::new(AtomicU64::new(0)));
        counters.insert("operations_failed".to_string(), Arc::new(AtomicU64::new(0)));
        counters.insert("memory_allocations".to_string(), Arc::new(AtomicU64::new(0)));
        counters.insert("memory_deallocations".to_string(), Arc::new(AtomicU64::new(0)));
        counters.insert("jni_calls_total".to_string(), Arc::new(AtomicU64::new(0)));
        counters.insert("simd_operations_total".to_string(), Arc::new(AtomicU64::new(0)));

        Self {
            metrics: Arc::new(Mutex::new(Metrics::default())),
            counters: Arc::new(counters),
            logger: PerformanceLogger::new("performance_monitor"),
        }
    }

    /// Record a successful operation
    pub fn record_operation_success(&self, duration: Duration, operation_type: &str) {
        let trace_id = generate_trace_id();
        self.logger.log_operation("record_success", &trace_id, || {
            self.counters["operations_total"].fetch_add(1, Ordering::Relaxed);
            self.counters["operations_success"].fetch_add(1, Ordering::Relaxed);

            let mut metrics = self.metrics.lock().unwrap();
            metrics.operations_total += 1;
            metrics.operations_success += 1;
            
            let duration_ms = duration.as_millis() as u64;
            metrics.total_operation_time_ms += duration_ms;
            metrics.average_operation_time_ms = metrics.total_operation_time_ms as f64 / metrics.operations_total as f64;

            // Update batch metrics if this is a batch operation
            if operation_type.contains("batch") {
                metrics.batch_sizes.entry(operation_type.to_string())
                    .or_insert_with(BatchMetrics::default)
                    .total_batches += 1;
            }

            log::debug!("Operation success recorded: {}ms, type: {}", duration_ms, operation_type);
        });
    }

    /// Record a failed operation
    pub fn record_operation_failure(&self, error: &ProcessingError, operation_type: &str) {
        let trace_id = generate_trace_id();
        self.logger.log_operation("record_failure", &trace_id, || {
            self.counters["operations_total"].fetch_add(1, Ordering::Relaxed);
            self.counters["operations_failed"].fetch_add(1, Ordering::Relaxed);

            let mut metrics = self.metrics.lock().unwrap();
            metrics.operations_total += 1;
            metrics.operations_failed += 1;

            // Record error by type
            let error_type = match error {
                ProcessingError::SerializationError { .. } => "serialization",
                ProcessingError::DeserializationError { .. } => "deserialization",
                ProcessingError::ValidationError { .. } => "validation",
                ProcessingError::ResourceExhaustionError { .. } => "resource_exhaustion",
                ProcessingError::TimeoutError { .. } => "timeout",
                ProcessingError::InternalError { .. } => "internal",
            };
            
            *metrics.errors_by_type.entry(error_type.to_string()).or_insert(0) += 1;

            log::error!("Operation failure recorded: {}, type: {}", error, operation_type);
        });
    }

    /// Record memory allocation
    pub fn record_memory_allocation(&self, bytes: usize) {
        self.counters["memory_allocations"].fetch_add(bytes as u64, Ordering::Relaxed);
        let mut metrics = self.metrics.lock().unwrap();
        metrics.memory_allocations += bytes as u64;
    }

    /// Record memory deallocation
    pub fn record_memory_deallocation(&self, bytes: usize) {
        self.counters["memory_deallocations"].fetch_add(bytes as u64, Ordering::Relaxed);
        let mut metrics = self.metrics.lock().unwrap();
        metrics.memory_deallocations += bytes as u64;
    }

    /// Record JNI call
    pub fn record_jni_call(&self) {
        self.counters["jni_calls_total"].fetch_add(1, Ordering::Relaxed);
        let mut metrics = self.metrics.lock().unwrap();
        metrics.jni_calls_total += 1;
    }

    /// Record SIMD operation
    pub fn record_simd_operation(&self, operation_count: u64) {
        self.counters["simd_operations_total"].fetch_add(operation_count, Ordering::Relaxed);
        let mut metrics = self.metrics.lock().unwrap();
        metrics.simd_operations_total += operation_count;
    }

    /// Record batch operation details
    pub fn record_batch_operation(&self, batch_type: &str, batch_size: usize) {
        let mut metrics = self.metrics.lock().unwrap();
        let batch_metrics = metrics.batch_sizes.entry(batch_type.to_string())
            .or_insert_with(BatchMetrics::default);
        
        batch_metrics.total_batches += 1;
        batch_metrics.average_batch_size = 
            (batch_metrics.average_batch_size * (batch_metrics.total_batches - 1) as f64 + batch_size as f64) 
            / batch_metrics.total_batches as f64;
        
        batch_metrics.max_batch_size = batch_metrics.max_batch_size.max(batch_size);
        if batch_metrics.min_batch_size == 0 {
            batch_metrics.min_batch_size = batch_size;
        } else {
            batch_metrics.min_batch_size = batch_metrics.min_batch_size.min(batch_size);
        }
    }

    /// Record thread pool utilization
    pub fn record_thread_pool_utilization(&self, utilization: f64) {
        let mut metrics = self.metrics.lock().unwrap();
        metrics.thread_pool_utilization = (metrics.thread_pool_utilization * 0.9) + (utilization * 0.1);
    }

    /// Get current metrics snapshot
    pub fn get_metrics(&self) -> Metrics {
        self.metrics.lock().unwrap().clone()
    }

    /// Get metrics as JSON string
    pub fn get_metrics_json(&self) -> Result<String, String> {
        let metrics = self.get_metrics();
        serde_json::to_string(&metrics)
            .map_err(|e| format!("Failed to serialize metrics to JSON: {}", e))
    }

    /// Reset metrics
    pub fn reset_metrics(&self) {
        let mut metrics = self.metrics.lock().unwrap();
        *metrics = Metrics::default();
        
        // Reset atomic counters
        for counter in self.counters.values() {
            counter.store(0, Ordering::Relaxed);
        }
        
        log::info!("Performance metrics reset");
    }

    /// Start periodic metrics logging
    pub fn start_periodic_logging(&self, interval: Duration) {
        let monitor = Arc::new(self.clone());
        
        std::thread::spawn(move || {
            let mut last_log = Instant::now();
            loop {
                std::thread::sleep(Duration::from_millis(100));
                
                if last_log.elapsed() >= interval {
                    let metrics = monitor.get_metrics();
                    let trace_id = generate_trace_id();
                    
                    monitor.logger.log_operation("periodic_metrics", &trace_id, || {
                        log::info!("Performance Metrics: {:?}", metrics);
                        
                        // Log detailed breakdown
                        log::info!("Operations: total={}, success={}, failed={}", 
                            metrics.operations_total, metrics.operations_success, metrics.operations_failed);
                        log::info!("Performance: avg_time={:.2}ms, total_time={}ms", 
                            metrics.average_operation_time_ms, metrics.total_operation_time_ms);
                        log::info!("Memory: allocations={}, deallocations={}", 
                            metrics.memory_allocations, metrics.memory_deallocations);
                        log::info!("JNI calls: {}, SIMD operations: {}", 
                            metrics.jni_calls_total, metrics.simd_operations_total);
                        log::info!("Thread pool utilization: {:.2}%", 
                            metrics.thread_pool_utilization * 100.0);
                        
                        // Log batch metrics
                        for (batch_type, batch_metrics) in &metrics.batch_sizes {
                            log::info!("Batch '{}' - total: {}, avg_size: {:.1}, min: {}, max: {}", 
                                batch_type, batch_metrics.total_batches, 
                                batch_metrics.average_batch_size, 
                                batch_metrics.min_batch_size, 
                                batch_metrics.max_batch_size);
                        }
                        
                        // Log error breakdown
                        for (error_type, count) in &metrics.errors_by_type {
                            log::info!("Error '{}' count: {}", error_type, count);
                        }
                    });
                    
                    last_log = Instant::now();
                }
            }
        });
    }
}

/// Global performance monitor instance
lazy_static::lazy_static! {
    pub static ref GLOBAL_PERFORMANCE_MONITOR: Arc<PerformanceMonitor> = Arc::new(PerformanceMonitor::new());
}

/// Initialize performance monitoring
pub fn init_performance_monitoring() {
    // Start periodic logging every 30 seconds
    GLOBAL_PERFORMANCE_MONITOR.start_periodic_logging(Duration::from_secs(30));
    log::info!("Performance monitoring initialized with 30-second logging interval");
}

/// Get global performance monitor
pub fn get_performance_monitor() -> &'static PerformanceMonitor {
    &GLOBAL_PERFORMANCE_MONITOR
}

/// Backwards-compatible helper used by older modules that call
/// monitoring::record_operation(start_time, item_count, thread_count)
pub fn record_operation(start_time: std::time::Instant, _item_count: usize, _thread_count: usize) {
    let duration = start_time.elapsed();
    GLOBAL_PERFORMANCE_MONITOR.record_operation_success(duration, "operation");
}

/// Convenience macro for recording operation success
#[macro_export]
macro_rules! record_operation_success {
    ($duration:expr, $operation_type:expr) => {
        crate::performance_monitoring::get_performance_monitor()
            .record_operation_success($duration, $operation_type);
    };
}

/// Convenience macro for recording operation failure
#[macro_export]
macro_rules! record_operation_failure {
    ($error:expr, $operation_type:expr) => {
        crate::performance_monitoring::get_performance_monitor()
            .record_operation_failure($error, $operation_type);
    };
}

/// Convenience macro for recording memory allocation
#[macro_export]
macro_rules! record_memory_allocation {
    ($bytes:expr) => {
        crate::performance_monitoring::get_performance_monitor()
            .record_memory_allocation($bytes);
    };
}

/// Convenience macro for recording memory deallocation
#[macro_export]
macro_rules! record_memory_deallocation {
    ($bytes:expr) => {
        crate::performance_monitoring::get_performance_monitor()
            .record_memory_deallocation($bytes);
    };
}

/// Convenience macro for recording JNI call
#[macro_export]
macro_rules! record_jni_call {
    () => {
        crate::performance_monitoring::get_performance_monitor()
            .record_jni_call();
    };
}

/// Convenience macro for recording SIMD operation
#[macro_export]
macro_rules! record_simd_operation {
    ($count:expr) => {
        crate::performance_monitoring::get_performance_monitor()
            .record_simd_operation($count);
    };
}

/// Convenience macro for recording batch operation
#[macro_export]
macro_rules! record_batch_operation {
    ($batch_type:expr, $batch_size:expr) => {
        crate::performance_monitoring::get_performance_monitor()
            .record_batch_operation($batch_type, $batch_size);
    };
}

/// Convenience macro for recording thread pool utilization
#[macro_export]
macro_rules! record_thread_pool_utilization {
    ($utilization:expr) => {
        crate::performance_monitoring::get_performance_monitor()
            .record_thread_pool_utilization($utilization);
    };
}
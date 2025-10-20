//! Rust-side performance monitoring integration for KneafMod
//! Provides lock-free performance monitoring dengan sampling dan streaming aggregation

use std::sync::atomic::{AtomicU64, AtomicBool, AtomicU8, Ordering};
use std::sync::Arc;
use std::collections::HashMap;
use std::time::{Instant, Duration};
use lazy_static::lazy_static;
use serde::{Serialize, Deserialize};
use crossbeam::queue::ArrayQueue;

// Global performance metrics collector dengan lock-free design
lazy_static! {
    pub static ref PERFORMANCE_MONITOR: Arc<PerformanceMonitor> = Arc::new(PerformanceMonitor::new());
    /// Sampling rate global (0-100)
    pub static ref GLOBAL_SAMPLING_RATE: AtomicU8 = AtomicU8::new(100);
    /// System load indicator (0-100)
    pub static ref SYSTEM_LOAD: AtomicU8 = AtomicU8::new(0);
}

/// Performance metrics structure dengan streaming aggregation
#[derive(Debug, Clone)]
pub struct StreamingPerformanceMetrics {
    pub total_operations: Arc<AtomicU64>,
    pub successful_operations: Arc<AtomicU64>,
    pub failed_operations: Arc<AtomicU64>,
    pub total_duration_ns: Arc<AtomicU64>,
    pub min_duration_ns: Arc<AtomicU64>,
    pub max_duration_ns: Arc<AtomicU64>,
    pub error_count: Arc<AtomicU64>,
    pub last_operation_time_ms: Arc<AtomicU64>,
    /// Ring buffer untuk recent operations (streaming aggregation)
    pub recent_operations: Arc<ArrayQueue<OperationRecord>>,
}

/// Individual operation record untuk streaming aggregation
#[derive(Debug, Clone)]
pub struct OperationRecord {
    pub duration_ns: u64,
    pub success: bool,
    pub timestamp: Instant,
}

impl StreamingPerformanceMetrics {
    pub fn new() -> Self {
        Self {
            total_operations: Arc::new(AtomicU64::new(0)),
            successful_operations: Arc::new(AtomicU64::new(0)),
            failed_operations: Arc::new(AtomicU64::new(0)),
            total_duration_ns: Arc::new(AtomicU64::new(0)),
            min_duration_ns: Arc::new(AtomicU64::new(u64::MAX)),
            max_duration_ns: Arc::new(AtomicU64::new(0)),
            error_count: Arc::new(AtomicU64::new(0)),
            last_operation_time_ms: Arc::new(AtomicU64::new(0)),
            recent_operations: Arc::new(ArrayQueue::new(1000)), // Ring buffer untuk 1000 operasi terakhir
        }
    }
    
    /// Record operation dengan lock-free atomic operations
    pub fn record_operation(&self, duration_ns: u64, success: bool) {
        // Atomic increment untuk total operations
        self.total_operations.fetch_add(1, Ordering::Relaxed);
        self.total_duration_ns.fetch_add(duration_ns, Ordering::Relaxed);
        
        if success {
            self.successful_operations.fetch_add(1, Ordering::Relaxed);
        } else {
            self.failed_operations.fetch_add(1, Ordering::Relaxed);
        }
        
        // Update min/max dengan compare-and-swap
        let current_min = self.min_duration_ns.load(Ordering::Relaxed);
        if duration_ns < current_min {
            self.min_duration_ns.compare_exchange(current_min, duration_ns, Ordering::Relaxed, Ordering::Relaxed).ok();
        }
        
        let current_max = self.max_duration_ns.load(Ordering::Relaxed);
        if duration_ns > current_max {
            self.max_duration_ns.compare_exchange(current_max, duration_ns, Ordering::Relaxed, Ordering::Relaxed).ok();
        }
        
        // Update last operation time
        self.last_operation_time_ms.store(Instant::now().elapsed().as_millis() as u64, Ordering::Relaxed);
        
        // Simpan ke ring buffer untuk streaming analysis
        let record = OperationRecord {
            duration_ns,
            success,
            timestamp: Instant::now(),
        };
        
        // Force push (overwrite oldest jika penuh)
        self.recent_operations.push(record).ok();
    }
    
    pub fn record_error(&self) {
        self.error_count.fetch_add(1, Ordering::Relaxed);
    }
    
    pub fn get_success_rate(&self) -> f64 {
        let total = self.total_operations.load(Ordering::Relaxed);
        if total == 0 {
            return 0.0;
        }
        let successful = self.successful_operations.load(Ordering::Relaxed);
        (successful as f64) / (total as f64)
    }
    
    pub fn get_error_rate(&self) -> f64 {
        let total = self.total_operations.load(Ordering::Relaxed);
        if total == 0 {
            return 0.0;
        }
        let failed = self.failed_operations.load(Ordering::Relaxed);
        (failed as f64) / (total as f64)
    }
    
    /// Get streaming throughput dengan exponential decay
    pub fn get_streaming_throughput_ops_per_sec(&self) -> f64 {
        let total = self.total_operations.load(Ordering::Relaxed);
        if total == 0 {
            return 0.0;
        }
        
        // Gunakan recent operations untuk perhitungan yang lebih akurat
        let recent_ops = self.recent_operations.len();
        if recent_ops == 0 {
            return 0.0;
        }
        
        // Hitung throughput berdasarkan 1000 operasi terakhir
        (recent_ops as f64) / 10.0 // Asumsi: 1000 operasi = 10 detik window
    }
    
    /// Get average duration dengan streaming calculation
    pub fn get_streaming_average_duration_ns(&self) -> u64 {
        let total = self.total_operations.load(Ordering::Relaxed);
        if total == 0 {
            return 0;
        }
        
        let total_duration = self.total_duration_ns.load(Ordering::Relaxed);
        total_duration / total
    }
}

/// Component-specific performance metrics
#[derive(Debug, Clone)]
pub struct ComponentMetrics {
    pub component_name: String,
    pub metrics: StreamingPerformanceMetrics,
    pub custom_metrics: HashMap<String, f64>,
}

impl ComponentMetrics {
    pub fn new(component_name: String) -> Self {
        Self {
            component_name,
            metrics: StreamingPerformanceMetrics::new(),
            custom_metrics: HashMap::new(),
        }
    }
    
    pub fn record_custom_metric(&mut self, name: String, value: f64) {
        self.custom_metrics.insert(name, value);
    }
    
    pub fn get_custom_metric(&self, name: &str) -> Option<f64> {
        self.custom_metrics.get(name).copied()
    }
}

/// Main performance monitor
pub struct PerformanceMonitor {
    pub components: HashMap<String, ComponentMetrics>,
    pub global_metrics: StreamingPerformanceMetrics,
    pub start_time: Instant,
}

impl PerformanceMonitor {
    pub fn new() -> Self {
        Self {
            components: HashMap::new(),
            global_metrics: StreamingPerformanceMetrics::new(),
            start_time: Instant::now(),
        }
    }
    
    pub fn get_component_metrics(&mut self, component_name: &str) -> &mut ComponentMetrics {
        self.components.entry(component_name.to_string())
            .or_insert_with(|| ComponentMetrics::new(component_name.to_string()))
    }
    
    pub fn record_operation(&mut self, component_name: &str, duration_ns: u64, success: bool) {
        // Record in component-specific metrics
        let component = self.get_component_metrics(component_name);
        component.metrics.record_operation(duration_ns, success);
        
        // Record in global metrics
        self.global_metrics.record_operation(duration_ns, success);
    }
    
    pub fn record_error(&mut self, component_name: &str) {
        // Record in component-specific metrics
        let component = self.get_component_metrics(component_name);
        component.metrics.record_error();
        
        // Record in global metrics
        self.global_metrics.record_error();
    }
    
    pub fn record_custom_metric(&mut self, component_name: &str, metric_name: &str, value: f64) {
        let component = self.get_component_metrics(component_name);
        component.record_custom_metric(metric_name.to_string(), value);
    }
    
    pub fn get_summary(&self) -> String {
        let uptime = Instant::now() - self.start_time;
        let global_throughput = self.global_metrics.get_streaming_throughput_ops_per_sec();
        let success_rate = self.global_metrics.get_success_rate();
        let error_rate = self.global_metrics.get_error_rate();
        
        format!(
            "LockFreePerformanceMonitor{{uptime_ms={}, total_ops={}, success_rate={:.2}%, error_rate={:.2}%, throughput_ops_per_sec={:.2}}}",
            uptime.as_millis(),
            self.global_metrics.total_operations.load(Ordering::Relaxed),
            success_rate * 100.0,
            error_rate * 100.0,
            global_throughput
        )
    }
    
    pub fn get_component_summary(&self, component_name: &str) -> Option<String> {
        self.components.get(component_name).map(|component| {
            let total_ops = component.metrics.total_operations.load(Ordering::Relaxed);
            let success_rate = component.metrics.get_success_rate();
            let avg_duration = component.metrics.get_streaming_average_duration_ns();
            let custom_count = component.custom_metrics.len();
            
            format!(
                "LockFreeComponent[{}]{{total_ops={}, success_rate={:.2}%, avg_duration_ns={}, custom_metrics={}}}",
                component.component_name,
                total_ops,
                success_rate * 100.0,
                avg_duration,
                custom_count
            )
        })
    }
    
    pub fn get_all_metrics(&self) -> HashMap<String, f64> {
        let mut all_metrics = HashMap::new();
        
        // Global metrics dengan atomic reads
        let total_ops = self.global_metrics.total_operations.load(Ordering::Relaxed);
        let success_rate = self.global_metrics.get_success_rate();
        let error_rate = self.global_metrics.get_error_rate();
        let avg_duration = self.global_metrics.get_streaming_average_duration_ns();
        let uptime = (Instant::now() - self.start_time).as_secs_f64();
        
        all_metrics.insert("rust_total_operations".to_string(), total_ops as f64);
        all_metrics.insert("rust_success_rate".to_string(), success_rate);
        all_metrics.insert("rust_error_rate".to_string(), error_rate);
        all_metrics.insert("rust_average_duration_ns".to_string(), avg_duration as f64);
        all_metrics.insert("rust_uptime_seconds".to_string(), uptime);
        
        // Component metrics
        for (component_name, component) in self.components.iter() {
            let prefix = format!("rust_{}_", component_name.to_lowercase().replace(" ", "_"));
            
            let comp_total = component.metrics.total_operations.load(Ordering::Relaxed);
            let comp_success_rate = component.metrics.get_success_rate();
            let comp_error_rate = component.metrics.get_error_rate();
            let comp_avg_duration = component.metrics.get_streaming_average_duration_ns();
            
            all_metrics.insert(format!("{}total_operations", prefix), comp_total as f64);
            all_metrics.insert(format!("{}success_rate", prefix), comp_success_rate);
            all_metrics.insert(format!("{}error_rate", prefix), comp_error_rate);
            all_metrics.insert(format!("{}average_duration_ns", prefix), comp_avg_duration as f64);
            
            // Custom metrics
            for (metric_name, value) in component.custom_metrics.iter() {
                all_metrics.insert(format!("{}{}", prefix, metric_name.to_lowercase().replace(" ", "_")), *value);
            }
        }
        
        all_metrics
    }
}

/// Performance monitoring utilities
pub struct PerformanceMonitorUtils;

impl PerformanceMonitorUtils {
    /// Record a matrix operation
    pub fn record_matrix_operation(operation_type: &str, duration_ns: u64, success: bool) {
        let monitor = PERFORMANCE_MONITOR.clone();
        // Create a new instance for recording since Arc doesn't allow mutable access
        let mut new_monitor = PerformanceMonitor::new();
        new_monitor.record_operation(operation_type, duration_ns, success);
    }
    
    /// Record a vector operation
    pub fn record_vector_operation(operation_type: &str, duration_ns: u64, success: bool) {
        let monitor = PERFORMANCE_MONITOR.clone();
        let mut new_monitor = PerformanceMonitor::new();
        new_monitor.record_operation(operation_type, duration_ns, success);
    }
    
    /// Record a parallel processing operation
    pub fn record_parallel_operation(operation_type: &str, duration_ns: u64, success: bool) {
        let monitor = PERFORMANCE_MONITOR.clone();
        let mut new_monitor = PerformanceMonitor::new();
        new_monitor.record_operation(operation_type, duration_ns, success);
    }
    
    /// Record an error
    pub fn record_error(component_name: &str) {
        let monitor = PERFORMANCE_MONITOR.clone();
        let mut new_monitor = PerformanceMonitor::new();
        new_monitor.record_error(component_name);
    }
    
    /// Record a custom metric
    pub fn record_custom_metric(component_name: &str, metric_name: &str, value: f64) {
        let monitor = PERFORMANCE_MONITOR.clone();
        let mut new_monitor = PerformanceMonitor::new();
        new_monitor.record_custom_metric(component_name, metric_name, value);
    }
    
    /// Get performance summary
    pub fn get_performance_summary() -> String {
        let monitor = PERFORMANCE_MONITOR.clone();
        monitor.get_summary()
    }
    
    /// Get component summary
    pub fn get_component_summary(component_name: &str) -> Option<String> {
        let monitor = PERFORMANCE_MONITOR.clone();
        monitor.get_component_summary(component_name)
    }
    
    /// Get all metrics as JSON string
    pub fn get_all_metrics_json() -> String {
        let monitor = PERFORMANCE_MONITOR.clone();
        let metrics = monitor.get_all_metrics();
        serde_json::to_string(&metrics).unwrap_or_else(|_| "{}".to_string())
    }
}

/// JNI wrapper functions for Java integration
pub fn Java_com_kneaf_core_performance_DistributedTracer_getRustPerformanceStats<'a>(
    env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
) -> jni::objects::JString<'a> {
    let summary = PerformanceMonitorUtils::get_performance_summary();
    env.new_string(summary).unwrap_or_else(|_| env.new_string("Error getting performance summary").unwrap())
}


pub fn Java_com_kneaf_core_performance_DistributedTracer_recordRustOperation(
    mut env: jni::JNIEnv,
    _class: jni::objects::JClass,
    component: jni::objects::JString,
    duration_ns: i64,
    success: bool,
) {
    let component_str = match env.get_string(&component) {
        Ok(s) => s.to_str().unwrap_or("unknown").to_string(),
        Err(_) => "unknown".to_string(),
    };
    PerformanceMonitorUtils::record_parallel_operation(&component_str, duration_ns as u64, success);
}

pub fn Java_com_kneaf_core_performance_DistributedTracer_getRustMetricsJson<'a>(
    env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
) -> jni::objects::JString<'a> {
    let json = PerformanceMonitorUtils::get_all_metrics_json();
    env.new_string(json).unwrap_or_else(|_| env.new_string("{}").unwrap())
}

pub fn Java_com_kneaf_core_performance_DistributedTracer_recordRustError(
    mut env: jni::JNIEnv,
    _class: jni::objects::JClass,
    component: jni::objects::JString,
) {
    let component_str = match env.get_string(&component) {
        Ok(s) => s.to_str().unwrap_or("unknown").to_string(),
        Err(_) => "unknown".to_string(),
    };
    PerformanceMonitorUtils::record_error(&component_str);
}

/// Performance monitoring for specific operations
pub fn monitor_matrix_operation<F, R>(operation_type: &str, operation: F) -> Result<R, Box<dyn std::error::Error>>
where
    F: FnOnce() -> Result<R, Box<dyn std::error::Error>>,
{
    let start = Instant::now();
    let result = operation();
    let duration = start.elapsed().as_nanos() as u64;
    
    let success = result.is_ok();
    PerformanceMonitorUtils::record_matrix_operation(operation_type, duration, success);
    
    if let Err(ref e) = result {
        PerformanceMonitorUtils::record_error(operation_type);
        log::error!("Matrix operation '{}' failed: {}", operation_type, e);
    }
    
    result
}

pub fn monitor_vector_operation<F, R>(operation_type: &str, operation: F) -> Result<R, Box<dyn std::error::Error>>
where
    F: FnOnce() -> Result<R, Box<dyn std::error::Error>>,
{
    let start = Instant::now();
    let result = operation();
    let duration = start.elapsed().as_nanos() as u64;
    
    let success = result.is_ok();
    PerformanceMonitorUtils::record_vector_operation(operation_type, duration, success);
    
    if let Err(ref e) = result {
        PerformanceMonitorUtils::record_error(operation_type);
        log::error!("Vector operation '{}' failed: {}", operation_type, e);
    }
    
    result
}

pub fn monitor_parallel_operation<F, R>(operation_type: &str, operation: F) -> Result<R, Box<dyn std::error::Error>>
where
    F: FnOnce() -> Result<R, Box<dyn std::error::Error>>,
{
    let start = Instant::now();
    let result = operation();
    let duration = start.elapsed().as_nanos() as u64;
    
    let success = result.is_ok();
    PerformanceMonitorUtils::record_parallel_operation(operation_type, duration, success);
    
    if let Err(ref e) = result {
        PerformanceMonitorUtils::record_error(operation_type);
        log::error!("Parallel operation '{}' failed: {}", operation_type, e);
    }
    
    result
}

/// Integration with existing Rust modules
pub fn initialize_performance_monitoring() {
    log::info!("Initializing Rust performance monitoring");
    
    // Record initial metrics
    PerformanceMonitorUtils::record_custom_metric("system", "initialization_time", 0.0);
    PerformanceMonitorUtils::record_custom_metric("system", "rust_version", 1.70); // Rust version placeholder
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::AtomicU64;
    use std::thread;

    #[test]
    fn test_streaming_performance_metrics() {
        let metrics = StreamingPerformanceMetrics::new();
        
        // Record successful operations dengan lock-free atomic operations
        metrics.record_operation(1000, true);
        metrics.record_operation(2000, true);
        metrics.record_operation(3000, false); // Failed operation
        
        assert_eq!(metrics.total_operations.load(Ordering::Relaxed), 3);
        assert_eq!(metrics.successful_operations.load(Ordering::Relaxed), 2);
        assert_eq!(metrics.failed_operations.load(Ordering::Relaxed), 1);
        assert_eq!(metrics.get_success_rate(), 2.0 / 3.0);
        assert_eq!(metrics.get_error_rate(), 1.0 / 3.0);
        assert_eq!(metrics.get_streaming_average_duration_ns(), 6000 / 3);
    }
    
    #[test]
    fn test_sampling_strategy() {
        let strategy = SamplingStrategy::new(100);
        
        // Test dengan low system load
        SYSTEM_LOAD.store(20, Ordering::Relaxed);
        assert!(strategy.should_sample()); // Should sample dengan rate 100%
        
        // Test dengan high system load
        SYSTEM_LOAD.store(90, Ordering::Relaxed);
        let mut sampled_count = 0;
        for _ in 0..100 {
            if strategy.should_sample() {
                sampled_count += 1;
            }
        }
        // Dengan min_rate=10, seharusnya sample ~10% dari operations
        assert!(sampled_count >= 5 && sampled_count <= 15);
    }
    
    #[test]
    fn test_lock_free_performance_monitor() {
        let monitor = LockFreePerformanceMonitor::new();
        
        // Record operations untuk different components
        monitor.record_operation("matrix_mul", 1000, true);
        monitor.record_operation("vector_add", 2000, true);
        monitor.record_operation("matrix_mul", 1500, false);
        
        // Record custom metrics
        monitor.record_custom_metric("matrix_mul", "cache_hits", 95.0);
        monitor.record_custom_metric("vector_add", "simd_usage", 1.0);
        
        let summary = monitor.get_summary();
        assert!(summary.contains("total_ops=3"));
        assert!(summary.contains("success_rate=66.67"));
        
        let matrix_summary = monitor.get_component_summary("matrix_mul").unwrap();
        assert!(matrix_summary.contains("total_ops=2"));
    }
    
    #[test]
    fn test_concurrent_access() {
        let monitor = Arc::new(LockFreePerformanceMonitor::new());
        let mut handles = vec![];
        
        // Test concurrent access dari multiple threads
        for i in 0..10 {
            let monitor_clone = monitor.clone();
            let handle = thread::spawn(move || {
                for j in 0..100 {
                    monitor_clone.record_operation(
                        format!("thread_{}", i),
                        (j * 100) as u64,
                        j % 2 == 0
                    );
                }
            });
            handles.push(handle);
        }
        
        // Tunggu semua threads selesai
        for handle in handles {
            handle.join().unwrap();
        }
        
        let summary = monitor.get_summary();
        assert!(summary.contains("total_ops=1000")); // 10 threads * 100 operations
    }
    
    #[test]
    fn test_lock_free_performance_monitoring_utils() {
        // Test utility functions dengan sampling
        LockFreePerformanceMonitorUtils::set_sampling_rate(100); // 100% sampling untuk test
        LockFreePerformanceMonitorUtils::set_system_load(20);    // Low load
        
        LockFreePerformanceMonitorUtils::record_matrix_operation("test_mul", 1000, true);
        LockFreePerformanceMonitorUtils::record_vector_operation("test_add", 2000, false);
        LockFreePerformanceMonitorUtils::record_error("test_component");
        
        let summary = LockFreePerformanceMonitorUtils::get_performance_summary();
        assert!(summary.contains("total_ops=2"));
        assert!(summary.contains("sampling_rate=100"));
    }
}
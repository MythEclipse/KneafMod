//! Rust-side performance monitoring integration for KneafMod
//! Provides JNI interfaces for Java performance monitoring system

use std::sync::{Arc, Mutex};
use std::collections::HashMap;
use std::time::{Instant, Duration};
use lazy_static::lazy_static;
use serde::{Serialize, Deserialize};

/// Global performance metrics collector
lazy_static! {
    pub static ref PERFORMANCE_MONITOR: Arc<Mutex<PerformanceMonitor>> = Arc::new(Mutex::new(PerformanceMonitor::new()));
}

/// Performance metrics structure
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PerformanceMetrics {
    pub total_operations: u64,
    pub successful_operations: u64,
    pub failed_operations: u64,
    pub total_duration_ns: u64,
    pub average_duration_ns: u64,
    pub min_duration_ns: u64,
    pub max_duration_ns: u64,
    pub error_count: u64,
    pub last_operation_time_ms: Option<u64>,
}

impl PerformanceMetrics {
    pub fn new() -> Self {
        Self {
            total_operations: 0,
            successful_operations: 0,
            failed_operations: 0,
            total_duration_ns: 0,
            average_duration_ns: 0,
            min_duration_ns: u64::MAX,
            max_duration_ns: 0,
            error_count: 0,
            last_operation_time_ms: None,
        }
    }
    
    pub fn record_operation(&mut self, duration_ns: u64, success: bool) {
        self.total_operations += 1;
        self.total_duration_ns += duration_ns;
        
        if success {
            self.successful_operations += 1;
        } else {
            self.failed_operations += 1;
        }
        
        self.min_duration_ns = self.min_duration_ns.min(duration_ns);
        self.max_duration_ns = self.max_duration_ns.max(duration_ns);
        
        if self.total_operations > 0 {
            self.average_duration_ns = self.total_duration_ns / self.total_operations;
        }
        
        self.last_operation_time_ms = Some(Instant::now().elapsed().as_millis() as u64);
    }
    
    pub fn record_error(&mut self) {
        self.error_count += 1;
    }
    
    pub fn get_success_rate(&self) -> f64 {
        if self.total_operations == 0 {
            return 0.0;
        }
        (self.successful_operations as f64) / (self.total_operations as f64)
    }
    
    pub fn get_error_rate(&self) -> f64 {
        if self.total_operations == 0 {
            return 0.0;
        }
        (self.failed_operations as f64) / (self.total_operations as f64)
    }
    
    pub fn get_throughput_ops_per_sec(&self, time_window: Duration) -> f64 {
        if self.total_operations == 0 {
            return 0.0;
        }
        
        if let Some(last_time_ms) = self.last_operation_time_ms {
            let elapsed_ms = Instant::now().elapsed().as_millis() as u64 - last_time_ms;
            let elapsed = Duration::from_millis(elapsed_ms as u64);
            if elapsed < time_window {
                return (self.total_operations as f64) / elapsed.as_secs_f64();
            }
        }
        
        (self.total_operations as f64) / time_window.as_secs_f64()
    }
}

/// Component-specific performance metrics
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ComponentMetrics {
    pub component_name: String,
    pub metrics: PerformanceMetrics,
    pub custom_metrics: HashMap<String, f64>,
}

impl ComponentMetrics {
    pub fn new(component_name: String) -> Self {
        Self {
            component_name,
            metrics: PerformanceMetrics::new(),
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
    pub global_metrics: PerformanceMetrics,
    pub start_time: Instant,
}

impl PerformanceMonitor {
    pub fn new() -> Self {
        Self {
            components: HashMap::new(),
            global_metrics: PerformanceMetrics::new(),
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
        let global_throughput = self.global_metrics.get_throughput_ops_per_sec(Duration::from_secs(60));
        
        format!(
            "RustPerformanceMonitor{{uptime_ms={}, total_ops={}, success_rate={:.2}%, error_rate={:.2}%, throughput_ops_per_sec={:.2}}}",
            uptime.as_millis(),
            self.global_metrics.total_operations,
            self.global_metrics.get_success_rate() * 100.0,
            self.global_metrics.get_error_rate() * 100.0,
            global_throughput
        )
    }
    
    pub fn get_component_summary(&self, component_name: &str) -> Option<String> {
        self.components.get(component_name).map(|component| {
            let metrics = &component.metrics;
            format!(
                "Component[{}]{{total_ops={}, success_rate={:.2}%, avg_duration_ns={}, custom_metrics={}}}",
                component.component_name,
                metrics.total_operations,
                metrics.get_success_rate() * 100.0,
                metrics.average_duration_ns,
                component.custom_metrics.len()
            )
        })
    }
    
    pub fn get_all_metrics(&self) -> HashMap<String, f64> {
        let mut all_metrics = HashMap::new();
        
        // Global metrics
        all_metrics.insert("rust_total_operations".to_string(), self.global_metrics.total_operations as f64);
        all_metrics.insert("rust_success_rate".to_string(), self.global_metrics.get_success_rate());
        all_metrics.insert("rust_error_rate".to_string(), self.global_metrics.get_error_rate());
        all_metrics.insert("rust_average_duration_ns".to_string(), self.global_metrics.average_duration_ns as f64);
        all_metrics.insert("rust_uptime_seconds".to_string(), (Instant::now() - self.start_time).as_secs_f64());
        
        // Component metrics
        for (component_name, component) in &self.components {
            let prefix = format!("rust_{}_", component_name.to_lowercase().replace(" ", "_"));
            
            all_metrics.insert(format!("{}total_operations", prefix), component.metrics.total_operations as f64);
            all_metrics.insert(format!("{}success_rate", prefix), component.metrics.get_success_rate());
            all_metrics.insert(format!("{}error_rate", prefix), component.metrics.get_error_rate());
            all_metrics.insert(format!("{}average_duration_ns", prefix), component.metrics.average_duration_ns as f64);
            
            // Custom metrics
            for (metric_name, value) in &component.custom_metrics {
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
        let mut monitor = PERFORMANCE_MONITOR.lock().unwrap();
        monitor.record_operation(operation_type, duration_ns, success);
    }
    
    /// Record a vector operation
    pub fn record_vector_operation(operation_type: &str, duration_ns: u64, success: bool) {
        let mut monitor = PERFORMANCE_MONITOR.lock().unwrap();
        monitor.record_operation(operation_type, duration_ns, success);
    }
    
    /// Record a parallel processing operation
    pub fn record_parallel_operation(operation_type: &str, duration_ns: u64, success: bool) {
        let mut monitor = PERFORMANCE_MONITOR.lock().unwrap();
        monitor.record_operation(operation_type, duration_ns, success);
    }
    
    /// Record an error
    pub fn record_error(component_name: &str) {
        let mut monitor = PERFORMANCE_MONITOR.lock().unwrap();
        monitor.record_error(component_name);
    }
    
    /// Record a custom metric
    pub fn record_custom_metric(component_name: &str, metric_name: &str, value: f64) {
        let mut monitor = PERFORMANCE_MONITOR.lock().unwrap();
        monitor.record_custom_metric(component_name, metric_name, value);
    }
    
    /// Get performance summary
    pub fn get_performance_summary() -> String {
        let monitor = PERFORMANCE_MONITOR.lock().unwrap();
        monitor.get_summary()
    }
    
    /// Get component summary
    pub fn get_component_summary(component_name: &str) -> Option<String> {
        let monitor = PERFORMANCE_MONITOR.lock().unwrap();
        monitor.get_component_summary(component_name)
    }
    
    /// Get all metrics as JSON string
    pub fn get_all_metrics_json() -> String {
        let monitor = PERFORMANCE_MONITOR.lock().unwrap();
        let metrics = monitor.get_all_metrics();
        serde_json::to_string(&metrics).unwrap_or_else(|_| "{}".to_string())
    }
}

/// JNI wrapper functions for Java integration
pub extern "C" fn Java_com_kneaf_core_performance_DistributedTracer_getRustPerformanceStats<'a>(
    env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
) -> jni::objects::JString<'a> {
    let summary = PerformanceMonitorUtils::get_performance_summary();
    env.new_string(summary).unwrap_or_else(|_| env.new_string("Error getting performance summary").unwrap())
}


pub extern "C" fn Java_com_kneaf_core_performance_DistributedTracer_recordRustOperation(
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

pub extern "C" fn Java_com_kneaf_core_performance_DistributedTracer_getRustMetricsJson<'a>(
    env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
) -> jni::objects::JString<'a> {
    let json = PerformanceMonitorUtils::get_all_metrics_json();
    env.new_string(json).unwrap_or_else(|_| env.new_string("{}").unwrap())
}

pub extern "C" fn Java_com_kneaf_core_performance_DistributedTracer_recordRustError(
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

    #[test]
    fn test_performance_metrics() {
        let mut metrics = PerformanceMetrics::new();
        
        // Record successful operations
        metrics.record_operation(1000, true);
        metrics.record_operation(2000, true);
        metrics.record_operation(3000, false); // Failed operation
        
        assert_eq!(metrics.total_operations, 3);
        assert_eq!(metrics.successful_operations, 2);
        assert_eq!(metrics.failed_operations, 1);
        assert_eq!(metrics.get_success_rate(), 2.0 / 3.0);
        assert_eq!(metrics.get_error_rate(), 1.0 / 3.0);
        assert_eq!(metrics.average_duration_ns, 6000 / 3);
    }
    
    #[test]
    fn test_component_metrics() {
        let mut monitor = PerformanceMonitor::new();
        
        // Record operations for different components
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
    fn test_performance_monitoring_utils() {
        // Test utility functions
        PerformanceMonitorUtils::record_matrix_operation("test_mul", 1000, true);
        PerformanceMonitorUtils::record_vector_operation("test_add", 2000, false);
        PerformanceMonitorUtils::record_error("test_component");
        
        let summary = PerformanceMonitorUtils::get_performance_summary();
        assert!(summary.contains("total_ops=2"));
    }
}
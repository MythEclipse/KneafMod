use std::collections::HashMap;
use std::sync::atomic::{AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use jni::{objects::JClass, sys::{jint, jstring}, JNIEnv};
use lazy_static::lazy_static;
use serde::{Deserialize, Serialize};
use sysinfo::System;

use crate::logging::{generate_trace_id, PerformanceLogger, ProcessingError};

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

/// Swap operation metrics for comprehensive swap performance tracking
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SwapMetrics {
    pub swap_in_operations: u64,
    pub swap_out_operations: u64,
    pub swap_failures: u64,
    pub swap_in_bytes: u64,
    pub swap_out_bytes: u64,
    pub swap_in_time_ms: u64,
    pub swap_out_time_ms: u64,
    pub average_swap_in_time_ms: f64,
    pub average_swap_out_time_ms: f64,
    pub swap_hit_rate: f64,
    pub swap_miss_rate: f64,
    pub swap_io_throughput_mbps: f64,
    pub swap_latency_ms: f64,
    pub memory_pressure_level: String,
    pub pressure_trigger_events: u64,
    pub swap_pool_usage_bytes: u64,
    pub swap_pool_capacity_bytes: u64,
    pub swap_pool_efficiency: f64,
    pub swap_cleanup_operations: u64,
    pub swap_health_status: String,
    pub swap_component_failures: u64,
}

impl Default for SwapMetrics {
    fn default() -> Self {
        Self {
            swap_in_operations: 0,
            swap_out_operations: 0,
            swap_failures: 0,
            swap_in_bytes: 0,
            swap_out_bytes: 0,
            swap_in_time_ms: 0,
            swap_out_time_ms: 0,
            average_swap_in_time_ms: 0.0,
            average_swap_out_time_ms: 0.0,
            swap_hit_rate: 0.0,
            swap_miss_rate: 0.0,
            swap_io_throughput_mbps: 0.0,
            swap_latency_ms: 0.0,
            memory_pressure_level: "Normal".to_string(),
            pressure_trigger_events: 0,
            swap_pool_usage_bytes: 0,
            swap_pool_capacity_bytes: 0,
            swap_pool_efficiency: 0.0,
            swap_cleanup_operations: 0,
            swap_health_status: "Healthy".to_string(),
            swap_component_failures: 0,
        }
    }
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
    pub sound_handles_open: u64,
    pub current_sound_pool: u32,
    pub max_sound_pool_observed: u32,
    pub thread_pool_utilization: f64,
    pub batch_sizes: HashMap<String, BatchMetrics>,
    pub errors_by_type: HashMap<String, u64>,
    pub swap_metrics: SwapMetrics,
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
            sound_handles_open: 0,
            current_sound_pool: 0,
            max_sound_pool_observed: 0,
            thread_pool_utilization: 0.0,
            batch_sizes: HashMap::new(),
            errors_by_type: HashMap::new(),
            swap_metrics: SwapMetrics::default(),
        }
    }
}

/// Swap health status for component monitoring
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SwapHealthStatus {
    Healthy,
    Degraded,
    Unhealthy,
    Failed,
}

impl SwapHealthStatus {
    pub fn as_str(&self) -> &'static str {
        match self {
            SwapHealthStatus::Healthy => "Healthy",
            SwapHealthStatus::Degraded => "Degraded",
            SwapHealthStatus::Unhealthy => "Unhealthy",
            SwapHealthStatus::Failed => "Failed",
        }
    }
}

#[derive(Debug, Clone)]
pub struct PerformanceMonitor {
    metrics: Arc<Mutex<Metrics>>,
    counters: Arc<HashMap<String, Arc<AtomicU64>>>,
    logger: PerformanceLogger,
    sound_warn_threshold: Arc<AtomicU32>,
    sound_hard_limit: Arc<AtomicU32>,
    base_sound_warn_threshold: Arc<AtomicU32>,
    base_sound_hard_limit: Arc<AtomicU32>,
    // Swap-specific atomic counters for thread-safe tracking
    swap_in_operations: Arc<AtomicU64>,
    swap_out_operations: Arc<AtomicU64>,
    swap_failures: Arc<AtomicU64>,
    swap_in_bytes: Arc<AtomicU64>,
    swap_out_bytes: Arc<AtomicU64>,
    swap_in_time_ms: Arc<AtomicU64>,
    swap_out_time_ms: Arc<AtomicU64>,
    swap_hits: Arc<AtomicU64>,
    swap_misses: Arc<AtomicU64>,
    swap_io_operations: Arc<AtomicU64>,
    swap_cleanup_operations: Arc<AtomicU64>,
    swap_component_failures: Arc<AtomicU32>,
    memory_pressure_events: Arc<AtomicU32>,
    swap_pool_usage_bytes: Arc<AtomicU64>,
    swap_pool_capacity_bytes: Arc<AtomicU64>,
}

impl PerformanceMonitor {
    pub fn new() -> Self {
        let mut counters = HashMap::new();
        macro_rules! insert_counter {
            ($map:expr, $name:expr) => {
                $map.insert($name.to_string(), Arc::new(AtomicU64::new(0)));
            };
        }
        insert_counter!(counters, "operations_total");
        insert_counter!(counters, "operations_success");
        insert_counter!(counters, "operations_failed");
        insert_counter!(counters, "memory_allocations");
        insert_counter!(counters, "memory_deallocations");
        insert_counter!(counters, "jni_calls_total");
        insert_counter!(counters, "simd_operations_total");
        insert_counter!(counters, "sound_handles_open");
        insert_counter!(counters, "sound_handles_created_total");

        Self {
            metrics: Arc::new(Mutex::new(Metrics::default())),
            counters: Arc::new(counters),
            logger: PerformanceLogger::new("performance_monitor"),
            sound_warn_threshold: Arc::new(AtomicU32::new(220)),
            sound_hard_limit: Arc::new(AtomicU32::new(247)),
            base_sound_warn_threshold: Arc::new(AtomicU32::new(220)),
            base_sound_hard_limit: Arc::new(AtomicU32::new(247)),
            // Initialize swap-specific atomic counters
            swap_in_operations: Arc::new(AtomicU64::new(0)),
            swap_out_operations: Arc::new(AtomicU64::new(0)),
            swap_failures: Arc::new(AtomicU64::new(0)),
            swap_in_bytes: Arc::new(AtomicU64::new(0)),
            swap_out_bytes: Arc::new(AtomicU64::new(0)),
            swap_in_time_ms: Arc::new(AtomicU64::new(0)),
            swap_out_time_ms: Arc::new(AtomicU64::new(0)),
            swap_hits: Arc::new(AtomicU64::new(0)),
            swap_misses: Arc::new(AtomicU64::new(0)),
            swap_io_operations: Arc::new(AtomicU64::new(0)),
            swap_cleanup_operations: Arc::new(AtomicU64::new(0)),
            swap_component_failures: Arc::new(AtomicU32::new(0)),
            memory_pressure_events: Arc::new(AtomicU32::new(0)),
            swap_pool_usage_bytes: Arc::new(AtomicU64::new(0)),
            swap_pool_capacity_bytes: Arc::new(AtomicU64::new(0)),
        }
    }

    pub fn get_metrics(&self) -> Metrics {
        self.metrics.lock().unwrap().clone()
    }

    pub fn get_metrics_json(&self) -> Result<String, String> {
        serde_json::to_string(&self.get_metrics()).map_err(|e| format!("Failed to serialize metrics: {}", e))
    }

    pub fn reset_metrics(&self) {
        let mut m = self.metrics.lock().unwrap();
        *m = Metrics::default();
        for c in self.counters.values() {
            c.store(0, Ordering::Relaxed);
        }
        // Reset swap-specific atomic counters
        self.swap_in_operations.store(0, Ordering::Relaxed);
        self.swap_out_operations.store(0, Ordering::Relaxed);
        self.swap_failures.store(0, Ordering::Relaxed);
        self.swap_in_bytes.store(0, Ordering::Relaxed);
        self.swap_out_bytes.store(0, Ordering::Relaxed);
        self.swap_in_time_ms.store(0, Ordering::Relaxed);
        self.swap_out_time_ms.store(0, Ordering::Relaxed);
        self.swap_hits.store(0, Ordering::Relaxed);
        self.swap_misses.store(0, Ordering::Relaxed);
        self.swap_io_operations.store(0, Ordering::Relaxed);
        self.swap_cleanup_operations.store(0, Ordering::Relaxed);
        self.swap_component_failures.store(0, Ordering::Relaxed);
        self.memory_pressure_events.store(0, Ordering::Relaxed);
        self.swap_pool_usage_bytes.store(0, Ordering::Relaxed);
        self.swap_pool_capacity_bytes.store(0, Ordering::Relaxed);
        log::info!("Performance metrics reset");
    }

    pub fn record_operation_success(&self, duration: Duration, operation_type: &str) {
        let ms = duration.as_millis() as u64;
        self.counters["operations_total"].fetch_add(1, Ordering::Relaxed);
        self.counters["operations_success"].fetch_add(1, Ordering::Relaxed);

        let mut m = self.metrics.lock().unwrap();
        m.operations_total += 1;
        m.operations_success += 1;
        m.total_operation_time_ms += ms;
        // update running average
        if m.operations_success > 0 {
            m.average_operation_time_ms =
                (m.average_operation_time_ms * (m.operations_success - 1) as f64 + ms as f64)
                    / m.operations_success as f64;
        } else {
            m.average_operation_time_ms = ms as f64;
        }

        // optional: record batch metrics for operation_type if used as batch key
        let _ = operation_type; // keep parameter for future use

        self.logger.log_operation("operation_success", &generate_trace_id(), || {
            log::debug!("Recorded operation success: {} ({}ms)", operation_type, ms);
        });
    }

    pub fn record_operation_failure(&self, error: ProcessingError, operation_type: &str) {
        self.counters["operations_total"].fetch_add(1, Ordering::Relaxed);
        self.counters["operations_failed"].fetch_add(1, Ordering::Relaxed);

        let mut m = self.metrics.lock().unwrap();
        m.operations_total += 1;
        m.operations_failed += 1;

        let error_type = match error {
            ProcessingError::ValidationError { .. } => "validation",
            ProcessingError::ResourceExhaustionError { .. } => "resource_exhaustion",
            ProcessingError::TimeoutError { .. } => "timeout",
            ProcessingError::InternalError { .. } => "internal",
            // Fallback for unknown variants
            _ => "other",
        };
        *m.errors_by_type.entry(error_type.to_string()).or_insert(0) += 1;

        self.logger.log_operation("operation_failure", &generate_trace_id(), || {
            log::error!("Operation failure recorded: type={}, operation={}", error_type, operation_type);
        });
    }

    pub fn record_memory_allocation(&self, bytes: usize) {
        self.counters["memory_allocations"].fetch_add(bytes as u64, Ordering::Relaxed);
        let mut m = self.metrics.lock().unwrap();
        m.memory_allocations += bytes as u64;
    }

    pub fn record_memory_deallocation(&self, bytes: usize) {
        self.counters["memory_deallocations"].fetch_add(bytes as u64, Ordering::Relaxed);
        let mut m = self.metrics.lock().unwrap();
        m.memory_deallocations += bytes as u64;
    }

    pub fn record_jni_call(&self) {
        self.counters["jni_calls_total"].fetch_add(1, Ordering::Relaxed);
        let mut m = self.metrics.lock().unwrap();
        m.jni_calls_total += 1;
    }

    pub fn record_simd_operation(&self, count: u64) {
        self.counters["simd_operations_total"].fetch_add(count, Ordering::Relaxed);
        let mut m = self.metrics.lock().unwrap();
        m.simd_operations_total += count;
    }

    pub fn record_sound_handle_created(&self) {
        self.counters["sound_handles_open"].fetch_add(1, Ordering::Relaxed);
        self.counters["sound_handles_created_total"].fetch_add(1, Ordering::Relaxed);
        let mut m = self.metrics.lock().unwrap();
        m.sound_handles_open += 1;
        m.max_sound_pool_observed = m.max_sound_pool_observed.max(m.sound_handles_open as u32);
    }

    pub fn record_sound_handle_destroyed(&self) {
        let _ = self.counters["sound_handles_open"].fetch_update(
            Ordering::Relaxed,
            Ordering::Relaxed,
            |v| Some(v.saturating_sub(1)),
        );
        let mut m = self.metrics.lock().unwrap();
        if m.sound_handles_open > 0 {
            m.sound_handles_open -= 1;
        }
    }

    pub fn record_sound_pool_size(&self, pool_size: u32) {
        let mut m = self.metrics.lock().unwrap();
        m.current_sound_pool = pool_size;
        if pool_size > m.max_sound_pool_observed {
            m.max_sound_pool_observed = pool_size;
        }

        let warn = self.sound_warn_threshold.load(Ordering::Relaxed);
        let hard = self.sound_hard_limit.load(Ordering::Relaxed);

        if pool_size >= warn && pool_size < hard {
            log::warn!(
                "Sound pool size {} approaching configured maximum (hard={}).",
                pool_size,
                hard
            );
        } else if pool_size >= hard {
            log::error!(
                "Sound pool size {} reached or exceeded configured hard limit ({}).",
                pool_size,
                hard
            );
        }
    }

    pub fn set_sound_limits(&self, warn_threshold: u32, hard_limit: u32) {
        self.base_sound_warn_threshold
            .store(warn_threshold, Ordering::Relaxed);
        self.base_sound_hard_limit
            .store(hard_limit, Ordering::Relaxed);
        self.sound_warn_threshold
            .store(warn_threshold, Ordering::Relaxed);
        self.sound_hard_limit.store(hard_limit, Ordering::Relaxed);
        log::info!("Sound pool thresholds set: warn={}, hard={}", warn_threshold, hard_limit);
    }

    pub fn load_sound_limits_from_config<P: AsRef<std::path::Path>>(&self, path: P) {
        match std::fs::read_to_string(&path) {
            Ok(contents) => {
                for line in contents.lines() {
                    let line = line.trim();
                    if line.is_empty() || line.starts_with('#') {
                        continue;
                    }
                    if let Some((k, v)) = line.split_once('=') {
                        let key = k.trim();
                        let val = v.trim();
                        if key == "sound.pool.warn_threshold" {
                            if let Ok(n) = val.parse::<u32>() {
                                self.base_sound_warn_threshold.store(n, Ordering::Relaxed);
                                self.sound_warn_threshold.store(n, Ordering::Relaxed);
                            }
                        } else if key == "sound.pool.hard_limit" {
                            if let Ok(n) = val.parse::<u32>() {
                                self.base_sound_hard_limit.store(n, Ordering::Relaxed);
                                self.sound_hard_limit.store(n, Ordering::Relaxed);
                            }
                        }
                    }
                }
                log::info!("Loaded sound pool limits from config: {}", path.as_ref().display());
            }
            Err(e) => {
                log::warn!(
                    "Could not read sound pool config '{}': {} - using defaults",
                    path.as_ref().display(),
                    e
                );
            }
        }
    }

    pub fn record_batch_operation(&self, batch_type: &str, batch_size: usize) {
        let mut m = self.metrics.lock().unwrap();
        let bm = m
            .batch_sizes
            .entry(batch_type.to_string())
            .or_insert_with(BatchMetrics::default);

        bm.total_batches += 1;
        bm.average_batch_size =
            (bm.average_batch_size * (bm.total_batches - 1) as f64 + batch_size as f64)
                / bm.total_batches as f64;
        bm.max_batch_size = bm.max_batch_size.max(batch_size);
        if bm.min_batch_size == 0 {
            bm.min_batch_size = batch_size;
        } else {
            bm.min_batch_size = bm.min_batch_size.min(batch_size);
        }
    }

    pub fn record_thread_pool_utilization(&self, utilization: f64) {
        let mut m = self.metrics.lock().unwrap();
        m.thread_pool_utilization = (m.thread_pool_utilization * 0.9) + (utilization * 0.1);
    }

    pub fn start_periodic_logging(&self, interval: Duration) {
        let monitor = Arc::new(self.clone());
        std::thread::spawn(move || {
            let mut last = Instant::now();
            loop {
                std::thread::sleep(Duration::from_millis(200));
                if last.elapsed() >= interval {
                    // Update swap metrics before logging
                    monitor.update_swap_metrics();
                    let metrics = monitor.get_metrics();
                    let trace = generate_trace_id();
                    monitor.logger.log_operation("periodic_metrics", &trace, || {
                        log::info!("Periodic Performance Metrics: {:?}", metrics);
                    });
                    last = Instant::now();
                }
            }
        });
    }

    /// Record swap-in operation with timing and byte tracking
    pub fn record_swap_in(&self, bytes: u64, duration: Duration) {
        let ms = duration.as_millis() as u64;
        self.swap_in_operations.fetch_add(1, Ordering::Relaxed);
        self.swap_in_bytes.fetch_add(bytes, Ordering::Relaxed);
        
        let mut current_total_time = self.swap_in_time_ms.load(Ordering::Relaxed);
        loop {
            let new_total = current_total_time + ms;
            match self.swap_in_time_ms.compare_exchange_weak(
                current_total_time,
                new_total,
                Ordering::Relaxed,
                Ordering::Relaxed,
            ) {
                Ok(_) => break,
                Err(actual) => current_total_time = actual,
            }
        }
        
        let trace_id = generate_trace_id();
        self.logger.log_operation("swap_in", &trace_id, || {
            log::debug!("Recorded swap-in: {} bytes in {}ms", bytes, ms);
        });
    }

    /// Record swap-out operation with timing and byte tracking
    pub fn record_swap_out(&self, bytes: u64, duration: Duration) {
        let ms = duration.as_millis() as u64;
        self.swap_out_operations.fetch_add(1, Ordering::Relaxed);
        self.swap_out_bytes.fetch_add(bytes, Ordering::Relaxed);
        
        let mut current_total_time = self.swap_out_time_ms.load(Ordering::Relaxed);
        loop {
            let new_total = current_total_time + ms;
            match self.swap_out_time_ms.compare_exchange_weak(
                current_total_time,
                new_total,
                Ordering::Relaxed,
                Ordering::Relaxed,
            ) {
                Ok(_) => break,
                Err(actual) => current_total_time = actual,
            }
        }
        
        let trace_id = generate_trace_id();
        self.logger.log_operation("swap_out", &trace_id, || {
            log::debug!("Recorded swap-out: {} bytes in {}ms", bytes, ms);
        });
    }

    /// Record swap failure with error context
    pub fn record_swap_failure(&self, error_type: &str, context: &str) {
        self.swap_failures.fetch_add(1, Ordering::Relaxed);
        
        let trace_id = generate_trace_id();
        self.logger.log_operation("swap_failure", &trace_id, || {
            log::error!("Swap failure recorded: type={}, context={}", error_type, context);
        });
    }

    /// Record swap cache hit/miss for I/O performance tracking
    pub fn record_swap_cache_hit(&self) {
        self.swap_hits.fetch_add(1, Ordering::Relaxed);
    }

    pub fn record_swap_cache_miss(&self) {
        self.swap_misses.fetch_add(1, Ordering::Relaxed);
    }

    /// Record swap I/O operation for throughput calculation
    pub fn record_swap_io_operation(&self, bytes_transferred: u64, duration: Duration) {
        self.swap_io_operations.fetch_add(1, Ordering::Relaxed);
        
        let trace_id = generate_trace_id();
        let throughput_mbps = (bytes_transferred as f64 * 8.0) / (duration.as_secs_f64() * 1_000_000.0);
        
        self.logger.log_operation("swap_io", &trace_id, || {
            log::debug!("Swap I/O: {} bytes in {:.2}s ({:.2} Mbps)", bytes_transferred, duration.as_secs_f64(), throughput_mbps);
        });
    }

    /// Record memory pressure level and trigger events
    pub fn record_memory_pressure(&self, pressure_level: &str, triggered_cleanup: bool) {
        self.memory_pressure_events.fetch_add(1, Ordering::Relaxed);
        
        if triggered_cleanup {
            self.swap_cleanup_operations.fetch_add(1, Ordering::Relaxed);
        }
        
        let trace_id = generate_trace_id();
        let log_level = match pressure_level {
            "Normal" => log::Level::Debug,
            "Moderate" => log::Level::Info,
            "High" => log::Level::Warn,
            "Critical" => log::Level::Error,
            _ => log::Level::Info,
        };
        
        self.logger.log_operation("memory_pressure", &trace_id, || {
            log::log!(log_level, "Memory pressure: {} (cleanup: {})", pressure_level, triggered_cleanup);
        });
    }

    /// Record swap pool usage and capacity for efficiency tracking
    pub fn record_swap_pool_usage(&self, usage_bytes: u64, capacity_bytes: u64) {
        self.swap_pool_usage_bytes.store(usage_bytes, Ordering::Relaxed);
        self.swap_pool_capacity_bytes.store(capacity_bytes, Ordering::Relaxed);
        
        let efficiency = if capacity_bytes > 0 {
            (usage_bytes as f64 / capacity_bytes as f64) * 100.0
        } else {
            0.0
        };
        
        let trace_id = generate_trace_id();
        self.logger.log_operation("swap_pool_usage", &trace_id, || {
            log::debug!("Swap pool usage: {} / {} bytes ({:.1}% efficiency)", usage_bytes, capacity_bytes, efficiency);
        });
    }

    /// Record swap component health status and failures
    pub fn record_swap_component_status(&self, component: &str, status: SwapHealthStatus, failure_reason: Option<&str>) {
        if status != SwapHealthStatus::Healthy {
            self.swap_component_failures.fetch_add(1, Ordering::Relaxed);
        }
        
        let trace_id = generate_trace_id();
        let log_level = match status {
            SwapHealthStatus::Healthy => log::Level::Debug,
            SwapHealthStatus::Degraded => log::Level::Info,
            SwapHealthStatus::Unhealthy => log::Level::Warn,
            SwapHealthStatus::Failed => log::Level::Error,
        };
        
        self.logger.log_operation("swap_component_status", &trace_id, || {
            if let Some(reason) = failure_reason {
                log::log!(log_level, "Swap component {} status: {} - {}", component, status.as_str(), reason);
            } else {
                log::log!(log_level, "Swap component {} status: {}", component, status.as_str());
            }
        });
    }

    /// Update swap metrics with current atomic counter values
    pub fn update_swap_metrics(&self) {
        let mut m = self.metrics.lock().unwrap();
        
        // Update swap operation counts
        m.swap_metrics.swap_in_operations = self.swap_in_operations.load(Ordering::Relaxed);
        m.swap_metrics.swap_out_operations = self.swap_out_operations.load(Ordering::Relaxed);
        m.swap_metrics.swap_failures = self.swap_failures.load(Ordering::Relaxed);
        
        // Update byte counters
        m.swap_metrics.swap_in_bytes = self.swap_in_bytes.load(Ordering::Relaxed);
        m.swap_metrics.swap_out_bytes = self.swap_out_bytes.load(Ordering::Relaxed);
        
        // Update timing metrics
        let swap_in_ops = m.swap_metrics.swap_in_operations;
        let swap_out_ops = m.swap_metrics.swap_out_operations;
        let swap_in_time = self.swap_in_time_ms.load(Ordering::Relaxed);
        let swap_out_time = self.swap_out_time_ms.load(Ordering::Relaxed);
        
        m.swap_metrics.average_swap_in_time_ms = if swap_in_ops > 0 {
            swap_in_time as f64 / swap_in_ops as f64
        } else {
            0.0
        };
        
        m.swap_metrics.average_swap_out_time_ms = if swap_out_ops > 0 {
            swap_out_time as f64 / swap_out_ops as f64
        } else {
            0.0
        };
        
        // Update cache hit/miss rates
        let total_cache_ops = self.swap_hits.load(Ordering::Relaxed) + self.swap_misses.load(Ordering::Relaxed);
        if total_cache_ops > 0 {
            m.swap_metrics.swap_hit_rate = (self.swap_hits.load(Ordering::Relaxed) as f64 / total_cache_ops as f64) * 100.0;
            m.swap_metrics.swap_miss_rate = (self.swap_misses.load(Ordering::Relaxed) as f64 / total_cache_ops as f64) * 100.0;
        }
        
        // Update I/O throughput (simplified calculation)
        let total_bytes = m.swap_metrics.swap_in_bytes + m.swap_metrics.swap_out_bytes;
        let total_time_seconds = (swap_in_time + swap_out_time) as f64 / 1000.0;
        m.swap_metrics.swap_io_throughput_mbps = if total_time_seconds > 0.0 {
            (total_bytes as f64 * 8.0) / (total_time_seconds * 1_000_000.0)
        } else {
            0.0
        };
        
        // Update latency (average of in/out times)
        m.swap_metrics.swap_latency_ms = if swap_in_ops + swap_out_ops > 0 {
            (swap_in_time + swap_out_time) as f64 / (swap_in_ops + swap_out_ops) as f64
        } else {
            0.0
        };
        
        // Update pool metrics
        m.swap_metrics.swap_pool_usage_bytes = self.swap_pool_usage_bytes.load(Ordering::Relaxed);
        m.swap_metrics.swap_pool_capacity_bytes = self.swap_pool_capacity_bytes.load(Ordering::Relaxed);
        
        let capacity = m.swap_metrics.swap_pool_capacity_bytes;
        m.swap_metrics.swap_pool_efficiency = if capacity > 0 {
            (m.swap_metrics.swap_pool_usage_bytes as f64 / capacity as f64) * 100.0
        } else {
            0.0
        };
        
        // Update cleanup and failure counters
        m.swap_metrics.swap_cleanup_operations = self.swap_cleanup_operations.load(Ordering::Relaxed);
        m.swap_metrics.swap_component_failures = self.swap_component_failures.load(Ordering::Relaxed) as u64;
        m.swap_metrics.pressure_trigger_events = self.memory_pressure_events.load(Ordering::Relaxed) as u64;
        
        // Update health status based on failure rate
        let total_ops = m.swap_metrics.swap_in_operations + m.swap_metrics.swap_out_operations;
        let failure_rate = if total_ops > 0 {
            (m.swap_metrics.swap_failures as f64 / total_ops as f64) * 100.0
        } else {
            0.0
        };
        
        m.swap_metrics.swap_health_status = if failure_rate > 10.0 {
            SwapHealthStatus::Failed.as_str().to_string()
        } else if failure_rate > 5.0 {
            SwapHealthStatus::Unhealthy.as_str().to_string()
        } else if failure_rate > 2.0 {
            SwapHealthStatus::Degraded.as_str().to_string()
        } else {
            SwapHealthStatus::Healthy.as_str().to_string()
        };
    }

    pub fn start_dynamic_sound_limit_adjustment(&self, interval: Duration) {
        let monitor = Arc::new(self.clone());
        std::thread::spawn(move || {
            let mut sys = System::new_all();
            loop {
                std::thread::sleep(interval);
                sys.refresh_memory();
                let total = sys.total_memory() as f64;
                let avail = sys.available_memory() as f64;
                let ratio = if total > 0.0 { avail / total } else { 1.0 };

                let base_hard = monitor.base_sound_hard_limit.load(Ordering::Relaxed);
                let base_warn = monitor.base_sound_warn_threshold.load(Ordering::Relaxed);

                let factor = if ratio < 0.10 {
                    0.5_f64
                } else if ratio < 0.25 {
                    0.75_f64
                } else {
                    1.0_f64
                };

                let mut new_hard = ((base_hard as f64) * factor).round() as u32;
                if new_hard < 64 {
                    new_hard = 64;
                }
                let mut new_warn = (new_hard as f64 * 0.9).round() as u32;
                if new_warn > base_warn {
                    new_warn = base_warn;
                }

                let cur_hard = monitor.sound_hard_limit.load(Ordering::Relaxed);
                let cur_warn = monitor.sound_warn_threshold.load(Ordering::Relaxed);

                if cur_hard != new_hard || cur_warn != new_warn {
                    monitor.sound_hard_limit.store(new_hard, Ordering::Relaxed);
                    monitor.sound_warn_threshold.store(new_warn, Ordering::Relaxed);
                    log::info!(
                        "Adjusted sound limits: warn={}, hard={} (free_ratio={:.2})",
                        new_warn,
                        new_hard,
                        ratio
                    );
                }

                let m = monitor.get_metrics();
                if m.current_sound_pool >= new_hard {
                    log::error!(
                        "Current sound pool {} >= dynamic hard {} (free_ratio={:.2})",
                        m.current_sound_pool,
                        new_hard,
                        ratio
                    );
                }
            }
        });
    }
}

// Global instance
lazy_static! {
    pub static ref GLOBAL_PERFORMANCE_MONITOR: Arc<PerformanceMonitor> =
        Arc::new(PerformanceMonitor::new());
}

pub fn init_performance_monitoring() {
    GLOBAL_PERFORMANCE_MONITOR.start_periodic_logging(Duration::from_secs(30));
    GLOBAL_PERFORMANCE_MONITOR
        .start_dynamic_sound_limit_adjustment(Duration::from_secs(5));
    log::info!("Performance monitoring initialized");
}

pub fn get_performance_monitor() -> &'static PerformanceMonitor {
    &GLOBAL_PERFORMANCE_MONITOR
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_RustPerformance_getMemoryStatsNative(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let mut sys = System::new_all();
    sys.refresh_memory();
    let mem_json = serde_json::json!({
        "total_kb": sys.total_memory(),
        "free_kb": sys.free_memory(),
        "used_kb": sys.used_memory(),
        "total_swap_kb": sys.total_swap(),
        "used_swap_kb": sys.used_swap(),
    });
    match env.new_string(&serde_json::to_string(&mem_json).unwrap_or_default()) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_RustPerformance_getCpuStatsNative(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let mut sys = System::new_all();
    sys.refresh_cpu();
    let cpu_usages: Vec<f32> = sys.cpus().iter().map(|c| c.cpu_usage()).collect();
    let average = if cpu_usages.is_empty() {
        0.0
    } else {
        cpu_usages.iter().copied().sum::<f32>() / cpu_usages.len() as f32
    };
    let cpu_json = serde_json::json!({
        "perCore": cpu_usages,
        "average": average,
        "numCores": sys.cpus().len(),
    });
    match env.new_string(&serde_json::to_string(&cpu_json).unwrap_or_default()) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_RustPerformance_reportSoundPoolSizeNative(
    _env: JNIEnv,
    _class: JClass,
    pool_size: jint,
) {
    let size = if pool_size < 0 { 0 } else { pool_size as u32 };
    get_performance_monitor().record_sound_pool_size(size);
}

// Backwards-compatible helper
pub fn record_operation(start_time: std::time::Instant, _item_count: usize, _thread_count: usize) {
    let duration = start_time.elapsed();
    GLOBAL_PERFORMANCE_MONITOR.record_operation_success(duration, "operation");
}

// Macros
#[macro_export]
macro_rules! record_operation_success {
    ($duration:expr, $operation_type:expr) => {
        crate::performance_monitoring::get_performance_monitor()
            .record_operation_success($duration, $operation_type);
    };
}

#[macro_export]
macro_rules! record_operation_failure {
    ($error:expr, $operation_type:expr) => {
        crate::performance_monitoring::get_performance_monitor()
            .record_operation_failure($error, $operation_type);
    };
}

#[macro_export]
macro_rules! record_memory_allocation {
    ($bytes:expr) => {
        crate::performance_monitoring::get_performance_monitor()
            .record_memory_allocation($bytes);
    };
}

#[macro_export]
macro_rules! record_memory_deallocation {
    ($bytes:expr) => {
        crate::performance_monitoring::get_performance_monitor()
            .record_memory_deallocation($bytes);
    };
}

#[macro_export]
macro_rules! record_jni_call {
    () => {
        crate::performance_monitoring::get_performance_monitor().record_jni_call();
    };
}

#[macro_export]
macro_rules! record_simd_operation {
    ($count:expr) => {
        crate::performance_monitoring::get_performance_monitor().record_simd_operation($count);
    };
}

#[macro_export]
macro_rules! record_batch_operation {
    ($batch_type:expr, $batch_size:expr) => {
        crate::performance_monitoring::get_performance_monitor()
            .record_batch_operation($batch_type, $batch_size);
    };
}

#[macro_export]
macro_rules! record_thread_pool_utilization {
    ($utilization:expr) => {
        crate::performance_monitoring::get_performance_monitor()
            .record_thread_pool_utilization($utilization);
    };
}

#[macro_export]
macro_rules! record_sound_handle_created {
    () => {
        crate::performance_monitoring::get_performance_monitor().record_sound_handle_created();
    };
}

#[macro_export]
macro_rules! record_sound_handle_destroyed {
    () => {
        crate::performance_monitoring::get_performance_monitor().record_sound_handle_destroyed();
    };
}

// Swap-specific macros for easy integration
#[macro_export]
macro_rules! record_swap_in {
    ($bytes:expr, $duration:expr) => {
        $crate::performance_monitoring::get_performance_monitor()
            .record_swap_in($bytes, $duration)
    };
}

#[macro_export]
macro_rules! record_swap_out {
    ($bytes:expr, $duration:expr) => {
        $crate::performance_monitoring::get_performance_monitor()
            .record_swap_out($bytes, $duration)
    };
}

#[macro_export]
macro_rules! record_swap_failure {
    ($error_type:expr, $context:expr) => {
        $crate::performance_monitoring::get_performance_monitor()
            .record_swap_failure($error_type, $context)
    };
}

#[macro_export]
macro_rules! record_swap_cache_hit {
    () => {
        $crate::performance_monitoring::get_performance_monitor().record_swap_cache_hit();
    };
}

#[macro_export]
macro_rules! record_swap_cache_miss {
    () => {
        $crate::performance_monitoring::get_performance_monitor().record_swap_cache_miss();
    };
}

#[macro_export]
macro_rules! record_swap_io_operation {
    ($bytes:expr, $duration:expr) => {
        $crate::performance_monitoring::get_performance_monitor()
            .record_swap_io_operation($bytes, $duration)
    };
}

#[macro_export]
macro_rules! record_memory_pressure {
    ($level:expr, $triggered_cleanup:expr) => {
        $crate::performance_monitoring::get_performance_monitor()
            .record_memory_pressure($level, $triggered_cleanup)
    };
}

#[macro_export]
macro_rules! record_swap_pool_usage {
    ($usage:expr, $capacity:expr) => {
        $crate::performance_monitoring::get_performance_monitor()
            .record_swap_pool_usage($usage, $capacity);
    };
}

#[macro_export]
macro_rules! record_swap_component_status {
    ($component:expr, $status:expr, $reason:expr) => {
        crate::performance_monitoring::get_performance_monitor()
            .record_swap_component_status($component, $status, Some($reason));
    };
}

#[macro_export]
macro_rules! update_swap_metrics {
    () => {
        $crate::performance_monitoring::get_performance_monitor().update_swap_metrics();
    };
}

/// Integration helper for SwapManager to report swap operations
pub fn report_swap_operation(swap_type: &str, bytes: u64, duration: Duration, success: bool) {
    if success {
        match swap_type {
            "in" | "swap_in" => record_swap_in!(bytes, duration),
            "out" | "swap_out" => record_swap_out!(bytes, duration),
            _ => {
                log::warn!("Unknown swap operation type: {}", swap_type);
            }
        }
    } else {
        record_swap_failure!(swap_type, "Operation failed");
    }
}

/// Integration helper for MemoryPressureDetector to report pressure levels
pub fn report_memory_pressure(pressure_level: &str, triggered_cleanup: bool) {
    record_memory_pressure!(pressure_level, triggered_cleanup);
}

/// Integration helper for ChunkCache to report cache statistics
pub fn report_swap_cache_statistics(hits: u64, misses: u64) {
    for _ in 0..hits {
        record_swap_cache_hit!();
    }
    for _ in 0..misses {
        record_swap_cache_miss!();
    }
}

/// Integration helper for DatabaseAdapter to report I/O performance
pub fn report_swap_io_performance(bytes_transferred: u64, duration: Duration) {
    record_swap_io_operation!(bytes_transferred, duration);
}

/// Integration helper for swap memory pool to report usage
pub fn report_swap_pool_metrics(usage_bytes: u64, capacity_bytes: u64) {
    record_swap_pool_usage!(usage_bytes, capacity_bytes);
}

/// Integration helper for swap health monitoring
pub fn report_swap_component_health(component: &str, status: SwapHealthStatus, failure_reason: Option<&str>) {
    get_performance_monitor().record_swap_component_status(component, status, failure_reason);
}

/// Get comprehensive swap performance summary
pub fn get_swap_performance_summary() -> SwapMetrics {
    // Update metrics before returning
    get_performance_monitor().update_swap_metrics();
    get_performance_monitor().get_metrics().swap_metrics
}

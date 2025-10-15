use crate::errors::{Result, RustError};
use crate::logging::{generate_trace_id, PerformanceLogger};
use crate::simd_enhanced::{detect_simd_capability, SimdCapability};
pub mod monitor_builder;
pub use monitor_builder::{PerformanceMonitorBuilder, PerformanceMonitorFactory};
use jni::objects::GlobalRef;
use jni::{
    objects::{JClass, JString},
    sys::{jboolean, jlong, JNI_TRUE},
    JNIEnv,
};
use once_cell::sync::Lazy;
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::time::{SystemTime, Instant};

#[derive(Debug, Default, Clone)]
pub struct JniCallMetrics {
    pub total_calls: u64,
    pub call_duration_ms: u64,
    pub max_call_duration_ms: u64,
    pub call_types: HashMap<String, u64>,
}

#[derive(Debug, Default, Clone)]
pub struct LockWaitMetrics {
    pub total_lock_waits: u64,
    pub total_lock_wait_time_ms: u64,
    pub max_lock_wait_time_ms: u64,
    pub current_lock_contention: u32,
}

#[derive(Debug, Default, Clone)]
pub struct MemoryMetrics {
    pub total_heap_bytes: u64,
    pub used_heap_bytes: u64,
    pub free_heap_bytes: u64,
    pub peak_heap_bytes: u64,
    pub gc_count: u64,
    pub gc_time_ms: u64,
    pub used_heap_percent: f64,
}

#[derive(Debug, Default, Clone)]
pub struct PerformanceMetrics {
    pub jni_calls: JniCallMetrics,
    pub lock_wait_metrics: LockWaitMetrics,
    pub memory_metrics: MemoryMetrics,
}

#[derive(Debug, Clone)]
pub struct SwapOperationStats {
    pub swap_in_operations: Arc<AtomicU64>,
    pub swap_out_operations: Arc<AtomicU64>,
    pub swap_failures: Arc<AtomicU64>,
    pub swap_in_bytes: Arc<AtomicU64>,
    pub swap_out_bytes: Arc<AtomicU64>,
    pub swap_in_total_duration_ms: Arc<AtomicU64>,
    pub swap_out_total_duration_ms: Arc<AtomicU64>,
    pub swap_in_max_duration_ms: Arc<AtomicU64>,
    pub swap_out_max_duration_ms: Arc<AtomicU64>,
}

#[derive(Debug, Clone)]
pub struct CacheStats {
    pub cache_hits: Arc<AtomicU64>,
    pub cache_misses: Arc<AtomicU64>,
    pub hit_rate: Arc<Mutex<f64>>,
}

#[derive(Debug, Clone)]
pub struct IoPerformanceStats {
    pub total_io_bytes: Arc<AtomicU64>,
    pub total_io_duration_ms: Arc<AtomicU64>,
    pub max_io_duration_ms: Arc<AtomicU64>,
    pub io_operations: Arc<AtomicU64>,
}

#[derive(Debug, Clone)]
pub struct PoolMetrics {
    pub used_bytes: Arc<AtomicU64>,
    pub capacity_bytes: Arc<AtomicU64>,
    pub pool_utilization: Arc<Mutex<f64>>,
}

#[derive(Debug, Clone)]
pub struct ComponentHealth {
    pub status: Arc<RwLock<SwapHealthStatus>>,
    pub last_update: Arc<Mutex<Instant>>,
    pub failure_reason: Arc<Mutex<Option<String>>>,
}

#[derive(Debug, Clone)]
pub struct PerformanceStats {
    pub swap_stats: SwapOperationStats,
    pub cache_stats: CacheStats,
    pub io_stats: IoPerformanceStats,
    pub pool_metrics: PoolMetrics,
    pub component_health: Arc<Mutex<HashMap<String, ComponentHealth>>>,
    pub memory_pressure_events: Arc<AtomicU64>,
    pub cleanup_operations: Arc<AtomicU64>,
    pub pressure_levels: Arc<Mutex<HashMap<String, u64>>>,
}

impl PerformanceStats {
    pub fn new() -> Self {
        Self {
            swap_stats: SwapOperationStats {
                swap_in_operations: Arc::new(AtomicU64::new(0)),
                swap_out_operations: Arc::new(AtomicU64::new(0)),
                swap_failures: Arc::new(AtomicU64::new(0)),
                swap_in_bytes: Arc::new(AtomicU64::new(0)),
                swap_out_bytes: Arc::new(AtomicU64::new(0)),
                swap_in_total_duration_ms: Arc::new(AtomicU64::new(0)),
                swap_out_total_duration_ms: Arc::new(AtomicU64::new(0)),
                swap_in_max_duration_ms: Arc::new(AtomicU64::new(0)),
                swap_out_max_duration_ms: Arc::new(AtomicU64::new(0)),
            },
            cache_stats: CacheStats {
                cache_hits: Arc::new(AtomicU64::new(0)),
                cache_misses: Arc::new(AtomicU64::new(0)),
                hit_rate: Arc::new(Mutex::new(0.0)),
            },
            io_stats: IoPerformanceStats {
                total_io_bytes: Arc::new(AtomicU64::new(0)),
                total_io_duration_ms: Arc::new(AtomicU64::new(0)),
                max_io_duration_ms: Arc::new(AtomicU64::new(0)),
                io_operations: Arc::new(AtomicU64::new(0)),
            },
            pool_metrics: PoolMetrics {
                used_bytes: Arc::new(AtomicU64::new(0)),
                capacity_bytes: Arc::new(AtomicU64::new(0)),
                pool_utilization: Arc::new(Mutex::new(0.0)),
            },
            component_health: Arc::new(Mutex::new(HashMap::new())),
            memory_pressure_events: Arc::new(AtomicU64::new(0)),
            cleanup_operations: Arc::new(AtomicU64::new(0)),
            pressure_levels: Arc::new(Mutex::new(HashMap::new())),
        }
    }

    pub fn calculate_hit_rate(&self) -> f64 {
        let hits = self.cache_stats.cache_hits.load(Ordering::Relaxed);
        let misses = self.cache_stats.cache_misses.load(Ordering::Relaxed);
        let total = hits + misses;
        
        if total > 0 {
            (hits as f64 / total as f64) * 100.0
        } else {
            0.0
        }
    }

    pub fn calculate_swap_throughput_mbps(&self) -> f64 {
        let total_bytes = self.io_stats.total_io_bytes.load(Ordering::Relaxed);
        let total_duration_ms = self.io_stats.total_io_duration_ms.load(Ordering::Relaxed);
        
        if total_duration_ms > 0 {
            let bytes_per_second = (total_bytes as f64 * 1000.0) / total_duration_ms as f64;
            (bytes_per_second * 8.0) / (1024.0 * 1024.0) // Convert to Mbps
        } else {
            0.0
        }
    }

    pub fn calculate_average_swap_time_ms(&self) -> (f64, f64) {
        let swap_in_ops = self.swap_stats.swap_in_operations.load(Ordering::Relaxed);
        let swap_out_ops = self.swap_stats.swap_out_operations.load(Ordering::Relaxed);
        
        let avg_in = if swap_in_ops > 0 {
            self.swap_stats.swap_in_total_duration_ms.load(Ordering::Relaxed) as f64 / swap_in_ops as f64
        } else {
            0.0
        };
        
        let avg_out = if swap_out_ops > 0 {
            self.swap_stats.swap_out_total_duration_ms.load(Ordering::Relaxed) as f64 / swap_out_ops as f64
        } else {
            0.0
        };
        
        (avg_in, avg_out)
    }

    pub fn update_pool_utilization(&self) {
        let used = self.pool_metrics.used_bytes.load(Ordering::Relaxed);
        let capacity = self.pool_metrics.capacity_bytes.load(Ordering::Relaxed);
        
        let utilization = if capacity > 0 {
            (used as f64 / capacity as f64) * 100.0
        } else {
            0.0
        };
        
        if let Ok(mut util) = self.pool_metrics.pool_utilization.lock() {
            *util = utilization;
        }
    }

    pub fn update_cache_hit_rate(&self) {
        let hit_rate = self.calculate_hit_rate();
        if let Ok(mut rate) = self.cache_stats.hit_rate.lock() {
            *rate = hit_rate;
        }
    }
}

impl Default for PerformanceStats {
    fn default() -> Self {
        Self::new()
    }
}



static SWAP_PERFORMANCE_STATS: Lazy<PerformanceStats> = Lazy::new(PerformanceStats::new);

pub struct PerformanceMonitor {
    pub logger: Arc<PerformanceLogger>,

    // Thresholds (lock-free)
    pub jni_call_threshold_ms: Arc<AtomicU64>,
    pub lock_wait_threshold_ms: Arc<AtomicU64>,
    pub memory_usage_threshold_pct: Arc<AtomicU32>,
    pub gc_duration_threshold_ms: Arc<AtomicU64>,

    // Counters (lock-free)
    pub jni_calls_total: Arc<AtomicU64>,
    pub jni_call_duration_ms: Arc<AtomicU64>,
    pub jni_max_call_duration_ms: Arc<AtomicU64>,

    pub lock_waits_total: Arc<AtomicU64>,
    pub lock_wait_time_ms: Arc<AtomicU64>,
    pub lock_max_wait_time_ms: Arc<AtomicU64>,
    pub current_lock_contention: Arc<AtomicU32>,

    pub memory_total_heap: Arc<AtomicU64>,
    pub memory_used_heap: Arc<AtomicU64>,
    pub memory_free_heap: Arc<AtomicU64>,
    pub memory_peak_heap: Arc<AtomicU64>,
    pub memory_gc_count: Arc<AtomicU64>,
    pub memory_gc_time_ms: Arc<AtomicU64>,

    // State (lock-free)
    pub is_monitoring: Arc<AtomicBool>,
    pub last_alert_time_ns: Arc<AtomicU64>, // Store as nanoseconds since epoch
    pub alert_cooldown: Arc<AtomicU64>,

    // JNI reference tracking to prevent leaks
    pub jni_global_refs: Arc<Mutex<HashMap<jlong, GlobalRef>>>,
}

impl PerformanceMonitor {
    pub fn new(logger: Arc<PerformanceLogger>) -> Result<Self> {
        let now_ns = SystemTime::now()
            .duration_since(SystemTime::UNIX_EPOCH)
            .map_err(|e| RustError::OperationFailed(format!("Failed to get current time: {}", e)))?
            .as_nanos() as u64;

        Ok(Self {
            logger,

            // Thresholds
            jni_call_threshold_ms: Arc::new(AtomicU64::new(100)),
            lock_wait_threshold_ms: Arc::new(AtomicU64::new(50)),
            memory_usage_threshold_pct: Arc::new(AtomicU32::new(90)),
            gc_duration_threshold_ms: Arc::new(AtomicU64::new(100)),

            // Counters - initialized to 0
            jni_calls_total: Arc::new(AtomicU64::new(0)),
            jni_call_duration_ms: Arc::new(AtomicU64::new(0)),
            jni_max_call_duration_ms: Arc::new(AtomicU64::new(0)),

            lock_waits_total: Arc::new(AtomicU64::new(0)),
            lock_wait_time_ms: Arc::new(AtomicU64::new(0)),
            lock_max_wait_time_ms: Arc::new(AtomicU64::new(0)),
            current_lock_contention: Arc::new(AtomicU32::new(0)),

            memory_total_heap: Arc::new(AtomicU64::new(0)),
            memory_used_heap: Arc::new(AtomicU64::new(0)),
            memory_free_heap: Arc::new(AtomicU64::new(0)),
            memory_peak_heap: Arc::new(AtomicU64::new(0)),
            memory_gc_count: Arc::new(AtomicU64::new(0)),
            memory_gc_time_ms: Arc::new(AtomicU64::new(0)),

            // State
            is_monitoring: Arc::new(AtomicBool::new(true)),
            last_alert_time_ns: Arc::new(AtomicU64::new(now_ns)),
            alert_cooldown: Arc::new(AtomicU64::new(3000)), // 3 seconds cooldown

            // JNI reference tracking to prevent leaks
            jni_global_refs: Arc::new(Mutex::new(HashMap::new())),
        })
    }

    pub fn record_jni_call(&self, call_type: &str, duration_ms: u64) {
        if !self.is_monitoring.load(Ordering::Relaxed) {
            return;
        }

        // Update atomic counters
        self.jni_calls_total.fetch_add(1, Ordering::Relaxed);
        self.jni_call_duration_ms
            .fetch_add(duration_ms, Ordering::Relaxed);

        // Update max call duration with compare-and-swap
        let current_max = self.jni_max_call_duration_ms.load(Ordering::Relaxed);
        if duration_ms > current_max {
            let _ = self.jni_max_call_duration_ms.compare_exchange_weak(
                current_max,
                duration_ms,
                Ordering::Relaxed,
                Ordering::Relaxed,
            );
        }

        // Check threshold and trigger alert if needed
        let threshold = self.jni_call_threshold_ms.load(Ordering::Relaxed);
        if duration_ms > threshold {
            self.trigger_threshold_alert(
                "JNI_CALL",
                &format!(
                    "JNI call exceeded threshold: {}ms > {}ms (type: {})",
                    duration_ms, threshold, call_type
                ),
            );
        }
    }

    pub fn record_lock_wait(&self, lock_name: &str, duration_ms: u64) {
        if !self.is_monitoring.load(Ordering::Relaxed) {
            return;
        }

        // Update atomic counters
        self.lock_waits_total.fetch_add(1, Ordering::Relaxed);
        self.lock_wait_time_ms
            .fetch_add(duration_ms, Ordering::Relaxed);

        // Update max lock wait time with compare-and-swap
        let current_max = self.lock_max_wait_time_ms.load(Ordering::Relaxed);
        if duration_ms > current_max {
            let _ = self.lock_max_wait_time_ms.compare_exchange_weak(
                current_max,
                duration_ms,
                Ordering::Relaxed,
                Ordering::Relaxed,
            );
        }

        // Update current lock contention
        self.current_lock_contention.fetch_add(1, Ordering::Relaxed);

        // Check threshold and trigger alert if needed
        let threshold = self.lock_wait_threshold_ms.load(Ordering::Relaxed);
        if duration_ms > threshold {
            self.trigger_threshold_alert(
                "LOCK_WAIT",
                &format!(
                    "Lock wait exceeded threshold: {}ms > {}ms (lock: {})",
                    duration_ms, threshold, lock_name
                ),
            );
        }
    }

    pub fn record_memory_usage(&self, total_bytes: u64, used_bytes: u64, free_bytes: u64) {
        if !self.is_monitoring.load(Ordering::Relaxed) {
            return;
        }

        // Update memory metrics
        self.memory_total_heap.store(total_bytes, Ordering::Relaxed);
        self.memory_used_heap.store(used_bytes, Ordering::Relaxed);
        self.memory_free_heap.store(free_bytes, Ordering::Relaxed);

        // Calculate and update peak heap usage
        let current_peak = self.memory_peak_heap.load(Ordering::Relaxed);
        if used_bytes > current_peak {
            let _ = self.memory_peak_heap.compare_exchange_weak(
                current_peak,
                used_bytes,
                Ordering::Relaxed,
                Ordering::Relaxed,
            );
        }

        // Calculate usage percentage and check threshold
        let used_percent = if total_bytes > 0 {
            (used_bytes as f64 / total_bytes as f64) * 100.0
        } else {
            0.0
        };

        let threshold = self.memory_usage_threshold_pct.load(Ordering::Relaxed) as f64;
        if used_percent > threshold {
            self.trigger_threshold_alert(
                "MEMORY_USAGE",
                &format!(
                    "Memory usage exceeded threshold: {:.1}% > {:.1}%",
                    used_percent, threshold
                ),
            );
        }
    }

    pub fn record_gc_event(&self, duration_ms: u64) {
        if !self.is_monitoring.load(Ordering::Relaxed) {
            return;
        }

        // Update GC metrics
        self.memory_gc_count.fetch_add(1, Ordering::Relaxed);
        self.memory_gc_time_ms
            .fetch_add(duration_ms, Ordering::Relaxed);

        // Check threshold and trigger alert if needed
        let threshold = self.gc_duration_threshold_ms.load(Ordering::Relaxed);
        if duration_ms > threshold {
            self.trigger_threshold_alert(
                "GC_DURATION",
                &format!(
                    "GC duration exceeded threshold: {}ms > {}ms",
                    duration_ms, threshold
                ),
            );
        }
    }

    fn trigger_threshold_alert(&self, _alert_type: &str, message: &str) {
        let now_ns = SystemTime::now()
            .duration_since(SystemTime::UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos() as u64;

        let last_alert_ns = self.last_alert_time_ns.load(Ordering::Relaxed);
        let cooldown_ms = self.alert_cooldown.load(Ordering::Relaxed);

        // Check cooldown to prevent alert spam (convert cooldown to nanoseconds)
        let cooldown_ns = cooldown_ms * 1_000_000;
        if now_ns < last_alert_ns + cooldown_ns {
            return;
        }

        // Log the alert and update last alert time atomically
        let trace_id = generate_trace_id();
        self.logger.log_error(
            "threshold_alert",
            &trace_id,
            &format!("[ALERT] {}", message),
            "THRESHOLD_ALERT",
        );

        // Use compare_exchange to update atomically
        let _ = self.last_alert_time_ns.compare_exchange(
            last_alert_ns,
            now_ns,
            Ordering::Relaxed,
            Ordering::Relaxed,
        );
    }

    pub fn get_metrics_snapshot(&self) -> PerformanceMetrics {
        // Create metrics directly from atomic counters (lock-free)
        let mut metrics = PerformanceMetrics::default();

        // Update metrics with current counter values
        metrics.jni_calls.total_calls = self.jni_calls_total.load(Ordering::Relaxed);
        metrics.jni_calls.call_duration_ms = self.jni_call_duration_ms.load(Ordering::Relaxed);
        metrics.jni_calls.max_call_duration_ms =
            self.jni_max_call_duration_ms.load(Ordering::Relaxed);

        metrics.lock_wait_metrics.total_lock_waits = self.lock_waits_total.load(Ordering::Relaxed);
        metrics.lock_wait_metrics.total_lock_wait_time_ms =
            self.lock_wait_time_ms.load(Ordering::Relaxed);
        metrics.lock_wait_metrics.max_lock_wait_time_ms =
            self.lock_max_wait_time_ms.load(Ordering::Relaxed);
        metrics.lock_wait_metrics.current_lock_contention =
            self.current_lock_contention.load(Ordering::Relaxed);

        metrics.memory_metrics.total_heap_bytes = self.memory_total_heap.load(Ordering::Relaxed);
        metrics.memory_metrics.used_heap_bytes = self.memory_used_heap.load(Ordering::Relaxed);
        metrics.memory_metrics.free_heap_bytes = self.memory_free_heap.load(Ordering::Relaxed);
        metrics.memory_metrics.peak_heap_bytes = self.memory_peak_heap.load(Ordering::Relaxed);
        metrics.memory_metrics.gc_count = self.memory_gc_count.load(Ordering::Relaxed);
        metrics.memory_metrics.gc_time_ms = self.memory_gc_time_ms.load(Ordering::Relaxed);
        metrics.memory_metrics.used_heap_percent = if metrics.memory_metrics.total_heap_bytes > 0 {
            (metrics.memory_metrics.used_heap_bytes as f64
                / metrics.memory_metrics.total_heap_bytes as f64)
                * 100.0
        } else {
            0.0
        };

        metrics
    }
}

// --- Minimal swap reporting API stubs (exported at crate root) ---
#[derive(Debug, Clone, Copy)]
pub enum SwapHealthStatus {
    Healthy,
    Degraded,
    Unhealthy,
}

pub struct SwapPerformanceSummary {
    pub swap_in_operations: u64,
    pub swap_out_operations: u64,
    pub swap_failures: u64,
    pub swap_in_bytes: u64,
    pub swap_out_bytes: u64,
    pub average_swap_in_time_ms: f64,
    pub average_swap_out_time_ms: f64,
    pub swap_latency_ms: f64,
    pub swap_io_throughput_mbps: f64,
    pub swap_hit_rate: f64,
    pub swap_miss_rate: f64,
    pub memory_pressure_level: String,
    pub pressure_trigger_events: u64,
    pub swap_cleanup_operations: u64,
}

pub fn report_swap_operation(
    direction: &str,
    bytes: u64,
    duration: std::time::Duration,
    success: bool,
) {
    let duration_ms = duration.as_millis() as u64;
    
    if direction == "in" {
        SWAP_PERFORMANCE_STATS.swap_stats.swap_in_operations.fetch_add(1, Ordering::Relaxed);
        SWAP_PERFORMANCE_STATS.swap_stats.swap_in_bytes.fetch_add(bytes, Ordering::Relaxed);
        SWAP_PERFORMANCE_STATS.swap_stats.swap_in_total_duration_ms.fetch_add(duration_ms, Ordering::Relaxed);
        
        // Update max duration
        let current_max = SWAP_PERFORMANCE_STATS.swap_stats.swap_in_max_duration_ms.load(Ordering::Relaxed);
        if duration_ms > current_max {
            let _ = SWAP_PERFORMANCE_STATS.swap_stats.swap_in_max_duration_ms.compare_exchange_weak(
                current_max,
                duration_ms,
                Ordering::Relaxed,
                Ordering::Relaxed,
            );
        }
    } else if direction == "out" {
        SWAP_PERFORMANCE_STATS.swap_stats.swap_out_operations.fetch_add(1, Ordering::Relaxed);
        SWAP_PERFORMANCE_STATS.swap_stats.swap_out_bytes.fetch_add(bytes, Ordering::Relaxed);
        SWAP_PERFORMANCE_STATS.swap_stats.swap_out_total_duration_ms.fetch_add(duration_ms, Ordering::Relaxed);
        
        // Update max duration
        let current_max = SWAP_PERFORMANCE_STATS.swap_stats.swap_out_max_duration_ms.load(Ordering::Relaxed);
        if duration_ms > current_max {
            let _ = SWAP_PERFORMANCE_STATS.swap_stats.swap_out_max_duration_ms.compare_exchange_weak(
                current_max,
                duration_ms,
                Ordering::Relaxed,
                Ordering::Relaxed,
            );
        }
    }
    
    if !success {
        SWAP_PERFORMANCE_STATS.swap_stats.swap_failures.fetch_add(1, Ordering::Relaxed);
    }
    
    // Log the operation
    let trace_id = generate_trace_id();
    PERFORMANCE_MONITOR.logger.log_info(
        "swap_operation",
        &trace_id,
        &format!(
            "Swap {}: {} bytes in {}ms, success: {}",
            direction, bytes, duration_ms, success
        ),
    );
}

pub fn report_memory_pressure(level: &str, cleanup_performed: bool) {
    SWAP_PERFORMANCE_STATS.memory_pressure_events.fetch_add(1, Ordering::Relaxed);
    
    // Track pressure levels
    if let Ok(mut levels) = SWAP_PERFORMANCE_STATS.pressure_levels.lock() {
        levels.entry(level.to_string())
            .and_modify(|count| *count += 1)
            .or_insert(1);
    }
    
    if cleanup_performed {
        SWAP_PERFORMANCE_STATS.cleanup_operations.fetch_add(1, Ordering::Relaxed);
    }
    
    // Log the pressure event
    let trace_id = generate_trace_id();
    PERFORMANCE_MONITOR.logger.log_warning(
        "memory_pressure",
        &trace_id,
        &format!("Memory pressure level: {}, cleanup: {}", level, cleanup_performed),
    );
}

pub fn report_swap_cache_statistics(hits: u64, misses: u64) {
    SWAP_PERFORMANCE_STATS.cache_stats.cache_hits.fetch_add(hits, Ordering::Relaxed);
    SWAP_PERFORMANCE_STATS.cache_stats.cache_misses.fetch_add(misses, Ordering::Relaxed);
    
    // Update hit rate
    SWAP_PERFORMANCE_STATS.update_cache_hit_rate();
    
    // Log cache statistics
    let trace_id = generate_trace_id();
    let hit_rate = SWAP_PERFORMANCE_STATS.calculate_hit_rate();
    PERFORMANCE_MONITOR.logger.log_info(
        "cache_stats",
        &trace_id,
        &format!("Cache hits: {}, misses: {}, hit rate: {:.1}%", hits, misses, hit_rate),
    );
}

pub fn report_swap_io_performance(bytes: u64, duration: std::time::Duration) {
    let duration_ms = duration.as_millis() as u64;
    
    SWAP_PERFORMANCE_STATS.io_stats.total_io_bytes.fetch_add(bytes, Ordering::Relaxed);
    SWAP_PERFORMANCE_STATS.io_stats.total_io_duration_ms.fetch_add(duration_ms, Ordering::Relaxed);
    SWAP_PERFORMANCE_STATS.io_stats.io_operations.fetch_add(1, Ordering::Relaxed);
    
    // Update max duration
    let current_max = SWAP_PERFORMANCE_STATS.io_stats.max_io_duration_ms.load(Ordering::Relaxed);
    if duration_ms > current_max {
        let _ = SWAP_PERFORMANCE_STATS.io_stats.max_io_duration_ms.compare_exchange_weak(
            current_max,
            duration_ms,
            Ordering::Relaxed,
            Ordering::Relaxed,
        );
    }
    
    // Log IO performance
    let trace_id = generate_trace_id();
    let throughput = SWAP_PERFORMANCE_STATS.calculate_swap_throughput_mbps();
    PERFORMANCE_MONITOR.logger.log_info(
        "io_performance",
        &trace_id,
        &format!("IO: {} bytes in {}ms, throughput: {:.2} Mbps", bytes, duration_ms, throughput),
    );
}

pub fn report_swap_pool_metrics(used_bytes: u64, capacity_bytes: u64) {
    SWAP_PERFORMANCE_STATS.pool_metrics.used_bytes.store(used_bytes, Ordering::Relaxed);
    SWAP_PERFORMANCE_STATS.pool_metrics.capacity_bytes.store(capacity_bytes, Ordering::Relaxed);
    
    // Update utilization
    SWAP_PERFORMANCE_STATS.update_pool_utilization();
    
    // Log pool metrics
    let trace_id = generate_trace_id();
    let utilization = if capacity_bytes > 0 {
        (used_bytes as f64 / capacity_bytes as f64) * 100.0
    } else {
        0.0
    };
    
    PERFORMANCE_MONITOR.logger.log_info(
        "pool_metrics",
        &trace_id,
        &format!("Pool: {} / {} bytes ({:.1}% utilization)", used_bytes, capacity_bytes, utilization),
    );
}

pub fn report_swap_component_health(
    component: &str,
    status: SwapHealthStatus,
    reason: Option<&str>,
) {
    let mut health_map = SWAP_PERFORMANCE_STATS.component_health.lock().unwrap();
    
    let health = health_map.entry(component.to_string()).or_insert_with(|| {
        ComponentHealth {
            status: Arc::new(RwLock::new(SwapHealthStatus::Healthy)),
            last_update: Arc::new(Mutex::new(Instant::now())),
            failure_reason: Arc::new(Mutex::new(None)),
        }
    });
    
    // Update status
    if let Ok(mut health_status) = health.status.write() {
        *health_status = status;
    }
    
    // Update last update time
    if let Ok(mut last_update) = health.last_update.lock() {
        *last_update = Instant::now();
    }
    
    // Update failure reason if provided
    if let Some(reason_str) = reason {
        if let Ok(mut failure_reason) = health.failure_reason.lock() {
            *failure_reason = Some(reason_str.to_string());
        }
    }
    
    // Log health status
    let trace_id = generate_trace_id();
    let status_str = match status {
        SwapHealthStatus::Healthy => "HEALTHY",
        SwapHealthStatus::Degraded => "DEGRADED",
        SwapHealthStatus::Unhealthy => "UNHEALTHY",
    };
    
    let log_message = if let Some(reason_str) = reason {
        format!("Component {} status: {}, reason: {}", component, status_str, reason_str)
    } else {
        format!("Component {} status: {}", component, status_str)
    };
    
    match status {
        SwapHealthStatus::Healthy => {
            PERFORMANCE_MONITOR.logger.log_info("component_health", &trace_id, &log_message);
        }
        SwapHealthStatus::Degraded => {
            PERFORMANCE_MONITOR.logger.log_warning("component_health", &trace_id, &log_message);
        }
        SwapHealthStatus::Unhealthy => {
            PERFORMANCE_MONITOR.logger.log_error("component_health", &trace_id, &log_message, "COMPONENT_ERROR");
        }
    }
}

pub fn get_swap_performance_summary() -> SwapPerformanceSummary {
    let (avg_in_time, avg_out_time) = SWAP_PERFORMANCE_STATS.calculate_average_swap_time_ms();
    let hit_rate = SWAP_PERFORMANCE_STATS.calculate_hit_rate();
    let throughput = SWAP_PERFORMANCE_STATS.calculate_swap_throughput_mbps();
    
    // Get most frequent pressure level
    let pressure_level = if let Ok(levels) = SWAP_PERFORMANCE_STATS.pressure_levels.lock() {
        levels.iter()
            .max_by_key(|(_, count)| *count)
            .map(|(level, _)| level.clone())
            .unwrap_or_else(|| "Normal".to_string())
    } else {
        "Normal".to_string()
    };
    
    SwapPerformanceSummary {
        swap_in_operations: SWAP_PERFORMANCE_STATS.swap_stats.swap_in_operations.load(Ordering::Relaxed),
        swap_out_operations: SWAP_PERFORMANCE_STATS.swap_stats.swap_out_operations.load(Ordering::Relaxed),
        swap_failures: SWAP_PERFORMANCE_STATS.swap_stats.swap_failures.load(Ordering::Relaxed),
        swap_in_bytes: SWAP_PERFORMANCE_STATS.swap_stats.swap_in_bytes.load(Ordering::Relaxed),
        swap_out_bytes: SWAP_PERFORMANCE_STATS.swap_stats.swap_out_bytes.load(Ordering::Relaxed),
        average_swap_in_time_ms: avg_in_time,
        average_swap_out_time_ms: avg_out_time,
        swap_latency_ms: SWAP_PERFORMANCE_STATS.io_stats.max_io_duration_ms.load(Ordering::Relaxed) as f64,
        swap_io_throughput_mbps: throughput,
        swap_hit_rate: hit_rate,
        swap_miss_rate: 100.0 - hit_rate,
        memory_pressure_level: pressure_level,
        pressure_trigger_events: SWAP_PERFORMANCE_STATS.memory_pressure_events.load(Ordering::Relaxed),
        swap_cleanup_operations: SWAP_PERFORMANCE_STATS.cleanup_operations.load(Ordering::Relaxed),
    }
}

/// Record an operation for lightweight performance tracking from other modules.
pub fn record_operation(start: std::time::Instant, items_processed: usize, thread_count: usize) {
    let duration = start.elapsed().as_millis() as u64;
    let trace_id = generate_trace_id();
    PERFORMANCE_MONITOR.logger.log_info(
        "operation",
        &trace_id,
        &format!(
            "processed {} items on {} threads in {} ms",
            items_processed, thread_count, duration
        ),
    );
}

/// Get comprehensive system status information for logging
pub fn get_system_status() -> String {
    let simd_capability = detect_simd_capability();
    let metrics = PERFORMANCE_MONITOR.get_metrics_snapshot();

    format!(
        "CPU Capabilities: {:?}, Memory: {:.1}% used, GC Events: {}, Lock Contention: {}",
        simd_capability,
        metrics.memory_metrics.used_heap_percent,
        metrics.memory_metrics.gc_count,
        metrics.lock_wait_metrics.current_lock_contention
    )
}

/// Log system status periodically
pub fn log_periodic_status() {
    let trace_id = generate_trace_id();
    let status = get_system_status();
    PERFORMANCE_MONITOR
        .logger
        .log_info("system_status", &trace_id, &status);
}

/// Log startup information with comprehensive system details
pub fn log_startup_info() {
    let trace_id = generate_trace_id();
    let simd_capability = detect_simd_capability();

    // Get CPU capabilities string
    let cpu_capabilities = match simd_capability {
        SimdCapability::Avx512Extreme => "AVX-512 Extreme ✓ AVX-512 ✓ AVX2 ✓ SSE4.2 ✓",
        SimdCapability::Avx512 => "AVX-512 ✓ AVX2 ✓ SSE4.2 ✓",
        SimdCapability::Avx2 => "AVX2 ✓ SSE4.2 ✓",
        SimdCapability::Sse => "SSE4.2 ✓",
        SimdCapability::Scalar => "Scalar (no SIMD)",
    };

    PERFORMANCE_MONITOR.logger.log_info("startup", &trace_id,
        &format!("=== KNEAF MOD STARTUP ===\nCPU Capabilities: {}\nSIMD Level: {:?}\nRust Performance Monitoring: ACTIVE",
        cpu_capabilities, simd_capability));
}

/// Log real-time status updates with threshold checking
pub fn log_real_time_status() {
    let trace_id = generate_trace_id();
    let metrics = PERFORMANCE_MONITOR.get_metrics_snapshot();

    // Check thresholds and log warnings if exceeded
    if metrics.memory_metrics.used_heap_percent > 90.0 {
        PERFORMANCE_MONITOR.logger.log_warning(
            "memory_threshold",
            &trace_id,
            &format!(
                "Memory usage high: {:.1}%",
                metrics.memory_metrics.used_heap_percent
            ),
        );
    }

    if metrics.lock_wait_metrics.current_lock_contention > 10 {
        PERFORMANCE_MONITOR.logger.log_warning(
            "lock_contention",
            &trace_id,
            &format!(
                "High lock contention: {}",
                metrics.lock_wait_metrics.current_lock_contention
            ),
        );
    }

    if metrics.jni_calls.max_call_duration_ms > 100 {
        PERFORMANCE_MONITOR.logger.log_warning(
            "jni_threshold",
            &trace_id,
            &format!(
                "Slow JNI call: {}ms",
                metrics.jni_calls.max_call_duration_ms
            ),
        );
    }
}

/// Log performance metrics summary
pub fn log_performance_summary() {
    let trace_id = generate_trace_id();
    let metrics = PERFORMANCE_MONITOR.get_metrics_snapshot();

    PERFORMANCE_MONITOR.logger.log_info(
        "performance_summary",
        &trace_id,
        &format!(
            "Performance Summary: {} JNI calls, {} lock waits, {:.1}% memory used, {} GC events",
            metrics.jni_calls.total_calls,
            metrics.lock_wait_metrics.total_lock_waits,
            metrics.memory_metrics.used_heap_percent,
            metrics.memory_metrics.gc_count
        ),
    );
}

pub static PERFORMANCE_MONITOR: Lazy<PerformanceMonitor> = Lazy::new(|| {
    let logger = PerformanceLogger::new("performance_monitor");
    PerformanceMonitor::new(Arc::new(logger)).expect("Failed to create PerformanceMonitor")
});

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_RustPerformance_recordJniCallNative(
    mut env: JNIEnv,
    _class: JClass,
    call_type: JString,
    duration_ms: jlong,
) -> jlong {
    match env.get_string(&call_type) {
        Ok(jni_call_type) => {
            let call_type = jni_call_type.to_string_lossy().into_owned();
            let duration_ms = duration_ms as u64;
            PERFORMANCE_MONITOR.record_jni_call(&call_type, duration_ms);
            0 // Success
        }
        Err(e) => {
            let trace_id = generate_trace_id();
            PERFORMANCE_MONITOR.logger.log_error(
                "jni_exception",
                &trace_id,
                &format!("Failed to get JNI call type: {}", e),
                "JNI_ERROR",
            );
            if let Err(throw_err) = env.throw_new(
                "java/lang/IllegalStateException",
                &format!("Failed to get JNI call type: {}", e),
            ) {
                PERFORMANCE_MONITOR.logger.log_error(
                    "jni_exception",
                    &trace_id,
                    &format!("Failed to throw exception: {}", throw_err),
                    "JNI_ERROR",
                );
            }
            -1 // Error
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_RustPerformance_recordLockWaitNative(
    mut env: JNIEnv,
    _class: JClass,
    lock_name: JString,
    duration_ms: jlong,
) -> jlong {
    match env.get_string(&lock_name) {
        Ok(jni_lock_name) => {
            let lock_name = jni_lock_name.to_string_lossy().into_owned();
            let duration_ms = duration_ms as u64;
            PERFORMANCE_MONITOR.record_lock_wait(&lock_name, duration_ms);
            0 // Success
        }
        Err(e) => {
            let trace_id = generate_trace_id();
            PERFORMANCE_MONITOR.logger.log_error(
                "jni_exception",
                &trace_id,
                &format!("Failed to get lock name: {}", e),
                "JNI_ERROR",
            );
            if let Err(throw_err) = env.throw_new(
                "java/lang/IllegalStateException",
                &format!("Failed to get lock name: {}", e),
            ) {
                PERFORMANCE_MONITOR.logger.log_error(
                    "jni_exception",
                    &trace_id,
                    &format!("Failed to throw exception: {}", throw_err),
                    "JNI_ERROR",
                );
            }
            -1 // Error
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_RustPerformance_recordMemoryUsageNative(
    _env: JNIEnv,
    _class: JClass,
    total_bytes: jlong,
    used_bytes: jlong,
    free_bytes: jlong,
) -> jlong {
    let total_bytes = total_bytes as u64;
    let used_bytes = used_bytes as u64;
    let free_bytes = free_bytes as u64;
    PERFORMANCE_MONITOR.record_memory_usage(total_bytes, used_bytes, free_bytes);
    0 // Success
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_RustPerformance_recordGcEventNative(
    _env: JNIEnv,
    _class: JClass,
    duration_ms: jlong,
) -> jlong {
    let duration_ms = duration_ms as u64;
    PERFORMANCE_MONITOR.record_gc_event(duration_ms);
    0 // Success
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_RustPerformance_getPerformanceMetricsNative(
    mut env: JNIEnv,
    _class: JClass,
) -> jni::sys::jstring {
    let metrics = PERFORMANCE_MONITOR.get_metrics_snapshot();

    // For simplicity, we'll return a JSON string for now
    let json = format!(
        "{{\"jniCalls\":{{\"totalCalls\":{},\"totalDurationMs\":{},\"maxDurationMs\":{}}},\"lockWaits\":{{\"totalLockWaits\":{},\"totalLockWaitTimeMs\":{},\"maxLockWaitTimeMs\":{},\"currentLockContention\":{}}},\"memory\":{{\"totalHeapBytes\":{},\"usedHeapBytes\":{},\"freeHeapBytes\":{},\"peakHeapBytes\":{},\"gcCount\":{},\"gcTimeMs\":{},\"usedHeapPercent\":{:.1}}}}}",
        metrics.jni_calls.total_calls,
        metrics.jni_calls.call_duration_ms,
        metrics.jni_calls.max_call_duration_ms,
        metrics.lock_wait_metrics.total_lock_waits,
        metrics.lock_wait_metrics.total_lock_wait_time_ms,
        metrics.lock_wait_metrics.max_lock_wait_time_ms,
        metrics.lock_wait_metrics.current_lock_contention,
        metrics.memory_metrics.total_heap_bytes,
        metrics.memory_metrics.used_heap_bytes,
        metrics.memory_metrics.free_heap_bytes,
        metrics.memory_metrics.peak_heap_bytes,
        metrics.memory_metrics.gc_count,
        metrics.memory_metrics.gc_time_ms,
        metrics.memory_metrics.used_heap_percent
    );

    match env.new_string(json) {
        Ok(jni_string) => jni_string.into_raw(),
        Err(e) => {
            let trace_id = generate_trace_id();
            PERFORMANCE_MONITOR.logger.log_error(
                "jni_exception",
                &trace_id,
                &format!("Failed to create JSON string: {}", e),
                "JNI_ERROR",
            );
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                &format!("Failed to create JSON string: {}", e),
            );
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_RustPerformance_nativeInitialize(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    // Initialize the performance monitoring system
    let trace_id = generate_trace_id();
    PERFORMANCE_MONITOR.logger.log_info(
        "initialization",
        &trace_id,
        "Initializing Rust performance monitoring system",
    );

    // Initialize SIMD capabilities
    let _simd_capability = detect_simd_capability();
    
    // Mark as initialized and start monitoring
    PERFORMANCE_MONITOR.is_monitoring.store(true, Ordering::SeqCst);
    
    PERFORMANCE_MONITOR.logger.log_info(
        "initialization",
        &trace_id,
        "Rust performance monitoring system initialized successfully",
    );

    JNI_TRUE
}

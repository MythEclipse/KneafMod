use std::sync::atomic::{AtomicU64, AtomicU32, AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use jni::{JNIEnv, objects::{JString, JClass}, sys::jlong};
use std::collections::HashMap;
use crate::logging::{PerformanceLogger, generate_trace_id};
use crate::jni_raii::JniGlobalRef;
use once_cell::sync::Lazy;
use std::time::SystemTime;
use std::collections::HashMap;

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
    pub jni_global_refs: Arc<Mutex<HashMap<jlong, JString>>>,
}

impl PerformanceMonitor {
    pub fn new(logger: Arc<PerformanceLogger>) -> Self {
        let now_ns = SystemTime::now()
            .duration_since(SystemTime::UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos() as u64;

        Self {
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
        }
    }

    pub fn record_jni_call(&self, call_type: &str, duration_ms: u64) {
        if !self.is_monitoring.load(Ordering::Relaxed) {
            return;
        }

        // Update atomic counters
        self.jni_calls_total.fetch_add(1, Ordering::Relaxed);
        self.jni_call_duration_ms.fetch_add(duration_ms, Ordering::Relaxed);

        // Update max call duration with compare-and-swap
        let current_max = self.jni_max_call_duration_ms.load(Ordering::Relaxed);
        if duration_ms > current_max {
            let _ = self.jni_max_call_duration_ms.compare_exchange_weak(
                current_max, duration_ms, Ordering::Relaxed, Ordering::Relaxed
            );
        }

        // Check threshold and trigger alert if needed
        let threshold = self.jni_call_threshold_ms.load(Ordering::Relaxed);
        if duration_ms > threshold {
            self.trigger_threshold_alert(
                "JNI_CALL", 
                &format!("JNI call exceeded threshold: {}ms > {}ms (type: {})",
                    duration_ms, threshold, call_type)
            );
        }
    }

    pub fn record_lock_wait(&self, lock_name: &str, duration_ms: u64) {
        if !self.is_monitoring.load(Ordering::Relaxed) {
            return;
        }

        // Update atomic counters
        self.lock_waits_total.fetch_add(1, Ordering::Relaxed);
        self.lock_wait_time_ms.fetch_add(duration_ms, Ordering::Relaxed);

        // Update max lock wait time with compare-and-swap
        let current_max = self.lock_max_wait_time_ms.load(Ordering::Relaxed);
        if duration_ms > current_max {
            let _ = self.lock_max_wait_time_ms.compare_exchange_weak(
                current_max, duration_ms, Ordering::Relaxed, Ordering::Relaxed
            );
        }

        // Update current lock contention
        self.current_lock_contention.fetch_add(1, Ordering::Relaxed);

        // Check threshold and trigger alert if needed
        let threshold = self.lock_wait_threshold_ms.load(Ordering::Relaxed);
        if duration_ms > threshold {
            self.trigger_threshold_alert(
                "LOCK_WAIT", 
                &format!("Lock wait exceeded threshold: {}ms > {}ms (lock: {})",
                    duration_ms, threshold, lock_name)
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
                current_peak, used_bytes, Ordering::Relaxed, Ordering::Relaxed
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
                &format!("Memory usage exceeded threshold: {:.1}% > {:.1}%",
                    used_percent, threshold)
            );
        }
    }

    pub fn record_gc_event(&self, duration_ms: u64) {
        if !self.is_monitoring.load(Ordering::Relaxed) {
            return;
        }

        // Update GC metrics
        self.memory_gc_count.fetch_add(1, Ordering::Relaxed);
        self.memory_gc_time_ms.fetch_add(duration_ms, Ordering::Relaxed);

        // Check threshold and trigger alert if needed
        let threshold = self.gc_duration_threshold_ms.load(Ordering::Relaxed);
        if duration_ms > threshold {
            self.trigger_threshold_alert(
                "GC_DURATION", 
                &format!("GC duration exceeded threshold: {}ms > {}ms",
                    duration_ms, threshold)
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
        self.logger.log_error("threshold_alert", &trace_id, &format!("[ALERT] {}", message), "THRESHOLD_ALERT");

        // Use compare_exchange to update atomically
        let _ = self.last_alert_time_ns.compare_exchange(
            last_alert_ns, now_ns, Ordering::Relaxed, Ordering::Relaxed
        );
    }

    pub fn get_metrics_snapshot(&self) -> PerformanceMetrics {
        // Create metrics directly from atomic counters (lock-free)
        let mut metrics = PerformanceMetrics::default();

        // Update metrics with current counter values
        metrics.jni_calls.total_calls = self.jni_calls_total.load(Ordering::Relaxed);
        metrics.jni_calls.call_duration_ms = self.jni_call_duration_ms.load(Ordering::Relaxed);
        metrics.jni_calls.max_call_duration_ms = self.jni_max_call_duration_ms.load(Ordering::Relaxed);

        metrics.lock_wait_metrics.total_lock_waits = self.lock_waits_total.load(Ordering::Relaxed);
        metrics.lock_wait_metrics.total_lock_wait_time_ms = self.lock_wait_time_ms.load(Ordering::Relaxed);
        metrics.lock_wait_metrics.max_lock_wait_time_ms = self.lock_max_wait_time_ms.load(Ordering::Relaxed);
        metrics.lock_wait_metrics.current_lock_contention = self.current_lock_contention.load(Ordering::Relaxed);

        metrics.memory_metrics.total_heap_bytes = self.memory_total_heap.load(Ordering::Relaxed);
        metrics.memory_metrics.used_heap_bytes = self.memory_used_heap.load(Ordering::Relaxed);
        metrics.memory_metrics.free_heap_bytes = self.memory_free_heap.load(Ordering::Relaxed);
        metrics.memory_metrics.peak_heap_bytes = self.memory_peak_heap.load(Ordering::Relaxed);
        metrics.memory_metrics.gc_count = self.memory_gc_count.load(Ordering::Relaxed);
        metrics.memory_metrics.gc_time_ms = self.memory_gc_time_ms.load(Ordering::Relaxed);
        metrics.memory_metrics.used_heap_percent = if metrics.memory_metrics.total_heap_bytes > 0 {
            (metrics.memory_metrics.used_heap_bytes as f64 / metrics.memory_metrics.total_heap_bytes as f64) * 100.0
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

pub fn report_swap_operation(_direction: &str, _bytes: u64, _duration: std::time::Duration, _success: bool) {
    // Lightweight stub - real implementation records stats
}

pub fn report_memory_pressure(_level: &str, _cleanup_performed: bool) {
    // stub
}

pub fn report_swap_cache_statistics(_hits: u64, _misses: u64) {
    // stub
}

pub fn report_swap_io_performance(_bytes: u64, _duration: std::time::Duration) {
    // stub
}

pub fn report_swap_pool_metrics(_used_bytes: u64, _capacity_bytes: u64) {
    // stub
}

pub fn report_swap_component_health(_component: &str, _status: SwapHealthStatus, _reason: Option<&str>) {
    // stub
}

pub fn get_swap_performance_summary() -> SwapPerformanceSummary {
    SwapPerformanceSummary {
        swap_in_operations: 0,
        swap_out_operations: 0,
        swap_failures: 0,
        swap_in_bytes: 0,
        swap_out_bytes: 0,
        average_swap_in_time_ms: 0.0,
        average_swap_out_time_ms: 0.0,
        swap_latency_ms: 0.0,
        swap_io_throughput_mbps: 0.0,
        swap_hit_rate: 0.0,
        swap_miss_rate: 0.0,
        memory_pressure_level: "Normal".to_string(),
        pressure_trigger_events: 0,
        swap_cleanup_operations: 0,
    }
}

/// Record an operation for lightweight performance tracking from other modules.
pub fn record_operation(start: std::time::Instant, items_processed: usize, thread_count: usize) {
    let duration = start.elapsed().as_millis() as u64;
    let trace_id = generate_trace_id();
    PERFORMANCE_MONITOR.logger.log_info("operation", &trace_id, &format!("processed {} items on {} threads in {} ms", items_processed, thread_count, duration));
}

static PERFORMANCE_MONITOR: Lazy<PerformanceMonitor> = Lazy::new(|| {
    let logger = PerformanceLogger::new("performance_monitor");
    PerformanceMonitor::new(Arc::new(logger))
});

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_RustPerformance_recordJniCallNative(
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
                        PERFORMANCE_MONITOR.logger.log_error("jni_exception", &trace_id, &format!("Failed to get JNI call type: {}", e), "JNI_ERROR");
            if let Err(throw_err) = env.throw_new("java/lang/IllegalStateException", &format!("Failed to get JNI call type: {}", e)) {
                PERFORMANCE_MONITOR.logger.log_error("jni_exception", &trace_id, &format!("Failed to throw exception: {}", throw_err), "JNI_ERROR");
            }
            -1 // Error
        }
    }
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_RustPerformance_recordLockWaitNative(
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
            PERFORMANCE_MONITOR.logger.log_error("jni_exception", &trace_id, &format!("Failed to get lock name: {}", e), "JNI_ERROR");
            if let Err(throw_err) = env.throw_new("java/lang/IllegalStateException", &format!("Failed to get lock name: {}", e)) {
                PERFORMANCE_MONITOR.logger.log_error("jni_exception", &trace_id, &format!("Failed to throw exception: {}", throw_err), "JNI_ERROR");
            }
            -1 // Error
        }
    }
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_RustPerformance_recordMemoryUsageNative(
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
pub extern "C" fn Java_com_kneaf_core_performance_RustPerformance_recordGcEventNative(
    _env: JNIEnv,
    _class: JClass,
    duration_ms: jlong,
) -> jlong {
    let duration_ms = duration_ms as u64;
    PERFORMANCE_MONITOR.record_gc_event(duration_ms);
    0 // Success
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_RustPerformance_getPerformanceMetricsNative(
    mut env: JNIEnv,
    _class: JClass,
) -> JString {
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
        Ok(jni_string) => {
            // Return the string directly - JNI will manage the local reference lifetime
            // No global ref needed since this is a return value that JNI will handle
            jni_string
        },
        Err(e) => {
            let trace_id = generate_trace_id();
            PERFORMANCE_MONITOR.logger.log_error("jni_exception", &trace_id, &format!("Failed to create JSON string: {}", e), "JNI_ERROR");
            if let Err(throw_err) = env.throw_new("java/lang/IllegalStateException", &format!("Failed to create JSON string: {}", e)) {
                PERFORMANCE_MONITOR.logger.log_error("jni_exception", &trace_id, &format!("Failed to throw exception: {}", throw_err), "JNI_ERROR");
            }
            // Return null string on error
            env.new_string("").unwrap_or_else(|_| {
                // If even empty string creation fails, return a null pointer
                std::ptr::null_mut()
            })
        }
    }
}

use crate::logging::{generate_trace_id, PerformanceLogger};
use crate::errors::{Result, RustError};
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{SystemTime};
use jni::objects::GlobalRef;
use std::sync::OnceLock;
use std::ptr::null_mut;
use jni::sys::jlong;
use std::collections::HashMap;

/// Builder for creating configured PerformanceMonitor instances
pub struct PerformanceMonitorBuilder {
    // Thresholds
    jni_call_threshold_ms: Option<u64>,
    lock_wait_threshold_ms: Option<u64>,
    memory_usage_threshold_pct: Option<u32>,
    gc_duration_threshold_ms: Option<u64>,
    
    // Alert configuration
    alert_cooldown: Option<u64>,
    
    // Logger configuration
    logger_name: Option<String>,
    
    // JNI reference tracking
    enable_jni_ref_tracking: Option<bool>,
}

impl Default for PerformanceMonitorBuilder {
    fn default() -> Self {
        Self {
            jni_call_threshold_ms: None,
            lock_wait_threshold_ms: None,
            memory_usage_threshold_pct: None,
            gc_duration_threshold_ms: None,
            alert_cooldown: None,
            logger_name: None,
            enable_jni_ref_tracking: None,
        }
    }
}

impl PerformanceMonitorBuilder {
    /// Create a new builder with default settings
    pub fn new() -> Self {
        Self::default()
    }
    
    /// Set JNI call threshold in milliseconds
    pub fn with_jni_call_threshold(mut self, threshold: u64) -> Self {
        self.jni_call_threshold_ms = Some(threshold);
        self
    }
    
    /// Set lock wait threshold in milliseconds
    pub fn with_lock_wait_threshold(mut self, threshold: u64) -> Self {
        self.lock_wait_threshold_ms = Some(threshold);
        self
    }
    
    /// Set memory usage threshold percentage
    pub fn with_memory_usage_threshold(mut self, threshold: u32) -> Self {
        self.memory_usage_threshold_pct = Some(threshold);
        self
    }
    
    /// Set GC duration threshold in milliseconds
    pub fn with_gc_duration_threshold(mut self, threshold: u64) -> Self {
        self.gc_duration_threshold_ms = Some(threshold);
        self
    }
    
    /// Set alert cooldown in milliseconds
    pub fn with_alert_cooldown(mut self, cooldown: u64) -> Self {
        self.alert_cooldown = Some(cooldown);
        self
    }
    
    /// Set custom logger name
    pub fn with_logger_name(mut self, name: String) -> Self {
        self.logger_name = Some(name);
        self
    }
    
    /// Enable or disable JNI reference tracking
    pub fn with_jni_ref_tracking(mut self, enable: bool) -> Self {
        self.enable_jni_ref_tracking = Some(enable);
        self
    }
    
    /// Build a PerformanceMonitor with the configured settings
    pub fn build(&self) -> Result<PerformanceMonitor> {
        let logger_name = self.logger_name.as_deref().unwrap_or("performance_monitor");
        let logger = Arc::new(PerformanceLogger::new(logger_name));
        
        let now_ns = SystemTime::now()
            .duration_since(SystemTime::UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos() as u64;

        let monitor = PerformanceMonitor {
            logger,
            
            // Thresholds with fallback to defaults
            jni_call_threshold_ms: Arc::new(AtomicU64::new(
                self.jni_call_threshold_ms.unwrap_or(100)
            )),
            lock_wait_threshold_ms: Arc::new(AtomicU64::new(
                self.lock_wait_threshold_ms.unwrap_or(50)
            )),
            memory_usage_threshold_pct: Arc::new(AtomicU32::new(
                self.memory_usage_threshold_pct.unwrap_or(90)
            )),
            gc_duration_threshold_ms: Arc::new(AtomicU64::new(
                self.gc_duration_threshold_ms.unwrap_or(100)
            )),
            
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
            alert_cooldown: Arc::new(AtomicU64::new(
                self.alert_cooldown.unwrap_or(3000) // 3 seconds default
            )),
            
            // JNI reference tracking
            jni_global_refs: Arc::new(Mutex::new(if self.enable_jni_ref_tracking.unwrap_or(true) {
                HashMap::new()
            } else {
                // Use a dummy mutex that will never be accessed if JNI ref tracking is disabled
                let mut map = HashMap::new();
                // Use a simple placeholder instead of JNI objects for dummy entry
                // Use a dummy value for mutex initialization (avoids empty mutex issues)
                // This is a safe workaround since we only need a placeholder value
                // TEMP: Skip dummy entry for now - fix JNI integration later
                // map.insert(0 as jlong, unsafe { GlobalRef::from_raw(std::ptr::null_mut()) });
                map
            })),
        };
        
        Ok(monitor)
    }
}

/// Factory for creating PerformanceMonitor instances
pub struct PerformanceMonitorFactory;

impl PerformanceMonitorFactory {
    /// Create a default PerformanceMonitor
    pub fn create_default() -> Result<PerformanceMonitor> {
        PerformanceMonitorBuilder::new().build()
    }
    
    /// Create a PerformanceMonitor with strict thresholds
    pub fn create_strict() -> Result<PerformanceMonitor> {
        PerformanceMonitorBuilder::new()
            .with_jni_call_threshold(50)
            .with_lock_wait_threshold(25)
            .with_memory_usage_threshold(80)
            .with_gc_duration_threshold(50)
            .build()
    }
    
    /// Create a PerformanceMonitor with lenient thresholds
    pub fn create_lenient() -> Result<PerformanceMonitor> {
        PerformanceMonitorBuilder::new()
            .with_jni_call_threshold(200)
            .with_lock_wait_threshold(100)
            .with_memory_usage_threshold(95)
            .with_gc_duration_threshold(200)
            .build()
    }
    
    /// Create a custom PerformanceMonitor with specific configuration
    pub fn create_custom<F>(builder_customizer: F) -> Result<PerformanceMonitor>
    where
        F: FnOnce(PerformanceMonitorBuilder) -> PerformanceMonitorBuilder,
    {
        let builder = builder_customizer(PerformanceMonitorBuilder::new());
        builder.build()
    }
}

// pub use super::PerformanceMonitor;
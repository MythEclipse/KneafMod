use crate::errors::{Result, RustError};
use crate::logging::{generate_trace_id, PerformanceLogger};
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, Instant};

// Common metrics structures used across performance modules
#[derive(Debug, Default, Clone)]
pub struct JniCallMetrics {
    pub total_calls: u64,
    pub call_duration_ms: u64,
    pub max_call_duration_ms: u64,
    pub call_types: std::collections::HashMap<String, u64>,
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

// Common performance monitor traits
pub trait PerformanceMonitorTrait {
    fn record_jni_call(&self, call_type: &str, duration_ms: u64);
    fn record_lock_wait(&self, lock_name: &str, duration_ms: u64);
    fn record_memory_usage(&self, total_bytes: u64, used_bytes: u64, free_bytes: u64);
    fn record_gc_event(&self, duration_ms: u64);
    fn get_metrics_snapshot(&self) -> PerformanceMetrics;
}
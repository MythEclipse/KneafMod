use super::types::*;
use std::sync::RwLock;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Instant;

#[derive(Debug, Clone)]
pub struct ThreadPoolConfig {
    pub min_threads: usize,
    pub max_threads: usize,
    pub thread_stack_size: usize,
    pub adaptive_scaling: bool,
    pub utilization_threshold_low: f32,
    pub utilization_threshold_high: f32,
    pub scaling_interval_seconds: u64,
}

impl Default for ThreadPoolConfig {
    fn default() -> Self {
        let cpu_cores = num_cpus::get();
        Self {
            min_threads: 1,
            max_threads: cpu_cores * 2,
            thread_stack_size: 2 * 1024 * 1024, // 2MB
            adaptive_scaling: true,
            utilization_threshold_low: 0.3,
            utilization_threshold_high: 0.8,
            scaling_interval_seconds: 30,
        }
    }
}

#[derive(Debug, Clone)]
pub struct ProfilingConfig {
    pub enabled: bool,
    pub slow_operation_threshold_ms: u64,
    pub log_detailed_timing: bool,
}

impl Default for ProfilingConfig {
    fn default() -> Self {
        Self {
            enabled: true,
            slow_operation_threshold_ms: 10,
            log_detailed_timing: true,
        }
    }
}

#[derive(Debug, Clone)]
pub struct ThreadPoolMetrics {
    pub current_thread_count: usize,
    pub active_thread_count: usize,
    pub queued_job_count: usize,
    pub utilization_rate: f32,
    pub last_scaling_time: Instant,
}

impl Default for ThreadPoolMetrics {
    fn default() -> Self {
        Self {
            current_thread_count: 0,
            active_thread_count: 0,
            queued_job_count: 0,
            utilization_rate: 0.0,
            last_scaling_time: Instant::now(),
        }
    }
}

#[derive(Debug)]
pub struct ProcessingCounters {
    pub total_items_processed: AtomicU64,
    pub total_chunks_processed: AtomicU64,
    pub total_grouping_operations: AtomicU64,
    pub total_merge_operations: AtomicU64,
    pub total_filter_operations: AtomicU64,
    pub total_sort_operations: AtomicU64,
    pub total_despawn_operations: AtomicU64,
}

impl Default for ProcessingCounters {
    fn default() -> Self {
        Self {
            total_items_processed: AtomicU64::new(0),
            total_chunks_processed: AtomicU64::new(0),
            total_grouping_operations: AtomicU64::new(0),
            total_merge_operations: AtomicU64::new(0),
            total_filter_operations: AtomicU64::new(0),
            total_sort_operations: AtomicU64::new(0),
            total_despawn_operations: AtomicU64::new(0),
        }
    }
}

lazy_static::lazy_static! {
    pub static ref ITEM_CONFIG: RwLock<ItemConfig> = RwLock::new(ItemConfig {
        merge_enabled: true,
        max_items_per_chunk: 100,
        despawn_time_seconds: 300,
    });
    pub static ref MERGED_COUNT: AtomicU64 = AtomicU64::new(0);
    pub static ref DESPAWNED_COUNT: AtomicU64 = AtomicU64::new(0);
    pub static ref LAST_LOG_TIME: RwLock<Instant> = RwLock::new(Instant::now());
    pub static ref PROFILING_CONFIG: RwLock<ProfilingConfig> = RwLock::new(ProfilingConfig::default());
    pub static ref PROCESSING_COUNTERS: ProcessingCounters = ProcessingCounters::default();
    pub static ref THREAD_POOL_CONFIG: RwLock<ThreadPoolConfig> = RwLock::new(ThreadPoolConfig::default());
    pub static ref THREAD_POOL_METRICS: RwLock<ThreadPoolMetrics> = RwLock::new(ThreadPoolMetrics::default());
}

pub fn update_thread_pool_metrics(current_threads: usize, active_threads: usize, queued_jobs: usize) {
    let mut metrics = THREAD_POOL_METRICS.write().unwrap();
    metrics.current_thread_count = current_threads;
    metrics.active_thread_count = active_threads;
    metrics.queued_job_count = queued_jobs;
    
    if current_threads > 0 {
        metrics.utilization_rate = active_threads as f32 / current_threads as f32;
    }
}

pub fn should_scale_thread_pool() -> Option<usize> {
    let config = THREAD_POOL_CONFIG.read().unwrap();
    let mut metrics = THREAD_POOL_METRICS.write().unwrap();
    
    if !config.adaptive_scaling {
        return None;
    }
    
    let now = Instant::now();
    if now.duration_since(metrics.last_scaling_time) < std::time::Duration::from_secs(config.scaling_interval_seconds) {
        return None;
    }
    
    metrics.last_scaling_time = now;
    
    if metrics.utilization_rate > config.utilization_threshold_high && metrics.current_thread_count < config.max_threads {
        let new_count = (metrics.current_thread_count + 2).min(config.max_threads);
        Some(new_count)
    } else if metrics.utilization_rate < config.utilization_threshold_low && metrics.current_thread_count > config.min_threads {
        let new_count = (metrics.current_thread_count - 1).max(config.min_threads);
        Some(new_count)
    } else {
        None
    }
}
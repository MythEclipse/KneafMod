// Performance configuration types
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PerformanceConfig {
    pub max_threads: usize,
    pub thread_pool_size: usize,
    pub work_stealing_enabled: bool,
    pub priority_execution: bool,
    pub memory_pressure_threshold: f64,
    pub circuit_breaker_threshold: f64,
}

impl Default for PerformanceConfig {
    fn default() -> Self {
        Self {
            max_threads: num_cpus::get(),
            thread_pool_size: num_cpus::get() * 2,
            work_stealing_enabled: true,
            priority_execution: true,
            memory_pressure_threshold: 0.8,
            circuit_breaker_threshold: 0.9,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WorkStealingConfig {
    pub steal_threshold: usize,
    pub max_steal_attempts: usize,
    pub backoff_delay_ms: u64,
}

impl Default for WorkStealingConfig {
    fn default() -> Self {
        Self {
            steal_threshold: 10,
            max_steal_attempts: 5,
            backoff_delay_ms: 10,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum PerformanceMode {
    Balanced,
    Performance,
    Efficiency,
    Custom,
}
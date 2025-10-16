use serde::{Deserialize, Serialize};
use std::time::Duration;

/// Performance configuration for the application
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PerformanceConfig {
    pub max_threads: usize,
    pub thread_pool_size: usize,
    pub work_stealing_enabled: bool,
    pub priority_execution: bool,
    pub memory_pressure_threshold: f64,
    pub circuit_breaker_threshold: f64,
    pub performance_mode: PerformanceMode,
    pub monitoring_enabled: bool,
    pub metrics_collection_interval: Duration,
}

/// Performance mode enumeration
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum PerformanceMode {
    Development,
    Testing,
    Production,
    Ultra,
    Extreme,
}

/// Work stealing configuration
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WorkStealingConfig {
    pub enabled: bool,
    pub max_steal_attempts: usize,
    pub steal_timeout_ms: u64,
    pub thread_local_queue_size: usize,
    pub global_queue_size: usize,
}

impl Default for PerformanceConfig {
    fn default() -> Self {
        Self {
            max_threads: 8,
            thread_pool_size: 4,
            work_stealing_enabled: true,
            priority_execution: true,
            memory_pressure_threshold: 0.8,
            circuit_breaker_threshold: 0.9,
            performance_mode: PerformanceMode::Production,
            monitoring_enabled: true,
            metrics_collection_interval: Duration::from_secs(30),
        }
    }
}

impl Default for WorkStealingConfig {
    fn default() -> Self {
        Self {
            enabled: true,
            max_steal_attempts: 3,
            steal_timeout_ms: 100,
            thread_local_queue_size: 64,
            global_queue_size: 256,
        }
    }
}

/// Performance configuration builder
pub struct PerformanceConfigBuilder {
    config: PerformanceConfig,
}

impl PerformanceConfigBuilder {
    pub fn new() -> Self {
        Self {
            config: PerformanceConfig::default(),
        }
    }
    
    pub fn max_threads(mut self, threads: usize) -> Self {
        self.config.max_threads = threads;
        self
    }
    
    pub fn thread_pool_size(mut self, size: usize) -> Self {
        self.config.thread_pool_size = size;
        self
    }
    
    pub fn work_stealing_enabled(mut self, enabled: bool) -> Self {
        self.config.work_stealing_enabled = enabled;
        self
    }
    
    pub fn priority_execution(mut self, enabled: bool) -> Self {
        self.config.priority_execution = enabled;
        self
    }
    
    pub fn memory_pressure_threshold(mut self, threshold: f64) -> Self {
        self.config.memory_pressure_threshold = threshold;
        self
    }
    
    pub fn circuit_breaker_threshold(mut self, threshold: f64) -> Self {
        self.config.circuit_breaker_threshold = threshold;
        self
    }
    
    pub fn performance_mode(mut self, mode: PerformanceMode) -> Self {
        self.config.performance_mode = mode;
        self
    }
    
    pub fn monitoring_enabled(mut self, enabled: bool) -> Self {
        self.config.monitoring_enabled = enabled;
        self
    }
    
    pub fn metrics_collection_interval(mut self, interval: Duration) -> Self {
        self.config.metrics_collection_interval = interval;
        self
    }
    
    pub fn build(self) -> PerformanceConfig {
        self.config
    }
}

/// Work stealing configuration builder
pub struct WorkStealingConfigBuilder {
    config: WorkStealingConfig,
}

impl WorkStealingConfigBuilder {
    pub fn new() -> Self {
        Self {
            config: WorkStealingConfig::default(),
        }
    }
    
    pub fn enabled(mut self, enabled: bool) -> Self {
        self.config.enabled = enabled;
        self
    }
    
    pub fn max_steal_attempts(mut self, attempts: usize) -> Self {
        self.config.max_steal_attempts = attempts;
        self
    }
    
    pub fn steal_timeout_ms(mut self, timeout: u64) -> Self {
        self.config.steal_timeout_ms = timeout;
        self
    }
    
    pub fn thread_local_queue_size(mut self, size: usize) -> Self {
        self.config.thread_local_queue_size = size;
        self
    }
    
    pub fn global_queue_size(mut self, size: usize) -> Self {
        self.config.global_queue_size = size;
        self
    }
    
    pub fn build(self) -> WorkStealingConfig {
        self.config
    }
}

/// Global performance configuration instance
static GLOBAL_PERF_CONFIG: std::sync::OnceLock<PerformanceConfig> = std::sync::OnceLock::new();

/// Get the global performance configuration
pub fn get_global_performance_config() -> PerformanceConfig {
    GLOBAL_PERF_CONFIG.get_or_init(|| PerformanceConfig::default()).clone()
}

/// Set the global performance configuration
pub fn set_global_performance_config(config: PerformanceConfig) {
    let _ = GLOBAL_PERF_CONFIG.set(config);
}

/// Global work stealing configuration instance
static GLOBAL_WORK_STEALING_CONFIG: std::sync::OnceLock<WorkStealingConfig> = std::sync::OnceLock::new();

/// Get the global work stealing configuration
pub fn get_global_work_stealing_config() -> WorkStealingConfig {
    GLOBAL_WORK_STEALING_CONFIG.get_or_init(|| WorkStealingConfig::default()).clone()
}

/// Set the global work stealing configuration
pub fn set_global_work_stealing_config(config: WorkStealingConfig) {
    let _ = GLOBAL_WORK_STEALING_CONFIG.set(config);
}

/// Performance configuration utilities
pub struct PerformanceConfigUtils;

impl PerformanceConfigUtils {
    /// Get recommended configuration for development
    pub fn development_config() -> PerformanceConfig {
        PerformanceConfigBuilder::new()
            .max_threads(2)
            .thread_pool_size(1)
            .work_stealing_enabled(false)
            .priority_execution(false)
            .performance_mode(PerformanceMode::Development)
            .monitoring_enabled(true)
            .build()
    }
    
    /// Get recommended configuration for testing
    pub fn testing_config() -> PerformanceConfig {
        PerformanceConfigBuilder::new()
            .max_threads(4)
            .thread_pool_size(2)
            .work_stealing_enabled(true)
            .priority_execution(true)
            .performance_mode(PerformanceMode::Testing)
            .monitoring_enabled(true)
            .build()
    }
    
    /// Get recommended configuration for production
    pub fn production_config() -> PerformanceConfig {
        PerformanceConfigBuilder::new()
            .max_threads(8)
            .thread_pool_size(4)
            .work_stealing_enabled(true)
            .priority_execution(true)
            .performance_mode(PerformanceMode::Production)
            .monitoring_enabled(true)
            .build()
    }
    
    /// Get recommended configuration for ultra performance
    pub fn ultra_config() -> PerformanceConfig {
        PerformanceConfigBuilder::new()
            .max_threads(16)
            .thread_pool_size(8)
            .work_stealing_enabled(true)
            .priority_execution(true)
            .performance_mode(PerformanceMode::Ultra)
            .monitoring_enabled(true)
            .build()
    }
    
    /// Get recommended configuration for extreme performance
    pub fn extreme_config() -> PerformanceConfig {
        PerformanceConfigBuilder::new()
            .max_threads(32)
            .thread_pool_size(16)
            .work_stealing_enabled(true)
            .priority_execution(true)
            .performance_mode(PerformanceMode::Extreme)
            .monitoring_enabled(true)
            .build()
    }
}
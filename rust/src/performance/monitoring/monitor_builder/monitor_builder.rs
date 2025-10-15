use std::sync::{Arc, Mutex};
use crate::logging::PerformanceLogger;
use crate::errors::{Result, RustError};

/// Builder for creating PerformanceMonitor instances
pub struct PerformanceMonitorBuilder {
    logger: Option<PerformanceLogger>,
    enabled: bool,
    metrics_interval: u64,
}

/// Factory for creating PerformanceMonitor instances
pub struct PerformanceMonitorFactory;

impl PerformanceMonitorBuilder {
    /// Creates a new PerformanceMonitorBuilder
    pub fn new() -> Self {
        Self {
            logger: None,
            enabled: true,
            metrics_interval: 1000, // Default to 1 second
        }
    }

    /// Sets the logger for the monitor
    pub fn with_logger(mut self, logger: PerformanceLogger) -> Self {
        self.logger = Some(logger);
        self
    }

    /// Enables or disables the monitor
    pub fn with_enabled(mut self, enabled: bool) -> Self {
        self.enabled = enabled;
        self
    }

    /// Sets the metrics reporting interval in milliseconds
    pub fn with_metrics_interval(mut self, interval: u64) -> Self {
        self.metrics_interval = interval;
        self
    }

    /// Builds a PerformanceMonitor instance
    pub fn build(self) -> Result<PerformanceMonitor> {
        let logger = self.logger.unwrap_or_else(|| PerformanceLogger::new("performance_monitor"));
        
        Ok(PerformanceMonitor {
            logger: Arc::new(Mutex::new(logger)),
            enabled: self.enabled,
            metrics_interval: self.metrics_interval,
            // Initialize other fields as needed
            total_operations: 0,
            last_metrics_time: std::time::Instant::now(),
        })
    }
}

impl PerformanceMonitorFactory {
    /// Creates a default PerformanceMonitor
    pub fn create_default() -> Result<PerformanceMonitor> {
        PerformanceMonitorBuilder::new().build()
    }

    /// Creates a PerformanceMonitor with custom settings
    pub fn create_with_settings(enabled: bool, interval: u64) -> Result<PerformanceMonitor> {
        PerformanceMonitorBuilder::new()
            .with_enabled(enabled)
            .with_metrics_interval(interval)
            .build()
    }
}

/// Performance monitor for tracking system metrics
pub struct PerformanceMonitor {
    logger: Arc<Mutex<PerformanceLogger>>,
    enabled: bool,
    metrics_interval: u64,
    total_operations: u64,
    last_metrics_time: std::time::Instant,
}

impl PerformanceMonitor {
    /// Records an operation
    pub fn record_operation(&mut self, operation: &str) {
        if !self.enabled {
            return;
        }

        self.total_operations += 1;
        
        // Log the operation
        let mut logger = self.logger.lock().unwrap();
        logger.info(&format!("Operation recorded: {}", operation));

        // Report metrics if interval has passed
        if self.should_report_metrics() {
            self.report_metrics();
        }
    }

    /// Checks if metrics should be reported
    fn should_report_metrics(&self) -> bool {
        let elapsed = self.last_metrics_time.elapsed().as_millis();
        elapsed >= self.metrics_interval
    }

    /// Reports collected metrics
    fn report_metrics(&mut self) {
        if !self.enabled {
            return;
        }

        let operations_per_second = self.calculate_operations_per_second();
        
        let mut logger = self.logger.lock().unwrap();
        logger.info(&format!(
            "Performance metrics: total_operations={}, ops_per_sec={:.2}",
            self.total_operations, operations_per_second
        ));

        // Reset for next interval
        self.total_operations = 0;
        self.last_metrics_time = std::time::Instant::now();
    }

    /// Calculates operations per second
    fn calculate_operations_per_second(&self) -> f64 {
        let elapsed = self.last_metrics_time.elapsed().as_secs_f64();
        if elapsed == 0.0 {
            0.0
        } else {
            self.total_operations as f64 / elapsed
        }
    }

    /// Enables or disables the monitor
    pub fn set_enabled(&mut self, enabled: bool) {
        self.enabled = enabled;
    }

    /// Checks if the monitor is enabled
    pub fn is_enabled(&self) -> bool {
        self.enabled
    }
}

// Re-export for public use
// pub use crate::enhanced_manager::PerformanceMonitor as EnhancedPerformanceMonitor;
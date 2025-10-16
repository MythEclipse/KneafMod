use crate::logging::{generate_trace_id, PerformanceLogger};
use std::error::Error;
use std::fmt;
use std::sync::Arc;

// ==============================
// Core Error Type
// ==============================
#[derive(Debug, Clone)]
pub enum RustError {
    ConfigurationError(String),
    PerformanceError(String),
    ThreadSafeOperationFailed(String),
    JniOperationFailed(String),
    MemoryOperationFailed(String),
    NetworkOperationFailed(String),
    IoError(String),
    ValidationError(String),
    RecoveryError(String),
    OptimizationError(String),
    CustomError(String),
}

impl RustError {
    pub fn with_trace_id(self) -> RustErrorWithTraceId {
        RustErrorWithTraceId {
            error: self,
            trace_id: generate_trace_id(),
            timestamp: std::time::SystemTime::now()
                .duration_since(std::time::SystemTime::UNIX_EPOCH)
                .map(|d| d.as_millis() as u64)
                .unwrap_or(0),
        }
    }

    pub fn new_custom(message: &str) -> Self {
        RustError::CustomError(message.to_string())
    }

    pub fn new_config(message: &str) -> Self {
        RustError::ConfigurationError(message.to_string())
    }

    pub fn new_perf(message: &str) -> Self {
        RustError::PerformanceError(message.to_string())
    }

    pub fn new_thread_safe(message: &str) -> Self {
        RustError::ThreadSafeOperationFailed(message.to_string())
    }

    pub fn new_jni(message: &str) -> Self {
        RustError::JniOperationFailed(message.to_string())
    }

    pub fn new_memory(message: &str) -> Self {
        RustError::MemoryOperationFailed(message.to_string())
    }

    pub fn new_network(message: &str) -> Self {
        RustError::NetworkOperationFailed(message.to_string())
    }

    pub fn new_io(message: &str) -> Self {
        RustError::IoError(message.to_string())
    }

    pub fn new_validation(message: &str) -> Self {
        RustError::ValidationError(message.to_string())
    }

    pub fn new_recovery(message: &str) -> Self {
        RustError::RecoveryError(message.to_string())
    }

    pub fn new_optimization(message: &str) -> Self {
        RustError::OptimizationError(message.to_string())
    }
}

impl fmt::Display for RustError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            RustError::ConfigurationError(msg) => write!(f, "Configuration Error: {}", msg),
            RustError::PerformanceError(msg) => write!(f, "Performance Error: {}", msg),
            RustError::ThreadSafeOperationFailed(msg) => write!(f, "Thread-Safe Operation Failed: {}", msg),
            RustError::JniOperationFailed(msg) => write!(f, "JNI Operation Failed: {}", msg),
            RustError::MemoryOperationFailed(msg) => write!(f, "Memory Operation Failed: {}", msg),
            RustError::NetworkOperationFailed(msg) => write!(f, "Network Operation Failed: {}", msg),
            RustError::IoError(msg) => write!(f, "I/O Error: {}", msg),
            RustError::ValidationError(msg) => write!(f, "Validation Error: {}", msg),
            RustError::RecoveryError(msg) => write!(f, "Recovery Error: {}", msg),
            RustError::OptimizationError(msg) => write!(f, "Optimization Error: {}", msg),
            RustError::CustomError(msg) => write!(f, "Custom Error: {}", msg),
        }
    }
}

impl Error for RustError {}

// ==============================
// Error Type with Trace ID
// ==============================
#[derive(Debug, Clone)]
pub struct RustErrorWithTraceId {
    pub error: RustError,
    pub trace_id: String,
    pub timestamp: u64,
}

impl RustErrorWithTraceId {
    pub fn new(error: RustError) -> Self {
        Self {
            error,
            trace_id: generate_trace_id(),
            timestamp: std::time::SystemTime::now()
                .duration_since(std::time::SystemTime::UNIX_EPOCH)
                .map(|d| d.as_millis() as u64)
                .unwrap_or(0),
        }
    }

    pub fn to_json(&self) -> String {
        let error_type = match &self.error {
            RustError::ConfigurationError(_) => "ConfigurationError",
            RustError::PerformanceError(_) => "PerformanceError",
            RustError::ThreadSafeOperationFailed(_) => "ThreadSafeOperationFailed",
            RustError::JniOperationFailed(_) => "JniOperationFailed",
            RustError::MemoryOperationFailed(_) => "MemoryOperationFailed",
            RustError::NetworkOperationFailed(_) => "NetworkOperationFailed",
            RustError::IoError(_) => "IoError",
            RustError::ValidationError(_) => "ValidationError",
            RustError::RecoveryError(_) => "RecoveryError",
            RustError::OptimizationError(_) => "OptimizationError",
            RustError::CustomError(_) => "CustomError",
        };

        let error_msg = format!("{}", self.error);

        format!(
            r#"{{
                "error_type": "{}",
                "error_message": "{}",
                "trace_id": "{}",
                "timestamp": "{}",
                "timestamp_ms": {}
            }}"#,
            error_type,
            error_msg,
            self.trace_id,
            chrono::Utc::now().format("%Y-%m-%dT%H:%M:%S%.3fZ"),
            self.timestamp
        )
    }

    pub fn log_detailed(&self, component: &str, logger: &Arc<PerformanceLogger>) {
        logger.log_error(
            "detailed_error",
            &self.trace_id,
            &format!(
                "Component: {}\nError Type: {}\nMessage: {}\nTrace ID: {}\nTimestamp: {}",
                component,
                match &self.error {
                    RustError::ConfigurationError(_) => "ConfigurationError",
                    RustError::PerformanceError(_) => "PerformanceError",
                    RustError::ThreadSafeOperationFailed(_) => "ThreadSafeOperationFailed",
                    RustError::JniOperationFailed(_) => "JniOperationFailed",
                    RustError::MemoryOperationFailed(_) => "MemoryOperationFailed",
                    RustError::NetworkOperationFailed(_) => "NetworkOperationFailed",
                    RustError::IoError(_) => "IoError",
                    RustError::ValidationError(_) => "ValidationError",
                    RustError::RecoveryError(_) => "RecoveryError",
                    RustError::OptimizationError(_) => "OptimizationError",
                    RustError::CustomError(_) => "CustomError",
                },
                self.error,
                self.trace_id,
                chrono::Utc::now().format("%Y-%m-%dT%H:%M:%S%.3fZ")
            ),
        );
    }
}

impl fmt::Display for RustErrorWithTraceId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "[{}] {} (Trace ID: {})",
            chrono::Utc::now().format("%Y-%m-%d %H:%M:%S"),
            self.error,
            self.trace_id
        )
    }
}

impl Error for RustErrorWithTraceId {
    fn source(&self) -> Option<&(dyn Error + 'static)> {
        Some(&self.error)
    }
}

// ==============================
// Result Type Alias
// ==============================
pub type Result<T> = std::result::Result<T, RustErrorWithTraceId>;

// ==============================
// Error Recovery Manager
// ==============================
pub struct ErrorRecoveryManager {
    logger: Arc<PerformanceLogger>,
    recovery_strategies: Arc<std::sync::RwLock<Vec<Box<dyn ErrorRecoveryStrategy>>>>,
    error_history: Arc<std::sync::RwLock<Vec<RustErrorWithTraceId>>>,
}

pub trait ErrorRecoveryStrategy: Send + Sync {
    fn can_recover(&self, error: &RustError) -> bool;
    fn recover(&self, error: &RustErrorWithTraceId) -> Result<RecoveryResult>;
    fn get_name(&self) -> &str;
}

#[derive(Debug, Clone)]
pub struct RecoveryResult {
    pub success: bool,
    pub strategy_name: String,
    pub error_type: String,
    pub details: String,
    pub trace_id: String,
}

impl ErrorRecoveryManager {
    pub fn new(component_name: &str) -> Self {
        let logger = Arc::new(PerformanceLogger::new(component_name));
        Self {
            logger: logger.clone(),
            recovery_strategies: Arc::new(std::sync::RwLock::new(Vec::new())),
            error_history: Arc::new(std::sync::RwLock::new(Vec::new())),
        }
    }

    pub fn register_strategy(&self, strategy: Box<dyn ErrorRecoveryStrategy>) {
        let mut strategies = self.recovery_strategies.write().unwrap();
        strategies.push(strategy);
        self.logger.log_info(
            "strategy_registered",
            &generate_trace_id(),
            &format!("Registered recovery strategy: {}", strategies.last().unwrap().get_name()),
        );
    }

    pub fn try_recover(&self, error: RustErrorWithTraceId) -> Result<RecoveryResult> {
        let trace_id = generate_trace_id();
        
        // Record error in history
        {
            let mut history = self.error_history.write().unwrap();
            history.push(error.clone());
            if history.len() > 100 {
                history.remove(0);
            }
        }

        // Try to recover using registered strategies
        let strategies = self.recovery_strategies.read().unwrap();
        for strategy in strategies.iter() {
            if strategy.can_recover(&error.error) {
                self.logger.log_info(
                    "attempting_recovery",
                    &trace_id,
                    &format!("Attempting recovery with strategy: {} for error: {}", strategy.get_name(), error.error),
                );

                match strategy.recover(&error) {
                    Ok(result) => {
                        self.logger.log_info(
                            "recovery_successful",
                            &trace_id,
                            &format!("Recovery successful with strategy: {} - {}", strategy.get_name(), result.details),
                        );
                        return Ok(result);
                    }
                    Err(e) => {
                        self.logger.log_warning(
                            "recovery_failed",
                            &trace_id,
                            &format!("Recovery failed with strategy: {} - {}", strategy.get_name(), e),
                        );
                    }
                }
            }
        }

        // No recovery strategy worked
        self.logger.log_error(
            "recovery_impossible",
            &trace_id,
            &format!("No recovery strategy found for error: {}", error.error),
        );

        Ok(RecoveryResult {
            success: false,
            strategy_name: "None".to_string(),
            error_type: format!("{}", error.error),
            details: "No recovery strategy available".to_string(),
            trace_id: error.trace_id,
        })
    }

    pub fn get_error_history(&self) -> Vec<RustErrorWithTraceId> {
        self.error_history.read().unwrap().clone()
    }

    pub fn clear_error_history(&self) {
        let mut history = self.error_history.write().unwrap();
        history.clear();
        self.logger.log_info(
            "error_history_cleared",
            &generate_trace_id(),
            "Error history cleared",
        );
    }
}

// ==============================
// Built-in Recovery Strategies
// ==============================
pub struct RetryStrategy {
    max_retries: usize,
    retry_delay_ms: u64,
    name: String,
}

impl RetryStrategy {
    pub fn new(max_retries: usize, retry_delay_ms: u64, name: &str) -> Self {
        Self {
            max_retries,
            retry_delay_ms,
            name: name.to_string(),
        }
    }
}

impl ErrorRecoveryStrategy for RetryStrategy {
    fn can_recover(&self, error: &RustError) -> bool {
        matches!(
            error,
            RustError::NetworkOperationFailed(_)
                | RustError::IoError(_)
                | RustError::JniOperationFailed(_)
        )
    }

    fn recover(&self, error: &RustErrorWithTraceId) -> Result<RecoveryResult> {
        let trace_id = generate_trace_id();
        let error_type = format!("{}", error.error);

        for attempt in 1..=self.max_retries {
            self.logger.log_info(
                "retry_attempt",
                &trace_id,
                &format!("Retry attempt {} for error: {} (Trace ID: {})", attempt, error_type, error.trace_id),
            );

            std::thread::sleep(std::time::Duration::from_millis(self.retry_delay_ms));

            // In a real implementation, you would actually retry the operation here
            // For this example, we'll simulate success on the last attempt
            if attempt == self.max_retries {
                self.logger.log_info(
                    "retry_successful",
                    &trace_id,
                    &format!("Retry successful on attempt {} for error: {} (Trace ID: {})", attempt, error_type, error.trace_id),
                );
                return Ok(RecoveryResult {
                    success: true,
                    strategy_name: self.name.clone(),
                    error_type,
                    details: format!("Operation succeeded on retry attempt {}", attempt),
                    trace_id: error.trace_id.clone(),
                });
            }
        }

        Err(RustError::new_recovery(&format!(
            "All {} retry attempts failed for error: {}",
            self.max_retries, error_type
        )).with_trace_id())
    }

    fn get_name(&self) -> &str {
        &self.name
    }
}

pub struct FallbackStrategy {
    fallback_value: String,
    name: String,
}

impl FallbackStrategy {
    pub fn new(fallback_value: &str, name: &str) -> Self {
        Self {
            fallback_value: fallback_value.to_string(),
            name: name.to_string(),
        }
    }
}

impl ErrorRecoveryStrategy for FallbackStrategy {
    fn can_recover(&self, error: &RustError) -> bool {
        matches!(error, RustError::ConfigurationError(_) | RustError::ValidationError(_))
    }

    fn recover(&self, error: &RustErrorWithTraceId) -> Result<RecoveryResult> {
        let trace_id = generate_trace_id();
        let error_type = format!("{}", error.error);

        self.logger.log_info(
            "fallback_applied",
            &trace_id,
            &format!("Applying fallback strategy for error: {} (Trace ID: {})", error_type, error.trace_id),
        );

        Ok(RecoveryResult {
            success: true,
            strategy_name: self.name.clone(),
            error_type,
            details: format!("Using fallback value: {}", self.fallback_value),
            trace_id: error.trace_id.clone(),
        })
    }

    fn get_name(&self) -> &str {
        &self.name
    }
}

pub struct CircuitBreakerStrategy {
    failure_threshold: usize,
    reset_timeout_ms: u64,
    failure_count: Arc<std::sync::Mutex<usize>>,
    last_failure_time: Arc<std::sync::Mutex<std::time::Instant>>,
    name: String,
}

impl CircuitBreakerStrategy {
    pub fn new(failure_threshold: usize, reset_timeout_ms: u64, name: &str) -> Self {
        Self {
            failure_threshold,
            reset_timeout_ms,
            failure_count: Arc::new(std::sync::Mutex::new(0)),
            last_failure_time: Arc::new(std::sync::Mutex::new(std::time::Instant::now())),
            name: name.to_string(),
        }
    }
}

impl ErrorRecoveryStrategy for CircuitBreakerStrategy {
    fn can_recover(&self, error: &RustError) -> bool {
        matches!(
            error,
            RustError::NetworkOperationFailed(_)
                | RustError::JniOperationFailed(_)
                | RustError::PerformanceError(_)
        )
    }

    fn recover(&self, error: &RustErrorWithTraceId) -> Result<RecoveryResult> {
        let trace_id = generate_trace_id();
        let error_type = format!("{}", error.error);

        // Check if we should reset the circuit breaker
        let mut last_failure_time = self.last_failure_time.lock().unwrap();
        let now = std::time::Instant::now();
        
        if now.duration_since(*last_failure_time).as_millis() > self.reset_timeout_ms as u128 {
            *self.failure_count.lock().unwrap() = 0;
            *last_failure_time = now;
            self.logger.log_info(
                "circuit_breaker_reset",
                &trace_id,
                &format!("Circuit breaker reset for error type: {}", error_type),
            );
        }

        // Record failure
        let mut failure_count = self.failure_count.lock().unwrap();
        *failure_count += 1;

        // Check if we've exceeded the failure threshold
        if *failure_count >= self.failure_threshold {
            *last_failure_time = now;
            self.logger.log_error(
                "circuit_breaker_open",
                &trace_id,
                &format!(
                    "Circuit breaker opened for error type: {} (failure count: {}/{}), resetting in {}ms",
                    error_type, *failure_count, self.failure_threshold, self.reset_timeout_ms
                ),
            );
            
            return Ok(RecoveryResult {
                success: false,
                strategy_name: self.name.clone(),
                error_type,
                details: format!("Circuit breaker opened - too many failures ({}/{}), reset in {}ms", *failure_count, self.failure_threshold, self.reset_timeout_ms),
                trace_id: error.trace_id.clone(),
            });
        }

        // Allow the operation to proceed (half-open state)
        self.logger.log_info(
            "circuit_breaker_half_open",
            &trace_id,
            &format!("Circuit breaker half-open - allowing operation: {} (failure count: {}/{})", error_type, *failure_count, self.failure_threshold),
        );

        Ok(RecoveryResult {
            success: true,
            strategy_name: self.name.clone(),
            error_type,
            details: format!("Circuit breaker half-open - allowing operation (failure count: {}/{})", *failure_count, self.failure_threshold),
            trace_id: error.trace_id.clone(),
        })
    }

    fn get_name(&self) -> &str {
        &self.name
    }
}

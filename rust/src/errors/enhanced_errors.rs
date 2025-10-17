use crate::errors::base_errors::{RustError, Result};
use crate::logging::{generate_trace_id, LogEntry, LogSeverity};
use std::error::Error;
use std::fmt;
use std::sync::Arc;

/// Enhanced error type with comprehensive context and trace ID support
#[derive(Debug, Clone)]
pub struct EnhancedError {
    pub base_error: RustError,
    pub trace_id: String,
    pub context: Vec<(String, String)>,
    pub timestamp: u64,
    pub cause: Option<String>,
}

impl EnhancedError {
    /// Create a new EnhancedError from a base RustError
    pub fn new(base_error: RustError) -> Self {
        Self {
            base_error,
            trace_id: generate_trace_id(),
            context: Vec::new(),
            timestamp: std::time::SystemTime::now()
                .duration_since(std::time::SystemTime::UNIX_EPOCH)
                .map(|d| d.as_millis() as u64)
                .unwrap_or(0),
            cause: None,
        }
    }

    /// Add a trace ID to the error
    pub fn with_trace_id(mut self, trace_id: String) -> Self {
        self.trace_id = trace_id;
        self
    }

    /// Add context to the error
    pub fn with_context(mut self, key: &str, value: &str) -> Self {
        self.context.push((key.to_string(), value.to_string()));
        self
    }

    /// Chain an underlying cause error
    pub fn with_cause(mut self, cause: Box<dyn Error + Send + Sync>) -> Self {
        // Store the string representation of the cause to avoid cloning boxed trait objects
        self.cause = Some(cause.to_string());
        self
    }

    /// Convert to detailed JSON string for logging
    pub fn to_detailed_json(&self) -> String {
        let mut context_map = serde_json::Map::new();
        for (key, value) in &self.context {
            context_map.insert(key.clone(), serde_json::Value::String(value.clone()));
        }

        let error_type = match &self.base_error {
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

        let error_msg = format!("{}", self.base_error);

        let mut json = serde_json::json!({
            "error_type": error_type,
            "error_message": error_msg,
            "trace_id": self.trace_id,
            "timestamp": chrono::Utc::now().format("%Y-%m-%dT%H:%M:%S%.3fZ").to_string(),
            "timestamp_ms": self.timestamp,
            "context": context_map,
        });

        if let Some(cause) = &self.cause {
            json["cause"] = serde_json::Value::String(cause.clone());
        }

        json.to_string()
    }

    /// Log the error with structured logging
    pub fn log_detailed(&self, component: &str, logger: &Arc<crate::logging::PerformanceLogger>) {
        let context_details = self.context
            .iter()
            .map(|(k, v)| format!("{}: {}", k, v))
            .collect::<Vec<_>>()
            .join(", ");

        let log_entry = LogEntry::new(
            LogSeverity::Error,
            component,
            "error_occurred",
            &format!("{}", self.base_error)
        )
        .with_trace_id(self.trace_id.clone())
        .with_error_code("ENHANCED_ERROR".to_string())
        .with_context("context", serde_json::Value::String(context_details));

        if let Some(cause) = &self.cause {
            let mut log_entry = log_entry.with_context("cause", serde_json::Value::String(cause.to_string()));
            log_entry.log();
            logger.log_entry(log_entry);
        } else {
            log_entry.log();
            logger.log_entry(log_entry);
        }
    }
}

impl fmt::Display for EnhancedError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let mut result = write!(
            f,
            "[{}] {} (Trace ID: {})",
            chrono::Utc::now().format("%Y-%m-%d %H:%M:%S"),
            self.base_error,
            self.trace_id
        );

        if !self.context.is_empty() {
            let context_str = self.context
                .iter()
                .map(|(k, v)| format!("{}={}", k, v))
                .collect::<Vec<_>>()
                .join(", ");
            result = result.and_then(|_| write!(f, " | Context: [{}]", context_str));
        }

        if let Some(cause) = &self.cause {
            result = result.and_then(|_| write!(f, " | Cause: {}", cause));
        }

        result
    }
}

impl Error for EnhancedError {
    fn source(&self) -> Option<&(dyn Error + 'static)> {
        // We store the cause as a String to avoid cloning trait objects, so there's no underlying
        // error trait object to return here.
        None
    }
}

/// Convert RustError to EnhancedError with trace ID
pub trait ToEnhancedError {
    fn to_enhanced(self) -> EnhancedError;
    fn to_enhanced_with_trace(self, trace_id: String) -> EnhancedError;
}

impl ToEnhancedError for RustError {
    fn to_enhanced(self) -> EnhancedError {
        EnhancedError::new(self)
    }

    fn to_enhanced_with_trace(self, trace_id: String) -> EnhancedError {
        EnhancedError::new(self).with_trace_id(trace_id)
    }
}

/// Result type alias using EnhancedError
pub type EnhancedResult<T> = std::result::Result<T, EnhancedError>;

/// Error with trace ID
#[derive(Debug, Clone)]
pub struct RustErrorWithTraceId {
    pub error: RustError,
    pub trace_id: String,
}

/// Error recovery manager
pub struct ErrorRecoveryManager {
    pub strategies: Vec<ErrorRecoveryStrategy>,
}

/// Error recovery strategy
#[derive(Debug, Clone)]
pub enum ErrorRecoveryStrategy {
    Retry(RetryStrategy),
    Fallback(FallbackStrategy),
    CircuitBreaker(CircuitBreakerStrategy),
}

/// Recovery result
pub type RecoveryResult<T> = std::result::Result<T, RecoveryError>;

/// Recovery error
#[derive(Debug, Clone)]
pub struct RecoveryError {
    pub original_error: RustError,
    pub recovery_attempts: usize,
}

/// Retry strategy
#[derive(Debug, Clone)]
pub struct RetryStrategy {
    pub max_attempts: usize,
    pub backoff_multiplier: f64,
    pub initial_delay_ms: u64,
}

/// Fallback strategy
pub struct FallbackStrategy {
    pub fallback_function: Box<dyn Fn() -> Result<()> + Send + Sync + 'static>,
}

/// Circuit breaker strategy
#[derive(Debug, Clone)]
pub struct CircuitBreakerStrategy {
    pub failure_threshold: usize,
    pub recovery_timeout_ms: u64,
    pub half_open_requests: usize,
}

/// Recovery strategy (generic trait for recovery strategies)
pub trait RecoveryStrategy: Send + Sync {
    fn recover(&self, error: &RustError) -> RecoveryResult<()>;
}
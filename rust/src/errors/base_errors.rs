use std::error::Error;
use std::fmt;

/// Base error type for the rustperf library
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

impl fmt::Display for RustError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            RustError::ConfigurationError(msg) => write!(f, "Configuration Error: {}", msg),
            RustError::PerformanceError(msg) => write!(f, "Performance Error: {}", msg),
            RustError::ThreadSafeOperationFailed(msg) => write!(f, "Thread-safe Operation Failed: {}", msg),
            RustError::JniOperationFailed(msg) => write!(f, "JNI Operation Failed: {}", msg),
            RustError::MemoryOperationFailed(msg) => write!(f, "Memory Operation Failed: {}", msg),
            RustError::NetworkOperationFailed(msg) => write!(f, "Network Operation Failed: {}", msg),
            RustError::IoError(msg) => write!(f, "IO Error: {}", msg),
            RustError::ValidationError(msg) => write!(f, "Validation Error: {}", msg),
            RustError::RecoveryError(msg) => write!(f, "Recovery Error: {}", msg),
            RustError::OptimizationError(msg) => write!(f, "Optimization Error: {}", msg),
            RustError::CustomError(msg) => write!(f, "Custom Error: {}", msg),
        }
    }
}

impl Error for RustError {}

/// Result type alias using RustError
pub type Result<T> = std::result::Result<T, RustError>;

/// Error messages module
pub mod messages {
    pub const INVALID_CONFIGURATION: &str = "Invalid configuration provided";
    pub const PERFORMANCE_THRESHOLD_EXCEEDED: &str = "Performance threshold exceeded";
    pub const THREAD_SAFE_OPERATION_FAILED: &str = "Thread-safe operation failed";
    pub const JNI_OPERATION_FAILED: &str = "JNI operation failed";
    pub const MEMORY_OPERATION_FAILED: &str = "Memory operation failed";
    pub const NETWORK_OPERATION_FAILED: &str = "Network operation failed";
    pub const IO_OPERATION_FAILED: &str = "IO operation failed";
    pub const VALIDATION_FAILED: &str = "Validation failed";
    pub const RECOVERY_FAILED: &str = "Recovery operation failed";
    pub const OPTIMIZATION_FAILED: &str = "Optimization failed";
    pub const CUSTOM_ERROR: &str = "Custom error occurred";
}
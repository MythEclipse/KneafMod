pub mod base_errors;
pub mod enhanced_errors;

// Re-export base error types first
pub use base_errors::{RustError, Result, messages, ExecutionError};

// Re-export enhanced error types
pub use enhanced_errors::{EnhancedError, EnhancedResult, ToEnhancedError, RustErrorWithTraceId, ErrorRecoveryManager, ErrorRecoveryStrategy, RecoveryResult, RecoveryStrategy, RetryStrategy, FallbackStrategy, CircuitBreakerStrategy};
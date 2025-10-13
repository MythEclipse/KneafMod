use chrono::{DateTime, Utc};
use log::{Level, Log, Metadata, Record};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Mutex;

// JNI-specific imports for Java logging
use jni::objects::{JObject, JValue};
use jni::sys::jstring;
use jni::JNIEnv;

static TRACE_ID_COUNTER: AtomicU64 = AtomicU64::new(1);

/// Generate a unique trace ID for request tracking
pub fn generate_trace_id() -> String {
    let id = TRACE_ID_COUNTER.fetch_add(1, Ordering::Relaxed);
    format!("{:016x}", id)
}

/// Structured log entry with context
#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct LogEntry {
    pub timestamp: DateTime<Utc>,
    pub level: String,
    pub trace_id: String,
    pub component: String,
    pub operation: String,
    pub message: String,
    pub error_code: Option<String>,
    pub context: HashMap<String, serde_json::Value>,
    pub duration_ms: Option<u64>,
    pub thread_id: String,
    pub process_id: u32,
}

impl LogEntry {
    pub fn new(level: &str, component: &str, operation: &str, message: &str) -> Self {
        Self {
            timestamp: Utc::now(),
            level: level.to_string(),
            trace_id: generate_trace_id(),
            component: component.to_string(),
            operation: operation.to_string(),
            message: message.to_string(),
            error_code: None,
            context: HashMap::new(),
            duration_ms: None,
            thread_id: format!("{:?}", std::thread::current().id()),
            process_id: std::process::id(),
        }
    }

    pub fn with_trace_id(mut self, trace_id: String) -> Self {
        self.trace_id = trace_id;
        self
    }

    pub fn with_error_code(mut self, error_code: String) -> Self {
        self.error_code = Some(error_code);
        self
    }

    pub fn with_context(mut self, key: &str, value: serde_json::Value) -> Self {
        self.context.insert(key.to_string(), value);
        self
    }

    pub fn with_duration(mut self, duration_ms: u64) -> Self {
        self.duration_ms = Some(duration_ms);
        self
    }

    pub fn log(self) {
        let json = serde_json::to_string(&self)
            .unwrap_or_else(|_| "Failed to serialize log entry".to_string());
        match self.level.as_str() {
            "ERROR" => log::error!("{}", json),
            "WARN" => log::warn!("{}", json),
            "INFO" => log::info!("{}", json),
            "DEBUG" => log::debug!("{}", json),
            "TRACE" => log::trace!("{}", json),
            _ => log::info!("{}", json),
        }
    }
}

/// Performance metrics logger
#[derive(Debug, Clone)]
pub struct PerformanceLogger {
    component: String,
}

impl PerformanceLogger {
    pub fn new(component: &str) -> Self {
        Self {
            component: component.to_string(),
        }
    }

    pub fn log_operation<F, R>(&self, operation: &str, trace_id: &str, f: F) -> R
    where
        F: FnOnce() -> R,
    {
        let start = std::time::Instant::now();
        let result = f();
        let duration = start.elapsed().as_millis() as u64;

        LogEntry::new("INFO", &self.component, operation, "Operation completed")
            .with_trace_id(trace_id.to_string())
            .with_duration(duration)
            .log();

        result
    }

    pub fn log_error(&self, operation: &str, trace_id: &str, error: &str, error_code: &str) {
        LogEntry::new("ERROR", &self.component, operation, error)
            .with_trace_id(trace_id.to_string())
            .with_error_code(error_code.to_string())
            .log();
    }

    pub fn log_warning(&self, operation: &str, trace_id: &str, message: &str) {
        LogEntry::new("WARN", &self.component, operation, message)
            .with_trace_id(trace_id.to_string())
            .log();
    }

    // Backwards-compatibility alias used in some modules
    pub fn log_warn(&self, operation: &str, trace_id: &str, message: &str) {
        self.log_warning(operation, trace_id, message);
    }

    pub fn log_info(&self, operation: &str, trace_id: &str, message: &str) {
        LogEntry::new("INFO", &self.component, operation, message)
            .with_trace_id(trace_id.to_string())
            .log();
    }

    pub fn log_debug(&self, operation: &str, trace_id: &str, message: &str) {
        LogEntry::new("DEBUG", &self.component, operation, message)
            .with_trace_id(trace_id.to_string())
            .log();
    }
}

/// Error types with context
#[derive(Debug, Clone)]
pub enum ProcessingError {
    SerializationError {
        message: String,
        trace_id: String,
    },
    DeserializationError {
        message: String,
        trace_id: String,
    },
    ValidationError {
        field: String,
        message: String,
        trace_id: String,
    },
    ResourceExhaustionError {
        resource: String,
        trace_id: String,
    },
    TimeoutError {
        operation: String,
        timeout_ms: u64,
        trace_id: String,
    },
    InternalError {
        message: String,
        trace_id: String,
    },
}

impl ProcessingError {
    pub fn trace_id(&self) -> &str {
        match self {
            ProcessingError::SerializationError { trace_id, .. } => trace_id,
            ProcessingError::DeserializationError { trace_id, .. } => trace_id,
            ProcessingError::ValidationError { trace_id, .. } => trace_id,
            ProcessingError::ResourceExhaustionError { trace_id, .. } => trace_id,
            ProcessingError::TimeoutError { trace_id, .. } => trace_id,
            ProcessingError::InternalError { trace_id, .. } => trace_id,
        }
    }

    pub fn error_code(&self) -> &str {
        match self {
            ProcessingError::SerializationError { .. } => "SERIALIZATION_ERROR",
            ProcessingError::DeserializationError { .. } => "DESERIALIZATION_ERROR",
            ProcessingError::ValidationError { .. } => "VALIDATION_ERROR",
            ProcessingError::ResourceExhaustionError { .. } => "RESOURCE_EXHAUSTION",
            ProcessingError::TimeoutError { .. } => "TIMEOUT_ERROR",
            ProcessingError::InternalError { .. } => "INTERNAL_ERROR",
        }
    }

    pub fn log(&self, component: &str, operation: &str) {
        let message = match self {
            ProcessingError::SerializationError { message, .. } => message.clone(),
            ProcessingError::DeserializationError { message, .. } => message.clone(),
            ProcessingError::ValidationError { field, message, .. } => {
                format!("Validation failed for field '{}': {}", field, message)
            }
            ProcessingError::ResourceExhaustionError { resource, .. } => {
                format!("Resource exhausted: {}", resource)
            }
            ProcessingError::TimeoutError {
                operation: op,
                timeout_ms,
                ..
            } => format!("Operation '{}' timed out after {}ms", op, timeout_ms),
            ProcessingError::InternalError { message, .. } => message.clone(),
        };

        LogEntry::new("ERROR", component, operation, &message)
            .with_trace_id(self.trace_id().to_string())
            .with_error_code(self.error_code().to_string())
            .log();
    }
}

impl std::fmt::Display for ProcessingError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ProcessingError::SerializationError { message, .. } => {
                write!(f, "Serialization error: {}", message)
            }
            ProcessingError::DeserializationError { message, .. } => {
                write!(f, "Deserialization error: {}", message)
            }
            ProcessingError::ValidationError { field, message, .. } => {
                write!(f, "Validation error for field '{}': {}", field, message)
            }
            ProcessingError::ResourceExhaustionError { resource, .. } => {
                write!(f, "Resource exhaustion: {}", resource)
            }
            ProcessingError::TimeoutError {
                operation,
                timeout_ms,
                ..
            } => write!(
                f,
                "Timeout in operation '{}' after {}ms",
                operation, timeout_ms
            ),
            ProcessingError::InternalError { message, .. } => {
                write!(f, "Internal error: {}", message)
            }
        }
    }
}

impl std::error::Error for ProcessingError {}

/// Result type alias for processing operations
pub type ProcessingResult<T> = Result<T, ProcessingError>;

/// Structured logger implementation
pub struct StructuredLogger {
    component: String,
    min_level: Level,
}

impl StructuredLogger {
    pub fn new(component: &str, min_level: Level) -> Self {
        Self {
            component: component.to_string(),
            min_level,
        }
    }
}

impl Log for StructuredLogger {
    fn enabled(&self, metadata: &Metadata) -> bool {
        metadata.level() <= self.min_level
    }

    fn log(&self, record: &Record) {
        if !self.enabled(record.metadata()) {
            return;
        }

        let mut entry = LogEntry::new(
            &record.level().to_string(),
            &self.component,
            "system",
            &record.args().to_string(),
        );

        // Add file and line information if available
        if let Some(file) = record.file() {
            entry = entry.with_context("file", serde_json::Value::String(file.to_string()));
        }
        if let Some(line) = record.line() {
            entry = entry.with_context("line", serde_json::Value::Number(line.into()));
        }

        entry.log();
    }

    fn flush(&self) {}
}

/// Global logger registry
pub struct LoggerRegistry {
    loggers: Mutex<HashMap<String, Box<dyn Log + Send + Sync>>>,
}

impl LoggerRegistry {
    pub fn new() -> Self {
        Self {
            loggers: Mutex::new(HashMap::new()),
        }
    }

    pub fn register_logger(&self, name: &str, logger: Box<dyn Log + Send + Sync>) {
        let mut loggers = self.loggers.lock().unwrap();
        loggers.insert(name.to_string(), logger);
    }

    pub fn get_logger(
        &self,
        name: &str,
    ) -> Option<std::sync::MutexGuard<'_, Box<dyn Log + Send + Sync>>> {
        let loggers = self.loggers.lock().unwrap();
        if loggers.contains_key(name) {
            // This is a simplified implementation - in practice, you'd need to handle the lifetime properly
            None
        } else {
            None
        }
    }
}

lazy_static::lazy_static! {
    pub static ref GLOBAL_LOGGER_REGISTRY: LoggerRegistry = LoggerRegistry::new();
}

/// Initialize logging system with Java integration
pub fn init_logging() -> Result<(), Box<dyn std::error::Error>> {
    let logger = StructuredLogger::new("rustperf", Level::Info);
    log::set_boxed_logger(Box::new(logger))?;
    log::set_max_level(log::LevelFilter::Info);
    Ok(())
}

/// Initialize logging with Java JNI environment
pub fn init_logging_with_jni(env: &mut JNIEnv) -> Result<(), Box<dyn std::error::Error>> {
    init_logging()?;
    JniLogger::init_java_logging(env)
        .map_err(|e| Box::new(std::io::Error::new(std::io::ErrorKind::Other, e)))?;
    Ok(())
}

/// Global flag to track if Java logging is initialized
static JAVA_LOGGING_INITIALIZED: AtomicBool = AtomicBool::new(false);

/// JNI Logger for forwarding logs from Rust to Java
#[allow(dead_code)]
pub struct JniLogger {
    component: String,
}

impl JniLogger {
    pub fn new(component: &str) -> Self {
        Self {
            component: component.to_string(),
        }
    }

    /// Log a message to Java via JNI with prefix formatting
    pub fn log_to_java(&self, env: &mut JNIEnv, level: &str, message: &str) {
        // Send message to Java as-is; Java side applies its own prefixing.
        let formatted_message = message.to_string();

        if let Ok(cls) = env.find_class("com/kneaf/core/performance/RustPerformance") {
            if let (Ok(jlevel), Ok(jmsg)) =
                (env.new_string(level), env.new_string(&formatted_message))
            {
                let jlevel_obj = JObject::from(jlevel);
                let jmsg_obj = JObject::from(jmsg);
                let _ = env.call_static_method(
                    cls,
                    "logFromNative",
                    "(Ljava/lang/String;Ljava/lang/String;)V",
                    &[JValue::Object(&jlevel_obj), JValue::Object(&jmsg_obj)],
                );
            }
        }
    }

    /// Initialize Java logging system
    pub fn init_java_logging(env: &mut JNIEnv) -> Result<(), String> {
        if JAVA_LOGGING_INITIALIZED.load(Ordering::Relaxed) {
            return Ok(());
        }

        if let Ok(cls) = env.find_class("com/kneaf/core/performance/RustPerformance") {
            let _ = env
                .call_static_method(cls, "initNativeLogging", "()V", &[])
                .map_err(|e| format!("Failed to initialize Java logging: {}", e))?;

            JAVA_LOGGING_INITIALIZED.store(true, Ordering::Relaxed);
            Ok(())
        } else {
            Err("Failed to find RustPerformance class for logging initialization".to_string())
        }
    }

    /// Log with context information including component prefix
    pub fn log_with_context(&self, env: &mut JNIEnv, level: &str, context: &str, message: &str) {
        let full_message = format!("[{}] {}", context, message);
        self.log_to_java(env, level, &full_message);
    }

    /// Log debug message
    pub fn debug(&self, env: &mut JNIEnv, message: &str) {
        self.log_to_java(env, "DEBUG", message);
    }

    /// Log info message
    pub fn info(&self, env: &mut JNIEnv, message: &str) {
        self.log_to_java(env, "INFO", message);
    }

    /// Log warning message
    pub fn warn(&self, env: &mut JNIEnv, message: &str) {
        self.log_to_java(env, "WARN", message);
    }

    /// Log error message
    pub fn error(&self, env: &mut JNIEnv, message: &str) {
        self.log_to_java(env, "ERROR", message);
    }

    /// Log trace message
    pub fn trace(&self, env: &mut JNIEnv, message: &str) {
        self.log_to_java(env, "TRACE", message);
    }

    /// Debug with context prefix
    pub fn debug_ctx(&self, env: &mut JNIEnv, context: &str, message: &str) {
        self.log_with_context(env, "DEBUG", context, message);
    }

    /// Info with context prefix
    pub fn info_ctx(&self, env: &mut JNIEnv, context: &str, message: &str) {
        self.log_with_context(env, "INFO", context, message);
    }

    /// Warning with context prefix
    pub fn warn_ctx(&self, env: &mut JNIEnv, context: &str, message: &str) {
        self.log_with_context(env, "WARN", context, message);
    }

    /// Error with context prefix
    pub fn error_ctx(&self, env: &mut JNIEnv, context: &str, message: &str) {
        self.log_with_context(env, "ERROR", context, message);
    }

    /// Trace with context prefix
    pub fn trace_ctx(&self, env: &mut JNIEnv, context: &str, message: &str) {
        self.log_with_context(env, "TRACE", context, message);
    }
}

/// Helper function to create error response strings for JNI
pub fn make_jni_error(env: &JNIEnv, message: &str) -> jstring {
    match env.new_string(message) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Helper function to create error response byte arrays for JNI
pub fn make_jni_error_bytes(env: &JNIEnv, message: &[u8]) -> jni::sys::jbyteArray {
    match env.byte_array_from_slice(message) {
        Ok(arr) => arr.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Enhanced logging macro that sends logs to both Rust logger and Java via JNI
#[macro_export]
macro_rules! log_to_java {
    ($env:expr, $level:expr, $component:expr, $operation:expr, $message:expr) => {{
        let trace_id = $crate::logging::generate_trace_id();
        let formatted_message = format!("[{}] {}: {}", $component, $operation, $message);

        // Log to Rust logger
        $crate::logging::LogEntry::new($level, $component, $operation, &formatted_message)
            .with_trace_id(trace_id.to_string())
            .log();

        // Log to Java via JNI if environment is available
        if let Some(env) = $env {
            let logger = $crate::logging::JniLogger::new($component);
            logger.log_to_java(env, $level, &formatted_message);
        }
    }};
}

/// Convenience macros for logging
#[macro_export]
macro_rules! log_error {
    ($component:expr, $operation:expr, $trace_id:expr, $error:expr) => {
        $crate::logging::LogEntry::new("ERROR", $component, $operation, &$error.to_string())
            .with_trace_id($trace_id.to_string())
            .with_error_code("GENERIC_ERROR".to_string())
            .log();
    };
    ($component:expr, $operation:expr, $trace_id:expr, $message:expr, $error:expr) => {
        $crate::logging::LogEntry::new(
            "ERROR",
            $component,
            $operation,
            &format!("{}: {}", $message, $error.to_string()),
        )
        .with_trace_id($trace_id.to_string())
        .with_error_code("GENERIC_ERROR".to_string())
        .log();
    };
}

/// Error handling macro with automatic logging
#[macro_export]
macro_rules! handle_error {
    ($component:expr, $operation:expr, $result:expr) => {
        match $result {
            Ok(value) => value,
            Err(e) => {
                let trace_id = $crate::logging::generate_trace_id();
                $crate::logging::log_error!($component, $operation, &trace_id, &e);
                return Err(e);
            }
        }
    };
}

/// Error handling macro with custom error code
#[macro_export]
macro_rules! handle_error_with_code {
    ($component:expr, $operation:expr, $error_code:expr, $result:expr) => {
        match $result {
            Ok(value) => value,
            Err(e) => {
                let trace_id = $crate::logging::generate_trace_id();
                let mut entry =
                    $crate::logging::LogEntry::new("ERROR", $component, $operation, &e.to_string())
                        .with_trace_id(trace_id.to_string());
                if !$error_code.is_empty() {
                    entry = entry.with_error_code($error_code);
                }
                entry.log();
                return Err(e);
            }
        }
    };
}

/// Result handling macro that returns a default value on error
#[macro_export]
macro_rules! handle_result_or_default {
    ($component:expr, $operation:expr, $result:expr, $default:expr) => {
        match $result {
            Ok(value) => value,
            Err(e) => {
                let trace_id = $crate::logging::generate_trace_id();
                $crate::logging::log_error!($component, $operation, &trace_id, &e);
                $default
            }
        }
    };
}

#[macro_export]
macro_rules! log_info {
    ($component:expr, $operation:expr, $trace_id:expr, $message:expr) => {
        $crate::logging::LogEntry::new("INFO", $component, $operation, $message)
            .with_trace_id($trace_id.to_string())
            .log();
    };
}

#[macro_export]
macro_rules! log_performance {
    ($component:expr, $operation:expr, $trace_id:expr, $duration_ms:expr) => {
        $crate::logging::LogEntry::new("INFO", $component, $operation, "Performance measurement")
            .with_trace_id($trace_id.to_string())
            .with_duration($duration_ms)
            .log();
    };
}

#[macro_export]
macro_rules! with_trace_logging {
    ($component:expr, $operation:expr, $code:block) => {{
        let trace_id = $crate::logging::generate_trace_id();
        let start = std::time::Instant::now();
        let result = $code;
        let duration = start.elapsed().as_millis() as u64;

        $crate::logging::log_performance!($component, $operation, &trace_id, duration);
        result
    }};
}

/// JNI-specific logging macros
#[macro_export]
macro_rules! jni_log_debug {
    ($logger:expr, $env:expr, $message:expr) => {
        $logger.debug($env, $message);
    };
    ($logger:expr, $env:expr, $context:expr, $message:expr) => {
        $logger.debug_ctx($env, $context, $message);
    };
}

#[macro_export]
macro_rules! jni_log_info {
    ($logger:expr, $env:expr, $message:expr) => {
        $logger.info($env, $message);
    };
    ($logger:expr, $env:expr, $context:expr, $message:expr) => {
        $logger.info_ctx($env, $context, $message);
    };
}

#[macro_export]
macro_rules! jni_log_warn {
    ($logger:expr, $env:expr, $message:expr) => {
        $logger.warn($env, $message);
    };
    ($logger:expr, $env:expr, $context:expr, $message:expr) => {
        $logger.warn_ctx($env, $context, $message);
    };
}

#[macro_export]
macro_rules! jni_log_error {
    ($logger:expr, $env:expr, $message:expr) => {
        $logger.error($env, $message);
    };
    ($logger:expr, $env:expr, $context:expr, $message:expr) => {
        $logger.error_ctx($env, $context, $message);
    };
}

#[macro_export]
macro_rules! jni_log_trace {
    ($logger:expr, $env:expr, $message:expr) => {
        $logger.trace($env, $message);
    };
    ($logger:expr, $env:expr, $context:expr, $message:expr) => {
        $logger.trace_ctx($env, $context, $message);
    };
}

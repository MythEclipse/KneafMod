use crate::logging::{generate_trace_id, LogEntry, LogSeverity, PerformanceLogger};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::mpsc;
use tokio::task;

/// Async structured logger with severity levels and trace ID integration
#[derive(Debug, Clone)]
pub struct StructuredLogger {
    component: String,
    min_severity: LogSeverity,
    enable_console_logging: bool,
    enable_file_logging: bool,
    log_file_path: Option<String>,
    tx: Option<mpsc::Sender<LogEntry>>,
}

impl StructuredLogger {
    /// Create a new StructuredLogger with default settings
    pub fn new(component: &str) -> Self {
        Self {
            component: component.to_string(),
            min_severity: LogSeverity::Info,
            enable_console_logging: true,
            enable_file_logging: false,
            log_file_path: None,
            tx: None,
        }
    }

    /// Set minimum severity level for logging
    pub fn with_min_severity(mut self, severity: LogSeverity) -> Self {
        self.min_severity = severity;
        self
    }

    /// Enable/disable console logging
    pub fn with_console_logging(mut self, enable: bool) -> Self {
        self.enable_console_logging = enable;
        self
    }

    /// Enable file logging with specified path
    pub fn with_file_logging(mut self, log_file_path: &str) -> Self {
        self.enable_file_logging = true;
        self.log_file_path = Some(log_file_path.to_string());
        self
    }

    /// Initialize async logging system
    pub fn init_async(self) -> Result<Self, String> {
        if self.enable_file_logging {
            let (tx, mut rx) = mpsc::channel(100);
            
            // Start background task to process log entries
            task::spawn(async move {
                while let Some(entry) = rx.recv().await {
                    entry.log();
                    
                    if let Some(file_path) = &self.log_file_path {
                        if let Err(e) = Self::write_to_file(file_path, &entry) {
                            eprintln!("[ERROR] Failed to write to log file: {}", e);
                        }
                    }
                }
            });

            Ok(Self {
                component: self.component,
                min_severity: self.min_severity,
                enable_console_logging: self.enable_console_logging,
                enable_file_logging: self.enable_file_logging,
                log_file_path: self.log_file_path,
                tx: Some(tx),
            })
        } else {
            Ok(self)
        }
    }

    /// Check if a log entry should be processed based on severity
    fn should_log(&self, severity: LogSeverity) -> bool {
        severity as u8 >= self.min_severity as u8
    }

    /// Write log entry to file
    fn write_to_file(file_path: &str, entry: &LogEntry) -> Result<(), String> {
        let mut file = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(file_path)
            .map_err(|e| format!("Failed to open log file {}: {}", file_path, e))?;

        let output = format!("{}\n", entry.to_json());
        file.write_all(output.as_bytes())
            .map_err(|e| format!("Failed to write to log file {}: {}", file_path, e))?;

        Ok(())
    }

    /// Log with specific severity level
    pub fn log_with_severity(&self, severity: LogSeverity, operation: &str, message: &str) {
        if !self.should_log(severity) {
            return;
        }

        let trace_id = generate_trace_id();
        let entry = LogEntry::new(severity, &self.component, operation, message)
            .with_trace_id(trace_id);

        if let Some(tx) = &self.tx {
            // Async logging
            let _ = tx.try_send(entry);
        } else {
            // Sync logging
            entry.log();
            
            if self.enable_file_logging {
                if let Some(file_path) = &self.log_file_path {
                    let _ = Self::write_to_file(file_path, &entry);
                }
            }
        }
    }

    /// Log a trace message
    pub fn trace(&self, operation: &str, message: &str) {
        self.log_with_severity(LogSeverity::Trace, operation, message);
    }

    /// Log a debug message
    pub fn debug(&self, operation: &str, message: &str) {
        self.log_with_severity(LogSeverity::Debug, operation, message);
    }

    /// Log an info message
    pub fn info(&self, operation: &str, message: &str) {
        self.log_with_severity(LogSeverity::Info, operation, message);
    }

    /// Log a warning message
    pub fn warn(&self, operation: &str, message: &str) {
        self.log_with_severity(LogSeverity::Warn, operation, message);
    }

    /// Log an error message
    pub fn error(&self, operation: &str, message: &str) {
        self.log_with_severity(LogSeverity::Error, operation, message);
    }

    /// Log a critical message
    pub fn critical(&self, operation: &str, message: &str) {
        self.log_with_severity(LogSeverity::Critical, operation, message);
    }

    /// Log with additional context
    pub fn log_with_context(&self, severity: LogSeverity, operation: &str, message: &str, context: &HashMap<String, String>) {
        if !self.should_log(severity) {
            return;
        }

        let trace_id = generate_trace_id();
        let mut entry = LogEntry::new(severity, &self.component, operation, message)
            .with_trace_id(trace_id);

        for (key, value) in context {
            entry = entry.with_context(key, serde_json::Value::String(value.clone()));
        }

        if let Some(tx) = &self.tx {
            let _ = tx.try_send(entry);
        } else {
            entry.log();
            
            if self.enable_file_logging {
                if let Some(file_path) = &self.log_file_path {
                    let _ = Self::write_to_file(file_path, &entry);
                }
            }
        }
    }

    /// Create a child logger with additional component prefix
    pub fn child(&self, child_component: &str) -> Self {
        let mut child = self.clone();
        child.component = format!("{}.{}", self.component, child_component);
        child
    }
}

/// Global async logger manager
#[derive(Debug, Clone)]
pub struct AsyncLoggerManager {
    loggers: Arc<std::sync::RwLock<HashMap<String, StructuredLogger>>>,
}

impl AsyncLoggerManager {
    /// Create a new AsyncLoggerManager
    pub fn new() -> Self {
        Self {
            loggers: Arc::new(std::sync::RwLock::new(HashMap::new())),
        }
    }

    /// Register a logger with a name
    pub fn register(&self, name: &str, logger: StructuredLogger) {
        let mut loggers = self.loggers.write().unwrap();
        loggers.insert(name.to_string(), logger);
    }

    /// Get a logger by name
    pub fn get(&self, name: &str) -> Option<StructuredLogger> {
        let loggers = self.loggers.read().unwrap();
        loggers.get(name).cloned()
    }

    /// Get or create a logger
    pub fn get_or_create(&self, name: &str, component: &str) -> StructuredLogger {
        let loggers = self.loggers.read().unwrap();
        
        if let Some(logger) = loggers.get(name) {
            logger.clone()
        } else {
            let logger = StructuredLogger::new(component)
                .with_min_severity(LogSeverity::Info)
                .with_console_logging(true);
            
            let mut loggers = self.loggers.write().unwrap();
            loggers.insert(name.to_string(), logger.clone());
            logger
        }
    }
}

/// Lazy static global logger manager
lazy_static::lazy_static! {
    pub static ref GLOBAL_ASYNC_LOGGER_MANAGER: AsyncLoggerManager = AsyncLoggerManager::new();
}

/// Macro for convenient logging with automatic trace ID
#[macro_export]
macro_rules! slog {
    ($component:expr, $severity:expr, $operation:expr, $message:expr) => {{
        let logger = $crate::logging::structured_logger::GLOBAL_ASYNC_LOGGER_MANAGER
            .get_or_create($component, $component);
        logger.log_with_severity($severity, $operation, $message);
    }};
    ($component:expr, $severity:expr, $operation:expr, $message:expr, $($key:expr => $value:expr),*) => {{
        let mut context = std::collections::HashMap::new();
        $(
            context.insert($key.to_string(), $value.to_string());
        )*
        
        let logger = $crate::logging::structured_logger::GLOBAL_ASYNC_LOGGER_MANAGER
            .get_or_create($component, $component);
        logger.log_with_context($severity, $operation, $message, &context);
    }};
}
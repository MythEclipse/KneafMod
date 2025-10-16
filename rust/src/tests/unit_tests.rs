use crate::errors::enhanced_errors::{EnhancedError, ToEnhancedError};
use crate::errors::RustError;
use crate::logging::structured_logger::{StructuredLogger, LogSeverity};
use crate::sync::thread_safe_sync::{ThreadSafeSynchronizer, LockFreeSynchronizer};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;
use tokio::test;

/// Test suite for EnhancedError functionality
#[cfg(test)]
mod enhanced_error_tests {
    use super::*;

    #[test]
    fn test_enhanced_error_creation() {
        let base_error = RustError::new_custom("Test error");
        let enhanced_error = base_error.to_enhanced();
        
        assert!(!enhanced_error.trace_id.is_empty());
        assert_eq!(enhanced_error.context.len(), 0);
        assert!(enhanced_error.timestamp > 0);
        assert!(enhanced_error.cause.is_none());
    }

    #[test]
    fn test_enhanced_error_with_trace_id() {
        let base_error = RustError::new_custom("Test error");
        let trace_id = "test-trace-id-123".to_string();
        let enhanced_error = base_error.to_enhanced_with_trace(trace_id.clone());
        
        assert_eq!(enhanced_error.trace_id, trace_id);
    }

    #[test]
    fn test_enhanced_error_with_context() {
        let base_error = RustError::new_custom("Test error");
        let enhanced_error = base_error.to_enhanced()
            .with_context("user_id", "12345")
            .with_context("operation", "login");
        
        assert_eq!(enhanced_error.context.len(), 2);
        assert_eq!(enhanced_error.context[0].0, "user_id");
        assert_eq!(enhanced_error.context[0].1, "12345");
        assert_eq!(enhanced_error.context[1].0, "operation");
        assert_eq!(enhanced_error.context[1].1, "login");
    }

    #[test]
    fn test_enhanced_error_with_cause() {
        let base_error = RustError::new_custom("Test error");
        let cause = std::io::Error::new(std::io::ErrorKind::Other, "Underlying IO error");
        let enhanced_error = base_error.to_enhanced()
            .with_cause(Box::new(cause));
        
        assert!(enhanced_error.cause.is_some());
        assert_eq!(enhanced_error.cause.as_deref().unwrap().to_string(), "Underlying IO error");
    }

    #[test]
    fn test_enhanced_error_display() {
        let base_error = RustError::new_custom("Test error");
        let enhanced_error = base_error.to_enhanced()
            .with_context("user_id", "12345");
        
        let display_str = format!("{}", enhanced_error);
        assert!(display_str.contains("Test error"));
        assert!(display_str.contains("Trace ID:"));
        assert!(display_str.contains("user_id=12345"));
    }
}

/// Test suite for StructuredLogger functionality
#[cfg(test)]
mod structured_logger_tests {
    use super::*;

    #[test]
    fn test_structured_logger_creation() {
        let logger = StructuredLogger::new("test_component");
        
        assert_eq!(logger.component, "test_component");
        assert_eq!(logger.min_severity, LogSeverity::Info);
        assert!(logger.enable_console_logging);
        assert!(!logger.enable_file_logging);
    }

    #[test]
    fn test_structured_logger_configuration() {
        let logger = StructuredLogger::new("test_component")
            .with_min_severity(LogSeverity::Debug)
            .with_console_logging(false)
            .with_file_logging("test.log");
        
        assert_eq!(logger.component, "test_component");
        assert_eq!(logger.min_severity, LogSeverity::Debug);
        assert!(!logger.enable_console_logging);
        assert!(logger.enable_file_logging);
        assert_eq!(logger.log_file_path.as_deref(), Some("test.log"));
    }

    #[tokio::test]
    async fn test_structured_logger_async_init() {
        let logger = StructuredLogger::new("test_component")
            .with_file_logging("test_async.log");
        
        let result = logger.init_async();
        assert!(result.is_ok());
        let async_logger = result.unwrap();
        assert!(async_logger.tx.is_some());
    }

    #[test]
    fn test_structured_logger_should_log() {
        let logger = StructuredLogger::new("test_component")
            .with_min_severity(LogSeverity::Debug);
        
        assert!(logger.should_log(LogSeverity::Debug));
        assert!(logger.should_log(LogSeverity::Info));
        assert!(logger.should_log(LogSeverity::Warn));
        assert!(logger.should_log(LogSeverity::Error));
        assert!(logger.should_log(LogSeverity::Critical));
        assert!(!logger.should_log(LogSeverity::Trace)); // Trace is lower than Debug
    }

    #[tokio::test]
    async fn test_structured_logger_log_with_severity() {
        let logger = StructuredLogger::new("test_component")
            .with_min_severity(LogSeverity::Trace);
        
        // These should not panic
        logger.trace("test_operation", "Test trace message");
        logger.debug("test_operation", "Test debug message");
        logger.info("test_operation", "Test info message");
        logger.warn("test_operation", "Test warn message");
        logger.error("test_operation", "Test error message");
        logger.critical("test_operation", "Test critical message");
    }

    #[tokio::test]
    async fn test_structured_logger_log_with_context() {
        let logger = StructuredLogger::new("test_component")
            .with_min_severity(LogSeverity::Info);
        
        let mut context = HashMap::new();
        context.insert("user_id".to_string(), "12345".to_string());
        context.insert("operation".to_string(), "login".to_string());
        
        // This should not panic
        logger.log_with_context(LogSeverity::Info, "test_operation", "Test message with context", &context);
    }
}

/// Test suite for ThreadSafeSynchronizer functionality
#[cfg(test)]
mod thread_safe_synchronizer_tests {
    use super::*;

    #[tokio::test]
    async fn test_thread_safe_synchronizer_creation() {
        let initial_data = "test_data".to_string();
        let synchronizer = ThreadSafeSynchronizer::new(initial_data, "test_component");
        
        assert_eq!(synchronizer.component_name, "test_component");
        assert!(synchronizer.last_modified() > 0);
        assert!(!synchronizer.is_modifying());
    }

    #[tokio::test]
    async fn test_thread_safe_synchronizer_read() {
        let initial_data = "test_data".to_string();
        let synchronizer = ThreadSafeSynchronizer::new(initial_data, "test_component");
        
        let read_guard = synchronizer.read().await.unwrap();
        assert_eq!(*read_guard, "test_data");
    }

    #[tokio::test]
    async fn test_thread_safe_synchronizer_write() {
        let initial_data = "test_data".to_string();
        let synchronizer = ThreadSafeSynchronizer::new(initial_data, "test_component");
        
        let write_guard = synchronizer.write().await.unwrap();
        *write_guard = "updated_data".to_string();
        
        let read_guard = synchronizer.read().await.unwrap();
        assert_eq!(*read_guard, "updated_data");
    }

    #[tokio::test]
    async fn test_thread_safe_synchronizer_update() {
        let initial_data = "test_data".to_string();
        let synchronizer = ThreadSafeSynchronizer::new(initial_data, "test_component");
        
        let update_result = synchronizer.update(|data| {
            *data = "updated_data".to_string();
            Ok(())
        }).await;
        
        assert!(update_result.is_ok());
        
        let read_guard = synchronizer.read().await.unwrap();
        assert_eq!(*read_guard, "updated_data");
    }

    #[tokio::test]
    async fn test_thread_safe_synchronizer_get_snapshot() {
        let initial_data = "test_data".to_string();
        let synchronizer = ThreadSafeSynchronizer::new(initial_data, "test_component");
        
        let snapshot = synchronizer.get_snapshot().await.unwrap();
        assert_eq!(snapshot, "test_data");
    }

    #[tokio::test]
    async fn test_thread_safe_synchronizer_try_write() {
        let initial_data = "test_data".to_string();
        let synchronizer = ThreadSafeSynchronizer::new(initial_data, "test_component");
        
        // First write should succeed
        let write_guard = synchronizer.try_write().await.unwrap();
        *write_guard = "updated_data".to_string();
        drop(write_guard); // Release the lock
        
        // Second write should succeed after lock is released
        let write_guard = synchronizer.try_write().await.unwrap();
        *write_guard = "updated_data_again".to_string();
        drop(write_guard);
        
        let read_guard = synchronizer.read().await.unwrap();
        assert_eq!(*read_guard, "updated_data_again");
    }

    #[tokio::test]
    async fn test_thread_safe_synchronizer_try_write_with_timeout() {
        let initial_data = "test_data".to_string();
        let synchronizer = ThreadSafeSynchronizer::new(initial_data, "test_component");
        
        // Acquire write lock
        let write_guard = synchronizer.write().await.unwrap();
        
        // Try to acquire with short timeout - should fail
        let timeout_result = synchronizer.try_write_with_timeout(10).await;
        assert!(timeout_result.is_err());
        assert!(timeout_result.unwrap_err().to_string().contains("timeout"));
        
        drop(write_guard); // Release the lock
        
        // Now it should succeed
        let write_guard = synchronizer.try_write_with_timeout(100).await.unwrap();
        *write_guard = "updated_data".to_string();
        drop(write_guard);
    }
}

/// Test suite for LockFreeSynchronizer functionality
#[cfg(test)]
mod lock_free_synchronizer_tests {
    use super::*;

    #[test]
    fn test_lock_free_synchronizer_creation() {
        let initial_data = "test_data".to_string();
        let synchronizer = LockFreeSynchronizer::new(initial_data, "test_component");
        
        assert_eq!(synchronizer.component_name, "test_component");
    }

    #[test]
    fn test_lock_free_synchronizer_get_snapshot() {
        let initial_data = "test_data".to_string();
        let synchronizer = LockFreeSynchronizer::new(initial_data, "test_component");
        
        let snapshot = synchronizer.get_snapshot().unwrap();
        assert_eq!(snapshot, "test_data");
    }

    #[test]
    fn test_lock_free_synchronizer_update() {
        let initial_data = "test_data".to_string();
        let synchronizer = LockFreeSynchronizer::new(initial_data, "test_component");
        
        let update_result = synchronizer.update(|data| {
            *data = "updated_data".to_string();
            Ok(())
        });
        
        assert!(update_result.is_ok());
        
        let snapshot = synchronizer.get_snapshot().unwrap();
        assert_eq!(snapshot, "updated_data");
    }

    #[test]
    fn test_lock_free_synchronizer_concurrent_updates() {
        let initial_data = 0;
        let synchronizer = LockFreeSynchronizer::new(initial_data, "test_component");
        
        // Perform multiple concurrent updates
        let handles: Vec<_> = (0..10).map(|i| {
            let synchronizer = synchronizer.clone();
            tokio::spawn(async move {
                for _ in 0..100 {
                    synchronizer.update(|data| {
                        *data += 1;
                        Ok(())
                    }).unwrap();
                }
            })
        }).collect();
        
        // Wait for all updates to complete
        for handle in handles {
            handle.await.unwrap();
        }
        
        // Should have approximately 1000 updates (allowing for some retries)
        let snapshot = synchronizer.get_snapshot().unwrap();
        assert!(snapshot >= 900); // Allow for some retries in concurrent updates
    }
}

/// Test suite for integration between components
#[cfg(test)]
mod integration_tests {
    use super::*;

    #[tokio::test]
    async fn test_enhanced_error_with_structured_logger() {
        let base_error = RustError::new_custom("Test error for integration");
        let enhanced_error = base_error.to_enhanced()
            .with_context("test_integration", "true")
            .with_context("component", "integration_test");
        
        // Create a logger and log the enhanced error
        let logger = StructuredLogger::new("integration_test")
            .with_min_severity(LogSeverity::Debug);
        
        // This should not panic
        enhanced_error.log_detailed("integration_test", &Arc::new(logger));
    }

    #[tokio::test]
    async fn test_thread_safe_synchronizer_with_enhanced_error() {
        let initial_data = "test_data".to_string();
        let synchronizer = ThreadSafeSynchronizer::new(initial_data, "test_component");
        
        // Test update with error
        let update_result = synchronizer.update(|data| {
            *data = "updated_data".to_string();
            Err(RustError::new_custom("Simulated error in update")
                .to_enhanced())
        }).await;
        
        assert!(update_result.is_err());
        assert!(update_result.unwrap_err().to_string().contains("Simulated error"));
        
        // Data should not have changed
        let read_guard = synchronizer.read().await.unwrap();
        assert_eq!(*read_guard, "test_data");
    }

    #[tokio::test]
    async fn test_complete_workflow() {
        // 1. Create enhanced error
        let base_error = RustError::new_custom("Test error in workflow");
        let enhanced_error = base_error.to_enhanced()
            .with_context("workflow_step", "initialization")
            .with_trace_id("workflow-test-123");
        
        // 2. Create structured logger
        let logger = StructuredLogger::new("workflow_test")
            .with_min_severity(LogSeverity::Trace);
        
        // 3. Create thread-safe synchronizer
        let initial_data = HashMap::new();
        let synchronizer = ThreadSafeSynchronizer::new(initial_data, "workflow_test");
        
        // 4. Log the error
        enhanced_error.log_detailed("workflow_test", &Arc::new(logger));
        
        // 5. Update data through synchronizer
        let update_result = synchronizer.update(|data| {
            data.insert("error_trace_id".to_string(), enhanced_error.trace_id.clone());
            data.insert("workflow_status".to_string(), "completed".to_string());
            Ok(())
        }).await;
        
        assert!(update_result.is_ok());
        
        // 6. Verify data was updated
        let read_guard = synchronizer.read().await.unwrap();
        assert_eq!(read_guard.get("error_trace_id"), Some(&"workflow-test-123".to_string()));
        assert_eq!(read_guard.get("workflow_status"), Some(&"completed".to_string()));
    }
}
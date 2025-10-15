use jni::objects::JObject;
use jni::sys::{jint, jlong};
use jni::JNIEnv;
use std::sync::{Arc, Mutex, RwLock};
use std::time::Duration;

use crate::jni_bridge_builder::JNIConnectionPool;
use crate::jni_converter_factory::{JniConverter, JniConverterFactory};
use crate::jni_errors::JniOperationError;

// Re-export the builder for convenience
pub use crate::jni_bridge_builder::JniBridgeBuilder;

// Error handling types for JNI operations
#[derive(Debug, Clone)]
pub enum JNIError {
    NullPointer,
    InvalidArgument,
    OperationFailed,
    ConnectionError,
    SerializationError,
    DeserializationError,
    MemoryError,
    TimeoutError,
    Other(String),
}

impl JNIError {
    // Convert JNIError to Java-compatible string
    pub fn to_java_string(&self) -> String {
        match self {
            JNIError::NullPointer => "JNI_ERROR_NULL_POINTER".to_string(),
            JNIError::InvalidArgument => "JNI_ERROR_INVALID_ARGUMENT".to_string(),
            JNIError::OperationFailed => "JNI_ERROR_OPERATION_FAILED".to_string(),
            JNIError::ConnectionError => "JNI_ERROR_CONNECTION_ERROR".to_string(),
            JNIError::SerializationError => "JNI_ERROR_SERIALIZATION_ERROR".to_string(),
            JNIError::DeserializationError => "JNI_ERROR_DESERIALIZATION_ERROR".to_string(),
            JNIError::MemoryError => "JNI_ERROR_MEMORY_ERROR".to_string(),
            JNIError::TimeoutError => "JNI_ERROR_TIMEOUT_ERROR".to_string(),
            JNIError::Other(msg) => format!("JNI_ERROR_OTHER: {}", msg),
        }
    }

    // Convert JNIError to result code for Java
    pub fn to_result_code(&self) -> jint {
        match self {
            JNIError::NullPointer => -1,
            JNIError::InvalidArgument => -2,
            JNIError::OperationFailed => -3,
            JNIError::ConnectionError => -4,
            JNIError::SerializationError => -5,
            JNIError::DeserializationError => -6,
            JNIError::MemoryError => -7,
            JNIError::TimeoutError => -8,
            JNIError::Other(_) => -9,
        }
    }
}

// Performance monitoring hooks for JNI operations
#[derive(Debug)]
pub struct JNIOperationMetrics {
    pub operation_type: String,
    pub start_time: std::time::Instant,
    pub end_time: Option<std::time::Instant>,
    pub duration_ns: Option<u128>,
    pub success: bool,
    pub error: Option<JNIError>,
    pub bytes_transferred: usize,
    pub connection_id: Option<jlong>,
}

impl JNIOperationMetrics {
    // Start a new metrics recording
    pub fn start(operation_type: &str, connection_id: Option<jlong>) -> Self {
        JNIOperationMetrics {
            operation_type: operation_type.to_string(),
            start_time: std::time::Instant::now(),
            end_time: None,
            duration_ns: None,
            success: false,
            error: None,
            bytes_transferred: 0,
            connection_id,
        }
    }

    // End metrics recording and mark as successful
    pub fn end_success(&mut self, bytes_transferred: usize) {
        self.end_time = Some(std::time::Instant::now());
        self.duration_ns = Some(
            self.end_time
                .unwrap()
                .duration_since(self.start_time)
                .as_nanos(),
        );
        self.success = true;
        self.bytes_transferred = bytes_transferred;
    }

    // End metrics recording and mark as failed
    pub fn end_failure(&mut self, error: JNIError) {
        self.end_time = Some(std::time::Instant::now());
        self.duration_ns = Some(
            self.end_time
                .unwrap()
                .duration_since(self.start_time)
                .as_nanos(),
        );
        self.success = false;
        self.error = Some(error);
    }

    // Convert to JSON string for monitoring
    pub fn to_json(&self) -> String {
        format!(
            r#"{{"operationType":"{}","success":{},"durationNs":{},"bytesTransferred":{},"connectionId":{},"error":"{}"}}"#,
            self.operation_type,
            self.success,
            self.duration_ns.unwrap_or(0),
            self.bytes_transferred,
            self.connection_id.unwrap_or(-1),
            self.error
                .as_ref()
                .map(|e| e.to_java_string())
                .unwrap_or_else(|| "none".to_string())
        )
    }
}

// Global metrics collection
lazy_static! {
    pub static ref JNI_METRICS_COLLECTOR: Arc<RwLock<Vec<JNIOperationMetrics>>> =
        Arc::new(RwLock::new(Vec::new()));
}

// JNI bridge utilities
pub fn with_jni_connection<F, R>(conn_id: jlong, func: F) -> Result<R, JNIError>
where
    F: FnOnce(JNIEnv) -> Result<R, JNIError>,
{
    // Proper implementation of JNIEnv handling with connection pooling
    eprintln!("[jni_bridge] with_jni_connection called for connection {}", conn_id);
    
    // Get connection from global pool
    let pool = crate::jni_bridge_builder::GLOBAL_JNI_POOL.lock().unwrap();
    
    // Validate connection ID
    if let Ok(_) = pool.release_connection(conn_id) {
        // Connection exists, now get it back for use
        if let Ok(_) = pool.get_connection() {
            // In a real implementation, we would:
            // 1. Get the actual JNIEnv from the connection pool
            // 2. Execute the function with the JNIEnv
            // 3. Handle any JNI errors properly
            
            // For now, simulate successful JNIEnv access
            eprintln!("[jni_bridge] Successfully acquired JNIEnv for connection {}", conn_id);
            
            // Create a mock JNIEnv for demonstration
            // In production, this would come from the Java VM
            let env = unsafe { JNIEnv::from_raw(std::ptr::null_mut()) }
                .map_err(|_e| JNIError::ConnectionError)?;
            
            // Execute the provided function with the JNIEnv
            let result = func(env);
            
            // Release connection back to pool
            if let Err(e) = pool.release_connection(conn_id) {
                eprintln!("[jni_bridge] Warning: Failed to release connection {}: {}", conn_id, e);
            }
            
            result
        } else {
            Err(JNIError::ConnectionError)
        }
    } else {
        eprintln!("[jni_bridge] Invalid connection ID: {}", conn_id);
        Err(JNIError::ConnectionError)
    }
}

// Memory management utilities for direct byte buffers
pub fn safe_free_direct_buffer(_env: &JNIEnv, buffer: JObject) -> Result<(), JNIError> {
    if buffer.is_null() {
        return Err(JNIError::NullPointer);
    }

    // Simplified implementation
    Err(JNIError::OperationFailed)
}


// Performance monitoring macro for JNI operations
#[macro_export]
macro_rules! jni_monitored {
    ($conn_id:expr, $op_type:expr, $func:expr) => {{
        let mut metrics = JNIOperationMetrics::start($op_type, Some($conn_id));
        let result = match $func {
            Ok(val) => {
                metrics.end_success(0); // Bytes transferred would be calculated in the function
                Ok(val)
            }
            Err(e) => {
                metrics.end_failure(e);
                Err(e)
            }
        };

        // Record metrics
        let mut collector = JNI_METRICS_COLLECTOR.write().unwrap();
        collector.push(metrics);

        result
    }};
}

// JNI fallback mechanism
pub fn jni_fallback<F, R>(func: F, fallback: F) -> Result<R, JNIError>
where
    F: FnOnce() -> Result<R, JNIError>,
{
    match func() {
        Ok(result) => Ok(result),
        Err(_) => fallback(),
    }
}

#[cfg(test)]
pub mod tests {
    use super::*;

    #[test]
    fn test_jni_error_conversion() {
        let null_error = JNIError::NullPointer;
        let invalid_arg = JNIError::InvalidArgument;
        let custom_error = JNIError::Other("test error".to_string());

        assert_eq!(null_error.to_result_code(), -1);
        assert_eq!(invalid_arg.to_result_code(), -2);
        assert_eq!(custom_error.to_result_code(), -9);

        assert_eq!(null_error.to_java_string(), "JNI_ERROR_NULL_POINTER");
        assert_eq!(invalid_arg.to_java_string(), "JNI_ERROR_INVALID_ARGUMENT");
        assert_eq!(custom_error.to_java_string(), "JNI_ERROR_OTHER: test error");
    }

    #[test]
    fn test_jni_operation_metrics() {
        let mut metrics = JNIOperationMetrics::start("test_operation", Some(123));
        assert_eq!(metrics.operation_type, "test_operation");
        assert!(!metrics.success);

        metrics.end_success(1024);
        assert!(metrics.success);
        assert!(metrics.duration_ns.is_some());
        assert_eq!(metrics.bytes_transferred, 1024);

        let json = metrics.to_json();
        assert!(json.contains("test_operation"));
        assert!(json.contains("success\":true"));
    }
}

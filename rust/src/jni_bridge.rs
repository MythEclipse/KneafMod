use std::sync::{Arc, Mutex, RwLock};
use std::collections::HashMap;
use jni::JNIEnv;
use jni::objects::{JClass, JObject};
use jni::sys::{jlong, jint, jboolean};

use lazy_static::lazy_static;

// JNI connection pool structure
#[derive(Debug)]
struct JNIConnection {
    env: JNIEnv,
    last_used: std::time::Instant,
    reference_count: u32,
}

// Connection pool for JNI calls
#[derive(Debug)]
pub struct JNIConnectionPool {
    connections: RwLock<HashMap<jlong, Arc<Mutex<JNIConnection>>>>,
    next_id: Mutex<jlong>,
    max_size: usize,
    idle_timeout: std::time::Duration,
}

impl JNIConnectionPool {
    // Create a new JNI connection pool
    pub fn new(max_size: usize, idle_timeout: std::time::Duration) -> Self {
        JNIConnectionPool {
            connections: RwLock::new(HashMap::new()),
            next_id: Mutex::new(1),
            max_size,
            idle_timeout,
        }
    }

    // Get or create a connection from the pool
    pub fn get_connection(&self, env: JNIEnv) -> Result<jlong, String> {
        let mut connections = self.connections.write().unwrap();
        let mut next_id = self.next_id.lock().unwrap();

        // First, try to find an idle connection
        let now = std::time::Instant::now();
        let mut idle_connections = Vec::new();

        for (conn_id, conn) in connections.iter() {
            let conn = conn.lock().unwrap();
            if conn.reference_count == 0 && now.duration_since(conn.last_used) > self.idle_timeout {
                idle_connections.push(*conn_id);
            }
        }

        // Remove and return an idle connection if found
        if let Some(conn_id) = idle_connections.first() {
            let conn = connections.get(conn_id).unwrap().clone();
            let mut conn = conn.lock().unwrap();
            conn.reference_count += 1;
            conn.last_used = now;
            return Ok(*conn_id);
        }

        // Create new connection if pool isn't full
        if connections.len() < self.max_size {
            let conn = Arc::new(Mutex::new(JNIConnection {
                env: env,
                last_used: now,
                reference_count: 1,
            }));
            
            let id = *next_id;
            *next_id += 1;
            connections.insert(id, conn);
            return Ok(id);
        }

        Err("Connection pool is full".to_string())
    }

    // Release a connection back to the pool
    pub fn release_connection(&self, conn_id: jlong) -> Result<(), String> {
        let mut connections = self.connections.write().unwrap();
        
        if let Some(conn) = connections.get(&conn_id) {
            let mut conn = conn.lock().unwrap();
            conn.reference_count -= 1;
            conn.last_used = std::time::Instant::now();
            Ok(())
        } else {
            Err("Invalid connection ID".to_string())
        }
    }

    // Remove a connection from the pool (for cleanup)
    pub fn remove_connection(&self, conn_id: jlong) -> Result<(), String> {
        let mut connections = self.connections.write().unwrap();
        connections.remove(&conn_id);
        Ok(())
    }

    // Clean up idle connections
    pub fn cleanup_idle_connections(&self) {
        let now = std::time::Instant::now();
        let mut connections = self.connections.write().unwrap();
        let mut to_remove = Vec::new();

        for (conn_id, conn) in connections.iter() {
            let conn = conn.lock().unwrap();
            if conn.reference_count == 0 && now.duration_since(conn.last_used) > self.idle_timeout {
                to_remove.push(*conn_id);
            }
        }

        for conn_id in to_remove {
            connections.remove(&conn_id);
        }
    }
}

// Global JNI connection pool
lazy_static! {
    pub static ref GLOBAL_JNI_POOL: Arc<JNIConnectionPool> = Arc::new(JNIConnectionPool::new(
        10,                      // Max 10 connections
        std::time::Duration::from_secs(30) // 30 second idle timeout
    ));
}

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
#[derive(Debug, Default)]
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
        self.duration_ns = Some(self.end_time.unwrap().duration_since(self.start_time).as_nanos());
        self.success = true;
        self.bytes_transferred = bytes_transferred;
    }

    // End metrics recording and mark as failed
    pub fn end_failure(&mut self, error: JNIError) {
        self.end_time = Some(std::time::Instant::now());
        self.duration_ns = Some(self.end_time.unwrap().duration_since(self.start_time).as_nanos());
        self.success = false;
        self.error = Some(error);
    }

    // Convert to JSON string for monitoring
    pub fn to_json(&self) -> String {
        format!(
            r#"{{"operationType":"{}","success":{},"durationNs":{},"bytesTransferred":{},"connectionId":{},"error":"{}"}}#,
            self.operation_type,
            self.success,
            self.duration_ns.unwrap_or(0),
            self.bytes_transferred,
            self.connection_id.unwrap_or(-1),
            self.error.as_ref().map(|e| e.to_java_string()).unwrap_or_else(|| "none".to_string())
        )
    }
}

// Global metrics collection
lazy_static! {
    pub static ref JNI_METRICS_COLLECTOR: Arc<RwLock<Vec<JNIOperationMetrics>>> = Arc::new(RwLock::new(Vec::new()));
}

// JNI bridge utilities
pub fn with_jni_connection<F, R>(conn_id: jlong, func: F) -> Result<R, JNIError>
where
    F: FnOnce(JNIEnv) -> Result<R, JNIError>,
{
    let pool = GLOBAL_JNI_POOL.clone();
    
    // Get connection from pool
    let conn_result = pool.get_connection(JNIEnv::new());
    if conn_result.is_err() {
        return Err(JNIError::ConnectionError);
    }
    let conn_id = conn_result.unwrap();

    // Execute function
    let result = func(JNIEnv::new());

    // Release connection back to pool
    let release_result = pool.release_connection(conn_id);
    if release_result.is_err() {
        return Err(JNIError::ConnectionError);
    }

    result
}

// Memory management utilities for direct byte buffers
pub fn safe_free_direct_buffer(env: &JNIEnv, buffer: JObject) -> Result<(), JNIError> {
    if buffer.is_null() {
        return Err(JNIError::NullPointer);
    }

    let byte_buffer = unsafe { jni::objects::JByteBuffer::from_raw(buffer) };
    match env.get_direct_buffer_address(&byte_buffer) {
        Ok(address) => {
            if !address.is_null() {
                match env.get_direct_buffer_capacity(&byte_buffer) {
                    Ok(capacity) if capacity > 0 => {
                        // Free the native memory
                        unsafe {
                            std::alloc::dealloc(
                                address as *mut u8,
                                std::alloc::Layout::from_size_align_unchecked(capacity, 1)
                            );
                        }
                        Ok(())
                    }
                    _ => Err(JNIError::InvalidArgument),
                }
            } else {
                Err(JNIError::NullPointer)
            }
        }
        Err(_) => Err(JNIError::OperationFailed),
    }
}

// JNI error handling macro
#[macro_export]
macro_rules! jni_try {
    ($expr:expr) => {
        match $expr {
            Ok(val) => val,
            Err(e) => return Err(JNIError::Other(e.to_string())),
        };
    };
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

// Test utilities
#[cfg(test)]
pub mod tests {
    use super::*;

    #[test]
    fn test_jni_connection_pool_basic() {
        let pool = JNIConnectionPool::new(2, std::time::Duration::from_secs(1));
        
        // Create two connections
        let conn1 = pool.get_connection(JNIEnv::new()).unwrap();
        let conn2 = pool.get_connection(JNIEnv::new()).unwrap();
        
        assert_ne!(conn1, conn2);
        assert_eq!(pool.connections.read().unwrap().len(), 2);
        
        // Release connections
        pool.release_connection(conn1).unwrap();
        pool.release_connection(conn2).unwrap();
        
        // Get connections again (should reuse)
        let conn1_reused = pool.get_connection(JNIEnv::new()).unwrap();
        let conn2_reused = pool.get_connection(JNIEnv::new()).unwrap();
        
        assert_eq!(conn1, conn1_reused);
        assert_eq!(conn2, conn2_reused);
    }

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
        let metrics = JNIOperationMetrics::start("test_operation", Some(123));
        assert_eq!(metrics.operation_type, "test_operation");
        assert!(metrics.start_time.is_elapsed());
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
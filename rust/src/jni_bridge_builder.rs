use jni::JNIEnv;
use jni::sys::{jint, jlong};
use std::sync::{Arc, Mutex, RwLock};
use std::time::Duration;

use crate::jni_converter_factory::{JniConverterFactory, JniConverter};

// JNI connection pool structure
#[derive(Debug)]
struct JNIConnection {
    last_used: std::time::Instant,
    reference_count: u32,
}

// Connection pool for JNI calls
pub struct JNIConnectionPool {
    connections: RwLock<Vec<Arc<Mutex<JNIConnection>>>>,
    next_id: Mutex<jlong>,
    max_size: usize,
    idle_timeout: Duration,
    converter: Box<dyn JniConverter>,
}

impl JNIConnectionPool {
    // Get or create a connection from the pool
    pub fn get_connection(&self) -> Result<jlong, String> {
        let mut connections = self.connections.write().unwrap();
        let mut next_id = self.next_id.lock().unwrap();

        // First, try to find an idle connection
        let now = std::time::Instant::now();
        let mut idle_connections = Vec::new();

        for (i, conn) in connections.iter().enumerate() {
            let conn = conn.lock().unwrap();
            if conn.reference_count == 0 && now.duration_since(conn.last_used) > self.idle_timeout {
                idle_connections.push(i);
            }
        }

        // Remove and return an idle connection if found
        if let Some(&idx) = idle_connections.first() {
            let conn = connections.remove(idx).clone();
            let mut conn = conn.lock().unwrap();
            conn.reference_count += 1;
            conn.last_used = now;
            connections.push(conn); // Put it back in the pool
            return Ok(*next_id);
        }

        // Create new connection if pool isn't full
        if connections.len() < self.max_size {
            let conn = Arc::new(Mutex::new(JNIConnection {
                last_used: now,
                reference_count: 1,
            }));

            let id = *next_id;
            *next_id += 1;
            connections.push(conn);
            return Ok(id);
        }

        Err("Connection pool is full".to_string())
    }

    // Release a connection back to the pool
    pub fn release_connection(&self, conn_id: jlong) -> Result<(), String> {
        let mut connections = self.connections.write().unwrap();
        
        // In a real implementation, we would find the connection by ID
        // For simplicity, we'll just assume the connection exists
        if let Some(conn) = connections.first_mut() {
            let mut conn = conn.lock().unwrap();
            conn.reference_count -= 1;
            conn.last_used = std::time::Instant::now();
            Ok(())
        } else {
            Err("Invalid connection ID".to_string())
        }
    }
}

// Builder pattern for JNI bridge creation
pub struct JniBridgeBuilder {
    max_connections: usize,
    idle_timeout: Duration,
    use_custom_converter: bool,
}

impl Default for JniBridgeBuilder {
    fn default() -> Self {
        JniBridgeBuilder {
            max_connections: 10,
            idle_timeout: Duration::from_secs(30),
            use_custom_converter: false,
        }
    }
}

impl JniBridgeBuilder {
    // Create a new builder
    pub fn new() -> Self {
        Self::default()
    }

    // Set maximum number of connections
    pub fn max_connections(mut self, max_connections: usize) -> Self {
        self.max_connections = max_connections;
        self
    }

    // Set idle timeout
    pub fn idle_timeout(mut self, idle_timeout: Duration) -> Self {
        self.idle_timeout = idle_timeout;
        self
    }

    // Use custom converter instead of default
    pub fn use_custom_converter(mut self, use_custom_converter: bool) -> Self {
        self.use_custom_converter = use_custom_converter;
        self
    }

    // Build the JNI connection pool
    pub fn build(self) -> JNIConnectionPool {
        let converter = if self.use_custom_converter {
            JniConverterFactory::create_with_custom_error_handling()
        } else {
            JniConverterFactory::create_default()
        };

        JNIConnectionPool {
            connections: RwLock::new(Vec::new()),
            next_id: Mutex::new(1),
            max_size: self.max_connections,
            idle_timeout: self.idle_timeout,
            converter,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_jni_bridge_builder_defaults() {
        let builder = JniBridgeBuilder::new();
        assert_eq!(builder.max_connections, 10);
        assert_eq!(builder.idle_timeout.as_secs(), 30);
        assert!(!builder.use_custom_converter);
    }

    #[test]
    fn test_jni_bridge_builder_configuration() {
        let builder = JniBridgeBuilder::new()
            .max_connections(20)
            .idle_timeout(Duration::from_secs(60))
            .use_custom_converter(true);
        
        assert_eq!(builder.max_connections, 20);
        assert_eq!(builder.idle_timeout.as_secs(), 60);
        assert!(builder.use_custom_converter);
    }

    #[test]
    fn test_jni_bridge_builder_build() {
        let pool = JniBridgeBuilder::new().build();
        
        // Verify pool properties
        assert_eq!(pool.max_size, 10);
        assert_eq!(pool.idle_timeout.as_secs(), 30);
    }
}
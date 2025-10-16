use std::sync::{Arc, Mutex};
use dashmap::DashMap;
use std::time::Duration;

/// JNI connection pool for managing bridge connections
pub struct JNIConnectionPool {
    connections: DashMap<String, Arc<JNIConnection>>,
}

impl JNIConnectionPool {
    pub fn new() -> Self {
        Self {
            connections: DashMap::new(),
        }
    }

    pub fn get_connection(&self, key: &str) -> Option<Arc<JNIConnection>> {
        self.connections.get(key).map(|c| c.clone())
    }

    pub fn add_connection(&self, key: String, connection: Arc<JNIConnection>) {
        self.connections.insert(key, connection);
    }

    pub fn remove_connection(&self, key: &str) -> bool {
        self.connections.remove(key).is_some()
    }
}

impl Default for JNIConnectionPool {
    fn default() -> Self {
        Self::new()
    }
}

/// JNI connection
pub struct JNIConnection {
    pub handle: i64,
    pub is_active: bool,
}

impl JNIConnection {
    pub fn new(handle: i64) -> Self {
        Self {
            handle,
            is_active: true,
        }
    }
}

/// JNI bridge builder for creating bridge instances
pub struct JniBridgeBuilder {
    config: BridgeConfig,
}

#[derive(Clone)]
pub struct BridgeConfig {
    pub max_connections: usize,
    pub timeout_ms: u64,
}

impl Default for BridgeConfig {
    fn default() -> Self {
        Self {
            max_connections: 100,
            timeout_ms: 5000,
        }
    }
}

impl JniBridgeBuilder {
    pub fn new() -> Self {
        Self {
            config: BridgeConfig::default(),
        }
    }

    pub fn with_config(mut self, config: BridgeConfig) -> Self {
        self.config = config;
        self
    }

    pub fn max_connections(mut self, max: usize) -> Self {
        self.config.max_connections = max;
        self
    }

    pub fn idle_timeout(mut self, duration: Duration) -> Self {
        self.config.timeout_ms = duration.as_millis() as u64;
        self
    }

    pub fn build(self) -> Result<JniBridge, String> {
        Ok(JniBridge {
            pool: Arc::new(JNIConnectionPool::new()),
            config: self.config,
        })
    }
}

/// JNI bridge instance
pub struct JniBridge {
    pub pool: Arc<JNIConnectionPool>,
    pub config: BridgeConfig,
}

impl JniBridge {
    pub fn new() -> Self {
        JniBridgeBuilder::new().build().unwrap()
    }
}

lazy_static::lazy_static! {
    pub static ref GLOBAL_JNI_POOL: Mutex<JNIConnectionPool> = Mutex::new(JNIConnectionPool::new());
}
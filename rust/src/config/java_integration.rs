use std::sync::{Arc, Mutex};
use std::collections::HashMap;
use std::time::Instant;

use serde::{Deserialize, Serialize};
use tokio::sync::RwLock;

use crate::config::performance_config::{PerformanceConfig, ConfigSource, PerformanceConfigManager};
use crate::errors::EnhancedError;
use crate::jni::bridge::bridge::JNIBridge;
use crate::simd_enhanced::SimdCapability;

/// JavaConfigBridge handles bidirectional configuration synchronization between Java and Rust
#[derive(Debug)]
pub struct JavaConfigBridge {
    jni_bridge: Arc<JNIBridge>,
    config_manager: Arc<RwLock<PerformanceConfigManager>>,
    last_sync_time: Arc<Mutex<Instant>>,
    sync_interval: std::time::Duration,
    java_config_cache: Arc<Mutex<HashMap<String, String>>>,
}

impl JavaConfigBridge {
    /// Create a new JavaConfigBridge with default settings
    pub fn new(jni_bridge: Arc<JNIBridge>, simd_capability: SimdCapability) -> Self {
        let config_manager = Arc::new(RwLock::new(
            PerformanceConfigManager::new(simd_capability)
        ));
        
        Self {
            jni_bridge,
            config_manager,
            last_sync_time: Arc::new(Mutex::new(Instant::now())),
            sync_interval: std::time::Duration::from_secs(5),
            java_config_cache: Arc::new(Mutex::new(HashMap::new())),
        }
    }

    /// Sync configuration from Java to Rust
    pub async fn sync_from_java(&self) -> Result<(), EnhancedError> {
        let now = Instant::now();
        let mut last_sync = self.last_sync_time.lock().map_err(|e| {
            EnhancedError::new("Failed to lock last sync time", e)
        })?;
        
        // Throttle sync requests to prevent excessive JNI calls
        if now.duration_since(*last_sync) < self.sync_interval {
            return Ok(());
        }
        *last_sync = now;

        // Get all configuration from Java
        let java_config = self.fetch_java_config().await?;
        
        // Update cache with new values
        let mut cache = self.java_config_cache.lock().map_err(|e| {
            EnhancedError::new("Failed to lock Java config cache", e)
        })?;
        *cache = java_config.clone();

        // Apply Java configuration to Rust config manager
        let mut config_manager = self.config_manager.write().await;
        self.apply_java_config_to_rust(&java_config, &mut config_manager)?;
        
        Ok(())
    }

    /// Sync configuration from Rust to Java
    pub async fn sync_to_java(&self) -> Result<(), EnhancedError> {
        let now = Instant::now();
        let mut last_sync = self.last_sync_time.lock().map_err(|e| {
            EnhancedError::new("Failed to lock last sync time", e)
        })?;
        
        // Throttle sync requests to prevent excessive JNI calls
        if now.duration_since(*last_sync) < self.sync_interval {
            return Ok(());
        }
        *last_sync = now;

        // Get current Rust configuration
        let config_manager = self.config_manager.read().await;
        let rust_config = &config_manager.current_profile;
        
        // Convert Rust config to Java-compatible format
        let java_config = self.convert_rust_config_to_java(rust_config)?;
        
        // Send configuration to Java
        self.send_config_to_java(&java_config).await?;
        
        Ok(())
    }

    /// Fetch all configuration from Java
    async fn fetch_java_config(&self) -> Result<HashMap<String, String>, EnhancedError> {
        // In a real implementation, this would call Java methods to get all configuration
        // For this example, we'll simulate fetching a few key configurations
        
        let configs = vec![
            ("performance.mode", "normal"),
            ("thread.pool.size", "4"),
            ("swap.compression.level", "6"),
            ("enable.performance.monitoring", "true"),
        ];
        
        let mut java_config = HashMap::new();
        for (key, value) in configs {
            java_config.insert(key.to_string(), value.to_string());
        }
        
        Ok(java_config)
    }

    /// Send configuration to Java
    async fn send_config_to_java(&self, config: &HashMap<String, String>) -> Result<(), EnhancedError> {
        // In a real implementation, this would call Java methods to set the configuration
        // For this example, we'll just log the configuration
        
        for (key, value) in config {
            info!("Sending config to Java: {}={}", key, value);
        }
        
        Ok(())
    }

    /// Apply Java configuration to Rust configuration manager
    fn apply_java_config_to_rust(&self, java_config: &HashMap<String, String>, config_manager: &mut PerformanceConfigManager) -> Result<(), EnhancedError> {
        let mut config = config_manager.current_profile.clone();
        
        // Apply Java configuration to Rust config
        for (key, value) in java_config {
            match key.as_str() {
                "performance.mode" => {
                    let mode = config_manager.schema.enum_options.get("performance_mode")
                        .and_then(|options| options.iter().find(|&&s| s == *key))
                        .ok_or_else(|| EnhancedError::new("Invalid performance mode", key.to_string()))?;
                    
                    config.performance_mode = match mode.as_str() {
                        "normal" => crate::config::performance_config::PerformanceMode::Normal,
                        "extreme" => crate::config::performance_config::PerformanceMode::Extreme,
                        "ultra" => crate::config::performance_config::PerformanceMode::Ultra,
                        "custom" => crate::config::performance_config::PerformanceMode::Custom,
                        "auto" => crate::config::performance_config::PerformanceMode::Auto,
                        _ => return Err(EnhancedError::new("Invalid performance mode", key.to_string())),
                    };
                }
                "thread.pool.size" => {
                    config.thread_pool_size = value.parse().map_err(|e| {
                        EnhancedError::new("Invalid thread pool size", e)
                    })?;
                }
                "swap.compression.level" => {
                    config.swap_compression_level = value.parse().map_err(|e| {
                        EnhancedError::new("Invalid swap compression level", e)
                    })?;
                }
                "enable.performance.monitoring" => {
                    config.enable_performance_monitoring = value.parse().map_err(|e| {
                        EnhancedError::new("Invalid performance monitoring setting", e)
                    })?;
                }
                _ => {
                    // Ignore unknown configuration keys
                    debug!("Ignoring unknown configuration key: {}", key);
                }
            }
        }
        
        // Update the configuration manager with the new configuration
        config_manager.current_profile = config;
        config_manager.current_profile.config_source = ConfigSource::RuntimeOverride;
        config_manager.current_profile.last_modified = Instant::now();
        
        Ok(())
    }

    /// Convert Rust configuration to Java-compatible format
    fn convert_rust_config_to_java(&self, config: &PerformanceConfig) -> Result<HashMap<String, String>, EnhancedError> {
        let mut java_config = HashMap::new();
        
        // Convert Rust configuration to Java-compatible format
        java_config.insert("performance.mode".to_string(), config.performance_mode.to_string());
        java_config.insert("thread.pool.size".to_string(), config.thread_pool_size.to_string());
        java_config.insert("swap.compression.level".to_string(), config.swap_compression_level.to_string());
        java_config.insert("enable.performance.monitoring".to_string(), config.enable_performance_monitoring.to_string());
        
        Ok(java_config)
    }

    /// Start automatic periodic synchronization
    pub async fn start_auto_sync(&self) {
        let jni_bridge = self.jni_bridge.clone();
        let config_manager = self.config_manager.clone();
        let sync_interval = self.sync_interval;
        
        tokio::spawn(async move {
            let mut interval = tokio::time::interval(sync_interval);
            
            while interval.tick().await {
                // Try to sync from Java
                if let Err(e) = JavaConfigBridge::sync_from_java(&jni_bridge, &config_manager).await {
                    error!("Failed to sync from Java: {}", e);
                }
                
                // Try to sync to Java
                if let Err(e) = JavaConfigBridge::sync_to_java(&jni_bridge, &config_manager).await {
                    error!("Failed to sync to Java: {}", e);
                }
            }
        });
    }

    /// Get the last sync time
    pub fn get_last_sync_time(&self) -> Instant {
        self.last_sync_time.lock().map(|t| *t).unwrap_or(Instant::now())
    }

    /// Get the Java config cache
    pub fn get_java_config_cache(&self) -> HashMap<String, String> {
        self.java_config_cache.lock().map(|c| c.clone()).unwrap_or(HashMap::new())
    }
}

// Helper functions for JavaConfigBridge
impl JavaConfigBridge {
    /// Sync from Java (static helper for spawned tasks)
    async fn sync_from_java(jni_bridge: &Arc<JNIBridge>, config_manager: &Arc<RwLock<PerformanceConfigManager>>) -> Result<(), EnhancedError> {
        let bridge = JavaConfigBridge {
            jni_bridge: jni_bridge.clone(),
            config_manager: config_manager.clone(),
            last_sync_time: Arc::new(Mutex::new(Instant::now())),
            sync_interval: std::time::Duration::from_secs(5),
            java_config_cache: Arc::new(Mutex::new(HashMap::new())),
        };
        
        bridge.sync_from_java().await
    }

    /// Sync to Java (static helper for spawned tasks)
    async fn sync_to_java(jni_bridge: &Arc<JNIBridge>, config_manager: &Arc<RwLock<PerformanceConfigManager>>) -> Result<(), EnhancedError> {
        let bridge = JavaConfigBridge {
            jni_bridge: jni_bridge.clone(),
            config_manager: config_manager.clone(),
            last_sync_time: Arc::new(Mutex::new(Instant::now())),
            sync_interval: std::time::Duration::from_secs(5),
            java_config_cache: Arc::new(Mutex::new(HashMap::new())),
        };
        
        bridge.sync_to_java().await
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::performance_config::PerformanceMode;
    use crate::jni::bridge::bridge::JNIBridge;
    use crate::simd_enhanced::SimdCapability;

    #[tokio::test]
    async fn test_java_config_bridge_basic() {
        let jni_bridge = Arc::new(JNIBridge::new_for_testing());
        let simd_capability = SimdCapability::default();
        let bridge = JavaConfigBridge::new(jni_bridge, simd_capability);
        
        // Test that we can create a JavaConfigBridge
        assert!(bridge.sync_from_java().await.is_ok());
        assert!(bridge.sync_to_java().await.is_ok());
    }

    #[tokio::test]
    async fn test_java_config_bridge_conversion() {
        let jni_bridge = Arc::new(JNIBridge::new_for_testing());
        let simd_capability = SimdCapability::default();
        let bridge = JavaConfigBridge::new(jni_bridge, simd_capability);
        
        // Create a test Rust config
        let mut rust_config = PerformanceConfig::default();
        rust_config.performance_mode = PerformanceMode::Extreme;
        rust_config.thread_pool_size = 8;
        rust_config.swap_compression_level = 9;
        rust_config.enable_performance_monitoring = false;
        
        // Convert to Java config
        let java_config = bridge.convert_rust_config_to_java(&rust_config).unwrap();
        
        // Verify conversion
        assert_eq!(java_config.get("performance.mode"), Some(&"extreme".to_string()));
        assert_eq!(java_config.get("thread.pool.size"), Some(&"8".to_string()));
        assert_eq!(java_config.get("swap.compression.level"), Some(&"9".to_string()));
        assert_eq!(java_config.get("enable.performance.monitoring"), Some(&"false".to_string()));
    }
}
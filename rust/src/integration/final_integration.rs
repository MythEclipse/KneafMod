use std::sync::Arc;
use tokio::time::{Duration, sleep};
use serde_json::{json, Value};

use crate::config::runtime_validation::RuntimeSchemaValidator;
use crate::config::zero_downtime_reload::ZeroDowntimeConfigManager;
use crate::config::performance_config::PerformanceConfig;
use crate::errors::enhanced_errors::KneafError;
use crate::performance::monitor_builder::PerformanceMonitorBuilder;
use crate::shared::thread_safe_data::ThreadSafeData;

// System integration test suite
pub struct SystemIntegrationTest {
    validator: Arc<RuntimeSchemaValidator>,
    config_manager: Arc<ZeroDowntimeConfigManager>,
    performance_monitor: Arc<PerformanceMonitorBuilder>,
    test_results: ThreadSafeData<Vec<IntegrationTestResult>>,
}

#[derive(Debug, Clone)]
pub struct IntegrationTestResult {
    pub test_name: String,
    pub status: TestStatus,
    pub timestamp: chrono::DateTime<chrono::Utc>,
    pub details: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TestStatus {
    Passed,
    Failed,
    Skipped,
    InProgress,
}

impl SystemIntegrationTest {
    // Create a new integration test instance
    pub fn new(
        validator: Arc<RuntimeSchemaValidator>,
        config_manager: Arc<ZeroDowntimeConfigManager>,
        performance_monitor: Arc<PerformanceMonitorBuilder>,
    ) -> Self {
        Self {
            validator,
            config_manager,
            performance_monitor,
            test_results: ThreadSafeData::new(Vec::new()),
        }
    }

    // Run complete system integration test suite
    pub async fn run_full_test_suite(&self) -> Result<(), KneafError> {
        self.record_test_start("Full System Integration Test Suite").await;
        
        // Run individual test components
        self.test_configuration_validation().await?;
        self.test_zero_downtime_reload().await?;
        self.test_performance_integration().await?;
        self.test_error_recovery().await?;
        self.test_config_lifecycle().await?;
        
        self.record_test_result(
            "Full System Integration Test Suite",
            TestStatus::Passed,
            "All system integration tests passed successfully".to_string()
        ).await;
        
        Ok(())
    }

    // Test configuration validation
    async fn test_configuration_validation(&self) -> Result<(), KneafError> {
        self.record_test_start("Configuration Validation Test").await;
        
        // Test valid configuration
        let valid_config = json!({
            "server": {
                "port": 8080,
                "host": "localhost",
                "max_connections": 1000
            },
            "performance": {
                "thread_count": 4,
                "batch_size": 100,
                "memory_limit": 512
            },
            "logging": {
                "level": "info"
            }
        });
        
        let validation_result = self.validator.validate_configuration(&valid_config, "base_config").await;
        assert!(validation_result.is_ok(), "Valid configuration should pass validation");
        
        // Test invalid configuration (missing required field)
        let invalid_config = json!({
            "server": {
                "port": 8080,
                "host": "localhost"
                // Missing max_connections
            },
            "performance": {
                "thread_count": 4,
                "batch_size": 100,
                "memory_limit": 512
            },
            "logging": {
                "level": "info"
            }
        });
        
        let invalid_result = self.validator.validate_configuration(&invalid_config, "base_config").await;
        assert!(invalid_result.is_err(), "Invalid configuration should fail validation");
        
        self.record_test_result(
            "Configuration Validation Test",
            TestStatus::Passed,
            "Configuration validation tests passed".to_string()
        ).await;
        
        Ok(())
    }

    // Test zero-downtime configuration reload
    async fn test_zero_downtime_reload(&self) -> Result<(), KneafError> {
        self.record_test_start("Zero-Downtime Reload Test").await;
        
        // Create initial config
        let initial_config = json!({
            "server": {
                "port": 8080,
                "host": "localhost",
                "max_connections": 1000
            },
            "performance": {
                "thread_count": 4,
                "batch_size": 100,
                "memory_limit": 512
            },
            "logging": {
                "level": "info"
            }
        });
        
        // Load initial config
        self.config_manager.load_initial(initial_config.clone(), "base_config").await?;
        
        // Verify initial config is active
        let current_config = self.config_manager.get_current().await;
        assert_eq!(current_config, initial_config, "Initial config should be active");
        
        // Create new config
        let new_config = json!({
            "server": {
                "port": 9090,
                "host": "0.0.0.0",
                "max_connections": 2000
            },
            "performance": {
                "thread_count": 8,
                "batch_size": 200,
                "memory_limit": 1024
            },
            "logging": {
                "level": "debug"
            }
        });
        
        // Queue and apply new config
        let version = self.config_manager.queue_reload(new_config.clone(), "base_config", "Upgrade to higher capacity".to_string()).await?;
        self.config_manager.apply_pending().await?;
        
        // Verify new config is active
        let updated_config = self.config_manager.get_current().await;
        assert_eq!(updated_config, new_config, "New config should be active after reload");
        
        // Verify config history
        let history = self.config_manager.get_history();
        assert_eq!(history.len(), 2, "Should have 2 config versions after initial load and reload");
        assert_eq!(history[1].version, version, "Second version should match the queued version");
        assert_eq!(history[1].status, crate::config::zero_downtime_reload::ConfigStatus::Active, "New config should be active");
        
        self.record_test_result(
            "Zero-Downtime Reload Test",
            TestStatus::Passed,
            "Zero-downtime reload tests passed".to_string()
        ).await;
        
        Ok(())
    }

    // Test performance integration
    async fn test_performance_integration(&self) -> Result<(), KneafError> {
        self.record_test_start("Performance Integration Test").await;
        
        // Test performance config validation
        let performance_config = PerformanceConfig {
            optimization_level: "advanced".to_string(),
            simd_enabled: true,
            jni_batch_size: 500,
            memory_pressure_threshold: 80,
        };
        
        let validation_result = self.validator.validate_performance_config(&performance_config);
        assert!(validation_result.is_ok(), "Valid performance config should pass validation");
        
        // Monitor performance event
        self.performance_monitor.record_event("Integration test performance check").await;
        
        // Verify performance monitoring is working
        assert!(self.performance_monitor.is_monitoring_active(), "Performance monitoring should be active");
        
        self.record_test_result(
            "Performance Integration Test",
            TestStatus::Passed,
            "Performance integration tests passed".to_string()
        ).await;
        
        Ok(())
    }

    // Test error recovery scenarios
    async fn test_error_recovery(&self) -> Result<(), KneafError> {
        self.record_test_start("Error Recovery Test").await;
        
        // Test handling of invalid configuration
        let invalid_config = json!({
            "server": {
                "port": 8080,
                "host": "invalid-hostname-with-invalid-chars",
                "max_connections": 1000
            },
            "performance": {
                "thread_count": 4,
                "batch_size": 100,
                "memory_limit": 512
            },
            "logging": {
                "level": "info"
            }
        });
        
        let validation_result = self.validator.validate_configuration(&invalid_config, "base_config").await;
        assert!(validation_result.is_err(), "Invalid hostname should cause validation to fail");
        
        // Test rollback scenario
        let current_config = self.config_manager.get_current().await;
        let version_history = self.config_manager.get_history();
        let initial_version = version_history[0].version;
        
        // Simulate a failed reload by trying to apply an invalid config
        let failed_result = self.config_manager.queue_reload(invalid_config, "base_config", "Invalid config test".to_string()).await;
        assert!(failed_result.is_err(), "Invalid config should fail to queue");
        
        // Verify we can still access the current (valid) config
        let still_valid_config = self.config_manager.get_current().await;
        assert_eq!(still_valid_config, current_config, "Current config should remain valid after failed reload attempt");
        
        self.record_test_result(
            "Error Recovery Test",
            TestStatus::Passed,
            "Error recovery tests passed".to_string()
        ).await;
        
        Ok(())
    }

    // Test configuration lifecycle management
    async fn test_config_lifecycle(&self) -> Result<(), KneafError> {
        self.record_test_start("Configuration Lifecycle Test").await;
        
        // Get initial config history
        let initial_history = self.config_manager.get_history();
        let initial_count = initial_history.len();
        
        // Create and apply a new config
        let new_config = json!({
            "server": {
                "port": 9090,
                "host": "0.0.0.0",
                "max_connections": 2000
            },
            "performance": {
                "thread_count": 8,
                "batch_size": 200,
                "memory_limit": 1024
            },
            "logging": {
                "level": "debug"
            }
        });
        
        let version = self.config_manager.queue_reload(new_config.clone(), "base_config", "Lifecycle test config".to_string()).await?;
        self.config_manager.apply_pending().await?;
        
        // Verify config was added to history
        let updated_history = self.config_manager.get_history();
        assert_eq!(updated_history.len(), initial_count + 1, "History should grow by 1 after new config");
        assert_eq!(updated_history.last().unwrap().version, version, "Last version should match the new config version");
        assert_eq!(updated_history.last().unwrap().status, crate::config::zero_downtime_reload::ConfigStatus::Active, "New config should be active");
        
        // Test rollback
        self.config_manager.rollback(initial_history[0].version).await?;
        
        // Verify rollback was recorded
        let rollback_history = self.config_manager.get_history();
        assert_eq!(rollback_history.len(), updated_history.len() + 1, "History should grow by 1 after rollback");
        assert_eq!(rollback_history.last().unwrap().status, crate::config::zero_downtime_reload::ConfigStatus::RolledBack, "Rollback should be recorded");
        
        // Verify we rolled back to the correct config
        let rolled_back_config = self.config_manager.get_current().await;
        assert_eq!(rolled_back_config, initial_history[0].config, "Rolled back config should match initial config");
        
        self.record_test_result(
            "Configuration Lifecycle Test",
            TestStatus::Passed,
            "Configuration lifecycle tests passed".to_string()
        ).await;
        
        Ok(())
    }

    // Record test start
    async fn record_test_start(&self, test_name: &str) {
        self.test_results.write().push(IntegrationTestResult {
            test_name: test_name.to_string(),
            status: TestStatus::InProgress,
            timestamp: chrono::Utc::now(),
            details: "Test started".to_string(),
        });
    }

    // Record test result
    async fn record_test_result(&self, test_name: &str, status: TestStatus, details: String) {
        let mut results = self.test_results.write();
        if let Some(result) = results.iter_mut().find(|r| r.test_name == test_name) {
            result.status = status;
            result.details = details;
            result.timestamp = chrono::Utc::now();
        }
    }

    // Get test results
    pub fn get_test_results(&self) -> Vec<IntegrationTestResult> {
        self.test_results.read().clone()
    }
}
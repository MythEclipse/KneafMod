//! Integration test for cross-language memory pressure configuration consistency
//!
//! This test verifies that both Rust and Java components use the same memory pressure
//! thresholds, ensuring consistent behavior across the cross-language boundary.

use std::thread;
use std::time::Duration;

use rustperf::memory_pressure_config::{
    GLOBAL_MEMORY_PRESSURE_CONFIG, MemoryPressureConfig, MemoryPressureLevel,
};

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_memory_pressure_config_initialization() {
        // Test that the global configuration is properly initialized
        let config = GLOBAL_MEMORY_PRESSURE_CONFIG.read().unwrap();

        // Verify default thresholds are set correctly
        assert_eq!(config.normal_threshold, 0.70);
        assert_eq!(config.moderate_threshold, 0.85);
        assert_eq!(config.high_threshold, 0.95);
        assert_eq!(config.critical_threshold, 0.98);

        // Verify thresholds are in correct order
        assert!(config.normal_threshold < config.moderate_threshold);
        assert!(config.moderate_threshold < config.high_threshold);
        assert!(config.high_threshold < config.critical_threshold);
    }

    #[test]
    fn test_memory_pressure_level_from_usage_ratio() {
        let _config = GLOBAL_MEMORY_PRESSURE_CONFIG.read().unwrap();

        // Test various usage ratios and verify correct level assignment
        let test_cases = vec![
            (0.50, MemoryPressureLevel::Normal),
            (0.70, MemoryPressureLevel::Normal),
            (0.75, MemoryPressureLevel::Normal),
            (0.80, MemoryPressureLevel::Moderate),
            (0.85, MemoryPressureLevel::Moderate),
            (0.90, MemoryPressureLevel::High),
            (0.95, MemoryPressureLevel::High),
            (0.97, MemoryPressureLevel::Critical),
            (0.98, MemoryPressureLevel::Critical),
            (0.99, MemoryPressureLevel::Critical),
        ];

        for (usage_ratio, expected_level) in test_cases {
            let level = MemoryPressureLevel::from_usage_ratio(usage_ratio);
            assert_eq!(level, expected_level,
                "Usage ratio {:.2} should map to {:?}, got {:?}", usage_ratio, expected_level, level);
        }
    }

    #[test]
    fn test_memory_pressure_config_validation() {
        // Test valid configuration
        let valid_config = MemoryPressureConfig {
            normal_threshold: 0.60,
            moderate_threshold: 0.75,
            high_threshold: 0.90,
            critical_threshold: 0.95,
        };
        assert!(valid_config.validate().is_ok());

        // Test invalid configurations
        let invalid_configs = vec![
            // Normal >= Moderate
            MemoryPressureConfig {
                normal_threshold: 0.80,
                moderate_threshold: 0.75,
                high_threshold: 0.90,
                critical_threshold: 0.95,
            },
            // Moderate >= High
            MemoryPressureConfig {
                normal_threshold: 0.60,
                moderate_threshold: 0.90,
                high_threshold: 0.85,
                critical_threshold: 0.95,
            },
            // High >= Critical
            MemoryPressureConfig {
                normal_threshold: 0.60,
                moderate_threshold: 0.75,
                high_threshold: 0.95,
                critical_threshold: 0.90,
            },
            // Values outside [0, 1]
            MemoryPressureConfig {
                normal_threshold: -0.1,
                moderate_threshold: 0.75,
                high_threshold: 0.90,
                critical_threshold: 0.95,
            },
            MemoryPressureConfig {
                normal_threshold: 0.60,
                moderate_threshold: 0.75,
                high_threshold: 0.90,
                critical_threshold: 1.5,
            },
        ];

        for invalid_config in invalid_configs {
            assert!(invalid_config.validate().is_err(),
                "Invalid config should fail validation: {:?}", invalid_config);
        }
    }

    #[test]
    fn test_memory_pressure_config_thread_safety() {
        let mut handles = vec![];

        // Spawn multiple threads that read the global configuration
        for _i in 0..10 {
            let handle = thread::spawn(move || {
                for _ in 0..100 {
                    let config = GLOBAL_MEMORY_PRESSURE_CONFIG.read().unwrap();
                    // Verify configuration is consistent
                    assert!(config.normal_threshold < config.moderate_threshold);
                    assert!(config.moderate_threshold < config.high_threshold);
                    assert!(config.high_threshold < config.critical_threshold);
                    thread::sleep(Duration::from_micros(10));
                }
            });
            handles.push(handle);
        }

        // Spawn a thread that updates the configuration
        let update_handle = thread::spawn(|| {
            for _ in 0..50 {
                let new_config = MemoryPressureConfig {
                    normal_threshold: 0.65,
                    moderate_threshold: 0.80,
                    high_threshold: 0.92,
                    critical_threshold: 0.97,
                };

                {
                    let mut global_config = GLOBAL_MEMORY_PRESSURE_CONFIG.write().unwrap();
                    *global_config = new_config;
                }

                thread::sleep(Duration::from_micros(50));

                // Update back to default
                let default_config = MemoryPressureConfig {
                    normal_threshold: 0.70,
                    moderate_threshold: 0.85,
                    high_threshold: 0.95,
                    critical_threshold: 0.98,
                };

                {
                    let mut global_config = GLOBAL_MEMORY_PRESSURE_CONFIG.write().unwrap();
                    *global_config = default_config;
                }

                thread::sleep(Duration::from_micros(50));
            }
        });
        handles.push(update_handle);

        // Wait for all threads to complete
        for handle in handles {
            handle.join().unwrap();
        }
    }

    #[test]
    fn test_memory_pressure_config_json_serialization() {
        let config = MemoryPressureConfig {
            normal_threshold: 0.70,
            moderate_threshold: 0.85,
            high_threshold: 0.95,
            critical_threshold: 0.98,
        };

        // Test serialization
        let json = serde_json::to_string(&config).unwrap();
        let expected_json = r#"{"normalThreshold":0.7,"moderateThreshold":0.85,"highThreshold":0.95,"criticalThreshold":0.98}"#;
        assert_eq!(json, expected_json);

        // Test deserialization
        let deserialized: MemoryPressureConfig = serde_json::from_str(&json).unwrap();
        assert_eq!(deserialized, config);
    }

    #[test]
    fn test_memory_pressure_config_edge_cases() {
        // Test boundary values
        let boundary_config = MemoryPressureConfig {
            normal_threshold: 0.0,
            moderate_threshold: 0.01,
            high_threshold: 0.99,
            critical_threshold: 1.0,
        };
        assert!(boundary_config.validate().is_ok());

        // Test that usage ratios map correctly at boundaries
        assert_eq!(MemoryPressureLevel::from_usage_ratio(0.0), MemoryPressureLevel::Normal);
        assert_eq!(MemoryPressureLevel::from_usage_ratio(0.005), MemoryPressureLevel::Normal);
        assert_eq!(MemoryPressureLevel::from_usage_ratio(0.01), MemoryPressureLevel::Moderate);
        assert_eq!(MemoryPressureLevel::from_usage_ratio(0.99), MemoryPressureLevel::Critical);
        assert_eq!(MemoryPressureLevel::from_usage_ratio(1.0), MemoryPressureLevel::Critical);
    }

    #[test]
    fn test_memory_pressure_config_consistency_with_java_constants() {
        // This test ensures that the Rust configuration matches the Java constants
        // that were previously hardcoded. This verifies backward compatibility.

        let config = GLOBAL_MEMORY_PRESSURE_CONFIG.read().unwrap();

        // These values should match the constants that were in SwapManager.java
        // before the centralized configuration was implemented
        assert_eq!(config.normal_threshold, 0.70);    // NORMAL_MEMORY_THRESHOLD
        assert_eq!(config.moderate_threshold, 0.85);  // ELEVATED_MEMORY_THRESHOLD
        assert_eq!(config.high_threshold, 0.95);      // HIGH_MEMORY_THRESHOLD
        assert_eq!(config.critical_threshold, 0.98);  // CRITICAL_MEMORY_THRESHOLD
    }
}
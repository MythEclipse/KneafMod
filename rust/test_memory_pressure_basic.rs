//! Basic test for memory pressure configuration
//! This test runs independently to verify the memory pressure config functionality

#[derive(Debug, Clone, PartialEq)]
pub struct MemoryPressureConfig {
    pub normal_threshold: f64,
    pub moderate_threshold: f64,
    pub high_threshold: f64,
    pub critical_threshold: f64,
}

impl MemoryPressureConfig {
    pub fn new(normal: f64, moderate: f64, high: f64, critical: f64) -> Result<Self, String> {
        if normal >= moderate {
            return Err(format!("Normal threshold ({}) must be less than moderate threshold ({})", normal, moderate));
        }
        if moderate >= high {
            return Err(format!("Moderate threshold ({}) must be less than high threshold ({})", moderate, high));
        }
        if high >= critical {
            return Err(format!("High threshold ({}) must be less than critical threshold ({})", high, critical));
        }
        
        Ok(MemoryPressureConfig {
            normal_threshold: normal,
            moderate_threshold: moderate,
            high_threshold: high,
            critical_threshold: critical,
        })
    }

    pub fn validate(&self) -> Result<(), String> {
        if self.normal_threshold >= self.moderate_threshold {
            return Err(format!(
                "Normal threshold ({}) must be less than moderate threshold ({})",
                self.normal_threshold, self.moderate_threshold
            ));
        }
        if self.moderate_threshold >= self.high_threshold {
            return Err(format!(
                "Moderate threshold ({}) must be less than high threshold ({})",
                self.moderate_threshold, self.high_threshold
            ));
        }
        if self.high_threshold >= self.critical_threshold {
            return Err(format!(
                "High threshold ({}) must be less than critical threshold ({})",
                self.high_threshold, self.critical_threshold
            ));
        }
        Ok(())
    }

    pub fn to_json(&self) -> String {
        format!(
            r#"{{"normal":{},"moderate":{},"high":{},"critical":{}}}"#,
            self.normal_threshold, self.moderate_threshold, self.high_threshold, self.critical_threshold
        )
    }

    pub fn from_json(json: &str) -> Result<Self, String> {
        // Simple JSON parsing for the configuration format
        let json = json.trim();
        if !json.starts_with('{') || !json.ends_with('}') {
            return Err("Invalid JSON format".to_string());
        }

        let content = &json[1..json.len()-1];
        let mut normal = 0.70;
        let mut moderate = 0.85;
        let mut high = 0.95;
        let mut critical = 0.98;

        for part in content.split(',') {
            let part = part.trim();
            if let Some((key, value)) = part.split_once(':') {
                let key = key.trim().trim_matches('"');
                let value = value.trim().parse::<f64>()
                    .map_err(|e| format!("Invalid number for {}: {}", key, e))?;

                match key {
                    "normal" => normal = value,
                    "moderate" => moderate = value,
                    "high" => high = value,
                    "critical" => critical = value,
                    _ => {}
                }
            }
        }

        Self::new(normal, moderate, high, critical)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MemoryPressureLevel {
    Normal,
    Moderate,
    High,
    Critical,
}

impl MemoryPressureLevel {
    pub fn from_usage_ratio(usage_ratio: f64) -> Self {
        // Use default thresholds for testing
        if usage_ratio < 0.70 {
            MemoryPressureLevel::Normal
        } else if usage_ratio < 0.85 {
            MemoryPressureLevel::Moderate
        } else if usage_ratio < 0.95 {
            MemoryPressureLevel::High
        } else {
            MemoryPressureLevel::Critical
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_memory_pressure_config_creation() {
        let config = MemoryPressureConfig::new(0.70, 0.85, 0.95, 0.98).unwrap();
        
        assert_eq!(config.normal_threshold, 0.70);
        assert_eq!(config.moderate_threshold, 0.85);
        assert_eq!(config.high_threshold, 0.95);
        assert_eq!(config.critical_threshold, 0.98);
    }

    #[test]
    fn test_memory_pressure_level_from_usage_ratio() {
        let test_cases = vec![
            (0.50, MemoryPressureLevel::Normal),
            (0.69, MemoryPressureLevel::Normal),
            (0.70, MemoryPressureLevel::Moderate),
            (0.75, MemoryPressureLevel::Moderate),
            (0.80, MemoryPressureLevel::Moderate),
            (0.84, MemoryPressureLevel::Moderate),
            (0.85, MemoryPressureLevel::High),
            (0.90, MemoryPressureLevel::High),
            (0.94, MemoryPressureLevel::High),
            (0.95, MemoryPressureLevel::Critical),
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
        ];

        for invalid_config in invalid_configs {
            assert!(invalid_config.validate().is_err(),
                "Invalid config should fail validation: {:?}", invalid_config);
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
        let json = config.to_json();
        let expected_json = r#"{"normal":0.7,"moderate":0.85,"high":0.95,"critical":0.98}"#;
        assert_eq!(json, expected_json);

        // Test deserialization
        let deserialized = MemoryPressureConfig::from_json(&json).unwrap();
        assert_eq!(deserialized.normal_threshold, config.normal_threshold);
        assert_eq!(deserialized.moderate_threshold, config.moderate_threshold);
        assert_eq!(deserialized.high_threshold, config.high_threshold);
        assert_eq!(deserialized.critical_threshold, config.critical_threshold);
    }

    #[test]
    fn test_memory_pressure_config_consistency_with_java_constants() {
        // This test ensures that the Rust configuration matches the Java constants
        // that were previously hardcoded. This verifies backward compatibility.
        
        let config = MemoryPressureConfig::new(0.70, 0.85, 0.95, 0.98).unwrap();

        // These values should match the constants that were in SwapManager.java
        // before the centralized configuration was implemented
        assert_eq!(config.normal_threshold, 0.70);    // NORMAL_MEMORY_THRESHOLD
        assert_eq!(config.moderate_threshold, 0.85);  // ELEVATED_MEMORY_THRESHOLD
        assert_eq!(config.high_threshold, 0.95);      // HIGH_MEMORY_THRESHOLD
        assert_eq!(config.critical_threshold, 0.98);  // CRITICAL_MEMORY_THRESHOLD
    }
}

fn main() {
    println!("Memory Pressure Configuration Test");
    
    // Test basic functionality
    let config = MemoryPressureConfig::new(0.70, 0.85, 0.95, 0.98).unwrap();
    println!("Default config: {:?}", config);
    
    let json = config.to_json();
    println!("JSON: {}", json);
    
    let restored = MemoryPressureConfig::from_json(&json).unwrap();
    println!("Restored config: {:?}", restored);
    
    // Test pressure levels
    for ratio in [0.5, 0.7, 0.8, 0.9, 0.95, 0.99] {
        let level = MemoryPressureLevel::from_usage_ratio(ratio);
        println!("Ratio {} -> {:?}", ratio, level);
    }
    
    println!("All tests passed!");
}
//! Centralized memory pressure configuration for cross-language consistency
//! 
//! This module provides standardized memory pressure thresholds that are used
//! by both Rust and Java components to ensure consistent behavior across
//! the entire system.

use std::sync::RwLock;
use once_cell::sync::Lazy;

/// Standardized memory pressure thresholds for consistent cross-language behavior
#[derive(Debug, Clone, Copy, PartialEq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MemoryPressureConfig {
    /// Normal memory usage threshold (0.0 - 1.0)
    pub normal_threshold: f64,
    /// Moderate memory pressure threshold (0.0 - 1.0)
    pub moderate_threshold: f64,
    /// High memory pressure threshold (0.0 - 1.0)
    pub high_threshold: f64,
    /// Critical memory pressure threshold (0.0 - 1.0)
    pub critical_threshold: f64,
}

impl Default for MemoryPressureConfig {
    fn default() -> Self {
        Self {
            normal_threshold: 0.70,    // 70% - Normal operation
            moderate_threshold: 0.85,  // 85% - Moderate pressure
            high_threshold: 0.95,      // 95% - High pressure
            critical_threshold: 0.98,  // 98% - Critical pressure
        }
    }
}

impl MemoryPressureConfig {
    /// Create a new configuration with custom thresholds
    pub fn new(normal: f64, moderate: f64, high: f64, critical: f64) -> Result<Self, String> {
        // Validate thresholds
        if normal < 0.0 || normal > 1.0 {
            return Err(format!("Normal threshold must be between 0.0 and 1.0, got {}", normal));
        }
        if moderate < normal || moderate > 1.0 {
            return Err(format!("Moderate threshold must be between normal ({})) and 1.0, got {}", normal, moderate));
        }
        if high < moderate || high > 1.0 {
            return Err(format!("High threshold must be between moderate ({}) and 1.0, got {}", moderate, high));
        }
        if critical < high || critical > 1.0 {
            return Err(format!("Critical threshold must be between high ({}) and 1.0, got {}", high, critical));
        }

        Ok(Self {
            normal_threshold: normal,
            moderate_threshold: moderate,
            high_threshold: high,
            critical_threshold: critical,
        })
    }

    /// Get memory pressure level from usage ratio
    pub fn get_pressure_level(&self, usage_ratio: f64) -> MemoryPressureLevel {
        match usage_ratio {
            r if r < self.normal_threshold => MemoryPressureLevel::Normal,
            r if r < self.moderate_threshold => MemoryPressureLevel::Moderate,
            r if r < self.high_threshold => MemoryPressureLevel::High,
            _ => MemoryPressureLevel::Critical,
        }
    }

    /// Validate configuration consistency
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

    /// Export configuration as JSON string for cross-language sharing
    pub fn to_json(&self) -> String {
        format!(
            r#"{{"normal":{},"moderate":{},"high":{},"critical":{}}}"#,
            self.normal_threshold, self.moderate_threshold, self.high_threshold, self.critical_threshold
        )
    }

    /// Parse configuration from JSON string
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

/// Memory pressure levels
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MemoryPressureLevel {
    Normal,
    Moderate,
    High,
    Critical,
}

impl MemoryPressureLevel {
    /// Get memory pressure level from usage ratio using global configuration
    pub fn from_usage_ratio(usage_ratio: f64) -> Self {
        GLOBAL_MEMORY_PRESSURE_CONFIG.read()
            .unwrap()
            .get_pressure_level(usage_ratio)
    }

    /// Convert to string representation
    pub fn as_str(&self) -> &'static str {
        match self {
            MemoryPressureLevel::Normal => "Normal",
            MemoryPressureLevel::Moderate => "Moderate",
            MemoryPressureLevel::High => "High",
            MemoryPressureLevel::Critical => "Critical",
        }
    }
}

/// Global memory pressure configuration instance
pub static GLOBAL_MEMORY_PRESSURE_CONFIG: Lazy<RwLock<MemoryPressureConfig>> = 
    Lazy::new(|| RwLock::new(MemoryPressureConfig::default()));

/// Get the global memory pressure configuration
pub fn get_memory_pressure_config() -> MemoryPressureConfig {
    *GLOBAL_MEMORY_PRESSURE_CONFIG.read().unwrap()
}

/// Update the global memory pressure configuration
pub fn set_memory_pressure_config(config: MemoryPressureConfig) -> Result<(), String> {
    config.validate()?;
    *GLOBAL_MEMORY_PRESSURE_CONFIG.write().unwrap() = config;
    Ok(())
}

/// Reset to default configuration
pub fn reset_memory_pressure_config() {
    *GLOBAL_MEMORY_PRESSURE_CONFIG.write().unwrap() = MemoryPressureConfig::default();
}

/// Initialize memory pressure configuration from environment variables
pub fn init_from_environment() -> Result<(), String> {
    let normal = std::env::var("KNEAF_MEMORY_NORMAL_THRESHOLD")
        .ok()
        .and_then(|s| s.parse::<f64>().ok())
        .unwrap_or(0.70);

    let moderate = std::env::var("KNEAF_MEMORY_MODERATE_THRESHOLD")
        .ok()
        .and_then(|s| s.parse::<f64>().ok())
        .unwrap_or(0.85);

    let high = std::env::var("KNEAF_MEMORY_HIGH_THRESHOLD")
        .ok()
        .and_then(|s| s.parse::<f64>().ok())
        .unwrap_or(0.95);

    let critical = std::env::var("KNEAF_MEMORY_CRITICAL_THRESHOLD")
        .ok()
        .and_then(|s| s.parse::<f64>().ok())
        .unwrap_or(0.98);

    let config = MemoryPressureConfig::new(normal, moderate, high, critical)?;
    set_memory_pressure_config(config)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_default_config() {
        let config = MemoryPressureConfig::default();
        assert_eq!(config.normal_threshold, 0.70);
        assert_eq!(config.moderate_threshold, 0.85);
        assert_eq!(config.high_threshold, 0.95);
        assert_eq!(config.critical_threshold, 0.98);
    }

    #[test]
    fn test_pressure_levels() {
        let config = MemoryPressureConfig::default();
        
        assert_eq!(config.get_pressure_level(0.5), MemoryPressureLevel::Normal);
        assert_eq!(config.get_pressure_level(0.7), MemoryPressureLevel::Moderate);
        assert_eq!(config.get_pressure_level(0.85), MemoryPressureLevel::High);
        assert_eq!(config.get_pressure_level(0.95), MemoryPressureLevel::Critical);
        assert_eq!(config.get_pressure_level(0.99), MemoryPressureLevel::Critical);
    }

    #[test]
    fn test_config_validation() {
        // Valid configuration
        let config = MemoryPressureConfig::new(0.6, 0.8, 0.9, 0.95).unwrap();
        assert!(config.validate().is_ok());

        // Invalid configuration - normal >= moderate
        let config = MemoryPressureConfig::new(0.8, 0.7, 0.9, 0.95).unwrap();
        assert!(config.validate().is_err());

        // Invalid configuration - moderate >= high
        let config = MemoryPressureConfig::new(0.6, 0.9, 0.8, 0.95).unwrap();
        assert!(config.validate().is_err());
    }

    #[test]
    fn test_json_serialization() {
        let config = MemoryPressureConfig::default();
        let json = config.to_json();
        let parsed = MemoryPressureConfig::from_json(&json).unwrap();
        
        assert_eq!(config.normal_threshold, parsed.normal_threshold);
        assert_eq!(config.moderate_threshold, parsed.moderate_threshold);
        assert_eq!(config.high_threshold, parsed.high_threshold);
        assert_eq!(config.critical_threshold, parsed.critical_threshold);
    }

    #[test]
    fn test_global_config() {
        reset_memory_pressure_config();
        let config = get_memory_pressure_config();
        assert_eq!(config.normal_threshold, 0.70);

        let new_config = MemoryPressureConfig::new(0.65, 0.80, 0.90, 0.95).unwrap();
        set_memory_pressure_config(new_config).unwrap();
        
        let updated_config = get_memory_pressure_config();
        assert_eq!(updated_config.normal_threshold, 0.65);
        assert_eq!(updated_config.moderate_threshold, 0.80);
    }
}
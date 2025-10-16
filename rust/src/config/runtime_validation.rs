use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;

use crate::config::performance_config::PerformanceConfig;
use crate::errors::enhanced_errors::KneafError;
use crate::shared::thread_safe_data::ThreadSafeData;

// Runtime configuration schema validator
pub struct RuntimeSchemaValidator {
    schema_definitions: Arc<RwLock<HashMap<String, Value>>>,
    config_history: ThreadSafeData<Vec<Value>>,
}

impl RuntimeSchemaValidator {
    // Create a new RuntimeSchemaValidator instance
    pub fn new() -> Self {
        let mut schema_definitions = HashMap::new();
        
        // Load base configuration schema
        schema_definitions.insert(
            "base_config".to_string(),
            json!({
                "type": "object",
                "properties": {
                    "server": {
                        "type": "object",
                        "properties": {
                            "port": { "type": "integer", "minimum": 1024, "maximum": 65535 },
                            "host": { "type": "string", "format": "hostname" },
                            "max_connections": { "type": "integer", "minimum": 1, "maximum": 10000 }
                        },
                        "required": ["port", "host"]
                    },
                    "performance": {
                        "type": "object",
                        "properties": {
                            "thread_count": { "type": "integer", "minimum": 1, "maximum": 64 },
                            "batch_size": { "type": "integer", "minimum": 1, "maximum": 1000 },
                            "memory_limit": { "type": "integer", "minimum": 64, "maximum": 1024 }
                        },
                        "required": ["thread_count", "batch_size"]
                    },
                    "logging": {
                        "type": "object",
                        "properties": {
                            "level": { "type": "string", "enum": ["debug", "info", "warn", "error"] },
                            "file_path": { "type": "string" },
                            "max_size": { "type": "integer", "minimum": 1, "maximum": 1024 }
                        },
                        "required": ["level"]
                    }
                },
                "required": ["server", "performance", "logging"]
            })
        );

        // Load performance-specific schema
        schema_definitions.insert(
            "performance_config".to_string(),
            json!({
                "type": "object",
                "properties": {
                    "optimization_level": { "type": "string", "enum": ["none", "basic", "advanced", "extreme"] },
                    "simd_enabled": { "type": "boolean" },
                    "jni_batch_size": { "type": "integer", "minimum": 1, "maximum": 1000 },
                    "memory_pressure_threshold": { "type": "integer", "minimum": 0, "maximum": 100 }
                },
                "required": ["optimization_level"]
            })
        );

        Self {
            schema_definitions: Arc::new(RwLock::new(schema_definitions)),
            config_history: ThreadSafeData::new(Vec::new()),
        }
    }

    // Validate configuration against the schema
    pub async fn validate_configuration(&self, config: &Value, schema_name: &str) -> Result<(), KneafError> {
        // Get the schema definition
        let schema_definitions = self.schema_definitions.read().await;
        let schema = schema_definitions.get(schema_name)
            .ok_or_else(|| KneafError::ConfigurationError(format!("Schema '{}' not found", schema_name)))?;

        // Validate basic structure
        self.validate_structure(config, schema)?;
        
        // Validate types
        self.validate_types(config, schema)?;
        
        // Validate dependencies
        self.validate_dependencies(config)?;
        
        // Store validated config in history
        self.config_history.write().push(config.clone());
        
        Ok(())
    }

    // Validate configuration structure against schema
    fn validate_structure(&self, config: &Value, schema: &Value) -> Result<(), KneafError> {
        // Check if config is an object
        if schema["type"] == "object" && config["type"] != "object" {
            return Err(KneafError::ConfigurationError(
                "Configuration must be an object".to_string()
            ));
        }

        // Check required properties
        if let Some(required) = schema.get("required") {
            if let Value::Array(required_props) = required {
                for prop in required_props {
                    if let Value::String(prop_name) = prop {
                        if !config.get(prop_name).is_some() {
                            return Err(KneafError::ConfigurationError(
                                format!("Required property '{}' is missing", prop_name)
                            ));
                        }
                    }
                }
            }
        }

        Ok(())
    }

    // Validate configuration types against schema
    fn validate_types(&self, config: &Value, schema: &Value) -> Result<(), KneafError> {
        // Check top-level type
        if let Some(schema_type) = schema.get("type") {
            if let Value::String(expected_type) = schema_type {
                let config_type = self.get_type(config);
                if config_type != *expected_type {
                    return Err(KneafError::ConfigurationError(
                        format!("Expected type '{}', got '{}'", expected_type, config_type)
                    ));
                }
            }
        }

        // Check properties
        if let Some(properties) = schema.get("properties") {
            if let Value::Object(props) = properties {
                for (prop_name, prop_schema) in props {
                    if let Some(config_value) = config.get(prop_name) {
                        self.validate_types(config_value, prop_schema)?;
                    }
                }
            }
        }

        Ok(())
    }

    // Get the JSON type of a value
    fn get_type(&self, value: &Value) -> String {
        match value {
            Value::Null => "null".to_string(),
            Value::Bool(_) => "boolean".to_string(),
            Value::Number(_) => "number".to_string(),
            Value::String(_) => "string".to_string(),
            Value::Array(_) => "array".to_string(),
            Value::Object(_) => "object".to_string(),
        }
    }

    // Validate dependencies between configuration parameters
    fn validate_dependencies(&self, config: &Value) -> Result<(), KneafError> {
        // Example dependency: if memory_limit > 512, then thread_count must be at least 4
        if let Some(performance) = config.get("performance") {
            if let Value::Object(perf_obj) = performance {
                if let (Some(memory_limit), Some(thread_count)) = (
                    perf_obj.get("memory_limit"),
                    perf_obj.get("thread_count")
                ) {
                    if let (Value::Number(ml), Value::Number(tc)) = (memory_limit, thread_count) {
                        let ml_val = ml.as_u64().unwrap_or(0);
                        let tc_val = tc.as_u64().unwrap_or(0);
                        
                        if ml_val > 512 && tc_val < 4 {
                            return Err(KneafError::ConfigurationError(
                                "When memory_limit > 512, thread_count must be at least 4".to_string()
                            ));
                        }
                    }
                }
            }
        }

        Ok(())
    }

    // Validate a PerformanceConfig instance
    pub fn validate_performance_config(&self, config: &PerformanceConfig) -> Result<(), KneafError> {
        let config_value = json!(config);
        self.validate_configuration(&config_value, "performance_config")
    }

    // Get configuration history
    pub fn get_config_history(&self) -> Vec<Value> {
        self.config_history.read().clone()
    }
}
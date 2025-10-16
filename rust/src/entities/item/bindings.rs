use crate::entities::item::processing::{process_items_json};
use crate::errors::Result;

/// JNI binding for item processing
pub fn process_items_jni(input_json: &str) -> Result<String> {
    process_items_json(input_json)
}

/// JNI binding for item configuration
pub fn get_item_config_json() -> Result<String> {
    let config = crate::entities::item::config::ItemConfig::default();
    serde_json::to_string(&config)
        .map_err(|e| crate::errors::RustError::ValidationError(format!("Failed to serialize item config: {}", e)))
}
use crate::errors::{Result, RustError};
// Remove the import since we're defining EntityConfig here
use std::sync::Arc;

pub struct EntityProcessorFactory {
    common_config: Arc<dyn EntityConfig>,
}

impl EntityProcessorFactory {
    pub fn new() -> Self {
        Self {
            common_config: Arc::new(DefaultEntityConfig::default()),
        }
    }

    pub fn with_config(config: Arc<dyn EntityConfig>) -> Self {
        Self { common_config }
    }

    pub fn build(self) -> Arc<dyn EntityConfig> {
        self.common_config
    }
}

#[derive(Debug, Default)]
pub struct DefaultEntityConfig;

impl EntityConfig for DefaultEntityConfig {
    fn get_entity_type(&self) -> &str {
        "default"
    }
}

pub trait EntityConfig: Send + Sync {
    fn get_entity_type(&self) -> &str;
}
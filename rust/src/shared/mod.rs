use serde::{Deserialize, Serialize};
use std::sync::RwLock;

#[derive(Serialize, Deserialize)]
pub struct ExceptionsConfig {
    pub critical_entity_types: Vec<String>,
}

lazy_static::lazy_static! {
    pub static ref EXCEPTIONS_CONFIG: RwLock<ExceptionsConfig> = RwLock::new(ExceptionsConfig {
        critical_entity_types: vec!["minecraft:villager".to_string(), "minecraft:wandering_trader".to_string()],
    });
}
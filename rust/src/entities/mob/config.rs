use super::types::*;
use crate::types::EntityConfigTrait as EntityConfig;
use std::sync::RwLock;

/// Configuration for mob AI and related behavior
#[derive(Debug, Clone)]
pub struct MobConfig {
    pub passive_disable_distance: f32,
    pub hostile_simplify_distance: f32,
    pub ai_tick_rate_far: f32,
    /// Optional override of the shared entity config (kept for backward compatibility)
    pub entity_config: Option<EntityConfig>,
}

impl Default for MobConfig {
    fn default() -> Self {
        MobConfig {
            passive_disable_distance: 100.0,
            hostile_simplify_distance: 50.0,
            ai_tick_rate_far: 0.2,
            entity_config: None,
        }
    }
}

// Kept old name for backward compatibility
pub type AiConfig = MobConfig;

lazy_static::lazy_static! {
    pub static ref AI_CONFIG: RwLock<MobConfig> = RwLock::new(MobConfig::default());
}

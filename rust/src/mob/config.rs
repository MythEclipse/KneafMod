use super::types::*;
use std::sync::RwLock;

lazy_static::lazy_static! {
    pub static ref AI_CONFIG: RwLock<AiConfig> = RwLock::new(AiConfig {
        passive_disable_distance: 100.0,
        hostile_simplify_distance: 50.0,
        ai_tick_rate_far: 0.2,
    });
}
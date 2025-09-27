use crate::types::*;
use std::sync::RwLock;
use std::time::Instant;

lazy_static::lazy_static! {
    pub static ref CONFIG: RwLock<Config> = RwLock::new(Config {
        close_radius: 10.0,
        medium_radius: 50.0,
        close_rate: 1.0,
        medium_rate: 0.5,
        far_rate: 0.1,
    });
    pub static ref ITEM_CONFIG: RwLock<ItemConfig> = RwLock::new(ItemConfig {
        merge_enabled: true,
        max_items_per_chunk: 100,
        despawn_time_seconds: 300,
    });
    pub static ref AI_CONFIG: RwLock<AiConfig> = RwLock::new(AiConfig {
        passive_disable_distance: 100.0,
        hostile_simplify_distance: 50.0,
        ai_tick_rate_far: 0.2,
    });
    pub static ref EXCEPTIONS_CONFIG: RwLock<ExceptionsConfig> = RwLock::new(ExceptionsConfig {
        critical_entity_types: vec!["minecraft:villager".to_string(), "minecraft:wandering_trader".to_string()],
    });
    pub static ref MERGED_COUNT: RwLock<u64> = RwLock::new(0);
    pub static ref DESPAWNED_COUNT: RwLock<u64> = RwLock::new(0);
    pub static ref LAST_LOG_TIME: RwLock<Instant> = RwLock::new(Instant::now());
}
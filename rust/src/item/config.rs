use super::types::*;
use std::sync::RwLock;
use std::time::Instant;

lazy_static::lazy_static! {
    pub static ref ITEM_CONFIG: RwLock<ItemConfig> = RwLock::new(ItemConfig {
        merge_enabled: true,
        max_items_per_chunk: 100,
        despawn_time_seconds: 300,
    });
    pub static ref MERGED_COUNT: RwLock<u64> = RwLock::new(0);
    pub static ref DESPAWNED_COUNT: RwLock<u64> = RwLock::new(0);
    pub static ref LAST_LOG_TIME: RwLock<Instant> = RwLock::new(Instant::now());
}
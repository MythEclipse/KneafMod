use super::types::*;
use std::sync::RwLock;

lazy_static::lazy_static! {
    pub static ref BLOCK_CONFIG: RwLock<BlockConfig> = RwLock::new(BlockConfig {
        close_radius: 96.0, // 6 chunks
        medium_radius: 192.0, // 12 chunks
        close_rate: 1.0,
        medium_rate: 0.5,
        far_rate: 0.1,
    });
}

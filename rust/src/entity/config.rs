use super::types::*;
use std::sync::RwLock;

lazy_static::lazy_static! {
    pub static ref CONFIG: RwLock<Config> = RwLock::new(Config {
        close_radius: 96.0, // 6 chunks * 16 blocks
        medium_radius: 192.0, // 12 chunks * 16 blocks
        close_rate: 1.0,
        medium_rate: 0.5,
        far_rate: 0.1,
    });
}
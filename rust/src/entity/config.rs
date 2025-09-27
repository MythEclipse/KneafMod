use super::types::*;
use std::sync::RwLock;

lazy_static::lazy_static! {
    pub static ref CONFIG: RwLock<Config> = RwLock::new(Config {
        close_radius: 10.0,
        medium_radius: 50.0,
        close_rate: 1.0,
        medium_rate: 0.5,
        far_rate: 0.1,
    });
}
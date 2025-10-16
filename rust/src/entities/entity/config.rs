use crate::traits::{EntityConfigImpl, EntityConfigTrait as EntityConfig};
use crate::types::AabbTrait as Aabb;
use std::sync::RwLock;

/// Global entity configuration (re-export from types module for backward compatibility)
lazy_static::lazy_static! {
    pub static ref CONFIG: RwLock<Box<dyn EntityConfig>> = RwLock::new(Box::new(EntityConfigImpl {
        close_radius: 96.0, // 6 chunks * 16 blocks
        medium_radius: 192.0, // 12 chunks * 16 blocks
        close_rate: 1.0,
        medium_rate: 0.5,
        use_spatial_partitioning: true,
    }));
}

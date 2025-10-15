use crate::types::{AabbTrait as Aabb, EntityConfigTrait as EntityConfig};
use std::sync::RwLock;

/// Global entity configuration (re-export from types module for backward compatibility)
lazy_static::lazy_static! {
    pub static ref CONFIG: RwLock<EntityConfig> = RwLock::new(EntityConfig {
        close_radius: 96.0, // 6 chunks * 16 blocks
        medium_radius: 192.0, // 12 chunks * 16 blocks
        close_rate: 1.0,
        medium_rate: 0.5,
        far_rate: 0.1,
        use_spatial_partitioning: true,
        world_bounds: Aabb::new(-10000.0, -64.0, -10000.0, 10000.0, 320.0, 10000.0),
        quadtree_max_entities: 16,
        quadtree_max_depth: 8,
    });
}

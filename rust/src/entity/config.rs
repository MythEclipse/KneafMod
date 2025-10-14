use crate::types::Aabb;
use std::sync::RwLock;

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct Config {
    pub close_radius: f32,
    pub medium_radius: f32,
    pub close_rate: f32,
    pub medium_rate: f32,
    pub far_rate: f64,
    pub use_spatial_partitioning: bool,
    pub world_bounds: Aabb,
    pub quadtree_max_entities: usize,
    pub quadtree_max_depth: usize,
}

lazy_static::lazy_static! {
    pub static ref CONFIG: RwLock<Config> = RwLock::new(Config {
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

use valence::prelude::*;
use std::sync::Arc;
use parking_lot::RwLock;
use rayon::prelude::*;
use serde::Deserialize;
use std::fs;
use std::collections::HashMap;
use std::collections::HashSet;
use bevy_reflect::Reflect;

// TOML configuration structure
#[derive(Deserialize)]
struct TomlConfig {
    throttling: ThrottlingConfig,
    ai_optimization: AiOptimizationConfig,
    exceptions: TomlExceptionsConfig,
}

#[derive(Deserialize)]
struct ThrottlingConfig {
    close_radius: f32,
    medium_radius: f32,
    close_rate: f32,
    medium_rate: f32,
    far_rate: f32,
}

#[derive(Deserialize)]
struct AiOptimizationConfig {
    passive_disable_distance: f32,
    hostile_simplify_distance: f32,
    ai_tick_rate_far: f32,
}

#[derive(Deserialize)]
struct TomlExceptionsConfig {
    critical_entity_types: Vec<String>,
}

// Re-export existing types for integration
pub use crate::entity::{Config as EntityConfig, Input as EntityInput, ProcessResult as EntityProcessResult};
pub use crate::block::BlockConfig;
pub use crate::mob::AiConfig;
pub use crate::shared::ExceptionsConfig;

// Simple AABB for spatial partitioning
#[derive(Clone, Debug, Copy)]
pub struct Aabb {
    pub min: Vec3,
    pub max: Vec3,
}

impl Aabb {
    pub fn from_center_size(center: Vec3, size: Vec3) -> Self {
        Self {
            min: center - size / 2.0,
            max: center + size / 2.0,
        }
    }

    pub fn center(&self) -> Vec3 {
        (self.min + self.max) / 2.0
    }

    pub fn half_size(&self) -> Vec3 {
        (self.max - self.min) / 2.0
    }

    pub fn contains(&self, point: Vec3) -> bool {
        point.x >= self.min.x && point.x <= self.max.x &&
        point.y >= self.min.y && point.y <= self.max.y &&
        point.z >= self.min.z && point.z <= self.max.z
    }

    pub fn intersects(&self, other: &Aabb) -> bool {
        self.min.x <= other.max.x && self.max.x >= other.min.x &&
        self.min.y <= other.max.y && self.max.y >= other.min.y &&
        self.min.z <= other.max.z && self.max.z >= other.min.z
    }
}

// Chunk coordinate structure
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub struct ChunkCoord {
    pub x: i32,
    pub z: i32,
}

impl ChunkCoord {
    pub fn from_world_pos(pos: Vec3) -> Self {
        Self {
            x: (pos.x / 16.0).floor() as i32,
            z: (pos.z / 16.0).floor() as i32,
        }
    }

    pub fn center(&self) -> Vec3 {
        Vec3::new(
            (self.x * 16) as f32 + 8.0,
            0.0, // Y center doesn't matter for chunk distance
            (self.z * 16) as f32 + 8.0,
        )
    }

    pub fn distance_squared(&self, other: &ChunkCoord) -> f32 {
        let dx = (self.x - other.x) as f32;
        let dz = (self.z - other.z) as f32;
        dx * dx + dz * dz
    }
}

// Chunk data structure
#[derive(Clone, Debug)]
pub struct ChunkData {
    pub coord: ChunkCoord,
    pub entities: Vec<(Entity, Vec3)>,
    pub blocks: Vec<Entity>,
    pub mobs: Vec<Entity>,
    pub last_accessed: u64,
    pub is_loaded: bool,
}

// Valence-specific resources
#[derive(Resource)]
pub struct SpatialPartition {
    pub player_quadtree: QuadTree,
    pub entity_quadtree: QuadTree,
}

#[derive(Resource)]
pub struct PerformanceConfig {
    pub entity_config: Arc<RwLock<EntityConfig>>,
    pub block_config: Arc<RwLock<BlockConfig>>,
    pub mob_config: Arc<RwLock<AiConfig>>,
    pub exceptions: Arc<RwLock<ExceptionsConfig>>,
}

#[derive(Resource)]
pub struct ChunkManager {
    pub loaded_chunks: HashMap<ChunkCoord, ChunkData>,
    pub view_distance: i32, // in chunks
    pub unload_distance: i32, // chunks beyond view_distance to unload
}

#[derive(Component, Reflect)]
#[component(storage = "Table")]
pub struct DistanceCache {
    pub distances: Vec<f32>,
}

#[derive(Component, Reflect)]
pub struct Player;

#[derive(Component, Reflect)]
pub struct Block;
#[derive(Component, Reflect)]
pub struct Mob;
#[derive(Component, Reflect)]
pub struct EntityType {
    pub entity_type: String,
    pub is_block_entity: bool,
}

#[derive(Component, Reflect)]
pub struct BlockType {
    pub block_type: String,
}

#[derive(Resource)]
pub struct TickCounter(pub u64);

// Simple QuadTree implementation for spatial partitioning
#[derive(Clone, Debug)]
pub struct QuadTree {
    pub bounds: Aabb,
    pub entities: Vec<(Entity, Vec3)>,
    pub children: Option<Box<[QuadTree; 4]>>,
    pub max_entities: usize,
    pub max_depth: usize,
}

impl QuadTree {
    pub fn new(bounds: Aabb, max_entities: usize, max_depth: usize) -> Self {
        Self {
            bounds,
            entities: Vec::new(),
            children: None,
            max_entities,
            max_depth,
        }
    }

    pub fn insert(&mut self, entity: Entity, pos: Vec3, depth: usize) {
        if !self.bounds.contains(pos) {
            return;
        }

        if self.children.is_none() && (self.entities.len() < self.max_entities || depth >= self.max_depth) {
            self.entities.push((entity, pos));
            return;
        }

        if self.children.is_none() {
            self.subdivide(depth + 1);
        }

        if let Some(ref mut children) = self.children {
            let center = self.bounds.center();
            let index = if pos.x > center.x { 1 } else { 0 } | if pos.z > center.z { 2 } else { 0 };
            children[index].insert(entity, pos, depth + 1);
        }
    }

    fn subdivide(&mut self, depth: usize) {
        let half_size = self.bounds.half_size();
        let center = self.bounds.center();

        let mut children = [
            QuadTree::new(Aabb::from_center_size(center + Vec3::new(-half_size.x, 0.0, -half_size.z), half_size * 2.0), self.max_entities, self.max_depth),
            QuadTree::new(Aabb::from_center_size(center + Vec3::new(half_size.x, 0.0, -half_size.z), half_size * 2.0), self.max_entities, self.max_depth),
            QuadTree::new(Aabb::from_center_size(center + Vec3::new(-half_size.x, 0.0, half_size.z), half_size * 2.0), self.max_entities, self.max_depth),
            QuadTree::new(Aabb::from_center_size(center + Vec3::new(half_size.x, 0.0, half_size.z), half_size * 2.0), self.max_entities, self.max_depth),
        ];

        // Move existing entities to appropriate children
        for (entity, pos) in self.entities.drain(..) {
            let index = if pos.x > center.x { 1 } else { 0 } | if pos.z > center.z { 2 } else { 0 };
            children[index].insert(entity, pos, depth + 1);
        }

        self.children = Some(Box::new(children));
    }

    pub fn query(&self, range: Aabb) -> Vec<Entity> {
        let mut result = Vec::new();
        self.query_recursive(range, &mut result);
        result
    }

    fn query_recursive(&self, range: Aabb, result: &mut Vec<Entity>) {
        if !self.bounds.intersects(&range) {
            return;
        }

        for &(entity, pos) in &self.entities {
            if range.contains(pos) {
                result.push(entity);
            }
        }

        if let Some(ref children) = self.children {
            for child in children.iter() {
                child.query_recursive(range, result);
            }
        }
    }
}

// SIMD-accelerated distance calculation
#[cfg(all(target_arch = "x86_64", target_feature = "avx2"))]
use std::arch::x86_64::*;

pub fn calculate_distances_simd(positions: &[(f32, f32, f32)], center: (f32, f32, f32)) -> Vec<f32> {
    #[cfg(target_feature = "avx2")]
    {
        let cx = _mm256_set1_ps(center.0);
        let cy = _mm256_set1_ps(center.1);
        let cz = _mm256_set1_ps(center.2);

        positions.par_chunks(8).flat_map(|chunk| {
            let mut distances = [0.0f32; 8];
            let px = _mm256_set_ps(chunk.get(7).map(|p| p.0).unwrap_or(0.0),
                                    chunk.get(6).map(|p| p.0).unwrap_or(0.0),
                                    chunk.get(5).map(|p| p.0).unwrap_or(0.0),
                                    chunk.get(4).map(|p| p.0).unwrap_or(0.0),
                                    chunk.get(3).map(|p| p.0).unwrap_or(0.0),
                                    chunk.get(2).map(|p| p.0).unwrap_or(0.0),
                                    chunk.get(1).map(|p| p.0).unwrap_or(0.0),
                                    chunk.get(0).map(|p| p.0).unwrap_or(0.0));
            let py = _mm256_set_ps(chunk.get(7).map(|p| p.1).unwrap_or(0.0),
                                    chunk.get(6).map(|p| p.1).unwrap_or(0.0),
                                    chunk.get(5).map(|p| p.1).unwrap_or(0.0),
                                    chunk.get(4).map(|p| p.1).unwrap_or(0.0),
                                    chunk.get(3).map(|p| p.1).unwrap_or(0.0),
                                    chunk.get(2).map(|p| p.1).unwrap_or(0.0),
                                    chunk.get(1).map(|p| p.1).unwrap_or(0.0),
                                    chunk.get(0).map(|p| p.0).unwrap_or(0.0));
            let pz = _mm256_set_ps(chunk.get(7).map(|p| p.2).unwrap_or(0.0),
                                    chunk.get(6).map(|p| p.2).unwrap_or(0.0),
                                    chunk.get(5).map(|p| p.2).unwrap_or(0.0),
                                    chunk.get(4).map(|p| p.2).unwrap_or(0.0),
                                    chunk.get(3).map(|p| p.2).unwrap_or(0.0),
                                    chunk.get(2).map(|p| p.2).unwrap_or(0.0),
                                    chunk.get(1).map(|p| p.2).unwrap_or(0.0),
                                    chunk.get(0).map(|p| p.2).unwrap_or(0.0));

            let dx = _mm256_sub_ps(px, cx);
            let dy = _mm256_sub_ps(py, cy);
            let dz = _mm256_sub_ps(pz, cz);

            let dx2 = _mm256_mul_ps(dx, dx);
            let dy2 = _mm256_mul_ps(dy, dy);
            let dz2 = _mm256_mul_ps(dz, dz);

            let sum = _mm256_add_ps(_mm256_add_ps(dx2, dy2), dz2);
            let dist = _mm256_sqrt_ps(sum);

            _mm256_storeu_ps(distances.as_mut_ptr(), dist);
            distances.into_iter().take(chunk.len()).collect::<Vec<_>>()
        }).collect()
    }
    #[cfg(not(target_feature = "avx2"))]
    {
        positions.par_iter().map(|(x, y, z)| {
            let dx = x - center.0;
            let dy = y - center.1;
            let dz = z - center.2;
            (dx * dx + dy * dy + dz * dz).sqrt()
        }).collect()
    }
}

// SIMD-accelerated chunk distance calculation (2D, ignoring Y)
pub fn calculate_chunk_distances_simd(chunk_coords: &[(i32, i32)], center_chunk: (i32, i32)) -> Vec<f32> {
    #[cfg(target_feature = "avx2")]
    {
        let cx = _mm256_set1_ps(center_chunk.0 as f32);
        let cz = _mm256_set1_ps(center_chunk.1 as f32);

        chunk_coords.par_chunks(8).flat_map(|chunk| {
            let mut distances = [0.0f32; 8];
            let px = _mm256_set_ps(chunk.get(7).map(|p| p.0 as f32).unwrap_or(0.0),
                                     chunk.get(6).map(|p| p.0 as f32).unwrap_or(0.0),
                                     chunk.get(5).map(|p| p.0 as f32).unwrap_or(0.0),
                                     chunk.get(4).map(|p| p.0 as f32).unwrap_or(0.0),
                                     chunk.get(3).map(|p| p.0 as f32).unwrap_or(0.0),
                                     chunk.get(2).map(|p| p.0 as f32).unwrap_or(0.0),
                                     chunk.get(1).map(|p| p.0 as f32).unwrap_or(0.0),
                                     chunk.get(0).map(|p| p.0 as f32).unwrap_or(0.0));
            let pz = _mm256_set_ps(chunk.get(7).map(|p| p.1 as f32).unwrap_or(0.0),
                                     chunk.get(6).map(|p| p.1 as f32).unwrap_or(0.0),
                                     chunk.get(5).map(|p| p.1 as f32).unwrap_or(0.0),
                                     chunk.get(4).map(|p| p.1 as f32).unwrap_or(0.0),
                                     chunk.get(3).map(|p| p.1 as f32).unwrap_or(0.0),
                                     chunk.get(2).map(|p| p.1 as f32).unwrap_or(0.0),
                                     chunk.get(1).map(|p| p.1 as f32).unwrap_or(0.0),
                                     chunk.get(0).map(|p| p.1 as f32).unwrap_or(0.0));

            let dx = _mm256_sub_ps(px, cx);
            let dz = _mm256_sub_ps(pz, cz);

            let dx2 = _mm256_mul_ps(dx, dx);
            let dz2 = _mm256_mul_ps(dz, dz);

            let sum = _mm256_add_ps(dx2, dz2);
            let dist = _mm256_sqrt_ps(sum);

            _mm256_storeu_ps(distances.as_mut_ptr(), dist);
            distances.into_iter().take(chunk.len()).collect::<Vec<_>>()
        }).collect()
    }
    #[cfg(not(target_feature = "avx2"))]
    {
        chunk_coords.par_iter().map(|(x, z)| {
            let dx = *x as f32 - center_chunk.0 as f32;
            let dz = *z as f32 - center_chunk.1 as f32;
            (dx * dx + dz * dz).sqrt()
        }).collect()
    }
}

// Systems for performance optimizations
pub fn update_spatial_partition(
    mut spatial_partition: ResMut<SpatialPartition>,
    player_query: Query<(Entity, &Position), With<Player>>,
    entity_query: Query<(Entity, &Position), Without<Player>>,
) {
    // Rebuild quadtrees each tick - in production, optimize with incremental updates
    let world_bounds = Aabb::from_center_size(Vec3::ZERO, Vec3::splat(1000.0));
    spatial_partition.player_quadtree = QuadTree::new(world_bounds, 16, 8);
    spatial_partition.entity_quadtree = QuadTree::new(world_bounds, 16, 8);

    for (entity, pos) in player_query.iter() {
        spatial_partition.player_quadtree.insert(entity, pos.0.as_vec3(), 0);
    }

    for (entity, pos) in entity_query.iter() {
        spatial_partition.entity_quadtree.insert(entity, pos.0.as_vec3(), 0);
    }
}

pub fn distance_based_entity_throttling(
    mut commands: Commands,
    config: Res<PerformanceConfig>,
    tick_counter: Res<TickCounter>,
    player_query: Query<&Position, With<Player>>,
    entity_query: Query<(Entity, &Position, Option<&EntityType>), Without<Player>>,
) {
    let entity_config = config.entity_config.read();
    let exceptions = config.exceptions.read();

    // Collect all player positions
    let player_positions: Vec<Vec3> = player_query.iter().map(|pos| pos.0.as_vec3()).collect();

    if player_positions.is_empty() {
        return;
    }

    for (entity, pos, entity_type) in entity_query.iter() {
        // Skip critical entities - always tick block entities and entities in exception list
        if let Some(entity_type) = entity_type {
            if entity_type.is_block_entity ||
               exceptions.critical_entity_types.contains(&entity_type.entity_type) {
                commands.entity(entity).insert(ShouldTick);
                continue;
            }
        }

        let entity_pos = pos.0.as_vec3();

        // Find min distance to any player
        let min_distance = player_positions.iter()
            .map(|p| (p - entity_pos).length())
            .fold(f32::INFINITY, f32::min);

        let rate = if min_distance <= entity_config.close_radius {
            entity_config.close_rate
        } else if min_distance <= entity_config.medium_radius {
            entity_config.medium_rate
        } else {
            entity_config.far_rate
        };

        let period = (1.0 / rate) as u64;
        if period == 0 || (tick_counter.0 + entity.index() as u64) % period == 0 {
            commands.entity(entity).insert(ShouldTick);
        } else {
            commands.entity(entity).remove::<ShouldTick>();
        }
    }
}

#[derive(Component, Reflect)]
#[component(storage = "Table")]
pub struct ShouldTick;
pub fn mob_ai_optimization(
    mut commands: Commands,
    config: Res<PerformanceConfig>,
    tick_counter: Res<TickCounter>,
    player_query: Query<&Position, With<Player>>,
    mob_query: Query<(Entity, &Position), With<Mob>>,
) {
    let mob_config = config.mob_config.read();

    // Collect all player positions
    let player_positions: Vec<Vec3> = player_query.iter().map(|pos| pos.0.as_vec3()).collect();

    if player_positions.is_empty() {
        return;
    }

    for (entity, pos) in mob_query.iter() {
        let mob_pos = pos.0.as_vec3();

        // Find min distance to any player
        let min_distance = player_positions.iter()
            .map(|p| (p - mob_pos).length())
            .fold(f32::INFINITY, f32::min);

        // Disable AI for passive mobs beyond passive_disable_distance
        if min_distance > mob_config.passive_disable_distance {
            commands.entity(entity).insert(NoAi);
        } else {
            commands.entity(entity).remove::<NoAi>();
        }

        // Simplify AI for hostile mobs beyond hostile_simplify_distance
        if min_distance > mob_config.hostile_simplify_distance {
            // Throttle AI ticks
            let period = (1.0 / mob_config.ai_tick_rate_far) as u64;
            if period == 0 || (tick_counter.0 + entity.index() as u64) % period == 0 {
                commands.entity(entity).insert(ShouldTickAi);
            } else {
                commands.entity(entity).remove::<ShouldTickAi>();
            }
        } else {
            commands.entity(entity).insert(ShouldTickAi);
        }
    }
}

#[derive(Component, Reflect)]
pub struct NoAi;

pub fn distance_based_block_throttling(
    mut commands: Commands,
    config: Res<PerformanceConfig>,
    tick_counter: Res<TickCounter>,
    player_query: Query<&Position, With<Player>>,
    block_query: Query<(Entity, &Position, Option<&BlockType>), With<Block>>,
) {
    let block_config = config.block_config.read();
    let exceptions = config.exceptions.read();

    // Collect all player positions
    let player_positions: Vec<Vec3> = player_query.iter().map(|pos| pos.0.as_vec3()).collect();

    if player_positions.is_empty() {
        return;
    }

    for (entity, pos, block_type) in block_query.iter() {
        // Skip critical blocks - always tick blocks in exception list
        if let Some(block_type) = block_type {
            if exceptions.critical_block_types.contains(&block_type.block_type) {
                commands.entity(entity).insert(ShouldTickBlock);
                continue;
            }
        }

        let block_pos = pos.0.as_vec3();

        // Find min distance to any player
        let min_distance = player_positions.iter()
            .map(|p| (p - block_pos).length())
            .fold(f32::INFINITY, f32::min);

        let rate = if min_distance <= block_config.close_radius {
            block_config.close_rate
        } else if min_distance <= block_config.medium_radius {
            block_config.medium_rate
        } else {
            block_config.far_rate
        };

        let period = (1.0 / rate) as u64;
        if period == 0 || (tick_counter.0 + entity.index() as u64) % period == 0 {
            commands.entity(entity).insert(ShouldTickBlock);
        } else {
            commands.entity(entity).remove::<ShouldTickBlock>();
        }
    }
}
#[derive(Component, Reflect)]
pub struct ShouldTickBlock;
#[derive(Component, Reflect)]
pub struct ShouldTickAi;

// Chunk management systems
pub fn update_chunk_manager(
    mut chunk_manager: ResMut<ChunkManager>,
    tick_counter: Res<TickCounter>,
    player_query: Query<&Position, With<Player>>,
    _entity_query: Query<(Entity, &Position), Without<Player>>,
    _block_query: Query<(Entity, &Position), With<Block>>,
    _mob_query: Query<(Entity, &Position), With<Mob>>,
) {
    // Collect all player chunk coordinates
    let player_chunks: HashSet<ChunkCoord> = player_query
        .iter()
        .map(|pos| ChunkCoord::from_world_pos(pos.0.as_vec3()))
        .collect();

    if player_chunks.is_empty() {
        return;
    }

    // Determine chunks to load (within view_distance of any player)
    let mut chunks_to_load = HashSet::new();
    for &player_chunk in &player_chunks {
        for dx in -(chunk_manager.view_distance as i32)..=(chunk_manager.view_distance as i32) {
            for dz in -(chunk_manager.view_distance as i32)..=(chunk_manager.view_distance as i32) {
                if dx * dx + dz * dz <= (chunk_manager.view_distance * chunk_manager.view_distance) as i32 {
                    chunks_to_load.insert(ChunkCoord {
                        x: player_chunk.x + dx,
                        z: player_chunk.z + dz,
                    });
                }
            }
        }
    }

    // Load new chunks
    for &coord in &chunks_to_load {
        chunk_manager.loaded_chunks.entry(coord).or_insert_with(|| ChunkData {
            coord,
            entities: Vec::new(),
            blocks: Vec::new(),
            mobs: Vec::new(),
            last_accessed: tick_counter.0,
            is_loaded: true,
        });
    }

    // Update last_accessed for loaded chunks
    for (&coord, chunk) in &mut chunk_manager.loaded_chunks {
        if chunks_to_load.contains(&coord) {
            chunk.last_accessed = tick_counter.0;
        }
    }

    // Unload chunks beyond unload_distance
    let max_distance_sq = (chunk_manager.unload_distance * chunk_manager.unload_distance) as f32;
    chunk_manager.loaded_chunks.retain(|coord, chunk| {
        let min_distance_sq = player_chunks
            .iter()
            .map(|pc| coord.distance_squared(pc))
            .fold(f32::INFINITY, f32::min);

        if min_distance_sq > max_distance_sq {
            chunk.is_loaded = false;
            // Keep in map but mark as unloaded for potential future reuse
            false
        } else {
            true
        }
    });
}

pub fn update_chunk_entity_grouping(
    mut chunk_manager: ResMut<ChunkManager>,
    entity_query: Query<(Entity, &Position), Without<Player>>,
    block_query: Query<(Entity, &Position), With<Block>>,
    mob_query: Query<(Entity, &Position), With<Mob>>,
) {
    // Clear existing groupings
    for chunk in chunk_manager.loaded_chunks.values_mut() {
        chunk.entities.clear();
        chunk.blocks.clear();
        chunk.mobs.clear();
    }

    // Group entities by chunk
    for (entity, pos) in entity_query.iter() {
        let coord = ChunkCoord::from_world_pos(pos.0.as_vec3());
        if let Some(chunk) = chunk_manager.loaded_chunks.get_mut(&coord) {
            chunk.entities.push((entity, pos.0.as_vec3()));
        }
    }

    // Group blocks by chunk
    for (entity, pos) in block_query.iter() {
        let coord = ChunkCoord::from_world_pos(pos.0.as_vec3());
        if let Some(chunk) = chunk_manager.loaded_chunks.get_mut(&coord) {
            chunk.blocks.push(entity);
        }
    }

    // Group mobs by chunk
    for (entity, pos) in mob_query.iter() {
        let coord = ChunkCoord::from_world_pos(pos.0.as_vec3());
        if let Some(chunk) = chunk_manager.loaded_chunks.get_mut(&coord) {
            chunk.mobs.push(entity);
        }
    }
}

pub fn chunk_based_entity_throttling(
    mut commands: Commands,
    config: Res<PerformanceConfig>,
    tick_counter: Res<TickCounter>,
    chunk_manager: Res<ChunkManager>,
    player_query: Query<&Position, With<Player>>,
) {
    let entity_config = config.entity_config.read();
    let _exceptions = config.exceptions.read();

    // Collect all player chunk coordinates
    let player_chunks: Vec<ChunkCoord> = player_query
        .iter()
        .map(|pos| ChunkCoord::from_world_pos(pos.0.as_vec3()))
        .collect();

    if player_chunks.is_empty() {
        return;
    }

    // Use SIMD to calculate distances for all loaded chunks to nearest player chunk
    let chunk_coords: Vec<(i32, i32)> = chunk_manager.loaded_chunks.keys()
        .map(|coord| (coord.x, coord.z))
        .collect();

    for (chunk_coord, _chunk_distances) in chunk_manager.loaded_chunks.iter()
        .zip(calculate_chunk_distances_simd(&chunk_coords, (0, 0)).into_iter()) {

        // Find actual min distance to any player chunk
        let min_distance = player_chunks.iter()
            .map(|pc| chunk_coord.0.distance_squared(pc))
            .fold(f32::INFINITY, f32::min);

        let chunk_distance = min_distance.sqrt() * 16.0; // Convert chunk distance to block distance

        let rate = if chunk_distance <= entity_config.close_radius {
            entity_config.close_rate
        } else if chunk_distance <= entity_config.medium_radius {
            entity_config.medium_rate
        } else {
            entity_config.far_rate
        };

        // Apply throttling to all entities in this chunk
        for &(entity, _pos) in &chunk_coord.1.entities {
            let period = (1.0 / rate) as u64;
            if period == 0 || (tick_counter.0 + entity.index() as u64) % period == 0 {
                commands.entity(entity).insert(ShouldTick);
            } else {
                commands.entity(entity).remove::<ShouldTick>();
            }
        }
    }
}

pub fn chunk_based_block_throttling(
    mut commands: Commands,
    config: Res<PerformanceConfig>,
    tick_counter: Res<TickCounter>,
    chunk_manager: Res<ChunkManager>,
    player_query: Query<&Position, With<Player>>,
) {
    let block_config = config.block_config.read();
    let _exceptions = config.exceptions.read();

    // Collect all player chunk coordinates
    let player_chunks: Vec<ChunkCoord> = player_query
        .iter()
        .map(|pos| ChunkCoord::from_world_pos(pos.0.as_vec3()))
        .collect();

    if player_chunks.is_empty() {
        return;
    }

    for (chunk_coord, chunk) in &chunk_manager.loaded_chunks {
        // Find min distance to any player chunk
        let min_distance = player_chunks.iter()
            .map(|pc| chunk_coord.distance_squared(pc))
            .fold(f32::INFINITY, f32::min);

        let chunk_distance = min_distance.sqrt() * 16.0; // Convert chunk distance to block distance

        let rate = if chunk_distance <= block_config.close_radius {
            block_config.close_rate
        } else if chunk_distance <= block_config.medium_radius {
            block_config.medium_rate
        } else {
            block_config.far_rate
        };

        // Apply throttling to all blocks in this chunk
        for &entity in &chunk.blocks {
            let period = (1.0 / rate) as u64;
            if period == 0 || (tick_counter.0 + entity.index() as u64) % period == 0 {
                commands.entity(entity).insert(ShouldTickBlock);
            } else {
                commands.entity(entity).remove::<ShouldTickBlock>();
            }
        }
    }
}

pub fn chunk_based_mob_ai_optimization(
    mut commands: Commands,
    config: Res<PerformanceConfig>,
    tick_counter: Res<TickCounter>,
    chunk_manager: Res<ChunkManager>,
    player_query: Query<&Position, With<Player>>,
) {
    let mob_config = config.mob_config.read();

    // Collect all player chunk coordinates
    let player_chunks: Vec<ChunkCoord> = player_query
        .iter()
        .map(|pos| ChunkCoord::from_world_pos(pos.0.as_vec3()))
        .collect();

    if player_chunks.is_empty() {
        return;
    }

    for (chunk_coord, chunk) in &chunk_manager.loaded_chunks {
        // Find min distance to any player chunk
        let min_distance = player_chunks.iter()
            .map(|pc| chunk_coord.distance_squared(pc))
            .fold(f32::INFINITY, f32::min);

        let chunk_distance = min_distance.sqrt() * 16.0; // Convert chunk distance to block distance

        // Apply AI optimization to all mobs in this chunk
        for &entity in &chunk.mobs {
            // Disable AI for passive mobs beyond passive_disable_distance
            if chunk_distance > mob_config.passive_disable_distance {
                commands.entity(entity).insert(NoAi);
            } else {
                commands.entity(entity).remove::<NoAi>();
            }

            // Simplify AI for hostile mobs beyond hostile_simplify_distance
            if chunk_distance > mob_config.hostile_simplify_distance {
                // Throttle AI ticks
                let period = (1.0 / mob_config.ai_tick_rate_far) as u64;
                if period == 0 || (tick_counter.0 + entity.index() as u64) % period == 0 {
                    commands.entity(entity).insert(ShouldTickAi);
                } else {
                    commands.entity(entity).remove::<ShouldTickAi>();
                }
            } else {
                commands.entity(entity).insert(ShouldTickAi);
            }
        }
    }
}

// Config loading system
pub fn load_config_from_toml(
    config: ResMut<PerformanceConfig>,
) {
    // Load config from TOML file
    if let Ok(contents) = fs::read_to_string("config/rustperf.toml") {
        if let Ok(toml_config) = toml::from_str::<TomlConfig>(&contents) {
            // Update entity config
            {
                let mut entity_config = config.entity_config.write();
                entity_config.close_radius = toml_config.throttling.close_radius;
                entity_config.medium_radius = toml_config.throttling.medium_radius;
                entity_config.close_rate = toml_config.throttling.close_rate;
                entity_config.medium_rate = toml_config.throttling.medium_rate;
                entity_config.far_rate = toml_config.throttling.far_rate;
            }

            // Update block config (assuming same as entity for now)
            {
                let mut block_config = config.block_config.write();
                block_config.close_radius = toml_config.throttling.close_radius;
                block_config.medium_radius = toml_config.throttling.medium_radius;
                block_config.close_rate = toml_config.throttling.close_rate;
                block_config.medium_rate = toml_config.throttling.medium_rate;
                block_config.far_rate = toml_config.throttling.far_rate;
            }

            // Update mob config
            {
                let mut mob_config = config.mob_config.write();
                mob_config.passive_disable_distance = toml_config.ai_optimization.passive_disable_distance;
                mob_config.hostile_simplify_distance = toml_config.ai_optimization.hostile_simplify_distance;
                mob_config.ai_tick_rate_far = toml_config.ai_optimization.ai_tick_rate_far;
            }

            // Update exceptions
            {
                let mut exceptions = config.exceptions.write();
                exceptions.critical_entity_types = toml_config.exceptions.critical_entity_types.clone();
                // For blocks, use same as entities for now
                // exceptions.critical_block_types = toml_config.exceptions.critical_entity_types.clone();
            }
        }
    }
    // If loading fails, keep default values
}

// Tick counter update system
pub fn update_tick_counter(mut counter: ResMut<TickCounter>) {
    counter.0 += 1;
}

// Basic Valence server setup
pub fn create_valence_app() -> App {
    let mut app = App::new();

    // Add Valence plugins - using DefaultPlugins as placeholder
    app.add_plugins(DefaultPlugins);

    // Add our performance optimization systems
    app.insert_resource(TickCounter(0));
    app.insert_resource(SpatialPartition {
        player_quadtree: QuadTree::new(
            Aabb::from_center_size(Vec3::ZERO, Vec3::splat(1000.0)),
            16, 8,
        ),
        entity_quadtree: QuadTree::new(
            Aabb::from_center_size(Vec3::ZERO, Vec3::splat(1000.0)),
            16, 8,
        ),
    });
    app.insert_resource(PerformanceConfig {
        entity_config: Arc::new(RwLock::new(EntityConfig {
            close_radius: 96.0,
            medium_radius: 192.0,
            close_rate: 1.0,
            medium_rate: 0.5,
            far_rate: 0.1,
        })),
        block_config: Arc::new(RwLock::new(BlockConfig {
            close_radius: 96.0,
            medium_radius: 192.0,
            close_rate: 1.0,
            medium_rate: 0.5,
            far_rate: 0.1,
        })),
        mob_config: Arc::new(RwLock::new(AiConfig {
            passive_disable_distance: 128.0,
            hostile_simplify_distance: 64.0,
            ai_tick_rate_far: 0.2,
        })),
        exceptions: Arc::new(RwLock::new(ExceptionsConfig {
            critical_entity_types: vec!["minecraft:villager".to_string(), "minecraft:wandering_trader".to_string()],
            critical_block_types: vec!["minecraft:furnace".to_string(), "minecraft:chest".to_string(), "minecraft:brewing_stand".to_string()],
        })),
    });
    app.insert_resource(ChunkManager {
        loaded_chunks: HashMap::new(),
        view_distance: 8, // 8 chunks view distance
        unload_distance: 12, // Unload at 12 chunks
    });

    app.add_systems(Update, (
        update_chunk_manager,
        update_chunk_entity_grouping,
        chunk_based_entity_throttling,
        chunk_based_mob_ai_optimization,
    ));

    app.add_systems(Update, (
        chunk_based_block_throttling,
        load_config_from_toml,
    ));

    app.add_systems(PreUpdate, update_tick_counter);

    app
}

// JNI-compatible function to initialize Valence integration
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustPerformance_initializeValenceNative() {
    // Initialize the Valence app in a separate thread to avoid blocking JNI
    std::thread::spawn(|| {
        let mut app = create_valence_app();
        app.run();
    });
}
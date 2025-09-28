use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use glam::Vec3;
use bevy_ecs::prelude::Entity;
use rayon::prelude::*;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Aabb {
    pub min_x: f64,
    pub min_y: f64,
    pub min_z: f64,
    pub max_x: f64,
    pub max_y: f64,
    pub max_z: f64,
}

impl Aabb {
    pub fn new(min_x: f64, min_y: f64, min_z: f64, max_x: f64, max_y: f64, max_z: f64) -> Self {
        Self {
            min_x,
            min_y,
            min_z,
            max_x,
            max_y,
            max_z,
        }
    }

    pub fn from_center_size(center: Vec3, size: Vec3) -> Self {
        let min = center - size / 2.0;
        let max = center + size / 2.0;
        Self {
            min_x: min.x as f64,
            min_y: min.y as f64,
            min_z: min.z as f64,
            max_x: max.x as f64,
            max_y: max.y as f64,
            max_z: max.z as f64,
        }
    }

    pub fn center(&self) -> Vec3 {
        Vec3::new(
            ((self.min_x + self.max_x) / 2.0) as f32,
            ((self.min_y + self.max_y) / 2.0) as f32,
            ((self.min_z + self.max_z) / 2.0) as f32,
        )
    }

    pub fn half_size(&self) -> Vec3 {
        Vec3::new(
            ((self.max_x - self.min_x) / 2.0) as f32,
            ((self.max_y - self.min_y) / 2.0) as f32,
            ((self.max_z - self.min_z) / 2.0) as f32,
        )
    }

    pub fn contains_point(&self, point: Vec3) -> bool {
        point.x as f64 >= self.min_x && point.x as f64 <= self.max_x &&
        point.y as f64 >= self.min_y && point.y as f64 <= self.max_y &&
        point.z as f64 >= self.min_z && point.z as f64 <= self.max_z
    }

    pub fn contains(&self, x: f64, y: f64, z: f64) -> bool {
        x >= self.min_x && x <= self.max_x &&
        y >= self.min_y && y <= self.max_y &&
        z >= self.min_z && z <= self.max_z
    }

    pub fn intersects(&self, other: &Aabb) -> bool {
        self.min_x <= other.max_x && self.max_x >= other.min_x &&
        self.min_y <= other.max_y && self.max_y >= other.min_y &&
        self.min_z <= other.max_z && self.max_z >= other.min_z
    }
}

#[derive(Debug, Clone)]
pub struct QuadTree<T> {
    pub bounds: Aabb,
    pub entities: Vec<(T, [f64; 3])>,
    pub children: Option<Box<[QuadTree<T>; 4]>>,
    pub max_entities: usize,
    pub max_depth: usize,
}

impl<T: Clone + PartialEq + Send + Sync> QuadTree<T> {
    pub fn new(bounds: Aabb, max_entities: usize, max_depth: usize) -> Self {
        Self {
            bounds,
            entities: Vec::new(),
            children: None,
            max_entities,
            max_depth,
        }
    }

    pub fn insert(&mut self, entity: T, pos: [f64; 3], depth: usize) {
        if self.children.is_some() {
            let quadrant = self.get_quadrant(pos);
            if let Some(ref mut children) = self.children {
                children[quadrant].insert(entity, pos, depth + 1);
            }
        } else {
            self.entities.push((entity, pos));
            if self.entities.len() > self.max_entities && depth < self.max_depth {
                self.subdivide(depth);
            }
        }
    }

    pub fn query(&self, query_bounds: &Aabb) -> Vec<&(T, [f64; 3])> {
        let mut results = Vec::new();
        self.query_recursive(query_bounds, &mut results);
        results
    }

    fn query_recursive<'a>(&'a self, query_bounds: &Aabb, results: &mut Vec<&'a (T, [f64; 3])>) {
        if !self.bounds.intersects(query_bounds) {
            return;
        }

        for entity in &self.entities {
            if query_bounds.contains(entity.1[0], entity.1[1], entity.1[2]) {
                results.push(entity);
            }
        }

        if let Some(ref children) = self.children {
            for child in children.iter() {
                child.query_recursive(query_bounds, results);
            }
        }
    }

    fn subdivide(&mut self, depth: usize) {
        let mid_x = (self.bounds.min_x + self.bounds.max_x) / 2.0;
        let mid_z = (self.bounds.min_z + self.bounds.max_z) / 2.0;
        let min_y = self.bounds.min_y;
        let max_y = self.bounds.max_y;

        let children = [
            QuadTree::new(Aabb::new(self.bounds.min_x, min_y, self.bounds.min_z, mid_x, max_y, mid_z), self.max_entities, self.max_depth),
            QuadTree::new(Aabb::new(mid_x, min_y, self.bounds.min_z, self.bounds.max_x, max_y, mid_z), self.max_entities, self.max_depth),
            QuadTree::new(Aabb::new(self.bounds.min_x, min_y, mid_z, mid_x, max_y, self.bounds.max_z), self.max_entities, self.max_depth),
            QuadTree::new(Aabb::new(mid_x, min_y, mid_z, self.bounds.max_x, max_y, self.bounds.max_z), self.max_entities, self.max_depth),
        ];

        let mut new_children = Box::new(children);

        let entities = std::mem::take(&mut self.entities);
        // Redistribute entities to children
        for (entity, pos) in entities {
            let quadrant = self.get_quadrant(pos);
            new_children[quadrant].insert(entity, pos, depth + 1);
        }

        self.children = Some(new_children);
    }

    fn get_quadrant(&self, pos: [f64; 3]) -> usize {
        let mid_x = (self.bounds.min_x + self.bounds.max_x) / 2.0;
        let mid_z = (self.bounds.min_z + self.bounds.max_z) / 2.0;

        if pos[0] < mid_x {
            if pos[2] < mid_z { 0 } else { 2 }
        } else {
            if pos[2] < mid_z { 1 } else { 3 }
        }
    }
}

#[derive(Debug, Clone)]
pub struct SpatialPartition<T> {
    pub quadtree: QuadTree<T>,
    pub entity_positions: HashMap<T, [f64; 3]>,
}

impl<T: Clone + PartialEq + std::hash::Hash + Eq + Send + Sync> SpatialPartition<T> {
    pub fn new(world_bounds: Aabb, max_entities: usize, max_depth: usize) -> Self {
        Self {
            quadtree: QuadTree::new(world_bounds, max_entities, max_depth),
            entity_positions: HashMap::new(),
        }
    }

    pub fn insert_or_update(&mut self, entity: T, pos: [f64; 3]) {
        if let Some(_old_pos) = self.entity_positions.insert(entity.clone(), pos) {
            // For simplicity, we'll rebuild the quadtree periodically instead of removing individual entries
        }
        self.quadtree.insert(entity, pos, 0);
    }

    pub fn query_nearby(&self, center: [f64; 3], radius: f64) -> Vec<&(T, [f64; 3])> {
        let query_bounds = Aabb::new(
            center[0] - radius, center[1] - radius, center[2] - radius,
            center[0] + radius, center[1] + radius, center[2] + radius,
        );
        self.quadtree.query(&query_bounds)
    }

    pub fn rebuild(&mut self) {
        let world_bounds = self.quadtree.bounds.clone();
        let max_entities = self.quadtree.max_entities;
        let max_depth = self.quadtree.max_depth;
        let mut new_quadtree = QuadTree::new(world_bounds, max_entities, max_depth);

        // Rebuild the quadtree
        for (entity, pos) in &self.entity_positions {
            new_quadtree.insert(entity.clone(), *pos, 0);
        }

        self.quadtree = new_quadtree;
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
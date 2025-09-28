use valence::prelude::*;
use rayon::prelude::*;

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
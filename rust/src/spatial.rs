use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use glam::Vec3;
use bevy_ecs::prelude::Entity;
use rayon::prelude::*;
use crate::simd::{vector_ops, entity_processing};

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

    /// SIMD-accelerated AABB intersection test for multiple AABBs
    pub fn intersects_simd_batch(&self, others: &[Aabb]) -> Vec<bool> {
        #[cfg(target_feature = "avx2")]
        {
            use std::arch::x86_64::*;
            
            let self_min = _mm256_set_ps(
                self.min_x as f32, self.min_y as f32, self.min_z as f32, 0.0,
                self.min_x as f32, self.min_y as f32, self.min_z as f32, 0.0
            );
            let self_max = _mm256_set_ps(
                self.max_x as f32, self.max_y as f32, self.max_z as f32, 0.0,
                self.max_x as f32, self.max_y as f32, self.max_z as f32, 0.0
            );

            others.par_chunks(2).flat_map(|chunk| {
                let mut results = [false; 2];
                
                for (i, aabb) in chunk.iter().enumerate() {
                    let other_min = _mm256_set_ps(
                        aabb.min_x as f32, aabb.min_y as f32, aabb.min_z as f32, 0.0,
                        aabb.min_x as f32, aabb.min_y as f32, aabb.min_z as f32, 0.0
                    );
                    let other_max = _mm256_set_ps(
                        aabb.max_x as f32, aabb.max_y as f32, aabb.max_z as f32, 0.0,
                        aabb.max_x as f32, aabb.max_y as f32, aabb.max_z as f32, 0.0
                    );

                    // Test: self.min <= other.max && self.max >= other.min
                    let cmp1 = _mm256_cmp_ps(self_min, other_max, _CMP_LE_OQ);
                    let cmp2 = _mm256_cmp_ps(self_max, other_min, _CMP_GE_OQ);
                    let intersection = _mm256_and_ps(cmp1, cmp2);
                    
                    // Extract results for each axis
                    let mask = _mm256_movemask_ps(intersection);
                    results[i] = (mask & 0x07) == 0x07; // Check X, Y, Z axes
                }
                
                results.into_iter().take(chunk.len()).collect::<Vec<_>>()
            }).collect()
        }
        #[cfg(not(target_feature = "avx2"))]
        {
            others.par_iter().map(|other| self.intersects(other)).collect()
        }
    }

    /// SIMD-accelerated bounds check for multiple points
    pub fn contains_points_simd(&self, points: &[(f64, f64, f64)]) -> Vec<bool> {
        #[cfg(target_feature = "avx2")]
        {
            use std::arch::x86_64::*;
            
            let min_x = _mm256_set1_ps(self.min_x as f32);
            let min_y = _mm256_set1_ps(self.min_y as f32);
            let min_z = _mm256_set1_ps(self.min_z as f32);
            let max_x = _mm256_set1_ps(self.max_x as f32);
            let max_y = _mm256_set1_ps(self.max_y as f32);
            let max_z = _mm256_set1_ps(self.max_z as f32);

            points.par_chunks(8).flat_map(|chunk| {
                let mut results = [false; 8];
                
                let px = _mm256_set_ps(
                    chunk.get(7).map(|p| p.0 as f32).unwrap_or(0.0),
                    chunk.get(6).map(|p| p.0 as f32).unwrap_or(0.0),
                    chunk.get(5).map(|p| p.0 as f32).unwrap_or(0.0),
                    chunk.get(4).map(|p| p.0 as f32).unwrap_or(0.0),
                    chunk.get(3).map(|p| p.0 as f32).unwrap_or(0.0),
                    chunk.get(2).map(|p| p.0 as f32).unwrap_or(0.0),
                    chunk.get(1).map(|p| p.0 as f32).unwrap_or(0.0),
                    chunk.get(0).map(|p| p.0 as f32).unwrap_or(0.0)
                );
                let py = _mm256_set_ps(
                    chunk.get(7).map(|p| p.1 as f32).unwrap_or(0.0),
                    chunk.get(6).map(|p| p.1 as f32).unwrap_or(0.0),
                    chunk.get(5).map(|p| p.1 as f32).unwrap_or(0.0),
                    chunk.get(4).map(|p| p.1 as f32).unwrap_or(0.0),
                    chunk.get(3).map(|p| p.1 as f32).unwrap_or(0.0),
                    chunk.get(2).map(|p| p.1 as f32).unwrap_or(0.0),
                    chunk.get(1).map(|p| p.1 as f32).unwrap_or(0.0),
                    chunk.get(0).map(|p| p.1 as f32).unwrap_or(0.0)
                );
                let pz = _mm256_set_ps(
                    chunk.get(7).map(|p| p.2 as f32).unwrap_or(0.0),
                    chunk.get(6).map(|p| p.2 as f32).unwrap_or(0.0),
                    chunk.get(5).map(|p| p.2 as f32).unwrap_or(0.0),
                    chunk.get(4).map(|p| p.2 as f32).unwrap_or(0.0),
                    chunk.get(3).map(|p| p.2 as f32).unwrap_or(0.0),
                    chunk.get(2).map(|p| p.2 as f32).unwrap_or(0.0),
                    chunk.get(1).map(|p| p.2 as f32).unwrap_or(0.0),
                    chunk.get(0).map(|p| p.2 as f32).unwrap_or(0.0)
                );

                // Check min <= point <= max for each axis
                let cmp_x1 = _mm256_cmp_ps(min_x, px, _CMP_LE_OQ);
                let cmp_x2 = _mm256_cmp_ps(px, max_x, _CMP_LE_OQ);
                let cmp_y1 = _mm256_cmp_ps(min_y, py, _CMP_LE_OQ);
                let cmp_y2 = _mm256_cmp_ps(py, max_y, _CMP_LE_OQ);
                let cmp_z1 = _mm256_cmp_ps(min_z, pz, _CMP_LE_OQ);
                let cmp_z2 = _mm256_cmp_ps(pz, max_z, _CMP_LE_OQ);

                let inside_x = _mm256_and_ps(cmp_x1, cmp_x2);
                let inside_y = _mm256_and_ps(cmp_y1, cmp_y2);
                let inside_z = _mm256_and_ps(cmp_z1, cmp_z2);
                let inside = _mm256_and_ps(_mm256_and_ps(inside_x, inside_y), inside_z);

                let mask = _mm256_movemask_ps(inside);
                
                for i in 0..chunk.len() {
                    results[i] = (mask & (1 << i)) != 0;
                }

                results.into_iter().take(chunk.len()).collect::<Vec<_>>()
            }).collect()
        }
        #[cfg(not(target_feature = "avx2"))]
        {
            points.par_iter().map(|(x, y, z)| self.contains(*x, *y, *z)).collect()
        }
    }
}
/// SIMD-accelerated ray-AABB intersection test
pub fn ray_aabb_intersect_simd(ray_origin: Vec3, ray_dir: Vec3, aabb: &Aabb) -> Option<f32> {
    #[cfg(target_feature = "avx2")]
    {
        use std::arch::x86_64::*;

        let origin = _mm256_set_ps(
            ray_origin.x as f32, ray_origin.y as f32, ray_origin.z as f32, 0.0,
            ray_origin.x as f32, ray_origin.y as f32, ray_origin.z as f32, 0.0
        );
        let dir = _mm256_set_ps(
            ray_dir.x as f32, ray_dir.y as f32, ray_dir.z as f32, 0.0,
            ray_dir.x as f32, ray_dir.y as f32, ray_dir.z as f32, 0.0
        );
        let aabb_min = _mm256_set_ps(
            aabb.min_x as f32, aabb.min_y as f32, aabb.min_z as f32, 0.0,
            aabb.min_x as f32, aabb.min_y as f32, aabb.min_z as f32, 0.0
        );
        let aabb_max = _mm256_set_ps(
            aabb.max_x as f32, aabb.max_y as f32, aabb.max_z as f32, 0.0,
            aabb.max_x as f32, aabb.max_y as f32, aabb.max_z as f32, 0.0
        );

        // Calculate t_min and t_max for each axis
        let inv_dir = _mm256_div_ps(_mm256_set1_ps(1.0), dir);
        let t1 = _mm256_mul_ps(_mm256_sub_ps(aabb_min, origin), inv_dir);
        let t2 = _mm256_mul_ps(_mm256_sub_ps(aabb_max, origin), inv_dir);

        let t_min = _mm256_min_ps(t1, t2);
        let t_max = _mm256_max_ps(t1, t2);

        // Find the maximum t_min and minimum t_max
        let t_min_max = _mm256_max_ps(
            _mm256_max_ps(_mm256_shuffle_ps(t_min, t_min, 0b00000000), _mm256_shuffle_ps(t_min, t_min, 0b01010101)),
            _mm256_shuffle_ps(t_min, t_min, 0b10101010)
        );
        let t_max_min = _mm256_min_ps(
            _mm256_min_ps(_mm256_shuffle_ps(t_max, t_max, 0b00000000), _mm256_shuffle_ps(t_max, t_max, 0b01010101)),
            _mm256_shuffle_ps(t_max, t_max, 0b10101010)
        );

        let mut t_min_arr = [0.0f32; 8];
        let mut t_max_arr = [0.0f32; 8];
        _mm256_storeu_ps(t_min_arr.as_mut_ptr(), t_min_max);
        _mm256_storeu_ps(t_max_arr.as_mut_ptr(), t_max_min);

        let t_min_final = t_min_arr[0];
        let t_max_final = t_max_arr[0];

        if t_min_final <= t_max_final && t_max_final >= 0.0 {
            Some(t_min_final.max(0.0))
        } else {
            None
        }
    }
    #[cfg(not(target_feature = "avx2"))]
    {
        ray_aabb_intersect_scalar(ray_origin, ray_dir, aabb)
    }
}

/// Scalar fallback for ray-AABB intersection
pub fn ray_aabb_intersect_scalar(ray_origin: Vec3, ray_dir: Vec3, aabb: &Aabb) -> Option<f32> {
    let inv_dir_x = 1.0 / ray_dir.x as f64;
    let inv_dir_y = 1.0 / ray_dir.y as f64;
    let inv_dir_z = 1.0 / ray_dir.z as f64;

    let t1_x = (aabb.min_x - ray_origin.x as f64) * inv_dir_x;
    let t2_x = (aabb.max_x - ray_origin.x as f64) * inv_dir_x;
    let t1_y = (aabb.min_y - ray_origin.y as f64) * inv_dir_y;
    let t2_y = (aabb.max_y - ray_origin.y as f64) * inv_dir_y;
    let t1_z = (aabb.min_z - ray_origin.z as f64) * inv_dir_z;
    let t2_z = (aabb.max_z - ray_origin.z as f64) * inv_dir_z;

    let t_min_x = t1_x.min(t2_x);
    let t_max_x = t1_x.max(t2_x);
    let t_min_y = t1_y.min(t2_y);
    let t_max_y = t1_y.max(t2_y);
    let t_min_z = t1_z.min(t2_z);
    let t_max_z = t1_z.max(t2_z);

    let t_min = t_min_x.max(t_min_y).max(t_min_z);
    let t_max = t_max_x.min(t_max_y).min(t_max_z);

    if t_min <= t_max && t_max >= 0.0 {
        Some(t_min.max(0.0) as f32)
    } else {
        None
    }
}

/// SIMD-accelerated batch ray-AABB intersection for multiple rays
pub fn batch_ray_aabb_intersect_simd(rays: &[(Vec3, Vec3)], aabb: &Aabb) -> Vec<Option<f32>> {
    #[cfg(target_feature = "avx2")]
    {
        use std::arch::x86_64::*;

        let aabb_min = _mm256_set1_ps(aabb.min_x as f32);
        let aabb_max = _mm256_set1_ps(aabb.max_x as f32);
        let aabb_min_y = _mm256_set1_ps(aabb.min_y as f32);
        let aabb_max_y = _mm256_set1_ps(aabb.max_y as f32);
        let aabb_min_z = _mm256_set1_ps(aabb.min_z as f32);
        let aabb_max_z = _mm256_set1_ps(aabb.max_z as f32);

        rays.par_chunks(8).flat_map(|chunk| {
            let mut results = vec![None; chunk.len()];

            for (i, (origin, dir)) in chunk.iter().enumerate() {
                let origin_x = _mm256_set1_ps(origin.x as f32);
                let origin_y = _mm256_set1_ps(origin.y as f32);
                let origin_z = _mm256_set1_ps(origin.z as f32);
                let dir_x = _mm256_set1_ps(dir.x as f32);
                let dir_y = _mm256_set1_ps(dir.y as f32);
                let dir_z = _mm256_set1_ps(dir.z as f32);

                // Calculate intersection for X axis
                let inv_dir_x = _mm256_div_ps(_mm256_set1_ps(1.0), dir_x);
                let t1_x = _mm256_mul_ps(_mm256_sub_ps(aabb_min, origin_x), inv_dir_x);
                let t2_x = _mm256_mul_ps(_mm256_sub_ps(aabb_max, origin_x), inv_dir_x);
                let t_min_x = _mm256_min_ps(t1_x, t2_x);
                let t_max_x = _mm256_max_ps(t1_x, t2_x);

                // Y axis
                let inv_dir_y = _mm256_div_ps(_mm256_set1_ps(1.0), dir_y);
                let t1_y = _mm256_mul_ps(_mm256_sub_ps(aabb_min_y, origin_y), inv_dir_y);
                let t2_y = _mm256_mul_ps(_mm256_sub_ps(aabb_max_y, origin_y), inv_dir_y);
                let t_min_y = _mm256_min_ps(t1_y, t2_y);
                let t_max_y = _mm256_max_ps(t1_y, t2_y);

                // Z axis
                let inv_dir_z = _mm256_div_ps(_mm256_set1_ps(1.0), dir_z);
                let t1_z = _mm256_mul_ps(_mm256_sub_ps(aabb_min_z, origin_z), inv_dir_z);
                let t2_z = _mm256_mul_ps(_mm256_sub_ps(aabb_max_z, origin_z), inv_dir_z);
                let t_min_z = _mm256_min_ps(t1_z, t2_z);
                let t_max_z = _mm256_max_ps(t1_z, t2_z);

                // Combine results
                let t_min = _mm256_max_ps(_mm256_max_ps(t_min_x, t_min_y), t_min_z);
                let t_max = _mm256_min_ps(_mm256_min_ps(t_max_x, t_max_y), t_max_z);

                let mut t_min_arr = [0.0f32; 8];
                let mut t_max_arr = [0.0f32; 8];
                _mm256_storeu_ps(t_min_arr.as_mut_ptr(), t_min);
                _mm256_storeu_ps(t_max_arr.as_mut_ptr(), t_max);

                if t_min_arr[0] <= t_max_arr[0] && t_max_arr[0] >= 0.0 {
                    results[i] = Some(t_min_arr[0].max(0.0));
                }
            }

            results
        }).collect()
    }
    #[cfg(not(target_feature = "avx2"))]
    {
        rays.iter().map(|(origin, dir)| ray_aabb_intersect_scalar(*origin, *dir, aabb)).collect()
    }
}

/// SIMD-accelerated swept AABB collision detection
pub fn swept_aabb_collision_simd(aabb1: &Aabb, velocity1: Vec3, aabb2: &Aabb, delta_time: f32) -> Option<f32> {
    #[cfg(target_feature = "avx2")]
    {
        use std::arch::x86_64::*;

        // Expand AABB2 by AABB1's size in the direction of movement
        let expanded_min_x = if velocity1.x > 0.0 {
            aabb2.min_x - aabb1.half_size().x as f64 * delta_time as f64
        } else {
            aabb2.min_x
        };
        let expanded_max_x = if velocity1.x < 0.0 {
            aabb2.max_x + aabb1.half_size().x as f64 * delta_time as f64
        } else {
            aabb2.max_x
        };

        let expanded_min_y = if velocity1.y > 0.0 {
            aabb2.min_y - aabb1.half_size().y as f64 * delta_time as f64
        } else {
            aabb2.min_y
        };
        let expanded_max_y = if velocity1.y < 0.0 {
            aabb2.max_y + aabb1.half_size().y as f64 * delta_time as f64
        } else {
            aabb2.max_y
        };

        let expanded_min_z = if velocity1.z > 0.0 {
            aabb2.min_z - aabb1.half_size().z as f64 * delta_time as f64
        } else {
            aabb2.min_z
        };
        let expanded_max_z = if velocity1.z < 0.0 {
            aabb2.max_z + aabb1.half_size().z as f64 * delta_time as f64
        } else {
            aabb2.max_z
        };

        let expanded_aabb = Aabb::new(expanded_min_x, expanded_min_y, expanded_min_z, expanded_max_x, expanded_max_y, expanded_max_z);

        // Check ray intersection with expanded AABB
        ray_aabb_intersect_simd(aabb1.center(), velocity1 * delta_time, &expanded_aabb)
    }
    #[cfg(not(target_feature = "avx2"))]
    {
        swept_aabb_collision_scalar(aabb1, velocity1, aabb2, delta_time)
    }
}

/// Scalar fallback for swept AABB collision
pub fn swept_aabb_collision_scalar(aabb1: &Aabb, velocity1: Vec3, aabb2: &Aabb, delta_time: f32) -> Option<f32> {
    // Expand AABB2 by AABB1's size in the direction of movement
    let expanded_min_x = if velocity1.x > 0.0 {
        aabb2.min_x - aabb1.half_size().x as f64 * delta_time as f64
    } else {
        aabb2.min_x
    };
    let expanded_max_x = if velocity1.x < 0.0 {
        aabb2.max_x + aabb1.half_size().x as f64 * delta_time as f64
    } else {
        aabb2.max_x
    };

    let expanded_min_y = if velocity1.y > 0.0 {
        aabb2.min_y - aabb1.half_size().y as f64 * delta_time as f64
    } else {
        aabb2.min_y
    };
    let expanded_max_y = if velocity1.y < 0.0 {
        aabb2.max_y + aabb1.half_size().y as f64 * delta_time as f64
    } else {
        aabb2.max_y
    };

    let expanded_min_z = if velocity1.z > 0.0 {
        aabb2.min_z - aabb1.half_size().z as f64 * delta_time as f64
    } else {
        aabb2.min_z
    };
    let expanded_max_z = if velocity1.z < 0.0 {
        aabb2.max_z + aabb1.half_size().z as f64 * delta_time as f64
    } else {
        aabb2.max_z
    };

    let expanded_aabb = Aabb::new(expanded_min_x, expanded_min_y, expanded_min_z, expanded_max_x, expanded_max_y, expanded_max_z);

    // Check ray intersection with expanded AABB
    ray_aabb_intersect_scalar(aabb1.center(), velocity1 * delta_time, &expanded_aabb)
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
        let mut stack = Vec::new();
        
        // Start with the root node
        stack.push(self);
        
        while let Some(current_node) = stack.pop() {
            // Skip if this node's bounds don't intersect with query bounds
            if !current_node.bounds.intersects(query_bounds) {
                continue;
            }
            
            // SIMD-accelerated entity filtering for better performance
            if current_node.entities.len() >= 8 {
                // Extract positions for SIMD processing
                let positions: Vec<(f64, f64, f64)> = current_node.entities
                    .iter()
                    .map(|(_, pos)| (pos[0], pos[1], pos[2]))
                    .collect();
                
                let contained = query_bounds.contains_points_simd(&positions);
                
                for (i, (_entity, _)) in current_node.entities.iter().enumerate() {
                    if contained[i] {
                        results.push(&current_node.entities[i]);
                    }
                }
            } else {
                // Fallback to scalar processing for small batches
                for entity in &current_node.entities {
                    if query_bounds.contains(entity.1[0], entity.1[1], entity.1[2]) {
                        results.push(entity);
                    }
                }
            }
            
            // Add children to stack for processing
            if let Some(ref children) = current_node.children {
                for child in children.iter() {
                    stack.push(child);
                }
            }
        }
        
        results
    }

    /// SIMD-optimized query with vectorized bounds checking and entity filtering
    pub fn query_simd(&self, query_bounds: &Aabb) -> Vec<&(T, [f64; 3])> {
        #[cfg(target_feature = "avx2")]
        {
            let mut results = Vec::new();
            let mut stack = Vec::new();
            
            // Start with the root node
            stack.push(self);
            
            while let Some(current_node) = stack.pop() {
                // SIMD-accelerated bounds intersection check
                if !current_node.bounds.intersects(query_bounds) {
                    continue;
                }
                
                // SIMD-accelerated entity filtering
                if current_node.entities.len() >= 8 {
                    let positions: Vec<(f64, f64, f64)> = current_node.entities
                        .iter()
                        .map(|(_, pos)| (pos[0], pos[1], pos[2]))
                        .collect();
                    
                    let contained = query_bounds.contains_points_simd(&positions);
                    
                    for (i, is_contained) in contained.iter().enumerate() {
                        if *is_contained {
                            results.push(&current_node.entities[i]);
                        }
                    }
                } else {
                    // Scalar fallback for small batches
                    for entity in &current_node.entities {
                        if query_bounds.contains(entity.1[0], entity.1[1], entity.1[2]) {
                            results.push(entity);
                        }
                    }
                }
                
                // Add children to stack for processing
                if let Some(ref children) = current_node.children {
                    for child in children.iter() {
                        stack.push(child);
                    }
                }
            }
            
            results
        }
        #[cfg(not(target_feature = "avx2"))]
        {
            // Fallback to standard query without SIMD
            self.query(query_bounds)
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

/// SIMD-accelerated distance calculation using the SIMD manager
pub fn calculate_distances_simd(positions: &[(f32, f32, f32)], center: (f32, f32, f32)) -> Vec<f32> {
    entity_processing::calculate_entity_distances(
        &positions.iter().map(|(x, y, z)| (*x, *y, *z)).collect::<Vec<_>>(),
        center
    )
}

/// SIMD-accelerated distance calculation using std::simd for better portability
pub fn calculate_distances_simd_portable(positions: &[(f32, f32, f32)], center: (f32, f32, f32)) -> Vec<f32> {
    #[cfg(target_feature = "avx2")]
    {
        use std::arch::x86_64::*;
        
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
                                     chunk.get(0).map(|p| p.1).unwrap_or(0.0));
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
        calculate_distances_simd(positions, center)
    }
}

/// SIMD-accelerated entity filtering with distance threshold using SIMD manager
pub fn filter_entities_by_distance_simd<T: Clone + Send + Sync>(
    entities: &[(T, [f64; 3])],
    center: [f64; 3],
    max_distance: f64,
) -> Vec<(T, [f64; 3])> {
    entity_processing::filter_entities_by_distance(
        entities,
        center,
        max_distance
    )
}

/// SIMD-accelerated chunk distance calculation (2D, ignoring Y) using SIMD manager
pub fn calculate_chunk_distances_simd(chunk_coords: &[(i32, i32)], center_chunk: (i32, i32)) -> Vec<f32> {
    vector_ops::calculate_chunk_distances(chunk_coords, center_chunk)
}

/// SIMD-accelerated AABB intersection batch processing using SIMD manager
pub fn batch_aabb_intersections(aabbs: &[Aabb], queries: &[Aabb]) -> Vec<Vec<bool>> {
    vector_ops::batch_aabb_intersections(aabbs, queries)
}

/// SIMD-accelerated QuadTree query with multiple bounds
pub fn batch_quadtree_queries<'a, T: Clone + PartialEq + Send + Sync>(
    quadtree: &'a QuadTree<T>,
    query_bounds: &'a [Aabb],
) -> Vec<Vec<&'a (T, [f64; 3])>> {
    query_bounds.par_iter().map(|bounds| quadtree.query_simd(bounds)).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use bevy_ecs::prelude::Entity;

    #[test]
    fn test_aabb_intersects_simd_batch() {
        let aabb1 = Aabb::new(0.0, 0.0, 0.0, 10.0, 10.0, 10.0);
        let test_aabbs = vec![
            Aabb::new(5.0, 5.0, 5.0, 15.0, 15.0, 15.0), // intersects
            Aabb::new(20.0, 20.0, 20.0, 30.0, 30.0, 30.0), // no intersection
            Aabb::new(2.0, 2.0, 2.0, 8.0, 8.0, 8.0), // intersects
            Aabb::new(-5.0, -5.0, -5.0, 5.0, 5.0, 5.0), // intersects
        ];

        let results = aabb1.intersects_simd_batch(&test_aabbs);
        
        assert_eq!(results.len(), 4);
        assert_eq!(results[0], true);  // intersects
        assert_eq!(results[1], false); // no intersection
        assert_eq!(results[2], true);  // intersects
        assert_eq!(results[3], true);  // intersects
    }

    #[test]
    fn test_contains_points_simd() {
        let aabb = Aabb::new(0.0, 0.0, 0.0, 10.0, 10.0, 10.0);
        let points = vec![
            (5.0, 5.0, 5.0),   // inside
            (15.0, 15.0, 15.0), // outside
            (0.0, 0.0, 0.0),   // on boundary (inside)
            (10.0, 10.0, 10.0), // on boundary (inside)
            (5.0, 15.0, 5.0),  // outside (y too high)
        ];

        let results = aabb.contains_points_simd(&points);
        
        assert_eq!(results.len(), 5);
        assert_eq!(results[0], true);  // inside
        assert_eq!(results[1], false); // outside
        assert_eq!(results[2], true);  // on boundary
        assert_eq!(results[3], true);  // on boundary
        assert_eq!(results[4], false); // outside
    }

    #[test]
    fn test_quadtree_query_simd() {
        let bounds = Aabb::new(-100.0, -100.0, -100.0, 100.0, 100.0, 100.0);
        let mut quadtree = QuadTree::new(bounds, 10, 5);
        
        // Insert some test entities
        quadtree.insert(Entity::from_raw(1), [10.0, 10.0, 10.0], 0);
        quadtree.insert(Entity::from_raw(2), [20.0, 20.0, 20.0], 0);
        quadtree.insert(Entity::from_raw(3), [50.0, 50.0, 50.0], 0);
        quadtree.insert(Entity::from_raw(4), [200.0, 200.0, 200.0], 0); // Outside query bounds
        
        let query_bounds = Aabb::new(0.0, 0.0, 0.0, 30.0, 30.0, 30.0);
        let results = quadtree.query_simd(&query_bounds);
        
        assert_eq!(results.len(), 2); // Should find entities 1 and 2
    }

    #[test]
    fn test_calculate_distances_simd() {
        let positions = vec![
            (0.0, 0.0, 0.0),
            (3.0, 4.0, 0.0), // 5 units away (3-4-5 triangle)
            (6.0, 8.0, 0.0), // 10 units away
            (1.0, 1.0, 1.0),
        ];
        let center = (0.0, 0.0, 0.0);
        
        let distances = calculate_distances_simd(&positions, center);
        
        assert_eq!(distances.len(), 4);
        assert!((distances[0] - 0.0).abs() < 0.001); // Distance to self
        assert!((distances[1] - 5.0).abs() < 0.001); // 3-4-5 triangle
        assert!((distances[2] - 10.0).abs() < 0.001); // Double the distance
        assert!((distances[3] - 1.732).abs() < 0.01); // sqrt(3) ≈ 1.732
    }

    #[test]
    fn test_filter_entities_by_distance_simd() {
        let entities = vec![
            (Entity::from_raw(1), [0.0, 0.0, 0.0]),
            (Entity::from_raw(2), [3.0, 4.0, 0.0]), // 5 units away
            (Entity::from_raw(3), [6.0, 8.0, 0.0]), // 10 units away
            (Entity::from_raw(4), [20.0, 20.0, 20.0]), // Far away
        ];
        let center = [0.0, 0.0, 0.0];
        let max_distance = 7.0; // Should include entities 1 and 2
        
        let results = filter_entities_by_distance_simd(&entities, center, max_distance);
        
        assert_eq!(results.len(), 2);
        assert_eq!(results[0].0, Entity::from_raw(1));
        assert_eq!(results[1].0, Entity::from_raw(2));
    }

    #[test]
    fn test_batch_aabb_intersections() {
        let aabbs = vec![
            Aabb::new(0.0, 0.0, 0.0, 10.0, 10.0, 10.0),
            Aabb::new(5.0, 5.0, 5.0, 15.0, 15.0, 15.0),
            Aabb::new(20.0, 20.0, 20.0, 30.0, 30.0, 30.0),
            Aabb::new(-5.0, -5.0, -5.0, 5.0, 5.0, 5.0),
        ];
        
        let queries = vec![
            Aabb::new(2.0, 2.0, 2.0, 8.0, 8.0, 8.0), // intersects with 0, 1, 3
            Aabb::new(25.0, 25.0, 25.0, 35.0, 35.0, 35.0), // intersects with 2
        ];
        
        let results = batch_aabb_intersections(&aabbs, &queries);
        
        assert_eq!(results.len(), 2); // Two query results
        assert_eq!(results[0].len(), 4); // Four AABBs tested against first query
        assert_eq!(results[1].len(), 4); // Four AABBs tested against second query
        
        // First query should intersect with AABBs 0, 1, and 3
        assert_eq!(results[0][0], true);  // AABB 0
        assert_eq!(results[0][1], true);  // AABB 1
        assert_eq!(results[0][2], false); // AABB 2
        assert_eq!(results[0][3], true);  // AABB 3
        
        // Second query should only intersect with AABB 2
        assert_eq!(results[1][0], false); // AABB 0
        assert_eq!(results[1][1], false); // AABB 1
        assert_eq!(results[1][2], true);  // AABB 2
        assert_eq!(results[1][3], false); // AABB 3
    }
}
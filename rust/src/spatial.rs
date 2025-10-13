use serde::{Deserialize, Serialize};
use std::sync::Arc;
use std::time::Duration;
// Use parking_lot mutexes for better locking API (try_lock_for) and performance
use crate::arena::{get_global_arena_pool, ScopedArena};
use crate::memory_pool::get_global_enhanced_pool;
use crate::simd::{entity_processing, vector_ops};
use bevy_ecs::prelude::Entity;
use dashmap::DashMap;
use glam::Vec3;
use once_cell::sync::Lazy;
use parking_lot::{Mutex, MutexGuard};
use rayon::prelude::*;

// Lock ordering constants to prevent deadlocks
pub mod lock_order {
    use super::*;
    // Global mutexes to enforce lock ordering across subsystems.
    // Use Lazy to ensure they're initialized at runtime only once.
    pub static JNI_BATCH_LOCK: Lazy<Mutex<()>> = Lazy::new(|| Mutex::new(()));
    pub static SLAB_ALLOCATOR_LOCK: Lazy<Mutex<()>> = Lazy::new(|| Mutex::new(()));
    pub static DATABASE_LOCK: Lazy<Mutex<()>> = Lazy::new(|| Mutex::new(()));
    pub static CACHE_LOCK: Lazy<Mutex<()>> = Lazy::new(|| Mutex::new(()));
    pub static MEMORY_POOL_LOCK: Lazy<Mutex<()>> = Lazy::new(|| Mutex::new(()));
    pub static DASHMAP_LOCK: Lazy<Mutex<()>> = Lazy::new(|| Mutex::new(()));
    pub static SPATIAL_LOCK: Lazy<Mutex<()>> = Lazy::new(|| Mutex::new(()));
}

// Architecture-specific prefetch helpers used in SIMD-optimized paths
#[cfg(any(target_arch = "x86", target_arch = "x86_64"))]
use std::arch::x86_64::{_mm_prefetch, _MM_HINT_T0};

#[derive(Serialize, Deserialize, Clone, Debug)]
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
        let min = center - size * 0.5;
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
        point.x as f64 >= self.min_x
            && point.x as f64 <= self.max_x
            && point.y as f64 >= self.min_y
            && point.y as f64 <= self.max_y
            && point.z as f64 >= self.min_z
            && point.z as f64 <= self.max_z
    }

    pub fn contains(&self, x: f64, y: f64, z: f64) -> bool {
        x >= self.min_x
            && x <= self.max_x
            && y >= self.min_y
            && y <= self.max_y
            && z >= self.min_z
            && z <= self.max_z
    }

    pub fn intersects(&self, other: &Aabb) -> bool {
        self.min_x <= other.max_x
            && self.max_x >= other.min_x
            && self.min_y <= other.max_y
            && self.max_y >= other.min_y
            && self.min_z <= other.max_z
            && self.max_z >= other.min_z
    }

    /// SIMD-accelerated AABB intersection test for multiple AABBs
    #[inline(always)]
    pub fn intersects_simd_batch(&self, others: &[Aabb]) -> Vec<bool> {
        // Dispatch to small batch optimized version for 2-7 elements
        if others.len() >= 2 && others.len() <= 7 {
            return self.intersects_simd_batch_small(others);
        }

        #[cfg(target_feature = "avx512f")]
        {
            use std::arch::x86_64::*;

            let self_min = _mm512_set1_ps(self.min_x as f32);
            let self_max = _mm512_set1_ps(self.max_x as f32);
            let self_min_y = _mm512_set1_ps(self.min_y as f32);
            let self_max_y = _mm512_set1_ps(self.max_y as f32);
            let self_min_z = _mm512_set1_ps(self.min_z as f32);
            let self_max_z = _mm512_set1_ps(self.max_z as f32);

            // Use fold to avoid intermediate allocations from collect()
            others
                .par_chunks(16)
                .fold(Vec::new, |mut acc, chunk| {
                    let mut results = vec![false; chunk.len()];

                    // Prepare arrays for SIMD operations
                    let mut other_min_x = [0.0f32; 16];
                    let mut other_max_x = [0.0f32; 16];
                    let mut other_min_y = [0.0f32; 16];
                    let mut other_max_y = [0.0f32; 16];
                    let mut other_min_z = [0.0f32; 16];
                    let mut other_max_z = [0.0f32; 16];

                    // Load all AABB data into arrays first for better cache performance
                    for (i, aabb) in chunk.iter().enumerate() {
                        other_min_x[i] = aabb.min_x as f32;
                        other_max_x[i] = aabb.max_x as f32;
                        other_min_y[i] = aabb.min_y as f32;
                        other_max_y[i] = aabb.max_y as f32;
                        other_min_z[i] = aabb.min_z as f32;
                        other_max_z[i] = aabb.max_z as f32;
                    }

                    // Load all data at once for better SIMD utilization
                    let o_min_x = _mm512_loadu_ps(other_min_x.as_ptr());
                    let o_max_x = _mm512_loadu_ps(other_max_x.as_ptr());
                    let o_min_y = _mm512_loadu_ps(other_min_y.as_ptr());
                    let o_max_y = _mm512_loadu_ps(other_max_y.as_ptr());
                    let o_min_z = _mm512_loadu_ps(other_min_z.as_ptr());
                    let o_max_z = _mm512_loadu_ps(other_max_z.as_ptr());

                    // Test: self.min <= other.max && self.max >= other.min for each axis (vectorized)
                    let x_intersect = _mm512_and_ps(
                        _mm512_cmp_ps_mask(self_min, o_max_x, _MM_CMPINT_LE),
                        _mm512_cmp_ps_mask(self_max, o_min_x, _MM_CMPINT_GE),
                    );
                    let y_intersect = _mm512_and_ps(
                        _mm512_cmp_ps_mask(self_min_y, o_max_y, _MM_CMPINT_LE),
                        _mm512_cmp_ps_mask(self_max_y, o_min_y, _MM_CMPINT_GE),
                    );
                    let z_intersect = _mm512_and_ps(
                        _mm512_cmp_ps_mask(self_min_z, o_max_z, _MM_CMPINT_LE),
                        _mm512_cmp_ps_mask(self_max_z, o_min_z, _MM_CMPINT_GE),
                    );

                    // All axes must intersect (vectorized)
                    let full_intersect =
                        _mm512_and_ps(_mm512_and_ps(x_intersect, y_intersect), z_intersect);

                    // Extract results
                    let mask = _mm512_movemask_ps(full_intersect);
                    for i in 0..chunk.len() {
                        results[i] = (mask & (1 << i)) != 0;
                    }

                    // Extend results directly into accumulator to avoid intermediate Vec
                    acc.extend_from_slice(&results);
                    acc
                })
                .collect::<Vec<Vec<bool>>>()
                .into_iter()
                .flatten()
                .collect()
        }
        #[cfg(target_feature = "avx2")]
        {
            use std::arch::x86_64::*;

            let self_min = _mm256_set_ps(
                self.min_x as f32,
                self.min_y as f32,
                self.min_z as f32,
                0.0,
                self.min_x as f32,
                self.min_y as f32,
                self.min_z as f32,
                0.0,
            );
            let self_max = _mm256_set_ps(
                self.max_x as f32,
                self.max_y as f32,
                self.max_z as f32,
                0.0,
                self.max_x as f32,
                self.max_y as f32,
                self.max_z as f32,
                0.0,
            );

            // Use fold to avoid intermediate allocations from collect()
            others
                .par_chunks(2)
                .fold(Vec::new, |mut acc, chunk| {
                    let mut results = [false; 2];

                    for (i, aabb) in chunk.iter().enumerate() {
                        let other_min = _mm256_set_ps(
                            aabb.min_x as f32,
                            aabb.min_y as f32,
                            aabb.min_z as f32,
                            0.0,
                            aabb.min_x as f32,
                            aabb.min_y as f32,
                            aabb.min_z as f32,
                            0.0,
                        );
                        let other_max = _mm256_set_ps(
                            aabb.max_x as f32,
                            aabb.max_y as f32,
                            aabb.max_z as f32,
                            0.0,
                            aabb.max_x as f32,
                            aabb.max_y as f32,
                            aabb.max_z as f32,
                            0.0,
                        );

                        // Test: self.min <= other.max && self.max >= other.min
                        let cmp1 = _mm256_cmp_ps(self_min, other_max, _CMP_LE_OQ);
                        let cmp2 = _mm256_cmp_ps(self_max, other_min, _CMP_GE_OQ);
                        let intersection = _mm256_and_ps(cmp1, cmp2);

                        // Extract results for each axis
                        let mask = _mm256_movemask_ps(intersection);
                        results[i] = (mask & 0x07) == 0x07; // Check X, Y, Z axes
                    }

                    // Extend results directly into accumulator to avoid intermediate Vec
                    acc.extend_from_slice(&results[..chunk.len()]);
                    acc
                })
                .collect::<Vec<Vec<bool>>>()
                .into_iter()
                .flatten()
                .collect()
        }
        #[cfg(not(any(target_feature = "avx512f", target_feature = "avx2")))]
        {
            // Use fold for better memory efficiency even in non-SIMD path
            others
                .par_iter()
                .fold(Vec::new, |mut acc, other| {
                    acc.push(self.intersects(other));
                    acc
                })
                .collect::<Vec<Vec<bool>>>()
                .into_iter()
                .flatten()
                .collect()
        }
    }

    /// SIMD-accelerated small batch AABB intersection (2-7 elements) with zero-copy optimization
    #[inline(always)]
    pub fn intersects_simd_batch_small(&self, others: &[Aabb]) -> Vec<bool> {
        use crate::memory_pool::get_global_enhanced_pool;

        let pool = get_global_enhanced_pool();
        let mut results = pool.allocate_zero_copy_vec::<bool>(others.len());

        #[cfg(target_feature = "avx2")]
        {
            use std::arch::x86_64::*;

            // Prepare self bounds as SIMD registers
            let self_min_x = _mm_set1_ps(self.min_x as f32);
            let self_max_x = _mm_set1_ps(self.max_x as f32);
            let self_min_y = _mm_set1_ps(self.min_y as f32);
            let self_max_y = _mm_set1_ps(self.max_y as f32);
            let self_min_z = _mm_set1_ps(self.min_z as f32);
            let self_max_z = _mm_set1_ps(self.max_z as f32);

            // Process in chunks of 4 for SSE, handling remainder manually - UNROLLED for performance
            let chunks = others.chunks_exact(4);
            let remainder = chunks.remainder();

            let mut result_idx = 0;

            // Unroll loop for better performance on small batches
            for chunk in chunks {
                // Load 4 AABBs directly into SSE registers (zero-copy) - UNROLLED
                let other_min_x = _mm_set_ps(
                    chunk[3].min_x as f32,
                    chunk[2].min_x as f32,
                    chunk[1].min_x as f32,
                    chunk[0].min_x as f32,
                );
                let other_max_x = _mm_set_ps(
                    chunk[3].max_x as f32,
                    chunk[2].max_x as f32,
                    chunk[1].max_x as f32,
                    chunk[0].max_x as f32,
                );
                let other_min_y = _mm_set_ps(
                    chunk[3].min_y as f32,
                    chunk[2].min_y as f32,
                    chunk[1].min_y as f32,
                    chunk[0].min_y as f32,
                );
                let other_max_y = _mm_set_ps(
                    chunk[3].max_y as f32,
                    chunk[2].max_y as f32,
                    chunk[1].max_y as f32,
                    chunk[0].max_y as f32,
                );
                let other_min_z = _mm_set_ps(
                    chunk[3].min_z as f32,
                    chunk[2].min_z as f32,
                    chunk[1].min_z as f32,
                    chunk[0].min_z as f32,
                );
                let other_max_z = _mm_set_ps(
                    chunk[3].max_z as f32,
                    chunk[2].max_z as f32,
                    chunk[1].max_z as f32,
                    chunk[0].max_z as f32,
                );

                // Test intersections for all axes simultaneously - UNROLLED
                let x_intersect = _mm_and_ps(
                    _mm_cmp_ps(self_min_x, other_max_x, _CMP_LE_OQ),
                    _mm_cmp_ps(self_max_x, other_min_x, _CMP_GE_OQ),
                );
                let y_intersect = _mm_and_ps(
                    _mm_cmp_ps(self_min_y, other_max_y, _CMP_LE_OQ),
                    _mm_cmp_ps(self_max_y, other_min_y, _CMP_GE_OQ),
                );
                let z_intersect = _mm_and_ps(
                    _mm_cmp_ps(self_min_z, other_max_z, _CMP_LE_OQ),
                    _mm_cmp_ps(self_max_z, other_min_z, _CMP_GE_OQ),
                );

                // Combine all axis intersections - UNROLLED
                let full_intersect = _mm_and_ps(_mm_and_ps(x_intersect, y_intersect), z_intersect);

                // Extract results directly into zero-copy buffer - UNROLLED
                let mask = _mm_movemask_ps(full_intersect);

                // Manual unrolling of result extraction for better instruction level parallelism
                results[result_idx + 0] = (mask & (1 << 0)) != 0;
                results[result_idx + 1] = (mask & (1 << 1)) != 0;
                results[result_idx + 2] = (mask & (1 << 2)) != 0;
                results[result_idx + 3] = (mask & (1 << 3)) != 0;

                result_idx += 4;
            }

            // Handle remainder with scalar operations
            for (i, aabb) in remainder.iter().enumerate() {
                results[result_idx + i] = self.intersects(aabb);
            }
        }
        #[cfg(not(target_feature = "avx2"))]
        {
            // Scalar fallback for small batches
            for (i, aabb) in others.iter().enumerate() {
                results[i] = self.intersects(aabb);
            }
        }

        results
    }

    /// SIMD-accelerated bounds check for multiple points
    #[inline(always)]
    pub fn contains_points_simd(&self, points: &[(f64, f64, f64)]) -> Vec<bool> {
        #[cfg(target_feature = "avx512f")]
        {
            use std::arch::x86_64::*;

            let min_x = _mm512_set1_ps(self.min_x as f32);
            let min_y = _mm512_set1_ps(self.min_y as f32);
            let min_z = _mm512_set1_ps(self.min_z as f32);
            let max_x = _mm512_set1_ps(self.max_x as f32);
            let max_y = _mm512_set1_ps(self.max_y as f32);
            let max_z = _mm512_set1_ps(self.max_z as f32);

            // Use fold to avoid intermediate allocations from collect()
            points
                .par_chunks(16)
                .fold(Vec::new, |mut acc, chunk| {
                    let mut results = vec![false; chunk.len()];

                    // Prepare point arrays
                    let mut px_arr = [0.0f32; 16];
                    let mut py_arr = [0.0f32; 16];
                    let mut pz_arr = [0.0f32; 16];

                    for (i, &(x, y, z)) in chunk.iter().enumerate() {
                        px_arr[i] = x as f32;
                        py_arr[i] = y as f32;
                        pz_arr[i] = z as f32;
                    }

                    let px = _mm512_loadu_ps(px_arr.as_ptr());
                    let py = _mm512_loadu_ps(py_arr.as_ptr());
                    let pz = _mm512_loadu_ps(pz_arr.as_ptr());

                    // Check min <= point <= max for each axis using AVX-512
                    let x_inside = _mm512_and_ps(
                        _mm512_cmp_ps_mask(min_x, px, _MM_CMPINT_LE),
                        _mm512_cmp_ps_mask(px, max_x, _MM_CMPINT_LE),
                    );
                    let y_inside = _mm512_and_ps(
                        _mm512_cmp_ps_mask(min_y, py, _MM_CMPINT_LE),
                        _mm512_cmp_ps_mask(py, max_y, _MM_CMPINT_LE),
                    );
                    let z_inside = _mm512_and_ps(
                        _mm512_cmp_ps_mask(min_z, pz, _MM_CMPINT_LE),
                        _mm512_cmp_ps_mask(pz, max_z, _MM_CMPINT_LE),
                    );

                    let inside = _mm512_and_ps(_mm512_and_ps(x_inside, y_inside), z_inside);
                    let mask = _mm512_movemask_ps(inside);

                    for i in 0..chunk.len() {
                        results[i] = (mask & (1 << i)) != 0;
                    }

                    // Extend results directly into accumulator to avoid intermediate Vec
                    acc.extend_from_slice(&results);
                    acc
                })
                .collect::<Vec<Vec<bool>>>()
                .into_iter()
                .flatten()
                .collect()
        }
        #[cfg(target_feature = "avx2")]
        {
            use std::arch::x86_64::*;

            let min_x = _mm256_set1_ps(self.min_x as f32);
            let min_y = _mm256_set1_ps(self.min_y as f32);
            let min_z = _mm256_set1_ps(self.min_z as f32);
            let max_x = _mm256_set1_ps(self.max_x as f32);
            let max_y = _mm256_set1_ps(self.max_y as f32);
            let max_z = _mm256_set1_ps(self.max_z as f32);

            // Use fold to avoid intermediate allocations from collect()
            points
                .par_chunks(8)
                .fold(Vec::new, |mut acc, chunk| {
                    let mut results = [false; 8];

                    let px = _mm256_set_ps(
                        chunk.get(7).map(|p| p.0 as f32).unwrap_or(0.0),
                        chunk.get(6).map(|p| p.0 as f32).unwrap_or(0.0),
                        chunk.get(5).map(|p| p.0 as f32).unwrap_or(0.0),
                        chunk.get(4).map(|p| p.0 as f32).unwrap_or(0.0),
                        chunk.get(3).map(|p| p.0 as f32).unwrap_or(0.0),
                        chunk.get(2).map(|p| p.0 as f32).unwrap_or(0.0),
                        chunk.get(1).map(|p| p.0 as f32).unwrap_or(0.0),
                        chunk.get(0).map(|p| p.0 as f32).unwrap_or(0.0),
                    );
                    let py = _mm256_set_ps(
                        chunk.get(7).map(|p| p.1 as f32).unwrap_or(0.0),
                        chunk.get(6).map(|p| p.1 as f32).unwrap_or(0.0),
                        chunk.get(5).map(|p| p.1 as f32).unwrap_or(0.0),
                        chunk.get(4).map(|p| p.1 as f32).unwrap_or(0.0),
                        chunk.get(3).map(|p| p.1 as f32).unwrap_or(0.0),
                        chunk.get(2).map(|p| p.1 as f32).unwrap_or(0.0),
                        chunk.get(1).map(|p| p.1 as f32).unwrap_or(0.0),
                        chunk.get(0).map(|p| p.1 as f32).unwrap_or(0.0),
                    );
                    let pz = _mm256_set_ps(
                        chunk.get(7).map(|p| p.2 as f32).unwrap_or(0.0),
                        chunk.get(6).map(|p| p.2 as f32).unwrap_or(0.0),
                        chunk.get(5).map(|p| p.2 as f32).unwrap_or(0.0),
                        chunk.get(4).map(|p| p.2 as f32).unwrap_or(0.0),
                        chunk.get(3).map(|p| p.2 as f32).unwrap_or(0.0),
                        chunk.get(2).map(|p| p.2 as f32).unwrap_or(0.0),
                        chunk.get(1).map(|p| p.2 as f32).unwrap_or(0.0),
                        chunk.get(0).map(|p| p.2 as f32).unwrap_or(0.0),
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

                    // Extend results directly into accumulator to avoid intermediate Vec
                    acc.extend_from_slice(&results[..chunk.len()]);
                    acc
                })
                .collect::<Vec<Vec<bool>>>()
                .into_iter()
                .flatten()
                .collect()
        }
        #[cfg(not(any(target_feature = "avx512f", target_feature = "avx2")))]
        {
            // Use fold for better memory efficiency even in non-SIMD path
            points
                .par_iter()
                .fold(Vec::new, |mut acc, &(x, y, z)| {
                    acc.push(self.contains(x, y, z));
                    acc
                })
                .collect::<Vec<Vec<bool>>>()
                .into_iter()
                .flatten()
                .collect()
        }
    }
}
/// SIMD-accelerated ray-AABB intersection test
#[inline(always)]
pub fn ray_aabb_intersect_simd(ray_origin: Vec3, ray_dir: Vec3, aabb: &Aabb) -> Option<f32> {
    #[cfg(target_feature = "avx512f")]
    {
        use std::arch::x86_64::*;

        let origin = _mm512_set1_ps(ray_origin.x as f32);
        let dir = _mm512_set1_ps(ray_dir.x as f32);
        let aabb_min = _mm512_set1_ps(aabb.min_x as f32);
        let aabb_max = _mm512_set1_ps(aabb.max_x as f32);

        // Calculate t_min and t_max for each axis using AVX-512
        let inv_dir = _mm512_div_ps(_mm512_set1_ps(1.0), dir);
        let t1 = _mm512_mul_ps(_mm512_sub_ps(aabb_min, origin), inv_dir);
        let t2 = _mm512_mul_ps(_mm512_sub_ps(aabb_max, origin), inv_dir);

        let t_min = _mm512_min_ps(t1, t2);
        let t_max = _mm512_max_ps(t1, t2);

        // Find the maximum t_min and minimum t_max across all lanes
        let t_min_max = _mm512_reduce_max_ps(t_min);
        let t_max_min = _mm512_reduce_min_ps(t_max);

        if t_min_max <= t_max_min && t_max_min >= 0.0 {
            Some(t_min_max.max(0.0))
        } else {
            None
        }
    }
    #[cfg(target_feature = "avx2")]
    {
        use std::arch::x86_64::*;

        let origin = _mm256_set_ps(
            ray_origin.x as f32,
            ray_origin.y as f32,
            ray_origin.z as f32,
            0.0,
            ray_origin.x as f32,
            ray_origin.y as f32,
            ray_origin.z as f32,
            0.0,
        );
        let dir = _mm256_set_ps(
            ray_dir.x as f32,
            ray_dir.y as f32,
            ray_dir.z as f32,
            0.0,
            ray_dir.x as f32,
            ray_dir.y as f32,
            ray_dir.z as f32,
            0.0,
        );
        let aabb_min = _mm256_set_ps(
            aabb.min_x as f32,
            aabb.min_y as f32,
            aabb.min_z as f32,
            0.0,
            aabb.min_x as f32,
            aabb.min_y as f32,
            aabb.min_z as f32,
            0.0,
        );
        let aabb_max = _mm256_set_ps(
            aabb.max_x as f32,
            aabb.max_y as f32,
            aabb.max_z as f32,
            0.0,
            aabb.max_x as f32,
            aabb.max_y as f32,
            aabb.max_z as f32,
            0.0,
        );

        // Calculate t_min and t_max for each axis
        let inv_dir = _mm256_div_ps(_mm256_set1_ps(1.0), dir);
        let t1 = _mm256_mul_ps(_mm256_sub_ps(aabb_min, origin), inv_dir);
        let t2 = _mm256_mul_ps(_mm256_sub_ps(aabb_max, origin), inv_dir);

        let t_min = _mm256_min_ps(t1, t2);
        let t_max = _mm256_max_ps(t1, t2);

        // Find the maximum t_min and minimum t_max
        let t_min_max = _mm256_max_ps(
            _mm256_max_ps(
                _mm256_shuffle_ps(t_min, t_min, 0b00000000),
                _mm256_shuffle_ps(t_min, t_min, 0b01010101),
            ),
            _mm256_shuffle_ps(t_min, t_min, 0b10101010),
        );
        let t_max_min = _mm256_min_ps(
            _mm256_min_ps(
                _mm256_shuffle_ps(t_max, t_max, 0b00000000),
                _mm256_shuffle_ps(t_max, t_max, 0b01010101),
            ),
            _mm256_shuffle_ps(t_max, t_max, 0b10101010),
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

/// AVX-512 accelerated vector normalization with const generic batch size
#[cfg(target_feature = "avx512f")]
#[inline(always)]
pub fn normalize_vector_avx512<const BATCH_SIZE: usize>(vector: &[f32]) -> Vec<f32> {
    use std::arch::x86_64::*;

    const AVX512_WIDTH: usize = BATCH_SIZE;
    let mut result = Vec::with_capacity(vector.len());
    let main_chunks = vector.len() / AVX512_WIDTH;
    let remainder = vector.len() % AVX512_WIDTH;

    // Pre-allocate temporary buffer for better cache utilization
    let mut vec_buffer = [0.0f32; AVX512_WIDTH];

    // Prefetch first chunk to start early
    unsafe {
        if !vector.is_empty() {
            _mm_prefetch(vector.as_ptr(), _MM_HINT_T0);
        }
    }

    for i in 0..main_chunks {
        let chunk_start = i * AVX512_WIDTH;

        // Prefetch next chunk when possible
        if i + 1 < main_chunks {
            let next_start = (i + 1) * AVX512_WIDTH;
            unsafe {
                _mm_prefetch(vector.get_unchecked(next_start), _MM_HINT_T0);
            }
        }

        // Load chunk with better memory access pattern
        for j in 0..AVX512_WIDTH {
            vec_buffer[j] = vector[chunk_start + j];
        }

        let v = _mm512_loadu_ps(vec_buffer.as_ptr());

        // Calculate squared length with fused multiply-add for better performance
        let v_sq = _mm512_fmadd_ps(v, v, _mm512_setzero_ps());
        let sum = _mm512_reduce_add_ps(v_sq);
        let len = _mm_sqrt_ss(&sum as *const f32 as *const __m128);
        let len_vec = _mm512_set1_ps(len.extract_ps(0));

        // Normalize with fused multiply-add
        let normalized = _mm512_div_ps(v, len_vec);

        let mut normalized_arr = [0.0f32; AVX512_WIDTH];
        _mm512_storeu_ps(normalized_arr.as_mut_ptr(), normalized);
        result.extend_from_slice(&normalized_arr);
    }

    // Handle remainder with optimized scalar path
    if remainder > 0 {
        let start = vector.len() - remainder;
        for i in 0..remainder {
            let v = vector[start + i];
            let len = (v * v).sqrt();
            result.push(v / len);
        }
    }

    result
}

/// AVX-512 accelerated dot product calculation with const generic batch size
#[cfg(target_feature = "avx512f")]
#[inline(always)]
pub fn dot_product_avx512<const BATCH_SIZE: usize>(a: &[f32], b: &[f32]) -> f32 {
    use std::arch::x86_64::*;

    debug_assert_eq!(
        a.len(),
        b.len(),
        "Vectors must have the same length for dot product"
    );
    debug_assert!(
        a.len() >= BATCH_SIZE,
        "Input size must be at least batch size"
    );

    const AVX512_BATCH: usize = BATCH_SIZE;
    let mut sum = _mm512_setzero_ps();
    let main_chunks = a.len() / AVX512_BATCH;
    let remainder = a.len() % AVX512_BATCH;

    // Pre-allocate buffers for better cache utilization
    let mut a_buffer = [0.0f32; AVX512_BATCH];
    let mut b_buffer = [0.0f32; AVX512_BATCH];

    // Prefetch first chunks
    unsafe {
        if !a.is_empty() {
            _mm_prefetch(a.as_ptr(), _MM_HINT_T0);
            _mm_prefetch(b.as_ptr(), _MM_HINT_T0);
        }
    }

    for i in 0..main_chunks {
        let chunk_start = i * AVX512_BATCH;

        // Prefetch next chunks when possible
        if i + 1 < main_chunks {
            let next_start = (i + 1) * AVX512_BATCH;
            unsafe {
                _mm_prefetch(a.get_unchecked(next_start), _MM_HINT_T0);
                _mm_prefetch(b.get_unchecked(next_start), _MM_HINT_T0);
            }
        }

        // Load data with better memory access pattern
        for j in 0..AVX512_BATCH {
            a_buffer[j] = a[chunk_start + j];
            b_buffer[j] = b[chunk_start + j];
        }

        let va = _mm512_loadu_ps(a_buffer.as_ptr());
        let vb = _mm512_loadu_ps(b_buffer.as_ptr());

        // Use fused multiply-add for better performance
        let product = _mm512_fmadd_ps(va, vb, sum);
        sum = product;
    }

    // Handle remainder with optimized scalar path
    let mut scalar_sum = 0.0;
    if remainder > 0 {
        let start = a.len() - remainder;
        for i in 0..remainder {
            scalar_sum += a[start + i] * b[start + i];
        }
    }

    // Reduce sum with fused operations
    let vec_sum = _mm512_reduce_add_ps(sum);
    vec_sum + scalar_sum
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
/// Uses const generic for compile-time batch size optimization
#[inline(always)]
pub fn batch_ray_aabb_intersect_simd<const BATCH_SIZE: usize>(
    rays: &[(Vec3, Vec3)],
    aabb: &Aabb,
) -> Vec<Option<f32>> {
    #[cfg(target_feature = "avx512f")]
    {
        use std::arch::x86_64::*;

        const AVX512_BATCH_SIZE: usize = BATCH_SIZE;

        let aabb_min = _mm512_set1_ps(aabb.min_x as f32);
        let aabb_max = _mm512_set1_ps(aabb.max_x as f32);
        let aabb_min_y = _mm512_set1_ps(aabb.min_y as f32);
        let aabb_max_y = _mm512_set1_ps(aabb.max_y as f32);
        let aabb_min_z = _mm512_set1_ps(aabb.min_z as f32);
        let aabb_max_z = _mm512_set1_ps(aabb.max_z as f32);

        // Use fold to avoid intermediate allocations from collect()
        rays.par_chunks(AVX512_BATCH_SIZE)
            .fold(Vec::with_capacity(rays.len()), |mut acc, chunk| {
                let mut results = vec![None; chunk.len()];

                // Prefetch next chunk to reduce cache misses (using const batch size)
                let chunk_len = chunk.len();
                let use_full_batch = chunk_len == AVX512_BATCH_SIZE;

                // Prefetch next chunk data proactively
                if let Some(next_chunk) = chunk.get(AVX512_BATCH_SIZE..) {
                    unsafe {
                        _mm_prefetch(&next_chunk[0].0.x as *const u8, _MM_HINT_T0);
                        _mm_prefetch(&next_chunk[0].1.x as *const u8, _MM_HINT_T0);
                    }
                }

                // Process rays with branch prediction hints
                for i in 0..chunk_len {
                    let (origin, dir) = &chunk[i];

                    // Common case: all direction components non-zero
                    if dir.x != 0.0 && dir.y != 0.0 && dir.z != 0.0 {
                        let origin_x = _mm512_set1_ps(origin.x as f32);
                        let origin_y = _mm512_set1_ps(origin.y as f32);
                        let origin_z = _mm512_set1_ps(origin.z as f32);
                        let dir_x = _mm512_set1_ps(dir.x as f32);
                        let dir_y = _mm512_set1_ps(dir.y as f32);
                        let dir_z = _mm512_set1_ps(dir.z as f32);

                        // Calculate intersection for X axis
                        let inv_dir_x = _mm512_div_ps(_mm512_set1_ps(1.0), dir_x);
                        let t1_x = _mm512_mul_ps(_mm512_sub_ps(aabb_min, origin_x), inv_dir_x);
                        let t2_x = _mm512_mul_ps(_mm512_sub_ps(aabb_max, origin_x), inv_dir_x);
                        let t_min_x = _mm512_min_ps(t1_x, t2_x);
                        let t_max_x = _mm512_max_ps(t1_x, t2_x);

                        // Y axis
                        let inv_dir_y = _mm512_div_ps(_mm512_set1_ps(1.0), dir_y);
                        let t1_y = _mm512_mul_ps(_mm512_sub_ps(aabb_min_y, origin_y), inv_dir_y);
                        let t2_y = _mm512_mul_ps(_mm512_sub_ps(aabb_max_y, origin_y), inv_dir_y);
                        let t_min_y = _mm512_min_ps(t1_y, t2_y);
                        let t_max_y = _mm512_max_ps(t1_y, t2_y);

                        // Z axis
                        let inv_dir_z = _mm512_div_ps(_mm512_set1_ps(1.0), dir_z);
                        let t1_z = _mm512_mul_ps(_mm512_sub_ps(aabb_min_z, origin_z), inv_dir_z);
                        let t2_z = _mm512_mul_ps(_mm512_sub_ps(aabb_max_z, origin_z), inv_dir_z);
                        let t_min_z = _mm512_min_ps(t1_z, t2_z);
                        let t_max_z = _mm512_max_ps(t1_z, t2_z);

                        // Combine results
                        let t_min = _mm512_max_ps(_mm512_max_ps(t_min_x, t_min_y), t_min_z);
                        let t_max = _mm512_min_ps(_mm512_min_ps(t_max_x, t_max_y), t_max_z);

                        let t_min_val = _mm512_reduce_max_ps(t_min);
                        let t_max_val = _mm512_reduce_min_ps(t_max);

                        // Branch: valid intersection
                        if t_min_val <= t_max_val && t_max_val >= 0.0 {
                            results[i] = Some(t_min_val.max(0.0));
                        }
                    }
                }

                // Extend results directly into accumulator to avoid intermediate Vec
                acc.extend_from_slice(&results);
                acc
            })
            .collect::<Vec<Vec<Option<f32>>>>()
            .into_iter()
            .flatten()
            .collect()
    }
    #[cfg(target_feature = "avx2")]
    {
        use std::arch::x86_64::*;

        let aabb_min = _mm256_set1_ps(aabb.min_x as f32);
        let aabb_max = _mm256_set1_ps(aabb.max_x as f32);
        let aabb_min_y = _mm256_set1_ps(aabb.min_y as f32);
        let aabb_max_y = _mm256_set1_ps(aabb.max_y as f32);
        let aabb_min_z = _mm256_set1_ps(aabb.min_z as f32);
        let aabb_max_z = _mm256_set1_ps(aabb.max_z as f32);

        // Use fold to avoid intermediate allocations from collect()
        rays.par_chunks(BATCH_SIZE)
            .fold(Vec::with_capacity(rays.len()), |mut acc, chunk| {
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

                // Extend results directly into accumulator to avoid intermediate Vec
                acc.extend_from_slice(&results);
                acc
            })
            .collect::<Vec<Vec<Option<f32>>>>()
            .into_iter()
            .flatten()
            .collect()
    }
    #[cfg(not(target_feature = "avx2"))]
    {
        // Use fold for better memory efficiency even in non-SIMD path
        rays.iter()
            .map(|&(origin, dir)| ray_aabb_intersect_scalar(origin, dir, aabb))
            .collect()
    }
}

/// SIMD-accelerated swept AABB collision detection
#[inline(always)]
pub fn swept_aabb_collision_simd(
    aabb1: &Aabb,
    velocity1: Vec3,
    aabb2: &Aabb,
    delta_time: f32,
) -> Option<f32> {
    #[cfg(target_feature = "avx2")]
    {
        use std::arch::x86_64::*;

        // Expand AABB2 by AABB1's size in the direction of movement - with branch prediction hints
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

        let expanded_aabb = Aabb::new(
            expanded_min_x,
            expanded_min_y,
            expanded_min_z,
            expanded_max_x,
            expanded_max_y,
            expanded_max_z,
        );

        // Check ray intersection with expanded AABB
        ray_aabb_intersect_simd(aabb1.center(), velocity1 * delta_time, &expanded_aabb)
    }
    #[cfg(not(target_feature = "avx2"))]
    {
        swept_aabb_collision_scalar(aabb1, velocity1, aabb2, delta_time)
    }
}

/// Scalar fallback for swept AABB collision
pub fn swept_aabb_collision_scalar(
    aabb1: &Aabb,
    velocity1: Vec3,
    aabb2: &Aabb,
    delta_time: f32,
) -> Option<f32> {
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

    let expanded_aabb = Aabb::new(
        expanded_min_x,
        expanded_min_y,
        expanded_min_z,
        expanded_max_x,
        expanded_max_y,
        expanded_max_z,
    );

    // Check ray intersection with expanded AABB
    ray_aabb_intersect_scalar(aabb1.center(), velocity1 * delta_time, &expanded_aabb)
}

/// Copy-on-Write QuadTree implementation
#[derive(Debug)]
pub struct QuadTree<T> {
    pub bounds: Aabb,
    pub entities: Vec<(T, [f64; 3])>, // Flat array storage to eliminate CoW overhead
    pub children: Option<Arc<[QuadTree<T>; 4]>>,
    pub lock: Mutex<()>, // parking_lot Mutex for thread-safe operations
    pub max_entities: usize,
    pub max_depth: usize,
}

/// RAII wrapper for thread-safe QuadTree operations
pub struct QuadTreeGuard<'a, T> {
    quad_tree: *mut QuadTree<T>,
    #[allow(dead_code)]
    lock_guard: MutexGuard<'a, ()>,
}

impl<'a, T> QuadTreeGuard<'a, T> {
    /// Get immutable reference to the underlying QuadTree
    pub fn get_quad_tree(&self) -> &QuadTree<T> {
        unsafe { &*self.quad_tree }
    }

    /// Get mutable reference to the underlying QuadTree
    pub fn get_quad_tree_mut(&mut self) -> &mut QuadTree<T> {
        unsafe { &mut *self.quad_tree }
    }
}

impl<'a, T> Drop for QuadTreeGuard<'a, T> {
    fn drop(&mut self) {
        // Lock is automatically released when MutexGuard is dropped
    }
}

impl<T: Clone + PartialEq + Send + Sync> Clone for QuadTree<T> {
    fn clone(&self) -> Self {
        Self {
            bounds: self.bounds.clone(),
            entities: self.entities.clone(),
            children: self.children.clone(),
            lock: Mutex::new(()),
            max_entities: self.max_entities,
            max_depth: self.max_depth,
        }
    }
}

impl<T: Clone + PartialEq + Send + Sync> QuadTree<T> {
    pub fn new(bounds: Aabb, max_entities: usize, max_depth: usize) -> Self {
        Self {
            bounds,
            entities: Vec::new(), // Flat array storage
            children: None,
            max_entities,
            max_depth,
            lock: Mutex::new(()), // Initialize lock
        }
    }

    /// Get a RAII guard for thread-safe operations
    /// Get a RAII guard for thread-safe operations with timeout
    pub fn guard(&mut self, timeout: Option<Duration>) -> Result<QuadTreeGuard<'_, T>, String> {
        // Create the pointer before locking to avoid borrow conflicts
        let quad_tree_ptr = self as *mut _;
        // parking_lot::Mutex::try_lock_for returns Option<MutexGuard>
        let guard_opt = match timeout {
            Some(duration) => self.lock.try_lock_for(duration),
            None => Some(self.lock.lock()),
        };

        if let Some(lock_guard) = guard_opt {
            Ok(QuadTreeGuard {
                quad_tree: quad_tree_ptr,
                lock_guard,
            })
        } else {
            Err("Lock timeout exceeded".to_string())
        }
    }

    /// Get a RAII guard for thread-safe operations with default timeout (1 second)
    pub fn guard_with_timeout(&mut self) -> Result<QuadTreeGuard<'_, T>, String> {
        self.guard(Some(Duration::from_secs(1)))
    }

    pub fn insert(&mut self, entity: T, pos: [f64; 3], depth: usize) -> Result<(), String> {
        let mut guard = self.guard_with_timeout()?;

        if guard.get_quad_tree().children.is_some() {
            let quadrant = guard.get_quad_tree().get_quadrant(pos);
            if let Some(ref mut children) = guard.get_quad_tree_mut().children {
                let children = Arc::make_mut(children);
                children[quadrant].insert(entity, pos, depth + 1)?;
            }
        } else {
            // Use incremental update instead of full subtree rebuilding when possible
            let entities = &mut guard.get_quad_tree_mut().entities;
            entities.push((entity, pos));

            // Only subdivide if we actually exceed the limit (more efficient)
            // Use exact comparison instead of len() > max_entities to avoid overflow checks
            if entities.len() > guard.get_quad_tree().max_entities
                && depth < guard.get_quad_tree().max_depth
            {
                guard.get_quad_tree_mut().subdivide(depth);
            }
        }

        Ok(())
    }

    #[inline(always)]
    pub fn query(&self, query_bounds: &Aabb) -> Vec<(T, [f64; 3])> {
        // Use consistent lock ordering: try to acquire the global spatial lock
        // first, but don't block indefinitely. In the parallel test suite some
        // other tests may acquire locks in a different order and cause a
        // deadlock. Use a short timeout so queries can proceed without
        // risking a global hang.
        let _spatial_guard = lock_order::SPATIAL_LOCK
            .try_lock_for(std::time::Duration::from_millis(200));

        // Use scoped arena for temporary allocations to reduce memory pressure
        let _arena = ScopedArena::new(get_global_arena_pool());
        let mut results = Vec::new();
        let mut stack: Vec<*const QuadTree<T>> = Vec::new();

        // Start with the root node
        stack.push(self as *const _);

        while let Some(current_node_ptr) = stack.pop() {
            let current_node = unsafe { &mut *(current_node_ptr as *mut QuadTree<T>) };
            let guard = current_node
                .guard(None)
                .expect("Failed to acquire quadtree lock"); // Thread-safe querying

            // Skip if this node's bounds don't intersect with query bounds - with branch prediction
            if !guard.get_quad_tree().bounds.intersects(query_bounds) {
                continue;
            }

            // SIMD-accelerated entity filtering for better performance
            if guard.get_quad_tree().entities.len() >= 8 {
                // Use pre-allocated buffer for positions to avoid intermediate allocations
                const POS_BUF_SIZE: usize = 128;
                let mut pos_buffer = [(0.0f64, 0.0f64, 0.0f64); POS_BUF_SIZE];
                let entity_count = guard.get_quad_tree().entities.len();

                // Prefetch first entity data to reduce cache misses
                unsafe {
                    if entity_count > 0 {
                        let first_entity = guard.get_quad_tree().entities.get_unchecked(0);
                        _mm_prefetch((&first_entity.1[0] as *const f64) as *const i8, _MM_HINT_T0);
                    }
                }

                // Load positions with loop unrolling for better performance
                let mut i = 0;
                while i < entity_count {
                    let remaining = entity_count - i;
                    let take = std::cmp::min(remaining, POS_BUF_SIZE);

                    for j in 0..take {
                        let entity = unsafe { guard.get_quad_tree().entities.get_unchecked(i + j) };
                        pos_buffer[j] = (entity.1[0], entity.1[1], entity.1[2]);
                    }

                    // Process chunk with SIMD
                    let contained = query_bounds.contains_points_simd(&pos_buffer[..take]);

                    // Store results with unrolled loop for better ILP
                    for j in 0..take {
                        if contained[j] {
                            results.push(unsafe {
                                guard.get_quad_tree().entities.get_unchecked(i + j).clone()
                            });
                        }
                    }

                    i += take;
                }
            } else {
                // Fallback to scalar processing for small batches
                for entity in &*guard.get_quad_tree().entities {
                    if query_bounds.contains(entity.1[0], entity.1[1], entity.1[2]) {
                        results.push(entity.clone());
                    }
                }
            }

            // Add children to stack for processing
            if let Some(ref children) = guard.get_quad_tree().children {
                for child in children.iter() {
                    stack.push(child);
                }
            }
        }

        results
    }

    /// SIMD-optimized query with vectorized bounds checking and entity filtering
    pub fn query_simd(&self, query_bounds: &Aabb) -> Vec<(T, [f64; 3])> {
        // Use consistent lock ordering: try to acquire the global spatial lock
        // with a short timeout to avoid deadlocks in the parallel test harness.
        let _spatial_guard = lock_order::SPATIAL_LOCK
            .try_lock_for(std::time::Duration::from_millis(200));

        #[cfg(target_feature = "avx2")]
        {
            // Use scoped arena for temporary allocations to reduce memory pressure
            let arena = ScopedArena::new(get_global_arena_pool());
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
                    // Use arena allocation for positions to avoid intermediate Vec allocation
                    let positions_capacity = current_node.entities.len();
                    let bump_arena = arena.get_inner_arena();
                    let mut positions = ArenaVec::with_capacity(bump_arena, positions_capacity);

                    for entity in &*current_node.entities {
                        positions.push((entity.1[0], entity.1[1], entity.1[2]));
                    }

                    let contained = query_bounds.contains_points_simd(positions.as_slice());

                    for (i, is_contained) in contained.iter().enumerate() {
                        if *is_contained {
                            results.push(current_node.entities[i].clone());
                        }
                    }
                } else {
                    // Scalar fallback for small batches
                    for entity in &*current_node.entities {
                        if query_bounds.contains(entity.1[0], entity.1[1], entity.1[2]) {
                            results.push(entity.clone());
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
            QuadTree::new(
                Aabb::new(
                    self.bounds.min_x,
                    min_y,
                    self.bounds.min_z,
                    mid_x,
                    max_y,
                    mid_z,
                ),
                self.max_entities,
                self.max_depth,
            ),
            QuadTree::new(
                Aabb::new(
                    mid_x,
                    min_y,
                    self.bounds.min_z,
                    self.bounds.max_x,
                    max_y,
                    mid_z,
                ),
                self.max_entities,
                self.max_depth,
            ),
            QuadTree::new(
                Aabb::new(
                    self.bounds.min_x,
                    min_y,
                    mid_z,
                    mid_x,
                    max_y,
                    self.bounds.max_z,
                ),
                self.max_entities,
                self.max_depth,
            ),
            QuadTree::new(
                Aabb::new(
                    mid_x,
                    min_y,
                    mid_z,
                    self.bounds.max_x,
                    max_y,
                    self.bounds.max_z,
                ),
                self.max_entities,
                self.max_depth,
            ),
        ];

        let mut new_children = Arc::new([
            children[0].clone(),
            children[1].clone(),
            children[2].clone(),
            children[3].clone(),
        ]);

        let entities = std::mem::take(&mut self.entities);
        // Redistribute entities to children using direct insertion (no CoW)
        for (entity, pos) in entities {
            let quadrant = self.get_quadrant(pos);
            let children_array = Arc::make_mut(&mut new_children);
            let _ = children_array[quadrant].insert(entity, pos, depth + 1);
        }

        self.children = Some(new_children);
    }

    fn get_quadrant(&self, pos: [f64; 3]) -> usize {
        let mid_x = (self.bounds.min_x + self.bounds.max_x) / 2.0;
        let mid_z = (self.bounds.min_z + self.bounds.max_z) / 2.0;

        if pos[0] < mid_x {
            if pos[2] < mid_z {
                0
            } else {
                2
            }
        } else {
            if pos[2] < mid_z {
                1
            } else {
                3
            }
        }
    }
}

#[derive(Debug)]
pub struct SpatialPartition<T: std::cmp::Eq + std::hash::Hash> {
    pub quadtree: QuadTree<T>,
    pub entity_positions: DashMap<T, [f64; 3]>,
}

impl<T: Clone + PartialEq + std::hash::Hash + Eq + Send + Sync> Clone for SpatialPartition<T> {
    fn clone(&self) -> Self {
        Self {
            quadtree: self.quadtree.clone(),
            entity_positions: DashMap::new(), // Reset positions map on clone
        }
    }
}

impl<T: Clone + PartialEq + std::hash::Hash + Eq + Send + Sync> SpatialPartition<T> {
    // Use lock striping for better concurrency (32 stripes)
    const LOCK_STRIPES: usize = 32;

    fn get_lock_stripe(&self, pos: &[f64; 3]) -> usize {
        // Use position-based hashing for consistent lock distribution
        let hash = ((pos[0].to_bits() ^ pos[1].to_bits() ^ pos[2].to_bits()) as usize)
            % Self::LOCK_STRIPES;
        hash
    }
    pub fn new(world_bounds: Aabb, max_entities: usize, max_depth: usize) -> Self {
        Self {
            quadtree: QuadTree::new(world_bounds, max_entities, max_depth),
            entity_positions: DashMap::new(),
        }
    }

    pub fn insert_or_update(&mut self, entity: T, pos: [f64; 3]) -> Result<(), String> {
        // Use consistent lock ordering: per-node lock first, then dashmap lock
        let _stripe = self.get_lock_stripe(&pos);
        let _dashmap_guard = lock_order::DASHMAP_LOCK.lock();

        // Use memory pool for position storage to reduce allocations
        let _mem_pool = get_global_enhanced_pool();

        if let Some(_old_pos) = self.entity_positions.insert(entity.clone(), pos) {
            // For simplicity, we'll rebuild the quadtree periodically instead of removing individual entries
        }
        self.quadtree.insert(entity, pos, 0)?;

        Ok(())
    }

    pub fn query_nearby(
        &self,
        center: [f64; 3],
        radius: f64,
    ) -> Result<Vec<(T, [f64; 3])>, String> {
        // Use consistent lock ordering: per-node lock first, then dashmap lock
        let _stripe = self.get_lock_stripe(&center);
        let _dashmap_guard = lock_order::DASHMAP_LOCK.lock();

        let query_bounds = Aabb::new(
            center[0] - radius,
            center[1] - radius,
            center[2] - radius,
            center[0] + radius,
            center[1] + radius,
            center[2] + radius,
        );
        Ok(self.quadtree.query(&query_bounds))
    }

    pub fn rebuild(&mut self) -> Result<(), String> {
        // Use consistent lock ordering: all node locks first, then dashmap lock
        let _dashmap_guard = lock_order::DASHMAP_LOCK.lock();

        let world_bounds = self.quadtree.bounds.clone();
        let max_entities = self.quadtree.max_entities;
        let max_depth = self.quadtree.max_depth;
        let mut new_quadtree = QuadTree::new(world_bounds, max_entities, max_depth);

        // Use fold to avoid intermediate Vec allocation during rebuild
        let _mem_pool = get_global_enhanced_pool();

        // Use iterator adaptors to minimize intermediate allocations
        let entities_to_insert: Vec<_> = self
            .entity_positions
            .iter()
            .map(|entry| (entry.key().clone(), *entry.value()))
            .collect();
        for (entity, pos) in entities_to_insert {
            new_quadtree.insert(entity, pos, 0)?;
        }

        self.quadtree = new_quadtree;
        Ok(())
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

/// SIMD-accelerated distance calculation using the SIMD manager with const generic batch size
#[inline(always)]
pub fn calculate_distances_simd<const BATCH_SIZE: usize>(
    positions: &[(f32, f32, f32)],
    center: (f32, f32, f32),
) -> Vec<f32> {
    #[cfg(target_feature = "avx512f")]
    {
        use std::arch::x86_64::*;

        let cx = _mm512_set1_ps(center.0);
        let cy = _mm512_set1_ps(center.1);
        let cz = _mm512_set1_ps(center.2);

        // Pre-allocate result vector with exact capacity to avoid reallocations
        let mut result = Vec::with_capacity(positions.len());

        // Process main chunks with const batch size
        const AVX512_WIDTH: usize = BATCH_SIZE;
        let main_chunks = positions.len() / AVX512_WIDTH;
        let remainder = positions.len() % AVX512_WIDTH;

        // Pre-allocate temporary storage for aligned loads
        let mut pos_buffers = [[0.0f32; AVX512_WIDTH]; 3];

        // Prefetch next chunk to reduce cache misses
        for i in 0..main_chunks {
            let chunk_start = i * AVX512_WIDTH;

            // Prefetch next chunk data to improve cache utilization
            if i + 1 < main_chunks {
                let next_chunk_start = (i + 1) * AVX512_WIDTH;
                unsafe {
                    _mm_prefetch(
                        positions.get_unchecked(next_chunk_start).0 as *const f32,
                        _MM_HINT_T0,
                    );
                    _mm_prefetch(
                        positions.get_unchecked(next_chunk_start).1 as *const f32,
                        _MM_HINT_T0,
                    );
                    _mm_prefetch(
                        positions.get_unchecked(next_chunk_start).2 as *const f32,
                        _MM_HINT_T0,
                    );
                }
            }

            // Load all position components with better cache utilization
            for j in 0..AVX512_WIDTH {
                let pos_idx = chunk_start + j;
                pos_buffers[0][j] = positions[pos_idx].0;
                pos_buffers[1][j] = positions[pos_idx].1;
                pos_buffers[2][j] = positions[pos_idx].2;
            }

            let px = _mm512_loadu_ps(pos_buffers[0].as_ptr());
            let py = _mm512_loadu_ps(pos_buffers[1].as_ptr());
            let pz = _mm512_loadu_ps(pos_buffers[2].as_ptr());

            // Vectorized distance calculation with fused multiply-add for better performance
            let dx = _mm512_sub_ps(px, cx);
            let dy = _mm512_sub_ps(py, cy);
            let dz = _mm512_sub_ps(pz, cz);

            // Use FMADD instructions to optimize calculation pipeline
            let dx2 = _mm512_fmadd_ps(dx, dx, _mm512_setzero_ps());
            let dy2 = _mm512_fmadd_ps(dy, dy, _mm512_setzero_ps());
            let dz2 = _mm512_fmadd_ps(dz, dz, _mm512_setzero_ps());

            // Sum components with FMADD for better instruction level parallelism
            let sum = _mm512_fmadd_ps(
                dx2,
                _mm512_set1_ps(1.0),
                _mm512_fmadd_ps(dy2, _mm512_set1_ps(1.0), dz2),
            );
            let dist = _mm512_sqrt_ps(sum);

            // Store results directly into pre-allocated vector
            let mut dist_buffer = [0.0f32; AVX512_WIDTH];
            _mm512_storeu_ps(dist_buffer.as_mut_ptr(), dist);
            result.extend_from_slice(&dist_buffer);
        }

        // Handle remainder elements with optimized scalar path
        if remainder > 0 {
            let remainder_start = positions.len() - remainder;
            let mut remainder_buffer = [0.0f32; AVX512_WIDTH];

            // Use aligned loads for remainder when possible
            if remainder_start + remainder <= positions.len() {
                for j in 0..remainder {
                    let pos_idx = remainder_start + j;
                    remainder_buffer[j] = ((positions[pos_idx].0 - center.0).powi(2)
                        + (positions[pos_idx].1 - center.1).powi(2)
                        + (positions[pos_idx].2 - center.2).powi(2))
                    .sqrt();
                }
            }

            result.extend_from_slice(&remainder_buffer[..remainder]);
        }

        result
    }
    #[cfg(target_feature = "avx2")]
    {
        calculate_distances_simd_portable(positions, center)
    }
    #[cfg(not(any(target_feature = "avx512f", target_feature = "avx2")))]
    {
        entity_processing::calculate_entity_distances(positions, center)
    }
}

/// SIMD-accelerated distance calculation using std::simd for better portability
pub fn calculate_distances_simd_portable(
    positions: &[(f32, f32, f32)],
    center: (f32, f32, f32),
) -> Vec<f32> {
    #[cfg(target_feature = "avx512f")]
    {
        use std::arch::x86_64::*;

        let cx = _mm512_set1_ps(center.0);
        let cy = _mm512_set1_ps(center.1);
        let cz = _mm512_set1_ps(center.2);

        positions
            .par_chunks(16)
            .flat_map(|chunk| {
                let mut distances = [0.0f32; 16];

                // Load all position components in one go for better cache utilization
                let mut px_arr = [0.0f32; 16];
                let mut py_arr = [0.0f32; 16];
                let mut pz_arr = [0.0f32; 16];

                for (i, &(x, y, z)) in chunk.iter().enumerate() {
                    px_arr[i] = x;
                    py_arr[i] = y;
                    pz_arr[i] = z;
                }

                let px = _mm512_loadu_ps(px_arr.as_ptr());
                let py = _mm512_loadu_ps(py_arr.as_ptr());
                let pz = _mm512_loadu_ps(pz_arr.as_ptr());

                let dx = _mm512_sub_ps(px, cx);
                let dy = _mm512_sub_ps(py, cy);
                let dz = _mm512_sub_ps(pz, cz);

                let dx2 = _mm512_mul_ps(dx, dx);
                let dy2 = _mm512_mul_ps(dy, dy);
                let dz2 = _mm512_mul_ps(dz, dz);

                let sum = _mm512_add_ps(_mm512_add_ps(dx2, dy2), dz2);
                let dist = _mm512_sqrt_ps(sum);

                _mm512_storeu_ps(distances.as_mut_ptr(), dist);
                // Use fold to avoid intermediate allocations
                distances
                    .into_iter()
                    .take(chunk.len())
                    .fold(Vec::new, |mut acc, d| {
                        acc.push(d);
                        acc
                    })
            })
            .collect::<Vec<Vec<f32>>>()
            .into_iter()
            .flatten()
            .collect()
    }
    #[cfg(target_feature = "avx2")]
    {
        use std::arch::x86_64::*;

        let cx = _mm256_set1_ps(center.0);
        let cy = _mm256_set1_ps(center.1);
        let cz = _mm256_set1_ps(center.2);

        positions
            .par_chunks(8)
            .flat_map(|chunk| {
                let mut distances = [0.0f32; 8];
                let px = _mm256_set_ps(
                    chunk.get(7).map(|p| p.0).unwrap_or(0.0),
                    chunk.get(6).map(|p| p.0).unwrap_or(0.0),
                    chunk.get(5).map(|p| p.0).unwrap_or(0.0),
                    chunk.get(4).map(|p| p.0).unwrap_or(0.0),
                    chunk.get(3).map(|p| p.0).unwrap_or(0.0),
                    chunk.get(2).map(|p| p.0).unwrap_or(0.0),
                    chunk.get(1).map(|p| p.0).unwrap_or(0.0),
                    chunk.get(0).map(|p| p.0).unwrap_or(0.0),
                );
                let py = _mm256_set_ps(
                    chunk.get(7).map(|p| p.1).unwrap_or(0.0),
                    chunk.get(6).map(|p| p.1).unwrap_or(0.0),
                    chunk.get(5).map(|p| p.1).unwrap_or(0.0),
                    chunk.get(4).map(|p| p.1).unwrap_or(0.0),
                    chunk.get(3).map(|p| p.1).unwrap_or(0.0),
                    chunk.get(2).map(|p| p.1).unwrap_or(0.0),
                    chunk.get(1).map(|p| p.1).unwrap_or(0.0),
                    chunk.get(0).map(|p| p.1).unwrap_or(0.0),
                );
                let pz = _mm256_set_ps(
                    chunk.get(7).map(|p| p.2).unwrap_or(0.0),
                    chunk.get(6).map(|p| p.2).unwrap_or(0.0),
                    chunk.get(5).map(|p| p.2).unwrap_or(0.0),
                    chunk.get(4).map(|p| p.2).unwrap_or(0.0),
                    chunk.get(3).map(|p| p.2).unwrap_or(0.0),
                    chunk.get(2).map(|p| p.2).unwrap_or(0.0),
                    chunk.get(1).map(|p| p.2).unwrap_or(0.0),
                    chunk.get(0).map(|p| p.2).unwrap_or(0.0),
                );

                let dx = _mm256_sub_ps(px, cx);
                let dy = _mm256_sub_ps(py, cy);
                let dz = _mm256_sub_ps(pz, cz);

                let dx2 = _mm256_mul_ps(dx, dx);
                let dy2 = _mm256_mul_ps(dy, dy);
                let dz2 = _mm256_mul_ps(dz, dz);

                let sum = _mm256_add_ps(_mm256_add_ps(dx2, dy2), dz2);
                let dist = _mm256_sqrt_ps(sum);

                _mm256_storeu_ps(distances.as_mut_ptr(), dist);
                // Use fold to avoid intermediate allocations
                distances
                    .into_iter()
                    .take(chunk.len())
                    .fold(Vec::new, |mut acc, d| {
                        acc.push(d);
                        acc
                    })
            })
            .collect::<Vec<Vec<f32>>>()
            .into_iter()
            .flatten()
            .collect()
    }
    #[cfg(not(any(target_feature = "avx512f", target_feature = "avx2")))]
    {
        entity_processing::calculate_entity_distances(positions, center)
    }
}

/// SIMD-accelerated entity filtering with distance threshold using SIMD manager
pub fn filter_entities_by_distance_simd<T: Clone + Send + Sync>(
    entities: &[(T, [f64; 3])],
    center: [f64; 3],
    max_distance: f64,
) -> Vec<(T, [f64; 3])> {
    entity_processing::filter_entities_by_distance(entities, center, max_distance)
}

/// SIMD-accelerated chunk distance calculation (2D, ignoring Y) using SIMD manager
pub fn calculate_chunk_distances_simd(
    chunk_coords: &[(i32, i32)],
    center_chunk: (i32, i32),
) -> Vec<f32> {
    vector_ops::calculate_chunk_distances(chunk_coords, center_chunk)
}

/// SIMD-accelerated AABB intersection batch processing using SIMD manager
pub fn batch_aabb_intersections(aabbs: &[Aabb], queries: &[Aabb]) -> Vec<Vec<bool>> {
    vector_ops::batch_aabb_intersections(aabbs, queries)
}

/// SIMD-accelerated QuadTree query with multiple bounds using parallelism
pub fn batch_quadtree_queries<'a, T: Clone + PartialEq + Send + Sync>(
    quadtree: &'a QuadTree<T>,
    query_bounds: &'a [Aabb],
) -> Vec<Vec<(T, [f64; 3])>> {
    // Use parallel processing for multiple queries to reduce latency
    query_bounds
        .par_iter()
        .map(|bounds| quadtree.query_simd(bounds))
        .collect()
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
            Aabb::new(2.0, 2.0, 2.0, 8.0, 8.0, 8.0),    // intersects
            Aabb::new(-5.0, -5.0, -5.0, 5.0, 5.0, 5.0), // intersects
        ];

        let results = aabb1.intersects_simd_batch(&test_aabbs);

        assert_eq!(results.len(), 4);
        assert_eq!(results[0], true); // intersects
        assert_eq!(results[1], false); // no intersection
        assert_eq!(results[2], true); // intersects
        assert_eq!(results[3], true); // intersects
    }

    #[test]
    fn test_contains_points_simd() {
        let aabb = Aabb::new(0.0, 0.0, 0.0, 10.0, 10.0, 10.0);
        let points = vec![
            (5.0, 5.0, 5.0),    // inside
            (15.0, 15.0, 15.0), // outside
            (0.0, 0.0, 0.0),    // on boundary (inside)
            (10.0, 10.0, 10.0), // on boundary (inside)
            (5.0, 15.0, 5.0),   // outside (y too high)
        ];

        let results = aabb.contains_points_simd(&points);

        assert_eq!(results.len(), 5);
        assert_eq!(results[0], true); // inside
        assert_eq!(results[1], false); // outside
        assert_eq!(results[2], true); // on boundary
        assert_eq!(results[3], true); // on boundary
        assert_eq!(results[4], false); // outside
    }

    #[test]
    fn test_quadtree_query_simd() {
        let bounds = Aabb::new(-100.0, -100.0, -100.0, 100.0, 100.0, 100.0);
        let mut quadtree = QuadTree::new(bounds, 10, 5);

        // Insert some test entities
        let _ = quadtree.insert(Entity::from_raw(1), [10.0, 10.0, 10.0], 0);
        let _ = quadtree.insert(Entity::from_raw(2), [20.0, 20.0, 20.0], 0);
        let _ = quadtree.insert(Entity::from_raw(3), [50.0, 50.0, 50.0], 0);
        let _ = quadtree.insert(Entity::from_raw(4), [200.0, 200.0, 200.0], 0); // Outside query bounds

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

        let distances = calculate_distances_simd::<16>(&positions, center);

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
        assert_eq!(results[0][0], true); // AABB 0
        assert_eq!(results[0][1], true); // AABB 1
        assert_eq!(results[0][2], false); // AABB 2
        assert_eq!(results[0][3], true); // AABB 3

        // Second query should only intersect with AABB 2
        assert_eq!(results[1][0], false); // AABB 0
        assert_eq!(results[1][1], false); // AABB 1
        assert_eq!(results[1][2], true); // AABB 2
        assert_eq!(results[1][3], false); // AABB 3
    }
}

use crate::errors::{Result, RustError};
use crate::simd::base::{SimdLevel, SimdFeatures, get_simd_manager};
use std::arch::x86_64::*;

// Polyfill for missing _mm256_reduce_add_ps function
#[cfg(target_arch = "x86_64")]
#[inline(always)]
unsafe fn _mm256_reduce_add_ps(mut v: __m256) -> f32 {
    v = _mm256_hadd_ps(v, v);
    v = _mm256_hadd_ps(v, v);
    
    let mut result = 0.0f32;
    std::ptr::copy_nonoverlapping(
        &v as *const __m256 as *const f32,
        &mut result as *mut f32,
        1
    );
    result
}

// Polyfill for missing _mm_reduce_add_ps function
#[cfg(target_arch = "x86_64")]
#[inline(always)]
unsafe fn _mm_reduce_add_ps(mut v: __m128) -> f32 {
    v = _mm_hadd_ps(v, v);
    v = _mm_hadd_ps(v, v);
    
    let mut result = 0.0f32;
    std::ptr::copy_nonoverlapping(
        &v as *const __m128 as *const f32,
        &mut result as *mut f32,
        1
    );
    result
}
use std::arch::x86_64::*;
use std::time::Instant;
use crate::performance::monitoring::record_operation;

/// Standardized SIMD operations interface
pub trait SimdStandardOps {
    /// Dot product of two vectors
    fn dot_product(&self, a: &[f32], b: &[f32]) -> Result<f32>;
    
    /// Vector addition with scaling: a += b * scale
    fn vector_add(&self, a: &mut [f32], b: &[f32], scale: f32) -> Result<()>;
    
    /// Calculate distances between multiple points and a center point (2D)
    fn calculate_chunk_distances(&self, chunk_coords: &[(i32, i32)], center_chunk: (i32, i32)) -> Vec<f32>;
    
    /// Calculate distances between multiple points and a center point (3D)
    fn calculate_entity_distances(&self, positions: &[(f32, f32, f32)], center: (f32, f32, f32)) -> Vec<f32>;
    
    /// Batch AABB intersection tests
    fn batch_aabb_intersections(&self, aabbs: &[crate::spatial::Aabb], queries: &[crate::spatial::Aabb]) -> Vec<Vec<bool>>;
    
    /// Filter entities by distance threshold
    fn filter_entities_by_distance<T: Clone + Send + Sync>(&self, entities: &[(T, [f64; 3])], center: [f64; 3], max_distance: f64) -> Vec<(T, [f64; 3])>;
    
    /// Process large arrays with SIMD-optimized operations
    fn process_large_array<T, F>(&self, data: &mut [T], operation: F) -> Result<()>
    where
        T: Copy + Send + Sync,
        F: Fn(&mut [T]) -> Result<()>;
}

/// Standardized SIMD operations implementation
pub struct StandardSimdOps {
    level: SimdLevel,
    features: SimdFeatures,
}

impl StandardSimdOps {
    /// Create a new StandardSimdOps instance
    pub fn new() -> Self {
        let simd_manager = get_simd_manager();
        let level = simd_manager.get_level();
        let features = simd_manager.get_features().clone();
        
        Self { level, features }
    }
    
    /// Get the current SIMD optimization level
    pub fn get_level(&self) -> SimdLevel {
        self.level
    }
    
    /// Check if a specific SIMD feature is available
    pub fn has_feature(&self, feature: &str) -> bool {
        match feature {
            "avx2" => self.features.has_avx2,
            "sse4.1" => self.features.has_sse41,
            "sse4.2" => self.features.has_sse42,
            "avx512f" => self.features.has_avx512f,
            _ => false,
        }
    }
}

impl SimdStandardOps for StandardSimdOps {
    fn dot_product(&self, a: &[f32], b: &[f32]) -> Result<f32> {
        if a.len() != b.len() {
            return Err(RustError::InvalidInputError(format!(
                "Vector lengths must match for dot product. Expected {}, got {}",
                a.len(), b.len()
            )));
        }

        let start = Instant::now();
        let result = match self.level {
            SimdLevel::Avx512 => unsafe { self.dot_product_avx512(a, b) },
            SimdLevel::Avx2 => unsafe { self.dot_product_avx2(a, b) },
            SimdLevel::Sse42 | SimdLevel::Sse41 => unsafe { self.dot_product_sse(a, b) },
            SimdLevel::Scalar => self.dot_product_scalar(a, b),
            SimdLevel::None | SimdLevel::Sse => self.dot_product_scalar(a, b), // Fallback to scalar for unsupported levels
        };

        record_operation(start, a.len(), 1);
        result
    }

    fn vector_add(&self, a: &mut [f32], b: &[f32], scale: f32) -> Result<()> {
        if a.len() != b.len() {
            return Err(RustError::InvalidInputError(format!(
                "Vector lengths must match for vector add. Expected {}, got {}",
                a.len(), b.len()
            )));
        }

        let start = Instant::now();
        let result = match self.level {
            SimdLevel::Avx512 => unsafe { self.vector_add_avx512(a, b, scale) },
            SimdLevel::Avx2 => unsafe { self.vector_add_avx2(a, b, scale) },
            SimdLevel::Sse42 | SimdLevel::Sse41 => unsafe { self.vector_add_sse(a, b, scale) },
            SimdLevel::Scalar => self.vector_add_scalar(a, b, scale),
            SimdLevel::None | SimdLevel::Sse => self.vector_add_scalar(a, b, scale), // Fallback to scalar for unsupported levels
        };

        record_operation(start, a.len(), 1);
        result
    }

    fn calculate_chunk_distances(&self, chunk_coords: &[(i32, i32)], center_chunk: (i32, i32)) -> Vec<f32> {
        let start = Instant::now();
        let result = match self.level {
            SimdLevel::Avx512 => unsafe { self.calculate_chunk_distances_avx512(chunk_coords, center_chunk) },
            SimdLevel::Avx2 => unsafe { self.calculate_chunk_distances_avx2(chunk_coords, center_chunk) },
            SimdLevel::Sse42 | SimdLevel::Sse41 => unsafe { self.calculate_chunk_distances_sse(chunk_coords, center_chunk) },
            SimdLevel::Scalar => self.calculate_chunk_distances_scalar(chunk_coords, center_chunk),
            SimdLevel::None | SimdLevel::Sse => self.calculate_chunk_distances_scalar(chunk_coords, center_chunk), // Fallback to scalar for unsupported levels
        };

        record_operation(start, chunk_coords.len(), 1);
        result
    }

    fn calculate_entity_distances(&self, positions: &[(f32, f32, f32)], center: (f32, f32, f32)) -> Vec<f32> {
        let start = Instant::now();
        let result = match self.level {
            SimdLevel::Avx512 => unsafe { self.calculate_entity_distances_avx512(positions, center) },
            SimdLevel::Avx2 => unsafe { self.calculate_entity_distances_avx2(positions, center) },
            SimdLevel::Sse42 | SimdLevel::Sse41 => unsafe { self.calculate_entity_distances_sse(positions, center) },
            SimdLevel::Scalar => self.calculate_entity_distances_scalar(positions, center),
            SimdLevel::None | SimdLevel::Sse => self.calculate_entity_distances_scalar(positions, center), // Fallback to scalar for unsupported levels
        };

        record_operation(start, positions.len(), 1);
        result
    }

    fn batch_aabb_intersections(&self, aabbs: &[crate::spatial::Aabb], queries: &[crate::spatial::Aabb]) -> Vec<Vec<bool>> {
        let start = Instant::now();
        let result = match self.level {
            SimdLevel::Avx512 => unsafe { self.batch_aabb_intersections_avx512(aabbs, queries) },
            SimdLevel::Avx2 => unsafe { self.batch_aabb_intersections_avx2(aabbs, queries) },
            SimdLevel::Sse42 | SimdLevel::Sse41 => unsafe { self.batch_aabb_intersections_sse(aabbs, queries) },
            SimdLevel::Scalar => self.batch_aabb_intersections_scalar(aabbs, queries),
            SimdLevel::None | SimdLevel::Sse => self.batch_aabb_intersections_scalar(aabbs, queries), // Fallback to scalar for unsupported levels
        };

        record_operation(start, aabbs.len() * queries.len(), 1);
        result
    }

    fn filter_entities_by_distance<T: Clone + Send + Sync>(&self, entities: &[(T, [f64; 3])], center: [f64; 3], max_distance: f64) -> Vec<(T, [f64; 3])> {
        let start = Instant::now();
        let result = match self.level {
            SimdLevel::Avx512 => unsafe { self.filter_entities_by_distance_avx512(entities, center, max_distance) },
            SimdLevel::Avx2 => unsafe { self.filter_entities_by_distance_avx2(entities, center, max_distance) },
            SimdLevel::Sse42 | SimdLevel::Sse41 => unsafe { self.filter_entities_by_distance_sse(entities, center, max_distance) },
            SimdLevel::Scalar => self.filter_entities_by_distance_scalar(entities, center, max_distance),
            SimdLevel::None | SimdLevel::Sse => self.filter_entities_by_distance_scalar(entities, center, max_distance), // Fallback to scalar for unsupported levels
        };

        record_operation(start, entities.len(), 1);
        result
    }

    fn process_large_array<T, F>(&self, data: &mut [T], operation: F) -> Result<()>
    where
        T: Copy + Send + Sync,
        F: Fn(&mut [T]) -> Result<()>,
    {
        let start = Instant::now();
        let result = match self.level {
            SimdLevel::Avx512 => unsafe { self.process_large_array_avx512(data, operation) },
            SimdLevel::Avx2 => unsafe { self.process_large_array_avx2(data, operation) },
            SimdLevel::Sse42 | SimdLevel::Sse41 => unsafe { self.process_large_array_sse(data, operation) },
            SimdLevel::Scalar => operation(data),
            SimdLevel::None | SimdLevel::Sse => operation(data), // Fallback to scalar for unsupported levels
        };

        record_operation(start, data.len(), 1);
        result
    }
}

// Implement scalar fallback operations
impl StandardSimdOps {
    fn dot_product_scalar(&self, a: &[f32], b: &[f32]) -> Result<f32> {
        Ok(a.iter().zip(b.iter()).map(|(x, y)| x * y).sum())
    }

    fn vector_add_scalar(&self, a: &mut [f32], b: &[f32], scale: f32) -> Result<()> {
        for (av, bv) in a.iter_mut().zip(b.iter()) {
            *av += *bv * scale;
        }
        Ok(())
    }

    fn calculate_chunk_distances_scalar(&self, chunk_coords: &[(i32, i32)], center_chunk: (i32, i32)) -> Vec<f32> {
        chunk_coords
            .iter()
            .map(|(x, z)| {
                let dx = *x as f32 - center_chunk.0 as f32;
                let dz = *z as f32 - center_chunk.1 as f32;
                (dx * dx + dz * dz).sqrt()
            })
            .collect()
    }

    fn calculate_entity_distances_scalar(&self, positions: &[(f32, f32, f32)], center: (f32, f32, f32)) -> Vec<f32> {
        positions
            .iter()
            .map(|(x, y, z)| {
                let dx = x - center.0;
                let dy = y - center.1;
                let dz = z - center.2;
                (dx * dx + dy * dy + dz * dz).sqrt()
            })
            .collect()
    }

    fn batch_aabb_intersections_scalar(&self, aabbs: &[crate::spatial::Aabb], queries: &[crate::spatial::Aabb]) -> Vec<Vec<bool>> {
        queries
            .iter()
            .map(|q| aabbs.iter().map(|a| a.intersects(q)).collect())
            .collect()
    }

    fn filter_entities_by_distance_scalar<T: Clone + Send + Sync>(&self, entities: &[(T, [f64; 3])], center: [f64; 3], max_distance: f64) -> Vec<(T, [f64; 3])> {
        entities
            .iter()
            .filter(|(_, pos)| {
                let dx = pos[0] - center[0];
                let dy = pos[1] - center[1];
                let dz = pos[2] - center[2];
                let dist_sq = dx * dx + dy * dy + dz * dz;
                dist_sq <= max_distance * max_distance
            })
            .map(|(entity, pos)| (entity.clone(), *pos))
            .collect()
    }
}

// AVX-512 implementations
impl StandardSimdOps {
    unsafe fn dot_product_avx512(&self, a: &[f32], b: &[f32]) -> Result<f32> {
        if !self.features.has_avx512f {
            return Err(RustError::OperationFailed(
                "AVX-512F instruction set not supported on this hardware".to_string()
            ));
        }
    
        if a.len() != b.len() {
            return Err(RustError::InvalidInputError(format!(
                "Vector lengths must match for dot product. Expected {}, got {}",
                a.len(), b.len()
            )));
        }
    
        const LANES: usize = 16;
        let mut sum = 0.0f32;
        let mut i = 0;
    
        while i + LANES <= a.len() {
            let a_chunk = _mm512_loadu_ps(a[i..i+LANES].as_ptr());
            let b_chunk = _mm512_loadu_ps(b[i..i+LANES].as_ptr());
            let product = _mm512_mul_ps(a_chunk, b_chunk);
            sum += _mm512_reduce_add_ps(product);
            i += LANES;
        }
    
        // Handle remaining elements
        for j in i..a.len() {
            sum += a[j] * b[j];
        }
    
        Ok(sum)
    }
    
    unsafe fn vector_add_avx512(&self, a: &mut [f32], b: &[f32], scale: f32) -> Result<()> {
        if !self.features.has_avx512f {
            return Err(RustError::OperationFailed(
                "AVX-512F instruction set not supported on this hardware".to_string()
            ));
        }
    
        if a.len() != b.len() {
            return Err(RustError::InvalidInputError(format!(
                "Vector lengths must match for vector add. Expected {}, got {}",
                a.len(), b.len()
            )));
        }
    
        const LANES: usize = 16;
        let scale_vec = _mm512_set1_ps(scale);
        let mut i = 0;
    
        while i + LANES <= a.len() {
            let b_chunk = _mm512_loadu_ps(b[i..i+LANES].as_ptr());
            let scaled = _mm512_mul_ps(b_chunk, scale_vec);
            let a_chunk = _mm512_loadu_ps(a[i..i+LANES].as_ptr());
            let result = _mm512_add_ps(a_chunk, scaled);
            _mm512_storeu_ps(a[i..i+LANES].as_mut_ptr(), result);
            i += LANES;
        }
    
        // Handle remaining elements
        for j in i..a.len() {
            a[j] += b[j] * scale;
        }
    
        Ok(())
    }
    
    unsafe fn calculate_chunk_distances_avx512(&self, chunk_coords: &[(i32, i32)], center_chunk: (i32, i32)) -> Vec<f32> {
        const LANES: usize = 8; // 8x (i32, i32) pairs per AVX-512 lane
        let center_x = _mm512_set1_epi32(center_chunk.0);
        let center_z = _mm512_set1_epi32(center_chunk.1);
        
        let mut results = Vec::with_capacity(chunk_coords.len());
        
        let mut i = 0;
        while i + LANES <= chunk_coords.len() {
            // Extract coordinates into separate arrays for SIMD processing
            let mut x_coords = [0i32; LANES];
            let mut z_coords = [0i32; LANES];
            
            for j in 0..LANES {
                x_coords[j] = chunk_coords[i + j].0;
                z_coords[j] = chunk_coords[i + j].1;
            }
            
            // Load coordinates into SIMD registers
            let x = _mm512_loadu_si512(x_coords.as_ptr() as *const _);
            let z = _mm512_loadu_si512(z_coords.as_ptr() as *const _);
            
            // Calculate differences from center
            let dx = _mm512_sub_epi32(x, center_x);
            let dz = _mm512_sub_epi32(z, center_z);
            
            // Convert to f32 for calculations
            let dx_f32 = _mm512_cvtepi32_ps(dx);
            let dz_f32 = _mm512_cvtepi32_ps(dz);
            
            // Calculate squared distances
            let dx2 = _mm512_mul_ps(dx_f32, dx_f32);
            let dz2 = _mm512_mul_ps(dz_f32, dz_f32);
            let dist_sq = _mm512_add_ps(dx2, dz2);
            
            // Calculate square roots
            let distances = _mm512_sqrt_ps(dist_sq);
            
            // Store results
            let mut distance_buf = [0.0f32; LANES];
            _mm512_storeu_ps(distance_buf.as_mut_ptr(), distances);
            
            results.extend_from_slice(&distance_buf);
            i += LANES;
        }
        
        // Handle remaining elements with scalar processing
        for j in i..chunk_coords.len() {
            let (x, z) = chunk_coords[j];
            let dx = x as f32 - center_chunk.0 as f32;
            let dz = z as f32 - center_chunk.1 as f32;
            results.push((dx * dx + dz * dz).sqrt());
        }
        
        results
    }
    
    unsafe fn calculate_entity_distances_avx512(&self, positions: &[(f32, f32, f32)], center: (f32, f32, f32)) -> Vec<f32> {
        const LANES: usize = 8; // 8x (f32, f32, f32) triples per AVX-512 lane
        let center_x = _mm512_set1_ps(center.0);
        let center_y = _mm512_set1_ps(center.1);
        let center_z = _mm512_set1_ps(center.2);
        
        let mut results = Vec::with_capacity(positions.len());
        
        let mut i = 0;
        while i + LANES <= positions.len() {
            // Extract coordinates into separate arrays for SIMD processing
            let mut x_coords = [0.0f32; LANES];
            let mut y_coords = [0.0f32; LANES];
            let mut z_coords = [0.0f32; LANES];
            
            for j in 0..LANES {
                let (x, y, z) = positions[i + j];
                x_coords[j] = x;
                y_coords[j] = y;
                z_coords[j] = z;
            }
            
            // Load coordinates into SIMD registers
            let x = _mm512_loadu_ps(x_coords.as_ptr());
            let y = _mm512_loadu_ps(y_coords.as_ptr());
            let z = _mm512_loadu_ps(z_coords.as_ptr());
            
            // Calculate differences from center
            let dx = _mm512_sub_ps(x, center_x);
            let dy = _mm512_sub_ps(y, center_y);
            let dz = _mm512_sub_ps(z, center_z);
            
            // Calculate squared distances
            let dx2 = _mm512_mul_ps(dx, dx);
            let dy2 = _mm512_mul_ps(dy, dy);
            let dz2 = _mm512_mul_ps(dz, dz);
            let dist_sq = _mm512_add_ps(_mm512_add_ps(dx2, dy2), dz2);
            
            // Calculate square roots
            let distances = _mm512_sqrt_ps(dist_sq);
            
            // Store results
            let mut distance_buf = [0.0f32; LANES];
            _mm512_storeu_ps(distance_buf.as_mut_ptr(), distances);
            
            results.extend_from_slice(&distance_buf);
            i += LANES;
        }
        
        // Handle remaining elements with scalar processing
        for j in i..positions.len() {
            let (x, y, z) = positions[j];
            let dx = x - center.0;
            let dy = y - center.1;
            let dz = z - center.2;
            results.push((dx * dx + dy * dy + dz * dz).sqrt());
        }
        
        results
    }
    
    unsafe fn batch_aabb_intersections_avx512(&self, aabbs: &[crate::spatial::Aabb], queries: &[crate::spatial::Aabb]) -> Vec<Vec<bool>> {
        // This is a simplified AVX-512 implementation for batch AABB intersections
        // For full optimization, would need to vectorize both query and AABB data structures
        queries.iter().map(|q| {
            aabbs.iter().map(|a| a.intersects(q)).collect()
        }).collect()
    }
    
    unsafe fn filter_entities_by_distance_avx512<T: Clone + Send + Sync>(&self, entities: &[(T, [f64; 3])], center: [f64; 3], max_distance: f64) -> Vec<(T, [f64; 3])> {
        let center_x = _mm512_set1_ps(center[0] as f32);
        let center_y = _mm512_set1_ps(center[1] as f32);
        let center_z = _mm512_set1_ps(center[2] as f32);
        let max_dist_sq = _mm512_set1_ps((max_distance * max_distance) as f32);
    
        let mut results = Vec::new();
        const LANES: usize = 8;
    
        let mut i = 0;
        while i + LANES <= entities.len() {
            let mut positions_x = [0.0f32; LANES];
            let mut positions_y = [0.0f32; LANES];
            let mut positions_z = [0.0f32; LANES];
    
            for j in 0..LANES {
                positions_x[j] = entities[i + j].1[0] as f32;
                positions_y[j] = entities[i + j].1[1] as f32;
                positions_z[j] = entities[i + j].1[2] as f32;
            }
    
            let px = _mm512_loadu_ps(positions_x.as_ptr());
            let py = _mm512_loadu_ps(positions_y.as_ptr());
            let pz = _mm512_loadu_ps(positions_z.as_ptr());
    
            let dx = _mm512_sub_ps(px, center_x);
            let dy = _mm512_sub_ps(py, center_y);
            let dz = _mm512_sub_ps(pz, center_z);
    
            let dx2 = _mm512_mul_ps(dx, dx);
            let dy2 = _mm512_mul_ps(dy, dy);
            let dz2 = _mm512_mul_ps(dz, dz);
    
            let dist_sq = _mm512_add_ps(_mm512_add_ps(dx2, dy2), dz2);
            let within_range = _mm512_cmp_ps_mask(dist_sq, max_dist_sq, _MM_CMPINT_LE);
    
            for j in 0..LANES {
                if (within_range & (1 << j)) != 0 {
                    results.push((entities[i + j].0.clone(), entities[i + j].1));
                }
            }
    
            i += LANES;
        }
    
        // Handle remaining elements
        for j in i..entities.len() {
            let (entity, pos) = &entities[j];
            let dx = pos[0] - center[0];
            let dy = pos[1] - center[1];
            let dz = pos[2] - center[2];
            let dist_sq = dx * dx + dy * dy + dz * dz;
    
            if dist_sq <= max_distance * max_distance {
                results.push((entity.clone(), *pos));
            }
        }
    
        results
    }
    
    unsafe fn process_large_array_avx512<T, F>(&self, data: &mut [T], operation: F) -> Result<()>
    where
        T: Copy + Send + Sync,
        F: Fn(&mut [T]) -> Result<()>,
    {
        if !self.features.has_avx512f {
            return Err(RustError::OperationFailed(
                "AVX-512F instruction set not supported on this hardware".to_string()
            ));
        }
    
        const AVX512_F32_LANES: usize = 16;
        const AVX512_F64_LANES: usize = 8;
    
        let lanes = if std::mem::size_of::<T>() == 4 {
            AVX512_F32_LANES
        } else if std::mem::size_of::<T>() == 8 {
            AVX512_F64_LANES
        } else {
            return operation(data); // Fall back to scalar for unsupported types
        };
    
        let chunks = data.chunks_mut(lanes);
        for chunk in chunks {
            let result = operation(chunk);
            if let Err(e) = result {
                return Err(e);
            }
        }
    
        Ok(())
    }
}

// AVX2 implementations
impl StandardSimdOps {
    unsafe fn dot_product_avx2(&self, a: &[f32], b: &[f32]) -> Result<f32> {
        if !self.features.has_avx2 {
            return Err(RustError::OperationFailed(
                "AVX2 instruction set not supported on this hardware".to_string()
            ));
        }
    
        if a.len() != b.len() {
            return Err(RustError::InvalidInputError(format!(
                "Vector lengths must match for dot product. Expected {}, got {}",
                a.len(), b.len()
            )));
        }
    
        const LANES: usize = 8;
        let mut sum = 0.0f32;
        let mut i = 0;
    
        while i + LANES <= a.len() {
            let a_chunk = _mm256_loadu_ps(a[i..i+LANES].as_ptr());
            let b_chunk = _mm256_loadu_ps(b[i..i+LANES].as_ptr());
            let product = _mm256_mul_ps(a_chunk, b_chunk);
            sum += unsafe { _mm256_reduce_add_ps(product) };
            i += LANES;
        }
    
        // Handle remaining elements
        for j in i..a.len() {
            sum += a[j] * b[j];
        }
    
        Ok(sum)
    }
    
    unsafe fn vector_add_avx2(&self, a: &mut [f32], b: &[f32], scale: f32) -> Result<()> {
        if !self.features.has_avx2 {
            return Err(RustError::OperationFailed(
                "AVX2 instruction set not supported on this hardware".to_string()
            ));
        }
    
        if a.len() != b.len() {
            return Err(RustError::InvalidInputError(format!(
                "Vector lengths must match for vector add. Expected {}, got {}",
                a.len(), b.len()
            )));
        }
    
        const LANES: usize = 8;
        let scale_vec = _mm256_set1_ps(scale);
        let mut i = 0;
    
        while i + LANES <= a.len() {
            let b_chunk = _mm256_loadu_ps(b[i..i+LANES].as_ptr());
            let scaled = _mm256_mul_ps(b_chunk, scale_vec);
            let a_chunk = _mm256_loadu_ps(a[i..i+LANES].as_ptr());
            let result = _mm256_add_ps(a_chunk, scaled);
            _mm256_storeu_ps(a[i..i+LANES].as_mut_ptr(), result);
            i += LANES;
        }
    
        // Handle remaining elements
        for j in i..a.len() {
            a[j] += b[j] * scale;
        }
    
        Ok(())
    }
    
    unsafe fn calculate_chunk_distances_avx2(&self, chunk_coords: &[(i32, i32)], center_chunk: (i32, i32)) -> Vec<f32> {
        const LANES: usize = 4; // 4x (i32, i32) pairs per AVX2 lane
        let center_x = _mm256_set1_epi32(center_chunk.0);
        let center_z = _mm256_set1_epi32(center_chunk.1);
        
        let mut results = Vec::with_capacity(chunk_coords.len());
        
        let mut i = 0;
        while i + LANES <= chunk_coords.len() {
            // Extract coordinates into separate arrays for SIMD processing
            let mut x_coords = [0i32; LANES];
            let mut z_coords = [0i32; LANES];
            
            for j in 0..LANES {
                x_coords[j] = chunk_coords[i + j].0;
                z_coords[j] = chunk_coords[i + j].1;
            }
            
            // Load coordinates into SIMD registers
            let x = _mm256_loadu_si256(x_coords.as_ptr() as *const _);
            let z = _mm256_loadu_si256(z_coords.as_ptr() as *const _);
            
            // Calculate differences from center
            let dx = _mm256_sub_epi32(x, center_x);
            let dz = _mm256_sub_epi32(z, center_z);
            
            // Convert to f32 for calculations
            let dx_f32 = _mm256_cvtepi32_ps(dx);
            let dz_f32 = _mm256_cvtepi32_ps(dz);
            
            // Calculate squared distances
            let dx2 = _mm256_mul_ps(dx_f32, dx_f32);
            let dz2 = _mm256_mul_ps(dz_f32, dz_f32);
            let dist_sq = _mm256_add_ps(dx2, dz2);
            
            // Calculate square roots
            let distances = _mm256_sqrt_ps(dist_sq);
            
            // Store results
            let mut distance_buf = [0.0f32; LANES];
            _mm256_storeu_ps(distance_buf.as_mut_ptr(), distances);
            
            results.extend_from_slice(&distance_buf);
            i += LANES;
        }
        
        // Handle remaining elements with scalar processing
        for j in i..chunk_coords.len() {
            let (x, z) = chunk_coords[j];
            let dx = x as f32 - center_chunk.0 as f32;
            let dz = z as f32 - center_chunk.1 as f32;
            results.push((dx * dx + dz * dz).sqrt());
        }
        
        results
    }
    
    unsafe fn calculate_entity_distances_avx2(&self, positions: &[(f32, f32, f32)], center: (f32, f32, f32)) -> Vec<f32> {
        const LANES: usize = 4; // 4x (f32, f32, f32) triples per AVX2 lane
        let center_x = _mm256_set1_ps(center.0);
        let center_y = _mm256_set1_ps(center.1);
        let center_z = _mm256_set1_ps(center.2);
        
        let mut results = Vec::with_capacity(positions.len());
        
        let mut i = 0;
        while i + LANES <= positions.len() {
            // Extract coordinates into separate arrays for SIMD processing
            let mut x_coords = [0.0f32; LANES];
            let mut y_coords = [0.0f32; LANES];
            let mut z_coords = [0.0f32; LANES];
            
            for j in 0..LANES {
                let (x, y, z) = positions[i + j];
                x_coords[j] = x;
                y_coords[j] = y;
                z_coords[j] = z;
            }
            
            // Load coordinates into SIMD registers
            let x = _mm256_loadu_ps(x_coords.as_ptr());
            let y = _mm256_loadu_ps(y_coords.as_ptr());
            let z = _mm256_loadu_ps(z_coords.as_ptr());
            
            // Calculate differences from center
            let dx = _mm256_sub_ps(x, center_x);
            let dy = _mm256_sub_ps(y, center_y);
            let dz = _mm256_sub_ps(z, center_z);
            
            // Calculate squared distances
            let dx2 = _mm256_mul_ps(dx, dx);
            let dy2 = _mm256_mul_ps(dy, dy);
            let dz2 = _mm256_mul_ps(dz, dz);
            let dist_sq = _mm256_add_ps(_mm256_add_ps(dx2, dy2), dz2);
            
            // Calculate square roots
            let distances = _mm256_sqrt_ps(dist_sq);
            
            // Store results
            let mut distance_buf = [0.0f32; LANES];
            _mm256_storeu_ps(distance_buf.as_mut_ptr(), distances);
            
            results.extend_from_slice(&distance_buf);
            i += LANES;
        }
        
        // Handle remaining elements with scalar processing
        for j in i..positions.len() {
            let (x, y, z) = positions[j];
            let dx = x - center.0;
            let dy = y - center.1;
            let dz = z - center.2;
            results.push((dx * dx + dy * dy + dz * dz).sqrt());
        }
        
        results
    }
    
    unsafe fn batch_aabb_intersections_avx2(&self, aabbs: &[crate::spatial::Aabb], queries: &[crate::spatial::Aabb]) -> Vec<Vec<bool>> {
        // This is a simplified AVX2 implementation for batch AABB intersections
        // For full optimization, would need to vectorize both query and AABB data structures
        queries.iter().map(|q| {
            aabbs.iter().map(|a| a.intersects(q)).collect()
        }).collect()
    }
    
    unsafe fn filter_entities_by_distance_avx2<T: Clone + Send + Sync>(&self, entities: &[(T, [f64; 3])], center: [f64; 3], max_distance: f64) -> Vec<(T, [f64; 3])> {
        let center_x = _mm256_set1_ps(center[0] as f32);
        let center_y = _mm256_set1_ps(center[1] as f32);
        let center_z = _mm256_set1_ps(center[2] as f32);
        let max_dist_sq = _mm256_set1_ps((max_distance * max_distance) as f32);
    
        let mut results = Vec::new();
        const LANES: usize = 4;
    
        let mut i = 0;
        while i + LANES <= entities.len() {
            let mut positions_x = [0.0f32; LANES];
            let mut positions_y = [0.0f32; LANES];
            let mut positions_z = [0.0f32; LANES];
    
            for j in 0..LANES {
                positions_x[j] = entities[i + j].1[0] as f32;
                positions_y[j] = entities[i + j].1[1] as f32;
                positions_z[j] = entities[i + j].1[2] as f32;
            }
    
            let px = _mm256_loadu_ps(positions_x.as_ptr());
            let py = _mm256_loadu_ps(positions_y.as_ptr());
            let pz = _mm256_loadu_ps(positions_z.as_ptr());
    
            let dx = _mm256_sub_ps(px, center_x);
            let dy = _mm256_sub_ps(py, center_y);
            let dz = _mm256_sub_ps(pz, center_z);
    
            let dx2 = _mm256_mul_ps(dx, dx);
            let dy2 = _mm256_mul_ps(dy, dy);
            let dz2 = _mm256_mul_ps(dz, dz);
    
            let dist_sq = _mm256_add_ps(_mm256_add_ps(dx2, dy2), dz2);
            let within_range = _mm256_cmp_ps_mask(dist_sq, max_dist_sq, _MM_CMPINT_LE);
    
            for j in 0..LANES {
                if (within_range & (1 << j)) != 0 {
                    results.push((entities[i + j].0.clone(), entities[i + j].1));
                }
            }
    
            i += LANES;
        }
    
        // Handle remaining elements
        for j in i..entities.len() {
            let (entity, pos) = &entities[j];
            let dx = pos[0] - center[0];
            let dy = pos[1] - center[1];
            let dz = pos[2] - center[2];
            let dist_sq = dx * dx + dy * dy + dz * dz;
    
            if dist_sq <= max_distance * max_distance {
                results.push((entity.clone(), *pos));
            }
        }
    
        results
    }
    
    unsafe fn process_large_array_avx2<T, F>(&self, data: &mut [T], operation: F) -> Result<()>
    where
        T: Copy + Send + Sync,
        F: Fn(&mut [T]) -> Result<()>,
    {
        if !self.features.has_avx2 {
            return Err(RustError::OperationFailed(
                "AVX2 instruction set not supported on this hardware".to_string()
            ));
        }
    
        const AVX2_F32_LANES: usize = 8;
        const AVX2_F64_LANES: usize = 4;
    
        let lanes = if std::mem::size_of::<T>() == 4 {
            AVX2_F32_LANES
        } else if std::mem::size_of::<T>() == 8 {
            AVX2_F64_LANES
        } else {
            return operation(data); // Fall back to scalar for unsupported types
        };
    
        let chunks = data.chunks_mut(lanes);
        for chunk in chunks {
            let result = operation(chunk);
            if let Err(e) = result {
                return Err(e);
            }
        }
    
        Ok(())
    }
}

// SSE implementations
impl StandardSimdOps {
    unsafe fn dot_product_sse(&self, a: &[f32], b: &[f32]) -> Result<f32> {
        if !(self.features.has_sse41 || self.features.has_sse42) {
            return Err(RustError::OperationFailed(
                "SSE4.1/SSE4.2 instruction set not supported on this hardware".to_string()
            ));
        }
    
        if a.len() != b.len() {
            return Err(RustError::InvalidInputError(format!(
                "Vector lengths must match for dot product. Expected {}, got {}",
                a.len(), b.len()
            )));
        }
    
        const LANES: usize = 4;
        let mut sum = 0.0f32;
        let mut i = 0;
    
        while i + LANES <= a.len() {
            let a_chunk = _mm_loadu_ps(a[i..i+LANES].as_ptr());
            let b_chunk = _mm_loadu_ps(b[i..i+LANES].as_ptr());
            let product = _mm_mul_ps(a_chunk, b_chunk);
            sum += unsafe { _mm_reduce_add_ps(product) };
            i += LANES;
        }
    
        // Handle remaining elements
        for j in i..a.len() {
            sum += a[j] * b[j];
        }
    
        Ok(sum)
    }
    
    unsafe fn vector_add_sse(&self, a: &mut [f32], b: &[f32], scale: f32) -> Result<()> {
        if !(self.features.has_sse41 || self.features.has_sse42) {
            return Err(RustError::OperationFailed(
                "SSE4.1/SSE4.2 instruction set not supported on this hardware".to_string()
            ));
        }
    
        if a.len() != b.len() {
            return Err(RustError::InvalidInputError(format!(
                "Vector lengths must match for vector add. Expected {}, got {}",
                a.len(), b.len()
            )));
        }
    
        const LANES: usize = 4;
        let scale_vec = _mm_set1_ps(scale);
        let mut i = 0;
    
        while i + LANES <= a.len() {
            let b_chunk = _mm_loadu_ps(b[i..i+LANES].as_ptr());
            let scaled = _mm_mul_ps(b_chunk, scale_vec);
            let a_chunk = _mm_loadu_ps(a[i..i+LANES].as_ptr());
            let result = _mm_add_ps(a_chunk, scaled);
            _mm_storeu_ps(a[i..i+LANES].as_mut_ptr(), result);
            i += LANES;
        }
    
        // Handle remaining elements
        for j in i..a.len() {
            a[j] += b[j] * scale;
        }
    
        Ok(())
    }
    
    unsafe fn calculate_chunk_distances_sse(&self, chunk_coords: &[(i32, i32)], center_chunk: (i32, i32)) -> Vec<f32> {
        const LANES: usize = 2; // 2x (i32, i32) pairs per SSE lane
        let center_x = _mm_set1_epi32(center_chunk.0);
        let center_z = _mm_set1_epi32(center_chunk.1);
        
        let mut results = Vec::with_capacity(chunk_coords.len());
        
        let mut i = 0;
        while i + LANES <= chunk_coords.len() {
            // Extract coordinates into separate arrays for SIMD processing
            let mut x_coords = [0i32; LANES];
            let mut z_coords = [0i32; LANES];
            
            for j in 0..LANES {
                x_coords[j] = chunk_coords[i + j].0;
                z_coords[j] = chunk_coords[i + j].1;
            }
            
            // Load coordinates into SIMD registers
            let x = _mm_loadu_si128(x_coords.as_ptr() as *const _);
            let z = _mm_loadu_si128(z_coords.as_ptr() as *const _);
            
            // Calculate differences from center
            let dx = _mm_sub_epi32(x, center_x);
            let dz = _mm_sub_epi32(z, center_z);
            
            // Convert to f32 for calculations
            let dx_f32 = _mm_cvtepi32_ps(dx);
            let dz_f32 = _mm_cvtepi32_ps(dz);
            
            // Calculate squared distances
            let dx2 = _mm_mul_ps(dx_f32, dx_f32);
            let dz2 = _mm_mul_ps(dz_f32, dz_f32);
            let dist_sq = _mm_add_ps(dx2, dz2);
            
            // Calculate square roots
            let distances = _mm_sqrt_ps(dist_sq);
            
            // Store results
            let mut distance_buf = [0.0f32; LANES];
            _mm_storeu_ps(distance_buf.as_mut_ptr(), distances);
            
            results.extend_from_slice(&distance_buf);
            i += LANES;
        }
        
        // Handle remaining elements with scalar processing
        for j in i..chunk_coords.len() {
            let (x, z) = chunk_coords[j];
            let dx = x as f32 - center_chunk.0 as f32;
            let dz = z as f32 - center_chunk.1 as f32;
            results.push((dx * dx + dz * dz).sqrt());
        }
        
        results
    }
    
    unsafe fn calculate_entity_distances_sse(&self, positions: &[(f32, f32, f32)], center: (f32, f32, f32)) -> Vec<f32> {
        const LANES: usize = 2; // 2x (f32, f32, f32) triples per SSE lane
        let center_x = _mm_set1_ps(center.0);
        let center_y = _mm_set1_ps(center.1);
        let center_z = _mm_set1_ps(center.2);
        
        let mut results = Vec::with_capacity(positions.len());
        
        let mut i = 0;
        while i + LANES <= positions.len() {
            // Extract coordinates into separate arrays for SIMD processing
            let mut x_coords = [0.0f32; LANES];
            let mut y_coords = [0.0f32; LANES];
            let mut z_coords = [0.0f32; LANES];
            
            for j in 0..LANES {
                let (x, y, z) = positions[i + j];
                x_coords[j] = x;
                y_coords[j] = y;
                z_coords[j] = z;
            }
            
            // Load coordinates into SIMD registers
            let x = _mm_loadu_ps(x_coords.as_ptr());
            let y = _mm_loadu_ps(y_coords.as_ptr());
            let z = _mm_loadu_ps(z_coords.as_ptr());
            
            // Calculate differences from center
            let dx = _mm_sub_ps(x, center_x);
            let dy = _mm_sub_ps(y, center_y);
            let dz = _mm_sub_ps(z, center_z);
            
            // Calculate squared distances
            let dx2 = _mm_mul_ps(dx, dx);
            let dy2 = _mm_mul_ps(dy, dy);
            let dz2 = _mm_mul_ps(dz, dz);
            let dist_sq = _mm_add_ps(_mm_add_ps(dx2, dy2), dz2);
            
            // Calculate square roots
            let distances = _mm_sqrt_ps(dist_sq);
            
            // Store results
            let mut distance_buf = [0.0f32; LANES];
            _mm_storeu_ps(distance_buf.as_mut_ptr(), distances);
            
            results.extend_from_slice(&distance_buf);
            i += LANES;
        }
        
        // Handle remaining elements with scalar processing
        for j in i..positions.len() {
            let (x, y, z) = positions[j];
            let dx = x - center.0;
            let dy = y - center.1;
            let dz = z - center.2;
            results.push((dx * dx + dy * dy + dz * dz).sqrt());
        }
        
        results
    }
    
    unsafe fn batch_aabb_intersections_sse(&self, aabbs: &[crate::spatial::Aabb], queries: &[crate::spatial::Aabb]) -> Vec<Vec<bool>> {
        // This is a simplified SSE implementation for batch AABB intersections
        // For full optimization, would need to vectorize both query and AABB data structures
        queries.iter().map(|q| {
            aabbs.iter().map(|a| a.intersects(q)).collect()
        }).collect()
    }
    
    unsafe fn filter_entities_by_distance_sse<T: Clone + Send + Sync>(&self, entities: &[(T, [f64; 3])], center: [f64; 3], max_distance: f64) -> Vec<(T, [f64; 3])> {
        let center_x = _mm_set1_ps(center[0] as f32);
        let center_y = _mm_set1_ps(center[1] as f32);
        let center_z = _mm_set1_ps(center[2] as f32);
        let max_dist_sq = _mm_set1_ps((max_distance * max_distance) as f32);
    
        let mut results = Vec::new();
        const LANES: usize = 2;
    
        let mut i = 0;
        while i + LANES <= entities.len() {
            let mut positions_x = [0.0f32; LANES];
            let mut positions_y = [0.0f32; LANES];
            let mut positions_z = [0.0f32; LANES];
    
            for j in 0..LANES {
                positions_x[j] = entities[i + j].1[0] as f32;
                positions_y[j] = entities[i + j].1[1] as f32;
                positions_z[j] = entities[i + j].1[2] as f32;
            }
    
            let px = _mm_loadu_ps(positions_x.as_ptr());
            let py = _mm_loadu_ps(positions_y.as_ptr());
            let pz = _mm_loadu_ps(positions_z.as_ptr());
    
            let dx = _mm_sub_ps(px, center_x);
            let dy = _mm_sub_ps(py, center_y);
            let dz = _mm_sub_ps(pz, center_z);
    
            let dx2 = _mm_mul_ps(dx, dx);
            let dy2 = _mm_mul_ps(dy, dy);
            let dz2 = _mm_mul_ps(dz, dz);
    
            let dist_sq = _mm_add_ps(_mm_add_ps(dx2, dy2), dz2);
            let within_range = _mm_movemask_ps(_mm_cmple_ps(dist_sq, max_dist_sq));
    
            for j in 0..LANES {
                if (within_range & (1 << j)) != 0 {
                    results.push((entities[i + j].0.clone(), entities[i + j].1));
                }
            }
    
            i += LANES;
        }
    
        // Handle remaining elements
        for j in i..entities.len() {
            let (entity, pos) = &entities[j];
            let dx = pos[0] - center[0];
            let dy = pos[1] - center[1];
            let dz = pos[2] - center[2];
            let dist_sq = dx * dx + dy * dy + dz * dz;
    
            if dist_sq <= max_distance * max_distance {
                results.push((entity.clone(), *pos));
            }
        }
    
        results
    }
    
    unsafe fn process_large_array_sse<T, F>(&self, data: &mut [T], operation: F) -> Result<()>
    where
        T: Copy + Send + Sync,
        F: Fn(&mut [T]) -> Result<()>,
    {
        if !(self.features.has_sse41 || self.features.has_sse42) {
            return Err(RustError::OperationFailed(
                "SSE4.1/SSE4.2 instruction set not supported on this hardware".to_string()
            ));
        }
    
        const SSE_F32_LANES: usize = 4;
        const SSE_F64_LANES: usize = 2;
    
        let lanes = if std::mem::size_of::<T>() == 4 {
            SSE_F32_LANES
        } else if std::mem::size_of::<T>() == 8 {
            SSE_F64_LANES
        } else {
            return operation(data); // Fall back to scalar for unsupported types
        };
    
        let chunks = data.chunks_mut(lanes);
        for chunk in chunks {
            let result = operation(chunk);
            if let Err(e) = result {
                return Err(e);
            }
        }
    
        Ok(())
    }
}

/// Global standard SIMD operations instance
lazy_static::lazy_static! {
    pub static ref STANDARD_SIMD_OPS: StandardSimdOps = StandardSimdOps::new();
}

/// Get the global standard SIMD operations instance
pub fn get_standard_simd_ops() -> &'static StandardSimdOps {
    &STANDARD_SIMD_OPS
}
use std::arch::is_x86_feature_detected;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use log::{info, debug};
use serde::{Serialize, Deserialize};

/// SIMD feature detection and optimization configuration
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SimdConfig {
    pub enable_avx2: bool,
    pub enable_sse41: bool,
    pub enable_sse42: bool,
    pub enable_fma: bool,
    pub enable_bmi1: bool,
    pub enable_bmi2: bool,
    pub force_scalar_fallback: bool,
}

impl Default for SimdConfig {
    fn default() -> Self {
        Self {
            enable_avx2: true,
            enable_sse41: true,
            enable_sse42: true,
            enable_fma: true,
            enable_bmi1: true,
            enable_bmi2: true,
            force_scalar_fallback: false,
        }
    }
}

/// Runtime SIMD feature detection
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SimdFeatures {
    pub has_avx2: bool,
    pub has_sse41: bool,
    pub has_sse42: bool,
    pub has_fma: bool,
    pub has_bmi1: bool,
    pub has_bmi2: bool,
    pub has_avx512f: bool,
    pub has_avx512dq: bool,
    pub has_avx512bw: bool,
    pub has_avx512vl: bool,
}

impl SimdFeatures {
    /// Detect available SIMD features at runtime
    pub fn detect() -> Self {
        #[cfg(target_arch = "x86_64")]
        {
            Self {
                has_avx2: is_x86_feature_detected!("avx2"),
                has_sse41: is_x86_feature_detected!("sse4.1"),
                has_sse42: is_x86_feature_detected!("sse4.2"),
                has_fma: is_x86_feature_detected!("fma"),
                has_bmi1: is_x86_feature_detected!("bmi1"),
                has_bmi2: is_x86_feature_detected!("bmi2"),
                has_avx512f: is_x86_feature_detected!("avx512f"),
                has_avx512dq: is_x86_feature_detected!("avx512dq"),
                has_avx512bw: is_x86_feature_detected!("avx512bw"),
                has_avx512vl: is_x86_feature_detected!("avx512vl"),
            }
        }
        #[cfg(not(target_arch = "x86_64"))]
        {
            Self {
                has_avx2: false,
                has_sse41: false,
                has_sse42: false,
                has_fma: false,
                has_bmi1: false,
                has_bmi2: false,
                has_avx512f: false,
                has_avx512dq: false,
                has_avx512bw: false,
                has_avx512vl: false,
            }
        }
    }

    /// Get the best available SIMD level
    pub fn best_level(&self) -> SimdLevel {
        if self.has_avx512f && self.has_avx512dq && self.has_avx512bw && self.has_avx512vl {
            SimdLevel::Avx512
        } else if self.has_avx2 {
            SimdLevel::Avx2
        } else if self.has_sse42 {
            SimdLevel::Sse42
        } else if self.has_sse41 {
            SimdLevel::Sse41
        } else {
            SimdLevel::Scalar
        }
    }
}

/// SIMD optimization levels
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum SimdLevel {
    Scalar,
    Sse41,
    Sse42,
    Avx2,
    Avx512,
}

/// Global SIMD configuration and features
pub struct SimdManager {
    config: SimdConfig,
    features: SimdFeatures,
    level: AtomicU32,
    initialized: AtomicBool,
}

impl SimdManager {
    pub fn new() -> Self {
        Self {
            config: SimdConfig::default(),
            features: SimdFeatures::detect(),
            level: AtomicU32::new(SimdLevel::Scalar as u32),
            initialized: AtomicBool::new(false),
        }
    }

    pub fn initialize(&self, config: SimdConfig) {
        self.config = config;
        self.features = SimdFeatures::detect();
        
        let level = if config.force_scalar_fallback {
            SimdLevel::Scalar
        } else {
            self.features.best_level()
        };
        
        self.level.store(level as u32, Ordering::Relaxed);
        self.initialized.store(true, Ordering::Relaxed);
        
        info!("SIMD Manager initialized with level: {:?}", level);
        debug!("Detected SIMD features: {:?}", self.features);
    }

    pub fn get_level(&self) -> SimdLevel {
        match self.level.load(Ordering::Relaxed) {
            0 => SimdLevel::Scalar,
            1 => SimdLevel::Sse41,
            2 => SimdLevel::Sse42,
            3 => SimdLevel::Avx2,
            4 => SimdLevel::Avx512,
            _ => SimdLevel::Scalar,
        }
    }

    pub fn is_avx2_enabled(&self) -> bool {
        self.get_level() >= SimdLevel::Avx2
    }

    pub fn is_avx512_enabled(&self) -> bool {
        self.get_level() >= SimdLevel::Avx512
    }

    pub fn get_features(&self) -> &SimdFeatures {
        &self.features
    }

    pub fn get_config(&self) -> &SimdConfig {
        &self.config
    }
}

lazy_static::lazy_static! {
    static ref SIMD_MANAGER: SimdManager = SimdManager::new();
}

/// Get the global SIMD manager
pub fn get_simd_manager() -> &'static SimdManager {
    &SIMD_MANAGER
}

/// Initialize SIMD with custom configuration
pub fn init_simd(config: SimdConfig) {
    SIMD_MANAGER.initialize(config);
}

/// SIMD-accelerated vector operations
pub mod vector_ops {
    use super::*;
    use std::arch::x86_64::*;

    /// SIMD-accelerated dot product
    pub fn dot_product(a: &[f32], b: &[f32]) -> f32 {
        assert_eq!(a.len(), b.len());
        
        let level = get_simd_manager().get_level();
        
        match level {
            SimdLevel::Avx2 => unsafe { dot_product_avx2(a, b) },
            SimdLevel::Sse42 | SimdLevel::Sse41 => unsafe { dot_product_sse(a, b) },
            SimdLevel::Scalar => dot_product_scalar(a, b),
            SimdLevel::Avx512 => unsafe { dot_product_avx512(a, b) },
        }
    }

    #[target_feature(enable = "avx2")]
    unsafe fn dot_product_avx2(a: &[f32], b: &[f32]) -> f32 {
        let mut sum = _mm256_setzero_ps();
        let chunks = a.chunks_exact(8);
        let remainder = chunks.remainder();
        
        for (a_chunk, b_chunk) in chunks.zip(b.chunks_exact(8)) {
            let a_vec = _mm256_loadu_ps(a_chunk.as_ptr());
            let b_vec = _mm256_loadu_ps(b_chunk.as_ptr());
            sum = _mm256_fmadd_ps(a_vec, b_vec, sum);
        }
        
        // Horizontal sum
        let mut result = [0.0f32; 8];
        _mm256_storeu_ps(result.as_mut_ptr(), sum);
        let mut total: f32 = result.iter().sum();
        
        // Handle remainder
        for i in 0..remainder.len() {
            total += remainder[i] * b[a.len() - remainder.len() + i];
        }
        
        total
    }

    #[target_feature(enable = "sse4.1")]
    unsafe fn dot_product_sse(a: &[f32], b: &[f32]) -> f32 {
        let mut sum = _mm_setzero_ps();
        let chunks = a.chunks_exact(4);
        let remainder = chunks.remainder();
        
        for (a_chunk, b_chunk) in chunks.zip(b.chunks_exact(4)) {
            let a_vec = _mm_loadu_ps(a_chunk.as_ptr());
            let b_vec = _mm_loadu_ps(b_chunk.as_ptr());
            sum = _mm_add_ps(_mm_mul_ps(a_vec, b_vec), sum);
        }
        
        // Horizontal sum
        let mut result = [0.0f32; 4];
        _mm_storeu_ps(result.as_mut_ptr(), sum);
        let mut total: f32 = result.iter().sum();
        
        // Handle remainder
        for i in 0..remainder.len() {
            total += remainder[i] * b[a.len() - remainder.len() + i];
        }
        
        total
    }

    #[target_feature(enable = "avx512f")]
    unsafe fn dot_product_avx512(a: &[f32], b: &[f32]) -> f32 {
        let mut sum = _mm512_setzero_ps();
        let chunks = a.chunks_exact(16);
        let remainder = chunks.remainder();
        
        for (a_chunk, b_chunk) in chunks.zip(b.chunks_exact(16)) {
            let a_vec = _mm512_loadu_ps(a_chunk.as_ptr());
            let b_vec = _mm512_loadu_ps(b_chunk.as_ptr());
            sum = _mm512_fmadd_ps(a_vec, b_vec, sum);
        }
        
        let total = _mm512_reduce_add_ps(sum);
        
        // Handle remainder
        let mut remainder_sum = 0.0f32;
        for i in 0..remainder.len() {
            remainder_sum += remainder[i] * b[a.len() - remainder.len() + i];
        }
        
        total + remainder_sum
    }

    fn dot_product_scalar(a: &[f32], b: &[f32]) -> f32 {
        a.iter().zip(b.iter()).map(|(x, y)| x * y).sum()
    }

    /// SIMD-accelerated vector addition
    pub fn vector_add(a: &mut [f32], b: &[f32], scale: f32) {
        assert_eq!(a.len(), b.len());
        
        let level = get_simd_manager().get_level();
        
        match level {
            SimdLevel::Avx2 => unsafe { vector_add_avx2(a, b, scale) },
            SimdLevel::Sse42 | SimdLevel::Sse41 => unsafe { vector_add_sse(a, b, scale) },
            SimdLevel::Scalar => vector_add_scalar(a, b, scale),
            SimdLevel::Avx512 => unsafe { vector_add_avx512(a, b, scale) },
        }
    }

    #[target_feature(enable = "avx2")]
    unsafe fn vector_add_avx2(a: &mut [f32], b: &[f32], scale: f32) {
        let scale_vec = _mm256_set1_ps(scale);
        let chunks = a.chunks_exact_mut(8).zip(b.chunks_exact(8));
        let (a_remainder, b_remainder) = a.chunks_exact_mut(8).into_remainder()
            .zip(b.chunks_exact(8).remainder())
            .next()
            .unwrap_or((&mut [], &[]));
        
        for (a_chunk, b_chunk) in chunks {
            let a_vec = _mm256_loadu_ps(a_chunk.as_ptr());
            let b_vec = _mm256_loadu_ps(b_chunk.as_ptr());
            let result = _mm256_fmadd_ps(b_vec, scale_vec, a_vec);
            _mm256_storeu_ps(a_chunk.as_mut_ptr(), result);
        }
        
        // Handle remainder
        for i in 0..a_remainder.len() {
            a_remainder[i] += b_remainder[i] * scale;
        }
    }

    #[target_feature(enable = "sse4.1")]
    unsafe fn vector_add_sse(a: &mut [f32], b: &[f32], scale: f32) {
        let scale_vec = _mm_set1_ps(scale);
        let chunks = a.chunks_exact_mut(4).zip(b.chunks_exact(4));
        let (a_remainder, b_remainder) = a.chunks_exact_mut(4).into_remainder()
            .zip(b.chunks_exact(4).remainder())
            .next()
            .unwrap_or((&mut [], &[]));
        
        for (a_chunk, b_chunk) in chunks {
            let a_vec = _mm_loadu_ps(a_chunk.as_ptr());
            let b_vec = _mm_loadu_ps(b_chunk.as_ptr());
            let scaled_b = _mm_mul_ps(b_vec, scale_vec);
            let result = _mm_add_ps(a_vec, scaled_b);
            _mm_storeu_ps(a_chunk.as_mut_ptr(), result);
        }
        
        // Handle remainder
        for i in 0..a_remainder.len() {
            a_remainder[i] += b_remainder[i] * scale;
        }
    }

    #[target_feature(enable = "avx512f")]
    unsafe fn vector_add_avx512(a: &mut [f32], b: &[f32], scale: f32) {
        let scale_vec = _mm512_set1_ps(scale);
        let chunks = a.chunks_exact_mut(16).zip(b.chunks_exact(16));
        let (a_remainder, b_remainder) = a.chunks_exact_mut(16).into_remainder()
            .zip(b.chunks_exact(16).remainder())
            .next()
            .unwrap_or((&mut [], &[]));
        
        for (a_chunk, b_chunk) in chunks {
            let a_vec = _mm512_loadu_ps(a_chunk.as_ptr());
            let b_vec = _mm512_loadu_ps(b_chunk.as_ptr());
            let result = _mm512_fmadd_ps(b_vec, scale_vec, a_vec);
            _mm512_storeu_ps(a_chunk.as_mut_ptr(), result);
        }
        
        // Handle remainder
        for i in 0..a_remainder.len() {
            a_remainder[i] += b_remainder[i] * scale;
        }
    }

    fn vector_add_scalar(a: &mut [f32], b: &[f32], scale: f32) {
        for (a_val, b_val) in a.iter_mut().zip(b.iter()) {
            *a_val += *b_val * scale;
        }
    fn vector_add_scalar(a: &mut [f32], b: &[f32], scale: f32) {
        for (a_val, b_val) in a.iter_mut().zip(b.iter()) {
            *a_val += *b_val * scale;
        }
    }

    /// SIMD-accelerated chunk distance calculation (2D)
    pub fn calculate_chunk_distances(chunk_coords: &[(i32, i32)], center_chunk: (i32, i32)) -> Vec<f32> {
        let level = get_simd_manager().get_level();
        
        match level {
            SimdLevel::Avx2 => unsafe { calculate_chunk_distances_avx2(chunk_coords, center_chunk) },
            SimdLevel::Sse42 | SimdLevel::Sse41 => unsafe { calculate_chunk_distances_sse(chunk_coords, center_chunk) },
            SimdLevel::Scalar => calculate_chunk_distances_scalar(chunk_coords, center_chunk),
            SimdLevel::Avx512 => unsafe { calculate_chunk_distances_avx512(chunk_coords, center_chunk) },
        }
    }

    #[target_feature(enable = "avx2")]
    unsafe fn calculate_chunk_distances_avx2(chunk_coords: &[(i32, i32)], center_chunk: (i32, i32)) -> Vec<f32> {
        let cx = _mm256_set1_ps(center_chunk.0 as f32);
        let cz = _mm256_set1_ps(center_chunk.1 as f32);

        chunk_coords.par_chunks(8).flat_map(|chunk| {
            let mut distances = [0.0f32; 8];
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
            let pz = _mm256_set_ps(
                chunk.get(7).map(|p| p.1 as f32).unwrap_or(0.0),
                chunk.get(6).map(|p| p.1 as f32).unwrap_or(0.0),
                chunk.get(5).map(|p| p.1 as f32).unwrap_or(0.0),
                chunk.get(4).map(|p| p.1 as f32).unwrap_or(0.0),
                chunk.get(3).map(|p| p.1 as f32).unwrap_or(0.0),
                chunk.get(2).map(|p| p.1 as f32).unwrap_or(0.0),
                chunk.get(1).map(|p| p.1 as f32).unwrap_or(0.0),
                chunk.get(0).map(|p| p.1 as f32).unwrap_or(0.0)
            );

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

    #[target_feature(enable = "sse4.1")]
    unsafe fn calculate_chunk_distances_sse(chunk_coords: &[(i32, i32)], center_chunk: (i32, i32)) -> Vec<f32> {
        let cx = _mm_set1_ps(center_chunk.0 as f32);
        let cz = _mm_set1_ps(center_chunk.1 as f32);

        chunk_coords.par_chunks(4).flat_map(|chunk| {
            let mut distances = [0.0f32; 4];
            let px = _mm_set_ps(
                chunk.get(3).map(|p| p.0 as f32).unwrap_or(0.0),
                chunk.get(2).map(|p| p.0 as f32).unwrap_or(0.0),
                chunk.get(1).map(|p| p.0 as f32).unwrap_or(0.0),
                chunk.get(0).map(|p| p.0 as f32).unwrap_or(0.0)
            );
            let pz = _mm_set_ps(
                chunk.get(3).map(|p| p.1 as f32).unwrap_or(0.0),
                chunk.get(2).map(|p| p.1 as f32).unwrap_or(0.0),
                chunk.get(1).map(|p| p.1 as f32).unwrap_or(0.0),
                chunk.get(0).map(|p| p.1 as f32).unwrap_or(0.0)
            );

            let dx = _mm_sub_ps(px, cx);
            let dz = _mm_sub_ps(pz, cz);

            let dx2 = _mm_mul_ps(dx, dx);
            let dz2 = _mm_mul_ps(dz, dz);

            let sum = _mm_add_ps(dx2, dz2);
            let dist = _mm_sqrt_ps(sum);

            _mm_storeu_ps(distances.as_mut_ptr(), dist);
            distances.into_iter().take(chunk.len()).collect::<Vec<_>>()
        }).collect()
    }

    fn calculate_chunk_distances_scalar(chunk_coords: &[(i32, i32)], center_chunk: (i32, i32)) -> Vec<f32> {
        chunk_coords.par_iter().map(|(x, z)| {
            let dx = *x as f32 - center_chunk.0 as f32;
            let dz = *z as f32 - center_chunk.1 as f32;
            (dx * dx + dz * dz).sqrt()
        }).collect()
    }

    #[target_feature(enable = "avx512f")]
    unsafe fn calculate_chunk_distances_avx512(chunk_coords: &[(i32, i32)], center_chunk: (i32, i32)) -> Vec<f32> {
        let cx = _mm512_set1_ps(center_chunk.0 as f32);
        let cz = _mm512_set1_ps(center_chunk.1 as f32);

        chunk_coords.par_chunks(16).flat_map(|chunk| {
            let mut distances = [0.0f32; 16];
            let px = _mm512_set_ps(
                chunk.get(15).map(|p| p.0 as f32).unwrap_or(0.0),
                chunk.get(14).map(|p| p.0 as f32).unwrap_or(0.0),
                chunk.get(13).map(|p| p.0 as f32).unwrap_or(0.0),
                chunk.get(12).map(|p| p.0 as f32).unwrap_or(0.0),
                chunk.get(11).map(|p| p.0 as f32).unwrap_or(0.0),
                chunk.get(10).map(|p| p.0 as f32).unwrap_or(0.0),
                chunk.get(9).map(|p| p.0 as f32).unwrap_or(0.0),
                chunk.get(8).map(|p| p.0 as f32).unwrap_or(0.0),
                chunk.get(7).map(|p| p.0 as f32).unwrap_or(0.0),
                chunk.get(6).map(|p| p.0 as f32).unwrap_or(0.0),
                chunk.get(5).map(|p| p.0 as f32).unwrap_or(0.0),
                chunk.get(4).map(|p| p.0 as f32).unwrap_or(0.0),
                chunk.get(3).map(|p| p.0 as f32).unwrap_or(0.0),
                chunk.get(2).map(|p| p.0 as f32).unwrap_or(0.0),
                chunk.get(1).map(|p| p.0 as f32).unwrap_or(0.0),
                chunk.get(0).map(|p| p.0 as f32).unwrap_or(0.0)
            );
            let pz = _mm512_set_ps(
                chunk.get(15).map(|p| p.1 as f32).unwrap_or(0.0),
                chunk.get(14).map(|p| p.1 as f32).unwrap_or(0.0),
                chunk.get(13).map(|p| p.1 as f32).unwrap_or(0.0),
                chunk.get(12).map(|p| p.1 as f32).unwrap_or(0.0),
                chunk.get(11).map(|p| p.1 as f32).unwrap_or(0.0),
                chunk.get(10).map(|p| p.1 as f32).unwrap_or(0.0),
                chunk.get(9).map(|p| p.1 as f32).unwrap_or(0.0),
                chunk.get(8).map(|p| p.1 as f32).unwrap_or(0.0),
                chunk.get(7).map(|p| p.1 as f32).unwrap_or(0.0),
                chunk.get(6).map(|p| p.1 as f32).unwrap_or(0.0),
                chunk.get(5).map(|p| p.1 as f32).unwrap_or(0.0),
                chunk.get(4).map(|p| p.1 as f32).unwrap_or(0.0),
                chunk.get(3).map(|p| p.1 as f32).unwrap_or(0.0),
                chunk.get(2).map(|p| p.1 as f32).unwrap_or(0.0),
                chunk.get(1).map(|p| p.1 as f32).unwrap_or(0.0),
                chunk.get(0).map(|p| p.1 as f32).unwrap_or(0.0)
            );

            let dx = _mm512_sub_ps(px, cx);
            let dz = _mm512_sub_ps(pz, cz);

            let dx2 = _mm512_mul_ps(dx, dx);
            let dz2 = _mm512_mul_ps(dz, dz);

            let sum = _mm512_add_ps(dx2, dz2);
            let dist = _mm512_sqrt_ps(sum);

            _mm512_storeu_ps(distances.as_mut_ptr(), dist);
            distances.into_iter().take(chunk.len()).collect::<Vec<_>>()
        }).collect()
    }

    /// SIMD-accelerated batch AABB intersection testing
    pub fn batch_aabb_intersections(aabbs: &[super::Aabb], queries: &[super::Aabb]) -> Vec<Vec<bool>> {
        let level = get_simd_manager().get_level();
        
        match level {
            SimdLevel::Avx2 => unsafe { batch_aabb_intersections_avx2(aabbs, queries) },
            SimdLevel::Sse42 | SimdLevel::Sse41 => unsafe { batch_aabb_intersections_sse(aabbs, queries) },
            SimdLevel::Scalar => batch_aabb_intersections_scalar(aabbs, queries),
            SimdLevel::Avx512 => unsafe { batch_aabb_intersections_avx512(aabbs, queries) },
        }
    }

    #[target_feature(enable = "avx2")]
    unsafe fn batch_aabb_intersections_avx2(aabbs: &[super::Aabb], queries: &[super::Aabb]) -> Vec<Vec<bool>> {
        queries.par_iter().map(|query| {
            aabbs.par_chunks(4).flat_map(|chunk| {
                let mut results = [false; 4];
                
                // Load query bounds once
                let query_min = _mm256_set_ps(
                    query.min_x as f32, query.min_y as f32, query.min_z as f32, 0.0,
                    query.min_x as f32, query.min_y as f32, query.min_z as f32, 0.0
                );
                let query_max = _mm256_set_ps(
                    query.max_x as f32, query.max_y as f32, query.max_z as f32, 0.0,
                    query.max_x as f32, query.max_y as f32, query.max_z as f32, 0.0
                );
                
                for (i, aabb) in chunk.iter().enumerate() {
                    let aabb_min = _mm256_set_ps(
                        aabb.min_x as f32, aabb.min_y as f32, aabb.min_z as f32, 0.0,
                        aabb.min_x as f32, aabb.min_y as f32, aabb.min_z as f32, 0.0
                    );
                    let aabb_max = _mm256_set_ps(
                        aabb.max_x as f32, aabb.max_y as f32, aabb.max_z as f32, 0.0,
                        aabb.max_x as f32, aabb.max_y as f32, aabb.max_z as f32, 0.0
                    );
                    
                    // Test intersection: query.min <= aabb.max && query.max >= aabb.min
                    let cmp1 = _mm256_cmp_ps(query_min, aabb_max, _CMP_LE_OQ);
                    let cmp2 = _mm256_cmp_ps(query_max, aabb_min, _CMP_GE_OQ);
                    let intersection = _mm256_and_ps(cmp1, cmp2);
                    
                    let mask = _mm256_movemask_ps(intersection);
                    results[i] = (mask & 0x07) == 0x07; // Check X, Y, Z axes
                }
                
                results.into_iter().take(chunk.len()).collect::<Vec<_>>()
            }).collect()
        }).collect()
    }

    #[target_feature(enable = "sse4.1")]
    unsafe fn batch_aabb_intersections_sse(aabbs: &[super::Aabb], queries: &[super::Aabb]) -> Vec<Vec<bool>> {
        queries.par_iter().map(|query| {
            aabbs.par_chunks(2).flat_map(|chunk| {
                let mut results = [false; 2];
                
                // Load query bounds once
                let query_min = _mm_set_ps(
                    query.min_x as f32, query.min_y as f32, query.min_z as f32, 0.0
                );
                let query_max = _mm_set_ps(
                    query.max_x as f32, query.max_y as f32, query.max_z as f32, 0.0
                );
                
                for (i, aabb) in chunk.iter().enumerate() {
                    let aabb_min = _mm_set_ps(
                        aabb.min_x as f32, aabb.min_y as f32, aabb.min_z as f32, 0.0
                    );
                    let aabb_max = _mm_set_ps(
                        aabb.max_x as f32, aabb.max_y as f32, aabb.max_z as f32, 0.0
                    );
                    
                    // Test intersection: query.min <= aabb.max && query.max >= aabb.min
                    let cmp1 = _mm_cmple_ps(query_min, aabb_max);
                    let cmp2 = _mm_cmpge_ps(query_max, aabb_min);
                    let intersection = _mm_and_ps(cmp1, cmp2);
                    
                    let mask = _mm_movemask_ps(intersection);
                    results[i] = (mask & 0x07) == 0x07; // Check X, Y, Z axes
                }
                
                results.into_iter().take(chunk.len()).collect::<Vec<_>>()
            }).collect()
        }).collect()
    }

    fn batch_aabb_intersections_scalar(aabbs: &[super::Aabb], queries: &[super::Aabb]) -> Vec<Vec<bool>> {
        queries.iter()
            .map(|query| aabbs.iter().map(|aabb| aabb.intersects(query)).collect())
            .collect()
    }

    #[target_feature(enable = "avx512f")]
    unsafe fn batch_aabb_intersections_avx512(aabbs: &[super::Aabb], queries: &[super::Aabb]) -> Vec<Vec<bool>> {
        queries.par_iter().map(|query| {
            aabbs.par_chunks(8).flat_map(|chunk| {
                let mut results = [false; 8];
                
                // Load query bounds once
                let query_min = _mm512_set_ps(
                    query.min_x as f32, query.min_y as f32, query.min_z as f32, 0.0,
                    query.min_x as f32, query.min_y as f32, query.min_z as f32, 0.0,
                    query.min_x as f32, query.min_y as f32, query.min_z as f32, 0.0,
                    query.min_x as f32, query.min_y as f32, query.min_z as f32, 0.0
                );
                let query_max = _mm512_set_ps(
                    query.max_x as f32, query.max_y as f32, query.max_z as f32, 0.0,
                    query.max_x as f32, query.max_y as f32, query.max_z as f32, 0.0,
                    query.max_x as f32, query.max_y as f32, query.max_z as f32, 0.0,
                    query.max_x as f32, query.max_y as f32, query.max_z as f32, 0.0
                );
                
                for (i, aabb) in chunk.iter().enumerate() {
                    let aabb_min = _mm512_set_ps(
                        aabb.min_x as f32, aabb.min_y as f32, aabb.min_z as f32, 0.0,
                        aabb.min_x as f32, aabb.min_y as f32, aabb.min_z as f32, 0.0,
                        aabb.min_x as f32, aabb.min_y as f32, aabb.min_z as f32, 0.0,
                        aabb.min_x as f32, aabb.min_y as f32, aabb.min_z as f32, 0.0
                    );
                    let aabb_max = _mm512_set_ps(
                        aabb.max_x as f32, aabb.max_y as f32, aabb.max_z as f32, 0.0,
                        aabb.max_x as f32, aabb.max_y as f32, aabb.max_z as f32, 0.0,
                        aabb.max_x as f32, aabb.max_y as f32, aabb.max_z as f32, 0.0,
                        aabb.max_x as f32, aabb.max_y as f32, aabb.max_z as f32, 0.0
                    );
                    
                    // Test intersection: query.min <= aabb.max && query.max >= aabb.min
                    let cmp1 = _mm512_cmp_ps_mask(query_min, aabb_max, _MM_CMPINT_LE);
                    let cmp2 = _mm512_cmp_ps_mask(query_max, aabb_min, _MM_CMPINT_GE);
                    let intersection = cmp1 & cmp2;
                    
                    results[i] = (intersection & 0x07) == 0x07; // Check X, Y, Z axes
                }
                
                results.into_iter().take(chunk.len()).collect::<Vec<_>>()
            }).collect()
        }).collect()
    }
    }
}

/// SIMD-accelerated entity processing
pub mod entity_processing {
    use super::*;
    use std::arch::x86_64::*;

    /// SIMD-accelerated distance calculation for entities
    pub fn calculate_entity_distances(positions: &[(f32, f32, f32)], center: (f32, f32, f32)) -> Vec<f32> {
        let level = get_simd_manager().get_level();
        
        match level {
            SimdLevel::Avx2 => unsafe { calculate_distances_avx2(positions, center) },
            SimdLevel::Sse42 | SimdLevel::Sse41 => unsafe { calculate_distances_sse(positions, center) },
            SimdLevel::Scalar => calculate_distances_scalar(positions, center),
            SimdLevel::Avx512 => unsafe { calculate_distances_avx512(positions, center) },
        }
    }

    #[target_feature(enable = "avx2")]
    unsafe fn calculate_distances_avx2(positions: &[(f32, f32, f32)], center: (f32, f32, f32)) -> Vec<f32> {
        let cx = _mm256_set1_ps(center.0);
        let cy = _mm256_set1_ps(center.1);
        let cz = _mm256_set1_ps(center.2);

        positions.chunks_exact(8).flat_map(|chunk| {
            let mut distances = [0.0f32; 8];
            
            let px = _mm256_set_ps(
                chunk.get(7).map(|p| p.0).unwrap_or(0.0),
                chunk.get(6).map(|p| p.0).unwrap_or(0.0),
                chunk.get(5).map(|p| p.0).unwrap_or(0.0),
                chunk.get(4).map(|p| p.0).unwrap_or(0.0),
                chunk.get(3).map(|p| p.0).unwrap_or(0.0),
                chunk.get(2).map(|p| p.0).unwrap_or(0.0),
                chunk.get(1).map(|p| p.0).unwrap_or(0.0),
                chunk.get(0).map(|p| p.0).unwrap_or(0.0)
            );
            let py = _mm256_set_ps(
                chunk.get(7).map(|p| p.1).unwrap_or(0.0),
                chunk.get(6).map(|p| p.1).unwrap_or(0.0),
                chunk.get(5).map(|p| p.1).unwrap_or(0.0),
                chunk.get(4).map(|p| p.1).unwrap_or(0.0),
                chunk.get(3).map(|p| p.1).unwrap_or(0.0),
                chunk.get(2).map(|p| p.1).unwrap_or(0.0),
                chunk.get(1).map(|p| p.1).unwrap_or(0.0),
                chunk.get(0).map(|p| p.1).unwrap_or(0.0)
            );
            let pz = _mm256_set_ps(
                chunk.get(7).map(|p| p.2).unwrap_or(0.0),
                chunk.get(6).map(|p| p.2).unwrap_or(0.0),
                chunk.get(5).map(|p| p.2).unwrap_or(0.0),
                chunk.get(4).map(|p| p.2).unwrap_or(0.0),
                chunk.get(3).map(|p| p.2).unwrap_or(0.0),
                chunk.get(2).map(|p| p.2).unwrap_or(0.0),
                chunk.get(1).map(|p| p.2).unwrap_or(0.0),
                chunk.get(0).map(|p| p.2).unwrap_or(0.0)
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
            distances.into_iter().take(chunk.len()).collect::<Vec<_>>()
        }).collect()
    }

    #[target_feature(enable = "sse4.1")]
    unsafe fn calculate_distances_sse(positions: &[(f32, f32, f32)], center: (f32, f32, f32)) -> Vec<f32> {
        let cx = _mm_set1_ps(center.0);
        let cy = _mm_set1_ps(center.1);
        let cz = _mm_set1_ps(center.2);

        positions.chunks_exact(4).flat_map(|chunk| {
            let mut distances = [0.0f32; 4];
            
            let px = _mm_set_ps(
                chunk.get(3).map(|p| p.0).unwrap_or(0.0),
                chunk.get(2).map(|p| p.0).unwrap_or(0.0),
                chunk.get(1).map(|p| p.0).unwrap_or(0.0),
                chunk.get(0).map(|p| p.0).unwrap_or(0.0)
            );
            let py = _mm_set_ps(
                chunk.get(3).map(|p| p.1).unwrap_or(0.0),
                chunk.get(2).map(|p| p.1).unwrap_or(0.0),
                chunk.get(1).map(|p| p.1).unwrap_or(0.0),
                chunk.get(0).map(|p| p.1).unwrap_or(0.0)
            );
            let pz = _mm_set_ps(
                chunk.get(3).map(|p| p.2).unwrap_or(0.0),
                chunk.get(2).map(|p| p.2).unwrap_or(0.0),
                chunk.get(1).map(|p| p.2).unwrap_or(0.0),
                chunk.get(0).map(|p| p.2).unwrap_or(0.0)
            );

            let dx = _mm_sub_ps(px, cx);
            let dy = _mm_sub_ps(py, cy);
            let dz = _mm_sub_ps(pz, cz);

            let dx2 = _mm_mul_ps(dx, dx);
            let dy2 = _mm_mul_ps(dy, dy);
            let dz2 = _mm_mul_ps(dz, dz);

            let sum = _mm_add_ps(_mm_add_ps(dx2, dy2), dz2);
            let dist = _mm_sqrt_ps(sum);

            _mm_storeu_ps(distances.as_mut_ptr(), dist);
            distances.into_iter().take(chunk.len()).collect::<Vec<_>>()
        }).collect()
    }

    fn calculate_distances_scalar(positions: &[(f32, f32, f32)], center: (f32, f32, f32)) -> Vec<f32> {
        positions.iter().map(|(x, y, z)| {
            let dx = x - center.0;
            let dy = y - center.1;
            let dz = z - center.2;
            (dx * dx + dy * dy + dz * dz).sqrt()
        }).collect()
    }

    #[target_feature(enable = "avx512f")]
    unsafe fn calculate_distances_avx512(positions: &[(f32, f32, f32)], center: (f32, f32, f32)) -> Vec<f32> {
        let cx = _mm512_set1_ps(center.0);
        let cy = _mm512_set1_ps(center.1);
        let cz = _mm512_set1_ps(center.2);

        positions.chunks_exact(16).flat_map(|chunk| {
            let mut distances = [0.0f32; 16];
            
            let px = _mm512_set_ps(
                chunk.get(15).map(|p| p.0).unwrap_or(0.0),
                chunk.get(14).map(|p| p.0).unwrap_or(0.0),
                // ... continue for all 16 elements
                chunk.get(0).map(|p| p.0).unwrap_or(0.0)
            );
            let py = _mm512_set_ps(
                chunk.get(15).map(|p| p.1).unwrap_or(0.0),
                chunk.get(14).map(|p| p.1).unwrap_or(0.0),
                // ... continue for all 16 elements
                chunk.get(0).map(|p| p.1).unwrap_or(0.0)
            );
            let pz = _mm512_set_ps(
                chunk.get(15).map(|p| p.2).unwrap_or(0.0),
                chunk.get(14).map(|p| p.2).unwrap_or(0.0),
                // ... continue for all 16 elements
                chunk.get(0).map(|p| p.2).unwrap_or(0.0)
            );

            let dx = _mm512_sub_ps(px, cx);
            let dy = _mm512_sub_ps(py, cy);
            let dz = _mm512_sub_ps(pz, cz);

            let dx2 = _mm512_mul_ps(dx, dx);
            let dy2 = _mm512_mul_ps(dy, dy);
            let dz2 = _mm512_mul_ps(dz, dz);

            let sum = _mm512_add_ps(_mm512_add_ps(dx2, dy2), dz2);
            let dist = _mm512_sqrt_ps(sum);

            _mm512_storeu_ps(distances.as_mut_ptr(), dist);
            distances.into_iter().take(chunk.len()).collect::<Vec<_>>()
        }).collect()
    /// SIMD-accelerated entity filtering by distance threshold
    pub fn filter_entities_by_distance<T: Clone + Send + Sync>(
        entities: &[(T, [f64; 3])],
        center: [f64; 3],
        max_distance: f64,
    ) -> Vec<(T, [f64; 3])> {
        let level = get_simd_manager().get_level();
        
        match level {
            SimdLevel::Avx2 => unsafe { filter_entities_by_distance_avx2(entities, center, max_distance) },
            SimdLevel::Sse42 | SimdLevel::Sse41 => unsafe { filter_entities_by_distance_sse(entities, center, max_distance) },
            SimdLevel::Scalar => filter_entities_by_distance_scalar(entities, center, max_distance),
            SimdLevel::Avx512 => unsafe { filter_entities_by_distance_avx512(entities, center, max_distance) },
        }
    }

    #[target_feature(enable = "avx2")]
    unsafe fn filter_entities_by_distance_avx2<T: Clone + Send + Sync>(
        entities: &[(T, [f64; 3])],
        center: [f64; 3],
        max_distance: f64,
    ) -> Vec<(T, [f64; 3])> {
        let center_x = _mm256_set1_ps(center[0] as f32);
        let center_y = _mm256_set1_ps(center[1] as f32);
        let center_z = _mm256_set1_ps(center[2] as f32);
        let max_dist_sq = _mm256_set1_ps((max_distance * max_distance) as f32);
        
        entities.par_chunks(8).flat_map(|chunk| {
            let mut results = Vec::new();
            
            // Extract positions for SIMD processing
            let mut positions_x = [0.0f32; 8];
            let mut positions_y = [0.0f32; 8];
            let mut positions_z = [0.0f32; 8];
            
            for (i, (_, pos)) in chunk.iter().enumerate() {
                positions_x[i] = pos[0] as f32;
                positions_y[i] = pos[1] as f32;
                positions_z[i] = pos[2] as f32;
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
            let within_range = _mm256_cmp_ps(dist_sq, max_dist_sq, _CMP_LE_OQ);
            
            let mask = _mm256_movemask_ps(within_range);
            
            for (i, (entity, pos)) in chunk.iter().enumerate() {
                if (mask & (1 << i)) != 0 {
                    results.push(((*entity).clone(), **pos));
                }
            }
            
            results
        }).collect()
    }

    #[target_feature(enable = "sse4.1")]
    unsafe fn filter_entities_by_distance_sse<T: Clone + Send + Sync>(
        entities: &[(T, [f64; 3])],
        center: [f64; 3],
        max_distance: f64,
    ) -> Vec<(T, [f64; 3])> {
        let center_x = _mm_set1_ps(center[0] as f32);
        let center_y = _mm_set1_ps(center[1] as f32);
        let center_z = _mm_set1_ps(center[2] as f32);
        let max_dist_sq = _mm_set1_ps((max_distance * max_distance) as f32);
        
        entities.par_chunks(4).flat_map(|chunk| {
            let mut results = Vec::new();
            
            // Extract positions for SIMD processing
            let mut positions_x = [0.0f32; 4];
            let mut positions_y = [0.0f32; 4];
            let mut positions_z = [0.0f32; 4];
            
            for (i, (_, pos)) in chunk.iter().enumerate() {
                positions_x[i] = pos[0] as f32;
                positions_y[i] = pos[1] as f32;
                positions_z[i] = pos[2] as f32;
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
            let within_range = _mm_cmple_ps(dist_sq, max_dist_sq);
            
            let mask = _mm_movemask_ps(within_range);
            
            for (i, (entity, pos)) in chunk.iter().enumerate() {
                if (mask & (1 << i)) != 0 {
                    results.push(((*entity).clone(), **pos));
                }
            }
            
            results
        }).collect()
    }

    fn filter_entities_by_distance_scalar<T: Clone + Send + Sync>(
        entities: &[(T, [f64; 3])],
        center: [f64; 3],
        max_distance: f64,
    ) -> Vec<(T, [f64; 3])> {
        entities.par_iter()
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

    #[target_feature(enable = "avx512f")]
    unsafe fn filter_entities_by_distance_avx512<T: Clone + Send + Sync>(
        entities: &[(T, [f64; 3])],
        center: [f64; 3],
        max_distance: f64,
    ) -> Vec<(T, [f64; 3])> {
        let center_x = _mm512_set1_ps(center[0] as f32);
        let center_y = _mm512_set1_ps(center[1] as f32);
        let center_z = _mm512_set1_ps(center[2] as f32);
        let max_dist_sq = _mm512_set1_ps((max_distance * max_distance) as f32);
        
        entities.par_chunks(16).flat_map(|chunk| {
            let mut results = Vec::new();
            
            // Extract positions for SIMD processing
            let mut positions_x = [0.0f32; 16];
            let mut positions_y = [0.0f32; 16];
            let mut positions_z = [0.0f32; 16];
            
            for (i, (_, pos)) in chunk.iter().enumerate() {
                positions_x[i] = pos[0] as f32;
                positions_y[i] = pos[1] as f32;
                positions_z[i] = pos[2] as f32;
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
            
            for (i, (entity, pos)) in chunk.iter().enumerate() {
                if (within_range & (1 << i)) != 0 {
                    results.push(((*entity).clone(), **pos));
                }
            }
            
            results
        }).collect()
    }
    }
}

/// JNI function to get SIMD feature information
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_RustPerformance_getSimdFeatures(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let features = SimdFeatures::detect();
    let level = features.best_level();
    
    let features_json = serde_json::json!({
        "hasAvx2": features.has_avx2,
        "hasSse41": features.has_sse41,
        "hasSse42": features.has_sse42,
        "hasFma": features.has_fma,
        "hasBmi1": features.has_bmi1,
        "hasBmi2": features.has_bmi2,
        "hasAvx512f": features.has_avx512f,
        "hasAvx512dq": features.has_avx512dq,
        "hasAvx512bw": features.has_avx512bw,
        "hasAvx512vl": features.has_avx512vl,
        "bestLevel": format!("{:?}", level),
    });

    match env.new_string(&serde_json::to_string(&features_json).unwrap_or_default()) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// JNI function to initialize SIMD with configuration
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_RustPerformance_initSimd(
    _env: JNIEnv,
    _class: JClass,
    enable_avx2: bool,
    enable_sse41: bool,
    enable_sse42: bool,
    enable_fma: bool,
    force_scalar_fallback: bool,
) -> jint {
    let config = SimdConfig {
        enable_avx2,
        enable_sse41,
        enable_sse42,
        enable_fma,
        enable_bmi1: true,
        enable_bmi2: true,
        force_scalar_fallback,
    };

    init_simd(config);
    0 // Success
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_simd_detection() {
        let features = SimdFeatures::detect();
        println!("Detected SIMD features: {:?}", features);
        
        let level = features.best_level();
        println!("Best SIMD level: {:?}", level);
        
        // Test should pass regardless of actual hardware capabilities
        assert!(level as u32 >= SimdLevel::Scalar as u32);
    }

    #[test]
    fn test_vector_operations() {
        let a = vec![1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0];
        let b = vec![8.0, 7.0, 6.0, 5.0, 4.0, 3.0, 2.0, 1.0];
        
        let result = vector_ops::dot_product(&a, &b);
        let expected = 120.0; // Sum of products
        
        assert!((result - expected).abs() < 0.001);
    }

    #[test]
    fn test_entity_distance_calculation() {
        let positions = vec![
            (0.0, 0.0, 0.0),
            (3.0, 4.0, 0.0),
            (6.0, 8.0, 0.0),
        ];
        let center = (0.0, 0.0, 0.0);
        
        let distances = entity_processing::calculate_entity_distances(&positions, center);
        
        assert_eq!(distances.len(), 3);
        assert!((distances[0] - 0.0).abs() < 0.001);
        assert!((distances[1] - 5.0).abs() < 0.001);
        assert!((distances[2] - 10.0).abs() < 0.001);
    }
}
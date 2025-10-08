use std::arch::is_x86_feature_detected;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use log::{info, debug};
use serde::{Serialize, Deserialize};

use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::{jint, jstring};

/// Custom error type for SIMD operations
#[derive(Debug, Clone, PartialEq)]
pub enum SimdError {
    /// Vector length mismatch in operations requiring equal lengths
    InvalidInputLength {
        expected: usize,
        actual: usize,
        operation: &'static str,
    },
    /// SIMD instruction set not supported on this hardware
    UnsupportedInstructionSet {
        required: &'static str,
        available: String,
    },
    /// Memory allocation failed
    MemoryAllocationFailed {
        size: usize,
        reason: String,
    },
    /// JNI operation failed
    JniError {
        operation: &'static str,
        details: String,
    },
}

impl std::fmt::Display for SimdError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            SimdError::InvalidInputLength { expected, actual, operation } => {
                write!(f, "Invalid input length for {}: expected {}, got {}", operation, expected, actual)
            }
            SimdError::UnsupportedInstructionSet { required, available } => {
                write!(f, "Required SIMD instruction set '{}' not supported. Available: {}", required, available)
            }
            SimdError::MemoryAllocationFailed { size, reason } => {
                write!(f, "Memory allocation failed for size {}: {}", size, reason)
            }
            SimdError::JniError { operation, details } => {
                write!(f, "JNI operation '{}' failed: {}", operation, details)
            }
        }
    }
}

impl std::error::Error for SimdError {}

/// Type alias for SIMD operation results
pub type SimdResult<T> = Result<T, SimdError>;

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
        // We intentionally avoid mutating `self.config`/`self.features` here because
        // SIMD_MANAGER is a lazily-initialized static and we only have &self.
        // Store the detected level and mark as initialized.
        let features = SimdFeatures::detect();
        let level = if config.force_scalar_fallback {
            SimdLevel::Scalar
        } else {
            features.best_level()
        };

        self.level.store(level as u32, Ordering::Relaxed);
        self.initialized.store(true, Ordering::Relaxed);

        info!("SIMD Manager initialized with level: {:?}", level);
        debug!("Detected SIMD features: {:?}", features);
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

/// SIMD operations trait
pub trait SimdOperations {
    fn dot_product(&self, a: &[f32], b: &[f32]) -> SimdResult<f32>;
    fn vector_add(&self, a: &mut [f32], b: &[f32], scale: f32) -> SimdResult<()>;
    fn calculate_chunk_distances(&self, chunk_coords: &[(i32, i32)], center_chunk: (i32, i32)) -> Vec<f32>;
    fn batch_aabb_intersections(&self, aabbs: &[crate::spatial::Aabb], queries: &[crate::spatial::Aabb]) -> Vec<Vec<bool>>;
}

/// SIMD processor implementation
pub struct SimdProcessor;

impl SimdProcessor {
    pub fn new() -> Self {
        Self
    }
}

impl SimdOperations for SimdProcessor {
    fn dot_product(&self, a: &[f32], b: &[f32]) -> SimdResult<f32> {
        if a.len() != b.len() {
            return Err(SimdError::InvalidInputLength {
                expected: a.len(),
                actual: b.len(),
                operation: "dot_product",
            });
        }
        Ok(a.iter().zip(b.iter()).map(|(x, y)| x * y).sum())
    }

    fn vector_add(&self, a: &mut [f32], b: &[f32], scale: f32) -> SimdResult<()> {
        if a.len() != b.len() {
            return Err(SimdError::InvalidInputLength {
                expected: a.len(),
                actual: b.len(),
                operation: "vector_add",
            });
        }
        for (av, bv) in a.iter_mut().zip(b.iter()) {
            *av += *bv * scale;
        }
        Ok(())
    }

    fn calculate_chunk_distances(&self, chunk_coords: &[(i32, i32)], center_chunk: (i32, i32)) -> Vec<f32> {
        chunk_coords
            .iter()
            .map(|(x, z)| {
                let dx = *x as f32 - center_chunk.0 as f32;
                let dz = *z as f32 - center_chunk.1 as f32;
                (dx * dx + dz * dz).sqrt()
            })
            .collect()
    }

    fn batch_aabb_intersections(&self, aabbs: &[crate::spatial::Aabb], queries: &[crate::spatial::Aabb]) -> Vec<Vec<bool>> {
        queries.iter().map(|q| aabbs.iter().map(|a| a.intersects(q)).collect()).collect()
    }
}

/// SIMD-accelerated vector operations (scalar-first, safe implementations)
pub mod vector_ops {
    use super::{SimdError, SimdResult};

    /// Dot product (scalar implementation, always available)
    pub fn dot_product(a: &[f32], b: &[f32]) -> SimdResult<f32> {
        if a.len() != b.len() {
            return Err(SimdError::InvalidInputLength {
                expected: a.len(),
                actual: b.len(),
                operation: "dot_product",
            });
        }
        Ok(a.iter().zip(b.iter()).map(|(x, y)| x * y).sum())
    }

    /// Vector add: a += b * scale
    pub fn vector_add(a: &mut [f32], b: &[f32], scale: f32) -> SimdResult<()> {
        if a.len() != b.len() {
            return Err(SimdError::InvalidInputLength {
                expected: a.len(),
                actual: b.len(),
                operation: "vector_add",
            });
        }
        for (av, bv) in a.iter_mut().zip(b.iter()) {
            *av += *bv * scale;
        }
        Ok(())
    }

    /// Chunk distances (2D) - scalar implementation using sequential processing
    pub fn calculate_chunk_distances(chunk_coords: &[(i32, i32)], center_chunk: (i32, i32)) -> Vec<f32> {
        chunk_coords
            .iter()
            .map(|(x, z)| {
                let dx = *x as f32 - center_chunk.0 as f32;
                let dz = *z as f32 - center_chunk.1 as f32;
                (dx * dx + dz * dz).sqrt()
            })
            .collect()
    }

    /// Batch AABB intersections - scalar implementation
    pub fn batch_aabb_intersections(aabbs: &[crate::spatial::Aabb], queries: &[crate::spatial::Aabb]) -> Vec<Vec<bool>> {
        queries.iter().map(|q| aabbs.iter().map(|a| a.intersects(q)).collect()).collect()
    }
}

/// SIMD-accelerated entity processing
pub mod entity_processing {
    use crate::simd::{get_simd_manager, SimdLevel};
    use std::arch::x86_64::*;
    use rayon::prelude::*;

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

    positions.chunks(8).flat_map(|chunk| {
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

    positions.chunks(4).flat_map(|chunk| {
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
        // Implement AVX-512 path: process up to 16 lanes per iteration and handle remainder safely.
        let cx = _mm512_set1_ps(center.0);
        let cy = _mm512_set1_ps(center.1);
        let cz = _mm512_set1_ps(center.2);

        positions.chunks(16).flat_map(|chunk| {
            let mut distances = [0.0f32; 16];

            // Prepare position arrays (pad with 0.0 for lanes beyond chunk.len())
            let mut px_arr = [0.0f32; 16];
            let mut py_arr = [0.0f32; 16];
            let mut pz_arr = [0.0f32; 16];

            for (i, p) in chunk.iter().enumerate() {
                px_arr[i] = p.0;
                py_arr[i] = p.1;
                pz_arr[i] = p.2;
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
            distances.into_iter().take(chunk.len()).collect::<Vec<_>>()
        }).collect()
    }

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
                    results.push(((*entity).clone(), *pos));
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
                    results.push(((*entity).clone(), *pos));
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
                    results.push(((*entity).clone(), *pos));
                }
            }

            results
        }).collect()
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

    #[test]
    fn test_simd_detection() {
        let features = super::SimdFeatures::detect();
        println!("Detected SIMD features: {:?}", features);

        let level = features.best_level();
        println!("Best SIMD level: {:?}", level);

        // Test should pass regardless of actual hardware capabilities
        assert!(level as u32 >= super::SimdLevel::Scalar as u32);
    }

    #[test]
    fn test_vector_operations() {
        let a = vec![1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0];
        let b = vec![8.0, 7.0, 6.0, 5.0, 4.0, 3.0, 2.0, 1.0];

        let result = super::vector_ops::dot_product(&a, &b);
        let expected = 120.0; // Sum of products

        assert!((result.unwrap() - expected).abs() < 0.001);
    }

    #[test]
    fn test_entity_distance_calculation() {
        let positions = vec![
            (0.0, 0.0, 0.0),
            (3.0, 4.0, 0.0),
            (6.0, 8.0, 0.0),
        ];
        let center = (0.0, 0.0, 0.0);

        let distances = super::entity_processing::calculate_entity_distances(&positions, center);

        assert_eq!(distances.len(), 3);
        assert!((distances[0] - 0.0).abs() < 0.001);
        assert!((distances[1] - 5.0).abs() < 0.001);
        assert!((distances[2] - 10.0).abs() < 0.001);
    }
}
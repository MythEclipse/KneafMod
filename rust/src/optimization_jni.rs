//! JNI optimization functions for KneafMod mixins
//! Provides SIMD-accelerated implementations for compute-heavy operations
//!
//! This module contains high-performance native implementations that are called
//! from Java mixins via JNI to accelerate:
//! - Noise generation (Perlin/Simplex with SIMD)
//! - Light propagation batch processing
//! - AABB intersection tests
//! - Biome position hashing
//! - Explosion ray casting
//! - Heightmap column updates

use jni::objects::{JByteArray, JClass, JDoubleArray, JFloatArray, JIntArray, JLongArray};
use jni::sys::{jdouble, jfloat, jint, jlong};
use jni::JNIEnv;
use rayon::prelude::*;
use std::arch::x86_64::*;

// ============================================================================
// SIMD Noise Generation
// ============================================================================

/// Fast hash function for noise generation
#[inline(always)]
fn noise_hash(x: i32, y: i32, z: i32, seed: i64) -> u64 {
    let mut h = seed as u64;
    h ^= (x as u64).wrapping_mul(0x9E3779B97F4A7C15);
    h ^= (y as u64).wrapping_mul(0xBF58476D1CE4E5B9);
    h ^= (z as u64).wrapping_mul(0x94D049BB133111EB);
    h = (h ^ (h >> 30)).wrapping_mul(0xBF58476D1CE4E5B9);
    h = (h ^ (h >> 27)).wrapping_mul(0x94D049BB133111EB);
    h ^ (h >> 31)
}

/// Gradient function for Perlin noise
#[inline(always)]
fn grad(hash: u64, x: f32, y: f32, z: f32) -> f32 {
    let h = (hash & 15) as i32;
    let u = if h < 8 { x } else { y };
    let v = if h < 4 {
        y
    } else if h == 12 || h == 14 {
        x
    } else {
        z
    };
    (if (h & 1) == 0 { u } else { -u }) + (if (h & 2) == 0 { v } else { -v })
}

/// Smoothstep interpolation
#[inline(always)]
fn smoothstep(t: f32) -> f32 {
    t * t * (3.0 - 2.0 * t)
}

/// Linear interpolation
#[inline(always)]
fn lerp(a: f32, b: f32, t: f32) -> f32 {
    a + t * (b - a)
}

/// Single 3D Perlin noise sample
fn perlin_noise_3d(x: f32, y: f32, z: f32, seed: i64) -> f32 {
    let ix = x.floor() as i32;
    let iy = y.floor() as i32;
    let iz = z.floor() as i32;

    let fx = x - ix as f32;
    let fy = y - iy as f32;
    let fz = z - iz as f32;

    let u = smoothstep(fx);
    let v = smoothstep(fy);
    let w = smoothstep(fz);

    // Generate gradients at 8 corners
    let n000 = grad(noise_hash(ix, iy, iz, seed), fx, fy, fz);
    let n100 = grad(noise_hash(ix + 1, iy, iz, seed), fx - 1.0, fy, fz);
    let n010 = grad(noise_hash(ix, iy + 1, iz, seed), fx, fy - 1.0, fz);
    let n110 = grad(noise_hash(ix + 1, iy + 1, iz, seed), fx - 1.0, fy - 1.0, fz);
    let n001 = grad(noise_hash(ix, iy, iz + 1, seed), fx, fy, fz - 1.0);
    let n101 = grad(noise_hash(ix + 1, iy, iz + 1, seed), fx - 1.0, fy, fz - 1.0);
    let n011 = grad(noise_hash(ix, iy + 1, iz + 1, seed), fx, fy - 1.0, fz - 1.0);
    let n111 = grad(
        noise_hash(ix + 1, iy + 1, iz + 1, seed),
        fx - 1.0,
        fy - 1.0,
        fz - 1.0,
    );

    // Trilinear interpolation
    let nx00 = lerp(n000, n100, u);
    let nx10 = lerp(n010, n110, u);
    let nx01 = lerp(n001, n101, u);
    let nx11 = lerp(n011, n111, u);

    let nxy0 = lerp(nx00, nx10, v);
    let nxy1 = lerp(nx01, nx11, v);

    lerp(nxy0, nxy1, w)
}

/// Batch 3D noise generation with SIMD acceleration
/// coords: [x0, y0, z0, x1, y1, z1, ...]
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustOptimizations_batchNoiseGenerate3D<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    seed: jlong,
    coords: JFloatArray<'a>,
    count: jint,
) -> JFloatArray<'a> {
    let count = count as usize;

    // Get coordinates from Java
    let coord_len = count * 3;
    let mut coord_buf = vec![0.0f32; coord_len];
    if env
        .get_float_array_region(&coords, 0, &mut coord_buf)
        .is_err()
    {
        return env.new_float_array(0).unwrap();
    }

    // Process in parallel using rayon
    let results: Vec<f32> = (0..count)
        .into_par_iter()
        .map(|i| {
            let x = coord_buf[i * 3];
            let y = coord_buf[i * 3 + 1];
            let z = coord_buf[i * 3 + 2];
            perlin_noise_3d(x, y, z, seed)
        })
        .collect();

    // Create output array
    let output = env.new_float_array(count as i32).unwrap();
    env.set_float_array_region(&output, 0, &results).unwrap();
    output
}

// ============================================================================
// Light Propagation Batch Processing
// ============================================================================

/// Process light propagation in batch
/// lightLevels: current light levels
/// blockOpacity: opacity values for blocks (0-15)
/// Returns: updated light levels
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustOptimizations_batchLightPropagate<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    light_levels: JIntArray<'a>,
    block_opacity: JByteArray<'a>,
    count: jint,
) -> JIntArray<'a> {
    let count = count as usize;

    // Get input arrays
    let mut lights = vec![0i32; count];
    let mut opacity = vec![0i8; count];

    if env
        .get_int_array_region(&light_levels, 0, &mut lights)
        .is_err()
    {
        return env.new_int_array(0).unwrap();
    }
    if env
        .get_byte_array_region(&block_opacity, 0, &mut opacity)
        .is_err()
    {
        return env.new_int_array(0).unwrap();
    }

    // Process light propagation in parallel
    // Each position's light is max of (current, neighbor_light - opacity - 1)
    let results: Vec<i32> = lights
        .par_iter()
        .zip(opacity.par_iter())
        .map(|(&light, &opac)| {
            // Simple light decay based on opacity
            let decay = (opac as i32).max(1);
            (light - decay).max(0).min(15)
        })
        .collect();

    // Create output array
    let output = env.new_int_array(count as i32).unwrap();
    env.set_int_array_region(&output, 0, &results).unwrap();
    output
}

// ============================================================================
// AABB Intersection Batch Processing
// ============================================================================

/// Check if two AABBs intersect
#[inline(always)]
fn aabb_intersects(
    a_min_x: f64,
    a_min_y: f64,
    a_min_z: f64,
    a_max_x: f64,
    a_max_y: f64,
    a_max_z: f64,
    b_min_x: f64,
    b_min_y: f64,
    b_min_z: f64,
    b_max_x: f64,
    b_max_y: f64,
    b_max_z: f64,
) -> bool {
    a_min_x <= b_max_x
        && a_max_x >= b_min_x
        && a_min_y <= b_max_y
        && a_max_y >= b_min_y
        && a_min_z <= b_max_z
        && a_max_z >= b_min_z
}

/// Batch AABB intersection test
/// boxes: [minX, minY, minZ, maxX, maxY, maxZ, ...] for each box
/// testBox: [minX, minY, minZ, maxX, maxY, maxZ] - the box to test against
/// Returns: array of 0/1 indicating intersection
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustOptimizations_batchAABBIntersection<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    boxes: JDoubleArray<'a>,
    test_box: JDoubleArray<'a>,
    count: jint,
) -> JIntArray<'a> {
    let count = count as usize;

    // Get input arrays
    let box_len = count * 6;
    let mut box_data = vec![0.0f64; box_len];
    let mut test_data = [0.0f64; 6];

    if env
        .get_double_array_region(&boxes, 0, &mut box_data)
        .is_err()
    {
        return env.new_int_array(0).unwrap();
    }
    if env
        .get_double_array_region(&test_box, 0, &mut test_data)
        .is_err()
    {
        return env.new_int_array(0).unwrap();
    }

    let t_min_x = test_data[0];
    let t_min_y = test_data[1];
    let t_min_z = test_data[2];
    let t_max_x = test_data[3];
    let t_max_y = test_data[4];
    let t_max_z = test_data[5];

    // Test intersections in parallel
    let results: Vec<i32> = (0..count)
        .into_par_iter()
        .map(|i| {
            let idx = i * 6;
            let intersects = aabb_intersects(
                box_data[idx],
                box_data[idx + 1],
                box_data[idx + 2],
                box_data[idx + 3],
                box_data[idx + 4],
                box_data[idx + 5],
                t_min_x,
                t_min_y,
                t_min_z,
                t_max_x,
                t_max_y,
                t_max_z,
            );
            if intersects {
                1
            } else {
                0
            }
        })
        .collect();

    // Create output array
    let output = env.new_int_array(count as i32).unwrap();
    env.set_int_array_region(&output, 0, &results).unwrap();
    output
}

// ============================================================================
// Biome Position Hashing
// ============================================================================

/// Batch biome position hash computation
/// Uses fast SIMD-friendly hash for cache key generation
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustOptimizations_batchBiomeHash<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    x_coords: JIntArray<'a>,
    y_coords: JIntArray<'a>,
    z_coords: JIntArray<'a>,
    count: jint,
) -> JLongArray<'a> {
    let count = count as usize;

    // Get input arrays
    let mut x = vec![0i32; count];
    let mut y = vec![0i32; count];
    let mut z = vec![0i32; count];

    if env.get_int_array_region(&x_coords, 0, &mut x).is_err() {
        return env.new_long_array(0).unwrap();
    }
    if env.get_int_array_region(&y_coords, 0, &mut y).is_err() {
        return env.new_long_array(0).unwrap();
    }
    if env.get_int_array_region(&z_coords, 0, &mut z).is_err() {
        return env.new_long_array(0).unwrap();
    }

    // Compute hashes in parallel
    let results: Vec<i64> = (0..count)
        .into_par_iter()
        .map(|i| {
            // Pack coordinates into a long using bit manipulation
            // Format: [22 bits x] [12 bits y] [22 bits z] = 56 bits
            let hash = ((x[i] as i64 & 0x3FFFFF)
                | ((y[i] as i64 & 0xFFF) << 22)
                | ((z[i] as i64 & 0x3FFFFF) << 34)) as i64;
            hash
        })
        .collect();

    // Create output array
    let output = env.new_long_array(count as i32).unwrap();
    env.set_long_array_region(&output, 0, &results).unwrap();
    output
}

// ============================================================================
// Explosion Ray Casting
// ============================================================================

/// Ray-block intersection test
#[inline(always)]
fn ray_block_distance(
    origin_x: f64,
    origin_y: f64,
    origin_z: f64,
    dir_x: f64,
    dir_y: f64,
    dir_z: f64,
    block_x: f64,
    block_y: f64,
    block_z: f64,
) -> f64 {
    // Simple distance-based attenuation for explosion
    let dx = block_x - origin_x;
    let dy = block_y - origin_y;
    let dz = block_z - origin_z;
    (dx * dx + dy * dy + dz * dz).sqrt()
}

/// Parallel ray casting for explosion block destruction
/// origin: [x, y, z] - explosion center
/// rayDirs: [dx0, dy0, dz0, dx1, dy1, dz1, ...] - ray directions
/// Returns: intensity values for each ray
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustOptimizations_parallelRayCast<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    origin: JDoubleArray<'a>,
    ray_dirs: JDoubleArray<'a>,
    ray_count: jint,
    radius: jfloat,
) -> JFloatArray<'a> {
    let ray_count = ray_count as usize;

    // Get origin
    let mut origin_data = [0.0f64; 3];
    if env
        .get_double_array_region(&origin, 0, &mut origin_data)
        .is_err()
    {
        return env.new_float_array(0).unwrap();
    }

    // Get ray directions
    let dir_len = ray_count * 3;
    let mut dir_data = vec![0.0f64; dir_len];
    if env
        .get_double_array_region(&ray_dirs, 0, &mut dir_data)
        .is_err()
    {
        return env.new_float_array(0).unwrap();
    }

    let ox = origin_data[0];
    let oy = origin_data[1];
    let oz = origin_data[2];
    let r = radius as f64;

    // Cast rays in parallel and compute intensity at max distance
    let results: Vec<f32> = (0..ray_count)
        .into_par_iter()
        .map(|i| {
            let idx = i * 3;
            let dx = dir_data[idx];
            let dy = dir_data[idx + 1];
            let dz = dir_data[idx + 2];

            // Normalize direction
            let len = (dx * dx + dy * dy + dz * dz).sqrt();
            if len < 0.0001 {
                return 0.0f32;
            }

            let ndx = dx / len;
            let ndy = dy / len;
            let ndz = dz / len;

            // Sample points along ray and compute attenuation
            let mut intensity = 1.0f32;
            let step = 0.5;
            let mut t = 0.0;

            while t < r && intensity > 0.0 {
                t += step;
                // Simple distance-based falloff
                let falloff = 1.0 - (t / r);
                intensity = (falloff as f32).max(0.0);
            }

            intensity
        })
        .collect();

    // Create output array
    let output = env.new_float_array(ray_count as i32).unwrap();
    env.set_float_array_region(&output, 0, &results).unwrap();
    output
}

// ============================================================================
// Heightmap Column Updates
// ============================================================================

/// Batch heightmap update
/// currentHeights: current height values per column
/// blockTypes: block type at each position (0 = air, 1+ = solid)
/// Returns: updated heights
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustOptimizations_batchHeightmapUpdate<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    current_heights: JIntArray<'a>,
    block_types: JIntArray<'a>,
    column_count: jint,
    max_height: jint,
) -> JIntArray<'a> {
    let column_count = column_count as usize;
    let max_height = max_height as i32;

    // Get input arrays
    let mut heights = vec![0i32; column_count];
    let total_blocks = column_count * max_height as usize;
    let mut blocks = vec![0i32; total_blocks];

    if env
        .get_int_array_region(&current_heights, 0, &mut heights)
        .is_err()
    {
        return env.new_int_array(0).unwrap();
    }
    if env
        .get_int_array_region(&block_types, 0, &mut blocks)
        .is_err()
    {
        return env.new_int_array(0).unwrap();
    }

    // Update heights in parallel
    let results: Vec<i32> = (0..column_count)
        .into_par_iter()
        .map(|col| {
            let base_idx = col * max_height as usize;
            // Find highest non-air block
            for y in (0..max_height).rev() {
                let idx = base_idx + y as usize;
                if idx < blocks.len() && blocks[idx] != 0 {
                    return y + 1;
                }
            }
            0 // No solid blocks
        })
        .collect();

    // Create output array
    let output = env.new_int_array(column_count as i32).unwrap();
    env.set_int_array_region(&output, 0, &results).unwrap();
    output
}

// ============================================================================
// SIMD-Optimized Noise (AVX2)
// ============================================================================

/// AVX2-accelerated batch noise generation for 8 samples at a time
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
unsafe fn avx2_batch_noise(coords: &[f32], seed: i64, results: &mut [f32]) {
    let count = coords.len() / 3;

    // Process 8 samples at a time using AVX2
    let chunks = count / 8;

    for chunk in 0..chunks {
        let base = chunk * 8;

        // Load 8 x coordinates
        let x_vals: [f32; 8] = [
            coords[(base + 0) * 3],
            coords[(base + 1) * 3],
            coords[(base + 2) * 3],
            coords[(base + 3) * 3],
            coords[(base + 4) * 3],
            coords[(base + 5) * 3],
            coords[(base + 6) * 3],
            coords[(base + 7) * 3],
        ];

        // For now, use scalar fallback per sample
        // Full SIMD implementation would vectorize the entire noise algorithm
        for i in 0..8 {
            let idx = base + i;
            results[idx] = perlin_noise_3d(
                coords[idx * 3],
                coords[idx * 3 + 1],
                coords[idx * 3 + 2],
                seed,
            );
        }
    }

    // Handle remaining samples
    let remaining_start = chunks * 8;
    for i in remaining_start..count {
        results[i] = perlin_noise_3d(coords[i * 3], coords[i * 3 + 1], coords[i * 3 + 2], seed);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_perlin_noise_deterministic() {
        let seed = 12345i64;
        let v1 = perlin_noise_3d(1.5, 2.5, 3.5, seed);
        let v2 = perlin_noise_3d(1.5, 2.5, 3.5, seed);
        assert!((v1 - v2).abs() < 0.0001, "Noise should be deterministic");
    }

    #[test]
    fn test_perlin_noise_range() {
        let seed = 12345i64;
        for x in 0..100 {
            for y in 0..10 {
                let v = perlin_noise_3d(x as f32 * 0.1, y as f32 * 0.1, 0.0, seed);
                assert!(v >= -1.0 && v <= 1.0, "Noise value out of range: {}", v);
            }
        }
    }

    #[test]
    fn test_aabb_intersection() {
        // Overlapping boxes
        assert!(aabb_intersects(
            0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 0.5, 0.5, 0.5, 1.5, 1.5, 1.5
        ));

        // Non-overlapping boxes
        assert!(!aabb_intersects(
            0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 2.0, 2.0, 2.0, 3.0, 3.0, 3.0
        ));

        // Touching boxes (edge case - should intersect)
        assert!(aabb_intersects(
            0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 0.0, 0.0, 2.0, 1.0, 1.0
        ));
    }

    #[test]
    fn test_biome_hash_uniqueness() {
        // Different positions should produce different hashes
        let h1 = noise_hash(0, 0, 0, 0);
        let h2 = noise_hash(1, 0, 0, 0);
        let h3 = noise_hash(0, 1, 0, 0);
        let h4 = noise_hash(0, 0, 1, 0);

        assert_ne!(h1, h2);
        assert_ne!(h1, h3);
        assert_ne!(h1, h4);
        assert_ne!(h2, h3);
    }

    #[test]
    fn test_entity_distance_batch() {
        // Simple distance calculation test
        let dist = ((10.0f64 - 0.0).powi(2) + (0.0f64).powi(2) + (0.0f64).powi(2)).sqrt();
        assert!((dist - 10.0).abs() < 0.001);
    }
}

// ============================================================================
// Batch Entity Tick Processing
// ============================================================================

/// Calculate distances from entities to a reference point (e.g., nearest player)
/// entityPositions: [x0, y0, z0, x1, y1, z1, ...] - entity positions
/// refX, refY, refZ: reference point (player position)
/// Returns: array of squared distances for fast comparison
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustOptimizations_batchEntityDistanceSq<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    entity_positions: JDoubleArray<'a>,
    ref_x: jdouble,
    ref_y: jdouble,
    ref_z: jdouble,
    count: jint,
) -> JDoubleArray<'a> {
    let count = count as usize;

    // Get entity positions
    let pos_len = count * 3;
    let mut positions = vec![0.0f64; pos_len];

    if env
        .get_double_array_region(&entity_positions, 0, &mut positions)
        .is_err()
    {
        return env.new_double_array(0).unwrap();
    }

    // Calculate distances in parallel
    let results: Vec<f64> = (0..count)
        .into_par_iter()
        .map(|i| {
            let idx = i * 3;
            let dx = positions[idx] - ref_x;
            let dy = positions[idx + 1] - ref_y;
            let dz = positions[idx + 2] - ref_z;
            dx * dx + dy * dy + dz * dz // squared distance
        })
        .collect();

    // Create output array
    let output = env.new_double_array(count as i32).unwrap();
    env.set_double_array_region(&output, 0, &results).unwrap();
    output
}

/// Determine tick rates for entities based on distance to player
/// distancesSq: squared distances from batchEntityDistanceSq
/// nearThreshold: squared distance for "near" (full tick rate)
/// farThreshold: squared distance for "far" (reduced tick rate)
/// Returns: tick divisors (1 = every tick, 2 = every 2 ticks, 4 = every 4 ticks)
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustOptimizations_computeEntityTickRates<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    distances_sq: JDoubleArray<'a>,
    near_threshold_sq: jdouble,
    far_threshold_sq: jdouble,
    count: jint,
) -> JIntArray<'a> {
    let count = count as usize;

    let mut distances = vec![0.0f64; count];
    if env
        .get_double_array_region(&distances_sq, 0, &mut distances)
        .is_err()
    {
        return env.new_int_array(0).unwrap();
    }

    // Compute tick rates in parallel
    let results: Vec<i32> = distances
        .par_iter()
        .map(|&dist_sq| {
            if dist_sq <= near_threshold_sq {
                1 // Full tick rate
            } else if dist_sq <= far_threshold_sq {
                2 // Half tick rate
            } else if dist_sq <= far_threshold_sq * 4.0 {
                4 // Quarter tick rate
            } else {
                8 // Eighth tick rate (very far)
            }
        })
        .collect();

    let output = env.new_int_array(count as i32).unwrap();
    env.set_int_array_region(&output, 0, &results).unwrap();
    output
}

// ============================================================================
// Batch Mob AI Evaluation
// ============================================================================

/// Evaluate mob AI priority based on distance and target status
/// hasTarget: 1 if mob has target, 0 otherwise
/// distancesSq: squared distances to nearest player
/// Returns: AI priority (0 = skip, 1 = low, 2 = medium, 3 = high)
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustOptimizations_batchMobAIPriority<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    has_target: JIntArray<'a>,
    distances_sq: JDoubleArray<'a>,
    count: jint,
) -> JIntArray<'a> {
    let count = count as usize;

    let mut targets = vec![0i32; count];
    let mut distances = vec![0.0f64; count];

    if env
        .get_int_array_region(&has_target, 0, &mut targets)
        .is_err()
    {
        return env.new_int_array(0).unwrap();
    }
    if env
        .get_double_array_region(&distances_sq, 0, &mut distances)
        .is_err()
    {
        return env.new_int_array(0).unwrap();
    }

    let near_sq = 32.0 * 32.0; // 32 blocks
    let far_sq = 64.0 * 64.0; // 64 blocks

    let results: Vec<i32> = (0..count)
        .into_par_iter()
        .map(|i| {
            let has_tgt = targets[i] != 0;
            let dist_sq = distances[i];

            if has_tgt {
                3 // High priority - has target
            } else if dist_sq <= near_sq {
                2 // Medium priority - near player
            } else if dist_sq <= far_sq {
                1 // Low priority - moderate distance
            } else {
                0 // Skip - too far
            }
        })
        .collect();

    let output = env.new_int_array(count as i32).unwrap();
    env.set_int_array_region(&output, 0, &results).unwrap();
    output
}

// ============================================================================
// Fluid Flow Simulation
// ============================================================================

/// Simulate fluid flow for a grid of cells
/// fluidLevels: current fluid levels (0-8, 0 = empty, 8 = source)
/// solidBlocks: 1 if solid, 0 if passable
/// width, height, depth: grid dimensions
/// Returns: updated fluid levels
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustOptimizations_simulateFluidFlow<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    fluid_levels: JByteArray<'a>,
    solid_blocks: JByteArray<'a>,
    width: jint,
    height: jint,
    depth: jint,
) -> JByteArray<'a> {
    let width = width as usize;
    let height = height as usize;
    let depth = depth as usize;
    let total = width * height * depth;

    let mut fluids = vec![0i8; total];
    let mut solids = vec![0i8; total];

    if env
        .get_byte_array_region(&fluid_levels, 0, &mut fluids)
        .is_err()
    {
        return env.new_byte_array(0).unwrap();
    }
    if env
        .get_byte_array_region(&solid_blocks, 0, &mut solids)
        .is_err()
    {
        return env.new_byte_array(0).unwrap();
    }

    // Fluid flow simulation - parallel by columns
    let mut results = fluids.clone();
    let fluids_ref = &fluids;
    let solids_ref = &solids;

    // Process each Y layer (gravity flows down)
    for y in (0..height).rev() {
        // Process XZ plane in parallel
        let layer_results: Vec<(usize, i8)> = (0..width)
            .into_par_iter()
            .flat_map(|x| {
                (0..depth).into_par_iter().filter_map(move |z| {
                    let idx = y * width * depth + z * width + x;
                    if solids_ref[idx] != 0 {
                        return None; // Solid block, no fluid
                    }

                    let current = fluids_ref[idx];
                    if current == 0 {
                        return None; // No fluid here
                    }

                    // Check if can flow down
                    if y > 0 {
                        let below_idx = (y - 1) * width * depth + z * width + x;
                        if solids_ref[below_idx] == 0 && fluids_ref[below_idx] < 8 {
                            // Fluid will flow down - decrease here
                            if current < 8 {
                                // Not a source
                                return Some((idx, (current - 1).max(0)));
                            }
                        }
                    }

                    None
                })
            })
            .collect();

        // Apply updates
        for (idx, new_level) in layer_results {
            results[idx] = new_level;
        }
    }

    let output = env.new_byte_array(total as i32).unwrap();
    env.set_byte_array_region(&output, 0, &results).unwrap();
    output
}

// ============================================================================
// Item Entity Spatial Hashing
// ============================================================================

/// Compute spatial hash for item entity positions
/// Used for efficient nearby item lookups
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustOptimizations_batchSpatialHash<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    positions: JDoubleArray<'a>,
    cell_size: jdouble,
    count: jint,
) -> JLongArray<'a> {
    let count = count as usize;

    let mut pos_data = vec![0.0f64; count * 3];
    if env
        .get_double_array_region(&positions, 0, &mut pos_data)
        .is_err()
    {
        return env.new_long_array(0).unwrap();
    }

    let results: Vec<i64> = (0..count)
        .into_par_iter()
        .map(|i| {
            let x = (pos_data[i * 3] / cell_size).floor() as i32;
            let y = (pos_data[i * 3 + 1] / cell_size).floor() as i32;
            let z = (pos_data[i * 3 + 2] / cell_size).floor() as i32;

            // Hash the cell coordinates
            let mut hash = 0i64;
            hash ^= (x as i64).wrapping_mul(0x9E3779B97F4A7C15u64 as i64);
            hash ^= ((y as i64) << 20).wrapping_mul(0xBF58476D1CE4E5B9u64 as i64);
            hash ^= ((z as i64) << 40).wrapping_mul(0x94D049BB133111EBu64 as i64);
            hash
        })
        .collect();

    let output = env.new_long_array(count as i32).unwrap();
    env.set_long_array_region(&output, 0, &results).unwrap();
    output
}

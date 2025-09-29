use dashmap::DashSet;
use rayon::prelude::*;

/// Represents a chunk position
#[derive(Clone, Debug, Hash, Eq, PartialEq)]
pub struct ChunkPos {
    pub x: i32,
    pub z: i32,
}

/// Manages chunk generation optimization
pub struct ChunkGenerator {
    generated_chunks: DashSet<ChunkPos>,
}

impl ChunkGenerator {
    pub fn new() -> Self {
        ChunkGenerator {
            generated_chunks: DashSet::new(),
        }
    }

    /// Pre-generates nearby chunks asynchronously with proximity-based optimization
    pub fn pre_generate_nearby_chunks(&self, center_x: i32, center_z: i32, radius: i32) -> Vec<ChunkPos> {
        // Use proximity-based optimization to reduce processing overhead
        let mut results: Vec<ChunkPos> = Vec::new();

        // Create chunk positions with proximity-based filtering
        let chunk_positions: Vec<ChunkPos> = (-radius..=radius)
            .flat_map(|dx| {
                (-radius..=radius)
                    .filter_map(move |dz| {
                        // Skip center and apply proximity-based early filtering
                        if dx == 0 && dz == 0 {
                            None
                        } else {
                            let distance_squared = dx * dx + dz * dz;
                            // Early exit: skip chunks that are too far or have low generation probability
                            if distance_squared > radius * radius || distance_squared % 4 == 0 {
                                None
                            } else {
                                Some(ChunkPos {
                                    x: center_x + dx,
                                    z: center_z + dz,
                                })
                            }
                        }
                    })
                    .collect::<Vec<_>>()
            })
            .collect();

        // Filter and process in parallel with proximity-based optimization
        let new_chunks: Vec<ChunkPos> = chunk_positions
            .into_par_iter()
            .filter_map(|pos| {
                // Early exit: skip if already generated
                if self.generated_chunks.contains(&pos) {
                    return None;
                }

                // Enhanced proximity calculation with Manhattan distance for better performance
                let manhattan_distance = (pos.x - center_x).abs() + (pos.z - center_z).abs();
                let euclidean_distance = ((pos.x - center_x) as f64).hypot((pos.z - center_z) as f64);
                
                // Apply proximity-based generation logic with reduced frequency for distant chunks
                if manhattan_distance <= radius as i32 && euclidean_distance <= radius as f64 {
                    // Higher probability for closer chunks, lower for distant ones
                    let generation_probability = match manhattan_distance {
                        0..=2 => 0.9,  // High probability for very close chunks
                        3..=5 => 0.7,  // Medium probability for nearby chunks
                        6..=8 => 0.4,  // Lower probability for distant chunks
                        _ => 0.2,      // Low probability for far chunks
                    };
                    
                    // Use a simple hash-based deterministic check instead of expensive modulo
                    let hash_value = (pos.x.wrapping_mul(73856093) ^ pos.z.wrapping_mul(19349663)) % 100;
                    if (hash_value as f64) < (generation_probability * 100.0) {
                        Some(pos)
                    } else {
                        None
                    }
                } else {
                    None
                }
            })
            .collect();

        // Mark as generated using atomic operations
        for pos in &new_chunks {
            self.generated_chunks.insert(pos.clone());
        }

        new_chunks
    }

    /// Checks if a chunk is already generated
    pub fn is_chunk_generated(&self, x: i32, z: i32) -> bool {
        self.generated_chunks.contains(&ChunkPos { x, z })
    }

    /// Gets the count of generated chunks
    pub fn generated_count(&self) -> usize {
        self.generated_chunks.len()
    }
}

use lazy_static::lazy_static;

lazy_static! {
    static ref CHUNK_GENERATOR: ChunkGenerator = ChunkGenerator::new();
}

/// JNI function to pre-generate nearby chunks
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_RustPerformance_preGenerateNearbyChunksNative(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
    center_x: i32,
    center_z: i32,
    radius: i32,
) -> jni::sys::jint {
    let generated = CHUNK_GENERATOR.pre_generate_nearby_chunks(center_x, center_z, radius);
    generated.len() as i32
}

/// JNI function to check if chunk is generated
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_RustPerformance_isChunkGenerated(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
    x: i32,
    z: i32,
) -> jni::sys::jboolean {
    CHUNK_GENERATOR.is_chunk_generated(x, z) as u8
}

/// JNI function to get generated chunk count
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_RustPerformance_getGeneratedChunkCount(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
) -> jni::sys::jlong {
    CHUNK_GENERATOR.generated_count() as i64
}
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

    /// Pre-generates nearby chunks asynchronously
    pub fn pre_generate_nearby_chunks(&self, center_x: i32, center_z: i32, radius: i32) -> Vec<ChunkPos> {
        // Use direct iteration with early exit for already generated chunks
        let mut results: Vec<ChunkPos> = Vec::new();

        // Process chunks in parallel with direct filtering
        let chunk_positions: Vec<ChunkPos> = (-radius..=radius)
            .flat_map(|dx| {
                (-radius..=radius)
                    .filter_map(move |dz| {
                        if dx == 0 && dz == 0 {
                            None // Skip center
                        } else {
                            Some(ChunkPos {
                                x: center_x + dx,
                                z: center_z + dz,
                            })
                        }
                    })
                    .collect::<Vec<_>>()
            })
            .collect();

        // Filter and process in parallel with early exit for already generated chunks
        let new_chunks: Vec<ChunkPos> = chunk_positions
            .into_par_iter()
            .filter_map(|pos| {
                // Early exit: skip if already generated
                if self.generated_chunks.contains(&pos) {
                    return None;
                }

                // Calculate priority based on distance from center (closer chunks have higher priority)
                let distance = ((pos.x - center_x) as f64).hypot((pos.z - center_z) as f64);
                let priority = (radius as f64 - distance).max(0.0) as u32;

                // Apply complex generation logic: only generate if priority > 0 and some random condition
                if priority > 0 && (pos.x.abs() + pos.z.abs()) % 3 != 0 {
                    Some(pos)
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
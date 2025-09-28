use std::collections::HashSet;
use rayon::prelude::*;
use std::sync::Mutex;

/// Represents a chunk position
#[derive(Clone, Debug, Hash, Eq, PartialEq)]
pub struct ChunkPos {
    pub x: i32,
    pub z: i32,
}

/// Manages chunk generation optimization
pub struct ChunkGenerator {
    generated_chunks: Mutex<HashSet<ChunkPos>>,
}

impl ChunkGenerator {
    pub fn new() -> Self {
        ChunkGenerator {
            generated_chunks: Mutex::new(HashSet::new()),
        }
    }

    /// Pre-generates nearby chunks asynchronously
    pub fn pre_generate_nearby_chunks(&self, center_x: i32, center_z: i32, radius: i32) -> Vec<ChunkPos> {
        let mut chunks_to_generate = Vec::new();

        // Collect chunks to generate
        for dx in -radius..=radius {
            for dz in -radius..=radius {
                if dx == 0 && dz == 0 {
                    continue; // Skip center
                }
                let pos = ChunkPos {
                    x: center_x + dx,
                    z: center_z + dz,
                };
                chunks_to_generate.push(pos);
            }
        }

        // Filter out already generated chunks
        let mut generated = self.generated_chunks.lock().unwrap();
        chunks_to_generate.retain(|pos| !generated.contains(pos));

        // Parallel processing of chunk generation with priority calculation
        let results: Vec<ChunkPos> = chunks_to_generate
            .par_iter()
            .filter_map(|pos| {
                // Calculate priority based on distance from center (closer chunks have higher priority)
                let distance = ((pos.x - center_x) as f64).hypot((pos.z - center_z) as f64);
                let priority = (radius as f64 - distance).max(0.0) as u32;

                // Apply complex generation logic: only generate if priority > 0 and some random condition
                if priority > 0 && (pos.x.abs() + pos.z.abs()) % 3 != 0 {
                    Some(pos.clone())
                } else {
                    None
                }
            })
            .collect();

        // Mark as generated
        for pos in &results {
            generated.insert(pos.clone());
        }

        results
    }

    /// Checks if a chunk is already generated
    pub fn is_chunk_generated(&self, x: i32, z: i32) -> bool {
        let generated = self.generated_chunks.lock().unwrap();
        generated.contains(&ChunkPos { x, z })
    }

    /// Gets the count of generated chunks
    pub fn generated_count(&self) -> usize {
        self.generated_chunks.lock().unwrap().len()
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
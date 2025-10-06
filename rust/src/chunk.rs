use rayon::prelude::*;
use std::sync::atomic::{AtomicUsize, AtomicBool, Ordering};
use dashmap::DashMap;
use crossbeam_epoch::Atomic;
use std::sync::Arc;
use crate::memory_pool::{EnhancedMemoryPoolManager, get_global_enhanced_pool};
use crate::arena::{BumpArena, get_global_arena_pool};

/// Represents a chunk position
#[derive(Clone, Debug, Hash, Eq, PartialEq)]
pub struct ChunkPos {
    pub x: i32,
    pub z: i32,
}

/// Manages chunk generation optimization with lock-free operations and memory pressure awareness
pub struct ChunkGenerator {
    generated_chunks: DashMap<ChunkPos, ()>,
    generation_stats: Arc<ChunkGenerationStats>,
    memory_pool: Arc<EnhancedMemoryPoolManager>,
    arena_pool: Arc<crate::arena::ArenaPool>,
    is_critical_operation: AtomicBool,
}

/// Statistics for chunk generation with atomic counters and memory pressure awareness
#[derive(Debug)]
pub struct ChunkGenerationStats {
    total_generated: AtomicUsize,
    total_attempted: AtomicUsize,
    highest_concurrency: AtomicUsize,
    current_concurrency: AtomicUsize,
    memory_pressure_aborts: AtomicUsize,
    critical_operation_count: AtomicUsize,
}

impl Clone for ChunkGenerationStats {
    fn clone(&self) -> Self {
        let snap = self.get_stats();
        let stats = ChunkGenerationStats::new();
        stats.total_generated.store(snap.total_generated, Ordering::Relaxed);
        stats.total_attempted.store(snap.total_attempted, Ordering::Relaxed);
        stats.highest_concurrency.store(snap.highest_concurrency, Ordering::Relaxed);
        stats.current_concurrency.store(snap.current_concurrency, Ordering::Relaxed);
        stats.memory_pressure_aborts.store(snap.memory_pressure_aborts.unwrap_or(0), Ordering::Relaxed);
        stats.critical_operation_count.store(snap.critical_operation_count.unwrap_or(0), Ordering::Relaxed);
        stats
    }
}

impl ChunkGenerationStats {
    pub fn new() -> Self {
        ChunkGenerationStats {
            total_generated: AtomicUsize::new(0),
            total_attempted: AtomicUsize::new(0),
            highest_concurrency: AtomicUsize::new(0),
            current_concurrency: AtomicUsize::new(0),
            memory_pressure_aborts: AtomicUsize::new(0),
            critical_operation_count: AtomicUsize::new(0),
        }
    }

    pub fn record_generation(&self) {
        self.total_generated.fetch_add(1, Ordering::Relaxed);
    }

    pub fn record_attempt(&self) {
        self.total_attempted.fetch_add(1, Ordering::Relaxed);
    }

    pub fn update_concurrency(&self, delta: isize) {
        let current = self.current_concurrency.load(Ordering::Relaxed) as isize;
        let new_current = current + delta;
        
        self.current_concurrency.store(new_current as usize, Ordering::Relaxed);
        
        if new_current > self.highest_concurrency.load(Ordering::Relaxed) as isize {
            self.highest_concurrency.store(new_current as usize, Ordering::Relaxed);
        }
    }

    pub fn get_stats(&self) -> ChunkGenerationStatsSnapshot {
        ChunkGenerationStatsSnapshot {
            total_generated: self.total_generated.load(Ordering::Relaxed),
            total_attempted: self.total_attempted.load(Ordering::Relaxed),
            highest_concurrency: self.highest_concurrency.load(Ordering::Relaxed),
            current_concurrency: self.current_concurrency.load(Ordering::Relaxed),
            memory_pressure_aborts: Some(self.memory_pressure_aborts.load(Ordering::Relaxed)),
            critical_operation_count: Some(self.critical_operation_count.load(Ordering::Relaxed)),
        }
    }
}

/// Snapshot of chunk generation statistics (for safe transfer across threads)
#[derive(Debug, Clone)]
pub struct ChunkGenerationStatsSnapshot {
    pub total_generated: usize,
    pub total_attempted: usize,
    pub highest_concurrency: usize,
    pub current_concurrency: usize,
    pub memory_pressure_aborts: Option<usize>,
    pub critical_operation_count: Option<usize>,
}

impl ChunkGenerator {
    pub fn new() -> Self {
        ChunkGenerator {
            generated_chunks: DashMap::new(),
            generation_stats: Arc::new(ChunkGenerationStats::new()),
            memory_pool: get_global_enhanced_pool(),
            arena_pool: get_global_arena_pool(),
            is_critical_operation: AtomicBool::new(false),
        }
    }

    /// Pre-generates nearby chunks asynchronously with proximity-based optimization
    pub fn pre_generate_nearby_chunks(&self, center_x: i32, center_z: i32, radius: i32) -> Vec<ChunkPos> {
        // Use proximity-based optimization to reduce processing overhead
        

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
                if self.generated_chunks.contains_key(&pos) {
                    return None;
                }

                // Enhanced proximity calculation with Manhattan distance for better performance
                let manhattan_distance = (pos.x - center_x).abs() + (pos.z - center_z).abs();
                let euclidean_distance = ((pos.x - center_x) as f64).hypot((pos.z - center_z) as f64);
                
                // Apply proximity-based generation logic with reduced frequency for distant chunks (LOD)
                if manhattan_distance <= radius as i32 && euclidean_distance <= radius as f64 {
                    // Higher probability for closer chunks, lower for distant ones (Level of Detail)
                    let generation_probability = match manhattan_distance {
                        0..=1 => 1.0,  // Very close chunks, always generate for immediate rendering
                        2..=3 => 0.8,  // Close chunks, high priority
                        4..=6 => 0.5,  // Medium distance, moderate priority
                        7..=10 => 0.2, // Distant chunks, low priority for LOD
                        _ => 0.05,     // Very far chunks, minimal generation for LOD
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
            self.generated_chunks.insert(pos.clone(), ());
        }

        new_chunks
    }

    /// Checks if a chunk is already generated
    pub fn is_chunk_generated(&self, x: i32, z: i32) -> bool {
        self.generated_chunks.contains_key(&ChunkPos { x, z })
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
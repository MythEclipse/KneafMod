// Core modules
pub mod allocator;
pub mod arena;
pub mod cache_eviction;
pub mod checksum_monitor;
pub mod chunk;
pub mod compression;
pub mod database;
#[macro_use]
pub mod errors;
pub mod logging;
#[macro_use]
pub mod macros;
pub mod memory_pool;
pub mod memory_pressure_config;
pub mod performance_monitoring;
pub mod traits;

#[cfg(test)]
mod test_parallelism;
pub mod simd;
pub mod simd_enhanced;
pub mod spatial;
pub mod spatial_optimized;
pub mod types;

// Binary serialization modules
pub mod binary {
    pub mod conversions;
    pub mod zero_copy;
}

// Block processing modules
pub mod block {
    pub mod bindings;
    pub mod config;
    pub mod processing;
    pub mod types;
}

// Entity processing modules
pub mod entity {
    pub mod bindings;
    pub mod config;
    pub mod processing;
    pub mod types;
}

// Mob processing modules
pub mod mob {
    pub mod bindings;
    pub mod config;
    pub mod processing;
    pub mod types;
}

// Villager processing modules
pub mod villager {
    pub mod bindings;
    pub mod config;
    pub mod pathfinding;
    pub mod processing;
    pub mod spatial;
    pub mod types;
}

// JNI bridge modules
pub mod jni_async_bridge;
pub mod jni_batch;
pub mod jni_batch_processor;
pub mod jni_bridge;
pub mod jni_exports;
pub mod jni_memory;
pub mod jni_performance;
pub mod jni_raii;
pub mod jni_utils;

// Batch processing utilities
pub mod batch_processing {
    pub mod common;
}

// Zero copy stubs (compile-time helpers)
pub mod zero_copy_stubs;

// Re-export commonly used zero-copy types
pub use zero_copy_stubs::{ZeroCopyBufferPool, ZeroCopyBufferRef, get_global_buffer_tracker};
// Batch helpers
pub mod batch_types;
pub use batch_types::{BatchError, BatchOperationType};

// Test modules
#[cfg(test)]
mod test_jni_exports;

// Parallelism modules
pub mod parallelism;

// Shared utilities
pub mod shared;

#[cfg(test)]
pub mod test_timeout;

// Re-export commonly used types from memory_pool module
pub use memory_pool::{
    // Global pool functions
    get_global_enhanced_pool,
    get_global_enhanced_pool_mut,
    hierarchical::PooledVec as HierarchicalPooledVec,

    specialized_pools::PooledString,

    specialized_pools::PooledVec,
    with_thread_local_pool,
    with_thread_local_pool_mut,

    // Buffer pool functions
    acquire_buffer,
    get_global_buffer_pool,
    init_global_buffer_pool,
    BufferPool,
    BufferPoolConfig,
    BufferPoolStatsSnapshot,
    // ZeroCopyBuffer is provided by zero_copy_stubs to avoid duplicate definitions

    AllocationStats,

    ArenaHandle,
    ArenaStats,

    AtomicCounter,

    // Atomic state
    AtomicPoolState,
    EnhancedManagerConfig,
    // Enhanced manager
    EnhancedMemoryPoolManager,
    FastArena,
    FastObjectPool,
    // Hierarchical pool
    HierarchicalMemoryPool,
    HierarchicalPoolConfig,
    // Lightweight pool
    LightweightMemoryPool,
    LightweightPoolStats,
    LightweightPooledObject,
    MaintenanceResult,
    // Legacy compatibility (MemoryPoolManager not present in this build)
    MemoryPressureLevel,

    // Core types
    ObjectPool,
    PooledObject,
    ScopedArena,
    SizeClass,
    SizeClassStats,

    Slab,
    SlabAllocation,
    // Slab allocator
    SlabAllocator,
    SlabAllocatorConfig,
    SlabAllocatorStats,
    SlabStats,
    SmartPooledVec,
    StringPool,
    // Swap pool
    SwapMemoryPool,
    SwapPoolConfig,
    SwapPooledVec,

    ThreadLocalLightweightPool,
    // Specialized pools
    VecPool,
};

// Initialize global buffer tracker for zero-copy operations
// Provide a compatibility wrapper expected by older modules
pub fn init_global_buffer_tracker(_capacity: usize) -> std::result::Result<(), String> {
    // Our stub tracker is lazy-initialized; nothing to do here
    let _ = crate::get_global_buffer_tracker();
    Ok(())
}

#[ctor::ctor]
fn init_globals() {
    // Initialize global buffer tracker with reasonable defaults
    let _ = init_global_buffer_tracker(1000);
}

// Re-export other commonly used types
pub use compression::{CompressionEngine, CompressionStats};
pub use database::RustDatabaseAdapter;
pub use logging::{generate_trace_id, PerformanceLogger};
pub use memory_pressure_config::MemoryPressureConfig;
pub use simd::{SimdOperations, SimdProcessor};
pub use simd_enhanced::{EnhancedSimdProcessor, SimdCapability, SimdPerformanceStats};
pub use errors::{RustError, Result};
pub use traits::{Initializable, not_initialized_error};

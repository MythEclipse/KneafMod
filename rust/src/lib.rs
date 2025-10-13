// Core modules
pub mod allocator;
pub mod arena;
pub mod cache_eviction;
pub mod checksum_monitor;
pub mod chunk;
pub mod compression;
pub mod database;
pub mod logging;
pub mod memory_pool;
pub mod memory_pressure_config;
pub mod performance_monitoring;

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
pub mod jni_raii;

// Parallelism modules
pub mod parallelism;

// Shared utilities
pub mod shared;

// Extreme performance testing
pub mod test_extreme_performance;
pub mod test_utils;
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
    // Legacy compatibility
    MemoryPoolManager,
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
use crate::jni_batch::init_global_buffer_tracker;

#[ctor::ctor]
fn init_globals() {
    // Initialize global buffer tracker with reasonable defaults
    let _ = init_global_buffer_tracker(1000); // Allow up to 1000 active zero-copy buffers
}

// Re-export other commonly used types
pub use compression::{CompressionEngine, CompressionStats};
pub use database::RustDatabaseAdapter;
pub use logging::{generate_trace_id, PerformanceLogger};
pub use memory_pressure_config::MemoryPressureConfig;
pub use simd::{SimdOperations, SimdProcessor};
pub use simd_enhanced::{EnhancedSimdProcessor, SimdCapability, SimdPerformanceStats};
pub use types::{Result, RustPerformanceError};

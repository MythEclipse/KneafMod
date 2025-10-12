// Core modules
pub mod logging;
pub mod types;
pub mod memory_pool;
pub mod memory_pressure_config;
pub mod performance_monitoring;
pub mod simd;
pub mod simd_enhanced;
pub mod spatial;
pub mod spatial_optimized;
pub mod compression;
pub mod cache_eviction;
pub mod checksum_monitor;
pub mod allocator;
pub mod database;
pub mod chunk;
pub mod arena;

// Binary serialization modules
pub mod binary {
    pub mod conversions;
    pub mod zero_copy;
}

// Block processing modules
pub mod block {
    pub mod types;
    pub mod config;
    pub mod processing;
    pub mod bindings;
}

// Entity processing modules
pub mod entity {
    pub mod types;
    pub mod config;
    pub mod processing;
    pub mod bindings;
}

// Mob processing modules
pub mod mob {
    pub mod types;
    pub mod config;
    pub mod processing;
    pub mod bindings;
}

// Villager processing modules
pub mod villager {
    pub mod types;
    pub mod config;
    pub mod processing;
    pub mod spatial;
    pub mod pathfinding;
    pub mod bindings;
}

// JNI bridge modules
pub mod jni_bridge;
pub mod jni_batch;
pub mod jni_batch_processor;
pub mod jni_async_bridge;
pub mod jni_raii;

// Parallelism modules
pub mod parallelism;

// Shared utilities
pub mod shared;

// Extreme performance testing
pub mod test_extreme_performance;

// Re-export commonly used types from memory_pool module
pub use memory_pool::{
    // Core types
    ObjectPool, PooledObject, MemoryPressureLevel,

    // Specialized pools
    VecPool, StringPool,
    specialized_pools::PooledVec,
    specialized_pools::PooledString,

    // Hierarchical pool
    HierarchicalMemoryPool, HierarchicalPoolConfig, FastObjectPool,
    hierarchical::PooledVec as HierarchicalPooledVec,

    // Swap pool
    SwapMemoryPool, SwapPoolConfig, SwapPooledVec,

    // Enhanced manager
    EnhancedMemoryPoolManager, EnhancedManagerConfig, SmartPooledVec, MaintenanceResult, AllocationStats,

    // Lightweight pool
    LightweightMemoryPool, LightweightPooledObject, ThreadLocalLightweightPool, FastArena, ArenaHandle, ScopedArena, LightweightPoolStats, ArenaStats,

    // Slab allocator
    SlabAllocator, SlabAllocation, SlabAllocatorConfig, Slab, SizeClass, SlabStats, SlabAllocatorStats, SizeClassStats,

    // Atomic state
    AtomicPoolState, AtomicCounter,

    // Global pool functions
    get_global_enhanced_pool, get_global_enhanced_pool_mut,
    with_thread_local_pool, with_thread_local_pool_mut,

    // Legacy compatibility
    MemoryPoolManager,
};

// Initialize global buffer tracker for zero-copy operations
use crate::jni_batch::init_global_buffer_tracker;

#[ctor::ctor]
fn init_globals() {
    // Initialize global buffer tracker with reasonable defaults
    let _ = init_global_buffer_tracker(1000); // Allow up to 1000 active zero-copy buffers
}

// Re-export other commonly used types
pub use logging::{PerformanceLogger, generate_trace_id};
pub use types::{RustPerformanceError, Result};
pub use memory_pressure_config::MemoryPressureConfig;
pub use simd::{SimdOperations, SimdProcessor};
pub use simd_enhanced::{EnhancedSimdProcessor, SimdCapability, SimdPerformanceStats};
pub use compression::{CompressionEngine, CompressionStats};
pub use database::RustDatabaseAdapter;
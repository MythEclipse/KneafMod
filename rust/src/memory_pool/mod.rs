//! Memory pool management module with thread-safe concurrent operations
//!
//! This module provides various memory pool implementations with different characteristics:
//! - **ObjectPool**: Generic object pooling with atomic operations
//! - **VecPool/StringPool**: Specialized pools for vectors and strings
//! - **HierarchicalMemoryPool**: Multi-level memory allocation with size classes
//! - **SwapMemoryPool**: Memory swapping with compression for low-memory scenarios
//! - **EnhancedMemoryPoolManager**: Intelligent pool manager with automatic selection
//! - **LightweightMemoryPool**: Single-threaded pool with minimal overhead
//! - **SlabAllocator**: Fixed-size block allocation with size classes
//! - **BufferPool**: Zero-copy buffer pool for high-performance data transfer

pub mod atomic_state;
pub mod buffer_pool;
pub mod enhanced_manager;
pub mod hierarchical;
pub mod lightweight;
pub mod object_pool;
pub mod slab_allocator;
pub mod specialized_pools;
pub mod swap;

// Re-export commonly used types for convenience
pub use atomic_state::{AtomicCounter, AtomicPoolState};
pub use buffer_pool::{
    acquire_buffer, get_global_buffer_pool, init_global_buffer_pool, BufferPool,
    BufferPoolConfig, BufferPoolStatsSnapshot, ZeroCopyBuffer,
};
pub use enhanced_manager::{
    AllocationStats, EnhancedManagerConfig, EnhancedMemoryPoolManager, MaintenanceResult,
    SmartPooledVec,
};
pub use hierarchical::{
    FastObjectPool, HierarchicalMemoryPool, HierarchicalPoolConfig,
    PooledVec as HierarchicalPooledVec,
};
pub use lightweight::{
    ArenaHandle, ArenaStats, FastArena, LightweightMemoryPool, LightweightPoolStats,
    LightweightPooledObject, ScopedArena, ThreadLocalLightweightPool,
};
pub use object_pool::{MemoryPressureLevel, ObjectPool, PooledObject};
pub use slab_allocator::{
    SizeClass, SizeClassStats, Slab, SlabAllocation, SlabAllocator, SlabAllocatorConfig,
    SlabAllocatorStats, SlabStats,
};
pub use specialized_pools::{PooledString, PooledVec, StringPool, VecPool};
pub use swap::{SwapMemoryPool, SwapPoolConfig, SwapPooledVec};

/// Global memory pool manager instance (thread-safe singleton)
static GLOBAL_MEMORY_POOL: std::sync::OnceLock<std::sync::RwLock<EnhancedMemoryPoolManager>> =
    std::sync::OnceLock::new();

/// Get the global enhanced memory pool manager
pub fn get_global_enhanced_pool() -> std::sync::RwLockReadGuard<'static, EnhancedMemoryPoolManager>
{
    let pool = GLOBAL_MEMORY_POOL.get_or_init(|| {
        std::sync::RwLock::new(
            EnhancedMemoryPoolManager::new(None).expect("Failed to initialize global memory pool"),
        )
    });
    pool.read().unwrap()
}

/// Get the global enhanced memory pool manager (mutable)
pub fn get_global_enhanced_pool_mut(
) -> std::sync::RwLockWriteGuard<'static, EnhancedMemoryPoolManager> {
    let pool = GLOBAL_MEMORY_POOL.get_or_init(|| {
        std::sync::RwLock::new(
            EnhancedMemoryPoolManager::new(None).expect("Failed to initialize global memory pool"),
        )
    });
    pool.write().unwrap()
}

thread_local! {
    static THREAD_LOCAL_POOL: std::cell::RefCell<EnhancedMemoryPoolManager> = std::cell::RefCell::new(
        EnhancedMemoryPoolManager::new(None).expect("Failed to initialize thread-local memory pool")
    );
}

/// Get the thread-local memory pool manager with a closure
pub fn with_thread_local_pool<F, R>(f: F) -> R
where
    F: FnOnce(&EnhancedMemoryPoolManager) -> R,
{
    THREAD_LOCAL_POOL.with(|pool| f(&pool.borrow()))
}

/// Get the thread-local memory pool manager (mutable) with a closure
pub fn with_thread_local_pool_mut<F, R>(f: F) -> R
where
    F: FnOnce(&mut EnhancedMemoryPoolManager) -> R,
{
    THREAD_LOCAL_POOL.with(|pool| f(&mut pool.borrow_mut()))
}


#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_global_pool_access() {
        let pool = get_global_enhanced_pool();
        assert_eq!(pool.get_memory_pressure(), MemoryPressureLevel::Normal);
    }

    #[test]
    fn test_thread_local_pool_access() {
        with_thread_local_pool(|pool| {
            assert_eq!(pool.get_memory_pressure(), MemoryPressureLevel::Normal);
        });
    }

    #[test]
    fn test_legacy_memory_pool_manager() {
        let manager = MemoryPoolManager::new().unwrap();
        let data = manager.allocate(100).unwrap();
        assert_eq!(data.len(), 100);
        assert_eq!(manager.get_memory_pressure(), MemoryPressureLevel::Normal);
    }
}

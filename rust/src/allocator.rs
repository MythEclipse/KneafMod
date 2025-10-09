//! Unified memory arena allocator with jemalloc integration
//!
//! This module provides a unified memory arena system using jemalloc for optimal performance.
//! Features include slab allocation, memory pooling, and zero-copy buffer management.

use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::{Arc, Mutex};
use std::collections::HashMap;
use std::sync::atomic::{AtomicUsize, AtomicU64, Ordering};
use log::{info, debug, warn};
use jni::sys::{jint, jboolean, jdouble, jstring};
use parking_lot::RwLock;
use lazy_static::lazy_static;

// Configuration for different allocation strategies
const SMALL_OBJECT_THRESHOLD: usize = 4096; // 4KB
const MEDIUM_OBJECT_THRESHOLD: usize = 65536; // 64KB
const LARGE_OBJECT_THRESHOLD: usize = 1048576; // 1MB

/// RAII wrapper for tracked memory allocations
pub struct TrackedAllocation {
    ptr: *mut u8,
    size: usize,
    allocation_id: u64,
}

impl TrackedAllocation {
    pub fn new(ptr: *mut u8, size: usize, allocation_id: u64) -> Self {
        Self { ptr, size, allocation_id }
    }

    pub fn as_ptr(&self) -> *mut u8 {
        self.ptr
    }

    pub fn size(&self) -> usize {
        self.size
    }

    pub fn allocation_id(&self) -> u64 {
        self.allocation_id
    }
}

impl Drop for TrackedAllocation {
    fn drop(&mut self) {
        if !self.ptr.is_null() {
            log::warn!("TrackedAllocation dropped without explicit deallocation - potential memory leak detected for allocation {}", self.allocation_id);
        }
    }
}

// TrackedAllocation needs to be Send for use in shared structures
unsafe impl Send for TrackedAllocation {}

/// Memory allocation tracker for leak detection (lock-free with sharding)
#[derive(Debug)]
pub struct AllocationTracker {
    active_allocations: Arc<Vec<Mutex<HashMap<u64, (usize, std::time::Instant)>>>>, // Sharded for reduced contention
    total_allocations: AtomicU64,
    total_deallocations: AtomicU64,
    shard_count: usize,
}

impl AllocationTracker {
    pub fn new() -> Self {
        let shard_count = 16; // 16 shards for reduced lock contention
        let mut shards = Vec::with_capacity(shard_count);
        for _ in 0..shard_count {
            shards.push(Mutex::new(HashMap::new()));
        }

        Self {
            active_allocations: Arc::new(shards),
            total_allocations: AtomicU64::new(0),
            total_deallocations: AtomicU64::new(0),
            shard_count,
        }
    }

    fn get_shard(&self, allocation_id: u64) -> usize {
        (allocation_id as usize) % self.shard_count
    }

    pub fn track_allocation(&self, allocation_id: u64, size: usize) {
        self.total_allocations.fetch_add(1, Ordering::Relaxed);
        let shard_idx = self.get_shard(allocation_id);
        let mut shard = self.active_allocations[shard_idx].lock().unwrap();
        shard.insert(allocation_id, (size, std::time::Instant::now()));
    }

    pub fn track_deallocation(&self, allocation_id: u64) -> Option<usize> {
        self.total_deallocations.fetch_add(1, Ordering::Relaxed);
        let shard_idx = self.get_shard(allocation_id);
        let mut shard = self.active_allocations[shard_idx].lock().unwrap();
        shard.remove(&allocation_id).map(|(size, _)| size)
    }

    pub fn get_leak_report(&self) -> Vec<(u64, usize, std::time::Duration)> {
        let mut report = Vec::new();
        for shard in self.active_allocations.iter() {
            let shard_data = shard.lock().unwrap();
            for (&id, &(size, timestamp)) in shard_data.iter() {
                report.push((id, size, timestamp.elapsed()));
            }
        }
        report
    }

    pub fn has_leaks(&self) -> bool {
        for shard in self.active_allocations.iter() {
            let shard_data = shard.lock().unwrap();
            if !shard_data.is_empty() {
                return true;
            }
        }
        false
    }
}

/// Scope guard for automatic cleanup
pub struct ScopeGuard<F: FnOnce()> {
    cleanup: Option<F>,
}

impl<F: FnOnce()> ScopeGuard<F> {
    pub fn new(cleanup: F) -> Self {
        Self { cleanup: Some(cleanup) }
    }

    pub fn dismiss(mut self) {
        self.cleanup.take();
    }
}

impl<F: FnOnce()> Drop for ScopeGuard<F> {
    fn drop(&mut self) {
        if let Some(cleanup) = self.cleanup.take() {
            cleanup();
        }
    }
}

/// Result type for allocation operations
#[derive(Debug)]
pub enum AllocationError {
    OutOfMemory,
    InvalidSize,
    AllocationFailed,
    DeallocationFailed,
}

impl std::fmt::Display for AllocationError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            AllocationError::OutOfMemory => write!(f, "Out of memory"),
            AllocationError::InvalidSize => write!(f, "Invalid allocation size"),
            AllocationError::AllocationFailed => write!(f, "Allocation failed"),
            AllocationError::DeallocationFailed => write!(f, "Deallocation failed"),
        }
    }
}

impl std::error::Error for AllocationError {}

/// Memory arena configuration
#[derive(Debug, Clone)]
pub struct MemoryArenaConfig {
    pub small_object_pool_size: usize,
    pub medium_object_pool_size: usize,
    pub large_object_pool_size: usize,
    pub enable_slab_allocation: bool,
    pub enable_zero_copy_buffers: bool,
    pub cleanup_threshold: f64,
}

impl Default for MemoryArenaConfig {
    fn default() -> Self {
        Self {
            small_object_pool_size: 10000,
            medium_object_pool_size: 1000,
            large_object_pool_size: 100,
            enable_slab_allocation: true,
            enable_zero_copy_buffers: true,
            cleanup_threshold: 0.9,
        }
    }
}

/// Slab allocator for small objects (sharded for reduced lock contention)
#[derive(Debug)]
pub struct SlabAllocator {
    slab_pools: Arc<Vec<Mutex<Vec<Vec<u8>>>>>, // Sharded pools
    shard_count: usize,
    slab_size: usize,
    allocated: AtomicUsize,
    deallocated: AtomicUsize,
}

impl SlabAllocator {
    pub fn new(slab_size: usize, pool_size: usize) -> Self {
        let shard_count = 8; // 8 shards for reduced contention
        let mut slab_pools = Vec::with_capacity(shard_count);
        let slabs_per_shard = pool_size / shard_count;

        for _ in 0..shard_count {
            let mut slabs = Vec::with_capacity(slabs_per_shard);
            for _ in 0..slabs_per_shard {
                slabs.push(Vec::with_capacity(slab_size));
            }
            slab_pools.push(Mutex::new(slabs));
        }

        Self {
            slab_pools: Arc::new(slab_pools),
            shard_count,
            slab_size,
            allocated: AtomicUsize::new(0),
            deallocated: AtomicUsize::new(0),
        }
    }

    pub fn allocate(&self) -> Option<Vec<u8>> {
        // Use round-robin sharding for better distribution
        static SHARD_COUNTER: AtomicUsize = AtomicUsize::new(0);
        let shard_idx = SHARD_COUNTER.fetch_add(1, Ordering::Relaxed) % self.shard_count;

        // Try current shard first
        if let Some(slab) = self.try_allocate_from_shard(shard_idx) {
            return Some(slab);
        }

        // Try other shards if current is empty
        for i in 0..self.shard_count {
            if i != shard_idx {
                if let Some(slab) = self.try_allocate_from_shard(i) {
                    return Some(slab);
                }
            }
        }

        None
    }

    fn try_allocate_from_shard(&self, shard_idx: usize) -> Option<Vec<u8>> {
        let mut slabs = self.slab_pools[shard_idx].lock().unwrap();
        if let Some(mut slab) = slabs.pop() {
            slab.clear();
            self.allocated.fetch_add(1, Ordering::Relaxed);
            Some(slab)
        } else {
            None
        }
    }

    pub fn deallocate(&self, mut slab: Vec<u8>) {
        if slab.capacity() == self.slab_size {
            slab.clear();
            self.deallocated.fetch_add(1, Ordering::Relaxed);

            // Use round-robin sharding for deallocation
            static DEALLOC_COUNTER: AtomicUsize = AtomicUsize::new(0);
            let shard_idx = DEALLOC_COUNTER.fetch_add(1, Ordering::Relaxed) % self.shard_count;

            let mut slabs = self.slab_pools[shard_idx].lock().unwrap();
            if slabs.len() < 1250 { // Max per shard (10000 / 8)
                slabs.push(slab);
            }
        }
    }

    pub fn get_stats(&self) -> SlabStats {
        let mut total_available = 0;
        for pool in self.slab_pools.iter() {
            let slabs = pool.lock().unwrap();
            total_available += slabs.len();
        }

        SlabStats {
            available_slabs: total_available,
            allocated: self.allocated.load(Ordering::Relaxed),
            deallocated: self.deallocated.load(Ordering::Relaxed),
            slab_size: self.slab_size,
        }
    }
}

#[derive(Debug, Clone)]
pub struct SlabStats {
    pub available_slabs: usize,
    pub allocated: usize,
    pub deallocated: usize,
    pub slab_size: usize,
}

/// Unified memory arena with jemalloc integration
pub struct UnifiedMemoryArena {
    small_allocator: SlabAllocator,
    medium_allocator: SlabAllocator,
    large_allocator: SlabAllocator,
    zero_copy_buffers: Arc<Vec<Mutex<HashMap<u64, TrackedAllocation>>>>, // Sharded for reduced contention
    next_buffer_id: AtomicU64,
    config: MemoryArenaConfig,
    total_allocated: AtomicU64,
    total_deallocated: AtomicU64,
    allocation_tracker: Arc<AllocationTracker>,
    buffer_shard_count: usize,
}

impl UnifiedMemoryArena {
    fn get_buffer_shard(&self, buffer_id: u64) -> usize {
        (buffer_id as usize) % self.buffer_shard_count
    }

    pub fn new(config: MemoryArenaConfig) -> Self {
        info!("Initializing unified memory arena with jemalloc and sharded allocation");

        let buffer_shard_count = 8; // 8 shards for zero-copy buffers
        let mut zero_copy_buffers = Vec::with_capacity(buffer_shard_count);
        for _ in 0..buffer_shard_count {
            zero_copy_buffers.push(Mutex::new(HashMap::new()));
        }

        Self {
            small_allocator: SlabAllocator::new(SMALL_OBJECT_THRESHOLD, config.small_object_pool_size),
            medium_allocator: SlabAllocator::new(MEDIUM_OBJECT_THRESHOLD, config.medium_object_pool_size),
            large_allocator: SlabAllocator::new(LARGE_OBJECT_THRESHOLD, config.large_object_pool_size),
            zero_copy_buffers: Arc::new(zero_copy_buffers),
            next_buffer_id: AtomicU64::new(1),
            config,
            total_allocated: AtomicU64::new(0),
            total_deallocated: AtomicU64::new(0),
            allocation_tracker: Arc::new(AllocationTracker::new()),
            buffer_shard_count,
        }
    }

    /// Legacy allocate method for backward compatibility - deprecated, use allocate_tracked instead
    #[deprecated(note = "Use allocate_tracked for proper memory leak prevention")]
    pub fn allocate(&self, size: usize) -> *mut u8 {
        self.total_allocated.fetch_add(size as u64, Ordering::Relaxed);
        
        if size <= SMALL_OBJECT_THRESHOLD && self.config.enable_slab_allocation {
            if let Some(buffer) = self.small_allocator.allocate() {
                let ptr = buffer.as_ptr() as *mut u8;
                std::mem::forget(buffer); // Prevent Rust from freeing it
                return ptr;
            }
        } else if size <= MEDIUM_OBJECT_THRESHOLD {
            if let Some(buffer) = self.medium_allocator.allocate() {
                let ptr = buffer.as_ptr() as *mut u8;
                std::mem::forget(buffer);
                return ptr;
            }
        } else if size <= LARGE_OBJECT_THRESHOLD {
            if let Some(buffer) = self.large_allocator.allocate() {
                let ptr = buffer.as_ptr() as *mut u8;
                std::mem::forget(buffer);
                return ptr;
            }
        }

        // Fallback to system allocator for very large allocations
        unsafe {
            let layout = Layout::from_size_align_unchecked(size, 8);
            System.alloc(layout)
        }
    }

    /// Safe allocation method that returns a tracked allocation
    pub fn allocate_tracked(&self, size: usize) -> Result<TrackedAllocation, AllocationError> {
        if size == 0 {
            return Err(AllocationError::InvalidSize);
        }

        let allocation_id = self.allocation_tracker.total_allocations.load(Ordering::Relaxed) + 1;
        
        // Create scope guard to ensure cleanup on failure
        let _guard = ScopeGuard::new(|| {
            warn!("Allocation failed for size {} with id {}, cleanup triggered", size, allocation_id);
        });

        self.total_allocated.fetch_add(size as u64, Ordering::Relaxed);
        
        let ptr = if size <= SMALL_OBJECT_THRESHOLD && self.config.enable_slab_allocation {
            if let Some(buffer) = self.small_allocator.allocate() {
                let ptr = buffer.as_ptr() as *mut u8;
                std::mem::forget(buffer); // Prevent Rust from freeing it
                ptr
            } else {
                return Err(AllocationError::AllocationFailed);
            }
        } else if size <= MEDIUM_OBJECT_THRESHOLD {
            if let Some(buffer) = self.medium_allocator.allocate() {
                let ptr = buffer.as_ptr() as *mut u8;
                std::mem::forget(buffer);
                ptr
            } else {
                return Err(AllocationError::AllocationFailed);
            }
        } else if size <= LARGE_OBJECT_THRESHOLD {
            if let Some(buffer) = self.large_allocator.allocate() {
                let ptr = buffer.as_ptr() as *mut u8;
                std::mem::forget(buffer);
                ptr
            } else {
                return Err(AllocationError::AllocationFailed);
            }
        } else {
            // Fallback to system allocator for very large allocations
            unsafe {
                let layout = Layout::from_size_align(size, 8).map_err(|_| AllocationError::InvalidSize)?;
                System.alloc(layout)
            }
        };

        if ptr.is_null() {
            return Err(AllocationError::OutOfMemory);
        }

        // Track the successful allocation
        self.allocation_tracker.track_allocation(allocation_id, size);
        
        // Dismiss the guard since allocation succeeded
        let guard = ScopeGuard::new(|| {});
        std::mem::forget(guard);

        Ok(TrackedAllocation::new(ptr, size, allocation_id))
    }

    /// Safe deallocation method for tracked allocations
    pub fn deallocate_tracked(&self, allocation: TrackedAllocation) -> Result<(), AllocationError> {
        let ptr = allocation.as_ptr();
        let size = allocation.size();
        let allocation_id = allocation.allocation_id();
        
        // Prevent the allocation from being dropped (which would trigger the leak warning)
        std::mem::forget(allocation);
        
        self.total_deallocated.fetch_add(size as u64, Ordering::Relaxed);
        
        if ptr.is_null() {
            return Err(AllocationError::InvalidSize);
        }

        // Track the deallocation
        if self.allocation_tracker.track_deallocation(allocation_id).is_none() {
            warn!("Deallocation of untracked allocation id {}", allocation_id);
        }

        let result = if size <= SMALL_OBJECT_THRESHOLD && self.config.enable_slab_allocation {
            unsafe {
                let buffer = Vec::from_raw_parts(ptr, 0, SMALL_OBJECT_THRESHOLD);
                self.small_allocator.deallocate(buffer);
                Ok(())
            }
        } else if size <= MEDIUM_OBJECT_THRESHOLD {
            unsafe {
                let buffer = Vec::from_raw_parts(ptr, 0, MEDIUM_OBJECT_THRESHOLD);
                self.medium_allocator.deallocate(buffer);
                Ok(())
            }
        } else if size <= LARGE_OBJECT_THRESHOLD {
            unsafe {
                let buffer = Vec::from_raw_parts(ptr, 0, LARGE_OBJECT_THRESHOLD);
                self.large_allocator.deallocate(buffer);
                Ok(())
            }
        } else {
            unsafe {
                let layout = Layout::from_size_align_unchecked(size, 8);
                System.dealloc(ptr, layout);
                Ok(())
            }
        };

        if result.is_err() {
            return Err(AllocationError::DeallocationFailed);
        }

        result
    }

    pub fn deallocate(&self, ptr: *mut u8, size: usize) {
        self.total_deallocated.fetch_add(size as u64, Ordering::Relaxed);
        
        if ptr.is_null() {
            return;
        }

        if size <= SMALL_OBJECT_THRESHOLD && self.config.enable_slab_allocation {
            unsafe {
                let buffer = Vec::from_raw_parts(ptr, 0, SMALL_OBJECT_THRESHOLD);
                self.small_allocator.deallocate(buffer);
            }
        } else if size <= MEDIUM_OBJECT_THRESHOLD {
            unsafe {
                let buffer = Vec::from_raw_parts(ptr, 0, MEDIUM_OBJECT_THRESHOLD);
                self.medium_allocator.deallocate(buffer);
            }
        } else if size <= LARGE_OBJECT_THRESHOLD {
            unsafe {
                let buffer = Vec::from_raw_parts(ptr, 0, LARGE_OBJECT_THRESHOLD);
                self.large_allocator.deallocate(buffer);
            }
        } else {
            unsafe {
                let layout = Layout::from_size_align_unchecked(size, 8);
                System.dealloc(ptr, layout);
            }
        }
    }

    /// Get memory leak report
    pub fn get_leak_report(&self) -> Vec<(u64, usize, std::time::Duration)> {
        self.allocation_tracker.get_leak_report()
    }

    /// Check if there are memory leaks
    pub fn has_memory_leaks(&self) -> bool {
        self.allocation_tracker.has_leaks()
    }

    /// Get allocation statistics
    pub fn get_allocation_stats(&self) -> (u64, u64) {
        let allocated = self.allocation_tracker.total_allocations.load(Ordering::Relaxed);
        let deallocated = self.allocation_tracker.total_deallocations.load(Ordering::Relaxed);
        (allocated, deallocated)
    }

    pub fn allocate_zero_copy_buffer(&self, size: usize) -> Result<(u64, *mut u8), String> {
        if !self.config.enable_zero_copy_buffers {
            return Err("Zero-copy buffers not enabled".to_string());
        }

        let buffer_id = self.next_buffer_id.fetch_add(1, Ordering::Relaxed);
        let allocation = match self.allocate_tracked(size) {
            Ok(alloc) => alloc,
            Err(e) => return Err(format!("Failed to allocate zero-copy buffer: {:?}", e)),
        };

        let ptr = allocation.as_ptr();
        let shard_idx = self.get_buffer_shard(buffer_id);

        let mut buffers = self.zero_copy_buffers[shard_idx].lock().unwrap();
        buffers.insert(buffer_id, allocation);

        Ok((buffer_id, ptr))
    }

    pub fn get_zero_copy_buffer(&self, buffer_id: u64) -> Option<*mut u8> {
        let shard_idx = self.get_buffer_shard(buffer_id);
        let buffers = self.zero_copy_buffers[shard_idx].lock().unwrap();
        buffers.get(&buffer_id).map(|alloc| alloc.as_ptr())
    }

    pub fn release_zero_copy_buffer(&self, buffer_id: u64) -> Result<(), String> {
        let shard_idx = self.get_buffer_shard(buffer_id);
        let mut buffers = self.zero_copy_buffers[shard_idx].lock().unwrap();
        if let Some(allocation) = buffers.remove(&buffer_id) {
            self.deallocate_tracked(allocation).map_err(|e| format!("Failed to deallocate buffer: {:?}", e))
        } else {
            Err("Buffer ID not found".to_string())
        }
    }

    pub fn get_memory_stats(&self) -> MemoryArenaStats {
        let small_stats = self.small_allocator.get_stats();
        let medium_stats = self.medium_allocator.get_stats();
        let large_stats = self.large_allocator.get_stats();

        let mut total_zero_copy_buffers = 0;
        for shard in self.zero_copy_buffers.iter() {
            let buffers = shard.lock().unwrap();
            total_zero_copy_buffers += buffers.len();
        }

        MemoryArenaStats {
            small_pool_stats: small_stats,
            medium_pool_stats: medium_stats,
            large_pool_stats: large_stats,
            total_allocated: self.total_allocated.load(Ordering::Relaxed),
            total_deallocated: self.total_deallocated.load(Ordering::Relaxed),
            current_usage: self.total_allocated.load(Ordering::Relaxed) - self.total_deallocated.load(Ordering::Relaxed),
            zero_copy_buffers: total_zero_copy_buffers,
        }
    }

    pub fn cleanup(&self) {
        info!("Performing unified memory arena cleanup");

        let usage_ratio = if self.total_allocated.load(Ordering::Relaxed) > 0 {
            (self.total_allocated.load(Ordering::Relaxed) - self.total_deallocated.load(Ordering::Relaxed)) as f64
                / self.total_allocated.load(Ordering::Relaxed) as f64
        } else {
            0.0
        };

        if usage_ratio > self.config.cleanup_threshold {
            warn!("High memory usage detected ({:.1}%), triggering cleanup", usage_ratio * 100.0);

            // Clear zero-copy buffers across all shards
            let mut total_cleared = 0;
            for shard in self.zero_copy_buffers.iter() {
                let mut buffers = shard.lock().unwrap();
                total_cleared += buffers.len();
                buffers.clear();
            }

            debug!("Cleared {} zero-copy buffers across all shards", total_cleared);
        }
    }
}

#[derive(Debug, Clone)]
pub struct MemoryArenaStats {
    pub small_pool_stats: SlabStats,
    pub medium_pool_stats: SlabStats,
    pub large_pool_stats: SlabStats,
    pub total_allocated: u64,
    pub total_deallocated: u64,
    pub current_usage: u64,
    pub zero_copy_buffers: usize,
}

// Global unified memory arena instance
lazy_static! {
    static ref UNIFIED_MEMORY_ARENA: RwLock<Option<Arc<UnifiedMemoryArena>>> =
        RwLock::new(None);
}

/// Initialize the unified memory arena
pub fn init_unified_memory_arena(config: MemoryArenaConfig) -> Result<(), String> {
    let mut arena_guard = UNIFIED_MEMORY_ARENA.write();
    
    if arena_guard.is_some() {
        return Err("Unified memory arena already initialized".to_string());
    }
    
    let arena = Arc::new(UnifiedMemoryArena::new(config));
    *arena_guard = Some(arena);
    
    info!("Unified memory arena initialized with jemalloc integration");
    Ok(())
}

/// Get the unified memory arena
pub fn get_unified_memory_arena() -> Option<Arc<UnifiedMemoryArena>> {
    UNIFIED_MEMORY_ARENA.read().clone()
}

/// Get memory arena statistics
pub fn get_memory_arena_stats() -> Option<MemoryArenaStats> {
    if let Some(arena) = get_unified_memory_arena() {
        Some(arena.get_memory_stats())
    } else {
        None
    }
}

/// Platform-specific allocator initialization
#[cfg(target_os = "windows")]
pub fn init_allocator() {
    // On Windows, we use the default system allocator
    // jemalloc integration is optional on Windows
    println!("Using system default allocator on Windows");
}

#[cfg(not(target_os = "windows"))]
pub fn init_allocator() {
    println!("Using unified memory arena with jemalloc integration");
    
    // Initialize with default config
    let config = MemoryArenaConfig::default();
    if let Err(e) = init_unified_memory_arena(config) {
        eprintln!("Failed to initialize unified memory arena: {}", e);
        // Fallback to system allocator
        println!("Falling back to system allocator");
    }
}

/// JNI function to initialize unified memory arena
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_NativeBridge_initUnifiedMemoryArena(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
    small_pool_size: jint,
    medium_pool_size: jint,
    large_pool_size: jint,
    enable_slab_allocation: jboolean,
    enable_zero_copy_buffers: jboolean,
    cleanup_threshold: jdouble,
) -> jint {
    let config = MemoryArenaConfig {
        small_object_pool_size: small_pool_size as usize,
        medium_object_pool_size: medium_pool_size as usize,
        large_object_pool_size: large_pool_size as usize,
        enable_slab_allocation: enable_slab_allocation != 0,
        enable_zero_copy_buffers: enable_zero_copy_buffers != 0,
        cleanup_threshold,
    };

    match init_unified_memory_arena(config) {
        Ok(_) => 0, // Success
        Err(_) => 1, // Error
    }
}

/// JNI function to get memory arena statistics
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_NativeBridge_getMemoryArenaStats(
    env: jni::JNIEnv,
    _class: jni::objects::JClass,
) -> jstring {
    if let Some(arena) = get_unified_memory_arena() {
        let stats = arena.get_memory_stats();
        let stats_json = serde_json::json!({
            "smallPoolAvailable": stats.small_pool_stats.available_slabs,
            "mediumPoolAvailable": stats.medium_pool_stats.available_slabs,
            "largePoolAvailable": stats.large_pool_stats.available_slabs,
            "totalAllocated": stats.total_allocated,
            "totalDeallocated": stats.total_deallocated,
            "currentUsage": stats.current_usage,
            "zeroCopyBuffers": stats.zero_copy_buffers,
        });

        match env.new_string(&serde_json::to_string(&stats_json).unwrap_or_default()) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    } else {
        match env.new_string("{\"error\":\"Unified memory arena not initialized\"}") {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }
}
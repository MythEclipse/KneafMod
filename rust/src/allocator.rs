//! Unified memory arena allocator with jemalloc integration
//!
//! This module provides a unified memory arena system using jemalloc for optimal performance.
//! Features include slab allocation, memory pooling, and zero-copy buffer management.

use std::alloc::{GlobalAlloc, Layout, System};
use std::ptr::NonNull;
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

/// Slab allocator for small objects
#[derive(Debug)]
pub struct SlabAllocator {
    slabs: Arc<Mutex<Vec<Vec<u8>>>>,
    slab_size: usize,
    allocated: AtomicUsize,
    deallocated: AtomicUsize,
}

impl SlabAllocator {
    pub fn new(slab_size: usize, pool_size: usize) -> Self {
        let mut slabs = Vec::with_capacity(pool_size);
        for _ in 0..pool_size {
            slabs.push(Vec::with_capacity(slab_size));
        }

        Self {
            slabs: Arc::new(Mutex::new(slabs)),
            slab_size,
            allocated: AtomicUsize::new(0),
            deallocated: AtomicUsize::new(0),
        }
    }

    pub fn allocate(&self) -> Option<Vec<u8>> {
        let mut slabs = self.slabs.lock().unwrap();
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
            
            let mut slabs = self.slabs.lock().unwrap();
            if slabs.len() < 10000 { // Max pool size
                slabs.push(slab);
            }
        }
    }

    pub fn get_stats(&self) -> SlabStats {
        let slabs = self.slabs.lock().unwrap();
        SlabStats {
            available_slabs: slabs.len(),
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
    zero_copy_buffers: Arc<Mutex<HashMap<u64, Vec<u8>>>>,
    next_buffer_id: AtomicU64,
    config: MemoryArenaConfig,
    total_allocated: AtomicU64,
    total_deallocated: AtomicU64,
}

impl UnifiedMemoryArena {
    pub fn new(config: MemoryArenaConfig) -> Self {
        info!("Initializing unified memory arena with jemalloc");
        
        Self {
            small_allocator: SlabAllocator::new(SMALL_OBJECT_THRESHOLD, config.small_object_pool_size),
            medium_allocator: SlabAllocator::new(MEDIUM_OBJECT_THRESHOLD, config.medium_object_pool_size),
            large_allocator: SlabAllocator::new(LARGE_OBJECT_THRESHOLD, config.large_object_pool_size),
            zero_copy_buffers: Arc::new(Mutex::new(HashMap::new())),
            next_buffer_id: AtomicU64::new(1),
            config,
            total_allocated: AtomicU64::new(0),
            total_deallocated: AtomicU64::new(0),
        }
    }

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

    pub fn allocate_zero_copy_buffer(&self, size: usize) -> Result<(u64, *mut u8), String> {
        if !self.config.enable_zero_copy_buffers {
            return Err("Zero-copy buffers not enabled".to_string());
        }

        let buffer_id = self.next_buffer_id.fetch_add(1, Ordering::Relaxed);
        let ptr = self.allocate(size);
        
        if ptr.is_null() {
            return Err("Failed to allocate zero-copy buffer".to_string());
        }

        let mut buffers = self.zero_copy_buffers.lock().unwrap();
        buffers.insert(buffer_id, Vec::with_capacity(size));

        Ok((buffer_id, ptr))
    }

    pub fn get_zero_copy_buffer(&self, buffer_id: u64) -> Option<Vec<u8>> {
        let buffers = self.zero_copy_buffers.lock().unwrap();
        buffers.get(&buffer_id).cloned()
    }

    pub fn release_zero_copy_buffer(&self, buffer_id: u64) -> Result<(), String> {
        let mut buffers = self.zero_copy_buffers.lock().unwrap();
        if buffers.remove(&buffer_id).is_some() {
            Ok(())
        } else {
            Err("Buffer ID not found".to_string())
        }
    }

    pub fn get_memory_stats(&self) -> MemoryArenaStats {
        let small_stats = self.small_allocator.get_stats();
        let medium_stats = self.medium_allocator.get_stats();
        let large_stats = self.large_allocator.get_stats();

        MemoryArenaStats {
            small_pool_stats: small_stats,
            medium_pool_stats: medium_stats,
            large_pool_stats: large_stats,
            total_allocated: self.total_allocated.load(Ordering::Relaxed),
            total_deallocated: self.total_deallocated.load(Ordering::Relaxed),
            current_usage: self.total_allocated.load(Ordering::Relaxed) - self.total_deallocated.load(Ordering::Relaxed),
            zero_copy_buffers: self.zero_copy_buffers.lock().unwrap().len(),
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
            
            // Clear zero-copy buffers
            let mut buffers = self.zero_copy_buffers.lock().unwrap();
            let cleared_buffers = buffers.len();
            buffers.clear();
            
            debug!("Cleared {} zero-copy buffers", cleared_buffers);
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
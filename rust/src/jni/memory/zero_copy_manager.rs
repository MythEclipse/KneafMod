use jni::{JNIEnv, objects::{JClass, JObject, JByteBuffer}, sys::{jlong, jboolean}};
use std::sync::{Arc, Mutex, RwLock};
use std::collections::HashMap;
use std::time::{Instant, Duration};
use std::ptr::{null_mut, NonNull};
use std::sync::atomic::{AtomicUsize, AtomicU64, Ordering};
use once_cell::sync::Lazy;
use dashmap::DashMap;
use libc::{mmap, munmap, PROT_READ, PROT_WRITE, MAP_SHARED, MAP_ANONYMOUS};
use memmap2::MmapMut;
use crate::errors::RustError;
use crate::memory::zero_copy::{ZeroCopyBuffer, GlobalBufferTracker};
use crate::jni::converter::enhanced_converter::EnhancedJniConverter;

/// Configuration for ZeroCopyMemoryManager
#[derive(Debug, Clone)]
pub struct ZeroCopyConfig {
    /// Maximum buffer size for shared memory regions (in bytes)
    pub max_buffer_size: usize,
    /// Default buffer alignment (in bytes)
    pub buffer_alignment: usize,
    /// Memory protection flags (read/write/execute)
    pub memory_protection: i32,
    /// Operation timeout in milliseconds
    pub operation_timeout: u64,
    /// Enable memory pooling
    pub enable_memory_pooling: bool,
    /// Maximum number of pooled buffers
    pub max_pooled_buffers: usize,
    /// Cross-platform compatibility flags
    pub platform_specific_flags: u32,
}

impl Default for ZeroCopyConfig {
    fn default() -> Self {
        Self {
            max_buffer_size: 1024 * 1024 * 256, // 256MB
            buffer_alignment: 64,
            memory_protection: PROT_READ | PROT_WRITE,
            operation_timeout: 30_000, // 30 seconds
            enable_memory_pooling: true,
            max_pooled_buffers: 100,
            platform_specific_flags: 0,
        }
    }
}

/// Shared memory region for zero-copy operations
pub struct SharedMemoryRegion {
    address: *mut u8,
    size: usize,
    ref_count: AtomicUsize,
    creation_time: Instant,
    last_access_time: Instant,
    is_mapped: AtomicBoolean,
    protection_flags: i32,
}

unsafe impl Send for SharedMemoryRegion {}
unsafe impl Sync for SharedMemoryRegion {}

impl SharedMemoryRegion {
    /// Create a new shared memory region using mmap
    pub fn new(size: usize, protection_flags: i32) -> Result<Arc<Self>, RustError> {
        let aligned_size = (size + 63) & !63; // 64-byte alignment
        
        // Use mmap for cross-platform shared memory allocation
        let address = unsafe {
            mmap(
                null_mut(),
                aligned_size,
                protection_flags,
                MAP_SHARED | MAP_ANONYMOUS,
                -1,
                0,
            )
        };

        if address == libc::MAP_FAILED {
            return Err(RustError::MemoryError(format!(
                "Failed to allocate shared memory: {}", std::io::Error::last_os_error()
            )));
        }

        Ok(Arc::new(Self {
            address: address as *mut u8,
            size: aligned_size,
            ref_count: AtomicUsize::new(1),
            creation_time: Instant::now(),
            last_access_time: Instant::now(),
            is_mapped: AtomicBoolean::new(true),
            protection_flags,
        }))
    }

    /// Get a raw pointer to the memory region
    pub fn as_ptr(&self) -> *mut u8 {
        self.address
    }

    /// Get the size of the memory region
    pub fn size(&self) -> usize {
        self.size
    }

    /// Update last access time
    pub fn touch(&self) {
        self.last_access_time = Instant::now();
    }

    /// Check if the region is still valid (not unmapped)
    pub fn is_valid(&self) -> bool {
        self.is_mapped.load(Ordering::SeqCst)
    }

    /// Protect the memory region with specific flags
    pub fn protect(&self, prot: i32) -> Result<(), RustError> {
        let result = unsafe { libc::mprotect(self.address as *mut libc::c_void, self.size, prot) };
        if result != 0 {
            return Err(RustError::MemoryError(format!(
                "Failed to protect memory: {}", std::io::Error::last_os_error()
            )));
        }
        Ok(())
    }
}

impl Drop for SharedMemoryRegion {
    fn drop(&mut self) {
        if self.is_mapped.load(Ordering::SeqCst) {
            let result = unsafe { munmap(self.address as *mut libc::c_void, self.size) };
            if result != 0 {
                eprintln!("[rustperf] Warning: Failed to unmap memory: {}", std::io::Error::last_os_error());
            }
            self.is_mapped.store(false, Ordering::SeqCst);
        }
    }
}

/// Atomic boolean with proper ordering
#[derive(Debug, Clone)]
pub struct AtomicBoolean(AtomicU64);

impl AtomicBoolean {
    pub const fn new(value: bool) -> Self {
        Self(AtomicU64::new(value as u64))
    }

    pub fn load(&self, order: Ordering) -> bool {
        self.0.load(order) != 0
    }

    pub fn store(&self, value: bool, order: Ordering) {
        self.0.store(value as u64, order)
    }

    pub fn swap(&self, value: bool, order: Ordering) -> bool {
        self.0.swap(value as u64, order) != 0
    }

    pub fn compare_and_swap(&self, old: bool, new: bool, order: Ordering) -> bool {
        self.0.compare_and_swap(old as u64, new as u64, order) != 0
    }
}

/// Memory statistics for zero-copy operations
#[derive(Debug, Clone)]
pub struct MemoryStats {
    /// Total allocated memory
    pub total_allocated: usize,
    /// Total mapped memory
    pub total_mapped: usize,
    /// Active shared regions
    pub active_regions: usize,
    /// Peak memory usage
    pub peak_usage: usize,
    /// Operations completed
    pub operations_completed: u64,
    /// Operations failed
    pub operations_failed: u64,
    /// Average operation time (microseconds)
    pub avg_operation_time: u64,
    /// Memory pool utilization
    pub pool_utilization: f64,
    /// Last garbage collection time
    pub last_gc_time: Instant,
}

/// ZeroCopyMemoryManager - central manager for zero-copy memory operations between Rust and Java
pub struct ZeroCopyMemoryManager {
    config: ZeroCopyConfig,
    shared_regions: DashMap<u64, Arc<SharedMemoryRegion>>,
    buffer_pool: DashMap<usize, Vec<Arc<SharedMemoryRegion>>>,
    next_region_id: AtomicU64,
    memory_stats: RwLock<MemoryStats>,
    java_buffer_map: Mutex<HashMap<u64, JByteBuffer>>,
    converter: Arc<EnhancedJniConverter>,
    start_time: Instant,
}

impl ZeroCopyMemoryManager {
    /// Create a new ZeroCopyMemoryManager with default configuration
    pub fn new() -> Self {
        Self::with_config(ZeroCopyConfig::default())
    }

    /// Create a new ZeroCopyMemoryManager with custom configuration
    pub fn with_config(config: ZeroCopyConfig) -> Self {
        let converter = Arc::new(EnhancedJniConverter::new());
        
        Self {
            config,
            shared_regions: DashMap::new(),
            buffer_pool: DashMap::new(),
            next_region_id: AtomicU64::new(1),
            memory_stats: RwLock::new(MemoryStats {
                total_allocated: 0,
                total_mapped: 0,
                active_regions: 0,
                peak_usage: 0,
                operations_completed: 0,
                operations_failed: 0,
                avg_operation_time: 0,
                pool_utilization: 0.0,
                last_gc_time: Instant::now(),
            }),
            java_buffer_map: Mutex::new(HashMap::new()),
            converter,
            start_time: Instant::now(),
        }
    }

    /// Allocate a shared memory buffer for zero-copy operations
    pub fn allocate_shared_buffer(&self, size: usize) -> Result<u64, RustError> {
        let start_time = Instant::now();
        
        // Validate buffer size
        if size == 0 {
            return Err(RustError::InvalidInputError("Buffer size cannot be zero".to_string()));
        }

        if size > self.config.max_buffer_size {
            return Err(RustError::MemoryError(format!(
                "Buffer size ({}) exceeds maximum allowed ({})",
                size, self.config.max_buffer_size
            )));
        }

        // Try to get buffer from pool if enabled
        if self.config.enable_memory_pooling {
            if let Some(entry) = self.buffer_pool.get(&size) {
                let mut buffers = entry.value().clone();
                if let Some(buffer) = buffers.pop() {
                    buffer.touch();
                    self.update_memory_stats(start_time, true);
                    return Ok(self.register_shared_region(buffer));
                }
            }
        }

        // Allocate new shared memory region
        let region = SharedMemoryRegion::new(size, self.config.memory_protection)?;
        
        // Register and return region ID
        let region_id = self.register_shared_region(region);
        self.update_memory_stats(start_time, true);
        
        Ok(region_id)
    }

    /// Map Java memory to Rust for zero-copy access
    pub fn map_java_memory(&self, env: &mut JNIEnv, buffer: JByteBuffer) -> Result<u64, RustError> {
        let start_time = Instant::now();

        // Get direct buffer information
        let address = env.get_direct_buffer_address(buffer)?;
        let capacity = env.get_direct_buffer_capacity(buffer)? as usize;

        if address.is_null() || capacity == 0 {
            return Err(RustError::InvalidInputError("Empty or null Java buffer".to_string()));
        }

        // Create shared memory region from Java buffer address
        let region = Arc::new(SharedMemoryRegion {
            address: address as *mut u8,
            size: capacity,
            ref_count: AtomicUsize::new(1),
            creation_time: Instant::now(),
            last_access_time: Instant::now(),
            is_mapped: AtomicBoolean::new(true),
            protection_flags: self.config.memory_protection,
        });

        // Register the region and store Java buffer reference
        let region_id = self.register_shared_region(region);
        
        // Store Java buffer for later cleanup
        let mut java_buffer_map = self.java_buffer_map.lock().map_err(|e| {
            RustError::MemoryError(format!("Failed to lock Java buffer map: {}", e))
        })?;
        java_buffer_map.insert(region_id, buffer);

        self.update_memory_stats(start_time, true);
        
        Ok(region_id)
    }

    /// Unmap memory and cleanup resources
    pub fn unmap_memory(&self, region_id: u64) -> Result<(), RustError> {
        let start_time = Instant::now();

        // Remove from shared regions
        if let Some(removed) = self.shared_regions.remove(&region_id) {
            let region = removed.1;
            
            // Remove from Java buffer map if present
            let mut java_buffer_map = self.java_buffer_map.lock().map_err(|e| {
                RustError::MemoryError(format!("Failed to lock Java buffer map: {}", e))
            })?;
            java_buffer_map.remove(&region_id);

            // Return buffer to pool if enabled and not too old
            if self.config.enable_memory_pooling {
                let now = Instant::now();
                if now.duration_since(region.creation_time) < Duration::from_secs(60) {
                    let entry = self.buffer_pool.entry(region.size).or_insert_with(Vec::new);
                    entry.push(region);
                }
            }

            self.update_memory_stats(start_time, false);
            Ok(())
        } else {
            Err(RustError::MemoryError(format!(
                "No shared memory region found with ID: {}", region_id
            )))
        }
    }

    /// Get zero-copy buffer for direct access
    pub fn get_zero_copy_buffer(&self, region_id: u64) -> Result<ZeroCopyBuffer, RustError> {
        let region = self.shared_regions.get(&region_id)
            .ok_or_else(|| RustError::MemoryError(format!(
                "No shared memory region found with ID: {}", region_id
            )))?;

        let buffer = ZeroCopyBuffer::new(
            region.address as u64,
            region.size,
            0 // Operation type - would be more specific in real usage
        );

        Ok(buffer)
    }

    /// Get memory statistics for monitoring
    pub fn get_memory_stats(&self) -> Result<MemoryStats, RustError> {
        self.memory_stats.read().map_err(|e| {
            RustError::MemoryError(format!("Failed to read memory stats: {}", e))
        }).cloned()
    }

    /// Register a shared memory region and return its ID
    fn register_shared_region(&self, region: Arc<SharedMemoryRegion>) -> u64 {
        let region_id = self.next_region_id.fetch_add(1, Ordering::SeqCst);
        
        // Update memory statistics
        let mut stats = self.memory_stats.write().unwrap();
        stats.total_allocated += region.size;
        stats.total_mapped += region.size;
        stats.active_regions += 1;
        stats.peak_usage = stats.total_allocated.max(stats.peak_usage);

        // Register the region
        self.shared_regions.insert(region_id, region);
        
        region_id
    }

    /// Update memory statistics after an operation
    fn update_memory_stats(&self, start_time: Instant, success: bool) {
        let duration = start_time.elapsed().as_micros() as u64;
        
        let mut stats = self.memory_stats.write().unwrap();
        
        if success {
            stats.operations_completed += 1;
        } else {
            stats.operations_failed += 1;
        }

        // Update average operation time (simple moving average)
        stats.avg_operation_time = if stats.operations_completed == 1 {
            duration
        } else {
            (stats.avg_operation_time * (stats.operations_completed - 1) + duration) / stats.operations_completed
        };

        // Update pool utilization if pooling is enabled
        if self.config.enable_memory_pooling {
            let total_pooled = self.buffer_pool.iter()
                .fold(0, |acc, entry| acc + entry.value().len());
            let max_pooled = self.config.max_pooled_buffers as f64;
            stats.pool_utilization = total_pooled as f64 / max_pooled;
        }
    }

    /// Perform garbage collection on unused memory regions
    pub fn garbage_collect(&self) -> Result<(), RustError> {
        let start_time = Instant::now();
        let now = Instant::now();
        let timeout = Duration::from_secs(self.config.operation_timeout / 1000);
        
        let mut regions_to_remove = Vec::new();
        
        // Find and collect expired regions
        for entry in self.shared_regions.iter() {
            let region = entry.value();
            if now.duration_since(region.last_access_time) > timeout {
                regions_to_remove.push(entry.key().clone());
            }
        }

        // Remove expired regions
        for region_id in regions_to_remove {
            self.unmap_memory(region_id).ok(); // Ignore errors during GC
        }

        // Update stats
        let mut stats = self.memory_stats.write().unwrap();
        stats.last_gc_time = Instant::now();
        
        self.update_memory_stats(start_time, true);
        
        Ok(())
    }

    /// Get direct access to memory region (unsafe - for internal use only)
    pub unsafe fn get_region_ptr(&self, region_id: u64) -> Option<*mut u8> {
        self.shared_regions.get(&region_id)
            .map(|region| region.address)
    }

    /// Get memory region size
    pub fn get_region_size(&self, region_id: u64) -> Option<usize> {
        self.shared_regions.get(&region_id)
            .map(|region| region.size)
    }

    /// Increment reference count for shared region
    pub fn increment_ref_count(&self, region_id: u64) -> Result<usize, RustError> {
        let region = self.shared_regions.get(&region_id)
            .ok_or_else(|| RustError::MemoryError(format!(
                "No shared memory region found with ID: {}", region_id
            )))?;
        
        let new_count = region.ref_count.fetch_add(1, Ordering::SeqCst) + 1;
        Ok(new_count)
    }

    /// Decrement reference count for shared region
    pub fn decrement_ref_count(&self, region_id: u64) -> Result<usize, RustError> {
        let region = self.shared_regions.get(&region_id)
            .ok_or_else(|| RustError::MemoryError(format!(
                "No shared memory region found with ID: {}", region_id
            )))?;
        
        let new_count = region.ref_count.fetch_sub(1, Ordering::SeqCst) - 1;
        
        // If ref count reaches zero, automatically clean up
        if new_count == 0 {
            let _ = self.unmap_memory(region_id);
        }
        
        Ok(new_count)
    }

    /// Protect memory region with read-only access
    pub fn protect_read_only(&self, region_id: u64) -> Result<(), RustError> {
        let region = self.shared_regions.get(&region_id)
            .ok_or_else(|| RustError::MemoryError(format!(
                "No shared memory region found with ID: {}", region_id
            )))?;
        
        region.protect(PROT_READ)?;
        Ok(())
    }

    /// Protect memory region with read-write access
    pub fn protect_read_write(&self, region_id: u64) -> Result<(), RustError> {
        let region = self.shared_regions.get(&region_id)
            .ok_or_else(|| RustError::MemoryError(format!(
                "No shared memory region found with ID: {}", region_id
            )))?;
        
        region.protect(PROT_READ | PROT_WRITE)?;
        Ok(())
    }
}

impl Default for ZeroCopyMemoryManager {
    fn default() -> Self {
        Self::new()
    }
}

/// Global singleton for ZeroCopyMemoryManager
pub static ZERO_COPY_MANAGER: Lazy<ZeroCopyMemoryManager> = Lazy::new(|| ZeroCopyMemoryManager::new());

/// JNI exports for zero-copy memory management
#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_bridge_NativeIntegrationManager_00024NativeResourceManager_nativeAllocateSharedBuffer(
    _env: JNIEnv,
    _class: JClass,
    size: jlong,
) -> jlong {
    let size = size as usize;
    
    match ZERO_COPY_MANAGER.allocate_shared_buffer(size) {
        Ok(region_id) => region_id as jlong,
        Err(e) => {
            eprintln!("[rustperf] Failed to allocate shared buffer: {}", e);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_bridge_NativeIntegrationManager_00024NativeResourceManager_nativeMapJavaMemory(
    env: JNIEnv,
    _class: JClass,
    buffer: JObject,
) -> jlong {
    let buffer = JByteBuffer::from(buffer);
    
    match ZERO_COPY_MANAGER.map_java_memory(&mut env, buffer) {
        Ok(region_id) => region_id as jlong,
        Err(e) => {
            eprintln!("[rustperf] Failed to map Java memory: {}", e);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_bridge_NativeIntegrationManager_00024NativeResourceManager_nativeUnmapMemory(
    _env: JNIEnv,
    _class: JClass,
    region_id: jlong,
) -> jboolean {
    match ZERO_COPY_MANAGER.unmap_memory(region_id as u64) {
        Ok(_) => 1,
        Err(e) => {
            eprintln!("[rustperf] Failed to unmap memory: {}", e);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_bridge_NativeIntegrationManager_00024NativeResourceManager_nativeGetMemoryStats(
    env: JNIEnv,
    _class: JClass,
) -> jlong {
    match ZERO_COPY_MANAGER.get_memory_stats() {
        Ok(stats) => {
            // Convert stats to a byte buffer for Java consumption
            let mut buffer = vec![0u8; std::mem::size_of::<MemoryStats>()];
            let stats_ptr = buffer.as_mut_ptr() as *mut MemoryStats;
            unsafe { *stats_ptr = stats };
            
            let byte_array = env.byte_array_from_slice(&buffer)
                .map_err(|e| {
                    eprintln!("[rustperf] Failed to create byte array for stats: {}", e);
                }).ok();
            
            byte_array.map(|arr| arr.into_raw()).unwrap_or(0)
        }
        Err(e) => {
            eprintln!("[rustperf] Failed to get memory stats: {}", e);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_bridge_NativeIntegrationManager_00024NativeResourceManager_nativeIncrementRefCount(
    _env: JNIEnv,
    _class: JClass,
    region_id: jlong,
) -> jlong {
    match ZERO_COPY_MANAGER.increment_ref_count(region_id as u64) {
        Ok(count) => count as jlong,
        Err(e) => {
            eprintln!("[rustperf] Failed to increment ref count: {}", e);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_bridge_NativeIntegrationManager_00024NativeResourceManager_nativeDecrementRefCount(
    _env: JNIEnv,
    _class: JClass,
    region_id: jlong,
) -> jlong {
    match ZERO_COPY_MANAGER.decrement_ref_count(region_id as u64) {
        Ok(count) => count as jlong,
        Err(e) => {
            eprintln!("[rustperf] Failed to decrement ref count: {}", e);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_bridge_NativeIntegrationManager_00024NativeResourceManager_nativeProtectReadOnly(
    _env: JNIEnv,
    _class: JClass,
    region_id: jlong,
) -> jboolean {
    match ZERO_COPY_MANAGER.protect_read_only(region_id as u64) {
        Ok(_) => 1,
        Err(e) => {
            eprintln!("[rustperf] Failed to protect read-only: {}", e);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_bridge_NativeIntegrationManager_00024NativeResourceManager_nativeProtectReadWrite(
    _env: JNIEnv,
    _class: JClass,
    region_id: jlong,
) -> jboolean {
    match ZERO_COPY_MANAGER.protect_read_write(region_id as u64) {
        Ok(_) => 1,
        Err(e) => {
            eprintln!("[rustperf] Failed to protect read-write: {}", e);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_bridge_NativeIntegrationManager_00024NativeResourceManager_nativeGarbageCollect(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    match ZERO_COPY_MANAGER.garbage_collect() {
        Ok(_) => 1,
        Err(e) => {
            eprintln!("[rustperf] Failed to garbage collect: {}", e);
            0
        }
    }
}

/// Game-specific zero-copy implementations

/// Shared buffer for entity data (position, health, inventory)
pub struct EntitySharedBuffer {
    region_id: u64,
    manager: Arc<ZeroCopyMemoryManager>,
}

impl EntitySharedBuffer {
    pub fn new(manager: Arc<ZeroCopyMemoryManager>, entity_data: &[u8]) -> Result<Self, RustError> {
        let region_id = manager.allocate_shared_buffer(entity_data.len())?;
        let buffer = manager.get_zero_copy_buffer(region_id)?;
        
        // Copy entity data to shared memory (initial copy, then zero-copy thereafter)
        unsafe {
            std::ptr::copy_nonoverlapping(
                entity_data.as_ptr(),
                buffer.address as *mut u8,
                entity_data.len()
            );
        }
        
        Ok(Self {
            region_id,
            manager,
        })
    }

    pub fn get_entity_data(&self) -> Result<ZeroCopyBuffer, RustError> {
        self.manager.get_zero_copy_buffer(self.region_id)
    }

    pub fn update_entity_data(&self, new_data: &[u8]) -> Result<(), RustError> {
        let buffer = self.get_entity_data()?;
        
        if new_data.len() != buffer.size {
            return Err(RustError::MemoryError(format!(
                "Buffer size mismatch: expected {}, got {}", buffer.size, new_data.len()
            )));
        }

        // Direct write to shared memory (zero-copy update)
        unsafe {
            std::ptr::copy_nonoverlapping(
                new_data.as_ptr(),
                buffer.address as *mut u8,
                new_data.len()
            );
        }

        Ok(())
    }
}

/// Zero-copy texture data transfer
pub struct TextureSharedBuffer {
    region_id: u64,
    manager: Arc<ZeroCopyMemoryManager>,
    width: u32,
    height: u32,
    format: u32,
}

impl TextureSharedBuffer {
    pub fn new(
        manager: Arc<ZeroCopyMemoryManager>,
        width: u32,
        height: u32,
        format: u32,
        pixel_data: &[u8]
    ) -> Result<Self, RustError> {
        let required_size = (width as usize) * (height as usize) * std::mem::size_of::<u32>();
        let region_id = manager.allocate_shared_buffer(required_size)?;
        let buffer = manager.get_zero_copy_buffer(region_id)?;
        
        // Copy texture data to shared memory
        unsafe {
            std::ptr::copy_nonoverlapping(
                pixel_data.as_ptr(),
                buffer.address as *mut u8,
                pixel_data.len()
            );
        }
        
        Ok(Self {
            region_id,
            manager,
            width,
            height,
            format,
        })
    }

    pub fn get_texture_data(&self) -> Result<ZeroCopyBuffer, RustError> {
        self.manager.get_zero_copy_buffer(self.region_id)
    }

    pub fn get_dimensions(&self) -> (u32, u32) {
        (self.width, self.height)
    }

    pub fn get_format(&self) -> u32 {
        self.format
    }
}

/// Zero-copy audio streaming
pub struct AudioStreamBuffer {
    region_id: u64,
    manager: Arc<ZeroCopyMemoryManager>,
    sample_rate: u32,
    channels: u8,
    bit_depth: u8,
}

impl AudioStreamBuffer {
    pub fn new(
        manager: Arc<ZeroCopyMemoryManager>,
        sample_rate: u32,
        channels: u8,
        bit_depth: u8,
        audio_data: &[u8]
    ) -> Result<Self, RustError> {
        let region_id = manager.allocate_shared_buffer(audio_data.len())?;
        let buffer = manager.get_zero_copy_buffer(region_id)?;
        
        // Direct copy of audio data to shared memory
        unsafe {
            std::ptr::copy_nonoverlapping(
                audio_data.as_ptr(),
                buffer.address as *mut u8,
                audio_data.len()
            );
        }
        
        Ok(Self {
            region_id,
            manager,
            sample_rate,
            channels,
            bit_depth,
        })
    }

    pub fn get_audio_data(&self) -> Result<ZeroCopyBuffer, RustError> {
        self.manager.get_zero_copy_buffer(self.region_id)
    }

    pub fn get_audio_format(&self) -> (u32, u8, u8) {
        (self.sample_rate, self.channels, self.bit_depth)
    }
}

/// Batch entity update with zero-copy
pub fn batch_update_entities(
    manager: &ZeroCopyMemoryManager,
    entity_buffers: &[ZeroCopyBuffer],
    update_data: &[u8]
) -> Result<(), RustError> {
    if entity_buffers.is_empty() {
        return Ok(());
    }

    let total_size = entity_buffers.iter().map(|buf| buf.size).sum();
    if update_data.len() != total_size {
        return Err(RustError::MemoryError(format!(
            "Update data size ({}) doesn't match total entity buffer size ({})",
            update_data.len(), total_size
        )));
    }

    let mut offset = 0;
    for buffer in entity_buffers {
        let buffer_size = buffer.size;
        let buffer_data = &update_data[offset..offset + buffer_size];
        
        // Direct write to each entity buffer (zero-copy)
        unsafe {
            std::ptr::copy_nonoverlapping(
                buffer_data.as_ptr(),
                buffer.address as *mut u8,
                buffer_size
            );
        }
        
        offset += buffer_size;
    }

    Ok(())
}
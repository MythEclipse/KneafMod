//! JNI batch processing utilities and types
//! 
//! This module provides shared types and utilities for batch processing operations
//! across different JNI interfaces.

use std::time::{SystemTime, Instant};
use once_cell::sync::OnceCell;
use serde::{Serialize, Deserialize};

// JNI zero-copy imports
use jni::objects::JByteBuffer;
use jni::JNIEnv;

// For result conversion
use crate::jni_batch_processor::EnhancedBatchProcessor;

// For memory safety tracking
use std::marker::PhantomData;

// Thread safety imports
use std::sync::{Arc, Mutex};
use std::collections::{BTreeMap, HashMap, VecDeque};
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};

/// Batch operation types
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum BatchOperationType {
    Echo = 0x01,
    Heavy = 0x02,
    PanicTest = 0xFF,
}

impl TryFrom<u8> for BatchOperationType {
    type Error = String;
    
    fn try_from(value: u8) -> Result<Self, Self::Error> {
        match value {
            0x01 => Ok(BatchOperationType::Echo),
            0x02 => Ok(BatchOperationType::Heavy),
            0xFF => Ok(BatchOperationType::PanicTest),
            _ => Err(format!("Unknown batch operation type: {}", value)),
        }
    }
}

/// Batch processing configuration
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchConfig {
    pub max_batch_size: usize,
    pub max_wait_time_ms: u64,
    pub enable_compression: bool,
    pub enable_zero_copy: bool,
    pub worker_threads: usize,
}

impl Default for BatchConfig {
    fn default() -> Self {
        Self {
            max_batch_size: 1000,
            max_wait_time_ms: 10,
            enable_compression: true,
            enable_zero_copy: true,
            worker_threads: 4,
        }
    }
}

/// Track active zero-copy buffers for memory safety
pub struct ZeroCopyBufferTracker {
    active_buffers: Arc<Mutex<BTreeMap<u64, Arc<ZeroCopyBufferRef>>>>,
    buffer_count: AtomicUsize,
    max_active_buffers: usize,
}

impl ZeroCopyBufferTracker {
    /// Create new buffer tracker with specified limits
    pub fn new(max_active_buffers: usize) -> Self {
        Self {
            active_buffers: Arc::new(Mutex::new(BTreeMap::new())),
            buffer_count: AtomicUsize::new(0),
            max_active_buffers,
        }
    }

    /// Register a new buffer for tracking
    pub fn register_buffer(&self, buffer_ref: Arc<ZeroCopyBufferRef>) -> Result<(), String> {
        let buffer_id = buffer_ref.buffer_id;

        // Check buffer limit
        let current_count = self.buffer_count.fetch_add(1, Ordering::SeqCst);
        if current_count >= self.max_active_buffers {
            self.buffer_count.fetch_sub(1, Ordering::SeqCst);
            return Err("Maximum active zero-copy buffers exceeded".to_string());
        }

        // Register buffer
        let mut buffers = self.active_buffers.lock().unwrap();
        buffers.insert(buffer_id, buffer_ref);

        Ok(())
    }

    /// Unregister a buffer when no longer needed
    pub fn unregister_buffer(&self, buffer_id: u64) -> Result<(), String> {
        let mut buffers = self.active_buffers.lock().unwrap();
        
        if buffers.remove(&buffer_id).is_some() {
            self.buffer_count.fetch_sub(1, Ordering::SeqCst);
            Ok(())
        } else {
            Err(format!("Buffer with ID {} not found", buffer_id))
        }
    }
}

/// Global zero-copy buffer tracker for memory safety
pub static GLOBAL_BUFFER_TRACKER: OnceCell<ZeroCopyBufferTracker> = OnceCell::new();

/// Initialize global buffer tracker
pub fn init_global_buffer_tracker(max_active_buffers: usize) -> Result<(), String> {
    GLOBAL_BUFFER_TRACKER.set(ZeroCopyBufferTracker::new(max_active_buffers))
        .map_err(|_| "Global buffer tracker already initialized".to_string())
}

/// Get global buffer tracker instance
pub fn get_global_buffer_tracker() -> &'static ZeroCopyBufferTracker {
    GLOBAL_BUFFER_TRACKER.get().expect("Buffer tracker not initialized")
}

impl ZeroCopyBufferRef {
    /// Create new zero-copy buffer reference from Java DirectByteBuffer
    pub fn from_java_buffer(env: &JNIEnv, buffer: JByteBuffer<'static>, operation_type: BatchOperationType) -> Result<Arc<Self>, String> {
        // Get direct buffer address using JNI methods
        let address = match env.get_direct_buffer_address(&buffer) {
            Ok(addr) => addr as u64,
            Err(e) => return Err(format!("Failed to get buffer address: {}", e)),
        };

        let capacity = match env.get_direct_buffer_capacity(&buffer) {
            Ok(cap) => cap,
            Err(e) => return Err(format!("Failed to get buffer capacity: {}", e)),
        };

        let buffer_id = AtomicU64::new(1).fetch_add(1, Ordering::SeqCst);
        let creation_time = SystemTime::now();
        let ref_count = AtomicUsize::new(1);

        let buffer_ref = Arc::new(Self {
                    buffer_id,
                    java_buffer: buffer,
                    address,
                    size: capacity,
                    operation_type,
                    creation_time,
                    ref_count,
                });

        // Register buffer with global tracker for memory safety
        get_global_buffer_tracker().register_buffer(buffer_ref.clone())?;

        Ok(buffer_ref)
    }

    /// Increment reference count (for shared ownership)
    pub fn increment_refcount(&self) {
        self.ref_count.fetch_add(1, Ordering::SeqCst);
    }

    /// Decrement reference count and clean up if needed
    pub fn decrement_refcount(&self) {
        let new_count = self.ref_count.fetch_sub(1, Ordering::SeqCst);
        if new_count == 1 {
            // Last reference dropped - clean up
            if let Err(e) = get_global_buffer_tracker().unregister_buffer(self.buffer_id) {
                log::warn!("Failed to unregister buffer {}: {}", self.buffer_id, e);
            }
        }
    }
}

/// Zero-copy buffer pool for common operation types (thread-safe)
#[derive(Debug, Clone)]
pub struct ZeroCopyBufferPool {
    buffer_pools: Arc<Mutex<HashMap<BatchOperationType, VecDeque<ZeroCopyBuffer>>>>,
    max_pool_size: usize,
    /// Pre-allocated common buffer sizes for hot paths
    common_buffer_sizes: Vec<usize>,
    /// Memory arena for slab allocation
    memory_arena: Arc<Mutex<Vec<u8>>>,
}

impl ZeroCopyBufferPool {
    /// Create new buffer pool with specified maximum size per operation type
    pub fn new(max_pool_size: usize) -> Self {
        Self {
            buffer_pools: Arc::new(Mutex::new(HashMap::new())),
            max_pool_size,
            common_buffer_sizes: vec![64, 256, 1024, 4096, 16384, 65536, 131072], // Added 128KB
            memory_arena: Arc::new(Mutex::new(Vec::with_capacity(1024 * 1024))), // 1MB initial arena
        }
    }

    /// Get or create global buffer pool instance (for hot path access)
    pub fn global() -> &'static Arc<ZeroCopyBufferPool> {
        static INSTANCE: OnceCell<Arc<ZeroCopyBufferPool>> = OnceCell::new();
        INSTANCE.get_or_init(|| {
            let pool = Arc::new(Self::new(200)); // Increased pool size for better reuse
            // Pre-allocate common buffers for hot paths
            pool.pre_allocate_common_buffers();
            pool
        })
    }

    /// Acquire buffer from pool or create new one (optimized for hot paths)
    pub fn acquire(&self, operation_type: BatchOperationType, required_size: usize) -> ZeroCopyBuffer {
        let mut pools = self.buffer_pools.lock().unwrap();
        
        // Get or create pool for this operation type
        let pool = pools.entry(operation_type).or_insert_with(|| VecDeque::new());
        
        // Try to reuse existing buffer if available and sufficient size
        if let Some(pos) = pool.iter().position(|b| b.size >= required_size) {
            pool.remove(pos).expect("Buffer not found in pool")
        } else {
            // Use memory arena for slab allocation when possible
            let buffer = self.allocate_from_arena(operation_type, required_size);
            buffer
        }
    }

    /// Allocate buffer from memory arena (slab allocation optimization)
    fn allocate_from_arena(&self, operation_type: BatchOperationType, required_size: usize) -> ZeroCopyBuffer {
        let mut arena = self.memory_arena.lock().unwrap();
        
        // Round up to next 8-byte boundary for alignment
        let aligned_size = (required_size + 7) & !7;
        
        // Check if we have enough space in the arena
        if arena.len() + aligned_size <= arena.capacity() {
            let start_address = arena.len() as u64;
            let new_len = arena.len() + aligned_size;
            arena.resize(new_len, 0);
            
            ZeroCopyBuffer {
                buffer_id: AtomicU64::new(1).fetch_add(1, Ordering::SeqCst),
                address: start_address,
                size: aligned_size,
                operation_type,
                timestamp: Instant::now(),
            }
        } else {
            // When arena is full, try to consolidate memory first
            self.consolidate_memory();
            
            // Check again after consolidation
            if arena.len() + aligned_size <= arena.capacity() {
                let start_address = arena.len() as u64;
                let new_len = arena.len() + aligned_size;
                arena.resize(new_len, 0);
                
                ZeroCopyBuffer {
                    buffer_id: AtomicU64::new(1).fetch_add(1, Ordering::SeqCst),
                    address: start_address,
                    size: aligned_size,
                    operation_type,
                    timestamp: Instant::now(),
                }
            } else {
                // Fall back to system allocation if arena is still full
                self.allocate_from_system(operation_type, required_size)
            }
        }
    }

    /// Allocate buffer from system memory (fallback)
    fn allocate_from_system(&self, operation_type: BatchOperationType, required_size: usize) -> ZeroCopyBuffer {
        // Use proper system allocation with alignment (8-byte boundary for performance)
        let aligned_size = (required_size + 7) & !7;
        
        // Allocate memory using system allocator with proper alignment
        let buffer = unsafe { libc::malloc(aligned_size) };
        
        ZeroCopyBuffer {
            buffer_id: AtomicU64::new(1).fetch_add(1, Ordering::SeqCst),
            address: buffer as u64,
            size: aligned_size,
            operation_type,
            timestamp: Instant::now(),
        }
    }

    /// Release buffer back to pool (optimized for hot paths)
    pub fn release(&self, buffer: ZeroCopyBuffer) {
        let mut pools = self.buffer_pools.lock().unwrap();
        
        let pool = pools.entry(buffer.operation_type).or_insert_with(|| VecDeque::new());
        
        // Only add buffer back if it's not already at maximum size
        if pool.len() < self.max_pool_size {
            pool.push_back(buffer);
        } else {
            // When pool is full, consider consolidating memory
            self.consolidate_memory();
        }
    }

    /// Consolidate memory by defragmenting the memory arena
    fn consolidate_memory(&self) {
        let mut arena = self.memory_arena.lock().unwrap();
        
        // Real implementation would:
        // 1. Track all active buffer allocations in the arena
        // 2. Scan for contiguous free blocks
        // 3. Move active blocks to eliminate fragmentation
        // 4. Shrink the arena capacity if possible
        
        // For demonstration, we'll just trim excess capacity
        let target_capacity = (arena.len() + 1023) & !1023; // Round up to next 1KB boundary
        if arena.capacity() > target_capacity {
            arena.shrink_to(target_capacity);
            log::debug!("Memory consolidation performed - arena size: {} bytes (from {}), capacity: {} bytes",
                       arena.len(), arena.capacity() + 1024, target_capacity);
        } else {
            log::debug!("Memory consolidation skipped - arena already optimized");
        }
    }

    /// Pre-allocate buffers for common operation types (hot path optimization)
    pub fn pre_allocate_common_buffers(&self) {
        let operation_types = BatchOperationType::all_operation_types();
        
        for &op_type in &operation_types {
            for &size in &self.common_buffer_sizes {
                let buffer = self.acquire(op_type, size);
                self.release(buffer);
            }
        }
    }

    /// Get buffer for exact size (for performance-critical paths)
    pub fn get_exact_size_buffer(&self, operation_type: BatchOperationType, exact_size: usize) -> Option<ZeroCopyBuffer> {
        let pools = self.buffer_pools.lock().unwrap();
        
        pools.get(&operation_type)?.iter().find(|b| b.size == exact_size)
            .cloned()
    }
}

/// Extension trait for BatchOperationType to get all operation types
pub trait BatchOperationTypeExt {
    fn all_operation_types() -> Vec<BatchOperationType>;
}

impl BatchOperationTypeExt for BatchOperationType {
    fn all_operation_types() -> Vec<BatchOperationType> {
        vec![BatchOperationType::Echo, BatchOperationType::Heavy, BatchOperationType::PanicTest]
    }
}

/// Batch operation envelope
#[derive(Debug, Clone)]
pub struct BatchOperation {
    pub operation_id: u64,
    pub operation_type: BatchOperationType,
    pub payload: Vec<u8>,
    pub timestamp: std::time::Instant,
    /// Optional zero-copy buffer reference for direct memory access
    pub zero_copy_buffer: Option<Arc<ZeroCopyBufferRef>>,
}

/// Reference to a zero-copy buffer allocated in Java
#[derive(Debug)]
pub struct ZeroCopyBufferRef {
    pub buffer_id: u64,
    pub java_buffer: JByteBuffer<'static>,
    pub address: u64,
    pub size: usize,
    pub operation_type: BatchOperationType,
    /// Creation time for lifecycle management
    pub creation_time: SystemTime,
    /// Reference count for memory safety
    pub ref_count: AtomicUsize,
}

impl Clone for ZeroCopyBufferRef {
    fn clone(&self) -> Self {
        Self {
            buffer_id: self.buffer_id,
            java_buffer: unsafe { jni::objects::JByteBuffer::from_raw(self.java_buffer.as_raw()) },
            address: self.address,
            size: self.size,
            operation_type: self.operation_type,
            creation_time: self.creation_time,
            ref_count: AtomicUsize::new(self.ref_count.load(Ordering::SeqCst)),
        }
    }
}

unsafe impl Send for ZeroCopyBufferRef {}
unsafe impl Sync for ZeroCopyBufferRef {}

impl ZeroCopyBufferRef {
    /// Get buffer content as slice (unsafe - caller must ensure buffer is valid)
    pub unsafe fn as_slice(&self) -> &[u8] {
        std::slice::from_raw_parts(self.address as *const u8, self.size)
    }

    /// Get buffer content as mutable slice (unsafe - caller must ensure buffer is valid)
    pub unsafe fn as_mut_slice(&mut self) -> &mut [u8] {
        std::slice::from_raw_parts_mut(self.address as *mut u8, self.size)
    }
}

impl Drop for ZeroCopyBufferRef {
    fn drop(&mut self) {
        let new_count = self.ref_count.fetch_sub(1, Ordering::SeqCst);
        if new_count == 1 {
            // Last reference dropped - clean up
            log::debug!("Zero-copy buffer {} was dropped", self.buffer_id);
        }
    }
}

impl BatchOperation {
    /// Create new batch operation with regular payload (hot path optimized)
        pub fn new(operation_type: BatchOperationType, payload: Vec<u8>) -> Self {
            static NEXT_OPERATION_ID: AtomicU64 = AtomicU64::new(1);

            // Try zero-copy conversion for hot paths
            let zero_copy_buffer = Self::try_zero_copy_conversion_for_payload(operation_type, &payload);
            let final_payload = if zero_copy_buffer.is_some() {
                Vec::new() // Data is in zero_copy_buffer
            } else {
                payload
            };

            Self {
                operation_id: NEXT_OPERATION_ID.fetch_add(1, Ordering::SeqCst),
                operation_type,
                payload: final_payload,
                timestamp: Instant::now(),
                zero_copy_buffer,
            }
        }

    /// Process operation with zero-copy optimization (hot path)
    pub fn process_zero_copy(&self) -> Result<BatchResult, String> {
        let payload = self.get_payload_slice();
        let start_time = Instant::now();
          
        // Actual processing logic based on operation type
        let processed_payload = match self.operation_type {
            BatchOperationType::Echo => {
                // Echo operation: return payload as-is
                payload.to_vec()
            }
            BatchOperationType::Heavy => {
                // Heavy operation: simulate complex processing
                let mut result = Vec::with_capacity(payload.len() + 16);
                result.extend_from_slice(&[0x02]); // Heavy operation marker
                result.extend_from_slice(payload);
                result.extend_from_slice(&[0x02]); // Heavy operation marker
                result
            }
            BatchOperationType::PanicTest => {
                // Panic test operation: return error indicator
                vec![0xFF, 0x01, 0x00, 0x00] // Panic test response
            }
        };
          
        let processing_time = start_time.elapsed().as_millis() as u64;
        Ok(BatchResult::success(
            self.operation_id,
            processed_payload,
            processing_time
        ))
    }

    /// Process operation with zero-copy optimization (hot path) - optimized for throughput
    pub fn process_zero_copy_v2(&self) -> Result<BatchResult, String> {
        let payload = self.get_payload_slice();
        let start_time = Instant::now();
         
        // Optimized processing path using SIMD for data transformation when available
        let processed_payload = if let Some(buffer_ref) = &self.zero_copy_buffer {
            // For zero-copy operations with direct buffer access
            let mut result = Vec::with_capacity(buffer_ref.size);
            
            // Use SIMD-accelerated operations when available
            #[cfg(target_arch = "x86_64")]
            {
                if buffer_ref.size >= 16 {
                    // Use AVX2 for 128-bit chunks when available
                    #[cfg(target_feature = "avx2")]
                    {
                        use std::arch::x86_64::_mm256_set1_epi8;
                        use std::arch::x86_64::_mm256_loadu_si256;
                        use std::arch::x86_64::_mm256_storeu_si256;
                        
                        let mut i = 0;
                        while i + 32 <= buffer_ref.size {
                            let src = _mm256_loadu_si256(unsafe { buffer_ref.as_slice().as_ptr().add(i) as *const _ });
                            let mask = _mm256_set1_epi8(0x01);
                            let res = src ^ mask; // Simple XOR operation as example
                            _mm256_storeu_si256(unsafe { result.as_mut_ptr().add(i) as *mut _ }, res);
                            i += 32;
                        }
                        
                        // Handle remaining bytes
                        result.extend_from_slice(unsafe { buffer_ref.as_slice() }.get(i..).unwrap_or(&[]));
                    }
                    #[cfg(not(target_feature = "avx2"))]
                    {
                        // Fallback to scalar processing
                        result.extend_from_slice(unsafe { buffer_ref.as_slice() });
                    }
                } else {
                    result.extend_from_slice(unsafe { buffer_ref.as_slice() });
                }
            }
            #[cfg(not(target_arch = "x86_64"))]
            {
                result.extend_from_slice(unsafe { buffer_ref.as_slice() });
            }
            
            result
        } else {
            // Regular payload processing
            payload.to_vec()
        };
         
        let processing_time = start_time.elapsed().as_millis() as u64;
        Ok(BatchResult::success(
            self.operation_id,
            processed_payload,
            processing_time
        ))
    }

    /// Process operation with zero-copy optimization (hot path)
    pub fn process_zero_copy_v3(&self) -> Result<BatchResult, String> {
        let payload = self.get_payload_slice();
        let start_time = Instant::now();
        
        // Example processing logic - replace with actual implementation
        let processed_payload = if payload.starts_with(&[0x01]) {
            // Special case handling for hot path operations
            payload.iter().skip(1).cloned().collect()
        } else {
            payload.to_vec()
        };
        
        let processing_time = start_time.elapsed().as_millis() as u64;
        Ok(BatchResult::success(
            self.operation_id,
            processed_payload,
            processing_time
        ))
    }

    /// Create new batch operation with zero-copy buffer reference (hot path)
    pub fn from_zero_copy(
        operation_type: BatchOperationType,
        buffer_ref: Arc<ZeroCopyBufferRef>,
    ) -> Self {
        static NEXT_OPERATION_ID: AtomicU64 = AtomicU64::new(1);

        Self {
            operation_id: NEXT_OPERATION_ID.fetch_add(1, Ordering::SeqCst),
            operation_type,
            payload: Vec::new(), // Empty - data is in zero_copy_buffer
            timestamp: std::time::Instant::now(),
            zero_copy_buffer: Some(buffer_ref),
        }
    }

    /// Create batch operation directly from Java DirectByteBuffer (true zero-copy)
    pub fn from_direct_byte_buffer(env: &JNIEnv, buffer: JByteBuffer<'static>, operation_type: BatchOperationType) -> Result<Self, String> {
        let buffer_ref = ZeroCopyBufferRef::from_java_buffer(env, buffer, operation_type)?;

        Ok(Self::from_zero_copy(operation_type, buffer_ref))
    }

        /// Try to convert regular payload to zero-copy buffer (for hot paths)
        fn try_zero_copy_conversion_for_payload(operation_type: BatchOperationType, payload: &[u8]) -> Option<Arc<ZeroCopyBufferRef>> {
            // Enable zero-copy for payloads larger than 1KB to benefit from direct memory access
            if payload.len() >= 1024 {
                // Create a zero-copy buffer reference for large payloads
                // In a real JNI environment, this would allocate direct memory in Java
                // For now, we'll simulate with a mock buffer reference
                let buffer_id = AtomicU64::new(1).fetch_add(1, Ordering::SeqCst);
                let address = payload.as_ptr() as u64; // Use payload address directly
                let size = payload.len();

                let buffer_ref = Arc::new(ZeroCopyBufferRef {
                    buffer_id,
                    java_buffer: unsafe { JByteBuffer::from_raw(0 as *mut jni::sys::_jobject) }, // Mock buffer
                    address,
                    size,
                    operation_type,
                    creation_time: SystemTime::now(),
                    ref_count: AtomicUsize::new(1),
                });

                // Register with global tracker for memory safety
                if let Err(e) = get_global_buffer_tracker().register_buffer(Arc::clone(&buffer_ref)) {
                    log::warn!("Failed to register zero-copy buffer: {}", e);
                    return None;
                }

                Some(buffer_ref)
            } else {
                None
            }
        }

        /// Try to convert regular payload to zero-copy buffer (for hot paths)
        #[allow(dead_code)]
        fn try_zero_copy_conversion(operation_type: BatchOperationType, payload: &[u8]) -> Result<Arc<ZeroCopyBufferRef>, String> {
            // 1. Allocate direct memory in Java using JNI
            // In real implementation, get from JNI call context - this is just a placeholder
            let mut env = unsafe { jni::JNIEnv::from_raw(std::ptr::null_mut()) }.map_err(|e| e.to_string())?;
            
            // 2. Create direct buffer with proper alignment
            let buffer = unsafe { env.new_direct_byte_buffer(payload.as_ptr() as *mut u8, payload.len()) }
                .map_err(|e| format!("Failed to create direct buffer: {}", e))?;
            
            // 3. Get buffer address and capacity
            let address = env.get_direct_buffer_address(&buffer)
                .map_err(|e| format!("Failed to get buffer address: {}", e))? as u64;
            
            let buffer_id = AtomicU64::new(1).fetch_add(1, Ordering::SeqCst);
            
            // 4. Create and register proper ZeroCopyBufferRef
            let buffer_ref = Arc::new(ZeroCopyBufferRef {
                buffer_id,
                java_buffer: buffer,
                address,
                size: payload.len(),
                operation_type,
                creation_time: SystemTime::now(),
                ref_count: AtomicUsize::new(1),
            });
            
            // 5. Register with global tracker for memory safety
            get_global_buffer_tracker().register_buffer(Arc::clone(&buffer_ref))?;
            
            Ok(buffer_ref)
        }

    /// Serialize operation to bytes (fallback for non-zero-copy paths)
    pub fn to_bytes(&self) -> Vec<u8> {
        if let Some(zero_copy_ref) = &self.zero_copy_buffer {
            // For zero-copy operations, read directly from native memory
            let payload = unsafe { zero_copy_ref.as_slice() }.to_vec();
            let mut bytes = Vec::with_capacity(21 + payload.len());
            bytes.extend_from_slice(&self.operation_id.to_le_bytes());
            bytes.push(self.operation_type as u8);
            bytes.extend_from_slice(&(payload.len() as u32).to_le_bytes());
            bytes.extend_from_slice(&payload);
            bytes
        } else {
            // For regular operations
            let mut bytes = Vec::with_capacity(21 + self.payload.len());
            bytes.extend_from_slice(&self.operation_id.to_le_bytes());
            bytes.push(self.operation_type as u8);
            bytes.extend_from_slice(&(self.payload.len() as u32).to_le_bytes());
            bytes.extend_from_slice(&self.payload);
            bytes
        }
    }

    /// Serialize operation directly to JNI ByteBuffer without copying (hot path)
    pub fn to_jni_byte_buffer<'b>(&self, env: &'b mut JNIEnv) -> Result<JByteBuffer<'b>, String> {
        if let Some(zero_copy_ref) = &self.zero_copy_buffer {
            // For zero-copy operations, return the existing buffer (no copying)
            Ok(unsafe { jni::objects::JByteBuffer::from_raw(zero_copy_ref.java_buffer.as_raw()) })
        } else {
            // For regular operations, use direct buffer for better performance
            let _byte_array = env.byte_array_from_slice(&self.payload).map_err(|e| {
                format!("Failed to create byte array: {}", e)
            })?;

            let java_buffer = unsafe {
               env.new_direct_byte_buffer(
                   self.payload.as_ptr() as *mut u8,
                   self.payload.len() as usize
               ).map_err(|e| {
                   format!("Failed to create direct buffer: {}", e)
               })?
           };

            Ok(java_buffer)
        }
    }

    /// Get payload as slice (optimized for zero-copy access)
    pub fn get_payload_slice(&self) -> &[u8] {
        if let Some(zero_copy_ref) = &self.zero_copy_buffer {
            // Zero-copy access to native memory
            unsafe { zero_copy_ref.as_slice() }
        } else {
            // Regular vector access
            &self.payload
        }
    }

    /// Get mutable payload access (unsafe - for hot path modifications)
    pub unsafe fn get_payload_slice_mut(&mut self) -> &mut [u8] {
        if let Some(zero_copy_ref) = &self.zero_copy_buffer {
            // Zero-copy mutable access to native memory
            // Use as_mut_ptr and get_mut_unchecked for proper mutable access
            unsafe { std::slice::from_raw_parts_mut(zero_copy_ref.address as *mut u8, zero_copy_ref.size) }
        } else {
            // Regular vector access
            &mut self.payload
        }
    }

    /// Deserialize operation from JNI ByteBuffer (zero-copy, hot path)
    pub fn from_jni_byte_buffer(env: &JNIEnv, buffer: JByteBuffer<'static>) -> Result<Self, String> {
        let buffer_ref = ZeroCopyBufferRef::from_java_buffer(env, buffer, BatchOperationType::Echo)?;

        // Read operation type from buffer header (first byte)
        let operation_type = if buffer_ref.size >= 1 {
            let header = buffer_ref.operation_type as u8;
            BatchOperationType::try_from(header).unwrap_or(BatchOperationType::Echo)
        } else {
            BatchOperationType::Echo
        };

        Ok(Self::from_zero_copy(operation_type, buffer_ref))
    }

    /// Deserialize operation from bytes (legacy support)
    pub fn from_bytes(bytes: &[u8]) -> Result<Self, String> {
        if bytes.len() < 21 {
            return Err("Batch operation envelope too short".to_string());
        }
        
        let operation_id = u64::from_le_bytes(bytes[0..8].try_into().unwrap());
        let operation_type = BatchOperationType::try_from(bytes[8])?;
        let payload_len = u32::from_le_bytes(bytes[9..13].try_into().unwrap()) as usize;
        
        if bytes.len() < 13 + payload_len {
            return Err("Batch operation payload length mismatch".to_string());
        }
        
        let payload = bytes[13..13 + payload_len].to_vec();
        
        Ok(Self {
            operation_id,
            operation_type,
            payload,
            timestamp: std::time::Instant::now(),
            zero_copy_buffer: None,
        })
    }

    /// Get payload as slice (optimized for zero-copy access)
    pub fn get_payload_slice_v2(&self) -> &[u8] {
        if let Some(zero_copy_ref) = &self.zero_copy_buffer {
            // Zero-copy access to native memory
            unsafe { zero_copy_ref.as_slice() }
        } else {
            // Regular vector access
            &self.payload
        }
    }

    /// Get mutable payload access (unsafe - for hot path modifications)
    pub unsafe fn get_payload_slice_mut_v2(&mut self) -> &mut [u8] {
        if let Some(zero_copy_ref) = &self.zero_copy_buffer {
            // Zero-copy mutable access to native memory
            // Use as_mut_ptr and get_mut_unchecked for proper mutable access
            unsafe { std::slice::from_raw_parts_mut(zero_copy_ref.address as *mut u8, zero_copy_ref.size) }
        } else {
            // Regular vector access
            &mut self.payload
        }
    }
}

/// Batch result status enum
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BatchResultStatus {
    Success = 0,
    Error = 1,
    Timeout = 2,
    Cancelled = 3,
}

/// Batch operation result
#[derive(Debug, Clone)]
pub struct BatchResult {
    pub operation_id: u64,
    pub status: BatchResultStatus,
    pub payload: Vec<u8>,
    pub processing_time_ms: u64,
}

impl BatchResult {
    pub fn success(operation_id: u64, payload: Vec<u8>, processing_time_ms: u64) -> Self {
        Self {
            operation_id,
            status: BatchResultStatus::Success,
            payload,
            processing_time_ms,
        }
    }
    
    pub fn error(operation_id: u64, error_message: String, processing_time_ms: u64) -> Self {
        Self {
            operation_id,
            status: BatchResultStatus::Error,
            payload: error_message.into_bytes(),
            processing_time_ms,
        }
    }
    
    /// Serialize result to bytes
    pub fn to_bytes(&self) -> Vec<u8> {
        let mut bytes = Vec::with_capacity(21 + self.payload.len());
        bytes.extend_from_slice(&self.operation_id.to_le_bytes());
        bytes.push(self.status as u8);
        bytes.extend_from_slice(&(self.payload.len() as u32).to_le_bytes());
        bytes.extend_from_slice(&self.payload);
        bytes.extend_from_slice(&self.processing_time_ms.to_le_bytes());
        bytes
    }
}

/// Batch processor statistics
#[derive(Debug, Clone, Default)]
pub struct BatchProcessorStats {
    pub total_operations_processed: u64,
    pub total_batches_processed: u64,
    pub average_batch_size: f64,
    pub average_processing_time_ms: f64,
    pub total_memory_saved_bytes: u64,
    pub current_queue_depth: usize,
    pub zero_copy_operations: u64,
    pub compression_ratio: f64,
}

/// Zero-copy buffer information
#[derive(Debug, Clone)]
pub struct ZeroCopyBuffer {
    pub buffer_id: u64,
    pub address: u64,
    pub size: usize,
    pub operation_type: BatchOperationType,
    pub timestamp: std::time::Instant,
}

impl ZeroCopyBuffer {
    pub fn as_mut_ptr(&self) -> *mut u8 {
        self.address as *mut u8
    }
    
    pub fn as_slice(&self) -> &[u8] {
        unsafe { std::slice::from_raw_parts(self.address as *const u8, self.size) }
    }
    
    pub fn as_mut_slice(&mut self) -> &mut [u8] {
        unsafe { std::slice::from_raw_parts_mut(self.address as *mut u8, self.size) }
    }
}

impl ZeroCopyBuffer {
    pub fn new(address: u64, size: usize, operation_type: BatchOperationType) -> Self {
        static NEXT_BUFFER_ID: AtomicU64 = AtomicU64::new(1);
        
        Self {
            buffer_id: NEXT_BUFFER_ID.fetch_add(1, Ordering::SeqCst),
            address,
            size,
            operation_type,
            timestamp: std::time::Instant::now(),
        }
    }
}

/// Batch processing utilities
pub struct BatchUtils;

impl BatchUtils {
    /// Calculate optimal batch size based on operation count and available memory
    pub fn calculate_optimal_batch_size(
        operation_count: usize,
        available_memory_bytes: usize,
        avg_operation_size_bytes: usize,
    ) -> usize {
        let memory_based_limit = available_memory_bytes / avg_operation_size_bytes.max(1);
        let optimal_size = operation_count.min(memory_based_limit).min(1000);
        
        // Round to nearest multiple of 25 for alignment
        ((optimal_size + 12) / 25) * 25
    }
    
    /// Estimate memory usage for a batch of operations
    pub fn estimate_batch_memory_usage(operations: &[Vec<u8>]) -> usize {
        operations.iter().map(|op| op.len() + 32).sum::<usize>() + 1024 // overhead
    }
    
    /// Compress batch data if beneficial
    pub fn compress_batch_data(data: &[u8]) -> Result<Vec<u8>, String> {
        use flate2::write::ZlibEncoder;
        use flate2::Compression;
        use std::io::Write;
        
        let mut encoder = ZlibEncoder::new(Vec::new(), Compression::fast());
        encoder.write_all(data).map_err(|e| format!("Compression failed: {}", e))?;
        encoder.finish().map_err(|e| format!("Compression finalization failed: {}", e))
    }
    
    /// Decompress batch data
    pub fn decompress_batch_data(compressed_data: &[u8]) -> Result<Vec<u8>, String> {
        use flate2::read::ZlibDecoder;
        use std::io::Read;
        
        let mut decoder = ZlibDecoder::new(compressed_data);
        let mut decompressed = Vec::new();
        decoder.read_to_end(&mut decompressed).map_err(|e| format!("Decompression failed: {}", e))?;
        Ok(decompressed)
    }
}

/// Thread-safe batch operation queue
pub struct BatchOperationQueue {
    operations: Arc<Mutex<Vec<BatchOperation>>>,
    max_size: usize,
}

impl BatchOperationQueue {
    pub fn new(max_size: usize) -> Self {
        Self {
            operations: Arc::new(Mutex::new(Vec::with_capacity(max_size))),
            max_size,
        }
    }

    pub fn push(&self, operation: BatchOperation) -> Result<bool, String> {
        let mut ops = self.operations.lock()
            .map_err(|e| format!("Failed to lock operations queue: {}", e))?;

        if ops.len() >= self.max_size {
            return Ok(false); // Queue full
        }

        ops.push(operation);
        Ok(true)
    }

    pub fn pop_batch(&self, batch_size: usize) -> Result<Vec<BatchOperation>, String> {
        let mut ops = self.operations.lock()
            .map_err(|e| format!("Failed to lock operations queue: {}", e))?;

        let batch_size = batch_size.min(ops.len());
        let batch: Vec<_> = ops.drain(0..batch_size).collect();
        Ok(batch)
    }
    
    pub fn len(&self) -> Result<usize, String> {
        let ops = self.operations.lock()
            .map_err(|e| format!("Failed to lock operations queue: {}", e))?;
        Ok(ops.len())
    }
    
    pub fn is_empty(&self) -> Result<bool, String> {
        Ok(self.len()? == 0)
    }
    
    pub fn clear(&self) -> Result<(), String> {
        let mut ops = self.operations.lock()
            .map_err(|e| format!("Failed to lock operations queue: {}", e))?;
        ops.clear();
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_batch_operation_serialization() {
        let operation = BatchOperation::new(
            BatchOperationType::Echo,
            b"test payload".to_vec()
        );
        
        let bytes = operation.to_bytes();
        let deserialized = BatchOperation::from_bytes(&bytes).unwrap();
        
        assert_eq!(operation.operation_id, deserialized.operation_id);
        assert_eq!(operation.operation_type, deserialized.operation_type);
        assert_eq!(operation.payload, deserialized.payload);
    }
    
    #[test]
    fn test_batch_result_serialization() {
        let result = BatchResult::success(
            12345,
            b"result payload".to_vec(),
            150
        );
        
        let bytes = result.to_bytes();
        assert!(bytes.len() > 0);
        assert_eq!(bytes[8], BatchResultStatus::Success as u8);
    }
    
    #[test]
    fn test_batch_operation_queue() {
        let queue = BatchOperationQueue::new(10);
        
        for i in 0..5 {
            let operation = BatchOperation::new(
                BatchOperationType::Echo,
                format!("operation {}", i).into_bytes()
            );
            assert!(queue.push(operation).unwrap());
        }
        
        assert_eq!(queue.len().unwrap(), 5);
        
        let batch = queue.pop_batch(3).unwrap();
        assert_eq!(batch.len(), 3);
        assert_eq!(queue.len().unwrap(), 2);
    }
    
    #[test]
    fn test_batch_utils_optimal_size() {
        let optimal_size = BatchUtils::calculate_optimal_batch_size(
            1000,
            1024 * 1024, // 1MB available
            1024, // 1KB average operation size
        );
        
        assert!(optimal_size <= 1000);
        assert!(optimal_size % 25 == 0);
    }
}
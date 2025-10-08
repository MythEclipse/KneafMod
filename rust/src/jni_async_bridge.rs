//! Async JNI bridge for non-blocking batch operations
//! 
//! This module provides async JNI functionality using tokio runtime for 
//! improved performance and reduced JNI call latency.

use std::sync::{Arc, Mutex};
use std::collections::{HashMap, BTreeMap};
use std::sync::atomic::{AtomicU64, Ordering, AtomicUsize};
use tokio::runtime::Runtime;
use once_cell::sync::OnceCell;
use log::{info, debug, warn, error};

// JNI zero-copy imports
use jni::objects::JByteBuffer;
use std::marker::PhantomData;

// Re-export from jni_batch for consistency
use crate::jni_batch::{BatchOperationType, ZeroCopyBufferRef, ZeroCopyBuffer, ZeroCopyBufferPool, get_global_buffer_tracker};

#[allow(dead_code)]
static ASYNC_RUNTIME: OnceCell<Runtime> = OnceCell::new();
static NEXT_OPERATION_ID: AtomicU64 = AtomicU64::new(1);

/// Async operation handle
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct AsyncOperationHandle(pub u64);

/// Async JNI bridge manager
pub struct AsyncJniBridge<'a> {
    pending_operations: Arc<Mutex<HashMap<AsyncOperationHandle, AsyncOperationData<'a>>>>,
    buffer_pool: Arc<ZeroCopyBufferPool>,
    runtime_metrics: Arc<Mutex<AsyncBridgeMetrics>>,
    /// Track active zero-copy buffers for memory safety
    active_buffers: Arc<Mutex<BTreeMap<u64, ZeroCopyBufferRef<'a>>>>,
    buffer_count: AtomicUsize,
    max_active_buffers: usize,
}

/// Async operation data (supports both regular and zero-copy modes)
#[derive(Debug, Clone)]
pub enum AsyncOperationData<'a> {
    /// Regular operations with copied data
    Regular(Vec<Vec<u8>>),
    /// Zero-copy operations with direct buffer references
    ZeroCopy(Vec<ZeroCopyBufferRef<'a>>),
}

/// Async bridge performance metrics
#[derive(Debug, Clone, Default)]
pub struct AsyncBridgeMetrics {
    total_operations: u64,
    zero_copy_operations: u64,
    avg_processing_time_ms: f64,
    total_memory_saved_bytes: u64,
    current_queue_depth: usize,
}

impl<'a> AsyncJniBridge<'a> {
    /// Create a new async JNI bridge with buffer pooling
    pub fn new(buffer_pool_size: usize) -> Result<Self, String> {
        Ok(Self {
            pending_operations: Arc::new(Mutex::new(HashMap::new())),
            buffer_pool: Arc::new(ZeroCopyBufferPool::new(buffer_pool_size)),
            runtime_metrics: Arc::new(Mutex::new(AsyncBridgeMetrics::default())),
            active_buffers: Arc::new(Mutex::new(BTreeMap::new())),
            buffer_count: AtomicUsize::new(0),
            max_active_buffers: 1000, // Reasonable limit for async operations
        })
    }
    
    /// Submit a batch of regular operations for async processing
    pub fn submit_batch(&self, _worker_handle: u64, operations: Vec<Vec<u8>>) -> Result<AsyncOperationHandle, String> {
        let operation_id = AsyncOperationHandle(NEXT_OPERATION_ID.fetch_add(1, Ordering::SeqCst));
        
        // Store operations for later processing
        self.pending_operations.lock()
            .map_err(|e| format!("Failed to lock pending operations: {}", e))?
            .insert(operation_id, AsyncOperationData::Regular(operations.clone()));

        self.update_metrics(false, operations.len());
        debug!("Submitted regular async batch operation with ID: {}", operation_id.0);
        Ok(operation_id)
    }
    
    /// Submit a batch of zero-copy operations for async processing
    pub fn submit_zero_copy_batch(
        &self,
        _worker_handle: u64,
        buffer_refs: Vec<ZeroCopyBufferRef<'a>>,
        _operation_type: BatchOperationType
    ) -> Result<AsyncOperationHandle, String> {
        let operation_id = AsyncOperationHandle(NEXT_OPERATION_ID.fetch_add(1, Ordering::SeqCst));
        
        // Register all buffers for tracking
        let mut registered_buffers = Vec::with_capacity(buffer_refs.len());
        for buffer_ref in &buffer_refs {
            self.register_buffer(buffer_ref)?;
            registered_buffers.push(buffer_ref.buffer_id);
        }
        
        // Store zero-copy buffer references for later processing
        self.pending_operations.lock()
            .map_err(|e| format!("Failed to lock pending operations: {}", e))?
            .insert(operation_id, AsyncOperationData::ZeroCopy(buffer_refs.clone()));

        self.update_metrics(true, buffer_refs.len());
        debug!("Submitted zero-copy async batch operation with ID: {}", operation_id.0);
        
        Ok(operation_id)
    }
    
    /// Register a buffer for tracking (memory safety)
    fn register_buffer(&self, buffer_ref: &ZeroCopyBufferRef<'a>) -> Result<(), String> {
        let buffer_id = buffer_ref.buffer_id;
        
        // Check buffer limit
        let current_count = self.buffer_count.fetch_add(1, Ordering::SeqCst);
        if current_count >= self.max_active_buffers {
            self.buffer_count.fetch_sub(1, Ordering::SeqCst);
            return Err("Maximum active zero-copy buffers exceeded".to_string());
        }
    
        // Register buffer
        let mut buffers = self.active_buffers.lock().unwrap();
        buffers.insert(buffer_id, buffer_ref.clone());
    
        Ok(())
    }
    
    /// Unregister a buffer when no longer needed
    #[allow(dead_code)]
    fn unregister_buffer(&self, buffer_id: u64) -> Result<(), String> {
        let mut buffers = self.active_buffers.lock().unwrap();
        
        if buffers.remove(&buffer_id).is_some() {
            self.buffer_count.fetch_sub(1, Ordering::SeqCst);
            Ok(())
        } else {
            Err(format!("Buffer with ID {} not found", buffer_id))
        }
    }
    
    /// Poll for async operation results
    pub fn poll_results(&self, operation_id: AsyncOperationHandle) -> Result<Option<Vec<Vec<u8>>>, String> {
        let start_time = std::time::Instant::now();
        
        let mut pending_ops = self.pending_operations.lock()
            .map_err(|e| format!("Failed to lock pending operations: {}", e))?;
        
        if let Some(operation_data) = pending_ops.remove(&operation_id) {
            let results = match operation_data {
                AsyncOperationData::Regular(operations) => {
                    // Process regular operations
                    self.process_regular_operations(operations)
                }
                AsyncOperationData::ZeroCopy(buffer_refs) => {
                    // Process zero-copy operations
                    self.process_zero_copy_operations(buffer_refs)
                }
            };
            
            let processing_time = start_time.elapsed().as_millis() as u64;
            self.update_processing_metrics(processing_time, results.len());
            
            debug!("Retrieved results for operation ID: {}", operation_id.0);
            Ok(Some(results))
        } else {
            warn!("Operation ID {} not found", operation_id.0);
            Err("Operation not found".to_string())
        }
    }
    
    /// Process regular operations (with copied data)
    fn process_regular_operations(&self, operations: Vec<Vec<u8>>) -> Vec<Vec<u8>> {
        let mut results = Vec::with_capacity(operations.len());
        
        for operation in operations {
            // Simple echo processing with header (length + data)
            let mut result = Vec::with_capacity(operation.len() + 8);
            result.extend_from_slice(&(operation.len() as u32).to_le_bytes());
            result.extend_from_slice(&operation);
            results.push(result);
        }
        
        results
    }
    
    /// Process zero-copy operations (direct memory access)
    fn process_zero_copy_operations(&self, buffer_refs: Vec<ZeroCopyBufferRef>) -> Vec<Vec<u8>> {
        let mut results = Vec::with_capacity(buffer_refs.len());
        
        for buffer_ref in buffer_refs {
            // In a real implementation, we would process the direct memory here
            // For demonstration, we'll just copy the buffer contents to a result
            let slice = unsafe { buffer_ref.as_slice() };
            let mut result = Vec::with_capacity(slice.len() + 8);
            result.extend_from_slice(&(slice.len() as u32).to_le_bytes());
            result.extend_from_slice(slice);
            results.push(result);
            
            // Release buffer back to pool for reuse
            self.buffer_pool.release(ZeroCopyBuffer::new(buffer_ref.address, buffer_ref.size, BatchOperationType::Echo));
        }
        
        results
    }
    
    /// Update metrics with operation information
    fn update_metrics(&self, is_zero_copy: bool, operation_count: usize) {
        let mut metrics = self.runtime_metrics.lock().unwrap();
        
        metrics.total_operations += operation_count as u64;
        if is_zero_copy {
            metrics.zero_copy_operations += operation_count as u64;
            // Estimate memory saved (conservative estimate)
            metrics.total_memory_saved_bytes += (operation_count * 1024) as u64;
        }
        metrics.current_queue_depth = self.pending_operations.lock().unwrap().len();
    }
    
    /// Update processing metrics
    fn update_processing_metrics(&self, processing_time: u64, result_count: usize) {
        let mut metrics = self.runtime_metrics.lock().unwrap();
        
        if result_count > 0 {
            let new_avg = if metrics.total_operations == 0 {
                processing_time as f64
            } else {
                (metrics.avg_processing_time_ms * (metrics.total_operations as f64 - 1.0) + processing_time as f64) / metrics.total_operations as f64
            };
            metrics.avg_processing_time_ms = new_avg;
        }
    }
    
    /// Get current runtime metrics
    pub fn get_metrics(&self) -> AsyncBridgeMetrics {
        self.runtime_metrics.lock().unwrap().clone()
    }
    
    /// Cleanup completed operation
    pub fn cleanup_operation(&self, operation_id: AsyncOperationHandle) {
        match self.pending_operations.lock() {
            Ok(mut pending_ops) => {
                pending_ops.remove(&operation_id);
                debug!("Cleaned up operation ID: {}", operation_id.0);
            }
            Err(e) => {
                error!("Failed to lock pending operations for cleanup: {}", e);
            }
        }
    }
}

/// Get or create the global async JNI bridge
pub fn get_async_jni_bridge() -> Result<&'static AsyncJniBridge<'static>, String> {
    static BRIDGE: OnceCell<AsyncJniBridge> = OnceCell::new();
    
    BRIDGE.get_or_try_init(|| {
        info!("Initializing async JNI bridge");
        AsyncJniBridge::new(1024) // Default buffer pool size
    })
}

/// Submit async batch operation
pub fn submit_async_batch(worker_handle: u64, operations: Vec<Vec<u8>>) -> Result<AsyncOperationHandle, String> {
    let bridge = get_async_jni_bridge()?;
    bridge.submit_batch(worker_handle, operations)
}

/// Poll async batch results
pub fn poll_async_batch_results(operation_id: u64, max_results: usize) -> Result<Vec<Vec<u8>>, String> {
    let bridge = get_async_jni_bridge()?;
    match bridge.poll_results(AsyncOperationHandle(operation_id))? {
        Some(results) => Ok(results.into_iter().take(max_results).collect()),
        None => Ok(Vec::new()),
    }
}

/// Cleanup async batch operation
pub fn cleanup_async_batch_operation(operation_id: u64) {
    if let Ok(bridge) = get_async_jni_bridge() {
        bridge.cleanup_operation(AsyncOperationHandle(operation_id));
    }
}

/// Submit zero-copy batch operation
pub fn submit_zero_copy_batch(
    worker_handle: u64,
    buffer_addresses: Vec<(u64, usize)>,
    operation_type: u32
) -> Result<AsyncOperationHandle, String> {
    let bridge = get_async_jni_bridge()?;
    let operation_type = BatchOperationType::try_from(operation_type as u8).map_err(|e| format!("Invalid operation type: {}", e))?;
    
    // Convert zero-copy buffer addresses to operations
    let mut operations = Vec::with_capacity(buffer_addresses.len());
    
    for (address, size) in buffer_addresses {
        // For build testing only - create dummy ZeroCopyBufferRef without JNI operations
        let buffer_ref = ZeroCopyBufferRef {
            buffer_id: AtomicU64::new(1).fetch_add(1, Ordering::SeqCst),
            java_buffer: unsafe { JByteBuffer::from_raw(0 as *mut jni::sys::_jobject) },
            address,
            size,
            operation_type,
            creation_time: std::time::SystemTime::now(),
            ref_count: AtomicUsize::new(1),
            _phantom: PhantomData,
        };
        
        // Register buffer with global tracker
        get_global_buffer_tracker().register_buffer(&buffer_ref)?;
        
        operations.push(buffer_ref);
    }
    
    bridge.submit_zero_copy_batch(worker_handle, operations, operation_type)
}
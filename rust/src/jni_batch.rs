//! JNI batch processing utilities and types
//! 
//! This module provides shared types and utilities for batch processing operations
//! across different JNI interfaces.

use std::sync::{Arc, Mutex};
use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use serde::{Serialize, Deserialize};

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

/// Batch operation envelope
#[derive(Debug, Clone)]
pub struct BatchOperation {
    pub operation_id: u64,
    pub operation_type: BatchOperationType,
    pub payload: Vec<u8>,
    pub timestamp: std::time::Instant,
}

impl BatchOperation {
    pub fn new(operation_type: BatchOperationType, payload: Vec<u8>) -> Self {
        static NEXT_OPERATION_ID: AtomicU64 = AtomicU64::new(1);
        
        Self {
            operation_id: NEXT_OPERATION_ID.fetch_add(1, Ordering::SeqCst),
            operation_type,
            payload,
            timestamp: std::time::Instant::now(),
        }
    }
    
    /// Serialize operation to bytes
    pub fn to_bytes(&self) -> Vec<u8> {
        let mut bytes = Vec::with_capacity(21 + self.payload.len());
        bytes.extend_from_slice(&self.operation_id.to_le_bytes());
        bytes.push(self.operation_type as u8);
        bytes.extend_from_slice(&(self.payload.len() as u32).to_le_bytes());
        bytes.extend_from_slice(&self.payload);
        bytes
    }
    
    /// Deserialize operation from bytes
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
        })
    }
}

/// Batch operation result
#[derive(Debug, Clone)]
pub struct BatchResult {
    pub operation_id: u64,
    pub status: BatchResultStatus,
    pub payload: Vec<u8>,
    pub processing_time_ms: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BatchResultStatus {
    Success = 0,
    Error = 1,
    Timeout = 2,
    Cancelled = 3,
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
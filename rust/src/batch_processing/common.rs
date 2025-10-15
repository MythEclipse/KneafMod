use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::time::Instant;
use byteorder::{LittleEndian, ReadBytesExt, WriteBytesExt};
use crate::errors::{RustError, Result};
use std::io::Read;

/// Common batch operation structure used across all batch processing modules
#[derive(Debug, Clone)]
pub struct BatchOperation {
    pub operation_type: u8,
    pub input_data: Vec<u8>,
    pub estimated_size: usize,
    pub timestamp: Instant,
    pub priority: u8,
}

impl BatchOperation {
    pub fn new(operation_type: u8, input_data: Vec<u8>, priority: u8) -> Self {
        Self {
            operation_type,
            input_data: input_data.clone(),
            estimated_size: input_data.len(),
            timestamp: Instant::now(),
            priority,
        }
    }
}

/// Common batch result structure used across all batch processing modules
#[derive(Debug, Clone)]
pub struct BatchResult {
    pub operation_type: u8,
    pub results: Vec<Vec<u8>>,
    pub processing_time_ns: u64,
    pub batch_size: usize,
    pub success_count: usize,
}

/// Common batch metrics structure used across all batch processing modules
#[derive(Debug, Default)]
pub struct BatchMetrics {
    pub total_batches_processed: AtomicU64,
    pub total_operations_batched: AtomicU64,
    pub average_batch_size: AtomicU64,
    pub current_queue_depth: AtomicUsize,
    pub failed_operations: AtomicU64,
    pub total_processing_time_ns: AtomicU64,
    pub adaptive_batch_size: AtomicUsize,
    pub pressure_level: AtomicU64,
}

impl BatchMetrics {
    pub fn new() -> Self {
        Self {
            total_batches_processed: AtomicU64::new(0),
            total_operations_batched: AtomicU64::new(0),
            average_batch_size: AtomicU64::new(50),
            current_queue_depth: AtomicUsize::new(0),
            failed_operations: AtomicU64::new(0),
            total_processing_time_ns: AtomicU64::new(0),
            adaptive_batch_size: AtomicUsize::new(50),
            pressure_level: AtomicU64::new(0),
        }
    }

    pub fn update_average_batch_size(&self, new_size: usize) {
        let current = self.average_batch_size.load(Ordering::Relaxed);
        let updated = (current * 9 + new_size as u64) / 10;
        self.average_batch_size.store(updated, Ordering::Relaxed);
        self.adaptive_batch_size.store(updated as usize, Ordering::Relaxed);
    }

    pub fn get_pressure_level(&self) -> u8 {
        self.pressure_level.load(Ordering::Relaxed) as u8
    }

    pub fn set_pressure_level(&self, level: u8) {
        self.pressure_level.store(level as u64, Ordering::Relaxed);
    }
}

/// Common batch configuration structure used across all batch processing modules
#[derive(Debug, Clone)]
pub struct BatchConfig {
    pub min_batch_size: usize,
    pub max_batch_size: usize,
    pub adaptive_batch_timeout_ms: u64,
    pub max_pending_batches: usize,
    pub worker_threads: usize,
    pub enable_adaptive_sizing: bool,
}

impl Default for BatchConfig {
    fn default() -> Self {
        Self {
            min_batch_size: 50,
            max_batch_size: 500,
            adaptive_batch_timeout_ms: 1,
            max_pending_batches: 200,
            worker_threads: 8,
            enable_adaptive_sizing: true,
        }
    }
}

/// Serialize batch result to binary format (common for all batch processors)
pub fn serialize_batch_result(
    batch_id: u64,
    successful_operations: i32,
    failed_operations: i32,
    total_bytes_processed: u64,
    batch_duration: u64,
    results: Vec<(bool, Vec<u8>, u64)>,
) -> Result<Vec<u8>> {
    let mut result_data = Vec::new();

    WriteBytesExt::write_u64::<LittleEndian>(&mut result_data, batch_id)
        .map_err(|e| RustError::SerializationError(format!("Failed to write batch ID: {}", e)))?;

    WriteBytesExt::write_i32::<LittleEndian>(&mut result_data, successful_operations)
        .map_err(|e| RustError::SerializationError(format!("Failed to write successful count: {}", e)))?;

    WriteBytesExt::write_i32::<LittleEndian>(&mut result_data, failed_operations)
        .map_err(|e| RustError::SerializationError(format!("Failed to write failed count: {}", e)))?;

    WriteBytesExt::write_u64::<LittleEndian>(&mut result_data, total_bytes_processed)
        .map_err(|e| RustError::SerializationError(format!("Failed to write bytes processed: {}", e)))?;

    WriteBytesExt::write_u64::<LittleEndian>(&mut result_data, batch_duration)
        .map_err(|e| RustError::SerializationError(format!("Failed to write batch duration: {}", e)))?;

    WriteBytesExt::write_i32::<LittleEndian>(&mut result_data, results.len() as i32)
        .map_err(|e| RustError::SerializationError(format!("Failed to write results count: {}", e)))?;

    for (success, data, duration) in results {
        WriteBytesExt::write_u8(&mut result_data, if success { 1 } else { 0 })
            .map_err(|e| RustError::SerializationError(format!("Failed to write result success flag: {}", e)))?;

        WriteBytesExt::write_u64::<LittleEndian>(&mut result_data, duration)
            .map_err(|e| RustError::SerializationError(format!("Failed to write result duration: {}", e)))?;

        WriteBytesExt::write_u32::<LittleEndian>(&mut result_data, data.len() as u32)
            .map_err(|e| RustError::SerializationError(format!("Failed to write result data length: {}", e)))?;

        result_data.extend_from_slice(&data);
    }

    Ok(result_data)
}

/// Parse batch operation from binary format (common for all batch processors)
pub fn parse_batch_operation(data: &[u8]) -> Result<(String, Vec<Vec<u8>>)> {
    if data.len() < 4 {
        return Err(RustError::ParseError("Operation data too small".to_string()));
    }

    let mut cursor = std::io::Cursor::new(data);

    let name_len = ReadBytesExt::read_u16::<LittleEndian>(&mut cursor)
        .map_err(|e| RustError::ParseError(format!("Failed to read operation name length: {}", e)))? as usize;

    if cursor.position() as usize + name_len > data.len() {
        return Err(RustError::ParseError("Invalid operation name length".to_string()));
    }

    let mut name_bytes = vec![0u8; name_len];
    cursor.read_exact(&mut name_bytes)
        .map_err(|e| RustError::ParseError(format!("Failed to read operation name: {}", e)))?;

    let operation_name = String::from_utf8(name_bytes)
        .map_err(|e| RustError::ParseError(format!("Invalid operation name encoding: {}", e)))?;

    let param_count = ReadBytesExt::read_u32::<LittleEndian>(&mut cursor)
        .map_err(|e| RustError::ParseError(format!("Failed to read parameter count: {}", e)))? as usize;

    let mut parameters = Vec::with_capacity(param_count);

    for i in 0..param_count {
        let param_len = ReadBytesExt::read_u32::<LittleEndian>(&mut cursor)
            .map_err(|e| RustError::ParseError(format!("Failed to read parameter {} length: {}", i, e)))? as usize;

        if cursor.position() as usize + param_len > data.len() {
            return Err(RustError::ParseError(format!("Parameter {} data exceeds buffer", i)));
        }

        let mut param_data = vec![0u8; param_len];
        cursor.read_exact(&mut param_data)
            .map_err(|e| RustError::ParseError(format!("Failed to read parameter {}: {}", i, e)))?;

        parameters.push(param_data);
    }

    Ok((operation_name, parameters))
}

/// Execute operation by name using existing processing functions (common for all batch processors)
pub fn execute_operation_by_name(operation_name: &str, parameters: &[Vec<u8>]) -> Result<Vec<u8>> {
    let _main_data: &[u8] = if parameters.is_empty() {
        &[]
    } else {
        parameters[0].as_slice()
    };

    match operation_name {
        "processVillager" => Ok(vec![0]), // Simplified - would call actual implementation
        "processEntity" => Ok(vec![1]),  // Simplified - would call actual implementation
        "processMob" => Ok(vec![2]),     // Simplified - would call actual implementation
        "processBlock" => Ok(vec![3]),   // Simplified - would call actual implementation
        _ => Err(RustError::InvalidOperationType {
            operation_type: 0,
            max_type: 255,
        }),
    }
}
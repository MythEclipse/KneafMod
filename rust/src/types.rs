use glam::Vec3A;
use serde::{Deserialize, Serialize};

/// Error type for Java-Rust performance integration
#[derive(Debug, Clone, PartialEq)]
pub enum RustPerformanceError {
    /// Native library not loaded
    LIBRARY_NOT_LOADED(String),
    
    /// Rust performance system not initialized
    NOT_INITIALIZED(String),
    
    /// Failed to load Rust performance native library
    LIBRARY_LOAD_FAILED(String),
    
    /// Rust performance initialization failed
    INITIALIZATION_FAILED(String),
    
    /// Ultra performance initialization failed
    ULTIMATE_INIT_FAILED(String),
    
    /// Invalid arguments provided
    INVALID_ARGUMENTS(String),
    
    /// Semaphore timeout
    SEMAPHORE_TIMEOUT(String),
    
    /// Native call failed
    NATIVE_CALL_FAILED(String),
    
    /// Memory optimization failed
    MEMORY_OPTIMIZATION_FAILED(String),
    
    /// Chunk optimization failed
    CHUNK_OPTIMIZATION_FAILED(String),
    
    /// Error during performance monitoring shutdown
    SHUTDOWN_ERROR(String),
    
    /// Error during counter reset
    COUNTER_RESET_ERROR(String),
    
    /// Error retrieving CPU stats
    CPU_STATS_ERROR(String),
    
    /// Error retrieving memory stats
    MEMORY_STATS_ERROR(String),
    
    /// Error retrieving TPS
    TPS_ERROR(String),
    
    /// Error retrieving entity count
    ENTITY_COUNT_ERROR(String),
    
    /// Error retrieving mob count
    MOB_COUNT_ERROR(String),
    
    /// Error retrieving block count
    BLOCK_COUNT_ERROR(String),
    
    /// Error retrieving merged count
    MERGED_COUNT_ERROR(String),
    
    /// Error retrieving despawned count
    DESPAWNED_COUNT_ERROR(String),
    
    /// Error during batch processing
    BATCH_PROCESSING_ERROR(String),
    
    /// Error during zero-copy operation
    ZERO_COPY_ERROR(String),
    
    /// Error retrieving metrics
    METRICS_ERROR(String),
    
    /// Error logging startup info
    LOG_STARTUP_ERROR(String),
    
    /// Generic error with custom message
    GENERIC_ERROR(String),
}

impl RustPerformanceError {
    /// Create a new RustPerformanceError with a custom message
    pub fn with_code(message: String, code: i32) -> Self {
        match code {
            1 => Self::LIBRARY_NOT_LOADED(message),
            2 => Self::NOT_INITIALIZED(message),
            3 => Self::LIBRARY_LOAD_FAILED(message),
            4 => Self::INITIALIZATION_FAILED(message),
            5 => Self::ULTIMATE_INIT_FAILED(message),
            6 => Self::INVALID_ARGUMENTS(message),
            7 => Self::SEMAPHORE_TIMEOUT(message),
            8 => Self::NATIVE_CALL_FAILED(message),
            9 => Self::MEMORY_OPTIMIZATION_FAILED(message),
            10 => Self::CHUNK_OPTIMIZATION_FAILED(message),
            11 => Self::SHUTDOWN_ERROR(message),
            12 => Self::COUNTER_RESET_ERROR(message),
            13 => Self::CPU_STATS_ERROR(message),
            14 => Self::MEMORY_STATS_ERROR(message),
            15 => Self::TPS_ERROR(message),
            16 => Self::ENTITY_COUNT_ERROR(message),
            17 => Self::MOB_COUNT_ERROR(message),
            18 => Self::BLOCK_COUNT_ERROR(message),
            19 => Self::MERGED_COUNT_ERROR(message),
            20 => Self::DESPAWNED_COUNT_ERROR(message),
            21 => Self::BATCH_PROCESSING_ERROR(message),
            22 => Self::ZERO_COPY_ERROR(message),
            23 => Self::METRICS_ERROR(message),
            24 => Self::LOG_STARTUP_ERROR(message),
            _ => Self::GENERIC_ERROR(message),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize, Default)]
pub struct Aabb {
    pub min: Vec3A,
    pub max: Vec3A,
}

impl Aabb {
    pub fn new(min_x: f32, min_y: f32, min_z: f32, max_x: f32, max_y: f32, max_z: f32) -> Self {
        Self {
            min: Vec3A::new(min_x, min_y, min_z),
            max: Vec3A::new(max_x, max_y, max_z),
        }
    }

    pub fn contains_point(&self, point: &Vec3A) -> bool {
        point.x >= self.min.x
            && point.x <= self.max.x
            && point.y >= self.min.y
            && point.y <= self.max.y
            && point.z >= self.min.z
            && point.z <= self.max.z
    }

    pub fn intersects(&self, other: &Aabb) -> bool {
        self.min.x <= other.max.x
            && self.max.x >= other.min.x
            && self.min.y <= other.max.y
            && self.max.y >= other.min.y
            && self.min.z <= other.max.z
            && self.max.z >= other.min.z
    }
}

/// Re-export centralized error types for consistency across the codebase
pub use crate::errors::{RustError, Result};

/// Convert legacy RustPerformanceError to centralized RustError
impl From<crate::errors::RustError> for RustPerformanceError {
    fn from(error: crate::errors::RustError) -> Self {
        Self::with_code(error.to_string(), match error {
            crate::errors::RustError::IoError(_) => 1,
            crate::errors::RustError::DatabaseError(_) => 2,
            crate::errors::RustError::ChunkNotFound(_) => 3,
            crate::errors::RustError::CompressionError(_) => 4,
            crate::errors::RustError::ChecksumError(_) => 5,
            crate::errors::RustError::JniError(_) => 6,
            crate::errors::RustError::TimeoutError(_) => 7,
            crate::errors::RustError::OperationFailed(_) => 8,
            crate::errors::RustError::SerializationError(_) => 9,
            crate::errors::RustError::DeserializationError(_) => 10,
            crate::errors::RustError::ValidationError(_) => 11,
            crate::errors::RustError::ResourceExhaustionError(_) => 12,
            crate::errors::RustError::InternalError(_) => 13,
            crate::errors::RustError::NotInitializedError(_) => 14,
            crate::errors::RustError::InvalidInputError(_) => 15,
            crate::errors::RustError::InvalidArgumentError(_) => 16,
            crate::errors::RustError::BufferError(_) => 17,
            crate::errors::RustError::ParseError(_) => 18,
            crate::errors::RustError::ConversionError(_) => 19,
            crate::errors::RustError::InvalidOperationType { .. } => 20,
            crate::errors::RustError::EmptyOperationSet => 21,
            crate::errors::RustError::AsyncTaskSendFailed { .. } => 22,
            crate::errors::RustError::AsyncResultReceiveFailed { .. } => 23,
            crate::errors::RustError::SharedBufferLockFailed => 24,
        })
    }
}

/// Result type alias for Rust performance operations (using centralized error type)
pub type PerformanceResult<T> = Result<T>;

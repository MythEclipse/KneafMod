use glam::Vec3A;
use serde::{Deserialize, Serialize};

/// Error type for Java-Rust performance integration
#[derive(Debug, Clone, PartialEq)]
pub enum RustPerformanceError {
    /// Native library not loaded
    LibraryNotLoaded(String),
    
    /// Rust performance system not initialized
    NotInitialized(String),
    
    /// Failed to load Rust performance native library
    LibraryLoadFailed(String),
    
    /// Rust performance initialization failed
    InitializationFailed(String),
    
    /// Ultra performance initialization failed
    UltimateInitFailed(String),
    
    /// Invalid arguments provided
    InvalidArguments(String),
    
    /// Semaphore timeout
    SemaphoreTimeout(String),
    
    /// Native call failed
    NativeCallFailed(String),
    
    /// Memory optimization failed
    MemoryOptimizationFailed(String),
    
    /// Chunk optimization failed
    ChunkOptimizationFailed(String),
    
    /// Error during performance monitoring shutdown
    ShutdownError(String),
    
    /// Error during counter reset
    CounterResetError(String),
    
    /// Error retrieving CPU stats
    CpuStatsError(String),
    
    /// Error retrieving memory stats
    MemoryStatsError(String),
    
    /// Error retrieving TPS
    TpsError(String),
    
    /// Error retrieving entity count
    EntityCountError(String),
    
    /// Error retrieving mob count
    MobCountError(String),
    
    /// Error retrieving block count
    BlockCountError(String),
    
    /// Error retrieving merged count
    MergedCountError(String),
    
    /// Error retrieving despawned count
    DespawnedCountError(String),
    
    /// Error during batch processing
    BatchProcessingError(String),
    
    /// Error during zero-copy operation
    ZeroCopyError(String),
    
    /// Error retrieving metrics
    MetricsError(String),
    
    /// Error logging startup info
    LogStartupError(String),
    
    /// Generic error with custom message
    GenericError(String),
}

impl RustPerformanceError {
    /// Create a new RustPerformanceError with a custom message
    pub fn with_code(message: String, code: i32) -> Self {
        match code {
            1 => Self::LibraryNotLoaded(message),
            2 => Self::NotInitialized(message),
            3 => Self::LibraryLoadFailed(message),
            4 => Self::InitializationFailed(message),
            5 => Self::UltimateInitFailed(message),
            6 => Self::InvalidArguments(message),
            7 => Self::SemaphoreTimeout(message),
            8 => Self::NativeCallFailed(message),
            9 => Self::MemoryOptimizationFailed(message),
            10 => Self::ChunkOptimizationFailed(message),
            11 => Self::ShutdownError(message),
            12 => Self::CounterResetError(message),
            13 => Self::CpuStatsError(message),
            14 => Self::MemoryStatsError(message),
            15 => Self::TpsError(message),
            16 => Self::EntityCountError(message),
            17 => Self::MobCountError(message),
            18 => Self::BlockCountError(message),
            19 => Self::MergedCountError(message),
            20 => Self::DespawnedCountError(message),
            21 => Self::BatchProcessingError(message),
            22 => Self::ZeroCopyError(message),
            23 => Self::MetricsError(message),
            24 => Self::LogStartupError(message),
            _ => Self::GenericError(message),
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

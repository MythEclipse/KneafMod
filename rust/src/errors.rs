use std::fmt;
use std::error::Error;

/// Centralized error types for the Rust performance module
#[derive(Debug)]
pub enum RustError {
    /// IO operation failed
    IoError(String),
    
    /// Database operation failed
    DatabaseError(String),
    
    /// Chunk not found
    ChunkNotFound(String),
    
    /// Compression error
    CompressionError(String),
    
    /// Checksum error
    ChecksumError(String),
    
    /// JNI operation failed
    JniError(String),
    
    /// Timeout error
    TimeoutError(String),
    
    /// Operation failed
    OperationFailed(String),
    InvalidInput(String),
    
    /// Serialization error
    SerializationError(String),
    
    /// Deserialization error
    DeserializationError(String),
    
    /// Validation error
    ValidationError(String),
    
    /// Resource exhaustion
    ResourceExhaustionError(String),
    
    /// Internal error
    InternalError(String),
    
    /// Not initialized error
    NotInitializedError(String),
    
    /// Invalid input error
        InvalidInputError(String),
        
        /// Invalid argument error
        InvalidArgumentError(String),
        
        /// Buffer error for direct memory access
        BufferError(String),
        
        /// Parse error for batch operations
        ParseError(String),
        
        /// Conversion error for JNI arrays
        ConversionError(String),
        
        /// Memory pool error
        MemoryPoolError(String),
        
        /// Resource limit exceeded
        ResourceLimitExceeded(String),
    
    /// Invalid operation type error
    InvalidOperationType {
            operation_type: u8,
            max_type: u8,
        },
    
        /// Empty operation set error
        EmptyOperationSet,
    
        /// Async task send failed error
        AsyncTaskSendFailed {
            source: String,
        },
    
        /// Async result receive failed error
        AsyncResultReceiveFailed {
            source: String,
        },
    
        /// Shared buffer lock failed error
        SharedBufferLockFailed,
        
        /// Memory pool operation error
        PoolError(String),
    }

impl fmt::Display for RustError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            RustError::IoError(msg) => write!(f, "IO error: {}", msg),
            RustError::DatabaseError(msg) => write!(f, "Database error: {}", msg),
            RustError::ChunkNotFound(msg) => write!(f, "Chunk not found: {}", msg),
            RustError::CompressionError(msg) => write!(f, "Compression error: {}", msg),
            RustError::ChecksumError(msg) => write!(f, "Checksum error: {}", msg),
            RustError::JniError(msg) => write!(f, "JNI error: {}", msg),
            RustError::TimeoutError(msg) => write!(f, "Timeout error: {}", msg),
            RustError::OperationFailed(msg) => write!(f, "Operation failed: {}", msg),
            RustError::InvalidInput(msg) => write!(f, "Invalid input: {}", msg),
            RustError::InvalidInputError(msg) => write!(f, "Invalid input: {}", msg),
            RustError::SerializationError(msg) => write!(f, "Serialization error: {}", msg),
            RustError::DeserializationError(msg) => write!(f, "Deserialization error: {}", msg),
            RustError::ValidationError(msg) => write!(f, "Validation error: {}", msg),
            RustError::ResourceExhaustionError(msg) => write!(f, "Resource exhaustion: {}", msg),
            RustError::InternalError(msg) => write!(f, "Internal error: {}", msg),
            RustError::NotInitializedError(msg) => write!(f, "Not initialized: {}", msg),
            RustError::InvalidInput(msg) => write!(f, "Invalid input: {}", msg),
            RustError::InvalidArgumentError(msg) => write!(f, "Invalid argument: {}", msg),
            RustError::BufferError(msg) => write!(f, "Buffer error: {}", msg),
            RustError::ParseError(msg) => write!(f, "Parse error: {}", msg),
            RustError::ConversionError(msg) => write!(f, "Conversion error: {}", msg),
            RustError::MemoryPoolError(msg) => write!(f, "Memory pool error: {}", msg),
            RustError::ResourceLimitExceeded(msg) => write!(f, "Resource limit exceeded: {}", msg),
            RustError::InvalidOperationType { operation_type, max_type } => {
                write!(f, "Invalid operation type {} (max: {})", operation_type, max_type)
            }
            RustError::EmptyOperationSet => write!(f, "Empty operation set"),
            RustError::AsyncTaskSendFailed { source } => write!(f, "Async task send failed: {}", source),
            RustError::AsyncResultReceiveFailed { source } => write!(f, "Async result receive failed: {}", source),
            RustError::SharedBufferLockFailed => write!(f, "Failed to acquire shared buffer lock"),
            RustError::PoolError(msg) => write!(f, "Pool error: {}", msg),
        }
    }
}

impl Error for RustError {}

/// Implement From trait for std::io::Error
impl From<std::io::Error> for RustError {
    fn from(error: std::io::Error) -> Self {
        RustError::IoError(error.to_string())
    }
}

/// Implement From trait for jni::errors::Error
impl From<jni::errors::Error> for RustError {
    fn from(error: jni::errors::Error) -> Self {
        RustError::JniError(error.to_string())
    }
}

/// Implement From trait for serde_json::Error
impl From<serde_json::Error> for RustError {
    fn from(error: serde_json::Error) -> Self {
        if error.is_io() {
            RustError::IoError(error.to_string())
        } else if error.is_syntax() {
            RustError::DeserializationError(error.to_string())
        } else if error.is_data() {
            RustError::ValidationError(error.to_string())
        } else {
            RustError::SerializationError(error.to_string())
        }
    }
}

/// Implement From trait for sled::Error
impl From<sled::Error> for RustError {
    fn from(error: sled::Error) -> Self {
        RustError::DatabaseError(error.to_string())
    }
}

/// Implement From trait for flate2::CompressionError
impl From<flate2::CompressError> for RustError {
    fn from(error: flate2::CompressError) -> Self {
        RustError::CompressionError(error.to_string())
    }
}

/// Implement From trait for lz4_flex::block::CompressError
impl From<lz4_flex::block::CompressError> for RustError {
    fn from(error: lz4_flex::block::CompressError) -> Self {
        RustError::CompressionError(error.to_string())
    }
}

/// Implement From trait for BinaryConversionError
impl From<crate::binary::conversions::BinaryConversionError> for RustError {
    fn from(error: crate::binary::conversions::BinaryConversionError) -> Self {
        match error {
            crate::binary::conversions::BinaryConversionError::Io(e) => RustError::IoError(e.to_string()),
            crate::binary::conversions::BinaryConversionError::Utf8(e) => RustError::InvalidInputError(e.to_string()),
            crate::binary::conversions::BinaryConversionError::InvalidData(msg) => RustError::InvalidInputError(msg),
            crate::binary::conversions::BinaryConversionError::SystemTime(e) => RustError::InternalError(e.to_string()),
        }
    }
}

/// Implement From trait for AllocationError
impl From<crate::memory::allocator::AllocationError> for RustError {
    fn from(error: crate::memory::allocator::AllocationError) -> Self {
        match error {
            crate::memory::allocator::AllocationError::OutOfMemory => RustError::ResourceExhaustionError("Out of memory".to_string()),
            crate::memory::allocator::AllocationError::InvalidSize => RustError::InvalidInputError("Invalid allocation size".to_string()),
            crate::memory::allocator::AllocationError::AllocationFailed => RustError::OperationFailed("Allocation failed".to_string()),
            crate::memory::allocator::AllocationError::DeallocationFailed => RustError::OperationFailed("Deallocation failed".to_string()),
        }
    }
}

// Note: tokio::io::Error currently maps to std::io::Error; avoid duplicate From impl
// If needed, conversion can be performed at the call site via `std::io::Error::from(tokio_err)`.

/// Implement From trait for j4rs::errors::J4RsError
impl From<j4rs::errors::J4RsError> for RustError {
    fn from(error: j4rs::errors::J4RsError) -> Self {
        RustError::JniError(error.to_string())
    }
}

/// Common error messages
pub mod messages {
    /// Common error messages for initialization checks
    pub const NOT_INITIALIZED: &str = "Component not initialized";
    pub const PROCESSOR_NOT_INITIALIZED: &str = "Enhanced batch processor not initialized";
    pub const INPUT_NULL: &str = "Input is null";
    pub const INPUT_EMPTY: &str = "Input is empty";
    pub const INPUT_TOO_LARGE: &str = "Input too large: {} bytes";
    pub const INVALID_UTF8: &str = "Invalid UTF-8 input: {}";
    pub const KEY_EMPTY: &str = "Key cannot be empty";
    pub const DATA_EMPTY: &str = "Data cannot be empty";
    pub const DIRECT_BUFFER_NULL: &str = "Direct buffer cannot be null";
    pub const BUFFER_ADDRESS_NULL: &str = "Failed to get direct buffer address";
    pub const BUFFER_ADDRESS_ERROR: &str = "Failed to get direct buffer address";
    pub const PARSE_ERROR: &str = "Failed to parse input";
    pub const ARRAY_CONVERSION_ERROR: &str = "Failed to convert byte array";
    pub const NULL_BUFFER_ERROR: &str = "Direct buffer cannot be null";
    pub const NULL_BUFFER_ADDRESS: &str = "Buffer address is null";
    pub const SWAP_FILE_NOT_FOUND: &str = "Swap file not found for key: {}";
    pub const DATABASE_PATH_INVALID: &str = "Failed to create database directory: {}";
    pub const LOCK_TIMEOUT: &str = "Timeout acquiring {} lock after {} ms";
}

/// Result type alias using our centralized error type
pub type Result<T> = std::result::Result<T, RustError>;

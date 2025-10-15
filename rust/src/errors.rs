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
            RustError::SerializationError(msg) => write!(f, "Serialization error: {}", msg),
            RustError::DeserializationError(msg) => write!(f, "Deserialization error: {}", msg),
            RustError::ValidationError(msg) => write!(f, "Validation error: {}", msg),
            RustError::ResourceExhaustionError(msg) => write!(f, "Resource exhaustion: {}", msg),
            RustError::InternalError(msg) => write!(f, "Internal error: {}", msg),
            RustError::NotInitializedError(msg) => write!(f, "Not initialized: {}", msg),
            RustError::InvalidInputError(msg) => write!(f, "Invalid input: {}", msg),
            RustError::InvalidArgumentError(msg) => write!(f, "Invalid argument: {}", msg),
            RustError::BufferError(msg) => write!(f, "Buffer error: {}", msg),
            RustError::ParseError(msg) => write!(f, "Parse error: {}", msg),
            RustError::ConversionError(msg) => write!(f, "Conversion error: {}", msg),
            RustError::InvalidOperationType { operation_type, max_type } => {
                write!(f, "Invalid operation type {} (max: {})", operation_type, max_type)
            }
            RustError::EmptyOperationSet => write!(f, "Empty operation set"),
            RustError::AsyncTaskSendFailed { source } => write!(f, "Async task send failed: {}", source),
            RustError::AsyncResultReceiveFailed { source } => write!(f, "Async result receive failed: {}", source),
            RustError::SharedBufferLockFailed => write!(f, "Failed to acquire shared buffer lock"),
        }
    }
}

impl Error for RustError {}

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

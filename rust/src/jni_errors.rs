use std::fmt;
use jni::errors::Error as JniError;

/// Custom error type for JNI operations
#[derive(Debug)]
pub enum JniOperationError {
    /// Null pointer error
    NullPointer,
    /// Invalid argument error
    InvalidArgument,
    /// Operation failed
    OperationFailed,
    /// Connection error
    ConnectionError,
    /// Serialization error
    SerializationError,
    /// Deserialization error
    DeserializationError,
    /// Memory error
    MemoryError,
    /// Timeout error
    TimeoutError,
    /// Other error with message
    Other(String),
    /// JNI system error
    JniSystemError(JniError),
}

impl fmt::Display for JniOperationError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            JniOperationError::NullPointer => write!(f, "JNI_ERROR_NULL_POINTER"),
            JniOperationError::InvalidArgument => write!(f, "JNI_ERROR_INVALID_ARGUMENT"),
            JniOperationError::OperationFailed => write!(f, "JNI_ERROR_OPERATION_FAILED"),
            JniOperationError::ConnectionError => write!(f, "JNI_ERROR_CONNECTION_ERROR"),
            JniOperationError::SerializationError => write!(f, "JNI_ERROR_SERIALIZATION_ERROR"),
            JniOperationError::DeserializationError => write!(f, "JNI_ERROR_DESERIALIZATION_ERROR"),
            JniOperationError::MemoryError => write!(f, "JNI_ERROR_MEMORY_ERROR"),
            JniOperationError::TimeoutError => write!(f, "JNI_ERROR_TIMEOUT_ERROR"),
            JniOperationError::Other(msg) => write!(f, "JNI_ERROR_OTHER: {}", msg),
            JniOperationError::JniSystemError(e) => write!(f, "JNI_SYSTEM_ERROR: {}", e),
        }
    }
}

impl std::error::Error for JniOperationError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            JniOperationError::JniSystemError(e) => Some(e),
            _ => None,
        }
    }
}

impl From<JniError> for JniOperationError {
    fn from(e: JniError) -> Self {
        JniOperationError::JniSystemError(e)
    }
}

/// Macro for standardized JNI error handling
#[macro_export]
macro_rules! jni_try {
    ($expr:expr) => {
        match $expr {
            Ok(val) => val,
            Err(e) => return Err(crate::jni_errors::JniOperationError::Other(e.to_string())),
        };
    };
}

/// Macro for creating error byte arrays for JNI
#[macro_export]
macro_rules! jni_error_bytes {
    ($env:expr, $err:expr) => {{
        let error_msg = format!("{}", $err);
        match $env.byte_array_from_slice(error_msg.as_bytes()) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }};
}

#[cfg(test)]
mod tests {
    use super::*;
    use jni::errors::Error;

    #[test]
    fn test_jni_operation_error_display() {
        let null_error = JniOperationError::NullPointer;
        let invalid_arg = JniOperationError::InvalidArgument;
        let custom_error = JniOperationError::Other("test error".to_string());
        let jni_error = JniOperationError::JniSystemError(Error::InvalidSignature("test".to_string()));

        assert_eq!(format!("{}", null_error), "JNI_ERROR_NULL_POINTER");
        assert_eq!(format!("{}", invalid_arg), "JNI_ERROR_INVALID_ARGUMENT");
        assert_eq!(format!("{}", custom_error), "JNI_ERROR_OTHER: test error");
        assert!(format!("{}", jni_error).starts_with("JNI_SYSTEM_ERROR:"));
    }

    #[test]
    fn test_jni_operation_error_source() {
        let jni_error = JniOperationError::JniSystemError(Error::InvalidSignature("test".to_string()));
        assert!(jni_error.source().is_some());
        
        let null_error = JniOperationError::NullPointer;
        assert!(null_error.source().is_none());
    }

    #[test]
    fn test_jni_operation_error_from() {
        let jni_error = Error::InvalidSignature("test".to_string());
        let operation_error = JniOperationError::from(jni_error);
        match operation_error {
            JniOperationError::JniSystemError(_) => assert!(true),
            _ => assert!(false),
        }
    }
}
use std::fmt;

/// JNI operation error
#[derive(Debug, Clone)]
pub struct JniOperationError {
    pub message: String,
    pub operation: String,
}

impl JniOperationError {
    pub fn new(operation: String, message: String) -> Self {
        Self { operation, message }
    }
}

impl fmt::Display for JniOperationError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "JNI operation '{}' failed: {}", self.operation, self.message)
    }
}

impl std::error::Error for JniOperationError {}

/// Create error byte array for JNI
pub fn jni_error_bytes(error: &str) -> Vec<u8> {
    error.as_bytes().to_vec()
}
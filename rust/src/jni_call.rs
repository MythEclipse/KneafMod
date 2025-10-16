use jni::JNIEnv;

/// Request structure for JNI calls
#[derive(Debug, Clone)]
pub struct JniRequest {
    pub operation: String,
    pub data: Vec<u8>,
    pub timeout_ms: u64,
}

impl JniRequest {
    pub fn new(operation: String, data: Vec<u8>) -> Self {
        Self {
            operation,
            data,
            timeout_ms: 5000,
        }
    }

    pub fn with_timeout(mut self, timeout_ms: u64) -> Self {
        self.timeout_ms = timeout_ms;
        self
    }
}

/// Build a JNI request
pub fn build_request(operation: String, data: Vec<u8>) -> JniRequest {
    JniRequest::new(operation, data)
}

/// Create error byte array for JNI
pub fn create_error_byte_array(env: &mut JNIEnv, message: &str) -> Result<jni::sys::jbyteArray, String> {
    env.byte_array_from_slice(message.as_bytes())
        .map(|arr| arr.into_raw())
        .map_err(|e| format!("Failed to create error byte array: {}", e))
}
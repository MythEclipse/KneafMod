use jni::{JNIEnv, objects::JString, sys::{jstring, jbyteArray}};
use std::sync::{Arc, Mutex, RwLock};
use crate::errors::{Result, RustError};

/// Thread-safe JNI environment wrapper
#[derive(Clone)]
pub struct ThreadSafeJniEnv {
    env: Arc<RwLock<Option<JniEnvWrapper>>>,
}

/// Wrapper for JNI environment to make it thread-safe
struct JniEnvWrapper {
    env: *mut jni::sys::JNIEnv,
}

// SAFETY: We ensure thread safety through proper synchronization
unsafe impl Send for JniEnvWrapper {}
unsafe impl Sync for JniEnvWrapper {}

impl ThreadSafeJniEnv {
    pub fn new() -> Self {
        Self {
            env: Arc::new(RwLock::new(None)),
        }
    }
    
    pub fn set_env(&self, env: *mut jni::sys::JNIEnv) {
        let mut guard = self.env.write().unwrap();
        *guard = Some(JniEnvWrapper { env });
    }
    
    pub fn get_env(&self) -> Option<*mut jni::sys::JNIEnv> {
        let guard = self.env.read().unwrap();
        guard.as_ref().map(|wrapper| wrapper.env)
    }
    
    pub fn is_attached(&self) -> bool {
        let guard = self.env.read().unwrap();
        guard.is_some()
    }
}

/// Global JNI environment manager
static JNI_ENV_MANAGER: std::sync::OnceLock<ThreadSafeJniEnv> = std::sync::OnceLock::new();

/// Get the global JNI environment manager
pub fn get_jni_env_manager() -> ThreadSafeJniEnv {
    JNI_ENV_MANAGER.get_or_init(|| ThreadSafeJniEnv::new()).clone()
}

/// Get the current JNI environment
pub fn get_jni_env() -> Option<*mut jni::sys::JNIEnv> {
    get_jni_env_manager().get_env()
}

/// Check if JNI environment is attached
pub fn is_jni_env_attached() -> bool {
    get_jni_env_manager().is_attached()
}

/// Convert JNI string to Rust String
pub fn jni_string_to_rust(env: &JNIEnv, string: jstring) -> Result<String> {
    if string.is_null() {
        return Err(RustError::JniError("Null JNI string".to_string()));
    }

    let java_string = unsafe { JString::from_raw(string) };
    match env.get_string(&java_string) {
        Ok(s) => Ok(s.to_string_lossy().to_string()),
        Err(e) => Err(RustError::JniError(format!("Failed to convert JNI string: {}", e))),
    }
}

/// Check JNI result and convert to Rust Result
pub fn check_jni_result<T>(result: jni::errors::Result<T>, operation: &str) -> Result<T> {
    match result {
        Ok(value) => Ok(value),
        Err(e) => Err(RustError::JniError(format!("JNI operation '{}' failed: {}", operation, e))),
    }
}

/// Create error JNI string
pub fn create_error_jni_string(env: &JNIEnv, error: &str) -> jstring {
    match env.new_string(error) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Convert Rust string to JNI string
pub fn rust_string_to_jni(env: &JNIEnv, string: &str) -> Result<jstring> {
    match env.new_string(string) {
        Ok(s) => Ok(s.into_raw()),
        Err(e) => Err(RustError::JniError(format!("Failed to create JNI string: {}", e))),
    }
}

/// Convert byte array to JNI byte array
pub fn bytes_to_jni_array(env: &JNIEnv, bytes: &[u8]) -> Result<jbyteArray> {
    match env.byte_array_from_slice(bytes) {
        Ok(array) => Ok(array),
        Err(e) => Err(RustError::JniError(format!("Failed to create JNI byte array: {}", e))),
    }
}

/// Convert JNI byte array to Rust bytes
pub fn jni_array_to_bytes(env: &JNIEnv, array: jbyteArray) -> Result<Vec<u8>> {
    if array.is_null() {
        return Err(RustError::JniError("Null JNI byte array".to_string()));
    }

    let len = match env.get_array_length(array) {
        Ok(len) => len,
        Err(e) => return Err(RustError::JniError(format!("Failed to get array length: {}", e))),
    };

    let mut bytes = vec![0u8; len as usize];
    match env.get_byte_array_region(array, 0, &mut bytes) {
        Ok(_) => Ok(bytes),
        Err(e) => Err(RustError::JniError(format!("Failed to get byte array: {}", e))),
    }
}

/// Safe JNI call wrapper with error handling
pub fn safe_jni_call<F, R>(env: &JNIEnv, operation: &str, func: F) -> Result<R>
where
    F: FnOnce(&JNIEnv) -> jni::errors::Result<R>,
{
    match func(env) {
        Ok(result) => Ok(result),
        Err(e) => Err(RustError::JniError(format!("JNI operation '{}' failed: {}", operation, e))),
    }
}

/// JNI call result type alias
pub type JniCallResult<T> = Result<T>;

/// Initialize JNI environment
pub fn initialize_jni_env(env: *mut jni::sys::JNIEnv) {
    let manager = get_jni_env_manager();
    manager.set_env(env);
}

/// Cleanup JNI environment
pub fn cleanup_jni_env() {
    let manager = get_jni_env_manager();
    manager.set_env(std::ptr::null_mut());
}

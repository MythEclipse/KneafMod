use jni::{JNIEnv, objects::{JString, JByteArray}, sys::{jstring, jbyteArray}};
use crate::errors::{RustError, Result};
use std::sync::{OnceLock, Mutex};
use std::marker::PhantomData;
use once_cell::sync::Lazy;
use std::cell::UnsafeCell;

pub fn jni_string_to_rust(env: &mut JNIEnv, j_str: JString) -> Result<String> {
    env.get_string(&j_str).map(|s| s.to_string_lossy().into_owned()).map_err(|e| RustError::JniError(e.to_string()))
}

pub fn create_error_jni_string(env: &mut JNIEnv, error_msg: &str) -> Result<jstring> {
    let error_str = format!("ERROR: {}", error_msg);
    env.new_string(error_str).map(|s| s.into_raw()).map_err(|e| RustError::JniError(e.to_string()))
}
use std::fmt::Display;

/// Re-export the JniConverter trait and factory for convenience
pub use crate::jni_converter_factory::{JniConverter, JniConverterFactory};

/// Check if JNI environment operation succeeded
pub fn check_jni_result<T, E: Display>(
    result: std::result::Result<T, E>,
    context: &str,
) -> crate::errors::Result<T> {
    result.map_err(|e| crate::errors::RustError::JniError(format!("{}: {}", context, e)))
}

/// Get the current JNI environment (for internal use)
/// JNI environment accessor (internal use only)
#[derive(Debug)]
pub struct JniEnvAccessorImpl {
    env: UnsafeCell<*mut JNIEnv<'static>>,
    initialized: OnceLock<bool>,
}

impl JniEnvAccessorImpl {
    /// Get singleton instance
    pub fn instance() -> &'static Self {
        static INSTANCE: OnceLock<Self> = OnceLock::new();
        INSTANCE.get_or_init(|| Self {
            env: UnsafeCell::new(std::ptr::null_mut()),
            initialized: OnceLock::new(),
        })
    }

    /// Initialize with JNI environment pointer
    pub fn initialize(&self, env: *mut JNIEnv) -> Result<()> {
        if self.initialized.get().is_some() {
            return Ok(());
        }

        // Safety: We're the only one initializing this
        unsafe {
            *self.env.get() = env as *mut JNIEnv<'static>;
        }

        self.initialized.set(true).map_err(|_| {
            RustError::JniError("Failed to mark JNI environment as initialized".into())
        })
    }

    /// Get JNI environment pointer (unsafe - caller must ensure thread safety)
    pub unsafe fn get_env(&self) -> *mut JNIEnv<'static> {
        *self.env.get()
    }

    /// Check if JNI environment is initialized
    pub fn is_initialized(&self) -> bool {
        self.initialized.get().is_some()
    }
}

/// Thread-safe JNI environment accessor
#[derive(Debug, Clone)]
pub struct JniEnvAccess {
    accessor: &'static JniEnvAccessorImpl,
}

impl JniEnvAccess {
    /// Create new JNI environment accessor
    pub fn new() -> Self {
        Self {
            accessor: JniEnvAccessorImpl::instance(),
        }
    }

    /// Get JNI environment pointer (safe wrapper)
    pub fn get_env(&self) -> Result<*mut JNIEnv<'static>> {
        if !self.accessor.is_initialized() {
            return Err(RustError::JniError(
                "JNI environment not initialized. Call initialize_jni_env first.".into()
            ));
        }

        Ok(unsafe { self.accessor.get_env() })
    }

    /// Check if JNI environment is attached to current thread
    pub fn is_attached(&self) -> Result<bool> {
        static ATTACHED: OnceLock<Mutex<bool>> = OnceLock::new();
        let attached = ATTACHED.get_or_init(|| Mutex::new(false)).lock()
            .map_err(|e| RustError::JniError(format!("Failed to check attachment: {}", e)))?;
        Ok(*attached)
    }

    /// Set JNI attachment status
    pub fn set_attached(&self, status: bool) -> Result<()> {
        static ATTACHED: OnceLock<Mutex<bool>> = OnceLock::new();
        let mut attached = ATTACHED.get_or_init(|| Mutex::new(false)).lock()
            .map_err(|e| RustError::JniError(format!("Failed to set attachment: {}", e)))?;
        *attached = status;
        Ok(())
    }
}

/// Initialize JNI environment (call once at application startup)
pub fn initialize_jni_env(env: *mut JNIEnv) -> Result<()> {
    JniEnvAccessorImpl::instance().initialize(env)
}

/// Get JNI environment accessor (for internal use)
pub fn get_jni_env() -> JniEnvAccess {
    JniEnvAccess::new()
}

/// Check if JNI environment is attached to current thread
pub fn is_jni_env_attached() -> Result<bool> {
    get_jni_env().is_attached()
}

/// Set JNI attachment status (internal use only)
pub fn set_jni_env_attached(status: bool) -> Result<()> {
    get_jni_env().set_attached(status)
}

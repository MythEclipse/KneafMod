use jni::{JNIEnv, objects::{JString, JByteArray}, sys::{jstring, jbyteArray}};
use crate::errors::{RustError, Result};

pub fn jni_string_to_rust(env: &mut JNIEnv, j_str: *mut jstring) -> Result<String> {
    let j_str_obj = env.get_string(j_str)?;
    Ok(j_str_obj.to_string_lossy().to_string())
}

pub fn create_error_jni_string(env: &mut JNIEnv, error_msg: &str) -> Result<*mut jstring> {
    let error_str = format!("ERROR: {}", error_msg);
    Ok(env.new_string(error_str)?)
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

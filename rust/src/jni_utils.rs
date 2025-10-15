use jni::{JNIEnv, objects::{JString, JByteArray}, sys::{jstring, jbyteArray}};
use std::fmt::Display;
use crate::errors::{RustError, Result};

/// Convert JNI string to Rust string with proper error handling
pub fn jni_string_to_rust<'a>(env: &mut JNIEnv<'a>, j_str: JString<'a>) -> Result<String> {
    env.get_string(&j_str)
        .map_err(|e| RustError::ConversionError(format!("Failed to convert JString to Rust string: {}", e)))
        .map(|s| s.to_string_lossy().to_string())
}

/// Create JNI string from Rust string with proper error handling
pub fn rust_string_to_jni<'a>(env: &mut JNIEnv<'a>, s: &str) -> Result<jstring> {
    env.new_string(s)
        .map(|s| s.into_raw())
        .map_err(|e| RustError::ConversionError(format!("Failed to convert Rust string to JString: {}", e)))
}

/// Create error JNI string with proper error handling
pub fn create_error_jni_string<'a>(env: &mut JNIEnv<'a>, error: &str) -> jstring {
    match rust_string_to_jni(env, error) {
        Ok(s) => s,
        Err(_) => {
            let fallback = env.new_string(error).unwrap_or_else(|_| env.new_string("").unwrap());
            fallback.into_raw()
        }
    }
}

/// Helper macro for JNI error handling that returns jbyteArray
#[macro_export]
macro_rules! jni_byte_error {
    ($env:expr, $err:expr) => {{
        let error_msg = format!("{}", $err);
        match $env.byte_array_from_slice(error_msg.as_bytes()) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }};
}

/// Check if JNI environment operation succeeded
pub fn check_jni_result<T, E: Display>(
    result: std::result::Result<T, E>,
    context: &str,
) -> Result<T> {
    result.map_err(|e| RustError::JniError(format!("{}: {}", context, e)))
}

/// Convert JNI byte array to Rust Vec<u8> with proper error handling
pub fn jbyte_array_to_vec(env: &mut JNIEnv, array: JByteArray) -> Result<Vec<u8>> {
    env.convert_byte_array(array)
        .map_err(|e| RustError::ConversionError(format!("Failed to convert JByteArray to Vec<u8>: {}", e)))
}

/// Convert Rust Vec<u8> to JNI byte array with proper error handling
pub fn vec_to_jbyte_array(env: &mut JNIEnv, vec: &[u8]) -> Result<jbyteArray> {
    env.byte_array_from_slice(vec)
        .map(|arr| arr.into_raw())
        .map_err(|e| RustError::ConversionError(format!("Failed to convert Vec<u8> to JByteArray: {}", e)))
}
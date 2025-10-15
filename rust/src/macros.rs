use crate::errors::{RustError, messages};
use std::result::Result;
use jni::JNIEnv;
use jni::sys::jstring;

/// Macro to create a JNI error string from a RustError
#[macro_export]
macro_rules! jni_error {
    ($env:expr, $error:expr) => {
        match $env.new_string($error.to_string()) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    };
}

/// Macro to create a JNI error string from a format string
#[macro_export]
macro_rules! jni_format_error {
    ($env:expr, $fmt:expr) => {
        match $env.new_string(format!($fmt)) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    };
    ($env:expr, $fmt:expr, $($arg:tt)*) => {
        match $env.new_string(format!($fmt, $($arg)*)) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    };
}

/// Macro to check if a JNI string is null and return an error if it is
#[macro_export]
macro_rules! check_jni_string {
    ($env:expr, $input:expr, $component:expr) => {
        if $input.is_null() {
            return Err(RustError::InvalidInputError(messages::INPUT_NULL.to_string()));
        }
        let input_str = match $env.get_string($input) {
            Ok(s) => s,
            Err(e) => {
                return Err(RustError::JniError(format!(
                    "Failed to get string from JNI: {}",
                    e
                )))
            }
        };
        let input_str = match input_str.to_str() {
            Ok(s) => s.to_string(),
            Err(e) => {
                return Err(RustError::InvalidInputError(format!(
                    messages::INVALID_UTF8, e
                )))
            }
        };
        input_str
    };
}

/// Macro to handle common JNI operation patterns with error checking
#[macro_export]
macro_rules! jni_operation {
    ($env:expr, $component:expr, $operation:expr, $result:expr) => {
        match $result {
            Ok(value) => value,
            Err(e) => {
                let error_msg = format!("{} operation failed: {}", $operation, e);
                error!("{}: {}", $component, error_msg);
                
                // Create and throw Java exception
                let exception_class = $env.find_class("java/lang/Exception").unwrap();
                let _ = $env.throw_new(&exception_class, &error_msg);
                
                return std::ptr::null_mut();
            }
        }
    };
}

/// Macro to handle common JNI boolean result patterns
#[macro_export]
macro_rules! jni_boolean_result {
    ($env:expr, $component:expr, $operation:expr, $result:expr) => {
        match $result {
            Ok(_) => {
                info!("{} {} succeeded", $component, $operation);
                1
            }
            Err(e) => {
                let error_msg = format!("{} {} failed: {}", $component, $operation, e);
                error!("{}", error_msg);
                
                let exception_class = $env.find_class("java/lang/Exception").unwrap();
                let _ = $env.throw_new(&exception_class, &error_msg);
                
                0
            }
        }
    };
}

/// Macro to validate that a key is not empty
#[macro_export]
macro_rules! validate_key {
    ($key:expr) => {
        if $key.is_empty() {
            return Err(RustError::InvalidInputError(messages::KEY_EMPTY.to_string()));
        }
    };
}

/// Macro to validate that data is not empty
#[macro_export]
macro_rules! validate_data {
    ($data:expr) => {
        if $data.is_empty() {
            return Err(RustError::InvalidInputError(messages::DATA_EMPTY.to_string()));
        }
    };
}

/// Macro to check if a component is initialized before proceeding
#[macro_export]
macro_rules! check_initialized {
    ($component:expr) => {
        $component.check_initialized()?;
    };
}
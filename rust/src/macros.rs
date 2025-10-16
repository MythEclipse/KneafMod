// Macros should reference the crate-local error types with fully-qualified paths
use log::{info, warn, error, debug};

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
    // Returns a Rust String. On error it will throw a Java exception and return an empty String.
    ($env:expr, $input:expr, $component:expr) => {{
        // Evaluate to a String expression; do not use `return` inside the macro so
        // callers control outer function returns.
        if $input.is_null() {
            let _ = $env.find_class("java/lang/IllegalArgumentException")
                .and_then(|cls| $env.throw_new(&cls, crate::errors::messages::INPUT_NULL));
            String::new()
        } else {
            match $env.get_string($input) {
                Ok(jstr) => match jstr.to_str() {
                    Ok(s) => s.to_string(),
                    Err(e) => {
                        let _ = $env.find_class("java/lang/IllegalArgumentException")
                            .and_then(|cls| $env.throw_new(&cls, &format!("{}: Invalid UTF-8: {}", $component, e)));
                        String::new()
                    }
                },
                Err(e) => {
                    let _ = $env.find_class("java/lang/RuntimeException")
                        .and_then(|cls| $env.throw_new(&cls, &format!("Failed to get string from JNI: {}", e)));
                    String::new()
                }
            }
        }
    }};
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
/// Macro for unified error handling in JNI operations
#[macro_export]
macro_rules! handle_jni_error {
    ($env:expr, $operation:expr, $error:expr) => {{
        let error_msg = format!("{} failed: {}", $operation, $error);
        error!("JNI operation error: {}", error_msg);

        // Create and throw Java exception
        if let Ok(exception_class) = $env.find_class("java/lang/RuntimeException") {
            let _ = $env.throw_new(&exception_class, &error_msg);
        }

        std::ptr::null_mut()
    }};
}

/// Macro for safe JNI byte array conversion with validation
#[macro_export]
macro_rules! safe_convert_byte_array {
    ($env:expr, $array:expr, $operation:expr) => {
        match $env.convert_byte_array($array) {
            Ok(data) => {
                if data.is_empty() {
                    handle_jni_error!($env, $operation, "Empty byte array provided");
                }
                data
            }
            Err(e) => {
                handle_jni_error!($env, $operation, format!("Failed to convert byte array: {}", e));
            }
        }
    };
}

/// Macro for validating buffer size limits
#[macro_export]
macro_rules! validate_buffer_size {
    ($data:expr, $max_size:expr, $operation:expr) => {
        if $data.len() > $max_size {
            return Err(RustError::InvalidInputError(format!(
                "{}: Buffer size {} exceeds maximum allowed size {}",
                $operation, $data.len(), $max_size
            )));
        }
    };
}


/// Macro for consistent operation result handling
#[macro_export]
macro_rules! handle_operation_result {
    ($result:expr, $operation:expr) => {
        match $result {
            Ok(value) => {
                info!("{} succeeded", $operation);
                Ok(value)
            }
            Err(e) => {
                error!("{} failed: {}", $operation, e);
                Err(RustError::OperationError(format!("{}: {}", $operation, e)))
            }
        }
    };
}

/// Macro for safe JNI string validation and conversion
#[macro_export]
macro_rules! validate_jni_string {
    ($env:expr, $jstring:expr, $operation:expr) => {
        if $jstring.is_null() {
            return Err(RustError::InvalidInputError(format!(
                "{}: Null string provided", $operation
            )));
        }

        match $env.get_string($jstring) {
            Ok(jstr) => match jstr.to_str() {
                Ok(s) => {
                    if s.is_empty() {
                        return Err(RustError::InvalidInputError(format!(
                            "{}: Empty string provided", $operation
                        )));
                    }
                    s.to_string()
                }
                Err(e) => {
                    return Err(RustError::InvalidInputError(format!(
                        "{}: Invalid UTF-8 string: {}", $operation, e
                    )));
                }
            },
            Err(e) => {
                return Err(RustError::JniError(format!(
                    "{}: Failed to get string from JNI: {}", $operation, e
                )));
            }
        }
    };
}

/// Macro for creating consistent error responses for JNI
#[macro_export]
macro_rules! create_jni_error_response {
    ($env:expr, $error:expr) => {
        match $env.byte_array_from_slice($error.to_string().as_bytes()) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    };
}

/// Macro for safe buffer allocation with size validation
#[macro_export]
macro_rules! safe_allocate_buffer {
    ($size:expr, $max_size:expr, $operation:expr) => {
        if $size > $max_size {
            return Err(RustError::InvalidInputError(format!(
                "{}: Requested buffer size {} exceeds maximum allowed size {}",
                $operation, $size, $max_size
            )));
        }

        if $size == 0 {
            return Err(RustError::InvalidInputError(format!(
                "{}: Cannot allocate buffer of size 0", $operation
            )));
        }

        Vec::with_capacity($size)
    };
}

/// Macro for memory pool error handling
#[macro_export]
macro_rules! memory_pool_error {
    ($($arg:tt)*) => {
        return Err(crate::errors::RustError::MemoryPoolError(format!($($arg)*)));
    };
}

/// Macro for JNI diagnostic logging
#[macro_export]
macro_rules! jni_diagnostic {
    ($level:expr, $component:expr, $($arg:tt)*) => {
        match $level {
            "INFO" => info!("[{}] {}", $component, format!($($arg)*)),
            "WARN" => warn!("[{}] {}", $component, format!($($arg)*)),
            "ERROR" => error!("[{}] {}", $component, format!($($arg)*)),
            "DEBUG" => debug!("[{}] {}", $component, format!($($arg)*)),
            _ => info!("[{}] {}", $component, format!($($arg)*)),
        }
    };
}
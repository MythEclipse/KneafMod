use jni::{JNIEnv, objects::{JString, JByteArray, JObject}, sys::jstring};
use std::fmt::Display;
use crate::errors::{RustError, Result};

/// Trait defining the conversion capabilities for JNI types
pub trait JniConverter {
    /// Convert JNI string to Rust string
    fn jstring_to_rust(&self, env: &mut JNIEnv, j_str: JString) -> Result<String>;
    
    /// Convert Rust string to JNI string
    fn rust_string_to_jni(&self, env: &mut JNIEnv, s: &str) -> Result<jstring>;
    
    /// Convert JNI byte array to Rust Vec<u8>
    fn jbyte_array_to_vec(&self, env: &mut JNIEnv, array: JByteArray) -> Result<Vec<u8>>;
    
    /// Convert Rust Vec<u8> to JNI byte array
    fn vec_to_jbyte_array(&self, env: &mut JNIEnv, vec: &[u8]) -> Result<JByteArray>;
    
    /// Create error JNI string
    fn create_error_jni_string(&self, env: &mut JNIEnv, error: &str) -> jstring;
}

/// Default implementation of JniConverter with standard behavior
pub struct DefaultJniConverter;

impl JniConverter for DefaultJniConverter {
    fn jstring_to_rust(&self, env: &mut JNIEnv, j_str: JString) -> Result<String> {
        env.get_string(&j_str)
            .map_err(|e| RustError::ConversionError(format!("Failed to convert JString to Rust string: {}", e)))
            .map(|s| s.to_string_lossy().to_string())
    }

    fn rust_string_to_jni(&self, env: &mut JNIEnv, s: &str) -> Result<jstring> {
        env.new_string(s)
            .map(|s| s.into_raw())
            .map_err(|e| RustError::ConversionError(format!("Failed to convert Rust string to JString: {}", e)))
    }

    fn jbyte_array_to_vec(&self, env: &mut JNIEnv, array: JByteArray) -> Result<Vec<u8>> {
        env.convert_byte_array(array)
            .map_err(|e| RustError::ConversionError(format!("Failed to convert JByteArray to Vec<u8>: {}", e)))
    }

    fn vec_to_jbyte_array(&self, env: &mut JNIEnv, vec: &[u8]) -> Result<JByteArray> {
        env.byte_array_from_slice(vec)
            .map_err(|e| RustError::ConversionError(format!("Failed to convert Vec<u8> to JByteArray: {}", e)))
    }

    fn create_error_jni_string(&self, env: &mut JNIEnv, error: &str) -> jstring {
        match self.rust_string_to_jni(env, error) {
            Ok(s) => s,
            Err(_) => {
                let fallback = env.new_string(error).unwrap_or_else(|_| env.new_string("").unwrap());
                fallback.into_raw()
            }
        }
    }
}

/// Factory for creating JNI converters with different configurations
pub struct JniConverterFactory;

impl JniConverterFactory {
    /// Create a default JNI converter
    pub fn create_default() -> Box<dyn JniConverter> {
        Box::new(DefaultJniConverter)
    }
    
    /// Create a converter with custom error handling (example configuration)
    pub fn create_with_custom_error_handling() -> Box<dyn JniConverter> {
        Box::new(DefaultJniConverter) // In a real implementation, this would return a different converter
    }
}

impl Default for JniConverterFactory {
    fn default() -> Self {
        JniConverterFactory
    }
}


#[cfg(test)]
mod tests {
    use super::*;
    use jni::errors::Error;
    use jni::objects::JString;
    use jni::sys::jstring;
    use mockall::mock;

    mock! {
        JNIEnv {}
        impl JNIEnv for JNIEnv {
            fn get_string(&mut self, _: &JString) -> Result<jni::objects::String, Error>;
            fn new_string(&mut self, _: &str) -> Result<jni::objects::String, Error>;
            fn convert_byte_array(&mut self, _: JByteArray) -> Result<Vec<u8>, Error>;
            fn byte_array_from_slice(&mut self, _: &[u8]) -> Result<JByteArray, Error>;
        }
    }

    #[test]
    fn test_default_converter() {
        let converter = DefaultJniConverter;
        let mut env = MockJNIEnv::new();
        
        // Set up mock expectations
        env.expect_get_string()
            .returning(|_| Ok(jni::objects::String::from("test")));
        
        let result = converter.jstring_to_rust(&mut env, JString::from_raw(std::ptr::null_mut()));
        assert!(result.is_ok());
    }
}
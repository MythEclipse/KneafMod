use jni::JNIEnv;
use jni::objects::{JObject, JString, JObjectArray, JByteArray};
use jni::sys::jbyteArray;

use crate::jni::converter::factory::{JniConverter, JniConverterFactory};
use crate::jni_errors::jni_error_bytes;

/// Simple request type returned by the parser
pub struct JniRequest {
    pub op_name: String,
    pub params: Vec<u8>,
}

/// Parse operation name and parameters into an owned JniRequest. This takes a
/// mutable reference to the JNIEnv (required by the JNI helper calls) but does
/// not hold that borrow across the call site.
pub fn build_request(env: &mut JNIEnv<'_>, operation: JString<'_>, parameters: JObjectArray<'_>) -> Result<JniRequest, jbyteArray> {
    // Get a default converter from the factory
    let converter = JniConverterFactory::default().create_default_converter().unwrap();
    let op_name: String = match converter.jstring_to_rust(env, operation) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("[rustperf] Failed to parse operation name: {:?}", e);
            return Err(jni_error_bytes!(env, "Failed to parse operation name"));
        }
    };

    // Extract first parameter as byte array
    let params_bytes = match env.get_object_array_element(&parameters, 0) {
        Ok(obj) => match convert_jobject_to_bytes(env, obj) {
            Ok(b) => b,
            Err(_) => return Err(jni_error_bytes!(env, "Failed to convert parameters to bytes")),
        },
        Err(e) => {
            eprintln!("[rustperf] Failed to get parameters array: {:?}", e);
            return Err(jni_error_bytes!(env, "Failed to get parameters array"));
        }
    };

    Ok(JniRequest { op_name, params: params_bytes })
}

/// Convert a JObject (expected to be a byte array) into Vec<u8>. On failure
/// return Err(()) — callers typically convert that into a JNI error reply.
pub fn convert_jobject_to_bytes(env: &mut JNIEnv<'_>, obj: JObject<'_>) -> Result<Vec<u8>, ()> {
    // Try to convert as JByteArray directly - cast to JByteArray first
    let byte_array: JByteArray = obj.into();
    
    // Get a default converter from the factory
    let converter = JniConverterFactory::default().create_default_converter().unwrap();
    
    match converter.jbyte_array_to_vec(env, byte_array) {
        Ok(bytes) => Ok(bytes),
        Err(e) => {
            eprintln!("[rustperf] Failed to convert byte array: {:?}", e);
            Err(())
        }
    }
}

/// Create a JNI-compatible error byte array to return to Java
pub fn create_error_byte_array(env: &mut JNIEnv<'_>, error_msg: &str) -> jbyteArray {
    // Use the new jni_error_bytes macro for consistency
    jni_error_bytes!(env, error_msg)
}

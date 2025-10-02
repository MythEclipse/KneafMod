use jni::JNIEnv;
use jni::objects::{JClass, JString, JByteBuffer, JObject};
use jni::sys::{jbyteArray, jstring};
use crate::item::processing::process_item_entities;
use crate::binary::conversions::{deserialize_item_input, serialize_item_result};
use crate::logging::JniLogger;
use crate::jni_log_error;
use std::sync::OnceLock;

static LOGGER: OnceLock<JniLogger> = OnceLock::new();

fn get_logger() -> &'static JniLogger {
    LOGGER.get_or_init(|| JniLogger::new("item"))
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_RustPerformance_processItemEntitiesBinaryNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    input_buffer: JObject<'local>,
) -> jbyteArray {
    let logger = get_logger();
    // Removed debug log to reduce noise - only log errors

    // Get direct access to the ByteBuffer data
    let input_buffer = JByteBuffer::from(input_buffer);
    let data = match env.get_direct_buffer_address(&input_buffer) {
        Ok(data) => data,
        Err(e) => {
            jni_log_error!(logger, &mut env, "JNI", &format!("Failed to get direct buffer address: {:?}", e));
            let error_msg = b"{\"error\":\"Direct ByteBuffer required\"}";
            return crate::logging::make_jni_error_bytes(&env, error_msg);
        }
    };

    let capacity = match env.get_direct_buffer_capacity(&input_buffer) {
        Ok(capacity) => capacity,
        Err(e) => {
            jni_log_error!(logger, &mut env, "JNI", &format!("Failed to get ByteBuffer capacity: {:?}", e));
            let error_msg = b"{\"error\":\"Failed to get ByteBuffer capacity\"}";
            return crate::logging::make_jni_error_bytes(&env, error_msg);
        }
    };

    // Removed debug log to reduce noise - only log errors
    let slice = unsafe {
        std::slice::from_raw_parts(data, capacity)
    };

    // Removed debug log to reduce noise - only log errors
    // Process binary data in batches for better JNI performance
    match process_item_entities_binary_batch(slice) {
        Ok(result) => {
            // Removed debug log to reduce noise - only log errors
            match env.byte_array_from_slice(&result) {
                Ok(arr) => arr.into_raw(),
                Err(e) => {
                    jni_log_error!(logger, &mut env, "JNI", &format!("Failed to create byte array from result: {:?}", e));
                    std::ptr::null_mut()
                }
            }
        },
        Err(e) => {
            jni_log_error!(logger, &mut env, "JNI", &format!("process_item_entities_binary_batch failed: {}", e));
            let error_msg = format!("{{\"error\":\"{}\"}}", e);
            match env.byte_array_from_slice(error_msg.as_bytes()) {
                Ok(arr) => arr.into_raw(),
                Err(_) => std::ptr::null_mut(),
            }
        }
    }
}

/// Process item entities from binary input in batches for better JNI performance
fn process_item_entities_binary_batch(data: &[u8]) -> Result<Vec<u8>, String> {
    let _logger = get_logger();
    // We need a mutable environment for logging, but we don't have one in this function.
    // We'll skip logging here since the calling function already logs the important information.
    
    // Expect a single manual-format message produced by Java's ManualSerializers.
    if data.is_empty() {
        return Ok(Vec::new());
    }

    // Deserialize the manual item input layout (little-endian) and process it.
    let input = deserialize_item_input(data).map_err(|e| format!("Failed to deserialize item input: {}", e))?;
    let result = process_item_entities(input);
    let out = serialize_item_result(&result).map_err(|e| format!("Failed to serialize item result: {}", e))?;
    Ok(out)
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_RustPerformance_processItemEntitiesNative(
    mut env: JNIEnv,
    _class: JClass,
    json_input: JString,
) -> jstring {
    let logger = get_logger();
    // Removed debug log to reduce noise - only log errors

    let input_str = match env.get_string(&json_input) {
        Ok(s) => match s.to_str() {
            Ok(st) => st.to_owned(),
            Err(e) => {
                let error_msg = format!("{{\"error\":\"Invalid UTF-8 input: {}\"}}", e);
                jni_log_error!(logger, &mut env, "JNI", &format!("Invalid UTF-8 input: {}", e));
                return crate::logging::make_jni_error(&env, &error_msg);
            },
        },
        Err(e) => {
            let error_msg = format!("{{\"error\":\"Failed to read input string: {}\"}}", e);
            jni_log_error!(logger, &mut env, "JNI", &format!("Failed to read input string: {}", e));
            return crate::logging::make_jni_error(&env, &error_msg);
        }
    };

    // Removed debug log to reduce noise - only log errors
    match crate::item::processing::process_item_entities_json(&input_str) {
        Ok(result_json) => {
            // Removed debug log to reduce noise - only log errors
            match env.new_string(result_json) {
                Ok(s) => s.into_raw(),
                Err(e) => {
                    jni_log_error!(logger, &mut env, "JNI", &format!("Failed to create JString from result: {:?}", e));
                    std::ptr::null_mut()
                }
            }
        },
        Err(e) => {
            jni_log_error!(logger, &mut env, "JNI", &format!("process_item_entities_json failed: {}", e));
            let error_msg = format!("{{\"error\":\"{}\"}}", e);
            crate::logging::make_jni_error(&env, &error_msg)
        }
    }
}
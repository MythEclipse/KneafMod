use jni::objects::{JByteBuffer, JClass, JObject, JString};
use jni::sys::{jbyteArray, jstring};
use jni::JNIEnv;

use crate::logging::JniLogger;
use crate::mob::processing::process_mob_ai_binary_batch;
use std::sync::OnceLock;
use crate::jni_log_error;

static LOGGER: OnceLock<JniLogger> = OnceLock::new();

fn get_logger() -> &'static JniLogger {
    LOGGER.get_or_init(|| JniLogger::new("mob"))
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_RustPerformance_processMobAiNative(
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
                jni_log_error!(
                    logger,
                    &mut env,
                    "JNI",
                    &format!("Invalid UTF-8 input: {}", e)
                );
                return crate::logging::make_jni_error(&env, &error_msg);
            }
        },
        Err(e) => {
            let error_msg = format!("{{\"error\":\"Failed to read input string: {}\"}}", e);
            jni_log_error!(
                logger,
                &mut env,
                "JNI",
                &format!("Failed to read input string: {}", e)
            );
            return crate::logging::make_jni_error(&env, &error_msg);
        }
    };

    // Removed debug log to reduce noise - only log errors
    match crate::mob::processing::process_mob_ai_json(&input_str) {
        Ok(result_json) => {
            // Removed debug log to reduce noise - only log errors
            match env.new_string(result_json) {
                Ok(s) => s.into_raw(),
                Err(e) => {
                    jni_log_error!(
                        logger,
                        &mut env,
                        "JNI",
                        &format!("Failed to create JString from result: {:?}", e)
                    );
                    std::ptr::null_mut()
                }
            }
        }
        Err(e) => {
            jni_log_error!(
                logger,
                &mut env,
                "JNI",
                &format!("process_mob_ai_json failed: {}", e)
            );
            let error_msg = format!("{{\"error\":\"{}\"}}", e);
            crate::logging::make_jni_error(&env, &error_msg)
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_RustPerformance_processMobAiBinaryNative<
    'local,
>(
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
            jni_log_error!(
                logger,
                &mut env,
                "JNI",
                &format!("Failed to get direct buffer address: {:?}", e)
            );
            let error_msg = b"{\"error\":\"Direct ByteBuffer required\"}";
            return crate::logging::make_jni_error_bytes(&env, error_msg);
        }
    };

    let capacity = match env.get_direct_buffer_capacity(&input_buffer) {
        Ok(capacity) => capacity,
        Err(e) => {
            jni_log_error!(
                logger,
                &mut env,
                "JNI",
                &format!("Failed to get ByteBuffer capacity: {:?}", e)
            );
            let error_msg = b"{\"error\":\"Failed to get ByteBuffer capacity\"}";
            return crate::logging::make_jni_error_bytes(&env, error_msg);
        }
    };

    // Removed debug log to reduce noise - only log errors
    let slice = unsafe { std::slice::from_raw_parts(data, capacity) };

    // Removed debug log to reduce noise - only log errors
    // Process binary data in batches for better JNI performance
    match process_mob_ai_binary_batch(slice) {
        Ok(result) => {
            // Removed debug log to reduce noise - only log errors
            match env.byte_array_from_slice(&result) {
                Ok(arr) => arr.into_raw(),
                Err(e) => {
                    jni_log_error!(
                        logger,
                        &mut env,
                        "JNI",
                        &format!("Failed to create byte array from result: {:?}", e)
                    );
                    std::ptr::null_mut()
                }
            }
        }
        Err(e) => {
            jni_log_error!(
                logger,
                &mut env,
                "JNI",
                &format!("process_mob_ai_binary_batch failed: {}", e)
            );
            let error_msg = format!("{{\"error\":\"{}\"}}", e);
            match env.byte_array_from_slice(error_msg.as_bytes()) {
                Ok(arr) => arr.into_raw(),
                Err(_) => std::ptr::null_mut(),
            }
        }
    }
}

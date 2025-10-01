use jni::JNIEnv;
use jni::objects::{JClass, JString, JByteBuffer, JObject};
use jni::sys::{jstring, jbyteArray};


use crate::mob::processing::process_mob_ai_binary_batch;

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_RustPerformance_processMobAiNative(
    mut env: JNIEnv,
    _class: JClass,
    json_input: JString,
) -> jstring {

    let input_str = match env.get_string(&json_input) {
        Ok(s) => match s.to_str() {
            Ok(st) => st.to_owned(),
            Err(e) => return match env.new_string(format!("{{\"error\":\"Invalid UTF-8 input: {}\"}}", e)) {
                Ok(s) => s.into_raw(),
                Err(_) => std::ptr::null_mut(),
            },
        },
        Err(e) => return match env.new_string(format!("{{\"error\":\"Failed to read input string: {}\"}}", e)) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
    };

    match crate::mob::processing::process_mob_ai_json(&input_str) {
        Ok(result_json) => match env.new_string(result_json) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(e) => match env.new_string(format!("{{\"error\":\"{}\"}}", e)) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_RustPerformance_processMobAiBinaryNative<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    input_buffer: JObject<'local>,
) -> jbyteArray {
    // Get direct access to the ByteBuffer data
    let input_buffer = JByteBuffer::from(input_buffer);
    let data = match env.get_direct_buffer_address(&input_buffer) {
        Ok(data) => data,
        Err(_) => {
            let error_msg = b"{\"error\":\"Direct ByteBuffer required\"}";
            return match env.byte_array_from_slice(error_msg) {
                Ok(arr) => arr.into_raw(),
                Err(_) => std::ptr::null_mut(),
            };
        }
    };

    let capacity = match env.get_direct_buffer_capacity(&input_buffer) {
        Ok(capacity) => capacity,
        Err(_) => {
            let error_msg = b"{\"error\":\"Failed to get ByteBuffer capacity\"}";
            return match env.byte_array_from_slice(error_msg) {
                Ok(arr) => arr.into_raw(),
                Err(_) => std::ptr::null_mut(),
            };
        }
    };

    let slice = unsafe {
        std::slice::from_raw_parts(data, capacity)
    };

    // Process binary data in batches for better JNI performance
    match process_mob_ai_binary_batch(slice) {
        Ok(result) => match env.byte_array_from_slice(&result) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(e) => {
            let error_msg = format!("{{\"error\":\"{}\"}}", e);
            match env.byte_array_from_slice(error_msg.as_bytes()) {
                Ok(arr) => arr.into_raw(),
                Err(_) => std::ptr::null_mut(),
            }
        }
    }
}
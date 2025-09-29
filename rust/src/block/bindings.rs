use jni::JNIEnv;
use jni::objects::{JClass, JString, JByteBuffer};
use jni::sys::jbyteArray;
use crate::block::processing::process_block_entities;
use crate::flatbuffers::conversions::{deserialize_block_input, serialize_block_result};

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_RustPerformance_processBlockEntitiesNative(
    mut env: JNIEnv,
    _class: JClass,
    json_input: JString,
) -> jbyteArray {
    let input_str: String = env.get_string(&json_input).unwrap().into();
    
    match crate::block::processing::process_block_entities_json(&input_str) {
        Ok(result_json) => {
            env.byte_array_from_slice(result_json.as_bytes()).unwrap().into_raw()
        }
        Err(e) => {
            let error_msg = format!("{{\"error\":\"{}\"}}", e);
            env.byte_array_from_slice(error_msg.as_bytes()).unwrap().into_raw()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_RustPerformance_processBlockEntitiesBinaryNative(
    env: JNIEnv,
    _class: JClass,
    input_buffer: JByteBuffer,
) -> jbyteArray {
    // Get direct access to the ByteBuffer data
    let data = match env.get_direct_buffer_address(&input_buffer) {
        Ok(data) => data,
        Err(_) => {
            let error_msg = b"{\"error\":\"Direct ByteBuffer required\"}";
            return env.byte_array_from_slice(error_msg).unwrap().into_raw();
        }
    };

    let capacity = match env.get_direct_buffer_capacity(&input_buffer) {
        Ok(capacity) => capacity,
        Err(_) => {
            let error_msg = b"{\"error\":\"Failed to get ByteBuffer capacity\"}";
            return env.byte_array_from_slice(error_msg).unwrap().into_raw();
        }
    };

    let slice = unsafe {
        std::slice::from_raw_parts(data, capacity)
    };

    // For now, return error as binary protocol is not fully implemented
    let error_msg = b"{\"error\":\"Binary protocol not fully implemented\"}";
    env.byte_array_from_slice(error_msg).unwrap().into_raw()
}
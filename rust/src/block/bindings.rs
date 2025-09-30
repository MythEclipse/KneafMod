use jni::JNIEnv;
use jni::objects::{JClass, JString, JByteBuffer, JObject};
use jni::sys::{jbyteArray, jobject};
use crate::block::processing::process_block_entities;
use crate::flatbuffers::conversions::{deserialize_block_input, serialize_block_result};

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_RustPerformance_processBlockEntitiesNative(
    mut env: JNIEnv,
    _class: JClass,
    json_input: JString,
) -> jbyteArray {
    // Helper to create a jbyteArray containing a JSON error message. If creation fails, return null.
    fn make_error(env: &JNIEnv, msg: &str) -> jbyteArray {
        let err_bytes = msg.as_bytes();
        match env.byte_array_from_slice(err_bytes) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }

    let input_str = match env.get_string(&json_input) {
        Ok(s) => match s.to_str() {
            Ok(st) => st.to_owned(),
            Err(e) => return make_error(&env, &format!("{{\"error\":\"Invalid UTF-8 input: {}\"}}", e)),
        },
        Err(e) => return make_error(&env, &format!("{{\"error\":\"Failed to read input string: {}\"}}", e)),
    };

    match crate::block::processing::process_block_entities_json(&input_str) {
        Ok(result_json) => match env.byte_array_from_slice(result_json.as_bytes()) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(e) => make_error(&env, &format!("{{\"error\":\"{}\"}}", e)),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_RustPerformance_processBlockEntitiesBinaryNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    input_buffer: JByteBuffer<'local>,
) -> JObject<'local> {
    // Get direct access to the ByteBuffer data
    let data = match env.get_direct_buffer_address(&input_buffer) {
        Ok(data) => data,
        Err(_) => {
            let error_msg = b"{\"error\":\"Direct ByteBuffer required\"}";
            return unsafe { env.new_direct_byte_buffer(error_msg.as_ptr() as *mut u8, error_msg.len()).unwrap().into() };
        }
    };

    let capacity = match env.get_direct_buffer_capacity(&input_buffer) {
        Ok(capacity) => capacity,
        Err(_) => {
            let error_msg = b"{\"error\":\"Failed to get ByteBuffer capacity\"}";
            return unsafe { env.new_direct_byte_buffer(error_msg.as_ptr() as *mut u8, error_msg.len()).unwrap().into() };
        }
    };

    let slice = unsafe {
        std::slice::from_raw_parts(data, capacity)
    };

    // For now, return error as binary protocol is not fully implemented
    let error_msg = b"{\"error\":\"Binary protocol not fully implemented\"}";
    unsafe { env.new_direct_byte_buffer(error_msg.as_ptr() as *mut u8, error_msg.len()).unwrap().into() }
}
use jni::JNIEnv;
use jni::objects::{JClass, JString, JByteBuffer, JObject};
use jni::sys::{jbyteArray, jstring, jobject};
use crate::item::processing::{process_item_entities, process_item_entities_json};
use crate::binary::conversions::{deserialize_item_input, serialize_item_result};

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_RustPerformance_processItemEntitiesBinaryNative<'local>(
    mut env: JNIEnv<'local>,
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
    match process_item_entities_binary_batch(slice) {
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

/// Process item entities from binary input in batches for better JNI performance
fn process_item_entities_binary_batch(data: &[u8]) -> Result<Vec<u8>, String> {
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

    match crate::item::processing::process_item_entities_json(&input_str) {
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
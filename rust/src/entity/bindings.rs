use jni::JNIEnv;
use jni::objects::{JClass, JString, JByteBuffer, JObject};
use jni::sys::{jbyteArray, jobject};
use crate::entity::processing::{process_entities, process_entities_json};

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_RustPerformance_processEntitiesNative(
    mut env: JNIEnv,
    _class: JClass,
    json_input: JString,
) -> jbyteArray {
    let input_str: String = env.get_string(&json_input).unwrap().into();
    
    match process_entities_json(&input_str) {
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
pub extern "system" fn Java_com_kneaf_core_performance_RustPerformance_processEntitiesBinaryNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    input_buffer: JObject<'local>,
) -> JObject<'local> {
    // Get direct access to the ByteBuffer data
    let input_buffer = JByteBuffer::from(input_buffer);
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
            return unsafe { env.new_direct_byte_buffer(error_msg.as_ptr() as *mut u8, error_msg.len()).unwrap().into_raw() };
        }
    };

    let slice = unsafe {
        std::slice::from_raw_parts(data, capacity)
    };

    // Process binary data in batches for better JNI performance
    match process_entities_binary_batch(slice) {
        Ok(result) => unsafe { env.new_direct_byte_buffer(result.as_ptr() as *mut u8, result.len()).unwrap().into_raw() },
        Err(e) => {
            let error_msg = format!("{{\"error\":\"{}\"}}", e).into_bytes();
            unsafe { env.new_direct_byte_buffer(error_msg.as_ptr() as *mut u8, error_msg.len()).unwrap().into_raw() }
        }
    }
}

/// Process entities from binary input in batches for better JNI performance
fn process_entities_binary_batch(data: &[u8]) -> Result<Vec<u8>, String> {
    // Batch size optimization for JNI performance - larger batches for entities
    const BATCH_SIZE: usize = 2048; // Larger batch size for entity processing
    
    if data.is_empty() {
        return Ok(b"{\"result\":{\"entities_to_tick\":[]}}".to_vec());
    }
    
    // Process data in batches to reduce JNI overhead
    let mut offset = 0;
    let mut all_entities = Vec::new();
    
    while offset < data.len() {
        let end_offset = (offset + BATCH_SIZE).min(data.len());
        let batch_data = &data[offset..end_offset];
        
        // For now, collect placeholder entities for each batch
        // In a full implementation, this would deserialize and process the batch
        all_entities.push(0u64); // Placeholder entity ID
        
        offset = end_offset;
    }
    
    // Return combined results
    Ok(format!("{{\"result\":{{\"entities_to_tick\":{:?}}}}}", all_entities).into_bytes())
}
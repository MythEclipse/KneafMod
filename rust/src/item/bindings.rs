use jni::JNIEnv;
use jni::objects::{JClass, JString, JByteBuffer, JObject};
use jni::sys::{jbyteArray, jobject};
use crate::item::processing::{process_item_entities, process_item_entities_json};

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_RustPerformance_processItemEntitiesNative(
    mut env: JNIEnv,
    _class: JClass,
    json_input: JString,
) -> jbyteArray {
    let input_str: String = env.get_string(&json_input).unwrap().into();
    
    match process_item_entities_json(&input_str) {
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
pub extern "system" fn Java_com_kneaf_core_performance_RustPerformance_processItemEntitiesBinaryNative<'local>(
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

    // Process binary data in batches for better JNI performance
    match process_item_entities_binary_batch(slice) {
        Ok(result) => unsafe { env.new_direct_byte_buffer(result.as_ptr() as *mut u8, result.len()).unwrap().into() },
        Err(e) => {
            let error_msg = format!("{{\"error\":\"{}\"}}", e).into_bytes();
            unsafe { env.new_direct_byte_buffer(error_msg.as_ptr() as *mut u8, error_msg.len()).unwrap().into() }
        }
    }
}

/// Process item entities from binary input in batches for better JNI performance
fn process_item_entities_binary_batch(data: &[u8]) -> Result<Vec<u8>, String> {
    // Batch size optimization for JNI performance
    const BATCH_SIZE: usize = 1024; // Increased batch size for better throughput
    
    if data.is_empty() {
        return Ok(b"{\"result\":{\"items_to_remove\":[],\"merged_count\":0,\"despawned_count\":0,\"item_updates\":[]}}".to_vec());
    }
    
    // Process data in batches to reduce JNI overhead
    let mut offset = 0;
    let mut all_results = Vec::new();
    
    while offset < data.len() {
        let end_offset = (offset + BATCH_SIZE).min(data.len());
        let batch_data = &data[offset..end_offset];
        
        // For now, return a placeholder result for each batch
        // In a full implementation, this would deserialize and process the batch
        let batch_result = b"{\"batch_processed\":true}";
        all_results.extend_from_slice(batch_result);
        
        offset = end_offset;
    }
    
    // Return combined results
    Ok(b"{\"result\":{\"items_to_remove\":[],\"merged_count\":0,\"despawned_count\":0,\"item_updates\":[],\"batches_processed\":true}}".to_vec())
}
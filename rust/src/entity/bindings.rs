use jni::JNIEnv;
use jni::objects::{JClass, JString, JByteBuffer, JObject, JValue};
use jni::sys::{jstring, jobject, jbyteArray};
use crate::entity::processing::process_entities_json;
use crate::flatbuffers::entity::kneaf::entity::root_as_entity_input;

// Helper to forward logs from Rust to Java: calls
// com.kneaf.core.performance.RustPerformance.logFromNative(String level, String msg)
fn log_to_java(env: &mut JNIEnv, level: &str, msg: &str) {
    if let Ok(cls) = env.find_class("com/kneaf/core/performance/RustPerformance") {
        if let (Ok(jlevel), Ok(jmsg)) = (env.new_string(level), env.new_string(msg)) {
            // Convert JString to JObject and pass references to JValue::Object
            let jlevel_obj = JObject::from(jlevel);
            let jmsg_obj = JObject::from(jmsg);
            let _ = env.call_static_method(
                cls,
                "logFromNative",
                "(Ljava/lang/String;Ljava/lang/String;)V",
                &[JValue::Object(&jlevel_obj), JValue::Object(&jmsg_obj)],
            );
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_RustPerformance_processEntitiesNative(
    mut env: JNIEnv,
    _class: JClass,
    json_input: JString,
) -> jstring {
    log_to_java(&mut env, "DEBUG", "[JNI] processEntitiesNative called");

    if json_input.is_null() {
    log_to_java(&mut env, "ERROR", "[JNI] json_input is null");
        let error_msg = "{\"error\":\"Input JSON string is null\"}";
        return match env.new_string(error_msg) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        };
    }

    let input_str: String = match env.get_string(&json_input) {
        Ok(s) => {
            let str_val: String = s.into();
            log_to_java(&mut env, "DEBUG", &format!("[JNI] Successfully converted JString to Rust String, length: {}", str_val.len()));
            if str_val.is_empty() {
                log_to_java(&mut env, "WARN", "[JNI] Input string is empty");
            } else if str_val.len() > 1000000 {
                log_to_java(&mut env, "ERROR", &format!("[JNI] Input string too large: {} bytes", str_val.len()));
                let error_msg = format!("{{\"error\":\"Input too large: {} bytes\"}}", str_val.len());
                return match env.new_string(error_msg) {
                    Ok(s) => s.into_raw(),
                    Err(_) => std::ptr::null_mut(),
                };
            }
            str_val
        },
        Err(e) => {
            log_to_java(&mut env, "ERROR", &format!("[JNI] Failed to get string from JString: {:?}", e));
            let error_msg = format!("{{\"error\":\"Failed to get string from JString: {:?}\"}}", e);
            return match env.new_string(error_msg) {
                Ok(s) => s.into_raw(),
                Err(_) => std::ptr::null_mut(),
            };
        }
    };

    log_to_java(&mut env, "DEBUG", &format!("[JNI] Calling process_entities_json with input length: {}", input_str.len()));
    match process_entities_json(&input_str) {
        Ok(result_json) => {
            log_to_java(&mut env, "DEBUG", &format!("[JNI] process_entities_json succeeded, result length: {}", result_json.len()));
            match env.new_string(result_json) {
                Ok(s) => s.into_raw(),
                Err(e) => {
                    log_to_java(&mut env, "ERROR", &format!("[JNI] Failed to create JString from result: {:?}", e));
                    std::ptr::null_mut()
                }
            }
        },
        Err(e) => {
            log_to_java(&mut env, "ERROR", &format!("[JNI] process_entities_json failed: {}", e));
            let error_msg = format!("{{\"error\":\"{}\"}}", e);
            match env.new_string(error_msg) {
                Ok(s) => s.into_raw(),
                Err(_) => std::ptr::null_mut(),
            }
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_RustPerformance_processEntitiesBinaryNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    input_buffer: JObject<'local>,
) -> jbyteArray {
    log_to_java(&mut env, "DEBUG", "[JNI] processEntitiesBinaryNative called");

    if input_buffer.is_null() {
    log_to_java(&mut env, "ERROR", "[JNI] input_buffer is null");
        let error_msg = b"{\"error\":\"Input ByteBuffer is null\"}";
        return match env.byte_array_from_slice(error_msg) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        };
    }

    let input_buffer = JByteBuffer::from(input_buffer);

    let data = match env.get_direct_buffer_address(&input_buffer) {
        Ok(data) => data,
        Err(e) => {
            log_to_java(&mut env, "ERROR", &format!("[JNI] Failed to get direct buffer address: {:?}", e));
            let error_msg = b"{\"error\":\"Direct ByteBuffer required\"}";
            return match env.byte_array_from_slice(error_msg) {
                Ok(arr) => arr.into_raw(),
                Err(_) => std::ptr::null_mut(),
            };
        }
    };

    let capacity = match env.get_direct_buffer_capacity(&input_buffer) {
        Ok(capacity) => capacity,
        Err(e) => {
            log_to_java(&mut env, "ERROR", &format!("[JNI] Failed to get ByteBuffer capacity: {:?}", e));
            let error_msg = b"{\"error\":\"Failed to get ByteBuffer capacity\"}";
            return match env.byte_array_from_slice(error_msg) {
                Ok(arr) => arr.into_raw(),
                Err(_) => std::ptr::null_mut(),
            };
        }
    };

    if capacity == 0 {
    log_to_java(&mut env, "WARN", "[JNI] Buffer capacity is 0");
        let error_msg = b"{\"error\":\"Buffer capacity is 0\"}";
        return match env.byte_array_from_slice(error_msg) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        };
    }

    let slice = unsafe { std::slice::from_raw_parts(data, capacity) };
    log_to_java(&mut env, "DEBUG", &format!("[JNI] Calling process_entities_binary_batch with slice length: {}", slice.len()));

    if slice.is_empty() {
    log_to_java(&mut env, "WARN", "[JNI] Empty slice, returning empty result");
        let result = vec![0u8; 4];
        return match env.byte_array_from_slice(&result) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        };
    }

    match process_entities_binary_batch(&mut env, slice) {
        Ok(result) => match env.byte_array_from_slice(&result) {
            Ok(arr) => arr.into_raw(),
            Err(e) => {
                log_to_java(&mut env, "ERROR", &format!("[JNI] Failed to create byte array from result: {:?}", e));
                std::ptr::null_mut()
            }
        },
        Err(e) => {
            log_to_java(&mut env, "ERROR", &format!("[JNI] process_entities_binary_batch failed: {}", e));
            let error_msg = format!("{{\"error\":\"{}\"}}", e);
            match env.byte_array_from_slice(error_msg.as_bytes()) {
                Ok(arr) => arr.into_raw(),
                Err(_) => std::ptr::null_mut(),
            }
        }
    }
}

/// Process entities from binary input in batches for better JNI performance
fn process_entities_binary_batch(env: &mut JNIEnv, data: &[u8]) -> Result<Vec<u8>, String> {
    log_to_java(env, "DEBUG", &format!("[BINARY] process_entities_binary_batch called with data length: {}", data.len()));

    if data.is_empty() {
    log_to_java(env, "WARN", "[BINARY] Empty input data, returning empty result");
        let mut result = Vec::with_capacity(4);
        result.extend_from_slice(&0i32.to_le_bytes());
        return Ok(result);
    }

    if data.len() < 8 {
    log_to_java(env, "ERROR", &format!("[BINARY] Data too small for FlatBuffers header: {} bytes", data.len()));
        return Err(format!("Data too small for FlatBuffers header: {} bytes", data.len()));
    }

    let entity_input = match root_as_entity_input(data) {
        Ok(input) => input,
        Err(e) => {
            log_to_java(env, "ERROR", &format!("[BINARY] Failed to deserialize FlatBuffers input: {:?}", e));
            // Try manual deserialization as fallback
            match crate::flatbuffers::conversions::deserialize_entity_input(data) {
                Ok(manual_input) => {
                    let entities_to_tick: Vec<u64> = manual_input.entities.iter().map(|e| e.id).collect();
                    let mut result = Vec::with_capacity(4 + entities_to_tick.len() * 8);
                    result.extend_from_slice(&(entities_to_tick.len() as i32).to_le_bytes());
                    for entity_id in &entities_to_tick {
                        result.extend_from_slice(&entity_id.to_le_bytes());
                    }
                    log_to_java(env, "DEBUG", &format!("[BINARY] Manual fallback successful, returning {} entities", entities_to_tick.len()));
                    return Ok(result);
                },
                Err(manual_err) => {
                    log_to_java(env, "ERROR", &format!("[BINARY] Manual deserialization also failed: {}", manual_err));
                    return Err(format!("FlatBuffers deserialization failed: {:?}, manual fallback failed: {}", e, manual_err));
                }
            }
        }
    };

    log_to_java(env, "DEBUG", &format!("[BINARY] Processing entity input - tick_count: {}, entities: {:?}, players: {:?}",
        entity_input.tick_count(),
        entity_input.entities().map(|v| v.len()).unwrap_or(0),
        entity_input.players().map(|v| v.len()).unwrap_or(0)
    ));

    let mut entities_to_tick = Vec::new();
    if let Some(entities) = entity_input.entities() {
        for i in 0..entities.len() {
            let entity = entities.get(i);
            entities_to_tick.push(entity.id());
        }
    }

    log_to_java(env, "DEBUG", &format!("[BINARY] Total entities to tick: {}", entities_to_tick.len()));

    let mut result = Vec::with_capacity(4 + entities_to_tick.len() * 8);
    result.extend_from_slice(&(entities_to_tick.len() as i32).to_le_bytes());
    for entity_id in &entities_to_tick {
        result.extend_from_slice(&entity_id.to_le_bytes());
    }

    log_to_java(env, "DEBUG", &format!("[BINARY] Successfully created binary result with {} entities", entities_to_tick.len()));
    Ok(result)
}
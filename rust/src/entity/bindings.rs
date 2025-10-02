use jni::JNIEnv;
use jni::objects::{JClass, JString, JByteBuffer, JObject};
use jni::sys::{jstring, jbyteArray};
use crate::entity::processing::process_entities_json;
use crate::logging::JniLogger;
use crate::{jni_log_debug, jni_log_warn, jni_log_error, jni_log_trace};
use std::sync::OnceLock;
// Generated FlatBuffers bindings are not used; the manual converters in
// `crate::binary::conversions` are the canonical path for binary data.

static LOGGER: OnceLock<JniLogger> = OnceLock::new();

fn get_logger() -> &'static JniLogger {
    LOGGER.get_or_init(|| JniLogger::new("entity"))
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_RustPerformance_processEntitiesNative(
    mut env: JNIEnv,
    _class: JClass,
    json_input: JString,
) -> jstring {
    let logger = get_logger();
    jni_log_debug!(logger, &mut env, "JNI", "processEntitiesNative called");

    if json_input.is_null() {
        jni_log_error!(logger, &mut env, "JNI", "json_input is null");
        let error_msg = "{\"error\":\"Input JSON string is null\"}";
        return match env.new_string(error_msg) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        };
    }

    let input_str: String = match env.get_string(&json_input) {
        Ok(s) => {
            let str_val: String = s.into();
            jni_log_debug!(logger, &mut env, "JNI", &format!("Successfully converted JString to Rust String, length: {}", str_val.len()));
            if str_val.is_empty() {
                jni_log_warn!(logger, &mut env, "JNI", "Input string is empty");
            } else if str_val.len() > 1000000 {
                jni_log_error!(logger, &mut env, "JNI", &format!("Input string too large: {} bytes", str_val.len()));
                let error_msg = format!("{{\"error\":\"Input too large: {} bytes\"}}", str_val.len());
                return match env.new_string(error_msg) {
                    Ok(s) => s.into_raw(),
                    Err(_) => std::ptr::null_mut(),
                };
            }
            str_val
        },
        Err(e) => {
            jni_log_error!(logger, &mut env, "JNI", &format!("Failed to get string from JString: {:?}", e));
            let error_msg = format!("{{\"error\":\"Failed to get string from JString: {:?}\"}}", e);
            return match env.new_string(error_msg) {
                Ok(s) => s.into_raw(),
                Err(_) => std::ptr::null_mut(),
            };
        }
    };

    jni_log_debug!(logger, &mut env, "JNI", &format!("Calling process_entities_json with input length: {}", input_str.len()));
    match process_entities_json(&input_str) {
        Ok(result_json) => {
            jni_log_debug!(logger, &mut env, "JNI", &format!("process_entities_json succeeded, result length: {}", result_json.len()));
            match env.new_string(result_json) {
                Ok(s) => s.into_raw(),
                Err(e) => {
                    jni_log_error!(logger, &mut env, "JNI", &format!("Failed to create JString from result: {:?}", e));
                    std::ptr::null_mut()
                }
            }
        },
        Err(e) => {
            jni_log_error!(logger, &mut env, "JNI", &format!("process_entities_json failed: {}", e));
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
    let logger = get_logger();
    // Remove debug log to reduce noise - only log errors
    if input_buffer.is_null() {
        jni_log_error!(logger, &mut env, "JNI", "input_buffer is null");
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
            jni_log_error!(logger, &mut env, "JNI", &format!("Failed to get direct buffer address: {:?}", e));
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
            jni_log_error!(logger, &mut env, "JNI", &format!("Failed to get ByteBuffer capacity: {:?}", e));
            let error_msg = b"{\"error\":\"Failed to get ByteBuffer capacity\"}";
            return match env.byte_array_from_slice(error_msg) {
                Ok(arr) => arr.into_raw(),
                Err(_) => std::ptr::null_mut(),
            };
        }
    };

    if capacity == 0 {
        jni_log_warn!(logger, &mut env, "JNI", "Buffer capacity is 0");
        let error_msg = b"{\"error\":\"Buffer capacity is 0\"}";
        return match env.byte_array_from_slice(error_msg) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        };
    }

    let slice = unsafe { std::slice::from_raw_parts(data, capacity) };
    // Remove debug log to reduce noise - only log warnings/errors

    if slice.is_empty() {
        jni_log_warn!(logger, &mut env, "JNI", "Empty slice, returning empty result");
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
                jni_log_error!(logger, &mut env, "JNI", &format!("Failed to create byte array from result: {:?}", e));
                std::ptr::null_mut()
            }
        },
        Err(e) => {
            jni_log_error!(logger, &mut env, "JNI", &format!("process_entities_binary_batch failed: {}", e));
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
    let logger = get_logger();
    // Remove debug log to reduce noise - only log warnings/errors

    if data.is_empty() {
        jni_log_warn!(logger, env, "BINARY", "Empty input data, returning empty result");
        let mut result = Vec::with_capacity(4);
        result.extend_from_slice(&0i32.to_le_bytes());
        return Ok(result);
    }

    if data.len() < 8 {
        jni_log_error!(logger, env, "BINARY", &format!("Data too small for FlatBuffers header: {} bytes", data.len()));
        return Err(format!("Data too small for FlatBuffers header: {} bytes", data.len()));
    }

    // Strengthened probe to decide whether the buffer resembles a FlatBuffers buffer.
    // FlatBuffers stores a uoffset_t (u32 little-endian) at the start that points to the root table.
    // We'll validate: non-zero root offset, alignment, bounds, and a small sanity-check of the
    // vtable (length and inline object size) before attempting a full parse. This reduces
    // false positives (manual layout buffers that resemble a u32 at the start).
    // We intentionally skip the FlatBuffers fast-path for entity input, so we don't need
    // a `looks_like_flatbuffers` flag here. Proceed with the probe for logging/diagnostics
    // but always use the manual deserializer below.
    if data.len() >= 6 {
        // Read the root offset (uoffset_t)
        let root_offset_u32 = u32::from_le_bytes([data[0], data[1], data[2], data[3]]);
        let root_offset = root_offset_u32 as usize;

        // Basic checks: non-zero, in-bounds, aligned and reasonably large (root table typically not at very small offsets)
        if root_offset_u32 == 0 {
            jni_log_trace!(logger, env, "BINARY", &format!("FlatBuffers header probe failed: root_offset=0, data_len={}", data.len()));
        } else if root_offset + 4 > data.len() {
            jni_log_trace!(logger, env, "BINARY", &format!("FlatBuffers header probe failed: root_offset out of bounds: {}, data_len={}", root_offset, data.len()));
        } else if (root_offset_u32 % 4) != 0 || root_offset < 8 {
            // Require 4-byte alignment and a minimum offset so we don't accept tiny/implausible values
            jni_log_trace!(logger, env, "BINARY", &format!("FlatBuffers header probe failed: root_offset alignment/size: {}, data_len={}", root_offset, data.len()));
        } else {
            // Attempt a small vtable sanity check. At table start, FlatBuffers stores a 16-bit vtable offset
            // (little-endian signed i16) that is typically negative; the vtable itself starts at
            // `vtable_pos = root_offset - (-vtable_offset)`.
            if root_offset + 2 <= data.len() {
                let vtable_rel = i16::from_le_bytes([data[root_offset], data[root_offset + 1]]);
                if vtable_rel >= 0 {
                    jni_log_trace!(logger, env, "BINARY", &format!("FlatBuffers header probe failed: unexpected non-negative vtable_rel={} at root_offset={}", vtable_rel, root_offset));
                } else {
                    // compute vtable position (safe because vtable_rel is negative)
                    let vtable_pos = root_offset - ((-vtable_rel) as usize);
                    if vtable_pos + 4 > data.len() {
                        jni_log_trace!(logger, env, "BINARY", &format!("FlatBuffers header probe failed: vtable out of bounds vtable_pos={}, data_len={}", vtable_pos, data.len()));
                    } else {
                        // vtable begins with two uint16: [vtable_len, object_inline_size]
                        let vtable_len = u16::from_le_bytes([data[vtable_pos], data[vtable_pos + 1]]) as usize;
                        let object_inline_size = u16::from_le_bytes([data[vtable_pos + 2], data[vtable_pos + 3]]) as usize;
                        // Basic sanity ranges and bounds checks to avoid mis-identifying manual layouts
                        // - vtable_len should be at least 4 (two uint16 values) and not huge
                        // - vtable_pos should be within bounds and 2-byte aligned
                        // - object_inline_size must be reasonable and fit within the remaining buffer after root_offset
                        if vtable_len < 4 {
                            jni_log_trace!(logger, env, "BINARY", &format!("FlatBuffers header probe failed: vtable_len too small={} at vtable_pos={} (root_offset={})", vtable_len, vtable_pos, root_offset));
                        } else if vtable_len > 64 {
                            // Tighten the vtable length cap - smaller vtables are expected for our schema.
                            jni_log_trace!(logger, env, "BINARY", &format!("FlatBuffers header probe failed: vtable_len unreasonably large={}", vtable_len));
                        } else if (vtable_pos % 2) != 0 {
                            jni_log_trace!(logger, env, "BINARY", &format!("FlatBuffers header probe failed: vtable_pos not 2-byte aligned={}", vtable_pos));
                        } else if vtable_pos + vtable_len > data.len() {
                            jni_log_trace!(logger, env, "BINARY", &format!("FlatBuffers header probe failed: vtable overruns buffer: vtable_pos={} vtable_len={} data_len={}", vtable_pos, vtable_len, data.len()));
                        } else if object_inline_size > 256 {
                            // Reduce allowed inline object size to avoid accepting manual formats that look
                            // superficially like FlatBuffers (we expect small inline object sizes for entity tables)
                            jni_log_trace!(logger, env, "BINARY", &format!("FlatBuffers header probe failed: object_inline_size unreasonably large={}", object_inline_size));
                        } else if root_offset + object_inline_size > data.len() {
                            jni_log_trace!(logger, env, "BINARY", &format!("FlatBuffers header probe failed: object_inline_size overruns buffer: root_offset={} object_inline_size={} data_len={}", root_offset, object_inline_size, data.len()));
                        } else {
                            jni_log_trace!(logger, env, "BINARY", &format!("FlatBuffers header probe passed: root_offset={}, vtable_pos={}, vtable_len={}, object_inline_size={}", root_offset, vtable_pos, vtable_len, object_inline_size));
                        }
                    }
                }
            } else {
                jni_log_trace!(logger, env, "BINARY", &format!("FlatBuffers header probe failed: not enough bytes for vtable check, root_offset={}, data_len={}", root_offset, data.len()));
            }
        }
    } else {
        jni_log_trace!(logger, env, "BINARY", &format!("FlatBuffers header probe skipped: data too small ({})", data.len()));
    }

    // For entity inputs we use a custom manual binary layout produced by the Java side.
    // The FlatBuffers fast-path has proved unreliable for this schema because the Java
    // serializer writes a manual layout (tickCount at the start) rather than a true
    // FlatBuffers buffer. Attempting the generated FlatBuffers parser may intermittently
    // produce alignment/Unaligned errors when the probe mis-identifies the layout.
    // To avoid noisy errors and unnecessary failed parse attempts, skip the FlatBuffers
    // fast-path entirely and use the manual deserializer directly.
    // Remove debug log to reduce noise - only log errors

    // Manual deserialization fallback. Build a Result here and return it once so we
    // avoid early `return` calls inside the match (which made subsequent code unreachable).
    let manual_result: Result<Vec<u8>, String> = match crate::binary::conversions::deserialize_entity_input(data) {
        Ok(manual_input) => {
            let entities_to_tick: Vec<u64> = manual_input.entities.iter().map(|e| e.id).collect();
            // Format expected by Java BinarySerializer for process result: [numItems:i32][ids...]
            let mut result = Vec::with_capacity(4 + entities_to_tick.len() * 8);
            // Number of entities (i32 little-endian)
            result.extend_from_slice(&(entities_to_tick.len() as i32).to_le_bytes());
            for entity_id in &entities_to_tick {
                result.extend_from_slice(&entity_id.to_le_bytes());
            }
            // Remove debug logs to reduce noise - only log errors
            Ok(result)
        },
        Err(manual_err) => {
            jni_log_error!(logger, env, "BINARY", &format!("Manual deserialization also failed: {}", manual_err));
            Err(format!("Manual deserialization failed: {}", manual_err))
        }
    };

    manual_result
}
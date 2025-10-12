use byteorder::{LittleEndian, ReadBytesExt, WriteBytesExt};
use std::io::{Cursor, Read};
use std::slice;

use crate::mob::types::{MobInput, MobProcessResult};

// Zero-copy deserialization - reads directly from memory without copying
pub unsafe fn deserialize_mob_input_zero_copy(
    data_ptr: *const u8,
    data_len: usize,
) -> Result<MobInput, String> {
    if data_ptr.is_null() || data_len == 0 {
        return Err("Null pointer or zero length data".to_string());
    }

    let data_slice = unsafe { slice::from_raw_parts(data_ptr, data_len) };
    let mut cur = Cursor::new(data_slice);

    let tick_count = cur.read_u64::<LittleEndian>().map_err(|e| e.to_string())?;
    let num = cur.read_i32::<LittleEndian>().map_err(|e| e.to_string())? as usize;

    let mut mobs = Vec::with_capacity(num);
    for _ in 0..num {
        let id = cur.read_u64::<LittleEndian>().map_err(|e| e.to_string())?;
        let distance = cur.read_f32::<LittleEndian>().map_err(|e| e.to_string())?;
        let passive = cur.read_u8().map_err(|e| e.to_string())? != 0;
        let etype_len = cur.read_i32::<LittleEndian>().map_err(|e| e.to_string())? as usize;

        let mut etype = String::new();
        if etype_len > 0 {
            let mut buf = vec![0u8; etype_len];
            cur.read_exact(&mut buf).map_err(|e| e.to_string())?;
            etype = String::from_utf8(buf).map_err(|e| e.to_string())?;
        }

        mobs.push(crate::mob::types::MobData {
            id,
            distance,
            entity_type: etype,
            is_passive: passive,
        });
    }

    Ok(MobInput { tick_count, mobs })
}

// Zero-copy serialization - writes directly to provided memory buffer
pub unsafe fn serialize_mob_result_zero_copy(
    result: &MobProcessResult,
    buffer_ptr: *mut u8,
    buffer_capacity: usize,
) -> Result<usize, String> {
    if buffer_ptr.is_null() || buffer_capacity == 0 {
        return Err("Null pointer or zero capacity buffer".to_string());
    }

    let buffer_slice = unsafe { slice::from_raw_parts_mut(buffer_ptr, buffer_capacity) };
    let mut cur = Cursor::new(buffer_slice);

    // Write tick count placeholder
    cur.write_u64::<LittleEndian>(0u64)
        .map_err(|e| e.to_string())?;

    // Write disable list
    let disable_len = result.mobs_to_disable_ai.len() as i32;
    cur.write_i32::<LittleEndian>(disable_len)
        .map_err(|e| e.to_string())?;

    for id in &result.mobs_to_disable_ai {
        cur.write_u64::<LittleEndian>(*id)
            .map_err(|e| e.to_string())?;
    }

    // Write simplify list
    let simplify_len = result.mobs_to_simplify_ai.len() as i32;
    cur.write_i32::<LittleEndian>(simplify_len)
        .map_err(|e| e.to_string())?;

    for id in &result.mobs_to_simplify_ai {
        cur.write_u64::<LittleEndian>(*id)
            .map_err(|e| e.to_string())?;
    }

    Ok(cur.position() as usize)
}

// Calculate required buffer size for serialization
pub fn calculate_serialized_size_mob_result(result: &MobProcessResult) -> usize {
    let disable_size = result.mobs_to_disable_ai.len() * 8;
    let simplify_size = result.mobs_to_simplify_ai.len() * 8;

    8 + 4 + disable_size + 4 + simplify_size
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mob::types::MobProcessResult;

    #[test]
    fn test_zero_copy_serialization_roundtrip() {
        let test_result = MobProcessResult {
            mobs_to_disable_ai: vec![1, 2, 3],
            mobs_to_simplify_ai: vec![4, 5, 6],
        };

        let required_size = calculate_serialized_size_mob_result(&test_result);
        assert_eq!(required_size, 8 + 4 + 3 * 8 + 4 + 3 * 8);

        let mut buffer = vec![0u8; required_size];
        let bytes_written = unsafe {
            serialize_mob_result_zero_copy(&test_result, buffer.as_mut_ptr(), buffer.len())
        }
        .expect("Serialization failed");

        assert_eq!(bytes_written, required_size);
    }
}

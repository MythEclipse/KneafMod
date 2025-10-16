use byteorder::{LittleEndian, ReadBytesExt, WriteBytesExt};
use std::io::{Cursor, Read};
use std::slice;

use crate::entities::mob::types::{MobInput, MobProcessResult};
use crate::types::EntityTypeTrait as EntityType;
use super::conversions::{BinaryConversionError, conversion_utils};

// Trait defining the zero-copy conversion interface
pub trait ZeroCopyConverter {
    type Input;
    type Output;
    
    /// Deserialize from raw pointer without copying data
    unsafe fn deserialize(&self, data_ptr: *const u8, data_len: usize) -> Result<Self::Input, BinaryConversionError>;
    
    /// Serialize to provided buffer without copying data
    unsafe fn serialize(
        &self, 
        data: &Self::Output, 
        tick_count: u64, 
        buffer_ptr: *mut u8, 
        buffer_capacity: usize
    ) -> Result<usize, BinaryConversionError>;
    
    /// Calculate required buffer size for serialization
    fn calculate_serialized_size(&self, data: &Self::Output) -> usize;
}

// Factory for creating zero-copy converters
pub struct ZeroCopyConverterFactory;

impl ZeroCopyConverterFactory {
    /// Get a mob zero-copy converter
    pub fn mob_converter() -> impl ZeroCopyConverter<Input=MobInput, Output=MobProcessResult> {
        MobZeroCopyConverter
    }
}

// Mob zero-copy converter implementation
struct MobZeroCopyConverter;

impl ZeroCopyConverter for MobZeroCopyConverter {
    type Input = MobInput;
    type Output = MobProcessResult;
    
    unsafe fn deserialize(&self, data_ptr: *const u8, data_len: usize) -> Result<Self::Input, BinaryConversionError> {
        if data_ptr.is_null() || data_len == 0 {
            return Err(BinaryConversionError::InvalidData(
                "Null pointer or zero length data".to_string()
            ));
        }

        let data_slice = slice::from_raw_parts(data_ptr, data_len);
        let mut cur = Cursor::new(data_slice);

        let tick_count = cur.read_u64::<LittleEndian>()?;
        let num = cur.read_i32::<LittleEndian>()? as usize;

        let mut mobs = Vec::with_capacity(num);
        for _ in 0..num {
            let id = cur.read_u64::<LittleEndian>()?;
            let distance = cur.read_f32::<LittleEndian>()?;
            let passive = cur.read_u8()? != 0;
            let etype_len = cur.read_i32::<LittleEndian>()? as usize;

            let etype = if etype_len > 0 {
                let mut buf = vec![0u8; etype_len];
                cur.read_exact(&mut buf)?;
                String::from_utf8(buf)?
            } else {
                String::new()
            };

            mobs.push(crate::mob::types::MobData {
                id,
                distance,
                entity_type: EntityType::Generic,
                is_passive: passive,
            });
        }

        Ok(MobInput { tick_count, mobs })
    }

    unsafe fn serialize(
        &self, 
        result: &Self::Output, 
        tick_count: u64, 
        buffer_ptr: *mut u8, 
        buffer_capacity: usize
    ) -> Result<usize, BinaryConversionError> {
        if buffer_ptr.is_null() || buffer_capacity == 0 {
            return Err(BinaryConversionError::InvalidData(
                "Null pointer or zero capacity buffer".to_string()
            ));
        }

        let buffer_slice = slice::from_raw_parts_mut(buffer_ptr, buffer_capacity);
        let mut cur = Cursor::new(buffer_slice);

        // Write tick count from the original input
        cur.write_u64::<LittleEndian>(tick_count)?;

        // Write disable list
        let disable_len = result.mobs_to_disable_ai.len() as i32;
        cur.write_i32::<LittleEndian>(disable_len)?;

        for id in &result.mobs_to_disable_ai {
            cur.write_u64::<LittleEndian>(*id)?;
        }

        // Write simplify list
        let simplify_len = result.mobs_to_simplify_ai.len() as i32;
        cur.write_i32::<LittleEndian>(simplify_len)?;

        for id in &result.mobs_to_simplify_ai {
            cur.write_u64::<LittleEndian>(*id)?;
        }

        Ok(cur.position() as usize)
    }

    fn calculate_serialized_size(&self, result: &Self::Output) -> usize {
        let disable_size = result.mobs_to_disable_ai.len() * 8;
        let simplify_size = result.mobs_to_simplify_ai.len() * 8;

        8 + 4 + disable_size + 4 + simplify_size
    }
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

        let converter = ZeroCopyConverterFactory::mob_converter();
        let required_size = converter.calculate_serialized_size(&test_result);
        assert_eq!(required_size, 8 + 4 + 3 * 8 + 4 + 3 * 8);

        let mut buffer = vec![0u8; required_size];
        let test_tick_count = 12345u64;
        let bytes_written = unsafe {
            converter.serialize(&test_result, test_tick_count, buffer.as_mut_ptr(), buffer.len())
        }.expect("Serialization failed");

        assert_eq!(bytes_written, required_size);
        
        // Test deserialization
        let deserialized = unsafe {
            converter.deserialize(buffer.as_ptr(), buffer.len())
        }.expect("Deserialization failed");
        
        assert_eq!(deserialized.tick_count, test_tick_count);
    }
}

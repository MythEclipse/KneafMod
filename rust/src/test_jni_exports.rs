#[cfg(test)]
mod tests {
    use crate::jni_exports::{process_mob_operation, process_block_operation};
    use crate::mob::types::{MobInput, MobData};
    use crate::block::types::{BlockInput, BlockEntityData};

    #[test]
    fn test_process_mob_operation_empty() {
        let empty_data = vec![];
        let result = process_mob_operation(&empty_data);
        assert!(result.is_ok());
        let output = result.unwrap();
        assert!(!output.is_empty()); // Should have timestamp header
    }

    #[test]
    fn test_process_mob_operation_with_data() {
        // Create test mob input data
        let mob_input = MobInput {
            tick_count: 100,
            mobs: vec![
                MobData {
                    id: 1,
                    entity_type: "zombie".to_string(),
                    distance: 25.0,
                    is_passive: false,
                },
                MobData {
                    id: 2,
                    entity_type: "cow".to_string(),
                    distance: 150.0,
                    is_passive: true,
                },
            ],
        };
        
        // Serialize the input
        let mut input_data = Vec::new();
        use byteorder::{LittleEndian, WriteBytesExt};
        input_data.write_u64::<LittleEndian>(mob_input.tick_count).unwrap();
        input_data.write_i32::<LittleEndian>(mob_input.mobs.len() as i32).unwrap();
        
        for mob in &mob_input.mobs {
            input_data.write_u64::<LittleEndian>(mob.id).unwrap();
            input_data.write_f32::<LittleEndian>(mob.distance).unwrap();
            input_data.write_u8(if mob.is_passive { 1 } else { 0 }).unwrap();
            input_data.write_i32::<LittleEndian>(mob.entity_type.len() as i32).unwrap();
            input_data.extend_from_slice(mob.entity_type.as_bytes());
        }
        
        // Process the data
        let result = process_mob_operation(&input_data);
        assert!(result.is_ok());
        let output = result.unwrap();
        assert!(!output.is_empty());
        
        // Verify the result has the expected format (timestamp + data)
        assert!(output.len() >= 12); // At least timestamp (8) + length (4)
        
        // Check that we have some mobs to disable or simplify
        // The result should contain: [timestamp:u64][disable_len:i32][disable_ids...][simplify_len:i32][simplify_ids...]
        use byteorder::ReadBytesExt;
        use std::io::Cursor;
        
        let mut cursor = Cursor::new(&output);
        let _timestamp = cursor.read_u64::<byteorder::LittleEndian>().unwrap();
        let disable_len = cursor.read_i32::<byteorder::LittleEndian>().unwrap();
        assert!(disable_len >= 0);
        
        // Skip disable IDs
        for _ in 0..disable_len {
            cursor.read_u64::<byteorder::LittleEndian>().unwrap();
        }
        
        let simplify_len = cursor.read_i32::<byteorder::LittleEndian>().unwrap();
        assert!(simplify_len >= 0);
        
        println!("Mob processing result: {} to disable, {} to simplify", disable_len, simplify_len);
    }

    #[test]
    fn test_process_block_operation_empty() {
        let empty_data = vec![];
        let result = process_block_operation(&empty_data);
        assert!(result.is_ok());
        let output = result.unwrap();
        assert!(!output.is_empty()); // Should have timestamp header
    }

    #[test]
    fn test_process_block_operation_with_data() {
        // Create test block input data
        let block_input = BlockInput {
            tick_count: 200,
            block_entities: vec![
                BlockEntityData {
                    id: 1,
                    block_type: "minecraft:chest".to_string(),
                    distance: 10.0,
                    x: 100,
                    y: 64,
                    z: 200,
                },
                BlockEntityData {
                    id: 2,
                    block_type: "minecraft:furnace".to_string(),
                    distance: 25.0,
                    x: 105,
                    y: 64,
                    z: 205,
                },
            ],
        };
        
        // Serialize the input
        let mut input_data = Vec::new();
        use byteorder::{LittleEndian, WriteBytesExt};
        input_data.write_u64::<LittleEndian>(block_input.tick_count).unwrap();
        input_data.write_i32::<LittleEndian>(block_input.block_entities.len() as i32).unwrap();
        
        for entity in &block_input.block_entities {
            input_data.write_u64::<LittleEndian>(entity.id).unwrap();
            input_data.write_f32::<LittleEndian>(entity.distance).unwrap();
            input_data.write_i32::<LittleEndian>(entity.block_type.len() as i32).unwrap();
            input_data.extend_from_slice(entity.block_type.as_bytes());
            input_data.write_i32::<LittleEndian>(entity.x).unwrap();
            input_data.write_i32::<LittleEndian>(entity.y).unwrap();
            input_data.write_i32::<LittleEndian>(entity.z).unwrap();
        }
        
        // Process the data
        let result = process_block_operation(&input_data);
        assert!(result.is_ok());
        let output = result.unwrap();
        assert!(!output.is_empty());
        
        // Verify the result has the expected format (timestamp + data)
        assert!(output.len() >= 12); // At least timestamp (8) + length (4)
        
        // Check that we have some block entities to tick
        // The result should contain: [timestamp:u64][len:i32][entity_ids...]
        use byteorder::ReadBytesExt;
        use std::io::Cursor;
        
        let mut cursor = Cursor::new(&output);
        let _timestamp = cursor.read_u64::<byteorder::LittleEndian>().unwrap();
        let entity_count = cursor.read_i32::<byteorder::LittleEndian>().unwrap();
        assert!(entity_count >= 0);
        
        println!("Block processing result: {} entities to tick", entity_count);
        assert!(entity_count > 0); // Should have some entities to tick
    }
}
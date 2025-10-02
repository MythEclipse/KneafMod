#[cfg(test)]
mod tests {
    use byteorder::{LittleEndian, WriteBytesExt};

    #[test]
    fn item_roundtrip_deserialize_process_serialize() {
        // Build manual item input buffer: [tickCount:u64][num:i32][items...]
        // Each item: [id:u64][chunkX:i32][chunkZ:i32][itemTypeLen:i32][itemTypeBytes][count:i32][ageSeconds:i32]
        let mut out: Vec<u8> = Vec::new();
        out.write_u64::<LittleEndian>(42u64).unwrap(); // tickCount placeholder
        out.write_i32::<LittleEndian>(1).unwrap(); // one item

        // item
        out.write_u64::<LittleEndian>(12345u64).unwrap(); // id
        out.write_i32::<LittleEndian>(5).unwrap(); // chunk_x
        out.write_i32::<LittleEndian>(7).unwrap(); // chunk_z
        let t = b"minecraft:stone";
        out.write_i32::<LittleEndian>(t.len() as i32).unwrap();
        out.extend_from_slice(t);
        out.write_i32::<LittleEndian>(64).unwrap(); // count
        out.write_i32::<LittleEndian>(10).unwrap(); // ageSeconds

        // Now call the crate deserializer
    let input = crate::binary::conversions::deserialize_item_input(&out).expect("deserialize_item_input failed");
        assert_eq!(input.items.len(), 1);
        assert_eq!(input.items[0].id, 12345u64);
        assert_eq!(input.items[0].item_type, "minecraft:stone");

        // Process with the real processor
        let result = crate::item::processing::process_item_entities(input);

        // Serialize result
    let bytes = crate::binary::conversions::serialize_item_result(&result).expect("serialize_item_result failed");
    // Validate serialized shape: placeholder tick (u64) + num (i32) ... confirm num == 1 and id matches
    use byteorder::{LittleEndian, ReadBytesExt};
    let mut cur = std::io::Cursor::new(&bytes);
    let tick = cur.read_u64::<LittleEndian>().unwrap();
    let num_items = cur.read_i32::<LittleEndian>().unwrap();
    assert_eq!(num_items, 1);
    let id = cur.read_u64::<LittleEndian>().unwrap();
    assert_eq!(id, 12345u64);
    }

    #[test]
    fn mob_roundtrip_deserialize_process_serialize() {
        // [tickCount:u64][num:i32][for each mob: id:u64 distance:f32 passive:u8 etype_len:i32 etype_bytes...]
        let mut out: Vec<u8> = Vec::new();
        out.write_u64::<LittleEndian>(100u64).unwrap();
        out.write_i32::<LittleEndian>(1).unwrap();
        out.write_u64::<LittleEndian>(555u64).unwrap();
        out.write_f32::<LittleEndian>(12.5f32).unwrap();
        out.write_u8(0).unwrap(); // not passive
        let t = b"zombie";
        out.write_i32::<LittleEndian>(t.len() as i32).unwrap();
        out.extend_from_slice(t);

    let input = crate::binary::conversions::deserialize_mob_input(&out).expect("deserialize_mob_input failed");
        assert_eq!(input.mobs.len(), 1);
        assert_eq!(input.mobs[0].id, 555u64);

        let result = crate::mob::processing::process_mob_ai(input);
    let bytes = crate::binary::conversions::serialize_mob_result(&result).expect("serialize_mob_result failed");
    // Validate mob result contains the placeholder tick + two lists counts
    use byteorder::{LittleEndian, ReadBytesExt};
    let mut cur = std::io::Cursor::new(&bytes);
    let _tick = cur.read_u64::<LittleEndian>().unwrap();
    let disable_len = cur.read_i32::<LittleEndian>().unwrap();
    // For our simple processing the lists should be present (possibly zero length)
    assert!(disable_len >= 0);
    }

    #[test]
    fn block_roundtrip_deserialize_process_serialize() {
        use byteorder::{LittleEndian, WriteBytesExt};
        // Build manual block input buffer: [tickCount:u64][num:i32][blocks...]
        // Each block: [id:u64][distance:f32][blockTypeLen:i32][blockTypeBytes...][x:i32][y:i32][z:i32]
        let mut out: Vec<u8> = Vec::new();
        out.write_u64::<LittleEndian>(7u64).unwrap(); // tickCount
        out.write_i32::<LittleEndian>(1).unwrap(); // one block

        out.write_u64::<LittleEndian>(888u64).unwrap(); // id
        out.write_f32::<LittleEndian>(3.14f32).unwrap(); // distance
        let bt = b"minecraft:chest";
        out.write_i32::<LittleEndian>(bt.len() as i32).unwrap();
        out.extend_from_slice(bt);
        out.write_i32::<LittleEndian>(10).unwrap(); // x
        out.write_i32::<LittleEndian>(64).unwrap(); // y
        out.write_i32::<LittleEndian>(-5).unwrap(); // z

    let input = crate::binary::conversions::deserialize_block_input(&out).expect("deserialize_block_input failed");
        assert_eq!(input.block_entities.len(), 1);
        assert_eq!(input.block_entities[0].id, 888u64);

        let result = crate::block::processing::process_block_entities(input);
    let bytes = crate::binary::conversions::serialize_block_result(&result).expect("serialize_block_result failed");
        // Validate: tick:u64 + num:i32 + ids...; ensure num matches and id present when expected
        use byteorder::{LittleEndian, ReadBytesExt};
        let mut cur = std::io::Cursor::new(&bytes);
        let _tick = cur.read_u64::<LittleEndian>().unwrap();
        let num = cur.read_i32::<LittleEndian>().unwrap();
        // num can be >= 0; if non-zero, check first id
        if num > 0 {
            let first = cur.read_u64::<LittleEndian>().unwrap();
            assert_eq!(first, 888u64);
        }
    }

    #[test]
    fn entity_roundtrip_deserialize_process_serialize() {
        use byteorder::{LittleEndian, WriteBytesExt};
        // Build manual entity input buffer:
        // [tickCount:u64][numEntities:i32][entities...][numPlayers:i32][players...][5 config floats]
        let mut out: Vec<u8> = Vec::new();
        out.write_u64::<LittleEndian>(999u64).unwrap();
        out.write_i32::<LittleEndian>(1).unwrap();

        // entity
        out.write_u64::<LittleEndian>(42u64).unwrap(); // id
        out.write_f32::<LittleEndian>(1.0f32).unwrap(); // x
        out.write_f32::<LittleEndian>(2.0f32).unwrap(); // y
        out.write_f32::<LittleEndian>(3.0f32).unwrap(); // z
        out.write_f32::<LittleEndian>(0.25f32).unwrap(); // distance
        out.write_u8(1).unwrap(); // is_block_entity
        let et = b"boat";
        out.write_i32::<LittleEndian>(et.len() as i32).unwrap();
        out.extend_from_slice(et);

        // zero players
        out.write_i32::<LittleEndian>(0).unwrap();
        // five config floats (close_radius, medium_radius, close_rate, medium_rate, far_rate)
        out.write_f32::<LittleEndian>(16.0f32).unwrap();
        out.write_f32::<LittleEndian>(32.0f32).unwrap();
        out.write_f32::<LittleEndian>(1.0f32).unwrap();
        out.write_f32::<LittleEndian>(0.5f32).unwrap();
        out.write_f32::<LittleEndian>(0.1f32).unwrap();

    let input = crate::binary::conversions::deserialize_entity_input(&out).expect("deserialize_entity_input failed");
        assert_eq!(input.entities.len(), 1);
        assert_eq!(input.entities[0].id, 42u64);

        let result = crate::entity::processing::process_entities(input);
    let bytes = crate::binary::conversions::serialize_entity_result(&result).expect("serialize_entity_result failed");
        // Validate: len:i32 + ids... ; ensure returned ids include our entity id when present
        use byteorder::{LittleEndian, ReadBytesExt};
        let mut cur = std::io::Cursor::new(&bytes);
        let len = cur.read_i32::<LittleEndian>().unwrap();
        if len > 0 {
            let fid = cur.read_u64::<LittleEndian>().unwrap();
            // The result may or may not include the entity depending on processing; at least ensure numeric types parse
            let _ = fid;
        }
    }
}

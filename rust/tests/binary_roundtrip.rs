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

    // Append merged/despawned counts? conversions.deserialize_item_input doesn't expect them for input

    // Now call the crate deserializer
    let input = rustperf::binary::conversions::deserialize_item_input(&out).expect("deserialize_item_input failed");
    assert_eq!(input.items.len(), 1);
    assert_eq!(input.items[0].id, 12345u64);
    assert_eq!(input.items[0].item_type, "minecraft:stone");

    // Process with the real processor
    let result = rustperf::item::process_item_entities(input);

    // Serialize result
    let bytes = rustperf::binary::conversions::serialize_item_result(&result).expect("serialize_item_result failed");
    // At minimum, ensure serialization produced bytes
    assert!(!bytes.is_empty());
}

#[test]
fn mob_roundtrip_deserialize_process_serialize() {
    // [tickCount:u64][num:i32][for each mob: id:u64 distance:f32 passive:u8 etype_len:i32 etype_bytes...]
    use byteorder::{WriteBytesExt};
    let mut out: Vec<u8> = Vec::new();
    out.write_u64::<LittleEndian>(100u64).unwrap();
    out.write_i32::<LittleEndian>(1).unwrap();
    out.write_u64::<LittleEndian>(555u64).unwrap();
    out.write_f32::<LittleEndian>(12.5f32).unwrap();
    out.write_u8(0).unwrap(); // not passive
    let t = b"zombie";
    out.write_i32::<LittleEndian>(t.len() as i32).unwrap();
    out.extend_from_slice(t);

    let input = rustperf::binary::conversions::deserialize_mob_input(&out).expect("deserialize_mob_input failed");
    assert_eq!(input.mobs.len(), 1);
    assert_eq!(input.mobs[0].id, 555u64);

    let result = rustperf::mob::processing::process_mob_ai(input);
    let bytes = rustperf::binary::conversions::serialize_mob_result(&result).expect("serialize_mob_result failed");
    assert!(!bytes.is_empty());
}

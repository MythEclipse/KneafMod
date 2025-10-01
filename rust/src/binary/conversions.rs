use super::*;
use crate::mob::types::{MobInput, MobProcessResult};
use crate::block::types::{BlockInput, BlockProcessResult};
use crate::entity::types::{Input as EntityInput, ProcessResult as EntityProcessResult, EntityData as REntityData, PlayerData as RPlayerData};
use crate::item::types::{ItemInput, ItemProcessResult, ItemEntityData as RItemEntityData};
use std::convert::TryInto;
use std::io::{Cursor, Read};
use byteorder::{LittleEndian, ReadBytesExt, WriteBytesExt};
use crate::entity::config::Config as EntityConfig;

// Mob conversions
pub fn deserialize_mob_input(data: &[u8]) -> Result<MobInput, String> {
    // Java layout (little-endian): [tickCount:u64][numMobs:i32][mobs...][aiConfig floats...]
    let mut cur = Cursor::new(data);
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
        mobs.push(crate::mob::types::MobData { id, distance, entity_type: etype, is_passive: passive });
    }
    Ok(MobInput { tick_count, mobs })
}

pub fn serialize_mob_result(result: &MobProcessResult) -> Result<Vec<u8>, String> {
    // Java expects a list of mob ids to disable/simplify? We'll serialize two vectors lengths + ids for simplicity
    // Format: [disable_len:i32][disable_ids...][simplify_len:i32][simplify_ids...]
    let mut out: Vec<u8> = Vec::new();
    // Java/BinarySerializer expects a leading tickCount:u64 in the result buffer for list deserializers.
    // The Rust processing doesn't provide a meaningful tickCount for mob results, so write a placeholder 0.
    out.write_u64::<LittleEndian>(0u64).map_err(|e| e.to_string())?;
    out.write_i32::<LittleEndian>(result.mobs_to_disable_ai.len() as i32).map_err(|e| e.to_string())?;
    for id in &result.mobs_to_disable_ai { out.write_u64::<LittleEndian>(*id).map_err(|e| e.to_string())?; }
    out.write_i32::<LittleEndian>(result.mobs_to_simplify_ai.len() as i32).map_err(|e| e.to_string())?;
    for id in &result.mobs_to_simplify_ai { out.write_u64::<LittleEndian>(*id).map_err(|e| e.to_string())?; }
    Ok(out)
}

// Block conversions
pub fn deserialize_block_input(data: &[u8]) -> Result<BlockInput, String> {
    let mut cur = Cursor::new(data);
    let tick_count = cur.read_u64::<LittleEndian>().map_err(|e| e.to_string())?;
    let num = cur.read_i32::<LittleEndian>().map_err(|e| e.to_string())? as usize;
    let mut blocks = Vec::with_capacity(num);
    for _ in 0..num {
        let id = cur.read_u64::<LittleEndian>().map_err(|e| e.to_string())?;
        let distance = cur.read_f32::<LittleEndian>().map_err(|e| e.to_string())?;
        let bt_len = cur.read_i32::<LittleEndian>().map_err(|e| e.to_string())? as usize;
        let mut bt = String::new();
        if bt_len > 0 {
            let mut buf = vec![0u8; bt_len];
            cur.read_exact(&mut buf).map_err(|e| e.to_string())?;
            bt = String::from_utf8(buf).map_err(|e| e.to_string())?;
        }
        let x = cur.read_i32::<LittleEndian>().map_err(|e| e.to_string())?;
        let y = cur.read_i32::<LittleEndian>().map_err(|e| e.to_string())?;
        let z = cur.read_i32::<LittleEndian>().map_err(|e| e.to_string())?;
        blocks.push(crate::block::types::BlockEntityData{ id, block_type: bt, distance, x, y, z });
    }
    Ok(BlockInput { tick_count, block_entities: blocks })
}

pub fn serialize_block_result(result: &BlockProcessResult) -> Result<Vec<u8>, String> {
    // Serialize list of block entity ids to tick: [len:i32][ids...]
    let mut out: Vec<u8> = Vec::new();
    // Add a placeholder tickCount:u64 to match Java BinarySerializer list format ([tickCount:u64][num:i32][ids...])
    out.write_u64::<LittleEndian>(0u64).map_err(|e| e.to_string())?;
    out.write_i32::<LittleEndian>(result.block_entities_to_tick.len() as i32).map_err(|e| e.to_string())?;
    for id in &result.block_entities_to_tick { out.write_u64::<LittleEndian>(*id).map_err(|e| e.to_string())?; }
    Ok(out)
}

// Placeholder for entity conversions (if needed in the future)
pub fn deserialize_entity_input(data: &[u8]) -> Result<crate::entity::types::Input, String> {
    let mut cur = Cursor::new(data);
    let tick_count = cur.read_u64::<LittleEndian>().map_err(|e| e.to_string())?;
    let num_entities = cur.read_i32::<LittleEndian>().map_err(|e| e.to_string())? as usize;
    let mut entities = Vec::with_capacity(num_entities);
    for _ in 0..num_entities {
        let id = cur.read_u64::<LittleEndian>().map_err(|e| e.to_string())?;
        let x = cur.read_f32::<LittleEndian>().map_err(|e| e.to_string())? as f64;
        let y = cur.read_f32::<LittleEndian>().map_err(|e| e.to_string())? as f64;
        let z = cur.read_f32::<LittleEndian>().map_err(|e| e.to_string())? as f64;
        let distance = cur.read_f32::<LittleEndian>().map_err(|e| e.to_string())?;
        let is_block = cur.read_u8().map_err(|e| e.to_string())? != 0;
        let etype_len = cur.read_i32::<LittleEndian>().map_err(|e| e.to_string())? as usize;
        let mut etype = String::new();
        if etype_len > 0 {
            let mut buf = vec![0u8; etype_len];
            cur.read_exact(&mut buf).map_err(|e| e.to_string())?;
            etype = String::from_utf8(buf).map_err(|e| e.to_string())?;
        }
        entities.push(REntityData { id, entity_type: etype, x, y, z, distance, is_block_entity: is_block });
    }
    let num_players = cur.read_i32::<LittleEndian>().map_err(|e| e.to_string())? as usize;
    let mut players = Vec::with_capacity(num_players);
    for _ in 0..num_players {
        let id = cur.read_u64::<LittleEndian>().map_err(|e| e.to_string())?;
        let x = cur.read_f32::<LittleEndian>().map_err(|e| e.to_string())? as f64;
        let y = cur.read_f32::<LittleEndian>().map_err(|e| e.to_string())? as f64;
        let z = cur.read_f32::<LittleEndian>().map_err(|e| e.to_string())? as f64;
        players.push(RPlayerData { id, x, y, z });
    }
    // Skip entity config floats if present (5 floats)
    // Attempt to read 5 floats but ignore errors
    for _ in 0..5 { let _ = cur.read_f32::<LittleEndian>(); }
    let cfg = EntityConfig { close_radius: 16.0, medium_radius: 32.0, close_rate: 1.0, medium_rate: 0.5, far_rate: 0.1, use_spatial_partitioning: true, world_bounds: crate::types::Aabb::new(-1000.0, 0.0, -1000.0, 1000.0, 256.0, 1000.0), quadtree_max_entities: 1000, quadtree_max_depth: 10 };
    Ok(EntityInput { tick_count, entities, players, entity_config: cfg })
}

pub fn serialize_entity_result(result: &crate::entity::types::ProcessResult) -> Result<Vec<u8>, String> {
    // Serialize: [len:i32][ids...]
    let mut out: Vec<u8> = Vec::new();
    // Prepend a tickCount:u64 header for alignment and to match Java-side expectations.
    // Rust processing does not currently provide a meaningful tick count here, write 0.
    out.write_u64::<LittleEndian>(0u64).map_err(|e| e.to_string())?;
    out.write_i32::<LittleEndian>(result.entities_to_tick.len() as i32).map_err(|e| e.to_string())?;
    for id in &result.entities_to_tick { out.write_u64::<LittleEndian>(*id).map_err(|e| e.to_string())?; }
    Ok(out)
}

// Placeholder for item conversions (if needed in the future)
pub fn deserialize_item_input(data: &[u8]) -> Result<crate::item::ItemInput, String> {
    let mut cur = Cursor::new(data);
    let tick_count = cur.read_u64::<LittleEndian>().map_err(|e| e.to_string())?;
    let num = cur.read_i32::<LittleEndian>().map_err(|e| e.to_string())? as usize;
    let mut items = Vec::with_capacity(num);
    for _ in 0..num {
        let id = cur.read_u64::<LittleEndian>().map_err(|e| e.to_string())?;
        let chunk_x = cur.read_i32::<LittleEndian>().map_err(|e| e.to_string())?;
        let chunk_z = cur.read_i32::<LittleEndian>().map_err(|e| e.to_string())?;
        let itype_len = cur.read_i32::<LittleEndian>().map_err(|e| e.to_string())? as usize;
        let mut itype = String::new();
        if itype_len > 0 { let mut buf = vec![0u8; itype_len]; cur.read_exact(&mut buf).map_err(|e| e.to_string())?; itype = String::from_utf8(buf).map_err(|e| e.to_string())?; }
    let count = cur.read_u32::<LittleEndian>().map_err(|e| e.to_string())?;
    let age = cur.read_u32::<LittleEndian>().map_err(|e| e.to_string())?;
    items.push(RItemEntityData { id, chunk_x, chunk_z, item_type: itype, count, age_seconds: age });
    }
    Ok(ItemInput { items })
}
pub fn serialize_item_result(result: &crate::item::ItemProcessResult) -> Result<Vec<u8>, String> {
    // Java side expects the BinarySerializer list format for deserializing item process results:
    // [tickCount:u64][numItems:i32][items...]
    // Each item: [id:u64][chunkX:i32][chunkZ:i32][itemTypeLen:i32][itemTypeBytes...][count:i32][ageSeconds:i32]
    // We'll write a placeholder tickCount (0) because the Rust result does not use it for items.
    let mut out: Vec<u8> = Vec::new();
    // placeholder tickCount
    out.write_u64::<LittleEndian>(0u64).map_err(|e| e.to_string())?;

    // Build a vector of ItemEntityData representing the current state: updates (with new_count) and removals (count=0)
    // We'll represent removed items as entries with count == 0 so Java can detect removals.
    let mut items: Vec<(u64, i32, i32, String, i32, i32)> = Vec::new();

    // Item updates: we don't have chunkX/chunkZ/ageSeconds in ItemUpdate; leave as zeros where unknown.
    for upd in &result.item_updates {
        items.push((upd.id, 0, 0, String::new(), upd.new_count as i32, 0));
    }

    // Items to remove: represent as entries with count == 0
    for id in &result.items_to_remove {
        items.push((*id, 0, 0, String::new(), 0, 0));
    }

    // Write number of items
    out.write_i32::<LittleEndian>(items.len() as i32).map_err(|e| e.to_string())?;

    for (id, chunk_x, chunk_z, item_type, count, age) in items {
        out.write_u64::<LittleEndian>(id).map_err(|e| e.to_string())?;
        out.write_i32::<LittleEndian>(chunk_x).map_err(|e| e.to_string())?;
        out.write_i32::<LittleEndian>(chunk_z).map_err(|e| e.to_string())?;
        let bytes = item_type.as_bytes();
        out.write_i32::<LittleEndian>(bytes.len() as i32).map_err(|e| e.to_string())?;
        if !bytes.is_empty() { out.extend_from_slice(bytes); }
        out.write_i32::<LittleEndian>(count).map_err(|e| e.to_string())?;
        out.write_i32::<LittleEndian>(age).map_err(|e| e.to_string())?;
    }

    // Also append merged/despawned counts at the end for compatibility, though Java code currently ignores these
    out.write_i64::<LittleEndian>(result.merged_count as i64).map_err(|e| e.to_string())?;
    out.write_i64::<LittleEndian>(result.despawned_count as i64).map_err(|e| e.to_string())?;

    Ok(out)
}

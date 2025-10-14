use crate::block::types::{BlockInput, BlockProcessResult};
use crate::entity::types::{
    EntityData as REntityData, Input as EntityInput, PlayerData as RPlayerData,
};
use crate::mob::types::{MobInput, MobProcessResult};
use crate::villager::types::{
    PlayerData as VillagerPlayerData, VillagerData, VillagerInput, VillagerProcessResult,
};

use crate::entity::config::Config as EntityConfig;
use byteorder::{LittleEndian, ReadBytesExt, WriteBytesExt};
use std::io::{Cursor, Read};
use std::time::{SystemTime, UNIX_EPOCH};

// Mob conversions
pub fn deserialize_mob_input(data: &[u8]) -> Result<MobInput, String> {
    // Java layout (little-endian): [tickCount:u64][numMobs:i32][mobs...][aiConfig floats...]
    let mut cur = Cursor::new(data);
    let tick_count = cur.read_u64::<LittleEndian>().map_err(|e| e.to_string())?;
    let num = cur.read_i32::<LittleEndian>().map_err(|e| e.to_string())? as usize;

    // Validate num to prevent excessive allocation
    if num > 10000 {
        return Err("Too many mobs".to_string());
    }

    let mut mobs = Vec::with_capacity(num);
    for _ in 0..num {
        let id = cur.read_u64::<LittleEndian>().map_err(|e| e.to_string())?;
        let distance = cur.read_f32::<LittleEndian>().map_err(|e| e.to_string())?;
        let passive = cur.read_u8().map_err(|e| e.to_string())? != 0;
        let etype_len = cur.read_i32::<LittleEndian>().map_err(|e| e.to_string())? as usize;

        // Validate etype_len to prevent capacity overflow
        if etype_len > 1000 {
            return Err("Entity type name too long".to_string());
        }

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

pub fn serialize_mob_result(result: &MobProcessResult) -> Result<Vec<u8>, String> {
    // Return JSON object instead of binary data to avoid JNI string conversion issues
    let json = format!("{{\"disableList\":{},\"simplifyList\":{}}}",
        format!("[{}]", result.mobs_to_disable_ai.iter()
            .map(|id| id.to_string())
            .collect::<Vec<String>>()
            .join(",")),
        format!("[{}]", result.mobs_to_simplify_ai.iter()
            .map(|id| id.to_string())
            .collect::<Vec<String>>()
            .join(","))
    );
    Ok(json.into_bytes())
}

// Block conversions
pub fn deserialize_block_input(data: &[u8]) -> Result<BlockInput, String> {
    let mut cur = Cursor::new(data);
    let tick_count = cur.read_u64::<LittleEndian>().map_err(|e| e.to_string())?;
    let num = cur.read_i32::<LittleEndian>().map_err(|e| e.to_string())? as usize;

    // Validate num to prevent excessive allocation
    if num > 10000 {
        return Err("Too many block entities".to_string());
    }

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
        blocks.push(crate::block::types::BlockEntityData {
            id,
            block_type: bt,
            distance,
            x,
            y,
            z,
        });
    }
    Ok(BlockInput {
        tick_count,
        block_entities: blocks,
    })
}

pub fn serialize_block_result(result: &BlockProcessResult) -> Result<Vec<u8>, String> {
    // Return JSON array instead of binary data to avoid JNI string conversion issues
    let json = format!("[{}]", result.block_entities_to_tick.iter()
        .map(|id| id.to_string())
        .collect::<Vec<String>>()
        .join(","));
    Ok(json.into_bytes())
}

// Placeholder for entity conversions (if needed in the future)
pub fn deserialize_entity_input(data: &[u8]) -> Result<crate::entity::types::Input, String> {
    let mut cur = Cursor::new(data);
    let tick_count = cur.read_u64::<LittleEndian>().map_err(|e| e.to_string())?;
    let num_entities = cur.read_i32::<LittleEndian>().map_err(|e| e.to_string())? as usize;

    // Validate num_entities to prevent excessive allocation
    if num_entities > 10000 {
        return Err("Too many entities".to_string());
    }

    let mut entities = Vec::with_capacity(num_entities);
    for _ in 0..num_entities {
        let id = cur.read_u64::<LittleEndian>().map_err(|e| e.to_string())?;
        let x = cur.read_f32::<LittleEndian>().map_err(|e| e.to_string())? as f64;
        let y = cur.read_f32::<LittleEndian>().map_err(|e| e.to_string())? as f64;
        let z = cur.read_f32::<LittleEndian>().map_err(|e| e.to_string())? as f64;
        let distance = cur.read_f32::<LittleEndian>().map_err(|e| e.to_string())?;
        let is_block = cur.read_u8().map_err(|e| e.to_string())? != 0;
        let etype_len = cur.read_i32::<LittleEndian>().map_err(|e| e.to_string())? as usize;

        // Validate etype_len to prevent capacity overflow
        if etype_len > 1000 {
            return Err("Entity type name too long".to_string());
        }

        let mut etype = String::new();
        if etype_len > 0 {
            let mut buf = vec![0u8; etype_len];
            cur.read_exact(&mut buf).map_err(|e| e.to_string())?;
            etype = String::from_utf8(buf).map_err(|e| e.to_string())?;
        }
        entities.push(REntityData {
            id,
            entity_type: etype,
            x,
            y,
            z,
            distance,
            is_block_entity: is_block,
        });
    }
    let num_players = cur.read_i32::<LittleEndian>().map_err(|e| e.to_string())? as usize;

    // Validate num_players
    if num_players > 1000 {
        return Err("Too many players".to_string());
    }

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
    for _ in 0..5 {
        let _ = cur.read_f32::<LittleEndian>();
    }
    let cfg = EntityConfig {
        close_radius: 16.0,
        medium_radius: 32.0,
        close_rate: 1.0,
        medium_rate: 0.5,
        far_rate: 0.1,
        use_spatial_partitioning: true,
        world_bounds: crate::types::Aabb::new(-1000.0, 0.0, -1000.0, 1000.0, 256.0, 1000.0),
        quadtree_max_entities: 1000,
        quadtree_max_depth: 10,
    };
    Ok(EntityInput {
        tick_count,
        entities,
        players,
        entity_config: cfg,
    })
}

pub fn serialize_entity_result(
    result: &crate::entity::types::ProcessResult,
) -> Result<Vec<u8>, String> {
    // Return JSON string instead of binary data to avoid JNI string conversion issues
    let json = format!("[{}]", result.entities_to_tick.iter()
        .map(|id| id.to_string())
        .collect::<Vec<String>>()
        .join(","));
    Ok(json.into_bytes())
}

// Villager conversions
pub fn deserialize_villager_input(data: &[u8]) -> Result<VillagerInput, String> {
    let mut cur = Cursor::new(data);
    let tick_count = cur.read_u64::<LittleEndian>().map_err(|e| e.to_string())?;
    let num = cur.read_i32::<LittleEndian>().map_err(|e| e.to_string())? as usize;

    // Validate num to prevent excessive allocation
    if num > 10000 {
        return Err("Too many villagers".to_string());
    }

    let mut villagers = Vec::with_capacity(num);

    for _ in 0..num {
        let id = cur.read_u64::<LittleEndian>().map_err(|e| e.to_string())?;
        let x = cur.read_f32::<LittleEndian>().map_err(|e| e.to_string())?;
        let y = cur.read_f32::<LittleEndian>().map_err(|e| e.to_string())?;
        let z = cur.read_f32::<LittleEndian>().map_err(|e| e.to_string())?;
        let distance = cur.read_f32::<LittleEndian>().map_err(|e| e.to_string())?;
        let profession_len = cur.read_i32::<LittleEndian>().map_err(|e| e.to_string())? as usize;

        // Validate profession_len to prevent capacity overflow
        if profession_len > 1000 {
            return Err("Villager profession name too long".to_string());
        }

        let mut profession = String::new();
        if profession_len > 0 {
            let mut buf = vec![0u8; profession_len];
            cur.read_exact(&mut buf).map_err(|e| e.to_string())?;
            profession = String::from_utf8(buf).map_err(|e| e.to_string())?;
        }
        let level = cur.read_u8().map_err(|e| e.to_string())?;
        let has_workstation = cur.read_u8().map_err(|e| e.to_string())? != 0;
        let is_resting = cur.read_u8().map_err(|e| e.to_string())? != 0;
        let is_breeding = cur.read_u8().map_err(|e| e.to_string())? != 0;
        let last_pathfind_tick = cur.read_u64::<LittleEndian>().map_err(|e| e.to_string())?;
        let pathfind_frequency = cur.read_u8().map_err(|e| e.to_string())?;
        let ai_complexity = cur.read_u8().map_err(|e| e.to_string())?;

        villagers.push(VillagerData {
            id,
            x,
            y,
            z,
            distance,
            profession,
            level,
            has_workstation,
            is_resting,
            is_breeding,
            last_pathfind_tick,
            pathfind_frequency,
            ai_complexity,
        });
    }

    // Read players
    let num_players = cur.read_i32::<LittleEndian>().map_err(|e| e.to_string())? as usize;

    // Validate num_players
    if num_players > 1000 {
        return Err("Too many players".to_string());
    }

    let mut players = Vec::with_capacity(num_players);
    for _ in 0..num_players {
        let id = cur.read_u64::<LittleEndian>().map_err(|e| e.to_string())?;
        let x = cur.read_f32::<LittleEndian>().map_err(|e| e.to_string())?;
        let y = cur.read_f32::<LittleEndian>().map_err(|e| e.to_string())?;
        let z = cur.read_f32::<LittleEndian>().map_err(|e| e.to_string())?;
        players.push(VillagerPlayerData { id, x, y, z });
    }

    Ok(VillagerInput {
        tick_count,
        villagers,
        players,
    })
}

pub fn serialize_villager_result(result: &VillagerProcessResult) -> Result<Vec<u8>, String> {
    // Format: [tickCount:u64][disable_len:i32][disable_ids...][simplify_len:i32][simplify_ids...][reduce_pathfind_len:i32][reduce_pathfind_ids...][num_groups:i32][groups...]
    let mut out: Vec<u8> = Vec::new();

    // Use current timestamp as tickCount
    out.write_u64::<LittleEndian>(SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_millis() as u64)
        .map_err(|e| e.to_string())?;

    // Villagers to disable AI
    out.write_i32::<LittleEndian>(result.villagers_to_disable_ai.len() as i32)
        .map_err(|e| e.to_string())?;
    for id in &result.villagers_to_disable_ai {
        out.write_u64::<LittleEndian>(*id)
            .map_err(|e| e.to_string())?;
    }

    // Villagers to simplify AI
    out.write_i32::<LittleEndian>(result.villagers_to_simplify_ai.len() as i32)
        .map_err(|e| e.to_string())?;
    for id in &result.villagers_to_simplify_ai {
        out.write_u64::<LittleEndian>(*id)
            .map_err(|e| e.to_string())?;
    }

    // Villagers to reduce pathfinding
    out.write_i32::<LittleEndian>(result.villagers_to_reduce_pathfinding.len() as i32)
        .map_err(|e| e.to_string())?;
    for id in &result.villagers_to_reduce_pathfinding {
        out.write_u64::<LittleEndian>(*id)
            .map_err(|e| e.to_string())?;
    }

    // Villager groups
    out.write_i32::<LittleEndian>(result.villager_groups.len() as i32)
        .map_err(|e| e.to_string())?;
    for group in &result.villager_groups {
        out.write_u32::<LittleEndian>(group.group_id)
            .map_err(|e| e.to_string())?;
        out.write_f32::<LittleEndian>(group.center_x)
            .map_err(|e| e.to_string())?;
        out.write_f32::<LittleEndian>(group.center_y)
            .map_err(|e| e.to_string())?;
        out.write_f32::<LittleEndian>(group.center_z)
            .map_err(|e| e.to_string())?;

        let group_type_len = group.group_type.len() as i32;
        out.write_i32::<LittleEndian>(group_type_len)
            .map_err(|e| e.to_string())?;
        out.extend_from_slice(group.group_type.as_bytes());

        out.write_u8(group.ai_tick_rate)
            .map_err(|e| e.to_string())?;

        out.write_i32::<LittleEndian>(group.villager_ids.len() as i32)
            .map_err(|e| e.to_string())?;
        for id in &group.villager_ids {
            out.write_u64::<LittleEndian>(*id)
                .map_err(|e| e.to_string())?;
        }
    }

    Ok(out)
}

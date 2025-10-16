use crate::entities::block::types::{BlockInput, BlockProcessResult};
use crate::types::{EntityData as REntityData, PlayerData as RPlayerData, EntityType};
use crate::entities::entity::types::{Input as EntityInput, ProcessResult as EntityProcessResult};
use crate::entities::mob::types::{MobInput, MobProcessResult};
use crate::entities::villager::types::{
    PlayerData as VillagerPlayerData, VillagerData, VillagerInput, VillagerProcessResult,
};
use crate::types::EntityConfig;
use byteorder::{LittleEndian, ReadBytesExt, WriteBytesExt};
use std::io::{Cursor, Read};
use std::time::{SystemTime, UNIX_EPOCH};

// Common error type for binary conversions
#[derive(Debug, thiserror::Error)]
pub enum BinaryConversionError {
    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),
    
    #[error("UTF-8 conversion failed: {0}")]
    Utf8(#[from] std::string::FromUtf8Error),
    
    #[error("Invalid data: {0}")]
    InvalidData(String),
    
    #[error("System time error: {0}")]
    SystemTime(#[from] std::time::SystemTimeError),
}

// Trait defining the conversion interface
pub trait BinaryConverter {
    type Input;
    type Output;
    
    fn deserialize(&self, data: &[u8]) -> Result<Self::Input, BinaryConversionError>;
    fn serialize(&self, data: &Self::Output) -> Result<Vec<u8>, BinaryConversionError>;
}

// Basic factory for creating binary converters (legacy)
pub struct BinaryConverterFactory;

impl BinaryConverterFactory {
    // Mob converter
    pub fn mob_converter() -> impl BinaryConverter<Input=MobInput, Output=MobProcessResult> {
        MobConverter::new()
    }
    
    // Block converter
    pub fn block_converter() -> impl BinaryConverter<Input=BlockInput, Output=BlockProcessResult> {
        BlockConverter::new()
    }
    
    // Entity converter
    pub fn entity_converter() -> impl BinaryConverter<Input=EntityInput, Output=EntityProcessResult> {
        EntityConverter::new()
    }
    
    // Villager converter
    pub fn villager_converter() -> impl BinaryConverter<Input=VillagerInput, Output=VillagerProcessResult> {
        VillagerConverter::new()
    }
}

// Enhanced converter implementations with configuration support
mod enhanced {
    use super::*;

    // Mob converter with configuration support
    pub struct MobConverter {
        config: BinaryConverterConfig,
    }

    impl MobConverter {
        pub fn new() -> Self {
            Self::new_with_config(BinaryConverterConfig::default())
        }
        
        pub fn new_with_config(config: BinaryConverterConfig) -> Self {
            Self { config }
        }
    }

    impl BinaryConverter for MobConverter {
        type Input = MobInput;
        type Output = MobProcessResult;
        
        fn deserialize(&self, data: &[u8]) -> Result<Self::Input, BinaryConversionError> {
            if self.config.validate_input {
                // Add validation logic here if needed
            }
            super::MobConverter.deserialize(data)
        }
        
        fn serialize(&self, data: &Self::Output) -> Result<Vec<u8>, BinaryConversionError> {
            if self.config.compress_large_data && data.mobs_to_disable_ai.len() > self.config.compression_threshold {
                // Add compression logic here if needed
            }
            super::MobConverter.serialize(data)
        }
    }

    // Block converter with configuration support
    pub struct BlockConverter {
        config: BinaryConverterConfig,
    }

    impl BlockConverter {
        pub fn new() -> Self {
            Self::new_with_config(BinaryConverterConfig::default())
        }
        
        pub fn new_with_config(config: BinaryConverterConfig) -> Self {
            Self { config }
        }
    }

    impl BinaryConverter for BlockConverter {
        type Input = BlockInput;
        type Output = BlockProcessResult;
        
        fn deserialize(&self, data: &[u8]) -> Result<Self::Input, BinaryConversionError> {
            if self.config.validate_input {
                // Add validation logic here if needed
            }
            super::BlockConverter.deserialize(data)
        }
        
        fn serialize(&self, data: &Self::Output) -> Result<Vec<u8>, BinaryConversionError> {
            if self.config.compress_large_data && data.block_entities_to_tick.len() > self.config.compression_threshold {
                // Add compression logic here if needed
            }
            super::BlockConverter.serialize(data)
        }
    }

    // Entity converter with configuration support
    pub struct EntityConverter {
        config: BinaryConverterConfig,
    }

    impl EntityConverter {
        pub fn new() -> Self {
            Self::new_with_config(BinaryConverterConfig::default())
        }
        
        pub fn new_with_config(config: BinaryConverterConfig) -> Self {
            Self { config }
        }
    }

    impl BinaryConverter for EntityConverter {
        type Input = EntityInput;
        type Output = EntityProcessResult;
        
        fn deserialize(&self, data: &[u8]) -> Result<Self::Input, BinaryConversionError> {
            if self.config.validate_input {
                // Add validation logic here if needed
            }
            super::EntityConverter.deserialize(data)
        }
        
        fn serialize(&self, data: &Self::Output) -> Result<Vec<u8>, BinaryConversionError> {
            if self.config.compress_large_data && data.entities_to_tick.len() > self.config.compression_threshold {
                // Add compression logic here if needed
            }
            super::EntityConverter.serialize(data)
        }
    }

    // Villager converter with configuration support
    pub struct VillagerConverter {
        config: BinaryConverterConfig,
    }

    impl VillagerConverter {
        pub fn new() -> Self {
            Self::new_with_config(BinaryConverterConfig::default())
        }
        
        pub fn new_with_config(config: BinaryConverterConfig) -> Self {
            Self { config }
        }
    }

    impl BinaryConverter for VillagerConverter {
        type Input = VillagerInput;
        type Output = VillagerProcessResult;
        
        fn deserialize(&self, data: &[u8]) -> Result<Self::Input, BinaryConversionError> {
            if self.config.validate_input {
                // Add validation logic here if needed
            }
            super::VillagerConverter.deserialize(data)
        }
        
        fn serialize(&self, data: &Self::Output) -> Result<Vec<u8>, BinaryConversionError> {
            if self.config.compress_large_data {
                let total_entries = data.villagers_to_disable_ai.len()
                    + data.villagers_to_simplify_ai.len()
                    + data.villagers_to_reduce_pathfinding.len()
                    + data.villager_groups.len();
                
                if total_entries > self.config.compression_threshold {
                    // Add compression logic here if needed
                }
            }
            super::VillagerConverter.serialize(data)
        }
    }
}

// Re-export for use in factory module
pub use enhanced::*;
pub use crate::types::BinaryConverterConfig;

// Common validation utilities
mod validation {
    use super::BinaryConversionError;
    
    pub fn validate_count(count: usize, max: usize, entity_type: &str) -> Result<(), BinaryConversionError> {
        if count > max {
            return Err(BinaryConversionError::InvalidData(format!(
                "Too many {} (max {})",
                entity_type, max
            )));
        }
        Ok(())
    }
    
    pub fn validate_string_length(len: usize, max: usize, field: &str) -> Result<(), BinaryConversionError> {
        if len > max {
            return Err(BinaryConversionError::InvalidData(format!(
                "{} too long (max {} chars)",
                field, max
            )));
        }
        Ok(())
    }
    
    pub fn validate_data_remaining(data: &[u8], position: u64, required: usize) -> Result<(), BinaryConversionError> {
        if position + required as u64 > data.len() as u64 {
            return Err(BinaryConversionError::InvalidData(
                "Insufficient data remaining".to_string()
            ));
        }
        Ok(())
    }
}

// Common conversion utilities
pub mod conversion_utils {
    use super::*;
    
    pub fn read_string(cur: &mut Cursor<&[u8]>, len: usize) -> Result<String, BinaryConversionError> {
        let mut buf = vec![0u8; len];
        cur.read_exact(&mut buf)?;
        Ok(String::from_utf8(buf)?)
    }
    
    pub fn write_string(cur: &mut Vec<u8>, s: &str) -> Result<(), BinaryConversionError> {
        let len = s.len() as i32;
        cur.write_i32::<LittleEndian>(len)?;
        if len > 0 {
            cur.extend_from_slice(s.as_bytes());
        }
        Ok(())
    }
}

// Mob converter implementation
struct MobConverter;

impl BinaryConverter for MobConverter {
    type Input = MobInput;
    type Output = MobProcessResult;
    
    fn deserialize(&self, data: &[u8]) -> Result<Self::Input, BinaryConversionError> {
        let mut cur = Cursor::new(data);
        let tick_count = cur.read_u64::<LittleEndian>()?;
        let num = cur.read_i32::<LittleEndian>()? as usize;
        
        validation::validate_count(num, 10000, "mobs")?;
        
        let mut mobs = Vec::with_capacity(num);
        for _ in 0..num {
            let id = cur.read_u64::<LittleEndian>()?;
            let distance = cur.read_f32::<LittleEndian>()?;
            let passive = cur.read_u8()? != 0;
            let etype_len = cur.read_i32::<LittleEndian>()? as usize;
            
            validation::validate_string_length(etype_len, 1000, "Entity type name")?;
            validation::validate_data_remaining(data, cur.position(), etype_len)?;
            
            let etype = if etype_len > 0 {
                conversion_utils::read_string(&mut cur, etype_len)?
            } else {
                String::new()
            };
            
            mobs.push(crate::mob::types::MobData {
                id,
                entity_type: EntityType::Mob, // Convert string to EntityType
                position: (0.0, 0.0, 0.0), // Default position
                distance,
                is_passive: passive,
            });
        }
        
        Ok(MobInput { tick_count, mobs })
    }
    
    fn serialize(&self, data: &Self::Output) -> Result<Vec<u8>, BinaryConversionError> {
        let mut out = Vec::new();
        
        out.write_i32::<LittleEndian>(data.mobs_to_disable_ai.len() as i32)?;
        for id in &data.mobs_to_disable_ai {
            out.write_u64::<LittleEndian>(*id)?;
        }
        
        out.write_i32::<LittleEndian>(data.mobs_to_simplify_ai.len() as i32)?;
        for id in &data.mobs_to_simplify_ai {
            out.write_u64::<LittleEndian>(*id)?;
        }
        
        Ok(out)
    }
}

// Block converter implementation
struct BlockConverter;

impl BinaryConverter for BlockConverter {
    type Input = BlockInput;
    type Output = BlockProcessResult;
    
    fn deserialize(&self, data: &[u8]) -> Result<Self::Input, BinaryConversionError> {
        let mut cur = Cursor::new(data);
        let tick_count = cur.read_u64::<LittleEndian>()?;
        let num = cur.read_i32::<LittleEndian>()? as usize;
        
        validation::validate_count(num, 10000, "block entities")?;
        
        let mut blocks = Vec::with_capacity(num);
        for _ in 0..num {
            let id = cur.read_u64::<LittleEndian>()?;
            let distance = cur.read_f32::<LittleEndian>()?;
            let bt_len = cur.read_i32::<LittleEndian>()? as usize;
            
            validation::validate_data_remaining(data, cur.position(), bt_len)?;
            
            let bt = if bt_len > 0 {
                conversion_utils::read_string(&mut cur, bt_len)?
            } else {
                String::new()
            };
            
            let x = cur.read_i32::<LittleEndian>()?;
            let y = cur.read_i32::<LittleEndian>()?;
            let z = cur.read_i32::<LittleEndian>()?;
            
            blocks.push(crate::block::types::BlockEntityData {
                id,
                block_type: bt,
                x,
                y,
                z,
                distance,
            });
        }
        
        Ok(BlockInput {
            tick_count,
            block_entities: blocks,
        })
    }
    
    fn serialize(&self, data: &Self::Output) -> Result<Vec<u8>, BinaryConversionError> {
        let mut out = Vec::new();
        
        out.write_i32::<LittleEndian>(data.block_entities_to_tick.len() as i32)?;
        for id in &data.block_entities_to_tick {
            out.write_u64::<LittleEndian>(*id)?;
        }
        
        Ok(out)
    }
}

// --- Compatibility helper functions (legacy API) ---
pub fn deserialize_entity_input(data: &[u8]) -> Result<EntityInput, BinaryConversionError> {
    let conv = BinaryConverterFactory::entity_converter();
    conv.deserialize(data)
}

pub fn serialize_entity_result(result: &EntityProcessResult) -> Result<Vec<u8>, BinaryConversionError> {
    let conv = BinaryConverterFactory::entity_converter();
    conv.serialize(result)
}

pub fn deserialize_block_input(data: &[u8]) -> Result<BlockInput, BinaryConversionError> {
    let conv = BinaryConverterFactory::block_converter();
    conv.deserialize(data)
}

pub fn serialize_block_result(result: &BlockProcessResult) -> Result<Vec<u8>, BinaryConversionError> {
    let conv = BinaryConverterFactory::block_converter();
    conv.serialize(result)
}

pub fn deserialize_villager_input(data: &[u8]) -> Result<VillagerInput, BinaryConversionError> {
    let conv = BinaryConverterFactory::villager_converter();
    conv.deserialize(data)
}

pub fn serialize_villager_result(result: &VillagerProcessResult) -> Result<Vec<u8>, BinaryConversionError> {
    let conv = BinaryConverterFactory::villager_converter();
    conv.serialize(result)
}

pub fn deserialize_mob_input(data: &[u8]) -> Result<MobInput, BinaryConversionError> {
    let conv = BinaryConverterFactory::mob_converter();
    conv.deserialize(data)
}

pub fn serialize_mob_result(result: &MobProcessResult) -> Result<Vec<u8>, BinaryConversionError> {
    let conv = BinaryConverterFactory::mob_converter();
    conv.serialize(result)
}

// Entity converter implementation
struct EntityConverter;

impl BinaryConverter for EntityConverter {
    type Input = EntityInput;
    type Output = EntityProcessResult;
    
    fn deserialize(&self, data: &[u8]) -> Result<Self::Input, BinaryConversionError> {
        let mut cur = Cursor::new(data);
        let tick_count = cur.read_u64::<LittleEndian>()?;
        let num_entities = cur.read_i32::<LittleEndian>()? as usize;
        
        validation::validate_count(num_entities, 10000, "entities")?;
        
        let mut entities = Vec::with_capacity(num_entities);
        for _ in 0..num_entities {
            let id = cur.read_u64::<LittleEndian>()?;
            let x = cur.read_f32::<LittleEndian>()? as f64;
            let y = cur.read_f32::<LittleEndian>()? as f64;
            let z = cur.read_f32::<LittleEndian>()? as f64;
            let distance = cur.read_f32::<LittleEndian>()?;
            let is_block = cur.read_u8()? != 0;
            let etype_len = cur.read_i32::<LittleEndian>()? as usize;
            
            if etype_len < 0 {
                return Err(BinaryConversionError::InvalidData(
                    "Negative entity type length".to_string()
                ));
            }
            
            const MAX_ENTITY_TYPE_LEN: usize = 1000;
            validation::validate_string_length(etype_len, MAX_ENTITY_TYPE_LEN, "Entity type name")?;
            validation::validate_data_remaining(data, cur.position(), etype_len)?;
            
            let etype = if etype_len > 0 {
                conversion_utils::read_string(&mut cur, etype_len)?
            } else {
                String::new()
            };
            
            entities.push(REntityData {
                entity_id: id.to_string(),
                entity_type: EntityType::Generic, // Convert string to EntityType
                x: x as f32,
                y: y as f32,
                z: z as f32,
                health: 20.0,
                max_health: 20.0,
                velocity_x: 0.0,
                velocity_y: 0.0,
                velocity_z: 0.0,
                rotation: 0.0,
                pitch: 0.0,
                yaw: 0.0,
                properties: std::collections::HashMap::new(),
            });
        }
        
        let num_players = cur.read_i32::<LittleEndian>()? as usize;
        validation::validate_count(num_players, 1000, "players")?;
        
        let mut players = Vec::with_capacity(num_players);
        for _ in 0..num_players {
            let id = cur.read_u64::<LittleEndian>()?;
            let x = cur.read_f32::<LittleEndian>()? as f64;
            let y = cur.read_f32::<LittleEndian>()? as f64;
            let z = cur.read_f32::<LittleEndian>()? as f64;
            players.push(RPlayerData {
                uuid: id.to_string(),
                username: "Player".to_string(),
                x: x as f32,
                y: y as f32,
                z: z as f32,
                health: 20.0,
                max_health: 20.0,
                level: 1,
                experience: 0,
                inventory: Vec::new(),
            });
        }
        
        // Skip entity config floats if present (5 floats)
        for _ in 0..5 {
            let _ = cur.read_f32::<LittleEndian>();
        }
        
        use crate::entities::mob::types::DefaultEntityConfig;
        let cfg = DefaultEntityConfig {
            entity_type: entity_type.clone(),
        };
        
        Ok(EntityInput {
            tick_count,
            entities,
            players,
            entity_config: cfg,
        })
    }
    
    fn serialize(&self, data: &Self::Output) -> Result<Vec<u8>, BinaryConversionError> {
        let mut out = Vec::new();
        
        out.write_i32::<LittleEndian>(data.entities_to_tick.len() as i32)?;
        for id in &data.entities_to_tick {
            out.write_u64::<LittleEndian>(*id)?;
        }
        
        Ok(out)
    }
}

// Villager converter implementation
struct VillagerConverter;

impl BinaryConverter for VillagerConverter {
    type Input = VillagerInput;
    type Output = VillagerProcessResult;
    
    fn deserialize(&self, data: &[u8]) -> Result<Self::Input, BinaryConversionError> {
        let mut cur = Cursor::new(data);
        let tick_count = cur.read_u64::<LittleEndian>()?;
        let num = cur.read_i32::<LittleEndian>()? as usize;
        
        validation::validate_count(num, 10000, "villagers")?;
        
        let mut villagers = Vec::with_capacity(num);
        
        for _ in 0..num {
            let id = cur.read_u64::<LittleEndian>()?;
            let x = cur.read_f32::<LittleEndian>()?;
            let y = cur.read_f32::<LittleEndian>()?;
            let z = cur.read_f32::<LittleEndian>()?;
            let distance = cur.read_f32::<LittleEndian>()?;
            let profession_len = cur.read_i32::<LittleEndian>()? as usize;
            
            validation::validate_string_length(profession_len, 1000, "Villager profession name")?;
            validation::validate_data_remaining(data, cur.position(), profession_len)?;
            
            let profession = if profession_len > 0 {
                conversion_utils::read_string(&mut cur, profession_len)?
            } else {
                String::new()
            };
            
            let level = cur.read_u8()?;
            let has_workstation = cur.read_u8()? != 0;
            let is_resting = cur.read_u8()? != 0;
            let is_breeding = cur.read_u8()? != 0;
            let last_pathfind_tick = cur.read_u64::<LittleEndian>()?;
            let pathfind_frequency = cur.read_u8()?;
            let ai_complexity = cur.read_u8()?;
            
            villagers.push(VillagerData {
                id,
                entity_type: EntityType::Villager,
                position: (x, y, z),
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
        
        let num_players = cur.read_i32::<LittleEndian>()? as usize;
        validation::validate_count(num_players, 1000, "players")?;
        
        let mut players = Vec::with_capacity(num_players);
        for _ in 0..num_players {
            let id = cur.read_u64::<LittleEndian>()?;
            let x = cur.read_f32::<LittleEndian>()?;
            let y = cur.read_f32::<LittleEndian>()?;
            let z = cur.read_f32::<LittleEndian>()?;
            players.push(VillagerPlayerData {
                id,
                x: x as f64,
                y: y as f64,
                z: z as f64,
            });
        }
        
        Ok(VillagerInput {
            tick_count,
            villagers,
            players,
        })
    }
    
    fn serialize(&self, data: &Self::Output) -> Result<Vec<u8>, BinaryConversionError> {
        let mut out = Vec::new();
        
        // Use current timestamp as tickCount
        let tick_count = SystemTime::now().duration_since(UNIX_EPOCH)?.as_millis() as u64;
        out.write_u64::<LittleEndian>(tick_count)?;
        
        // Villagers to disable AI
        out.write_i32::<LittleEndian>(data.villagers_to_disable_ai.len() as i32)?;
        for id in &data.villagers_to_disable_ai {
            out.write_u64::<LittleEndian>(*id)?;
        }
        
        // Villagers to simplify AI
        out.write_i32::<LittleEndian>(data.villagers_to_simplify_ai.len() as i32)?;
        for id in &data.villagers_to_simplify_ai {
            out.write_u64::<LittleEndian>(*id)?;
        }
        
        // Villagers to reduce pathfinding
        out.write_i32::<LittleEndian>(data.villagers_to_reduce_pathfinding.len() as i32)?;
        for id in &data.villagers_to_reduce_pathfinding {
            out.write_u64::<LittleEndian>(*id)?;
        }
        
        // Villager groups
        out.write_i32::<LittleEndian>(data.villager_groups.len() as i32)?;
        for group in &data.villager_groups {
            out.write_u32::<LittleEndian>(group.group_id)?;
            out.write_f32::<LittleEndian>(group.center_x)?;
            out.write_f32::<LittleEndian>(group.center_y)?;
            out.write_f32::<LittleEndian>(group.center_z)?;
            
            let group_type_len = group.group_type.len() as i32;
            out.write_i32::<LittleEndian>(group_type_len)?;
            out.extend_from_slice(group.group_type.as_bytes());
            
            out.write_u8(group.ai_tick_rate)?;
            
            out.write_i32::<LittleEndian>(group.villager_ids.len() as i32)?;
            for id in &group.villager_ids {
                out.write_u64::<LittleEndian>(*id)?;
            }
        }
        
        Ok(out)
    }
}

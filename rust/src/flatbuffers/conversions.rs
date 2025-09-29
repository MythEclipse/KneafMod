use super::*;
use crate::mob::types::{MobInput, MobProcessResult};
use crate::block::types::{BlockInput, BlockProcessResult};

// Mob conversions
pub fn deserialize_mob_input(data: &[u8]) -> Result<MobInput, String> {
    // For now, return an error as binary protocol is not fully implemented
    Err("Binary protocol not fully implemented".to_string())
}

pub fn serialize_mob_result(result: &MobProcessResult) -> Result<Vec<u8>, String> {
    // For now, return an error as binary protocol is not fully implemented
    Err("Binary protocol not fully implemented".to_string())
}

// Block conversions
pub fn deserialize_block_input(data: &[u8]) -> Result<BlockInput, String> {
    // For now, return an error as binary protocol is not fully implemented
    Err("Binary protocol not fully implemented".to_string())
}

pub fn serialize_block_result(result: &BlockProcessResult) -> Result<Vec<u8>, String> {
    // For now, return an error as binary protocol is not fully implemented
    Err("Binary protocol not fully implemented".to_string())
}

// Placeholder for entity conversions (if needed in the future)
pub fn deserialize_entity_input(data: &[u8]) -> Result<crate::entity::types::Input, String> {
    Err("Binary protocol not fully implemented".to_string())
}

pub fn serialize_entity_result(result: &crate::entity::types::ProcessResult) -> Result<Vec<u8>, String> {
    Err("Binary protocol not fully implemented".to_string())
}

// Placeholder for item conversions (if needed in the future)
pub fn deserialize_item_input(data: &[u8]) -> Result<crate::item::ItemInput, String> {
    Err("Binary protocol not fully implemented".to_string())
}
pub fn serialize_item_result(result: &crate::item::ItemProcessResult) -> Result<Vec<u8>, String> {
    Err("Binary protocol not fully implemented".to_string())
}
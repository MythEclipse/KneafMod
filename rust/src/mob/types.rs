use serde::{Deserialize, Serialize};
use crate::common_entity::types::*;

/// Mob-specific data extending the common EntityData
#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct MobData {
    pub id: u64,
    pub entity_type: EntityType,
    pub position: (f32, f32, f32),
    pub distance: f32,
    pub is_passive: bool,
    // Add any mob-specific fields here
}

/// Mob input extending the common EntityProcessingInput
#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct MobInput {
    pub tick_count: u64,
    pub mobs: Vec<MobData>,
}

/// Mob process result extending the common EntityProcessingResult
#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct MobProcessResult {
    pub mobs_to_disable_ai: Vec<u64>,
    pub mobs_to_simplify_ai: Vec<u64>,
}

/// Convert MobData to EntityData for use with the common processor
impl From<MobData> for EntityData {
    fn from(mob_data: MobData) -> Self {
        EntityData {
            id: mob_data.id,
            entity_type: mob_data.entity_type,
            position: mob_data.position,
            distance: mob_data.distance,
            metadata: Some(serde_json::json!({
                "is_passive": mob_data.is_passive
            })),
        }
    }
}

/// Convert MobInput to EntityProcessingInput for use with the common processor
impl From<MobInput> for EntityProcessingInput {
    fn from(mob_input: MobInput) -> Self {
        EntityProcessingInput {
            tick_count: mob_input.tick_count,
            entities: mob_input.mobs.into_iter().map(|m| m.into()).collect(),
            players: None,
        }
    }
}

/// Convert EntityProcessingResult to MobProcessResult
impl From<EntityProcessingResult> for MobProcessResult {
    fn from(result: EntityProcessingResult) -> Self {
        MobProcessResult {
            mobs_to_disable_ai: result.entities_to_disable_ai,
            mobs_to_simplify_ai: result.entities_to_simplify_ai,
        }
    }
}

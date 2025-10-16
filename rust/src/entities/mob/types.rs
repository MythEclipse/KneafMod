use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use crate::entities::common::types::*;

/// Mob AI complexity levels
#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq)]
pub enum MobAiComplexity {
    Simple,
    Medium,
    Complex,
    Advanced,
}

/// Mob state
#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq)]
pub enum MobState {
    Idle,
    Wandering,
    Attacking,
    Fleeing,
    Breeding,
    Sleeping,
    Eating,
    Swimming,
    Flying,
    Climbing,
}

/// Mob types
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub enum MobType {
    Zombie,
    Skeleton,
    Creeper,
    Spider,
    Enderman,
    Piglin,
    Hoglin,
    Strider,
    Ghast,
    Blaze,
    WitherSkeleton,
    MagmaCube,
    Slime,
    Silverfish,
    Endermite,
    Guardian,
    ElderGuardian,
    Drowned,
    Phantom,
    Ravager,
    Pillager,
    Vindicator,
    Evoker,
    Illusioner,
    Witch,
    Vex,
    Other(String),
}
use crate::EntityProcessingInput;
use crate::EntityProcessingResult;
use crate::EntityType;
use crate::EntityData;

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
            entity_id: mob_data.id.to_string(),
            entity_type: mob_data.entity_type,
            x: mob_data.position.0,
            y: mob_data.position.1,
            z: mob_data.position.2,
            health: 20.0, // Default health
            max_health: 20.0,
            velocity_x: 0.0,
            velocity_y: 0.0,
            velocity_z: 0.0,
            rotation: 0.0,
            pitch: 0.0,
            yaw: 0.0,
            properties: {
                let mut map = std::collections::HashMap::new();
                map.insert("is_passive".to_string(), mob_data.is_passive.to_string());
                map.insert("distance".to_string(), mob_data.distance.to_string());
                map
            },
        }
    }
}

/// Convert MobInput to EntityProcessingInput for use with the common processor
impl From<MobInput> for EntityProcessingInput {
    fn from(mob_input: MobInput) -> Self {
        // For batch processing, we'll create a single input for the first mob
        if let Some(first_mob) = mob_input.mobs.first() {
            EntityProcessingInput {
                entity_id: first_mob.id.to_string(),
                entity_type: first_mob.entity_type,
                data: first_mob.clone().into(),
                delta_time: 0.05, // Default 50ms tick
                simulation_distance: 128,
            }
        } else {
            // Default input if no mobs
            EntityProcessingInput {
                entity_id: "0".to_string(),
                entity_type: EntityType::Mob,
                data: EntityData {
                    entity_id: "0".to_string(),
                    entity_type: EntityType::Mob,
                    x: 0.0,
                    y: 0.0,
                    z: 0.0,
                    health: 20.0,
                    max_health: 20.0,
                    velocity_x: 0.0,
                    velocity_y: 0.0,
                    velocity_z: 0.0,
                    rotation: 0.0,
                    pitch: 0.0,
                    yaw: 0.0,
                    properties: HashMap::new(),
                },
                delta_time: 0.05,
                simulation_distance: 128,
            }
        }
    }
}

/// Convert EntityProcessingResult to MobProcessResult
impl From<EntityProcessingResult> for MobProcessResult {
    fn from(result: EntityProcessingResult) -> Self {
        // Parse entity_id to get mob ID
        let mob_id = result.entity_id.parse::<u64>().unwrap_or(0);
        
        MobProcessResult {
            mobs_to_disable_ai: if result.success && result.metadata_changed.is_some() {
                vec![mob_id]
            } else {
                Vec::new()
            },
            mobs_to_simplify_ai: if !result.success {
                vec![mob_id]
            } else {
                Vec::new()
            },
        }
    }
}

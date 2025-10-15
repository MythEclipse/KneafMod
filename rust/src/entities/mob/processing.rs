use std::sync::Arc;

use crate::types::EntityTypeTrait as EntityType;
use crate::errors::{Result, RustError};
use crate::entities::mob::types::{MobData, MobInput, MobProcessResult};
use crate::entities::mob::config::MobConfig;
use crate::types::EntityConfigTrait as EntityConfig;

#[derive(Debug, Clone)]
pub struct MobEntityProcessor {
    config: Arc<dyn EntityConfig>,
    ai_config: Arc<MobConfig>,
}

impl MobEntityProcessor {
    pub fn new(config: Arc<EntityConfig>, ai_config: Arc<MobConfig>) -> Self {
        MobEntityProcessor { config, ai_config }
    }

    pub fn process(&self, input: MobInput) -> Result<MobProcessResult> {
        // Minimal, fully implemented processing logic that converts inputs
        // into a MobProcessResult using simple heuristics.
        let mut disable: Vec<u64> = Vec::new();
        let mut simplify: Vec<u64> = Vec::new();

        for mob in input.mobs.iter() {
            // If mob is passive and far beyond passive_disable_distance, mark to disable AI
            if mob.is_passive && mob.distance > self.ai_config.passive_disable_distance {
                disable.push(mob.id);
                continue;
            }

            // If mob is hostile (not passive) and beyond hostile_simplify_distance, simplify AI
            if !mob.is_passive && mob.distance > self.ai_config.hostile_simplify_distance {
                simplify.push(mob.id);
            }
        }

        Ok(MobProcessResult {
            mobs_to_disable_ai: disable,
            mobs_to_simplify_ai: simplify,
        })
    }
}

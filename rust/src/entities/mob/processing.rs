use std::sync::Arc;

use crate::types::EntityTypeTrait as EntityType;
use crate::errors::{Result, RustError};
use crate::entities::mob::types::{MobData, MobInput, MobProcessResult};
use crate::entities::mob::config::MobConfig;
use crate::types::EntityConfig;
use crate::types::DefaultEntityConfig;
use crate::binary::conversions::{deserialize_mob_input, serialize_mob_result};

#[derive(Debug, Clone)]
pub struct MobEntityProcessor {
    config: Arc<dyn EntityConfig>,
    ai_config: Arc<MobConfig>,
}

impl MobEntityProcessor {
    pub fn new(config: Arc<dyn EntityConfig>, ai_config: Arc<MobConfig>) -> Self {
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
        
        /// Process mob entities from binary input in batches
        pub fn process_mob_ai_binary_batch(data: &[u8]) -> Result<Vec<u8>> {
            if data.is_empty() {
                return Ok(Vec::new());
            }
        
            let input = deserialize_mob_input(data).map_err(|e| RustError::DeserializationError(e.to_string()))?;
            let result = process_mob_ai(input)?;
            let out = serialize_mob_result(&result).map_err(|e| RustError::SerializationError(e.to_string()))?;
            Ok(out)
        }

        Ok(MobProcessResult {
            mobs_to_disable_ai: disable,
            mobs_to_simplify_ai: simplify,
        })
    }
}

pub fn process_mob_ai_json(input: &str) -> Result<String> {
    let input: MobInput = serde_json::from_str(input)?;
    let config = Arc::new(MobConfig::default());
    let processor = MobEntityProcessor::new(Arc::new(DefaultEntityConfig::default()), config);
    let result = processor.process(input)?;
    serde_json::to_string(&result).map_err(|e| RustError::SerializationError(e.to_string()))
}

pub fn process_mob_ai(input: MobInput) -> Result<MobProcessResult> {
    let config = Arc::new(MobConfig::default());
    let processor = MobEntityProcessor::new(Arc::new(DefaultEntityConfig::default()), config);
    processor.process(input)
}

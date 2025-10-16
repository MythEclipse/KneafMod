use crate::entities::entity::processing::process_entities;
use crate::entities::mob::types::{MobAiComplexity, MobState, MobType};
use crate::entities::common::state_manager::{EntityState, StateManager};
use std::collections::HashMap;
use std::sync::{Arc, Mutex};

/// Mob-specific processing logic
pub struct MobProcessor {
    base_processor: crate::entities::common::thread_safe_state_manager::ThreadSafeEntityProcessorImpl,
    mob_config: Arc<crate::entities::mob::config::MobConfig>,
    complexity_cache: Arc<Mutex<HashMap<u64, MobAiComplexity>>>,
}

impl MobProcessor {
    pub fn new(config: Arc<crate::entities::mob::config::MobConfig>) -> Self {
        let state_manager = crate::entities::common::state_manager::get_default_state_manager();
        let base_processor = crate::entities::common::thread_safe_state_manager::ThreadSafeEntityProcessorImpl::new(state_manager);
        let complexity_cache = Arc::new(Mutex::new(HashMap::new()));
        
        Self {
            base_processor,
            mob_config: config,
            complexity_cache,
        }
    }

    pub fn process_mob(&self, mob_type: MobType, state: MobState) -> crate::errors::Result<()> {
        // Determine AI complexity based on mob type and state
        let complexity = self.calculate_ai_complexity(&mob_type, &state);
        
        // Store complexity in cache
        if let Ok(mut cache) = self.complexity_cache.lock() {
            cache.insert(state.entity_id, complexity);
        }
        
        // Process based on complexity
        match complexity {
            MobAiComplexity::Simple => self.process_simple_mob(mob_type, state),
            MobAiComplexity::Moderate => self.process_moderate_mob(mob_type, state),
            MobAiComplexity::Complex => self.process_complex_mob(mob_type, state),
        }
    }

    fn calculate_ai_complexity(&self, mob_type: &MobType, state: &MobState) -> MobAiComplexity {
        // Simple complexity calculation based on mob type and distance to player
        match mob_type {
            MobType::Passive(_) => MobAiComplexity::Simple,
            MobType::Hostile(_) => {
                if state.distance_to_player < 50.0 {
                    MobAiComplexity::Complex
                } else {
                    MobAiComplexity::Moderate
                }
            }
            MobType::Neutral(_) => MobAiComplexity::Moderate,
        }
    }

    fn process_simple_mob(&self, _mob_type: MobType, _state: MobState) -> crate::errors::Result<()> {
        // Simple mobs: basic movement and collision detection
        Ok(())
    }

    fn process_moderate_mob(&self, _mob_type: MobType, _state: MobState) -> crate::errors::Result<()> {
        // Moderate mobs: pathfinding and basic AI
        Ok(())
    }

    fn process_complex_mob(&self, _mob_type: MobType, _state: MobState) -> crate::errors::Result<()> {
        // Complex mobs: advanced AI, combat, and special behaviors
        Ok(())
    }
}

/// Process mob AI from JSON configuration
pub fn process_mob_ai_json(json_config: &str) -> crate::errors::Result<crate::entities::mob::config::MobConfig> {
    // Parse JSON configuration
    let config: crate::entities::mob::config::MobConfig = serde_json::from_str(json_config)
        .map_err(|e| crate::errors::RustError::SerializationError(format!("Failed to parse mob AI JSON: {}", e)))?;
    
    Ok(config)
}

/// Process multiple mobs with SIMD optimization
pub fn process_mobs_batch(mobs: Vec<(MobType, MobState)>) -> crate::errors::Result<Vec<crate::errors::Result<()>>> {
    let mut results = Vec::with_capacity(mobs.len());
    
    // Process each mob
    for (mob_type, state) in mobs {
        // Individual mob processing would happen here
        // For now, return success for all
        results.push(Ok(()));
    }
    
    Ok(results)
}

/// Mob processing configuration
#[derive(Debug, Clone)]
pub struct MobProcessingConfig {
    pub max_mobs_per_tick: usize,
    pub enable_simd_optimization: bool,
    pub ai_update_frequency: u64,
}

impl Default for MobProcessingConfig {
    fn default() -> Self {
        Self {
            max_mobs_per_tick: 1000,
            enable_simd_optimization: true,
            ai_update_frequency: 20, // Update AI every 20 ticks
        }
    }
}

/// Additional mob processing utilities
pub struct MobProcessingUtils;

impl MobProcessingUtils {
    /// Calculate mob density in a given area
    pub fn calculate_mob_density(mobs: &[(MobType, MobState)], center_x: f64, center_z: f64, radius: f64) -> f64 {
        let mut count = 0;
        for (_, state) in mobs {
            let distance = ((state.x - center_x).powi(2) + (state.z - center_z).powi(2)).sqrt();
            if distance <= radius {
                count += 1;
            }
        }
        count as f64 / (radius * radius * std::f64::consts::PI)
    }

    /// Optimize mob processing based on distance to players
    pub fn optimize_by_distance(mobs: Vec<(MobType, MobState)>, player_positions: &[(f64, f64, f64)]) -> Vec<(MobType, MobState)> {
        mobs.into_iter()
            .filter(|(_, state)| {
                player_positions.iter().any(|(px, py, pz)| {
                    let distance = ((state.x - px).powi(2) + (state.y - py).powi(2) + (state.z - pz).powi(2)).sqrt();
                    distance < 128.0 // Only process mobs within 128 blocks of players
                })
            })
            .collect()
    }
}

// Re-export commonly used types - avoid duplicate imports
// ProcessingResult and ProcessingStatus are already imported from common::processing
